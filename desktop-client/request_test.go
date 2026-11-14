package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"testing"
	"time"
	"unicode"

	"github.com/gorilla/websocket"
)

func TestNewRequesterID(t *testing.T) {
	pattern := regexp.MustCompile(`^reqdev-[0-9]+-[0-9a-f]{8}$`)
	seen := make(map[string]struct{}, 64)
	for i := 0; i < 64; i++ {
		id, err := newRequesterID()
		if err != nil {
			t.Fatalf("newRequesterID draw %d: %v", i, err)
		}
		if !pattern.MatchString(id) {
			t.Fatalf("newRequesterID = %q, want reqdev-<pid>-<8 hex>", id)
		}
		seen[id] = struct{}{}
	}
	// The suffix carries 32 random bits: PID reuse alone must not collide
	// with another live requester. Only require that the generator is not
	// constant, so the test cannot flake on a legitimate collision.
	if len(seen) < 2 {
		t.Fatal("newRequesterID returned one id for 64 draws")
	}
}

func TestNewPendingRequestGrant(t *testing.T) {
	_, peerKey := DeriveSubKeys("request-test-token")
	now := time.UnixMilli(1730000000000)

	pending, err := newPendingRequest(peerKey, "reqdev-1-abcd", "phone-1", "files", now)
	if err != nil {
		t.Fatalf("newPendingRequest: %v", err)
	}
	if !regexp.MustCompile(`^req-[0-9a-f]{16}$`).MatchString(pending.RequestID) {
		t.Fatalf("request id = %q", pending.RequestID)
	}
	if want := now.Add(requestTTL).UnixMilli(); pending.ExpiresTs != want {
		t.Fatalf("expires_ts = %d, want %d", pending.ExpiresTs, want)
	}
	wantGrant := ComputeRequestGrant(peerKey, pending.RequestID, "reqdev-1-abcd", "phone-1", pending.ExpiresTs)
	if pending.Grant != wantGrant {
		t.Fatalf("grant = %s, want %s", pending.Grant, wantGrant)
	}
	if !VerifyRequestGrant(peerKey, pending.RequestID, pending.RequesterID, pending.TargetID, pending.ExpiresTs, pending.Grant) {
		t.Fatal("pending grant does not verify against its own record")
	}
}

func TestPendingRequestMatchesOffer(t *testing.T) {
	_, peerKey := DeriveSubKeys("request-test-token")
	now := time.UnixMilli(1730000000000)
	pending, err := newPendingRequest(peerKey, "reqdev-1-abcd", "phone-1", "files", now)
	if err != nil {
		t.Fatalf("newPendingRequest: %v", err)
	}
	valid := map[string]interface{}{
		"request_id": pending.RequestID,
		"sender_id":  pending.TargetID,
		"grant":      pending.Grant,
	}

	cases := []struct {
		name   string
		mutate func(map[string]interface{})
		want   bool
	}{
		{"valid", func(map[string]interface{}) {}, true},
		{"wrong request id", func(m map[string]interface{}) { m["request_id"] = "req-0000000000000000" }, false},
		{"wrong sender", func(m map[string]interface{}) { m["sender_id"] = "phone-2" }, false},
		{"wrong grant", func(m map[string]interface{}) { m["grant"] = strings.Repeat("00", 32) }, false},
		{"missing grant", func(m map[string]interface{}) { delete(m, "grant") }, false},
		// The grant commits to expires_ts, and the read deadline bounds the
		// exchange; the wall clock is deliberately not re-checked here, so an
		// offer that arrives as the deadline is crossed still matches.
		{"grant minted for an expired request still matches", func(map[string]interface{}) {}, true},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			inner := make(map[string]interface{}, len(valid))
			for k, v := range valid {
				inner[k] = v
			}
			tc.mutate(inner)
			if got := pending.matchesOffer(peerKey, inner); got != tc.want {
				t.Errorf("matchesOffer = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestParseRequestMeta(t *testing.T) {
	validGrant := strings.Repeat("ab", 32)
	cases := []struct {
		name    string
		raw     string
		wantErr bool
	}{
		{"valid", "req-0011223344556677:" + validGrant + ":1767225600000", false},
		{"whitespace is trimmed", " req-0011223344556677 : " + validGrant + " : 1767225600000 ", false},
		{"two parts", "req-0011223344556677:" + validGrant, true},
		{"four parts", "req:0011223344556677:" + validGrant + ":1767225600000", true},
		{"short grant", "req-0011223344556677:" + validGrant[:62] + ":1767225600000", true},
		{"long grant", "req-0011223344556677:" + validGrant + "ab:1767225600000", true},
		{"uppercase grant", "req-0011223344556677:" + strings.ToUpper(validGrant) + ":1767225600000", true},
		{"non-hex grant", "req-0011223344556677:" + strings.Repeat("zz", 32) + ":1767225600000", true},
		{"empty request id", ":" + validGrant + ":1767225600000", true},
		{"request id without prefix", "0011223344556677:" + validGrant + ":1767225600000", true},
		{"request id with a colon", "req-0011223344556677:x:" + validGrant + ":1767225600000", true},
		{"short request id", "req-00112233445566:" + validGrant + ":1767225600000", true},
		{"uppercase request id", "req-00112233445566AA:" + validGrant + ":1767225600000", true},
		{"non-numeric expiry", "req-0011223344556677:" + validGrant + ":soon", true},
		{"zero expiry", "req-0011223344556677:" + validGrant + ":0", true},
		{"negative expiry", "req-0011223344556677:" + validGrant + ":-1", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			meta, err := parseRequestMeta(tc.raw)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("parseRequestMeta(%q) succeeded with %+v, want error", tc.raw, meta)
				}
				return
			}
			if err != nil {
				t.Fatalf("parseRequestMeta(%q): %v", tc.raw, err)
			}
			if meta.RequestID != "req-0011223344556677" || meta.Grant != validGrant || meta.ExpiresTs != 1767225600000 {
				t.Fatalf("parsed meta = %+v", meta)
			}
		})
	}
}

