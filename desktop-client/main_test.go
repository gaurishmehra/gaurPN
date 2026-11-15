package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
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

// captureStdout runs f with os.Stdout replaced by a pipe and returns what
// was printed. Tests in this package run sequentially, so swapping the
// global stdout is safe.
func captureStdout(f func()) string {
	old := os.Stdout
	r, w, err := os.Pipe()
	if err != nil {
		panic(err)
	}
	os.Stdout = w
	defer func() {
		os.Stdout = old
		_ = w.Close()
	}()
	f()
	_ = w.Close()
	out, _ := io.ReadAll(r)
	return string(out)
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

// --- MED-9: tiered target resolution ---

func TestResolveTargetQuery(t *testing.T) {
	cases := []struct {
		name     string
		peers    []targetPeer
		query    string
		wantID   string
		wantName string
		wantErr  string
		sentinel error
	}{
		{
			name:     "exact device id wins over an exact name",
			peers:    []targetPeer{{ID: "lilac-1", Name: "Lilac"}, {ID: "other-1", Name: "lilac-1"}},
			query:    "lilac-1",
			wantID:   "lilac-1",
			wantName: "Lilac",
		},
		{
			name:     "exact name beats a prefix match",
			peers:    []targetPeer{{ID: "lilac-1", Name: "Lilac"}, {ID: "lilac-2", Name: "Lilac Phone"}},
			query:    "lilac",
			wantID:   "lilac-1",
			wantName: "Lilac",
		},
		{
			name:     "exact name is case-insensitive",
			peers:    []targetPeer{{ID: "lilac-1", Name: "Lilac"}},
			query:    "LILAC",
			wantID:   "lilac-1",
			wantName: "Lilac",
		},
		{
			name:     "single prefix match resolves",
			peers:    []targetPeer{{ID: "lilac-1", Name: "Lilac"}, {ID: "desktop-9", Name: "Desktop"}},
			query:    "lil",
			wantID:   "lilac-1",
			wantName: "Lilac",
		},
		{
			name:     "two prefix matches are ambiguous",
			peers:    []targetPeer{{ID: "lilac-1", Name: "Lilac"}, {ID: "lime-2", Name: "Lime"}},
			query:    "li",
			wantErr:  "ambiguous target \"li\": Lilac (lilac-1), Lime (lime-2)",
			sentinel: errTargetAmbiguous,
		},
		{
			name:     "two exact names are ambiguous",
			peers:    []targetPeer{{ID: "a-1", Name: "Lilac"}, {ID: "b-2", Name: "Lilac"}},
			query:    "lilac",
			wantErr:  "ambiguous target \"lilac\": Lilac (a-1), Lilac (b-2)",
			sentinel: errTargetAmbiguous,
		},
		{
			name:     "substring that is not a prefix never matches",
			peers:    []targetPeer{{ID: "slime-1", Name: "Slime"}},
			query:    "li",
			wantErr:  "not currently online",
			sentinel: errTargetNotFound,
		},
		{
			name:     "no match reports not found",
			peers:    []targetPeer{{ID: "lilac-1", Name: "Lilac"}},
			query:    "zeta",
			wantErr:  "not currently online",
			sentinel: errTargetNotFound,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			match, err := resolveTargetQuery(tc.peers, tc.query)
			if tc.wantErr != "" {
				if err == nil {
					t.Fatalf("resolveTargetQuery(%q) = %+v, want error", tc.query, match)
				}
				if !contains(err.Error(), tc.wantErr) {
					t.Fatalf("error = %q, want it to contain %q", err, tc.wantErr)
				}
				if !errors.Is(err, tc.sentinel) {
					t.Fatalf("error = %q, want sentinel %v", err, tc.sentinel)
				}
				return
			}
			if err != nil {
				t.Fatalf("resolveTargetQuery(%q): %v", tc.query, err)
			}
			if match.ID != tc.wantID || match.Name != tc.wantName {
				t.Fatalf("match = %+v, want %s (%s)", match, tc.wantName, tc.wantID)
			}
		})
	}
}

