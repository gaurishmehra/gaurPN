package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"log"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"
)

type mirroredAction struct {
	Index int    `json:"i"`
	Title string `json:"t"`
	Reply bool   `json:"r"`
}

type mirroredNotification struct {
	Key       string           `json:"key"`
	Package   string           `json:"package"`
	App       string           `json:"app"`
	Title     string           `json:"title"`
	Text      string           `json:"text"`
	BigText   string           `json:"big_text"`
	Subtext   string           `json:"subtext"`
	Category  string           `json:"category"`
	Ongoing   bool             `json:"ongoing"`
	Clearable bool             `json:"clearable"`
	TS        int64            `json:"ts"`
	Icon      string           `json:"icon"`
	Actions   []mirroredAction `json:"actions"`
}

// handleIncomingNotification decrypts a mirrored notification and renders it
// with the source app's icon and action buttons. Actions are sent back to the
// source device, which owns the PendingIntent and any RemoteInput.
func handleIncomingNotification(cfg Config, peerCryptoKey []byte, client *wsClient, envelope map[string]interface{}) {
	if !cfg.Notifications {
		return
	}
	fromID := envelopeString(envelope, "from_id")
	if fromID == "" || fromID == cfg.DeviceID {
		return
	}
	senderIdentity := offerSenderID(envelope)
	if onlyTrustedEnabled(cfg) && senderIdentity != cfg.DeviceID &&
		!isTrustedDevice(senderIdentity) {
		log.Printf("[NOTIF] Dropped notification from untrusted %s", singleLineDisplayName(senderIdentity))
		return
	}
	// fromName is display-only; fromID is bound into the AAD, so only its
	// display copy is sanitized.
	fromName := singleLineDisplayName(envelopeString(envelope, "from_name"))
	if fromName == "" {
		fromName = singleLineDisplayName(fromID)
	}
	logFromID := singleLineDisplayName(fromID)
	msgID := envelopeString(envelope, "msg_id")
	ts := envelopeInt64(envelope, "ts")
	event := envelopeString(envelope, "event")
	nonceB64 := envelopeString(envelope, "nonce")
	dataB64 := envelopeString(envelope, "data")
	if msgID == "" || ts <= 0 || (event != "posted" && event != "removed") ||
		nonceB64 == "" || dataB64 == "" {
		return
	}
	if !replayGuard.Allow("notification", msgID, envelopeRelayTs(envelope)) {
		log.Printf("[NOTIF] Dropped replayed notification from %s (msg_id=%s)", logFromID, singleLineDisplayName(msgID))
		return
	}

	nonce, err := base64.StdEncoding.DecodeString(nonceB64)
	if err != nil {
		return
	}
	ciphertext, err := base64.StdEncoding.DecodeString(dataB64)
	if err != nil {
		return
	}
	aad := []byte(fmt.Sprintf("notification:%s:%s:%d:%s", fromID, msgID, ts, event))
	plaintext, err := DecryptClipboard(
		DeriveNotificationKey(peerCryptoKey), nonce, ciphertext, aad,
	)
	if err != nil {
		log.Printf("[NOTIF] Authentication failed for notification from %s", logFromID)
		return
	}
	if len(plaintext) > 256*1024 {
		return
	}
	var payload mirroredNotification
	if err := json.Unmarshal(plaintext, &payload); err != nil {
		return
	}
	// notify-send cannot reliably retract a notification by id; let it expire.
	if event == "removed" {
		return
	}
	displayMirroredNotification(cfg, client, peerCryptoKey, fromID, fromName, payload)
}

