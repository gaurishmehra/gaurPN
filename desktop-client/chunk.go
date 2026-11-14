package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// ChunkSize is the plaintext chunk size. 1 MiB keeps per-chunk overhead
	// low without holding a large buffer per worker.
	ChunkSize = 1 << 20
	// MaxChunkSize is the relay's hard limit on a ciphertext chunk.
	MaxChunkSize = 4 << 20
	// ChunkConcurrency bounds parallel uploads and downloads.
	ChunkConcurrency = 4

	uploadRetries   = 6
	downloadRetries = 30
)

// ReceiveState is the durable resume bitmap for one incoming transfer. It is
// written next to the .part file so a killed daemon can pick up where it left
// off without re-downloading verified chunks.
type ReceiveState struct {
	TransferID string `json:"transfer_id"`
	FileHash   string `json:"file_hash"`
	FileSize   int64  `json:"file_size"`
	ChunkSize  int    `json:"chunk_size"`
	ChunkCount int    `json:"chunk_count"`
	Have       []int  `json:"have"`

	mu         sync.Mutex
	dirty      bool
	dirtyCount int
	last       time.Time
}

func LoadReceiveState(path, transferID, fileHash string) (*ReceiveState, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var state ReceiveState
	if err := json.Unmarshal(data, &state); err != nil {
		return nil, err
	}
	if state.TransferID != transferID || state.FileHash != fileHash {
		return nil, fmt.Errorf("resume state does not match transfer")
	}
	if state.ChunkCount <= 0 || state.ChunkSize <= 0 || state.FileSize < 0 {
		return nil, fmt.Errorf("resume state is invalid")
	}
	seen := make(map[int]bool, len(state.Have))
	clean := state.Have[:0]
	for _, idx := range state.Have {
		if idx >= 0 && idx < state.ChunkCount && !seen[idx] {
			seen[idx] = true
			clean = append(clean, idx)
		}
	}
	sort.Ints(clean)
	state.Have = clean
	return &state, nil
}

func NewReceiveState(transferID, fileHash string, fileSize int64, chunkSize, chunkCount int) *ReceiveState {
	return &ReceiveState{
		TransferID: transferID,
		FileHash:   fileHash,
		FileSize:   fileSize,
		ChunkSize:  chunkSize,
		ChunkCount: chunkCount,
		Have:       make([]int, 0, chunkCount),
	}
}

func (s *ReceiveState) Has(index int) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, idx := range s.Have {
		if idx == index {
			return true
		}
	}
	return false
}

// Snapshot returns a copy of the resume bitmap. Callers must not mutate Have
// directly while a download worker may be appending to it.
func (s *ReceiveState) Snapshot() []int {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]int, len(s.Have))
	copy(out, s.Have)
	sort.Ints(out)
	return out
}

func (s *ReceiveState) Missing(chunkCount int) []int {
	s.mu.Lock()
	defer s.mu.Unlock()
	seen := make(map[int]bool, len(s.Have))
	for _, idx := range s.Have {
		seen[idx] = true
	}
	missing := make([]int, 0, chunkCount-len(s.Have))
	for i := 0; i < chunkCount; i++ {
		if !seen[i] {
			missing = append(missing, i)
		}
	}
	return missing
}

// MarkHave records a verified chunk and periodically flushes the bitmap. The
// throttle keeps a fast transfer from fsyncing once per megabyte.
func (s *ReceiveState) MarkHave(index int, path string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, idx := range s.Have {
		if idx == index {
			return
		}
	}
	s.Have = append(s.Have, index)
	s.dirty = true
	s.dirtyCount++
	// Persist every 8 chunks or every second, whichever comes first. The
	// chunk threshold keeps a short-lived transfer from losing its bitmap
	// before the first time-based flush.
	if s.dirtyCount >= 8 || time.Since(s.last) >= time.Second {
		s.saveLocked(path)
		s.dirtyCount = 0
	}
}

func (s *ReceiveState) Save(path string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.saveLocked(path)
}

