package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSealEnvelopeRoundTrip(t *testing.T) {
	peer := []byte("peer-key-material")
	inner := map[string]interface{}{
		"sender_id":   "android-123",
		"sender_name": "Lavander",
		"file_name":   "contract.pdf",
		"file_size":   float64(1024),
	}
	envelope, err := sealEnvelope(peer, "lilac-1", "offer", inner)
	if err != nil {
		t.Fatalf("sealEnvelope: %v", err)
	}
	kind, got, err := openSealed(peer, envelope)
	if err != nil {
		t.Fatalf("openSealed: %v", err)
	}
	if kind != "offer" {
		t.Fatalf("kind = %q", kind)
	}
	if got["file_name"] != "contract.pdf" || got["sender_id"] != "android-123" {
		t.Fatalf("unexpected inner payload: %+v", got)
	}
}

func TestSealEnvelopeBindsTarget(t *testing.T) {
	peer := []byte("peer-key-material")
	envelope, err := sealEnvelope(peer, "lilac-1", "offer", map[string]interface{}{
		"file_name": "contract.pdf",
	})
	if err != nil {
		t.Fatal(err)
	}
	envelope["target_id"] = "lilac-2"
	if _, _, err := openSealed(peer, envelope); err == nil {
		t.Fatal("changing the target must fail authentication")
	}
}

func TestDeriveFileKeyDomainSeparation(t *testing.T) {
	peer := []byte("peer-key-material")
	fileKey := DeriveFileKey(peer, "tx-1")
	if len(fileKey) != 32 {
		t.Fatalf("file key length = %d", len(fileKey))
	}
	if !bytes.Equal(fileKey, DeriveFileKey(peer, "tx-1")) {
		t.Fatal("file key must be deterministic")
	}
	if bytes.Equal(fileKey, DeriveFileKey(peer, "tx-2")) {
		t.Fatal("different transfers must derive different keys")
	}
	if bytes.Equal(fileKey, DeriveNotificationKey(peer)) {
		t.Fatal("file and notification keys must be domain-separated")
	}
}

func TestDeriveSessionIDDeterministic(t *testing.T) {
	peer := []byte("peer-key-material")
	transferID := DeriveTransferID(peer, "deadbeef")

	a := DeriveSessionID(peer, transferID)
	b := DeriveSessionID(peer, transferID)
	if a != b {
		t.Fatalf("session id is not deterministic: %q vs %q", a, b)
	}
	if len(a) != 16 {
		t.Fatalf("session id length = %d, want 16", len(a))
	}
	for _, c := range a {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			t.Fatalf("session id %q must be lowercase hex", a)
		}
	}
	if a == DeriveSessionID(peer, DeriveTransferID(peer, "cafebabe")) {
		t.Fatal("session id must differ across transfers")
	}
	if a == DeriveSessionID([]byte("other-peer"), transferID) {
		t.Fatal("session id must depend on the peer key")
	}
	if strings.Contains(transferID, a) || a == "gaur-session-v3" {
		t.Fatalf("session id %q must be an opaque HMAC, not a derived prefix", a)
	}
}

// sealedTestEnvelope seals a notification_ack for the given target. The kind
// is chosen because a processed envelope only logs: no notification daemon,
// clipboard write, or network is touched, so tests stay side-effect free.
func sealedTestEnvelope(t *testing.T, peer []byte, target string) map[string]interface{} {
	t.Helper()
	inner := map[string]interface{}{
		"sender_id":   target,
		"sender_name": "Lilac",
		"notif_key":   "desktop-abc",
		"status":      "displayed",
	}
	envelope, err := sealEnvelope(peer, target, "notification_ack", inner)
	if err != nil {
		t.Fatal(err)
	}
	return envelope
}

func TestSealedEnvelopeTargetCheck(t *testing.T) {
	peer := []byte("peer-key-material")
	envelope := sealedTestEnvelope(t, peer, "device-A")

	// A relay redirecting device-A's mail to device-B must be dropped before
	// it is ever decrypted or processed.
	out := captureLog(func() {
		handleIncomingSealed(Config{DeviceID: "device-B", Name: "Iris"}, "", peer, nil, nil, nil, envelope)
	})
	if !contains(out, "device-A") || !contains(out, "not this device") {
		t.Fatalf("expected a target-mismatch drop log, got: %s", out)
	}
	if contains(out, "[SEALED] Notification") {
		t.Fatalf("an envelope addressed to another device must not be processed: %s", out)
	}

	// The intended target processes it.
	out = captureLog(func() {
		handleIncomingSealed(Config{DeviceID: "device-A", Name: "Lilac"}, "", peer, nil, nil, nil, envelope)
	})
	if !contains(out, "[SEALED] Notification desktop-abc on Lilac: displayed") {
		t.Fatalf("expected the intended device to process the envelope, got: %s", out)
	}
}

