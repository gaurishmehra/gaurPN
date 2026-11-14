package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	// clipboardPollInterval is the legacy poller's tick. Quickshell uses the
	// socket source so the desktop never wakes up just to check the
	// clipboard.
	clipboardPollInterval = 1 * time.Second

	// The Quickshell socket delivers one JSON line per clip; the scanner cap
	// bounds a hostile or buggy writer.
	clipboardSocketReadBuf = 64 * 1024
	clipboardSocketMaxLine = 16 * 1024 * 1024
)

// runClipboardSync is the legacy polling source. It is only used when the
// daemon is started with -clipboard-source=poll. Quickshell uses the socket
// source so the desktop never wakes up just to check the clipboard.
func runClipboardSync(ctx context.Context, cfg Config, client *wsClient, peerCryptoKey []byte, store *peerStore) {
	ticker := time.NewTicker(clipboardPollInterval)
	defer ticker.Stop()
	warned := false

	// Do not blast whatever happened to be on the clipboard before the daemon
	// started. Only new copy events after startup are broadcast. On a reconnect
	// the hash is already set, so a clip copied while offline is still sent.
	clipboardStateMu.Lock()
	firstRun := !clipboardInitialized
	clipboardInitialized = true
	clipboardStateMu.Unlock()
	if firstRun {
		if payload, err := readClipboard(); err == nil && payload.Hash != "" {
			clipboardStateMu.Lock()
			clipboardLastHash = payload.Hash
			clipboardStateMu.Unlock()
		}
	}

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			payload, err := readClipboard()
			if err != nil {
				if !warned {
					log.Printf("[CLIP] Clipboard read unavailable: %v (install wl-clipboard/xclip on Linux)", err)
					warned = true
				}
				continue
			}
			warned = false
			if payload.Hash == "" {
				continue
			}
			sendClipboardPayload(cfg, client, peerCryptoKey, store, payload)
		}
	}
}

// sendClipboardPayload encrypts one clipboard item and fans it out to every
// other peer. It is shared by the polling source and the Quickshell socket.
func sendClipboardPayload(cfg Config, client *wsClient, peerCryptoKey []byte, store *peerStore, payload ClipboardPayload) bool {
	if payload.Hash == "" {
		payload.Hash = clipboardHash(payload.Kind, payload.Mime, payload.Data)
	}

	clipboardStateMu.Lock()
	same := payload.Hash == clipboardLastHash
	suppressed := time.Now().UnixMilli() < clipboardSuppressUntil && payload.Kind == clipboardSuppressKind
	clipboardStateMu.Unlock()
	if same || suppressed {
		if suppressed && payload.Kind == "image" {
			log.Printf("[CLIP] Suppressed image echo after applying a remote clip")
		}
		return false
	}

	peers := store.snapshot()
	if len(peers) == 0 {
		return false
	}

	clipboardKey := DeriveClipboardKey(peerCryptoKey)
	msgID := fmt.Sprintf("clip-%d", time.Now().UnixNano())
	ts := time.Now().UnixMilli()
	nonce, err := RandomBytes(12)
	if err != nil {
		return false
	}
	aad := []byte(fmt.Sprintf("clipboard:%s:%s:%d:%s:%s", cfg.DeviceID, msgID, ts, payload.Kind, payload.Mime))
	ciphertext, err := EncryptClipboard(clipboardKey, nonce, payload.Data, aad)
	if err != nil {
		log.Printf("[CLIP] Encrypt failed: %v", err)
		return false
	}

	sent := 0
	for _, peer := range peers {
		if peer.IsSelf {
			continue
		}
		msg := map[string]interface{}{
			"type":      "clipboard_update",
			"target_id": peer.ID,
			"msg_id":    msgID,
			"ts":        ts,
			"kind":      payload.Kind,
			"mime":      payload.Mime,
			"nonce":     base64.StdEncoding.EncodeToString(nonce),
			"data":      base64.StdEncoding.EncodeToString(ciphertext),
		}
		if err := client.WriteJSON(msg); err != nil {
			log.Printf("[CLIP] Send failed: %v", err)
			break
		}
		sent++
	}
	if sent == 0 {
		return false
	}

	clipboardStateMu.Lock()
	clipboardLastHash = payload.Hash
	clipboardStateMu.Unlock()
	log.Printf("[CLIP] Sent %s clipboard (%d bytes) to %d peer(s)", payload.Kind, len(payload.Data), sent)
	return true
}

