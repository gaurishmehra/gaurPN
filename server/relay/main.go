package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

const (
	// defaultMaxPeers bounds how many distinct devices may join the cluster.
	defaultMaxPeers = 32
	// maxDeviceNameBytes bounds the UTF-8 size of a peer's display name.
	maxDeviceNameBytes = 128
	// authFailureLimit is how many failed auth attempts one IP may make per
	// window before further attempts are throttled.
	authFailureLimit = 10
	// authFailureWindow is the fixed-window length for the failure counter.
	authFailureWindow = time.Minute
)

// WebSocket liveness windows. The relay pings every client each
// wsPingInterval; a client that neither sends traffic nor answers pings
// within wsReadWindow is dropped instead of lingering forever as a stale
// "online" peer. Every write to a client is bounded by wsWriteTimeout so
// a client that connected but stopped reading can never block its
// correspondents. Package vars so tests can shrink them; each connection
// copies the current values at join time (see Peer).
var (
	wsPingInterval = 30 * time.Second
	wsReadWindow   = 75 * time.Second
	wsWriteTimeout = 15 * time.Second
)

// validDeviceID bounds the join-time device id: opaque, URL-safe, short.
var validDeviceID = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

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

	// Liveness settings copied from the ws* package vars when the peer
	// joins. Capturing them per connection means goroutines serving
	// older connections never read the vars again, so tests can shrink
	// the windows between connections without tripping the race
	// detector.
	pingInterval time.Duration
	readWindow   time.Duration
	writeTimeout time.Duration
}

type PeerInfo struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// writeMessage sends one frame under the write mutex with a bounded write
// deadline, so a stalled client can never block a writer (or hold
// writeMu) indefinitely. Every relay write to a peer goes through this
// helper or writeJSON.
func (p *Peer) writeMessage(messageType int, data []byte) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	_ = p.conn.SetWriteDeadline(time.Now().Add(p.writeTimeout))
	return p.conn.WriteMessage(messageType, data)
}

// writeJSON is writeMessage for JSON envelopes.
func (p *Peer) writeJSON(v interface{}) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	_ = p.conn.SetWriteDeadline(time.Now().Add(p.writeTimeout))
	return p.conn.WriteJSON(v)
}

// runPingLoop probes the client with WebSocket pings until stop closes.
// The app-level ping is client-initiated, so it proves the relay is
// reachable but never the reverse. A ping that cannot be written within
// the write timeout means the client is gone or wedged: closing the conn
// unblocks the read loop, whose deferred cleanup removes the peer and
// broadcasts the leave.
func (p *Peer) runPingLoop(stop <-chan struct{}) {
	ticker := time.NewTicker(p.pingInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			p.writeMu.Lock()
			deadline := time.Now().Add(p.writeTimeout)
			_ = p.conn.SetWriteDeadline(deadline)
			err := p.conn.WriteControl(websocket.PingMessage, nil, deadline)
			p.writeMu.Unlock()
			if err != nil {
				_ = p.conn.Close()
				return
			}
		case <-stop:
			return
		}
	}
}

