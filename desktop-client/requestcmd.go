package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"time"
)

// requestTTL is how long a pull request stays valid. The target enforces it
// before opening the picker and again when the picker closes; the requester's
// read deadline uses it to bound the wait for the first offer. Once offers
// start arriving, the receive window (max of the remaining wait and the
// size-based completion timeout) bounds the rest of the batch.
const requestTTL = 5 * time.Minute

// requestTimeout bounds the requester's wait for the target's decision.
// Package var so tests can shrink it.
var requestTimeout = 5 * time.Minute

// pendingRequest is the requester's local record of one pull request. Every
// field is bound into the grant, so a returning offer can be checked against
// exactly what was asked.
type pendingRequest struct {
	RequestID   string
	RequesterID string
	TargetID    string
	Kind        string
	ExpiresTs   int64
	Grant       string
}

// requestArgsFromFlags accepts the documented "request <target> --screenshot"
// order as well as "request --screenshot <target>". Go's flag package stops
// parsing at the first positional argument, so a trailing screenshot flag is
// moved in front before the FlagSet sees it.
func requestArgsFromFlags(args []string) []string {
	if n := len(args); n >= 2 {
		last := args[n-1]
		if last == "--screenshot" || last == "-screenshot" {
			out := make([]string, 0, n)
			out = append(out, last)
			return append(out, args[:n-1]...)
		}
	}
	return args
}

// newRequesterID builds the short-lived identity a request uses. It follows
// the list command's pattern and never reuses the daemon's device id, which
// the relay would evict. The suffix is 32 random bits: PID reuse alone must
// not let two live requesters collide and evict each other's connection.
func newRequesterID() (string, error) {
	suffix, err := randomHex(4)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("reqdev-%d-%s", os.Getpid(), suffix), nil
}

// newPendingRequest mints a request id and its grant. The grant is the
// unforgeable proof that a returning offer answers this exact request.
func newPendingRequest(peerCryptoKey []byte, requesterID, targetID, kind string, now time.Time) (*pendingRequest, error) {
	requestID, err := NewRequestID()
	if err != nil {
		return nil, err
	}
	expiresTs := now.Add(requestTTL).UnixMilli()
	return &pendingRequest{
		RequestID:   requestID,
		RequesterID: requesterID,
		TargetID:    targetID,
		Kind:        kind,
		ExpiresTs:   expiresTs,
		Grant:       ComputeRequestGrant(peerCryptoKey, requestID, requesterID, targetID, expiresTs),
	}, nil
}

// matchesOffer reports whether a sealed offer answers this request: the
// request id matches, the inner sender is the device that was asked, and the
// grant verifies against the exact pending record. A relay cannot forge any of
// these without K_peer.
//
// The request's expiry is deliberately not re-checked here. The grant already
// commits to expires_ts, and the requester's own read deadline bounds the
// whole exchange; re-checking the wall clock at offer time only rejected
// legitimate offers that arrived as the deadline was crossed.
func (p *pendingRequest) matchesOffer(peerCryptoKey []byte, inner map[string]interface{}) bool {
	if envelopeString(inner, "request_id") != p.RequestID {
		return false
	}
	if envelopeString(inner, "sender_id") != p.TargetID {
		return false
	}
	grant := envelopeString(inner, "grant")
	return VerifyRequestGrant(peerCryptoKey, p.RequestID, p.RequesterID, p.TargetID, p.ExpiresTs, grant)
}

// runRequest asks a device to pick files and send them back. It prints the
// waiting line, then blocks until the offer arrives, the target declines, the
// request expires, or the relay link drops. The returned error text is the
// user-facing outcome for every failure.
func runRequest(cfg Config, targetQuery string, screenshot bool) error {
	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		return err
	}
	relayToken, peerCryptoKey := DeriveSubKeys(token)

	// The connection uses an ephemeral reqdev- id so the relay does not evict
	// the daemon's connection, but the target's trust list is keyed by stable
	// device ids. origin_id carries the real id for the trust check only;
	// the grant, routing, and the decline address keep using requester_id.
	originID := cfg.DeviceID
	requesterID, err := newRequesterID()
	if err != nil {
		return fmt.Errorf("could not create request identity: %w", err)
	}
	cfg.DeviceID = requesterID

	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		return fmt.Errorf("cannot connect to relay at %s: %w", cfg.RelayAddr, err)
	}
	defer conn.Close()
	client := &wsClient{conn: conn}

	// Exclude this ephemeral connection from name resolution: it carries the
	// same display name as the daemon, and a name query must not become
	// ambiguous because the requester sees itself in the peer list. The
	// daemon's stable id is excluded too: a request addressed to this machine
	// can never be served (the target drops self-addressed requests), so it
	// must not resolve, and asking for it must fail immediately.
	excludeIDs := []string{requesterID}
	if originID != "" {
		excludeIDs = append(excludeIDs, originID)
	}
	targetID, targetName, err := discoverTargetExcluding(conn, targetQuery, excludeIDs...)
	if err != nil {
		return err
	}
	if err := selfTargetError(targetID, originID); err != nil {
		return err
	}
	targetName = singleLineDisplayName(targetName)
	if targetName == "" {
		targetName = singleLineDisplayName(targetID)
	}

	kind := "files"
	if screenshot {
		kind = "screenshot"
	}
	pending, err := newPendingRequest(peerCryptoKey, requesterID, targetID, kind, time.Now())
	if err != nil {
		return fmt.Errorf("could not create request: %w", err)
	}

	inner := map[string]interface{}{
		"request_id":     pending.RequestID,
		"requester_id":   requesterID,
		"requester_name": cfg.Name,
		"target_id":      targetID,
		"kind":           kind,
		"expires_ts":     pending.ExpiresTs,
		"grant":          pending.Grant,
		"ts":             time.Now().UnixMilli(),
	}
	// Optional: absent means "trust the requester_id", which is what older
	// clients send and what Android sends (there origin_id == requester_id).
	if originID != "" {
		inner["origin_id"] = originID
	}
	envelope, err := sealEnvelope(peerCryptoKey, targetID, "request", inner)
	if err != nil {
		return fmt.Errorf("could not seal request: %w", err)
	}
	if err := client.WriteJSON(envelope); err != nil {
		return fmt.Errorf("could not send request: %w", err)
	}

	fmt.Printf("Waiting for %s to %s...\n", peerLabel(targetName, targetID), requestAction(kind))
	return waitForRequestedFile(cfg, relayToken, peerCryptoKey, client, pending)
}

