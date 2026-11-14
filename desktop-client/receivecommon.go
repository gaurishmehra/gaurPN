package main

import (
	"fmt"
	"log"
	"path/filepath"
	"time"
)

// Auth-tag payload formats. The v1 and v2 formats are deliberately separate
// constants: each is pinned in lockstep with Android's TransferOfferCodec,
// and they must never be merged into one parameterized builder.
const (
	offerTagFormatV1    = "offer:v1:%s:%s:%s:%s:%d"
	acceptTagFormatV1   = "accept:%s:%s:%s"
	offerTagFormatV2    = "offer:v2:%s:%s:%s:%s:%d:%d:%s"
	acceptTagFormatV2   = "accept:v2:%s:%s:%s:%s"
	completeTagFormatV2 = "complete:v2:%s:%s"
)

// offerCommon carries the offer fields shared by the v1 and v2 receive
// paths; version-specific fields stay in their handlers.
type offerCommon struct {
	TransferID   string
	SenderID     string
	SenderName   string
	RawFileName  string
	FileSize     int64
	SenderPubB64 string
	OfferTag     string
	BatchID      string
	BatchIndex   int
	BatchTotal   int
}

// parseOfferCommon reads the offer fields both receive paths need. The v1
// and v2 auth-tag formats stay separate: see the tag-format constants.
func parseOfferCommon(offer map[string]interface{}) offerCommon {
	batchTotal := int(envelopeInt64(offer, "batch_total"))
	if batchTotal < 1 {
		batchTotal = 1
	}
	return offerCommon{
		TransferID:   envelopeString(offer, "transfer_id"),
		SenderID:     envelopeString(offer, "from_id"),
		SenderName:   envelopeString(offer, "from_name"),
		RawFileName:  envelopeString(offer, "file_name"),
		FileSize:     envelopeInt64(offer, "file_size"),
		SenderPubB64: envelopeString(offer, "pub_key"),
		OfferTag:     envelopeString(offer, "auth_tag"),
		BatchID:      envelopeString(offer, "batch_id"),
		BatchIndex:   int(envelopeInt64(offer, "batch_index")),
		BatchTotal:   batchTotal,
	}
}

// writeTransferReject reports a rejected offer to the sender. WriteJSON
// marshals map keys in sorted order, so the wire bytes are identical to the
// multi-line literals this helper replaces.
func writeTransferReject(wsConn *wsClient, targetID, transferID, reason string) {
	_ = wsConn.WriteJSON(map[string]interface{}{
		"type": "transfer_reject", "target_id": targetID, "transfer_id": transferID, "reason": reason,
	})
}

// blockUntrustedSender enforces the only-trusted gate on an incoming offer.
// It reports true when the offer was rejected, so the caller returns.
func blockUntrustedSender(cfg Config, wsConn *wsClient, offer map[string]interface{}, transferID, senderID string) bool {
	if !onlyTrustedEnabled(cfg) {
		return false
	}
	senderIdentity := offerSenderID(offer)
	if senderIdentity == cfg.DeviceID || isTrustedDevice(senderIdentity) {
		return false
	}
	writeTransferReject(wsConn, senderID, transferID, "untrusted")
	notifyUser("Transfer blocked", "Not from a trusted device")
	log.Printf("[RECV] Blocked %s from untrusted %s", transferID, senderIdentity)
	return true
}

// sanitizeOfferName sanitizes the offered filename and verifies the
// destination stays inside the inbox. The second return is false when the
// name would escape the inbox; the caller rejects the offer.
func sanitizeOfferName(cfg Config, rawFileName string) (string, bool) {
	cleanFileName := SanitizeFileName(rawFileName)
	destPath := filepath.Join(cfg.InboxDir, cleanFileName)
	if !IsPathInsideDirectory(destPath, cfg.InboxDir) {
		return "", false
	}
	return cleanFileName, true
}

// partFilePath is the staging name for an incoming transfer: hidden, and
// unique per transfer so concurrent offers of the same file name do not
// fight over one .part.
func partFilePath(cfg Config, cleanFileName, transferID string) string {
	return filepath.Join(cfg.InboxDir, fmt.Sprintf(".%s.%s.part", cleanFileName, transferID))
}

// announceIncomingOffer logs the incoming transfer and raises the user
// notification. Shared by the v1 and v2 receive paths.
func announceIncomingOffer(batchID string, batchIndex, batchTotal int, senderName, senderID, cleanFileName string, fileSize int64) {
	if batchTotal > 1 {
		log.Printf("[RECV] Incoming batch %s file %d/%d from %s (%s): %s (%d bytes)",
			batchID, batchIndex+1, batchTotal, senderName, senderID, cleanFileName, fileSize)
	} else {
		log.Printf("[RECV] Incoming file from %s (%s): %s (%d bytes)", senderName, senderID, cleanFileName, fileSize)
	}
	notifyUser("Incoming File Transfer", fmt.Sprintf("%s is sending %s (%.1f MB)", senderName, cleanFileName, float64(fileSize)/(1024*1024)))
}

// notifyTransferFailed raises the failure toast shared by the v1 and v2
// receive paths.
func notifyTransferFailed(cleanFileName, senderName string) {
	notifyUser("Transfer Failed", fmt.Sprintf("Failed receiving '%s' from %s", cleanFileName, senderName))
}

// transferMbps converts a byte total and start time into the throughput
// figure for the completion log. Shared by the v1 and v2 receive paths.
func transferMbps(total int64, startTime time.Time) float64 {
	duration := time.Since(startTime).Seconds()
	if duration <= 0 {
		duration = 0.001
	}
	return (float64(total) / (1024 * 1024)) / duration
}

// announceReceivedFile logs and notifies a completed incoming transfer.
// Shared by the v1 and v2 receive paths.
func announceReceivedFile(finalPath string, size int64, mbps float64) {
	log.Printf("[RECV] SUCCESS: Saved '%s' (%.2f MB at %.1f MB/s)", filepath.Base(finalPath), float64(size)/(1024*1024), mbps)
	notifyUser("Transfer Complete", fmt.Sprintf("Saved '%s' (%.1f MB)", filepath.Base(finalPath), float64(size)/(1024*1024)))
}
