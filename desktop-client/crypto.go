package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/hkdf"
)

type KeyPair struct {
	Private *ecdh.PrivateKey
	Public  []byte
}

func GenerateKeyPair() (*KeyPair, error) {
	curve := ecdh.P256()
	priv, err := curve.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate key error: %w", err)
	}
	pub := priv.PublicKey().Bytes()
	return &KeyPair{Private: priv, Public: pub}, nil
}

func DeriveSharedSecret(priv *ecdh.PrivateKey, peerPubBytes []byte) ([]byte, error) {
	curve := ecdh.P256()
	peerPub, err := curve.NewPublicKey(peerPubBytes)
	if err != nil {
		return nil, fmt.Errorf("invalid peer public key: %w", err)
	}
	rawSecret, err := priv.ECDH(peerPub)
	if err != nil {
		return nil, fmt.Errorf("ecdh agreement failed: %w", err)
	}

	// HKDF-SHA256 to derive a high-entropy 32-byte AES-256 key
	hkdfReader := hkdf.New(sha256.New, rawSecret, []byte("gaur-share-salt"), []byte("gaur-share-session-aes256"))
	aesKey := make([]byte, 32)
	if _, err := io.ReadFull(hkdfReader, aesKey); err != nil {
		return nil, fmt.Errorf("hkdf failed: %w", err)
	}
	return aesKey, nil
}

// NewAEAD builds the AES-256-GCM AEAD once per transfer. Callers reuse it for
// every chunk instead of rebuilding the cipher for each 64 KiB block.
func NewAEAD(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

func EncryptChunk(key []byte, nonce []byte, plaintext []byte) ([]byte, error) {
	gcm, err := NewAEAD(key)
	if err != nil {
		return nil, err
	}
	return gcm.Seal(nil, nonce, plaintext, nil), nil
}

func DecryptChunk(key []byte, nonce []byte, ciphertext []byte) ([]byte, error) {
	gcm, err := NewAEAD(key)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, nonce, ciphertext, nil)
}

// FileDigest is the content-addressed manifest for one file. The transfer id
// is derived from FileHash, so the same bytes always resume the same transfer
// while the relay only ever sees an opaque HMAC.
type FileDigest struct {
	FileHash    string
	ChunkHashes []string
	ChunkCount  int
	Size        int64
}

// HashFileChunks reads path once and returns the whole-file SHA-256 plus a
// SHA-256 per fixed-size chunk. Chunk boundaries are fixed, so a resumed
// transfer can address any chunk by index.
func HashFileChunks(path string, chunkSize int) (*FileDigest, error) {
	if chunkSize <= 0 {
		chunkSize = ChunkSize
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	fileHash := sha256.New()
	buf := make([]byte, chunkSize)
	hashes := make([]string, 0)
	var total int64
	for {
		n, readErr := io.ReadFull(f, buf)
		if n > 0 {
			fileHash.Write(buf[:n])
			sum := sha256.Sum256(buf[:n])
			hashes = append(hashes, hex.EncodeToString(sum[:]))
			total += int64(n)
		}
		if readErr == io.EOF || readErr == io.ErrUnexpectedEOF {
			break
		}
		if readErr != nil {
			return nil, readErr
		}
	}
	return &FileDigest{
		FileHash:    hex.EncodeToString(fileHash.Sum(nil)),
		ChunkHashes: hashes,
		ChunkCount:  len(hashes),
		Size:        total,
	}, nil
}

// DeriveTransferID binds a file's content hash to the cluster peer key. The
// relay sees only this opaque value, never the raw content hash.
func DeriveTransferID(peerCryptoKey []byte, fileHash string) string {
	mac := hmac.New(sha256.New, peerCryptoKey)
	mac.Write([]byte("gaur-transfer-id-v1"))
	mac.Write([]byte(fileHash))
	return "tx-" + hex.EncodeToString(mac.Sum(nil))[:32]
}

// DeriveSessionID returns the deterministic v3 session id: the same file
// always resumes the same session, so staged ciphertext stays valid across
// sender retries. 16 lowercase hex chars.
func DeriveSessionID(peerKey []byte, transferID string) string {
	mac := hmac.New(sha256.New, peerKey)
	mac.Write([]byte("gaur-session-v3:" + transferID))
	return hex.EncodeToString(mac.Sum(nil))[:16]
}

// ChunkNonce is the 96-bit GCM nonce for a chunk: four zero bytes followed by
// the big-endian chunk index. The index is unique per transfer, so the nonce
// never repeats under the ephemeral session key.
func ChunkNonce(index int) []byte {
	nonce := make([]byte, 12)
	binary.BigEndian.PutUint64(nonce[4:], uint64(index))
	return nonce
}

// ChunkAAD binds a chunk to its transfer, session, index, count, and file
// size. A relay cannot move a chunk to another transfer or reorder it without
// failing authentication.
func ChunkAAD(transferID, sessionID string, index, count int, fileSize int64) []byte {
	return []byte(fmt.Sprintf("chunk:v2:%s:%s:%d:%d:%d", transferID, sessionID, index, count, fileSize))
}

func EncryptChunkAAD(key, nonce, plaintext, aad []byte) ([]byte, error) {
	gcm, err := NewAEAD(key)
	if err != nil {
		return nil, err
	}
	return gcm.Seal(nil, nonce, plaintext, aad), nil
}

func DecryptChunkAAD(key, nonce, ciphertext, aad []byte) ([]byte, error) {
	gcm, err := NewAEAD(key)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, nonce, ciphertext, aad)
}

