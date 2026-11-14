package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type TrustedDevice struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	TrustedAt int64  `json:"trusted_at"`
}

func trustedFilePath() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "trusted.json"
	}
	return filepath.Join(home, ".config", "gaur-share", "trusted.json")
}

func loadTrustedDevices() ([]TrustedDevice, error) {
	data, err := os.ReadFile(trustedFilePath())
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var devices []TrustedDevice
	if err := json.Unmarshal(data, &devices); err != nil {
		return nil, err
	}
	return devices, nil
}

func saveTrustedDevices(devices []TrustedDevice) error {
	path := trustedFilePath()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(devices, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, append(data, '\n'), 0o600); err != nil {
		return err
	}
	return os.Chmod(path, 0o600)
}

func isTrustedDevice(id string) bool {
	id = strings.TrimSpace(id)
	if id == "" {
		return false
	}
	devices, err := loadTrustedDevices()
	if err != nil {
		return false
	}
	for _, d := range devices {
		if d.ID == id {
			return true
		}
	}
	return false
}

func trustDevice(id, name string) error {
	id = strings.TrimSpace(id)
	if id == "" {
		return fmt.Errorf("device id is required")
	}
	name = strings.TrimSpace(name)
	if name == "" {
		name = id
	}
	devices, err := loadTrustedDevices()
	if err != nil {
		return err
	}
	out := devices[:0]
	for _, d := range devices {
		if d.ID != id {
			out = append(out, d)
		}
	}
	out = append(out, TrustedDevice{
		ID:        id,
		Name:      name,
		TrustedAt: time.Now().UnixMilli(),
	})
	sort.Slice(out, func(i, j int) bool {
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	return saveTrustedDevices(out)
}

func untrustDevice(id string) (bool, error) {
	id = strings.TrimSpace(id)
	devices, err := loadTrustedDevices()
	if err != nil {
		return false, err
	}
	out := make([]TrustedDevice, 0, len(devices))
	removed := false
	for _, d := range devices {
		if d.ID == id {
			removed = true
			continue
		}
		out = append(out, d)
	}
	if !removed {
		return false, nil
	}
	return true, saveTrustedDevices(out)
}

// resolveTrustTarget accepts an id or a display name and resolves it against
// the daemon's peer cache. If the device is offline, the argument is treated
// as an id so the user can still pin it.
func resolveTrustTarget(query string) (string, string) {
	query = strings.TrimSpace(query)
	if query == "" {
		return "", ""
	}
	if data, err := os.ReadFile(defaultPeersFile()); err == nil {
		var payload struct {
			Peers []peerFileEntry `json:"peers"`
		}
		if json.Unmarshal(data, &payload) == nil {
			for _, p := range payload.Peers {
				if p.IsSelf {
					continue
				}
				if strings.EqualFold(p.ID, query) || strings.EqualFold(p.Name, query) {
					return p.ID, p.Name
				}
			}
		}
	}
	return query, query
}

func loadOnlyTrustedFromConfig() bool {
	data, err := os.ReadFile(defaultConfigFile())
	if err != nil {
		return false
	}
	var conf struct {
		OnlyTrusted bool `json:"only_trusted"`
	}
	if json.Unmarshal(data, &conf) != nil {
		return false
	}
	return conf.OnlyTrusted
}

// setOnlyTrusted preserves the rest of config.json (including legacy fields)
// while flipping the daemon's trust mode.
func setOnlyTrusted(enabled bool) error {
	path := defaultConfigFile()
	conf := map[string]interface{}{}
	if data, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(data, &conf)
	}
	conf["only_trusted"] = enabled
	data, err := json.MarshalIndent(conf, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	if err := os.WriteFile(path, append(data, '\n'), 0o600); err != nil {
		return err
	}
	return os.Chmod(path, 0o600)
}

func onlyTrustedEnabled(cfg Config) bool {
	return cfg.OnlyTrusted || loadOnlyTrustedFromConfig()
}

// offerSenderID prefers the stable sender_id added by newer clients and falls
// back to the relay's connection id for older ones.
func offerSenderID(envelope map[string]interface{}) string {
	if id, _ := envelope["sender_id"].(string); strings.TrimSpace(id) != "" {
		return strings.TrimSpace(id)
	}
	id, _ := envelope["from_id"].(string)
	return strings.TrimSpace(id)
}
