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
	default:
		return 0
	}
}

// envelopeString reads a string field from a JSON envelope. A missing or
// non-string field yields "" so a malformed envelope fails safe instead of
// misbehaving downstream.
func envelopeString(m map[string]interface{}, key string) string {
	s, _ := m[key].(string)
	return s
}

// envelopeInt64 reads a numeric field from a JSON envelope. JSON numbers
// arrive as float64; Go-constructed envelopes may carry int or int64. A
// missing or non-numeric field yields 0.
func envelopeInt64(m map[string]interface{}, key string) int64 {
	return numberToInt64(m[key])
}

func openSealed(
	peerCryptoKey []byte,
	envelope map[string]interface{},
) (string, map[string]interface{}, error) {
	targetID := envelopeString(envelope, "target_id")
	msgID := envelopeString(envelope, "msg_id")
	ts := envelopeInt64(envelope, "ts")
	kind := envelopeString(envelope, "kind")
	nonceB64 := envelopeString(envelope, "nonce")
	dataB64 := envelopeString(envelope, "data")
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

// v3SendRetryDelay separates upload attempts for one file. Package var so
// tests can shrink it.
var v3SendRetryDelay = 1 * time.Second

const (
	// peerListTimeout bounds how long a one-shot command waits for the
	// relay's peer list.
	peerListTimeout = 5 * time.Second
	// sealedOfferTimeout bounds one sealed offer's download: the staged
	// ciphertext is resumable, so an expired attempt loses nothing.
	sealedOfferTimeout = 30 * time.Minute
)

// runSendV3 uploads each file once and sends a sealed offer to every target.
// Targets are stable device ids; an offline target is served by the relay's
// mailbox when it reconnects.
func runSendV3(cfg Config, targets []string, filePaths []string, offline, openRequested bool) error {
	cfg.SenderID = cfg.DeviceID
	cfg.DeviceID = cfg.SenderID + "-out"

	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		return err
	}
	relayToken, peerCryptoKey := DeriveSubKeys(token)

	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		return fmt.Errorf("cannot connect to relay at %s: %w", cfg.RelayAddr, err)
	}
	defer conn.Close()
	client := &wsClient{conn: conn}

	if len(targets) == 0 {
		targets = onlinePeerIDs(conn, cfg.DeviceID, cfg.SenderID)
		if len(targets) == 0 {
			return fmt.Errorf("no other devices are online")
		}
	}

	metas, err := collectSendMetas(filePaths, openRequested)
	if err != nil {
		return err
	}
	if len(metas) == 0 {
		return fmt.Errorf("no files found to send")
	}

	batchID := fmt.Sprintf("batch-%d", time.Now().UnixNano())
	for index, meta := range metas {
		digest, err := HashFileChunks(meta.Path, ChunkSize)
		if err != nil {
			return fmt.Errorf("could not hash %s: %w", meta.Path, err)
		}
		transferID := DeriveTransferID(peerCryptoKey, digest.FileHash)
		// Deterministic session id: a retry lands in the same relay session,
		// so ciphertext staged by a failed attempt is reused, not orphaned.
		sessionID := DeriveSessionID(peerCryptoKey, transferID)
		fileKey := DeriveFileKey(peerCryptoKey, transferID)

		fmt.Printf("Uploading %s once for %d device(s)...\n", meta.Name, len(targets))
		if err := uploadV3Chunks(cfg, relayToken, peerCryptoKey, transferID, sessionID, digest, fileKey, meta); err != nil {
			return err
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
			// A pull request's target echoes the request id and grant. The
			// sender_id above is cfg.SenderID (the real device id), even
			// though this subprocess connects under a "-out" id: the
			// requester verifies the sender against the target it asked.
			if cfg.RequestMeta != nil {
				inner["request_id"] = cfg.RequestMeta.RequestID
				inner["grant"] = cfg.RequestMeta.Grant
			}
			envelope, err := sealEnvelope(peerCryptoKey, target, "offer", inner)
			if err != nil {
				log.Printf("[SEND] Could not seal offer for %s: %v", singleLineDisplayName(target), err)
				continue
			}
			if err := client.WriteJSON(envelope); err != nil {
				log.Printf("[SEND] Could not send offer to %s: %v", singleLineDisplayName(target), err)
			}
		}
		fmt.Printf("Queued %s for %d device(s)\n", meta.Name, len(targets))
	}
	return nil
}

