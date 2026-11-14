package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

// DeriveFileKey is the shared content key for v3 transfers. Unlike v2's
// per-recipient ECDH key, this one is derived from K_peer and the transfer id,
// so one uploaded ciphertext can be fetched by every recipient.
func DeriveFileKey(peerCryptoKey []byte, transferID string) []byte {
	mac := hmac.New(sha256.New, peerCryptoKey)
	mac.Write([]byte("gaur-shared-file-v1:" + transferID))
	return mac.Sum(nil)
}

func deriveSealKey(peerCryptoKey []byte, kind string) []byte {
	mac := hmac.New(sha256.New, peerCryptoKey)
	mac.Write([]byte("gaur-seal-v3:" + kind))
	return mac.Sum(nil)
}

func sealEnvelope(
	peerCryptoKey []byte,
	targetID, kind string,
	inner map[string]interface{},
) (map[string]interface{}, error) {
	msgID, err := randomHex(16)
	if err != nil {
		return nil, err
	}
	msgID = "seal-" + msgID
	ts := time.Now().UnixMilli()
	nonce, err := RandomBytes(12)
	if err != nil {
		return nil, err
	}
	data, err := json.Marshal(inner)
	if err != nil {
		return nil, err
	}
	aad := []byte(fmt.Sprintf("seal:v3:%s:%s:%d:%s", targetID, msgID, ts, kind))
	ciphertext, err := EncryptClipboard(
		deriveSealKey(peerCryptoKey, kind), nonce, data, aad,
	)
	if err != nil {
		return nil, err
	}
	return map[string]interface{}{
		"type":      "sealed",
		"v":         3,
		"target_id": targetID,
		"msg_id":    msgID,
		"ts":        ts,
		"kind":      kind,
		"nonce":     base64.StdEncoding.EncodeToString(nonce),
		"data":      base64.StdEncoding.EncodeToString(ciphertext),
	}, nil
}

func numberToInt64(value interface{}) int64 {
	switch n := value.(type) {
	case int64:
		return n
	case int:
		return int64(n)
	case float64:
		return int64(n)
	case json.Number:
		v, _ := n.Int64()
		return v
	default:
		return 0
	}
}

func openSealed(
	peerCryptoKey []byte,
	envelope map[string]interface{},
) (string, map[string]interface{}, error) {
	targetID, _ := envelope["target_id"].(string)
	msgID, _ := envelope["msg_id"].(string)
	ts := numberToInt64(envelope["ts"])
	kind, _ := envelope["kind"].(string)
	nonceB64, _ := envelope["nonce"].(string)
	dataB64, _ := envelope["data"].(string)
	if targetID == "" || msgID == "" || ts <= 0 || kind == "" ||
		nonceB64 == "" || dataB64 == "" {
		return "", nil, fmt.Errorf("malformed sealed envelope")
	}
	nonce, err := base64.StdEncoding.DecodeString(nonceB64)
	if err != nil {
		return "", nil, err
	}
	ciphertext, err := base64.StdEncoding.DecodeString(dataB64)
	if err != nil {
		return "", nil, err
	}
	aad := []byte(fmt.Sprintf("seal:v3:%s:%s:%d:%s", targetID, msgID, ts, kind))
	plaintext, err := DecryptClipboard(
		deriveSealKey(peerCryptoKey, kind), nonce, ciphertext, aad,
	)
	if err != nil {
		return "", nil, err
	}
	if len(plaintext) > 512*1024 {
		return "", nil, fmt.Errorf("sealed payload too large")
	}
	var inner map[string]interface{}
	if err := json.Unmarshal(plaintext, &inner); err != nil {
		return "", nil, err
	}
	return kind, inner, nil
}

