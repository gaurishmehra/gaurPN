package main

import (
	"encoding/base64"
	"errors"
	"fmt"
	"log"
	"os"
)

// notifySession applies the one-shot outbound identity — the "-out" suffix
// keeps the daemon's connection registered — loads the cluster token,
// derives the sub keys, and connects to the relay. It is the shared
// prologue of runNotify and runNotifyAll.
func notifySession(cfg Config) (Config, *wsClient, []byte) {
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
	client := &wsClient{conn: conn}
	return cfg, client, peerCryptoKey
}

// runNotify sends a sealed notification to a device. It is the desktop ->
// device direction of the same mirroring channel.
func runNotify(cfg Config, targetQuery, title, body, iconPath string) {
	cfg, client, peerCryptoKey := notifySession(cfg)
	defer client.Close()

	targetID, targetName, err := discoverTarget(client.conn, targetQuery)
	if err != nil {
		if errors.Is(err, errTargetAmbiguous) {
			// Guessing among several online devices would misroute the
			// notification; make the user pick instead.
			log.Fatalf("[FATAL] %v", err)
		}
		// No match, or the relay never delivered a peer list: the relay
		// mailboxes sealed notifications for offline devices, so address
		// the raw query as a device id like the substring matcher did.
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
	cfg, client, peerCryptoKey := notifySession(cfg)
	defer client.Close()

	targets := onlinePeerIDs(client.conn, cfg.DeviceID, cfg.SenderID)
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
