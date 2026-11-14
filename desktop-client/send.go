package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

// discoverTimeout bounds how long a sender waits for the relay's peer list
// before giving up on target resolution.
const discoverTimeout = 5 * time.Second

// acceptTimeout bounds how long a sender waits for the receiver's accept or
// reject after sending an offer.
const acceptTimeout = 60 * time.Second

type sendFileMeta struct {
	Path string
	Name string
	Size int64
	Open bool
}

// appendFolderMetas walks one folder and appends a meta for every regular
// file. Folder sends are a flat batch: every regular file is sent, and the
// receiver resolves duplicate base names. Relative paths are not preserved
// yet. It is the one folder walk shared by the v1 send path and the v3
// collectSendMetas; callers keep their own error reporting.
func appendFolderMetas(metas []sendFileMeta, root string, openRequested bool) ([]sendFileMeta, error) {
	err := filepath.Walk(root, func(p string, fi os.FileInfo, walkErr error) error {
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
	return metas, err
}

func collectSendMetas(filePaths []string, openRequested bool) ([]sendFileMeta, error) {
	var metas []sendFileMeta
	for _, path := range filePaths {
		info, err := os.Stat(path)
		if err != nil {
			return nil, fmt.Errorf("cannot open %s: %w", path, err)
		}
		if info.IsDir() {
			var walkErr error
			metas, walkErr = appendFolderMetas(metas, path, openRequested)
			if walkErr != nil {
				return nil, fmt.Errorf("cannot read folder %s: %w", path, walkErr)
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
	return metas, nil
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

	targetID, targetName, err := discoverTarget(conn, targetQuery)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
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
			metas, err = appendFolderMetas(metas, path, openRequested)
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

	targetID, targetName, err := discoverTarget(conn, targetQuery)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
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

// targetPeer is one entry of the relay's peer list for target resolution.
type targetPeer struct {
	ID   string
	Name string
}

// Sentinel errors for target resolution: callers must distinguish "no such
// device" (a send fails, a notification falls back to the mailbox) from
// "several devices match" (always fatal — a sender must never guess).
var (
	errTargetNotFound  = errors.New("target not found")
	errTargetAmbiguous = errors.New("ambiguous target")
)

// resolveTargetQuery resolves a send target against the relay's peer list.
// Matching is tiered so a short or overlapping query cannot misroute a send:
// exact device id first, then case-insensitive exact name, then
// case-insensitive name prefix. Several matches within a tier are an error
// naming the candidates; a substring that is not a prefix never matches.
func resolveTargetQuery(peers []targetPeer, query string) (targetPeer, error) {
	// Tier 1: exact device id.
	var idMatches []targetPeer
	for _, p := range peers {
		if p.ID == query {
			idMatches = append(idMatches, p)
		}
	}
	if len(idMatches) == 1 {
		return idMatches[0], nil
	}
	if len(idMatches) > 1 {
		return targetPeer{}, ambiguousTargetError(query, idMatches)
	}

	// Tier 2: case-insensitive exact name.
	var nameMatches []targetPeer
	for _, p := range peers {
		if strings.EqualFold(p.Name, query) {
			nameMatches = append(nameMatches, p)
		}
	}
	if len(nameMatches) == 1 {
		return nameMatches[0], nil
	}
	if len(nameMatches) > 1 {
		return targetPeer{}, ambiguousTargetError(query, nameMatches)
	}

	// Tier 3: case-insensitive name prefix.
	var prefixMatches []targetPeer
	lower := strings.ToLower(query)
	for _, p := range peers {
		if strings.HasPrefix(strings.ToLower(p.Name), lower) {
			prefixMatches = append(prefixMatches, p)
		}
	}
	if len(prefixMatches) == 1 {
		return prefixMatches[0], nil
	}
	if len(prefixMatches) > 1 {
		return targetPeer{}, ambiguousTargetError(query, prefixMatches)
	}

	return targetPeer{}, fmt.Errorf("%w: device %q is not currently online in the tunnel", errTargetNotFound, query)
}

// ambiguousTargetError lists the matching candidates so the user can pick
// one instead of the sender routing the file by guesswork.
func ambiguousTargetError(query string, matches []targetPeer) error {
	labels := make([]string, 0, len(matches))
	for _, p := range matches {
		if p.Name == "" || p.Name == p.ID {
			labels = append(labels, p.ID)
		} else {
			labels = append(labels, fmt.Sprintf("%s (%s)", p.Name, p.ID))
		}
	}
	return fmt.Errorf("%w %q: %s", errTargetAmbiguous, query, strings.Join(labels, ", "))
}

// discoverTarget waits for the relay peer list and resolves a device by
// exact id, exact name, or name prefix. It returns an error when no device
// matches, when several match, or when the relay never delivers a peer list.
func discoverTarget(conn *websocket.Conn, targetQuery string) (string, string, error) {
	_ = conn.SetReadDeadline(time.Now().Add(discoverTimeout))
	defer conn.SetReadDeadline(time.Time{})
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			return "", "", fmt.Errorf("could not read the peer list from the relay: %w", err)
		}
		var envelope map[string]interface{}
		if json.Unmarshal(msgBytes, &envelope) != nil {
			continue
		}
		if envelopeString(envelope, "type") != "peers" {
			continue
		}
		peersRaw, _ := envelope["peers"].([]interface{})
		peers := make([]targetPeer, 0, len(peersRaw))
		for _, p := range peersRaw {
			pMap, _ := p.(map[string]interface{})
			id, _ := pMap["id"].(string)
			name, _ := pMap["name"].(string)
			if id != "" {
				peers = append(peers, targetPeer{ID: id, Name: name})
			}
		}
		match, err := resolveTargetQuery(peers, targetQuery)
		if err != nil {
			return "", "", err
		}
		return match.ID, match.Name, nil
	}
}

type acceptResult struct {
	PubKey []byte
	Have   []int
}

// waitForAccept reads the signaling socket until the matching accept or reject
// arrives, ignoring unrelated peer-list traffic.
func waitForAccept(conn *websocket.Conn, transferID string, peerCryptoKey []byte, deviceID string) (*acceptResult, error) {
	_ = conn.SetReadDeadline(time.Now().Add(acceptTimeout))
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
		if envelopeString(envelope, "type") == "transfer_accept" && envelopeString(envelope, "transfer_id") == transferID {
			receiverPubB64 := envelopeString(envelope, "pub_key")
			acceptTag := envelopeString(envelope, "auth_tag")
			have := parseHave(envelope["have"])
			expected := fmt.Sprintf(acceptTagFormatV2, transferID, deviceID, receiverPubB64, HaveHash(have))
			if !VerifyAuthTag(peerCryptoKey, expected, acceptTag) {
				return nil, fmt.Errorf("accept auth tag verification failed")
			}
			pub, err := base64.StdEncoding.DecodeString(receiverPubB64)
			if err != nil {
				return nil, fmt.Errorf("invalid receiver public key: %w", err)
			}
			return &acceptResult{PubKey: pub, Have: have}, nil
		}
		if envelopeString(envelope, "type") == "transfer_reject" && envelopeString(envelope, "transfer_id") == transferID {
			reason := envelopeString(envelope, "reason")
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
		if envelopeString(envelope, "transfer_id") != transferID {
			continue
		}
		switch envelopeString(envelope, "type") {
		case "transfer_complete":
			fileHash := envelopeString(envelope, "file_hash")
			tag := envelopeString(envelope, "auth_tag")
			expected := fmt.Sprintf(completeTagFormatV2, transferID, fileHash)
			if fileHash != digest.FileHash || !VerifyAuthTag(peerCryptoKey, expected, tag) {
				return fmt.Errorf("completion receipt failed verification")
			}
			return nil
		case "transfer_error":
			reason := envelopeString(envelope, "reason")
			return fmt.Errorf("receiver reported: %s", reason)
		}
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
	return fmt.Sprintf(offerTagFormatV2,
		transferID, deviceID, senderPubB64, fileHash, chunkCount, chunkSize, openBit)
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
