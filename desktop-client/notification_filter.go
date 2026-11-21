package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// notificationAppStopWords are package/app-name segments too generic to
// identify a running desktop program. Matching on them would suppress
// unrelated notifications (for example, "music" from any media player).
var notificationAppStopWords = map[string]struct{}{
	"android":   {},
	"google":    {},
	"app":       {},
	"apps":      {},
	"mobile":    {},
	"client":    {},
	"messenger": {},
	"music":     {},
}

// desktopClientAliases maps Android packages to desktop process names for
// services whose Linux client ships under a different brand. The prefix match
// covers variants such as WhatsApp Business (com.whatsapp.w4b).
var desktopClientAliases = []struct {
	packagePrefix string
	processes     []string
}{
	{packagePrefix: "com.whatsapp", processes: []string{"zapfast", "karere"}},
}

// desktopAliasesForPackage returns the rebranded desktop clients that speak
// for an Android package. The comparison is case-insensitive because the
// payload crosses the wire and only the sender controls its casing.
func desktopAliasesForPackage(pkg string) []string {
	pkg = strings.ToLower(pkg)
	for _, alias := range desktopClientAliases {
		if pkg == alias.packagePrefix ||
			strings.HasPrefix(pkg, alias.packagePrefix+".") {
			return alias.processes
		}
	}
	return nil
}

// localProcessNames lists the names of running processes. It is a package var
// so tests can inject a fixed set instead of depending on the host.
var localProcessNames = listLocalProcessNames

// listLocalProcessNames asks the platform for one name per process. The output
// is only read when a notification arrives, never on a timer.
func listLocalProcessNames() []string {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("tasklist", "/FO", "CSV", "/NH")
	default:
		cmd = exec.Command("ps", "-A", "-o", "comm=")
	}
	out, err := cmd.Output()
	if err != nil {
		return nil
	}
	lines := strings.Split(string(out), "\n")
	names := make([]string, 0, len(lines))
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		// tasklist emits CSV; the process name is the first field.
		if runtime.GOOS == "windows" {
			if i := strings.IndexByte(line, ','); i >= 0 {
				line = line[:i]
			}
			line = strings.Trim(line, `"`)
		}
		names = append(names, line)
	}
	return names
}

// normalizedProcessName keeps only lowercase letters and digits, so
// "Telegram Desktop", "/usr/bin/telegram-desktop", and "Telegram.exe" all
// reduce to comparable strings.
func normalizedProcessName(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range strings.ToLower(s) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// notificationSourceKeys derives the tokens that identify the Android app
// behind a mirrored notification. The display name is the strongest signal
// ("Telegram", "Slack", "Signal"); package segments fill in apps whose label
// is missing. Top-level and generic segments are dropped to avoid matching a
// process that has nothing to do with the app.
func notificationSourceKeys(app, pkg string) []string {
	seen := make(map[string]struct{})
	keys := make([]string, 0, 6)
	add := func(candidate string) {
		key := normalizedProcessName(candidate)
		if len(key) < 4 {
			return
		}
		if _, stop := notificationAppStopWords[key]; stop {
			return
		}
		if _, dup := seen[key]; dup {
			return
		}
		seen[key] = struct{}{}
		keys = append(keys, key)
	}

	add(app)
	for _, word := range strings.Fields(app) {
		add(word)
	}
	parts := strings.Split(pkg, ".")
	for i, part := range parts {
		if i == 0 && len(parts) > 1 { // reverse-DNS top level: com, org, net, io, ...
			continue
		}
		add(part)
	}
	// A rebranded desktop client (WhatsApp -> ZapFast/Karere) has no token in
	// common with the Android label or package, so add it explicitly.
	for _, alias := range desktopAliasesForPackage(pkg) {
		add(alias)
	}
	return keys
}

// processMatchesKey decides whether one process identity corresponds to an
// app token. Exact matches are always trusted. Substring matches need a token
// of at least five characters, so a short label such as "Work" cannot match
// an unrelated "kworker" process.
func processMatchesKey(proc, key string) bool {
	if proc == key {
		return true
	}
	return len(key) >= 5 && strings.Contains(proc, key)
}

// notificationAppRunning reports whether the app that produced a mirrored
// notification is already running on this desktop. A running app receives and
// renders its own notifications, so the mirrored copy would be a duplicate.
//
// Payloads that carry no package (desktop-originated notifications) are never
// suppressed: there is no Android package to map to a local process.
func notificationAppRunning(payload mirroredNotification) bool {
	if payload.Package == "" {
		return false
	}
	keys := notificationSourceKeys(payload.App, payload.Package)
	if len(keys) == 0 {
		return false
	}
	// Never let the client match its own process. Without this, an Android
	// app labelled "Share" would collide with "gaur-share".
	self := normalizedProcessName(filepath.Base(os.Args[0]))
	for _, proc := range localProcessNames() {
		proc = normalizedProcessName(proc)
		if len(proc) < 4 || proc == self {
			continue
		}
		for _, key := range keys {
			if processMatchesKey(proc, key) {
				return true
			}
		}
	}
	return false
}