// runSendV3 uploads each file once and sends a sealed offer to every target.
// Targets are stable device ids; an offline target is served by the relay's
// mailbox when it reconnects.
func runSendV3(cfg Config, targets []string, filePaths []string, offline, openRequested bool) {
	cfg.SenderID = cfg.DeviceID
	cfg.DeviceID = cfg.SenderID + "-out"

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
	client := &wsClient{conn: conn}

	if len(targets) == 0 {
		targets = onlinePeerIDs(conn, cfg.DeviceID, cfg.SenderID)
		if len(targets) == 0 {
			log.Fatalf("[FATAL] No other devices are online")
		}
	}

	metas := collectSendMetas(filePaths, openRequested)
	if len(metas) == 0 {
		log.Fatalf("[FATAL] No files found to send")
	}

	batchID := fmt.Sprintf("batch-%d", time.Now().UnixNano())
	for index, meta := range metas {
		digest, err := HashFileChunks(meta.Path, ChunkSize)
		if err != nil {
			log.Fatalf("[FATAL] Could not hash %s: %v", meta.Path, err)
		}
		transferID := DeriveTransferID(peerCryptoKey, digest.FileHash)
		sessionID, err := randomHex(16)
		if err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		fileKey := DeriveFileKey(peerCryptoKey, transferID)
		all := make([]int, digest.ChunkCount)
		for i := range all {
			all[i] = i
		}

		fmt.Printf("Uploading %s once for %d device(s)...\n", meta.Name, len(targets))
		if err := uploadChunks(
			cfg, ChunkAuth{RelayToken: relayToken, DeviceID: cfg.DeviceID, PeerKey: peerCryptoKey},
			transferID, sessionID, digest, fileKey, all, meta.Path,
			func(done, total int64) {
				if total > 0 {
					fmt.Printf("\r  %5.1f%%", float64(done)/float64(total)*100)
				}
			},
		); err != nil {
			log.Fatalf("[FATAL] Upload failed for %s: %v", meta.Name, err)
		}
		fmt.Println()

		for _, target := range targets {
			inner := map[string]interface{}{
				"sender_id":    cfg.SenderID,
				"sender_name":  cfg.Name,
				"transfer_id":  transferID,
				"session_id":   sessionID,
				"file_name":    meta.Name,
				"file_size":    meta.Size,
				"chunk_size":   ChunkSize,
				"chunk_count":  digest.ChunkCount,
				"file_hash":    digest.FileHash,
				"chunk_hashes": digest.ChunkHashes,
				"batch_id":     batchID,
				"batch_index":  index,
				"batch_total":  len(metas),
				"open":         meta.Open,
				"offline":      offline,
			}
			envelope, err := sealEnvelope(peerCryptoKey, target, "offer", inner)
			if err != nil {
				log.Printf("[SEND] Could not seal offer for %s: %v", target, err)
				continue
			}
			if err := client.WriteJSON(envelope); err != nil {
				log.Printf("[SEND] Could not send offer to %s: %v", target, err)
			}
		}
		fmt.Printf("Queued %s for %d device(s)\n", meta.Name, len(targets))
	}
}

func collectSendMetas(filePaths []string, openRequested bool) []sendFileMeta {
	var metas []sendFileMeta
	for _, path := range filePaths {
		info, err := os.Stat(path)
		if err != nil {
			log.Fatalf("[FATAL] Cannot open %s: %v", path, err)
		}
		if info.IsDir() {
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
		metas = append(metas, sendFileMeta{
			Path: path,
			Name: filepath.Base(path),
			Size: info.Size(),
			Open: openRequested,
		})
	}
	return metas
}

func onlinePeerIDs(conn *websocket.Conn, selfIDs ...string) []string {
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	defer conn.SetReadDeadline(time.Time{})
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			return nil
		}
		var envelope map[string]interface{}
		if json.Unmarshal(msgBytes, &envelope) != nil {
			continue
		}
		if envelope["type"] != "peers" {
			continue
		}
		peersRaw, _ := envelope["peers"].([]interface{})
		ids := make([]string, 0, len(peersRaw))
		for _, p := range peersRaw {
			pMap, _ := p.(map[string]interface{})
			id, _ := pMap["id"].(string)
			if id == "" {
				continue
			}
			self := false
			for _, selfID := range selfIDs {
				if id == selfID {
					self = true
					break
				}
			}
			if !self {
				ids = append(ids, id)
			}
		}
		return ids
	}
}

