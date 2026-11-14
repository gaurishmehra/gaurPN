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
	"path/filepath"
	"strconv"
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
	joinedAt time.Time
}

type PeerInfo struct {
	ID   string `json:"id"`
	Name string `json:"name"`
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
	mu        sync.RWMutex
	peers     map[string]*Peer
	token     []byte
	upgrader  websocket.Upgrader
	pipeMu    sync.Mutex
	pipes     map[string]*PipeSession
	chunks    *ChunkStore
	mailbox   *Mailbox
	done      chan struct{}
	closeOnce sync.Once
}

type PipeSession struct {
	transferID        string
	pipe              *bufferedPipe
	receiverReady     chan struct{}
	senderReady       chan struct{}
	done              chan struct{}
	closeRecvOnce     sync.Once
	closeSenderOnce   sync.Once
	closeDoneOnce     sync.Once
	activeConnections int32
}

func newPipeSession(transferID string) *PipeSession {
	return &PipeSession{
		transferID:    transferID,
		pipe:          newBufferedPipe(2 << 20), // 2 MiB of decoupling per transfer
		receiverReady: make(chan struct{}),
		senderReady:   make(chan struct{}),
		done:          make(chan struct{}),
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
	w         io.Writer
	flusher   http.Flusher
	lastFlush time.Time
	interval  time.Duration
}

// Write forwards the chunk and flushes at most once per interval. Flushing on
// every 64 KiB chunk forced a syscall and a chunk boundary per write; a short
// interval keeps progress responsive without that per-chunk cost.
func (fw *flushWriter) Write(p []byte) (n int, err error) {
	n, err = fw.w.Write(p)
	if fw.flusher != nil && n > 0 {
		now := time.Now()
		if fw.interval <= 0 || now.Sub(fw.lastFlush) >= fw.interval {
			fw.flusher.Flush()
			fw.lastFlush = now
		}
	}
	return
}

func NewHub(token []byte, chunks *ChunkStore, mailboxes ...*Mailbox) *Hub {
	if chunks == nil {
		chunks = NewChunkStore("", 0, 0)
	}
	mailbox := NewMailbox(time.Hour, 16<<20)
	if len(mailboxes) > 0 && mailboxes[0] != nil {
		mailbox = mailboxes[0]
	}
	h := &Hub{
		peers:   make(map[string]*Peer),
		token:   token,
		pipes:   make(map[string]*PipeSession),
		chunks:  chunks,
		mailbox: mailbox,
		done:    make(chan struct{}),
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool {
				return true // loopback / tunnel only
			},
		},
	}
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				h.pipeMu.Lock()
				now := time.Now()
				for id, session := range h.pipes {
					// Evict on idleness, never on age. A large file on a slow
					// link must not be killed just because it takes a while.
					if now.Sub(session.pipe.LastActivity()) > 5*time.Minute {
						_ = session.pipe.CloseWithError(fmt.Errorf("pipe session idle timeout"))
						delete(h.pipes, id)
						log.Printf("[PIPE] Evicted idle pipe session %s", id)
					}
				}
				h.pipeMu.Unlock()
			case <-h.done:
				return
			}
		}
	}()
	return h
}

// Close stops the eviction goroutines. Safe to call more than once.
func (h *Hub) Close() {
	h.closeOnce.Do(func() {
		close(h.done)
		if h.chunks != nil {
			h.chunks.Close()
		}
	})
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
	list, peers := h.peersSnapshot()
	payload, err := json.Marshal(map[string]interface{}{"type": "peers", "peers": list})
	if err != nil {
		return
	}

	for _, p := range peers {
		_ = p.WriteMessage(websocket.TextMessage, payload)
	}
}

func (h *Hub) sendPeerList(peer *Peer) {
	list, _ := h.peersSnapshot()
	payload, err := json.Marshal(map[string]interface{}{"type": "peers", "peers": list})
	if err != nil {
		return
	}
	_ = peer.WriteMessage(websocket.TextMessage, payload)
}

