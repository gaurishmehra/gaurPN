package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

type fetchSendCall struct {
	requesterID string
	meta        string
	files       []string
}

// captureFetchSend replaces the send subprocess with a recorder. The restore
// is a t.Cleanup so it runs after the fixture's handler-wait cleanup.
func captureFetchSend(t *testing.T) <-chan fetchSendCall {
	t.Helper()
	calls := make(chan fetchSendCall, 8)
	old := startFetchSend
	startFetchSend = func(cfg Config, requesterID, meta string, files []string) error {
		calls <- fetchSendCall{
			requesterID: requesterID,
			meta:        meta,
			files:       append([]string(nil), files...),
		}
		return nil
	}
	t.Cleanup(func() { startFetchSend = old })
	return calls
}

func (fx *browseTargetFixture) fetchInner(pending *pendingRequest, paths []string) map[string]interface{} {
	return map[string]interface{}{
		"request_id":     pending.RequestID,
		"requester_id":   fx.requesterID,
		"requester_name": "Requestor",
		"target_id":      fx.cfg.DeviceID,
		"paths":          paths,
		"expires_ts":     pending.ExpiresTs,
		"grant":          pending.Grant,
		"ts":             time.Now().UnixMilli(),
	}
}

func (fx *browseTargetFixture) sendFetch(t *testing.T, inner map[string]interface{}) {
	t.Helper()
	envelope, err := sealEnvelope(fx.peerKey, fx.cfg.DeviceID, "fetch", inner)
	if err != nil {
		t.Fatalf("seal fetch: %v", err)
	}
	if err := fx.requester.WriteJSON(envelope); err != nil {
		t.Fatalf("send fetch: %v", err)
	}
}

func (fx *browseTargetFixture) expectDecline(t *testing.T, requestID string) string {
	t.Helper()
	reply := readSealedInner(t, fx.requester, fx.peerKey, "request_declined", 5*time.Second)
	if got := envelopeString(reply, "request_id"); got != requestID {
		t.Fatalf("decline request_id = %q, want %q", got, requestID)
	}
	return envelopeString(reply, "reason")
}

// --- fetch target ---

func TestFetchTargetSendsFiles(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	calls := captureFetchSend(t)
	fx.startTargetLoop(t)

	pending, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	fx.sendFetch(t, fx.fetchInner(pending, []string{"alpha.txt", "nested/child.txt"}))

	select {
	case call := <-calls:
		if call.requesterID != fx.requesterID {
			t.Fatalf("send requester = %q, want %q", call.requesterID, fx.requesterID)
		}
		wantMeta := fmt.Sprintf("%s:%s:%d", pending.RequestID, pending.Grant, pending.ExpiresTs)
		if call.meta != wantMeta {
			t.Fatalf("send meta = %q, want %q", call.meta, wantMeta)
		}
		wantFiles := []string{
			filepath.Join(fx.root, "alpha.txt"),
			filepath.Join(fx.root, "nested", "child.txt"),
		}
		if !slices.Equal(call.files, wantFiles) {
			t.Fatalf("send files = %q, want %q", call.files, wantFiles)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("fetch was not accepted")
	}
}

func TestFetchTargetDeclines(t *testing.T) {
	thirtyThree := make([]string, fetchMaxPaths+1)
	for i := range thirtyThree {
		thirtyThree[i] = "alpha.txt"
	}

	cases := []struct {
		name        string
		paths       []string
		onlyTrusted bool
		untrust     bool
		maxTotal    int64
		mutate      func(*browseTargetFixture, *pendingRequest, map[string]interface{})
		wantReason  string
	}{
		{
			name: "missing path", paths: []string{"missing"},
			wantReason: "not_found",
		},
		{
			name: "dotfile", paths: []string{".hidden"},
			wantReason: "not_found",
		},
		{
			name: "symlink escape", paths: []string{"escape/leak.txt"},
			wantReason: "not_found",
		},
		{
			name: "directory", paths: []string{"Beta"},
			wantReason: "not_found",
		},
		{
			name: "too many paths", paths: thirtyThree,
			wantReason: "too_large",
		},
		{
			name: "total size cap", paths: []string{"alpha.txt", "zeta.bin"}, maxTotal: 10,
			wantReason: "too_large",
		},
		{
			name: "expired", paths: []string{"alpha.txt"},
			mutate: func(_ *browseTargetFixture, _ *pendingRequest, inner map[string]interface{}) {
				inner["expires_ts"] = time.Now().Add(-time.Minute).UnixMilli()
			},
			wantReason: "expired",
		},
		{
			name: "expiry beyond the skew window", paths: []string{"alpha.txt"},
			mutate: func(_ *browseTargetFixture, _ *pendingRequest, inner map[string]interface{}) {
				inner["expires_ts"] = time.Now().Add(10 * time.Minute).UnixMilli()
			},
			wantReason: "expired",
		},
		{
			name: "untrusted", paths: []string{"alpha.txt"}, untrust: true,
			wantReason: "not_allowed",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fx := newBrowseTargetFixture(t)
			if tc.name == "symlink escape" && !fx.escapeOK {
				t.Skip("symlinks are unavailable on this platform")
			}
			fx.cfg.OnlyTrusted = tc.onlyTrusted
			if tc.untrust {
				if _, err := untrustDevice(fx.requesterID); err != nil {
					t.Fatalf("untrustDevice: %v", err)
				}
			}
			calls := captureFetchSend(t)
			fx.startTargetLoop(t)
			if tc.maxTotal > 0 {
				old := fetchMaxTotalBytes
				fetchMaxTotalBytes = tc.maxTotal
				defer func() { fetchMaxTotalBytes = old }()
			}

			pending, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
			if err != nil {
				t.Fatal(err)
			}
			inner := fx.fetchInner(pending, tc.paths)
			if tc.mutate != nil {
				tc.mutate(fx, pending, inner)
			}
			fx.sendFetch(t, inner)
			if reason := fx.expectDecline(t, pending.RequestID); reason != tc.wantReason {
				t.Fatalf("decline reason = %q, want %q", reason, tc.wantReason)
			}
			select {
			case <-calls:
				t.Fatal("a declined fetch must not spawn a send subprocess")
			default:
			}
		})
	}
}

