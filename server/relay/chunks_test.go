package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestChunkStoreMemoryPutGetPresentDelete(t *testing.T) {
	store := NewChunkStore("", 0, time.Hour)
	defer store.Close()

	key := "tx-abc/session-1"
	chunks := [][]byte{[]byte("first"), []byte("second"), []byte("third")}
	for i, chunk := range chunks {
		if err := store.Put(key, i, chunk); err != nil {
			t.Fatalf("Put(%d) failed: %v", i, err)
		}
	}

	for i, want := range chunks {
		got, ok := store.Get(key, i)
		if !ok {
			t.Fatalf("Get(%d) reported missing", i)
		}
		if !bytes.Equal(got, want) {
			t.Fatalf("Get(%d) = %q, want %q", i, got, want)
		}
	}

	present := store.Present(key, 5)
	if len(present) != 3 {
		t.Fatalf("Present returned %v, want 3 entries", present)
	}
	for i, idx := range present {
		if idx != i {
			t.Fatalf("Present[%d] = %d, want %d", i, idx, i)
		}
	}

	store.Delete(key)
	if _, ok := store.Get(key, 0); ok {
		t.Fatalf("Get after Delete still returned a chunk")
	}
}

func TestChunkStoreOverwriteDoesNotDoubleCount(t *testing.T) {
	store := NewChunkStore("", 0, time.Hour)
	defer store.Close()

	key := "tx-abc/session-1"
	if err := store.Put(key, 0, []byte("short")); err != nil {
		t.Fatalf("Put failed: %v", err)
	}
	if err := store.Put(key, 0, []byte("a much longer replacement chunk")); err != nil {
		t.Fatalf("overwrite Put failed: %v", err)
	}

	_, used := store.Stats()
	if used != int64(len("a much longer replacement chunk")) {
		t.Fatalf("usedBytes = %d after overwrite", used)
	}
}

func TestChunkStoreDiskRoundTrip(t *testing.T) {
	dir := t.TempDir()
	store := NewChunkStore(dir, 0, time.Hour)
	defer store.Close()

	key := "tx-disk/session-a"
	payload := bytes.Repeat([]byte("x"), 4096)
	if err := store.Put(key, 7, payload); err != nil {
		t.Fatalf("Put failed: %v", err)
	}

	got, ok := store.Get(key, 7)
	if !ok || !bytes.Equal(got, payload) {
		t.Fatalf("disk Get mismatch")
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir failed: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("expected one transfer dir, got %d", len(entries))
	}

	store.Delete(key)
	entries, _ = os.ReadDir(dir)
	if len(entries) != 0 {
		t.Fatalf("Delete left %d entries", len(entries))
	}
}

func TestChunkStoreRejectsTraversalAndBadIndex(t *testing.T) {
	store := NewChunkStore("", 0, time.Hour)
	defer store.Close()

	badKeys := []string{
		"../etc/passwd",
		"tx-abc/../session",
		"tx-abc/session/extra",
		"",
		"tx-abc/",
	}
	for _, key := range badKeys {
		if err := store.Put(key, 0, []byte("x")); err == nil {
			t.Fatalf("Put accepted unsafe key %q", key)
		}
	}

	if err := store.Put("tx-abc/session", -1, []byte("x")); err == nil {
		t.Fatalf("Put accepted negative index")
	}
	if err := store.Put("tx-abc/session", maxChunksPerTransfer, []byte("x")); err == nil {
		t.Fatalf("Put accepted out-of-range index")
	}
	if err := store.Put("tx-abc/session", 0, nil); err == nil {
		t.Fatalf("Put accepted empty chunk")
	}
	if err := store.Put("tx-abc/session", 0, make([]byte, maxChunkSize+1)); err == nil {
		t.Fatalf("Put accepted oversized chunk")
	}
}

func TestChunkStoreEvictsOldestTransfer(t *testing.T) {
	store := NewChunkStore("", 64, time.Hour)
	defer store.Close()

	if err := store.Put("tx-old/session", 0, bytes.Repeat([]byte("a"), 40)); err != nil {
		t.Fatalf("first Put failed: %v", err)
	}
	time.Sleep(2 * time.Millisecond)
	if err := store.Put("tx-new/session", 0, bytes.Repeat([]byte("b"), 40)); err != nil {
		t.Fatalf("second Put failed: %v", err)
	}

	if _, ok := store.Get("tx-old/session", 0); ok {
		t.Fatalf("oldest transfer was not evicted")
	}
	if _, ok := store.Get("tx-new/session", 0); !ok {
		t.Fatalf("newest transfer was evicted")
	}
}

