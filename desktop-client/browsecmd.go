package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"text/tabwriter"
	"time"
	"unicode"
)

// requestTTL is how long a pull request stays valid. The target enforces it
// before serving a fetch; the requester's read deadline uses it to bound the
// wait for the first offer. Once offers start arriving, the receive window
// (max of the remaining wait and the size-based completion timeout) bounds the
// rest of the batch.
const requestTTL = 5 * time.Minute

// requestTimeout bounds the requester's wait for the target's decision.
// Package var so tests can shrink it.
var requestTimeout = 5 * time.Minute

// browseTimeout bounds one browse round trip. A listing is a single small
// reply, so it does not need the request TTL.
var browseTimeout = 30 * time.Second

// pendingRequest is the requester's local record of one pull request. Every
// field is bound into the grant, so a returning offer can be checked against
// exactly what was asked.
type pendingRequest struct {
	RequestID   string
	RequesterID string
	TargetID    string
	ExpiresTs   int64
	Grant       string
}

// newRequesterID builds the short-lived identity a pull command uses. It
// follows the list command's pattern and never reuses the daemon's device id,
// which the relay would evict. The suffix is 32 random bits: PID reuse alone
// must not let two live requesters collide and evict each other's connection.
func newRequesterID() (string, error) {
	suffix, err := randomHex(4)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("reqdev-%d-%s", os.Getpid(), suffix), nil
}

// newPendingRequest mints a request id and its grant. The grant is the
// unforgeable proof that a returning offer answers this exact request.
func newPendingRequest(peerCryptoKey []byte, requesterID, targetID string, now time.Time) (*pendingRequest, error) {
	requestID, err := NewRequestID()
	if err != nil {
		return nil, err
	}
	expiresTs := now.Add(requestTTL).UnixMilli()
	return &pendingRequest{
		RequestID:   requestID,
		RequesterID: requesterID,
		TargetID:    targetID,
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

// requesterSession is one ephemeral requester connection: the relay link, the
// short-lived identity, and the resolved target. Browse, fetch, and screenshot
// all use it so the origin_id/trust plumbing exists in exactly one place.
type requesterSession struct {
	cfg           Config
	client        *wsClient
	relayToken    string
	peerCryptoKey []byte
	requesterID   string
	originID      string
	targetID      string
	targetName    string
}

// openRequesterSession connects under an ephemeral reqdev- id and resolves the
// target. The connection uses the ephemeral id so the relay does not evict the
// daemon's connection, but the target's trust list is keyed by stable device
// ids. origin_id carries the real id for the trust check only; the grant,
// routing, and the decline address keep using requester_id.
func openRequesterSession(cfg Config, targetQuery string) (*requesterSession, error) {
	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		return nil, err
	}
	relayToken, peerCryptoKey := DeriveSubKeys(token)

	originID := cfg.DeviceID
	requesterID, err := newRequesterID()
	if err != nil {
		return nil, fmt.Errorf("could not create request identity: %w", err)
	}
	cfg.DeviceID = requesterID

	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		return nil, fmt.Errorf("cannot connect to relay at %s: %w", cfg.RelayAddr, err)
	}
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
		_ = client.Close()
		return nil, err
	}
	if err := selfTargetError(targetID, originID); err != nil {
		_ = client.Close()
		return nil, err
	}
	targetName = singleLineDisplayName(targetName)
	if targetName == "" {
		targetName = singleLineDisplayName(targetID)
	}
	return &requesterSession{
		cfg:           cfg,
		client:        client,
		relayToken:    relayToken,
		peerCryptoKey: peerCryptoKey,
		requesterID:   requesterID,
		originID:      originID,
		targetID:      targetID,
		targetName:    targetName,
	}, nil
}

func (r *requesterSession) close() { _ = r.client.Close() }

// baseInner is the common requester envelope payload. origin_id is optional:
// absent means "trust the requester_id", which is what older clients send.
func (r *requesterSession) baseInner(requestID string) map[string]interface{} {
	inner := map[string]interface{}{
		"request_id":     requestID,
		"requester_id":   r.requesterID,
		"requester_name": r.cfg.Name,
		"target_id":      r.targetID,
		"ts":             time.Now().UnixMilli(),
	}
	if r.originID != "" {
		inner["origin_id"] = r.originID
	}
	return inner
}

