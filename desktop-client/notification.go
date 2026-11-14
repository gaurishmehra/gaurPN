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

// runNotify sends a sealed notification to a device. It is the desktop ->
// device direction of the same mirroring channel.
func runNotify(cfg Config, targetQuery, title, body, iconPath string) {
	cfg.SenderID = cfg.DeviceID
	cfg.DeviceID = cfg.SenderID + "-out"

	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	relayToken, peerCryptoKey := DeriveSubKeys(token)

	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		log.Fatalf("[FATAL] Cannot connect to relay at %s: %v", cfg.RelayAddr, err)
	}
	defer conn.Close()
	client := &wsClient{conn: conn}

	targetID, targetName := discoverTarget(conn, targetQuery)
	if targetID == "" {
		targetID = targetQuery
		targetName = targetQuery
	}
	inner := notificationInner(cfg, title, body, iconPath)
	envelope, err := sealEnvelope(peerCryptoKey, targetID, "notification", inner)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	if err := client.WriteJSON(envelope); err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	fmt.Printf("Notification sent to %s (%s)\n", targetName, targetID)
}

// runNotifyAll fans one sealed notification out to every online device. It is
// used by the Quickshell notification server to mirror desktop notifications.
func runNotifyAll(cfg Config, title, body, iconPath string) {
	cfg.SenderID = cfg.DeviceID
	cfg.DeviceID = cfg.SenderID + "-out"

	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}
	relayToken, peerCryptoKey := DeriveSubKeys(token)

	conn, err := connectWS(cfg, relayToken)
	if err != nil {
		log.Fatalf("[FATAL] Cannot connect to relay at %s: %v", cfg.RelayAddr, err)
	}
	defer conn.Close()
	client := &wsClient{conn: conn}

	targets := onlinePeerIDs(conn, cfg.DeviceID, cfg.SenderID)
	if len(targets) == 0 {
		log.Fatalf("[FATAL] No other devices are online")
	}
	inner := notificationInner(cfg, title, body, iconPath)
	sent := 0
	for _, target := range targets {
		envelope, err := sealEnvelope(peerCryptoKey, target, "notification", inner)
		if err != nil {
			log.Printf("[NOTIFY] Could not seal for %s: %v", target, err)
			continue
		}
		if err := client.WriteJSON(envelope); err != nil {
			log.Printf("[NOTIFY] Could not send to %s: %v", target, err)
			continue
		}
		sent++
	}
	fmt.Printf("Notification sent to %d device(s)\n", sent)
}

func notificationInner(cfg Config, title, body, iconPath string) map[string]interface{} {
	key, _ := randomHex(8)
	inner := map[string]interface{}{
		"sender_id":   cfg.SenderID,
		"sender_name": cfg.Name,
		"event":       "posted",
		"key":         "desktop-" + key,
		"app":         cfg.Name,
		"title":       title,
		"text":        body,
		"actions":     []interface{}{},
	}
	if icon := readIconBase64(iconPath); icon != "" {
		inner["icon"] = icon
	}
	return inner
}

func readIconBase64(path string) string {
	if path == "" {
		return ""
	}
	data, err := os.ReadFile(path)
	if err != nil || len(data) > 64*1024 {
		return ""
	}
	return base64.StdEncoding.EncodeToString(data)
}

// handleIncomingNotification decrypts a mirrored notification and renders it
// with the source app's icon and action buttons. Actions are sent back to the
// source device, which owns the PendingIntent and any RemoteInput.
func handleIncomingNotification(cfg Config, peerCryptoKey []byte, client *wsClient, envelope map[string]interface{}) {
	if !cfg.Notifications {
		return
	}
	fromID, _ := envelope["from_id"].(string)
	if fromID == "" || fromID == cfg.DeviceID {
		return
	}
	senderIdentity := offerSenderID(envelope)
	if onlyTrustedEnabled(cfg) && senderIdentity != cfg.DeviceID &&
		!isTrustedDevice(senderIdentity) {
		log.Printf("[NOTIF] Dropped notification from untrusted %s", senderIdentity)
		return
	}
	fromName, _ := envelope["from_name"].(string)
	if fromName == "" {
		fromName = fromID
	}
	msgID, _ := envelope["msg_id"].(string)
	tsFloat, _ := envelope["ts"].(float64)
	ts := int64(tsFloat)
	event, _ := envelope["event"].(string)
	nonceB64, _ := envelope["nonce"].(string)
	dataB64, _ := envelope["data"].(string)
	if msgID == "" || ts <= 0 || (event != "posted" && event != "removed") ||
		nonceB64 == "" || dataB64 == "" {
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

	args := []string{"-a", "gaurPN", "-t", "8000"}
	if iconPath != "" {
		args = append(args, "-i", iconPath)
	}
	for _, action := range payload.Actions {
		args = append(args, "-A", fmt.Sprintf("%d=%s", action.Index, action.Title))
	}
	args = append(args, displayTitle, body)

	out, err := exec.Command("notify-send", args...).Output()
	if err != nil {
		// Some notification daemons reject action buttons. Fall back to a
		// plain notification rather than dropping the event.
		fallback := []string{"-a", "gaurPN", "-t", "8000"}
		if iconPath != "" {
			fallback = append(fallback, "-i", iconPath)
		}
		fallback = append(fallback, displayTitle, body)
		_ = exec.Command("notify-send", fallback...).Run()
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
