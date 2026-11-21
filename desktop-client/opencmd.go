package main

import (
	"encoding/json"
	"fmt"
	"mime"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// openTargetTTL bounds how long a URL or package handoff stays actionable on
// the target. The relay mailbox can hold a sealed message for an hour, so the
// payload carries its own short deadline.
const openTargetTTL = 60 * time.Second

// openResultTimeout bounds the wait for the target's open confirmation after
// the request or offer has been sent. Package var so tests can shrink it.
var openResultTimeout = 60 * time.Second

var androidPackagePattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`)

type openTarget struct {
	kind  string // "url", "file", or "package"
	value string
	path  string
}

// classifyOpenTarget picks the wire kind for one positional argument. URLs
// are validated before anything else, then an existing regular file wins over
// a package-shaped name. Directories, devices, and oversized files are
// rejected with a usage error rather than a late transfer failure.
func classifyOpenTarget(raw, appFlag string) (openTarget, error) {
	if appFlag != "" {
		if !validAndroidPackage(appFlag) {
			return openTarget{}, usageControlError("invalid Android package name")
		}
		return openTarget{kind: "package", value: appFlag}, nil
	}
	if canonical, ok := ValidateShareURL(raw); ok {
		return openTarget{kind: "url", value: canonical}, nil
	}
	info, statErr := os.Stat(raw)
	if statErr == nil {
		if info.IsDir() {
			return openTarget{}, usageControlError("open handles one file, not a directory; use send for a batch")
		}
		if !info.Mode().IsRegular() {
			return openTarget{}, usageControlError("open handles regular files only")
		}
		if info.Size() > 4*1024*1024*1024 {
			return openTarget{}, usageControlError("file is larger than the 4 GiB transfer limit")
		}
		return openTarget{kind: "file", value: raw, path: raw}, nil
	}
	if validAndroidPackage(raw) {
		return openTarget{kind: "package", value: raw}, nil
	}
	return openTarget{}, usageControlError(
		"open expects an http(s) URL, one regular file, or an Android package name",
	)
}

func validAndroidPackage(value string) bool {
	if len(value) == 0 || len(value) > 255 {
		return false
	}
	return androidPackagePattern.MatchString(value)
}

// mimeTypeForPath returns the extension-derived MIME type without parameters
// such as "; charset=utf-8", which Android's ACTION_VIEW matching does not
// expect. Unknown extensions become application/octet-stream.
func mimeTypeForPath(path string) string {
	value := mime.TypeByExtension(strings.ToLower(filepath.Ext(path)))
	if i := strings.IndexByte(value, ';'); i >= 0 {
		value = value[:i]
	}
	value = strings.TrimSpace(value)
	if value == "" {
		return "application/octet-stream"
	}
	return value
}

// runOpen hands one URL, file, or app package to a device and waits for its
// result. Exit status is 0 for opened or notification, 1 for denied, 3 for
// timeout, and 4 for transport/unsupported/failure.
func runOpen(cfg Config, targetQuery, value, appFlag string, jsonOut bool) error {
	target, err := classifyOpenTarget(value, appFlag)
	if err != nil {
		return err
	}

	sess, err := openRequesterSession(cfg, targetQuery)
	if err != nil {
		return controlErrorf(4, "error", "", "%v", err)
	}
	defer sess.close()

	switch target.kind {
	case "file":
		return runOpenFile(sess, target, jsonOut)
	default:
		return runOpenURLOrPackage(sess, target, jsonOut)
	}
}

func runOpenURLOrPackage(sess *requesterSession, target openTarget, jsonOut bool) error {
	requestID, err := NewRequestID()
	if err != nil {
		return controlErrorf(4, "error", "", "could not create handoff id: %v", err)
	}
	expiresTs := time.Now().Add(openTargetTTL).UnixMilli()
	grant := ComputeRequestGrant(
		sess.peerCryptoKey, requestID, sess.requesterID, sess.targetID, expiresTs,
	)
	inner := sess.baseInner(requestID)
	inner["open_kind"] = target.kind
	inner["value"] = target.value
	inner["expires_ts"] = expiresTs
	inner["grant"] = grant
	if err := sess.seal("open", inner); err != nil {
		return controlErrorf(4, "error", "", "%v", err)
	}

	reply, err := waitForSealedReply(sess, "open_result", requestID, openResultTimeout)
	if err != nil {
		if err == errReplyTimeout {
			return controlErrorf(3, "timeout", "", "handoff sent to %s, but no confirmation arrived", sess.targetName)
		}
		return controlErrorf(4, "error", "", "%v", err)
	}
	return finishOpenReply(sess, reply, requestID, expiresTs, target.kind, "", jsonOut)
}

func runOpenFile(sess *requesterSession, target openTarget, jsonOut bool) error {
	digest, err := HashFileChunks(target.path, ChunkSize)
	if err != nil {
		return controlErrorf(4, "error", "", "could not read %s: %v", target.path, err)
	}
	transferID := DeriveTransferID(sess.peerCryptoKey, digest.FileHash)
	sessionID := DeriveSessionID(sess.peerCryptoKey, transferID)
	fileKey := DeriveFileKey(sess.peerCryptoKey, transferID)
	meta := sendFileMeta{
		Path: target.path,
		Name: filepath.Base(target.path),
		Size: digest.Size,
		Open: true,
	}

	requestID, err := NewRequestID()
	if err != nil {
		return controlErrorf(4, "error", "", "could not create handoff id: %v", err)
	}

	fmt.Printf("Uploading %s for %s...\n", meta.Name, sess.targetName)
	if err := uploadV3Chunks(
		sess.cfg, sess.relayToken, sess.peerCryptoKey,
		transferID, sessionID, digest, fileKey, meta,
	); err != nil {
		return controlErrorf(4, "error", "", "%v", err)
	}
	fmt.Println()

	inner := map[string]interface{}{
		"sender_id":       sess.originID,
		"sender_name":     sess.cfg.Name,
		"transfer_id":     transferID,
		"session_id":      sessionID,
		"file_name":       meta.Name,
		"file_size":       digest.Size,
		"chunk_size":      ChunkSize,
		"chunk_count":     digest.ChunkCount,
		"file_hash":       digest.FileHash,
		"chunk_hashes":    digest.ChunkHashes,
		"batch_id":        fmt.Sprintf("handoff-%d", time.Now().UnixNano()),
		"batch_index":     0,
		"batch_total":     1,
		"open":            true,
		"open_request_id": requestID,
		"reply_to":        sess.requesterID,
		"mime_type":       mimeTypeForPath(target.path),
		"offline":         false,
	}
	envelope, err := sealEnvelope(sess.peerCryptoKey, sess.targetID, "offer", inner)
	if err != nil {
		return controlErrorf(4, "error", "", "could not seal handoff: %v", err)
	}
	if err := sess.client.WriteJSON(envelope); err != nil {
		return controlErrorf(4, "error", "", "could not send handoff: %v", err)
	}

	reply, err := waitForSealedReply(
		sess, "open_result", requestID,
		completionTimeout(digest.Size)+openResultTimeout,
	)
	if err != nil {
		if err == errReplyTimeout {
			return controlErrorf(3, "timeout", "", "file sent to %s, but no open confirmation arrived", sess.targetName)
		}
		return controlErrorf(4, "error", "", "%v", err)
	}
	return finishOpenReply(sess, reply, requestID, 0, "file", transferID, jsonOut)
}

func finishOpenReply(
	sess *requesterSession,
	reply map[string]interface{},
	requestID string,
	expiresTs int64,
	kind string,
	wantTransferID string,
	jsonOut bool,
) error {
	if envelopeString(reply, "responder_id") != sess.targetID {
		return controlErrorf(4, "error", "", "handoff result came from the wrong device")
	}
	if wantTransferID != "" {
		if envelopeString(reply, "transfer_id") != wantTransferID {
			return controlErrorf(4, "error", "", "handoff result named a different transfer")
		}
	}
	if kind != "file" && !VerifyRequestGrant(
		sess.peerCryptoKey, requestID, sess.requesterID, sess.targetID, expiresTs,
		envelopeString(reply, "grant"),
	) {
		// File offers do not carry the v3.2 request grant. Their sealed offer
		// is already bound to the target and content; the request id and the
		// responder id are the correlation. Only URL/package handoffs echo a
		// grant.
		return controlErrorf(4, "error", "", "handoff result grant did not verify")
	}

	status := envelopeString(reply, "status")
	reason := sanitizeControlReason(envelopeString(reply, "reason"))
	device := sess.targetName
	switch status {
	case "opened", "notification":
		if jsonOut {
			_ = json.NewEncoder(os.Stdout).Encode(map[string]interface{}{
				"status":     status,
				"kind":       kind,
				"device":     sess.targetID,
				"deviceName": device,
				"request_id": requestID,
				"reason":     reason,
			})
		} else if status == "opened" {
			fmt.Printf("Opened on %s\n", device)
		} else {
			fmt.Printf("Sent to %s; open it from the notification\n", device)
		}
		return nil
	case "denied":
		return controlErrorf(1, "denied", reason, "handoff denied by %s%s", device, reasonSuffix(reason))
	case "timeout":
		return controlErrorf(3, "timeout", reason, "handoff timed out on %s", device)
	case "unsupported", "busy", "failed":
		return controlErrorf(4, status, reason, "handoff %s on %s%s", status, device, reasonSuffix(reason))
	default:
		return controlErrorf(4, "error", "", "invalid handoff result from %s", device)
	}
}