func TestRequestArgsFromFlags(t *testing.T) {
	cases := []struct {
		name string
		in   []string
		want []string
	}{
		{"flag before target", []string{"--screenshot", "phone-1"}, []string{"--screenshot", "phone-1"}},
		{"flag after target", []string{"phone-1", "--screenshot"}, []string{"--screenshot", "phone-1"}},
		{"single dash after target", []string{"phone-1", "-screenshot"}, []string{"-screenshot", "phone-1"}},
		{"no flag", []string{"phone-1"}, []string{"phone-1"}},
		{"flag only", []string{"--screenshot"}, []string{"--screenshot"}},
		{"no args", nil, nil},
		{"trailing flag after extra arg", []string{"phone-1", "extra", "--screenshot"}, []string{"--screenshot", "phone-1", "extra"}},
		{"middle flag is not moved", []string{"phone-1", "--screenshot", "extra"}, []string{"phone-1", "--screenshot", "extra"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := requestArgsFromFlags(tc.in); !slices.Equal(got, tc.want) {
				t.Fatalf("requestArgsFromFlags(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}

func TestRequestAction(t *testing.T) {
	cases := map[string]string{
		"files":      "choose files",
		"screenshot": "take a screenshot",
	}
	for kind, want := range cases {
		if got := requestAction(kind); got != want {
			t.Fatalf("requestAction(%q) = %q, want %q", kind, got, want)
		}
	}
}

func TestRequestSendArgs(t *testing.T) {
	meta := "req-0011223344556677:" + strings.Repeat("ab", 32) + ":1767225600000"
	cfg := Config{
		RelayAddr: "127.0.0.1:9876",
		TokenFile: "/tmp/cluster.key",
		DeviceID:  "desktop-1",
		Name:      "Desktop",
	}
	files := []string{"/tmp/a b.txt", "/tmp/c.bin"}

	got := requestSendArgs(cfg, "reqdev-42-abcd", meta, files)
	want := []string{
		"-relay", "127.0.0.1:9876",
		"-key-file", "/tmp/cluster.key",
		"-device-id", "desktop-1",
		"-name", "Desktop",
		"send", "--to", "reqdev-42-abcd", "--request-meta", meta,
		"--", "/tmp/a b.txt", "/tmp/c.bin",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("requestSendArgs = %q, want %q", got, want)
	}
	// The meta and each path are single argv elements: exec runs the vector
	// directly, so a space must never split an argument.
	for _, arg := range got {
		if arg == meta {
			continue
		}
		if strings.Contains(arg, " ") && arg != "/tmp/a b.txt" {
			t.Fatalf("unexpected split argument %q in %q", arg, got)
		}
	}

	// Empty settings are omitted rather than passed as empty flag values.
	minimal := requestSendArgs(Config{}, "reqdev-1-abcd", meta, []string{"file.bin"})
	wantMinimal := []string{"send", "--to", "reqdev-1-abcd", "--request-meta", meta, "--", "file.bin"}
	if !slices.Equal(minimal, wantMinimal) {
		t.Fatalf("minimal args = %q, want %q", minimal, wantMinimal)
	}
}

func TestSplitPickerOutput(t *testing.T) {
	cases := []struct {
		name string
		raw  string
		want []string
	}{
		{"one per line", "/a/b.txt\n/c/d.txt\n", []string{"/a/b.txt", "/c/d.txt"}},
		{"crlf", "/a/b.txt\r\n/c/d.txt\r\n", []string{"/a/b.txt", "/c/d.txt"}},
		{"blank lines", "\n/a\n\n/b\n", []string{"/a", "/b"}},
		{"interior spaces", "/a/two words.txt\n", []string{"/a/two words.txt"}},
		{"leading space is preserved", " report.txt\n", []string{" report.txt"}},
		{"empty", "", []string{}},
		{"only newlines", "\n\n", []string{}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := splitPickerOutput(tc.raw); !slices.Equal(got, tc.want) {
				t.Fatalf("splitPickerOutput(%q) = %q, want %q", tc.raw, got, tc.want)
			}
		})
	}
}

// writeExecutable writes a tiny /bin/sh script for the picker tests.
func writeExecutable(t *testing.T, path, body string) {
	t.Helper()
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+body+"\n"), 0o755); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func TestPickFilesWithDesktop(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("desktop pickers are Linux-only")
	}

	t.Run("zenity is preferred", func(t *testing.T) {
		dir := t.TempDir()
		argvPath := filepath.Join(dir, "argv")
		t.Setenv("PATH", dir)
		t.Setenv("GAUR_PICKER_ARGV", argvPath)
		writeExecutable(t, filepath.Join(dir, "zenity"),
			"printf '%s\\n' \"$@\" > \"$GAUR_PICKER_ARGV\"\n"+
				"printf '%s\\n' '/tmp/one.txt' '/tmp/two words.txt'")

		files, err := pickFilesWithDesktop("Lavander")
		if err != nil {
			t.Fatalf("pickFilesWithDesktop: %v", err)
		}
		if want := []string{"/tmp/one.txt", "/tmp/two words.txt"}; !slices.Equal(files, want) {
			t.Fatalf("files = %q, want %q", files, want)
		}
		argv, err := os.ReadFile(argvPath)
		if err != nil {
			t.Fatalf("read argv: %v", err)
		}
		for _, wantArg := range []string{
			"--file-selection", "--multiple", "--separator=\n",
			"--title=gaurPN: Lavander requests files",
		} {
			if !strings.Contains(string(argv), wantArg+"\n") {
				t.Fatalf("zenity argv %q is missing %q", argv, wantArg)
			}
		}
	})

	t.Run("kdialog fallback", func(t *testing.T) {
		dir := t.TempDir()
		argvPath := filepath.Join(dir, "argv")
		t.Setenv("PATH", dir)
		t.Setenv("GAUR_PICKER_ARGV", argvPath)
		writeExecutable(t, filepath.Join(dir, "kdialog"),
			"printf '%s\\n' \"$@\" > \"$GAUR_PICKER_ARGV\"\n"+
				"printf '%s\\n' '/tmp/k1.txt' '/tmp/k2.txt'")

		files, err := pickFilesWithDesktop("Lavander")
		if err != nil {
			t.Fatalf("pickFilesWithDesktop: %v", err)
		}
		if want := []string{"/tmp/k1.txt", "/tmp/k2.txt"}; !slices.Equal(files, want) {
			t.Fatalf("files = %q, want %q", files, want)
		}
		argv, err := os.ReadFile(argvPath)
		if err != nil {
			t.Fatalf("read argv: %v", err)
		}
		for _, wantArg := range []string{"--getopenfilename", "--multiple", "--separate-output"} {
			if !strings.Contains(string(argv), wantArg+"\n") {
				t.Fatalf("kdialog argv %q is missing %q", argv, wantArg)
			}
		}
	})

	t.Run("neither picker", func(t *testing.T) {
		t.Setenv("PATH", t.TempDir())
		if _, err := pickFilesWithDesktop("Lavander"); !errors.Is(err, errPickerUnavailable) {
			t.Fatalf("error = %v, want errPickerUnavailable", err)
		}
	})

	t.Run("user cancel", func(t *testing.T) {
		dir := t.TempDir()
		t.Setenv("PATH", dir)
		t.Setenv("GAUR_PICKER_ARGV", filepath.Join(dir, "argv"))
		writeExecutable(t, filepath.Join(dir, "zenity"), "exit 1")
		if _, err := pickFilesWithDesktop("Lavander"); !errors.Is(err, errPickerCanceled) {
			t.Fatalf("error = %v, want errPickerCanceled", err)
		}
	})
}

func TestRunPickerCancelAndDeadline(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("uses /bin/false and /bin/sleep")
	}

	if _, err := runPicker(context.Background(), "/bin/false"); !errors.Is(err, errPickerCanceled) {
		t.Fatalf("cancel error = %v, want errPickerCanceled", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if _, err := runPicker(ctx, "/bin/sleep", "5"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("deadline error = %v, want context.DeadlineExceeded", err)
	}
}

// --- fake-relay helpers ---

func readSealedInner(t *testing.T, conn *websocket.Conn, peerKey []byte, wantKind string, timeout time.Duration) map[string]interface{} {
	t.Helper()
	inner, ok := tryReadSealedInner(conn, peerKey, wantKind, timeout)
	if !ok {
		t.Fatalf("no sealed %s within %v", wantKind, timeout)
	}
	return inner
}

// tryReadSealedInner reads until a sealed envelope of wantKind arrives. It
// returns false on any read error, including the deadline. A timed-out
// gorilla connection is not reusable afterwards, so callers only use this
// for a final negative assertion.
func tryReadSealedInner(conn *websocket.Conn, peerKey []byte, wantKind string, timeout time.Duration) (map[string]interface{}, bool) {
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	defer conn.SetReadDeadline(time.Time{})
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			return nil, false
		}
		var envelope map[string]interface{}
		if json.Unmarshal(data, &envelope) != nil {
			continue
		}
		if envelopeString(envelope, "type") != "sealed" {
			continue
		}
		kind, inner, err := openSealed(peerKey, envelope)
		if err != nil {
			continue
		}
		if kind == wantKind {
			return inner, true
		}
	}
}

type requestTargetFixture struct {
	relay       *fakeRelay
	cfg         Config
	target      *wsClient
	requester   *websocket.Conn
	requesterID string
	peerKey     []byte
}

func newRequestTargetFixture(t *testing.T) *requestTargetFixture {
	t.Helper()
	return newRequestTargetFixtureWithRequester(t, "reqdev-4242-abcd")
}

// newRequestTargetFixtureWithRequester is the fixture with an explicit
// requester connection id. Trust-fallback tests need the decline to route to
// the id under test, so the requester must connect under exactly that id.
func newRequestTargetFixtureWithRequester(t *testing.T, requesterID string) *requestTargetFixture {
	t.Helper()
	// Isolate the trust list and config.json: onlyTrustedEnabled also reads
	// the real user's config, which must not decide these tests.
	t.Setenv("HOME", t.TempDir())
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)
	dir := t.TempDir()
	cfg := Config{
		RelayAddr: f.URL,
		DeviceID:  "desktop-1",
		Name:      "Desktop",
		InboxDir:  filepath.Join(dir, "inbox"),
		PeersFile: filepath.Join(dir, "peers.json"),
		Clipboard: false,
	}
	targetConn, err := connectWS(cfg, relayToken)
	if err != nil {
		t.Fatalf("target connect: %v", err)
	}
	t.Cleanup(func() { _ = targetConn.Close() })

	requesterCfg := Config{RelayAddr: f.URL, DeviceID: requesterID, Name: "Requestor"}
	requesterConn, err := connectWS(requesterCfg, relayToken)
	if err != nil {
		t.Fatalf("requester connect: %v", err)
	}
	t.Cleanup(func() { _ = requesterConn.Close() })

	return &requestTargetFixture{
		relay:       f,
		cfg:         cfg,
		target:      &wsClient{conn: targetConn},
		requester:   requesterConn,
		requesterID: requesterID,
		peerKey:     peerKey,
	}
}

func buildRequestInner(t *testing.T, peerKey []byte, requesterID, targetID, kind string, expiresTs int64) map[string]interface{} {
	t.Helper()
	requestID, err := NewRequestID()
	if err != nil {
		t.Fatalf("NewRequestID: %v", err)
	}
	return map[string]interface{}{
		"request_id":     requestID,
		"requester_id":   requesterID,
		"requester_name": "Requestor",
		"target_id":      targetID,
		"kind":           kind,
		"expires_ts":     expiresTs,
		"grant":          ComputeRequestGrant(peerKey, requestID, requesterID, targetID, expiresTs),
		"ts":             time.Now().UnixMilli(),
	}
}

// --- daemon target ---

func TestHandleIncomingRequestDeclines(t *testing.T) {
	cases := []struct {
		name        string
		kind        string
		expiresIn   time.Duration
		picker      func(string) ([]string, error)
		onlyTrusted bool
		wantReason  string
	}{
		{
			name: "user cancels", kind: "files", expiresIn: 5 * time.Minute,
			picker:     func(string) ([]string, error) { return nil, errPickerCanceled },
			wantReason: "declined",
		},
		{
			name: "picker unavailable", kind: "files", expiresIn: 5 * time.Minute,
			picker:     func(string) ([]string, error) { return nil, errPickerUnavailable },
			wantReason: "picker_unavailable",
		},
		{
			name: "screenshot unsupported", kind: "screenshot", expiresIn: 5 * time.Minute,
			wantReason: "unsupported",
		},
		{
			name: "expired", kind: "files", expiresIn: -time.Minute,
			wantReason: "expired",
		},
		{
			name: "untrusted with only-trusted", kind: "files", expiresIn: 5 * time.Minute,
			onlyTrusted: true, wantReason: "not_allowed",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fx := newRequestTargetFixture(t)
			replayGuard = NewReplayGuard()
			requestBusy.Store(false)
			defer requestBusy.Store(false)

			oldPicker := pickRequestFiles
			if tc.picker != nil {
				pickRequestFiles = tc.picker
			} else {
				pickRequestFiles = func(string) ([]string, error) { return nil, errPickerCanceled }
			}
			defer func() { pickRequestFiles = oldPicker }()

			cfg := fx.cfg
			cfg.OnlyTrusted = tc.onlyTrusted
			if tc.onlyTrusted {
				t.Setenv("HOME", t.TempDir())
			}
			inner := buildRequestInner(
				t, fx.peerKey, fx.requesterID, cfg.DeviceID, tc.kind,
				time.Now().Add(tc.expiresIn).UnixMilli(),
			)
			handleIncomingRequest(cfg, fx.peerKey, fx.target, inner)

			decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
			if got := envelopeString(decline, "request_id"); got != envelopeString(inner, "request_id") {
				t.Fatalf("decline request_id = %q, want %q", got, envelopeString(inner, "request_id"))
			}
			if got := envelopeString(decline, "reason"); got != tc.wantReason {
				t.Fatalf("decline reason = %q, want %q", got, tc.wantReason)
			}
		})
	}
}

