package main

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestUIGuardBlocksRebindingAndCrossOrigin(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	handler := uiGuard("127.0.0.1:9911", next)

	cases := []struct {
		name   string
		host   string
		origin string
		want   int
	}{
		{"bound host, no origin (curl)", "127.0.0.1:9911", "", http.StatusOK},
		{"rebound host", "evil.example:9911", "", http.StatusForbidden},
		{"rebound host with port stripped", "evil.example", "", http.StatusForbidden},
		{"wrong port on loopback", "127.0.0.1:9912", "", http.StatusForbidden},
		{"evil origin", "127.0.0.1:9911", "https://evil.example", http.StatusForbidden},
		{"own origin", "127.0.0.1:9911", "http://127.0.0.1:9911", http.StatusOK},
		{"localhost host courtesy", "localhost:9911", "", http.StatusOK},
		{"localhost origin courtesy", "localhost:9911", "http://localhost:9911", http.StatusOK},
		{"empty host", "", "", http.StatusForbidden},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodPost, "/api/send-text", nil)
			req.Host = tc.host
			if tc.origin != "" {
				req.Header.Set("Origin", tc.origin)
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
	handler := newUIHandler(Config{}, "127.0.0.1:9911")

	// The panel page is served for the bound host.
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Host = "127.0.0.1:9911"
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("index: code = %d, want 200", rec.Code)
	}
	if body := rec.Body.String(); !strings.Contains(body, "gaurPN share") {
		t.Fatalf("index body does not look like the panel: %q", body)
	}

	// A rebound, cross-site API call is rejected before reaching the handler.
	req = httptest.NewRequest(http.MethodPost, "/api/send-text", strings.NewReader(`{"target":"x","text":"y"}`))
	req.Host = "evil.example"
	req.Header.Set("Origin", "https://evil.example")
	req.Header.Set("Content-Type", "application/json")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("evil API call: code = %d, want 403", rec.Code)
	}

	// A same-origin API call reaches the route (and fails harmlessly there
	// because the test config has no reachable relay).
	req = httptest.NewRequest(http.MethodGet, "/api/peers", nil)
	req.Host = "127.0.0.1:9911"
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("same-origin API call: code = %d, want 200", rec.Code)
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

// TestUIBindHelperIsConsistent guards against drift between the courtesy
// origins and the hosts the guard accepts.
func TestUIGuardBindForms(t *testing.T) {
	for _, bind := range []string{"127.0.0.1:9911", "[::1]:9911", "localhost:9911"} {
		t.Run(bind, func(t *testing.T) {
			next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(http.StatusOK)
			})
			handler := uiGuard(bind, next)

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