type Hub struct {
	mu        sync.RWMutex
	peers     map[string]*Peer
	token     []byte
	peerKey   []byte
	maxPeers  int
	limiter   *authLimiter
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
	// senderActive and receiverActive are guarded by Hub.pipeMu. They give
	// each transfer exactly one live sender and one live receiver so a second
	// connection cannot interleave-corrupt the stream. A slot frees when its
	// connection closes (see the deferred cleanup in handlePipe).
	senderActive   bool
	receiverActive bool
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

// claimRoleLocked reserves the sender or receiver slot. Callers must hold
// Hub.pipeMu. It returns false when the role is already held by a live
// connection.
func (s *PipeSession) claimRoleLocked(role string) bool {
	switch role {
	case "sender":
		if s.senderActive {
			return false
		}
		s.senderActive = true
	case "receiver":
		if s.receiverActive {
			return false
		}
		s.receiverActive = true
	}
	return true
}

// releaseRoleLocked frees the role slot so a future connection can take it.
// Callers must hold Hub.pipeMu.
func (s *PipeSession) releaseRoleLocked(role string) {
	switch role {
	case "sender":
		s.senderActive = false
	case "receiver":
		s.receiverActive = false
	}
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
		// Memory-only fallback. An empty dir performs no disk setup, so
		// this construction cannot fail.
		chunks, _ = NewChunkStore("", 0, 0)
	}
	mailbox := NewMailbox(defaultMailboxTTL, defaultMailboxMaxBytes, defaultMailboxMaxTargetBytes)
	if len(mailboxes) > 0 && mailboxes[0] != nil {
		mailbox = mailboxes[0]
	}
	// K_peer is the cluster-wide peer key every member derives locally from
	// the master token: HMAC-SHA256(master, "gaur-peer-auth-v1"). It is never
	// sent over the wire; the relay only uses it to verify per-transfer
	// capability tags.
	peerMAC := hmac.New(sha256.New, token)
	peerMAC.Write([]byte("gaur-peer-auth-v1"))
	h := &Hub{
		peers:    make(map[string]*Peer),
		token:    token,
		peerKey:  peerMAC.Sum(nil),
		maxPeers: defaultMaxPeers,
		limiter:  newAuthLimiter(authFailureLimit, authFailureWindow),
		pipes:    make(map[string]*PipeSession),
		chunks:   chunks,
		mailbox:  mailbox,
		done:     make(chan struct{}),
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool {
				return true // loopback / tunnel only
			},
		},
	}
	// The mailbox prunes expired entries in the background; lazy
	// pruning on Put/Take/Stats alone would let entries for
	// never-touched targets linger in RAM until a health poll.
	// hub.Close stops the sweeper.
	h.mailbox.Start()
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
				// Drop expired auth-failure counters in the same sweep so the
				// per-IP map stays bounded without per-request cleanup.
				h.limiter.sweep()
			case <-h.done:
				return
			}
		}
	}()
	return h
}

// SetMaxPeers bounds how many distinct devices may be online at once. Values
// <= 0 disable the cap. The main function wires this to -max-peers.
func (h *Hub) SetMaxPeers(n int) {
	h.mu.Lock()
	h.maxPeers = n
	h.mu.Unlock()
}

// peerCount reports the number of connected devices under the hub mutex.
func (h *Hub) peerCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.peers)
}

// Close stops the eviction goroutines. Safe to call more than once.
func (h *Hub) Close() {
	h.closeOnce.Do(func() {
		close(h.done)
		if h.chunks != nil {
			h.chunks.Close()
		}
		if h.mailbox != nil {
			h.mailbox.Close()
		}
	})
}

// Closed reports whether Close has run and the eviction goroutine has
// stopped. Exposed for shutdown tests.
func (h *Hub) Closed() bool {
	select {
	case <-h.done:
		return true
	default:
		return false
	}
}

// closePeerConns closes every live peer connection. Shutdown uses it to
// give WebSocket clients a clean close and let their handlers exit:
// hijacked connections are invisible to http.Server.Shutdown, so without
// this they would only die with the process.
func (h *Hub) closePeerConns() {
	_, peers := h.peersSnapshot()
	for _, p := range peers {
		_ = p.conn.Close()
	}
}

// authenticate reports whether token is either the master cluster token or
// the derived relay auth token K_relay = hex(HMAC-SHA256(master, "gaur-relay-auth-v1")).
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

// authGate authenticates a request and applies per-IP failure throttling.
// Every authenticated endpoint funnels through here. Successful auths do not
// touch the limiter; once an IP has exhausted its failure budget, further
// attempts from it are rejected with 429 regardless of token validity.
//
// NOTE: in production all clients arrive from loopback through the
// Shadowsocks tunnel, so this budget is effectively cluster-wide. That is a
// deliberate trade-off: it stops a broken client from hammering the relay
// while legitimate members wait a minute. Per-device keys (protocol v4) are
// the real fix and would allow per-member budgets.
func (h *Hub) authGate(w http.ResponseWriter, r *http.Request, token string) bool {
	ip := clientIP(r)
	if h.limiter.blocked(ip) {
		w.Header().Set("Retry-After", "60")
		http.Error(w, "Too many failed auth attempts", http.StatusTooManyRequests)
		return false
	}
	if h.authenticate(token) {
		return true
	}
	h.limiter.recordFailure(ip)
	http.Error(w, "Unauthorized", http.StatusUnauthorized)
	return false
}

