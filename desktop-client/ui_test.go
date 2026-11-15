package main

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestUIGuardBlocksRebindingAndCrossOrigin(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	handler := uiGuard("127.0.0.1:9911", "test-token", next)

	cases := []struct {
		name   string
		host   string
		origin string
		token  string
		want   int
	}{
		{"bound host, no origin (curl)", "127.0.0.1:9911", "", "test-token", http.StatusOK},
		{"rebound host", "evil.example:9911", "", "test-token", http.StatusForbidden},
		{"rebound host with port stripped", "evil.example", "", "test-token", http.StatusForbidden},
		{"wrong port on loopback", "127.0.0.1:9912", "", "test-token", http.StatusForbidden},
		{"evil origin", "127.0.0.1:9911", "https://evil.example", "test-token", http.StatusForbidden},
		{"own origin", "127.0.0.1:9911", "http://127.0.0.1:9911", "test-token", http.StatusOK},
		{"localhost host courtesy", "localhost:9911", "", "test-token", http.StatusOK},
		{"localhost origin courtesy", "localhost:9911", "http://localhost:9911", "test-token", http.StatusOK},
		{"empty host", "", "", "test-token", http.StatusForbidden},
		{"missing token", "127.0.0.1:9911", "", "", http.StatusForbidden},
		{"wrong token", "127.0.0.1:9911", "", "not-the-token", http.StatusForbidden},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPost, "/api/send-text", nil)
			req.Host = tc.host
			if tc.origin != "" {
				req.Header.Set("Origin", tc.origin)
			}
			if tc.token != "" {
				req.Header.Set("X-Gaur-Token", tc.token)
			}
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)
			if rec.Code != tc.want {
				t.Fatalf("code = %d, want %d", rec.Code, tc.want)
			}
		})
	}
}

func TestNewUIHandlerAppliesGuard(t *testing.T) {
	oldCLI := runCLI
	runCLI = func(cfg Config, timeout time.Duration, args ...string) (string, error) {
		return "[]", nil
	}
	defer func() { runCLI = oldCLI }()

	handler := newUIHandler(Config{}, "127.0.0.1:9911", "test-token")

	// The panel page is served for the bound host and carries the token.
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Host = "127.0.0.1:9911"
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("index: code = %d, want 200", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, "gaurPN share") {
		t.Fatalf("index body does not look like the panel: %q", body)
	}
	if !strings.Contains(body, `const GAUR_TOKEN = "test-token"`) {
		t.Fatalf("index does not carry the CSRF token: %q", body)
	}
	if strings.Contains(body, uiTokenPlaceholder) {
		t.Fatal("index still contains the token placeholder")
	}

	// A rebound, cross-site API call is rejected before reaching the handler.
	req = httptest.NewRequest(http.MethodPost, "/api/send-text", strings.NewReader(`{"target":"x","text":"y"}`))
	req.Host = "evil.example"
	req.Header.Set("Origin", "https://evil.example")
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Gaur-Token", "test-token")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("evil API call: code = %d, want 403", rec.Code)
	}

	// A same-origin GET (the liveness probe) needs no token.
	req = httptest.NewRequest(http.MethodGet, "/api/peers", nil)
	req.Host = "127.0.0.1:9911"
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("same-origin API call: code = %d, want 200", rec.Code)
	}
}