// uploadV3Chunks pushes one file's chunks with sender-side resume. Each
// attempt asks the relay which indices are already staged and uploads only
// the missing ones; the session id is deterministic, so ciphertext staged by
// a failed attempt stays valid for the next one. After three failed attempts
// the error is returned — the CLI caller reports it and exits non-zero.
func uploadV3Chunks(cfg Config, relayToken string, peerCryptoKey []byte, transferID, sessionID string, digest *FileDigest, fileKey []byte, meta sendFileMeta) error {
	auth := ChunkAuth{RelayToken: relayToken, DeviceID: cfg.DeviceID, PeerKey: peerCryptoKey}
	const maxAttempts = 3
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		if attempt > 1 {
			time.Sleep(v3SendRetryDelay)
			fmt.Printf("Retrying upload for %s (attempt %d/%d)...\n", meta.Name, attempt, maxAttempts)
		}
		present := fetchChunkStatus(cfg, auth, transferID, sessionID, digest.ChunkCount)
		missing := missingChunkIndices(digest.ChunkCount, present)
		if len(missing) == 0 {
			return nil
		}
		if attempt > 1 && len(missing) < digest.ChunkCount {
			fmt.Printf("Resuming %s: %d/%d chunks already staged\n", meta.Name, digest.ChunkCount-len(missing), digest.ChunkCount)
		}
		err := uploadChunks(cfg, auth, transferID, sessionID, digest, fileKey, missing, meta.Path, func(done, total int64) {
			if total > 0 {
				fmt.Printf("\r  %5.1f%%", float64(done)/float64(total)*100)
			}
		})
		if err == nil {
			return nil
		}
		lastErr = err
		log.Printf("[SEND] v3 upload attempt %d/%d for %s failed: %v", attempt, maxAttempts, meta.Name, err)
	}
	return fmt.Errorf("upload failed for %s after %d attempts: %w", meta.Name, maxAttempts, lastErr)
}

// missingChunkIndices returns 0..count-1 minus the present set.
func missingChunkIndices(count int, present []int) []int {
	have := make(map[int]bool, len(present))
	for _, idx := range present {
		have[idx] = true
	}
	missing := make([]int, 0, count)
	for i := 0; i < count; i++ {
		if !have[i] {
			missing = append(missing, i)
		}
	}
	return missing
}

