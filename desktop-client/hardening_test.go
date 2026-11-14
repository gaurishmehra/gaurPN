package main

// Cross-cutting hardening tests: transfer-id path traversal (D1), the
// WebSocket read limit (D5.4), and notification/file hygiene (D6).

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// --- D1: transfer-id path traversal ---

func TestIsValidTransferID(t *testing.T) {
	valid := "tx-" + strings.Repeat("ab", 16)
	cases := []struct {
		id   string
		want bool
	}{
		{valid, true},
		{"", false},
		{"tx-", false},
		{"tx-" + strings.Repeat("AB", 16), false},          // uppercase hex
		{"tx-" + strings.Repeat("ab", 15), false},          // too short
		{"tx-" + strings.Repeat("ab", 17), false},          // too long
		{"tx-" + strings.Repeat("zz", 16), false},          // not hex
		{"../" + valid, false},                             // traversal prefix
		{valid + "/../evil", false},                        // traversal suffix
		{"tx-" + strings.Repeat("ab", 15) + "\x00", false}, // NUL
	}
	for _, tc := range cases {
		if got := IsValidTransferID(tc.id); got != tc.want {
			t.Fatalf("IsValidTransferID(%q) = %v, want %v", tc.id, got, tc.want)
		}
	}
}

// TestPartPathsForRejectsTraversal pins the defense-in-depth check: an id
// that slips offer-time validation must not produce a staging path outside
// the inbox. The crafted id needs three ".." components because the id is
// embedded after a "." prefix in the file name.
func TestPartPathsForRejectsTraversal(t *testing.T) {
	dir := t.TempDir()
	inbox := filepath.Join(dir, "inbox")
	if err := os.MkdirAll(inbox, 0o700); err != nil {
		t.Fatal(err)
	}
	cfg := Config{InboxDir: inbox}

	crafted := "../../../.config/autostart/evil"
	raw := partFilePath(cfg, "report.pdf", crafted)
	if IsPathInsideDirectory(raw, inbox) {
		t.Fatalf("test premise broken: %q is already inside the inbox", raw)
	}

	var partPath, statePath string
	var ok bool
	out := captureLog(func() {
		partPath, statePath, ok = partPathsFor(cfg, "report.pdf", crafted)
	})
	if ok {
		t.Fatalf("partPathsFor accepted an escaping id: %q / %q", partPath, statePath)
	}
	if partPath != "" || statePath != "" {
		t.Fatalf("rejected paths must be empty, got %q / %q", partPath, statePath)
	}
	if !contains(out, "escapes the inbox") {
		t.Fatalf("expected one rejection log line, got: %s", out)
	}

	valid := "tx-" + strings.Repeat("ab", 16)
	partPath, statePath, ok = partPathsFor(cfg, "report.pdf", valid)
	if !ok {
		t.Fatal("a valid transfer id must be accepted")
	}
	if !IsPathInsideDirectory(partPath, inbox) || !IsPathInsideDirectory(statePath, inbox) {
		t.Fatalf("valid paths escape the inbox: %q / %q", partPath, statePath)
	}
	if want := filepath.Join(inbox, ".report.pdf."+valid+".part"); partPath != want {
		t.Fatalf("part path = %q, want %q", partPath, want)
	}
	if statePath != partPath+".state" {
		t.Fatalf("state path = %q, want %q", statePath, partPath+".state")
	}
}

// readLegacyReject reads forwarded legacy frames until the transfer_reject for
// transferID arrives and returns its reason.
func readLegacyReject(t *testing.T, conn *websocket.Conn, transferID string) string {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	defer conn.SetReadDeadline(time.Time{})
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("no transfer_reject for %q: %v", transferID, err)
		}
		var envelope map[string]interface{}
		if json.Unmarshal(data, &envelope) != nil {
			continue
		}
		if envelopeString(envelope, "type") != "transfer_reject" ||
			envelopeString(envelope, "transfer_id") != transferID {
			continue
		}
		return envelopeString(envelope, "reason")
	}
}

