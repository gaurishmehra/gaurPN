package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// Protocol v3.2 primitives: request grants, encrypted peer status blobs, and
// the text-share URL validator. Every construction here is pinned byte-for-
// byte against the Android client; changing a label, separator, or field
// order breaks wire compatibility.

const (
	requestGrantLabel = "gaur-request-grant-v1"
	statusKeyLabel    = "gaur-status-v1"
	statusAADPrefix   = "status:v1:"
	statusNonceSize   = 12
	statusTagSize     = 16
	shareURLMaxLength = 2048
)

// requestGrantMessage is the exact HMAC input for a request grant. It is a
// separate helper so tests can pin the message independently of the MAC.
func requestGrantMessage(requestID, requesterID, targetID string, expiresTs int64) string {
	return requestGrantLabel + ":" + requestID + ":" + requesterID + ":" +
		targetID + ":" + strconv.FormatInt(expiresTs, 10)
}

// ComputeRequestGrant authorizes the requester to auto-accept the file
// transfer that comes back in response to its own request. The grant is bound
// to the request, both device ids, and the expiry, so a relay cannot replay it
// against a different request or peer.
func ComputeRequestGrant(peerKey []byte, requestID, requesterID, targetID string, expiresTs int64) string {
	mac := hmac.New(sha256.New, peerKey)
	mac.Write([]byte(requestGrantMessage(requestID, requesterID, targetID, expiresTs)))
	return hex.EncodeToString(mac.Sum(nil))
}

// VerifyRequestGrant is the constant-time check for a grant. Grants are
// lowercase hex on the wire, so the comparison is exact: no trimming or case
// folding, which keeps the accept/reject decision identical to the Kotlin
// client.
func VerifyRequestGrant(peerKey []byte, requestID, requesterID, targetID string, expiresTs int64, grant string) bool {
	expected := ComputeRequestGrant(peerKey, requestID, requesterID, targetID, expiresTs)
	return subtle.ConstantTimeCompare([]byte(grant), []byte(expected)) == 1
}

// NewRequestID returns "req-" plus 16 lowercase hex chars. 64 bits of
// randomness is enough to make collisions irrelevant for the relay's short
// request TTL.
func NewRequestID() (string, error) {
	suffix, err := randomHex(8)
	if err != nil {
		return "", err
	}
	return "req-" + suffix, nil
}

// DeriveStatusKey domain-separates peer status blobs from file transfers,
// clipboard sync, text shares, and notifications.
func DeriveStatusKey(peerKey []byte) []byte {
	mac := hmac.New(sha256.New, peerKey)
	mac.Write([]byte(statusKeyLabel))
	return mac.Sum(nil)
}

// statusPlaintext builds the pinned status JSON by hand. json.Marshal would
// happen to emit the same bytes today, but its field order and escaping are
// not part of any contract; the Kotlin encoder writes exactly this string.
//
// battery is not validated here: the caller owns the 0-100 range, and the
// relay only ever sees the ciphertext.
func statusPlaintext(battery int, charging bool, ts int64) string {
	chargingText := "false"
	if charging {
		chargingText = "true"
	}
	return `{"battery":` + strconv.Itoa(battery) +
		`,"charging":` + chargingText +
		`,"ts":` + strconv.FormatInt(ts, 10) + `}`
}

// EncodeStatusBlob encrypts cluster telemetry for the relay's peers broadcast.
// The relay stores the blob opaquely; only cluster devices holding K_status can
// read it. A fresh random nonce is used per encode, and the AAD binds the blob
// to deviceID so a relay cannot swap statuses between peers.
func EncodeStatusBlob(statusKey []byte, deviceID string, battery int, charging bool, ts int64) (string, error) {
	nonce, err := RandomBytes(statusNonceSize)
	if err != nil {
		return "", err
	}
	plaintext := []byte(statusPlaintext(battery, charging, ts))
	aad := []byte(statusAADPrefix + deviceID)
	ciphertext, err := EncryptClipboard(statusKey, nonce, plaintext, aad)
	if err != nil {
		return "", err
	}
	blob := make([]byte, 0, len(nonce)+len(ciphertext))
	blob = append(blob, nonce...)
	blob = append(blob, ciphertext...)
	return base64.StdEncoding.EncodeToString(blob), nil
}

