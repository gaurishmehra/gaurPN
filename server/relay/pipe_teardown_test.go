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
// errReceiverGone the moment the receiver leaves, so the sender's copy
// aborts and its request returns.
func TestPipeReceiverDisconnectEndsSender(t *testing.T) {
	token := []byte("test-secret-token")
	hub := NewHub(token, mustChunkStore(t, "", 0, 0))
	t.Cleanup(hub.Close)

	srv := httptest.NewServer(http.HandlerFunc(hub.handlePipe))
	t.Cleanup(srv.Close)

	transferID := fmt.Sprintf("tx-teardown-%d", time.Now().UnixNano())
	senderURL := srv.URL + "/pipe?transfer_id=" + transferID + "&role=sender&token=test-secret-token"
	recvURL := srv.URL + "/pipe?transfer_id=" + transferID + "&role=receiver&token=test-secret-token"

	// The sender POSTs a body that stays open until the relay kills it.
	senderDone := make(chan error, 1)
	go func() {
		pr, pw := io.Pipe()
		go pw.Write([]byte("frame-zero"))
		req, err := http.NewRequest(http.MethodPost, senderURL, pr)
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

	// The receiver connects, then hangs up without draining.
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
