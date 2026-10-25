package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"io"

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
