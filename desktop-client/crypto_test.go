package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCryptoECDHAndEncryption(t *testing.T) {
	aliceKP, err := GenerateKeyPair()
	if err != nil {
		t.Fatalf("Alice keypair failed: %v", err)
	}
	bobKP, err := GenerateKeyPair()
	if err != nil {
		t.Fatalf("Bob keypair failed: %v", err)
	}

	aliceSecret, err := DeriveSharedSecret(aliceKP.Private, bobKP.Public)
	if err != nil {
		t.Fatalf("Alice shared secret derivation failed: %v", err)
	}
	bobSecret, err := DeriveSharedSecret(bobKP.Private, aliceKP.Public)
	if err != nil {
		t.Fatalf("Bob shared secret derivation failed: %v", err)
	}

	if !bytes.Equal(aliceSecret, bobSecret) {
		t.Fatalf("Shared secrets mismatch between Alice and Bob")
	}

	plaintext := []byte("Testing in-tunnel payload encryption across peers")
	nonce := make([]byte, 12)
	nonce[11] = 1

	ciphertext, err := EncryptChunk(aliceSecret, nonce, plaintext)
	if err != nil {
		t.Fatalf("EncryptChunk failed: %v", err)
	}

	decrypted, err := DecryptChunk(bobSecret, nonce, ciphertext)
	if err != nil {
		t.Fatalf("DecryptChunk failed: %v", err)
	}

	if !bytes.Equal(plaintext, decrypted) {
		t.Fatalf("Decrypted plaintext mismatch: got %q, want %q", decrypted, plaintext)
	}
}

func TestSubKeyDerivationAndAuthTag(t *testing.T) {
	masterToken := "test-cluster-token-xyz-12345"
	relayToken, peerKey := DeriveSubKeys(masterToken)

	expectedRelay := "bbd0b8b332a2ba8131e9c750a97a112da3d200a4bc0887f8457ec4507c438e01"
	if relayToken != expectedRelay {
		t.Errorf("RelayToken = %s, want %s", relayToken, expectedRelay)
	}
	if len(peerKey) != 32 {
		t.Errorf("Expected 32-byte peer key, got %d", len(peerKey))
	}

	msg := "offer:tx-1001:bob-device:04abcdef"
	tag := ComputeAuthTag(peerKey, msg)
	expectedTag := "b043ff3d60b1633570b44528ec8f77ebe2c9f2b96d7e4b4088c5a5ffca4eaf6a"
	if tag != expectedTag {
		t.Errorf("AuthTag = %s, want %s", tag, expectedTag)
	}

	if !VerifyAuthTag(peerKey, msg, tag) {
		t.Errorf("Valid tag failed to verify")
	}

	if VerifyAuthTag(peerKey, msg+"tampered", tag) {
		t.Errorf("Tampered message verified unexpectedly")
	}

	_, otherPeerKey := DeriveSubKeys("different-cluster-token")
	if VerifyAuthTag(otherPeerKey, msg, tag) {
		t.Errorf("Different key verified tag unexpectedly")
	}
}

func TestSafetyNumberDeterminism(t *testing.T) {
	alicePub := bytes.Repeat([]byte{1}, 65)
	bobPub := bytes.Repeat([]byte{2}, 65)
	transferID := "tx-sample-transfer-123"

	sn1 := ComputeSafetyNumber(alicePub, bobPub, transferID)
	sn2 := ComputeSafetyNumber(bobPub, alicePub, transferID)

	expectedSN := "63877 21084 56392 35848 28620 54908"
	if sn1 != expectedSN {
		t.Fatalf("Safety number = %s, want %s", sn1, expectedSN)
	}
	if sn1 != sn2 {
		t.Fatalf("Safety number not commutative: sn1=%s, sn2=%s", sn1, sn2)
	}

	blocks := strings.Split(sn1, " ")
	if len(blocks) != 6 {
		t.Fatalf("Expected 6 blocks in safety number, got %d", len(blocks))
	}
	for _, b := range blocks {
		if len(b) != 5 {
			t.Errorf("Expected 5 digits per block, got %d (%s)", len(b), b)
		}
	}
}

func TestSanitizeFileNameAndPathCheck(t *testing.T) {
	cases := []struct {
		input    string
		expected string
	}{
		{"../../etc/passwd", "passwd"},
		{"/etc/shadow", "shadow"},
		{"..\\..\\windows\\system32", "system32"},
		{"nul\x00lbyte.exe", "nul_lbyte.exe"},
		{"...hidden", "hidden"},
	}

	for _, tc := range cases {
		got := SanitizeFileName(tc.input)
		if got != tc.expected {
			t.Errorf("SanitizeFileName(%q) = %q, want %q", tc.input, got, tc.expected)
		}
	}

	blank := SanitizeFileName("   ")
	if !strings.HasPrefix(blank, "download_") {
		t.Errorf("Expected download_ prefix for blank input, got %q", blank)
	}

	baseDir := filepath.Join(os.TempDir(), "gaurpn_inbox_test")
	safeFile := filepath.Join(baseDir, "file.txt")
	escapeFile := filepath.Join(baseDir, "../../etc/passwd")

	if !IsPathInsideDirectory(safeFile, baseDir) {
		t.Errorf("Expected safeFile to be inside baseDir")
	}
	if IsPathInsideDirectory(escapeFile, baseDir) {
		t.Errorf("Expected escapeFile to NOT be inside baseDir")
	}
}

func TestResolveUniqueFile(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "gaurpn_collision_test_*")
	if err != nil {
		t.Fatalf("Failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	fileA := filepath.Join(tempDir, "document.pdf")
	if err := os.WriteFile(fileA, []byte("first content"), 0o644); err != nil {
		t.Fatalf("Failed to write initial file: %v", err)
	}

	unique1 := ResolveUniqueFile(tempDir, "document.pdf")
	expected1 := filepath.Join(tempDir, "document (1).pdf")
	if unique1 != expected1 {
		t.Errorf("ResolveUniqueFile 1st = %s, want %s", unique1, expected1)
	}

	if err := os.WriteFile(unique1, []byte("second content"), 0o644); err != nil {
		t.Fatalf("Failed to write 2nd file: %v", err)
	}

	unique2 := ResolveUniqueFile(tempDir, "document.pdf")
	expected2 := filepath.Join(tempDir, "document (2).pdf")
	if unique2 != expected2 {
		t.Errorf("ResolveUniqueFile 2nd = %s, want %s", unique2, expected2)
	}
}
