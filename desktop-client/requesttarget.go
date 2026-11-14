package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"os/exec"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"
)

// requestPickerTimeout bounds the desktop file picker. A request expires
// after five minutes anyway, so a picker left open past that is declined as
// expired rather than blocking the daemon forever.
var requestPickerTimeout = 5 * time.Minute

// requestNow is the clock behind the request's two expiry checks. It is a
// package var so tests can cross the deadline while the picker is open
// without sleeping.
var requestNow = time.Now

var (
	errPickerUnavailable = errors.New("no file picker available")
	errPickerCanceled    = errors.New("file picker canceled")
)

// requestBusy is the daemon's one-picker-at-a-time guard. Each sealed
// envelope is handled in its own goroutine, so a second request that arrives
// while a picker is open is declined "busy" instead of opening a second
// picker. The slot is released when the picker closes, not when the send
// subprocess finishes.
var requestBusy atomic.Bool

func acquireRequestSlot() bool { return requestBusy.CompareAndSwap(false, true) }
func releaseRequestSlot()      { requestBusy.Store(false) }

// pickRequestFiles and startRequestSend are package vars so tests can drive
// the request handler without a real desktop picker or subprocess.
var (
	pickRequestFiles = pickFilesWithDesktop
	startRequestSend = launchRequestSend
)

// singleLineDisplayName strips line breaks, control characters, and Unicode
// format characters (Cf: zero-width joiners, bidi/RTL overrides, BOM) from a
// peer-supplied display name. Names arrive inside the sealed envelope and are
// shown in the picker title and log lines, so they must not be able to forge
// extra log lines, reorder text, or break the picker text. Only the local
// rendering changes; the wire value is untouched.
func singleLineDisplayName(raw string) string {
	var b strings.Builder
	b.Grow(len(raw))
	for _, r := range raw {
		switch {
		case r == '\u2028' || r == '\u2029': // Unicode line/paragraph separators
			b.WriteRune(' ')
		case r < 0x20 || r == 0x7f || (r >= 0x80 && r <= 0x9f):
			b.WriteRune(' ')
		case unicode.Is(unicode.Cf, r):
			// Drop format characters outright: a space would still leave a
			// visible gap for a zero-width joiner.
		default:
			b.WriteRune(r)
		}
	}
	return strings.TrimSpace(b.String())
}

// peerLabel renders a peer for the picker title and log lines: the sanitized
// display name plus a short prefix of its stable device id, so two devices
// sharing a name (the default is "Lilac") stay distinguishable. The id is
// peer-supplied too and is sanitized before it is shown.
func peerLabel(name, id string) string {
	name = singleLineDisplayName(name)
	id = singleLineDisplayName(id)
	if name == "" {
		name = "unknown device"
	}
	if id == "" {
		return name
	}
	if name == id {
		// The name fell back to the id; repeating it as a suffix is noise.
		return id
	}
	short := id
	if runes := []rune(short); len(runes) > 8 {
		short = string(runes[:8]) + "…"
	}
	return fmt.Sprintf("%s (%s)", name, short)
}

// deviceIDPattern is the relay's connection charset. Critically it excludes
// commas, so a crafted requester_id of "a,b" can never become two targets if
// it is fed to splitTargets.
var deviceIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

// isValidDeviceID reports whether id is a well-formed device id.
func isValidDeviceID(id string) bool { return deviceIDPattern.MatchString(id) }

