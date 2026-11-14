package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

const statusTestToken = "status-test-token"

// newStatusTestServer starts a hub with one WebSocket handler and returns
// the base ws:// URL. The hub and server are torn down with the test.
func newStatusTestServer(t *testing.T) (*Hub, string) {
	t.Helper()
	hub := NewHub([]byte(statusTestToken), mustChunkStore(t, "", 0, 0))
	t.Cleanup(hub.Close)
	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	t.Cleanup(server.Close)
	return hub, "ws" + strings.TrimPrefix(server.URL, "http")
}

// dialStatusPeer connects one authenticated client and closes it when the
// test ends.
func dialStatusPeer(t *testing.T, wsBase, deviceID string) *websocket.Conn {
	t.Helper()
	url := wsBase + "?device_id=" + deviceID + "&name=" + deviceID + "&token=" + statusTestToken
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("dial %s: %v", deviceID, err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

// peersEntry is one peer object from a peers broadcast, kept as raw JSON so
// tests can distinguish an omitted status key from an empty string.
type peersEntry map[string]interface{}

func decodePeersFrame(data []byte) ([]peersEntry, bool) {
	var env struct {
		Type  string       `json:"type"`
		Peers []peersEntry `json:"peers"`
	}
	if err := json.Unmarshal(data, &env); err != nil || env.Type != "peers" {
		return nil, false
	}
	return env.Peers, true
}

func findPeerEntry(entries []peersEntry, id string) (peersEntry, bool) {
	for _, entry := range entries {
		if entry["id"] == id {
			return entry, true
		}
	}
	return nil, false
}

// readPeersUntil reads frames until a peers broadcast satisfies pred and
// returns its entries, skipping pongs and non-matching broadcasts.
func readPeersUntil(t *testing.T, conn *websocket.Conn, pred func([]peersEntry) bool, timeout time.Duration) []peersEntry {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		entries, ok := decodePeersFrame(data)
		if ok && pred(entries) {
			return entries
		}
	}
}

// readUntilPong reads frames until a pong, returning every peers broadcast
// seen on the way. It proves the read loop is still alive after a frame.
func readUntilPong(t *testing.T, conn *websocket.Conn, timeout time.Duration) [][]peersEntry {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	var seen [][]peersEntry
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		var env struct {
			Type string `json:"type"`
		}
		if err := json.Unmarshal(data, &env); err != nil {
			continue
		}
		switch env.Type {
		case "pong":
			return seen
		case "peers":
			if entries, ok := decodePeersFrame(data); ok {
				seen = append(seen, entries)
			}
		}
	}
}

// peerStatus reads one peer's stored status under the hub mutex.
func peerStatus(hub *Hub, deviceID string) string {
	hub.mu.RLock()
	defer hub.mu.RUnlock()
	if peer, ok := hub.peers[deviceID]; ok {
		return peer.status
	}
	return ""
}

func statusFrame(status interface{}) map[string]interface{} {
	return map[string]interface{}{"type": "status", "v": 1, "status": status}
}

// TestPeerStatusStoredAndBroadcast covers the happy path: a status frame is
// stored on the sender and every connected peer sees the blob in the next
// peers broadcast, while peers that never sent one omit the key entirely.
func TestPeerStatusStoredAndBroadcast(t *testing.T) {
	hub, wsBase := newStatusTestServer(t)
	connA := dialStatusPeer(t, wsBase, "device-a")
	connB := dialStatusPeer(t, wsBase, "device-b")
	waitForPeerCount(t, hub, 2)

	blob := "q83vAAECAwQFBgcICQoLDA0ODw=="
	if err := connA.WriteJSON(statusFrame(blob)); err != nil {
		t.Fatalf("send status: %v", err)
	}

	for _, tc := range []struct {
		name string
		conn *websocket.Conn
	}{
		{name: "sender", conn: connA},
		{name: "observer", conn: connB},
	} {
		t.Run(tc.name, func(t *testing.T) {
			entries := readPeersUntil(t, tc.conn, func(es []peersEntry) bool {
				entry, ok := findPeerEntry(es, "device-a")
				return ok && entry["status"] == blob
			}, 2*time.Second)

			entryA, ok := findPeerEntry(entries, "device-a")
			if !ok {
				t.Fatal("device-a missing from peers broadcast")
			}
			if got := entryA["status"]; got != blob {
				t.Fatalf("device-a status = %v, want %q", got, blob)
			}
			entryB, ok := findPeerEntry(entries, "device-b")
			if !ok {
				t.Fatal("device-b missing from peers broadcast")
			}
			if got, present := entryB["status"]; present {
				t.Fatalf("device-b has a status key it never sent: %v", got)
			}
		})
	}

	if got := peerStatus(hub, "device-a"); got != blob {
		t.Fatalf("stored status = %q, want %q", got, blob)
	}
	if got := peerStatus(hub, "device-b"); got != "" {
		t.Fatalf("device-b stored status = %q, want empty", got)
	}
}

