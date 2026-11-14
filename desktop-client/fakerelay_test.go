package main

// Minimal in-process relay for integration tests, implementing the wire
// protocol from docs/FILE_SHARING.md: authenticated /ws with peer presence,
// sealed and legacy forwarding, chunk staging, chunk status, transfer delete,
// and the /pipe stream. Mailbox delivery, TTLs, eviction, the auth-failure
// limiter, and chunk ownership are omitted — only what the tests below need
// is implemented. It is safe for concurrent use.

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type fakePeer struct {
	id       string
	name     string
	mu       sync.Mutex
	conn     *websocket.Conn
	dark     chan struct{}
	darkOnce sync.Once
}

// writeMessage serializes writes: gorilla permits one concurrent writer, and
// the fake forwards from many handler goroutines.
func (p *fakePeer) writeMessage(messageType int, data []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.conn.WriteMessage(messageType, data)
}

func (p *fakePeer) writeJSON(v interface{}) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.conn.WriteJSON(v)
}

// goDark marks the peer's connection as half-open.
func (p *fakePeer) goDark() { p.darkOnce.Do(func() { close(p.dark) }) }

type fakeRelay struct {
	URL   string // host:port, matching Config.RelayAddr
	Token string // master cluster token

	srv  *httptest.Server
	done chan struct{}

	mu            sync.Mutex
	peers         map[string]*fakePeer
	chunks        map[string]map[int][]byte
	pipes         map[string]*fakePipe
	putIndices    []int // successful PUTs
	putAttempts   int   // all PUTs, including rejected ones
	getIndices    []int
	statusCalls   int
	sealedTargets []string
	legacyTargets []string
	wsConnects    int
	rejectPuts    bool
}

func newFakeRelay(t *testing.T) *fakeRelay {
	t.Helper()
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		t.Fatalf("fake relay token: %v", err)
	}
	f := &fakeRelay{
		Token:  hex.EncodeToString(raw),
		peers:  make(map[string]*fakePeer),
		chunks: make(map[string]map[int][]byte),
		done:   make(chan struct{}),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", f.handleWS)
	mux.HandleFunc("/chunk/status", f.handleChunkStatus)
	mux.HandleFunc("/chunk", f.handleChunk)
	mux.HandleFunc("/transfer", f.handleTransferDelete)
	mux.HandleFunc("/pipe", f.handlePipe)
	f.srv = httptest.NewServer(mux)
	f.URL = strings.TrimPrefix(f.srv.URL, "http://")
	t.Cleanup(func() {
		close(f.done)
		f.srv.Close()
	})
	return f
}

// relayToken returns the derived K_relay = hex(HMAC(master, "gaur-relay-auth-v1"))
// that clients send in X-Gaur-Token.
func (f *fakeRelay) relayToken() string {
	mac := hmac.New(sha256.New, []byte(f.Token))
	mac.Write([]byte("gaur-relay-auth-v1"))
	return hex.EncodeToString(mac.Sum(nil))
}

// auth mirrors the relay: accept the derived relay token or the master token,
// via the X-Gaur-Token header or the token query parameter.
func (f *fakeRelay) auth(r *http.Request) bool {
	token := strings.TrimSpace(r.Header.Get("X-Gaur-Token"))
	if token == "" {
		token = strings.TrimSpace(r.URL.Query().Get("token"))
	}
	return token != "" && (token == f.Token || token == f.relayToken())
}

var fakeUpgrader = websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}