func TestSealedEnvelopeDropsReplay(t *testing.T) {
	peer := []byte("peer-key-material")
	envelope := sealedTestEnvelope(t, peer, "device-A")
	cfg := Config{DeviceID: "device-A", Name: "Lilac"}

	out := captureLog(func() {
		handleIncomingSealed(cfg, "", peer, nil, nil, nil, envelope)
	})
	if !contains(out, "[SEALED] Notification desktop-abc") {
		t.Fatalf("first delivery must be processed, got: %s", out)
	}

	out = captureLog(func() {
		handleIncomingSealed(cfg, "", peer, nil, nil, nil, envelope)
	})
	if !contains(out, "Dropped replayed or expired notification_ack envelope") {
		t.Fatalf("replayed envelope must be dropped, got: %s", out)
	}
	if contains(out, "[SEALED] Notification desktop-abc") {
		t.Fatalf("replayed envelope must not be processed, got: %s", out)
	}
}

func TestSealedMediaCommandExpires(t *testing.T) {
	peer := []byte("peer-key-material")
	inner := map[string]interface{}{
		"sender_id":  "device-A",
		"command":    "pause",
		"session_id": "org.mpris.MediaPlayer2.firefox",
	}
	envelope, err := sealEnvelope(peer, "device-A", "media_command", inner)
	if err != nil {
		t.Fatal(err)
	}
	cfg := Config{DeviceID: "device-A", Name: "Lilac"}

	// The relay stamps relay_ts when it first sees the envelope; a mailbox
	// replay delivers the original receive time, which must expire.
	old := time.Now().Add(-time.Minute).UnixMilli()
	envelope["relay_ts"] = float64(old)

	out := captureLog(func() {
		handleIncomingSealed(cfg, "", peer, nil, nil, nil, envelope)
	})
	if !contains(out, "Dropped replayed or expired media_command envelope") {
		t.Fatalf("a media command older than 30 seconds must be dropped, got: %s", out)
	}
	if contains(out, "Could not open") {
		t.Fatalf("an expired command must be dropped before decryption, got: %s", out)
	}

	// A fresh command with a current relay timestamp passes the guard and
	// opens cleanly (the nil localMedia watcher then discards it).
	fresh, err := sealEnvelope(peer, "device-A", "media_command", inner)
	if err != nil {
		t.Fatal(err)
	}
	fresh["relay_ts"] = float64(time.Now().UnixMilli())
	out = captureLog(func() {
		handleIncomingSealed(cfg, "", peer, nil, nil, nil, fresh)
	})
	if contains(out, "Dropped replayed or expired media_command envelope") {
		t.Fatalf("a fresh media command must pass the guard, got: %s", out)
	}
	if contains(out, "Could not open") {
		t.Fatalf("a fresh media command must decrypt cleanly, got: %s", out)
	}
}

// --- D5: sealed receive caps ---

func TestSealedOfferGeometryOK(t *testing.T) {
	const maxCount = 65536
	cases := []struct {
		name       string
		fileSize   int64
		chunkSize  int
		chunkCount int
		hashCount  int
		want       bool
	}{
		{"empty file", 0, ChunkSize, 0, 0, true},
		{"one full chunk", ChunkSize, ChunkSize, 1, 1, true},
		{"partial last chunk", ChunkSize + 1, ChunkSize, 2, 2, true},
		{"max chunk count", int64(maxCount) * ChunkSize, ChunkSize, maxCount, maxCount, true},
		{"chunk size mismatch", 1024, ChunkSize / 2, 1, 1, false},
		{"chunk size zero", 1024, 0, 1, 1, false},
		{"chunk count overflow", ChunkSize, ChunkSize, maxCount + 1, maxCount + 1, false},
		{"negative chunk count", 0, ChunkSize, -1, 0, false},
		{"hash count mismatch", 1024, ChunkSize, 1, 0, false},
		{"size exceeds chunks", ChunkSize + 1, ChunkSize, 1, 1, false},
		{"negative size", -1, ChunkSize, 1, 1, false},
		{"zero size with chunks", 0, ChunkSize, 1, 1, false},
		{"non-empty with no chunks", 1, ChunkSize, 0, 0, false},
		{"size underclaims the chunk count", 1, ChunkSize, 2, 2, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := sealedOfferGeometryOK(tc.fileSize, tc.chunkSize, tc.chunkCount, tc.hashCount); got != tc.want {
				t.Fatalf("sealedOfferGeometryOK(%d, %d, %d, %d) = %v, want %v",
					tc.fileSize, tc.chunkSize, tc.chunkCount, tc.hashCount, got, tc.want)
			}
		})
	}
}