// handleIncomingRequest is the daemon side of a pull request. It validates
// the request, enforces the trust, replay, expiry, and busy gates, opens the
// desktop picker, and hands the selected files to a send subprocess. Declines
// are sealed back to the requester; the grant and file contents never enter a
// log line.
func handleIncomingRequest(
	cfg Config,
	peerCryptoKey []byte,
	client *wsClient,
	inner map[string]interface{},
) {
	requestID := envelopeString(inner, "request_id")
	requesterID := envelopeString(inner, "requester_id")
	originID := envelopeString(inner, "origin_id")
	requesterName := envelopeString(inner, "requester_name")
	targetID := envelopeString(inner, "target_id")
	kind := envelopeString(inner, "kind")
	expiresTs := envelopeInt64(inner, "expires_ts")
	grant := envelopeString(inner, "grant")

	if !isRequestID(requestID) || requesterID == "" || targetID == "" || expiresTs <= 0 ||
		!isLowerHex(grant, 64) {
		log.Printf("[REQUEST] Dropped malformed request from %s", singleLineDisplayName(requesterID))
		return
	}
	// Device ids are peer-supplied and end up in log lines, the picker title,
	// and a sealed decline address. Validate them against the relay's
	// connection charset before anything uses them: a comma is not allowed,
	// so "a,b" can never split into two targets downstream.
	if !isValidDeviceID(requesterID) || !isValidDeviceID(targetID) ||
		(originID != "" && !isValidDeviceID(originID)) {
		declineRequest(peerCryptoKey, client, requesterID,
			peerLabel("", requesterID), requestID, "not_allowed")
		return
	}
	// The picker title and the outcome lines are the only places this name is
	// shown; keep them one line whatever the peer sent. An empty name falls
	// back to the id, sanitized too, since a malformed id can carry the same
	// characters.
	requesterName = singleLineDisplayName(requesterName)
	if requesterName == "" {
		requesterName = singleLineDisplayName(requesterID)
	}
	if requesterName == "" {
		requesterName = "unknown device"
	}
	// The stable id is what the trust list is keyed by, so it is also the
	// useful half of the label in the picker and log lines.
	trustID := requesterID
	if originID != "" {
		trustID = originID
	}
	requesterLabel := peerLabel(requesterName, trustID)
	if targetID != cfg.DeviceID {
		log.Printf("[REQUEST] Dropped request for %s, not this device (%s)",
			singleLineDisplayName(targetID), cfg.DeviceID)
		return
	}
	// A requester always connects under its ephemeral reqdev- id, so a
	// request that claims this device's own id is forged or buggy. Android
	// drops it too; serving it would open a picker for a transfer to self.
	// origin_id is checked as well: it is the stable id the trust gate uses,
	// so a request claiming this device there is equally self-addressed.
	if requesterID == cfg.DeviceID || originID == cfg.DeviceID {
		log.Printf("[REQUEST] Dropped self-addressed request %s", requestID)
		return
	}

	// Trust gate first: an untrusted device learns nothing about the picker
	// or the busy state. The list is keyed by stable device ids, so a desktop
	// CLI that asks under an ephemeral reqdev- id is judged by the stable
	// origin_id it carries. origin_id is a claim inside the E2E-sealed
	// envelope: the seal proves a cluster member sent it, exactly like an
	// offer's sender_id claim, and the trust list decides whether to believe
	// it. Routing, the grant, and the decline address keep using requester_id.
	if onlyTrustedEnabled(cfg) && !isTrustedDevice(trustID) {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "not_allowed")
		return
	}

	// A duplicate request id is dropped silently: the first delivery is
	// already being handled, and a second decline would confuse the
	// requester's single pending record.
	if replayGuard.Seen("request:" + requestID) {
		return
	}

	// now >= expires_ts is expired, the same boundary the requester applies.
	if requestNow().UnixMilli() >= expiresTs {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "expired")
		return
	}

	if !acquireRequestSlot() {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "busy")
		return
	}
	// The slot's purpose is one picker at a time, so it is released as soon
	// as the picker closes (the handoff point), not when the send subprocess
	// finishes. The deferred release covers every early return before that.
	slotHeld := true
	releaseSlot := func() {
		if slotHeld {
			releaseRequestSlot()
			slotHeld = false
		}
	}
	defer releaseSlot()

	switch kind {
	case "files":
		// Handled below.
	case "screenshot":
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "unsupported")
		return
	default:
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "unsupported")
		return
	}

	files, err := pickRequestFiles(requesterLabel)
	// The picker has closed: hand the slot back before the upload starts.
	releaseSlot()
	// The picker can outlive the request. A selection made after the
	// deadline cannot be auto-accepted — the requester's receive window has
	// closed — so decline it instead of uploading files nobody will take.
	if requestNow().UnixMilli() >= expiresTs {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "expired")
		return
	}
	if err != nil {
		reason := "declined"
		switch {
		case errors.Is(err, errPickerUnavailable):
			reason = "picker_unavailable"
		case errors.Is(err, context.DeadlineExceeded):
			reason = "expired"
		}
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, reason)
		return
	}
	if len(files) == 0 {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "declined")
		return
	}

	meta := fmt.Sprintf("%s:%s:%d", requestID, grant, expiresTs)
	log.Printf("[REQUEST] accepted %s from %s (%d file(s))", requestID, requesterLabel, len(files))
	if err := startRequestSend(cfg, requesterID, meta, files); err != nil {
		log.Printf("[REQUEST] send subprocess for %s failed: %v", requestID, err)
	}
}