func (f *fakeRelay) handleWS(w http.ResponseWriter, r *http.Request) {
	if !f.auth(r) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	deviceID := strings.TrimSpace(r.URL.Query().Get("device_id"))
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if deviceID == "" {
		http.Error(w, "Invalid device_id", http.StatusBadRequest)
		return
	}
	if name == "" {
		name = deviceID
	}
	conn, err := fakeUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	peer := &fakePeer{id: deviceID, name: name, conn: conn, dark: make(chan struct{})}

	f.mu.Lock()
	if old, exists := f.peers[deviceID]; exists {
		// A reconnecting device replaces itself, like the real relay.
		_ = old.conn.Close()
		old.goDark()
	}
	f.peers[deviceID] = peer
	f.wsConnects++
	f.mu.Unlock()

	f.broadcastPeers()

	defer func() {
		f.mu.Lock()
		if cur, ok := f.peers[deviceID]; ok && cur == peer {
			delete(f.peers, deviceID)
		}
		f.mu.Unlock()
		_ = conn.Close()
		f.broadcastPeers()
	}()

	for {
		_, message, err := conn.ReadMessage()
		if err != nil {
			return
		}
		// Once dark, the relay stops reading and writing its side: the TCP
		// connection stays open but no data flows in either direction,
		// which is what a half-open link looks like from the client.
		select {
		case <-peer.dark:
			<-f.done
			return
		default:
		}

		var envelope map[string]interface{}
		if json.Unmarshal(message, &envelope) != nil {
			continue
		}
		switch envelope["type"] {
		case "ping":
			_ = peer.writeJSON(map[string]string{"type": "pong"})
		case "list_peers":
			f.sendPeerList(peer)
		case "sealed":
			target, _ := envelope["target_id"].(string)
			if target == "" {
				continue
			}
			// The relay never attaches sender identity to sealed mail; it
			// strips it and stamps relay_ts only.
			delete(envelope, "from_id")
			delete(envelope, "from_name")
			envelope["relay_ts"] = float64(time.Now().UnixMilli())
			forward, _ := json.Marshal(envelope)
			f.forward("sealed", target, forward)
		default:
			target, _ := envelope["target_id"].(string)
			if target == "" {
				continue
			}
			envelope["from_id"] = deviceID
			envelope["from_name"] = name
			envelope["relay_ts"] = float64(time.Now().UnixMilli())
			forward, _ := json.Marshal(envelope)
			f.forward("legacy", target, forward)
		}
	}
}

// forward records a sealed or legacy forward and delivers it if the target is
// online (no mailbox: offline targets are simply recorded).
func (f *fakeRelay) forward(kind, target string, message []byte) {
	f.mu.Lock()
	if kind == "sealed" {
		f.sealedTargets = append(f.sealedTargets, target)
	} else {
		f.legacyTargets = append(f.legacyTargets, target)
	}
	targetPeer := f.peers[target]
	f.mu.Unlock()
	if targetPeer != nil {
		_ = targetPeer.writeMessage(websocket.TextMessage, message)
	}
}

func (f *fakeRelay) peersSnapshot() []map[string]string {
	f.mu.Lock()
	defer f.mu.Unlock()
	list := make([]map[string]string, 0, len(f.peers))
	for _, p := range f.peers {
		list = append(list, map[string]string{"id": p.id, "name": p.name})
	}
	return list
}

func (f *fakeRelay) broadcastPeers() {
	payload, _ := json.Marshal(map[string]interface{}{"type": "peers", "peers": f.peersSnapshot()})
	f.mu.Lock()
	peers := make([]*fakePeer, 0, len(f.peers))
	for _, p := range f.peers {
		peers = append(peers, p)
	}
	f.mu.Unlock()
	for _, p := range peers {
		_ = p.writeMessage(websocket.TextMessage, payload)
	}
}

func (f *fakeRelay) sendPeerList(peer *fakePeer) {
	payload, _ := json.Marshal(map[string]interface{}{"type": "peers", "peers": f.peersSnapshot()})
	_ = peer.writeMessage(websocket.TextMessage, payload)
}

// goDark makes the relay's side of deviceID's connection stop reading and
// writing while keeping the TCP connection open — a half-open link. The
// client's ping writes keep succeeding into the socket buffer, but nothing
// ever comes back, so only the client's read deadline can detect it.
func (f *fakeRelay) goDark(deviceID string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if p, ok := f.peers[deviceID]; ok {
		p.goDark()
	}
}

// killConn closes deviceID's underlying TCP connection without a WS close
// frame, so the client's subsequent writes fail.
func (f *fakeRelay) killConn(deviceID string) {
	f.mu.Lock()
	p := f.peers[deviceID]
	f.mu.Unlock()
	if p != nil {
		_ = p.conn.Close()
	}
}

func (f *fakeRelay) setRejectPuts(reject bool) {
	f.mu.Lock()
	f.rejectPuts = reject
	f.mu.Unlock()
}

