package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"path/filepath"
	"time"
)

// runFetch asks a device for a set of files from a prior listing and saves
// every offer the target sends back. The grant proves each returning offer
// answers this exact request; the target's send subprocess echoes the request
// id and grant into every offer of the batch.
func runFetch(cfg Config, targetQuery string, paths []string) error {
	if len(paths) == 0 {
		return errors.New("no paths to fetch")
	}
	if len(paths) > fetchMaxPaths {
		return fmt.Errorf("too many paths: %d (max %d)", len(paths), fetchMaxPaths)
	}
	for _, path := range paths {
		if path == "" || !validBrowsePath(path) {
			return fmt.Errorf("invalid path: %q", singleLineDisplayName(path))
		}
	}

	sess, err := openRequesterSession(cfg, targetQuery)
	if err != nil {
		return err
	}
	defer sess.close()

	pending, err := newPendingRequest(sess.peerCryptoKey, sess.requesterID, sess.targetID, time.Now())
	if err != nil {
		return fmt.Errorf("could not create request: %w", err)
	}
	inner := sess.baseInner(pending.RequestID)
	inner["paths"] = paths
	inner["expires_ts"] = pending.ExpiresTs
	inner["grant"] = pending.Grant
	if err := sess.seal("fetch", inner); err != nil {
		return err
	}

	fmt.Printf("Fetching %d file(s) from %s...\n", len(paths), peerLabel(sess.targetName, sess.targetID))
	return waitForFetchedFiles(sess.cfg, sess.relayToken, sess.peerCryptoKey, sess.client, pending, len(paths))
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

// fetchReceiveMaxTotalBytes caps the total bytes a requester will accept in
// one batch. The target already caps what it sends, but a malicious or buggy
// target must not be able to fill the requester's disk with one request.
// Package var so tests can shrink it without writing multi-gigabyte fixtures.
var fetchReceiveMaxTotalBytes int64 = 4 << 30

// fetchSavedMsgCap bounds the saved-offer dedupe set. The relay can replay
// envelopes, so the requester remembers msg_ids; an unbounded map would grow
// with a long batch. 1024 is far above fetchMaxPaths.
const fetchSavedMsgCap = 1024

// savedMsgSet is a bounded FIFO set of sealed msg_ids already saved. When it
// is full the oldest id is evicted; a replay of that id would be caught by the
// offer's grant and content hash anyway, so the bound only trades a tiny
// replay window for a fixed memory ceiling.
type savedMsgSet struct {
	seen  map[string]bool
	order []string
	cap   int
}

func newSavedMsgSet(capacity int) *savedMsgSet {
	return &savedMsgSet{
		seen:  make(map[string]bool, capacity),
		order: make([]string, 0, capacity),
		cap:   capacity,
	}
}

func (s *savedMsgSet) Has(id string) bool { return s.seen[id] }

func (s *savedMsgSet) Add(id string) {
	if s.seen[id] {
		return
	}
	if len(s.seen) >= s.cap {
		if len(s.order) > 0 {
			delete(s.seen, s.order[0])
			s.order = s.order[1:]
		} else {
			s.seen = make(map[string]bool, s.cap)
		}
	}
	s.seen[id] = true
	s.order = append(s.order, id)
}

// waitForFetchedFiles is the requester's message loop. It ignores peer-list
// traffic and anything not addressed to this ephemeral identity. The target's
// send subprocess emits one offer per file with batch_total set, so the loop
// keeps receiving grant-verified offers until the whole batch is saved or the
// receive window closes. maxFiles is how many paths the user actually asked
// for: a malicious batch_total larger than that is clamped, so the requester
// never waits for files it did not request. It is shared by fetch and the
// screenshot quick action, which answers with a single offer.
func waitForFetchedFiles(
	cfg Config,
	relayToken string,
	peerCryptoKey []byte,
	client *wsClient,
	pending *pendingRequest,
	maxFiles int,
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

	if maxFiles < 1 {
		maxFiles = 1
	}
	received := 0
	expected := 0
	var receivedBytes int64
	saved := newSavedMsgSet(fetchSavedMsgCap)
	for {
		_, msgBytes, err := client.ReadMessage()
		if err != nil {
			var netErr net.Error
			if errors.As(err, &netErr) && netErr.Timeout() {
				if received > 0 {
					fmt.Printf("%d of %d files received\n", received, expected)
					return fmt.Errorf("%d of %d files received", received, expected)
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
			if msgID != "" && saved.Has(msgID) {
				continue
			}
			batchTotal := int(envelopeInt64(inner, "batch_total"))
			if batchTotal < 1 {
				batchTotal = 1
			}
			if expected == 0 {
				expected = batchTotal
				if expected > maxFiles {
					expected = maxFiles
				}
				window := completionWindow(time.Until(deadline), envelopeInt64(inner, "file_size"))
				deadline = time.Now().Add(window)
				_ = client.conn.SetReadDeadline(deadline)
			}
			fileSize := envelopeInt64(inner, "file_size")
			if fileSize < 0 {
				fileSize = 0
			}
			if receivedBytes+fileSize > fetchReceiveMaxTotalBytes {
				// Keep everything already saved; report the shortfall and
				// let the CLI exit non-zero so a script sees the truncation.
				fmt.Printf("%d of %d files received\n", received, expected)
				return fmt.Errorf("%d of %d files received", received, expected)
			}
			finalPath, err := receiveSealedOfferFile(
				cfg, relayToken, peerCryptoKey, pending.RequesterID, pending.TargetID, inner,
			)
			if err != nil {
				return fmt.Errorf("Receive failed: %w", err)
			}
			receivedBytes += fileSize
			if msgID != "" {
				saved.Add(msgID)
			}
			received++
			fmt.Printf("Received %s\n", filepath.Base(finalPath))
			if received >= expected {
				fmt.Printf("Received %d file(s) to %s\n", received, cfg.InboxDir)
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
