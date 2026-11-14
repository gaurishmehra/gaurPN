package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestPeerConcurrentWrites(t *testing.T) {
	token := []byte("test-cluster-token-secret")
	hub := NewHub(token, NewChunkStore("", 0, 0))
	defer hub.Close()

	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	defer server.Close()

	wsURL := "ws" + strings.TrimPrefix(server.URL, "http") + "?device_id=test-device-1&name=Device1&token=test-cluster-token-secret"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("Dial failed: %v", err)
	}
	defer conn.Close()

	// Wait for peer to register in hub
	time.Sleep(100 * time.Millisecond)

	hub.mu.RLock()
	peer := hub.peers["test-device-1"]
	hub.mu.RUnlock()

	if peer == nil {
		t.Fatalf("Peer not found in hub")
	}

	// Hammer concurrent writes to prove zero data races
	var wg sync.WaitGroup
	workers := 25
	iterations := 20

	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			for j := 0; j < iterations; j++ {
				_ = peer.WriteJSON(map[string]interface{}{
					"type":   "test",
					"worker": workerID,
					"iter":   j,
				})
			}
		}(i)
	}

	wg.Wait()
}

func TestPipeSessionCleanup(t *testing.T) {
	token := []byte("test-secret-token")
	hub := NewHub(token, NewChunkStore("", 0, 0))
	defer hub.Close()

	transferID := "tx-test-cleanup-1"

	// Simulate sender arriving and timing out without receiver
	reqSender := httptest.NewRequest("POST", "/pipe?transfer_id="+transferID+"&role=sender&token=test-secret-token", strings.NewReader("sample payload"))
	wSender := httptest.NewRecorder()

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	reqSender = reqSender.WithContext(ctx)

	hub.handlePipe(wSender, reqSender)

	// Verify session was cleanly removed from hub.pipes
	hub.pipeMu.Lock()
	_, exists := hub.pipes[transferID]
	hub.pipeMu.Unlock()

	if exists {
		t.Errorf("Expected pipe session %s to be evicted from hub.pipes, but it still exists", transferID)
	}
}

func TestPipeSessionSuccessfulStream(t *testing.T) {
	token := []byte("test-secret-token")
	hub := NewHub(token, NewChunkStore("", 0, 0))
	defer hub.Close()

	transferID := fmt.Sprintf("tx-test-success-%d", time.Now().UnixNano())
	payloadData := "hello from in-tunnel stream verification"

	var wg sync.WaitGroup
	wg.Add(2)

	var receivedContent strings.Builder

	// Run receiver
	go func() {
		defer wg.Done()
		reqRecv := httptest.NewRequest("GET", "/pipe?transfer_id="+transferID+"&role=receiver&token=test-secret-token", nil)
		wRecv := httptest.NewRecorder()
		hub.handlePipe(wRecv, reqRecv)

		resp := wRecv.Result()
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		receivedContent.WriteString(string(body))
	}()

	time.Sleep(50 * time.Millisecond)

	// Run sender
	go func() {
		defer wg.Done()
		reqSend := httptest.NewRequest("POST", "/pipe?transfer_id="+transferID+"&role=sender&token=test-secret-token", strings.NewReader(payloadData))
		wSend := httptest.NewRecorder()
		hub.handlePipe(wSend, reqSend)
	}()

	wg.Wait()

	if receivedContent.String() != payloadData {
		t.Fatalf("Stream mismatch: got %q, want %q", receivedContent.String(), payloadData)
	}

	// Verify session evicted from hub.pipes
	hub.pipeMu.Lock()
	count := len(hub.pipes)
	hub.pipeMu.Unlock()

	if count != 0 {
		t.Errorf("Expected 0 active pipes in hub, got %d", count)
	}
}

func TestHubAuthenticateWithDerivedToken(t *testing.T) {
	masterToken := []byte("super-secret-cluster-token")
	hub := NewHub(masterToken, NewChunkStore("", 0, 0))
	defer hub.Close()

	// Direct master token should authenticate
	if !hub.authenticate("super-secret-cluster-token") {
		t.Errorf("Direct master token failed to authenticate")
	}

	// Derived relay auth token should authenticate
	mac := hmac.New(sha256.New, masterToken)
	mac.Write([]byte("gaur-relay-auth-v1"))
	derivedToken := hex.EncodeToString(mac.Sum(nil))

	if !hub.authenticate(derivedToken) {
		t.Errorf("Derived relay token %s failed to authenticate", derivedToken)
	}

	// Invalid token must fail
	if hub.authenticate("wrong-token") {
		t.Errorf("Invalid token authenticated unexpectedly")
	}
}

func TestRelayAddsTimestampAndSenderIdentity(t *testing.T) {
	token := []byte("test-cluster-token-secret")
	hub := NewHub(token, NewChunkStore("", 0, 0))
	defer hub.Close()

	server := httptest.NewServer(http.HandlerFunc(hub.handleWS))
	defer server.Close()
	wsURL := "ws" + strings.TrimPrefix(server.URL, "http")

	connA, _, err := websocket.DefaultDialer.Dial(wsURL+"?device_id=device-a&name=DeviceA&token=test-cluster-token-secret", nil)
	if err != nil {
		t.Fatalf("dial A: %v", err)
	}
	defer connA.Close()

	connB, _, err := websocket.DefaultDialer.Dial(wsURL+"?device_id=device-b&name=DeviceB&token=test-cluster-token-secret", nil)
	if err != nil {
		t.Fatalf("dial B: %v", err)
	}
	defer connB.Close()

	time.Sleep(100 * time.Millisecond)

	if err := connA.WriteJSON(map[string]interface{}{
		"type":      "clipboard_update",
		"target_id": "device-b",
		"msg_id":    "clip-1",
		"ts":        123,
	}); err != nil {
		t.Fatalf("send: %v", err)
	}

	_ = connB.SetReadDeadline(time.Now().Add(2 * time.Second))
	for {
		_, data, err := connB.ReadMessage()
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		var envelope map[string]interface{}
		if err := json.Unmarshal(data, &envelope); err != nil {
			continue
		}
		if envelope["type"] != "clipboard_update" {
			continue
		}
		if envelope["from_id"] != "device-a" {
			t.Fatalf("from_id = %v, want device-a", envelope["from_id"])
		}
		if _, ok := envelope["relay_ts"].(float64); !ok {
			t.Fatalf("relay_ts missing or not numeric: %v", envelope["relay_ts"])
		}
		return
	}
}