// clientIP extracts the peer address from RemoteAddr (host part only).
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func (h *Hub) broadcastPeerList() {
	list, peers := h.peersSnapshot()
	payload, err := json.Marshal(map[string]interface{}{"type": "peers", "peers": list})
	if err != nil {
		return
	}

	for _, p := range peers {
		_ = p.writeMessage(websocket.TextMessage, payload)
	}
}

func (h *Hub) sendPeerList(peer *Peer) {
	list, _ := h.peersSnapshot()
	payload, err := json.Marshal(map[string]interface{}{"type": "peers", "peers": list})
	if err != nil {
		return
	}
	_ = peer.writeMessage(websocket.TextMessage, payload)
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

	if !h.authGate(w, r, token) {
		return
	}
	if !validDeviceID.MatchString(deviceID) {
		http.Error(w, "Invalid device_id (1-64 chars, [A-Za-z0-9._-])", http.StatusBadRequest)
		return
	}
	if len(deviceName) > maxDeviceNameBytes {
		http.Error(w, "Name too long (max 128 bytes)", http.StatusBadRequest)
		return
	}

	if deviceName == "" {
		deviceName = deviceID
	}

	// Enforce the distinct-device cap before the handshake. A device that is
	// already connected may always reconnect (it replaces its old connection
	// below), so a cap-size cluster is not locked out of its own session.
	// The check and the registration are separate lock acquisitions, so two
	// simultaneous joins can overshoot the cap by one; that is acceptable for
	// a policy limit.
	h.mu.Lock()
	if h.maxPeers > 0 && len(h.peers) >= h.maxPeers {
		if _, exists := h.peers[deviceID]; !exists {
			h.mu.Unlock()
			http.Error(w, "Cluster full", http.StatusForbidden)
			return
		}
	}
	h.mu.Unlock()

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
		ID:           deviceID,
		Name:         deviceName,
		conn:         conn,
		joinedAt:     time.Now(),
		pingInterval: wsPingInterval,
		readWindow:   wsReadWindow,
		writeTimeout: wsWriteTimeout,
	}

	// Liveness: the read loop runs under a rolling deadline. Any inbound
	// frame — data or pong — extends it; silence past the window means
	// the client is gone, and the connection is dropped instead of
	// lingering as a stale "online" peer.
	_ = conn.SetReadDeadline(time.Now().Add(peer.readWindow))
	conn.SetPongHandler(func(string) error {
		_ = conn.SetReadDeadline(time.Now().Add(peer.readWindow))
		return nil
	})

	h.mu.Lock()
	if old, exists := h.peers[deviceID]; exists {
		_ = old.conn.Close()
	}
	h.peers[deviceID] = peer
	h.mu.Unlock()

	log.Printf("[HUB] Device joined: %s (%s)", deviceName, deviceID)
	h.broadcastPeerList()

	// Server-side pings: without them the read deadline above could only
	// be extended by client-initiated traffic, so a silently dead client
	// would look online forever.
	pingDone := make(chan struct{})
	var stopPingOnce sync.Once
	stopPing := func() { stopPingOnce.Do(func() { close(pingDone) }) }
	go peer.runPingLoop(pingDone)

	// Deliver sealed messages that arrived while this device was
	// offline. Drain removes an entry only after its write succeeds, so
	// a client that vanishes mid-join loses nothing: undelivered entries
	// stay queued, in order, for the next join. Receivers dedupe by
	// msg_id, so the resulting at-least-once delivery is harmless.
	h.mailbox.Drain(deviceID, func(pending []byte) bool {
		return peer.writeMessage(websocket.TextMessage, pending) == nil
	})

	defer func() {
		stopPing()
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
		// Any successful read proves the client is alive; re-arm the
		// read window before blocking in ReadMessage again.
		_ = conn.SetReadDeadline(time.Now().Add(peer.readWindow))
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
			_ = peer.writeJSON(map[string]string{"type": "pong"})
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
			// The sender's map is untrusted input: a buggy or malicious
			// client could stuff from_id/from_name into a sealed envelope and
			// leak identity despite the sealed-sender design. Strip them
			// server-side; the relay only ever adds relay_ts.
			delete(envelope, "from_id")
			delete(envelope, "from_name")
			envelope["relay_ts"] = time.Now().UnixMilli()
			forwardBytes, _ := json.Marshal(envelope)
			h.mu.RLock()
			targetPeer, exists := h.peers[targetID]
			h.mu.RUnlock()
			if exists && targetPeer != nil {
				if err := targetPeer.writeMessage(websocket.TextMessage, forwardBytes); err != nil {
					// The peer looked present but the write failed: the
					// device died mid-join or stopped reading. Re-queue
					// the envelope instead of dropping it. Put enforces
					// the byte cap and TTL, and the re-queued bytes keep
					// their original relay_ts, so receiver-side
					// freshness still applies.
					h.mailbox.Put(targetID, forwardBytes)
				}
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
			_ = targetPeer.writeMessage(websocket.TextMessage, forwardBytes)
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

	if transferID == "" || (role != "sender" && role != "receiver") || !h.authGate(w, r, token) {
		http.Error(w, "Unauthorized or invalid params", http.StatusUnauthorized)
		return
	}

	h.pipeMu.Lock()
	session, exists := h.pipes[transferID]
	if !exists {
		session = newPipeSession(transferID)
		h.pipes[transferID] = session
	}
	// One live sender and one live receiver per transfer. A second
	// connection claiming an occupied role would interleave its bytes into
	// the other side's stream and corrupt it, so it gets a 409 instead.
	if !session.claimRoleLocked(role) {
		h.pipeMu.Unlock()
		http.Error(w, "Role already active for this transfer", http.StatusConflict)
		return
	}
	atomic.AddInt32(&session.activeConnections, 1)
	h.pipeMu.Unlock()

	defer func() {
		h.pipeMu.Lock()
		session.releaseRoleLocked(role)
		remaining := atomic.AddInt32(&session.activeConnections, -1)
		if remaining <= 0 {
			if cur, ok := h.pipes[transferID]; ok && cur == session {
				delete(h.pipes, transferID)
			}
		}
		h.pipeMu.Unlock()
		if remaining <= 0 {
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
// did not supply one (status and delete endpoints do not need it). It returns
// the raw transfer and session ids alongside the composite key, because
// capability tags are computed over the exact ids, not the joined key.
func (h *Hub) transferKey(r *http.Request) (transferID, sessionID, key string, index int, ok bool) {
	transferID = strings.TrimSpace(r.URL.Query().Get("transfer_id"))
	sessionID = strings.TrimSpace(r.URL.Query().Get("session_id"))
	if transferID == "" || sessionID == "" {
		return "", "", "", 0, false
	}
	index = -1
	if raw := strings.TrimSpace(r.URL.Query().Get("index")); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 0 || parsed >= maxChunksPerTransfer {
			return "", "", "", 0, false
		}
		index = parsed
	}
	return transferID, sessionID, transferID + "/" + sessionID, index, true
}

// capabilityTag computes the per-transfer capability tag that cluster members
// derive on their side: hex(HMAC-SHA256(K_peer, "gaur-cap-v1:"+transferID+":"+sessionID)).
func (h *Hub) capabilityTag(transferID, sessionID string) string {
	mac := hmac.New(sha256.New, h.peerKey)
	mac.Write([]byte("gaur-cap-v1:" + transferID + ":" + sessionID))
	return hex.EncodeToString(mac.Sum(nil))
}

// authorizeChunkAccess enforces per-transfer ownership and capability tags on
// every chunk/transfer endpoint. Access rules, in order:
//
//  1. A valid "cap" query param (capability tag for this exact transfer and
//     session) allows access.
//  2. A "device_id" equal to the recorded owner allows access.
//  3. If no owner is recorded yet, access is allowed in open mode so legacy
//     clients keep working; the first such access logs one deprecation line
//     per transfer key.
//  4. Anything else is rejected with 403.
//
// NOTE: this is relay-side policy, not a cryptographic boundary. Any cluster
// member holds the master token and can therefore mint valid capability tags
// for any transfer. Per-device keys (protocol v4) are the real fix; this guard
// exists to stop well-behaved members from accidentally reading, overwriting,
// or deleting each other's transfers.
func (h *Hub) authorizeChunkAccess(w http.ResponseWriter, r *http.Request, transferID, sessionID string) bool {
	key := transferID + "/" + sessionID
	capParam := strings.TrimSpace(r.URL.Query().Get("cap"))
	if capParam != "" {
		want := h.capabilityTag(transferID, sessionID)
		if subtle.ConstantTimeCompare([]byte(capParam), []byte(want)) == 1 {
			return true
		}
	}
	deviceID := strings.TrimSpace(r.URL.Query().Get("device_id"))
	if deviceID != "" {
		if owner, ok := h.chunks.Owner(key); ok && owner == deviceID {
			return true
		}
	}
	if _, ok := h.chunks.Owner(key); !ok {
		// Open mode: ownership not established yet. A request carrying a
		// device_id is a creator claiming the transfer (normal operation);
		// only requests with neither device_id nor cap are legacy open-mode
		// access and get the one-time deprecation log.
		if deviceID == "" && capParam == "" {
			h.chunks.NoteOpenMode(key)
		}
		return true
	}
	http.Error(w, "Transfer owned by another device", http.StatusForbidden)
	return false
}

func (h *Hub) handleChunkPut(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !h.authGate(w, r, requestToken(r)) {
		return
	}
	transferID, sessionID, key, index, ok := h.transferKey(r)
	if !ok || index < 0 {
		http.Error(w, "Invalid transfer_id, session_id, or index", http.StatusBadRequest)
		return
	}
	if !h.authorizeChunkAccess(w, r, transferID, sessionID) {
		return
	}
	// The first PUT carrying a device_id claims ownership for that device.
	// Recording happens once the request is authorized, before the body is
	// read: ownership is write-once and never overwritten, even if this PUT
	// later fails on chunk size or the store's byte cap. Ownership alone
	// never grants admission: the cap check in Put keys on staged chunks,
	// so a PUT refused with 507 stays refused on retry.
	if deviceID := strings.TrimSpace(r.URL.Query().Get("device_id")); deviceID != "" {
		h.chunks.RecordOwner(key, deviceID)
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxChunkSize+1))
	if err != nil {
		http.Error(w, "Read failed", http.StatusBadRequest)
		return
	}
	if err := h.chunks.Put(key, index, body); err != nil {
		// Map the store's error classes to honest status codes:
		// 507 cap-exceeded, 413 oversized, 500 disk I/O, 400
		// validation. Disk errors are logged server-side but answered
		// with a generic body so filesystem paths stay internal.
		switch {
		case errors.Is(err, errCapExceeded):
			http.Error(w, err.Error(), http.StatusInsufficientStorage)
		case errors.Is(err, errOversized):
			http.Error(w, err.Error(), http.StatusRequestEntityTooLarge)
		case errors.Is(err, errDiskIO):
			log.Printf("[CHUNK] Staging failed for %s index %d: %v", key, index, err)
			http.Error(w, "Chunk staging failed", http.StatusInternalServerError)
		default:
			http.Error(w, err.Error(), http.StatusBadRequest)
		}
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
	if !h.authGate(w, r, requestToken(r)) {
		return
	}
	transferID, sessionID, key, index, ok := h.transferKey(r)
	if !ok || index < 0 {
		http.Error(w, "Invalid transfer_id, session_id, or index", http.StatusBadRequest)
		return
	}
	if !h.authorizeChunkAccess(w, r, transferID, sessionID) {
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
	if !h.authGate(w, r, requestToken(r)) {
		return
	}
	transferID, sessionID, key, _, ok := h.transferKey(r)
	if !ok {
		http.Error(w, "Invalid transfer_id or session_id", http.StatusBadRequest)
		return
	}
	if !h.authorizeChunkAccess(w, r, transferID, sessionID) {
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
	if !h.authGate(w, r, requestToken(r)) {
		return
	}
	transferID, sessionID, key, _, ok := h.transferKey(r)
	if !ok {
		http.Error(w, "Invalid transfer_id or session_id", http.StatusBadRequest)
		return
	}
	if !h.authorizeChunkAccess(w, r, transferID, sessionID) {
		return
	}
	h.chunks.Delete(key)
	w.WriteHeader(http.StatusNoContent)
}

// handleHealth reports relay stats. It reads hub.peers through peerCount so
// the map is never touched without the hub mutex while WebSocket peers join
// and leave concurrently.
func (h *Hub) handleHealth(w http.ResponseWriter, r *http.Request) {
	transfers, bytes := h.chunks.Stats()
	mailboxPeers, mailboxBytes := h.mailbox.Stats()
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"status":          "healthy",
		"peers":           h.peerCount(),
		"chunk_transfers": transfers,
		"chunk_bytes":     bytes,
		"mailbox_peers":   mailboxPeers,
		"mailbox_bytes":   mailboxBytes,
	})
}

// loadToken resolves the cluster token. The token file is authoritative when
// its flag is set: a present-but-unreadable or empty file is a hard error and
// never falls through to the environment. The environment is consulted only
// when no token file is configured. The relay must never start tokenless.
func loadToken(cfg Config) (string, error) {
	if cfg.TokenFile != "" {
		data, err := os.ReadFile(cfg.TokenFile)
		if err != nil {
			return "", fmt.Errorf("read cluster token file %s: %w", cfg.TokenFile, err)
		}
		trimmed := strings.TrimSpace(string(data))
		if trimmed == "" {
			return "", fmt.Errorf("cluster token file %s is empty", cfg.TokenFile)
		}
		return trimmed, nil
	}
	if cfg.TokenEnv != "" {
		if val := strings.TrimSpace(os.Getenv(cfg.TokenEnv)); val != "" {
			return val, nil
		}
		return "", fmt.Errorf("cluster token not configured: no token file (-token-file is empty) and environment variable %s is unset or empty", cfg.TokenEnv)
	}
	return "", fmt.Errorf("cluster token not configured: no token file (-token-file is empty) and no token environment variable (-token-env is empty)")
}

// interfaceAddrs resolves the addresses of a network interface. It is a
// package-level variable so tests can substitute deterministic fixtures; the
// stdlib offers no way to construct a net.Interface that carries addresses.
var interfaceAddrs = func(ifi net.Interface) ([]net.Addr, error) {
	return ifi.Addrs()
}

// isLoopbackBind reports whether addr binds to a loopback-assigned address:
// either a loopback literal (127.0.0.0/8 or ::1) or any address assigned to an
// interface with net.FlagLoopback (e.g. a public IP aliased on lo, which is
// how the VPS exposes the relay to the tunnel). Wildcard binds ("", 0.0.0.0,
// "::") are rejected: they would listen on every interface.
func isLoopbackBind(addr string, ifaces []net.Interface) (bool, error) {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return false, fmt.Errorf("parse bind address %q: %w", addr, err)
	}
	if host == "" {
		return false, nil
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false, fmt.Errorf("bind host %q is not an IP address", host)
	}
	// Loopback literals are accepted without consulting the interface list.
	if ip.IsLoopback() {
		return true, nil
	}
	if ip.IsUnspecified() { // 0.0.0.0 or ::
		return false, nil
	}
	for _, ifi := range ifaces {
		if ifi.Flags&net.FlagLoopback == 0 {
			continue
		}
		addrs, err := interfaceAddrs(ifi)
		if err != nil {
			return false, fmt.Errorf("list addresses of loopback interface %s: %w", ifi.Name, err)
		}
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			if ipnet.IP.Equal(ip) {
				return true, nil
			}
		}
	}
	return false, nil
}

// newRelayMux builds the relay's full HTTP surface. Extracted from main
// so tests boot the identical routing table.
func newRelayMux(h *Hub) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", h.handleWS)
	mux.HandleFunc("/pipe", h.handlePipe)
	mux.HandleFunc("/chunk", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodPut:
			h.handleChunkPut(w, r)
		case http.MethodGet, http.MethodHead:
			h.handleChunkGet(w, r)
		default:
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/chunk/status", h.handleChunkStatus)
	mux.HandleFunc("/transfer", h.handleTransferDelete)
	mux.HandleFunc("/health", h.handleHealth)
	return mux
}

// shutdownTimeout bounds how long in-flight requests (chunk uploads,
// pipe streams) may take to complete during graceful shutdown before the
// relay exits anyway. Package var so tests can shrink it.
var shutdownTimeout = 10 * time.Second

// shutdownRelay drains the HTTP server, then stops the hub's and chunk
// store's background workers. Both Close methods are sync.Once-guarded,
// so the deferred hub.Close in main (and test cleanups) can run them
// again safely. Extracted from main so tests can exercise it end to end.
func shutdownRelay(srv *http.Server, hub *Hub, chunks *ChunkStore) {
	// WebSocket connections are hijacked from the HTTP server, so
	// Shutdown neither closes nor waits for them. Close the peers first
	// for a prompt stop and a clean close instead of a socket reset at
	// process exit.
	hub.closePeerConns()
	ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("[SHUTDOWN] HTTP drain incomplete: %v", err)
	}
	hub.Close()
	if chunks != nil {
		chunks.Close()
	}
}