// TestHandleIncomingRequestTrustUsesOriginID pins the v3.2 trust fallback: a
// desktop requester connects under an ephemeral reqdev- id, so the gate must
// judge the stable origin_id it carries, and fall back to requester_id only
// when the field is absent. The picker opening (and declining "declined") is
// the proof the trust gate let the request through.
func TestHandleIncomingRequestTrustUsesOriginID(t *testing.T) {
	cases := []struct {
		name       string
		requester  string
		origin     string // "" means the field is absent
		trusted    []string
		wantReason string
	}{
		{
			name: "origin trusted", requester: "reqdev-4242-abcd", origin: "laptop-1",
			trusted: []string{"laptop-1"}, wantReason: "declined",
		},
		{
			name: "neither trusted", requester: "reqdev-4242-abcd", origin: "laptop-1",
			wantReason: "not_allowed",
		},
		{
			name: "requester trusted when origin absent", requester: "laptop-1",
			trusted: []string{"laptop-1"}, wantReason: "declined",
		},
		{
			name: "requester untrusted when origin absent", requester: "laptop-1",
			wantReason: "not_allowed",
		},
		{
			name: "origin wins over a trusted requester", requester: "laptop-1", origin: "stranger-1",
			trusted: []string{"laptop-1"}, wantReason: "not_allowed",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fx := newRequestTargetFixtureWithRequester(t, tc.requester)
			replayGuard = NewReplayGuard()
			requestBusy.Store(false)
			defer requestBusy.Store(false)

			for _, id := range tc.trusted {
				if err := trustDevice(id, id); err != nil {
					t.Fatalf("trustDevice(%q): %v", id, err)
				}
			}

			picked := false
			oldPicker := pickRequestFiles
			pickRequestFiles = func(string) ([]string, error) {
				picked = true
				return nil, errPickerCanceled
			}
			defer func() { pickRequestFiles = oldPicker }()

			cfg := fx.cfg
			cfg.OnlyTrusted = true
			inner := buildRequestInner(
				t, fx.peerKey, tc.requester, cfg.DeviceID, "files",
				time.Now().Add(5*time.Minute).UnixMilli(),
			)
			if tc.origin != "" {
				inner["origin_id"] = tc.origin
			}
			handleIncomingRequest(cfg, fx.peerKey, fx.target, inner)

			decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
			if got := envelopeString(decline, "reason"); got != tc.wantReason {
				t.Fatalf("decline reason = %q, want %q", got, tc.wantReason)
			}
			if wantPicked := tc.wantReason != "not_allowed"; picked != wantPicked {
				t.Fatalf("picker opened = %v, want %v", picked, wantPicked)
			}
		})
	}
}

// TestHandleIncomingRequestSanitizesRequesterName pins the display boundary:
// the peer-supplied name is shown in the picker title and log lines, so line
// breaks and control characters must never reach it.
func TestHandleIncomingRequestSanitizesRequesterName(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	var pickerName string
	oldPicker := pickRequestFiles
	pickRequestFiles = func(name string) ([]string, error) {
		pickerName = name
		return nil, errPickerCanceled
	}
	defer func() { pickRequestFiles = oldPicker }()

	inner := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	inner["requester_name"] = "Mallory\n[REQUEST] accepted req-0011223344556677 from trusted\r\x00\x1b[31m\u202e\u200b"
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)

	if strings.ContainsAny(pickerName, "\n\r\x00\x1b") {
		t.Fatalf("picker name still carries control characters: %q", pickerName)
	}
	for _, r := range pickerName {
		if unicode.Is(unicode.Cf, r) {
			t.Fatalf("picker name still carries a format character: %q", pickerName)
		}
	}
	// The label carries the stable id alongside the name so two devices that
	// share a display name stay distinguishable.
	want := "Mallory [REQUEST] accepted req-0011223344556677 from trusted   [31m (reqdev-4…)"
	if pickerName != want {
		t.Fatalf("picker name = %q, want %q", pickerName, want)
	}
}

func TestPeerLabel(t *testing.T) {
	cases := []struct {
		name string
		in   string
		id   string
		want string
	}{
		{"name and id", "Lilac", "lilac-76ee-1234", "Lilac (lilac-76…)"},
		{"short id is not truncated", "Lilac", "lilac", "Lilac (lilac)"},
		{"empty name falls back", "", "lilac-76ee", "unknown device (lilac-76…)"},
		{"name equals id", "lilac-1", "lilac-1", "lilac-1"},
		{"empty id shows only the name", "Lilac", "", "Lilac"},
		{"both empty", "", "", "unknown device"},
		{"name is sanitized", "Li\nlac\x1b", "id-1", "Li lac (id-1)"},
		{"id is sanitized", "Lilac", "id\n1", "Lilac (id 1)"},
		{"bidi override is dropped", "Li\u202elac", "id-1", "Lilac (id-1)"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := peerLabel(tc.in, tc.id); got != tc.want {
				t.Fatalf("peerLabel(%q, %q) = %q, want %q", tc.in, tc.id, got, tc.want)
			}
		})
	}
}

