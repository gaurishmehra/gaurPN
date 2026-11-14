package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRunPairWritesTokenAndConfig(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)

	cfg := Config{
		TokenFile: filepath.Join(home, ".config", "gaur-share", "cluster.key"),
	}
	uri := "gaur://pair?relay=198.51.100.1%3A9876&token=test-token&name=Test-Phone"
	if err := runPair(cfg, uri); err != nil {
		t.Fatalf("runPair: %v", err)
	}

	data, err := os.ReadFile(cfg.TokenFile)
	if err != nil {
		t.Fatalf("read token: %v", err)
	}
	if strings.TrimSpace(string(data)) != "test-token" {
		t.Fatalf("token = %q", strings.TrimSpace(string(data)))
	}
	info, err := os.Stat(cfg.TokenFile)
	if err != nil {
		t.Fatalf("stat token: %v", err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("token mode = %o, want 600", info.Mode().Perm())
	}

	confPath := filepath.Join(home, ".config", "gaur-share", "config.json")
	conf, err := os.ReadFile(confPath)
	if err != nil {
		t.Fatalf("read config: %v", err)
	}
	if strings.Contains(string(conf), "test-token") {
		t.Fatal("token must not be written into config.json")
	}
	if !strings.Contains(string(conf), "198.51.100.1:9876") {
		t.Fatalf("relay missing from config: %s", conf)
	}
}

func TestRunPairRejectsWrongScheme(t *testing.T) {
	cfg := Config{TokenFile: filepath.Join(t.TempDir(), "cluster.key")}
	if err := runPair(cfg, "https://example.com"); err == nil {
		t.Fatal("expected an error for a non-gaur URI")
	}
}
