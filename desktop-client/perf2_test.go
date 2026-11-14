package main

import (
	"crypto/rand"
	"fmt"
	"testing"
)

// Real-world ratio: full EncryptChunkAAD (rebuilds AEAD, as the code does
// today) vs encrypting with a pre-built AEAD, on a realistic 1 MiB chunk.
func BenchmarkEncryptChunkAADPerChunk1MiB(b *testing.B) {
	key := make([]byte, 32)
	plain := make([]byte, ChunkSize)
	rand.Read(plain)
	nonce := ChunkNonce(0)
	aad := ChunkAAD("tx", "sess", 0, 1, int64(len(plain)))
	b.SetBytes(int64(len(plain)))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _ = EncryptChunkAAD(key, nonce, plain, aad)
	}
}

func BenchmarkEncryptChunkAADReused1MiB(b *testing.B) {
	key := make([]byte, 32)
	aead, _ := NewAEAD(key)
	plain := make([]byte, ChunkSize)
	rand.Read(plain)
	nonce := ChunkNonce(0)
	aad := ChunkAAD("tx", "sess", 0, 1, int64(len(plain)))
	b.SetBytes(int64(len(plain)))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = aead.Seal(nil, nonce, plain, aad)
	}
}

var _ = fmt.Sprintf
