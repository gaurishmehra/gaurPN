package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

// testPeerKey derives K_peer exactly as cluster members do:
// HMAC-SHA256(masterToken, "gaur-peer-auth-v1"), binary.
func testPeerKey(master []byte) []byte {
	mac := hmac.New(sha256.New, master)
	mac.Write([]byte("gaur-peer-auth-v1"))
	return mac.Sum(nil)
}

// testCap computes the capability tag for a transfer exactly as clients do:
// hex(HMAC-SHA256(K_peer, "gaur-cap-v1:"+transferID+":"+sessionID)).
func testCap(peerKey []byte, transferID, sessionID string) string {
	mac := hmac.New(sha256.New, peerKey)
	mac.Write([]byte("gaur-cap-v1:" + transferID + ":" + sessionID))
	return hex.EncodeToString(mac.Sum(nil))
}

func TestChunkOwnershipAndCapabilities(t *testing.T) {
	master := "ownership-master-token"
	store := mustChunkStore(t, "", 0, time.Hour)
	hub := NewHub([]byte(master), store)
	defer hub.Close()

	peerKey := testPeerKey([]byte(master))

	authed := func(method, target string, body io.Reader) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, target, body)
		req.Header.Set("X-Gaur-Token", master)
		rec := httptest.NewRecorder()
		switch {
		case strings.HasPrefix(target, "/chunk/status"):
			hub.handleChunkStatus(rec, req)
		case strings.HasPrefix(target, "/transfer"):
			hub.handleTransferDelete(rec, req)
		case strings.HasPrefix(target, "/chunk"):
			if method == http.MethodPut {
				hub.handleChunkPut(rec, req)
			} else {
				hub.handleChunkGet(rec, req)
			}
		default:
			t.Fatalf("unexpected target %q", target)
		}
		return rec
	}

	const tid, sid = "tx-own", "s-1"
	key := tid + "/" + sid
	payload := []byte("ciphertext-bytes")

	// Creator PUT with device_id: allowed in open mode (no owner yet) and
	// records the device as owner.
	rec := authed(http.MethodPut, "/chunk?transfer_id="+tid+"&session_id="+sid+"&index=0&device_id=device-a", bytes.NewReader(payload))
	if rec.Code != http.StatusOK {
		t.Fatalf("creator PUT status = %d, want 200", rec.Code)
	}
	owner, ok := store.Owner(key)
	if !ok || owner != "device-a" {
		t.Fatalf("owner after creator PUT = (%q, %v), want (device-a, true)", owner, ok)
	}

	// Second device without a cap: denied.
	rec = authed(http.MethodGet, "/chunk?transfer_id="+tid+"&session_id="+sid+"&index=0&device_id=device-b", nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("second device GET status = %d, want 403", rec.Code)
	}

	// Second device with a valid capability tag: allowed.
	cap := testCap(peerKey, tid, sid)
	rec = authed(http.MethodGet, "/chunk?transfer_id="+tid+"&session_id="+sid+"&index=0&device_id=device-b&cap="+cap, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("capped GET status = %d, want 200", rec.Code)
	}
	if !bytes.Equal(rec.Body.Bytes(), payload) {
		t.Fatalf("capped GET body = %q, want %q", rec.Body.Bytes(), payload)
	}

	// Capability tag for the wrong session (or transfer) is invalid.
	wrongCap := testCap(peerKey, tid, "s-other")
	rec = authed(http.MethodGet, "/chunk?transfer_id="+tid+"&session_id="+sid+"&index=0&device_id=device-b&cap="+wrongCap, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("wrong-session cap GET status = %d, want 403", rec.Code)
	}

	// Legacy request (no device_id, no cap) after ownership is recorded: denied.
	rec = authed(http.MethodGet, "/chunk?transfer_id="+tid+"&session_id="+sid+"&index=0", nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("legacy GET after owner recorded status = %d, want 403", rec.Code)
	}

	// The status endpoint honours the same rules.
	rec = authed(http.MethodGet, "/chunk/status?transfer_id="+tid+"&session_id="+sid+"&count=4&device_id=device-b", nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("second device status status = %d, want 403", rec.Code)
	}
	rec = authed(http.MethodGet, "/chunk/status?transfer_id="+tid+"&session_id="+sid+"&count=4&cap="+cap, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("capped status status = %d, want 200", rec.Code)
	}

	// Owner DELETE works.
	rec = authed(http.MethodDelete, "/transfer?transfer_id="+tid+"&session_id="+sid+"&device_id=device-a", nil)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("owner DELETE status = %d, want 204", rec.Code)
	}
	if _, ok := store.Get(key, 0); ok {
		t.Fatalf("chunk survived owner DELETE")
	}
	if _, ok := store.Owner(key); ok {
		t.Fatalf("ownership survived DELETE")
	}

	// After deletion the key is fresh again: a legacy PUT is allowed in open
	// mode (this also proves removeLocked cleaned the ownership state).
	rec = authed(http.MethodPut, "/chunk?transfer_id="+tid+"&session_id="+sid+"&index=0", bytes.NewReader(payload))
	if rec.Code != http.StatusOK {
		t.Fatalf("legacy PUT after DELETE status = %d, want 200", rec.Code)
	}
}