func TestChunkHTTPEndpoints(t *testing.T) {
	token := []byte("test-cluster-token-secret")
	store := NewChunkStore("", 0, time.Hour)
	hub := NewHub(token, store)
	defer hub.Close()

	mux := http.NewServeMux()
	mux.HandleFunc("/chunk", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodPut:
			hub.handleChunkPut(w, r)
		case http.MethodGet, http.MethodHead:
			hub.handleChunkGet(w, r)
		default:
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/chunk/status", hub.handleChunkStatus)
	mux.HandleFunc("/transfer", hub.handleTransferDelete)
	server := httptest.NewServer(mux)
	defer server.Close()

	payload := []byte("ciphertext-chunk")
	putReq, _ := http.NewRequest(http.MethodPut, server.URL+"/chunk?transfer_id=tx-1&session_id=s-1&index=2", bytes.NewReader(payload))
	putReq.Header.Set("X-Gaur-Token", string(token))
	putResp, err := http.DefaultClient.Do(putReq)
	if err != nil {
		t.Fatalf("PUT failed: %v", err)
	}
	putResp.Body.Close()
	if putResp.StatusCode != http.StatusOK {
		t.Fatalf("PUT status = %d", putResp.StatusCode)
	}

	getReq, _ := http.NewRequest(http.MethodGet, server.URL+"/chunk?transfer_id=tx-1&session_id=s-1&index=2", nil)
	getReq.Header.Set("X-Gaur-Token", string(token))
	getResp, err := http.DefaultClient.Do(getReq)
	if err != nil {
		t.Fatalf("GET failed: %v", err)
	}
	got := new(bytes.Buffer)
	_, _ = got.ReadFrom(getResp.Body)
	getResp.Body.Close()
	if !bytes.Equal(got.Bytes(), payload) {
		t.Fatalf("GET body = %q", got.Bytes())
	}

	statusReq, _ := http.NewRequest(http.MethodGet, server.URL+"/chunk/status?transfer_id=tx-1&session_id=s-1&count=5", nil)
	statusReq.Header.Set("X-Gaur-Token", string(token))
	statusResp, err := http.DefaultClient.Do(statusReq)
	if err != nil {
		t.Fatalf("status failed: %v", err)
	}
	var status struct {
		Present []int `json:"present"`
	}
	_ = json.NewDecoder(statusResp.Body).Decode(&status)
	statusResp.Body.Close()
	if len(status.Present) != 1 || status.Present[0] != 2 {
		t.Fatalf("status present = %v", status.Present)
	}

	delReq, _ := http.NewRequest(http.MethodDelete, server.URL+"/transfer?transfer_id=tx-1&session_id=s-1", nil)
	delReq.Header.Set("X-Gaur-Token", string(token))
	delResp, err := http.DefaultClient.Do(delReq)
	if err != nil {
		t.Fatalf("DELETE failed: %v", err)
	}
	delResp.Body.Close()
	if delResp.StatusCode != http.StatusNoContent {
		t.Fatalf("DELETE status = %d", delResp.StatusCode)
	}

	if _, ok := store.Get("tx-1/s-1", 2); ok {
		t.Fatalf("chunk survived DELETE")
	}
}

func TestChunkHTTPRejectsUnauthorized(t *testing.T) {
	store := NewChunkStore("", 0, time.Hour)
	hub := NewHub([]byte("secret"), store)
	defer hub.Close()

	req := httptest.NewRequest(http.MethodPut, "/chunk?transfer_id=tx&session_id=s&index=0", bytes.NewReader([]byte("x")))
	rec := httptest.NewRecorder()
	hub.handleChunkPut(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}

func TestChunkStorePresentMissingReturnsEmpty(t *testing.T) {
	store := NewChunkStore("", 0, time.Hour)
	defer store.Close()

	present := store.Present("tx-missing/session", 10)
	if present == nil {
		t.Fatalf("Present returned nil for a missing transfer")
	}
	if len(present) != 0 {
		t.Fatalf("Present returned %v for a missing transfer", present)
	}
}

func TestChunkStoreDiskClearedOnBoot(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "stale.chunk"), []byte("old"), 0o600); err != nil {
		t.Fatalf("seed failed: %v", err)
	}
	store := NewChunkStore(dir, 0, time.Hour)
	defer store.Close()

	entries, _ := os.ReadDir(dir)
	if len(entries) != 0 {
		t.Fatalf("boot left %d stale entries", len(entries))
	}
}