func main() {
	var cfg Config
	flag.StringVar(&cfg.BindAddr, "bind", "127.0.0.1:9876", "Internal address and port to bind to (loopback only)")
	flag.StringVar(&cfg.TokenFile, "token-file", "/etc/gaur-relay/cluster.key", "Path to cluster secret token file")
	flag.StringVar(&cfg.TokenEnv, "token-env", "GAURPN_CLUSTER_TOKEN", "Environment variable for token fallback")
	chunkDir := flag.String("chunk-dir", filepath.Join(os.TempDir(), "gaur-relay-chunks"), "Directory for resumable chunk staging (empty = memory only)")
	chunkMax := flag.Int64("chunk-max-bytes", 8<<30, "Maximum bytes of staged chunks across all transfers")
	chunkTTL := flag.Duration("chunk-ttl", 24*time.Hour, "Idle time before a staged transfer is evicted")
	mailboxTTL := flag.Duration("mailbox-ttl", defaultMailboxTTL, "How long sealed messages for offline peers are held")
	mailboxMax := flag.Int64("mailbox-max-bytes", defaultMailboxMaxBytes, "Maximum bytes of queued sealed messages")
	mailboxMaxTarget := flag.Int64("mailbox-max-target-bytes", defaultMailboxMaxTargetBytes, "Maximum bytes of queued sealed messages per target device (0 = default 4 MiB)")
	maxPeers := flag.Int("max-peers", defaultMaxPeers, "Maximum number of distinct online devices (0 = unlimited)")
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

	chunks, err := NewChunkStore(*chunkDir, *chunkMax, *chunkTTL)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	mailbox := NewMailbox(*mailboxTTL, *mailboxMax, *mailboxMaxTarget)
	hub := NewHub([]byte(token), chunks, mailbox)
	hub.SetMaxPeers(*maxPeers)
	// shutdownRelay calls hub.Close itself; sync.Once makes this deferred
	// call a safe no-op if shutdown already ran (and the only cleanup if
	// main returns some other way).
	defer hub.Close()

	mux := newRelayMux(hub)

	// The relay must stay loopback-only per the security model: clients reach
	// it through the Shadowsocks tunnel, never over a public interface.
	// Enforce it in code instead of trusting the deployment convention.
	ifaces, err := net.Interfaces()
	if err != nil {
		log.Fatalf("[FATAL] Cannot enumerate network interfaces: %v", err)
	}
	loopback, err := isLoopbackBind(cfg.BindAddr, ifaces)
	if err != nil {
		log.Fatalf("[FATAL] Invalid bind address %q: %v", cfg.BindAddr, err)
	}
	if !loopback {
		log.Fatalf("[FATAL] Refusing to bind %s: the relay must stay loopback-only per the security model. Bind to 127.0.0.1 or an address assigned to a loopback interface (clients connect through the Shadowsocks tunnel)", cfg.BindAddr)
	}

	log.Printf("[START] gaur-relay listening on %s (loopback only, zero public exposure)", cfg.BindAddr)
	server := &http.Server{
		Addr:              cfg.BindAddr,
		Handler:           mux,
		ReadHeaderTimeout: 30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	// SIGTERM (systemd stop, restart) drains in-flight transfers instead
	// of killing them mid-stream; a serve error aborts with status 1.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	serveErr := make(chan error, 1)
	go func() {
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serveErr <- err
		}
	}()

	select {
	case err := <-serveErr:
		log.Printf("[FATAL] Server terminated: %v", err)
		shutdownRelay(server, hub, chunks)
		os.Exit(1)
	case <-ctx.Done():
		log.Printf("[SHUTDOWN] Signal received, draining relay")
		shutdownRelay(server, hub, chunks)
	}
	log.Printf("[SHUTDOWN] gaur-relay stopped")
}
