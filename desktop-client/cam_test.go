package main

// Camera stream v1 tests: session derivation vectors recomputed in-test, the
// frame seal/open round trip with index-bound AAD rejection, length-prefix
// framing including the oversize abort, the control envelope shape, the
// player command lines, and the end-to-end flow against the fake relay.

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

// testPeerKey derives a stable K_peer for vectors that do not care about the
// token it came from.
func testPeerKey(token string) []byte {
	_, peerKey := DeriveSubKeys(token)
	return peerKey
}

func TestDeriveCamSessionVectors(t *testing.T) {
	cases := []struct {
		name      string
		peerKey   []byte
		requestID string
	}{
		{"derived cluster key", testPeerKey("cluster-token-alpha"), "req-0011223344556677"},
		{"raw key bytes", []byte("0123456789abcdef0123456789abcdef"), "req-ffffffffffffffff"},
		{"empty request id", testPeerKey("cluster-token-beta"), ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := deriveCamSessionID(tc.peerKey, tc.requestID)
			// session_id = hex(HMAC-SHA256(K_peer, "gaur-cam-session-v1:"+request_id)),
			// recomputed here rather than hardcoded from a run.
			mac := hmac.New(sha256.New, tc.peerKey)
			mac.Write([]byte("gaur-cam-session-v1:" + tc.requestID))
			want := hex.EncodeToString(mac.Sum(nil))
			if got != want {
				t.Fatalf("session id = %q, want %q", got, want)
			}
			if len(got) != 64 || strings.ToLower(got) != got {
				t.Fatalf("session id = %q, want 64 lowercase hex chars", got)
			}

			key := camFrameKey(tc.peerKey, got)
			frameMac := hmac.New(sha256.New, tc.peerKey)
			frameMac.Write([]byte("gaur-camera-v1:" + got))
			wantKey := frameMac.Sum(nil)
			if !bytes.Equal(key, wantKey) {
				t.Fatalf("K_cam = %x, want %x", key, wantKey)
			}
			if len(key) != 32 {
				t.Fatalf("K_cam length = %d, want 32", len(key))
			}
		})
	}

	// Domain separation: a different request id means a different session and
	// a different frame key, and the session id is never usable as key
	// material.
	peerKey := testPeerKey("cluster-token-alpha")
	first := deriveCamSessionID(peerKey, "req-0011223344556677")
	second := deriveCamSessionID(peerKey, "req-8899aabbccddeeff")
	if first == second {
		t.Fatal("two request ids derived the same session id")
	}
	if bytes.Equal(camFrameKey(peerKey, first), camFrameKey(peerKey, second)) {
		t.Fatal("two session ids derived the same K_cam")
	}
	if bytes.Equal(camFrameKey(peerKey, first), []byte(first)) {
		t.Fatal("K_cam is the session id bytes")
	}
}

