package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

type Config struct {
	RelayAddr string
	TokenFile string
	DeviceID  string
	Name      string
	InboxDir  string
}

func defaultInboxDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "./gaur-inbox"
	}
	return filepath.Join(home, "Downloads", "gaur-inbox")
}

func defaultTokenFile() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "./cluster.key"
	}
	return filepath.Join(home, ".config", "gaur-share", "cluster.key")
}

func defaultConfigFile() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "./config.json"
	}
	return filepath.Join(home, ".config", "gaur-share", "config.json")
}

func loadSettingsFromConfig() (string, string) {
	path := defaultConfigFile()
	data, err := os.ReadFile(path)
	if err != nil {
		return "Lilac", "198.51.100.1:9876"
	}
	var conf struct {
		DeviceName string `json:"device_name"`
		Relay      string `json:"relay"`
	}
	name := "Lilac"
	relay := "198.51.100.1:9876"
	if err := json.Unmarshal(data, &conf); err == nil {
		if strings.TrimSpace(conf.DeviceName) != "" {
			name = strings.TrimSpace(conf.DeviceName)
		}
		if strings.TrimSpace(conf.Relay) != "" {
			relay = strings.TrimSpace(conf.Relay)
		}
	}
	return name, relay
}

func loadToken(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		envToken := os.Getenv("GAURPN_CLUSTER_TOKEN")
		if envToken != "" {
			return strings.TrimSpace(envToken), nil
		}
		return "", fmt.Errorf("unable to read token from %s and GAURPN_CLUSTER_TOKEN not set: %w", path, err)
	}
	return strings.TrimSpace(string(data)), nil
}

func generateDeviceID() string {
	home, err := os.UserHomeDir()
	if err == nil {
		idPath := filepath.Join(home, ".config", "gaur-share", "device.id")
		if data, err := os.ReadFile(idPath); err == nil {
			trimmed := strings.TrimSpace(string(data))
			if len(trimmed) > 0 {
				return trimmed
			}
		}
	}

	hostname, _ := os.Hostname()
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	cleanHost := strings.ToLower(strings.ReplaceAll(hostname, " ", "-"))
	if cleanHost == "" {
		cleanHost = "desktop"
	}
	newID := fmt.Sprintf("%s-%x", cleanHost, b)

	if home, err := os.UserHomeDir(); err == nil {
		idDir := filepath.Join(home, ".config", "gaur-share")
		_ = os.MkdirAll(idDir, 0o755)
		_ = os.WriteFile(filepath.Join(idDir, "device.id"), []byte(newID), 0o644)
	}
	return newID
}

func notifyUser(title, message string) {
	switch runtime.GOOS {
	case "linux":
		_ = exec.Command("notify-send", title, message).Run()
	case "darwin":
		script := fmt.Sprintf(`display notification "%s" with title "%s"`, message, title)
		_ = exec.Command("osascript", "-e", script).Run()
	}
}

func connectWS(cfg Config, token string) (*websocket.Conn, error) {
	u := url.URL{
		Scheme: "ws",
		Host:   cfg.RelayAddr,
		Path:   "/ws",
	}
	q := u.Query()
	q.Set("device_id", cfg.DeviceID)
	q.Set("name", cfg.Name)
	q.Set("token", token)
	u.RawQuery = q.Encode()

	conn, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
	return conn, err
}

func runDaemon(cfg Config) {
	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}

	_ = os.MkdirAll(cfg.InboxDir, 0o755)
	log.Printf("[DAEMON] Starting gaur-share daemon as '%s' (ID: %s)", cfg.Name, cfg.DeviceID)
	log.Printf("[DAEMON] Incoming files will be saved to: %s", cfg.InboxDir)

	for {
		conn, err := connectWS(cfg, token)
		if err != nil {
			log.Printf("[DAEMON] Connecting to in-tunnel relay (%s)... Error: %v. Retrying in 5s...", cfg.RelayAddr, err)
			time.Sleep(5 * time.Second)
			continue
		}

		log.Printf("[DAEMON] Connected to in-tunnel relay at %s. Standing by for incoming transfers...", cfg.RelayAddr)

		// Heartbeat ping loop
		stopPing := make(chan struct{})
		go func() {
			ticker := time.NewTicker(20 * time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-ticker.C:
					if err := conn.WriteJSON(map[string]string{"type": "ping"}); err != nil {
						return
					}
				case <-stopPing:
					return
				}
			}
		}()

		// Message loop
		for {
			_, msgBytes, err := conn.ReadMessage()
			if err != nil {
				close(stopPing)
				log.Printf("[DAEMON] Relay connection interrupted: %v. Reconnecting...", err)
				_ = conn.Close()
				break
			}

			var envelope map[string]interface{}
			if err := json.Unmarshal(msgBytes, &envelope); err != nil {
				continue
			}

			msgType, _ := envelope["type"].(string)
			switch msgType {
			case "peers":
				// Peer list update
			case "transfer_offer":
				go handleIncomingTransfer(cfg, token, conn, envelope)
			}
		}

		time.Sleep(2 * time.Second)
	}
}

