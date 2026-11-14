package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"strconv"
	"strings"
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
		log.Printf("[NOTIF] Dropped notification from untrusted %s", senderIdentity)
		return
	}
	fromName := envelopeString(envelope, "from_name")
	if fromName == "" {
		fromName = fromID
	}
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
		log.Printf("[NOTIF] Dropped replayed notification from %s (msg_id=%s)", fromID, msgID)
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
		log.Printf("[NOTIF] Authentication failed for notification from %s", fromID)
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
	out, err := exec.Command("notify-send", notifySendArgs(displayTitle, body, iconPath, actionArgs)...).Output()
	if err != nil {
		// Some notification daemons reject action buttons. Fall back to a
		// plain notification rather than dropping the event.
		_ = exec.Command("notify-send", notifySendArgs(displayTitle, body, iconPath, nil)...).Run()
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
		log.Printf("[NOTIF] Could not send action to %s: %v", fromID, err)
	}
}

// notifySendArgs assembles the argument vector for notify-send: fixed
// options, an optional icon, optional "-A" actions, then the "--" sentinel
// and the positional title and body. "--" ends option parsing, so a title or
// body beginning with "-" is delivered as text instead of being read as an
// option.
func notifySendArgs(title, body, iconPath string, actions []string) []string {
	args := []string{"-a", "gaurPN", "-t", "8000"}
	if iconPath != "" {
		args = append(args, "-i", iconPath)
	}
	for _, action := range actions {
		args = append(args, "-A", action)
	}
	return append(args, "--", title, body)
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
