package main

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// TestPipeReceiverFlushParam pins the receiver-side flush cadence of /pipe
// against a real socket: httptest.NewRecorder never withholds bytes (its body
// grows on Write, not on Flush), so the throttle is only observable over a
// live connection.
//
// The first frame always flushes (flushWriter's clock starts empty), so the
// interesting case is a second frame sent right after the first, well inside
// one pipeFlushInterval. With flush=1 (the camera stream's low-latency mode)
// it must be on the wire immediately; without the param the default throttle
// must hold — no bytes until the interval lapses or the stream ends — so v1
// file clients see today's behavior.
func TestPipeReceiverFlushParam(t *testing.T) {
	// promptWindow sits well inside one throttle interval: with flush=1 the
	// second frame must arrive within it, and under the default cadence it
	// cannot arrive at all before a full interval has passed since the
	// previous flush.
	const promptWindow = pipeFlushInterval / 2

	tests := []struct {
		name       string
		flushParam string
		wantPrompt bool
	}{
		{"flush=1 delivers a second frame without the throttle", "1", true},
		{"no flush param keeps the throttled cadence", "", false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			token := []byte("test-secret-token")
			hub := NewHub(token, mustChunkStore(t, "", 0, 0))
			t.Cleanup(hub.Close) // cleanups run LIFO: after the server is down

			srv := httptest.NewServer(http.HandlerFunc(hub.handlePipe))
			t.Cleanup(srv.Close)

			transferID := fmt.Sprintf("tx-pipe-flush-%d", time.Now().UnixNano())
			recvURL := srv.URL + "/pipe?transfer_id=" + transferID + "&role=receiver&token=test-secret-token"
			if tt.flushParam != "" {
				recvURL += "&flush=" + tt.flushParam
			}
			senderURL := srv.URL + "/pipe?transfer_id=" + transferID + "&role=sender&token=test-secret-token"

			frameA := []byte("frame-zero-csd")
			frameB := []byte("frame-one-payload")

			// Both requests share one context: the deferred cancel aborts
			// them on early failure, unwinding the handlers so the server
			// closes instead of waiting out a rendezvous timeout.
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)

			// The receiver streams the response and reports each frame as
			// soon as it has read all of its bytes.
			aSeen := make(chan struct{})
			bSeen := make(chan []byte, 1)
			recvDone := make(chan struct{})
			go func() {
				defer close(recvDone)
				req, err := http.NewRequestWithContext(ctx, http.MethodGet, recvURL, nil)
				if err != nil {
					t.Errorf("building receiver request: %v", err)
					return
				}
				resp, err := http.DefaultClient.Do(req)
				if err != nil {
					t.Errorf("receiver GET failed: %v", err)
					return
				}
				defer resp.Body.Close()
				if resp.StatusCode != http.StatusOK {
					t.Errorf("receiver status = %d, want %d", resp.StatusCode, http.StatusOK)
					return
				}
				buf := make([]byte, len(frameA))
				if _, err := io.ReadFull(resp.Body, buf); err != nil {
					t.Errorf("reading first frame: %v", err)
					return
				}
				if !bytes.Equal(buf, frameA) {
					t.Errorf("first frame = %q, want %q", buf, frameA)
					return
				}
				close(aSeen)
				buf = make([]byte, len(frameB))
				if _, err := io.ReadFull(resp.Body, buf); err != nil {
					t.Errorf("reading second frame: %v", err)
					return
				}
				bSeen <- buf
				_, _ = io.Copy(io.Discard, resp.Body)
			}()

			// The sender POSTs a chunked body and keeps it open, so after
			// the second frame only the receiver's flush cadence decides
			// when those bytes reach the wire.
			pr, pw := io.Pipe()
			senderDone := make(chan struct{})
			go func() {
				defer close(senderDone)
				req, err := http.NewRequestWithContext(ctx, http.MethodPost, senderURL, pr)
				if err != nil {
					_ = pw.CloseWithError(err)
					t.Errorf("building sender request: %v", err)
					return
				}
				resp, err := http.DefaultClient.Do(req)
				if err != nil {
					_ = pw.CloseWithError(err)
					t.Errorf("sender POST failed: %v", err)
					return
				}
				_, _ = io.Copy(io.Discard, resp.Body)
				_ = resp.Body.Close()
			}()

			// Cancel first so both helpers unwind before the test ends;
			// a wedged helper then cannot log after test completion.
			// Closing pw unblocks the sender's transport, whose write
			// loop may be parked reading the still-open body.
			t.Cleanup(func() {
				cancel()
				_ = pw.Close()
				for _, done := range []chan struct{}{recvDone, senderDone} {
					select {
					case <-done:
					case <-time.After(5 * time.Second):
						return
					}
				}
			})

			// The first frame always flushes, so both cadences must
			// deliver it promptly.
			if _, err := pw.Write(frameA); err != nil {
				t.Fatalf("writing first frame: %v", err)
			}
			select {
			case <-aSeen:
			case <-time.After(2 * time.Second):
				t.Fatalf("first frame did not arrive within 2s")
			}

			// The second frame goes out well inside one interval of the
			// first flush.
			if _, err := pw.Write(frameB); err != nil {
				t.Fatalf("writing second frame: %v", err)
			}

			if tt.wantPrompt {
				select {
				case got := <-bSeen:
					if !bytes.Equal(got, frameB) {
						t.Fatalf("second frame = %q, want %q", got, frameB)
					}
				case <-time.After(promptWindow):
					t.Fatalf("flush=1: second frame undelivered after %v; response is still throttled by pipeFlushInterval", promptWindow)
				}
			} else {
				// Nothing may arrive while the interval is still open.
				select {
				case got := <-bSeen:
					t.Fatalf("second frame arrived within %v without flush=1; the default cadence must stay throttled (got %q)", promptWindow, got)
				case <-time.After(promptWindow):
				}
			}

			// End of body closes the pipe; the handler's final flush
			// delivers whatever the throttle still holds back.
			_ = pw.Close()

			if !tt.wantPrompt {
				select {
				case got := <-bSeen:
					if !bytes.Equal(got, frameB) {
						t.Fatalf("second frame = %q, want %q", got, frameB)
					}
				case <-time.After(2 * time.Second):
					t.Fatalf("second frame never arrived after the sender closed")
				}
			}

			select {
			case <-senderDone:
			case <-time.After(5 * time.Second):
				t.Fatalf("sender POST did not finish")
			}
			select {
			case <-recvDone:
			case <-time.After(5 * time.Second):
				t.Fatalf("receiver GET did not finish")
			}
		})
	}
}