// declineRequest seals a request_declined envelope back to the requester and
// logs the pinned one-line outcome. requesterLabel is already sanitized and
// carries the stable id. The grant and the selected files are never logged.
func declineRequest(
	peerCryptoKey []byte,
	client *wsClient,
	requesterID, requesterLabel, requestID, reason string,
) {
	inner := map[string]interface{}{
		"request_id": requestID,
		"reason":     reason,
		"ts":         time.Now().UnixMilli(),
	}
	envelope, err := sealEnvelope(peerCryptoKey, requesterID, "request_declined", inner)
	if err != nil {
		log.Printf("[REQUEST] Could not seal decline for %s: %v", requestID, err)
		return
	}
	if client != nil {
		if err := client.WriteJSON(envelope); err != nil {
			log.Printf("[REQUEST] Could not send decline for %s: %v", requestID, err)
		}
	}
	log.Printf("[REQUEST] declined %s from %s (%s)", requestID, requesterLabel, reason)
}

// pickFilesWithDesktop opens zenity, then kdialog, and returns the selected
// paths. requesterLabel is the already-sanitized "Name (id…)" pair shown in
// the title. It is Linux-only; other platforms report the picker as
// unavailable so the request is declined cleanly and the binary still
// cross-compiles.
func pickFilesWithDesktop(requesterLabel string) ([]string, error) {
	if runtime.GOOS != "linux" {
		return nil, errPickerUnavailable
	}
	ctx, cancel := context.WithTimeout(context.Background(), requestPickerTimeout)
	defer cancel()

	title := "gaurPN: " + singleLineDisplayName(requesterLabel) + " requests files"
	if _, err := exec.LookPath("zenity"); err == nil {
		// The separator is a real newline, so multi-select output splits
		// one path per line.
		return runPicker(ctx, "zenity",
			"--file-selection", "--multiple", "--separator=\n", "--title="+title,
		)
	}
	if _, err := exec.LookPath("kdialog"); err == nil {
		return runPicker(ctx, "kdialog",
			"--getopenfilename", "--multiple", "--separate-output",
		)
	}
	return nil, errPickerUnavailable
}

// runPicker runs one picker command and normalizes its output. A context
// deadline is reported as context.DeadlineExceeded, a user cancel as
// errPickerCanceled, and an empty selection as errPickerCanceled.
func runPicker(ctx context.Context, name string, args ...string) ([]string, error) {
	out, err := exec.CommandContext(ctx, name, args...).Output()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil {
		return nil, errPickerCanceled
	}
	files := splitPickerOutput(string(out))
	if len(files) == 0 {
		return nil, errPickerCanceled
	}
	return files, nil
}