func TestCamFrameSealOpenRoundTrip(t *testing.T) {
	const sessionID = "cafebabecafebabecafebabecafebabecafebabecafebabecafebabecafebabe"
	key := camFrameKey(testPeerKey("cluster-token-alpha"), sessionID)

	cases := []struct {
		name       string
		plain      []byte
		frameIndex int64
	}{
		{"empty frame 0", []byte{}, 0},
		{"csd frame", []byte{0x00, 0x00, 0x01, 0x40, 0x01, 0x01, 0x50}, 0},
		{"small frame", []byte("hevc"), 1},
		{"large frame", bytes.Repeat([]byte{0xA5}, 256*1024), 42},
		{"high frame index", []byte("later"), 1 << 40},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			nonce, err := RandomBytes(camNonceSize)
			if err != nil {
				t.Fatal(err)
			}
			frame, err := sealCamFrame(key, nonce, tc.plain, sessionID, tc.frameIndex)
			if err != nil {
				t.Fatalf("seal: %v", err)
			}
			// Record shape: L = nonce + tag + plaintext, record = 4 + L.
			length := binary.BigEndian.Uint32(frame[:camFrameHeaderSize])
			if int(length) != camFrameOverhead+len(tc.plain) {
				t.Fatalf("L = %d, want %d", length, camFrameOverhead+len(tc.plain))
			}
			if len(frame) != camFrameHeaderSize+int(length) {
				t.Fatalf("record length = %d, want %d", len(frame), camFrameHeaderSize+int(length))
			}

			gotNonce, sealed, err := parseCamFrame(frame)
			if err != nil {
				t.Fatalf("parse: %v", err)
			}
			if !bytes.Equal(gotNonce, nonce) {
				t.Fatal("parsed nonce does not match the sealed nonce")
			}
			// Wire layout after the length prefix: [12-byte nonce]
			// [L-28 ciphertext] [16-byte GCM tag]. parseCamFrame hands back
			// the ciphertext and tag as one slice (the shape Go's GCM
			// Open expects), so the tail is L-12 bytes with the tag last.
			if len(sealed) != int(length)-camNonceSize {
				t.Fatalf("sealed tail = %d bytes, want L-12 = %d", len(sealed), int(length)-camNonceSize)
			}
			if tag := sealed[len(sealed)-camTagSize:]; len(tag) != camTagSize {
				t.Fatalf("tag = %d bytes, want %d", len(tag), camTagSize)
			}

			plain, err := openCamFrame(key, gotNonce, sealed, sessionID, tc.frameIndex)
			if err != nil {
				t.Fatalf("open: %v", err)
			}
			if !bytes.Equal(plain, tc.plain) {
				t.Fatal("round trip changed the plaintext")
			}

			// Wrong-index AAD rejection: the same bytes under any other
			// receive count must fail authentication.
			wrongIndex := tc.frameIndex + 1
			if _, err := openCamFrame(key, gotNonce, sealed, sessionID, wrongIndex); err == nil {
				t.Fatalf("frame %d opened under AAD index %d", tc.frameIndex, wrongIndex)
			}
			// Cross-session rejection.
			if _, err := openCamFrame(key, gotNonce, sealed, "other-session", tc.frameIndex); err == nil {
				t.Fatal("frame opened under a different session id")
			}
			// Tampered ciphertext rejection.
			if len(sealed) == 0 {
				t.Fatal("test premise broken: sealed body is empty")
			}
			tampered := append([]byte(nil), sealed...)
			tampered[0] ^= 0xFF
			if _, err := openCamFrame(key, gotNonce, tampered, sessionID, tc.frameIndex); err == nil {
				t.Fatal("tampered frame authenticated")
			}
		})
	}

	// A fresh nonce per frame: the same plaintext under the same index must
	// never produce the same record.
	nonce1, _ := RandomBytes(camNonceSize)
	nonce2, _ := RandomBytes(camNonceSize)
	one, err := sealCamFrame(key, nonce1, []byte("p"), sessionID, 0)
	if err != nil {
		t.Fatal(err)
	}
	two, err := sealCamFrame(key, nonce2, []byte("p"), sessionID, 0)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(one, two) {
		t.Fatal("two seals with fresh nonces produced identical records")
	}

	// Builder-side guards.
	if _, err := sealCamFrame(key, []byte("short"), []byte("p"), sessionID, 0); err == nil {
		t.Fatal("a short nonce must be rejected, not panic GCM")
	}
	if _, err := sealCamFrame(key, nonce1, make([]byte, camMaxFrameLen), sessionID, 0); err == nil {
		t.Fatalf("a plaintext over the %d MiB cap must be rejected", camMaxFrameLen>>20)
	}
	if _, err := openCamFrame(key, []byte("short"), one[camFrameHeaderSize:], sessionID, 0); err == nil {
		t.Fatal("a short nonce must be rejected on open, not panic GCM")
	}
}

func TestCamFrameReaderFraming(t *testing.T) {
	const sessionID = "reader-session"
	key := camFrameKey(testPeerKey("cluster-token-alpha"), sessionID)

	build := func(t *testing.T, frameIndex int64, plain []byte) []byte {
		t.Helper()
		nonce, err := RandomBytes(camNonceSize)
		if err != nil {
			t.Fatal(err)
		}
		record, err := sealCamFrame(key, nonce, plain, sessionID, frameIndex)
		if err != nil {
			t.Fatal(err)
		}
		return record
	}

	// An ordered stream decrypts in order and ends in a clean io.EOF.
	plains := [][]byte{
		{0x00, 0x00, 0x01, 0x40, 0x01}, // HEVC csd flavor
		bytes.Repeat([]byte{0x42}, 4096),
		{},
	}
	var stream bytes.Buffer
	for i, plain := range plains {
		stream.Write(build(t, int64(i), plain))
	}
	reader := newCamFrameReader(&stream, key, sessionID)
	for i, plain := range plains {
		got, err := reader.next()
		if err != nil {
			t.Fatalf("frame %d: %v", i, err)
		}
		if !bytes.Equal(got, plain) {
			t.Fatalf("frame %d = %x, want %x", i, got, plain)
		}
	}
	if _, err := reader.next(); !errors.Is(err, io.EOF) {
		t.Fatalf("clean stream end = %v, want io.EOF", err)
	}

	// A truncated stream: the frame count must not advance past the frames
	// that actually authenticated.
	bad := []struct {
		name     string
		okBefore int
		build    func(*bytes.Buffer)
		wantIn   string
	}{
		{
			name:     "oversize length aborts before buffering",
			okBefore: 0,
			build: func(b *bytes.Buffer) {
				var head [camFrameHeaderSize]byte
				binary.BigEndian.PutUint32(head[:], camMaxFrameLen+1)
				b.Write(head[:])
				b.Write(bytes.Repeat([]byte{0}, 512))
			},
			wantIn: "exceeds",
		},
		{
			name:     "length below nonce and tag overhead",
			okBefore: 0,
			build: func(b *bytes.Buffer) {
				var head [camFrameHeaderSize]byte
				binary.BigEndian.PutUint32(head[:], camFrameOverhead-1)
				b.Write(head[:])
				b.Write(bytes.Repeat([]byte{0}, camFrameOverhead-1))
			},
			wantIn: "overhead",
		},
		{
			name:     "stream ends mid-frame",
			okBefore: 0,
			build: func(b *bytes.Buffer) {
				record := build(t, 0, []byte("x"))
				b.Write(record[:len(record)-1])
			},
			wantIn: "mid-frame",
		},
		{
			name:     "index mismatch fails authentication",
			okBefore: 1,
			build: func(b *bytes.Buffer) {
				b.Write(build(t, 0, []byte("a")))
				// Sealed for index 2 while the receiver expects 1.
				b.Write(build(t, 2, []byte("c")))
			},
			wantIn: "authentication",
		},
	}
	for _, tc := range bad {
		t.Run(tc.name, func(t *testing.T) {
			var stream bytes.Buffer
			tc.build(&stream)
			reader := newCamFrameReader(&stream, key, sessionID)
			for i := 0; i < tc.okBefore; i++ {
				if _, err := reader.next(); err != nil {
					t.Fatalf("frame %d: %v", i, err)
				}
			}
			_, err := reader.next()
			if err == nil {
				t.Fatal("a malformed stream produced no error")
			}
			if errors.Is(err, io.EOF) {
				t.Fatal("a malformed stream must not read as a clean end")
			}
			if !strings.Contains(err.Error(), tc.wantIn) {
				t.Fatalf("error = %q, want it to mention %q", err.Error(), tc.wantIn)
			}
		})
	}

	// An empty stream is a clean end, not a transport failure.
	empty := newCamFrameReader(strings.NewReader(""), key, sessionID)
	if _, err := empty.next(); !errors.Is(err, io.EOF) {
		t.Fatalf("empty stream = %v, want io.EOF", err)
	}
}

