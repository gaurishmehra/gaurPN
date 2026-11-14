package main

import (
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

type pairConfig struct {
	DeviceName   string `json:"device_name,omitempty"`
	RelayAddr    string `json:"relay_address,omitempty"`
	ClusterToken string `json:"cluster_token,omitempty"`
	DownloadDir  string `json:"download_dir,omitempty"`
}

// runPair consumes a gaur://pair URI printed by `gaur-relay -init`. The token
// goes to the 0600 key file, never into config.json or logs.
func runPair(cfg Config, uri string) error {
	u, err := url.Parse(strings.TrimSpace(uri))
	if err != nil {
		return fmt.Errorf("invalid pairing URI: %w", err)
	}
	if u.Scheme != "gaur" || u.Host != "pair" {
		return fmt.Errorf("not a gaur://pair URI")
	}
	q := u.Query()
	token := strings.TrimSpace(q.Get("token"))
	if token == "" {
		return fmt.Errorf("pairing URI has no token")
	}
	relay := strings.TrimSpace(q.Get("relay"))

	if dir := filepath.Dir(cfg.TokenFile); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return err
		}
	}
	if err := os.WriteFile(cfg.TokenFile, []byte(token+"\n"), 0o600); err != nil {
		return err
	}
	_ = os.Chmod(cfg.TokenFile, 0o600)

	confPath := defaultConfigFile()
	conf := pairConfig{}
	if data, err := os.ReadFile(confPath); err == nil {
		_ = json.Unmarshal(data, &conf)
	}
	if relay != "" {
		conf.RelayAddr = relay
	}
	// The token belongs in the key file, not in a config that users may copy
	// around or paste into an issue.
	conf.ClusterToken = ""

	data, err := json.MarshalIndent(conf, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(confPath), 0o700); err != nil {
		return err
	}
	if err := os.WriteFile(confPath, append(data, '\n'), 0o600); err != nil {
		return err
	}
	_ = os.Chmod(confPath, 0o600)

	fmt.Printf("Paired with relay %s\n", conf.RelayAddr)
	fmt.Printf("Token written to %s (mode 0600)\n", cfg.TokenFile)
	return nil
}
