package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

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
	if len(seen) < 2 {
		t.Fatal("newRequesterID returned one id for 64 draws")
	}
}

func TestNewPendingRequestGrant(t *testing.T) {
	_, peerKey := DeriveSubKeys("request-test-token")
	now := time.UnixMilli(1730000000000)

	pending, err := newPendingRequest(peerKey, "reqdev-1-abcd", "phone-1", now)
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
	pending, err := newPendingRequest(peerKey, "reqdev-1-abcd", "phone-1", now)
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

func TestBrowseArgsFromFlags(t *testing.T) {
	cases := []struct {
		name string
		in   []string
		want []string
	}{
		{"flag before target", []string{"--json", "phone-1"}, []string{"--json", "phone-1"}},
		{"flag after target", []string{"phone-1", "--json"}, []string{"--json", "phone-1"}},
		{"single dash after target", []string{"phone-1", "-json"}, []string{"-json", "phone-1"}},
		{"no flag", []string{"phone-1", "docs"}, []string{"phone-1", "docs"}},
		{"flag only", []string{"--json"}, []string{"--json"}},
		{"no args", nil, nil},
		{"trailing flag after path", []string{"phone-1", "docs", "--json"}, []string{"--json", "phone-1", "docs"}},
		{"middle flag is not moved", []string{"phone-1", "--json", "docs"}, []string{"phone-1", "--json", "docs"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := browseArgsFromFlags(tc.in); !slices.Equal(got, tc.want) {
				t.Fatalf("browseArgsFromFlags(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}

func TestFetchSendArgs(t *testing.T) {
	meta := "req-0011223344556677:" + strings.Repeat("ab", 32) + ":1767225600000"
	cfg := Config{
		RelayAddr: "127.0.0.1:9876",
		TokenFile: "/tmp/cluster.key",
		DeviceID:  "desktop-1",
		Name:      "Desktop",
	}
	files := []string{"/tmp/a b.txt", "/tmp/c.bin"}

	got := fetchSendArgs(cfg, "reqdev-42-abcd", meta, files)
	want := []string{
		"-relay", "127.0.0.1:9876",
		"-key-file", "/tmp/cluster.key",
		"-device-id", "desktop-1",
		"-name", "Desktop",
		"send", "--to", "reqdev-42-abcd", "--request-meta", meta,
		"--", "/tmp/a b.txt", "/tmp/c.bin",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("fetchSendArgs = %q, want %q", got, want)
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

	minimal := fetchSendArgs(Config{}, "reqdev-1-abcd", meta, []string{"file.bin"})
	wantMinimal := []string{"send", "--to", "reqdev-1-abcd", "--request-meta", meta, "--", "file.bin"}
	if !slices.Equal(minimal, wantMinimal) {
		t.Fatalf("minimal args = %q, want %q", minimal, wantMinimal)
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

func TestValidBrowsePath(t *testing.T) {
	cases := []struct {
		name string
		path string
		want bool
	}{
		{"root", "", true},
		{"single segment", "docs", true},
		{"nested", "docs/notes", true},
		{"spaces", "my docs/note one.txt", true},
		{"leading slash", "/docs", false},
		{"trailing slash", "docs/", false},
		{"double slash", "docs//notes", false},
		{"dotdot", "docs/../etc", false},
		{"dotdot only", "..", false},
		{"dot", ".", false},
		{"dotfile is a valid wire path", ".hidden", true},
		{"nested dotfile is a valid wire path", "docs/.hidden", true},
		{"backslash is a valid wire path", "docs\\notes", true},
		{"nul", "docs\x00notes", false},
		{"newline", "docs\nnotes", false},
		{"c1 control", "docs\u009bnotes", false},
		{"max length", strings.Repeat("a/", 511) + "aa", true},
		{"over max length", strings.Repeat("a/", 511) + "aaa", false},
		{"max UTF-16 units", strings.Repeat("é/", 511) + "éé", true},
		{"over max UTF-16 units", strings.Repeat("é/", 511) + "ééé", false},
		{"max segment", strings.Repeat("a", browseSegmentMax), true},
		{"over max segment", strings.Repeat("a", browseSegmentMax+1), false},
		{"max UTF-16 segment", strings.Repeat("é", browseSegmentMax), true},
		{"over max UTF-16 segment", strings.Repeat("é", browseSegmentMax+1), false},
		{"over max segment in a nested path", "docs/" + strings.Repeat("a", browseSegmentMax+1), false},
		{"colon is legal on linux", "notes: draft.txt", runtime.GOOS != "windows"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := validBrowsePath(tc.path); got != tc.want {
				t.Fatalf("validBrowsePath(%q) = %v, want %v", tc.path, got, tc.want)
			}
		})
	}
}

func TestBrowsePathPolicy(t *testing.T) {
	cases := []struct {
		name string
		path string
		want bool
	}{
		{"root", "", true},
		{"plain", "docs/notes.txt", true},
		{"dotfile", ".hidden", false},
		{"nested dotfile", "docs/.hidden", false},
		{"dotdir", ".config/settings", false},
		{"backslash on linux", "docs\\notes", runtime.GOOS != "windows"},
		{"colon on linux", "notes: draft.txt", runtime.GOOS != "windows"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := browsePathPolicyOK(tc.path); got != tc.want {
				t.Fatalf("browsePathPolicyOK(%q) = %v, want %v", tc.path, got, tc.want)
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
		{"not_found", "not_found"},
		{"too_large", "too_large"},
		{"", "declined"},
		{"DECLINED", "declined"},
		{"declined\n[FETCH] accepted req-1", "declined"},
		{"\x1b[31mexpired", "declined"},
		{"something-else", "declined"},
	}
	for _, tc := range cases {
		if got := sanitizeDeclineReason(tc.in); got != tc.want {
			t.Fatalf("sanitizeDeclineReason(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestSanitizeBrowseError(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"denied", "denied"},
		{"not_found", "not_found"},
		{"rate_limited", "rate_limited"},
		{"unsupported", "unsupported"},
		{"", "denied"},
		{"DENIED", "denied"},
		{"denied\n[FETCH] accepted req-1", "denied"},
		{"\x1b[31mnot_found", "denied"},
		{"something-else", "denied"},
	}
	for _, tc := range cases {
		if got := sanitizeBrowseError(tc.in); got != tc.want {
			t.Fatalf("sanitizeBrowseError(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestFormatBrowseSize(t *testing.T) {
	cases := []struct {
		size int64
		want string
	}{
		{0, "0 B"},
		{1023, "1023 B"},
		{1024, "1.0 KiB"},
		{1536, "1.5 KiB"},
		{1 << 20, "1.0 MiB"},
		{4 << 30, "4.0 GiB"},
	}
	for _, tc := range cases {
		if got := formatBrowseSize(tc.size); got != tc.want {
			t.Fatalf("formatBrowseSize(%d) = %q, want %q", tc.size, got, tc.want)
		}
	}
}

func TestSanitizeBrowseName(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"plain.txt", "plain.txt"},
		{" leading space", " leading space"},
		{"a\nb", "a b"},
		{"a\x1b[31mb", "a [31mb"},
		{"a\u202eb", "ab"},
		{"a\u2028b", "a b"},
	}
	for _, tc := range cases {
		if got := sanitizeBrowseName(tc.in); got != tc.want {
			t.Fatalf("sanitizeBrowseName(%q) = %q, want %q", tc.in, got, tc.want)
		}
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

// readSealedInners reads n sealed envelopes of wantKind. It is used when
// several replies are already queued (the rate-limit test), so one deadline
// covers the whole batch.
func readSealedInners(t *testing.T, conn *websocket.Conn, peerKey []byte, wantKind string, n int, timeout time.Duration) []map[string]interface{} {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	defer conn.SetReadDeadline(time.Time{})
	out := make([]map[string]interface{}, 0, n)
	for len(out) < n {
		_, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read %s %d/%d: %v", wantKind, len(out), n, err)
		}
		var envelope map[string]interface{}
		if json.Unmarshal(data, &envelope) != nil {
			continue
		}
		if envelopeString(envelope, "type") != "sealed" {
			continue
		}
		kind, inner, err := openSealed(peerKey, envelope)
		if err != nil || kind != wantKind {
			continue
		}
		out = append(out, inner)
	}
	return out
}

// writeExecutable writes a tiny /bin/sh script for launcher tests.
func writeExecutable(t *testing.T, path, body string) {
	t.Helper()
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+body+"\n"), 0o755); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func writeFixtureFile(t *testing.T, root, rel string, data []byte) {
	t.Helper()
	path := filepath.Join(root, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("mkdir for %s: %v", rel, err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatalf("write %s: %v", rel, err)
	}
}

// browseTargetFixture is a fake relay with one daemon target and one
// requester connection. The target exposes a small fixture tree.
type browseTargetFixture struct {
	relay       *fakeRelay
	cfg         Config
	target      *wsClient
	requester   *websocket.Conn
	requesterID string
	peerKey     []byte
	root        string
	escapeOK    bool
	fileLinkOK  bool
	handlers    sync.WaitGroup
}

func newBrowseTargetFixture(t *testing.T) *browseTargetFixture {
	t.Helper()
	// Isolate the trust list and config.json: onlyTrustedEnabled also reads
	// the real user's config, which must not decide these tests.
	t.Setenv("HOME", t.TempDir())
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)

	root := t.TempDir()
	writeFixtureFile(t, root, "alpha.txt", []byte("alpha"))
	writeFixtureFile(t, root, "zeta.bin", bytes.Repeat([]byte("z"), 10))
	writeFixtureFile(t, root, "nested/child.txt", []byte("child!!"))
	if err := os.MkdirAll(filepath.Join(root, "Beta"), 0o755); err != nil {
		t.Fatalf("mkdir Beta: %v", err)
	}
	writeFixtureFile(t, root, ".hidden", []byte("secret"))
	writeFixtureFile(t, root, ".secret/inside.txt", []byte("inside"))

	outside := t.TempDir()
	writeFixtureFile(t, outside, "leak.txt", []byte("leak"))
	escapeOK := os.Symlink(outside, filepath.Join(root, "escape")) == nil
	fileLinkOK := os.Symlink(filepath.Join(root, "alpha.txt"), filepath.Join(root, "link.txt")) == nil

	cfg := Config{
		RelayAddr:  f.URL,
		DeviceID:   "desktop-1",
		Name:       "Desktop",
		InboxDir:   filepath.Join(t.TempDir(), "inbox"),
		BrowseRoot: root,
	}
	targetConn, err := connectWS(cfg, relayToken)
	if err != nil {
		t.Fatalf("target connect: %v", err)
	}
	t.Cleanup(func() { _ = targetConn.Close() })

	requesterID := "phone-1"
	requesterCfg := Config{RelayAddr: f.URL, DeviceID: requesterID, Name: "Requestor"}
	requesterConn, err := connectWS(requesterCfg, relayToken)
	if err != nil {
		t.Fatalf("requester connect: %v", err)
	}
	t.Cleanup(func() { _ = requesterConn.Close() })

	replayGuard = NewReplayGuard()
	resetBrowseState()
	fetchBusy.Store(false)
	t.Cleanup(func() {
		fetchBusy.Store(false)
		browseScanHook = nil
		resetBrowseState()
	})
	// Browse/fetch require trust unconditionally (D1). Trust the fixture's
	// requester by default; tests that need an untrusted peer revoke it.
	if err := trustDevice(requesterID, "Requestor"); err != nil {
		t.Fatalf("trustDevice: %v", err)
	}

	return &browseTargetFixture{
		relay:       f,
		cfg:         cfg,
		target:      &wsClient{conn: targetConn},
		requester:   requesterConn,
		requesterID: requesterID,
		peerKey:     peerKey,
		root:        root,
		escapeOK:    escapeOK,
		fileLinkOK:  fileLinkOK,
	}
}

// startTargetLoop reads sealed browse/fetch frames from the target connection
// and dispatches them exactly like the daemon does: one goroutine per frame.
// The cleanup closes the target connection and waits for the loop and every
// handler to exit, so no goroutine can outlive the test and race the next
// fixture's global resets.
func (fx *browseTargetFixture) startTargetLoop(t *testing.T) {
	t.Helper()
	loopDone := make(chan struct{})
	t.Cleanup(func() {
		_ = fx.target.conn.Close()
		<-loopDone
		fx.handlers.Wait()
	})
	go func() {
		defer close(loopDone)
		for {
			_, data, err := fx.target.conn.ReadMessage()
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
			kind, inner, err := openSealed(fx.peerKey, envelope)
			if err != nil {
				continue
			}
			switch kind {
			case "browse":
				fx.handlers.Add(1)
				go func() {
					defer fx.handlers.Done()
					handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
				}()
			case "fetch":
				fx.handlers.Add(1)
				go func() {
					defer fx.handlers.Done()
					handleIncomingFetch(fx.cfg, fx.peerKey, fx.target, inner)
				}()
			}
		}
	}()
}

func buildBrowseInner(t *testing.T, requesterID, originID, targetID, path string) map[string]interface{} {
	t.Helper()
	requestID, err := NewRequestID()
	if err != nil {
		t.Fatalf("NewRequestID: %v", err)
	}
	inner := map[string]interface{}{
		"request_id":     requestID,
		"requester_id":   requesterID,
		"requester_name": "Requestor",
		"target_id":      targetID,
		"path":           path,
		"ts":             time.Now().UnixMilli(),
	}
	if originID != "" {
		inner["origin_id"] = originID
	}
	return inner
}

func (fx *browseTargetFixture) sendBrowse(t *testing.T, inner map[string]interface{}) {
	t.Helper()
	envelope, err := sealEnvelope(fx.peerKey, fx.cfg.DeviceID, "browse", inner)
	if err != nil {
		t.Fatalf("seal browse: %v", err)
	}
	if err := fx.requester.WriteJSON(envelope); err != nil {
		t.Fatalf("send browse: %v", err)
	}
}

// browseOnce sends one browse frame through the relay and returns the listing
// and its error reason.
func (fx *browseTargetFixture) browseOnce(t *testing.T, path string) (map[string]interface{}, string) {
	t.Helper()
	// These tests exercise listing semantics, not the rate limits; reset the
	// per-second and global budgets so rapid sequential probes stay
	// deterministic.
	resetBrowseLimits()
	inner := buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, path)
	fx.sendBrowse(t, inner)
	reply := readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
	if got := envelopeString(reply, "request_id"); got != envelopeString(inner, "request_id") {
		t.Fatalf("listing request_id = %q, want %q", got, envelopeString(inner, "request_id"))
	}
	return reply, envelopeString(reply, "error")
}

// --- browse target ---

func TestBrowseRoundTrip(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	fx.startTargetLoop(t)

	// A filename with a control character is legal on Linux but cannot be a
	// wire entry name; the listing must skip it rather than emit a row an
	// Android requester would reject.
	if runtime.GOOS != "windows" {
		if err := os.WriteFile(filepath.Join(fx.root, "bad\nname.txt"), []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}

	reply, reason := fx.browseOnce(t, "")
	if reason != "" {
		t.Fatalf("root listing error = %q", reason)
	}
	if got := envelopeString(reply, "path"); got != "" {
		t.Fatalf("root path = %q, want empty", got)
	}
	entries := parseBrowseEntries(reply["entries"])
	want := []struct {
		name string
		dir  bool
	}{
		{"Beta", true},
		{"nested", true},
		{"alpha.txt", false},
		{"zeta.bin", false},
	}
	if len(entries) != len(want) {
		t.Fatalf("root entries = %+v, want %d entries", entries, len(want))
	}
	for i, expected := range want {
		if entries[i].Name != expected.name || entries[i].Dir != expected.dir {
			t.Fatalf("entry %d = %+v, want %s (dir=%v)", i, entries[i], expected.name, expected.dir)
		}
		if entries[i].Modified <= 0 {
			t.Fatalf("entry %d has no mtime: %+v", i, entries[i])
		}
		if entries[i].Dir && entries[i].Size != 0 {
			t.Fatalf("directory %s has size %d, want 0", entries[i].Name, entries[i].Size)
		}
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name, ".") {
			t.Fatalf("dotfile %q leaked into the listing", entry.Name)
		}
	}
	if entries[2].Name != "alpha.txt" || entries[2].Size != 5 {
		t.Fatalf("alpha.txt = %+v, want 5 bytes", entries[2])
	}
	if entries[len(entries)-1].Name != "zeta.bin" || entries[len(entries)-1].Size != 10 {
		t.Fatalf("zeta.bin = %+v, want 10 bytes", entries[len(entries)-1])
	}

	reply, reason = fx.browseOnce(t, "nested")
	if reason != "" {
		t.Fatalf("nested listing error = %q", reason)
	}
	nested := parseBrowseEntries(reply["entries"])
	if len(nested) != 1 || nested[0].Name != "child.txt" || nested[0].Size != 7 || nested[0].Dir {
		t.Fatalf("nested entries = %+v", nested)
	}

	// An empty directory still carries entries: [] — never an omitted field.
	reply, reason = fx.browseOnce(t, "Beta")
	if reason != "" {
		t.Fatalf("empty listing error = %q", reason)
	}
	if raw, ok := reply["entries"].([]interface{}); !ok || len(raw) != 0 {
		t.Fatalf("empty directory entries = %#v, want []", reply["entries"])
	}

	if _, reason := fx.browseOnce(t, "missing"); reason != "not_found" {
		t.Fatalf("missing path reason = %q, want not_found", reason)
	}
	if _, reason := fx.browseOnce(t, ".hidden"); reason != "denied" {
		t.Fatalf("dotfile reason = %q, want denied", reason)
	}
	if _, reason := fx.browseOnce(t, "nested/child.txt"); reason != "denied" {
		t.Fatalf("browsing a file reason = %q, want denied", reason)
	}
	if fx.escapeOK {
		// An escaped symlink is indistinguishable from a missing path: the
		// requester must not learn that something exists outside the root.
		if _, reason := fx.browseOnce(t, "escape"); reason != "not_found" {
			t.Fatalf("symlink escape reason = %q, want not_found", reason)
		}
	}
	if fx.fileLinkOK {
		// Symlinks are skipped in listings entirely, even when they point
		// inside the root.
		for _, entry := range entries {
			if entry.Name == "link.txt" {
				t.Fatal("a symlink leaked into the listing")
			}
		}
		if _, reason := fx.browseOnce(t, "link.txt"); reason != "denied" {
			t.Fatalf("browsing a symlinked file reason = %q, want denied", reason)
		}
	}
}

func TestBrowseTruncatesAtCap(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	fx.startTargetLoop(t)

	many := filepath.Join(fx.root, "many")
	if err := os.MkdirAll(many, 0o755); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < browseMaxEntries+5; i++ {
		name := filepath.Join(many, fmt.Sprintf("f%04d.txt", i))
		if err := os.WriteFile(name, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}

	reply, reason := fx.browseOnce(t, "many")
	if reason != "" {
		t.Fatalf("many listing error = %q", reason)
	}
	entries := parseBrowseEntries(reply["entries"])
	if len(entries) != browseMaxEntries {
		t.Fatalf("entries = %d, want the %d cap", len(entries), browseMaxEntries)
	}
	if !envelopeBool(reply, "truncated") {
		t.Fatal("truncated must be set when the cap cuts the listing")
	}
}

func TestBrowseRateLimit(t *testing.T) {
	fx := newBrowseTargetFixture(t)

	oldWindow := browseRateWindow
	browseRateWindow = time.Second
	defer func() { browseRateWindow = oldWindow }()

	requestIDs := make([]string, 0, 3)
	for i := 0; i < 3; i++ {
		inner := buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, "")
		requestIDs = append(requestIDs, envelopeString(inner, "request_id"))
		handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
	}
	replies := readSealedInners(t, fx.requester, fx.peerKey, "browse_listing", 3, 5*time.Second)
	reasons := make(map[string]string, 3)
	for _, reply := range replies {
		reasons[envelopeString(reply, "request_id")] = envelopeString(reply, "error")
	}
	for i, requestID := range requestIDs {
		want := ""
		if i == 2 {
			want = "rate_limited"
		}
		if got := reasons[requestID]; got != want {
			t.Fatalf("reply %d reason = %q, want %q", i, got, want)
		}
	}

	// A different trust id has its own budget: the limiter must key on the
	// stable id, not the ephemeral connection id.
	if err := trustDevice("laptop-9", "Laptop"); err != nil {
		t.Fatalf("trustDevice: %v", err)
	}
	inner := buildBrowseInner(t, fx.requesterID, "laptop-9", fx.cfg.DeviceID, "")
	handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
	reply := readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
	if got := envelopeString(reply, "error"); got != "" {
		t.Fatalf("a different trust id was limited: %q", got)
	}

	// The window recovers.
	browseRateWindow = 50 * time.Millisecond
	time.Sleep(60 * time.Millisecond)
	inner = buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, "")
	handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
	reply = readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
	if got := envelopeString(reply, "error"); got != "" {
		t.Fatalf("window did not recover: %q", got)
	}
}

func TestBrowseRateLimiterRotation(t *testing.T) {
	limiter := newBrowseRateLimiter()
	limiter.now = func() time.Time { return time.UnixMilli(1000) }
	if !limiter.Allow("laptop-1") || !limiter.Allow("laptop-1") {
		t.Fatal("the first two frames must be accepted")
	}
	if limiter.Allow("laptop-1") {
		t.Fatal("the third frame in the window must be rejected")
	}
	// A rotating ephemeral requester id does not help: the key is the stable
	// trust id.
	if limiter.Allow("laptop-1") {
		t.Fatal("a rejected frame must not consume budget or reset the window")
	}
	if !limiter.Allow("laptop-2") {
		t.Fatal("a different trust id has its own budget")
	}
	limiter.now = func() time.Time { return time.UnixMilli(2500) }
	if !limiter.Allow("laptop-1") {
		t.Fatal("the budget must reset after the window")
	}
}

// TestBrowseRotationHitsOriginBudget drives the handler with distinct
// requester ids that all claim the same origin. The per-origin window must
// still cap them, proving the limiter keys on the claimed origin rather than
// the rotatable connection id.
func TestBrowseRotationHitsOriginBudget(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	if err := trustDevice("laptop-9", "Laptop"); err != nil {
		t.Fatalf("trustDevice: %v", err)
	}
	// Keep the global bucket out of the way so the per-origin window is the
	// layer under test.
	oldRate, oldBurst := browseGlobalRate, browseGlobalBurst
	browseGlobalRate, browseGlobalBurst = 1000, 1000
	resetBrowseState()
	t.Cleanup(func() {
		browseGlobalRate, browseGlobalBurst = oldRate, oldBurst
		resetBrowseState()
	})

	ids := []string{"phone-2", "phone-3", "phone-4"}
	relayToken, _ := DeriveSubKeys(fx.relay.Token)
	conns := make([]*websocket.Conn, len(ids))
	for i, id := range ids {
		conn, err := connectWS(Config{RelayAddr: fx.relay.URL, DeviceID: id, Name: "Requestor"}, relayToken)
		if err != nil {
			t.Fatalf("connect %s: %v", id, err)
		}
		defer conn.Close()
		conns[i] = conn
	}

	for i, id := range ids {
		inner := buildBrowseInner(t, id, "laptop-9", fx.cfg.DeviceID, "")
		handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
		reply := readSealedInner(t, conns[i], fx.peerKey, "browse_listing", 5*time.Second)
		got := envelopeString(reply, "error")
		want := ""
		if i == 2 {
			want = "rate_limited"
		}
		if got != want {
			t.Fatalf("requester %s reason = %q, want %q", id, got, want)
		}
	}
}

// TestBrowseWorkerCapDropsThird pins the concurrency ceiling: two handlers
// hold the worker slots, and the third is answered "rate_limited" without
// reaching the scan hook.
func TestBrowseWorkerCapDropsThird(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	oldRate, oldBurst := browseGlobalRate, browseGlobalBurst
	browseGlobalRate, browseGlobalBurst = 1000, 1000
	resetBrowseState()
	t.Cleanup(func() {
		browseGlobalRate, browseGlobalBurst = oldRate, oldBurst
		browseScanHook = nil
		resetBrowseState()
	})

	entered := make(chan struct{}, 2)
	release := make(chan struct{})
	var releaseOnce sync.Once
	releaseAll := func() { releaseOnce.Do(func() { close(release) }) }
	t.Cleanup(releaseAll)
	browseScanHook = func() {
		entered <- struct{}{}
		<-release
	}

	var handlers sync.WaitGroup
	start := func(inner map[string]interface{}) {
		handlers.Add(1)
		go func() {
			defer handlers.Done()
			handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
		}()
	}
	start(buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, ""))
	start(buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, ""))
	for i := 0; i < 2; i++ {
		select {
		case <-entered:
		case <-time.After(5 * time.Second):
			t.Fatal("a browse handler never reached the scan hook")
		}
	}

	// The third browse must be rejected before the hook runs.
	third := buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, "")
	handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, third)
	reply := readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
	if got := envelopeString(reply, "error"); got != "rate_limited" {
		t.Fatalf("third concurrent browse reason = %q, want rate_limited", got)
	}
	select {
	case <-entered:
		t.Fatal("the third browse reached the scan hook; the worker cap did not hold")
	default:
	}

	releaseAll()
	handlers.Wait()
}

// TestBrowseGlobalBudgetCapsBurst pins the cluster-wide token bucket: with a
// burst of two, the third accepted browse in the same instant is rejected even
// when every requester has its own trusted origin.
func TestBrowseGlobalBudgetCapsBurst(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	oldRate, oldBurst := browseGlobalRate, browseGlobalBurst
	browseGlobalRate, browseGlobalBurst = 1, 2
	resetBrowseState()
	t.Cleanup(func() {
		browseGlobalRate, browseGlobalBurst = oldRate, oldBurst
		resetBrowseState()
	})

	for i := 0; i < 3; i++ {
		id := fmt.Sprintf("laptop-%d", i)
		if err := trustDevice(id, id); err != nil {
			t.Fatalf("trustDevice: %v", err)
		}
		inner := buildBrowseInner(t, fx.requesterID, id, fx.cfg.DeviceID, "")
		handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
		reply := readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
		got := envelopeString(reply, "error")
		want := ""
		if i == 2 {
			want = "rate_limited"
		}
		if got != want {
			t.Fatalf("browse %d reason = %q, want %q", i, got, want)
		}
	}
}

type countingDirReader struct {
	inner   browseDirReader
	calls   int
	entries int
}

func (c *countingDirReader) ReadDir(n int) ([]fs.DirEntry, error) {
	batch, err := c.inner.ReadDir(n)
	c.calls++
	c.entries += len(batch)
	return batch, err
}

// TestBrowseReadStopsAtCap pins the bounded listing work: a 5000-entry
// directory is read in batches and abandoned as soon as the cap is exceeded,
// not materialized and sorted in full.
func TestBrowseReadStopsAtCap(t *testing.T) {
	dir := t.TempDir()
	for i := 0; i < 5000; i++ {
		name := filepath.Join(dir, fmt.Sprintf("f%04d.txt", i))
		if err := os.WriteFile(name, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	f, err := os.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()

	counter := &countingDirReader{inner: f}
	entries, truncated, err := readBrowseEntries(counter)
	if err != nil {
		t.Fatalf("readBrowseEntries: %v", err)
	}
	if !truncated {
		t.Fatal("a 5000-entry directory must report truncated")
	}
	if len(entries) != browseMaxEntries+1 {
		t.Fatalf("entries = %d, want %d (cap+1)", len(entries), browseMaxEntries+1)
	}
	// 1001 accepted entries need at most four 256-entry batches; allow one
	// extra batch for filesystem readdir granularity. The entry counter is
	// the FS-independent proof that the whole 5000-entry directory was not
	// materialized.
	if counter.entries > browseMaxEntries+browseReadBatch {
		t.Fatalf("read %d entries, want at most %d (the reader scanned the whole directory)",
			counter.entries, browseMaxEntries+browseReadBatch)
	}
}

// TestResolveBrowseTargetRejectsResolvedDotBase pins the Windows 8.3 alias
// defense on every platform: a non-dot alias that resolves to a dotfile is
// rejected, and fetch validation collapses it to not_found.
func TestResolveBrowseTargetRejectsResolvedDotBase(t *testing.T) {
	root := t.TempDir()
	writeFixtureFile(t, root, ".hidden", []byte("secret"))
	alias := filepath.Join(root, "alias")
	if err := os.Symlink(filepath.Join(root, ".hidden"), alias); err != nil {
		t.Skipf("symlinks are unavailable: %v", err)
	}
	cfg := Config{BrowseRoot: root}

	if _, reason := resolveBrowseTarget(cfg, "alias"); reason != "denied" {
		t.Fatalf("resolved dotfile reason = %q, want denied", reason)
	}
	if _, reason := resolveFetchFiles(cfg, []string{"alias"}); reason != "not_found" {
		t.Fatalf("fetch of resolved dotfile reason = %q, want not_found", reason)
	}
}

func TestBrowseTrustRequiredRegardlessOfOnlyTrusted(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	// D1: browse is a consent capability. Even with only-trusted off, an
	// untrusted origin must not get a listing.
	fx.cfg.OnlyTrusted = false
	if _, err := untrustDevice(fx.requesterID); err != nil {
		t.Fatalf("untrustDevice: %v", err)
	}
	fx.startTargetLoop(t)

	inner := buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, "")
	fx.sendBrowse(t, inner)
	reply := readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
	if got := envelopeString(reply, "error"); got != "denied" {
		t.Fatalf("untrusted browse reason = %q, want denied", got)
	}

	// Trusting the device once is the whole consent step; only-trusted stays
	// off and the listing is served.
	if err := trustDevice(fx.requesterID, "Requestor"); err != nil {
		t.Fatalf("trustDevice: %v", err)
	}
	inner = buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, "")
	fx.sendBrowse(t, inner)
	reply = readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
	if got := envelopeString(reply, "error"); got != "" {
		t.Fatalf("trusted browse reason = %q, want a listing", got)
	}
}

func TestBrowseTrustUsesClaimedOrigin(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	fx.cfg.OnlyTrusted = false
	// The requester id is ephemeral and untrusted; only the claimed origin is
	// on the trust list. If the handler checked requester_id instead of
	// origin_id, this browse would be denied.
	if _, err := untrustDevice(fx.requesterID); err != nil {
		t.Fatalf("untrustDevice: %v", err)
	}
	if err := trustDevice("laptop-9", "Laptop"); err != nil {
		t.Fatalf("trustDevice: %v", err)
	}
	fx.startTargetLoop(t)

	inner := buildBrowseInner(t, fx.requesterID, "laptop-9", fx.cfg.DeviceID, "")
	fx.sendBrowse(t, inner)
	reply := readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
	if got := envelopeString(reply, "error"); got != "" {
		t.Fatalf("trusted origin browse reason = %q, want a listing", got)
	}
}

func TestBrowseTargetDropsDuplicateRequestID(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	inner := buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, "")

	handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
	reply := readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
	if got := envelopeString(reply, "request_id"); got != envelopeString(inner, "request_id") {
		t.Fatalf("listing request_id = %q, want %q", got, envelopeString(inner, "request_id"))
	}

	// The same request id is dropped silently: no second listing.
	handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
	if _, ok := tryReadSealedInner(fx.requester, fx.peerKey, "browse_listing", 200*time.Millisecond); ok {
		t.Fatal("duplicate browse request id must not produce a second listing")
	}
}

func TestBrowseTargetDropsMalformed(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(map[string]interface{})
	}{
		{"missing path", func(m map[string]interface{}) { delete(m, "path") }},
		{"non-string path", func(m map[string]interface{}) { m["path"] = float64(1) }},
		{"missing request id", func(m map[string]interface{}) { delete(m, "request_id") }},
		{"missing target id", func(m map[string]interface{}) { delete(m, "target_id") }},
		{"invalid requester id", func(m map[string]interface{}) { m["requester_id"] = "a,b" }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fx := newBrowseTargetFixture(t)
			inner := buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, "")
			tc.mutate(inner)
			handleIncomingBrowse(fx.cfg, fx.peerKey, fx.target, inner)
			if _, ok := tryReadSealedInner(fx.requester, fx.peerKey, "browse_listing", 200*time.Millisecond); ok {
				t.Fatal("a malformed browse must be dropped silently")
			}
		})
	}
}

func TestBrowseDispatch(t *testing.T) {
	fx := newBrowseTargetFixture(t)

	inner := buildBrowseInner(t, fx.requesterID, "", fx.cfg.DeviceID, "")
	envelope, err := sealEnvelope(fx.peerKey, fx.cfg.DeviceID, "browse", inner)
	if err != nil {
		t.Fatalf("seal browse: %v", err)
	}

	// A browse has no sender_id; the sealed dispatcher must route it before
	// the generic sender gate, or it would be dropped before the handler.
	handleIncomingSealed(fx.cfg, "", fx.peerKey, fx.target, nil, nil, envelope)
	reply := readSealedInner(t, fx.requester, fx.peerKey, "browse_listing", 5*time.Second)
	if got := envelopeString(reply, "request_id"); got != envelopeString(inner, "request_id") {
		t.Fatalf("listing request_id = %q, want %q", got, envelopeString(inner, "request_id"))
	}

	// The old picker request kind is answered with an immediate
	// "unsupported" decline so an old client fails fast.
	requestID, _ := NewRequestID()
	oldInner := map[string]interface{}{
		"request_id":     requestID,
		"requester_id":   fx.requesterID,
		"requester_name": "Requestor",
		"target_id":      fx.cfg.DeviceID,
		"kind":           "files",
		"expires_ts":     time.Now().Add(5 * time.Minute).UnixMilli(),
		"grant":          strings.Repeat("ab", 32),
		"ts":             time.Now().UnixMilli(),
	}
	oldEnvelope, err := sealEnvelope(fx.peerKey, fx.cfg.DeviceID, "request", oldInner)
	if err != nil {
		t.Fatalf("seal request: %v", err)
	}
	handleIncomingSealed(fx.cfg, "", fx.peerKey, fx.target, nil, nil, oldEnvelope)
	decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
	if got := envelopeString(decline, "reason"); got != "unsupported" {
		t.Fatalf("legacy request reason = %q, want unsupported", got)
	}
	if got := envelopeString(decline, "request_id"); got != requestID {
		t.Fatalf("legacy request decline id = %q, want %q", got, requestID)
	}
}

// --- browse command ---

func browseCLIConfig(t *testing.T, fx *browseTargetFixture) Config {
	t.Helper()
	// The CLI connects under its own stable id, which becomes origin_id.
	if err := trustDevice("laptop-1", "Laptop"); err != nil {
		t.Fatalf("trustDevice: %v", err)
	}
	return Config{
		RelayAddr: fx.relay.URL,
		TokenFile: writeClusterToken(t, t.TempDir(), fx.relay.Token),
		DeviceID:  "laptop-1",
		Name:      "Laptop",
		InboxDir:  filepath.Join(t.TempDir(), "inbox"),
	}
}

func TestBrowseCommandJSONAndHuman(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	fx.startTargetLoop(t)
	cfg := browseCLIConfig(t, fx)

	var browseErr error
	resetBrowseLimits()
	out := captureStdout(func() {
		browseErr = runBrowse(cfg, "desktop-1", "", true)
	})
	if browseErr != nil {
		t.Fatalf("runBrowse JSON: %v", browseErr)
	}
	var payload struct {
		Path      string        `json:"path"`
		Entries   []browseEntry `json:"entries"`
		Truncated bool          `json:"truncated"`
		Error     string        `json:"error"`
	}
	if err := json.Unmarshal([]byte(strings.TrimSpace(out)), &payload); err != nil {
		t.Fatalf("browse JSON is not valid: %q: %v", out, err)
	}
	if payload.Path != "" || payload.Error != "" || len(payload.Entries) == 0 {
		t.Fatalf("browse JSON payload = %+v", payload)
	}
	names := make([]string, 0, len(payload.Entries))
	for _, entry := range payload.Entries {
		names = append(names, entry.Name)
	}
	if !slices.Contains(names, "alpha.txt") || !slices.Contains(names, "Beta") {
		t.Fatalf("browse JSON entries = %v", names)
	}
	if slices.Contains(names, ".hidden") {
		t.Fatalf("dotfile leaked into JSON: %v", names)
	}

	out = captureStdout(func() {
		resetBrowseLimits()
		browseErr = runBrowse(cfg, "desktop-1", "nested", false)
	})
	if browseErr != nil {
		t.Fatalf("runBrowse human: %v", browseErr)
	}
	for _, want := range []string{"TYPE", "NAME", "SIZE", "MODIFIED", "child.txt", "7 B"} {
		if !strings.Contains(out, want) {
			t.Fatalf("human listing %q is missing %q", out, want)
		}
	}

	// A target-side error is the pinned reason in both modes.
	out = captureStdout(func() {
		resetBrowseLimits()
		browseErr = runBrowse(cfg, "desktop-1", "missing", true)
	})
	if browseErr == nil || browseErr.Error() != "not_found" {
		t.Fatalf("JSON error = %v, want not_found", browseErr)
	}
	payload = struct {
		Path      string        `json:"path"`
		Entries   []browseEntry `json:"entries"`
		Truncated bool          `json:"truncated"`
		Error     string        `json:"error"`
	}{}
	if err := json.Unmarshal([]byte(strings.TrimSpace(out)), &payload); err != nil {
		t.Fatalf("browse error JSON is not valid: %q: %v", out, err)
	}
	if payload.Error != "not_found" || payload.Path != "missing" {
		t.Fatalf("browse error payload = %+v", payload)
	}

	resetBrowseLimits()
	browseErr = runBrowse(cfg, "desktop-1", "missing", false)
	if browseErr == nil || browseErr.Error() != "not_found" {
		t.Fatalf("human error = %v, want not_found", browseErr)
	}
}

// --- lifecycle helpers ---

func TestFetchBusyGuardSerializes(t *testing.T) {
	fetchBusy.Store(false)
	defer fetchBusy.Store(false)

	if !acquireFetchSlot() {
		t.Fatal("first acquire must succeed")
	}
	if acquireFetchSlot() {
		t.Fatal("second acquire must fail while the slot is held")
	}
	releaseFetchSlot()
	if !acquireFetchSlot() {
		t.Fatal("acquire after release must succeed")
	}
	releaseFetchSlot()
}

// TestKillFetchSubprocesses pins the shutdown kill: a tracked subprocess is
// terminated, and the tracking table is emptied when it exits.
func TestKillFetchSubprocesses(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("uses /bin/sleep")
	}
	killFetchSubprocesses() // start from a clean table

	cmd := exec.Command("/bin/sleep", "30")
	done := make(chan error, 1)
	go func() { done <- runTrackedCommand(cmd) }()

	waitFor(t, 5*time.Second, func() bool {
		fetchSendMu.Lock()
		defer fetchSendMu.Unlock()
		return len(fetchSendCmds) == 1
	})
	killFetchSubprocesses()

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("a killed subprocess must report an error")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("subprocess was not killed")
	}
	waitFor(t, 5*time.Second, func() bool {
		fetchSendMu.Lock()
		defer fetchSendMu.Unlock()
		return len(fetchSendCmds) == 0
	})
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

// TestBrowseLimiterCapacity pins the bounded table: more trust ids than the
// capacity must not grow the map without limit.
func TestBrowseLimiterCapacity(t *testing.T) {
	limiter := newBrowseRateLimiter()
	limiter.now = func() time.Time { return time.UnixMilli(1000) }
	for i := 0; i < browseLimiterSlots+10; i++ {
		if !limiter.Allow(fmt.Sprintf("device-%d", i)) {
			t.Fatalf("device-%d was limited on its first frame", i)
		}
	}
	limiter.mu.Lock()
	size := len(limiter.entries)
	limiter.mu.Unlock()
	if size > browseLimiterSlots {
		t.Fatalf("limiter table = %d entries, want at most %d", size, browseLimiterSlots)
	}
}

// TestLegacyRequestDeclinedFast pins the desktop target's answer to the old
// picker/screenshot request kind: an immediate "unsupported" decline, so the
// CLI fails in milliseconds instead of waiting out its five-minute window.
func TestLegacyRequestDeclinedFast(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	requestID, err := NewRequestID()
	if err != nil {
		t.Fatal(err)
	}
	inner := map[string]interface{}{
		"request_id":     requestID,
		"requester_id":   fx.requesterID,
		"requester_name": "Requestor",
		"target_id":      fx.cfg.DeviceID,
		"kind":           "screenshot",
		"expires_ts":     time.Now().Add(5 * time.Minute).UnixMilli(),
		"grant":          strings.Repeat("ab", 32),
		"ts":             time.Now().UnixMilli(),
	}
	envelope, err := sealEnvelope(fx.peerKey, fx.cfg.DeviceID, "request", inner)
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	handleIncomingSealed(fx.cfg, "", fx.peerKey, fx.target, nil, nil, envelope)
	decline := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 2*time.Second)
	if got := envelopeString(decline, "reason"); got != "unsupported" {
		t.Fatalf("legacy request reason = %q, want unsupported", got)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("legacy request took %v to decline, want a fast failure", elapsed)
	}
}

// TestBrowseDoubleDashPath proves the CLI plumbing lets a path literally named
// "-json" through: the sentinel is split off before flag parsing, and the tail
// is appended to the positionals.
func TestBrowseDoubleDashPath(t *testing.T) {
	head, tail := splitDoubleDash([]string{"phone-1", "--", "-json"})
	flags := flag.NewFlagSet("browse", flag.ContinueOnError)
	jsonOut := flags.Bool("json", false, "json")
	if err := flags.Parse(browseArgsFromFlags(head)); err != nil {
		t.Fatalf("parse: %v", err)
	}
	rest := append(append([]string(nil), flags.Args()...), tail...)
	if *jsonOut {
		t.Fatal("the path named -json must not set the json flag")
	}
	if !slices.Equal(rest, []string{"phone-1", "-json"}) {
		t.Fatalf("positionals = %q, want [phone-1 -json]", rest)
	}
}
