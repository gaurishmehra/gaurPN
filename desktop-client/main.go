package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

type Config struct {
	RelayAddr       string
	TokenFile       string
	DeviceID        string
	SenderID        string
	Name            string
	InboxDir        string
	PeersFile       string
	Clipboard       bool
	ClipboardSource string
	ClipboardSocket string
	LogFile         string
	AutoOpen        bool
	OnlyTrusted     bool
	Notifications   bool
}

func defaultInboxDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "./gaur-inbox"
	}
	return filepath.Join(home, "Downloads", "gaur-inbox")
}

func defaultTokenFile() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "./cluster.key"
	}
	return filepath.Join(home, ".config", "gaur-share", "cluster.key")
}

func defaultConfigFile() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "./config.json"
	}
	return filepath.Join(home, ".config", "gaur-share", "config.json")
}

func defaultPeersFile() string {
	cache, err := os.UserCacheDir()
	if err != nil {
		return "./gaur-peers.json"
	}
	return filepath.Join(cache, "gaur-share", "peers.json")
}

func defaultClipboardSocket() string {
	cache, err := os.UserCacheDir()
	if err != nil {
		return filepath.Join(os.TempDir(), "gaur-share-clipboard.sock")
	}
	return filepath.Join(cache, "gaur-share", "clipboard.sock")
}

type peerFileEntry struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Status string `json:"status"`
	IsSelf bool   `json:"is_self"`
}

// wsClient serializes writes. Gorilla WebSocket permits one concurrent writer;
// the daemon writes from the ping loop, transfer handler, and clipboard poller.
type wsClient struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

func (c *wsClient) WriteJSON(v interface{}) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.WriteJSON(v)
}

func (c *wsClient) WriteMessage(messageType int, data []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.conn.WriteMessage(messageType, data)
}

func (c *wsClient) ReadMessage() (int, []byte, error) {
	return c.conn.ReadMessage()
}

func (c *wsClient) Close() error {
	return c.conn.Close()
}

type peerStore struct {
	mu    sync.RWMutex
	peers []peerFileEntry
}

func (s *peerStore) set(peers []peerFileEntry) {
	s.mu.Lock()
	s.peers = append([]peerFileEntry(nil), peers...)
	s.mu.Unlock()
}

func (s *peerStore) snapshot() []peerFileEntry {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]peerFileEntry(nil), s.peers...)
}

// writePeersFile publishes the current peer list for desktop shells to watch.
// It writes in place rather than via rename so QFileSystemWatcher keeps its
// inode watch and reacts to a single modify event.
func writePeersFile(cfg Config, peers []peerFileEntry) {
	if cfg.PeersFile == "" {
		return
	}
	if peers == nil {
		peers = []peerFileEntry{}
	}
	payload := struct {
		Updated int64           `json:"updated"`
		Peers   []peerFileEntry `json:"peers"`
	}{Updated: time.Now().Unix(), Peers: peers}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	if err := os.MkdirAll(filepath.Dir(cfg.PeersFile), 0o755); err != nil {
		return
	}
	f, err := os.OpenFile(cfg.PeersFile, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return
	}
	_, _ = f.Write(data)
	_ = f.Close()
}

func loadSettingsFromConfig() (string, string) {
	path := defaultConfigFile()
	data, err := os.ReadFile(path)
	if err != nil {
		return "Lilac", "198.51.100.1:9876"
	}
	var conf struct {
		DeviceName   string `json:"device_name"`
		Name         string `json:"name"`
		Relay        string `json:"relay"`
		RelayAddress string `json:"relay_address"`
	}
	name := "Lilac"
	relay := "198.51.100.1:9876"
	if err := json.Unmarshal(data, &conf); err == nil {
		if strings.TrimSpace(conf.DeviceName) != "" {
			name = strings.TrimSpace(conf.DeviceName)
		} else if strings.TrimSpace(conf.Name) != "" {
			name = strings.TrimSpace(conf.Name)
		}
		if strings.TrimSpace(conf.Relay) != "" {
			relay = strings.TrimSpace(conf.Relay)
		} else if strings.TrimSpace(conf.RelayAddress) != "" {
			relay = strings.TrimSpace(conf.RelayAddress)
		}
	}
	return name, relay
}

func loadToken(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		envToken := os.Getenv("GAURPN_CLUSTER_TOKEN")
		if envToken != "" {
			return strings.TrimSpace(envToken), nil
		}
		return "", fmt.Errorf("unable to read token from %s and GAURPN_CLUSTER_TOKEN not set: %w", path, err)
	}
	// A group- or world-readable cluster key hands the whole cluster to every
	// other local user, so refuse to start rather than use it.
	info, statErr := os.Stat(path)
	if statErr != nil {
		return "", fmt.Errorf("cannot verify permissions of token file %s: %w", path, statErr)
	}
	if perms := info.Mode().Perm(); perms&0o077 != 0 {
		return "", fmt.Errorf(
			"token file %s is exposed to other users (mode %04o); refusing to start. Fix with: chmod 600 %s",
			path, perms, path,
		)
	}
	return strings.TrimSpace(string(data)), nil
}

func generateDeviceID() string {
	home, err := os.UserHomeDir()
	if err == nil {
		idPath := filepath.Join(home, ".config", "gaur-share", "device.id")
		if data, err := os.ReadFile(idPath); err == nil {
			trimmed := strings.TrimSpace(string(data))
			if len(trimmed) > 0 {
				return trimmed
			}
		}
	}

	hostname, _ := os.Hostname()
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	cleanHost := strings.ToLower(strings.ReplaceAll(hostname, " ", "-"))
	if cleanHost == "" {
		cleanHost = "desktop"
	}
	newID := fmt.Sprintf("%s-%x", cleanHost, b)

	if home, err := os.UserHomeDir(); err == nil {
		idDir := filepath.Join(home, ".config", "gaur-share")
		_ = os.MkdirAll(idDir, 0o755)
		_ = os.WriteFile(filepath.Join(idDir, "device.id"), []byte(newID), 0o644)
	}
	return newID
}

func notifyUser(title, message string) {
	switch runtime.GOOS {
	case "linux":
		// "--" ends option parsing so a title or message beginning with "-"
		// is delivered as text, never read as a notify-send option.
		_ = exec.Command("notify-send", "-a", "gaurPN", "--", title, message).Run()
	case "darwin":
		script := fmt.Sprintf(`display notification "%s" with title "%s"`,
			escapeAppleScriptString(message), escapeAppleScriptString(title))
		_ = exec.Command("osascript", "-e", script).Run()
	}
}

// openCommand builds the platform-specific command that opens a file with its
// default application. The context bounds the launcher so a hung opener
// cannot linger; it does not affect the already-completed transfer.
func openCommand(ctx context.Context, path string) *exec.Cmd {
	switch runtime.GOOS {
	case "darwin":
		return exec.CommandContext(ctx, "open", path)
	case "windows":
		return exec.CommandContext(ctx, "cmd", "/c", "start", "", path)
	default:
		return exec.CommandContext(ctx, "xdg-open", path)
	}
}

// openFile launches the platform file opener without blocking the caller.
// Failures are logged and never affect the completed transfer.
func openFile(path string) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	cmd := openCommand(ctx, path)
	go func() {
		defer cancel()
		if err := cmd.Start(); err != nil {
			log.Printf("[RECV] Failed to open '%s': %v", path, err)
			return
		}
		// Reap the launcher so it does not linger as a zombie.
		_ = cmd.Wait()
	}()
}

func connectWS(cfg Config, token string) (*websocket.Conn, error) {
	u := url.URL{
		Scheme: "ws",
		Host:   cfg.RelayAddr,
		Path:   "/ws",
	}
	q := u.Query()
	q.Set("device_id", cfg.DeviceID)
	q.Set("name", cfg.Name)
	u.RawQuery = q.Encode()

	// The relay token travels in a header, not the query string, so it never
	// lands in URL form (the relay accepts both; the header is the contract).
	header := http.Header{}
	header.Set("X-Gaur-Token", token)
	conn, _, err := websocket.DefaultDialer.Dial(u.String(), header)
	return conn, err
}

type transferRun struct {
	cancel context.CancelFunc
}