// TestPeerStatusFrameValidation is the table-driven shape check: invalid
// frames are dropped without a broadcast and without disconnecting, valid
// frames are stored and broadcast.
func TestPeerStatusFrameValidation(t *testing.T) {
	validMax := strings.Repeat("A", maxStatusBlobBytes)

	cases := []struct {
		name          string
		frame         map[string]interface{}
		wantBroadcast bool
		wantStatus    string
	}{
		{
			name:          "valid short",
			frame:         statusFrame("AQID"),
			wantBroadcast: true,
			wantStatus:    "AQID",
		},
		{
			name:          "valid max length",
			frame:         statusFrame(validMax),
			wantBroadcast: true,
			wantStatus:    validMax,
		},
		{
			name:       "empty status",
			frame:      statusFrame(""),
			wantStatus: "",
		},
		{
			name:       "oversized by one",
			frame:      statusFrame(strings.Repeat("A", maxStatusBlobBytes+1)),
			wantStatus: "",
		},
		{
			name:       "non-base64 character",
			frame:      statusFrame("AQID@@@"),
			wantStatus: "",
		},
		{
			name:       "embedded space",
			frame:      statusFrame("AQ ID"),
			wantStatus: "",
		},
		{
			name:       "url-safe alphabet",
			frame:      statusFrame("AQID-_8="),
			wantStatus: "",
		},
		{
			name:       "missing status field",
			frame:      map[string]interface{}{"type": "status", "v": 1},
			wantStatus: "",
		},
		{
			name:       "non-string status",
			frame:      statusFrame(42),
			wantStatus: "",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			hub, wsBase := newStatusTestServer(t)
			conn := dialStatusPeer(t, wsBase, "device-a")
			waitForPeerCount(t, hub, 1)

			// Drain the join broadcast so any later peers frame can only
			// come from the status frame under test.
			readPeersUntil(t, conn, func([]peersEntry) bool { return true }, 2*time.Second)

			if err := conn.WriteJSON(tc.frame); err != nil {
				t.Fatalf("send status frame: %v", err)
			}
			if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
				t.Fatalf("send ping: %v", err)
			}

			seen := readUntilPong(t, conn, 2*time.Second)
			if tc.wantBroadcast {
				if len(seen) != 1 {
					t.Fatalf("peers broadcasts = %d, want 1", len(seen))
				}
				entry, ok := findPeerEntry(seen[0], "device-a")
				if !ok {
					t.Fatal("device-a missing from status broadcast")
				}
				if got := entry["status"]; got != tc.wantStatus {
					t.Fatalf("broadcast status = %v, want %q", got, tc.wantStatus)
				}
			} else if len(seen) != 0 {
				t.Fatalf("invalid frame produced %d peers broadcast(s), want 0", len(seen))
			}

			if got := peerStatus(hub, "device-a"); got != tc.wantStatus {
				t.Fatalf("stored status = %q, want %q", got, tc.wantStatus)
			}
		})
	}
}

// TestPeerStatusClearedOnLeave proves the status vanishes with the peer:
// the leave broadcast carries no status for the departed device and no
// stale blob for anyone else.
func TestPeerStatusClearedOnLeave(t *testing.T) {
	hub, wsBase := newStatusTestServer(t)
	connA := dialStatusPeer(t, wsBase, "device-a")
	connB := dialStatusPeer(t, wsBase, "device-b")
	waitForPeerCount(t, hub, 2)

	blob := "bGVhdmUtdGVzdA=="
	if err := connA.WriteJSON(statusFrame(blob)); err != nil {
		t.Fatalf("send status: %v", err)
	}
	readPeersUntil(t, connB, func(es []peersEntry) bool {
		entry, ok := findPeerEntry(es, "device-a")
		return ok && entry["status"] == blob
	}, 2*time.Second)

	if err := connA.Close(); err != nil {
		t.Fatalf("close device-a: %v", err)
	}

	entries := readPeersUntil(t, connB, func(es []peersEntry) bool {
		_, present := findPeerEntry(es, "device-a")
		return !present
	}, 3*time.Second)

	for _, entry := range entries {
		if got, present := entry["status"]; present {
			t.Fatalf("leave broadcast still carries status %v for %v", got, entry["id"])
		}
	}
	if got := peerStatus(hub, "device-a"); got != "" {
		t.Fatalf("departed device still has stored status %q", got)
	}
}