func TestCamControlEnvelopeShape(t *testing.T) {
	const requestID = "req-0011223344556677"
	const sessionID = "cafebabecafebabecafebabecafebabecafebabecafebabecafebabecafebabe"

	cases := []struct {
		name    string
		action  string
		on      bool
		tier    string
		wantOK  bool
		hasOn   bool
		onValue bool
		hasTier bool
	}{
		{"torch on", "torch", true, "", true, true, true, false},
		{"torch off", "torch", false, "", true, true, false, false},
		{"lens toggle", "lens", false, "", true, false, false, false},
		{"tier high", "tier", false, "high", true, false, false, true},
		{"tier balanced", "tier", false, "balanced", true, false, false, true},
		{"tier low", "tier", false, "low", true, false, false, true},
		{"stop", "stop", false, "", true, false, false, false},
		{"unknown action", "zoom", false, "", false, false, false, false},
		{"unknown tier", "tier", false, "ultra", false, false, false, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ctl, err := newCamControl(requestID, sessionID, tc.action, tc.on, tc.tier)
			if !tc.wantOK {
				if err == nil {
					t.Fatalf("%s control built without an error", tc.action)
				}
				return
			}
			if err != nil {
				t.Fatalf("build: %v", err)
			}
			inner := ctl.inner()
			for _, key := range []string{"request_id", "session_id", "action", "ts"} {
				if _, ok := inner[key]; !ok {
					t.Fatalf("inner payload missing %q", key)
				}
			}
			_, hasOn := inner["on"]
			_, hasTier := inner["tier"]
			if hasOn != tc.hasOn || hasTier != tc.hasTier {
				t.Fatalf("on/tier presence = %v/%v, want %v/%v", hasOn, hasTier, tc.hasOn, tc.hasTier)
			}
			wantKeys := 4
			if hasOn {
				wantKeys++
			}
			if hasTier {
				wantKeys++
			}
			if len(inner) != wantKeys {
				t.Fatalf("inner payload has %d keys, want exactly %d", len(inner), wantKeys)
			}
			if envelopeInt64(inner, "ts") <= 0 {
				t.Fatal("inner payload ts is not a positive timestamp")
			}

			// Round trip through JSON — the wire form — and back.
			data, err := json.Marshal(inner)
			if err != nil {
				t.Fatal(err)
			}
			var wire map[string]interface{}
			if err := json.Unmarshal(data, &wire); err != nil {
				t.Fatal(err)
			}
			parsed, ok := parseCamControl(wire)
			if !ok {
				t.Fatalf("parse rejected a well-formed %s control", tc.action)
			}
			if parsed.requestID != requestID || parsed.sessionID != sessionID || parsed.action != tc.action {
				t.Fatalf("parsed = %+v", parsed)
			}
			if parsed.hasOn != tc.hasOn || parsed.on != tc.onValue {
				t.Fatalf("parsed on = %v (%v), want %v (%v)", parsed.on, parsed.hasOn, tc.onValue, tc.hasOn)
			}
			if parsed.hasTier != tc.hasTier || (tc.hasTier && parsed.tier != tc.tier) {
				t.Fatalf("parsed tier = %q (%v), want %q (%v)", parsed.tier, parsed.hasTier, tc.tier, tc.hasTier)
			}
		})
	}

	// The sealed envelope machinery must carry the pinned kind.
	peerKey := testPeerKey("cluster-token-alpha")
	ctl, err := newCamControl(requestID, sessionID, "tier", false, "low")
	if err != nil {
		t.Fatal(err)
	}
	envelope, err := sealEnvelope(peerKey, "phone-1", "cam_control", ctl.inner())
	if err != nil {
		t.Fatal(err)
	}
	kind, inner, err := openSealed(peerKey, envelope)
	if err != nil {
		t.Fatal(err)
	}
	if kind != "cam_control" {
		t.Fatalf("sealed kind = %q, want cam_control", kind)
	}
	if _, ok := parseCamControl(inner); !ok {
		t.Fatal("a control that arrived through sealEnvelope failed to parse")
	}

	// Malformed payloads are rejected, field by field.
	bad := []map[string]interface{}{
		{"request_id": "r", "session_id": "s", "action": "torch"},
		{"request_id": "r", "session_id": "s", "action": "torch", "on": "yes"},
		{"request_id": "r", "session_id": "s", "action": "torch", "on": true, "tier": "high"},
		{"request_id": "r", "session_id": "s", "action": "lens", "on": true},
		{"request_id": "r", "session_id": "s", "action": "stop", "tier": "high"},
		{"request_id": "r", "session_id": "s", "action": "tier"},
		{"request_id": "r", "session_id": "s", "action": "tier", "tier": "ultra"},
		{"request_id": "r", "session_id": "s", "action": "tier", "tier": "high", "on": false},
		{"session_id": "s", "action": "stop"},
		{"request_id": "r", "action": "stop"},
		{"request_id": "r", "session_id": "s", "action": "rewind"},
	}
	for _, payload := range bad {
		if _, ok := parseCamControl(payload); ok {
			t.Fatalf("parse accepted a malformed control: %v", payload)
		}
	}
}