func TestUIBrowseAndFetchEndpoints(t *testing.T) {
	oldCLI := runCLI
	defer func() { runCLI = oldCLI }()

	var calls [][]string
	runCLI = func(cfg Config, timeout time.Duration, args ...string) (string, error) {
		calls = append(calls, append([]string(nil), args...))
		switch args[0] {
		case "browse":
			return `{"path":"nested","entries":[{"name":"a.txt","size":3,"modified":1,"dir":false}]}`, nil
		case "fetch":
			return "Received a.txt\nReceived 1 file(s) to /tmp/inbox\n", nil
		default:
			return "", nil
		}
	}

	handler := newUIHandler(Config{}, "127.0.0.1:9911", "test-token")
	post := func(path, host, origin, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, path, strings.NewReader(body))
		req.Host = host
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Gaur-Token", "test-token")
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		return rec
	}

	rec := post("/api/browse", "127.0.0.1:9911", "", `{"target":"phone-1","path":"nested"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("browse: code = %d, want 200 (body %q)", rec.Code, rec.Body.String())
	}
	var listing struct {
		Path    string        `json:"path"`
		Entries []browseEntry `json:"entries"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &listing); err != nil {
		t.Fatalf("browse body is not JSON: %q: %v", rec.Body.String(), err)
	}
	if listing.Path != "nested" || len(listing.Entries) != 1 || listing.Entries[0].Name != "a.txt" {
		t.Fatalf("browse payload = %+v", listing)
	}

	// The Host/Origin guard still applies to the new endpoints.
	if rec := post("/api/browse", "evil.example", "", `{"target":"phone-1","path":""}`); rec.Code != http.StatusForbidden {
		t.Fatalf("rebound browse: code = %d, want 403", rec.Code)
	}
	if rec := post("/api/browse", "127.0.0.1:9911", "https://evil.example", `{"target":"phone-1","path":""}`); rec.Code != http.StatusForbidden {
		t.Fatalf("cross-origin browse: code = %d, want 403", rec.Code)
	}
	if rec := post("/api/fetch", "127.0.0.1:9911", "https://evil.example", `{"target":"phone-1","paths":["a.txt"]}`); rec.Code != http.StatusForbidden {
		t.Fatalf("cross-origin fetch: code = %d, want 403", rec.Code)
	}

	// Invalid paths never reach the subprocess.
	if rec := post("/api/browse", "127.0.0.1:9911", "", `{"target":"phone-1","path":"../x"}`); rec.Code != http.StatusBadRequest {
		t.Fatalf("invalid browse path: code = %d, want 400", rec.Code)
	}
	if rec := post("/api/fetch", "127.0.0.1:9911", "", `{"target":"phone-1","paths":["../x"]}`); rec.Code != http.StatusBadRequest {
		t.Fatalf("invalid fetch path: code = %d, want 400", rec.Code)
	}

	rec = post("/api/fetch", "127.0.0.1:9911", "", `{"target":"phone-1","paths":["a.txt","b.txt"]}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("fetch: code = %d, want 200 (body %q)", rec.Code, rec.Body.String())
	}
	var result struct {
		OK     bool   `json:"ok"`
		Output string `json:"output"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatalf("fetch body is not JSON: %q: %v", rec.Body.String(), err)
	}
	if !result.OK || !strings.Contains(result.Output, "Received a.txt") {
		t.Fatalf("fetch payload = %+v", result)
	}

	tooMany := make([]string, fetchMaxPaths+1)
	for i := range tooMany {
		tooMany[i] = "a.txt"
	}
	body, err := json.Marshal(map[string]interface{}{"target": "phone-1", "paths": tooMany})
	if err != nil {
		t.Fatal(err)
	}
	if rec := post("/api/fetch", "127.0.0.1:9911", "", string(body)); rec.Code != http.StatusBadRequest {
		t.Fatalf("too many fetch paths: code = %d, want 400", rec.Code)
	}

	if len(calls) != 2 {
		t.Fatalf("runCLI calls = %q, want exactly the browse and fetch calls", calls)
	}
	if want := []string{"browse", "--json", "phone-1", "--", "nested"}; !equalStrings(calls[0], want) {
		t.Fatalf("browse args = %q, want %q", calls[0], want)
	}
	if want := []string{"fetch", "phone-1", "--", "a.txt", "b.txt"}; !equalStrings(calls[1], want) {
		t.Fatalf("fetch args = %q, want %q", calls[1], want)
	}
}

func equalStrings(got, want []string) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}