func runDaemon(cfg Config) {
	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	relayToken, peerCryptoKey := DeriveSubKeys(token)

	// Fresh replay protection for this daemon lifetime.
	replayGuard = NewReplayGuard()

	sender := &CommandSender{cfg: cfg}
	mpris, mprisErr := NewMprisManager(sender.Send)
	if mprisErr != nil {
		log.Printf("[MPRIS] Disabled: %v", mprisErr)
	}
	broadcaster := &MediaBroadcaster{cfg: cfg}
	localMedia, localErr := NewLocalMediaWatcher(cfg, broadcaster)
	if localErr != nil {
		log.Printf("[MPRIS] Local watcher disabled: %v", localErr)
	}

	_ = os.MkdirAll(cfg.InboxDir, 0o755)
	log.Printf("[DAEMON] Starting gaur-share daemon as '%s' (ID: %s)", cfg.Name, cfg.DeviceID)
	log.Printf("[DAEMON] Incoming files will be saved to: %s", cfg.InboxDir)
	writePeersFile(cfg, nil)

	store := &peerStore{}
	activeTransfers := &sync.Map{}
	for {
		conn, err := connectWS(cfg, relayToken)
		if err != nil {
			writePeersFile(cfg, nil)
			log.Printf("[DAEMON] Connecting to in-tunnel relay (%s)... Error: %v. Retrying in 5s...", cfg.RelayAddr, err)
			time.Sleep(5 * time.Second)
			continue
		}
		client := &wsClient{conn: conn}
		sender.Set(client, peerCryptoKey)
		broadcaster.Set(client, peerCryptoKey)

		log.Printf("[DAEMON] Connected to in-tunnel relay at %s. Standing by for incoming transfers...", cfg.RelayAddr)

		// Heartbeat ping loop
		stopPing := make(chan struct{})
		go func() {
			ticker := time.NewTicker(20 * time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-ticker.C:
					if err := client.WriteJSON(map[string]string{"type": "ping"}); err != nil {
						return
					}
				case <-stopPing:
					return
				}
			}
		}()

		// Clipboard sync lives for the duration of this relay connection.
		clipCtx, clipCancel := context.WithCancel(context.Background())
		if cfg.Clipboard {
			switch cfg.ClipboardSource {
			case "socket":
				go runClipboardSocketServer(clipCtx, cfg, client, peerCryptoKey, store)
			case "poll":
				go runClipboardSync(clipCtx, cfg, client, peerCryptoKey, store)
			case "off":
				// Explicitly disabled.
			default:
				log.Printf("[CLIP] Unknown clipboard source %q; clipboard sync disabled", cfg.ClipboardSource)
			}
		}

		// Message loop
		for {
			_, msgBytes, err := client.ReadMessage()
			if err != nil {
				close(stopPing)
				clipCancel()
				sender.Set(nil, nil)
				broadcaster.Set(nil, nil)
				store.set(nil)
				writePeersFile(cfg, nil)
				log.Printf("[DAEMON] Relay connection interrupted: %v. Reconnecting...", err)
				_ = client.Close()
				break
			}

			var envelope map[string]interface{}
			if err := json.Unmarshal(msgBytes, &envelope); err != nil {
				continue
			}

			msgType, _ := envelope["type"].(string)
			switch msgType {
			case "peers":
				peersRaw, _ := envelope["peers"].([]interface{})
				entries := make([]peerFileEntry, 0, len(peersRaw))
				for _, p := range peersRaw {
					pMap, _ := p.(map[string]interface{})
					id, _ := pMap["id"].(string)
					name, _ := pMap["name"].(string)
					entries = append(entries, peerFileEntry{
						ID:     id,
						Name:   name,
						Status: "online",
						IsSelf: id == cfg.DeviceID || strings.EqualFold(name, cfg.Name),
					})
				}
				store.set(entries)
				writePeersFile(cfg, entries)
				broadcaster.SetPeers(peerIDs(entries))
				if localMedia != nil {
					localMedia.ForceRefresh()
				}
			case "transfer_offer":
				txID, _ := envelope["transfer_id"].(string)
				if txID == "" {
					continue
				}
				if old, ok := activeTransfers.Load(txID); ok {
					old.(*transferRun).cancel()
				}
				ctx, cancel := context.WithCancel(context.Background())
				run := &transferRun{cancel: cancel}
				activeTransfers.Store(txID, run)
				go func() {
					defer func() {
						if cur, ok := activeTransfers.Load(txID); ok && cur.(*transferRun) == run {
							activeTransfers.Delete(txID)
						}
						cancel()
					}()
					handleIncomingTransfer(ctx, cfg, relayToken, peerCryptoKey, client, envelope)
				}()
			case "clipboard_update":
				go handleIncomingClipboard(cfg, peerCryptoKey, envelope)
			case "text_message":
				go handleIncomingText(cfg, peerCryptoKey, envelope)
			case "notification_event":
				go handleIncomingNotification(cfg, peerCryptoKey, client, envelope)
			case "sealed":
				go handleIncomingSealed(cfg, relayToken, peerCryptoKey, client, mpris, localMedia, envelope)
			}
		}

		time.Sleep(2 * time.Second)
	}
}