func TestFetchTargetTrustedAccepted(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	fx.cfg.OnlyTrusted = true
	if err := trustDevice(fx.requesterID, "Requestor"); err != nil {
		t.Fatalf("trustDevice: %v", err)
	}
	calls := captureFetchSend(t)
	fx.startTargetLoop(t)

	pending, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	fx.sendFetch(t, fx.fetchInner(pending, []string{"alpha.txt"}))
	select {
	case <-calls:
	case <-time.After(5 * time.Second):
		t.Fatal("a trusted fetch was not accepted")
	}
}

func TestFetchTargetDropsMalformed(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(*browseTargetFixture, map[string]interface{})
	}{
		{
			name: "self-addressed",
			mutate: func(fx *browseTargetFixture, inner map[string]interface{}) {
				inner["requester_id"] = fx.cfg.DeviceID
			},
		},
		{
			name: "invalid grant",
			mutate: func(_ *browseTargetFixture, inner map[string]interface{}) {
				inner["grant"] = "not-a-grant"
			},
		},
		{
			name: "missing paths",
			mutate: func(_ *browseTargetFixture, inner map[string]interface{}) {
				delete(inner, "paths")
			},
		},
		{
			name: "non-string path",
			mutate: func(_ *browseTargetFixture, inner map[string]interface{}) {
				inner["paths"] = []interface{}{float64(1)}
			},
		},
		{
			name: "wrong target",
			mutate: func(_ *browseTargetFixture, inner map[string]interface{}) {
				inner["target_id"] = "phone-2"
			},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fx := newBrowseTargetFixture(t)
			calls := captureFetchSend(t)
			fx.startTargetLoop(t)

			pending, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
			if err != nil {
				t.Fatal(err)
			}
			inner := fx.fetchInner(pending, []string{"alpha.txt"})
			tc.mutate(fx, inner)
			fx.sendFetch(t, inner)

			if _, ok := tryReadSealedInner(fx.requester, fx.peerKey, "request_declined", 200*time.Millisecond); ok {
				t.Fatal("a malformed fetch must be dropped silently")
			}
			select {
			case <-calls:
				t.Fatal("a malformed fetch must not spawn a send subprocess")
			default:
			}
		})
	}
}