// HaveHash commits to the receiver's resume bitmap so a relay cannot quietly
// strip chunks from the accept message.
func HaveHash(have []int) string {
	sorted := append([]int(nil), have...)
	sort.Ints(sorted)
	parts := make([]string, len(sorted))
	for i, idx := range sorted {
		parts[i] = strconv.Itoa(idx)
	}
	sum := sha256.Sum256([]byte(strings.Join(parts, ",")))
	return hex.EncodeToString(sum[:])
}

func DeriveSubKeys(clusterToken string) (relayToken string, peerCryptoKey []byte) {
	tokenBytes := []byte(clusterToken)

	macRelay := hmac.New(sha256.New, tokenBytes)
	macRelay.Write([]byte("gaur-relay-auth-v1"))
	relayToken = hex.EncodeToString(macRelay.Sum(nil))

	macPeer := hmac.New(sha256.New, tokenBytes)
	macPeer.Write([]byte("gaur-peer-auth-v1"))
	peerCryptoKey = macPeer.Sum(nil)

	return relayToken, peerCryptoKey
}

// DeriveClipboardKey domain-separates clipboard traffic from file-transfer
// traffic. Both sides derive it from K_peer, never from the raw cluster token.
func DeriveClipboardKey(peerCryptoKey []byte) []byte {
	mac := hmac.New(sha256.New, peerCryptoKey)
	mac.Write([]byte("gaur-clipboard-v1"))
	return mac.Sum(nil)
}

// DeriveTextKey domain-separates one-shot text/link shares from both file
// transfers and clipboard sync, so a message cannot be replayed across
// channels.
func DeriveTextKey(peerCryptoKey []byte) []byte {
	mac := hmac.New(sha256.New, peerCryptoKey)
	mac.Write([]byte("gaur-text-v1"))
	return mac.Sum(nil)
}

// DeriveNotificationKey domain-separates mirrored notification payloads from
// file transfers, clipboard sync, and text shares.
func DeriveNotificationKey(peerCryptoKey []byte) []byte {
	mac := hmac.New(sha256.New, peerCryptoKey)
	mac.Write([]byte("gaur-notification-v1"))
	return mac.Sum(nil)
}

func RandomBytes(n int) ([]byte, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	return b, nil
}

func EncryptClipboard(key, nonce, plaintext, aad []byte) ([]byte, error) {
	aead, err := NewAEAD(key)
	if err != nil {
		return nil, err
	}
	return aead.Seal(nil, nonce, plaintext, aad), nil
}

func DecryptClipboard(key, nonce, ciphertext, aad []byte) ([]byte, error) {
	aead, err := NewAEAD(key)
	if err != nil {
		return nil, err
	}
	return aead.Open(nil, nonce, ciphertext, aad)
}

func Sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func ComputeAuthTag(peerCryptoKey []byte, message string) string {
	mac := hmac.New(sha256.New, peerCryptoKey)
	mac.Write([]byte(message))
	return hex.EncodeToString(mac.Sum(nil))
}

func VerifyAuthTag(peerCryptoKey []byte, message string, tagHex string) bool {
	if strings.TrimSpace(tagHex) == "" {
		return false
	}
	expected := ComputeAuthTag(peerCryptoKey, message)
	return subtle.ConstantTimeCompare([]byte(strings.ToLower(tagHex)), []byte(expected)) == 1
}

func ComputeSafetyNumber(alicePub, bobPub []byte, transferID string) string {
	h := sha256.New()
	if bytes.Compare(alicePub, bobPub) <= 0 {
		h.Write(alicePub)
		h.Write(bobPub)
	} else {
		h.Write(bobPub)
		h.Write(alicePub)
	}
	h.Write([]byte(transferID))
	digest := h.Sum(nil)

	blocks := make([]string, 6)
	for i := 0; i < 6; i++ {
		offset := i * 4
		val := binary.BigEndian.Uint32(digest[offset : offset+4])
		positive := uint64(val) % 100000
		blocks[i] = fmt.Sprintf("%05d", positive)
	}
	return strings.Join(blocks, " ")
}

func SanitizeFileName(rawName string) string {
	normalized := strings.ReplaceAll(rawName, "\\", "/")
	normalized = strings.TrimRight(strings.TrimSpace(normalized), "/")
	idx := strings.LastIndex(normalized, "/")
	base := normalized
	if idx >= 0 {
		base = normalized[idx+1:]
	}
	reg := regexp.MustCompile(`[\x00-\x1F\x7F/\\:*?"<>|]`)
	clean := strings.TrimSpace(reg.ReplaceAllString(base, "_"))
	clean = strings.TrimLeft(clean, ".")
	if clean == "" {
		return fmt.Sprintf("download_%d", time.Now().Unix())
	}
	return clean
}

func IsPathInsideDirectory(destPath, baseDir string) bool {
	absBase, err := filepath.Abs(baseDir)
	if err != nil {
		return false
	}
	absDest, err := filepath.Abs(destPath)
	if err != nil {
		return false
	}
	rel, err := filepath.Rel(absBase, absDest)
	if err != nil {
		return false
	}
	return !strings.HasPrefix(rel, "..") && rel != ".."
}

func ResolveUniqueFile(targetDir string, fileName string) string {
	initialPath := filepath.Join(targetDir, fileName)
	if _, err := os.Stat(initialPath); os.IsNotExist(err) {
		return initialPath
	}

	ext := filepath.Ext(fileName)
	stem := strings.TrimSuffix(fileName, ext)

	for i := 1; i <= 1000; i++ {
		candidate := filepath.Join(targetDir, fmt.Sprintf("%s (%d)%s", stem, i, ext))
		if _, err := os.Stat(candidate); os.IsNotExist(err) {
			return candidate
		}
	}
	return filepath.Join(targetDir, fmt.Sprintf("%s (%d)%s", stem, time.Now().UnixNano(), ext))
}
