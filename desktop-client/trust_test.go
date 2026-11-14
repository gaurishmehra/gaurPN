package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestTrustDevicePersistsAndRevokes(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)

	if err := trustDevice("android-123", "Lavander"); err != nil {
		t.Fatalf("trustDevice: %v", err)
	}
	if !isTrustedDevice("android-123") {
		t.Fatal("device should be trusted")
	}
	devices, err := loadTrustedDevices()
	if err != nil {
		t.Fatalf("loadTrustedDevices: %v", err)
	}
	if len(devices) != 1 || devices[0].Name != "Lavander" {
		t.Fatalf("unexpected trust list: %+v", devices)
	}
	info, err := os.Stat(trustedFilePath())
	if err != nil {
		t.Fatalf("stat trust file: %v", err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("trust file mode = %o, want 600", info.Mode().Perm())
	}

	removed, err := untrustDevice("android-123")
	if err != nil {
		t.Fatalf("untrustDevice: %v", err)
	}
	if !removed {
		t.Fatal("untrustDevice should report a removal")
	}
	if isTrustedDevice("android-123") {
		t.Fatal("device should no longer be trusted")
	}
}

func TestSetOnlyTrustedPreservesConfig(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)

	path := defaultConfigFile()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	original := map[string]interface{}{
		"device_name":   "Lilac",
		"relay_address": "198.51.100.1:9876",
	}
	data, _ := json.Marshal(original)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}

	if err := setOnlyTrusted(true); err != nil {
		t.Fatalf("setOnlyTrusted: %v", err)
	}
	if !loadOnlyTrustedFromConfig() {
		t.Fatal("only_trusted should be true")
	}

	updated, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var conf map[string]interface{}
	if err := json.Unmarshal(updated, &conf); err != nil {
		t.Fatal(err)
	}
	if conf["device_name"] != "Lilac" {
		t.Fatalf("device_name was not preserved: %v", conf["device_name"])
	}
	if conf["only_trusted"] != true {
		t.Fatalf("only_trusted = %v", conf["only_trusted"])
	}
}

// TestOnlyTrustedCacheKeyedByMtime pins the REL-11 cache: the parsed value is
// reused while config.json's mtime is unchanged and re-read once it moves.
func TestOnlyTrustedCacheKeyedByMtime(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)

	path := defaultConfigFile()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	writeConfig := func(enabled bool, mtime time.Time) {
		t.Helper()
		data, _ := json.Marshal(map[string]interface{}{"only_trusted": enabled})
		if err := os.WriteFile(path, data, 0o600); err != nil {
			t.Fatal(err)
		}
		// Pin the mtime explicitly: filesystem timestamp granularity must
		// not decide whether the cache invalidates.
		if err := os.Chtimes(path, mtime, mtime); err != nil {
			t.Fatal(err)
		}
	}

	base := time.Now().Add(-time.Hour).UTC()
	writeConfig(true, base)
	if !onlyTrustedEnabled(Config{}) {
		t.Fatal("first read must see only_trusted=true")
	}

	// Same mtime, different content: the cache must still serve the old
	// parsed value without re-reading the file.
	writeConfig(false, base)
	if !onlyTrustedEnabled(Config{}) {
		t.Fatal("same mtime must serve the cached value")
	}

	// Bumping the mtime must pick up the new content.
	writeConfig(false, base.Add(2*time.Second))
	if onlyTrustedEnabled(Config{}) {
		t.Fatal("a new mtime must invalidate the cache")
	}

	// The -only-trusted flag short-circuits the config read entirely.
	if !onlyTrustedEnabled(Config{OnlyTrusted: true}) {
		t.Fatal("cfg.OnlyTrusted must win over the config file")
	}
}

func TestResolveTrustTargetUsesPeerCache(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CACHE_HOME", filepath.Join(home, ".cache"))

	peersPath := defaultPeersFile()
	if err := os.MkdirAll(filepath.Dir(peersPath), 0o755); err != nil {
		t.Fatal(err)
	}
	payload := `{"updated":1,"peers":[{"id":"android-abc","name":"Lavander","status":"online","is_self":false}]}`
	if err := os.WriteFile(peersPath, []byte(payload), 0o644); err != nil {
		t.Fatal(err)
	}

	id, name := resolveTrustTarget("lavander")
	if id != "android-abc" || name != "Lavander" {
		t.Fatalf("resolved %q/%q", id, name)
	}
}

func TestOfferSenderIDPrefersStableID(t *testing.T) {
	envelope := map[string]interface{}{
		"sender_id": "lilac-stable",
		"from_id":   "send-1234",
	}
	if got := offerSenderID(envelope); got != "lilac-stable" {
		t.Fatalf("offerSenderID = %q", got)
	}
	envelope = map[string]interface{}{"from_id": "send-1234"}
	if got := offerSenderID(envelope); got != "send-1234" {
		t.Fatalf("fallback offerSenderID = %q", got)
	}
}
