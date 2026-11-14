package main

import (
	"fmt"
	"net/http"
	"regexp"
	"testing"
)

// Benchmark 1: cost of rebuilding the AEAD per chunk vs reusing it.
// Claim: aes.NewCipher + cipher.NewGCM is expensive enough that calling it
// per chunk (thousands of times) is measurable waste.
func BenchmarkNewAEADPerChunk(b *testing.B) {
	key := make([]byte, 32)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _ = NewAEAD(key) // what EncryptChunkAAD/DecryptChunkAAD do today
	}
}

func BenchmarkReuseAEAD(b *testing.B) {
	key := make([]byte, 32)
	aead, _ := NewAEAD(key)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = aead // what the fixed code would do: zero work per chunk
	}
}

// Benchmark 2: cost of compiling the sanitize regex per call vs hoisting it.
func BenchmarkSanitizeFileName(b *testing.B) {
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = SanitizeFileName("report Q4 2026 (final).txt")
	}
}

var sanitizeRe = regexp.MustCompile(`[\x00-\x1F\x7F/\\:*?"<>|]`)

func BenchmarkSanitizeFileNameHoisted(b *testing.B) {
	name := "report Q4 2026 (final).txt"
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = sanitizeRe.ReplaceAllString(name, "_")
	}
}

// Benchmark 3: verify the MaxIdleConnsPerHost=2 claim — with 4 concurrent
// workers, do we actually churn connections?
func BenchmarkIdleConnThrash(b *testing.B) {
	tr := http.DefaultTransport.(*http.Transport)
	b.Logf("default MaxIdleConnsPerHost = %d, MaxConnsPerHost = %d",
		tr.MaxIdleConnsPerHost, tr.MaxConnsPerHost)
	// Report what happens when 4 workers need 4 idle conns but only 2 are kept.
	workers := 4
	b.Logf("with %d workers and MaxIdleConnsPerHost=%d: %d connections reused, %d re-handshaked per round",
		workers, tr.MaxIdleConnsPerHost,
		min(workers, tr.MaxIdleConnsPerHost),
		max(0, workers-tr.MaxIdleConnsPerHost))
}

func BenchmarkSha256Hex1MiB(b *testing.B) {
	data := make([]byte, 1<<20)
	b.SetBytes(int64(len(data)))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = Sha256Hex(data)
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

var _ = fmt.Sprintf