func TestFetchTargetBusy(t *testing.T) {
	fx := newBrowseTargetFixture(t)

	// This test isolates the one-fetch-at-a-time guard; disable the
	// per-origin cooldown so the third fetch is not declined by it.
	oldWindow := fetchOriginWindow
	fetchOriginWindow = 0
	t.Cleanup(func() { fetchOriginWindow = oldWindow })

	entered := make(chan struct{})
	release := make(chan struct{})
	var mu sync.Mutex
	calls := 0
	old := startFetchSend
	startFetchSend = func(cfg Config, requesterID, meta string, files []string) error {
		mu.Lock()
		calls++
		n := calls
		mu.Unlock()
		if n == 1 {
			close(entered)
			<-release
		}
		return nil
	}
	t.Cleanup(func() { startFetchSend = old })
	fx.startTargetLoop(t)

	// If an assertion fails before the release, the handler-wait cleanup must
	// not deadlock on the blocked first send.
	var releaseOnce sync.Once
	releaseFn := func() { releaseOnce.Do(func() { close(release) }) }
	t.Cleanup(releaseFn)

	first, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	fx.sendFetch(t, fx.fetchInner(first, []string{"alpha.txt"}))
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("first fetch never reached the send subprocess")
	}

	second, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	fx.sendFetch(t, fx.fetchInner(second, []string{"zeta.bin"}))
	if reason := fx.expectDecline(t, second.RequestID); reason != "busy" {
		t.Fatalf("second fetch reason = %q, want busy", reason)
	}

	releaseFn()
	waitFor(t, 5*time.Second, func() bool { return !fetchBusy.Load() })

	third, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	fx.sendFetch(t, fx.fetchInner(third, []string{"alpha.txt"}))
	waitFor(t, 5*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return calls == 2
	})
}

func TestFetchTargetDropsDuplicateRequestID(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	calls := captureFetchSend(t)
	fx.startTargetLoop(t)

	pending, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	inner := fx.fetchInner(pending, []string{"alpha.txt"})
	fx.sendFetch(t, inner)
	select {
	case <-calls:
	case <-time.After(5 * time.Second):
		t.Fatal("first fetch was not accepted")
	}

	// Same request id, fresh envelope: the inner dedup must drop it.
	fx.sendFetch(t, inner)
	select {
	case <-calls:
		t.Fatal("duplicate request id must not spawn a second send")
	case <-time.After(300 * time.Millisecond):
	}
}