// splitPickerOutput turns newline-separated picker output into paths. Blank
// lines are dropped; paths are otherwise preserved verbatim, including any
// leading or trailing spaces a file name may carry.
func splitPickerOutput(raw string) []string {
	parts := strings.Split(strings.ReplaceAll(raw, "\r\n", "\n"), "\n")
	files := make([]string, 0, len(parts))
	for _, part := range parts {
		if part != "" {
			files = append(files, part)
		}
	}
	return files
}

// requestSendArgs is the exact argv for the send subprocess: the daemon's
// relay identity first, then the send command with the request metadata and
// the picked files. "--" terminates flag parsing so a path that begins with
// "-" is still treated as a file. exec.Command runs this vector directly;
// there is no shell.
func requestSendArgs(cfg Config, requesterID, requestMeta string, files []string) []string {
	args := make([]string, 0, 12+len(files))
	// Forward the daemon's identity and relay settings: the offer's
	// sender_id must be the real device id the requester asked, and a
	// non-default relay/token must not silently fall back to config.json.
	if cfg.RelayAddr != "" {
		args = append(args, "-relay", cfg.RelayAddr)
	}
	if cfg.TokenFile != "" {
		args = append(args, "-key-file", cfg.TokenFile)
	}
	if cfg.DeviceID != "" {
		args = append(args, "-device-id", cfg.DeviceID)
	}
	if cfg.Name != "" {
		args = append(args, "-name", cfg.Name)
	}
	args = append(args, "send", "--to", requesterID, "--request-meta", requestMeta, "--")
	return append(args, files...)
}

// requestSendTimeout bounds one send subprocess. A black-holed relay can
// stall an upload, but not forever; one hour is generous for a tunnel upload
// and well inside the completion-timeout clamp. Package var so tests can
// shrink it.
var requestSendTimeout = time.Hour

// requestSendCmds tracks live send subprocesses so the daemon's shutdown path
// can kill them. os.Exit skips deferred cancels, so a context alone cannot
// clean them up.
var (
	requestSendMu   sync.Mutex
	requestSendCmds = make(map[*exec.Cmd]struct{})
)

// killRequestSubprocesses kills every send subprocess still running. It is
// called from the daemon's graceful-shutdown path. The lock is held across
// the kills so it cannot race a command that is mid-Start.
func killRequestSubprocesses() {
	requestSendMu.Lock()
	defer requestSendMu.Unlock()
	for cmd := range requestSendCmds {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
	}
}

// runTrackedCommand starts cmd, registers it for the shutdown kill, and waits
// for it to finish. Registration happens under the same lock as Start, so the
// shutdown path either sees the command before it starts or after it is
// running — never a started-but-untracked process.
func runTrackedCommand(cmd *exec.Cmd) error {
	requestSendMu.Lock()
	requestSendCmds[cmd] = struct{}{}
	err := cmd.Start()
	if err != nil {
		delete(requestSendCmds, cmd)
	}
	requestSendMu.Unlock()
	if err != nil {
		return err
	}
	defer func() {
		requestSendMu.Lock()
		delete(requestSendCmds, cmd)
		requestSendMu.Unlock()
	}()
	return cmd.Wait()
}

// launchRequestSend starts a send subprocess of this same binary. The
// subprocess owns the whole v3 upload and offer flow, so the daemon loop
// never has to grow transfer state for a pull request. stdout/stderr go to
// the daemon log; exec is used directly, with no shell. The context bounds
// the subprocess lifetime, and the command is tracked so daemon shutdown can
// kill it.
func launchRequestSend(cfg Config, requesterID, requestMeta string, files []string) error {
	exe, err := os.Executable()
	if err != nil {
		return fmt.Errorf("locate gaur-share binary: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), requestSendTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, exe, requestSendArgs(cfg, requesterID, requestMeta, files)...)
	cmd.Stdout = log.Writer()
	cmd.Stderr = log.Writer()
	return runTrackedCommand(cmd)
}
