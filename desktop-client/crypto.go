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

func EncryptChunk(key []byte, nonce []byte, plaintext []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return gcm.Seal(nil, nonce, plaintext, nil), nil
}

func DecryptChunk(key []byte, nonce []byte, ciphertext []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, nonce, ciphertext, nil)
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