func chunkKey(transferID, sessionID string) string { return transferID + "/" + sessionID }

func (f *fakeRelay) handleChunk(w http.ResponseWriter, r *http.Request) {
	if !f.auth(r) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	transferID := r.URL.Query().Get("transfer_id")
	sessionID := r.URL.Query().Get("session_id")
	index, err := strconv.Atoi(r.URL.Query().Get("index"))
	if transferID == "" || sessionID == "" || err != nil || index < 0 {
		http.Error(w, "Invalid params", http.StatusBadRequest)
		return
	}
	key := chunkKey(transferID, sessionID)
	switch r.Method {
	case http.MethodPut:
		body, _ := io.ReadAll(io.LimitReader(r.Body, MaxChunkSize+1))
		f.mu.Lock()
		f.putAttempts++
		reject := f.rejectPuts
		if !reject {
			if f.chunks[key] == nil {
				f.chunks[key] = make(map[int][]byte)
			}
			f.chunks[key][index] = append([]byte(nil), body...)
			f.putIndices = append(f.putIndices, index)
		}
		f.mu.Unlock()
		if reject {
			http.Error(w, "Upload rejected", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "index": index})
	case http.MethodGet:
		f.mu.Lock()
		data, ok := f.chunks[key][index]
		if ok {
			f.getIndices = append(f.getIndices, index)
		}
		f.mu.Unlock()
		if !ok {
			http.Error(w, "Chunk not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(data)
	default:
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
	}
}

func (f *fakeRelay) handleChunkStatus(w http.ResponseWriter, r *http.Request) {
	if !f.auth(r) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	transferID := r.URL.Query().Get("transfer_id")
	sessionID := r.URL.Query().Get("session_id")
	count, err := strconv.Atoi(r.URL.Query().Get("count"))
	if transferID == "" || sessionID == "" || err != nil || count < 0 {
		http.Error(w, "Invalid params", http.StatusBadRequest)
		return
	}
	f.mu.Lock()
	present := make([]int, 0)
	for idx := range f.chunks[chunkKey(transferID, sessionID)] {
		if idx < count {
			present = append(present, idx)
		}
	}
	f.statusCalls++
	f.mu.Unlock()
	sort.Ints(present)
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{"present": present})
}

func (f *fakeRelay) handleTransferDelete(w http.ResponseWriter, r *http.Request) {
	if !f.auth(r) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	if r.Method != http.MethodDelete {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	f.mu.Lock()
	delete(f.chunks, chunkKey(r.URL.Query().Get("transfer_id"), r.URL.Query().Get("session_id")))
	f.mu.Unlock()
	w.WriteHeader(http.StatusNoContent)
}

// fakePipe is a 2 MiB buffered byte pipe between the sender POST and the
// receiver GET, mirroring the relay's /pipe semantics.
const fakePipeBufferLimit = 2 << 20

type fakePipe struct {
	senderReady   chan struct{}
	receiverReady chan struct{}
	senderOnce    sync.Once
	receiverOnce  sync.Once

	mu         sync.Mutex
	cond       *sync.Cond
	buf        bytes.Buffer
	senderDone bool
}

func newFakePipe() *fakePipe {
	p := &fakePipe{
		senderReady:   make(chan struct{}),
		receiverReady: make(chan struct{}),
	}
	p.cond = sync.NewCond(&p.mu)
	return p
}

func (p *fakePipe) markSenderReady()   { p.senderOnce.Do(func() { close(p.senderReady) }) }
func (p *fakePipe) markReceiverReady() { p.receiverOnce.Do(func() { close(p.receiverReady) }) }

// write copies from the sender's request body into the buffer, blocking while
// the buffer is full.
func (p *fakePipe) write(r io.Reader) error {
	buf := make([]byte, 256*1024)
	for {
		n, readErr := r.Read(buf)
		if n > 0 {
			p.mu.Lock()
			for p.buf.Len() >= fakePipeBufferLimit && !p.senderDone {
				p.cond.Wait()
			}
			p.buf.Write(buf[:n])
			p.cond.Broadcast()
			p.mu.Unlock()
		}
		if readErr == io.EOF {
			p.mu.Lock()
			p.senderDone = true
			p.cond.Broadcast()
			p.mu.Unlock()
			return nil
		}
		if readErr != nil {
			return readErr
		}
	}
}

// read streams the buffer to the receiver, blocking until data arrives or the
// sender finishes.
func (p *fakePipe) read(w io.Writer) error {
	buf := make([]byte, 256*1024)
	for {
		p.mu.Lock()
		for p.buf.Len() == 0 && !p.senderDone {
			p.cond.Wait()
		}
		n, _ := p.buf.Read(buf)
		p.cond.Broadcast()
		p.mu.Unlock()
		if n > 0 {
			if _, err := w.Write(buf[:n]); err != nil {
				return err
			}
			continue
		}
		return nil
	}
}

func (f *fakeRelay) handlePipe(w http.ResponseWriter, r *http.Request) {
	if !f.auth(r) {
		http.Error(w, "Unauthorized", http.StatusUnauthorized)
		return
	}
	transferID := r.URL.Query().Get("transfer_id")
	role := r.URL.Query().Get("role")
	if transferID == "" || (role != "sender" && role != "receiver") {
		http.Error(w, "Invalid params", http.StatusBadRequest)
		return
	}
	f.mu.Lock()
	session := f.pipes[transferID]
	if session == nil {
		session = newFakePipe()
		if f.pipes == nil {
			f.pipes = make(map[string]*fakePipe)
		}
		f.pipes[transferID] = session
	}
	f.mu.Unlock()

	switch role {
	case "receiver":
		session.markReceiverReady()
		select {
		case <-session.senderReady:
		case <-r.Context().Done():
			return
		case <-time.After(60 * time.Second):
			http.Error(w, "Sender connection timeout", http.StatusGatewayTimeout)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.WriteHeader(http.StatusOK)
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		_ = session.read(w)
	case "sender":
		session.markSenderReady()
		select {
		case <-session.receiverReady:
		case <-r.Context().Done():
			return
		case <-time.After(60 * time.Second):
			http.Error(w, "Receiver connection timeout", http.StatusGatewayTimeout)
			return
		}
		if err := session.write(r.Body); err != nil {
			http.Error(w, "Pipe read failed", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
	}
}

// Recording accessors. Snapshots are sorted so concurrent chunk workers do not
// make assertions order-dependent.

func (f *fakeRelay) putSnapshot() []int {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := append([]int(nil), f.putIndices...)
	sort.Ints(out)
	return out
}

func (f *fakeRelay) getSnapshot() []int {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := append([]int(nil), f.getIndices...)
	sort.Ints(out)
	return out
}

func (f *fakeRelay) sealedSnapshot() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.sealedTargets...)
}

func (f *fakeRelay) connects() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.wsConnects
}

func (f *fakeRelay) statusCallsCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.statusCalls
}

func (f *fakeRelay) putAttemptsCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.putAttempts
}