// handleIncomingSealed decrypts a v3 envelope and dispatches it. The relay
// never sees the sender id, file name, or payload.
func handleIncomingSealed(
	cfg Config,
	relayToken string,
	peerCryptoKey []byte,
	client *wsClient,
	mpris *MprisManager,
	localMedia *LocalMediaWatcher,
	envelope map[string]interface{},
) {
	// The seal key is shared by the whole cluster per kind, so without this
	// check a malicious relay could redirect an envelope addressed to another
	// device here, where it would decrypt and be processed all the same.
	targetID, _ := envelope["target_id"].(string)
	if targetID != cfg.DeviceID {
		log.Printf("[SEALED] Dropped envelope addressed to %s, not this device (%s)", targetID, cfg.DeviceID)
		return
	}
	// Replay protection: a duplicate msg_id is dropped, and command kinds
	// (media_command) expire after their freshness window.
	msgID, _ := envelope["msg_id"].(string)
	kind, _ := envelope["kind"].(string)
	if !replayGuard.Allow(kind, msgID, envelopeRelayTs(envelope)) {
		log.Printf("[SEALED] Dropped replayed or expired %s envelope (msg_id=%s)", kind, msgID)
		return
	}
	kind, inner, err := openSealed(peerCryptoKey, envelope)
	if err != nil {
		log.Printf("[SEALED] Could not open %v envelope: %v", envelope["kind"], err)
		return
	}
	senderID, _ := inner["sender_id"].(string)
	senderName, _ := inner["sender_name"].(string)
	if senderID == "" {
		return
	}
	if senderName == "" {
		senderName = senderID
	}
	if onlyTrustedEnabled(cfg) && senderID != cfg.DeviceID && !isTrustedDevice(senderID) {
		log.Printf("[SEALED] Dropped %s from untrusted %s", kind, senderID)
		return
	}

	switch kind {
	case "media_state":
		if mpris != nil {
			state := mediaStateFromInner(inner)
			log.Printf(
				"[MPRIS] State from %s: %q — %s (playing=%v)",
				state.SenderName, state.Title, state.App, state.Playing,
			)
			mpris.Update(state)
		}
	case "media_command":
		if localMedia != nil {
			localMedia.Execute(inner)
		}
	case "offer":
		go handleSealedOffer(cfg, relayToken, peerCryptoKey, client, senderID, senderName, inner)
	case "complete":
		fileName, _ := inner["file_name"].(string)
		if fileName == "" {
			fileName = "file"
		}
		notifyUser("Delivered", fmt.Sprintf("%s received %s", senderName, fileName))
		log.Printf("[SEALED] Delivery receipt from %s for %s", senderID, fileName)
	case "notification_ack":
		status, _ := inner["status"].(string)
		notifKey, _ := inner["notif_key"].(string)
		log.Printf("[SEALED] Notification %s on %s: %s", notifKey, senderName, status)
		if status != "displayed" {
			notifyUser("Notification blocked", fmt.Sprintf("%s could not display it", senderName))
		}
	case "notification":
		if event, _ := inner["event"].(string); event == "removed" {
			return
		}
		data, err := json.Marshal(inner)
		if err != nil {
			return
		}
		var payload mirroredNotification
		if json.Unmarshal(data, &payload) != nil {
			return
		}
		displayMirroredNotification(cfg, client, peerCryptoKey, senderID, senderName, payload)
	case "text":
		// Sealed v3 text/link share (docs section 6): same clipboard + toast
		// behavior as the v1 path, minus the wire-visible sender.
		applyIncomingText(senderID, senderName, stringField(inner, "kind"), []byte(stringField(inner, "text")))
	}
}