// runClipboardSync is the legacy polling source. It is only used when the
// daemon is started with -clipboard-source=poll. Quickshell uses the socket
// source so the desktop never wakes up just to check the clipboard.
func runClipboardSync(ctx context.Context, cfg Config, client *wsClient, peerCryptoKey []byte, store *peerStore) {
	ticker := time.NewTicker(1 * time.Second)
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

// runClipboardSocketServer listens on a Unix socket for Quickshell clipboard
// events. Quickshell watches cliphist's BoltDB with inotify and sends one JSON
// line per new clip, so the daemon does not poll the clipboard at all.
func runClipboardSocketServer(ctx context.Context, cfg Config, client *wsClient, peerCryptoKey []byte, store *peerStore) {
	path := cfg.ClipboardSocket
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
	scanner.Buffer(make([]byte, 64*1024), 16*1024*1024)
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

func handleIncomingClipboard(cfg Config, peerCryptoKey []byte, envelope map[string]interface{}) {
	clipboardApplyMu.Lock()
	defer clipboardApplyMu.Unlock()

	fromID, _ := envelope["from_id"].(string)
	if fromID == "" || fromID == cfg.DeviceID {
		return
	}
	msgID, _ := envelope["msg_id"].(string)
	tsFloat, _ := envelope["ts"].(float64)
	ts := int64(tsFloat)
	relayTs := ts
	if relayFloat, ok := envelope["relay_ts"].(float64); ok {
		relayTs = int64(relayFloat)
	}
	kind, _ := envelope["kind"].(string)
	mime, _ := envelope["mime"].(string)
	nonceB64, _ := envelope["nonce"].(string)
	dataB64, _ := envelope["data"].(string)
	if msgID == "" || ts <= 0 || (kind != "text" && kind != "image") || nonceB64 == "" || dataB64 == "" {
		log.Printf("[CLIP] Dropped malformed clipboard from %s (kind=%q msg=%q)", fromID, kind, msgID)
		return
	}
	log.Printf("[CLIP] Received %s clipboard from %s (relayTs=%d, b64=%d)", kind, fromID, relayTs, len(dataB64))

	clipboardStateMu.Lock()
	if relayTs <= clipboardLastTs {
		log.Printf("[CLIP] Dropped stale clipboard from %s (relayTs=%d last=%d)", fromID, relayTs, clipboardLastTs)
		clipboardStateMu.Unlock()
		return
	}
	clipboardStateMu.Unlock()

	nonce, err := base64.StdEncoding.DecodeString(nonceB64)
	if err != nil {
		return
	}
	ciphertext, err := base64.StdEncoding.DecodeString(dataB64)
	if err != nil {
		return
	}
	aad := []byte(fmt.Sprintf("clipboard:%s:%s:%d:%s:%s", fromID, msgID, ts, kind, mime))
	plaintext, err := DecryptClipboard(DeriveClipboardKey(peerCryptoKey), nonce, ciphertext, aad)
	if err != nil {
		log.Printf("[CLIP] Authentication failed for clipboard from %s", fromID)
		return
	}
	if kind == "text" && len(plaintext) > 1024*1024 {
		log.Printf("[CLIP] Rejecting oversized text clipboard from %s (%d bytes)", fromID, len(plaintext))
		return
	}
	if kind == "image" && len(plaintext) > maxClipboardImageBytes {
		log.Printf("[CLIP] Rejecting oversized image clipboard from %s (%d bytes)", fromID, len(plaintext))
		return
	}

	payload := ClipboardPayload{
		Kind: kind,
		Mime: mime,
		Data: plaintext,
		Hash: clipboardHash(kind, mime, plaintext),
	}
	clipboardStateMu.Lock()
	clipboardLastHash = payload.Hash
	clipboardLastTs = relayTs
	// cliphist re-encodes images, so the decoded hash will not match the
	// received hash. Suppress the echo by kind long enough for cliphist to
	// record the write and Quickshell to send it back.
	suppressMs := int64(2000)
	if kind == "image" {
		suppressMs = 8000
	}
	clipboardSuppressUntil = time.Now().Add(time.Duration(suppressMs) * time.Millisecond).UnixMilli()
	clipboardSuppressKind = kind
	clipboardStateMu.Unlock()

	if err := writeClipboard(payload); err != nil {
		log.Printf("[CLIP] Could not apply clipboard from %s: %v", fromID, err)
		return
	}
	log.Printf("[CLIP] Applied %s clipboard from %s (%d bytes)", kind, fromID, len(plaintext))
}

// handleIncomingText decrypts a one-shot text/link share, copies it to the
// desktop clipboard, and raises a notification. It is intentionally not
// persisted: the relay never stores text, and the daemon keeps no history.
func handleIncomingText(cfg Config, peerCryptoKey []byte, envelope map[string]interface{}) {
	if v, ok := envelope["v"].(float64); ok && int(v) > 1 {
		return
	}
	senderIdentity := offerSenderID(envelope)
	if onlyTrustedEnabled(cfg) && senderIdentity != cfg.DeviceID &&
		!isTrustedDevice(senderIdentity) {
		log.Printf("[TEXT] Dropped text from untrusted %s", senderIdentity)
		return
	}
	fromID, _ := envelope["from_id"].(string)
	if fromID == "" || fromID == cfg.DeviceID {
		return
	}
	fromName, _ := envelope["from_name"].(string)
	if fromName == "" {
		fromName = fromID
	}
	msgID, _ := envelope["msg_id"].(string)
	tsFloat, _ := envelope["ts"].(float64)
	ts := int64(tsFloat)
	kind, _ := envelope["kind"].(string)
	nonceB64, _ := envelope["nonce"].(string)
	dataB64, _ := envelope["data"].(string)
	if msgID == "" || ts <= 0 || (kind != "text" && kind != "url") || nonceB64 == "" || dataB64 == "" {
		log.Printf("[TEXT] Dropped malformed text message from %s", fromID)
		return
	}
	if !replayGuard.Allow("text", msgID, envelopeRelayTs(envelope)) {
		log.Printf("[TEXT] Dropped replayed text message from %s (msg_id=%s)", fromID, msgID)
		return
	}

	nonce, err := base64.StdEncoding.DecodeString(nonceB64)
	if err != nil {
		return
	}
	ciphertext, err := base64.StdEncoding.DecodeString(dataB64)
	if err != nil {
		return
	}
	aad := []byte(fmt.Sprintf("text:%s:%s:%d:%s", fromID, msgID, ts, kind))
	plaintext, err := DecryptClipboard(DeriveTextKey(peerCryptoKey), nonce, ciphertext, aad)
	if err != nil {
		log.Printf("[TEXT] Authentication failed for text message from %s", fromID)
		return
	}

	applyIncomingText(fromID, fromName, kind, plaintext)
}

// applyIncomingText copies a received text or link to the clipboard and
// raises a notification. It is shared by the v1 text_message path and the
// sealed v3 text path; neither persists the text anywhere.
func applyIncomingText(fromID, fromName, kind string, plaintext []byte) {
	if len(plaintext) == 0 || len(plaintext) > 64*1024 {
		log.Printf("[TEXT] Rejecting invalid text message from %s (%d bytes)", fromID, len(plaintext))
		return
	}
	text := string(plaintext)
	// Writing the clipboard makes Quickshell's cliphist watcher fire. Mark the
	// payload as already-applied so the daemon does not echo it back to the
	// cluster as a clipboard update.
	clipboardStateMu.Lock()
	clipboardLastHash = clipboardHash("text", "text/plain", plaintext)
	clipboardLastTs = time.Now().UnixMilli()
	clipboardSuppressUntil = time.Now().Add(2 * time.Second).UnixMilli()
	clipboardSuppressKind = "text"
	clipboardStateMu.Unlock()
	if err := writeClipboard(ClipboardPayload{Kind: "text", Mime: "text/plain", Data: plaintext}); err != nil {
		log.Printf("[TEXT] Could not apply text from %s to clipboard: %v", fromID, err)
	}
	title := "Text from " + fromName
	if kind == "url" {
		title = "Link from " + fromName
	}
	notifyUser(title, text)
	log.Printf("[TEXT] Received %s from %s (%d bytes)", kind, fromID, len(plaintext))
}

func handleIncomingTransfer(ctx context.Context, cfg Config, relayToken string, peerCryptoKey []byte, wsConn *wsClient, offer map[string]interface{}) {
	version, _ := offer["v"].(float64)
	if int(version) >= 2 {
		handleIncomingTransferV2(ctx, cfg, relayToken, peerCryptoKey, wsConn, offer)
		return
	}
	handleIncomingTransferV1(cfg, relayToken, peerCryptoKey, wsConn, offer)
}

func handleIncomingTransferV1(cfg Config, relayToken string, peerCryptoKey []byte, wsConn *wsClient, offer map[string]interface{}) {
	transferID, _ := offer["transfer_id"].(string)
	senderID, _ := offer["from_id"].(string)
	senderName, _ := offer["from_name"].(string)
	rawFileName, _ := offer["file_name"].(string)
	fileSizeFloat, _ := offer["file_size"].(float64)
	senderPubB64, _ := offer["pub_key"].(string)
	offerTag, _ := offer["auth_tag"].(string)
	fileSize := int64(fileSizeFloat)
	batchID, _ := offer["batch_id"].(string)
	batchIndexFloat, _ := offer["batch_index"].(float64)
	batchTotalFloat, _ := offer["batch_total"].(float64)
	batchIndex := int(batchIndexFloat)
	batchTotal := int(batchTotalFloat)
	if batchTotal < 1 {
		batchTotal = 1
	}

	if transferID == "" || rawFileName == "" || senderPubB64 == "" {
		return
	}

	// Finding 3: Zero-knowledge peer authentication (Relay MITM Defense).
	// The v1 tag binds the raw (pre-sanitization) filename and exact size so a
	// malicious relay cannot alter offer metadata. Format is pinned in
	// lockstep with Android's TransferOfferCodec.
	expectedOfferMsg := fmt.Sprintf("offer:v1:%s:%s:%s:%s:%d",
		transferID, cfg.DeviceID, senderPubB64,
		base64.StdEncoding.EncodeToString([]byte(rawFileName)), fileSize)
	if !VerifyAuthTag(peerCryptoKey, expectedOfferMsg, offerTag) {
		log.Printf("[RECV] Incoming offer auth tag invalid! Rejecting potential MITM or unauthorized peer: %s", transferID)
		_ = wsConn.WriteJSON(map[string]interface{}{
			"type":        "transfer_reject",
			"target_id":   senderID,
			"transfer_id": transferID,
			"reason":      "auth_failed",
		})
		return
	}

	if onlyTrustedEnabled(cfg) {
		senderIdentity := offerSenderID(offer)
		if senderIdentity != cfg.DeviceID && !isTrustedDevice(senderIdentity) {
			_ = wsConn.WriteJSON(map[string]interface{}{
				"type":        "transfer_reject",
				"target_id":   senderID,
				"transfer_id": transferID,
				"reason":      "untrusted",
			})
			notifyUser("Transfer blocked", "Not from a trusted device")
			log.Printf("[RECV] Blocked %s from untrusted %s", transferID, senderIdentity)
			return
		}
	}

	// Finding 1: Path Traversal Protection
	cleanFileName := SanitizeFileName(rawFileName)
	destPath := filepath.Join(cfg.InboxDir, cleanFileName)
	if !IsPathInsideDirectory(destPath, cfg.InboxDir) {
		log.Printf("[RECV] Path traversal attack detected in filename %q! Rejecting %s", rawFileName, transferID)
		_ = wsConn.WriteJSON(map[string]interface{}{
			"type":        "transfer_reject",
			"target_id":   senderID,
			"transfer_id": transferID,
			"reason":      "invalid_path",
		})
		return
	}

	senderPubBytes, err := base64.StdEncoding.DecodeString(senderPubB64)
	if err != nil {
		log.Printf("[RECV] Invalid sender public key: %v", err)
		return
	}

	// Generate receiver ephemeral keypair
	keyPair, err := GenerateKeyPair()
	if err != nil {
		log.Printf("[RECV] Failed to generate ephemeral keypair: %v", err)
		return
	}

	// Derive AES-256-GCM session key
	aesKey, err := DeriveSharedSecret(keyPair.Private, senderPubBytes)
	if err != nil {
		log.Printf("[RECV] Failed to derive shared secret: %v", err)
		return
	}

	myPubB64 := base64.StdEncoding.EncodeToString(keyPair.Public)
	acceptTag := ComputeAuthTag(peerCryptoKey, fmt.Sprintf("accept:%s:%s:%s", transferID, senderID, myPubB64))

	safetyNumber := ComputeSafetyNumber(senderPubBytes, keyPair.Public, transferID)
	log.Printf("[RECV] Verified transfer %s safety number: %s", transferID, safetyNumber)

	if batchTotal > 1 {
		log.Printf("[RECV] Incoming batch %s file %d/%d from %s (%s): %s (%d bytes)",
			batchID, batchIndex+1, batchTotal, senderName, senderID, cleanFileName, fileSize)
	} else {
		log.Printf("[RECV] Incoming file from %s (%s): %s (%d bytes)", senderName, senderID, cleanFileName, fileSize)
	}
	notifyUser("Incoming File Transfer", fmt.Sprintf("%s is sending %s (%.1f MB)", senderName, cleanFileName, float64(fileSize)/(1024*1024)))

	// Send transfer_accept with receiver's public key and auth_tag
	acceptMsg := map[string]interface{}{
		"type":        "transfer_accept",
		"target_id":   senderID,
		"transfer_id": transferID,
		"pub_key":     myPubB64,
		"auth_tag":    acceptTag,
	}
	_ = wsConn.WriteJSON(acceptMsg)

	// Connect to pipe. Token rides in the header, not the URL.
	pipeURL := fmt.Sprintf("http://%s/pipe?transfer_id=%s&role=receiver", cfg.RelayAddr, transferID)
	req, _ := http.NewRequest("GET", pipeURL, nil)
	req.Header.Set("X-Gaur-Token", relayToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		log.Printf("[RECV] Failed to connect to pipe: %v", err)
		return
	}
	defer resp.Body.Close()

	// Finding 2: Safe collision handling & .part temporary staging (auto-accept active)
	partPath := filepath.Join(cfg.InboxDir, fmt.Sprintf(".%s.%s.part", cleanFileName, transferID))
	outFile, err := os.OpenFile(partPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		log.Printf("[RECV] Failed to create temporary part file: %v", err)
		notifyUser("Transfer Failed", fmt.Sprintf("Cannot save '%s'", cleanFileName))
		return
	}

	var totalRead int64
	var transferSuccess bool
	defer func() {
		_ = outFile.Close()
		if !transferSuccess || (totalRead == 0 && fileSize > 0) {
			_ = os.Remove(partPath)
			notifyUser("Transfer Failed", fmt.Sprintf("Failed receiving '%s' from %s", cleanFileName, senderName))
		}
	}()

	// Read and decrypt chunks
	aead, err := NewAEAD(aesKey)
	if err != nil {
		log.Printf("[RECV] Failed to init AEAD: %v", err)
		return
	}
	bw := bufio.NewWriterSize(outFile, 1<<20)
	lenBuf := make([]byte, 4)
	nonceBuf := make([]byte, 12)
	cipherBuf := make([]byte, 0, 256*1024+aead.Overhead())
	plainBuf := make([]byte, 0, 256*1024)
	expectedCounter := uint64(0)

	startTime := time.Now()
	for {
		// Read 4-byte chunk length
		if _, err := io.ReadFull(resp.Body, lenBuf); err != nil {
			if err == io.EOF || err == io.ErrUnexpectedEOF {
				break
			}
			log.Printf("[RECV] Chunk read header error: %v", err)
			return
		}
		chunkLen := binary.BigEndian.Uint32(lenBuf)
		if chunkLen == 0 {
			break // Clean stream EOF sentinel
		}
		if chunkLen > 1024*1024 {
			log.Printf("[RECV] Invalid chunk length (%d bytes), aborting", chunkLen)
			return
		}

		// Read 12-byte nonce
		if _, err := io.ReadFull(resp.Body, nonceBuf); err != nil {
			log.Printf("[RECV] Nonce read error: %v", err)
			return
		}

		// Finding 4: Enforce sequential nonces
		prefix := binary.BigEndian.Uint32(nonceBuf[:4])
		counter := binary.BigEndian.Uint64(nonceBuf[4:])
		if prefix != 0 || counter != expectedCounter {
			log.Printf("[RECV] Nonce sequence violation: expected %d, got %d (prefix=%d)", expectedCounter, counter, prefix)
			return
		}
		expectedCounter++

		// Read ciphertext into a reused buffer
		if cap(cipherBuf) < int(chunkLen) {
			cipherBuf = make([]byte, chunkLen)
		} else {
			cipherBuf = cipherBuf[:chunkLen]
		}
		if _, err := io.ReadFull(resp.Body, cipherBuf); err != nil {
			log.Printf("[RECV] Ciphertext read error: %v", err)
			return
		}

		plaintext, err := aead.Open(plainBuf[:0], nonceBuf, cipherBuf, nil)
		if err != nil {
			log.Printf("[RECV] Decryption authentication failed! File may be tampered: %v", err)
			return
		}

		if _, err := bw.Write(plaintext); err != nil {
			log.Printf("[RECV] File write error: %v", err)
			return
		}
		totalRead += int64(len(plaintext))
	}

	if totalRead > 0 || fileSize == 0 {
		if err := bw.Flush(); err != nil {
			log.Printf("[RECV] Flush failed: %v", err)
			return
		}
		_ = outFile.Close()
		// Finding 2: Safe collision resolution and atomic rename
		finalPath := ResolveUniqueFile(cfg.InboxDir, cleanFileName)
		if err := os.Rename(partPath, finalPath); err != nil {
			log.Printf("[RECV] Rename failed: %v", err)
			return
		}
		transferSuccess = true
		duration := time.Since(startTime).Seconds()
		if duration <= 0 {
			duration = 0.001
		}
		mbps := (float64(totalRead) / (1024 * 1024)) / duration
		log.Printf("[RECV] SUCCESS: Saved '%s' (%.2f MB at %.1f MB/s)", filepath.Base(finalPath), float64(totalRead)/(1024*1024), mbps)
		notifyUser("Transfer Complete", fmt.Sprintf("Saved '%s' (%.1f MB)", filepath.Base(finalPath), float64(totalRead)/(1024*1024)))
	}
}

// offerTagPayload builds the canonical v2 offer string covered by the offer
// auth tag. The trailing open bit is "1" when the sender asked the receiver
// to open the file on arrival, "0" otherwise. Senders and receivers must
// construct the string identically or verification fails.
func offerTagPayload(transferID, deviceID, senderPubB64, fileHash string, chunkCount, chunkSize int, openRequested bool) string {
	openBit := "0"
	if openRequested {
		openBit = "1"
	}
	return fmt.Sprintf("offer:v2:%s:%s:%s:%s:%d:%d:%s",
		transferID, deviceID, senderPubB64, fileHash, chunkCount, chunkSize, openBit)
}

func handleIncomingTransferV2(ctx context.Context, cfg Config, relayToken string, peerCryptoKey []byte, wsConn *wsClient, offer map[string]interface{}) {
	transferID, _ := offer["transfer_id"].(string)
	sessionID, _ := offer["session_id"].(string)
	senderID, _ := offer["from_id"].(string)
	senderName, _ := offer["from_name"].(string)
	rawFileName, _ := offer["file_name"].(string)
	fileHash, _ := offer["file_hash"].(string)
	senderPubB64, _ := offer["pub_key"].(string)
	offerTag, _ := offer["auth_tag"].(string)
	chunkSizeFloat, _ := offer["chunk_size"].(float64)
	chunkCountFloat, _ := offer["chunk_count"].(float64)
	fileSizeFloat, _ := offer["file_size"].(float64)
	chunkSize := int(chunkSizeFloat)
	chunkCount := int(chunkCountFloat)
	fileSize := int64(fileSizeFloat)
	batchID, _ := offer["batch_id"].(string)
	batchIndexFloat, _ := offer["batch_index"].(float64)
	batchTotalFloat, _ := offer["batch_total"].(float64)
	batchIndex := int(batchIndexFloat)
	batchTotal := int(batchTotalFloat)
	if batchTotal < 1 {
		batchTotal = 1
	}
	openRequested, _ := offer["open"].(bool)
	mimeType, _ := offer["mime_type"].(string)

	if transferID == "" || sessionID == "" || rawFileName == "" || fileHash == "" || senderPubB64 == "" || senderID == "" {
		return
	}
	validEmpty := fileSize == 0 && chunkCount == 0
	validNonEmpty := fileSize > 0 && chunkCount > 0
	if chunkSize <= 0 || chunkSize > MaxChunkSize || chunkCount > 65536 || (!validEmpty && !validNonEmpty) {
		return
	}
	chunkHashes := parseStringArray(offer["chunk_hashes"])
	if len(chunkHashes) != chunkCount {
		log.Printf("[RECV] Rejecting %s: manifest has %d hashes for %d chunks", transferID, len(chunkHashes), chunkCount)
		return
	}

	expectedOffer := offerTagPayload(transferID, cfg.DeviceID, senderPubB64, fileHash, chunkCount, chunkSize, openRequested)
	if !VerifyAuthTag(peerCryptoKey, expectedOffer, offerTag) {
		log.Printf("[RECV] Incoming offer auth tag invalid: %s", transferID)
		_ = wsConn.WriteJSON(map[string]interface{}{
			"type": "transfer_reject", "target_id": senderID, "transfer_id": transferID, "reason": "auth_failed",
		})
		return
	}

	if onlyTrustedEnabled(cfg) {
		senderIdentity := offerSenderID(offer)
		if senderIdentity != cfg.DeviceID && !isTrustedDevice(senderIdentity) {
			_ = wsConn.WriteJSON(map[string]interface{}{
				"type": "transfer_reject", "target_id": senderID, "transfer_id": transferID, "reason": "untrusted",
			})
			notifyUser("Transfer blocked", "Not from a trusted device")
			log.Printf("[RECV] Blocked %s from untrusted %s", transferID, senderIdentity)
			return
		}
	}

	cleanFileName := SanitizeFileName(rawFileName)
	destPath := filepath.Join(cfg.InboxDir, cleanFileName)
	if !IsPathInsideDirectory(destPath, cfg.InboxDir) {
		_ = wsConn.WriteJSON(map[string]interface{}{
			"type": "transfer_reject", "target_id": senderID, "transfer_id": transferID, "reason": "invalid_path",
		})
		return
	}

	partPath := filepath.Join(cfg.InboxDir, fmt.Sprintf(".%s.%s.part", cleanFileName, transferID))
	statePath := partPath + ".state"

	state, err := LoadReceiveState(statePath, transferID, fileHash)
	if err != nil {
		state = NewReceiveState(transferID, fileHash, fileSize, chunkSize, chunkCount)
		_ = os.Remove(partPath)
		_ = os.Remove(statePath)
		state.Save(statePath)
	}
	if _, statErr := os.Stat(partPath); statErr != nil {
		state = NewReceiveState(transferID, fileHash, fileSize, chunkSize, chunkCount)
		state.Save(statePath)
	}

	senderPubBytes, err := base64.StdEncoding.DecodeString(senderPubB64)
	if err != nil {
		return
	}
	keyPair, err := GenerateKeyPair()
	if err != nil {
		return
	}
	aesKey, err := DeriveSharedSecret(keyPair.Private, senderPubBytes)
	if err != nil {
		return
	}

	myPubB64 := base64.StdEncoding.EncodeToString(keyPair.Public)
	have := state.Snapshot()
	acceptTag := ComputeAuthTag(peerCryptoKey, fmt.Sprintf("accept:v2:%s:%s:%s:%s", transferID, senderID, myPubB64, HaveHash(have)))
	if err := wsConn.WriteJSON(map[string]interface{}{
		"type": "transfer_accept", "v": 2, "target_id": senderID,
		"transfer_id": transferID, "pub_key": myPubB64, "auth_tag": acceptTag, "have": have,
	}); err != nil {
		return
	}

	safetyNumber := ComputeSafetyNumber(senderPubBytes, keyPair.Public, transferID)
	log.Printf("[RECV] Transfer %s safety number: %s", transferID, safetyNumber)

	if batchTotal > 1 {
		log.Printf("[RECV] Incoming batch %s file %d/%d from %s (%s): %s (%d bytes)",
			batchID, batchIndex+1, batchTotal, senderName, senderID, cleanFileName, fileSize)
	} else {
		log.Printf("[RECV] Incoming file from %s (%s): %s (%d bytes)", senderName, senderID, cleanFileName, fileSize)
	}
	notifyUser("Incoming File Transfer", fmt.Sprintf("%s is sending %s (%.1f MB)", senderName, cleanFileName, float64(fileSize)/(1024*1024)))

	digest := &FileDigest{
		FileHash:    fileHash,
		ChunkHashes: chunkHashes,
		ChunkCount:  chunkCount,
		Size:        fileSize,
	}
	missing := state.Missing(chunkCount)
	if len(missing) > 0 {
		log.Printf("[RECV] Resuming %s: %d/%d chunks already present", transferID, chunkCount-len(missing), chunkCount)
	}

	startTime := time.Now()
	lastPrint := time.Now()
	err = downloadChunks(ctx, cfg, ChunkAuth{RelayToken: relayToken, DeviceID: cfg.DeviceID, PeerKey: peerCryptoKey}, transferID, sessionID, digest, aesKey, missing, partPath, state, statePath, func(done, total int64) {
		if time.Since(lastPrint) < 250*time.Millisecond {
			return
		}
		lastPrint = time.Now()
		pct := 0.0
		if total > 0 {
			pct = float64(done) / float64(total) * 100
		}
		log.Printf("[RECV] %s: %.1f%% (%.1f/%.1f MB)", cleanFileName, pct, float64(done)/(1024*1024), float64(total)/(1024*1024))
	})
	if err != nil {
		if ctx.Err() == context.Canceled {
			log.Printf("[RECV] Transfer %s superseded by a newer attempt", transferID)
			return
		}
		log.Printf("[RECV] Download failed for %s: %v", transferID, err)
		_ = wsConn.WriteJSON(map[string]interface{}{
			"type": "transfer_error", "target_id": senderID, "transfer_id": transferID, "reason": err.Error(),
		})
		notifyUser("Transfer Failed", fmt.Sprintf("Failed receiving '%s' from %s", cleanFileName, senderName))
		return
	}
	state.Save(statePath)

	if fileSize == 0 {
		if _, err := os.Stat(partPath); os.IsNotExist(err) {
			if f, err := os.Create(partPath); err == nil {
				_ = f.Close()
			}
		}
	}
	ok, err := VerifyFileHash(partPath, fileHash)
	if err != nil || !ok {
		log.Printf("[RECV] Integrity check failed for %s: %v", transferID, err)
		_ = os.Remove(partPath)
		_ = os.Remove(statePath)
		_ = wsConn.WriteJSON(map[string]interface{}{
			"type": "transfer_error", "target_id": senderID, "transfer_id": transferID, "reason": "integrity_failed",
		})
		notifyUser("Transfer Failed", fmt.Sprintf("'%s' failed integrity verification", cleanFileName))
		return
	}

	finalPath := ResolveUniqueFile(cfg.InboxDir, cleanFileName)
	if err := os.Rename(partPath, finalPath); err != nil {
		log.Printf("[RECV] Rename failed for %s: %v", transferID, err)
		_ = wsConn.WriteJSON(map[string]interface{}{
			"type": "transfer_error", "target_id": senderID, "transfer_id": transferID, "reason": "save_failed",
		})
		return
	}
	_ = os.Remove(statePath)

	receipt := ComputeAuthTag(peerCryptoKey, fmt.Sprintf("complete:v2:%s:%s", transferID, fileHash))
	_ = wsConn.WriteJSON(map[string]interface{}{
		"type": "transfer_complete", "v": 2, "target_id": senderID,
		"transfer_id": transferID, "file_hash": fileHash, "auth_tag": receipt,
	})

	if openRequested {
		if cfg.AutoOpen {
			log.Printf("[RECV] Opening '%s' (mime: %s) as requested by sender", filepath.Base(finalPath), mimeType)
			openFile(finalPath)
		} else {
			log.Printf("[RECV] Sender requested open for '%s' but auto-open is disabled", filepath.Base(finalPath))
		}
	}

	duration := time.Since(startTime).Seconds()
	if duration <= 0 {
		duration = 0.001
	}
	mbps := (float64(fileSize) / (1024 * 1024)) / duration
	log.Printf("[RECV] SUCCESS: Saved '%s' (%.2f MB at %.1f MB/s)", filepath.Base(finalPath), float64(fileSize)/(1024*1024), mbps)
	notifyUser("Transfer Complete", fmt.Sprintf("Saved '%s' (%.1f MB)", filepath.Base(finalPath), float64(fileSize)/(1024*1024)))
}

type JSONPeer struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Status string `json:"status"`
	IsSelf bool   `json:"is_self"`
}

func runList(cfg Config, asJSON bool) {
	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		if asJSON {
			fmt.Println("[]")
			return
		}
		log.Fatalf("[FATAL] %v", err)
	}
	relayToken, _ := DeriveSubKeys(token)

	// Discovery must not reuse the daemon's device ID: the relay replaces an
	// existing peer with the same ID, so a poll would evict the daemon.
	listCfg := cfg
	listCfg.DeviceID = fmt.Sprintf("list-%d", os.Getpid())

	conn, err := connectWS(listCfg, relayToken)
	if err != nil {
		if asJSON {
			fmt.Println("[]")
			return
		}
		log.Fatalf("[FATAL] Unable to connect to relay at %s: %v", cfg.RelayAddr, err)
	}
	defer conn.Close()

	_ = conn.SetReadDeadline(time.Now().Add(4 * time.Second))
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			if asJSON {
				fmt.Println("[]")
				return
			}
			log.Fatalf("[FATAL] Timeout or error waiting for peer list: %v", err)
		}

		var envelope map[string]interface{}
		if err := json.Unmarshal(msgBytes, &envelope); err != nil {
			continue
		}

		if envelope["type"] == "peers" {
			peersRaw, _ := envelope["peers"].([]interface{})
			if asJSON {
				out := make([]JSONPeer, 0)
				for _, p := range peersRaw {
					pMap, _ := p.(map[string]interface{})
					id, _ := pMap["id"].(string)
					name, _ := pMap["name"].(string)
					if id == listCfg.DeviceID {
						continue
					}
					isSelf := strings.EqualFold(name, cfg.Name)
					out = append(out, JSONPeer{
						ID:     id,
						Name:   name,
						Status: "online",
						IsSelf: isSelf,
					})
				}
				jsonBytes, _ := json.Marshal(out)
				fmt.Println(string(jsonBytes))
				return
			}

			fmt.Println("\nONLINE CLUSTER DEVICES (IN-TUNNEL)")
			fmt.Println("────────────────────────────────────────────────────────────────────────")
			fmt.Printf("%-24s %-28s %-12s\n", "DEVICE ID", "NAME", "STATUS")
			fmt.Println("────────────────────────────────────────────────────────────────────────")
			for _, p := range peersRaw {
				pMap, _ := p.(map[string]interface{})
				id, _ := pMap["id"].(string)
				name, _ := pMap["name"].(string)
				if id == listCfg.DeviceID {
					continue
				}
				isSelf := strings.EqualFold(name, cfg.Name)
				status := "online"
				if isSelf {
					status = "online (this device)"
				}
				fmt.Printf("%-24s %-28s %-12s\n", id, name, status)
			}
			fmt.Println("────────────────────────────────────────────────────────────────────────")
			fmt.Println()
			return
		}
	}
}