// resetRecording clears the per-request recordings so a test can observe only
// the traffic of the code under test.
func (f *fakeRelay) resetRecording() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.putIndices = nil
	f.putAttempts = 0
	f.getIndices = nil
	f.statusCalls = 0
	f.sealedTargets = nil
	f.legacyTargets = nil
}

// waitFor polls cond until it holds or the timeout expires.
func waitFor(t *testing.T, timeout time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("condition not met within %v", timeout)
}

// writeClusterToken writes the master token the way the pair command does.
func writeClusterToken(t *testing.T, dir string, token string) string {
	t.Helper()
	path := filepath.Join(dir, "cluster.key")
	if err := os.WriteFile(path, []byte(token+"\n"), 0o600); err != nil {
		t.Fatalf("write token: %v", err)
	}
	return path
}

// ifaceStrings converts []string to the []interface{} shape a JSON round trip
// produces, so test-built offers match what the wire path delivers.
func ifaceStrings(in []string) []interface{} {
	out := make([]interface{}, len(in))
	for i, s := range in {
		out[i] = s
	}
	return out
}

// stageV3Chunk encrypts one chunk exactly as the v3 sender does and stages it
// on the relay.
func stageV3Chunk(t *testing.T, f *fakeRelay, cfg Config, auth ChunkAuth, payload []byte, digest *FileDigest, fileKey []byte, transferID, sessionID string, index int) {
	t.Helper()
	start := index * ChunkSize
	end := start + ChunkSize
	if end > len(payload) {
		end = len(payload)
	}
	plain := payload[start:end]
	nonce := ChunkNonce(index)
	aad := ChunkAAD(transferID, sessionID, index, digest.ChunkCount, digest.Size)
	ciphertext, err := EncryptChunkAAD(fileKey, nonce, plain, aad)
	if err != nil {
		t.Fatalf("encrypt chunk %d: %v", index, err)
	}
	if err := putChunk(cfg, auth, transferID, sessionID, index, ciphertext); err != nil {
		t.Fatalf("stage chunk %d: %v", index, err)
	}
}