func (s *ReceiveState) saveLocked(path string) {
	sort.Ints(s.Have)
	payload, err := json.Marshal(struct {
		TransferID string `json:"transfer_id"`
		FileHash   string `json:"file_hash"`
		FileSize   int64  `json:"file_size"`
		ChunkSize  int    `json:"chunk_size"`
		ChunkCount int    `json:"chunk_count"`
		Have       []int  `json:"have"`
	}{
		TransferID: s.TransferID,
		FileHash:   s.FileHash,
		FileSize:   s.FileSize,
		ChunkSize:  s.ChunkSize,
		ChunkCount: s.ChunkCount,
		Have:       s.Have,
	})
	if err != nil {
		return
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, payload, 0o600); err != nil {
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return
	}
	s.dirty = false
	s.last = time.Now()
}

func chunkLength(index int, fileSize int64) int {
	offset := int64(index) * ChunkSize
	remaining := fileSize - offset
	if remaining <= 0 {
		return 0
	}
	if remaining > ChunkSize {
		return ChunkSize
	}
	return int(remaining)
}

func chunkEndpoint(cfg Config, transferID, sessionID string, index int) string {
	return fmt.Sprintf("http://%s/chunk?transfer_id=%s&session_id=%s&index=%d",
		cfg.RelayAddr, url.QueryEscape(transferID), url.QueryEscape(sessionID), index)
}