func TestRunUIOpenFlag(t *testing.T) {
	oldServe, oldOpen := uiServe, uiOpenURL
	defer func() { uiServe, uiOpenURL = oldServe, oldOpen }()

	var opened []string
	uiOpenURL = func(url string) { opened = append(opened, url) }
	uiServe = func(ln net.Listener, handler http.Handler) error {
		return ln.Close()
	}

	if err := runUI(Config{}, "127.0.0.1:0", true); err != nil {
		t.Fatalf("runUI with --open: %v", err)
	}
	if len(opened) != 1 || !strings.HasPrefix(opened[0], "http://127.0.0.1:") || !strings.HasSuffix(opened[0], "/") {
		t.Fatalf("opened URLs = %q, want the bound UI URL", opened)
	}

	opened = nil
	if err := runUI(Config{}, "127.0.0.1:0", false); err != nil {
		t.Fatalf("runUI without --open: %v", err)
	}
	if len(opened) != 0 {
		t.Fatalf("the opener ran without --open: %q", opened)
	}
}

func TestRunUIBusyPort(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	addr := ln.Addr().String()

	err = runUI(Config{}, addr, false)
	if err == nil || err.Error() != "ui already running on "+addr {
		t.Fatalf("runUI error = %v, want the busy-port message", err)
	}
}

func TestDefaultUIAddr(t *testing.T) {
	if defaultUIAddr != "127.0.0.1:9877" {
		t.Fatalf("defaultUIAddr = %q, want 127.0.0.1:9877", defaultUIAddr)
	}
}

func TestOpenUIInBrowserLinux(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("xdg-open is Linux-only")
	}
	dir := t.TempDir()
	argvPath := filepath.Join(dir, "argv")
	t.Setenv("PATH", dir)
	t.Setenv("GAUR_OPEN_ARGV", argvPath)
	writeExecutable(t, filepath.Join(dir, "xdg-open"),
		"printf '%s\\n' \"$@\" > \"$GAUR_OPEN_ARGV\"")

	openUIInBrowser("http://127.0.0.1:9877/")
	waitFor(t, 5*time.Second, func() bool {
		data, err := os.ReadFile(argvPath)
		return err == nil && len(data) > 0
	})
	data, err := os.ReadFile(argvPath)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) != 1 || lines[0] != "http://127.0.0.1:9877/" {
		t.Fatalf("xdg-open argv = %q, want the URL as a single argument", lines)
	}
}

func TestUIHTMLHasBrowseSection(t *testing.T) {
	for _, want := range []string{
		`id="browsePeer"`,
		`id="crumbs"`,
		`id="fetchBtn"`,
		"/api/browse",
		"/api/fetch",
		"replaceChildren",
		"X-Gaur-Token",
		"__GAUR_TOKEN__",
	} {
		if !strings.Contains(uiHTML, want) {
			t.Fatalf("UI page is missing %q", want)
		}
	}
	// Server data is rendered with textContent/createElement only.
	if strings.Contains(uiHTML, "innerHTML") {
		t.Fatal("UI page must not use innerHTML with peer-supplied data")
	}
}

func TestIsLoopbackHost(t *testing.T) {
	cases := []struct {
		host string
		want bool
	}{
		{"127.0.0.1", true},
		{"::1", true},
		{"localhost", true},
		{"LOCALHOST", true},
		{"192.168.1.5", false},
		{"0.0.0.0", false},
		{"", false},
		{"myhost", false},
	}
	for _, tc := range cases {
		if got := isLoopbackHost(tc.host); got != tc.want {
			t.Errorf("isLoopbackHost(%q) = %v, want %v", tc.host, got, tc.want)
		}
	}
}

