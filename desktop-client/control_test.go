package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestClassifyOpenTarget(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "report.pdf")
	if err := os.WriteFile(file, []byte("pdf"), 0o600); err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		name    string
		value   string
		app     string
		kind    string
		wantErr bool
	}{
		{"url", "https://example.com/report", "", "url", false},
		{"file", file, "", "file", false},
		{"package", "com.example.app", "", "package", false},
		{"explicit package", "ignored", "com.example.app", "package", false},
		{"directory", dir, "", "", true},
		{"garbage", "not a target", "", "", true},
		{"bad explicit package", "ignored", "not-a-package", "", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := classifyOpenTarget(tc.value, tc.app)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected an error, got %+v", got)
				}
				if code := controlExitCode(err); code != 2 {
					t.Fatalf("exit code = %d, want 2", code)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got.kind != tc.kind {
				t.Fatalf("kind = %q, want %q", got.kind, tc.kind)
			}
		})
	}
}

func TestMimeTypeForPath(t *testing.T) {
	if got := mimeTypeForPath("/tmp/report.pdf"); got != "application/pdf" {
		t.Fatalf("pdf mime = %q", got)
	}
	if got := mimeTypeForPath("/tmp/unknown.zzznope"); got != "application/octet-stream" {
		t.Fatalf("unknown mime = %q", got)
	}
	if got := mimeTypeForPath("/tmp/note.txt"); strings.Contains(got, ";") {
		t.Fatalf("mime parameters leaked: %q", got)
	}
}

func startControlTarget(
	t *testing.T,
	relay *fakeRelay,
	id, requestKind, responseKind string,
	handle func(inner map[string]interface{}) map[string]interface{},
) {
	t.Helper()
	_, peerKey := DeriveSubKeys(relay.Token)
	conn, err := connectWS(Config{RelayAddr: relay.URL, DeviceID: id, Name: id}, relay.relayToken())
	if err != nil {
		t.Fatalf("target connect: %v", err)
	}
	client := &wsClient{conn: conn}
	t.Cleanup(func() { _ = client.Close() })
	go func() {
		for {
			inner, ok := tryReadSealedInner(conn, peerKey, requestKind, 10*time.Second)
			if !ok {
				return
			}
			reply := handle(inner)
			if reply == nil {
				continue
			}
			replyTo := envelopeString(inner, "requester_id")
			if replyTo == "" {
				replyTo = envelopeString(inner, "reply_to")
			}
			envelope, err := sealEnvelope(peerKey, replyTo, responseKind, reply)
			if err != nil {
				return
			}
			if err := client.WriteJSON(envelope); err != nil {
				return
			}
		}
	}()
}

func TestRunAskApproved(t *testing.T) {
	relay := newFakeRelay(t)
	dir := t.TempDir()
	tokenPath := writeClusterToken(t, dir, relay.Token)
	startControlTarget(t, relay, "phone-1", "ask", "ask_response", func(inner map[string]interface{}) map[string]interface{} {
		if envelopeString(inner, "reason") != "deploy?" {
			t.Errorf("reason = %q", envelopeString(inner, "reason"))
		}
		return map[string]interface{}{
			"request_id":   envelopeString(inner, "request_id"),
			"responder_id": "phone-1",
			"decision":     "approved",
			"grant":        envelopeString(inner, "grant"),
			"ts":           time.Now().UnixMilli(),
		}
	})

	old := askTimeout
	askTimeout = 5 * time.Second
	t.Cleanup(func() { askTimeout = old })

	cfg := Config{RelayAddr: relay.URL, TokenFile: tokenPath, DeviceID: "desktop-1", Name: "Laptop"}
	var err error
	_ = captureStdout(func() {
		err = runAsk(cfg, "phone-1", "deploy?", false)
	})
	if err != nil {
		t.Fatalf("runAsk: %v", err)
	}
}