// TestReceiveRejectsTraversalTransferID drives the v1 and v2 offer paths with
// a crafted transfer id and asserts each rejects it before touching a path.
func TestReceiveRejectsTraversalTransferID(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)
	dir := t.TempDir()
	cfg := Config{
		RelayAddr: f.URL,
		DeviceID:  "desktop-1",
		Name:      "Desktop",
		InboxDir:  filepath.Join(dir, "inbox"),
	}

	recvConn, err := connectWS(cfg, relayToken)
	if err != nil {
		t.Fatalf("receiver connect: %v", err)
	}
	defer recvConn.Close()
	client := &wsClient{conn: recvConn}

	senderCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	senderConn, err := connectWS(senderCfg, relayToken)
	if err != nil {
		t.Fatalf("sender connect: %v", err)
	}
	defer senderConn.Close()

	crafted := "../../../.config/autostart/evil"
	base := map[string]interface{}{
		"type":        "transfer_offer",
		"target_id":   "desktop-1",
		"transfer_id": crafted,
		"from_id":     "phone-1",
		"from_name":   "Lavander",
		"file_name":   "evil.bin",
		"file_size":   float64(10),
		"pub_key":     "AAAA",
		"auth_tag":    "00",
	}

	v1 := make(map[string]interface{}, len(base))
	for k, v := range base {
		v1[k] = v
	}
	v1["v"] = float64(1)
	handleIncomingTransferV1(cfg, relayToken, peerKey, client, v1)
	if reason := readLegacyReject(t, senderConn, crafted); reason != "invalid_transfer_id" {
		t.Fatalf("v1 reject reason = %q, want invalid_transfer_id", reason)
	}

	v2 := make(map[string]interface{}, len(base))
	for k, v := range base {
		v2[k] = v
	}
	v2["v"] = float64(2)
	v2["session_id"] = "session-1"
	v2["file_hash"] = "deadbeef"
	handleIncomingTransferV2(context.Background(), cfg, relayToken, peerKey, client, v2)
	if reason := readLegacyReject(t, senderConn, crafted); reason != "invalid_transfer_id" {
		t.Fatalf("v2 reject reason = %q, want invalid_transfer_id", reason)
	}

	// Nothing may have been staged anywhere: the crafted path resolves
	// outside the inbox, and the inbox itself was never created.
	if _, err := os.Stat(filepath.Join(dir, ".config")); !os.IsNotExist(err) {
		t.Fatalf("a crafted transfer id created a path outside the inbox (stat err: %v)", err)
	}

	// A valid id passes the shape check and reaches the auth-tag gate, which
	// rejects it for the bad tag. That proves the id check is not a blanket
	// rejection.
	valid := "tx-" + strings.Repeat("ab", 16)
	validOffer := map[string]interface{}{
		"type": "transfer_offer", "v": float64(1), "target_id": "desktop-1",
		"transfer_id": valid, "from_id": "phone-1", "from_name": "Lavander",
		"file_name": "ok.bin", "file_size": float64(10),
		"pub_key": "AAAA", "auth_tag": "00",
	}
	handleIncomingTransferV1(cfg, relayToken, peerKey, client, validOffer)
	if reason := readLegacyReject(t, senderConn, valid); reason != "auth_failed" {
		t.Fatalf("valid id reject reason = %q, want auth_failed", reason)
	}
}

// TestSealedOfferRejectsTraversalTransferID pins the same gate on the v3
// sealed path, which returns an error instead of a legacy reject frame.
func TestSealedOfferRejectsTraversalTransferID(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{InboxDir: filepath.Join(dir, "inbox")}
	inner := map[string]interface{}{
		"transfer_id":  "../../../.config/autostart/evil",
		"session_id":   "session-1",
		"file_name":    "evil.bin",
		"file_hash":    strings.Repeat("ab", 32),
		"file_size":    float64(0),
		"chunk_size":   float64(ChunkSize),
		"chunk_count":  float64(0),
		"chunk_hashes": []interface{}{},
	}
	_, err := receiveSealedOfferFile(cfg, "token", []byte("peer"), "desktop-1", "phone-1", inner)
	if err == nil || !contains(err.Error(), "invalid transfer id") {
		t.Fatalf("error = %v, want an invalid transfer id rejection", err)
	}
	if _, err := os.Stat(filepath.Join(dir, ".config")); !os.IsNotExist(err) {
		t.Fatalf("a crafted transfer id created a path outside the inbox (stat err: %v)", err)
	}
}