func TestFetchDispatch(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	calls := captureFetchSend(t)

	pending, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	inner := fx.fetchInner(pending, []string{"alpha.txt"})
	envelope, err := sealEnvelope(fx.peerKey, fx.cfg.DeviceID, "fetch", inner)
	if err != nil {
		t.Fatalf("seal fetch: %v", err)
	}

	// A fetch has no sender_id; the sealed dispatcher must route it before
	// the generic sender gate.
	handleIncomingSealed(fx.cfg, "", fx.peerKey, fx.target, nil, nil, envelope)
	select {
	case call := <-calls:
		if call.requesterID != fx.requesterID {
			t.Fatalf("send requester = %q, want %q", call.requesterID, fx.requesterID)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("dispatcher did not route the fetch")
	}
	// The handler releases the busy slot after startFetchSend returns; wait
	// for that so no goroutine outlives the test.
	waitFor(t, 5*time.Second, func() bool { return !fetchBusy.Load() })
}

func TestFetchTotalCapDefault(t *testing.T) {
	if fetchMaxTotalBytes != 4<<30 {
		t.Fatalf("fetchMaxTotalBytes = %d, want 4 GiB", fetchMaxTotalBytes)
	}
	if fetchMaxPaths != 32 {
		t.Fatalf("fetchMaxPaths = %d, want 32", fetchMaxPaths)
	}
}

func TestFetchReceiveCapDefault(t *testing.T) {
	if fetchReceiveMaxTotalBytes != 4<<30 {
		t.Fatalf("fetchReceiveMaxTotalBytes = %d, want 4 GiB", fetchReceiveMaxTotalBytes)
	}
	if fetchSendTimeout != 30*time.Minute {
		t.Fatalf("fetchSendTimeout = %v, want 30m", fetchSendTimeout)
	}
}

func TestFetchOriginBudget(t *testing.T) {
	limiter := newFetchOriginBudget()
	now := time.UnixMilli(1_000_000)
	limiter.now = func() time.Time { return now }

	if !limiter.Allow("laptop-1") {
		t.Fatal("the first fetch from an origin must be accepted")
	}
	if limiter.Allow("laptop-1") {
		t.Fatal("a second fetch inside the window must be rejected")
	}
	now = now.Add(fetchOriginWindow - time.Millisecond)
	if limiter.Allow("laptop-1") {
		t.Fatal("the budget must not recover one millisecond early")
	}
	now = now.Add(time.Millisecond)
	if !limiter.Allow("laptop-1") {
		t.Fatal("the budget must recover after the window")
	}
	if !limiter.Allow("laptop-2") {
		t.Fatal("a different origin has its own budget")
	}
}

func TestFetchTargetOriginBudgetDeclinesSecond(t *testing.T) {
	fx := newBrowseTargetFixture(t)
	calls := captureFetchSend(t)
	fx.startTargetLoop(t)

	first, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	fx.sendFetch(t, fx.fetchInner(first, []string{"alpha.txt"}))
	select {
	case <-calls:
	case <-time.After(5 * time.Second):
		t.Fatal("the first fetch was not accepted")
	}
	waitFor(t, 5*time.Second, func() bool { return !fetchBusy.Load() })

	second, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	fx.sendFetch(t, fx.fetchInner(second, []string{"zeta.bin"}))
	if reason := fx.expectDecline(t, second.RequestID); reason != "busy" {
		t.Fatalf("second fetch reason = %q, want busy", reason)
	}
	select {
	case <-calls:
		t.Fatal("the origin budget must stop a second send inside the window")
	case <-time.After(200 * time.Millisecond):
	}
}

func TestFetchAcceptedNotifiesBeforeSend(t *testing.T) {
	fx := newBrowseTargetFixture(t)

	var mu sync.Mutex
	var events []string
	var notifiedLabel string
	var notifiedCount int

	oldNotify := notifyFetchAccepted
	notifyFetchAccepted = func(label string, count int) {
		mu.Lock()
		defer mu.Unlock()
		events = append(events, "notify")
		notifiedLabel, notifiedCount = label, count
	}
	t.Cleanup(func() { notifyFetchAccepted = oldNotify })

	oldSend := startFetchSend
	startFetchSend = func(cfg Config, requesterID, meta string, files []string) error {
		mu.Lock()
		defer mu.Unlock()
		events = append(events, "send")
		return nil
	}
	t.Cleanup(func() { startFetchSend = oldSend })
	fx.startTargetLoop(t)

	pending, err := newPendingRequest(fx.peerKey, fx.requesterID, fx.cfg.DeviceID, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	fx.sendFetch(t, fx.fetchInner(pending, []string{"alpha.txt"}))

	waitFor(t, 5*time.Second, func() bool {
		mu.Lock()
		defer mu.Unlock()
		return len(events) == 2
	})
	mu.Lock()
	defer mu.Unlock()
	if !slices.Equal(events, []string{"notify", "send"}) {
		t.Fatalf("event order = %q, want notify before send", events)
	}
	if notifiedCount != 1 {
		t.Fatalf("notified count = %d, want 1", notifiedCount)
	}
	if !strings.Contains(notifiedLabel, "Requestor") {
		t.Fatalf("notified label = %q, want the sanitized peer label", notifiedLabel)
	}
}

func TestNotifyFetchAcceptedArgs(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("notify-send is Linux-only")
	}
	dir := t.TempDir()
	argvPath := filepath.Join(dir, "argv")
	t.Setenv("PATH", dir)
	t.Setenv("GAUR_NOTIFY_ARGV", argvPath)
	writeExecutable(t, filepath.Join(dir, "notify-send"),
		"printf '%s\\n' \"$@\" > \"$GAUR_NOTIFY_ARGV\"")

	notifyFetchAcceptedDesktop("Lilac (lilac-76…)", 2)
	waitFor(t, 5*time.Second, func() bool {
		data, err := os.ReadFile(argvPath)
		return err == nil && len(data) > 0
	})
	data, err := os.ReadFile(argvPath)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	want := []string{
		"-a", "gaurPN", "--", "Files shared",
		"Lilac (lilac-76…) fetched 2 file(s)",
	}
	if !slices.Equal(lines, want) {
		t.Fatalf("notify-send argv = %q, want %q", lines, want)
	}
}

func TestSavedMsgSetFIFO(t *testing.T) {
	set := newSavedMsgSet(2)
	set.Add("a")
	set.Add("b")
	if !set.Has("a") || !set.Has("b") {
		t.Fatal("both ids must be remembered")
	}
	set.Add("c")
	if set.Has("a") {
		t.Fatal("the oldest id must be evicted")
	}
	if !set.Has("b") || !set.Has("c") {
		t.Fatal("the newest ids must survive")
	}
}

// --- fetch command ---

// stageFetchFile stages one v3 file on the fake relay and returns the sealed
// offer inner payload that answers a fetch.
func stageFetchFile(
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

// TestFetchCommandReceivesBatch pins the multi-file contract: the target's
// send subprocess emits one offer per file with batch_total set, and the
// requester must keep receiving until the whole batch is saved and verified.
func TestFetchCommandReceivesBatch(t *testing.T) {
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
		baseInners[i] = stageFetchFile(
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
			if err != nil || kind != "fetch" {
				continue
			}
			requestID := envelopeString(inner, "request_id")
			requesterID := envelopeString(inner, "requester_id")
			if got := parseStringArray(inner["paths"]); !slices.Equal(got, names) {
				phoneDone <- fmt.Errorf("fetch paths = %q, want %q", got, names)
				return
			}
			if origin := envelopeString(inner, "origin_id"); origin != "laptop-1" {
				phoneDone <- fmt.Errorf("origin_id = %q, want laptop-1", origin)
				return
			}
			if envelopeString(inner, "target_id") != "phone-1" {
				phoneDone <- fmt.Errorf("target_id = %q", envelopeString(inner, "target_id"))
				return
			}
			if !strings.HasPrefix(requesterID, "reqdev-") {
				phoneDone <- fmt.Errorf("requester_id = %q, want an ephemeral reqdev- id", requesterID)
				return
			}
			expiresTs := envelopeInt64(inner, "expires_ts")
			grant := envelopeString(inner, "grant")
			if !VerifyRequestGrant(peerKey, requestID, requesterID, "phone-1", expiresTs, grant) {
				phoneDone <- fmt.Errorf("grant does not verify")
				return
			}
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
		DeviceID:  "laptop-1",
		Name:      "Laptop",
		InboxDir:  inbox,
	}
	if err := runFetch(reqCfg, "phone-1", names); err != nil {
		t.Fatalf("runFetch: %v", err)
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

// TestFetchCommandReceivesDuplicateContentBatch pins the dedupe key: two files
// with identical bytes share a transfer id, but they are distinct offers
// (different sealed msg_ids and names), so both must be saved.
func TestFetchCommandReceivesDuplicateContentBatch(t *testing.T) {
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
		baseInners[i] = stageFetchFile(
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
			if err != nil || kind != "fetch" {
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
		DeviceID:  "laptop-1",
		Name:      "Laptop",
		InboxDir:  inbox,
	}
	if err := runFetch(reqCfg, "phone-1", names); err != nil {
		t.Fatalf("runFetch: %v", err)
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

func TestFetchCommandDeclined(t *testing.T) {
	cases := []struct {
		name       string
		wireReason string
		wantErr    string
	}{
		{"pinned reason", "busy", "Declined (busy)"},
		{"not found", "not_found", "Declined (not_found)"},
		{"too large", "too_large", "Declined (too_large)"},
		{"unknown reason", "something-else", "Declined (declined)"},
		{"reason with a newline", "declined\n[FETCH] accepted req-1", "Declined (declined)"},
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
					if err != nil || kind != "fetch" {
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
				DeviceID:  "laptop-1",
				Name:      "Laptop",
				InboxDir:  filepath.Join(dir, "inbox"),
			}
			err = runFetch(reqCfg, "phone-1", []string{"one.bin"})
			if err == nil || err.Error() != tc.wantErr {
				t.Fatalf("runFetch error = %v, want %s", err, tc.wantErr)
			}
			if phoneErr := <-phoneDone; phoneErr != nil {
				t.Fatalf("phone: %v", phoneErr)
			}
		})
	}
}

func TestFetchCommandTimesOut(t *testing.T) {
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
		DeviceID:  "laptop-1",
		Name:      "Laptop",
		InboxDir:  filepath.Join(dir, "inbox"),
	}
	err = runFetch(reqCfg, "phone-1", []string{"one.bin"})
	if err == nil || err.Error() != "Request timed out" {
		t.Fatalf("runFetch error = %v, want Request timed out", err)
	}
}

func TestFetchCommandRelayDrop(t *testing.T) {
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
			if err != nil || kind != "fetch" {
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
		DeviceID:  "laptop-1",
		Name:      "Laptop",
		InboxDir:  filepath.Join(dir, "inbox"),
	}
	err = runFetch(reqCfg, "phone-1", []string{"one.bin"})
	if err == nil || !strings.Contains(err.Error(), "Relay connection lost") {
		t.Fatalf("runFetch error = %v, want Relay connection lost", err)
	}
}

func TestFetchCommandRejectsBadPaths(t *testing.T) {
	cfg := Config{}
	if err := runFetch(cfg, "phone-1", nil); err == nil || !strings.Contains(err.Error(), "no paths") {
		t.Fatalf("empty paths error = %v", err)
	}
	tooMany := make([]string, fetchMaxPaths+1)
	for i := range tooMany {
		tooMany[i] = "a.txt"
	}
	if err := runFetch(cfg, "phone-1", tooMany); err == nil || !strings.Contains(err.Error(), "too many") {
		t.Fatalf("too many paths error = %v", err)
	}
	for _, bad := range []string{"", "../x", "/etc/passwd", "a//b", "a/../b"} {
		if err := runFetch(cfg, "phone-1", []string{bad}); err == nil || !strings.Contains(err.Error(), "invalid path") {
			t.Fatalf("path %q error = %v", bad, err)
		}
	}
}

// TestFetchCommandReceiveWindowCloses pins the window semantics: the request
// TTL bounds only the wait for the first offer; after it, the size-based
// completion timeout (with its floor) bounds the rest of the batch.
func TestFetchCommandReceiveWindowCloses(t *testing.T) {
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

	pending, err := newPendingRequest(peerKey, requesterID, "phone-1", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	// One offer of a three-file batch: the rest never arrives. The offer is
	// already in the requester's socket when the loop starts, so the first
	// read cannot race the 50 ms request wait.
	offerInner := stageFetchFile(
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
	err = waitForFetchedFiles(
		Config{RelayAddr: f.URL, InboxDir: inbox}, relayToken, peerKey,
		&wsClient{conn: requesterConn}, pending, 3,
	)
	if err == nil || err.Error() != "1 of 3 files received" {
		t.Fatalf("a partial batch must report the shortfall, got %v", err)
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

// TestFetchCommandDeadlineBoundsLoop pins the other half of the deadline
// change: with the per-offer expiry check gone, the loop's own read deadline
// is what bounds the exchange. An already-expired grant times out immediately.
func TestFetchCommandDeadlineBoundsLoop(t *testing.T) {
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
	pending, err := newPendingRequest(peerKey, requesterID, "phone-1", time.Now().Add(-2*requestTTL))
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	err = waitForFetchedFiles(Config{RelayAddr: f.URL}, relayToken, peerKey, &wsClient{conn: conn}, pending, 1)
	if err == nil || err.Error() != "Request timed out" {
		t.Fatalf("error = %v, want Request timed out", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("expired grant took %v to time out", elapsed)
	}
}

// TestScreenshotCommand pins the renamed quick action: it still sends the
// sealed "request" kind with kind "screenshot" and receives the offer through
// the grant-verified loop.
func TestScreenshotCommand(t *testing.T) {
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

	payload := bytes.Repeat([]byte("s"), 4096)
	offerBase := stageFetchFile(
		t, f, relayToken, peerKey, "screenshot.png", payload, 0, 1, "", "",
	)

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
			if got := envelopeString(inner, "kind"); got != "screenshot" {
				phoneDone <- fmt.Errorf("request kind = %q, want screenshot", got)
				return
			}
			if got := envelopeString(inner, "origin_id"); got != "laptop-1" {
				phoneDone <- fmt.Errorf("origin_id = %q, want laptop-1", got)
				return
			}
			requestID := envelopeString(inner, "request_id")
			requesterID := envelopeString(inner, "requester_id")
			expiresTs := envelopeInt64(inner, "expires_ts")
			if !VerifyRequestGrant(peerKey, requestID, requesterID, "phone-1", expiresTs, envelopeString(inner, "grant")) {
				phoneDone <- fmt.Errorf("grant does not verify")
				return
			}
			offerInner := make(map[string]interface{}, len(offerBase)+2)
			for k, v := range offerBase {
				offerInner[k] = v
			}
			offerInner["request_id"] = requestID
			offerInner["grant"] = envelopeString(inner, "grant")
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
		DeviceID:  "laptop-1",
		Name:      "Laptop",
		InboxDir:  inbox,
	}
	if err := runScreenshot(reqCfg, "phone-1"); err != nil {
		t.Fatalf("runScreenshot: %v", err)
	}
	if err := <-phoneDone; err != nil {
		t.Fatalf("phone: %v", err)
	}
	got, err := os.ReadFile(filepath.Join(inbox, "screenshot.png"))
	if err != nil {
		t.Fatalf("screenshot missing: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("screenshot does not match the original")
	}
}

// TestFetchCommandClampsBatchTotal pins the receive-side clamp: a malicious
// target can claim batch_total=1000, but the requester only waits for the
// number of paths it actually asked for.
func TestFetchCommandClampsBatchTotal(t *testing.T) {
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

	names := []string{"one.bin", "two.bin"}
	payloads := [][]byte{
		bytes.Repeat([]byte("a"), 64),
		bytes.Repeat([]byte("b"), 64),
	}
	baseInners := make([]map[string]interface{}, len(names))
	for i := range names {
		baseInners[i] = stageFetchFile(
			t, f, relayToken, peerKey, names[i], payloads[i], i, 1000, "", "",
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
			if err != nil || kind != "fetch" {
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
		DeviceID:  "laptop-1",
		Name:      "Laptop",
		InboxDir:  inbox,
	}
	if err := runFetch(reqCfg, "phone-1", names); err != nil {
		t.Fatalf("runFetch: %v", err)
	}
	if err := <-phoneDone; err != nil {
		t.Fatalf("phone: %v", err)
	}
	for _, name := range names {
		if _, err := os.Stat(filepath.Join(inbox, name)); err != nil {
			t.Fatalf("clamped batch file %s missing: %v", name, err)
		}
	}
}

// TestFetchCommandReceiveCapKeepsFiles pins the requester-side byte cap: an
// oversized batch stops before the offending file, keeps what was saved, and
// exits non-zero with the shortfall.
func TestFetchCommandReceiveCapKeepsFiles(t *testing.T) {
	oldCap := fetchReceiveMaxTotalBytes
	fetchReceiveMaxTotalBytes = 10
	defer func() { fetchReceiveMaxTotalBytes = oldCap }()

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

	names := []string{"small.bin", "huge.bin"}
	payloads := [][]byte{
		bytes.Repeat([]byte("s"), 4),
		bytes.Repeat([]byte("h"), 100),
	}
	baseInners := make([]map[string]interface{}, len(names))
	for i := range names {
		baseInners[i] = stageFetchFile(
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
			if err != nil || kind != "fetch" {
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
		DeviceID:  "laptop-1",
		Name:      "Laptop",
		InboxDir:  inbox,
	}
	err = runFetch(reqCfg, "phone-1", names)
	if err == nil || err.Error() != "1 of 2 files received" {
		t.Fatalf("runFetch error = %v, want the shortfall", err)
	}
	if err := <-phoneDone; err != nil {
		t.Fatalf("phone: %v", err)
	}
	if _, err := os.Stat(filepath.Join(inbox, names[0])); err != nil {
		t.Fatalf("the file received before the cap must be kept: %v", err)
	}
	if _, err := os.Stat(filepath.Join(inbox, names[1])); !os.IsNotExist(err) {
		t.Fatalf("the oversized file must not be saved (stat err: %v)", err)
	}
}