func TestRunAskDenied(t *testing.T) {
	relay := newFakeRelay(t)
	dir := t.TempDir()
	tokenPath := writeClusterToken(t, dir, relay.Token)
	startControlTarget(t, relay, "phone-1", "ask", "ask_response", func(inner map[string]interface{}) map[string]interface{} {
		return map[string]interface{}{
			"request_id":   envelopeString(inner, "request_id"),
			"responder_id": "phone-1",
			"decision":     "denied",
			"reason":       "user_denied",
			"grant":        envelopeString(inner, "grant"),
			"ts":           time.Now().UnixMilli(),
		}
	})

	old := askTimeout
	askTimeout = 5 * time.Second
	t.Cleanup(func() { askTimeout = old })

	cfg := Config{RelayAddr: relay.URL, TokenFile: tokenPath, DeviceID: "desktop-1", Name: "Laptop"}
	var err error
	_ = captureStdout(func() {
		err = runAsk(cfg, "phone-1", "deploy?", false)
	})
	if err == nil {
		t.Fatal("expected a denial")
	}
	if code := controlExitCode(err); code != 1 {
		t.Fatalf("exit code = %d, want 1", code)
	}
}

func TestRunOpenURL(t *testing.T) {
	relay := newFakeRelay(t)
	dir := t.TempDir()
	tokenPath := writeClusterToken(t, dir, relay.Token)
	startControlTarget(t, relay, "phone-1", "open", "open_result", func(inner map[string]interface{}) map[string]interface{} {
		if envelopeString(inner, "open_kind") != "url" {
			t.Errorf("open_kind = %q", envelopeString(inner, "open_kind"))
		}
		if envelopeString(inner, "value") != "https://example.com/report" {
			t.Errorf("value = %q", envelopeString(inner, "value"))
		}
		return map[string]interface{}{
			"request_id":   envelopeString(inner, "request_id"),
			"responder_id": "phone-1",
			"status":       "notification",
			"grant":        envelopeString(inner, "grant"),
			"ts":           time.Now().UnixMilli(),
		}
	})

	old := openResultTimeout
	openResultTimeout = 5 * time.Second
	t.Cleanup(func() { openResultTimeout = old })

	cfg := Config{RelayAddr: relay.URL, TokenFile: tokenPath, DeviceID: "desktop-1", Name: "Laptop"}
	var err error
	out := captureStdout(func() {
		err = runOpen(cfg, "phone-1", "https://example.com/report", "", false)
	})
	if err != nil {
		t.Fatalf("runOpen: %v", err)
	}
	if !strings.Contains(out, "notification") {
		t.Fatalf("output = %q, want the tap-to-open message", out)
	}
}

func TestRunOpenFile(t *testing.T) {
	relay := newFakeRelay(t)
	dir := t.TempDir()
	tokenPath := writeClusterToken(t, dir, relay.Token)
	file := filepath.Join(dir, "report.pdf")
	if err := os.WriteFile(file, []byte("pdf-data"), 0o600); err != nil {
		t.Fatal(err)
	}

	startControlTarget(t, relay, "phone-1", "offer", "open_result", func(inner map[string]interface{}) map[string]interface{} {
		if !envelopeBool(inner, "open") {
			t.Errorf("offer did not request an open")
		}
		if envelopeString(inner, "mime_type") != "application/pdf" {
			t.Errorf("mime_type = %q", envelopeString(inner, "mime_type"))
		}
		return map[string]interface{}{
			"request_id":   envelopeString(inner, "open_request_id"),
			"responder_id": "phone-1",
			"transfer_id":  envelopeString(inner, "transfer_id"),
			"status":       "notification",
			"ts":           time.Now().UnixMilli(),
		}
	})

	old := openResultTimeout
	openResultTimeout = 5 * time.Second
	t.Cleanup(func() { openResultTimeout = old })

	cfg := Config{RelayAddr: relay.URL, TokenFile: tokenPath, DeviceID: "desktop-1", Name: "Laptop"}
	var err error
	out := captureStdout(func() {
		err = runOpen(cfg, "phone-1", file, "", false)
	})
	if err != nil {
		t.Fatalf("runOpen file: %v\n%s", err, out)
	}
	if !strings.Contains(out, "notification") {
		t.Fatalf("output = %q, want the tap-to-open message", out)
	}
}