func (h *Hub) peersSnapshot() ([]PeerInfo, []*Peer) {
	h.mu.RLock()
	defer h.mu.RUnlock()

	list := make([]PeerInfo, 0, len(h.peers))
	peers := make([]*Peer, 0, len(h.peers))
	for _, peer := range h.peers {
		list = append(list, PeerInfo{ID: peer.ID, Name: peer.Name})
		peers = append(peers, peer)
	}
	return list, peers
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
	// Signaling messages are small (offers, accepts, text, clipboard). Chunk
	// bodies travel over HTTP, so a 1 MiB frame cap is generous and prevents a
	// token holder from forcing the relay to buffer arbitrary payloads.
	conn.SetReadLimit(1 << 20)

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

	// Deliver sealed messages that arrived while this device was offline.
	for _, pending := range h.mailbox.Take(deviceID) {
		_ = peer.WriteMessage(websocket.TextMessage, pending)
	}

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
		if msgType == "list_peers" {
			h.sendPeerList(peer)
			continue
		}

		// Sealed envelopes are opaque to the relay. They carry only a target,
		// a message id, a timestamp, and a kind. If the target is offline the
		// envelope is queued in the mailbox; no sender identity is attached.
		if msgType == "sealed" {
			targetID, _ := envelope["target_id"].(string)
			if targetID == "" {
				continue
			}
			envelope["relay_ts"] = time.Now().UnixMilli()
			forwardBytes, _ := json.Marshal(envelope)
			h.mu.RLock()
			targetPeer, exists := h.peers[targetID]
			h.mu.RUnlock()
			if exists && targetPeer != nil {
				_ = targetPeer.WriteMessage(websocket.TextMessage, forwardBytes)
			} else {
				h.mailbox.Put(targetID, forwardBytes)
			}
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
			// Server receive time gives receivers a common ordering clock for
			// last-write-wins clipboard updates, independent of device clocks.
			envelope["relay_ts"] = time.Now().UnixMilli()
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
			_ = session.pipe.Close()
		}
	}()

	if role == "receiver" {
		session.markReceiverReady()

		// Wait for sender to arrive
		select {
		case <-session.senderReady:
		case <-r.Context().Done():
			_ = session.pipe.CloseWithError(r.Context().Err())
			return
		case <-time.After(60 * time.Second):
			http.Error(w, "Sender connection timeout", http.StatusGatewayTimeout)
			_ = session.pipe.CloseWithError(fmt.Errorf("sender timed out"))
			return
		}

		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.WriteHeader(http.StatusOK)

		flusher, isFlusher := w.(http.Flusher)
		if isFlusher {
			flusher.Flush()
		}

		fw := &flushWriter{w: w, flusher: flusher, interval: 100 * time.Millisecond}
		buf := make([]byte, 256*1024)
		_, err := io.CopyBuffer(fw, session.pipe, buf)
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
			_ = session.pipe.CloseWithError(r.Context().Err())
			return
		case <-time.After(60 * time.Second):
			http.Error(w, "Receiver connection timeout", http.StatusGatewayTimeout)
			_ = session.pipe.CloseWithError(fmt.Errorf("receiver timed out"))
			return
		}

		buf := make([]byte, 256*1024)
		_, err := io.CopyBuffer(session.pipe, r.Body, buf)
		_ = session.pipe.Close()

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

func requestToken(r *http.Request) string {
	token := strings.TrimSpace(r.Header.Get("X-Gaur-Token"))
	if token == "" {
		token = strings.TrimSpace(r.URL.Query().Get("token"))
	}
	return token
}

// transferKey parses the shared chunk parameters. index is -1 when the caller
// did not supply one (status and delete endpoints do not need it).
func (h *Hub) transferKey(r *http.Request) (key string, index int, ok bool) {
	transferID := strings.TrimSpace(r.URL.Query().Get("transfer_id"))
	sessionID := strings.TrimSpace(r.URL.Query().Get("session_id"))
	if transferID == "" || sessionID == "" {
		return "", 0, false
	}
	index = -1
	if raw := strings.TrimSpace(r.URL.Query().Get("index")); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 0 || parsed >= maxChunksPerTransfer {
			return "", 0, false
		}
		index = parsed
	}
	return transferID + "/" + sessionID, index, true
}

