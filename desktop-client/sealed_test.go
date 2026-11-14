package main

import (
	"bytes"
	"testing"
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
