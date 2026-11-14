package main

import (
	"bytes"
	"testing"
)

func TestClipboardCryptoRoundTrip(t *testing.T) {
	peerKey := bytes.Repeat([]byte{7}, 32)
	key := DeriveClipboardKey(peerKey)
	nonce := bytes.Repeat([]byte{3}, 12)
	plaintext := []byte("hello clipboard")
	aad := []byte("clipboard:dev:msg:1:text:text/plain")

	ciphertext, err := EncryptClipboard(key, nonce, plaintext, aad)
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	got, err := DecryptClipboard(key, nonce, ciphertext, aad)
	if err != nil {
		t.Fatalf("decrypt: %v", err)
	}
	if !bytes.Equal(got, plaintext) {
		t.Fatalf("round trip mismatch: got %q want %q", got, plaintext)
	}

	if _, err := DecryptClipboard(key, nonce, ciphertext, []byte("wrong aad")); err == nil {
		t.Fatal("expected authentication failure with wrong AAD")
	}
}

func TestClipboardHashIncludesKindAndMime(t *testing.T) {
	data := []byte("same bytes")
	textHash := clipboardHash("text", "text/plain", data)
	imageHash := clipboardHash("image", "image/png", data)
	if textHash == imageHash {
		t.Fatal("hash must include kind and mime")
	}
	if textHash != clipboardHash("text", "text/plain", data) {
		t.Fatal("hash must be deterministic")
	}
}