// TestPeerStatusNotCarriedAcrossReconnect proves a same-device reconnect
// starts from an empty status and never inherits the old connection's blob.
func TestPeerStatusNotCarriedAcrossReconnect(t *testing.T) {
	hub, wsBase := newStatusTestServer(t)
	observer := dialStatusPeer(t, wsBase, "device-b")
	connA := dialStatusPeer(t, wsBase, "device-a")
	waitForPeerCount(t, hub, 2)

	blob := "cmVjb25uZWN0LXRlc3Q="
	if err := connA.WriteJSON(statusFrame(blob)); err != nil {
		t.Fatalf("send status: %v", err)
	}
	readPeersUntil(t, observer, func(es []peersEntry) bool {
		entry, ok := findPeerEntry(es, "device-a")
		return ok && entry["status"] == blob
	}, 2*time.Second)

	// Same device id reconnects; the relay replaces the old connection.
	connA2 := dialStatusPeer(t, wsBase, "device-a")
	waitForPeerCount(t, hub, 2)

	entries := readPeersUntil(t, observer, func(es []peersEntry) bool {
		entry, ok := findPeerEntry(es, "device-a")
		if !ok {
			return false
		}
		_, present := entry["status"]
		return !present
	}, 3*time.Second)

	entryA, ok := findPeerEntry(entries, "device-a")
	if !ok {
		t.Fatal("device-a missing from reconnect broadcast")
	}
	if got, present := entryA["status"]; present {
		t.Fatalf("reconnected device inherited status %v", got)
	}
	if got := peerStatus(hub, "device-a"); got != "" {
		t.Fatalf("reconnected device stored status = %q, want empty", got)
	}

	// The replacement connection is the live one.
	if err := connA2.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("ping replacement connection: %v", err)
	}
	readUntilPong(t, connA2, 2*time.Second)
}

// TestPeerStatusRaceWithBroadcasts is the regression test for the locking
// rule: status updates (from the WS read path and directly through the hub)
// churn while another goroutine snapshots and broadcasts the peer list. Run
// under -race; a bare field write fails here.
func TestPeerStatusRaceWithBroadcasts(t *testing.T) {
	hub, wsBase := newStatusTestServer(t)
	connA := dialStatusPeer(t, wsBase, "device-a")
	connB := dialStatusPeer(t, wsBase, "device-b")
	waitForPeerCount(t, hub, 2)

	// Drain both clients so relay writes never back up and block churn.
	var drainWG sync.WaitGroup
	for _, conn := range []*websocket.Conn{connA, connB} {
		drainWG.Add(1)
		go func(conn *websocket.Conn) {
			defer drainWG.Done()
			for {
				if _, _, err := conn.ReadMessage(); err != nil {
					return
				}
			}
		}(conn)
	}

	const iterations = 200
	var churnWG sync.WaitGroup
	churnWG.Add(3)

	// Status frames over the real WS path.
	go func() {
		defer churnWG.Done()
		for i := 0; i < iterations; i++ {
			blob := base64.StdEncoding.EncodeToString([]byte(fmt.Sprintf("status-%d", i)))
			if err := connA.WriteJSON(statusFrame(blob)); err != nil {
				return
			}
		}
	}()

	// Direct hub writes, interleaved with the WS path.
	go func() {
		defer churnWG.Done()
		for i := 0; i < iterations; i++ {
			hub.setPeerStatus("device-a", "AQID")
			hub.setPeerStatus("device-b", "BAUG")
		}
	}()

	// Snapshot and broadcast readers.
	go func() {
		defer churnWG.Done()
		for i := 0; i < iterations; i++ {
			if _, peers := hub.peersSnapshot(); len(peers) != 2 {
				return
			}
			hub.broadcastPeerList()
		}
	}()

	churnWG.Wait()
	_ = connA.Close()
	_ = connB.Close()
	drainWG.Wait()
}