// --- D5.4: WebSocket read limit ---

// TestWSReadLimitDropsOversizedFrame stands up a malicious relay that sends a
// frame larger than the client limit and asserts the client drops the
// connection instead of buffering it.
func TestWSReadLimitDropsOversizedFrame(t *testing.T) {
	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	done := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		_ = conn.WriteMessage(websocket.TextMessage, make([]byte, wsReadLimit+1024))
		<-done
	}))
	t.Cleanup(func() { close(done) }) // runs before httptest's srv.Close

	cfg := Config{
		RelayAddr: strings.TrimPrefix(srv.URL, "http://"),
		DeviceID:  "desktop-1",
		Name:      "Desktop",
	}
	conn, err := connectWS(cfg, "token")
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer conn.Close()

	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, _, err := conn.ReadMessage(); err == nil {
		t.Fatal("an oversized frame must close the connection")
	} else if !errors.Is(err, websocket.ErrReadLimit) {
		t.Fatalf("error = %v, want websocket.ErrReadLimit", err)
	}
}

// --- D6: notification and file hygiene ---

func TestWritePeersFilePermissions(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "gaur-share")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	peersFile := filepath.Join(dir, "peers.json")
	if err := os.WriteFile(peersFile, []byte("{}"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(peersFile, 0o644); err != nil {
		t.Fatal(err)
	}

	writePeersFile(Config{PeersFile: peersFile}, nil)

	info, err := os.Stat(peersFile)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("peers.json mode = %04o, want 0600", perm)
	}
	dirInfo, err := os.Stat(dir)
	if err != nil {
		t.Fatal(err)
	}
	if perm := dirInfo.Mode().Perm(); perm != 0o700 {
		t.Fatalf("peers directory mode = %04o, want 0700", perm)
	}
}

func TestSetupLogFilePermissions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "daemon.log")
	if err := os.WriteFile(path, []byte("old\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(path, 0o644); err != nil {
		t.Fatal(err)
	}

	old := log.Writer()
	defer log.SetOutput(old)
	setupLogFile(Config{LogFile: path})

	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("log file mode = %04o, want 0600", perm)
	}
}

func TestURLActionNotificationsAreBounded(t *testing.T) {
	oldAction, oldPlain := notifyURLAction, notifyPlainText
	defer func() { notifyURLAction, notifyPlainText = oldAction, oldPlain }()

	// Start from an empty semaphore and restore it afterwards.
	for len(urlActionSlots) > 0 {
		<-urlActionSlots
	}
	defer func() {
		for len(urlActionSlots) > 0 {
			<-urlActionSlots
		}
	}()

	urlActionSlots <- struct{}{}
	urlActionSlots <- struct{}{}

	actionCalled := false
	notifyURLAction = func(string, string) bool {
		actionCalled = true
		return true
	}
	plainCalled := false
	notifyPlainText = func(string, string) { plainCalled = true }

	notifyIncomingText("url", "Lavander", "https://example.com/x")
	if actionCalled {
		t.Fatal("a third action wait must not start while both slots are held")
	}
	if !plainCalled {
		t.Fatal("a full semaphore must fall back to the plain toast")
	}

	// One slot frees up: the action path is used again.
	<-urlActionSlots
	notifyIncomingText("url", "Lavander", "https://example.com/x")
	if !actionCalled {
		t.Fatal("a free slot must use the action notification")
	}
}

func TestStatusPublisherStopIsIdempotent(t *testing.T) {
	p := &StatusPublisher{stop: make(chan struct{})}
	p.Stop()
	p.Stop()
	select {
	case <-p.stop:
	default:
		t.Fatal("Stop must close the stop channel")
	}

	var nilPublisher *StatusPublisher
	nilPublisher.Stop() // must not panic
}