type sendFileMeta struct {
	Path string
	Name string
	Size int64
	Open bool
}

// runSendText sends a one-shot text or link to a peer. The payload is
// encrypted with a key domain-separated from file transfers and clipboard
// sync; the relay only sees ciphertext and routing metadata.
func runSendText(cfg Config, targetQuery, text string) {
	// Do not reuse the daemon's device ID: the relay replaces an existing
	// peer with the same ID, which would evict the receiver mid-session.
	// Keep the stable identity in SenderID so the receiver can apply trust.
	cfg.SenderID = cfg.DeviceID
	cfg.DeviceID = fmt.Sprintf("send-%d", os.Getpid())

	if text == "-" {
		data, err := io.ReadAll(io.LimitReader(os.Stdin, 64*1024+1))
		if err != nil {
			log.Fatalf("[FATAL] Could not read text from stdin: %v", err)
		}
		text = string(data)
	}
	text = strings.TrimSpace(text)
	if text == "" {
		log.Fatalf("[FATAL] Refusing to send empty text")
	}
	if len(text) > 64*1024 {
		log.Fatalf("[FATAL] Text is too large (max 64 KiB)")
	}

	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	relayToken, peerCryptoKey := DeriveSubKeys(token)

	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		log.Fatalf("[FATAL] Could not connect to relay: %v", err)
	}
	defer conn.Close()
	client := &wsClient{conn: conn}

	targetID, targetName := discoverTarget(conn, targetQuery)
	if targetID == "" {
		log.Fatalf("[FATAL] Target device '%s' is not currently online in the tunnel", targetQuery)
	}

	kind := "text"
	if strings.HasPrefix(text, "http://") || strings.HasPrefix(text, "https://") {
		kind = "url"
	}
	msgID, err := randomHex(16)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	msgID = "text-" + msgID
	ts := time.Now().UnixMilli()
	nonce, err := RandomBytes(12)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	aad := fmt.Sprintf("text:%s:%s:%d:%s", cfg.DeviceID, msgID, ts, kind)
	ciphertext, err := EncryptClipboard(DeriveTextKey(peerCryptoKey), nonce, []byte(text), []byte(aad))
	if err != nil {
		log.Fatalf("[FATAL] Could not encrypt text: %v", err)
	}

	msg := map[string]interface{}{
		"type":      "text_message",
		"v":         1,
		"target_id": targetID,
		"sender_id": cfg.SenderID,
		"msg_id":    msgID,
		"ts":        ts,
		"kind":      kind,
		"nonce":     base64.StdEncoding.EncodeToString(nonce),
		"data":      base64.StdEncoding.EncodeToString(ciphertext),
	}
	if err := client.WriteJSON(msg); err != nil {
		log.Fatalf("[FATAL] Could not send text: %v", err)
	}
	fmt.Printf("Sent %s to %s (%s)\n", kind, targetName, targetID)
}

