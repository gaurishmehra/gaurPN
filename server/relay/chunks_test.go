package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// mustChunkStore builds a store for tests, failing on any construction
// error (the boot-time marker gate made construction fallible).
func mustChunkStore(t *testing.T, dir string, maxBytes int64, ttl time.Duration) *ChunkStore {
	t.Helper()
	s, err := NewChunkStore(dir, maxBytes, ttl)
	if err != nil {
		t.Fatalf("NewChunkStore(%q, %d, %v): %v", dir, maxBytes, ttl, err)
	}
	return s
}

// countStagedDirs counts transfer staging directories in dir, ignoring
// the .gaur-relay-chunks marker file.
func countStagedDirs(t *testing.T, dir string) int {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir(%s): %v", dir, err)
	}
	n := 0
	for _, e := range entries {
		if e.Name() != chunkDirMarker {
			n++
		}
	}
	return n
}

func TestChunkStoreMemoryPutGetPresentDelete(t *testing.T) {
	store := mustChunkStore(t, "", 0, time.Hour)
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
	store := mustChunkStore(t, "", 0, time.Hour)
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
	store := mustChunkStore(t, dir, 0, time.Hour)
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

	// The boot marker plus one transfer staging dir.
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir failed: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("expected marker plus one transfer dir, got %d entries", len(entries))
	}

	store.Delete(key)
	if got := countStagedDirs(t, dir); got != 0 {
		t.Fatalf("Delete left %d staged dirs", got)
	}
}