// shrinkStatusInterval lowers the status rate window for one test and
// restores it when the test ends. Call it before newStatusTestServer: each
// Peer copies the value at join time, so no running handler reads the
// package var afterwards, and the restore cleanup (registered first) runs
// after the server and connection cleanups.
func shrinkStatusInterval(t *testing.T, d time.Duration) {
	t.Helper()
	orig := statusMinInterval
	statusMinInterval = d
	t.Cleanup(func() { statusMinInterval = orig })
}

// drainPeersFrames sends a ping and reads to its pong, discarding the peers
// broadcasts already queued (join/leave traffic) so later reads can only see
// frames caused by the test's own status traffic.
func drainPeersFrames(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send drain ping: %v", err)
	}
	_ = readUntilPong(t, conn, 2*time.Second)
}

// lockedBuffer is a log sink that is safe to read while relay goroutines
// write to it.
type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// TestPeerStatusRateLimitDropsRapidFrames proves a burst of valid status
// frames yields exactly one accepted update, and therefore exactly one peers
// broadcast to every peer, with the stored blob matching the first frame.
func TestPeerStatusRateLimitDropsRapidFrames(t *testing.T) {
	// A long window makes "only the first frame is accepted" deterministic
	// even if the test goroutine is descheduled mid-burst.
	shrinkStatusInterval(t, time.Hour)

	hub, wsBase := newStatusTestServer(t)
	connA := dialStatusPeer(t, wsBase, "device-a")
	connB := dialStatusPeer(t, wsBase, "device-b")
	waitForPeerCount(t, hub, 2)

	// Drain the later joiner first: its join broadcast is written before it
	// can answer a ping, so by the time its pong arrives that broadcast is
	// already queued on device-a's stream too. Draining device-a afterwards
	// then consumes every join frame ahead of its pong.
	drainPeersFrames(t, connB)
	drainPeersFrames(t, connA)

	blobs := []string{"AQID", "BAUG", "BwgJ", "CgsM", "DQ4P"}
	for _, blob := range blobs {
		if err := connA.WriteJSON(statusFrame(blob)); err != nil {
			t.Fatalf("send status %q: %v", blob, err)
		}
	}
	if err := connA.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send ping: %v", err)
	}

	seenA := readUntilPong(t, connA, 2*time.Second)
	if len(seenA) != 1 {
		t.Fatalf("sender saw %d peers broadcast(s), want 1", len(seenA))
	}
	entry, ok := findPeerEntry(seenA[0], "device-a")
	if !ok {
		t.Fatal("device-a missing from status broadcast")
	}
	if got := entry["status"]; got != blobs[0] {
		t.Fatalf("broadcast status = %v, want first blob %q", got, blobs[0])
	}

	// The observer sees the same single broadcast no matter how many frames
	// the sender emitted.
	if err := connB.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send observer ping: %v", err)
	}
	seenB := readUntilPong(t, connB, 2*time.Second)
	if len(seenB) != 1 {
		t.Fatalf("observer saw %d peers broadcast(s), want 1", len(seenB))
	}
	if got := peerStatus(hub, "device-a"); got != blobs[0] {
		t.Fatalf("stored status = %q, want first blob %q", got, blobs[0])
	}
}

// TestPeerStatusRateLimitIsPerDevice proves one peer's burst does not consume
// another peer's budget.
func TestPeerStatusRateLimitIsPerDevice(t *testing.T) {
	shrinkStatusInterval(t, time.Hour)

	hub, wsBase := newStatusTestServer(t)
	connA := dialStatusPeer(t, wsBase, "device-a")
	connB := dialStatusPeer(t, wsBase, "device-b")
	waitForPeerCount(t, hub, 2)
	drainPeersFrames(t, connB)
	drainPeersFrames(t, connA)

	// device-a burns its window.
	if err := connA.WriteJSON(statusFrame("AQID")); err != nil {
		t.Fatalf("send device-a status: %v", err)
	}
	if err := connA.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send device-a ping: %v", err)
	}
	if seen := readUntilPong(t, connA, 2*time.Second); len(seen) != 1 {
		t.Fatalf("device-a frame produced %d peers broadcast(s), want 1", len(seen))
	}

	// Consume device-a's broadcast on device-b's stream so the next read can
	// only see device-b's own status frame.
	drainPeersFrames(t, connB)

	// device-b's first frame in the same wall-clock window is still accepted.
	if err := connB.WriteJSON(statusFrame("BAUG")); err != nil {
		t.Fatalf("send device-b status: %v", err)
	}
	if err := connB.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send device-b ping: %v", err)
	}
	seen := readUntilPong(t, connB, 2*time.Second)
	if len(seen) != 1 {
		t.Fatalf("device-b frame produced %d peers broadcast(s), want 1", len(seen))
	}
	entry, ok := findPeerEntry(seen[0], "device-b")
	if !ok {
		t.Fatal("device-b missing from its own status broadcast")
	}
	if got := entry["status"]; got != "BAUG" {
		t.Fatalf("device-b broadcast status = %v, want %q", got, "BAUG")
	}
	if got := peerStatus(hub, "device-b"); got != "BAUG" {
		t.Fatalf("device-b stored status = %q, want %q", got, "BAUG")
	}
}