func TestIsValidDeviceID(t *testing.T) {
	cases := []struct {
		id   string
		want bool
	}{
		{"lilac-76ee", true},
		{"reqdev-4242-abcd", true},
		{"A.b_c-9", true},
		{"a,b", false}, // the splitTargets injection shape
		{"a b", false},
		{"a/b", false},
		{"a\\b", false},
		{"", false},
		{strings.Repeat("a", 64), true},
		{strings.Repeat("a", 65), false},
		{"lilac\n76ee", false},
		{"lilac\u202e76ee", false},
	}
	for _, tc := range cases {
		if got := isValidDeviceID(tc.id); got != tc.want {
			t.Fatalf("isValidDeviceID(%q) = %v, want %v", tc.id, got, tc.want)
		}
	}
}

func TestSingleLineDisplayName(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"plain", "Lavander", "Lavander"},
		{"trimmed", "  Lavander  ", "Lavander"},
		{"newlines", "a\nb\r\nc", "a b  c"},
		{"tab and nul", "a\tb\x00c", "a b c"},
		{"delete and c1", "a\x7fb\u009bc", "a b c"},
		{"unicode separators", "a\u2028b\u2029c", "a b c"},
		{"unicode preserved", "Lavandér 📱", "Lavandér 📱"},
		{"format characters dropped", "a\u200bb\u202ec\ufeffd", "abcd"},
		{"zero-width joiner dropped", "👨\u200d👩", "👨👩"},
		{"only controls", "\n\r\t", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := singleLineDisplayName(tc.in); got != tc.want {
				t.Fatalf("singleLineDisplayName(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}

func TestHandleIncomingRequestBusy(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	started := make(chan struct{})
	release := make(chan struct{})
	oldPicker := pickRequestFiles
	pickRequestFiles = func(string) ([]string, error) {
		close(started)
		<-release
		return []string{"/tmp/picked.bin"}, nil
	}
	defer func() { pickRequestFiles = oldPicker }()

	var sentMeta string
	var sentFiles []string
	oldSend := startRequestSend
	startRequestSend = func(cfg Config, requesterID, meta string, files []string) error {
		sentMeta = meta
		sentFiles = append([]string(nil), files...)
		return nil
	}
	defer func() { startRequestSend = oldSend }()

	first := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	done := make(chan struct{})
	go func() {
		handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, first)
		close(done)
	}()
	<-started

	second := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, second)
	decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
	if got := envelopeString(decline, "reason"); got != "busy" {
		t.Fatalf("second request reason = %q, want busy", got)
	}

	close(release)
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("first request handler did not finish")
	}

	wantMeta := fmt.Sprintf("%s:%s:%d",
		envelopeString(first, "request_id"),
		envelopeString(first, "grant"),
		envelopeInt64(first, "expires_ts"),
	)
	if sentMeta != wantMeta {
		t.Fatalf("send meta = %q, want %q", sentMeta, wantMeta)
	}
	if len(sentFiles) != 1 || sentFiles[0] != "/tmp/picked.bin" {
		t.Fatalf("send files = %v", sentFiles)
	}
}

func TestHandleIncomingRequestDropsDuplicate(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	oldPicker := pickRequestFiles
	pickRequestFiles = func(string) ([]string, error) { return nil, errPickerCanceled }
	defer func() { pickRequestFiles = oldPicker }()

	inner := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)
	decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
	if got := envelopeString(decline, "reason"); got != "declined" {
		t.Fatalf("first request reason = %q, want declined", got)
	}

	// The duplicate request id is dropped silently: no second decline.
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)
	if _, ok := tryReadSealedInner(fx.requester, fx.peerKey, "request_declined", 300*time.Millisecond); ok {
		t.Fatal("duplicate request must not produce a decline")
	}
}

func TestHandleIncomingRequestDropsMalformed(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(map[string]interface{})
	}{
		{"grant is not a grant", func(m map[string]interface{}) { m["grant"] = "not-a-grant" }},
		{"short grant", func(m map[string]interface{}) {
			m["grant"] = envelopeString(m, "grant")[:62]
		}},
		{"uppercase grant", func(m map[string]interface{}) {
			m["grant"] = strings.ToUpper(envelopeString(m, "grant"))
		}},
		{"non-hex grant", func(m map[string]interface{}) { m["grant"] = strings.Repeat("zz", 32) }},
		{"missing grant", func(m map[string]interface{}) { delete(m, "grant") }},
		{"request id without prefix", func(m map[string]interface{}) { m["request_id"] = "0011223344556677" }},
		{"request id with colon", func(m map[string]interface{}) { m["request_id"] = "req-0011223344556677:x" }},
		{"missing request id", func(m map[string]interface{}) { delete(m, "request_id") }},
		{"missing requester id", func(m map[string]interface{}) { delete(m, "requester_id") }},
		{"missing target id", func(m map[string]interface{}) { delete(m, "target_id") }},
		{"zero expiry", func(m map[string]interface{}) { m["expires_ts"] = int64(0) }},
		{"negative expiry", func(m map[string]interface{}) { m["expires_ts"] = int64(-1) }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fx := newRequestTargetFixture(t)
			replayGuard = NewReplayGuard()
			requestBusy.Store(false)
			defer requestBusy.Store(false)

			oldPicker := pickRequestFiles
			pickRequestFiles = func(string) ([]string, error) {
				t.Fatal("picker must not open for a malformed request")
				return nil, nil
			}
			defer func() { pickRequestFiles = oldPicker }()

			inner := buildRequestInner(
				t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
				time.Now().Add(5*time.Minute).UnixMilli(),
			)
			tc.mutate(inner)
			handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)

			// A malformed request is dropped, not declined: the requester
			// never had a valid grant to echo, so it cannot auto-accept.
			if _, ok := tryReadSealedInner(fx.requester, fx.peerKey, "request_declined", 100*time.Millisecond); ok {
				t.Fatal("malformed request must be dropped silently")
			}
		})
	}
}

func TestHandleIncomingRequestDropsSelfRequest(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	oldPicker := pickRequestFiles
	pickRequestFiles = func(string) ([]string, error) {
		t.Fatal("picker must not open for a self-addressed request")
		return nil, nil
	}
	defer func() { pickRequestFiles = oldPicker }()

	inner := buildRequestInner(
		t, fx.peerKey, fx.cfg.DeviceID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)

	if _, ok := tryReadSealedInner(fx.requester, fx.peerKey, "request_declined", 100*time.Millisecond); ok {
		t.Fatal("self-addressed request must be dropped silently")
	}
}

func TestHandleIncomingRequestDropsSelfOrigin(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	oldPicker := pickRequestFiles
	pickRequestFiles = func(string) ([]string, error) {
		t.Fatal("picker must not open for a self-addressed request")
		return nil, nil
	}
	defer func() { pickRequestFiles = oldPicker }()

	// The ephemeral requester id is not this device, but origin_id claims it
	// is: the trust gate would treat that as a self-request, so drop it.
	inner := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	inner["origin_id"] = fx.cfg.DeviceID
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)

	if _, ok := tryReadSealedInner(fx.requester, fx.peerKey, "request_declined", 100*time.Millisecond); ok {
		t.Fatal("self-addressed origin must be dropped silently")
	}
}

func TestHandleIncomingRequestExpiresDuringPicker(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	base := time.Now()
	calls := 0
	oldNow := requestNow
	requestNow = func() time.Time {
		calls++
		if calls == 1 {
			return base
		}
		return base.Add(10 * time.Minute)
	}
	defer func() { requestNow = oldNow }()

	picked := false
	oldPicker := pickRequestFiles
	pickRequestFiles = func(string) ([]string, error) {
		picked = true
		return []string{"/tmp/picked.bin"}, nil
	}
	defer func() { pickRequestFiles = oldPicker }()

	sent := false
	oldSend := startRequestSend
	startRequestSend = func(Config, string, string, []string) error {
		sent = true
		return nil
	}
	defer func() { startRequestSend = oldSend }()

	inner := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		base.Add(5*time.Minute).UnixMilli(),
	)
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)

	decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
	if got := envelopeString(decline, "reason"); got != "expired" {
		t.Fatalf("decline reason = %q, want expired", got)
	}
	if !picked {
		t.Fatal("picker should have been opened before the request expired")
	}
	if sent {
		t.Fatal("an expired selection must not spawn a send subprocess")
	}
}