func runSend(cfg Config, targetQuery string, filePaths []string, openRequested bool) {
	// A send must not reuse the daemon's device ID: the relay replaces an
	// existing peer with the same ID, so the daemon's reconnect would evict
	// the sender mid-transfer. Keep the stable ID in SenderID so receivers
	// can pin trust to the machine rather than the connection.
	cfg.SenderID = cfg.DeviceID
	cfg.DeviceID = fmt.Sprintf("send-%d", os.Getpid())

	if len(filePaths) == 0 {
		log.Fatalf("[FATAL] No files specified")
	}

	metas := make([]sendFileMeta, 0, len(filePaths))
	for _, path := range filePaths {
		info, err := os.Stat(path)
		if err != nil {
			log.Fatalf("[FATAL] Cannot open file %s: %v", path, err)
		}
		if info.IsDir() {
			// Folder sends are a flat batch: every regular file is sent, and
			// the receiver resolves duplicate base names. Relative paths are
			// not preserved yet.
			err := filepath.Walk(path, func(p string, fi os.FileInfo, walkErr error) error {
				if walkErr != nil {
					return walkErr
				}
				if fi.Mode().IsRegular() {
					metas = append(metas, sendFileMeta{
						Path: p,
						Name: filepath.Base(p),
						Size: fi.Size(),
						Open: openRequested,
					})
				}
				return nil
			})
			if err != nil {
				log.Fatalf("[FATAL] Cannot read folder %s: %v", path, err)
			}
			continue
		}
		metas = append(metas, sendFileMeta{Path: path, Name: filepath.Base(path), Size: info.Size(), Open: openRequested})
	}
	if len(metas) == 0 {
		log.Fatalf("[FATAL] No files found to send")
	}

	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	relayToken, peerCryptoKey := DeriveSubKeys(token)

	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		log.Fatalf("[FATAL] Cannot connect to relay at %s: %v", cfg.RelayAddr, err)
	}
	defer conn.Close()

	targetID, targetName := discoverTarget(conn, targetQuery)
	if targetID == "" {
		log.Fatalf("[FATAL] Target device '%s' is not currently online in the tunnel", targetQuery)
	}
	fmt.Printf("Target located: %s (%s)\n", targetName, targetID)

	batchID := fmt.Sprintf("batch-%d", time.Now().UnixNano())
	startTime := time.Now()
	var sentTotal int64
	for i, meta := range metas {
		label := meta.Name
		if len(metas) > 1 {
			label = fmt.Sprintf("%d files (%d/%d): %s", len(metas), i+1, len(metas), meta.Name)
		}
		fmt.Printf("Sending %s (%.2f MB)...\n", label, float64(meta.Size)/(1024*1024))
		if err := sendOne(cfg, conn, relayToken, peerCryptoKey, targetID, targetName, meta, batchID, i, len(metas)); err != nil {
			notifyUser("Transfer Failed", fmt.Sprintf("Failed sending '%s': %v", meta.Name, err))
			log.Fatalf("[FATAL] %v", err)
		}
		sentTotal += meta.Size
	}

	duration := time.Since(startTime).Seconds()
	if duration <= 0 {
		duration = 0.001
	}
	mbps := (float64(sentTotal) / (1024 * 1024)) / duration
	notifyUser("Transfer Sent", fmt.Sprintf("Sent %d file(s) (%.1f MB) to %s", len(metas), float64(sentTotal)/(1024*1024), targetName))
	fmt.Printf("Transfer completed: %d file(s), %.1f MB in %.1fs (%.1f MB/s)\n",
		len(metas), float64(sentTotal)/(1024*1024), duration, mbps)
}