func handleIncomingTransfer(cfg Config, token string, wsConn *websocket.Conn, offer map[string]interface{}) {
	transferID, _ := offer["transfer_id"].(string)
	senderID, _ := offer["from_id"].(string)
	senderName, _ := offer["from_name"].(string)
	fileName, _ := offer["file_name"].(string)
	fileSizeFloat, _ := offer["file_size"].(float64)
	senderPubB64, _ := offer["pub_key"].(string)
	fileSize := int64(fileSizeFloat)

	if transferID == "" || fileName == "" || senderPubB64 == "" {
		return
	}

	cleanFileName := filepath.Base(fileName)
	senderPubBytes, err := base64.StdEncoding.DecodeString(senderPubB64)
	if err != nil {
		log.Printf("[RECV] Invalid sender public key: %v", err)
		return
	}

	log.Printf("[RECV] Incoming file from %s (%s): %s (%d bytes)", senderName, senderID, cleanFileName, fileSize)
	notifyUser("Incoming File Transfer", fmt.Sprintf("%s is sending %s (%.1f MB)", senderName, cleanFileName, float64(fileSize)/(1024*1024)))

	// Generate receiver ephemeral keypair
	keyPair, err := GenerateKeyPair()
	if err != nil {
		log.Printf("[RECV] Failed to generate ephemeral keypair: %v", err)
		return
	}

	// Derive AES-256-GCM session key
	aesKey, err := DeriveSharedSecret(keyPair.Private, senderPubBytes)
	if err != nil {
		log.Printf("[RECV] Failed to derive shared secret: %v", err)
		return
	}

	// Send transfer_accept with receiver's public key
	acceptMsg := map[string]interface{}{
		"type":        "transfer_accept",
		"target_id":   senderID,
		"transfer_id": transferID,
		"pub_key":     base64.StdEncoding.EncodeToString(keyPair.Public),
	}
	_ = wsConn.WriteJSON(acceptMsg)

	// Connect to pipe
	pipeURL := fmt.Sprintf("http://%s/pipe?transfer_id=%s&role=receiver&token=%s", cfg.RelayAddr, transferID, url.QueryEscape(token))
	req, _ := http.NewRequest("GET", pipeURL, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		log.Printf("[RECV] Failed to connect to pipe: %v", err)
		return
	}
	defer resp.Body.Close()

	destPath := filepath.Join(cfg.InboxDir, cleanFileName)
	outFile, err := os.OpenFile(destPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		log.Printf("[RECV] Failed to create destination file: %v", err)
		notifyUser("Transfer Failed", fmt.Sprintf("Cannot save '%s'", cleanFileName))
		return
	}

	var totalRead int64
	var transferSuccess bool
	defer func() {
		_ = outFile.Close()
		if !transferSuccess || (totalRead == 0 && fileSize > 0) {
			_ = os.Remove(destPath)
			notifyUser("Transfer Failed", fmt.Sprintf("Failed receiving '%s' from %s", cleanFileName, senderName))
		}
	}()

	// Read and decrypt chunks
	lenBuf := make([]byte, 4)
	nonceBuf := make([]byte, 12)

	startTime := time.Now()
	for {
		// Read 4-byte chunk length
		if _, err := io.ReadFull(resp.Body, lenBuf); err != nil {
			if err == io.EOF || err == io.ErrUnexpectedEOF {
				break
			}
			log.Printf("[RECV] Chunk read header error: %v", err)
			return
		}
		chunkLen := binary.BigEndian.Uint32(lenBuf)
		if chunkLen == 0 {
			break // Clean stream EOF sentinel
		}
		if chunkLen > 10*1024*1024 {
			log.Printf("[RECV] Invalid chunk length (%d bytes), aborting", chunkLen)
			return
		}

		// Read 12-byte nonce
		if _, err := io.ReadFull(resp.Body, nonceBuf); err != nil {
			log.Printf("[RECV] Nonce read error: %v", err)
			return
		}

		// Read ciphertext
		cipherBuf := make([]byte, chunkLen)
		if _, err := io.ReadFull(resp.Body, cipherBuf); err != nil {
			log.Printf("[RECV] Ciphertext read error: %v", err)
			return
		}

		plaintext, err := DecryptChunk(aesKey, nonceBuf, cipherBuf)
		if err != nil {
			log.Printf("[RECV] Decryption authentication failed! File may be tampered: %v", err)
			return
		}

		if _, err := outFile.Write(plaintext); err != nil {
			log.Printf("[RECV] File write error: %v", err)
			return
		}
		totalRead += int64(len(plaintext))
	}

	if totalRead > 0 || fileSize == 0 {
		transferSuccess = true
		duration := time.Since(startTime).Seconds()
		if duration <= 0 {
			duration = 0.001
		}
		mbps := (float64(totalRead) / (1024 * 1024)) / duration
		log.Printf("[RECV] SUCCESS: Saved '%s' (%.2f MB at %.1f MB/s)", cleanFileName, float64(totalRead)/(1024*1024), mbps)
		notifyUser("Transfer Complete", fmt.Sprintf("Saved '%s' (%.1f MB)", cleanFileName, float64(totalRead)/(1024*1024)))
	}
}

