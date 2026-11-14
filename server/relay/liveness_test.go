package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// setWSLiveness shrinks the WebSocket liveness windows for this test and
// restores them when it ends. The values are copied into each Peer at
// join time, so no running connection ever reads the vars again.
func setWSLiveness(t *testing.T, ping, read, write time.Duration) {
	t.Helper()
	origPing, origRead, origWrite := wsPingInterval, wsReadWindow, wsWriteTimeout
	wsPingInterval, wsReadWindow, wsWriteTimeout = ping, read, write
	t.Cleanup(func() {
		wsPingInterval, wsReadWindow, wsWriteTimeout = origPing, origRead, origWrite
	})
}

// waitForPeerCount polls the hub until it holds want peers.
func waitForPeerCount(t *testing.T, hub *Hub, want int) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for hub.peerCount() != want && time.Now().Before(deadline) {
		time.Sleep(2 * time.Millisecond)
	}
	if got := hub.peerCount(); got != want {
		t.Fatalf("peerCount = %d, want %d", got, want)
	}
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// peerListHas reports whether data is a peers broadcast listing id.
func peerListHas(data []byte, id string) bool {
	var env struct {
		Type  string     `json:"type"`
		Peers []PeerInfo `json:"peers"`
	}
	if err := json.Unmarshal(data, &env); err != nil || env.Type != "peers" {
		return false
	}
	for _, p := range env.Peers {
		if p.ID == id {
			return true
		}
	}
	return false
}

// isPeerList reports whether data is a peers broadcast.
func isPeerList(data []byte) bool {
	var env struct {
		Type string `json:"type"`
	}
	return json.Unmarshal(data, &env) == nil && env.Type == "peers"
}

// readUntil reads from conn until pred matches a frame, skipping
// non-matching ones (peers broadcasts, pongs).
func readUntil(t *testing.T, conn *websocket.Conn, pred func([]byte) bool, timeout time.Duration) []byte {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		if pred(data) {
			return data
		}
	}
}

// TestWSDropsSilentClient covers REL-2 (a): a client that connects,
// authenticates, then neither reads nor answers pings must be dropped
// within the read window instead of staying "online" forever, and the
// leave must be broadcast to the remaining peers.
func TestWSDropsSilentClient(t *testing.T) {
	setWSLiveness(t, 30*time.Millisecond, 150*time.Millisecond, time.Second)
	hub := NewHub([]byte("liveness-token"), mustChunkStore(t, "", 0, 0))
	defer hub.Close()

	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")

	// Observer: a normal client that keeps reading the whole time, so it
	// auto-pongs the relay's pings and records every broadcast.
	observer, _, err := websocket.DefaultDialer.Dial(wsBase+"?device_id=observer&token=liveness-token", nil)
	if err != nil {
		t.Fatalf("dial observer: %v", err)
	}
	defer observer.Close()
	var obsMu sync.Mutex
	var obsMsgs [][]byte
	go func() {
		for {
			_ = observer.SetReadDeadline(time.Now().Add(5 * time.Second))
			_, data, err := observer.ReadMessage()
			if err != nil {
				return // connection closed; the test is over
			}
			obsMu.Lock()
			obsMsgs = append(obsMsgs, data)
			obsMu.Unlock()
		}
	}()

	// Silent client: swallows pings without ponging and never reads.
	silent, _, err := websocket.DefaultDialer.Dial(wsBase+"?device_id=silent-client&token=liveness-token", nil)
	if err != nil {
		t.Fatalf("dial silent client: %v", err)
	}
	silent.SetPingHandler(func(string) error { return nil })
	defer silent.Close()

	waitForPeerCount(t, hub, 2)

	// The read deadline must expire within the shrunken window.
	deadline := time.Now().Add(3 * time.Second)
	for hub.peerCount() != 1 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if got := hub.peerCount(); got != 1 {
		t.Fatalf("silent client still online after read window; peerCount = %d, want 1", got)
	}

	// The relay closed the silent client's connection: drain the few
	// queued broadcasts, then the read must fail with a close error
	// rather than a timeout (which would mean the conn is still open).
	_ = silent.SetReadDeadline(time.Now().Add(2 * time.Second))
	for {
		_, _, err := silent.ReadMessage()
		if err == nil {
			continue
		}
		if isTimeout(err) {
			t.Fatal("relay did not close the silent client's connection")
		}
		break
	}

	// The leave was broadcast: the observer saw a peers list without
	// the silent device.
	deadline = time.Now().Add(2 * time.Second)
	sawLeave := false
	for time.Now().Before(deadline) && !sawLeave {
		obsMu.Lock()
		for _, data := range obsMsgs {
			if isPeerList(data) && !peerListHas(data, "silent-client") {
				sawLeave = true
				break
			}
		}
		obsMu.Unlock()
		time.Sleep(5 * time.Millisecond)
	}
	if !sawLeave {
		t.Fatal("the leave of the silent client was never broadcast")
	}
}

