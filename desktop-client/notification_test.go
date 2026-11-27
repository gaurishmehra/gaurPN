package main

import (
	"slices"
	"strings"
	"testing"
)

func TestNotifySendArgsEndsOptionsBeforePositionals(t *testing.T) {
	title := "-e" // would be read as an option without the sentinel
	body := `say "hello"`
	args := notifySendArgs(title, body, "", nil)

	if !slices.Contains(args, "--") {
		t.Fatalf("expected the end-of-options sentinel, got %q", args)
	}
	// The sentinel must sit immediately before the title and body.
	if got := args[len(args)-3:]; !slices.Equal(got, []string{"--", title, body}) {
		t.Fatalf("expected [-- title body] at the end, got %q", got)
	}
}

func TestNotifySendArgsCarriesIconAndActions(t *testing.T) {
	args := notifySendArgs("title", "body", "/tmp/icon.png", []string{"1=Reply", "2=Open"})
	want := []string{
		"-a", "gaurPN", "-t", "8000",
		"-i", "/tmp/icon.png",
		"-A", "1=Reply", "-A", "2=Open",
		"--", "title", "body",
	}
	if !slices.Equal(args, want) {
		t.Fatalf("args = %q, want %q", args, want)
	}
}

func TestNotifySendArgsForIDReplacesByDefault(t *testing.T) {
	args := notifySendArgsForID("title", "body", "", nil, 8000, 42)
	want := []string{
		"-a", "gaurPN", "-t", "8000",
		"-r", "42",
		"--", "title", "body",
	}
	if !slices.Equal(args, want) {
		t.Fatalf("args = %q, want %q", args, want)
	}
	// A zero id means "do not replace"; approval and URL notifications must
	// stay independent bubbles.
	plain := notifySendArgsForID("title", "body", "", nil, 8000, 0)
	if slices.Contains(plain, "-r") {
		t.Fatalf("zero replace id must omit -r, got %q", plain)
	}
}

func TestNotificationReplaceIDIsStableAndDistinct(t *testing.T) {
	a := notificationReplaceID("android-1", "0|com.whatsapp|1")
	b := notificationReplaceID("android-1", "0|com.whatsapp|1")
	if a != b {
		t.Fatalf("replace id not stable: %d != %d", a, b)
	}
	if a <= 0 {
		t.Fatalf("replace id must be positive, got %d", a)
	}
	if a == notificationReplaceID("android-2", "0|com.whatsapp|1") {
		t.Fatal("different senders must not share a replace id")
	}
	if a == notificationReplaceID("android-1", "0|com.whatsapp|2") {
		t.Fatal("different notification keys must not share a replace id")
	}
}

func TestNotificationInnerCarriesSourceIdentity(t *testing.T) {
	cfg := Config{Name: "Desktop", SenderID: "desktop-1"}
	inner := notificationInner(cfg, "Title", "Body", "", "ZapFast", "com.whatsapp")
	if inner["app"] != "ZapFast" {
		t.Fatalf("app = %q, want ZapFast", inner["app"])
	}
	if inner["package"] != "com.whatsapp" {
		t.Fatalf("package = %q, want com.whatsapp", inner["package"])
	}
	if inner["sender_id"] != "desktop-1" {
		t.Fatalf("sender_id = %q, want desktop-1", inner["sender_id"])
	}
	if inner["event"] != "posted" {
		t.Fatalf("event = %q, want posted", inner["event"])
	}

	plain := notificationInner(cfg, "Title", "Body", "", "", "")
	if _, ok := plain["package"]; ok {
		t.Fatal("a payload without a source package must omit the field")
	}
	if plain["app"] != "Desktop" {
		t.Fatalf("app = %q, want the sender name fallback", plain["app"])
	}
}

func TestEscapeAppleScriptString(t *testing.T) {
	cases := []struct{ in, want string }{
		{"plain text", "plain text"},
		{`" & quit & "`, `\" & quit & \"`},
		{`back\slash`, `back\\slash`},
		{`\"`, `\\\"`},
		{"line\nbreak", "line\nbreak"},
	}
	for _, tc := range cases {
		if got := escapeAppleScriptString(tc.in); got != tc.want {
			t.Fatalf("escapeAppleScriptString(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}

	// The injection vector must not survive as live AppleScript: every quote
	// is escaped, so the payload stays inside the string literal.
	inert := escapeAppleScriptString(`" & quit & "`)
	if strings.Contains(inert, `" & quit & "`) {
		t.Fatalf("injection vector not neutralized: %q", inert)
	}
	if !strings.Contains(inert, `\"`) {
		t.Fatalf("quotes must be escaped: %q", inert)
	}
}
