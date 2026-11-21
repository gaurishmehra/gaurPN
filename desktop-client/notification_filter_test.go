package main

import (
	"os"
	"slices"
	"testing"
)

func withLocalProcesses(t *testing.T, names []string) {
	t.Helper()
	old := localProcessNames
	localProcessNames = func() []string { return names }
	t.Cleanup(func() { localProcessNames = old })
}

func TestNotificationSourceKeys(t *testing.T) {
	cases := []struct {
		name string
		app  string
		pkg  string
		want []string
	}{
		{
			name: "telegram uses all meaningful package segments",
			app:  "Telegram",
			pkg:  "org.telegram.messenger",
			want: []string{"telegram"},
		},
		{
			name: "generic package segments are dropped",
			app:  "",
			pkg:  "com.google.android.gm",
			want: nil,
		},
		{
			name: "display name words are separate keys",
			app:  "Google Messages",
			pkg:  "com.google.android.apps.messaging",
			want: []string{"googlemessages", "messages", "messaging"},
		},
		{
			name: "short segments never match",
			app:  "X",
			pkg:  "com.x.y",
			want: nil,
		},
		{
			name: "duplicates collapse",
			app:  "Slack",
			pkg:  "com.Slack",
			want: []string{"slack"},
		},
		{
			name: "whatsapp gains its rebranded desktop clients",
			app:  "WhatsApp",
			pkg:  "com.whatsapp",
			want: []string{"whatsapp", "zapfast", "karere"},
		},
		{
			name: "whatsapp business inherits the aliases",
			app:  "",
			pkg:  "com.whatsapp.w4b",
			want: []string{"whatsapp", "zapfast", "karere"},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := notificationSourceKeys(tc.app, tc.pkg)
			if !slices.Equal(got, tc.want) {
				t.Fatalf("notificationSourceKeys(%q, %q) = %q, want %q", tc.app, tc.pkg, got, tc.want)
			}
		})
	}
}

func TestNotificationAppRunning(t *testing.T) {
	cases := []struct {
		name  string
		procs []string
		app   string
		pkg   string
		want  bool
	}{
		{
			name:  "telegram desktop is running",
			procs: []string{"bash", "telegram-desktop", "firefox"},
			app:   "Telegram",
			pkg:   "org.telegram.messenger",
			want:  true,
		},
		{
			name:  "matching full path on macOS",
			procs: []string{"/Applications/Telegram.app/Contents/MacOS/Telegram"},
			app:   "Telegram",
			pkg:   "org.telegram.messenger",
			want:  true,
		},
		{
			name:  "windows executable suffix",
			procs: []string{"Telegram.exe", "explorer.exe"},
			app:   "Telegram",
			pkg:   "org.telegram.messenger",
			want:  true,
		},
		{
			name:  "telegram is not running",
			procs: []string{"bash", "firefox", "code"},
			app:   "Telegram",
			pkg:   "org.telegram.messenger",
			want:  false,
		},
		{
			name:  "desktop payload has no package and is never suppressed",
			procs: []string{"desktop", "gaur-share"},
			app:   "Desktop",
			pkg:   "",
			want:  false,
		},
		{
			name:  "app installed but only in a browser is not a process match",
			procs: []string{"chrome", "firefox"},
			app:   "Gmail",
			pkg:   "com.google.android.gm",
			want:  false,
		},
		{
			name:  "spotify process matches package token",
			procs: []string{"spotify"},
			app:   "Spotify",
			pkg:   "com.spotify.music",
			want:  true,
		},
		{
			name:  "whatsapp matches the zapfast desktop client",
			procs: []string{"zapfast", "bash"},
			app:   "WhatsApp",
			pkg:   "com.whatsapp",
			want:  true,
		},
		{
			name:  "whatsapp business matches the karere desktop client",
			procs: []string{"karere"},
			app:   "",
			pkg:   "com.whatsapp.w4b",
			want:  true,
		},
		{
			name:  "short label does not match an unrelated long process",
			procs: []string{"kworker/u16:0"},
			app:   "Work",
			pkg:   "com.example.work",
			want:  false,
		},
		{
			name:  "short label still matches an exact process name",
			procs: []string{"work"},
			app:   "Work",
			pkg:   "com.example.work",
			want:  true,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			withLocalProcesses(t, tc.procs)
			payload := mirroredNotification{App: tc.app, Package: tc.pkg}
			if got := notificationAppRunning(payload); got != tc.want {
				t.Fatalf("notificationAppRunning(%q, %q) = %v, want %v", tc.app, tc.pkg, got, tc.want)
			}
		})
	}
}

func TestNotificationAppRunningIgnoresOwnProcess(t *testing.T) {
	oldArgs := os.Args
	os.Args = []string{"gaur-share"}
	t.Cleanup(func() { os.Args = oldArgs })
	withLocalProcesses(t, []string{"gaur-share"})

	// "share" is a substring of the client's own name; it must not count as
	// the source app running.
	payload := mirroredNotification{App: "Share", Package: "com.example.share"}
	if notificationAppRunning(payload) {
		t.Fatal("the client's own process must never suppress a notification")
	}
}

func TestNotificationAppRunningSkipsLookupWithoutPackage(t *testing.T) {
	old := localProcessNames
	localProcessNames = func() []string {
		t.Fatal("process lookup must not run for a payload without a package")
		return nil
	}
	t.Cleanup(func() { localProcessNames = old })

	if notificationAppRunning(mirroredNotification{App: "Telegram"}) {
		t.Fatal("packageless payloads are always shown")
	}
}

func TestListLocalProcessNamesReturnsSomething(t *testing.T) {
	if testing.Short() {
		t.Skip("spawns the platform process lister")
	}
	if names := listLocalProcessNames(); len(names) == 0 {
		t.Fatal("expected at least one running process")
	}
}