func putChunk(cfg Config, relayToken, transferID, sessionID string, index int, data []byte) error {
	req, err := http.NewRequest(http.MethodPut, chunkEndpoint(cfg, transferID, sessionID, index), bytes.NewReader(data))
	if err != nil {
		return err
	}
	req.Header.Set("X-Gaur-Token", relayToken)
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("relay returned HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	return nil
}

func getChunk(cfg Config, relayToken, transferID, sessionID string, index int) ([]byte, bool, error) {
	req, err := http.NewRequest(http.MethodGet, chunkEndpoint(cfg, transferID, sessionID, index), nil)
	if err != nil {
		return nil, false, err
	}
	req.Header.Set("X-Gaur-Token", relayToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return nil, false, nil
	}
	if resp.StatusCode != http.StatusOK {
		return nil, false, fmt.Errorf("relay returned HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, MaxChunkSize+1))
	if err != nil {
		return nil, false, err
	}
	if len(data) > MaxChunkSize {
		return nil, false, fmt.Errorf("chunk %d exceeds relay limit", index)
	}
	return data, true, nil
}

func fetchChunkWithRetry(ctx context.Context, cfg Config, relayToken, transferID, sessionID string, index int) ([]byte, error) {
	backoff := 100 * time.Millisecond
	var lastErr error
	for attempt := 0; attempt < downloadRetries; attempt++ {
		data, ok, err := getChunk(cfg, relayToken, transferID, sessionID, index)
		if err == nil && ok {
			return data, nil
		}
		if err != nil {
			lastErr = err
		} else {
			lastErr = fmt.Errorf("chunk %d not uploaded yet", index)
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(backoff):
		}
		if backoff < 2*time.Second {
			backoff *= 2
		}
	}
	return nil, lastErr
}

func deleteTransfer(cfg Config, relayToken, transferID, sessionID string) {
	endpoint := fmt.Sprintf("http://%s/transfer?transfer_id=%s&session_id=%s",
		cfg.RelayAddr, url.QueryEscape(transferID), url.QueryEscape(sessionID))
	req, err := http.NewRequest(http.MethodDelete, endpoint, nil)
	if err != nil {
		return
	}
	req.Header.Set("X-Gaur-Token", relayToken)
	resp, err := http.DefaultClient.Do(req)
	if err == nil {
		_ = resp.Body.Close()
	}
}

// uploadChunks reads the file at arbitrary offsets and pushes only the chunks
// the receiver does not already have. Positional reads make the worker pool
// safe without a shared file cursor.
func uploadChunks(cfg Config, relayToken, transferID, sessionID string, digest *FileDigest, aesKey []byte, missing []int, path string, onProgress func(done, total int64)) error {
	if len(missing) == 0 {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()

	var total int64
	for _, idx := range missing {
		total += int64(chunkLength(idx, digest.Size))
	}
	var done atomic.Int64

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	errCh := make(chan error, 1)
	fail := func(err error) {
		select {
		case errCh <- err:
		default:
		}
		cancel()
	}

	sem := make(chan struct{}, ChunkConcurrency)
	var wg sync.WaitGroup
	for _, idx := range missing {
		idx := idx
		wg.Add(1)
		go func() {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-sem }()

			length := chunkLength(idx, digest.Size)
			plain := make([]byte, length)
			readN, readErr := f.ReadAt(plain, int64(idx)*ChunkSize)
			if readErr != nil && readErr != io.EOF {
				fail(fmt.Errorf("read chunk %d: %w", idx, readErr))
				return
			}
			if readN != length {
				fail(fmt.Errorf("read chunk %d: got %d bytes, want %d", idx, readN, length))
				return
			}
			if got := Sha256Hex(plain); got != digest.ChunkHashes[idx] {
				fail(fmt.Errorf("chunk %d hash mismatch before upload", idx))
				return
			}
			nonce := ChunkNonce(idx)
			aad := ChunkAAD(transferID, sessionID, idx, digest.ChunkCount, digest.Size)
			ciphertext, err := EncryptChunkAAD(aesKey, nonce, plain, aad)
			if err != nil {
				fail(fmt.Errorf("encrypt chunk %d: %w", idx, err))
				return
			}
			if err := putChunk(cfg, relayToken, transferID, sessionID, idx, ciphertext); err != nil {
				fail(fmt.Errorf("upload chunk %d: %w", idx, err))
				return
			}
			n := done.Add(int64(length))
			if onProgress != nil {
				onProgress(n, total)
			}
		}()
	}
	wg.Wait()
	select {
	case err := <-errCh:
		return err
	default:
		return nil
	}
}

func downloadChunks(ctx context.Context, cfg Config, relayToken, transferID, sessionID string, digest *FileDigest, aesKey []byte, missing []int, partPath string, state *ReceiveState, statePath string, onProgress func(done, total int64)) error {
	if len(missing) == 0 {
		return nil
	}
	f, err := os.OpenFile(partPath, os.O_CREATE|os.O_RDWR, 0o644)
	if err != nil {
		return err
	}
	defer f.Close()

	var total int64
	for _, idx := range missing {
		total += int64(chunkLength(idx, digest.Size))
	}
	var done atomic.Int64

	dlCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	errCh := make(chan error, 1)
	fail := func(err error) {
		select {
		case errCh <- err:
		default:
		}
		cancel()
	}

	sem := make(chan struct{}, ChunkConcurrency)
	var wg sync.WaitGroup
	for _, idx := range missing {
		idx := idx
		wg.Add(1)
		go func() {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
			case <-dlCtx.Done():
				return
			}
			defer func() { <-sem }()

			ciphertext, err := fetchChunkWithRetry(dlCtx, cfg, relayToken, transferID, sessionID, idx)
			if err != nil {
				fail(err)
				return
			}
			nonce := ChunkNonce(idx)
			aad := ChunkAAD(transferID, sessionID, idx, digest.ChunkCount, digest.Size)
			plain, err := DecryptChunkAAD(aesKey, nonce, ciphertext, aad)
			if err != nil {
				fail(fmt.Errorf("chunk %d failed authentication: %w", idx, err))
				return
			}
			length := chunkLength(idx, digest.Size)
			if len(plain) != length {
				fail(fmt.Errorf("chunk %d length = %d, want %d", idx, len(plain), length))
				return
			}
			if got := Sha256Hex(plain); got != digest.ChunkHashes[idx] {
				fail(fmt.Errorf("chunk %d content hash mismatch", idx))
				return
			}
			written, writeErr := f.WriteAt(plain, int64(idx)*ChunkSize)
			if writeErr != nil {
				fail(fmt.Errorf("write chunk %d: %w", idx, writeErr))
				return
			}
			if written != length {
				fail(fmt.Errorf("write chunk %d: wrote %d bytes, want %d", idx, written, length))
				return
			}
			state.MarkHave(idx, statePath)
			n := done.Add(int64(length))
			if onProgress != nil {
				onProgress(n, total)
			}
		}()
	}
	wg.Wait()
	select {
	case err := <-errCh:
		return err
	default:
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	return nil
}

func VerifyFileHash(path, expected string) (bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return false, err
	}
	return hex.EncodeToString(h.Sum(nil)) == expected, nil
}