func TestSealedRequestDispatch(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	oldPicker := pickRequestFiles
	pickRequestFiles = func(string) ([]string, error) { return nil, errPickerCanceled }
	defer func() { pickRequestFiles = oldPicker }()

	inner := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	envelope, err := sealEnvelope(fx.peerKey, fx.cfg.DeviceID, "request", inner)
	if err != nil {
		t.Fatalf("seal request: %v", err)
	}

	// A request has no sender_id; the sealed dispatcher must route it before
	// the generic sender gate, or it would be dropped before the handler.
	handleIncomingSealed(fx.cfg, "", fx.peerKey, fx.target, nil, nil, envelope)

	decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
	if got := envelopeString(decline, "reason"); got != "declined" {
		t.Fatalf("dispatch decline reason = %q, want declined", got)
	}
}

// --- requester command ---

func TestRequestCommandReceivesOffer(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)
	dir := t.TempDir()
	inbox := filepath.Join(dir, "inbox")

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("phone connect: %v", err)
	}
	defer phoneConn.Close()

	payload := bytes.Repeat([]byte("r"), 4096)
	src := filepath.Join(dir, "requested.bin")
	if err := os.WriteFile(src, payload, 0o600); err != nil {
		t.Fatal(err)
	}
	digest, err := HashFileChunks(src, ChunkSize)
	if err != nil {
		t.Fatal(err)
	}
	transferID := DeriveTransferID(peerKey, digest.FileHash)
	sessionID := DeriveSessionID(peerKey, transferID)
	fileKey := DeriveFileKey(peerKey, transferID)
	auth := ChunkAuth{RelayToken: relayToken, DeviceID: "phone-1", PeerKey: peerKey}
	stageCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	for i := 0; i < digest.ChunkCount; i++ {
		stageV3Chunk(t, f, stageCfg, auth, payload, digest, fileKey, transferID, sessionID, i)
	}

	phoneDone := make(chan error, 1)
	go func() {
		_ = phoneConn.SetReadDeadline(time.Now().Add(10 * time.Second))
		for {
			_, data, err := phoneConn.ReadMessage()
			if err != nil {
				phoneDone <- err
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(data, &envelope) != nil {
				continue
			}
			if envelopeString(envelope, "type") != "sealed" {
				continue
			}
			kind, inner, err := openSealed(peerKey, envelope)
			if err != nil || kind != "request" {
				continue
			}
			requestID := envelopeString(inner, "request_id")
			requesterID := envelopeString(inner, "requester_id")
			expiresTs := envelopeInt64(inner, "expires_ts")
			// The request travels under an ephemeral connection id, but the
			// stable device id must ride along as origin_id for the target's
			// trust gate.
			if originID := envelopeString(inner, "origin_id"); originID != "desktop-1" {
				phoneDone <- fmt.Errorf("origin_id = %q, want desktop-1", originID)
				return
			}
			if !strings.HasPrefix(requesterID, "reqdev-") {
				phoneDone <- fmt.Errorf("requester_id = %q, want an ephemeral reqdev- id", requesterID)
				return
			}
			if envelopeString(inner, "target_id") != "phone-1" {
				phoneDone <- fmt.Errorf("target_id = %q", envelopeString(inner, "target_id"))
				return
			}
			if envelopeString(inner, "requester_name") != "Desktop" {
				phoneDone <- fmt.Errorf("requester_name = %q", envelopeString(inner, "requester_name"))
				return
			}
			if envelopeString(inner, "kind") != "files" {
				phoneDone <- fmt.Errorf("kind = %q", envelopeString(inner, "kind"))
				return
			}
			if !VerifyRequestGrant(peerKey, requestID, requesterID, "phone-1", expiresTs, envelopeString(inner, "grant")) {
				phoneDone <- fmt.Errorf("grant does not verify")
				return
			}

			offerInner := map[string]interface{}{
				"sender_id":    "phone-1",
				"sender_name":  "Lavander",
				"transfer_id":  transferID,
				"session_id":   sessionID,
				"file_name":    "requested.bin",
				"file_size":    digest.Size,
				"chunk_size":   ChunkSize,
				"chunk_count":  digest.ChunkCount,
				"file_hash":    digest.FileHash,
				"chunk_hashes": digest.ChunkHashes,
				"request_id":   requestID,
				"grant":        envelopeString(inner, "grant"),
			}
			offer, err := sealEnvelope(peerKey, requesterID, "offer", offerInner)
			if err != nil {
				phoneDone <- err
				return
			}
			phoneDone <- phoneConn.WriteJSON(offer)
			return
		}
	}()

	reqCfg := Config{
		RelayAddr: f.URL,
		TokenFile: writeClusterToken(t, dir, f.Token),
		DeviceID:  "desktop-1",
		Name:      "Desktop",
		InboxDir:  inbox,
	}
	if err := runRequest(reqCfg, "phone-1", false); err != nil {
		t.Fatalf("runRequest: %v", err)
	}
	if err := <-phoneDone; err != nil {
		t.Fatalf("phone: %v", err)
	}

	got, err := os.ReadFile(filepath.Join(inbox, "requested.bin"))
	if err != nil {
		t.Fatalf("received file missing: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("received file does not match the original")
	}
}

func TestRequestCommandDeclined(t *testing.T) {
	cases := []struct {
		name       string
		wireReason string
		wantErr    string
	}{
		{"pinned reason", "busy", "Declined (busy)"},
		{"unknown reason", "something-else", "Declined (declined)"},
		{"reason with a newline", "declined\n[REQUEST] accepted req-1", "Declined (declined)"},
		{"reason with ansi", "\x1b[31mexpired", "Declined (declined)"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			f := newFakeRelay(t)
			relayToken, peerKey := DeriveSubKeys(f.Token)
			dir := t.TempDir()

			phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
			phoneConn, err := connectWS(phoneCfg, relayToken)
			if err != nil {
				t.Fatalf("phone connect: %v", err)
			}
			defer phoneConn.Close()

			phoneDone := make(chan error, 1)
			go func() {
				_ = phoneConn.SetReadDeadline(time.Now().Add(10 * time.Second))
				for {
					_, data, err := phoneConn.ReadMessage()
					if err != nil {
						phoneDone <- err
						return
					}
					var envelope map[string]interface{}
					if json.Unmarshal(data, &envelope) != nil {
						continue
					}
					if envelopeString(envelope, "type") != "sealed" {
						continue
					}
					kind, inner, err := openSealed(peerKey, envelope)
					if err != nil || kind != "request" {
						continue
					}
					decline, err := sealEnvelope(peerKey, envelopeString(inner, "requester_id"), "request_declined", map[string]interface{}{
						"request_id": envelopeString(inner, "request_id"),
						"reason":     tc.wireReason,
						"ts":         time.Now().UnixMilli(),
					})
					if err != nil {
						phoneDone <- err
						return
					}
					phoneDone <- phoneConn.WriteJSON(decline)
					return
				}
			}()

			reqCfg := Config{
				RelayAddr: f.URL,
				TokenFile: writeClusterToken(t, dir, f.Token),
				DeviceID:  "desktop-1",
				Name:      "Desktop",
				InboxDir:  filepath.Join(dir, "inbox"),
			}
			err = runRequest(reqCfg, "phone-1", false)
			if err == nil || err.Error() != tc.wantErr {
				t.Fatalf("runRequest error = %v, want %s", err, tc.wantErr)
			}
			if phoneErr := <-phoneDone; phoneErr != nil {
				t.Fatalf("phone: %v", phoneErr)
			}
		})
	}
}

func TestRequestCommandTimesOut(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, _ := DeriveSubKeys(f.Token)
	dir := t.TempDir()

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("phone connect: %v", err)
	}
	defer phoneConn.Close()

	oldTimeout := requestTimeout
	requestTimeout = 100 * time.Millisecond
	defer func() { requestTimeout = oldTimeout }()

	reqCfg := Config{
		RelayAddr: f.URL,
		TokenFile: writeClusterToken(t, dir, f.Token),
		DeviceID:  "desktop-1",
		Name:      "Desktop",
		InboxDir:  filepath.Join(dir, "inbox"),
	}
	err = runRequest(reqCfg, "phone-1", false)
	if err == nil || err.Error() != "Request timed out" {
		t.Fatalf("runRequest error = %v, want Request timed out", err)
	}
}

