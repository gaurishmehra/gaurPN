package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

type Config struct {
	BindAddr  string
	TokenFile string
	TokenEnv  string
}

type Peer struct {
	ID       string          `json:"id"`
	Name     string          `json:"name"`
	conn     *websocket.Conn `json:"-"`
	writeMu  sync.Mutex      `json:"-"`
	joinedAt time.Time       `json:"joined_at"`
}

func (p *Peer) WriteMessage(messageType int, data []byte) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	return p.conn.WriteMessage(messageType, data)
}

func (p *Peer) WriteJSON(v interface{}) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	return p.conn.WriteJSON(v)
}

type Hub struct {
	mu       sync.RWMutex
	peers    map[string]*Peer
	token    []byte
	upgrader websocket.Upgrader
	pipeMu   sync.Mutex
	pipes    map[string]*PipeSession
}

type PipeSession struct {
	transferID        string
	pr                *io.PipeReader
	pw                *io.PipeWriter
	receiverReady     chan struct{}
	senderReady       chan struct{}
	done              chan struct{}
	closeRecvOnce     sync.Once
	closeSenderOnce   sync.Once
	closeDoneOnce     sync.Once
	created           time.Time
	activeConnections int32
}

func newPipeSession(transferID string) *PipeSession {
	pr, pw := io.Pipe()
	return &PipeSession{
		transferID:    transferID,
		pr:            pr,
		pw:            pw,
		receiverReady: make(chan struct{}),
		senderReady:   make(chan struct{}),
		done:          make(chan struct{}),
		created:       time.Now(),
	}
}

func (s *PipeSession) markReceiverReady() {
	s.closeRecvOnce.Do(func() {
		close(s.receiverReady)
	})
}

func (s *PipeSession) markSenderReady() {
	s.closeSenderOnce.Do(func() {
		close(s.senderReady)
	})
}

func (s *PipeSession) markDone() {
	s.closeDoneOnce.Do(func() {
		close(s.done)
	})
}

type flushWriter struct {
	w       io.Writer
	flusher http.Flusher
}

func (fw *flushWriter) Write(p []byte) (n int, err error) {
	n, err = fw.w.Write(p)
	if fw.flusher != nil && n > 0 {
		fw.flusher.Flush()
	}
	return
}

func NewHub(token []byte) *Hub {
	h := &Hub{
		peers: make(map[string]*Peer),
		token: token,
		pipes: make(map[string]*PipeSession),
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool {
				return true // loopback / tunnel only
			},
		},
	}
	go func() {
		ticker := time.NewTicker(60 * time.Second)
		for range ticker.C {
			h.pipeMu.Lock()
			now := time.Now()
			for id, session := range h.pipes {
				if now.Sub(session.created) > 3*time.Minute {
					_ = session.pr.Close()
					_ = session.pw.Close()
					delete(h.pipes, id)
					log.Printf("[PIPE] Evicted expired pipe session %s", id)
				}
			}
			h.pipeMu.Unlock()
		}
	}()
	return h
}

func (h *Hub) authenticate(token string) bool {
	if len(h.token) == 0 {
		return false
	}
	if subtle.ConstantTimeCompare([]byte(token), h.token) == 1 {
		return true
	}
	// Check derived relay auth token if h.token is the master cluster token
	mac := hmac.New(sha256.New, h.token)
	mac.Write([]byte("gaur-relay-auth-v1"))
	derived := hex.EncodeToString(mac.Sum(nil))
	return subtle.ConstantTimeCompare([]byte(token), []byte(derived)) == 1
}

func (h *Hub) broadcastPeerList() {
	h.mu.RLock()
	defer h.mu.RUnlock()

	type PeerInfo struct {
		ID   string `json:"id"`
		Name string `json:"name"`
	}

	list := make([]PeerInfo, 0, len(h.peers))
	for _, p := range h.peers {
		list = append(list, PeerInfo{ID: p.ID, Name: p.Name})
	}

	payload, err := json.Marshal(map[string]interface{}{
		"type":  "peers",
		"peers": list,
	})
	if err != nil {
		return
	}

	for _, p := range h.peers {
		_ = p.WriteMessage(websocket.TextMessage, payload)
	}
}

