package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	qrcode "github.com/skip2/go-qrcode"
)

const (
	defaultPairRelay = "198.51.100.1:9876"
	defaultPairDNS   = "10.0.0.182"
)

// runInit creates a fresh cluster token, writes it with 0600 permissions, and
// prints a pairing QR. The QR encodes a gaur://pair URI that the Android app
// can open directly, so onboarding never requires typing the secret.
func runInit(tokenFile, relayAddr, dns, deviceName string, force bool) error {
	if !force {
		if _, err := os.Stat(tokenFile); err == nil {
			return fmt.Errorf("%s already exists; pass -force to overwrite it", tokenFile)
		}
	}

	token, err := generateToken(32)
	if err != nil {
		return err
	}
	if dir := filepath.Dir(tokenFile); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return err
		}
	}
	if err := os.WriteFile(tokenFile, []byte(token+"\n"), 0o600); err != nil {
		return err
	}
	_ = os.Chmod(tokenFile, 0o600)

	fmt.Printf("Cluster token written to %s (mode 0600)\n\n", tokenFile)
	printPairQR(token, relayAddr, dns, deviceName)
	return nil
}

// runQR prints a pairing QR for the token already on disk. Use this to add a
// device without rotating the cluster token.
func runQR(tokenFile, relayAddr, dns, deviceName string) error {
	data, err := os.ReadFile(tokenFile)
	if err != nil {
		return err
	}
	token := strings.TrimSpace(string(data))
	if token == "" {
		return fmt.Errorf("%s is empty", tokenFile)
	}
	fmt.Printf("Using existing token from %s\n\n", tokenFile)
	printPairQR(token, relayAddr, dns, deviceName)
	return nil
}

func printPairQR(token, relayAddr, dns, deviceName string) {
	pairURI := buildPairURI(relayAddr, token, dns, deviceName)
	fmt.Println("Scan this QR with the gaurPN app, or run:")
	fmt.Println("  gaur-share pair '<uri>'")
	fmt.Println()
	if qr, err := qrcode.New(pairURI, qrcode.Medium); err == nil {
		fmt.Println(qr.ToSmallString(false))
	}
	fmt.Println()
	fmt.Println("Pairing URI:")
	fmt.Println(pairURI)
	fmt.Println()
	fmt.Println("The URI contains the cluster secret. Do not paste it into logs or chat.")
}

func generateToken(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func buildPairURI(relayAddr, token, dns, deviceName string) string {
	q := url.Values{}
	q.Set("relay", relayAddr)
	q.Set("token", token)
	if dns != "" {
		q.Set("dns", dns)
	}
	if deviceName != "" {
		q.Set("name", deviceName)
	}
	return "gaur://pair?" + q.Encode()
}
