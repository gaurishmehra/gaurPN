package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestWSRejectsInvalidIdentity(t *testing.T) {
	hub := NewHub([]byte("secret"), mustChunkStore(t, "", 0, 0))
	defer hub.Close()

	badDeviceIDs := []string{
		"",
		"has space",
		"device/1",
		"device:1",
		"device#1",
		"ümlaut",
		"device\ttab",
		strings.Repeat("a", 65),
	}
	for _, id := range badDeviceIDs {
		req := httptest.NewRequest(http.MethodGet, "/ws?device_id="+url.QueryEscape(id)+"&token=secret", nil)
		rec := httptest.NewRecorder()
		hub.handleWS(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("device_id %q: status = %d, want 400", id, rec.Code)
		}
	}

	// A 129-byte name is rejected.
	req := httptest.NewRequest(http.MethodGet, "/ws?device_id=good-device&name="+url.QueryEscape(strings.Repeat("n", maxDeviceNameBytes+1))+"&token=secret", nil)
	rec := httptest.NewRecorder()
	hub.handleWS(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("129-byte name: status = %d, want 400", rec.Code)
	}

	// A maximal valid identity (64-char device_id, 128-byte name) joins
	// through a real WebSocket handshake. A recorder cannot be used here:
	// the upgrader also answers 400 when the handshake headers are absent.
	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	defer server.Close()
	wsURL := "ws" + strings.TrimPrefix(server.URL, "http") +
		"?device_id=" + strings.Repeat("d", 64) +
		"&name=" + url.QueryEscape(strings.Repeat("n", maxDeviceNameBytes)) +
		"&token=secret"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("valid identity rejected: %v", err)
	}
	conn.Close()
}

func TestMaxPeersCap(t *testing.T) {
	hub := NewHub([]byte("secret"), mustChunkStore(t, "", 0, 0))
	hub.SetMaxPeers(2)
	defer hub.Close()

	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")

	var conns []*websocket.Conn
	defer func() {
		for _, c := range conns {
			c.Close()
		}
	}()

	// Fill the cluster to the cap.
	for i := 0; i < 2; i++ {
		conn, _, err := websocket.DefaultDialer.Dial(wsBase+fmt.Sprintf("?device_id=device-%d&token=secret", i), nil)
		if err != nil {
			t.Fatalf("dial device-%d: %v", i, err)
		}
		conns = append(conns, conn)
	}
	deadline := time.Now().Add(2 * time.Second)
	for hub.peerCount() < 2 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if hub.peerCount() != 2 {
		t.Fatalf("peerCount = %d after joins, want 2", hub.peerCount())
	}

	// A third distinct device is rejected with 403 before the upgrade.
	req := httptest.NewRequest(http.MethodGet, "/ws?device_id=device-third&token=secret", nil)
	rec := httptest.NewRecorder()
	hub.handleWS(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("third distinct device: status = %d, want 403", rec.Code)
	}

	// An existing device may still reconnect at cap (it replaces its old
	// connection rather than adding a peer).
	conn, _, err := websocket.DefaultDialer.Dial(wsBase+"?device_id=device-0&token=secret", nil)
	if err != nil {
		t.Fatalf("existing device reconnect at cap: %v", err)
	}
	conns = append(conns, conn)

	deadline = time.Now().Add(2 * time.Second)
	for hub.peerCount() != 2 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if got := hub.peerCount(); got != 2 {
		t.Fatalf("peerCount = %d after reconnect, want 2", got)
	}
}

func TestAuthFailureThrottle(t *testing.T) {
	hub := NewHub([]byte("secret"), mustChunkStore(t, "", 0, 0))
	defer hub.Close()

	attempt := func(ip, token string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPut, "/chunk?transfer_id=tx&session_id=s&index=0", strings.NewReader("x"))
		req.Header.Set("X-Gaur-Token", token)
		req.RemoteAddr = ip + ":5555"
		rec := httptest.NewRecorder()
		hub.handleChunkPut(rec, req)
		return rec
	}

	// The first ten failed auths from one IP get 401.
	for i := 0; i < authFailureLimit; i++ {
		rec := attempt("192.0.2.10", "wrong-token")
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("failure %d: status = %d, want 401", i+1, rec.Code)
		}
	}

	// The eleventh failure (and anything else from that IP) is throttled.
	rec := attempt("192.0.2.10", "wrong-token")
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("eleventh failure: status = %d, want 429", rec.Code)
	}
	if got := rec.Header().Get("Retry-After"); got != "60" {
		t.Fatalf("Retry-After = %q, want 60", got)
	}
	rec = attempt("192.0.2.10", "secret")
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("good token from throttled IP: status = %d, want 429", rec.Code)
	}

	// A good token from another IP is unaffected.
	rec = attempt("192.0.2.20", "secret")
	if rec.Code != http.StatusOK {
		t.Fatalf("good token from other IP: status = %d, want 200", rec.Code)
	}
}

func TestAuthFailureWindowReset(t *testing.T) {
	hub := NewHub([]byte("secret"), mustChunkStore(t, "", 0, 0))
	defer hub.Close()

	// Shrink the window under the limiter's mutex so the test need not wait
	// a real minute.
	hub.limiter.mu.Lock()
	hub.limiter.window = 30 * time.Millisecond
	hub.limiter.mu.Unlock()

	attempt := func(token string) int {
		req := httptest.NewRequest(http.MethodPut, "/chunk?transfer_id=tx&session_id=s&index=0", strings.NewReader("x"))
		req.Header.Set("X-Gaur-Token", token)
		req.RemoteAddr = "192.0.2.30:5555"
		rec := httptest.NewRecorder()
		hub.handleChunkPut(rec, req)
		return rec.Code
	}

	for i := 0; i < authFailureLimit+1; i++ {
		if code := attempt("wrong-token"); code == http.StatusOK {
			t.Fatalf("bad token authenticated")
		}
	}
	if code := attempt("wrong-token"); code != http.StatusTooManyRequests {
		t.Fatalf("after %d failures: status = %d, want 429", authFailureLimit+1, code)
	}

	time.Sleep(60 * time.Millisecond)
	if code := attempt("wrong-token"); code != http.StatusUnauthorized {
		t.Fatalf("after window rollover: status = %d, want 401", code)
	}
}