func handleSealedOffer(
	cfg Config,
	relayToken string,
	peerCryptoKey []byte,
	client *wsClient,
	senderID, senderName string,
	inner map[string]interface{},
) {
	transferID, _ := inner["transfer_id"].(string)
	sessionID, _ := inner["session_id"].(string)
	fileName, _ := inner["file_name"].(string)
	fileHash, _ := inner["file_hash"].(string)
	fileSizeFloat, _ := inner["file_size"].(float64)
	chunkSizeFloat, _ := inner["chunk_size"].(float64)
	chunkCountFloat, _ := inner["chunk_count"].(float64)
	fileSize := int64(fileSizeFloat)
	chunkSize := int(chunkSizeFloat)
	chunkCount := int(chunkCountFloat)
	chunkHashes := parseStringArray(inner["chunk_hashes"])

	if transferID == "" || sessionID == "" || fileName == "" || fileHash == "" ||
		chunkSize <= 0 || chunkCount < 0 || len(chunkHashes) != chunkCount {
		log.Printf("[SEALED] Rejecting malformed offer from %s", senderID)
		return
	}
	if fileSize > 0 && chunkCount == 0 {
		return
	}

	cleanName := SanitizeFileName(fileName)
	destPath := filepath.Join(cfg.InboxDir, cleanName)
	if !IsPathInsideDirectory(destPath, cfg.InboxDir) {
		log.Printf("[SEALED] Path traversal attempt from %s: %q", senderID, fileName)
		return
	}
	if err := os.MkdirAll(cfg.InboxDir, 0o755); err != nil {
		log.Printf("[SEALED] Inbox unavailable: %v", err)
		return
	}

	partPath := filepath.Join(cfg.InboxDir, fmt.Sprintf(".%s.%s.part", cleanName, transferID))
	statePath := partPath + ".state"
	state := NewReceiveState(transferID, fileHash, fileSize, chunkSize, chunkCount)
	_ = os.Remove(partPath)
	_ = os.Remove(statePath)
	state.Save(statePath)

	digest := &FileDigest{
		FileHash:    fileHash,
		ChunkHashes: chunkHashes,
		ChunkCount:  chunkCount,
		Size:        fileSize,
	}
	missing := make([]int, chunkCount)
	for i := range missing {
		missing[i] = i
	}
	fileKey := DeriveFileKey(peerCryptoKey, transferID)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()
	if err := downloadChunks(
		ctx, cfg, ChunkAuth{RelayToken: relayToken, DeviceID: cfg.DeviceID, PeerKey: peerCryptoKey},
		transferID, sessionID, digest, fileKey,
		missing, partPath, state, statePath, nil,
	); err != nil {
		log.Printf("[SEALED] Download failed for %s: %v", cleanName, err)
		return
	}
	ok, err := VerifyFileHash(partPath, fileHash)
	if err != nil || !ok {
		log.Printf("[SEALED] Integrity check failed for %s", cleanName)
		_ = os.Remove(partPath)
		return
	}
	finalPath := ResolveUniqueFile(cfg.InboxDir, cleanName)
	if err := os.Rename(partPath, finalPath); err != nil {
		log.Printf("[SEALED] Could not finalize %s: %v", cleanName, err)
		return
	}
	_ = os.Remove(statePath)
	notifyUser("File Received", fmt.Sprintf("%s from %s", filepath.Base(finalPath), senderName))
	log.Printf("[SEALED] Received %s from %s", filepath.Base(finalPath), senderID)

	completion := map[string]interface{}{
		"sender_id":   cfg.DeviceID,
		"sender_name": cfg.Name,
		"transfer_id": transferID,
		"file_hash":   fileHash,
		"file_name":   filepath.Base(finalPath),
	}
	envelope, err := sealEnvelope(peerCryptoKey, senderID, "complete", completion)
	if err != nil {
		log.Printf("[SEALED] Could not seal completion: %v", err)
		return
	}
	if err := client.WriteJSON(envelope); err != nil {
		log.Printf("[SEALED] Could not send completion: %v", err)
	}
}

func splitTargets(raw string) []string {
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}