type clipboardSocketMessage struct {
	Kind string `json:"kind"`
	Mime string `json:"mime"`
	Data string `json:"data"`
}

// clipboardSocketPath is the single resolver for the Quickshell clipboard
// socket path so the server and the shutdown cleanup agree on the same file.
// Empty means the socket source is disabled.
func clipboardSocketPath(cfg Config) string {
	return cfg.ClipboardSocket
}

// removeClipboardSocket unlinks the clipboard socket on daemon shutdown.
// Without this the socket file outlives the daemon and Quickshell's next
// dial fails until a new daemon removes it at startup.
func removeClipboardSocket(cfg Config) {
	path := clipboardSocketPath(cfg)
	if path == "" {
		return
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		log.Printf("[CLIP] Could not remove clipboard socket %s on shutdown: %v", path, err)
	}
}

// runClipboardSocketServer listens on a Unix socket for Quickshell clipboard
// events. Quickshell watches cliphist's BoltDB with inotify and sends one JSON
// line per new clip, so the daemon does not poll the clipboard at all.
func runClipboardSocketServer(ctx context.Context, cfg Config, client *wsClient, peerCryptoKey []byte, store *peerStore) {
	path := clipboardSocketPath(cfg)
	if path == "" {
		return
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		log.Printf("[CLIP] Cannot create socket directory: %v", err)
		return
	}
	_ = os.Remove(path)
	// Create the socket under a tightened umask so it is private from the
	// moment it appears; the explicit chmod below is kept for the contract.
	var ln net.Listener
	listenErr := withUmask(func() error {
		l, err := net.Listen("unix", path)
		if err != nil {
			return err
		}
		ln = l
		return nil
	})
	if listenErr != nil {
		log.Printf("[CLIP] Cannot listen on %s: %v", path, listenErr)
		return
	}
	defer ln.Close()
	defer os.Remove(path)
	_ = os.Chmod(path, 0o600)

	var connMu sync.Mutex
	conns := make(map[net.Conn]struct{})
	closeAll := func() {
		connMu.Lock()
		for conn := range conns {
			_ = conn.Close()
		}
		conns = make(map[net.Conn]struct{})
		connMu.Unlock()
	}
	go func() {
		<-ctx.Done()
		_ = ln.Close()
		closeAll()
	}()

	log.Printf("[CLIP] Listening for Quickshell clipboard events on %s", path)
	for {
		conn, err := ln.Accept()
		if err != nil {
			closeAll()
			return
		}
		// Only processes of the same user may push clipboard events.
		if credErr := sameUserConn(conn); credErr != nil {
			log.Printf("[CLIP] Rejected clipboard socket connection: %v", credErr)
			_ = conn.Close()
			continue
		}
		connMu.Lock()
		conns[conn] = struct{}{}
		connMu.Unlock()
		go func() {
			defer func() {
				connMu.Lock()
				delete(conns, conn)
				connMu.Unlock()
				_ = conn.Close()
			}()
			handleClipboardSocketConn(ctx, conn, cfg, client, peerCryptoKey, store)
		}()
	}
}

func handleClipboardSocketConn(ctx context.Context, conn net.Conn, cfg Config, client *wsClient, peerCryptoKey []byte, store *peerStore) {
	scanner := bufio.NewScanner(conn)
	scanner.Buffer(make([]byte, clipboardSocketReadBuf), clipboardSocketMaxLine)
	for scanner.Scan() {
		select {
		case <-ctx.Done():
			return
		default:
		}
		var msg clipboardSocketMessage
		if err := json.Unmarshal(scanner.Bytes(), &msg); err != nil {
			continue
		}
		if msg.Kind != "text" && msg.Kind != "image" {
			continue
		}
		data, err := base64.StdEncoding.DecodeString(msg.Data)
		if err != nil {
			continue
		}
		if msg.Kind == "text" && len(data) > 1024*1024 {
			log.Printf("[CLIP] Rejecting oversized text from Quickshell (%d bytes)", len(data))
			continue
		}
		if msg.Kind == "image" && len(data) > maxClipboardImageBytes {
			log.Printf("[CLIP] Rejecting oversized image from Quickshell (%d bytes)", len(data))
			continue
		}
		payload := ClipboardPayload{
			Kind: msg.Kind,
			Mime: msg.Mime,
			Data: data,
			Hash: clipboardHash(msg.Kind, msg.Mime, data),
		}
		sendClipboardPayload(cfg, client, peerCryptoKey, store, payload)
	}
}