// TestSealedOfferRejectsBadCaps drives the caps through the real receive
// entry point and asserts nothing is staged for a rejected offer.
func TestSealedOfferRejectsBadCaps(t *testing.T) {
	transferID := "tx-" + strings.Repeat("ab", 16)
	cases := []struct {
		name   string
		mutate func(map[string]interface{})
	}{
		{"chunk size mismatch", func(m map[string]interface{}) {
			m["chunk_size"] = float64(ChunkSize / 2)
		}},
		{"chunk count overflow", func(m map[string]interface{}) {
			m["chunk_count"] = float64(65537)
		}},
		{"size overflow", func(m map[string]interface{}) {
			m["file_size"] = float64(ChunkSize + 1)
		}},
		{"negative size", func(m map[string]interface{}) {
			m["file_size"] = float64(-1)
		}},
		{"zero size with chunks", func(m map[string]interface{}) {
			m["file_size"] = float64(0)
		}},
		{"non-empty with no chunks", func(m map[string]interface{}) {
			m["chunk_count"] = float64(0)
			m["chunk_hashes"] = []interface{}{}
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			cfg := Config{InboxDir: filepath.Join(dir, "inbox")}
			inner := map[string]interface{}{
				"transfer_id":  transferID,
				"session_id":   "session-1",
				"file_name":    "x.bin",
				"file_hash":    strings.Repeat("cd", 32),
				"file_size":    float64(1024),
				"chunk_size":   float64(ChunkSize),
				"chunk_count":  float64(1),
				"chunk_hashes": []interface{}{strings.Repeat("ef", 32)},
			}
			tc.mutate(inner)
			if _, err := receiveSealedOfferFile(cfg, "token", []byte("peer"), "desktop-1", "phone-1", inner); err == nil {
				t.Fatal("a bad chunk geometry must be rejected")
			}
			if _, err := os.Stat(cfg.InboxDir); !os.IsNotExist(err) {
				t.Fatalf("a rejected offer must not create the inbox (stat err: %v)", err)
			}
		})
	}
}

// TestSealedOfferEmptyFile pins the empty-file case: zero bytes and zero
// chunks must still stage an owner-only file, verify its hash, and commit.
func TestSealedOfferEmptyFile(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{InboxDir: filepath.Join(dir, "inbox")}
	emptyHash := Sha256Hex(nil)
	transferID := DeriveTransferID([]byte("peer"), emptyHash)
	inner := map[string]interface{}{
		"transfer_id":  transferID,
		"session_id":   "session-1",
		"file_name":    "empty.txt",
		"file_hash":    emptyHash,
		"file_size":    float64(0),
		"chunk_size":   float64(ChunkSize),
		"chunk_count":  float64(0),
		"chunk_hashes": []interface{}{},
	}
	finalPath, err := receiveSealedOfferFile(cfg, "token", []byte("peer"), "desktop-1", "phone-1", inner)
	if err != nil {
		t.Fatalf("receiveSealedOfferFile: %v", err)
	}
	info, err := os.Stat(finalPath)
	if err != nil {
		t.Fatalf("empty file is missing: %v", err)
	}
	if info.Size() != 0 {
		t.Fatalf("empty file size = %d, want 0", info.Size())
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("empty file mode = %04o, want 0600", perm)
	}
	partPath := partFilePath(cfg, "empty.txt", transferID)
	if _, err := os.Stat(partPath); !os.IsNotExist(err) {
		t.Fatalf(".part must be renamed away, stat err: %v", err)
	}
	if _, err := os.Stat(partPath + ".state"); !os.IsNotExist(err) {
		t.Fatalf("state must be cleaned up, stat err: %v", err)
	}
}