func TestChunkStoreRejectsTraversalAndBadIndex(t *testing.T) {
	store := mustChunkStore(t, "", 0, time.Hour)
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
	store := mustChunkStore(t, "", 64, time.Hour)
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

// TestChunkStoreHardCapRejectsNewTransfers covers MED-1: the byte cap is
// a hard admission gate. A store at its cap refuses transfers it has
// never seen, while a transfer with staged chunks may always continue
// so an in-flight transfer can finish.
func TestChunkStoreHardCapRejectsNewTransfers(t *testing.T) {
	store := mustChunkStore(t, "", 64, time.Hour)
	defer store.Close()

	if err := store.Put("tx-full/session", 0, bytes.Repeat([]byte("a"), 64)); err != nil {
		t.Fatalf("fill Put failed: %v", err)
	}
	if _, used := store.Stats(); used != 64 {
		t.Fatalf("used = %d bytes, want 64", used)
	}

	err := store.Put("tx-new/session", 0, []byte("x"))
	if !errors.Is(err, errCapExceeded) {
		t.Fatalf("new-transfer Put at cap: err = %v, want errCapExceeded", err)
	}

	// Recorded ownership must not count as admission: the handler
	// records the owner before the body is read, so a rejected PUT
	// retried has to stay rejected.
	store.RecordOwner("tx-new/session", "device-a")
	if err := store.Put("tx-new/session", 0, []byte("x")); !errors.Is(err, errCapExceeded) {
		t.Fatalf("retry after RecordOwner: err = %v, want errCapExceeded", err)
	}

	// A known transfer continues past the cap, bounded only by the
	// per-transfer chunk ceiling.
	if err := store.Put("tx-full/session", 1, []byte("b")); err != nil {
		t.Fatalf("existing-transfer Put at cap failed: %v", err)
	}
	if _, used := store.Stats(); used != 65 {
		t.Fatalf("used = %d bytes, want 65 (in-flight overshoot)", used)
	}
}

// TestChunkStoreEvictsIdleTransferForNewOne covers MED-1: eviction of
// idle transfers still makes room for a new transfer while the store is
// below its cap.
func TestChunkStoreEvictsIdleTransferForNewOne(t *testing.T) {
	store := mustChunkStore(t, "", 64, time.Hour)
	defer store.Close()

	// 63 of 64 bytes: under the cap, so the new transfer is admitted,
	// but only by evicting the idle one.
	if err := store.Put("tx-idle/session", 0, bytes.Repeat([]byte("a"), 63)); err != nil {
		t.Fatalf("first Put failed: %v", err)
	}
	time.Sleep(2 * time.Millisecond)
	if err := store.Put("tx-fresh/session", 0, bytes.Repeat([]byte("b"), 40)); err != nil {
		t.Fatalf("second Put failed: %v", err)
	}

	if _, ok := store.Get("tx-idle/session", 0); ok {
		t.Fatalf("idle transfer was not evicted to make room")
	}
	if _, ok := store.Get("tx-fresh/session", 0); !ok {
		t.Fatalf("new transfer was refused although eviction made room")
	}
}

// TestChunkPutHardCapStatuses covers MED-1 at the HTTP layer: 507 for a
// new transfer once the store is at its cap, 200 for an in-flight one.
func TestChunkPutHardCapStatuses(t *testing.T) {
	token := []byte("cap-token")
	store := mustChunkStore(t, "", 64, time.Hour)
	hub := NewHub(token, store)
	defer hub.Close()

	put := func(target string, body []byte) int {
		req := httptest.NewRequest(http.MethodPut, target, bytes.NewReader(body))
		req.Header.Set("X-Gaur-Token", string(token))
		rec := httptest.NewRecorder()
		hub.handleChunkPut(rec, req)
		return rec.Code
	}

	if code := put("/chunk?transfer_id=tx-a&session_id=s&index=0", bytes.Repeat([]byte("a"), 64)); code != http.StatusOK {
		t.Fatalf("fill PUT status = %d, want 200", code)
	}
	if code := put("/chunk?transfer_id=tx-b&session_id=s&index=0", []byte("x")); code != http.StatusInsufficientStorage {
		t.Fatalf("new-transfer PUT at cap = %d, want 507", code)
	}
	if code := put("/chunk?transfer_id=tx-a&session_id=s&index=1", []byte("b")); code != http.StatusOK {
		t.Fatalf("in-flight PUT at cap = %d, want 200", code)
	}
}

// TestChunkPutErrorStatusMapping covers MED-2: honest status codes.
// Oversized bodies are 413, staging I/O failures are 500 without
// leaking filesystem paths, and validation errors stay 400.
func TestChunkPutErrorStatusMapping(t *testing.T) {
	token := []byte("map-token")

	t.Run("oversized body is 413", func(t *testing.T) {
		store := mustChunkStore(t, "", 0, time.Hour)
		hub := NewHub(token, store)
		defer hub.Close()

		req := httptest.NewRequest(http.MethodPut, "/chunk?transfer_id=tx&session_id=s&index=0",
			bytes.NewReader(bytes.Repeat([]byte("x"), maxChunkSize+1)))
		req.Header.Set("X-Gaur-Token", string(token))
		rec := httptest.NewRecorder()
		hub.handleChunkPut(rec, req)
		if rec.Code != http.StatusRequestEntityTooLarge {
			t.Fatalf("oversized PUT = %d, want 413", rec.Code)
		}
	})

	t.Run("disk failure is 500 and leaks no paths", func(t *testing.T) {
		chunkDir := filepath.Join(t.TempDir(), "chunks")
		store := mustChunkStore(t, chunkDir, 0, time.Hour)
		hub := NewHub(token, store)
		defer hub.Close()

		// Seed the transfer so the failure hits the chunk write, not
		// the transfer-dir creation.
		if err := store.Put("tx-disk/session", 0, []byte("first")); err != nil {
			t.Fatalf("seed Put failed: %v", err)
		}
		// Replace the chunk dir with a plain file: every staging write
		// now fails at the disk layer.
		if err := os.RemoveAll(chunkDir); err != nil {
			t.Fatalf("remove chunk dir: %v", err)
		}
		if err := os.WriteFile(chunkDir, []byte("not a dir"), 0o600); err != nil {
			t.Fatalf("sabotage chunk dir: %v", err)
		}

		req := httptest.NewRequest(http.MethodPut, "/chunk?transfer_id=tx-disk&session_id=session&index=1",
			bytes.NewReader([]byte("second")))
		req.Header.Set("X-Gaur-Token", string(token))
		rec := httptest.NewRecorder()
		hub.handleChunkPut(rec, req)
		if rec.Code != http.StatusInternalServerError {
			t.Fatalf("disk-failed PUT = %d, want 500", rec.Code)
		}
		if strings.Contains(rec.Body.String(), chunkDir) {
			t.Fatalf("500 body leaks the staging path: %q", rec.Body.String())
		}
	})

	t.Run("validation errors stay 400", func(t *testing.T) {
		store := mustChunkStore(t, "", 0, time.Hour)
		hub := NewHub(token, store)
		defer hub.Close()

		// "." is outside the safe transfer-key charset, so the store
		// rejects the key after the request itself parsed fine.
		req := httptest.NewRequest(http.MethodPut, "/chunk?transfer_id=bad.id&session_id=s&index=0",
			bytes.NewReader([]byte("x")))
		req.Header.Set("X-Gaur-Token", string(token))
		rec := httptest.NewRecorder()
		hub.handleChunkPut(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("invalid-key PUT = %d, want 400", rec.Code)
		}
	})
}

// TestChunkStorePutFailureCleansStagingFile covers MED-2: a failed
// staging write must not leak its .tmp file. Blocking the staging path
// with a directory makes os.WriteFile fail with EISDIR while the path
// exists, which is exactly the failed-write path that used to leave the
// file behind.
func TestChunkStorePutFailureCleansStagingFile(t *testing.T) {
	chunkDir := filepath.Join(t.TempDir(), "chunks")
	store := mustChunkStore(t, chunkDir, 0, time.Hour)
	defer store.Close()

	if err := store.Put("tx-tmp/session", 0, []byte("first")); err != nil {
		t.Fatalf("seed Put failed: %v", err)
	}

	// The transfer staging dir is the only non-marker entry.
	entries, err := os.ReadDir(chunkDir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	transferDir := ""
	for _, e := range entries {
		if e.Name() != chunkDirMarker {
			transferDir = filepath.Join(chunkDir, e.Name())
		}
	}
	if transferDir == "" {
		t.Fatal("no transfer staging dir found")
	}

	// Block index 1's staging path with a directory.
	tmp := filepath.Join(transferDir, "00000001.chunk.tmp")
	if err := os.Mkdir(tmp, 0o700); err != nil {
		t.Fatalf("block staging path: %v", err)
	}

	err = store.Put("tx-tmp/session", 1, []byte("second"))
	if !errors.Is(err, errDiskIO) {
		t.Fatalf("Put err = %v, want errDiskIO", err)
	}
	if _, statErr := os.Stat(tmp); !errors.Is(statErr, fs.ErrNotExist) {
		t.Fatalf("failed Put left the .tmp staging path behind")
	}
}

// TestPresentRefreshesTransferActivity covers MED-3: Present refreshes
// a transfer's idle timestamp. A receiver that only polls status must
// not have its transfer TTL-swept mid-download; once the polling stops,
// the transfer ages out.
func TestPresentRefreshesTransferActivity(t *testing.T) {
	orig := chunkEvictInterval
	chunkEvictInterval = 20 * time.Millisecond
	t.Cleanup(func() { chunkEvictInterval = orig })

	store := mustChunkStore(t, "", 0, 60*time.Millisecond)
	defer store.Close()

	if err := store.Put("tx-poll/session", 0, []byte("chunk")); err != nil {
		t.Fatalf("Put failed: %v", err)
	}

	// Poll Present for far longer than the TTL. Stats does not refresh
	// activity, so it observes the sweeper's verdict without affecting
	// it.
	deadline := time.Now().Add(400 * time.Millisecond)
	for time.Now().Before(deadline) {
		store.Present("tx-poll/session", 1)
		time.Sleep(10 * time.Millisecond)
	}
	if transfers, used := store.Stats(); transfers != 1 || used != int64(len("chunk")) {
		t.Fatalf("transfer was TTL-swept while Present kept it alive: %d transfers, %d bytes", transfers, used)
	}

	// Stop calling Present: the transfer must now age out.
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if transfers, _ := store.Stats(); transfers == 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("transfer was never evicted after Present calls stopped")
}

func TestChunkHTTPEndpoints(t *testing.T) {
	token := []byte("test-cluster-token-secret")
	store := mustChunkStore(t, "", 0, time.Hour)
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
	store := mustChunkStore(t, "", 0, time.Hour)
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
	store := mustChunkStore(t, "", 0, time.Hour)
	defer store.Close()

	present := store.Present("tx-missing/session", 10)
	if present == nil {
		t.Fatalf("Present returned nil for a missing transfer")
	}
	if len(present) != 0 {
		t.Fatalf("Present returned %v for a missing transfer", present)
	}
}

// TestChunkStoreBootWipeMarkerGate covers MED-6: the boot-time wipe is
// gated on the .gaur-relay-chunks marker. A non-empty directory without
// it is refused; an empty or marked one is wiped and re-marked.
func TestChunkStoreBootWipeMarkerGate(t *testing.T) {
	cases := []struct {
		name    string
		seed    func(t *testing.T, dir string)
		wantErr bool
		// wantMarkerInErr is set for refusals that must name the marker
		// file in their message (the unmarked-data case); an
		// uninspectable path only names the path.
		wantMarkerInErr bool
		survivor        string // file under dir that must survive a refusal
	}{
		{
			name: "non-empty dir without marker is refused",
			seed: func(t *testing.T, dir string) {
				if err := os.MkdirAll(dir, 0o700); err != nil {
					t.Fatalf("seed mkdir: %v", err)
				}
				if err := os.WriteFile(filepath.Join(dir, "user-data.bin"), []byte("precious"), 0o600); err != nil {
					t.Fatalf("seed file: %v", err)
				}
			},
			wantErr:         true,
			wantMarkerInErr: true,
			survivor:        "user-data.bin",
		},
		{
			name: "plain file at the chunk dir path is refused",
			seed: func(t *testing.T, dir string) {
				if err := os.WriteFile(dir, []byte("not a dir"), 0o600); err != nil {
					t.Fatalf("seed file: %v", err)
				}
			},
			wantErr: true,
		},
		{
			name: "marked dir is wiped and re-marked",
			seed: func(t *testing.T, dir string) {
				if err := os.MkdirAll(dir, 0o700); err != nil {
					t.Fatalf("seed mkdir: %v", err)
				}
				if err := os.WriteFile(filepath.Join(dir, chunkDirMarker), nil, 0o600); err != nil {
					t.Fatalf("seed marker: %v", err)
				}
				if err := os.WriteFile(filepath.Join(dir, "00000000.chunk"), []byte("stale"), 0o600); err != nil {
					t.Fatalf("seed stale chunk: %v", err)
				}
			},
		},
		{
			name: "empty dir is wiped and marked",
			seed: func(t *testing.T, dir string) {
				if err := os.MkdirAll(dir, 0o700); err != nil {
					t.Fatalf("seed mkdir: %v", err)
				}
			},
		},
		{
			name: "missing dir is created and marked",
			seed: func(t *testing.T, dir string) {},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dir := filepath.Join(t.TempDir(), "chunks")
			tc.seed(t, dir)

			store, err := NewChunkStore(dir, 0, time.Hour)
			if tc.wantErr {
				if err == nil {
					store.Close()
					t.Fatal("boot accepted a chunk dir it must refuse")
				}
				if !strings.Contains(err.Error(), dir) {
					t.Fatalf("error must name the dir, got: %v", err)
				}
				if tc.wantMarkerInErr && !strings.Contains(err.Error(), chunkDirMarker) {
					t.Fatalf("error must name the marker, got: %v", err)
				}
				if tc.survivor != "" {
					if _, statErr := os.Stat(filepath.Join(dir, tc.survivor)); statErr != nil {
						t.Fatalf("refused boot must not touch the dir contents: %v", statErr)
					}
				} else if _, statErr := os.Stat(dir); statErr != nil {
					t.Fatalf("refused boot must not touch the path itself: %v", statErr)
				}
				return
			}
			if err != nil {
				t.Fatalf("boot failed: %v", err)
			}
			defer store.Close()

			if got := countStagedDirs(t, dir); got != 0 {
				t.Fatalf("boot left %d staged entries", got)
			}
			if _, statErr := os.Stat(filepath.Join(dir, chunkDirMarker)); statErr != nil {
				t.Fatalf("marker missing after boot: %v", statErr)
			}
		})
	}
}
