package main

import (
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// A receiver that disconnects mid-stream must end the sender's request.
// Before the receiver-side close, the sender blocked forever: its writes
// stalled once the pipe buffer filled, the session was never idle (Write
// touches it), and the sweep never reaped it. The pipe now closes with
// errReceiverGone the moment the receiver leaves, so the sender's next
// write errors and its request returns.
//
// The sender models a live streamer, not a one-shot upload: it writes
// continuously, because the sender only learns the pipe closed by failing
// a write — a silent body would stay parked inside the relay's body read
// and prove nothing.
func TestPipeReceiverDisconnectEndsSender(t *testing.T) {
	token := []byte("test-secret-token")
	hub := NewHub(token, mustChunkStore(t, "", 0, 0))
	t.Cleanup(hub.Close)

	srv := httptest.NewServer(http.HandlerFunc(hub.handlePipe))
	t.Cleanup(srv.Close)

	transferID := fmt.Sprintf("tx-teardown-%d", time.Now().UnixNano())
	senderURL := srv.URL + "/pipe?transfer_id=" + transferID + "&role=sender&token=test-secret-token"
	recvURL := srv.URL + "/pipe?transfer_id=" + transferID + "&role=receiver&token=test-secret-token"

	endless := struct{ io.Reader }{io.MultiReader(strings.NewReader("frame-zero"), neverEnding{})}

	senderDone := make(chan error, 1)
	go func() {
		req, err := http.NewRequest(http.MethodPost, senderURL, endless)
		if err != nil {
			senderDone <- err
			return
		}
		resp, err := srv.Client().Do(req)
		if err != nil {
			senderDone <- err
			return
		}
		defer resp.Body.Close()
		io.Copy(io.Discard, resp.Body)
		senderDone <- nil
	}()

	// The receiver connects, reads what is already flowing, then hangs up.
	deadline := time.Now().Add(5 * time.Second)
	var resp *http.Response
	for {
		req, _ := http.NewRequest(http.MethodGet, recvURL, nil)
		r, err := srv.Client().Do(req)
		if err == nil {
			resp = r
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("receiver never connected: %v", err)
		}
		time.Sleep(20 * time.Millisecond)
	}
	buf := make([]byte, 64)
	resp.Body.Read(buf)
	resp.Body.Close()

	select {
	case err := <-senderDone:
		if err != nil {
			t.Fatalf("sender request failed: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("sender still blocked after the receiver disconnected")
	}
}

// neverEnding always has one more chunk, like a camera that never stops
// producing frames.
type neverEnding struct{}

func (neverEnding) Read(p []byte) (int, error) {
	for i := range p {
		p[i] = 'x'
	}
	return len(p), nil
}

// The sender-first order keeps its current behavior: the receiver drains the
// buffered tail and sees a clean EOF.
func TestPipeSenderCloseStillDrainsReceiver(t *testing.T) {
	token := []byte("test-secret-token")
	hub := NewHub(token, mustChunkStore(t, "", 0, 0))
	t.Cleanup(hub.Close)

	srv := httptest.NewServer(http.HandlerFunc(hub.handlePipe))
	t.Cleanup(srv.Close)

	transferID := fmt.Sprintf("tx-senderclose-%d", time.Now().UnixNano())
	senderURL := srv.URL + "/pipe?transfer_id=" + transferID + "&role=sender&token=test-secret-token"
	recvURL := srv.URL + "/pipe?transfer_id=" + transferID + "&role=receiver&token=test-secret-token"

	go func() {
		req, _ := http.NewRequest(http.MethodPost, senderURL, strings.NewReader("payload"))
		resp, err := srv.Client().Do(req)
		if err == nil {
			resp.Body.Close()
		}
	}()

	deadline := time.Now().Add(5 * time.Second)
	for {
		req, _ := http.NewRequest(http.MethodGet, recvURL, nil)
		resp, err := srv.Client().Do(req)
		if err == nil {
			body, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if string(body) != "payload" {
				t.Fatalf("receiver got %q", body)
			}
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("receiver never connected: %v", err)
		}
		time.Sleep(20 * time.Millisecond)
	}
}