type JSONPeer struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Status string `json:"status"`
	IsSelf bool   `json:"is_self"`
}

func runList(cfg Config, asJSON bool) {
	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		if asJSON {
			fmt.Println("[]")
			return
		}
		log.Fatalf("[FATAL] %v", err)
	}

	conn, err := connectWS(cfg, token)
	if err != nil {
		if asJSON {
			fmt.Println("[]")
			return
		}
		log.Fatalf("[FATAL] Unable to connect to relay at %s: %v", cfg.RelayAddr, err)
	}
	defer conn.Close()

	_ = conn.SetReadDeadline(time.Now().Add(4 * time.Second))
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			if asJSON {
				fmt.Println("[]")
				return
			}
			log.Fatalf("[FATAL] Timeout or error waiting for peer list: %v", err)
		}

		var envelope map[string]interface{}
		if err := json.Unmarshal(msgBytes, &envelope); err != nil {
			continue
		}

		if envelope["type"] == "peers" {
			peersRaw, _ := envelope["peers"].([]interface{})
			if asJSON {
				out := make([]JSONPeer, 0)
				for _, p := range peersRaw {
					pMap, _ := p.(map[string]interface{})
					id, _ := pMap["id"].(string)
					name, _ := pMap["name"].(string)
					isSelf := (id == cfg.DeviceID) || strings.EqualFold(name, cfg.Name)
					out = append(out, JSONPeer{
						ID:     id,
						Name:   name,
						Status: "online",
						IsSelf: isSelf,
					})
				}
				jsonBytes, _ := json.Marshal(out)
				fmt.Println(string(jsonBytes))
				return
			}

			fmt.Println("\nONLINE CLUSTER DEVICES (IN-TUNNEL)")
			fmt.Println("────────────────────────────────────────────────────────────────────────")
			fmt.Printf("%-24s %-28s %-12s\n", "DEVICE ID", "NAME", "STATUS")
			fmt.Println("────────────────────────────────────────────────────────────────────────")
			for _, p := range peersRaw {
				pMap, _ := p.(map[string]interface{})
				id, _ := pMap["id"].(string)
				name, _ := pMap["name"].(string)
				isSelf := (id == cfg.DeviceID) || strings.EqualFold(name, cfg.Name)
				status := "online"
				if isSelf {
					status = "online (this device)"
				}
				fmt.Printf("%-24s %-28s %-12s\n", id, name, status)
			}
			fmt.Println("────────────────────────────────────────────────────────────────────────\n")
			return
		}
	}
}

