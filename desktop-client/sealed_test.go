package main

import (
	"bytes"
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