func TestCamTierSpecsPinned(t *testing.T) {
	cases := []struct {
		name string
		want camTierSpec
	}{
		{"high", camTierSpec{Width: 1920, Height: 1080, FPS: 30, Bitrate: 8000000}},
		{"balanced", camTierSpec{Width: 1280, Height: 720, FPS: 30, Bitrate: 3500000}},
		{"low", camTierSpec{Width: 640, Height: 360, FPS: 30, Bitrate: 500000}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := camTierSpecs[tc.name]; got != tc.want {
				t.Fatalf("%s = %+v, want %+v", tc.name, got, tc.want)
			}
		})
	}
	if len(camTierSpecs) != 3 {
		t.Fatalf("tier table has %d entries, want the 3 pinned v1 tiers", len(camTierSpecs))
	}
}

func TestCamPlayerCommandLines(t *testing.T) {
	cases := []struct {
		name      string
		loopDev   string
		override  string
		wantName  string
		wantArgs  []string
		wantUsage bool
	}{
		{
			name:     "mpv window default",
			wantName: "mpv",
			wantArgs: []string{"--no-cache", "--profile=low-latency", "--demuxer-lavf-format=hevc", "--hwdec=auto", "-"},
		},
		{
			name:     "loopback",
			loopDev:  "/dev/video10",
			wantName: "ffmpeg",
			wantArgs: []string{"-f", "hevc", "-i", "-", "-pix_fmt", "yuv420p", "-f", "v4l2", "/dev/video10"},
		},
		{
			name:     "player override with args",
			override: "myplayer --quiet --fps 30",
			wantName: "myplayer",
			wantArgs: []string{"--quiet", "--fps", "30", "-"},
		},
		{
			name:     "bare player override gets a stdin target",
			override: "mpv",
			wantName: "mpv",
			wantArgs: []string{"-"},
		},
		{
			name:     "override that names stdin stays verbatim",
			override: "mpv --fs -",
			wantName: "mpv",
			wantArgs: []string{"--fs", "-"},
		},
		{
			name:     "ffmpeg-style override with -i - stays verbatim",
			override: "ffmpeg -f hevc -i - -f null -",
			wantName: "ffmpeg",
			wantArgs: []string{"-f", "hevc", "-i", "-", "-f", "null", "-"},
		},
		{
			name:     "player override single word",
			override: "cat",
			wantName: "cat",
			wantArgs: []string{"-"},
		},
		{
			name:      "loopback and player are exclusive",
			loopDev:   "/dev/video10",
			override:  "mpv",
			wantUsage: true,
		},
		{
			name:      "loopback device suffix rejected",
			loopDev:   "/dev/video9x",
			wantUsage: true,
		},
		{
			name:      "loopback must be a device node",
			loopDev:   "v4l2:/dev/video1",
			wantUsage: true,
		},
		{
			name:      "player override whitespace only",
			override:  "   ",
			wantUsage: true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			player, err := camPlayerArgs(tc.loopDev, tc.override)
			if tc.wantUsage {
				if err == nil {
					t.Fatalf("camPlayerArgs(%q, %q) accepted invalid flags", tc.loopDev, tc.override)
				}
				if code := controlExitCode(err); code != 2 {
					t.Fatalf("exit code = %d, want 2 for a usage error", code)
				}
				return
			}
			if err != nil {
				t.Fatalf("camPlayerArgs(%q, %q): %v", tc.loopDev, tc.override, err)
			}
			if player.name != tc.wantName {
				t.Fatalf("player = %q, want %q", player.name, tc.wantName)
			}
			if !slices.Equal(player.args, tc.wantArgs) {
				t.Fatalf("args = %v, want %v", player.args, tc.wantArgs)
			}
		})
	}
}