// discoverTarget waits for the relay peer list and resolves a device by id,
// exact name, or case-insensitive name substring.
func discoverTarget(conn *websocket.Conn, targetQuery string) (string, string) {
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	defer conn.SetReadDeadline(time.Time{})
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			return "", ""
		}
		var envelope map[string]interface{}
		if json.Unmarshal(msgBytes, &envelope) != nil {
			continue
		}
		if envelope["type"] != "peers" {
			continue
		}
		peersRaw, _ := envelope["peers"].([]interface{})
		for _, p := range peersRaw {
			pMap, _ := p.(map[string]interface{})
			id, _ := pMap["id"].(string)
			name, _ := pMap["name"].(string)
			if id == targetQuery || strings.EqualFold(name, targetQuery) ||
				strings.Contains(strings.ToLower(name), strings.ToLower(targetQuery)) {
				return id, name
			}
		}
		return "", ""
	}
}

type acceptResult struct {
	PubKey []byte
	Have   []int
}

// waitForAccept reads the signaling socket until the matching accept or reject
// arrives, ignoring unrelated peer-list traffic.
func waitForAccept(conn *websocket.Conn, transferID string, peerCryptoKey []byte, deviceID string) (*acceptResult, error) {
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	defer conn.SetReadDeadline(time.Time{})
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			return nil, fmt.Errorf("peer did not accept transfer within timeout: %w", err)
		}
		var envelope map[string]interface{}
		if json.Unmarshal(msgBytes, &envelope) != nil {
			continue
		}
		if envelope["type"] == "transfer_accept" && envelope["transfer_id"] == transferID {
			receiverPubB64, _ := envelope["pub_key"].(string)
			acceptTag, _ := envelope["auth_tag"].(string)
			have := parseHave(envelope["have"])
			expected := fmt.Sprintf("accept:v2:%s:%s:%s:%s", transferID, deviceID, receiverPubB64, HaveHash(have))
			if !VerifyAuthTag(peerCryptoKey, expected, acceptTag) {
				return nil, fmt.Errorf("accept auth tag verification failed")
			}
			pub, err := base64.StdEncoding.DecodeString(receiverPubB64)
			if err != nil {
				return nil, fmt.Errorf("invalid receiver public key: %w", err)
			}
			return &acceptResult{PubKey: pub, Have: have}, nil
		}
		if envelope["type"] == "transfer_reject" && envelope["transfer_id"] == transferID {
			reason, _ := envelope["reason"].(string)
			return nil, fmt.Errorf("peer declined the transfer (reason: %s)", reason)
		}
	}
}

