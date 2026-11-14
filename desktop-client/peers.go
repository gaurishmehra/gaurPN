package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// listDeadline bounds how long `gaur-share list` waits for the relay's peer
// list before giving up.
const listDeadline = 4 * time.Second

type peerFileEntry struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Status string `json:"status"`
	IsSelf bool   `json:"is_self"`
}

type peerStore struct {
	mu    sync.RWMutex
	peers []peerFileEntry
}

func (s *peerStore) set(peers []peerFileEntry) {
	s.mu.Lock()
	s.peers = append([]peerFileEntry(nil), peers...)
	s.mu.Unlock()
}

func (s *peerStore) snapshot() []peerFileEntry {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]peerFileEntry(nil), s.peers...)
}

// writePeersFile publishes the current peer list for desktop shells to watch.
// It writes in place rather than via rename so QFileSystemWatcher keeps its
// inode watch and reacts to a single modify event.
//
// Failures are logged here rather than returned: the peers file is best-effort
// state for the desktop shell, the daemon must stay alive when it is
// unwritable, and every caller would otherwise repeat the same handling.
func writePeersFile(cfg Config, peers []peerFileEntry) {
	if cfg.PeersFile == "" {
		return
	}
	if peers == nil {
		peers = []peerFileEntry{}
	}
	payload := struct {
		Updated int64           `json:"updated"`
		Peers   []peerFileEntry `json:"peers"`
	}{Updated: time.Now().Unix(), Peers: peers}
	data, err := json.Marshal(payload)
	if err != nil {
		log.Printf("[PEERS] Could not serialize peer list for %s: %v", cfg.PeersFile, err)
		return
	}
	if err := os.MkdirAll(filepath.Dir(cfg.PeersFile), 0o755); err != nil {
		log.Printf("[PEERS] Could not create directory for %s: %v", cfg.PeersFile, err)
		return
	}
	f, err := os.OpenFile(cfg.PeersFile, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		log.Printf("[PEERS] Could not open %s for writing: %v", cfg.PeersFile, err)
		return
	}
	if _, err := f.Write(data); err != nil {
		log.Printf("[PEERS] Could not write %s: %v", cfg.PeersFile, err)
	}
	if err := f.Close(); err != nil {
		log.Printf("[PEERS] Could not close %s: %v", cfg.PeersFile, err)
	}
}

type JSONPeer struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Status string `json:"status"`
	IsSelf bool   `json:"is_self"`
}

// runList prints the cluster's online peers, as a table or as JSON. In JSON
// mode a failure prints a {"error": ...} object on stdout — scripts parse
// stdout, so an empty array must never mask a failure — and the returned
// error is the caller's cue to print it to stderr and exit non-zero.
func runList(cfg Config, asJSON bool) error {
	token, err := loadToken(cfg.TokenFile)
	if err != nil {
		return listFailure(asJSON, err)
	}
	relayToken, _ := DeriveSubKeys(token)

	// Discovery must not reuse the daemon's device ID: the relay replaces an
	// existing peer with the same ID, so a poll would evict the daemon.
	listCfg := cfg
	listCfg.DeviceID = fmt.Sprintf("list-%d", os.Getpid())

	conn, err := connectWS(listCfg, relayToken)
	if err != nil {
		return listFailure(asJSON, fmt.Errorf("Unable to connect to relay at %s: %w", cfg.RelayAddr, err))
	}
	defer conn.Close()

	_ = conn.SetReadDeadline(time.Now().Add(listDeadline))
	for {
		_, msgBytes, err := conn.ReadMessage()
		if err != nil {
			return listFailure(asJSON, fmt.Errorf("Timeout or error waiting for peer list: %w", err))
		}

		var envelope map[string]interface{}
		if err := json.Unmarshal(msgBytes, &envelope); err != nil {
			continue
		}

		if envelopeString(envelope, "type") == "peers" {
			peersRaw, _ := envelope["peers"].([]interface{})
			if asJSON {
				out := make([]JSONPeer, 0)
				for _, p := range peersRaw {
					pMap, _ := p.(map[string]interface{})
					id, _ := pMap["id"].(string)
					name, _ := pMap["name"].(string)
					if id == listCfg.DeviceID {
						continue
					}
					isSelf := strings.EqualFold(name, cfg.Name)
					out = append(out, JSONPeer{
						ID:     id,
						Name:   name,
						Status: "online",
						IsSelf: isSelf,
					})
				}
				jsonBytes, _ := json.Marshal(out)
				fmt.Println(string(jsonBytes))
				return nil
			}

			fmt.Println("\nONLINE CLUSTER DEVICES (IN-TUNNEL)")
			fmt.Println("────────────────────────────────────────────────────────────────────────")
			fmt.Printf("%-24s %-28s %-12s\n", "DEVICE ID", "NAME", "STATUS")
			fmt.Println("────────────────────────────────────────────────────────────────────────")
			for _, p := range peersRaw {
				pMap, _ := p.(map[string]interface{})
				id, _ := pMap["id"].(string)
				name, _ := pMap["name"].(string)
				if id == listCfg.DeviceID {
					continue
				}
				isSelf := strings.EqualFold(name, cfg.Name)
				status := "online"
				if isSelf {
					status = "online (this device)"
				}
				fmt.Printf("%-24s %-28s %-12s\n", id, name, status)
			}
			fmt.Println("────────────────────────────────────────────────────────────────────────")
			fmt.Println()
			return nil
		}
	}
}

// listFailure reports a failed list run. JSON mode gets a machine-readable
// {"error": ...} object on stdout so shell scripts can parse the failure;
// the returned error still reaches stderr and a non-zero exit via the caller.
func listFailure(asJSON bool, err error) error {
	if asJSON {
		payload, _ := json.Marshal(struct {
			Error string `json:"error"`
		}{Error: err.Error()})
		fmt.Println(string(payload))
	}
	return err
}