func (h *Hub) handleChunkPut(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !h.authenticate(requestToken(r)) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	key, index, ok := h.transferKey(r)
	if !ok || index < 0 {
		http.Error(w, "Invalid transfer_id, session_id, or index", http.StatusBadRequest)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxChunkSize+1))
	if err != nil {
		http.Error(w, "Read failed", http.StatusBadRequest)
		return
	}
	if err := h.chunks.Put(key, index, body); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "index": index})
}

func (h *Hub) handleChunkGet(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !h.authenticate(requestToken(r)) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	key, index, ok := h.transferKey(r)
	if !ok || index < 0 {
		http.Error(w, "Invalid transfer_id, session_id, or index", http.StatusBadRequest)
		return
	}
	data, found := h.chunks.Get(key, index)
	if !found {
		http.Error(w, "Chunk not found", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return
	}
	_, _ = w.Write(data)
}

func (h *Hub) handleChunkStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !h.authenticate(requestToken(r)) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	key, _, ok := h.transferKey(r)
	if !ok {
		http.Error(w, "Invalid transfer_id or session_id", http.StatusBadRequest)
		return
	}
	count, err := strconv.Atoi(strings.TrimSpace(r.URL.Query().Get("count")))
	if err != nil || count < 0 || count > maxChunksPerTransfer {
		http.Error(w, "Invalid count", http.StatusBadRequest)
		return
	}
	present := h.chunks.Present(key, count)
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{"present": present})
}

func (h *Hub) handleTransferDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !h.authenticate(requestToken(r)) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	key, _, ok := h.transferKey(r)
	if !ok {
		http.Error(w, "Invalid transfer_id or session_id", http.StatusBadRequest)
		return
	}
	h.chunks.Delete(key)
	w.WriteHeader(http.StatusNoContent)
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
	chunkDir := flag.String("chunk-dir", filepath.Join(os.TempDir(), "gaur-relay-chunks"), "Directory for resumable chunk staging (empty = memory only)")
	chunkMax := flag.Int64("chunk-max-bytes", 8<<30, "Maximum bytes of staged chunks across all transfers")
	chunkTTL := flag.Duration("chunk-ttl", 24*time.Hour, "Idle time before a staged transfer is evicted")
	mailboxTTL := flag.Duration("mailbox-ttl", time.Hour, "How long sealed messages for offline peers are held")
	mailboxMax := flag.Int64("mailbox-max-bytes", 16<<20, "Maximum bytes of queued sealed messages")
	initMode := flag.Bool("init", false, "Generate a cluster token, write it, print a pairing QR, and exit")
	qrMode := flag.Bool("qr", false, "Print a pairing QR for the existing token and exit")
	initForce := flag.Bool("force", false, "Overwrite an existing token file with -init")
	initRelay := flag.String("pair-relay", defaultPairRelay, "Relay address advertised in the pairing QR")
	initDNS := flag.String("pair-dns", defaultPairDNS, "DNS server advertised in the pairing QR")
	initName := flag.String("pair-name", "", "Optional device name advertised in the pairing QR")
	flag.Parse()

	if *initMode {
		if err := runInit(cfg.TokenFile, *initRelay, *initDNS, *initName, *initForce); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		return
	}
	if *qrMode {
		if err := runQR(cfg.TokenFile, *initRelay, *initDNS, *initName); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		return
	}

	token, err := loadToken(cfg)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}

	chunks := NewChunkStore(*chunkDir, *chunkMax, *chunkTTL)
	mailbox := NewMailbox(*mailboxTTL, *mailboxMax)
	hub := NewHub(token, chunks, mailbox)
	defer hub.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", hub.handleWS)
	mux.HandleFunc("/pipe", hub.handlePipe)
	mux.HandleFunc("/chunk", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodPut:
			hub.handleChunkPut(w, r)
		case http.MethodGet, http.MethodHead:
			hub.handleChunkGet(w, r)
		default:
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/chunk/status", hub.handleChunkStatus)
	mux.HandleFunc("/transfer", hub.handleTransferDelete)
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		transfers, bytes := chunks.Stats()
		mailboxPeers, mailboxBytes := hub.mailbox.Stats()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"status":          "healthy",
			"peers":           len(hub.peers),
			"chunk_transfers": transfers,
			"chunk_bytes":     bytes,
			"mailbox_peers":   mailboxPeers,
			"mailbox_bytes":   mailboxBytes,
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