func TestRequestCommandRelayDrop(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)
	dir := t.TempDir()

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("phone connect: %v", err)
	}
	defer phoneConn.Close()

	requestSeen := make(chan string, 1)
	go func() {
		_ = phoneConn.SetReadDeadline(time.Now().Add(10 * time.Second))
		for {
			_, data, err := phoneConn.ReadMessage()
			if err != nil {
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(data, &envelope) != nil {
				continue
			}
			if envelopeString(envelope, "type") != "sealed" {
				continue
			}
			kind, inner, err := openSealed(peerKey, envelope)
			if err != nil || kind != "request" {
				continue
			}
			requestSeen <- envelopeString(inner, "requester_id")
			return
		}
	}()
	go func() {
		requesterID := <-requestSeen
		f.killConn(requesterID)
	}()

	reqCfg := Config{
		RelayAddr: f.URL,
		TokenFile: writeClusterToken(t, dir, f.Token),
		DeviceID:  "desktop-1",
		Name:      "Desktop",
		InboxDir:  filepath.Join(dir, "inbox"),
	}
	err = runRequest(reqCfg, "phone-1", false)
	if err == nil || !strings.Contains(err.Error(), "Relay connection lost") {
		t.Fatalf("runRequest error = %v, want Relay connection lost", err)
	}
}

func TestDiscoverTargetExcludesRequesterIdentity(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, _ := DeriveSubKeys(f.Token)

	targetCfg := Config{RelayAddr: f.URL, DeviceID: "desktop-1", Name: "Desktop"}
	targetConn, err := connectWS(targetCfg, relayToken)
	if err != nil {
		t.Fatalf("target connect: %v", err)
	}
	defer targetConn.Close()

	requesterID := "reqdev-9999-abcd"
	requesterCfg := Config{RelayAddr: f.URL, DeviceID: requesterID, Name: "Desktop"}
	requesterConn, err := connectWS(requesterCfg, relayToken)
	if err != nil {
		t.Fatalf("requester connect: %v", err)
	}
	defer requesterConn.Close()

	// Both peers are named Desktop. Without the exclusion the name query is
	// ambiguous; with it, the daemon is the only match.
	id, name, err := discoverTargetExcluding(requesterConn, "Desktop", requesterID)
	if err != nil {
		t.Fatalf("discoverTargetExcluding: %v", err)
	}
	if id != "desktop-1" || name != "Desktop" {
		t.Fatalf("resolved %q/%q, want desktop-1/Desktop", id, name)
	}
}

func TestRunSendV3IncludesRequestMeta(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("phone connect: %v", err)
	}
	defer phoneConn.Close()

	dir := t.TempDir()
	src := filepath.Join(dir, "meta.bin")
	if err := os.WriteFile(src, bytes.Repeat([]byte("m"), 1024), 0o600); err != nil {
		t.Fatal(err)
	}
	meta := &requestMeta{
		RequestID: "req-0011223344556677",
		Grant:     strings.Repeat("ab", 32),
		ExpiresTs: 1767225600000,
	}
	sendCfg := Config{
		RelayAddr:   f.URL,
		TokenFile:   writeClusterToken(t, dir, f.Token),
		DeviceID:    "desktop-1",
		Name:        "Desktop",
		RequestMeta: meta,
	}
	if err := runSendV3(sendCfg, []string{"phone-1"}, []string{src}, false, false); err != nil {
		t.Fatalf("runSendV3: %v", err)
	}

	inner := readSealedInner(t, phoneConn, peerKey, "offer", 5*time.Second)
	if got := envelopeString(inner, "request_id"); got != meta.RequestID {
		t.Fatalf("offer request_id = %q, want %q", got, meta.RequestID)
	}
	if got := envelopeString(inner, "grant"); got != meta.Grant {
		t.Fatalf("offer grant = %q, want %q", got, meta.Grant)
	}
	// The payload must claim the real device id, not the "-out" connection
	// id the subprocess uses.
	if got := envelopeString(inner, "sender_id"); got != "desktop-1" {
		t.Fatalf("offer sender_id = %q, want desktop-1", got)
	}
}

// TestRequestBusyGuardSerializes pins the package-level slot itself, without
// a relay: the second acquire fails until the first release.
func TestRequestBusyGuardSerializes(t *testing.T) {
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	if !acquireRequestSlot() {
		t.Fatal("first acquire must succeed")
	}
	if acquireRequestSlot() {
		t.Fatal("second acquire must fail while the slot is held")
	}
	releaseRequestSlot()
	if !acquireRequestSlot() {
		t.Fatal("acquire after release must succeed")
	}
	releaseRequestSlot()
}

// --- D2/D3: request lifecycle hardening ---

// stageRequestFile stages one v3 file on the fake relay and returns the sealed
// offer inner payload that answers a pull request.
func stageRequestFile(
	t *testing.T,
	f *fakeRelay,
	relayToken string,
	peerKey []byte,
	name string,
	payload []byte,
	batchIndex, batchTotal int,
	requestID, grant string,
) map[string]interface{} {
	t.Helper()
	dir := t.TempDir()
	src := filepath.Join(dir, name)
	if err := os.WriteFile(src, payload, 0o600); err != nil {
		t.Fatal(err)
	}
	digest, err := HashFileChunks(src, ChunkSize)
	if err != nil {
		t.Fatal(err)
	}
	transferID := DeriveTransferID(peerKey, digest.FileHash)
	sessionID := DeriveSessionID(peerKey, transferID)
	fileKey := DeriveFileKey(peerKey, transferID)
	stageCfg := Config{RelayAddr: f.URL}
	auth := ChunkAuth{RelayToken: relayToken, DeviceID: "phone-1", PeerKey: peerKey}
	for i := 0; i < digest.ChunkCount; i++ {
		stageV3Chunk(t, f, stageCfg, auth, payload, digest, fileKey, transferID, sessionID, i)
	}
	return map[string]interface{}{
		"sender_id":    "phone-1",
		"sender_name":  "Lavander",
		"transfer_id":  transferID,
		"session_id":   sessionID,
		"file_name":    name,
		"file_size":    digest.Size,
		"chunk_size":   ChunkSize,
		"chunk_count":  digest.ChunkCount,
		"file_hash":    digest.FileHash,
		"chunk_hashes": digest.ChunkHashes,
		"batch_id":     "batch-1",
		"batch_index":  batchIndex,
		"batch_total":  batchTotal,
		"request_id":   requestID,
		"grant":        grant,
	}
}

// TestRequestCommandReceivesBatch pins the multi-file fix: the target's
// picker allows several files and the send subprocess emits one offer per
// file with batch_total set. The requester must keep receiving until the
// whole batch is saved, not return after the first offer.
func TestRequestCommandReceivesBatch(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)
	dir := t.TempDir()
	inbox := filepath.Join(dir, "inbox")

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("phone connect: %v", err)
	}
	defer phoneConn.Close()

	payloads := [][]byte{
		bytes.Repeat([]byte("a"), 4096),
		bytes.Repeat([]byte("b"), 5000),
		bytes.Repeat([]byte("c"), 6000),
	}
	names := []string{"one.bin", "two.bin", "three.bin"}

	// Stage every file before the phone goroutine starts: t.Fatalf must not
	// run off the test goroutine.
	baseInners := make([]map[string]interface{}, len(names))
	for i := range names {
		baseInners[i] = stageRequestFile(
			t, f, relayToken, peerKey, names[i], payloads[i], i, len(names), "", "",
		)
	}

	phoneDone := make(chan error, 1)
	go func() {
		_ = phoneConn.SetReadDeadline(time.Now().Add(10 * time.Second))
		for {
			_, data, err := phoneConn.ReadMessage()
			if err != nil {
				phoneDone <- err
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(data, &envelope) != nil {
				continue
			}
			if envelopeString(envelope, "type") != "sealed" {
				continue
			}
			kind, inner, err := openSealed(peerKey, envelope)
			if err != nil || kind != "request" {
				continue
			}
			requestID := envelopeString(inner, "request_id")
			requesterID := envelopeString(inner, "requester_id")
			grant := envelopeString(inner, "grant")
			for i := range baseInners {
				offerInner := make(map[string]interface{}, len(baseInners[i])+2)
				for k, v := range baseInners[i] {
					offerInner[k] = v
				}
				offerInner["request_id"] = requestID
				offerInner["grant"] = grant
				offer, err := sealEnvelope(peerKey, requesterID, "offer", offerInner)
				if err != nil {
					phoneDone <- err
					return
				}
				if err := phoneConn.WriteJSON(offer); err != nil {
					phoneDone <- err
					return
				}
			}
			phoneDone <- nil
			return
		}
	}()

	reqCfg := Config{
		RelayAddr: f.URL,
		TokenFile: writeClusterToken(t, dir, f.Token),
		DeviceID:  "desktop-1",
		Name:      "Desktop",
		InboxDir:  inbox,
	}
	if err := runRequest(reqCfg, "phone-1", false); err != nil {
		t.Fatalf("runRequest: %v", err)
	}
	if err := <-phoneDone; err != nil {
		t.Fatalf("phone: %v", err)
	}
	for i, name := range names {
		got, err := os.ReadFile(filepath.Join(inbox, name))
		if err != nil {
			t.Fatalf("batch file %s missing: %v", name, err)
		}
		if !bytes.Equal(got, payloads[i]) {
			t.Fatalf("batch file %s does not match the original", name)
		}
	}
}