// TestPeerStatusRateLimitWindowExpiry proves the window is time-based: a
// frame inside the window is dropped, and the next frame after it elapses is
// accepted and broadcast.
func TestPeerStatusRateLimitWindowExpiry(t *testing.T) {
	const window = 100 * time.Millisecond
	shrinkStatusInterval(t, window)

	hub, wsBase := newStatusTestServer(t)
	conn := dialStatusPeer(t, wsBase, "device-a")
	waitForPeerCount(t, hub, 1)
	drainPeersFrames(t, conn)

	const first, second = "AQID", "BAUG"
	if err := conn.WriteJSON(statusFrame(first)); err != nil {
		t.Fatalf("send first status: %v", err)
	}
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send ping: %v", err)
	}
	if seen := readUntilPong(t, conn, 2*time.Second); len(seen) != 1 {
		t.Fatalf("first frame produced %d peers broadcast(s), want 1", len(seen))
	}

	// Still inside the window: dropped with no broadcast.
	if err := conn.WriteJSON(statusFrame(second)); err != nil {
		t.Fatalf("send early second status: %v", err)
	}
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send ping: %v", err)
	}
	if seen := readUntilPong(t, conn, 2*time.Second); len(seen) != 0 {
		t.Fatalf("frame inside window produced %d peers broadcast(s), want 0", len(seen))
	}
	if got := peerStatus(hub, "device-a"); got != first {
		t.Fatalf("stored status inside window = %q, want %q", got, first)
	}

	// After the window: accepted and broadcast.
	time.Sleep(3 * window)
	if err := conn.WriteJSON(statusFrame(second)); err != nil {
		t.Fatalf("send late second status: %v", err)
	}
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send ping: %v", err)
	}
	seen := readUntilPong(t, conn, 2*time.Second)
	if len(seen) != 1 {
		t.Fatalf("frame after window produced %d peers broadcast(s), want 1", len(seen))
	}
	entry, ok := findPeerEntry(seen[0], "device-a")
	if !ok {
		t.Fatal("device-a missing from post-window broadcast")
	}
	if got := entry["status"]; got != second {
		t.Fatalf("post-window broadcast status = %v, want %q", got, second)
	}
	if got := peerStatus(hub, "device-a"); got != second {
		t.Fatalf("stored status after window = %q, want %q", got, second)
	}
}

// TestPeerStatusRateLimitKeepsConnectionAlive proves rate-limited frames are
// dropped silently: the peer is not disconnected, pings still get pongs, and
// list_peers is still answered.
func TestPeerStatusRateLimitKeepsConnectionAlive(t *testing.T) {
	shrinkStatusInterval(t, time.Hour)

	hub, wsBase := newStatusTestServer(t)
	conn := dialStatusPeer(t, wsBase, "device-a")
	waitForPeerCount(t, hub, 1)
	drainPeersFrames(t, conn)

	const burst = 64
	for i := 0; i < burst; i++ {
		if err := conn.WriteJSON(statusFrame("AQID")); err != nil {
			t.Fatalf("send status %d: %v", i, err)
		}
	}
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send ping: %v", err)
	}
	if seen := readUntilPong(t, conn, 2*time.Second); len(seen) != 1 {
		t.Fatalf("burst produced %d peers broadcast(s), want 1", len(seen))
	}

	// The read loop is still serving the connection.
	if err := conn.WriteJSON(map[string]string{"type": "list_peers"}); err != nil {
		t.Fatalf("send list_peers: %v", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read list_peers reply: %v", err)
		}
		if _, ok := decodePeersFrame(data); ok {
			break
		}
	}
	if got := hub.peerCount(); got != 1 {
		t.Fatalf("peerCount after rate limiting = %d, want 1", got)
	}

	// A later ping still gets a pong and produces no extra broadcast.
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send follow-up ping: %v", err)
	}
	if seen := readUntilPong(t, conn, 2*time.Second); len(seen) != 0 {
		t.Fatalf("follow-up ping produced %d peers broadcast(s), want 0", len(seen))
	}
}