func onlinePeerIDs(conn *websocket.Conn, selfIDs ...string) []string {
	_ = conn.SetReadDeadline(time.Now().Add(peerListTimeout))
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
		if envelopeString(envelope, "type") != "peers" {
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

// parseStringArray reads a JSON array of strings, skipping non-string
// entries. Used for the chunk_hashes manifest on both the v2 and sealed
// offer paths.
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
	targetID := envelopeString(envelope, "target_id")
	if targetID != cfg.DeviceID {
		log.Printf("[SEALED] Dropped envelope addressed to %s, not this device (%s)",
			singleLineDisplayName(targetID), cfg.DeviceID)
		return
	}
	// Replay protection: a duplicate msg_id is dropped, and command kinds
	// (media_command) expire after their freshness window.
	msgID := envelopeString(envelope, "msg_id")
	kind := envelopeString(envelope, "kind")
	if !replayGuard.Allow(kind, msgID, envelopeRelayTs(envelope)) {
		log.Printf("[SEALED] Dropped replayed or expired %s envelope (msg_id=%s)", kind, msgID)
		return
	}
	kind, inner, err := openSealed(peerCryptoKey, envelope)
	if err != nil {
		log.Printf("[SEALED] Could not open %v envelope: %v", envelope["kind"], err)
		return
	}
	// A pull request carries requester_id/requester_name, not sender_id, so it
	// must be dispatched before the generic sender gate. Its own trust check
	// lives in handleIncomingRequest and declines rather than dropping.
	if kind == "request" {
		go handleIncomingRequest(cfg, peerCryptoKey, client, inner)
		return
	}
	senderID := envelopeString(inner, "sender_id")
	senderName := envelopeString(inner, "sender_name")
	if senderID == "" {
		return
	}
	if senderName == "" {
		senderName = senderID
	}
	// The name is display-only; the id keeps its wire value for trust,
	// routing, and diagnostics.
	senderName = singleLineDisplayName(senderName)
	if onlyTrustedEnabled(cfg) && senderID != cfg.DeviceID && !isTrustedDevice(senderID) {
		log.Printf("[SEALED] Dropped %s from untrusted %s", kind, singleLineDisplayName(senderID))
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
		fileName := envelopeString(inner, "file_name")
		if fileName == "" {
			fileName = "file"
		}
		notifyUser("Delivered", fmt.Sprintf("%s received %s", senderName, singleLineDisplayName(fileName)))
		log.Printf("[SEALED] Delivery receipt from %s for %s", singleLineDisplayName(senderID), singleLineDisplayName(fileName))
	case "notification_ack":
		status := envelopeString(inner, "status")
		notifKey := envelopeString(inner, "notif_key")
		log.Printf("[SEALED] Notification %s on %s: %s", notifKey, senderName, status)
		if status != "displayed" {
			notifyUser("Notification blocked", fmt.Sprintf("%s could not display it", senderName))
		}
	case "notification":
		if event := envelopeString(inner, "event"); event == "removed" {
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
	finalPath, err := receiveSealedOfferFile(cfg, relayToken, peerCryptoKey, cfg.DeviceID, senderID, inner)
	if err != nil {
		log.Printf("[SEALED] %v", err)
		return
	}
	notifyUser("File Received", fmt.Sprintf("%s from %s", filepath.Base(finalPath), senderName))
	log.Printf("[SEALED] Received %s from %s", filepath.Base(finalPath), singleLineDisplayName(senderID))

	completion := map[string]interface{}{
		"sender_id":   cfg.DeviceID,
		"sender_name": cfg.Name,
		"transfer_id": envelopeString(inner, "transfer_id"),
		"file_hash":   envelopeString(inner, "file_hash"),
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

// receiveSealedOfferFile downloads, verifies, and commits one sealed v3
// offer. It is shared by the daemon's push path and the request (pull)
// command; deviceID is the identity used for chunk auth — the daemon's id
// for a pushed offer, the request's ephemeral id for a requested one.
//
// Resume works like the v2 path: the .part file and chunk bitmap are reused
// when they belong to this exact transfer and file, and a failed download
// keeps both so the next offer resumes instead of restarting from zero.
func receiveSealedOfferFile(
	cfg Config,
	relayToken string,
	peerCryptoKey []byte,
	deviceID, senderID string,
	inner map[string]interface{},
) (string, error) {
	transferID := envelopeString(inner, "transfer_id")
	sessionID := envelopeString(inner, "session_id")
	fileName := envelopeString(inner, "file_name")
	fileHash := envelopeString(inner, "file_hash")
	fileSize := envelopeInt64(inner, "file_size")
	chunkSize := int(envelopeInt64(inner, "chunk_size"))
	chunkCount := int(envelopeInt64(inner, "chunk_count"))
	chunkHashes := parseStringArray(inner["chunk_hashes"])

	if transferID == "" || sessionID == "" || fileName == "" || fileHash == "" {
		return "", fmt.Errorf("rejecting malformed offer from %s", senderID)
	}
	// The transfer id becomes part of the .part and .state file names; only
	// the pinned "tx-" + 32 lowercase hex shape may reach a path.
	if !IsValidTransferID(transferID) {
		return "", fmt.Errorf("rejecting offer with invalid transfer id from %s", senderID)
	}
	// The same caps as the v2 path: chunk geometry is peer-supplied, and a
	// bad combination could address outside the file or make the receiver
	// hash a chunk count the sender never staged.
	if !sealedOfferGeometryOK(fileSize, chunkSize, chunkCount, len(chunkHashes)) {
		return "", fmt.Errorf("rejecting malformed offer from %s", senderID)
	}

	cleanName := SanitizeFileName(fileName)
	destPath := filepath.Join(cfg.InboxDir, cleanName)
	if !IsPathInsideDirectory(destPath, cfg.InboxDir) {
		return "", fmt.Errorf("path traversal attempt from %s: %q", senderID, fileName)
	}
	if err := ensureInboxDir(cfg.InboxDir); err != nil {
		return "", fmt.Errorf("inbox unavailable: %w", err)
	}

	partPath, statePath, pathOk := partPathsFor(cfg, cleanName, transferID)
	if !pathOk {
		return "", fmt.Errorf("rejecting offer with an escaping staging path from %s", senderID)
	}

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

	digest := &FileDigest{
		FileHash:    fileHash,
		ChunkHashes: chunkHashes,
		ChunkCount:  chunkCount,
		Size:        fileSize,
	}
	missing := state.Missing(chunkCount)
	if len(missing) > 0 && len(missing) < chunkCount {
		log.Printf("[SEALED] Resuming %s: %d/%d chunks already present", cleanName, chunkCount-len(missing), chunkCount)
	}
	fileKey := DeriveFileKey(peerCryptoKey, transferID)
	ctx, cancel := context.WithTimeout(context.Background(), sealedOfferTimeout)
	defer cancel()
	if err := downloadChunks(
		ctx, cfg, ChunkAuth{RelayToken: relayToken, DeviceID: deviceID, PeerKey: peerCryptoKey},
		transferID, sessionID, digest, fileKey,
		missing, partPath, state, statePath, nil,
	); err != nil {
		return "", fmt.Errorf("download failed for %s: %w", cleanName, err)
	}
	if fileSize == 0 {
		// An empty file has no chunks to download, so nothing created the
		// .part. Stage it owner-only like the v2 path; the hash check below
		// still runs.
		if _, err := os.Stat(partPath); os.IsNotExist(err) {
			if f, err := os.OpenFile(partPath, os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
				_ = f.Close()
			}
		}
	}
	ok, err := VerifyFileHash(partPath, fileHash)
	if err != nil || !ok {
		_ = os.Remove(partPath)
		return "", fmt.Errorf("integrity check failed for %s", cleanName)
	}
	finalPath, err := CommitUniqueFile(partPath, cfg.InboxDir, cleanName)
	if err != nil {
		return "", fmt.Errorf("could not finalize %s: %w", cleanName, err)
	}
	_ = os.Remove(statePath)
	return finalPath, nil
}

// sealedOfferGeometryOK applies the v2 receive caps to a sealed v3 offer:
// the chunk size must be exactly the pinned ChunkSize, the chunk count is
// bounded, the manifest must have one hash per chunk, and the file size must
// fit the chunks. An empty file is exactly (0 bytes, 0 chunks); a zero-byte
// file with chunks, or a non-empty file with none, is malformed.
func sealedOfferGeometryOK(fileSize int64, chunkSize, chunkCount, hashCount int) bool {
	validEmpty := fileSize == 0 && chunkCount == 0
	validNonEmpty := fileSize > 0 && chunkCount > 0
	return chunkSize == ChunkSize &&
		chunkCount >= 0 && chunkCount <= 65536 &&
		hashCount == chunkCount &&
		fileSize >= 0 && fileSize <= int64(chunkCount)*int64(ChunkSize) &&
		(validEmpty || validNonEmpty)
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
