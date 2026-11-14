package main

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
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
	// chunkDirMarker names the sentinel file that marks a directory as
	// gaur-relay chunk staging. The boot-time wipe only clears
	// directories that are empty or carry this marker, so a mistyped
	// -chunk-dir holding unrelated data is refused instead of deleted.
	chunkDirMarker = ".gaur-relay-chunks"
)

// Store error sentinels. The chunk handlers map each class to an honest
// status code: errOversized → 413, errCapExceeded → 507, errDiskIO →
// 500; everything else is a validation error → 400.
var (
	errOversized   = errors.New("chunk exceeds the maximum chunk size")
	errCapExceeded = errors.New("chunk store is at its byte cap")
	errDiskIO      = errors.New("chunk staging I/O failed")
)

// chunkEvictInterval is how often the store sweeps idle transfers.
// Package var so tests can shrink it; each store copies the current
// value when it is constructed.
var chunkEvictInterval = 30 * time.Second

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

// NewChunkStore prepares the staging directory and returns the store.
// The error is non-nil only when dir is configured but cannot be used
// safely: a non-empty directory without the gaur-relay marker is
// refused instead of wiped, and a path that cannot be inspected (for
// example a plain file) is an error rather than a silent memory
// fallback. main surfaces it with log.Fatal.
func NewChunkStore(dir string, maxBytes int64, ttl time.Duration) (*ChunkStore, error) {
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
		if err := checkChunkDir(dir); err != nil {
			return nil, err
		}
		// The in-memory index is lost on restart, so any chunks left on
		// disk are unreachable. Clear them at boot instead of leaking
		// disk forever; checkChunkDir has verified the directory is
		// either empty or ours.
		if err := os.RemoveAll(dir); err != nil {
			log.Printf("[CHUNK] Cannot clear chunk dir %s: %v", dir, err)
		}
		if err := os.MkdirAll(dir, 0o700); err != nil {
			log.Printf("[CHUNK] Cannot create chunk dir %s: %v (using memory)", dir, err)
			s.dir = ""
		} else if err := os.WriteFile(filepath.Join(dir, chunkDirMarker), nil, 0o600); err != nil {
			log.Printf("[CHUNK] Cannot write %s marker in %s: %v (using memory)", chunkDirMarker, dir, err)
			s.dir = ""
		}
	}
	// Copy the interval synchronously: reading the package var inside
	// the goroutine would race with tests that shrink it after the
	// constructor returns but before the goroutine is first scheduled.
	evictInterval := chunkEvictInterval
	go s.evictLoop(evictInterval)
	return s, nil
}

// checkChunkDir gates the boot-time wipe. A directory the relay may
// clear is one that does not exist yet, is empty, or carries the
// gaur-relay marker from a previous boot. Anything else — including a
// plain file at the path — is refused with an error naming the
// directory and the marker.
func checkChunkDir(dir string) error {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil // nothing to protect yet
		}
		return fmt.Errorf("cannot inspect chunk dir %s: %w", dir, err)
	}
	if len(entries) == 0 {
		return nil
	}
	for _, e := range entries {
		if e.Name() == chunkDirMarker {
			return nil
		}
	}
	return fmt.Errorf("refusing to clear chunk dir %s: it is not empty and has no %s marker; move its contents or point -chunk-dir at a dedicated directory", dir, chunkDirMarker)
}

func (s *ChunkStore) Close() {
	s.closeOnce.Do(func() {
		close(s.done)
	})
}

func (s *ChunkStore) evictLoop(interval time.Duration) {
	ticker := time.NewTicker(interval)
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

// createTransferLocked admits a new transfer and prepares its staging
// directory. Callers must hold s.mu and have already enforced the byte
// cap: a transfer becomes "known" — and therefore exempt from the cap —
// only by being admitted here.
func (s *ChunkStore) createTransferLocked(key string) (*chunkTransfer, error) {
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
			return nil, fmt.Errorf("%w: create transfer dir: %w", errDiskIO, err)
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
//
// The byte cap is an admission gate: once the store is at its cap, PUTs
// for transfers without staged chunks are refused (507 in the HTTP
// layer), while transfers already admitted may always continue so an
// in-flight transfer can finish, bounded by the per-transfer chunk
// ceiling.
func (s *ChunkStore) Put(key string, index int, data []byte) error {
	if !safeTransferKey.MatchString(key) {
		return fmt.Errorf("invalid transfer key")
	}
	if index < 0 || index >= maxChunksPerTransfer {
		return fmt.Errorf("invalid chunk index %d", index)
	}
	if len(data) == 0 {
		return fmt.Errorf("invalid chunk size %d", len(data))
	}
	if len(data) > maxChunkSize {
		return fmt.Errorf("%w: %d bytes (max %d)", errOversized, len(data), maxChunkSize)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	t, known := s.transfers[key]
	if !known && s.usedBytes >= s.maxBytes {
		// Recorded ownership must not count as admission: the handler
		// records the owner before the body is read, so treating it as
		// "known" would let a rejected PUT succeed on retry and bypass
		// the cap entirely.
		return fmt.Errorf("%w: %d of %d bytes staged; new transfers are refused until space frees", errCapExceeded, s.usedBytes, s.maxBytes)
	}
	if !known {
		var err error
		t, err = s.createTransferLocked(key)
		if err != nil {
			return err
		}
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
		// Remove the staging file on every exit path. After a successful
		// rename the path no longer exists and the remove fails
		// silently; after a failed write or rename it reclaims the
		// space instead of leaking .tmp files.
		defer os.Remove(tmp)
		if err := os.WriteFile(tmp, data, 0o600); err != nil {
			return fmt.Errorf("%w: write chunk: %w", errDiskIO, err)
		}
		if err := os.Rename(tmp, final); err != nil {
			return fmt.Errorf("%w: commit chunk: %w", errDiskIO, err)
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

// Present reports which indices (0..count-1) are already staged. Like
// Put and Get it refreshes the transfer's idle timestamp: a receiver
// that only polls status is actively using the transfer and must not be
// TTL-swept mid-download.
func (s *ChunkStore) Present(key string, count int) []int {
	s.mu.Lock()
	defer s.mu.Unlock()
	t, ok := s.transfers[key]
	if !ok {
		return []int{}
	}
	t.last = time.Now()
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