// TestDiscoverTargetViaRelay wires the full discovery path against the fake
// relay: an ambiguous prefix lists the candidates, an exact id resolves
// through the name cloud, and an unknown query reports not found. The fake
// relay broadcasts the peer list on every connect, so each probe opens one
// extra connection to trigger a fresh list.
func TestDiscoverTargetViaRelay(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, _ := DeriveSubKeys(f.Token)

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "lilac-1", Name: "Lilac"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("connect phone: %v", err)
	}
	defer phoneConn.Close()
	waitFor(t, 5*time.Second, func() bool { return f.connects() >= 1 })

	tabletCfg := Config{RelayAddr: f.URL, DeviceID: "lime-2", Name: "Lime"}
	tabletConn, err := connectWS(tabletCfg, relayToken)
	if err != nil {
		t.Fatalf("connect tablet: %v", err)
	}
	defer tabletConn.Close()
	waitFor(t, 5*time.Second, func() bool { return f.connects() >= 2 })

	seekerCfg := Config{RelayAddr: f.URL, DeviceID: "desktop-9", Name: "Desktop"}
	seekerConn, err := connectWS(seekerCfg, relayToken)
	if err != nil {
		t.Fatalf("connect seeker: %v", err)
	}
	defer seekerConn.Close()

	// The connect broadcast already carries the full peer list.
	_, _, err = discoverTarget(seekerConn, "li")
	if !errors.Is(err, errTargetAmbiguous) {
		t.Fatalf("discoverTarget(\"li\") err = %v, want ambiguity", err)
	}
	if !contains(err.Error(), "Lilac") || !contains(err.Error(), "Lime") {
		t.Fatalf("ambiguity error must list the candidates, got: %v", err)
	}

	// Trigger a fresh broadcast for each further probe.
	broadcast := func() *websocket.Conn {
		t.Helper()
		conn, err := connectWS(Config{RelayAddr: f.URL, DeviceID: fmt.Sprintf("extra-%d", f.connects())}, relayToken)
		if err != nil {
			t.Fatalf("connect extra peer: %v", err)
		}
		return conn
	}

	extra1 := broadcast()
	defer extra1.Close()
	id, name, err := discoverTarget(seekerConn, "lilac-1")
	if err != nil {
		t.Fatalf("discoverTarget by id: %v", err)
	}
	if id != "lilac-1" || name != "Lilac" {
		t.Fatalf("resolved %s (%s), want Lilac (lilac-1)", name, id)
	}

	extra2 := broadcast()
	defer extra2.Close()
	if _, _, err := discoverTarget(seekerConn, "zeta"); !errors.Is(err, errTargetNotFound) {
		t.Fatalf("discoverTarget(\"zeta\") err = %v, want not found", err)
	}
}

// --- MED-10: list error reporting ---

// TestRunListJSONReportsErrors pins the script contract: a failed -json
// list prints a {"error": ...} object on stdout and returns a non-nil error
// so the CLI exits non-zero — an empty array must never mask a failure.
func TestRunListJSONReportsErrors(t *testing.T) {
	t.Setenv("GAURPN_CLUSTER_TOKEN", "")
	cfg := Config{
		RelayAddr: "127.0.0.1:1",
		TokenFile: filepath.Join(t.TempDir(), "missing.key"),
	}

	var listErr error
	out := captureStdout(func() {
		listErr = runList(cfg, true)
	})
	if listErr == nil {
		t.Fatal("runList must return an error when the token is missing (the CLI exits non-zero on it)")
	}

	var payload struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal([]byte(strings.TrimSpace(out)), &payload); err != nil {
		t.Fatalf("stdout is not a JSON error object: %q: %v", out, err)
	}
	if payload.Error == "" {
		t.Fatalf("JSON error object must carry the failure, got: %q", out)
	}
	if !contains(payload.Error, "missing.key") {
		t.Fatalf("error must name the token file, got: %q", payload.Error)
	}
	if !contains(listErr.Error(), payload.Error) {
		t.Fatalf("returned error must match the reported one, got: %v", listErr)
	}
}

