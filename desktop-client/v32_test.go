package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"math"
	"regexp"
	"strings"
	"testing"
)

// Pinned protocol v3.2 vectors. These literals are shared with the Android
// client's tests; do not regenerate them from this implementation.
const (
	v32PeerKeyHex   = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
	v32StatusKeyHex = "a277969fc57720bff75b3a807504c29e7306899fc4b5e9883368c2cba409764f"
	v32GrantMsg     = "gaur-request-grant-v1:req-0011223344556677:reqester-abc123:android-f3dd4a01:1767225600000"
	v32GrantHex     = "cd232c541e09733b88b73687f7b4806e7cf8ad22995adf2b5fe7542bc77506d6"
	v32StatusJSON   = `{"battery":73,"charging":true,"ts":1730000000000}`
)

func v32Hex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("decode hex %q: %v", s, err)
	}
	return b
}

func TestV32DeriveStatusKey(t *testing.T) {
	peerKey := v32Hex(t, v32PeerKeyHex)

	got := DeriveStatusKey(peerKey)
	if hex.EncodeToString(got) != v32StatusKeyHex {
		t.Fatalf("DeriveStatusKey = %x, want %s", got, v32StatusKeyHex)
	}
	if len(got) != 32 {
		t.Fatalf("status key length = %d, want 32", len(got))
	}
	if !bytes.Equal(got, DeriveStatusKey(peerKey)) {
		t.Fatal("status key derivation must be deterministic")
	}

	// Status blobs must not be decryptable with any other channel key.
	for name, other := range map[string][]byte{
		"clipboard":    DeriveClipboardKey(peerKey),
		"text":         DeriveTextKey(peerKey),
		"notification": DeriveNotificationKey(peerKey),
	} {
		if bytes.Equal(got, other) {
			t.Errorf("status key collides with the %s key", name)
		}
	}
}

func TestV32RequestGrantVector(t *testing.T) {
	peerKey := v32Hex(t, v32PeerKeyHex)

	msg := requestGrantMessage("req-0011223344556677", "reqester-abc123", "android-f3dd4a01", 1767225600000)
	if msg != v32GrantMsg {
		t.Fatalf("grant message = %q, want %q", msg, v32GrantMsg)
	}

	got := ComputeRequestGrant(peerKey, "req-0011223344556677", "reqester-abc123", "android-f3dd4a01", 1767225600000)
	if got != v32GrantHex {
		t.Fatalf("ComputeRequestGrant = %s, want %s", got, v32GrantHex)
	}
}