// --- REL-1: half-open connection detection ---

// TestDaemonSessionDetectsHalfOpenAndReconnects runs the daemon's message
// loop against a relay that goes dark: it stops reading and writing while
// keeping the TCP connection open, so pings keep succeeding and only the
// read deadline can detect the dead link. The session must tear down and the
// next one must reconnect and process messages again.
func TestDaemonSessionDetectsHalfOpenAndReconnects(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)

	oldRead, oldPing := daemonReadTimeout, daemonPingEvery
	daemonReadTimeout, daemonPingEvery = 400*time.Millisecond, 100*time.Millisecond
	defer func() { daemonReadTimeout, daemonPingEvery = oldRead, oldPing }()

	dir := t.TempDir()
	cfg := Config{
		RelayAddr: f.URL,
		DeviceID:  "desktop-1",
		Name:      "Desktop",
		InboxDir:  filepath.Join(dir, "inbox"),
		PeersFile: filepath.Join(dir, "peers.json"),
		Clipboard: false,
	}
	session := &daemonSession{
		cfg:             cfg,
		relayToken:      relayToken,
		peerCryptoKey:   peerKey,
		sender:          &CommandSender{cfg: cfg},
		broadcaster:     &MediaBroadcaster{cfg: cfg},
		store:           &peerStore{},
		activeTransfers: &sync.Map{},
	}

	// First connection: healthy.
	done1 := make(chan bool, 1)
	go func() { done1 <- session.runOnce() }()
	waitFor(t, 5*time.Second, func() bool { return f.connects() >= 1 })

	// Half-open: the relay stops responding but the socket stays up.
	f.goDark(cfg.DeviceID)

	select {
	case connected := <-done1:
		if !connected {
			t.Fatal("a session that had connected must report it did")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("daemon did not detect the half-open connection within the deadline")
	}

	// Reconnect: the second session must reach the relay and process its
	// peer list again (peers.json is rewritten with this device in it).
	done2 := make(chan bool, 1)
	go func() { done2 <- session.runOnce() }()
	waitFor(t, 5*time.Second, func() bool { return f.connects() >= 2 })
	waitFor(t, 5*time.Second, func() bool {
		data, err := os.ReadFile(cfg.PeersFile)
		return err == nil && strings.Contains(string(data), `"desktop-1"`)
	})

	// End the second session cleanly so no goroutines outlive the test.
	f.goDark(cfg.DeviceID)
	select {
	case <-done2:
	case <-time.After(5 * time.Second):
		t.Fatal("second session did not wind down after the relay went dark")
	}
}

// TestPingLoopClosesConnOnWriteFailure pins the second detection mechanism:
// when a ping write fails, the ping loop must close the connection — that is
// what unblocks a read loop parked in ReadMessage — and then exit instead of
// returning silently.
func TestPingLoopClosesConnOnWriteFailure(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, _ := DeriveSubKeys(f.Token)
	cfg := Config{RelayAddr: f.URL, DeviceID: "desktop-1", Name: "Desktop"}

	oldPing := daemonPingEvery
	daemonPingEvery = 20 * time.Millisecond
	defer func() { daemonPingEvery = oldPing }()

	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer conn.Close()
	client := &wsClient{conn: conn}

	// Kill the TCP connection from the server side without a WS close
	// frame: subsequent ping writes fail.
	f.killConn(cfg.DeviceID)

	stop := make(chan struct{})
	exited := make(chan struct{})
	go func() { pingLoop(client, stop); close(exited) }()
	select {
	case <-exited:
	case <-time.After(5 * time.Second):
		t.Fatal("ping loop did not exit after its write failed")
	}

	// The read loop is unblocked because the conn was closed.
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := client.ReadMessage(); err == nil {
		t.Fatal("expected ReadMessage to fail after the ping loop closed the conn")
	}
}