func TestCamDialFailureCodes(t *testing.T) {
	cases := []struct {
		name     string
		err      error
		wantCode int
	}{
		{"expired without accept", errCamRequestExpired, 3},
		{"transport failure", errors.New("relay returned HTTP 500"), 4},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := camDialFailure(tc.err, "Lavander")
			if code := controlExitCode(err); code != tc.wantCode {
				t.Fatalf("exit code = %d, want %d (%v)", code, tc.wantCode, err)
			}
		})
	}
}

func TestCamPipeURL(t *testing.T) {
	cases := []struct {
		name      string
		relayAddr string
		sessionID string
		want      string
	}{
		{
			name:      "loopback relay",
			relayAddr: "127.0.0.1:9876",
			sessionID: "cafebabe",
			want:      "http://127.0.0.1:9876/pipe?transfer_id=cafebabe&role=receiver&flush=1",
		},
		{
			name:      "session id is escaped",
			relayAddr: "relay.local:9000",
			sessionID: "a b/c",
			want:      "http://relay.local:9000/pipe?transfer_id=a+b%2Fc&role=receiver&flush=1",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := camPipeURL(Config{RelayAddr: tc.relayAddr}, tc.sessionID); got != tc.want {
				t.Fatalf("pipe URL = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestCamStatsLine(t *testing.T) {
	cases := []struct {
		name    string
		frames  int
		payload int64
		elapsed time.Duration
		want    []string
	}{
		{"bitrate math", 300, 300000, 10 * time.Second, []string{"300 frames", "10.0s", "240 kbps"}},
		{"empty stream", 0, 0, 0, []string{"0 frames", "0.0s", "~0 kbps"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			line := camStatsLine(tc.frames, tc.payload, tc.elapsed)
			for _, want := range tc.want {
				if !strings.Contains(line, want) {
					t.Fatalf("stats line %q missing %q", line, want)
				}
			}
			if strings.Contains(line, "NaN") || strings.Contains(line, "+Inf") {
				t.Fatalf("stats line %q is not a finite number", line)
			}
		})
	}
}

func TestRunCamUsageValidation(t *testing.T) {
	cases := []struct {
		name     string
		target   string
		loopDev  string
		player   string
		wantCode int
	}{
		{"empty device", "", "", "", 2},
		{"loopback and player together", "phone-1", "/dev/video10", "mpv", 2},
		{"bad loopback device", "phone-1", "/dev/tty0", "", 2},
		{"missing player binary", "phone-1", "", "gaur-share-no-such-player", 4},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			// Every row fails before the requester session opens, so no
			// relay or token file is involved.
			err := runCam(Config{}, tc.target, tc.loopDev, tc.player)
			if err == nil {
				t.Fatalf("runCam accepted invalid arguments (%q, %q, %q)", tc.target, tc.loopDev, tc.player)
			}
			if code := controlExitCode(err); code != tc.wantCode {
				t.Fatalf("exit code = %d, want %d (%v)", code, tc.wantCode, err)
			}
		})
	}
}

// camTestPhone connects a phone peer to the fake relay and returns its link.
func camTestPhone(t *testing.T, relay *fakeRelay, id, name string) *wsClient {
	t.Helper()
	conn, err := connectWS(Config{RelayAddr: relay.URL, DeviceID: id, Name: name}, relay.relayToken())
	if err != nil {
		t.Fatalf("phone connect: %v", err)
	}
	client := &wsClient{conn: conn}
	t.Cleanup(func() { _ = client.Close() })
	return client
}

// camTestStdin points os.Stdin at /dev/null for the duration of f: runCam's
// raw-mode probe and its key loop both look at os.Stdin, and a test run must
// neither put the invoking terminal into raw mode nor read its keystrokes.
func camTestStdin(t *testing.T, f func()) {
	t.Helper()
	nullDev, err := os.Open(os.DevNull)
	if err != nil {
		t.Fatalf("open %s: %v", os.DevNull, err)
	}
	old := os.Stdin
	os.Stdin = nullDev
	defer func() {
		os.Stdin = old
		_ = nullDev.Close()
	}()
	f()
}

func TestRunCamStreamsFramesAndEndsCleanly(t *testing.T) {
	relay := newFakeRelay(t)
	tokenPath := writeClusterToken(t, t.TempDir(), relay.Token)
	_, peerKey := DeriveSubKeys(relay.Token)

	phone := camTestPhone(t, relay, "phone-1", "Lavander")

	// The phone answers the request by streaming three sealed frames into
	// the pipe: the csd, then two encoder buffers.
	csd := []byte{0x00, 0x00, 0x01, 0x40, 0x01, 0x01, 0x50}
	frameOne := bytes.Repeat([]byte{0x42}, 100)
	frameTwo := []byte("gaur-cam-frame-two")

	var sender sync.WaitGroup
	sender.Add(1)
	go func() {
		defer sender.Done()
		inner, ok := tryReadSealedInner(phone.conn, peerKey, "request", 10*time.Second)
		if !ok {
			t.Error("phone never received the camera request")
			return
		}
		if kind := envelopeString(inner, "kind"); kind != "camera" {
			t.Errorf("request kind = %q, want camera", kind)
		}
		if target := envelopeString(inner, "target_id"); target != "phone-1" {
			t.Errorf("target_id = %q, want phone-1", target)
		}
		if origin := envelopeString(inner, "origin_id"); origin != "desktop-1" {
			t.Errorf("origin_id = %q, want desktop-1", origin)
		}
		if name := envelopeString(inner, "requester_name"); name != "Laptop" {
			t.Errorf("requester_name = %q, want Laptop", name)
		}
		requestID := envelopeString(inner, "request_id")
		if !isRequestID(requestID) {
			t.Errorf("request_id = %q, not a pinned id", requestID)
		}
		requesterID := envelopeString(inner, "requester_id")
		if !strings.HasPrefix(requesterID, "reqdev-") {
			t.Errorf("requester_id = %q, want the ephemeral reqdev- identity", requesterID)
		}
		expiresTs := envelopeInt64(inner, "expires_ts")
		if until := time.Until(time.UnixMilli(expiresTs)); until < 110*time.Second || until > 130*time.Second {
			t.Errorf("expires_ts is %v away, want the pinned 120s window", until)
		}
		grant := envelopeString(inner, "grant")
		if !VerifyRequestGrant(peerKey, requestID, requesterID, "phone-1", expiresTs, grant) {
			t.Error("grant did not verify against the request it rode in")
		}

		// Both sides derive the session independently; the desktop's frames
		// only decrypt if the derivation matches.
		sessionID := deriveCamSessionID(peerKey, requestID)
		frameKey := camFrameKey(peerKey, sessionID)
		var body bytes.Buffer
		for i, plain := range [][]byte{csd, frameOne, frameTwo} {
			nonce, err := RandomBytes(camNonceSize)
			if err != nil {
				t.Errorf("nonce: %v", err)
				return
			}
			record, err := sealCamFrame(frameKey, nonce, plain, sessionID, int64(i))
			if err != nil {
				t.Errorf("seal frame %d: %v", i, err)
				return
			}
			body.Write(record)
		}
		pipeURL := fmt.Sprintf("http://%s/pipe?transfer_id=%s&role=sender", relay.URL, sessionID)
		req, err := http.NewRequest(http.MethodPost, pipeURL, &body)
		if err != nil {
			t.Errorf("sender request: %v", err)
			return
		}
		req.Header.Set("X-Gaur-Token", relay.relayToken())
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Errorf("sender POST: %v", err)
			return
		}
		_ = resp.Body.Close()
	}()

	cfg := Config{RelayAddr: relay.URL, TokenFile: tokenPath, DeviceID: "desktop-1", Name: "Laptop"}
	var runErr error
	var out string
	camTestStdin(t, func() {
		out = captureStdout(func() {
			runErr = runCam(cfg, "phone-1", "", "cat")
		})
	})
	sender.Wait()

	if runErr != nil {
		t.Fatalf("runCam: %v", runErr)
	}
	// cat received the decrypted elementary stream on stdin.
	for _, plain := range [][]byte{csd, frameOne, frameTwo} {
		if !strings.Contains(out, string(plain)) {
			t.Fatalf("player output missing a frame: %q", out)
		}
	}
	if !strings.Contains(out, "3 frames") || !strings.Contains(out, "kbps") {
		t.Fatalf("stats line missing from output: %q", out)
	}
}

func TestRunCamDeclinedExitsOne(t *testing.T) {
	relay := newFakeRelay(t)
	tokenPath := writeClusterToken(t, t.TempDir(), relay.Token)
	_, peerKey := DeriveSubKeys(relay.Token)

	phone := camTestPhone(t, relay, "phone-1", "Lavander")

	go func() {
		inner, ok := tryReadSealedInner(phone.conn, peerKey, "request", 10*time.Second)
		if !ok {
			t.Error("phone never received the camera request")
			return
		}
		decline := map[string]interface{}{
			"request_id": envelopeString(inner, "request_id"),
			"reason":     "declined",
			"ts":         time.Now().UnixMilli(),
		}
		envelope, err := sealEnvelope(peerKey, envelopeString(inner, "requester_id"), "request_declined", decline)
		if err != nil {
			t.Errorf("seal decline: %v", err)
			return
		}
		if err := phone.WriteJSON(envelope); err != nil {
			t.Errorf("send decline: %v", err)
		}
	}()

	cfg := Config{RelayAddr: relay.URL, TokenFile: tokenPath, DeviceID: "desktop-1", Name: "Laptop"}
	var runErr error
	camTestStdin(t, func() {
		_ = captureStdout(func() {
			runErr = runCam(cfg, "phone-1", "", "cat")
		})
	})
	if runErr == nil {
		t.Fatal("a declined request must be an error")
	}
	if code := controlExitCode(runErr); code != 1 {
		t.Fatalf("exit code = %d, want 1 for a decline (%v)", code, runErr)
	}
}

// TestCamInterruptSendsStopAndExits130 runs the real main() — dispatch,
// signal takeover, camExit and all — in a child process against the fake
// relay, interrupts it mid-stream, and pins the SIGINT contract: the sealed
// stop control reaches the phone, and only then does the process exit 130.
func TestCamInterruptSendsStopAndExits130(t *testing.T) {
	if os.Getenv("GAUR_CAM_MAIN") == "1" {
		// Child: become `gaur-share -relay ... cam --player cat phone-1`
		// and run the real main(). The testing package already parsed its
		// own flags on the global set; main() needs a fresh one.
		if home := os.Getenv("GAUR_CAM_HOME"); home != "" {
			_ = os.Setenv("HOME", home)
		}
		os.Args = []string{
			"gaur-share",
			"-relay", os.Getenv("GAUR_CAM_RELAY"),
			"-key-file", os.Getenv("GAUR_CAM_TOKEN"),
			"-device-id", "desktop-1",
			"-name", "Laptop",
			"cam", "--player", "cat", "phone-1",
		}
		flag.CommandLine = flag.NewFlagSet("gaur-share", flag.ExitOnError)
		main()
		os.Exit(0)
		return
	}

	relay := newFakeRelay(t)
	tokenPath := writeClusterToken(t, t.TempDir(), relay.Token)
	_, peerKey := DeriveSubKeys(relay.Token)
	phone := camTestPhone(t, relay, "phone-1", "Lavander")

	// The phone accepts by opening the sender side of the pipe and holding
	// it open, so the child stays mid-stream when the interrupt lands.
	type phoneResult struct {
		control map[string]interface{}
		err     error
	}
	phoneDone := make(chan phoneResult, 1)
	go func() {
		inner, ok := tryReadSealedInner(phone.conn, peerKey, "request", 10*time.Second)
		if !ok {
			phoneDone <- phoneResult{err: errors.New("phone never received the camera request")}
			return
		}
		requestID := envelopeString(inner, "request_id")
		sessionID := deriveCamSessionID(peerKey, requestID)
		frameKey := camFrameKey(peerKey, sessionID)

		pipeURL := fmt.Sprintf("http://%s/pipe?transfer_id=%s&role=sender", relay.URL, sessionID)
		pipeReader, pipeWriter := io.Pipe()
		req, err := http.NewRequest(http.MethodPost, pipeURL, pipeReader)
		if err != nil {
			phoneDone <- phoneResult{err: err}
			return
		}
		req.Header.Set("X-Gaur-Token", relay.relayToken())
		go func() {
			if resp, err := http.DefaultClient.Do(req); err == nil {
				_ = resp.Body.Close()
			}
		}()
		nonce, err := RandomBytes(camNonceSize)
		if err != nil {
			phoneDone <- phoneResult{err: err}
			return
		}
		record, err := sealCamFrame(frameKey, nonce, []byte{0x00, 0x00, 0x01, 0x40, 0x01}, sessionID, 0)
		if err != nil {
			phoneDone <- phoneResult{err: err}
			return
		}
		if _, err := pipeWriter.Write(record); err != nil {
			phoneDone <- phoneResult{err: err}
			return
		}
		// The pipe body stays open: no stream EOF, the child keeps
		// streaming until the interrupt tears it down.

		control, ok := tryReadSealedInner(phone.conn, peerKey, "cam_control", 10*time.Second)
		if !ok {
			phoneDone <- phoneResult{err: errors.New("phone never received a cam_control envelope")}
			return
		}
		phoneDone <- phoneResult{control: control}
		_ = pipeWriter.Close()
	}()

	cmd := exec.Command(os.Args[0], "-test.run=TestCamInterruptSendsStopAndExits130")
	cmd.Env = append(os.Environ(),
		"GAUR_CAM_MAIN=1",
		"GAUR_CAM_HOME="+t.TempDir(),
		"GAUR_CAM_RELAY="+relay.URL,
		"GAUR_CAM_TOKEN="+tokenPath,
	)
	var childOut bytes.Buffer
	cmd.Stdout = &childOut
	cmd.Stderr = &childOut
	if err := cmd.Start(); err != nil {
		t.Fatalf("child: %v", err)
	}
	defer func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	}()

	// Give the child time to accept and enter the stream, then interrupt.
	time.Sleep(500 * time.Millisecond)
	if err := cmd.Process.Signal(os.Interrupt); err != nil {
		t.Fatalf("signal: %v", err)
	}
	waitErr := cmd.Wait()

	result := <-phoneDone
	if result.err != nil {
		t.Fatalf("phone side: %v (child output %q)", result.err, childOut.String())
	}
	ctl, ok := parseCamControl(result.control)
	if !ok {
		t.Fatalf("stop control malformed: %v", result.control)
	}
	if ctl.action != "stop" {
		t.Fatalf("action = %q, want stop", ctl.action)
	}
	if !isRequestID(ctl.requestID) {
		t.Fatalf("request_id = %q, not a pinned id", ctl.requestID)
	}
	if want := deriveCamSessionID(peerKey, ctl.requestID); ctl.sessionID != want {
		t.Fatalf("session_id = %q, want the derived %q", ctl.sessionID, want)
	}
	if ctl.hasOn || ctl.hasTier {
		t.Fatalf("stop control carries extra fields: %v", result.control)
	}

	var exitErr *exec.ExitError
	if !errors.As(waitErr, &exitErr) {
		t.Fatalf("child exited with %v, want the interrupt exit (output %q)", waitErr, childOut.String())
	}
	if code := exitErr.ExitCode(); code != 130 {
		t.Fatalf("exit code = %d, want 130 (output %q)", code, childOut.String())
	}
}

