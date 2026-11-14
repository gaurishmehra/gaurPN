package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHashFileChunks(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "payload.bin")
	payload := bytes.Repeat([]byte("abcdefgh"), ChunkSize/8+17) // 1 MiB + 17 bytes
	if err := os.WriteFile(path, payload, 0o600); err != nil {
		t.Fatalf("write failed: %v", err)
	}

	digest, err := HashFileChunks(path, ChunkSize)
	if err != nil {
		t.Fatalf("HashFileChunks failed: %v", err)
	}
	if digest.Size != int64(len(payload)) {
		t.Fatalf("size = %d, want %d", digest.Size, len(payload))
	}
	if digest.ChunkCount != 2 {
		t.Fatalf("chunk count = %d, want 2", digest.ChunkCount)
	}

	sum := sha256.Sum256(payload)
	if digest.FileHash != hex.EncodeToString(sum[:]) {
		t.Fatalf("file hash mismatch")
	}

	first := sha256.Sum256(payload[:ChunkSize])
	if digest.ChunkHashes[0] != hex.EncodeToString(first[:]) {
		t.Fatalf("first chunk hash mismatch")
	}
	second := sha256.Sum256(payload[ChunkSize:])
	if digest.ChunkHashes[1] != hex.EncodeToString(second[:]) {
		t.Fatalf("second chunk hash mismatch")
	}
}

func TestChunkEncryptDecryptAAD(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, 32)
	plain := bytes.Repeat([]byte("chunk-data"), 1024)
	nonce := ChunkNonce(7)
	aad := ChunkAAD("tx-abc", "session-1", 7, 12, 12345)

	ciphertext, err := EncryptChunkAAD(key, nonce, plain, aad)
	if err != nil {
		t.Fatalf("encrypt failed: %v", err)
	}
	if bytes.Equal(ciphertext, plain) {
		t.Fatalf("ciphertext equals plaintext")
	}

	got, err := DecryptChunkAAD(key, nonce, ciphertext, aad)
	if err != nil {
		t.Fatalf("decrypt failed: %v", err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatalf("round trip mismatch")
	}

	// Wrong index in the AAD must fail: this is what stops a relay from
	// reordering chunks.
	wrongAAD := ChunkAAD("tx-abc", "session-1", 8, 12, 12345)
	if _, err := DecryptChunkAAD(key, nonce, ciphertext, wrongAAD); err == nil {
		t.Fatalf("decrypt accepted mismatched AAD")
	}

	// Wrong session must fail too: this stops replay across attempts.
	wrongSession := ChunkAAD("tx-abc", "session-2", 7, 12, 12345)
	if _, err := DecryptChunkAAD(key, nonce, ciphertext, wrongSession); err == nil {
		t.Fatalf("decrypt accepted mismatched session")
	}

	// Tampering must fail.
	tampered := append([]byte(nil), ciphertext...)
	tampered[0] ^= 0xff
	if _, err := DecryptChunkAAD(key, nonce, tampered, aad); err == nil {
		t.Fatalf("decrypt accepted tampered ciphertext")
	}
}

func TestDeriveTransferIDIsStableAndOpaque(t *testing.T) {
	peerKey := []byte("peer-key-material")
	fileHash := "deadbeef"

	a := DeriveTransferID(peerKey, fileHash)
	b := DeriveTransferID(peerKey, fileHash)
	if a != b {
		t.Fatalf("transfer id is not deterministic")
	}
	if a == "tx-"+fileHash[:8] {
		t.Fatalf("transfer id leaks the file hash")
	}
	other := DeriveTransferID([]byte("different-peer-key"), fileHash)
	if a == other {
		t.Fatalf("transfer id does not depend on the peer key")
	}
}

func TestChunkLength(t *testing.T) {
	cases := []struct {
		index    int
		fileSize int64
		want     int
	}{
		{0, 0, 0},
		{0, 1, 1},
		{0, ChunkSize, ChunkSize},
		{0, ChunkSize + 1, ChunkSize},
		{1, ChunkSize + 1, 1},
		{2, ChunkSize + 1, 0},
	}
	for _, c := range cases {
		if got := chunkLength(c.index, c.fileSize); got != c.want {
			t.Fatalf("chunkLength(%d, %d) = %d, want %d", c.index, c.fileSize, got, c.want)
		}
	}
}