func (h *Hub) handleWS(w http.ResponseWriter, r *http.Request) {
	deviceID := strings.TrimSpace(r.URL.Query().Get("device_id"))
	deviceName := strings.TrimSpace(r.URL.Query().Get("name"))
	token := strings.TrimSpace(r.URL.Query().Get("token"))
	if token == "" {
		token = strings.TrimSpace(r.Header.Get("X-Gaur-Token"))
	}

	if deviceID == "" || !h.authenticate(token) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}

	if deviceName == "" {
		deviceName = deviceID
	}

	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[WS] Upgrade failed: %v", err)
		return
	}

	peer := &Peer{
		ID:       deviceID,
		Name:     deviceName,
		conn:     conn,
		joinedAt: time.Now(),
	}

	h.mu.Lock()
	if old, exists := h.peers[deviceID]; exists {
		_ = old.conn.Close()
	}
	h.peers[deviceID] = peer
	h.mu.Unlock()

	log.Printf("[HUB] Device joined: %s (%s)", deviceName, deviceID)
	h.broadcastPeerList()

	defer func() {
		h.mu.Lock()
		if curr, ok := h.peers[deviceID]; ok && curr == peer {
			delete(h.peers, deviceID)
		}
		h.mu.Unlock()
		_ = conn.Close()
		log.Printf("[HUB] Device left: %s (%s)", deviceName, deviceID)
		h.broadcastPeerList()
	}()

	for {
		_, message, err := conn.ReadMessage()
		if err != nil {
			break
		}

		var envelope map[string]interface{}
		if err := json.Unmarshal(message, &envelope); err != nil {
			continue
		}

		msgType, _ := envelope["type"].(string)
		if msgType == "ping" {
			_ = peer.WriteJSON(map[string]string{"type": "pong"})
			continue
		}

		targetID, _ := envelope["target_id"].(string)
		if targetID == "" {
			continue
		}

		// Ensure sender info is accurate
		envelope["from_id"] = deviceID
		envelope["from_name"] = deviceName

		h.mu.RLock()
		targetPeer, exists := h.peers[targetID]
		h.mu.RUnlock()

		if exists && targetPeer != nil {
			forwardBytes, _ := json.Marshal(envelope)
			_ = targetPeer.WriteMessage(websocket.TextMessage, forwardBytes)
		}
	}
}