func displayMirroredNotification(
	cfg Config,
	client *wsClient,
	peerCryptoKey []byte,
	fromID, fromName string,
	payload mirroredNotification,
) {
	// The app is already running here, so it posts this notification itself.
	// Showing the mirror on top would duplicate it.
	if notificationAppRunning(payload) {
		label := payload.App
		if label == "" {
			label = payload.Package
		}
		log.Printf(
			"[NOTIF] Suppressed %s notification: the app is running on this desktop",
			singleLineDisplayName(label),
		)
		return
	}

	appName := payload.App
	if appName == "" {
		appName = fromName
	}
	title := payload.Title
	if title == "" {
		title = appName
	}
	body := payload.BigText
	if body == "" {
		body = payload.Text
	}
	displayTitle := title
	if appName != "" && appName != "gaurPN" {
		displayTitle = appName + ": " + title
	}

	iconPath := ""
	if payload.Icon != "" {
		if data, err := base64.StdEncoding.DecodeString(payload.Icon); err == nil {
			if f, err := os.CreateTemp("", "gaur-notif-*.png"); err == nil {
				if _, err := f.Write(data); err == nil {
					iconPath = f.Name()
					defer os.Remove(iconPath)
				}
				_ = f.Close()
			}
		}
	}

	actionArgs := make([]string, 0, len(payload.Actions))
	for _, action := range payload.Actions {
		actionArgs = append(actionArgs, fmt.Sprintf("%d=%s", action.Index, action.Title))
	}
	replaceID := notificationReplaceID(fromID, payload.Key)
	out, err := exec.Command(
		"notify-send",
		notifySendArgsForID(displayTitle, body, iconPath, actionArgs, 8000, replaceID)...,
	).Output()
	if err != nil {
		// Some notification daemons reject action buttons or the replace
		// option. Fall back to a plain notification rather than dropping the
		// event, but keep the replace id when the daemon supports it.
		_ = exec.Command(
			"notify-send",
			notifySendArgs(displayTitle, body, iconPath, nil)...,
		).Run()
		return
	}

	chosen := strings.TrimSpace(string(out))
	if chosen == "" {
		return
	}
	index, err := strconv.Atoi(chosen)
	if err != nil {
		return
	}
	var action *mirroredAction
	for i := range payload.Actions {
		if payload.Actions[i].Index == index {
			action = &payload.Actions[i]
			break
		}
	}
	if action == nil {
		return
	}
	reply := ""
	if action.Reply {
		reply = promptReply(title)
		if reply == "" {
			return
		}
	}
	if err := sendNotificationAction(
		cfg, client, peerCryptoKey, fromID, payload.Key, action.Index, reply,
	); err != nil {
		log.Printf("[NOTIF] Could not send action to %s: %v", singleLineDisplayName(fromID), err)
	}
}

// notifySendArgs assembles the argument vector for notify-send: fixed
// options, an optional icon, optional "-A" actions, then the "--" sentinel
// and the positional title and body. "--" ends option parsing, so a title or
// body beginning with "-" is delivered as text instead of being read as an
// option.
func notifySendArgs(title, body, iconPath string, actions []string) []string {
	return notifySendArgsFor(title, body, iconPath, actions, 8000)
}

// notificationReplaceID is the stable 31-bit id handed to notify-send -r.
// Updates to one Android notification (WhatsApp rewrites the same chat entry
// on every message) then replace the existing desktop bubble instead of
// stacking a new one. Including the sender keeps two phones with the same
// app apart.
func notificationReplaceID(fromID, notifKey string) int32 {
	h := fnv.New32a()
	_, _ = h.Write([]byte(fromID))
	_, _ = h.Write([]byte{0})
	_, _ = h.Write([]byte(notifKey))
	id := int32(h.Sum32() & 0x7fffffff)
	if id == 0 {
		return 1
	}
	return id
}

// notifySendArgsFor is notifySendArgs with an explicit expiry in
// milliseconds. The URL action uses a 30-second expiry so the button stays
// clickable for the whole action wait.
func notifySendArgsFor(title, body, iconPath string, actions []string, timeoutMs int) []string {
	return notifySendArgsForID(title, body, iconPath, actions, timeoutMs, 0)
}

// notifySendArgsForID is notifySendArgsFor with a notify-send -r replace id.
// A zero replaceID leaves the option out entirely, which keeps the URL and
// approval notifications non-replacing.
func notifySendArgsForID(
	title, body, iconPath string,
	actions []string,
	timeoutMs int,
	replaceID int32,
) []string {
	args := []string{"-a", "gaurPN", "-t", strconv.Itoa(timeoutMs)}
	if replaceID != 0 {
		args = append(args, "-r", strconv.FormatInt(int64(replaceID), 10))
	}
	if iconPath != "" {
		args = append(args, "-i", iconPath)
	}
	for _, action := range actions {
		args = append(args, "-A", action)
	}
	return append(args, "--", title, body)
}