// requestAction is the user-facing verb for a request kind.
func requestAction(kind string) string {
	if kind == "screenshot" {
		return "take a screenshot"
	}
	return "choose files"
}

// selfTargetError is the fail-fast error for a request that resolved to this
// machine. The daemon drops self-addressed requests, so asking would burn the
// whole request TTL for nothing.
func selfTargetError(targetID, originID string) error {
	if originID != "" && targetID == originID {
		return fmt.Errorf("cannot request files from this device (%s)", singleLineDisplayName(originID))
	}
	return nil
}

// requestDeclineReasons is the pinned decline-reason enum. Anything else a
// peer sends is untrusted text and is reported as the generic "declined".
var requestDeclineReasons = map[string]bool{
	"declined":           true,
	"busy":               true,
	"expired":            true,
	"unsupported":        true,
	"picker_unavailable": true,
	"not_allowed":        true,
}

// sanitizeDeclineReason validates a peer-supplied decline reason against the
// pinned enum and strips anything that could forge output. An unknown or
// missing reason becomes "declined".
func sanitizeDeclineReason(raw string) string {
	reason := singleLineDisplayName(raw)
	if !requestDeclineReasons[reason] {
		return "declined"
	}
	return reason
}

// completionWindow is the receive window after a request's first offer: the
// larger of the remaining request wait and the size-based completion timeout.
// The request TTL bounds only the time to the first offer; once files start
// arriving, a multi-file batch must be allowed to finish uploading.
func completionWindow(remaining time.Duration, fileSize int64) time.Duration {
	window := completionTimeout(fileSize)
	if remaining > window {
		window = remaining
	}
	return window
}

// waitForRequestedFile is the requester's message loop. It ignores peer-list
// traffic and anything not addressed to this ephemeral identity. A target's
// picker allows several files and the send subprocess emits one offer per
// file with batch_total set, so the loop keeps receiving grant-verified
// offers until the whole batch is saved or the receive window closes.
func waitForRequestedFile(
	cfg Config,
	relayToken string,
	peerCryptoKey []byte,
	client *wsClient,
	pending *pendingRequest,
) error {
	// The read deadline is the shorter of the wait budget and the request
	// expiry, so a target that never answers cannot keep the CLI alive past
	// the grant's lifetime. The first offer extends it to the receive window.
	deadline := time.Now().Add(requestTimeout)
	if expires := time.UnixMilli(pending.ExpiresTs); expires.Before(deadline) {
		deadline = expires
	}
	_ = client.conn.SetReadDeadline(deadline)
	defer client.conn.SetReadDeadline(time.Time{})

	received := 0
	expected := 0
	saved := make(map[string]bool)
	for {
		_, msgBytes, err := client.ReadMessage()
		if err != nil {
			var netErr net.Error
			if errors.As(err, &netErr) && netErr.Timeout() {
				if received > 0 {
					fmt.Printf("Receive window closed: %d of %d files received\n", received, expected)
					return nil
				}
				return errors.New("Request timed out")
			}
			return fmt.Errorf("Relay connection lost: %w", err)
		}
		var envelope map[string]interface{}
		if json.Unmarshal(msgBytes, &envelope) != nil {
			continue
		}
		if envelopeString(envelope, "type") != "sealed" {
			continue
		}
		if envelopeString(envelope, "target_id") != pending.RequesterID {
			continue
		}
		kind, inner, err := openSealed(peerCryptoKey, envelope)
		if err != nil {
			continue
		}
		switch kind {
		case "offer":
			if !pending.matchesOffer(peerCryptoKey, inner) {
				continue
			}
			// Dedupe by the sealed envelope's msg_id, which is bound into
			// the AAD: a relay replay of the same offer must not produce a
			// second copy, while two distinct offers for identical file
			// content (same transfer id) are both legitimate files.
			msgID := envelopeString(envelope, "msg_id")
			if msgID != "" && saved[msgID] {
				continue
			}
			batchTotal := int(envelopeInt64(inner, "batch_total"))
			if batchTotal < 1 {
				batchTotal = 1
			}
			if expected == 0 {
				expected = batchTotal
				window := completionWindow(time.Until(deadline), envelopeInt64(inner, "file_size"))
				deadline = time.Now().Add(window)
				_ = client.conn.SetReadDeadline(deadline)
			}
			finalPath, err := receiveSealedOfferFile(
				cfg, relayToken, peerCryptoKey, pending.RequesterID, pending.TargetID, inner,
			)
			if err != nil {
				return fmt.Errorf("Receive failed: %w", err)
			}
			if msgID != "" {
				saved[msgID] = true
			}
			received++
			fmt.Printf("Received %s\n", filepath.Base(finalPath))
			if received >= expected {
				return nil
			}
		case "request_declined":
			if envelopeString(inner, "request_id") != pending.RequestID {
				continue
			}
			return fmt.Errorf("Declined (%s)", sanitizeDeclineReason(envelopeString(inner, "reason")))
		}
	}
}