// TestPeerStatusRateLimitLogsOncePerBurst proves the drop log is bounded: one
// line for a whole burst, and one more for a new burst after the window.
func TestPeerStatusRateLimitLogsOncePerBurst(t *testing.T) {
	const window = 300 * time.Millisecond
	shrinkStatusInterval(t, window)

	var logs lockedBuffer
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(os.Stderr) })

	hub, wsBase := newStatusTestServer(t)
	conn := dialStatusPeer(t, wsBase, "device-a")
	waitForPeerCount(t, hub, 1)
	drainPeersFrames(t, conn)

	const want = "[STATUS] rate-limited device-a"

	// First burst: the first frame is accepted, the rest are dropped.
	for i := 0; i < 50; i++ {
		if err := conn.WriteJSON(statusFrame("AQID")); err != nil {
			t.Fatalf("send status %d: %v", i, err)
		}
	}
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send ping: %v", err)
	}
	readUntilPong(t, conn, 2*time.Second)
	if n := strings.Count(logs.String(), want); n != 1 {
		t.Fatalf("rate-limit log lines after first burst = %d, want 1", n)
	}

	// A new burst after the window logs once more, not once per frame.
	time.Sleep(2 * window)
	if err := conn.WriteJSON(statusFrame("BAUG")); err != nil {
		t.Fatalf("send accepted status: %v", err)
	}
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send ping: %v", err)
	}
	readUntilPong(t, conn, 2*time.Second)
	for i := 0; i < 50; i++ {
		if err := conn.WriteJSON(statusFrame("BwgJ")); err != nil {
			t.Fatalf("send status %d: %v", i, err)
		}
	}
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send ping: %v", err)
	}
	readUntilPong(t, conn, 2*time.Second)
	if n := strings.Count(logs.String(), want); n != 2 {
		t.Fatalf("rate-limit log lines after second burst = %d, want 2", n)
	}
}

// TestPeerStatusRateLimitRaceUnderChurn hammers the rate-limited status path
// from two connections while direct hub writes and peer-list broadcasts
// churn. Run under -race: lastStatusAt/statusDropLogged must stay under the
// hub mutex.
func TestPeerStatusRateLimitRaceUnderChurn(t *testing.T) {
	shrinkStatusInterval(t, 10*time.Millisecond)

	hub, wsBase := newStatusTestServer(t)
	connA := dialStatusPeer(t, wsBase, "device-a")
	connB := dialStatusPeer(t, wsBase, "device-b")
	waitForPeerCount(t, hub, 2)

	// Drain both clients so relay writes never back up and block churn.
	var drainWG sync.WaitGroup
	for _, conn := range []*websocket.Conn{connA, connB} {
		drainWG.Add(1)
		go func(conn *websocket.Conn) {
			defer drainWG.Done()
			for {
				if _, _, err := conn.ReadMessage(); err != nil {
					return
				}
			}
		}(conn)
	}

	const iterations = 150
	var churnWG sync.WaitGroup
	churnWG.Add(4)

	// Status frames over the real WS path, from both peers.
	for _, conn := range []*websocket.Conn{connA, connB} {
		go func(conn *websocket.Conn) {
			defer churnWG.Done()
			for i := 0; i < iterations; i++ {
				blob := base64.StdEncoding.EncodeToString([]byte(fmt.Sprintf("status-%d", i)))
				if err := conn.WriteJSON(statusFrame(blob)); err != nil {
					return
				}
			}
		}(conn)
	}

	// Direct hub writes, interleaved with the WS path.
	go func() {
		defer churnWG.Done()
		for i := 0; i < iterations; i++ {
			hub.setPeerStatus("device-a", "AQID")
			hub.setPeerStatus("device-b", "BAUG")
		}
	}()

	// Snapshot and broadcast readers.
	go func() {
		defer churnWG.Done()
		for i := 0; i < iterations; i++ {
			if _, peers := hub.peersSnapshot(); len(peers) != 2 {
				return
			}
			hub.broadcastPeerList()
		}
	}()

	churnWG.Wait()
	_ = connA.Close()
	_ = connB.Close()
	drainWG.Wait()
}