const (
	urlActionKey   = "open"
	urlActionLabel = "Open in browser"
	urlActionWait  = 30 * time.Second
)

// notifyURLAction renders a received link with an "Open in browser" action.
// It is a package var so tests can observe the choice without spawning
// notify-send. It returns false when the action notification could not be
// displayed, in which case the caller falls back to the plain toast.
var notifyURLAction = showURLActionNotification

// urlActionNotificationArgs is the exact notify-send argv for a link: one
// action whose key is "open", the canonical URL as the body, and a timeout
// that matches the action wait.
func urlActionNotificationArgs(title, canonicalURL string) []string {
	return notifySendArgsFor(
		title, canonicalURL, "",
		[]string{urlActionKey + "=" + urlActionLabel},
		int(urlActionWait/time.Millisecond),
	)
}

// showURLActionNotification displays the link notification and, if the user
// picks the action, launches the canonical URL with xdg-open. The URL comes
// from ValidateShareURL, never from the raw clipboard text, and it is passed
// as a single argv with no shell. The 30-second context bounds the wait so
// the receiving goroutine exits cleanly when the user ignores it.
func showURLActionNotification(title, canonicalURL string) bool {
	if runtime.GOOS != "linux" {
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), urlActionWait)
	defer cancel()
	out, err := exec.CommandContext(
		ctx, "notify-send", urlActionNotificationArgs(title, canonicalURL)...,
	).Output()
	if err != nil {
		// A notification that hit the wait deadline was displayed and
		// ignored; raising a second plain toast would duplicate it. Only a
		// daemon that rejects the action falls back.
		if ctx.Err() != nil {
			return true
		}
		return false
	}
	if strings.TrimSpace(string(out)) != urlActionKey {
		// Displayed, but the user dismissed it without choosing the action.
		return true
	}
	openCtx, openCancel := context.WithTimeout(context.Background(), 5*time.Second)
	cmd := exec.CommandContext(openCtx, "xdg-open", canonicalURL)
	if err := cmd.Start(); err != nil {
		openCancel()
		log.Printf("[TEXT] Could not open link: %v", err)
		return true
	}
	go func() {
		defer openCancel()
		_ = cmd.Wait()
	}()
	return true
}

// escapeAppleScriptString makes text safe to embed inside an AppleScript
// double-quoted string literal: backslash first, then the double quote.
// Without it a body like `" & quit & "` would break out into live script.
func escapeAppleScriptString(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	return strings.ReplaceAll(s, `"`, `\"`)
}

func promptReply(title string) string {
	if _, err := exec.LookPath("zenity"); err == nil {
		out, err := exec.Command(
			"zenity", "--entry", "--title=Reply", "--text=Reply to "+title,
		).Output()
		if err == nil {
			return strings.TrimSpace(string(out))
		}
	}
	if _, err := exec.LookPath("kdialog"); err == nil {
		out, err := exec.Command(
			"kdialog", "--title", "Reply", "--inputbox", "Reply to "+title,
		).Output()
		if err == nil {
			return strings.TrimSpace(string(out))
		}
	}
	return ""
}

func sendNotificationAction(
	cfg Config,
	client *wsClient,
	peerCryptoKey []byte,
	targetID, notifKey string,
	actionIndex int,
	reply string,
) error {
	if client == nil {
		return fmt.Errorf("relay is not connected")
	}
	inner := map[string]interface{}{
		"sender_id":    cfg.DeviceID,
		"sender_name":  cfg.Name,
		"notif_key":    notifKey,
		"action_index": actionIndex,
	}
	if reply != "" {
		inner["reply"] = reply
	}
	envelope, err := sealEnvelope(peerCryptoKey, targetID, "notification_action", inner)
	if err != nil {
		return err
	}
	return client.WriteJSON(envelope)
}