// TestWSKeepsPongingClient covers REL-2 (b): a client that keeps
// answering pings stays connected across several read windows.
func TestWSKeepsPongingClient(t *testing.T) {
	setWSLiveness(t, 30*time.Millisecond, 150*time.Millisecond, time.Second)
	hub := NewHub([]byte("liveness-token"), mustChunkStore(t, "", 0, 0))
	defer hub.Close()

	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")

	conn, _, err := websocket.DefaultDialer.Dial(wsBase+"?device_id=alive-client&token=liveness-token", nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	waitForPeerCount(t, hub, 1)

	// The connection is alive: an app-level ping is answered.
	if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
		t.Fatalf("send ping: %v", err)
	}
	readUntil(t, conn, func(data []byte) bool {
		var env struct {
			Type string `json:"type"`
		}
		return json.Unmarshal(data, &env) == nil && env.Type == "pong"
	}, 2*time.Second)

	// Block in a single read for several read windows. The relay pings
	// every 30ms; the default handler auto-pongs each one, and the pong
	// handler extends the relay's read deadline. If the relay dropped a
	// ponging client, this read would end early with a close error
	// instead of the 600ms deadline expiry.
	_ = conn.SetReadDeadline(time.Now().Add(600 * time.Millisecond))
	_, _, err = conn.ReadMessage()
	if err == nil {
		t.Fatal("read returned a frame; expected the deadline expiry")
	}
	if !isTimeout(err) {
		t.Fatalf("ponging client was dropped by the relay: %v", err)
	}
	if got := hub.peerCount(); got != 1 {
		t.Fatalf("ponging client was dropped: peerCount = %d, want 1", got)
	}
}

// TestWSWriteTimeoutUnblocksWriter covers REL-2 (c): once a peer stops
// reading and its socket fills, concurrent writers fail within the write
// timeout instead of blocking forever while holding peer.writeMu.
func TestWSWriteTimeoutUnblocksWriter(t *testing.T) {
	// Only the write timeout is short: the client stays "present" so the
	// failing writes are purely deadline-driven.
	setWSLiveness(t, time.Hour, time.Hour, 100*time.Millisecond)
	hub := NewHub([]byte("liveness-token"), mustChunkStore(t, "", 0, 0))
	defer hub.Close()

	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")

	// A client with a minimal receive buffer that never reads: once the
	// relay has filled the send buffer, every further write must fail
	// within the write timeout.
	dialer := &websocket.Dialer{
		NetDial: func(network, addr string) (net.Conn, error) {
			c, err := net.Dial(network, addr)
			if err != nil {
				return nil, err
			}
			if tc, ok := c.(*net.TCPConn); ok {
				_ = tc.SetReadBuffer(4096) // disables receive autotuning
			}
			return c, nil
		},
	}
	stalled, _, err := dialer.Dial(wsBase+"?device_id=stalled-client&token=liveness-token", nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer stalled.Close()
	waitForPeerCount(t, hub, 1)

	hub.mu.RLock()
	peer := hub.peers["stalled-client"]
	hub.mu.RUnlock()
	if peer == nil {
		t.Fatal("stalled peer not registered")
	}

	// Far larger than any TCP send/receive buffer, so the write cannot
	// complete once the socket is full.
	payload := bytes.Repeat([]byte("w"), 32<<20)

	type result struct {
		err      error
		maxWrite time.Duration
	}
	results := make(chan result, 2)
	for i := 0; i < 2; i++ {
		go func() {
			var maxWrite time.Duration
			var werr error
			for n := 0; n < 4 && werr == nil; n++ {
				begin := time.Now()
				werr = peer.writeMessage(websocket.TextMessage, payload)
				if d := time.Since(begin); d > maxWrite {
					maxWrite = d
				}
			}
			results <- result{err: werr, maxWrite: maxWrite}
		}()
	}

	bound := wsWriteTimeout + 2*time.Second
	for i := 0; i < 2; i++ {
		select {
		case res := <-results:
			if res.err == nil {
				t.Fatal("writes to a stalled client kept succeeding; the socket never filled")
			}
			if res.maxWrite > bound {
				t.Fatalf("write blocked for %v, past the %v bound", res.maxWrite, bound)
			}
		case <-time.After(bound + 3*time.Second):
			t.Fatalf("concurrent writer blocked past the write timeout")
		}
	}
}
