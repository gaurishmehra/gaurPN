package main

import (
	"bytes"
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// captureLog runs f with the standard logger writing to a buffer and returns
// what was logged. Tests in this package run sequentially, so swapping the
// global logger is safe.
func captureLog(f func()) string {
	var buf bytes.Buffer
	old := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(old)
	f()
	return buf.String()
}

func contains(haystack, needle string) bool {
	return strings.Contains(haystack, needle)
}

func TestLoadTokenRefusesExposedFile(t *testing.T) {
	dir := t.TempDir()
	cases := []struct {
		name    string
		perm    os.FileMode
		wantErr bool
	}{
		{"group and world readable 0644 is refused", 0o644, true},
		{"group readable 0640 is refused", 0o640, true},
		{"owner only 0600 is accepted", 0o600, false},
		{"owner read only 0400 is accepted", 0o400, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(dir, strings.ReplaceAll(tc.name, " ", "_")+".key")
			if err := os.WriteFile(path, []byte("cluster-secret\n"), tc.perm); err != nil {
				t.Fatal(err)
			}
			// WriteFile applies the umask; pin the exact mode under test.
			if err := os.Chmod(path, tc.perm); err != nil {
				t.Fatal(err)
			}

			token, err := loadToken(path)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected loadToken to refuse mode %04o", tc.perm)
				}
				if !contains(err.Error(), path) {
					t.Fatalf("error must name the file, got: %v", err)
				}
				if !contains(err.Error(), "chmod 600") {
					t.Fatalf("error must name the fix, got: %v", err)
				}
				return
			}
			if err != nil {
				t.Fatalf("expected mode %04o to be accepted: %v", tc.perm, err)
			}
			if token != "cluster-secret" {
				t.Fatalf("token = %q, want %q", token, "cluster-secret")
			}
		})
	}
}

func TestLoadTokenEnvVarUnaffected(t *testing.T) {
	t.Setenv("GAURPN_CLUSTER_TOKEN", "env-secret")
	token, err := loadToken(filepath.Join(t.TempDir(), "missing.key"))
	if err != nil {
		t.Fatalf("env token must not require a file: %v", err)
	}
	if token != "env-secret" {
		t.Fatalf("token = %q, want env-secret", token)
	}
}

// TestWritePeersFileSurfacesErrors pins REL-13: an unwritable peers file must
// produce a [PEERS] log naming the path — never a silent swallow, never a
// panic — and writePeersFile must return so the daemon stays alive.
func TestWritePeersFileSurfacesErrors(t *testing.T) {
	dir := t.TempDir()
	occupied := filepath.Join(dir, "occupied")
	if err := os.WriteFile(occupied, []byte("regular file"), 0o600); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name      string
		peersFile string
	}{
		{"target path is a directory", filepath.Join(dir, "peers.json-blocker")},
		{"parent directory cannot be created", filepath.Join(occupied, "peers.json")},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if tc.name == "target path is a directory" {
				if err := os.Mkdir(tc.peersFile, 0o755); err != nil {
					t.Fatal(err)
				}
			}
			cfg := Config{PeersFile: tc.peersFile}
			out := captureLog(func() {
				writePeersFile(cfg, []peerFileEntry{{ID: "phone-1", Name: "Lavander", Status: "online"}})
			})
			if !contains(out, "[PEERS]") {
				t.Fatalf("expected a [PEERS] failure log, got: %s", out)
			}
			if !contains(out, tc.peersFile) {
				t.Fatalf("log must name the peers file path, got: %s", out)
			}
			// Reaching here proves writePeersFile returned normally: the
			// daemon survives an unwritable peers file.
		})
	}
}

func TestWritePeersFileWritesWatchableSchema(t *testing.T) {
	peersFile := filepath.Join(t.TempDir(), "peers.json")
	cfg := Config{PeersFile: peersFile}

	writePeersFile(cfg, nil)
	data, err := os.ReadFile(peersFile)
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Updated int64           `json:"updated"`
		Peers   []peerFileEntry `json:"peers"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		t.Fatalf("peers file is not valid JSON: %v", err)
	}
	if payload.Peers == nil {
		t.Fatal("empty peer list must serialize as [], not null (Quickshell contract)")
	}

	writePeersFile(cfg, []peerFileEntry{{ID: "phone-1", Name: "Lavander", Status: "online"}})
	data, err = os.ReadFile(peersFile)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Peers) != 1 || payload.Peers[0].ID != "phone-1" {
		t.Fatalf("unexpected peers payload: %s", data)
	}
}