func runSend(cfg Config, targetQuery string, filePath string) {
	fileInfo, err := os.Stat(filePath)
	if err != nil {
		log.Fatalf("[FATAL] Cannot open file: %v", err)
	}
	fileSize := fileInfo.Size()
	fileName := filepath.Base(filePath)

	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		log.Fatalf("[FATAL] %v", err)
	}

	conn, err := connectWS(cfg, token)
	if err != nil {
		log.Fatalf("[FATAL] Cannot connect to relay at %s: %v", cfg.RelayAddr, err)
	}
	defer conn.Close()

	// Wait for peers list to locate target
	var targetID, targetName string
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			log.Fatalf("[FATAL] Failed to discover target device: %v", err)
		}
		var envelope map[string]interface{}
		_ = json.Unmarshal(msgBytes, &envelope)
		if envelope["type"] == "peers" {
			peersRaw, _ := envelope["peers"].([]interface{})
			for _, p := range peersRaw {
				pMap, _ := p.(map[string]interface{})
				id, _ := pMap["id"].(string)
				name, _ := pMap["name"].(string)
				if id == targetQuery || strings.EqualFold(name, targetQuery) || strings.Contains(strings.ToLower(name), strings.ToLower(targetQuery)) {
					targetID = id
					targetName = name
					break
				}
			}
			break
		}
	}

	if targetID == "" {
		log.Fatalf("[FATAL] Target device '%s' is not currently online in the tunnel", targetQuery)
	}

	fmt.Printf("Target located: %s (%s)\n", targetName, targetID)
	transferID := fmt.Sprintf("tx-%d", time.Now().UnixNano())

	// Generate sender ephemeral keypair
	keyPair, err := GenerateKeyPair()
	if err != nil {
		log.Fatalf("[FATAL] KeyPair generation failed: %v", err)
	}

	// Send offer
	offerMsg := map[string]interface{}{
		"type":        "transfer_offer",
		"target_id":   targetID,
		"transfer_id": transferID,
		"file_name":   fileName,
		"file_size":   fileSize,
		"pub_key":     base64.StdEncoding.EncodeToString(keyPair.Public),
	}
	_ = conn.WriteJSON(offerMsg)
	fmt.Printf("Sent transfer offer for '%s' (%.2f MB). Waiting for acceptance...\n", fileName, float64(fileSize)/(1024*1024))

	// Wait for acceptance
	var aesKey []byte
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			log.Fatalf("[FATAL] Peer did not accept transfer within timeout: %v", err)
		}
		var envelope map[string]interface{}
		_ = json.Unmarshal(msgBytes, &envelope)
		if envelope["type"] == "transfer_accept" && envelope["transfer_id"] == transferID {
			receiverPubB64, _ := envelope["pub_key"].(string)
			receiverPubBytes, _ := base64.StdEncoding.DecodeString(receiverPubB64)
			aesKey, err = DeriveSharedSecret(keyPair.Private, receiverPubBytes)
			if err != nil {
				log.Fatalf("[FATAL] Key derivation failed: %v", err)
			}
			fmt.Println("Transfer accepted! Ephemeral E2EE session verified.")
			break
		} else if envelope["type"] == "transfer_reject" && envelope["transfer_id"] == transferID {
			log.Fatalf("[FATAL] Peer declined the transfer.")
		}
	}

	// Connect to pipe as sender
	pipeURL := fmt.Sprintf("http://%s/pipe?transfer_id=%s&role=sender&token=%s", cfg.RelayAddr, transferID, url.QueryEscape(token))
	pipeReader, pipeWriter := io.Pipe()

	uploadDone := make(chan error, 1)
	req, _ := http.NewRequest("POST", pipeURL, pipeReader)
	go func() {
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			uploadDone <- err
			return
		}
		if resp != nil {
			_ = resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				uploadDone <- fmt.Errorf("relay returned HTTP %d", resp.StatusCode)
				return
			}
		}
		uploadDone <- nil
	}()

	inFile, err := os.Open(filePath)
	if err != nil {
		log.Fatalf("[FATAL] Cannot read input file: %v", err)
	}
	defer inFile.Close()

	chunkBuf := make([]byte, 64*1024) // 64 KB chunks
	nonceCounter := uint64(0)
	var sentBytes int64
	startTime := time.Now()

	for {
		n, readErr := inFile.Read(chunkBuf)
		if n > 0 {
			nonce := make([]byte, 12)
			binary.BigEndian.PutUint64(nonce[4:], nonceCounter)
			nonceCounter++

			cipherChunk, encErr := EncryptChunk(aesKey, nonce, chunkBuf[:n])
			if encErr != nil {
				log.Fatalf("[FATAL] Encryption failed: %v", encErr)
			}

			// Write 4-byte chunk len + 12-byte nonce + ciphertext
			lenHeader := make([]byte, 4)
			binary.BigEndian.PutUint32(lenHeader, uint32(len(cipherChunk)))
			_, _ = pipeWriter.Write(lenHeader)
			_, _ = pipeWriter.Write(nonce)
			_, _ = pipeWriter.Write(cipherChunk)

			sentBytes += int64(n)
			pct := float64(sentBytes) / float64(fileSize) * 100.0
			speed := (float64(sentBytes) / (1024 * 1024)) / time.Since(startTime).Seconds()
			fmt.Printf("\rStreaming: [%-30s] %.1f%% (%.1f/%.1f MB) - %.1f MB/s",
				strings.Repeat("█", int(pct*0.3)), pct,
				float64(sentBytes)/(1024*1024), float64(fileSize)/(1024*1024), speed)
		}

		if readErr == io.EOF {
			// Write zero-length EOF marker
			zeroHeader := make([]byte, 4)
			_, _ = pipeWriter.Write(zeroHeader)
			break
		}
		if readErr != nil {
			log.Fatalf("\n[FATAL] Error reading file: %v", readErr)
		}
	}

	_ = pipeWriter.Close()
	if err := <-uploadDone; err != nil {
		notifyUser("Transfer Failed", fmt.Sprintf("Failed sending '%s': %v", fileName, err))
		log.Fatalf("\n[FATAL] Pipe upload failed: %v\n", err)
	}

	notifyUser("Transfer Sent", fmt.Sprintf("Sent '%s' (%.1f MB)", fileName, float64(sentBytes)/(1024*1024)))
	fmt.Printf("\nTransfer successfully completed in %.1fs!\n", time.Since(startTime).Seconds())
}

