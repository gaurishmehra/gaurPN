package main

import (
	"slices"
	"testing"
)

func TestURLActionNotificationArgs(t *testing.T) {
	const (
		title = "Link from Lavander"
		url   = "https://example.com/path?q=1"
	)
	args := urlActionNotificationArgs(title, url)

	if !slices.Contains(args, "-A") {
		t.Fatalf("missing action flag: %q", args)
	}
	if !slices.Contains(args, "open=Open in browser") {
		t.Fatalf("missing open-in-browser action: %q", args)
	}
	if !slices.Contains(args, url) {
		t.Fatalf("missing canonical URL: %q", args)
	}
	if got := args[len(args)-3:]; !slices.Equal(got, []string{"--", title, url}) {
		t.Fatalf("expected [-- title url] at the end, got %q", got)
	}
	// The action wait is 30 seconds, so the notification must stay alive for
	// the whole wait rather than the default 8 seconds.
	timeout := ""
	for i := 0; i+1 < len(args); i++ {
		if args[i] == "-t" {
			timeout = args[i+1]
			break
		}
	}
	if timeout != "30000" {
		t.Fatalf("notification timeout = %q, want 30000", timeout)
	}
}

func TestNotifyIncomingTextUsesCanonicalURL(t *testing.T) {
	oldAction, oldPlain := notifyURLAction, notifyPlainText
	defer func() { notifyURLAction, notifyPlainText = oldAction, oldPlain }()

	var gotTitle, gotURL string
	called := false
	notifyURLAction = func(title, url string) bool {
		called = true
		gotTitle, gotURL = title, url
		return true
	}
	notifyPlainText = func(string, string) {
		t.Fatal("a URL with a working action must not use the plain toast")
	}

	notifyIncomingText("url", "Lavander", " HTTPS://EXAMPLE.COM/Path ")
	if !called {
		t.Fatal("URL text must use the open-in-browser action")
	}
	if gotTitle != "Link from Lavander" {
		t.Fatalf("title = %q", gotTitle)
	}
	// The action receives the canonical form, never the raw clipboard text.
	if gotURL != "https://EXAMPLE.COM/Path" {
		t.Fatalf("action URL = %q, want the canonical URL", gotURL)
	}
}

func TestNotifyIncomingTextPlainPaths(t *testing.T) {
	cases := []struct {
		name string
		kind string
		text string
	}{
		{"plain text", "text", "hello there"},
		{"steam scheme is rejected", "url", "steam://store"},
		{"javascript scheme is rejected", "url", "javascript:alert(1)"},
		{"no scheme", "url", "example.com/path"},
		{"space in URL is rejected", "url", "https://example.com/a b"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			oldAction, oldPlain := notifyURLAction, notifyPlainText
			defer func() { notifyURLAction, notifyPlainText = oldAction, oldPlain }()

			notifyURLAction = func(string, string) bool {
				t.Fatal("invalid or non-URL text must not use the URL action")
				return false
			}
			var plainBody string
			notifyPlainText = func(title, body string) { plainBody = body }

			notifyIncomingText(tc.kind, "Lavander", tc.text)
			if plainBody != tc.text {
				t.Fatalf("plain body = %q, want %q", plainBody, tc.text)
			}
		})
	}
}

func TestNotifyIncomingTextFallsBackWhenActionsUnsupported(t *testing.T) {
	oldAction, oldPlain := notifyURLAction, notifyPlainText
	defer func() { notifyURLAction, notifyPlainText = oldAction, oldPlain }()

	notifyURLAction = func(string, string) bool { return false }
	var plainTitle, plainBody string
	notifyPlainText = func(title, body string) {
		plainTitle, plainBody = title, body
	}

	notifyIncomingText("url", "Lavander", "https://example.com/x")
	if plainTitle != "Link from Lavander" || plainBody != "https://example.com/x" {
		t.Fatalf("fallback = (%q, %q)", plainTitle, plainBody)
	}
}