func TestReceiveStateRoundTrip(t *testing.T) {
	dir := t.TempDir()
	statePath := filepath.Join(dir, "state.json")

	state := NewReceiveState("tx-1", "hash-1", int64(ChunkSize*3+5), ChunkSize, 4)
	state.MarkHave(0, statePath)
	state.MarkHave(2, statePath)
	state.Save(statePath)

	loaded, err := LoadReceiveState(statePath, "tx-1", "hash-1")
	if err != nil {
		t.Fatalf("LoadReceiveState failed: %v", err)
	}
	have := loaded.Snapshot()
	if len(have) != 2 || have[0] != 0 || have[1] != 2 {
		t.Fatalf("have = %v, want [0 2]", have)
	}
	missing := loaded.Missing(4)
	if len(missing) != 2 || missing[0] != 1 || missing[1] != 3 {
		t.Fatalf("missing = %v, want [1 3]", missing)
	}

	if _, err := LoadReceiveState(statePath, "tx-1", "different-hash"); err == nil {
		t.Fatalf("LoadReceiveState accepted a mismatched file hash")
	}
}

func TestReceiveStateDropsInvalidIndices(t *testing.T) {
	dir := t.TempDir()
	statePath := filepath.Join(dir, "state.json")
	payload := []byte(`{"transfer_id":"tx-1","file_hash":"hash-1","file_size":10,"chunk_size":1048576,"chunk_count":2,"have":[0,0,1,5,-3]}`)
	if err := os.WriteFile(statePath, payload, 0o600); err != nil {
		t.Fatalf("write failed: %v", err)
	}
	state, err := LoadReceiveState(statePath, "tx-1", "hash-1")
	if err != nil {
		t.Fatalf("LoadReceiveState failed: %v", err)
	}
	have := state.Snapshot()
	if len(have) != 2 || have[0] != 0 || have[1] != 1 {
		t.Fatalf("have = %v, want [0 1]", have)
	}
}

func TestReceiveStatePersistsEveryEightChunks(t *testing.T) {
	dir := t.TempDir()
	statePath := filepath.Join(dir, "state.json")
	state := NewReceiveState("tx-1", "hash-1", int64(ChunkSize*20), ChunkSize, 20)
	state.Save(statePath)

	for i := 0; i < 8; i++ {
		state.MarkHave(i, statePath)
	}

	loaded, err := LoadReceiveState(statePath, "tx-1", "hash-1")
	if err != nil {
		t.Fatalf("LoadReceiveState failed: %v", err)
	}
	if got := len(loaded.Snapshot()); got != 8 {
		t.Fatalf("persisted %d chunks, want 8", got)
	}
}

func TestHaveHashIsOrderIndependent(t *testing.T) {
	a := HaveHash([]int{2, 0, 1})
	b := HaveHash([]int{0, 1, 2})
	if a != b {
		t.Fatalf("HaveHash depends on input order")
	}
	if HaveHash(nil) == HaveHash([]int{0}) {
		t.Fatalf("HaveHash collides for different sets")
	}
}

// TestChunkWireFormat pins the exact bytes the Kotlin client must produce.
// A mismatch here means Android and desktop silently cannot resume each
// other's transfers.
func TestChunkWireFormat(t *testing.T) {
	aad := string(ChunkAAD("tx-abc", "session-1", 7, 12, 12345))
	wantAAD := "chunk:v2:tx-abc:session-1:7:12:12345"
	if aad != wantAAD {
		t.Fatalf("AAD = %q, want %q", aad, wantAAD)
	}

	nonce := ChunkNonce(7)
	wantNonce := []byte{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7}
	if !bytes.Equal(nonce, wantNonce) {
		t.Fatalf("nonce = %v, want %v", nonce, wantNonce)
	}

	id := DeriveTransferID([]byte("peer-key"), "deadbeef")
	if !strings.HasPrefix(id, "tx-") || len(id) != 35 {
		t.Fatalf("transfer id = %q, want tx- plus 32 hex chars", id)
	}
}