func parseHave(raw interface{}) []int {
	arr, ok := raw.([]interface{})
	if !ok {
		return nil
	}
	out := make([]int, 0, len(arr))
	for _, v := range arr {
		switch n := v.(type) {
		case float64:
			if n >= 0 {
				out = append(out, int(n))
			}
		case int:
			if n >= 0 {
				out = append(out, n)
			}
		}
	}
	sort.Ints(out)
	return out
}

func parseStringArray(raw interface{}) []string {
	arr, ok := raw.([]interface{})
	if !ok {
		return nil
	}
	out := make([]string, 0, len(arr))
	for _, v := range arr {
		if s, ok := v.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

func randomHex(n int) (string, error) {
	b, err := RandomBytes(n)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func waitForComplete(conn *websocket.Conn, transferID string, peerCryptoKey []byte, deviceID string, digest *FileDigest) error {
	timeout := time.Duration(digest.Size/(512*1024)+120) * time.Second
	if timeout < 2*time.Minute {
		timeout = 2 * time.Minute
	}
	if timeout > 2*time.Hour {
		timeout = 2 * time.Hour
	}
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	defer conn.SetReadDeadline(time.Time{})
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			return fmt.Errorf("no completion receipt: %w", err)
		}
		var envelope map[string]interface{}
		if json.Unmarshal(msgBytes, &envelope) != nil {
			continue
		}
		if envelope["transfer_id"] != transferID {
			continue
		}
		switch envelope["type"] {
		case "transfer_complete":
			fileHash, _ := envelope["file_hash"].(string)
			tag, _ := envelope["auth_tag"].(string)
			expected := fmt.Sprintf("complete:v2:%s:%s", transferID, fileHash)
			if fileHash != digest.FileHash || !VerifyAuthTag(peerCryptoKey, expected, tag) {
				return fmt.Errorf("completion receipt failed verification")
			}
			return nil
		case "transfer_error":
			reason, _ := envelope["reason"].(string)
			return fmt.Errorf("receiver reported: %s", reason)
		}
	}
}

func sendOne(cfg Config, conn *websocket.Conn, relayToken string, peerCryptoKey []byte, targetID, targetName string, meta sendFileMeta, batchID string, batchIndex, batchTotal int) error {
	digest, err := HashFileChunks(meta.Path, ChunkSize)
	if err != nil {
		return fmt.Errorf("hash file: %w", err)
	}
	if digest.Size != meta.Size {
		return fmt.Errorf("file changed while hashing (expected %d bytes, read %d)", meta.Size, digest.Size)
	}
	transferID := DeriveTransferID(peerCryptoKey, digest.FileHash)

	const maxAttempts = 3
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		if attempt > 1 {
			fmt.Printf("Retrying %s (attempt %d/%d)...\n", meta.Name, attempt, maxAttempts)
			time.Sleep(time.Duration(attempt-1) * time.Second)
		}
		sessionID, err := randomHex(16)
		if err != nil {
			return fmt.Errorf("session id: %w", err)
		}
		err = sendAttempt(cfg, conn, relayToken, peerCryptoKey, targetID, targetName, meta, digest, transferID, sessionID, batchID, batchIndex, batchTotal)
		if err == nil {
			return nil
		}
		lastErr = err
		log.Printf("[SEND] Attempt %d for %s failed: %v", attempt, meta.Name, err)
	}
	return fmt.Errorf("transfer failed after %d attempts: %w", maxAttempts, lastErr)
}

func sendAttempt(cfg Config, conn *websocket.Conn, relayToken string, peerCryptoKey []byte, targetID, targetName string, meta sendFileMeta, digest *FileDigest, transferID, sessionID, batchID string, batchIndex, batchTotal int) error {
	keyPair, err := GenerateKeyPair()
	if err != nil {
		return fmt.Errorf("keypair generation failed: %w", err)
	}
	myPubB64 := base64.StdEncoding.EncodeToString(keyPair.Public)
	offerTag := ComputeAuthTag(peerCryptoKey, offerTagPayload(
		transferID, targetID, myPubB64, digest.FileHash, digest.ChunkCount, ChunkSize, meta.Open))

	senderID := cfg.SenderID
	if senderID == "" {
		senderID = cfg.DeviceID
	}
	offerMsg := map[string]interface{}{
		"type":         "transfer_offer",
		"v":            2,
		"target_id":    targetID,
		"sender_id":    senderID,
		"transfer_id":  transferID,
		"session_id":   sessionID,
		"file_name":    meta.Name,
		"file_size":    digest.Size,
		"chunk_size":   ChunkSize,
		"chunk_count":  digest.ChunkCount,
		"file_hash":    digest.FileHash,
		"chunk_hashes": digest.ChunkHashes,
		"pub_key":      myPubB64,
		"auth_tag":     offerTag,
		"batch_id":     batchID,
		"batch_index":  batchIndex,
		"batch_total":  batchTotal,
	}
	if meta.Open {
		offerMsg["open"] = true
		offerMsg["mime_type"] = mime.TypeByExtension(filepath.Ext(meta.Path))
	}
	if err := conn.WriteJSON(offerMsg); err != nil {
		return fmt.Errorf("send offer failed: %w", err)
	}

	accept, err := waitForAccept(conn, transferID, peerCryptoKey, cfg.DeviceID)
	if err != nil {
		return err
	}
	aesKey, err := DeriveSharedSecret(keyPair.Private, accept.PubKey)
	if err != nil {
		return fmt.Errorf("key derivation failed: %w", err)
	}
	safetyNumber := ComputeSafetyNumber(keyPair.Public, accept.PubKey, transferID)
	fmt.Printf("Accepted by %s. Safety number: %s\n", targetName, safetyNumber)

	have := make(map[int]bool, len(accept.Have))
	for _, idx := range accept.Have {
		have[idx] = true
	}
	missing := make([]int, 0, digest.ChunkCount)
	for i := 0; i < digest.ChunkCount; i++ {
		if !have[i] {
			missing = append(missing, i)
		}
	}
	if len(missing) > 0 {
		fmt.Printf("Uploading %d/%d chunks for %s...\n", len(missing), digest.ChunkCount, meta.Name)
	}

	startTime := time.Now()
	lastPrint := time.Now()
	err = uploadChunks(cfg, ChunkAuth{RelayToken: relayToken, DeviceID: cfg.DeviceID, PeerKey: peerCryptoKey}, transferID, sessionID, digest, aesKey, missing, meta.Path, func(done, total int64) {
		if time.Since(lastPrint) < 200*time.Millisecond {
			return
		}
		lastPrint = time.Now()
		pct := 0.0
		if total > 0 {
			pct = float64(done) / float64(total) * 100
		}
		speed := 0.0
		if elapsed := time.Since(startTime).Seconds(); elapsed > 0 {
			speed = (float64(done) / (1024 * 1024)) / elapsed
		}
		fmt.Printf("\rUploading %s: %.1f%% (%.1f/%.1f MB) %.1f MB/s",
			meta.Name, pct, float64(done)/(1024*1024), float64(total)/(1024*1024), speed)
	})
	if len(missing) > 0 {
		fmt.Println()
	}
	if err != nil {
		return err
	}

	// A relay 200 only proves the chunk was staged. Wait for the receiver's
	// signed receipt before calling the transfer done.
	if err := waitForComplete(conn, transferID, peerCryptoKey, cfg.DeviceID, digest); err != nil {
		return err
	}
	deleteTransfer(cfg, ChunkAuth{RelayToken: relayToken, DeviceID: cfg.DeviceID, PeerKey: peerCryptoKey}, transferID, sessionID)
	return nil
}