// TestUIGuardBindForms guards against drift between the courtesy origins and
// the hosts the guard accepts.
func TestUIGuardBindForms(t *testing.T) {
	for _, bind := range []string{"127.0.0.1:9911", "[::1]:9911", "localhost:9911"} {
		t.Run(bind, func(t *testing.T) {
			next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(http.StatusOK)
			})
			handler := uiGuard(bind, "test-token", next)

			req := httptest.NewRequest(http.MethodGet, "/api/peers", nil)
			req.Host = bind
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)
			if rec.Code != http.StatusOK {
				t.Fatalf("bound host %s rejected: code %d", bind, rec.Code)
			}

			_, port, err := net.SplitHostPort(bind)
			if err != nil {
				t.Fatal(err)
			}
			if bind == "localhost:9911" {
				return // no extra courtesy form for an explicit localhost bind
			}
			req = httptest.NewRequest(http.MethodGet, "/api/peers", nil)
			req.Host = "localhost:" + port
			rec = httptest.NewRecorder()
			handler.ServeHTTP(rec, req)
			if rec.Code != http.StatusOK {
				t.Fatalf("localhost courtesy form rejected for bind %s: code %d", bind, rec.Code)
			}
		})
	}
}

func TestUICSRFToken(t *testing.T) {
	oldCLI := runCLI
	runCLI = func(cfg Config, timeout time.Duration, args ...string) (string, error) {
		return `{"path":"","entries":[]}`, nil
	}
	defer func() { runCLI = oldCLI }()

	handler := newUIHandler(Config{}, "127.0.0.1:9911", "secret-token")
	post := func(token string) int {
		req := httptest.NewRequest(http.MethodPost, "/api/browse", strings.NewReader(`{"target":"phone-1","path":""}`))
		req.Host = "127.0.0.1:9911"
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("X-Gaur-Token", token)
		}
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		return rec.Code
	}

	if code := post(""); code != http.StatusForbidden {
		t.Fatalf("missing token: code = %d, want 403", code)
	}
	if code := post("wrong-token"); code != http.StatusForbidden {
		t.Fatalf("wrong token: code = %d, want 403", code)
	}
	if code := post("secret-token"); code != http.StatusOK {
		t.Fatalf("correct token: code = %d, want 200", code)
	}

	// GET / stays open for the liveness probe.
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Host = "127.0.0.1:9911"
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /: code = %d, want 200", rec.Code)
	}
}

func TestWriteBrowseResultReencodes(t *testing.T) {
	rec := httptest.NewRecorder()
	writeBrowseResult(rec,
		`{"path":"nested","entries":[{"name":"a.txt","size":1,"modified":2,"dir":false}],"evil":"<script>alert(1)</script>","truncated":true}`,
		nil,
	)
	body := rec.Body.String()
	if strings.Contains(body, "evil") || strings.Contains(body, "<script>") {
		t.Fatalf("raw CLI output leaked into the response: %q", body)
	}
	var payload struct {
		Path      string        `json:"path"`
		Entries   []browseEntry `json:"entries"`
		Truncated bool          `json:"truncated"`
		Error     string        `json:"error"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("response is not JSON: %q: %v", body, err)
	}
	if payload.Path != "nested" || len(payload.Entries) != 1 || payload.Entries[0].Name != "a.txt" || !payload.Truncated {
		t.Fatalf("re-encoded payload = %+v", payload)
	}

	rec = httptest.NewRecorder()
	writeBrowseResult(rec, "not json", errors.New("boom"))
	if rec.Code != http.StatusBadGateway {
		t.Fatalf("invalid output with an error: code = %d, want 502", rec.Code)
	}
	payload = struct {
		Path      string        `json:"path"`
		Entries   []browseEntry `json:"entries"`
		Truncated bool          `json:"truncated"`
		Error     string        `json:"error"`
	}{}
	if err := json.Unmarshal(rec.Body.Bytes(), &payload); err != nil {
		t.Fatalf("error response is not JSON: %q: %v", rec.Body.String(), err)
	}
	if payload.Error != "boom" {
		t.Fatalf("error payload = %+v, want boom", payload)
	}

	rec = httptest.NewRecorder()
	writeBrowseResult(rec, "", nil)
	if rec.Code != http.StatusInternalServerError {
		t.Fatalf("empty output: code = %d, want 500", rec.Code)
	}
}
