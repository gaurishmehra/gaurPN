package main

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
)

type Config struct {
	RelayAddr       string
	TokenFile       string
	DeviceID        string
	SenderID        string
	Name            string
	InboxDir        string
	PeersFile       string
	Clipboard       bool
	ClipboardSource string
	ClipboardSocket string
	LogFile         string
	AutoOpen        bool
	OnlyTrusted     bool
	Notifications   bool
}

func defaultInboxDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return "./gaur-inbox"
	}
	return filepath.Join(home, "Downloads", "gaur-inbox")
}

// ensureInboxDir creates the inbox directory with owner-only permissions and
// tightens a pre-existing directory that other local users can read: the
// inbox holds received plaintext, and it is gaur's own directory. Modes
// stricter than 0700 are left untouched.
func ensureInboxDir(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	info, err := os.Stat(dir)
	if err != nil {
		return err
	}
	if !info.IsDir() {
		return fmt.Errorf("%s is not a directory", dir)
	}
	if info.Mode().Perm()&0o077 != 0 {
		if err := os.Chmod(dir, 0o700); err != nil {
			return err
		}
	}
	return nil
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

func defaultPeersFile() string {
	cache, err := os.UserCacheDir()
	if err != nil {
		return "./gaur-peers.json"
	}
	return filepath.Join(cache, "gaur-share", "peers.json")
}

func defaultClipboardSocket() string {
	cache, err := os.UserCacheDir()
	if err != nil {
		return filepath.Join(os.TempDir(), "gaur-share-clipboard.sock")
	}
	return filepath.Join(cache, "gaur-share", "clipboard.sock")
}

func loadSettingsFromConfig() (string, string) {
	path := defaultConfigFile()
	data, err := os.ReadFile(path)
	if err != nil {
		return "Lilac", "198.51.100.1:9876"
	}
	var conf struct {
		DeviceName   string `json:"device_name"`
		Name         string `json:"name"`
		Relay        string `json:"relay"`
		RelayAddress string `json:"relay_address"`
	}
	name := "Lilac"
	relay := "198.51.100.1:9876"
	if err := json.Unmarshal(data, &conf); err == nil {
		if strings.TrimSpace(conf.DeviceName) != "" {
			name = strings.TrimSpace(conf.DeviceName)
		} else if strings.TrimSpace(conf.Name) != "" {
			name = strings.TrimSpace(conf.Name)
		}
		if strings.TrimSpace(conf.Relay) != "" {
			relay = strings.TrimSpace(conf.Relay)
		} else if strings.TrimSpace(conf.RelayAddress) != "" {
			relay = strings.TrimSpace(conf.RelayAddress)
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
	// A group- or world-readable cluster key hands the whole cluster to every
	// other local user, so refuse to start rather than use it.
	info, statErr := os.Stat(path)
	if statErr != nil {
		return "", fmt.Errorf("cannot verify permissions of token file %s: %w", path, statErr)
	}
	if perms := info.Mode().Perm(); perms&0o077 != 0 {
		return "", fmt.Errorf(
			"token file %s is exposed to other users (mode %04o); refusing to start. Fix with: chmod 600 %s",
			path, perms, path,
		)
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

// setupLogFile points the standard logger at cfg.LogFile when one was given.
// A log file that cannot be opened is ignored: the daemon must stay runnable
// with logs going to stderr.
func setupLogFile(cfg Config) {
	if cfg.LogFile == "" {
		return
	}
	if f, err := os.OpenFile(cfg.LogFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err == nil {
		log.SetOutput(f)
	}
}