func main() {
	var cfg Config
	var jsonOutput bool
	defaultName, defaultRelay := loadSettingsFromConfig()
	flag.StringVar(&cfg.RelayAddr, "relay", defaultRelay, "In-tunnel relay address (host:port)")
	flag.StringVar(&cfg.TokenFile, "key-file", defaultTokenFile(), "Path to cluster secret key file")
	flag.StringVar(&cfg.DeviceID, "device-id", generateDeviceID(), "Unique device identifier")
	flag.StringVar(&cfg.Name, "name", defaultName, "Human readable device name")
	flag.StringVar(&cfg.InboxDir, "inbox", defaultInboxDir(), "Directory where incoming files are saved")
	flag.StringVar(&cfg.PeersFile, "peers-file", defaultPeersFile(), "Path to the peer state file watched by desktop shells")
	flag.BoolVar(&cfg.Clipboard, "clipboard", true, "Sync the desktop clipboard with cluster devices")
	flag.StringVar(&cfg.ClipboardSource, "clipboard-source", "poll", "Clipboard source: poll, socket, or off")
	flag.StringVar(&cfg.ClipboardSocket, "clipboard-socket", defaultClipboardSocket(), "Unix socket for Quickshell clipboard events")
	flag.StringVar(&cfg.LogFile, "log-file", "", "Append daemon logs to this file instead of stderr")
	flag.BoolVar(&jsonOutput, "json", false, "Output results as JSON")
	noOpen := false
	flag.BoolVar(&noOpen, "no-open", false, "Disable opening received files automatically")
	flag.BoolVar(&cfg.OnlyTrusted, "only-trusted", loadOnlyTrustedFromConfig(), "Reject offers from devices not in the trust list")
	flag.BoolVar(&cfg.Notifications, "notifications", true, "Display notifications mirrored from other devices")
	flag.Parse()

	cfg.AutoOpen = !noOpen

	if cfg.LogFile != "" {
		if f, err := os.OpenFile(cfg.LogFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err == nil {
			log.SetOutput(f)
		}
	}

	args := flag.Args()
	if len(args) == 0 {
		fmt.Println("gaur-share: In-Tunnel Zero-Knowledge File Sharing Client")
		fmt.Println("\nUsage:")
		fmt.Println("  gaur-share daemon                   Run background receiver daemon")
		fmt.Println("  gaur-share list [-json]             List all active cluster devices")
		fmt.Println("  gaur-share send [-open] <device> <file|dir>...  Send files or a folder (flat batch)")
		fmt.Println("  gaur-share send --all <file|dir>...          Upload once, send to every online device")
		fmt.Println("  gaur-share send --offline <device> <file>... Queue for a device that may be offline")
		fmt.Println("  gaur-share send --to <id,id> <file>...       Send to specific device IDs")
		fmt.Println("  gaur-share send-text <device> <text|->       Send a text or link (use - for stdin)")
		fmt.Println("  gaur-share pair <gaur://pair?...>           Pair using the QR URI from gaur-relay -init")
		fmt.Println("  gaur-share ui [-addr 127.0.0.1:0]           Local browser UI for peers, text, and files")
		fmt.Println("  gaur-share notify <device> <title> [body]   Send a notification to a device")
		fmt.Println("  gaur-share notify --all <title> [body]      Send a notification to every online device")
		fmt.Println("  gaur-share trust <device>                   Trust a device by id or name")
		fmt.Println("  gaur-share untrust <device>                 Revoke a trusted device")
		fmt.Println("  gaur-share trusted                          List trusted devices and mode")
		fmt.Println("  gaur-share trust-mode on|off                Only accept from trusted devices")
		fmt.Println("\nFlags:")
		flag.PrintDefaults()
		return
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigChan
		fmt.Println("\nShutting down gaur-share...")
		if args[0] == "daemon" {
			writePeersFile(cfg, nil)
		}
		os.Exit(0)
	}()

	switch args[0] {
	case "daemon":
		runDaemon(cfg)
	case "list":
		for _, a := range args[1:] {
			if a == "-json" || a == "--json" {
				jsonOutput = true
			}
		}
		runList(cfg, jsonOutput)
	case "send":
		sendFlags := flag.NewFlagSet("send", flag.ExitOnError)
		openRequested := sendFlags.Bool("open", false, "Ask the receiving device to open the file on arrival")
		sendAll := sendFlags.Bool("all", false, "Send to every online device (v3, one upload)")
		sendOffline := sendFlags.Bool("offline", false, "Queue for a device that may be offline (v3)")
		sendTo := sendFlags.String("to", "", "Comma-separated device IDs (v3)")
		if err := sendFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		rest := sendFlags.Args()
		switch {
		case *sendAll:
			if len(rest) < 1 {
				log.Fatalf("Usage: gaur-share send --all <file|dir>...")
			}
			runSendV3(cfg, nil, rest, false, *openRequested)
		case *sendTo != "":
			if len(rest) < 1 {
				log.Fatalf("Usage: gaur-share send --to <id,id> <file|dir>...")
			}
			runSendV3(cfg, splitTargets(*sendTo), rest, false, *openRequested)
		case *sendOffline:
			if len(rest) < 2 {
				log.Fatalf("Usage: gaur-share send --offline <device-id> <file|dir>...")
			}
			runSendV3(cfg, []string{rest[0]}, rest[1:], true, *openRequested)
		default:
			if len(rest) < 2 {
				log.Fatalf("Usage: gaur-share send [-open] <target-device> <file-path> [file-path...]")
			}
			runSend(cfg, rest[0], rest[1:], *openRequested)
		}
	case "send-text":
		if len(args) < 3 {
			log.Fatalf("Usage: gaur-share send-text <target-device> <text|->")
		}
		runSendText(cfg, args[1], strings.Join(args[2:], " "))
	case "pair":
		uri := ""
		if len(args) >= 2 {
			uri = args[1]
		} else {
			data, err := io.ReadAll(io.LimitReader(os.Stdin, 8*1024))
			if err != nil {
				log.Fatalf("[FATAL] Could not read pairing URI: %v", err)
			}
			uri = string(data)
		}
		if err := runPair(cfg, uri); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
	case "ui":
		uiFlags := flag.NewFlagSet("ui", flag.ExitOnError)
		uiAddr := uiFlags.String("addr", "127.0.0.1:0", "Local address for the UI")
		if err := uiFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		runUI(cfg, *uiAddr)
	case "notify":
		notifyFlags := flag.NewFlagSet("notify", flag.ExitOnError)
		notifyAll := notifyFlags.Bool("all", false, "Send to every online device")
		notifyIcon := notifyFlags.String("icon", "", "Icon file (PNG, max 64 KiB)")
		if err := notifyFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		rest := notifyFlags.Args()
		if *notifyAll {
			if len(rest) < 1 {
				log.Fatalf("Usage: gaur-share notify --all <title> [body...]")
			}
			runNotifyAll(cfg, rest[0], strings.Join(rest[1:], " "), *notifyIcon)
		} else {
			if len(rest) < 2 {
				log.Fatalf("Usage: gaur-share notify <device> <title> [body...]")
			}
			runNotify(cfg, rest[0], rest[1], strings.Join(rest[2:], " "), *notifyIcon)
		}
	case "trust":
		if len(args) < 2 {
			log.Fatalf("Usage: gaur-share trust <device-id-or-name>")
		}
		id, name := resolveTrustTarget(args[1])
		if err := trustDevice(id, name); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		fmt.Printf("Trusted %s (%s)\n", name, id)
	case "untrust":
		if len(args) < 2 {
			log.Fatalf("Usage: gaur-share untrust <device-id-or-name>")
		}
		id, name := resolveTrustTarget(args[1])
		removed, err := untrustDevice(id)
		if err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		if removed {
			fmt.Printf("Revoked %s (%s)\n", name, id)
		} else {
			fmt.Printf("%s was not trusted\n", name)
		}
	case "trusted":
		devices, err := loadTrustedDevices()
		if err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		mode := "off"
		if onlyTrustedEnabled(cfg) {
			mode = "on"
		}
		fmt.Printf("Only trusted devices: %s\n", mode)
		if len(devices) == 0 {
			fmt.Println("No trusted devices.")
			return
		}
		for _, d := range devices {
			fmt.Printf("%-24s %s\n", d.ID, d.Name)
		}
	case "trust-mode":
		if len(args) < 2 || (args[1] != "on" && args[1] != "off") {
			log.Fatalf("Usage: gaur-share trust-mode on|off")
		}
		if err := setOnlyTrusted(args[1] == "on"); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		fmt.Printf("Only trusted devices: %s\n", args[1])
	default:
		log.Fatalf("Unknown command: %s. Valid commands: daemon, list, send, send-text, pair, ui, notify, trust, untrust, trusted, trust-mode", args[0])
	}
}