// TestRunCamPlayerExitEndsCleanly pins the player-exit teardown: the phone
// holds the stream open, the player quits on its own, and the command must
// end with the stats line and a nil error — including when the frame pump is
// still quiet when the player's exit lands. A regression here deadlocks the
// second wait for the player's already-consumed exit report.
func TestRunCamPlayerExitEndsCleanly(t *testing.T) {
	relay := newFakeRelay(t)
	tokenPath := writeClusterToken(t, t.TempDir(), relay.Token)
	_, peerKey := DeriveSubKeys(relay.Token)
	phone := camTestPhone(t, relay, "phone-1", "Lavander")

	phoneDone := make(chan error, 1)
	var pipeWriter *io.PipeWriter
	go func() {
		inner, ok := tryReadSealedInner(phone.conn, peerKey, "request", 10*time.Second)
		if !ok {
			phoneDone <- errors.New("phone never received the camera request")
			return
		}
		sessionID := deriveCamSessionID(peerKey, envelopeString(inner, "request_id"))
		frameKey := camFrameKey(peerKey, sessionID)
		pipeReader, writer := io.Pipe()
		pipeWriter = writer
		pipeURL := fmt.Sprintf("http://%s/pipe?transfer_id=%s&role=sender", relay.URL, sessionID)
		req, err := http.NewRequest(http.MethodPost, pipeURL, pipeReader)
		if err != nil {
			phoneDone <- err
			return
		}
		req.Header.Set("X-Gaur-Token", relay.relayToken())
		go func() {
			if resp, err := http.DefaultClient.Do(req); err == nil {
				_ = resp.Body.Close()
			}
		}()
		nonce, err := RandomBytes(camNonceSize)
		if err != nil {
			phoneDone <- err
			return
		}
		record, err := sealCamFrame(frameKey, nonce, []byte{0x00, 0x00, 0x01, 0x40, 0x01}, sessionID, 0)
		if err != nil {
			phoneDone <- err
			return
		}
		if _, err := pipeWriter.Write(record); err != nil {
			phoneDone <- err
			return
		}
		// The stream stays open; "true" exits on its own without reading a
		// byte, so only the player's exit can end the command.
		phoneDone <- nil
	}()

	cfg := Config{RelayAddr: relay.URL, TokenFile: tokenPath, DeviceID: "desktop-1", Name: "Laptop"}
	var runErr error
	var out string
	camTestStdin(t, func() {
		out = captureStdout(func() {
			runErr = runCam(cfg, "phone-1", "", "true")
		})
	})
	// Let the sender POST finish before the relay's cleanup closes the
	// server out from under it.
	if pipeWriter != nil {
		_ = pipeWriter.Close()
	}
	if err := <-phoneDone; err != nil {
		t.Fatalf("phone side: %v", err)
	}
	if runErr != nil {
		t.Fatalf("runCam: %v", runErr)
	}
	if !strings.Contains(out, "Camera stream ended") {
		t.Fatalf("stats line missing from output: %q", out)
	}
}