func TestChunkOpenModeLogsOncePerTransfer(t *testing.T) {
	var logs bytes.Buffer
	log.SetOutput(&logs)
	defer log.SetOutput(os.Stderr)

	master := "openmode-master-token"
	store := mustChunkStore(t, "", 0, time.Hour)
	hub := NewHub([]byte(master), store)
	defer hub.Close()

	const tid, sid = "tx-legacy", "s-9"
	target := "/chunk?transfer_id=" + tid + "&session_id=" + sid + "&index=0"

	for i := 0; i < 2; i++ {
		req := httptest.NewRequest(http.MethodPut, target, strings.NewReader("payload"))
		req.Header.Set("X-Gaur-Token", master)
		rec := httptest.NewRecorder()
		hub.handleChunkPut(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("legacy PUT %d status = %d, want 200", i+1, rec.Code)
		}
	}
	getReq := httptest.NewRequest(http.MethodGet, target, nil)
	getReq.Header.Set("X-Gaur-Token", master)
	getRec := httptest.NewRecorder()
	hub.handleChunkGet(getRec, getReq)
	if getRec.Code != http.StatusOK {
		t.Fatalf("legacy GET status = %d, want 200", getRec.Code)
	}

	want := "[CHUNK] Legacy open-mode access for " + tid + "/" + sid
	if n := strings.Count(logs.String(), want); n != 1 {
		t.Fatalf("open-mode log line appeared %d times, want exactly 1", n)
	}
}

func TestOwnershipFIFOEvictionAndWriteOnce(t *testing.T) {
	o := NewOwnership(3)
	o.Record("k1", "d1")
	o.Record("k2", "d2")
	o.Record("k3", "d3")

	// Recording a fourth owner evicts the oldest (FIFO).
	o.Record("k4", "d4")
	if _, ok := o.Owner("k1"); ok {
		t.Fatalf("k1 should have been FIFO-evicted")
	}
	if owner, ok := o.Owner("k4"); !ok || owner != "d4" {
		t.Fatalf("k4 owner = (%q, %v), want (d4, true)", owner, ok)
	}
	for _, k := range []string{"k2", "k3"} {
		if _, ok := o.Owner(k); !ok {
			t.Fatalf("%s should still be owned", k)
		}
	}

	// Ownership is write-once: a later Record never overwrites.
	o.Record("k2", "attacker")
	if owner, _ := o.Owner("k2"); owner != "d2" {
		t.Fatalf("owner was overwritten: %q", owner)
	}

	// Forget clears state; a forgotten key can be owned again.
	o.Forget("k2")
	if _, ok := o.Owner("k2"); ok {
		t.Fatalf("k2 should be forgotten")
	}
	o.Record("k2", "d2-new")
	if owner, _ := o.Owner("k2"); owner != "d2-new" {
		t.Fatalf("re-recorded owner = %q, want d2-new", owner)
	}

	// The open-mode log is bounded and deduplicated independently.
	o.NoteOpenMode("k1")
	o.NoteOpenMode("k1")
	o.mu.Lock()
	if len(o.logged) != 1 {
		t.Fatalf("open-mode log entries = %d, want 1", len(o.logged))
	}
	o.mu.Unlock()
}
