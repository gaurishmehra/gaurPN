package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"
)

const (
	// maxChunkSize bounds a single uploaded ciphertext chunk. Plaintext chunks
	// are 1 MiB; GCM adds a 16-byte tag. The headroom lets clients raise the
	// chunk size later without a relay change.
	maxChunkSize = 4 << 20
	// maxChunksPerTransfer bounds the index space (64 GiB at 1 MiB chunks).
	maxChunksPerTransfer = 65536
)

// Transfer keys are "<transfer_id>/<session_id>". Both halves are generated
// from hex or random bytes, so this pattern doubles as path-traversal defence.
var safeTransferKey = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}/[A-Za-z0-9_-]{1,128}$`)

type chunkTransfer struct {
	key     string
	dir     string
	chunks  map[int]int64
	mem     map[int][]byte
	bytes   int64
	created time.Time
	last    time.Time
}

// ChunkStore stages encrypted chunks for resumable transfers. It is a cache,
// not durable storage: chunks are dropped on TTL, on eviction, and on relay
// restart. Clients always hold the authoritative resume bitmap, so losing the
// store only costs a re-upload.
type ChunkStore struct {
	mu        sync.Mutex
	dir       string
	maxBytes  int64
	usedBytes int64
	ttl       time.Duration
	transfers map[string]*chunkTransfer
	// owners tracks per-transfer ownership for the access policy in
	// Hub.authorizeChunkAccess. It lives here so removeLocked can clean it
	// up in lockstep with the transfer data.
	owners    *Ownership
	done      chan struct{}
	closeOnce sync.Once
}

func NewChunkStore(dir string, maxBytes int64, ttl time.Duration) *ChunkStore {
	if maxBytes <= 0 {
		maxBytes = 8 << 30
	}
	if ttl <= 0 {
		ttl = 24 * time.Hour
	}
	s := &ChunkStore{
		dir:       dir,
		maxBytes:  maxBytes,
		ttl:       ttl,
		transfers: make(map[string]*chunkTransfer),
		owners:    NewOwnership(maxTrackedTransfers),
		done:      make(chan struct{}),
	}
	if dir != "" {
		// The in-memory index is lost on restart, so any chunks left on disk
		// are unreachable. Clear them at boot instead of leaking disk forever.
		if err := os.RemoveAll(dir); err != nil {
			log.Printf("[CHUNK] Cannot clear chunk dir %s: %v", dir, err)
		}
		if err := os.MkdirAll(dir, 0o700); err != nil {
			log.Printf("[CHUNK] Cannot create chunk dir %s: %v (using memory)", dir, err)
			s.dir = ""
		}
	}
	go s.evictLoop()
	return s
}

func (s *ChunkStore) Close() {
	s.closeOnce.Do(func() {
		close(s.done)
	})
}

func (s *ChunkStore) evictLoop() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			s.mu.Lock()
			now := time.Now()
			for key, t := range s.transfers {
				if now.Sub(t.last) > s.ttl {
					s.removeLocked(key)
					log.Printf("[CHUNK] Evicted idle transfer %s", key)
				}
			}
			s.mu.Unlock()
		case <-s.done:
			return
		}
	}
}

func (s *ChunkStore) removeLocked(key string) {
	// Ownership is policy state for the transfer's data; it must not outlive
	// the data, whether the removal is an explicit DELETE, a TTL expiry, or
	// a byte-cap eviction.
	s.owners.Forget(key)
	t, ok := s.transfers[key]
	if !ok {
		return
	}
	delete(s.transfers, key)
	s.usedBytes -= t.bytes
	if t.dir != "" {
		_ = os.RemoveAll(t.dir)
	}
}

// Owner returns the device that claimed the transfer, if ownership was
// recorded on its first PUT.
func (s *ChunkStore) Owner(key string) (string, bool) {
	return s.owners.Owner(key)
}

// RecordOwner makes device the owner of key if none is recorded yet.
// Ownership is write-once and never overwritten.
func (s *ChunkStore) RecordOwner(key, device string) {
	s.owners.Record(key, device)
}

// NoteOpenMode emits the one-time legacy open-mode deprecation log for key.
func (s *ChunkStore) NoteOpenMode(key string) {
	s.owners.NoteOpenMode(key)
}

// evictForLocked makes room for need more bytes, never evicting exclude.
func (s *ChunkStore) evictForLocked(need int64, exclude string) {
	for s.usedBytes+need > s.maxBytes {
		var victim *chunkTransfer
		for key, t := range s.transfers {
			if key == exclude {
				continue
			}
			if victim == nil || t.last.Before(victim.last) {
				victim = t
			}
		}
		if victim == nil {
			return
		}
		s.removeLocked(victim.key)
	}
}

func (s *ChunkStore) transferLocked(key string) (*chunkTransfer, error) {
	if t, ok := s.transfers[key]; ok {
		return t, nil
	}
	t := &chunkTransfer{
		key:     key,
		chunks:  make(map[int]int64),
		mem:     make(map[int][]byte),
		created: time.Now(),
		last:    time.Now(),
	}
	if s.dir != "" {
		sum := sha256.Sum256([]byte(key))
		t.dir = filepath.Join(s.dir, hex.EncodeToString(sum[:16]))
		if err := os.MkdirAll(t.dir, 0o700); err != nil {
			return nil, fmt.Errorf("create transfer dir: %w", err)
		}
	}
	s.transfers[key] = t
	return t, nil
}

func chunkPath(t *chunkTransfer, index int) string {
	return filepath.Join(t.dir, fmt.Sprintf("%08d.chunk", index))
}

// Put stores one ciphertext chunk. Re-uploading the same index replaces it,
// which lets a sender retry a chunk without inflating the byte count.
func (s *ChunkStore) Put(key string, index int, data []byte) error {
	if !safeTransferKey.MatchString(key) {
		return fmt.Errorf("invalid transfer key")
	}
	if index < 0 || index >= maxChunksPerTransfer {
		return fmt.Errorf("invalid chunk index %d", index)
	}
	if len(data) == 0 || len(data) > maxChunkSize {
		return fmt.Errorf("invalid chunk size %d", len(data))
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	t, err := s.transferLocked(key)
	if err != nil {
		return err
	}

	old := t.chunks[index]
	delta := int64(len(data)) - old
	if delta > 0 {
		s.evictForLocked(delta, key)
		// Eviction never removes the active key, so t is still valid.
	}

	if s.dir != "" {
		final := chunkPath(t, index)
		tmp := final + ".tmp"
		if err := os.WriteFile(tmp, data, 0o600); err != nil {
			return fmt.Errorf("write chunk: %w", err)
		}
		if err := os.Rename(tmp, final); err != nil {
			_ = os.Remove(tmp)
			return fmt.Errorf("commit chunk: %w", err)
		}
	} else {
		cp := make([]byte, len(data))
		copy(cp, data)
		t.mem[index] = cp
	}

	t.chunks[index] = int64(len(data))
	t.bytes += delta
	t.last = time.Now()
	s.usedBytes += delta
	return nil
}

// Get returns a copy of the ciphertext chunk. The copy keeps callers from
// mutating the in-memory store while another request reads it.
func (s *ChunkStore) Get(key string, index int) ([]byte, bool) {
	s.mu.Lock()
	t, ok := s.transfers[key]
	if !ok {
		s.mu.Unlock()
		return nil, false
	}
	size, ok := t.chunks[index]
	if !ok {
		s.mu.Unlock()
		return nil, false
	}
	t.last = time.Now()
	path := ""
	var mem []byte
	if s.dir != "" {
		path = chunkPath(t, index)
	} else {
		mem = t.mem[index]
	}
	s.mu.Unlock()

	if s.dir != "" {
		data, err := os.ReadFile(path)
		if err != nil || int64(len(data)) != size {
			return nil, false
		}
		return data, true
	}
	cp := make([]byte, len(mem))
	copy(cp, mem)
	return cp, true
}

// Present reports which indices (0..count-1) are already staged.
func (s *ChunkStore) Present(key string, count int) []int {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.transfers[key]
	if !ok {
		return []int{}
	}
	out := make([]int, 0, len(t.chunks))
	for i := 0; i < count; i++ {
		if _, ok := t.chunks[i]; ok {
			out = append(out, i)
		}
	}
	return out
}

func (s *ChunkStore) Delete(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.removeLocked(key)
}

func (s *ChunkStore) Stats() (transfers int, bytes int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.transfers), s.usedBytes
}