func (r *requesterSession) seal(kind string, inner map[string]interface{}) error {
	envelope, err := sealEnvelope(r.peerCryptoKey, r.targetID, kind, inner)
	if err != nil {
		return fmt.Errorf("could not seal %s: %w", kind, err)
	}
	if err := r.client.WriteJSON(envelope); err != nil {
		return fmt.Errorf("could not send %s: %w", kind, err)
	}
	return nil
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

// requestDeclineReasons is the pinned decline-reason enum. picker_unavailable
// is kept for older Android targets that still send it; anything else a peer
// sends is untrusted text and is reported as the generic "declined".
var requestDeclineReasons = map[string]bool{
	"declined":           true,
	"busy":               true,
	"expired":            true,
	"unsupported":        true,
	"picker_unavailable": true,
	"not_allowed":        true,
	"not_found":          true,
	"too_large":          true,
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

// browseListing is one decoded browse reply. Entries is always non-nil on a
// successful listing, including an empty directory.
type browseListing struct {
	Path      string
	Entries   []browseEntry
	Truncated bool
}

// browseArgsFromFlags accepts the documented "browse <target> [path] --json"
// order as well as "browse --json <target> [path]". Go's flag package stops
// parsing at the first positional argument, so a trailing json flag is moved
// in front before the FlagSet sees it.
func browseArgsFromFlags(args []string) []string {
	if n := len(args); n >= 2 {
		last := args[n-1]
		if last == "--json" || last == "-json" {
			out := make([]string, 0, n)
			out = append(out, last)
			return append(out, args[:n-1]...)
		}
	}
	return args
}

// runBrowse asks a device for one directory listing and prints it. In JSON
// mode every outcome is a JSON object on stdout so the web UI can relay it; in
// human mode the caller prints the returned error as "error: <reason>".
func runBrowse(cfg Config, targetQuery, path string, jsonOut bool) error {
	if !validBrowsePath(path) {
		return finishBrowseError(path, "denied", jsonOut)
	}
	sess, err := openRequesterSession(cfg, targetQuery)
	if err != nil {
		return finishBrowseError(path, err.Error(), jsonOut)
	}
	defer sess.close()

	requestID, err := NewRequestID()
	if err != nil {
		return finishBrowseError(path, err.Error(), jsonOut)
	}
	inner := sess.baseInner(requestID)
	inner["path"] = path
	if err := sess.seal("browse", inner); err != nil {
		return finishBrowseError(path, err.Error(), jsonOut)
	}
	listing, err := waitForBrowseListing(sess, requestID, path)
	if err != nil {
		return finishBrowseError(path, err.Error(), jsonOut)
	}
	if jsonOut {
		writeBrowseJSON(listing)
	} else {
		writeBrowseTable(listing)
	}
	return nil
}

// finishBrowseError normalizes a browse failure to one line, emits the JSON
// error object when the caller asked for JSON, and returns the message for the
// human-mode "error: ..." line.
func finishBrowseError(path, message string, jsonOut bool) error {
	message = singleLineDisplayName(message)
	if message == "" {
		message = "browse failed"
	}
	if jsonOut {
		writeBrowseJSONError(path, message)
	}
	return errors.New(message)
}

// waitForBrowseListing is the browse requester's message loop. It ignores
// peer-list traffic and anything not addressed to this ephemeral identity, and
// returns the first listing that answers this exact request id.
func waitForBrowseListing(sess *requesterSession, requestID, path string) (*browseListing, error) {
	_ = sess.client.conn.SetReadDeadline(time.Now().Add(browseTimeout))
	defer sess.client.conn.SetReadDeadline(time.Time{})

	for {
		_, msgBytes, err := sess.client.ReadMessage()
		if err != nil {
			var netErr net.Error
			if errors.As(err, &netErr) && netErr.Timeout() {
				return nil, errors.New("Browse timed out")
			}
			return nil, fmt.Errorf("Relay connection lost: %w", err)
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
		kind, inner, err := openSealed(sess.peerCryptoKey, envelope)
		if err != nil || kind != "browse_listing" {
			continue
		}
		if envelopeString(inner, "request_id") != requestID {
			continue
		}
		if reason := envelopeString(inner, "error"); reason != "" {
			return nil, errors.New(sanitizeBrowseError(reason))
		}
		listing := &browseListing{
			Path:      envelopeString(inner, "path"),
			Entries:   parseBrowseEntries(inner["entries"]),
			Truncated: envelopeBool(inner, "truncated"),
		}
		if listing.Path == "" && path != "" {
			listing.Path = path
		}
		return listing, nil
	}
}

// parseBrowseEntries converts the wire entries array into typed rows. A
// malformed row is skipped; the rest of the listing is still useful.
func parseBrowseEntries(raw interface{}) []browseEntry {
	arr, ok := raw.([]interface{})
	if !ok {
		return []browseEntry{}
	}
	entries := make([]browseEntry, 0, len(arr))
	for _, item := range arr {
		row, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		name := envelopeString(row, "name")
		if name == "" {
			continue
		}
		entries = append(entries, browseEntry{
			Name:     name,
			Size:     envelopeInt64(row, "size"),
			Modified: envelopeInt64(row, "modified"),
			Dir:      envelopeBool(row, "dir"),
		})
	}
	return entries
}

func writeBrowseJSON(listing *browseListing) {
	entries := listing.Entries
	if entries == nil {
		entries = []browseEntry{}
	}
	payload := map[string]interface{}{
		"path":    listing.Path,
		"entries": entries,
	}
	if listing.Truncated {
		payload["truncated"] = true
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	fmt.Println(string(data))
}

func writeBrowseJSONError(path, reason string) {
	data, err := json.Marshal(map[string]interface{}{
		"path":  path,
		"error": reason,
	})
	if err != nil {
		return
	}
	fmt.Println(string(data))
}

// writeBrowseTable prints the human listing. Names are sanitized for display
// only: control characters and bidi overrides in a peer's filename must not
// forge table rows or reorder text.
func writeBrowseTable(listing *browseListing) {
	if listing.Path == "" {
		fmt.Println("Path: /")
	} else {
		fmt.Printf("Path: /%s\n", sanitizeBrowseName(listing.Path))
	}
	w := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(w, "TYPE\tNAME\tSIZE\tMODIFIED")
	for _, entry := range listing.Entries {
		name := sanitizeBrowseName(entry.Name)
		if entry.Dir {
			name += "/"
		}
		size := "-"
		if !entry.Dir {
			size = formatBrowseSize(entry.Size)
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n",
			browseType(entry), name, size, formatBrowseModified(entry.Modified))
	}
	_ = w.Flush()
	if len(listing.Entries) == 0 {
		fmt.Println("(empty directory)")
	}
	if listing.Truncated {
		fmt.Printf("(listing truncated to %d entries)\n", browseMaxEntries)
	}
}

func browseType(entry browseEntry) string {
	if entry.Dir {
		return "dir"
	}
	return "file"
}

// sanitizeBrowseName replaces control characters and Unicode format characters
// with spaces without trimming: a filename's leading or trailing spaces are
// part of its identity, but a newline or bidi override must not break the
// table.
func sanitizeBrowseName(raw string) string {
	var b strings.Builder
	b.Grow(len(raw))
	for _, r := range raw {
		switch {
		case r == '\u2028' || r == '\u2029':
			b.WriteRune(' ')
		case r < 0x20 || r == 0x7f || (r >= 0x80 && r <= 0x9f):
			b.WriteRune(' ')
		case unicode.Is(unicode.Cf, r):
			// Drop format characters outright.
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}

func formatBrowseSize(size int64) string {
	if size < 1024 {
		return fmt.Sprintf("%d B", size)
	}
	value := float64(size)
	for _, unit := range []string{"KiB", "MiB", "GiB", "TiB"} {
		value /= 1024
		if value < 1024 {
			return fmt.Sprintf("%.1f %s", value, unit)
		}
	}
	return fmt.Sprintf("%.1f PiB", value/1024)
}

func formatBrowseModified(ms int64) string {
	if ms <= 0 {
		return "-"
	}
	return time.UnixMilli(ms).Local().Format("2006-01-02 15:04")
}

// runScreenshot asks a device for a screenshot. It is the old
// "request --screenshot" quick action under a clearer name: the sealed kind is
// still "request" with kind "screenshot" so Android targets keep working, and
// the offer comes back through the same grant-verified receive loop.
func runScreenshot(cfg Config, targetQuery string) error {
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
	inner["kind"] = "screenshot"
	inner["expires_ts"] = pending.ExpiresTs
	inner["grant"] = pending.Grant
	if err := sess.seal("request", inner); err != nil {
		return err
	}
	fmt.Printf("Waiting for %s to take a screenshot...\n", peerLabel(sess.targetName, sess.targetID))
	return waitForFetchedFiles(sess.cfg, sess.relayToken, sess.peerCryptoKey, sess.client, pending, 1)
}