// --- REL-4: v3 resume ---

// TestSendV3UploadsOnlyMissingChunks pre-stages all but the last chunk on the
// relay — exactly what an interrupted previous attempt leaves behind — and
// asserts a v3 send uploads only the missing index, under the deterministic
// session id.
func TestSendV3UploadsOnlyMissingChunks(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)

	dir := t.TempDir()
	src := filepath.Join(dir, "resume.bin")
	payload := make([]byte, ChunkSize*2+12345) // 3 chunks
	for i := range payload {
		payload[i] = byte(i * 7)
	}
	if err := os.WriteFile(src, payload, 0o600); err != nil {
		t.Fatal(err)
	}

	digest, err := HashFileChunks(src, ChunkSize)
	if err != nil {
		t.Fatal(err)
	}
	transferID := DeriveTransferID(peerKey, digest.FileHash)
	sessionID := DeriveSessionID(peerKey, transferID)
	fileKey := DeriveFileKey(peerKey, transferID)

	cfg := Config{RelayAddr: f.URL, DeviceID: "desktop-1", Name: "Desktop"}
	auth := ChunkAuth{RelayToken: relayToken, DeviceID: "desktop-1-out", PeerKey: peerKey}
	for i := 0; i < digest.ChunkCount-1; i++ {
		stageV3Chunk(t, f, cfg, auth, payload, digest, fileKey, transferID, sessionID, i)
	}
	f.resetRecording()

	sendCfg := Config{
		RelayAddr: f.URL,
		TokenFile: writeClusterToken(t, dir, f.Token),
		DeviceID:  "desktop-1",
		Name:      "Desktop",
	}
	if err := runSendV3(sendCfg, []string{"phone-1"}, []string{src}, false, false); err != nil {
		t.Fatalf("runSendV3: %v", err)
	}

	if puts := f.putSnapshot(); len(puts) != 1 || puts[0] != digest.ChunkCount-1 {
		t.Fatalf("uploaded chunk indices = %v, want only the missing index [%d]", puts, digest.ChunkCount-1)
	}
	if f.statusCallsCount() < 1 {
		t.Fatal("sender must consult /chunk/status before uploading")
	}
	// The sealed offer is forwarded asynchronously once the fake's WS
	// handler reads it.
	waitFor(t, 5*time.Second, func() bool {
		sealed := f.sealedSnapshot()
		return len(sealed) == 1 && sealed[0] == "phone-1"
	})
}