func main() {
	var cfg Config
	var jsonOutput bool
	defaultName, defaultRelay := loadSettingsFromConfig()
	flag.StringVar(&cfg.RelayAddr, "relay", defaultRelay, "In-tunnel relay address (host:port)")
	flag.StringVar(&cfg.TokenFile, "key-file", defaultTokenFile(), "Path to cluster secret key file")
	flag.StringVar(&cfg.DeviceID, "device-id", generateDeviceID(), "Unique device identifier")
	flag.StringVar(&cfg.Name, "name", defaultName, "Human readable device name")
	flag.StringVar(&cfg.InboxDir, "inbox", defaultInboxDir(), "Directory where incoming files are saved")
	flag.BoolVar(&jsonOutput, "json", false, "Output results as JSON")
	flag.Parse()

	args := flag.Args()
	if len(args) == 0 {
		fmt.Println("gaur-share: In-Tunnel Zero-Knowledge File Sharing Client")
		fmt.Println("\nUsage:")
		fmt.Println("  gaur-share daemon                   Run background receiver daemon")
		fmt.Println("  gaur-share list [-json]             List all active cluster devices")
		fmt.Println("  gaur-share send <device> <file>     Send file to a specific device")
		fmt.Println("\nFlags:")
		flag.PrintDefaults()
		return
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigChan
		fmt.Println("\nShutting down gaur-share...")
		os.Exit(0)
	}()

	switch args[0] {
	case "daemon":
		runDaemon(cfg)
	case "list":
		for _, a := range args[1:] {
			if a == "-json" || a == "--json" {
				jsonOutput = true
			}
		}
		runList(cfg, jsonOutput)
	case "send":
		if len(args) < 3 {
			log.Fatalf("Usage: gaur-share send <target-device> <file-path>")
		}
		runSend(cfg, args[1], args[2])
	default:
		log.Fatalf("Unknown command: %s. Valid commands: daemon, list, send", args[0])
	}
}
