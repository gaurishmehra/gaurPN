package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Reconnect delays: a failed dial waits longer than a dropped established
// link, which may just be the relay restarting.
const (
	reconnectDelayDialFailed = 5 * time.Second
	reconnectDelayAfterLink  = 2 * time.Second
)

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

// daemonReadTimeout bounds how long the read loop waits for any relay
// traffic — data, pong, or close — before treating the link as dead. Without
// it a half-open connection (the relay host vanished without a close frame)
// blocks ReadMessage forever while the ping writes keep succeeding into the
// void. Package var so tests can shrink the window.
var daemonReadTimeout = 60 * time.Second

// daemonPingEvery is the heartbeat interval. The relay answers every ping
// with a pong, which resets the read deadline on a healthy but otherwise
// idle link. Package var so tests can shrink the interval.
var daemonPingEvery = 20 * time.Second

// daemonSession carries the daemon state that survives relay reconnects: the
// peer cache, transfer bookkeeping, and the MPRIS plumbing.
type daemonSession struct {
	cfg             Config
	relayToken      string
	peerCryptoKey   []byte
	sender          *CommandSender
	broadcaster     *MediaBroadcaster
	store           *peerStore
	activeTransfers *sync.Map
	mpris           *MprisManager
	localMedia      *LocalMediaWatcher
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

	if err := ensureInboxDir(cfg.InboxDir); err != nil {
		log.Printf("[DAEMON] Inbox directory unavailable: %v", err)
	}
	log.Printf("[DAEMON] Starting gaur-share daemon as '%s' (ID: %s)", cfg.Name, cfg.DeviceID)
	log.Printf("[DAEMON] Incoming files will be saved to: %s", cfg.InboxDir)
	writePeersFile(cfg, nil)

	session := &daemonSession{
		cfg:             cfg,
		relayToken:      relayToken,
		peerCryptoKey:   peerCryptoKey,
		sender:          sender,
		broadcaster:     broadcaster,
		store:           &peerStore{},
		activeTransfers: &sync.Map{},
		mpris:           mpris,
		localMedia:      localMedia,
	}
	for {
		connected := session.runOnce()
		if !connected {
			time.Sleep(reconnectDelayDialFailed)
			continue
		}
		time.Sleep(reconnectDelayAfterLink)
	}
}

// runOnce runs one relay connection to completion: dial, heartbeat, and the
// message loop. It returns false when the dial itself failed so the caller
// applies the longer reconnect delay. The message loop exits on any read
// error — including the read deadline firing on a half-open link — after
// which the caller reconnects.
func (s *daemonSession) runOnce() bool {
	cfg := s.cfg
	conn, err := connectWS(cfg, s.relayToken)
	if err != nil {
		writePeersFile(cfg, nil)
		log.Printf("[DAEMON] Connecting to in-tunnel relay (%s)... Error: %v. Retrying in 5s...", cfg.RelayAddr, err)
		return false
	}
	client := &wsClient{conn: conn}
	s.sender.Set(client, s.peerCryptoKey)
	s.broadcaster.Set(client, s.peerCryptoKey)

	log.Printf("[DAEMON] Connected to in-tunnel relay at %s. Standing by for incoming transfers...", cfg.RelayAddr)

	// Heartbeat ping loop
	stopPing := make(chan struct{})
	go pingLoop(client, stopPing)

	// Clipboard sync lives for the duration of this relay connection.
	clipCtx, clipCancel := context.WithCancel(context.Background())
	if cfg.Clipboard {
		switch cfg.ClipboardSource {
		case "socket":
			go runClipboardSocketServer(clipCtx, cfg, client, s.peerCryptoKey, s.store)
		case "poll":
			go runClipboardSync(clipCtx, cfg, client, s.peerCryptoKey, s.store)
		case "off":
			// Explicitly disabled.
		default:
			log.Printf("[CLIP] Unknown clipboard source %q; clipboard sync disabled", cfg.ClipboardSource)
		}
	}

	// Message loop. The deadline bounds every ReadMessage so a link that
	// stops delivering anything — half-open, relay hung — is detected
	// instead of blocking the daemon forever.
	for {
		_ = conn.SetReadDeadline(time.Now().Add(daemonReadTimeout))
		_, msgBytes, err := client.ReadMessage()
		if err != nil {
			close(stopPing)
			clipCancel()
			s.sender.Set(nil, nil)
			s.broadcaster.Set(nil, nil)
			s.store.set(nil)
			writePeersFile(cfg, nil)
			log.Printf("[DAEMON] Relay connection interrupted: %v. Reconnecting...", err)
			_ = client.Close()
			break
		}

		var envelope map[string]interface{}
		if err := json.Unmarshal(msgBytes, &envelope); err != nil {
			continue
		}

		switch envelopeString(envelope, "type") {
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
			s.store.set(entries)
			writePeersFile(cfg, entries)
			s.broadcaster.SetPeers(peerIDs(entries))
			if s.localMedia != nil {
				s.localMedia.ForceRefresh()
			}
		case "transfer_offer":
			txID := envelopeString(envelope, "transfer_id")
			if txID == "" {
				continue
			}
			if old, ok := s.activeTransfers.Load(txID); ok {
				old.(*transferRun).cancel()
			}
			ctx, cancel := context.WithCancel(context.Background())
			run := &transferRun{cancel: cancel}
			s.activeTransfers.Store(txID, run)
			go func() {
				defer func() {
					if cur, ok := s.activeTransfers.Load(txID); ok && cur.(*transferRun) == run {
						s.activeTransfers.Delete(txID)
					}
					cancel()
				}()
				handleIncomingTransfer(ctx, cfg, s.relayToken, s.peerCryptoKey, client, envelope)
			}()
		case "clipboard_update":
			go handleIncomingClipboard(cfg, s.peerCryptoKey, envelope)
		case "text_message":
			go handleIncomingText(cfg, s.peerCryptoKey, envelope)
		case "notification_event":
			go handleIncomingNotification(cfg, s.peerCryptoKey, client, envelope)
		case "sealed":
			go handleIncomingSealed(cfg, s.relayToken, s.peerCryptoKey, client, s.mpris, s.localMedia, envelope)
		}
	}

	return true
}

// pingLoop sends the heartbeat ping every daemonPingEvery. On a write failure
// it closes the connection: gorilla's Conn.Close is safe from any goroutine,
// and the close unblocks the read loop, which drives the normal teardown and
// reconnect path. Returning without closing would strand the daemon on a
// dead link whose reads never fail.
func pingLoop(client *wsClient, stop chan struct{}) {
	ticker := time.NewTicker(daemonPingEvery)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			if err := client.WriteJSON(map[string]string{"type": "ping"}); err != nil {
				_ = client.Close()
				return
			}
		case <-stop:
			return
		}
	}
}
