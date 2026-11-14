package main

import (
	"errors"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// TestGracefulShutdown covers REL-6: the shutdown path drains in-flight
// HTTP requests to completion instead of resetting them, stops the hub
// and chunk store exactly once, and lets the serve loop exit cleanly.
func TestGracefulShutdown(t *testing.T) {
	orig := shutdownTimeout
	shutdownTimeout = 2 * time.Second
	t.Cleanup(func() { shutdownTimeout = orig })

	chunks := mustChunkStore(t, "", 0, 0)
	hub := NewHub([]byte("shutdown-token"), chunks)
	defer hub.Close() // sync.Once: shutdownRelay already ran this

	mux := newRelayMux(hub)
	// A slow route stands in for an in-flight transfer so the test can
	// prove Shutdown waits for active requests.
	mux.HandleFunc("/slow", func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(150 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
	})

	srv := &http.Server{Handler: mux}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	base := "http://" + ln.Addr().String()
	serveErr := make(chan error, 1)
	go func() { serveErr <- srv.Serve(ln) }()

	// A live WebSocket peer and an in-flight request are both active
	// when the signal arrives.
	wsConn, _, err := websocket.DefaultDialer.Dial(
		"ws"+strings.TrimPrefix(base, "http")+"/ws?device_id=shutdown-peer&token=shutdown-token", nil)
	if err != nil {
		t.Fatalf("dial ws: %v", err)
	}
	defer wsConn.Close()

	type reply struct {
		code int
		err  error
	}
	inFlight := make(chan reply, 1)
	go func() {
		resp, err := http.Get(base + "/slow")
		if err != nil {
			inFlight <- reply{err: err}
			return
		}
		defer resp.Body.Close()
		inFlight <- reply{code: resp.StatusCode}
	}()
	time.Sleep(50 * time.Millisecond) // let the request enter its handler

	shutdownRelay(srv, hub, chunks)

	// The in-flight request completed cleanly instead of getting a RST.
	select {
	case res := <-inFlight:
		if res.err != nil {
			t.Fatalf("in-flight request failed: %v", res.err)
		}
		if res.code != http.StatusOK {
			t.Fatalf("in-flight request status = %d, want 200", res.code)
		}
	default:
		t.Fatal("in-flight request did not complete during shutdown")
	}

	// Background workers stopped exactly once.
	if !hub.Closed() {
		t.Fatal("hub.Close did not run")
	}
	select {
	case <-chunks.done:
	default:
		t.Fatal("chunks.Close did not run")
	}

	// The serve loop exits with ErrServerClosed once Shutdown closed the
	// listener.
	select {
	case err := <-serveErr:
		if !errors.Is(err, http.ErrServerClosed) {
			t.Fatalf("Serve error = %v, want ErrServerClosed", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Serve did not return after shutdown")
	}
}