func (h *Hub) handlePipe(w http.ResponseWriter, r *http.Request) {
	transferID := strings.TrimSpace(r.URL.Query().Get("transfer_id"))
	role := strings.TrimSpace(r.URL.Query().Get("role")) // "sender" or "receiver"
	token := strings.TrimSpace(r.URL.Query().Get("token"))
	if token == "" {
		token = strings.TrimSpace(r.Header.Get("X-Gaur-Token"))
	}

	if transferID == "" || (role != "sender" && role != "receiver") || !h.authenticate(token) {
		http.Error(w, "Unauthorized or invalid params", http.StatusUnauthorized)
		return
	}

	h.pipeMu.Lock()
	session, exists := h.pipes[transferID]
	if !exists {
		session = newPipeSession(transferID)
		h.pipes[transferID] = session
	}
	atomic.AddInt32(&session.activeConnections, 1)
	h.pipeMu.Unlock()

	defer func() {
		if atomic.AddInt32(&session.activeConnections, -1) <= 0 {
			h.pipeMu.Lock()
			if cur, ok := h.pipes[transferID]; ok && cur == session {
				delete(h.pipes, transferID)
			}
			h.pipeMu.Unlock()
			_ = session.pr.Close()
			_ = session.pw.Close()
		}
	}()

	if role == "receiver" {
		session.markReceiverReady()

		// Wait for sender to arrive
		select {
		case <-session.senderReady:
		case <-r.Context().Done():
			_ = session.pw.CloseWithError(r.Context().Err())
			return
		case <-time.After(60 * time.Second):
			http.Error(w, "Sender connection timeout", http.StatusGatewayTimeout)
			_ = session.pw.CloseWithError(fmt.Errorf("sender timed out"))
			return
		}

		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.WriteHeader(http.StatusOK)

		flusher, isFlusher := w.(http.Flusher)
		if isFlusher {
			flusher.Flush()
		}

		fw := &flushWriter{w: w, flusher: flusher}
		buf := make([]byte, 64*1024)
		_, err := io.CopyBuffer(fw, session.pr, buf)
		if isFlusher {
			flusher.Flush()
		}
		session.markDone()

		if err != nil && err != io.EOF {
			log.Printf("[PIPE] Error streaming to receiver for %s: %v", transferID, err)
		} else {
			log.Printf("[PIPE] Successfully streamed transfer %s to receiver", transferID)
		}
		return
	}

	if role == "sender" {
		session.markSenderReady()

		// Wait for receiver to arrive
		select {
		case <-session.receiverReady:
		case <-r.Context().Done():
			_ = session.pr.CloseWithError(r.Context().Err())
			return
		case <-time.After(60 * time.Second):
			http.Error(w, "Receiver connection timeout", http.StatusGatewayTimeout)
			_ = session.pr.CloseWithError(fmt.Errorf("receiver timed out"))
			return
		}

		buf := make([]byte, 64*1024)
		_, err := io.CopyBuffer(session.pw, r.Body, buf)
		_ = session.pw.Close()

		if err != nil && err != io.EOF {
			log.Printf("[PIPE] Error reading sender body for %s: %v", transferID, err)
			http.Error(w, "Pipe read failed", http.StatusInternalServerError)
			return
		}

		// Wait for receiver to finish consuming the stream
		select {
		case <-session.done:
		case <-r.Context().Done():
		case <-time.After(60 * time.Second):
		}

		w.WriteHeader(http.StatusOK)
		log.Printf("[PIPE] Sender completed upload for %s", transferID)
		return
	}
}

func loadToken(cfg Config) ([]byte, error) {
	if cfg.TokenFile != "" {
		data, err := os.ReadFile(cfg.TokenFile)
		if err == nil {
			trimmed := strings.TrimSpace(string(data))
			if len(trimmed) > 0 {
				return []byte(trimmed), nil
			}
		}
	}
	if cfg.TokenEnv != "" {
		val := strings.TrimSpace(os.Getenv(cfg.TokenEnv))
		if len(val) > 0 {
			return []byte(val), nil
		}
	}
	return nil, fmt.Errorf("no valid cluster token found in file (%s) or env (%s)", cfg.TokenFile, cfg.TokenEnv)
}

func main() {
	var cfg Config
	flag.StringVar(&cfg.BindAddr, "bind", "127.0.0.1:9876", "Internal address and port to bind to (loopback only)")
	flag.StringVar(&cfg.TokenFile, "token-file", "/etc/gaur-relay/cluster.key", "Path to cluster secret token file")
	flag.StringVar(&cfg.TokenEnv, "token-env", "GAURPN_CLUSTER_TOKEN", "Environment variable for token fallback")
	flag.Parse()

	token, err := loadToken(cfg)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}

	hub := NewHub(token)

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", hub.handleWS)
	mux.HandleFunc("/pipe", hub.handlePipe)
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"status": "healthy",
			"peers":  len(hub.peers),
		})
	})

	log.Printf("[START] gaur-relay listening on %s (loopback only, zero public exposure)", cfg.BindAddr)
	server := &http.Server{
		Addr:              cfg.BindAddr,
		Handler:           mux,
		ReadHeaderTimeout: 30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	if err := server.ListenAndServe(); err != nil {
		log.Fatalf("[FATAL] Server terminated: %v", err)
	}
}