// TestSendV3RetriesUploadsAndReturnsError rejects every upload at the relay
// and asserts the sender retries three times and returns an error instead of
// exiting the process.
func TestSendV3RetriesUploadsAndReturnsError(t *testing.T) {
	f := newFakeRelay(t)
	f.setRejectPuts(true)

	oldRetry, oldBackoff := v3SendRetryDelay, chunkStatusBackoff
	v3SendRetryDelay, chunkStatusBackoff = 5*time.Millisecond, 5*time.Millisecond
	defer func() { v3SendRetryDelay, chunkStatusBackoff = oldRetry, oldBackoff }()

	dir := t.TempDir()
	src := filepath.Join(dir, "small.bin")
	if err := os.WriteFile(src, bytes.Repeat([]byte("x"), 4096), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg := Config{
		RelayAddr: f.URL,
		TokenFile: writeClusterToken(t, dir, f.Token),
		DeviceID:  "desktop-1",
		Name:      "Desktop",
	}
	err := runSendV3(cfg, []string{"phone-1"}, []string{src}, false, false)
	if err == nil {
		t.Fatal("runSendV3 must return an error when every upload attempt fails")
	}
	if !contains(err.Error(), "3 attempts") {
		t.Fatalf("error must report the attempt count, got: %v", err)
	}
	if got := f.putAttemptsCount(); got != 3 {
		t.Fatalf("upload attempts = %d, want 3", got)
	}
	if puts := f.putSnapshot(); len(puts) != 0 {
		t.Fatalf("rejected uploads must not be staged, got %v", puts)
	}
	// Reaching this line at all proves the failure path returns instead of
	// calling os.Exit.
}

// TestSealedOfferResumesPartFile stages the full ciphertext on the relay,
// leaves half the file and its resume state on disk from an interrupted
// download, and asserts the receiver fetches only the missing chunks and
// completes the file with the correct hash.
func TestSealedOfferResumesPartFile(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)

	dir := t.TempDir()
	inbox := filepath.Join(dir, "inbox")
	if err := os.MkdirAll(inbox, 0o755); err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(dir, "photo.bin")
	payload := make([]byte, ChunkSize*3+777) // 4 chunks
	for i := range payload {
		payload[i] = byte(i * 13)
	}
	if err := os.WriteFile(src, payload, 0o600); err != nil {
		t.Fatal(err)
	}

	digest, err := HashFileChunks(src, ChunkSize)
	if err != nil {
		t.Fatal(err)
	}
	transferID := DeriveTransferID(peerKey, digest.FileHash)
	sessionID := DeriveSessionID(peerKey, transferID)
	fileKey := DeriveFileKey(peerKey, transferID)

	cfg := Config{RelayAddr: f.URL, DeviceID: "desktop-1", Name: "Desktop"}
	auth := ChunkAuth{RelayToken: relayToken, DeviceID: "desktop-1-out", PeerKey: peerKey}
	for i := 0; i < digest.ChunkCount; i++ {
		stageV3Chunk(t, f, cfg, auth, payload, digest, fileKey, transferID, sessionID, i)
	}

	// A previous, interrupted download left the first half of the file and
	// its resume state on disk.
	cleanName := "photo.bin"
	partPath := filepath.Join(inbox, fmt.Sprintf(".%s.%s.part", cleanName, transferID))
	statePath := partPath + ".state"
	part, err := os.OpenFile(partPath, os.O_CREATE|os.O_RDWR, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.WriteAt(payload[:ChunkSize*2], 0); err != nil {
		t.Fatal(err)
	}
	if err := part.Close(); err != nil {
		t.Fatal(err)
	}
	state := NewReceiveState(transferID, digest.FileHash, digest.Size, ChunkSize, digest.ChunkCount)
	state.MarkHave(0, statePath)
	state.MarkHave(1, statePath)
	state.Save(statePath)

	f.resetRecording()

	// The receiver's own WS connection so the completion receipt can be sent.
	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer conn.Close()
	client := &wsClient{conn: conn}

	inner := map[string]interface{}{
		"transfer_id":  transferID,
		"session_id":   sessionID,
		"file_name":    cleanName,
		"file_size":    float64(digest.Size),
		"chunk_size":   float64(ChunkSize),
		"chunk_count":  float64(digest.ChunkCount),
		"file_hash":    digest.FileHash,
		"chunk_hashes": ifaceStrings(digest.ChunkHashes),
	}
	recvCfg := Config{RelayAddr: f.URL, DeviceID: "desktop-1", Name: "Desktop", InboxDir: inbox}
	handleSealedOffer(recvCfg, relayToken, peerKey, client, "android-1", "Lavander", inner)

	if gets := f.getSnapshot(); !reflect.DeepEqual(gets, []int{2, 3}) {
		t.Fatalf("downloaded chunk indices = %v, want only the missing [2 3]", gets)
	}

	got, err := os.ReadFile(filepath.Join(inbox, cleanName))
	if err != nil {
		t.Fatalf("completed file is missing: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("received file does not match the original")
	}
	if _, err := os.Stat(partPath); !os.IsNotExist(err) {
		t.Fatalf(".part must be renamed away on success, stat err: %v", err)
	}
	if _, err := os.Stat(statePath); !os.IsNotExist(err) {
		t.Fatalf("state must be cleaned up on success, stat err: %v", err)
	}
	// The completion receipt is forwarded asynchronously once the fake's WS
	// handler reads it.
	waitFor(t, 5*time.Second, func() bool {
		sealed := f.sealedSnapshot()
		return len(sealed) == 1 && sealed[0] == "android-1"
	})
}

// TestSealedOfferResetsOnHashMismatch feeds a resume state written for a
// different file hash and asserts the receiver starts over instead of mixing
// chunks from two files.
func TestSealedOfferResetsOnHashMismatch(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)

	dir := t.TempDir()
	inbox := filepath.Join(dir, "inbox")
	if err := os.MkdirAll(inbox, 0o755); err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(dir, "doc.bin")
	payload := bytes.Repeat([]byte("d"), ChunkSize+100) // 2 chunks
	if err := os.WriteFile(src, payload, 0o600); err != nil {
		t.Fatal(err)
	}

	digest, err := HashFileChunks(src, ChunkSize)
	if err != nil {
		t.Fatal(err)
	}
	transferID := DeriveTransferID(peerKey, digest.FileHash)
	sessionID := DeriveSessionID(peerKey, transferID)
	fileKey := DeriveFileKey(peerKey, transferID)

	cfg := Config{RelayAddr: f.URL, DeviceID: "desktop-1", Name: "Desktop"}
	auth := ChunkAuth{RelayToken: relayToken, DeviceID: "desktop-1-out", PeerKey: peerKey}
	for i := 0; i < digest.ChunkCount; i++ {
		stageV3Chunk(t, f, cfg, auth, payload, digest, fileKey, transferID, sessionID, i)
	}

	// State from a different file: same transfer id, different hash.
	cleanName := "doc.bin"
	partPath := filepath.Join(inbox, fmt.Sprintf(".%s.%s.part", cleanName, transferID))
	statePath := partPath + ".state"
	stale := NewReceiveState(transferID, "deadbeef"+digest.FileHash[8:], digest.Size, ChunkSize, digest.ChunkCount)
	stale.MarkHave(0, statePath)
	stale.Save(statePath)
	if err := os.WriteFile(partPath, bytes.Repeat([]byte("z"), 512), 0o644); err != nil {
		t.Fatal(err)
	}

	f.resetRecording()

	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer conn.Close()
	client := &wsClient{conn: conn}

	inner := map[string]interface{}{
		"transfer_id":  transferID,
		"session_id":   sessionID,
		"file_name":    cleanName,
		"file_size":    float64(digest.Size),
		"chunk_size":   float64(ChunkSize),
		"chunk_count":  float64(digest.ChunkCount),
		"file_hash":    digest.FileHash,
		"chunk_hashes": ifaceStrings(digest.ChunkHashes),
	}
	recvCfg := Config{RelayAddr: f.URL, DeviceID: "desktop-1", Name: "Desktop", InboxDir: inbox}
	handleSealedOffer(recvCfg, relayToken, peerKey, client, "android-1", "Lavander", inner)

	// The stale state is discarded: every chunk is downloaded fresh.
	if gets := f.getSnapshot(); !reflect.DeepEqual(gets, []int{0, 1}) {
		t.Fatalf("downloaded chunk indices = %v, want a full [0 1] download", gets)
	}
	got, err := os.ReadFile(filepath.Join(inbox, cleanName))
	if err != nil {
		t.Fatalf("completed file is missing: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("received file does not match the original")
	}
}

// TestFetchChunkStatusRetriesOnError points fetchChunkStatus at a dead
// endpoint and asserts it retries three times and returns nil so the caller
// uploads everything.
func TestFetchChunkStatusRetriesOnError(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)

	oldBackoff := chunkStatusBackoff
	chunkStatusBackoff = 5 * time.Millisecond
	defer func() { chunkStatusBackoff = oldBackoff }()

	cfg := Config{RelayAddr: "127.0.0.1:1"} // nothing listens
	auth := ChunkAuth{RelayToken: relayToken, DeviceID: "desktop-1", PeerKey: peerKey}
	if got := fetchChunkStatus(cfg, auth, "tx-1", "sess-1", 4); got != nil {
		t.Fatalf("fetchChunkStatus on a dead relay = %v, want nil", got)
	}
}
