package main

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadToken(t *testing.T) {
	t.Run("file read error mentions path", func(t *testing.T) {
		missing := filepath.Join(t.TempDir(), "missing.key")
		cfg := Config{TokenFile: missing}
		token, err := loadToken(cfg)
		if err == nil {
			t.Fatalf("expected an error for a missing token file, got token %q", token)
		}
		if !strings.Contains(err.Error(), missing) {
			t.Fatalf("error %q does not mention the file path", err)
		}
	})

	t.Run("empty file is an error", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "cluster.key")
		if err := os.WriteFile(path, []byte("  \n"), 0o600); err != nil {
			t.Fatalf("seed: %v", err)
		}
		_, err := loadToken(Config{TokenFile: path})
		if err == nil {
			t.Fatalf("expected an error for an empty token file")
		}
		if !strings.Contains(err.Error(), "empty") {
			t.Fatalf("error %q should say the file is empty", err)
		}
	})

	t.Run("valid file wins and is trimmed", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "cluster.key")
		if err := os.WriteFile(path, []byte("  file-secret \n"), 0o600); err != nil {
			t.Fatalf("seed: %v", err)
		}
		token, err := loadToken(Config{TokenFile: path})
		if err != nil {
			t.Fatalf("loadToken: %v", err)
		}
		if token != "file-secret" {
			t.Fatalf("token = %q, want file-secret", token)
		}
	})

	t.Run("env fallback only when file flag is empty", func(t *testing.T) {
		t.Setenv("GAUR_TEST_TOKEN", "env-secret")

		token, err := loadToken(Config{TokenFile: "", TokenEnv: "GAUR_TEST_TOKEN"})
		if err != nil || token != "env-secret" {
			t.Fatalf("env fallback: token = %q, err = %v, want env-secret", token, err)
		}

		// A configured-but-unreadable file must NOT fall through to the env.
		missing := filepath.Join(t.TempDir(), "missing.key")
		token, err = loadToken(Config{TokenFile: missing, TokenEnv: "GAUR_TEST_TOKEN"})
		if err == nil {
			t.Fatalf("unreadable file silently fell through to env, token = %q", token)
		}
		if !strings.Contains(err.Error(), missing) {
			t.Fatalf("error %q does not mention the file path", err)
		}
	})

	t.Run("both missing is an error", func(t *testing.T) {
		t.Setenv("GAUR_TEST_TOKEN", "")
		_, err := loadToken(Config{TokenFile: "", TokenEnv: "GAUR_TEST_TOKEN"})
		if err == nil {
			t.Fatalf("expected an error with no file and an empty env var")
		}
		_, err = loadToken(Config{})
		if err == nil {
			t.Fatalf("expected an error with neither file nor env configured")
		}
	})
}

// stubInterfaceAddrs replaces the interface address resolver with
// deterministic fixtures keyed by interface name.
func stubInterfaceAddrs(t *testing.T, byName map[string][]string) {
	t.Helper()
	prev := interfaceAddrs
	interfaceAddrs = func(ifi net.Interface) ([]net.Addr, error) {
		cidrs, ok := byName[ifi.Name]
		if !ok {
			return nil, fmt.Errorf("no fixture for interface %q", ifi.Name)
		}
		addrs := make([]net.Addr, 0, len(cidrs))
		for _, c := range cidrs {
			_, ipnet, err := net.ParseCIDR(c)
			if err != nil {
				return nil, err
			}
			addrs = append(addrs, ipnet)
		}
		return addrs, nil
	}
	t.Cleanup(func() { interfaceAddrs = prev })
}

func TestIsLoopbackBind(t *testing.T) {
	lo := net.Interface{Index: 1, Name: "lo", Flags: net.FlagLoopback | net.FlagUp}
	eth0 := net.Interface{Index: 2, Name: "eth0", Flags: net.FlagUp}

	t.Run("loopback literals regardless of fixtures", func(t *testing.T) {
		cases := []struct {
			addr string
			want bool
		}{
			{"127.0.0.1:9876", true},
			{"127.255.255.254:9876", true}, // whole 127.0.0.0/8
			{"[::1]:9876", true},
			{"0.0.0.0:9876", false},
			{"[::]:9876", false},
			{":9876", false},
		}
		for _, tc := range cases {
			got, err := isLoopbackBind(tc.addr, nil)
			if err != nil {
				t.Fatalf("isLoopbackBind(%q): %v", tc.addr, err)
			}
			if got != tc.want {
				t.Errorf("isLoopbackBind(%q, no ifaces) = %v, want %v", tc.addr, got, tc.want)
			}
		}
	})

	t.Run("any address assigned to a loopback interface is accepted", func(t *testing.T) {
		stubInterfaceAddrs(t, map[string][]string{
			"lo":   {"127.0.0.1/8", "::1/128", "198.51.100.1/32", "10.0.0.5/32"},
			"eth0": {"203.0.113.9/24"},
		})
		cases := []struct {
			addr string
			want bool
		}{
			{"198.51.100.1:9876", true}, // public IP aliased on lo
			{"10.0.0.5:9876", true},     // any IP on lo is acceptable
			{"203.0.113.9:9876", false}, // address of a non-loopback interface
			{"192.0.2.50:9876", false},  // not assigned anywhere
		}
		for _, tc := range cases {
			got, err := isLoopbackBind(tc.addr, []net.Interface{lo, eth0})
			if err != nil {
				t.Fatalf("isLoopbackBind(%q): %v", tc.addr, err)
			}
			if got != tc.want {
				t.Errorf("isLoopbackBind(%q) = %v, want %v", tc.addr, got, tc.want)
			}
		}
	})

	t.Run("the same IP on a non-loopback interface is rejected", func(t *testing.T) {
		stubInterfaceAddrs(t, map[string][]string{
			"lo":   {"127.0.0.1/8"},
			"eth0": {"198.51.100.1/32"},
		})
		got, err := isLoopbackBind("198.51.100.1:9876", []net.Interface{lo, eth0})
		if err != nil {
			t.Fatalf("isLoopbackBind: %v", err)
		}
		if got {
			t.Errorf("IP on eth0-only accepted; loopback interface is the invariant")
		}
	})

	t.Run("unparseable input is an error", func(t *testing.T) {
		for _, addr := range []string{"127.0.0.1", "localhost:9876"} {
			if _, err := isLoopbackBind(addr, nil); err == nil {
				t.Errorf("isLoopbackBind(%q) succeeded, want error", addr)
			}
		}
	})
}