// TestRunListJSONHappyPathUnchanged pins that a successful -json list still
// prints the peers array — the Quickshell share picker parses it.
func TestRunListJSONHappyPathUnchanged(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, _ := DeriveSubKeys(f.Token)

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("connect phone: %v", err)
	}
	defer phoneConn.Close()
	waitFor(t, 5*time.Second, func() bool { return f.connects() >= 1 })

	dir := t.TempDir()
	cfg := Config{
		RelayAddr: f.URL,
		TokenFile: writeClusterToken(t, dir, f.Token),
		DeviceID:  "desktop-1",
		Name:      "Desktop",
	}

	var listErr error
	out := captureStdout(func() {
		listErr = runList(cfg, true)
	})
	if listErr != nil {
		t.Fatalf("runList: %v", listErr)
	}

	var peers []JSONPeer
	if err := json.Unmarshal([]byte(strings.TrimSpace(out)), &peers); err != nil {
		t.Fatalf("stdout is not a JSON peers array: %q: %v", out, err)
	}
	ids := map[string]bool{}
	for _, p := range peers {
		ids[p.ID] = true
	}
	if !ids["phone-1"] {
		t.Fatalf("peers array must contain phone-1, got: %s", out)
	}
	if ids[fmt.Sprintf("list-%d", os.Getpid())] {
		t.Fatalf("the list client must not list itself, got: %s", out)
	}
}

// TestRequestCommandRemovedExitCode runs the real main() in a child process
// and pins the removed command's contract: a usage-error exit code of 2 and a
// one-line hint pointing at browse/fetch and the UI.
func TestRequestCommandRemovedExitCode(t *testing.T) {
	if os.Getenv("GAUR_HELPER_MAIN") == "1" {
		if home := os.Getenv("GAUR_HELPER_HOME"); home != "" {
			_ = os.Setenv("HOME", home)
		}
		os.Args = []string{"gaur-share", "request"}
		// The testing package already parsed its own flags on the global
		// set; main() needs a fresh set to parse the CLI arguments.
		flag.CommandLine = flag.NewFlagSet("gaur-share", flag.ExitOnError)
		main()
		os.Exit(0)
		return
	}

	cmd := exec.Command(os.Args[0], "-test.run=TestRequestCommandRemovedExitCode")
	cmd.Env = append(os.Environ(),
		"GAUR_HELPER_MAIN=1",
		"GAUR_HELPER_HOME="+t.TempDir(),
	)
	out, err := cmd.CombinedOutput()
	var exitErr *exec.ExitError
	if !errors.As(err, &exitErr) {
		t.Fatalf("expected a non-zero exit, got %v (output %q)", err, out)
	}
	if code := exitErr.ExitCode(); code != 2 {
		t.Fatalf("exit code = %d, want 2 (output %q)", code, out)
	}
	if !strings.Contains(string(out), "browse/fetch") {
		t.Fatalf("output = %q, want the browse/fetch hint", out)
	}
}

func TestSplitDoubleDash(t *testing.T) {
	cases := []struct {
		name string
		in   []string
		head []string
		tail []string
	}{
		{"sentinel before a dash path", []string{"phone-1", "--", "-json"}, []string{"phone-1"}, []string{"-json"}},
		{"no sentinel", []string{"phone-1", "docs"}, []string{"phone-1", "docs"}, nil},
		{"sentinel first", []string{"--", "-json"}, []string{}, []string{"-json"}},
		{"only sentinel", []string{"--"}, []string{}, []string{}},
		{"only the first sentinel is special", []string{"a", "--", "b", "--", "c"}, []string{"a"}, []string{"b", "--", "c"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			head, tail := splitDoubleDash(tc.in)
			if !slices.Equal(head, tc.head) {
				t.Fatalf("head = %q, want %q", head, tc.head)
			}
			if !slices.Equal(tail, tc.tail) {
				t.Fatalf("tail = %q, want %q", tail, tc.tail)
			}
		})
	}
}