func TestV32VerifyRequestGrant(t *testing.T) {
	peerKey := v32Hex(t, v32PeerKeyHex)
	_, otherKey := DeriveSubKeys("v32-other-cluster-token")

	const (
		requestID   = "req-0011223344556677"
		requesterID = "reqester-abc123"
		targetID    = "android-f3dd4a01"
		expiresTs   = int64(1767225600000)
	)
	valid := ComputeRequestGrant(peerKey, requestID, requesterID, targetID, expiresTs)
	if valid != v32GrantHex {
		t.Fatalf("valid grant = %s, want pinned %s", valid, v32GrantHex)
	}
	// Changing the first two hex chars is enough to make the digest differ
	// regardless of what they were.
	tampered := "00" + valid[2:]

	cases := []struct {
		name        string
		key         []byte
		requestID   string
		requesterID string
		targetID    string
		expiresTs   int64
		grant       string
		want        bool
	}{
		{"pinned vector", peerKey, requestID, requesterID, targetID, expiresTs, v32GrantHex, true},
		{"tampered grant", peerKey, requestID, requesterID, targetID, expiresTs, tampered, false},
		{"wrong request id", peerKey, "req-8899aabbccddeeff", requesterID, targetID, expiresTs, valid, false},
		{"wrong requester", peerKey, requestID, "reqester-other", targetID, expiresTs, valid, false},
		{"wrong target", peerKey, requestID, requesterID, "android-00000000", expiresTs, valid, false},
		{"wrong expiry", peerKey, requestID, requesterID, targetID, expiresTs + 1, valid, false},
		{"wrong key", otherKey, requestID, requesterID, targetID, expiresTs, valid, false},
		{"empty grant", peerKey, requestID, requesterID, targetID, expiresTs, "", false},
		{"uppercase grant is not normalized", peerKey, requestID, requesterID, targetID, expiresTs, strings.ToUpper(valid), false},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := VerifyRequestGrant(tc.key, tc.requestID, tc.requesterID, tc.targetID, tc.expiresTs, tc.grant)
			if got != tc.want {
				t.Errorf("VerifyRequestGrant = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestV32NewRequestID(t *testing.T) {
	pattern := regexp.MustCompile(`^req-[0-9a-f]{16}$`)
	seen := make(map[string]struct{}, 1000)
	for i := 0; i < 1000; i++ {
		id, err := NewRequestID()
		if err != nil {
			t.Fatalf("NewRequestID draw %d: %v", i, err)
		}
		if !pattern.MatchString(id) {
			t.Fatalf("NewRequestID draw %d = %q, want req-[0-9a-f]{16}", i, id)
		}
		if len(id) != 20 {
			t.Fatalf("NewRequestID draw %d = %q, length %d, want 20", i, id, len(id))
		}
		if _, dup := seen[id]; dup {
			t.Fatalf("NewRequestID returned duplicate %q on draw %d", id, i)
		}
		seen[id] = struct{}{}
	}
}

func TestV32StatusPlaintextPinned(t *testing.T) {
	if got := statusPlaintext(73, true, 1730000000000); got != v32StatusJSON {
		t.Fatalf("statusPlaintext = %q, want %q", got, v32StatusJSON)
	}

	cases := []struct {
		name     string
		battery  int
		charging bool
		ts       int64
		want     string
	}{
		{"zero values", 0, false, 0, `{"battery":0,"charging":false,"ts":0}`},
		{"full battery", 100, true, math.MaxInt64, `{"battery":100,"charging":true,"ts":9223372036854775807}`},
		// No range validation in the encoder: the caller owns battery bounds.
		{"out of range passes through", -1, false, -5, `{"battery":-1,"charging":false,"ts":-5}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := statusPlaintext(tc.battery, tc.charging, tc.ts); got != tc.want {
				t.Errorf("statusPlaintext(%d, %v, %d) = %q, want %q", tc.battery, tc.charging, tc.ts, got, tc.want)
			}
		})
	}
}

func TestV32StatusBlobRoundTrip(t *testing.T) {
	statusKey := v32Hex(t, v32StatusKeyHex)

	blob, err := EncodeStatusBlob(statusKey, "device-test-1", 73, true, 1730000000000)
	if err != nil {
		t.Fatalf("EncodeStatusBlob: %v", err)
	}

	raw, err := base64.StdEncoding.DecodeString(blob)
	if err != nil {
		t.Fatalf("blob is not standard base64: %v", err)
	}
	// 12-byte nonce + 49-byte pinned plaintext + 16-byte GCM tag = 77 bytes,
	// which is 104 base64 chars.
	if want := statusNonceSize + len(v32StatusJSON) + statusTagSize; len(raw) != want {
		t.Fatalf("blob payload = %d bytes, want %d", len(raw), want)
	}
	if len(blob) != 104 {
		t.Fatalf("blob length = %d chars, want 104", len(blob))
	}

	// A second encode must use a fresh nonce, so the blobs differ.
	blob2, err := EncodeStatusBlob(statusKey, "device-test-1", 73, true, 1730000000000)
	if err != nil {
		t.Fatalf("second EncodeStatusBlob: %v", err)
	}
	if blob == blob2 {
		t.Fatal("two encodes produced identical blobs; nonce is not fresh")
	}

	battery, charging, ts, err := DecodeStatusBlob(statusKey, "device-test-1", blob)
	if err != nil {
		t.Fatalf("DecodeStatusBlob: %v", err)
	}
	if battery != 73 || !charging || ts != 1730000000000 {
		t.Fatalf("round trip = (%d, %v, %d), want (73, true, 1730000000000)", battery, charging, ts)
	}
}

func TestV32StatusBlobFailsClosed(t *testing.T) {
	statusKey := v32Hex(t, v32StatusKeyHex)
	blob, err := EncodeStatusBlob(statusKey, "device-test-1", 73, true, 1730000000000)
	if err != nil {
		t.Fatalf("EncodeStatusBlob: %v", err)
	}
	raw, err := base64.StdEncoding.DecodeString(blob)
	if err != nil {
		t.Fatalf("decode blob: %v", err)
	}

	tampered := append([]byte(nil), raw...)
	tampered[len(tampered)-1] ^= 0x01
	tamperedBlob := base64.StdEncoding.EncodeToString(tampered)

	_, otherKey := DeriveSubKeys("v32-wrong-status-key")

	// Authenticated but non-canonical plaintext: a peer running a different
	// encoder must be rejected, not best-effort parsed.
	nonce, err := RandomBytes(statusNonceSize)
	if err != nil {
		t.Fatalf("nonce: %v", err)
	}
	badPlaintext := []byte(`{"battery":73,"charging":true,"ts":1730000000000,"x":1}`)
	badCiphertext, err := EncryptClipboard(statusKey, nonce, badPlaintext, []byte(statusAADPrefix+"device-test-1"))
	if err != nil {
		t.Fatalf("encrypt non-canonical plaintext: %v", err)
	}
	badPlaintextBlob := base64.StdEncoding.EncodeToString(append(append([]byte(nil), nonce...), badCiphertext...))

	cases := []struct {
		name     string
		key      []byte
		deviceID string
		blob     string
		wantErr  error
	}{
		{"wrong device id (AAD)", statusKey, "device-test-2", blob, nil},
		{"tampered ciphertext", statusKey, "device-test-1", tamperedBlob, nil},
		{"garbage base64", statusKey, "device-test-1", "!!!not-base64!!!", nil},
		{"truncated blob", statusKey, "device-test-1", base64.StdEncoding.EncodeToString(raw[:20]), nil},
		{"empty blob", statusKey, "device-test-1", "", nil},
		{"wrong key", otherKey, "device-test-1", blob, nil},
		{"non-canonical plaintext", statusKey, "device-test-1", badPlaintextBlob, errMalformedStatus},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			battery, charging, ts, err := DecodeStatusBlob(tc.key, tc.deviceID, tc.blob)
			if err == nil {
				t.Fatalf("DecodeStatusBlob succeeded with (%d, %v, %d), want error", battery, charging, ts)
			}
			if tc.wantErr != nil && !errors.Is(err, tc.wantErr) {
				t.Errorf("error = %v, want %v", err, tc.wantErr)
			}
		})
	}
}

func TestV32StatusBlobLengthCap(t *testing.T) {
	statusKey := v32Hex(t, v32StatusKeyHex)

	for _, battery := range []int{0, 100} {
		for _, charging := range []bool{false, true} {
			for _, ts := range []int64{0, 1730000000000, math.MaxInt64} {
				blob, err := EncodeStatusBlob(statusKey, "device-test-1", battery, charging, ts)
				if err != nil {
					t.Fatalf("EncodeStatusBlob(%d, %v, %d): %v", battery, charging, ts, err)
				}
				if len(blob) > 160 {
					t.Errorf("blob for battery=%d charging=%v ts=%d is %d chars, relay cap is 160", battery, charging, ts, len(blob))
				}
				gotBattery, gotCharging, gotTs, err := DecodeStatusBlob(statusKey, "device-test-1", blob)
				if err != nil {
					t.Fatalf("round trip (%d, %v, %d): %v", battery, charging, ts, err)
				}
				if gotBattery != battery || gotCharging != charging || gotTs != ts {
					t.Errorf("round trip = (%d, %v, %d), want (%d, %v, %d)", gotBattery, gotCharging, gotTs, battery, charging, ts)
				}
			}
		}
	}
}

func TestV32ValidateShareURL(t *testing.T) {
	boundary := "https://example.com/" + strings.Repeat("a", shareURLMaxLength-len("https://example.com/"))
	cases := []struct {
		name      string
		raw       string
		want      bool
		canonical string
	}{
		{"https path and query", "https://example.com/path?q=1", true, "https://example.com/path?q=1"},
		{"http host only", "http://example.com", true, "http://example.com"},
		{"uppercase scheme and host", "HTTPS://EXAMPLE.COM/Path", true, "https://EXAMPLE.COM/Path"},
		{"port and fragment", "https://example.com:8080/p#frag", true, "https://example.com:8080/p#frag"},
		{"surrounding whitespace trimmed", " https://example.com ", true, "https://example.com"},
		{"exactly 2048 chars", boundary, true, boundary},

		{"empty host", "https://", false, ""},
		{"non-http scheme", "steam://store", false, ""},
		{"javascript scheme", "javascript:alert(1)", false, ""},
		{"file scheme", "file:///etc/passwd", false, ""},
		{"space in path", "https://example.com/a b", false, ""},
		{"no scheme", "example.com", false, ""},
		{"less-than", "https://example.com/a<b", false, ""},
		{"pipe", "https://example.com/a|b", false, ""},
		{"backtick", "https://example.com/a`b", false, ""},
		{"three kilobyte url", "https://example.com/" + strings.Repeat("a", 3000), false, ""},
		{"tab in path", "https://example.com/a\tb", false, ""},
		{"2049 chars", boundary + "a", false, ""},
		{"double quote", `https://example.com/a"b`, false, ""},
		{"greater-than", "https://example.com/a>b", false, ""},
		{"backslash", `https://example.com/a\b`, false, ""},
		{"caret", "https://example.com/a^b", false, ""},
		{"open brace", "https://example.com/a{b", false, ""},
		{"close brace", "https://example.com/a}b", false, ""},
		{"delete character", "https://example.com/a\x7fb", false, ""},
		{"empty string", "", false, ""},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := ValidateShareURL(tc.raw)
			if ok != tc.want {
				t.Fatalf("ValidateShareURL(%q) ok = %v, want %v", tc.raw, ok, tc.want)
			}
			if !tc.want {
				if got != "" {
					t.Errorf("rejected URL returned canonical %q, want empty", got)
				}
				return
			}
			if got != tc.canonical {
				t.Errorf("ValidateShareURL(%q) = %q, want %q", tc.raw, got, tc.canonical)
			}
			// The canonical form of an accepted ASCII URL must itself be
			// accepted, so callers can re-check before handing it off.
			if again, ok := ValidateShareURL(got); !ok || again != got {
				t.Errorf("canonical form %q is not stable: ok=%v again=%q", got, ok, again)
			}
		})
	}
}

// TestV32ValidateShareURLCountsUTF16Units pins the length rule to Kotlin's
// String.length (UTF-16 code units) rather than UTF-8 bytes: this URL is 2220
// bytes but only 1120 units, so it is accepted on both platforms.
func TestV32ValidateShareURLCountsUTF16Units(t *testing.T) {
	raw := "https://example.com/" + strings.Repeat("é", 1100)
	if len(raw) <= shareURLMaxLength {
		t.Fatalf("test URL is only %d bytes, need more than %d", len(raw), shareURLMaxLength)
	}
	if utf16Length(raw) > shareURLMaxLength {
		t.Fatalf("test URL is %d UTF-16 units, need at most %d", utf16Length(raw), shareURLMaxLength)
	}
	if _, ok := ValidateShareURL(raw); !ok {
		t.Fatal("URL under the UTF-16 length cap was rejected")
	}
}