// TestRequestCommandReceivesDuplicateContentBatch pins the dedupe key: two
// files with identical bytes share a transfer id, but they are distinct
// offers (different sealed msg_ids and names), so both must be saved.
func TestRequestCommandReceivesDuplicateContentBatch(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)
	dir := t.TempDir()
	inbox := filepath.Join(dir, "inbox")

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("phone connect: %v", err)
	}
	defer phoneConn.Close()

	payload := bytes.Repeat([]byte("same"), 1024)
	names := []string{"copy-a.bin", "copy-b.bin"}
	baseInners := make([]map[string]interface{}, len(names))
	for i := range names {
		baseInners[i] = stageRequestFile(
			t, f, relayToken, peerKey, names[i], payload, i, len(names), "", "",
		)
	}
	if baseInners[0]["transfer_id"] != baseInners[1]["transfer_id"] {
		t.Fatal("test premise broken: identical content must share a transfer id")
	}

	phoneDone := make(chan error, 1)
	go func() {
		_ = phoneConn.SetReadDeadline(time.Now().Add(10 * time.Second))
		for {
			_, data, err := phoneConn.ReadMessage()
			if err != nil {
				phoneDone <- err
				return
			}
			var envelope map[string]interface{}
			if json.Unmarshal(data, &envelope) != nil {
				continue
			}
			if envelopeString(envelope, "type") != "sealed" {
				continue
			}
			kind, inner, err := openSealed(peerKey, envelope)
			if err != nil || kind != "request" {
				continue
			}
			for i := range baseInners {
				offerInner := make(map[string]interface{}, len(baseInners[i])+2)
				for k, v := range baseInners[i] {
					offerInner[k] = v
				}
				offerInner["request_id"] = envelopeString(inner, "request_id")
				offerInner["grant"] = envelopeString(inner, "grant")
				offer, err := sealEnvelope(peerKey, envelopeString(inner, "requester_id"), "offer", offerInner)
				if err != nil {
					phoneDone <- err
					return
				}
				if err := phoneConn.WriteJSON(offer); err != nil {
					phoneDone <- err
					return
				}
			}
			phoneDone <- nil
			return
		}
	}()

	reqCfg := Config{
		RelayAddr: f.URL,
		TokenFile: writeClusterToken(t, dir, f.Token),
		DeviceID:  "desktop-1",
		Name:      "Desktop",
		InboxDir:  inbox,
	}
	if err := runRequest(reqCfg, "phone-1", false); err != nil {
		t.Fatalf("runRequest: %v", err)
	}
	if err := <-phoneDone; err != nil {
		t.Fatalf("phone: %v", err)
	}
	for _, name := range names {
		got, err := os.ReadFile(filepath.Join(inbox, name))
		if err != nil {
			t.Fatalf("batch file %s missing: %v", name, err)
		}
		if !bytes.Equal(got, payload) {
			t.Fatalf("batch file %s does not match the original", name)
		}
	}
}

// TestRequestCommandReceiveWindowCloses pins the window semantics: the
// request TTL bounds only the wait for the first offer; after it, the
// size-based completion timeout (with its floor) bounds the rest of the
// batch. A short window must end the command with the files it has.
func TestRequestCommandReceiveWindowCloses(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)
	dir := t.TempDir()
	inbox := filepath.Join(dir, "inbox")

	oldBase, oldTimeout, oldMin := completionTimeoutBase, requestTimeout, completionTimeoutMin
	completionTimeoutBase = 50 * time.Millisecond
	requestTimeout, completionTimeoutMin = 50*time.Millisecond, 300*time.Millisecond
	defer func() {
		completionTimeoutBase, requestTimeout, completionTimeoutMin = oldBase, oldTimeout, oldMin
	}()

	requesterID := "reqdev-4242-abcd"
	requesterCfg := Config{RelayAddr: f.URL, DeviceID: requesterID, Name: "Requestor"}
	requesterConn, err := connectWS(requesterCfg, relayToken)
	if err != nil {
		t.Fatalf("requester connect: %v", err)
	}
	defer requesterConn.Close()

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("phone connect: %v", err)
	}
	defer phoneConn.Close()

	pending, err := newPendingRequest(peerKey, requesterID, "phone-1", "files", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	// One offer of a three-file batch: the rest never arrives. The offer is
	// already in the requester's socket when the loop starts, so the first
	// read cannot race the 50 ms request wait.
	offerInner := stageRequestFile(
		t, f, relayToken, peerKey, "only.bin", bytes.Repeat([]byte("x"), 2048),
		0, 3, pending.RequestID, pending.Grant,
	)
	offer, err := sealEnvelope(peerKey, requesterID, "offer", offerInner)
	if err != nil {
		t.Fatal(err)
	}
	if err := phoneConn.WriteJSON(offer); err != nil {
		t.Fatal(err)
	}

	start := time.Now()
	if err := waitForRequestedFile(
		Config{RelayAddr: f.URL, InboxDir: inbox}, relayToken, peerKey,
		&wsClient{conn: requesterConn}, pending,
	); err != nil {
		t.Fatalf("a partial batch must not fail the request: %v", err)
	}
	elapsed := time.Since(start)
	if elapsed < completionTimeoutMin {
		t.Fatalf("window closed after %v, want at least %v", elapsed, completionTimeoutMin)
	}
	if elapsed > 5*time.Second {
		t.Fatalf("receive window took %v, want the short test window", elapsed)
	}
	if _, err := os.Stat(filepath.Join(inbox, "only.bin")); err != nil {
		t.Fatalf("the first batch file must be saved: %v", err)
	}
}

// TestRequestCommandDeadlineBoundsLoop pins the other half of the deadline
// change: with the per-offer expiry check gone, the loop's own read deadline
// is what bounds the exchange. An already-expired grant times out immediately.
func TestRequestCommandDeadlineBoundsLoop(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)

	requesterID := "reqdev-4242-abcd"
	requesterCfg := Config{RelayAddr: f.URL, DeviceID: requesterID, Name: "Requestor"}
	conn, err := connectWS(requesterCfg, relayToken)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer conn.Close()

	// newPendingRequest stamps expires_ts = now + requestTTL, so a start time
	// two TTLs in the past leaves the grant already expired.
	pending, err := newPendingRequest(peerKey, requesterID, "phone-1", "files", time.Now().Add(-2*requestTTL))
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	err = waitForRequestedFile(Config{RelayAddr: f.URL}, relayToken, peerKey, &wsClient{conn: conn}, pending)
	if err == nil || err.Error() != "Request timed out" {
		t.Fatalf("error = %v, want Request timed out", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("expired grant took %v to time out", elapsed)
	}
}

func TestSelfTargetError(t *testing.T) {
	if err := selfTargetError("phone-1", "desktop-1"); err != nil {
		t.Fatalf("a different target must be allowed: %v", err)
	}
	if err := selfTargetError("desktop-1", "desktop-1"); err == nil ||
		!strings.Contains(err.Error(), "cannot request files from this device") {
		t.Fatalf("self target error = %v", err)
	}
	if err := selfTargetError("desktop-1", ""); err != nil {
		t.Fatalf("an unknown origin must not reject: %v", err)
	}
}