// DecodeStatusBlob fails closed: any decode, authentication, or parse error
// returns err and the caller ignores the update. There is no partial result.
func DecodeStatusBlob(statusKey []byte, deviceID, blob string) (battery int, charging bool, ts int64, err error) {
	raw, err := base64.StdEncoding.DecodeString(blob)
	if err != nil {
		return 0, false, 0, fmt.Errorf("status blob: %w", err)
	}
	if len(raw) < statusNonceSize+statusTagSize {
		return 0, false, 0, fmt.Errorf("status blob: %d bytes is too short", len(raw))
	}
	nonce, ciphertext := raw[:statusNonceSize], raw[statusNonceSize:]
	aad := []byte(statusAADPrefix + deviceID)
	plaintext, err := DecryptClipboard(statusKey, nonce, ciphertext, aad)
	if err != nil {
		return 0, false, 0, fmt.Errorf("status blob: %w", err)
	}
	battery, charging, ts, err = parseStatusPlaintext(plaintext)
	if err != nil {
		return 0, false, 0, err
	}
	return battery, charging, ts, nil
}

// errMalformedStatus marks authenticated plaintext that is not the pinned
// status format. It can only come from a cluster peer running a different
// encoder, so the update is dropped rather than guessed at.
var errMalformedStatus = errors.New("status blob: malformed plaintext")

// parseStatusPlaintext is the strict counterpart to statusPlaintext. It
// accepts exactly the pinned shape and rejects everything else, including
// non-canonical JSON that json.Unmarshal would happily accept.
func parseStatusPlaintext(plaintext []byte) (int, bool, int64, error) {
	const (
		batteryPrefix = `{"battery":`
		chargingSep   = `,"charging":`
		tsSep         = `,"ts":`
	)
	rest, ok := strings.CutPrefix(string(plaintext), batteryPrefix)
	if !ok {
		return 0, false, 0, errMalformedStatus
	}
	batteryText, rest, ok := strings.Cut(rest, chargingSep)
	if !ok {
		return 0, false, 0, errMalformedStatus
	}
	battery, err := strconv.Atoi(batteryText)
	if err != nil {
		return 0, false, 0, errMalformedStatus
	}
	chargingText, rest, ok := strings.Cut(rest, tsSep)
	if !ok {
		return 0, false, 0, errMalformedStatus
	}
	var charging bool
	switch chargingText {
	case "true":
		charging = true
	case "false":
		charging = false
	default:
		return 0, false, 0, errMalformedStatus
	}
	tsText, ok := strings.CutSuffix(rest, "}")
	if !ok {
		return 0, false, 0, errMalformedStatus
	}
	ts, err := strconv.ParseInt(tsText, 10, 64)
	if err != nil {
		return 0, false, 0, errMalformedStatus
	}
	return battery, charging, ts, nil
}

// shareURLUnsafeRunes is the URL-unsafe set beyond control characters and
// space. It is stricter than RFC 3986 on purpose: the same rule set is
// implemented in Kotlin, and a small shared alphabet is what keeps the two
// accept/reject decisions identical.
const shareURLUnsafeRunes = "\"<>\\^`{|}"

// ValidateShareURL returns the canonical URL and true iff it is safe to hand
// to xdg-open. The canonical form is for local use only (it never crosses the
// wire), so minor serializer differences are acceptable; the accept/reject
// decision is the part that must match the Kotlin client exactly.
func ValidateShareURL(raw string) (string, bool) {
	trimmed := strings.TrimSpace(raw)
	// Count UTF-16 code units, not UTF-8 bytes, to match Kotlin's
	// String.length on non-ASCII input.
	if utf16Length(trimmed) > shareURLMaxLength {
		return "", false
	}
	for _, r := range trimmed {
		if r < 0x21 || r == 0x7F || strings.ContainsRune(shareURLUnsafeRunes, r) {
			return "", false
		}
	}
	u, err := url.Parse(trimmed)
	if err != nil {
		return "", false
	}
	switch strings.ToLower(u.Scheme) {
	case "http", "https":
	default:
		return "", false
	}
	// Hostname() strips the port and IPv6 brackets, matching Kotlin's
	// URI.host: "https://:8080/x" has no host and is rejected.
	if u.Hostname() == "" {
		return "", false
	}
	return u.String(), true
}

// utf16Length returns the number of UTF-16 code units in s, which is what
// Kotlin's String.length counts. Invalid UTF-8 counts one unit per
// replacement rune; net/url never produces such input from a valid string.
func utf16Length(s string) int {
	units := 0
	for _, r := range s {
		if r > 0xFFFF {
			units += 2
			continue
		}
		units++
	}
	return units
}
