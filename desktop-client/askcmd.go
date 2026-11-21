package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"
	"unicode/utf8"
)

// askTimeout bounds how long `gaur-share ask` waits for the phone's answer.
// Package var so tests can shrink it.
var askTimeout = 2 * time.Minute

// maxApprovalReasonRunes keeps the notification body readable and bounds
// what a caller can push into the phone's notification shade.
const maxApprovalReasonRunes = 200

// runAsk sends a sealed approval request and waits for the phone's decision.
// Exit status is 0 for approved, 1 for denied, 3 for timeout, and 4 for a
// transport or protocol failure; main maps the error through
// printControlError.
func runAsk(cfg Config, targetQuery, reason string, jsonOut bool) error {
	reason = strings.TrimSpace(reason)
	if reason == "" {
		return usageControlError("approval reason must not be empty")
	}
	if utf8.RuneCountInString(reason) > maxApprovalReasonRunes {
		return usageControlError("approval reason is longer than %d characters", maxApprovalReasonRunes)
	}
	for _, r := range reason {
		if r < 0x20 || r == 0x7f {
			return usageControlError("approval reason must be a single line without control characters")
		}
	}
	if askTimeout < 5*time.Second || askTimeout > 5*time.Minute {
		return usageControlError("approval timeout must be between 5s and 5m")
	}

	sess, err := openRequesterSession(cfg, targetQuery)
	if err != nil {
		return controlErrorf(4, "error", "", "%v", err)
	}
	defer sess.close()

	requestID, err := NewRequestID()
	if err != nil {
		return controlErrorf(4, "error", "", "could not create approval id: %v", err)
	}
	expiresTs := time.Now().Add(askTimeout).UnixMilli()
	grant := ComputeRequestGrant(
		sess.peerCryptoKey, requestID, sess.requesterID, sess.targetID, expiresTs,
	)

	inner := sess.baseInner(requestID)
	inner["reason"] = reason
	inner["expires_ts"] = expiresTs
	inner["grant"] = grant
	if err := sess.seal("ask", inner); err != nil {
		return controlErrorf(4, "error", "", "%v", err)
	}

	reply, err := waitForSealedReply(sess, "ask_response", requestID, askTimeout)
	if err != nil {
		if errors.Is(err, errReplyTimeout) {
			return controlErrorf(3, "timeout", "", "approval timed out on %s", sess.targetName)
		}
		return controlErrorf(4, "error", "", "%v", err)
	}
	if envelopeString(reply, "responder_id") != sess.targetID {
		return controlErrorf(4, "error", "", "approval response came from the wrong device")
	}
	if !VerifyRequestGrant(
		sess.peerCryptoKey, requestID, sess.requesterID, sess.targetID, expiresTs,
		envelopeString(reply, "grant"),
	) {
		return controlErrorf(4, "error", "", "approval response grant did not verify")
	}

	decision := envelopeString(reply, "decision")
	reasonCode := sanitizeControlReason(envelopeString(reply, "reason"))
	switch decision {
	case "approved":
		if jsonOut {
			_ = json.NewEncoder(os.Stdout).Encode(map[string]interface{}{
				"status":     "approved",
				"device":     sess.targetID,
				"deviceName": sess.targetName,
				"request_id": requestID,
			})
		} else {
			fmt.Printf("Approved by %s\n", sess.targetName)
		}
		return nil
	case "denied":
		return controlErrorf(1, "denied", reasonCode, "approval denied by %s%s", sess.targetName, reasonSuffix(reasonCode))
	default:
		return controlErrorf(4, "error", "", "invalid approval decision from %s", sess.targetName)
	}
}

// sanitizeControlReason limits peer-supplied reason codes to the pinned
// vocabulary. Anything else becomes an empty string rather than arbitrary
// text printed to the terminal.
func sanitizeControlReason(raw string) string {
	switch singleLineDisplayName(raw) {
	case "user_denied", "expired", "busy", "not_allowed", "notifications_blocked",
		"device_locked", "auto_open_disabled", "no_handler", "invalid":
		return singleLineDisplayName(raw)
	default:
		return ""
	}
}

func reasonSuffix(reason string) string {
	if reason == "" {
		return ""
	}
	return " (" + reason + ")"
}