// TestDiscoverTargetExcludesStableID pins the other half of the self-target
// fix: the daemon's real id is removed from name resolution, not just the
// ephemeral requester id, so a same-name query cannot resolve to this machine.
func TestDiscoverTargetExcludesStableID(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, _ := DeriveSubKeys(f.Token)

	daemonCfg := Config{RelayAddr: f.URL, DeviceID: "desktop-1", Name: "Desktop"}
	daemonConn, err := connectWS(daemonCfg, relayToken)
	if err != nil {
		t.Fatalf("daemon connect: %v", err)
	}
	defer daemonConn.Close()

	requesterID := "reqdev-9999-abcd"
	requesterCfg := Config{RelayAddr: f.URL, DeviceID: requesterID, Name: "Desktop"}
	requesterConn, err := connectWS(requesterCfg, relayToken)
	if err != nil {
		t.Fatalf("requester connect: %v", err)
	}
	defer requesterConn.Close()

	_, _, err = discoverTargetExcluding(requesterConn, "Desktop", requesterID, "desktop-1")
	if !errors.Is(err, errTargetNotFound) {
		t.Fatalf("error = %v, want the daemon's own id excluded", err)
	}
}

// TestRequestBusyReleasedWhenPickerCloses pins the handoff point: the slot
// exists to serialize pickers, so a second request must be served while the
// first request's send subprocess is still running.
func TestRequestBusyReleasedWhenPickerCloses(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	pickerCalls := 0
	oldPicker := pickRequestFiles
	pickRequestFiles = func(string) ([]string, error) {
		pickerCalls++
		if pickerCalls == 1 {
			return []string{"/tmp/picked.bin"}, nil
		}
		return nil, errPickerCanceled
	}
	defer func() { pickRequestFiles = oldPicker }()

	sendStarted := make(chan struct{})
	releaseSend := make(chan struct{})
	oldSend := startRequestSend
	startRequestSend = func(Config, string, string, []string) error {
		close(sendStarted)
		<-releaseSend
		return nil
	}
	defer func() { startRequestSend = oldSend }()

	first := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	done := make(chan struct{})
	go func() {
		handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, first)
		close(done)
	}()
	select {
	case <-sendStarted:
	case <-time.After(5 * time.Second):
		t.Fatal("first request never reached the send subprocess")
	}

	// The picker is closed and the subprocess is still running: the slot must
	// be free, so this request is declined by the user's cancel, not "busy".
	second := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, second)
	decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
	if got := envelopeString(decline, "reason"); got != "declined" {
		t.Fatalf("second request reason = %q, want declined (slot released at picker close)", got)
	}

	close(releaseSend)
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("first request handler did not finish")
	}
}

// TestRequestSlotReleasedOnDecline pins that every early return after the
// acquire hands the slot back.
func TestRequestSlotReleasedOnDecline(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	oldPicker := pickRequestFiles
	pickRequestFiles = func(string) ([]string, error) { return nil, errPickerUnavailable }
	defer func() { pickRequestFiles = oldPicker }()

	inner := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files",
		time.Now().Add(5*time.Minute).UnixMilli(),
	)
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)
	_ = readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)

	if !acquireRequestSlot() {
		t.Fatal("the slot must be free after a decline")
	}
	releaseRequestSlot()
}

// TestRequestExpiryBoundary pins the shared boundary: now >= expires_ts is
// expired, in both the target and the requester.
func TestRequestExpiryBoundary(t *testing.T) {
	fx := newRequestTargetFixture(t)
	replayGuard = NewReplayGuard()
	requestBusy.Store(false)
	defer requestBusy.Store(false)

	base := time.Now()
	oldNow := requestNow
	requestNow = func() time.Time { return base }
	defer func() { requestNow = oldNow }()

	oldPicker := pickRequestFiles
	pickRequestFiles = func(string) ([]string, error) {
		t.Fatal("picker must not open at the expiry boundary")
		return nil, nil
	}
	defer func() { pickRequestFiles = oldPicker }()

	inner := buildRequestInner(
		t, fx.peerKey, fx.requesterID, fx.cfg.DeviceID, "files", base.UnixMilli(),
	)
	handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)

	decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
	if got := envelopeString(decline, "reason"); got != "expired" {
		t.Fatalf("reason at the boundary = %q, want expired", got)
	}
}

// TestRequestRejectsInvalidDeviceIDs pins the charset gate, including the
// comma that would otherwise become two targets via splitTargets.
func TestRequestRejectsInvalidDeviceIDs(t *testing.T) {
	cases := []struct {
		name      string
		mutate    func(map[string]interface{})
		requester string
		readDecl  bool
	}{
		{
			name: "origin id with comma", requester: "reqdev-4242-abcd", readDecl: true,
			mutate: func(m map[string]interface{}) { m["origin_id"] = "a,b" },
		},
		{
			name: "target id with comma", requester: "reqdev-4242-abcd", readDecl: true,
			mutate: func(m map[string]interface{}) { m["target_id"] = "a,b" },
		},
		{
			name: "requester id with comma", requester: "a,b", readDecl: false,
			mutate: func(map[string]interface{}) {},
		},
		{
			name: "origin id with a newline", requester: "reqdev-4242-abcd", readDecl: true,
			mutate: func(m map[string]interface{}) { m["origin_id"] = "lilac\n1" },
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fx := newRequestTargetFixtureWithRequester(t, tc.requester)
			replayGuard = NewReplayGuard()
			requestBusy.Store(false)
			defer requestBusy.Store(false)

			oldPicker := pickRequestFiles
			pickRequestFiles = func(string) ([]string, error) {
				t.Fatal("picker must not open for an invalid device id")
				return nil, nil
			}
			defer func() { pickRequestFiles = oldPicker }()

			inner := buildRequestInner(
				t, fx.peerKey, tc.requester, fx.cfg.DeviceID, "files",
				time.Now().Add(5*time.Minute).UnixMilli(),
			)
			tc.mutate(inner)

			var out string
			if tc.readDecl {
				handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)
				decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
				if got := envelopeString(decline, "reason"); got != "not_allowed" {
					t.Fatalf("reason = %q, want not_allowed", got)
				}
				return
			}
			out = captureLog(func() {
				handleIncomingRequest(fx.cfg, fx.peerKey, fx.target, inner)
			})
			if !contains(out, "not_allowed") {
				t.Fatalf("expected a not_allowed decline log, got: %s", out)
			}
		})
	}
}

func TestSanitizeDeclineReason(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"declined", "declined"},
		{"busy", "busy"},
		{"expired", "expired"},
		{"unsupported", "unsupported"},
		{"picker_unavailable", "picker_unavailable"},
		{"not_allowed", "not_allowed"},
		{"", "declined"},
		{"DECLINED", "declined"},
		{"declined\n[REQUEST] accepted req-1", "declined"},
		{"\x1b[31mexpired", "declined"},
		{"something-else", "declined"},
	}
	for _, tc := range cases {
		if got := sanitizeDeclineReason(tc.in); got != tc.want {
			t.Fatalf("sanitizeDeclineReason(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestCompletionTimeout(t *testing.T) {
	cases := []struct {
		size int64
		want time.Duration
	}{
		{0, 2 * time.Minute},
		{512 * 1024, 121 * time.Second},
		{600 * 512 * 1024, 12 * time.Minute},
		{60 * 1024 * 1024, 240 * time.Second},
		{10 * 1024 * 1024 * 1024, 2 * time.Hour},
		{-1 << 40, 2 * time.Minute}, // a negative peer-supplied size clamps
	}
	for _, tc := range cases {
		if got := completionTimeout(tc.size); got != tc.want {
			t.Fatalf("completionTimeout(%d) = %v, want %v", tc.size, got, tc.want)
		}
	}
}

func TestCompletionWindow(t *testing.T) {
	if got := completionWindow(time.Second, 0); got != completionTimeout(0) {
		t.Fatalf("window = %v, want the completion timeout when the request wait is shorter", got)
	}
	long := 5 * time.Hour
	if got := completionWindow(long, 0); got != long {
		t.Fatalf("window = %v, want the remaining request wait when it is longer", got)
	}
}

// TestKillRequestSubprocesses pins the shutdown kill: a tracked subprocess is
// terminated, and the tracking table is emptied when it exits.
func TestKillRequestSubprocesses(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("uses /bin/sleep")
	}
	killRequestSubprocesses() // start from a clean table

	cmd := exec.Command("/bin/sleep", "30")
	done := make(chan error, 1)
	go func() { done <- runTrackedCommand(cmd) }()

	waitFor(t, 5*time.Second, func() bool {
		requestSendMu.Lock()
		defer requestSendMu.Unlock()
		return len(requestSendCmds) == 1
	})
	killRequestSubprocesses()

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("a killed subprocess must report an error")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("subprocess was not killed")
	}
	waitFor(t, 5*time.Second, func() bool {
		requestSendMu.Lock()
		defer requestSendMu.Unlock()
		return len(requestSendCmds) == 0
	})
}
