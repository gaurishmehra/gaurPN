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
