package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// Echo-suppression windows. Applying a remote clip makes the local cliphist
// watcher fire; without a window the daemon would echo the clip back to the
// cluster. Images re-encode through cliphist and take longer to settle.
const (
	clipboardEchoSuppressTextMs  = 2000
	clipboardEchoSuppressImageMs = 8000
)

// textEchoSuppressWindow is the echo-suppression window after applying a
// received text share as a clipboard write.
const textEchoSuppressWindow = 2 * time.Second

func handleIncomingClipboard(cfg Config, peerCryptoKey []byte, envelope map[string]interface{}) {
	clipboardApplyMu.Lock()
	defer clipboardApplyMu.Unlock()

	fromID := envelopeString(envelope, "from_id")
	if fromID == "" || fromID == cfg.DeviceID {
		return
	}
	// fromID is bound into the AAD, so only the display copy is sanitized.
	logFromID := singleLineDisplayName(fromID)
	msgID := envelopeString(envelope, "msg_id")
	ts := envelopeInt64(envelope, "ts")
	relayTs := ts
	if _, ok := envelope["relay_ts"]; ok {
		relayTs = envelopeInt64(envelope, "relay_ts")
	}
	kind := envelopeString(envelope, "kind")
	mime := envelopeString(envelope, "mime")
	nonceB64 := envelopeString(envelope, "nonce")
	dataB64 := envelopeString(envelope, "data")
	if msgID == "" || ts <= 0 || (kind != "text" && kind != "image") || nonceB64 == "" || dataB64 == "" {
		log.Printf("[CLIP] Dropped malformed clipboard from %s (kind=%q msg=%q)", logFromID, kind, msgID)
		return
	}
	log.Printf("[CLIP] Received %s clipboard from %s (relayTs=%d, b64=%d)", kind, logFromID, relayTs, len(dataB64))

	clipboardStateMu.Lock()
	if relayTs <= clipboardLastTs {
		log.Printf("[CLIP] Dropped stale clipboard from %s (relayTs=%d last=%d)", logFromID, relayTs, clipboardLastTs)
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
		log.Printf("[CLIP] Authentication failed for clipboard from %s", logFromID)
		return
	}
	if kind == "text" && len(plaintext) > 1024*1024 {
		log.Printf("[CLIP] Rejecting oversized text clipboard from %s (%d bytes)", logFromID, len(plaintext))
		return
	}
	if kind == "image" && len(plaintext) > maxClipboardImageBytes {
		log.Printf("[CLIP] Rejecting oversized image clipboard from %s (%d bytes)", logFromID, len(plaintext))
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
	suppressMs := int64(clipboardEchoSuppressTextMs)
	if kind == "image" {
		suppressMs = clipboardEchoSuppressImageMs
	}
	clipboardSuppressUntil = time.Now().Add(time.Duration(suppressMs) * time.Millisecond).UnixMilli()
	clipboardSuppressKind = kind
	clipboardStateMu.Unlock()

	if err := writeClipboard(payload); err != nil {
		log.Printf("[CLIP] Could not apply clipboard from %s: %v", logFromID, err)
		return
	}
	log.Printf("[CLIP] Applied %s clipboard from %s (%d bytes)", kind, logFromID, len(plaintext))
}

// handleIncomingText decrypts a one-shot text/link share, copies it to the
// desktop clipboard, and raises a notification. It is intentionally not
// persisted: the relay never stores text, and the daemon keeps no history.
func handleIncomingText(cfg Config, peerCryptoKey []byte, envelope map[string]interface{}) {
	if envelopeInt64(envelope, "v") > 1 {
		return
	}
	senderIdentity := offerSenderID(envelope)
	if onlyTrustedEnabled(cfg) && senderIdentity != cfg.DeviceID &&
		!isTrustedDevice(senderIdentity) {
		log.Printf("[TEXT] Dropped text from untrusted %s", singleLineDisplayName(senderIdentity))
		return
	}
	fromID := envelopeString(envelope, "from_id")
	if fromID == "" || fromID == cfg.DeviceID {
		return
	}
	// fromName is display-only; fromID is bound into the AAD, so only its
	// display copy is sanitized.
	fromName := singleLineDisplayName(envelopeString(envelope, "from_name"))
	if fromName == "" {
		fromName = singleLineDisplayName(fromID)
	}
	logFromID := singleLineDisplayName(fromID)
	msgID := envelopeString(envelope, "msg_id")
	ts := envelopeInt64(envelope, "ts")
	kind := envelopeString(envelope, "kind")
	nonceB64 := envelopeString(envelope, "nonce")
	dataB64 := envelopeString(envelope, "data")
	if msgID == "" || ts <= 0 || (kind != "text" && kind != "url") || nonceB64 == "" || dataB64 == "" {
		log.Printf("[TEXT] Dropped malformed text message from %s", logFromID)
		return
	}
	if !replayGuard.Allow("text", msgID, envelopeRelayTs(envelope)) {
		log.Printf("[TEXT] Dropped replayed text message from %s (msg_id=%s)", logFromID, singleLineDisplayName(msgID))
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
		log.Printf("[TEXT] Authentication failed for text message from %s", logFromID)
		return
	}

	applyIncomingText(fromID, fromName, kind, plaintext)
}

// applyIncomingText copies a received text or link to the clipboard and
// raises a notification. It is shared by the v1 text_message path and the
// sealed v3 text path; neither persists the text anywhere.
func applyIncomingText(fromID, fromName, kind string, plaintext []byte) {
	if len(plaintext) == 0 || len(plaintext) > 64*1024 {
		log.Printf("[TEXT] Rejecting invalid text message from %s (%d bytes)", singleLineDisplayName(fromID), len(plaintext))
		return
	}
	text := string(plaintext)
	// Writing the clipboard makes Quickshell's cliphist watcher fire. Mark the
	// payload as already-applied so the daemon does not echo it back to the
	// cluster as a clipboard update.
	clipboardStateMu.Lock()
	clipboardLastHash = clipboardHash("text", "text/plain", plaintext)
	clipboardLastTs = time.Now().UnixMilli()
	clipboardSuppressUntil = time.Now().Add(textEchoSuppressWindow).UnixMilli()
	clipboardSuppressKind = "text"
	clipboardStateMu.Unlock()
	if err := writeClipboard(ClipboardPayload{Kind: "text", Mime: "text/plain", Data: plaintext}); err != nil {
		log.Printf("[TEXT] Could not apply text from %s to clipboard: %v", singleLineDisplayName(fromID), err)
	}
	notifyIncomingText(kind, fromName, text)
	log.Printf("[TEXT] Received %s from %s (%d bytes)", kind, singleLineDisplayName(fromID), len(plaintext))
}

// notifyPlainText is notifyUser behind a var so tests can observe the
// fallback path without spawning notify-send.
var notifyPlainText = notifyUser

// urlActionSlots bounds concurrent "notify-send with an action" waits. Each
// wait parks a process and a goroutine for up to urlActionWait; a member
// spamming URL texts must not be able to spawn them without bound. When both
// slots are taken the caller falls back to the plain toast.
var urlActionSlots = make(chan struct{}, 2)

// notifyIncomingText raises the notification for a received text share. A
// validated http(s) URL gets an "Open in browser" action; every other text,
// and any link whose action notification could not be displayed or could not
// get an action slot, keeps the plain toast. The action wait is bounded so the
// receive goroutine exits.
func notifyIncomingText(kind, fromName, text string) {
	fromName = singleLineDisplayName(fromName)
	title := "Text from " + fromName
	if kind == "url" {
		title = "Link from " + fromName
	}
	if canonical, ok := ValidateShareURL(text); ok {
		select {
		case urlActionSlots <- struct{}{}:
			defer func() { <-urlActionSlots }()
			if notifyURLAction(title, canonical) {
				return
			}
		default:
			// Both action notifications are already waiting on a user; a
			// third would only queue another process.
		}
	}
	notifyPlainText(title, text)
}

func handleIncomingTransfer(ctx context.Context, cfg Config, relayToken string, peerCryptoKey []byte, wsConn *wsClient, offer map[string]interface{}) {
	if envelopeInt64(offer, "v") >= 2 {
		handleIncomingTransferV2(ctx, cfg, relayToken, peerCryptoKey, wsConn, offer)
		return
	}
	handleIncomingTransferV1(cfg, relayToken, peerCryptoKey, wsConn, offer)
}

func handleIncomingTransferV1(cfg Config, relayToken string, peerCryptoKey []byte, wsConn *wsClient, offer map[string]interface{}) {
	common := parseOfferCommon(offer)
	transferID := common.TransferID
	senderID := common.SenderID
	senderName := common.SenderName
	rawFileName := common.RawFileName
	fileSize := common.FileSize
	senderPubB64 := common.SenderPubB64
	offerTag := common.OfferTag
	batchID := common.BatchID
	batchIndex := common.BatchIndex
	batchTotal := common.BatchTotal

	if transferID == "" || rawFileName == "" || senderPubB64 == "" {
		return
	}
	// The transfer id becomes part of the .part file name. Only the pinned
	// "tx-" + 32 lowercase hex shape DeriveTransferID produces may reach a
	// path; anything else (path separators, "..", a NUL) is rejected here.
	if !IsValidTransferID(transferID) {
		log.Printf("[RECV] Rejecting offer with invalid transfer id %q from %s",
			singleLineDisplayName(transferID), singleLineDisplayName(senderID))
		writeTransferReject(wsConn, senderID, transferID, "invalid_transfer_id")
		return
	}

	// Finding 3: Zero-knowledge peer authentication (Relay MITM Defense).
	// The v1 tag binds the raw (pre-sanitization) filename and exact size so a
	// malicious relay cannot alter offer metadata. Format is pinned in
	// lockstep with Android's TransferOfferCodec.
	expectedOfferMsg := fmt.Sprintf(offerTagFormatV1,
		transferID, cfg.DeviceID, senderPubB64,
		base64.StdEncoding.EncodeToString([]byte(rawFileName)), fileSize)
	if !VerifyAuthTag(peerCryptoKey, expectedOfferMsg, offerTag) {
		log.Printf("[RECV] Incoming offer auth tag invalid! Rejecting potential MITM or unauthorized peer: %s", transferID)
		writeTransferReject(wsConn, senderID, transferID, "auth_failed")
		return
	}

	if blockUntrustedSender(cfg, wsConn, offer, transferID, senderID) {
		return
	}

	// Finding 1: Path Traversal Protection
	cleanFileName, nameOk := sanitizeOfferName(cfg, rawFileName)
	if !nameOk {
		log.Printf("[RECV] Path traversal attack detected in filename %q! Rejecting %s", rawFileName, transferID)
		writeTransferReject(wsConn, senderID, transferID, "invalid_path")
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
	acceptTag := ComputeAuthTag(peerCryptoKey, fmt.Sprintf(acceptTagFormatV1, transferID, senderID, myPubB64))

	safetyNumber := ComputeSafetyNumber(senderPubBytes, keyPair.Public, transferID)
	log.Printf("[RECV] Verified transfer %s safety number: %s", transferID, safetyNumber)

	announceIncomingOffer(batchID, batchIndex, batchTotal, senderName, senderID, cleanFileName, fileSize)

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
	// The inbox holds received plaintext: it must exist and be owner-only.
	if err := ensureInboxDir(cfg.InboxDir); err != nil {
		log.Printf("[RECV] Inbox unavailable: %v", err)
		notifyUser("Transfer Failed", fmt.Sprintf("Cannot save '%s'", cleanFileName))
		return
	}
	partPath, _, pathOk := partPathsFor(cfg, cleanFileName, transferID)
	if !pathOk {
		writeTransferReject(wsConn, senderID, transferID, "invalid_path")
		return
	}
	// 0600: the staged plaintext is for gaur alone. O_CREATE never tightens
	// an existing file, so re-pin the mode for a part left by an older build.
	outFile, err := os.OpenFile(partPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		log.Printf("[RECV] Failed to create temporary part file: %v", err)
		notifyUser("Transfer Failed", fmt.Sprintf("Cannot save '%s'", cleanFileName))
		return
	}
	_ = os.Chmod(partPath, 0o600)

	var totalRead int64
	var transferSuccess bool
	defer func() {
		_ = outFile.Close()
		if !transferSuccess || (totalRead == 0 && fileSize > 0) {
			_ = os.Remove(partPath)
			notifyTransferFailed(cleanFileName, senderName)
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
		// Finding 2: Safe collision resolution and atomic commit. The
		// link-based commit cannot overwrite a concurrent completion of
		// the same name and leaves the final file 0600.
		finalPath, err := CommitUniqueFile(partPath, cfg.InboxDir, cleanFileName)
		if err != nil {
			log.Printf("[RECV] Could not finalize '%s': %v", cleanFileName, err)
			return
		}
		transferSuccess = true
		announceReceivedFile(finalPath, totalRead, transferMbps(totalRead, startTime))
	}
}

func handleIncomingTransferV2(ctx context.Context, cfg Config, relayToken string, peerCryptoKey []byte, wsConn *wsClient, offer map[string]interface{}) {
	common := parseOfferCommon(offer)
	transferID := common.TransferID
	senderID := common.SenderID
	senderName := common.SenderName
	rawFileName := common.RawFileName
	fileSize := common.FileSize
	senderPubB64 := common.SenderPubB64
	offerTag := common.OfferTag
	batchID := common.BatchID
	batchIndex := common.BatchIndex
	batchTotal := common.BatchTotal
	sessionID := envelopeString(offer, "session_id")
	fileHash := envelopeString(offer, "file_hash")
	chunkSize := int(envelopeInt64(offer, "chunk_size"))
	chunkCount := int(envelopeInt64(offer, "chunk_count"))
	openRequested, _ := offer["open"].(bool)
	mimeType := envelopeString(offer, "mime_type")

	if transferID == "" || sessionID == "" || rawFileName == "" || fileHash == "" || senderPubB64 == "" || senderID == "" {
		return
	}
	// The transfer id becomes part of the .part and .state file names. Only
	// the pinned "tx-" + 32 lowercase hex shape DeriveTransferID produces may
	// reach a path.
	if !IsValidTransferID(transferID) {
		log.Printf("[RECV] Rejecting offer with invalid transfer id %q from %s",
			singleLineDisplayName(transferID), singleLineDisplayName(senderID))
		writeTransferReject(wsConn, senderID, transferID, "invalid_transfer_id")
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
		writeTransferReject(wsConn, senderID, transferID, "auth_failed")
		return
	}

	if blockUntrustedSender(cfg, wsConn, offer, transferID, senderID) {
		return
	}

	cleanFileName, nameOk := sanitizeOfferName(cfg, rawFileName)
	if !nameOk {
		writeTransferReject(wsConn, senderID, transferID, "invalid_path")
		return
	}

	// The inbox holds received plaintext: it must exist and be owner-only.
	if err := ensureInboxDir(cfg.InboxDir); err != nil {
		log.Printf("[RECV] Inbox unavailable: %v", err)
		_ = wsConn.WriteJSON(map[string]interface{}{
			"type": "transfer_error", "target_id": senderID, "transfer_id": transferID, "reason": "save_failed",
		})
		notifyUser("Transfer Failed", fmt.Sprintf("Cannot save '%s'", cleanFileName))
		return
	}

	partPath, statePath, pathOk := partPathsFor(cfg, cleanFileName, transferID)
	if !pathOk {
		writeTransferReject(wsConn, senderID, transferID, "invalid_path")
		return
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
	acceptTag := ComputeAuthTag(peerCryptoKey, fmt.Sprintf(acceptTagFormatV2, transferID, senderID, myPubB64, HaveHash(have)))
	if err := wsConn.WriteJSON(map[string]interface{}{
		"type": "transfer_accept", "v": 2, "target_id": senderID,
		"transfer_id": transferID, "pub_key": myPubB64, "auth_tag": acceptTag, "have": have,
	}); err != nil {
		return
	}

	safetyNumber := ComputeSafetyNumber(senderPubBytes, keyPair.Public, transferID)
	log.Printf("[RECV] Transfer %s safety number: %s", transferID, safetyNumber)

	announceIncomingOffer(batchID, batchIndex, batchTotal, senderName, senderID, cleanFileName, fileSize)

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
		notifyTransferFailed(cleanFileName, senderName)
		return
	}
	state.Save(statePath)

	if fileSize == 0 {
		if _, err := os.Stat(partPath); os.IsNotExist(err) {
			// os.Create would land 0644; an empty file is still plaintext.
			if f, err := os.OpenFile(partPath, os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
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

	finalPath, err := CommitUniqueFile(partPath, cfg.InboxDir, cleanFileName)
	if err != nil {
		log.Printf("[RECV] Could not finalize %s: %v", transferID, err)
		_ = wsConn.WriteJSON(map[string]interface{}{
			"type": "transfer_error", "target_id": senderID, "transfer_id": transferID, "reason": "save_failed",
		})
		return
	}
	_ = os.Remove(statePath)

	receipt := ComputeAuthTag(peerCryptoKey, fmt.Sprintf(completeTagFormatV2, transferID, fileHash))
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

	announceReceivedFile(finalPath, fileSize, transferMbps(fileSize, startTime))
}
