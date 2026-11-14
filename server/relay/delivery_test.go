package main

import (
	"bytes"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// dialDeadClient dials a client that never reads and answers nothing,
// with a minimal receive buffer so the relay's writes to it fail once
// the socket fills.
func dialDeadClient(t *testing.T, wsBase, deviceID, token string) *websocket.Conn {
	t.Helper()
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
	conn, _, err := dialer.Dial(wsBase+"?device_id="+deviceID+"&token="+token, nil)
	if err != nil {
		t.Fatalf("dial %s: %v", deviceID, err)
	}
	conn.SetPingHandler(func(string) error { return nil })
	return conn
}

// TestMailboxSurvivesFailedJoin covers REL-5 (a): a device whose join
// dies mid-drain must not lose its mail. Nothing is removed from the
// mailbox until its write succeeds, so a failed join leaves every entry
// queued, in order, and the next healthy join delivers them all.
func TestMailboxSurvivesFailedJoin(t *testing.T) {
	// Short write timeout so the wedged drain fails fast; fast pings so
	// the wedged peer is observably dropped; long read window so the
	// drop is driven by the failed writes, not the read deadline. The
	// 500ms write timeout still leaves ample headroom for the healthy
	// rejoin to receive the 16 MiB envelope inside one deadline.
	setWSLiveness(t, 50*time.Millisecond, time.Hour, 500*time.Millisecond)
	mailbox := NewMailbox(time.Hour, 64<<20)
	hub := NewHub([]byte("delivery-token"), NewChunkStore("", 0, 0), mailbox)
	defer hub.Close()

	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")

	// The big entry is larger than any socket buffer: the first drain
	// write cannot complete within the write timeout.
	big := bytes.Repeat([]byte("m"), 16<<20)
	small := []byte(`{"type":"sealed","msg_id":"small-1","payload":"tiny"}`)
	mailbox.Put("device-x", big)
	mailbox.Put("device-x", small)

	dead := dialDeadClient(t, wsBase, "device-x", "delivery-token")
	defer dead.Close()

	// The join drain wedges on the first write; the ping loop then also
	// fails against the full socket, the relay drops the client, and
	// the deferred cleanup broadcasts the leave.
	waitForPeerCount(t, hub, 0)

	// Nothing was acked: both entries survive, in FIFO order.
	mailbox.mu.Lock()
	entries := mailbox.entries["device-x"]
	mailbox.mu.Unlock()
	if len(entries) != 2 {
		t.Fatalf("mailbox holds %d entries after failed join, want 2", len(entries))
	}
	if !bytes.Equal(entries[0].data, big) || !bytes.Equal(entries[1].data, small) {
		t.Fatal("queued entries were reordered or corrupted by the failed join")
	}

	// A healthy join delivers the survivors, FIFO, bytes intact.
	conn, _, err := websocket.DefaultDialer.Dial(wsBase+"?device_id=device-x&token=delivery-token", nil)
	if err != nil {
		t.Fatalf("dial healthy rejoin: %v", err)
	}
	defer conn.Close()
	waitForPeerCount(t, hub, 1)

	readUntil(t, conn, func(data []byte) bool { return bytes.Equal(data, big) }, 10*time.Second)
	readUntil(t, conn, func(data []byte) bool { return bytes.Equal(data, small) }, 5*time.Second)

	if _, queued := mailbox.Stats(); queued != 0 {
		t.Fatalf("mailbox still holds %d bytes after a fully acked drain", queued)
	}
}

// TestSealedForwardRequeuesToPresentButDeadPeer covers REL-5 (c): a
// sealed envelope to a peer that is present but unwritable (died
// mid-join, stopped reading) is re-queued into the mailbox instead of
// being dropped, keeps its original relay_ts, and is delivered when the
// device reconnects.
func TestSealedForwardRequeuesToPresentButDeadPeer(t *testing.T) {
	// No ping drops and a long read window so the dead peer stays
	// "present"; only the write timeout is short.
	setWSLiveness(t, time.Hour, time.Hour, 500*time.Millisecond)
	mailbox := NewMailbox(time.Hour, 64<<20)
	hub := NewHub([]byte("delivery-token"), NewChunkStore("", 0, 0), mailbox)
	defer hub.Close()

	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	defer server.Close()
	wsBase := "ws" + strings.TrimPrefix(server.URL, "http")

	// B joins as a present-but-dead peer: the join drain wedges on a big
	// queued envelope, leaving B's send buffer full and unwritable.
	big := bytes.Repeat([]byte("b"), 16<<20)
	mailbox.Put("device-b", big)
	deadB := dialDeadClient(t, wsBase, "device-b", "delivery-token")
	defer deadB.Close()
	waitForPeerCount(t, hub, 1)

	// A joins to send the sealed envelope.
	connA, _, err := websocket.DefaultDialer.Dial(wsBase+"?device_id=device-a&token=delivery-token", nil)
	if err != nil {
		t.Fatalf("dial A: %v", err)
	}
	defer connA.Close()
	waitForPeerCount(t, hub, 2)

	// Give the wedged drain write time to fail before sending the sealed
	// envelope. (Sending earlier would still work — the direct write
	// just queues behind the drain on writeMu — but this keeps the
	// timeline easy to follow.)
	time.Sleep(700 * time.Millisecond)

	if err := connA.WriteJSON(map[string]interface{}{
		"type":      "sealed",
		"target_id": "device-b",
		"msg_id":    "sealed-1",
		"kind":      "text",
		"payload":   "opaque-ciphertext",
	}); err != nil {
		t.Fatalf("send sealed: %v", err)
	}

	// The direct write fails against the full socket and the envelope
	// must be re-queued behind the big one.
	poll := time.Now().Add(5 * time.Second)
	for {
		mailbox.mu.Lock()
		n := len(mailbox.entries["device-b"])
		mailbox.mu.Unlock()
		if n == 2 {
			break
		}
		if time.Now().After(poll) {
			t.Fatalf("sealed envelope was not re-queued; mailbox holds %d entries", n)
		}
		time.Sleep(5 * time.Millisecond)
	}
	waitForPeerCount(t, hub, 2) // B was never dropped: it was present the whole time

	// B reconnects with a healthy client; its mail arrives in FIFO order.
	connB, _, err := websocket.DefaultDialer.Dial(wsBase+"?device_id=device-b&token=delivery-token", nil)
	if err != nil {
		t.Fatalf("dial B rejoin: %v", err)
	}
	defer connB.Close()

	readUntil(t, connB, func(data []byte) bool { return bytes.Equal(data, big) }, 10*time.Second)
	sealed := readUntil(t, connB, func(data []byte) bool {
		var env map[string]interface{}
		return json.Unmarshal(data, &env) == nil && env["type"] == "sealed"
	}, 5*time.Second)

	var env map[string]interface{}
	if err := json.Unmarshal(sealed, &env); err != nil {
		t.Fatalf("sealed envelope is not JSON: %v", err)
	}
	if env["msg_id"] != "sealed-1" || env["payload"] != "opaque-ciphertext" {
		t.Fatalf("re-queued envelope was corrupted: %v", env)
	}
	if _, ok := env["from_id"]; ok {
		t.Fatalf("sealed forward leaked from_id: %v", env)
	}
	if _, ok := env["relay_ts"].(float64); !ok {
		t.Fatalf("re-queued envelope lost its original relay_ts: %v", env)
	}
	if _, queued := mailbox.Stats(); queued != 0 {
		t.Fatalf("mailbox still holds %d bytes after redelivery", queued)
	}
}
