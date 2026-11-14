package main

import (
	"bytes"
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
