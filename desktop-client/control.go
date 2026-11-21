package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"time"
)

// errReplyTimeout distinguishes a quiet target from a broken relay link. The
// CLI maps it to the timeout exit code instead of a generic failure.
var errReplyTimeout = errors.New("reply timed out")

// controlError carries the process exit code and the machine-readable status
// for a command that is expected to fail in a scriptable way (denial,
// timeout, unsupported).
type controlError struct {
	code    int
	status  string
	reason  string
	message string
}

func (e *controlError) Error() string { return e.message }

func controlErrorf(code int, status, reason, format string, args ...interface{}) error {
	return &controlError{
		code:    code,
		status:  status,
		reason:  reason,
		message: fmt.Sprintf(format, args...),
	}
}

func usageControlError(format string, args ...interface{}) error {
	return controlErrorf(2, "usage", "", format, args...)
}

func controlExitCode(err error) int {
	var ce *controlError
	if errors.As(err, &ce) {
		return ce.code
	}
	return 4
}

// printControlError writes the human or JSON failure form and exits. The
// message is sanitized because a peer-supplied reason can end up here.
func printControlError(err error, jsonOut bool) {
	var ce *controlError
	if !errors.As(err, &ce) {
		ce = &controlError{code: 4, status: "error", message: err.Error()}
	}
	message := singleLineDisplayName(ce.message)
	if message == "" {
		message = "command failed"
	}
	if jsonOut {
		payload := map[string]interface{}{
			"status": ce.status,
			"reason": ce.reason,
			"error":  message,
		}
		_ = json.NewEncoder(os.Stdout).Encode(payload)
	} else {
		fmt.Fprintln(os.Stderr, "error: "+message)
	}
	os.Exit(ce.code)
}

// waitForSealedReply reads the one-shot requester's socket until a sealed
// reply of [kind] answers [requestID]. The target-id check happens before
// decryption because the seal key is shared by the whole cluster.
func waitForSealedReply(
	sess *requesterSession,
	kind, requestID string,
	timeout time.Duration,
) (map[string]interface{}, error) {
	_ = sess.client.conn.SetReadDeadline(time.Now().Add(timeout))
	defer sess.client.conn.SetReadDeadline(time.Time{})

	for {
		_, msgBytes, err := sess.client.ReadMessage()
		if err != nil {
			var netErr net.Error
			if errors.As(err, &netErr) && netErr.Timeout() {
				return nil, errReplyTimeout
			}
			return nil, fmt.Errorf("relay connection lost: %w", err)
		}
		var envelope map[string]interface{}
		if json.Unmarshal(msgBytes, &envelope) != nil {
			continue
		}
		if envelopeString(envelope, "type") != "sealed" {
			continue
		}
		if envelopeString(envelope, "target_id") != sess.requesterID {
			continue
		}
		gotKind, inner, err := openSealed(sess.peerCryptoKey, envelope)
		if err != nil || gotKind != kind {
			continue
		}
		if envelopeString(inner, "request_id") != requestID {
			continue
		}
		return inner, nil
	}
}
