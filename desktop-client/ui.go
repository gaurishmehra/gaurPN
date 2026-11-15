package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"syscall"
	"time"
)

// defaultUIAddr is the fixed loopback port the UI binds. A launcher can probe
// it to detect an already-running server; a busy port is reported as a clean
// one-line error instead of a random port nobody can find.
const defaultUIAddr = "127.0.0.1:9877"

// uiOpenURL is a package var so tests can observe the --open behavior without
// launching a browser.
var uiOpenURL = openUIInBrowser

// uiServe is a package var so tests can stop the server without holding a real
// port open.
var uiServe = http.Serve

// openUIInBrowser opens the UI in the default browser. Linux uses xdg-open
// with the URL as a single argv element (no shell). Other platforms print the
// URL instead of guessing a launcher.
func openUIInBrowser(url string) {
	if runtime.GOOS != "linux" {
		fmt.Printf("Open %s in your browser.\n", url)
		return
	}
	cmd := exec.Command("xdg-open", url)
	if err := cmd.Start(); err != nil {
		fmt.Fprintf(os.Stderr, "[WARNING] Could not open the browser: %v\n", err)
		return
	}
	go func() { _ = cmd.Wait() }()
}

// runUI serves a localhost-only control panel. Every action shells out to the
// same binary with the same config, so the UI cannot drift from the CLI's
// crypto or relay behavior. It binds loopback and never exposes the relay token.
func runUI(cfg Config, addr string, open bool) error {
	if host, _, err := net.SplitHostPort(addr); err != nil || !isLoopbackHost(host) {
		fmt.Fprintf(os.Stderr,
			"[WARNING] The UI is starting on %s, which is NOT a loopback address.\n"+
				"Anyone else on that network can open this page and send text and files as this machine.\n"+
				"Press Ctrl-C now if that was not intended.\n", addr)
	}

	ln, err := net.Listen("tcp", addr)
	if err != nil {
		if errors.Is(err, syscall.EADDRINUSE) {
			return fmt.Errorf("ui already running on %s", addr)
		}
		return fmt.Errorf("could not start UI on %s: %w", addr, err)
	}
	// Pin every request to the address the listener actually bound; with a
	// custom -addr the port is only known here.
	bind := ln.Addr().String()
	// A fresh CSRF token per server run. The page carries it and every
	// /api/* POST must echo it; the token is never logged.
	token, err := randomHex(32)
	if err != nil {
		_ = ln.Close()
		return fmt.Errorf("could not create UI token: %w", err)
	}
	handler := newUIHandler(cfg, bind, token)
	url := "http://" + bind + "/"
	fmt.Printf("gaur-share UI: %s\n", url)
	fmt.Println("Local only. Press Ctrl-C to stop.")
	if open {
		uiOpenURL(url)
	}
	if err := uiServe(ln, handler); err != nil {
		return fmt.Errorf("UI stopped: %w", err)
	}
	return nil
}

// newUIHandler builds the UI routes wrapped in a guard that pins requests to
// the bound address and requires the CSRF token on every /api/* POST.
func newUIHandler(cfg Config, bind, token string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = io.WriteString(w, strings.ReplaceAll(uiHTML, uiTokenPlaceholder, token))
	})
	mux.HandleFunc("/api/peers", func(w http.ResponseWriter, r *http.Request) {
		out, err := runCLI(cfg, 15*time.Second, "list", "-json")
		writeResult(w, out, err)
	})
	mux.HandleFunc("/api/send-text", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			Target string `json:"target"`
			Text   string `json:"text"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 128*1024)).Decode(&body); err != nil {
			http.Error(w, "invalid JSON", http.StatusBadRequest)
			return
		}
		if strings.TrimSpace(body.Target) == "" || strings.TrimSpace(body.Text) == "" {
			http.Error(w, "target and text are required", http.StatusBadRequest)
			return
		}
		out, err := runCLI(cfg, 60*time.Second, "send-text", body.Target, body.Text)
		writeResult(w, out, err)
	})
	mux.HandleFunc("/api/send-file", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", http.StatusMethodNotAllowed)
			return
		}
		if err := r.ParseMultipartForm(32 << 20); err != nil {
			http.Error(w, "invalid upload", http.StatusBadRequest)
			return
		}
		target := strings.TrimSpace(r.FormValue("target"))
		file, header, err := r.FormFile("file")
		if err != nil || target == "" {
			http.Error(w, "target and file are required", http.StatusBadRequest)
			return
		}
		defer file.Close()

		tmp, err := os.CreateTemp("", "gaur-share-ui-*"+filepathExt(header.Filename))
		if err != nil {
			http.Error(w, "could not stage upload", http.StatusInternalServerError)
			return
		}
		tmpPath := tmp.Name()
		defer os.Remove(tmpPath)
		if _, err := io.Copy(tmp, file); err != nil {
			tmp.Close()
			http.Error(w, "could not stage upload", http.StatusInternalServerError)
			return
		}
		if err := tmp.Close(); err != nil {
			http.Error(w, "could not stage upload", http.StatusInternalServerError)
			return
		}

		out, err := runCLI(cfg, 30*time.Minute, "send", target, tmpPath)
		writeResult(w, out, err)
	})
	mux.HandleFunc("/api/browse", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			Target string `json:"target"`
			Path   string `json:"path"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64*1024)).Decode(&body); err != nil {
			http.Error(w, "invalid JSON", http.StatusBadRequest)
			return
		}
		target := strings.TrimSpace(body.Target)
		if target == "" {
			http.Error(w, "target is required", http.StatusBadRequest)
			return
		}
		if !validBrowsePath(body.Path) {
			http.Error(w, "invalid path", http.StatusBadRequest)
			return
		}
		out, err := runCLI(cfg, 2*time.Minute, "browse", "--json", target, "--", body.Path)
		writeBrowseResult(w, out, err)
	})
	mux.HandleFunc("/api/fetch", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", http.StatusMethodNotAllowed)
			return
		}
		var body struct {
			Target string   `json:"target"`
			Paths  []string `json:"paths"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64*1024)).Decode(&body); err != nil {
			http.Error(w, "invalid JSON", http.StatusBadRequest)
			return
		}
		target := strings.TrimSpace(body.Target)
		if target == "" || len(body.Paths) == 0 || len(body.Paths) > fetchMaxPaths {
			http.Error(w, "target and 1-32 paths are required", http.StatusBadRequest)
			return
		}
		for _, path := range body.Paths {
			if path == "" || !validBrowsePath(path) {
				http.Error(w, "invalid path", http.StatusBadRequest)
				return
			}
		}
		args := append([]string{"fetch", target, "--"}, body.Paths...)
		out, err := runCLI(cfg, 30*time.Minute, args...)
		w.Header().Set("Content-Type", "application/json")
		ok := err == nil
		if err != nil {
			out = strings.TrimSpace(out + "\n" + err.Error())
		}
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"ok":     ok,
			"output": out,
		})
	})
	return uiGuard(bind, token, mux)
}

// browseResultPayload is the only shape the UI relays back to the page. The
// CLI's stdout is decoded into this struct and re-encoded, so a future CLI
// field (or a compromised subprocess's extra output) can never pass through to
// the browser.
type browseResultPayload struct {
	Path      string        `json:"path"`
	Entries   []browseEntry `json:"entries"`
	Truncated bool          `json:"truncated,omitempty"`
	Error     string        `json:"error,omitempty"`
}

// writeBrowseResult decodes the browse CLI's JSON into a fixed struct and
// re-encodes it. A non-zero exit is reported as 502 so the page can surface
// the error without guessing at a wrapper shape; the body is still JSON either
// way. Raw stdout is never relayed.
func writeBrowseResult(w http.ResponseWriter, out string, err error) {
	w.Header().Set("Content-Type", "application/json")
	payload := browseResultPayload{Entries: []browseEntry{}}
	decodeErr := json.Unmarshal([]byte(strings.TrimSpace(out)), &payload)
	if payload.Entries == nil {
		payload.Entries = []browseEntry{}
	}
	status := http.StatusOK
	switch {
	case err != nil:
		status = http.StatusBadGateway
		if payload.Error == "" {
			payload.Error = singleLineDisplayName(err.Error())
		}
	case decodeErr != nil:
		status = http.StatusInternalServerError
		payload = browseResultPayload{Entries: []browseEntry{}}
		payload.Error = "browse produced no output"
	}
	payload.Error = singleLineDisplayName(payload.Error)
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

// uiTokenPlaceholder is replaced with the per-run CSRF token when the page is
// served. It is a placeholder, not a secret, so the const HTML stays static.
const uiTokenPlaceholder = "__GAUR_TOKEN__"

// uiGuard rejects requests whose Host header is not the bound address (a
// DNS-rebinding probe) and, when a browser supplies an Origin header, requests
// whose Origin is not the UI's own origin (a cross-site form POST or fetch).
// Every /api/* POST must also carry the per-run CSRF token; GET / stays open
// for the liveness probe. Non-browser clients such as curl still need the
// token, which the page obtains by loading the HTML first.
func uiGuard(bind, token string, next http.Handler) http.Handler {
	allowedHosts := map[string]bool{bind: true}
	allowedOrigins := map[string]bool{"http://" + bind: true}
	if host, port, err := net.SplitHostPort(bind); err == nil && host != "localhost" {
		// Courtesy: the same listener is also reachable as localhost:<port>,
		// and localhost cannot be DNS-rebound.
		allowedHosts["localhost:"+port] = true
		allowedOrigins["http://localhost:"+port] = true
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowedHosts[r.Host] {
			http.Error(w, "host not allowed", http.StatusForbidden)
			return
		}
		if origin := r.Header.Get("Origin"); origin != "" && !allowedOrigins[origin] {
			http.Error(w, "origin not allowed", http.StatusForbidden)
			return
		}
		if r.Method == http.MethodPost && strings.HasPrefix(r.URL.Path, "/api/") {
			supplied := r.Header.Get("X-Gaur-Token")
			if subtle.ConstantTimeCompare([]byte(supplied), []byte(token)) != 1 {
				http.Error(w, "invalid token", http.StatusForbidden)
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// isLoopbackHost reports whether the host part of a listen address is
// loopback. An empty host means all interfaces and is not loopback.
func isLoopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.IsLoopback()
	}
	return false
}

// runCLI is a package var so UI tests can stub the subprocess.
var runCLI = runCLIProcess

func runCLIProcess(cfg Config, timeout time.Duration, args ...string) (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	base := []string{
		"-relay", cfg.RelayAddr,
		"-key-file", cfg.TokenFile,
	}
	// Forward the UI's identity and directories so a subprocess acts as the
	// same device the daemon does and writes fetched files to the inbox the
	// user configured.
	if cfg.DeviceID != "" {
		base = append(base, "-device-id", cfg.DeviceID)
	}
	if cfg.InboxDir != "" {
		base = append(base, "-inbox", cfg.InboxDir)
	}
	if cfg.BrowseRoot != "" {
		base = append(base, "-browse-root", cfg.BrowseRoot)
	}
	if strings.TrimSpace(cfg.Name) != "" {
		base = append(base, "-name", cfg.Name)
	}
	base = append(base, args...)

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, exe, base...)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func writeResult(w http.ResponseWriter, out string, err error) {
	w.Header().Set("Content-Type", "application/json")
	status := "ok"
	if err != nil {
		status = "error"
		out = strings.TrimSpace(out + "\n" + err.Error())
	}
	_ = json.NewEncoder(w).Encode(map[string]string{
		"status": status,
		"output": out,
	})
}

func filepathExt(name string) string {
	if i := strings.LastIndexByte(name, '.'); i >= 0 && len(name)-i <= 16 {
		return name[i:]
	}
	return ""
}

const uiHTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>gaurPN share</title>
<style>
  :root { color-scheme: dark; --bg:#0a0d12; --card:#12151b; --line:#232a33; --text:#e8edf3; --muted:#98a2b0; --accent:#5bd9a3; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:14px/1.5 system-ui, -apple-system, Segoe UI, sans-serif; }
  main { max-width: 860px; margin: 0 auto; padding: 40px 20px 80px; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  .sub { color: var(--muted); margin: 0 0 28px; }
  .card { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 18px; margin-bottom: 16px; }
  label { display:block; color: var(--muted); font-size: 12px; margin-bottom: 6px; }
  select, textarea, input[type=file] { width:100%; background:#0e1117; color:var(--text); border:1px solid var(--line); border-radius:10px; padding:10px; font:inherit; }
  textarea { min-height: 92px; resize: vertical; }
  button { background: var(--accent); color:#04231a; border:0; border-radius:10px; padding:10px 16px; font-weight:650; cursor:pointer; margin-top:10px; }
  button.ghost { background: transparent; color: var(--text); border:1px solid var(--line); }
  button:disabled { opacity:.5; cursor:default; }
  .row { display:flex; gap:10px; align-items:center; }
  .row > * { flex:1; }
  pre { white-space: pre-wrap; word-break: break-word; background:#0e1117; border:1px solid var(--line); border-radius:10px; padding:12px; min-height: 42px; color: var(--muted); }
  .hint { color: var(--muted); font-size: 12px; margin-top: 8px; }
  .crumbs { display:flex; gap:4px; align-items:center; flex-wrap:wrap; margin:12px 0 8px; }
  .crumbs button { margin:0; padding:4px 8px; font-size:12px; }
  .crumbs span { color: var(--muted); }
  table { width:100%; border-collapse: collapse; }
  th, td { text-align:left; padding:6px 8px; border-bottom:1px solid var(--line); font-size:13px; }
  th { color: var(--muted); font-weight:600; }
  td.pick, th.pick { width: 28px; }
  .link { color: var(--accent); cursor:pointer; }
  .link:hover { text-decoration: underline; }
  .empty { color: var(--muted); padding: 10px 8px; }
</style>
</head>
<body>
<main>
  <h1>gaurPN share</h1>
  <p class="sub">Local control panel. Traffic stays inside the Shadowsocks tunnel.</p>

  <div class="card">
    <div class="row">
      <div>
        <label for="peer">Device</label>
        <select id="peer"><option value="">Refresh to load devices…</option></select>
      </div>
      <div style="flex:0 0 auto; align-self:end;">
        <button class="ghost" id="refresh">Refresh</button>
      </div>
    </div>
  </div>

  <div class="card">
    <label for="text">Text or link</label>
    <textarea id="text" placeholder="https://example.com or a short note"></textarea>
    <button id="sendText">Send text</button>
  </div>

  <div class="card">
    <label for="file">File (drag and drop or choose)</label>
    <input id="file" type="file">
    <button id="sendFile">Send file</button>
    <p class="hint">The file is staged in a temporary directory, sent through the tunnel, then deleted locally.</p>
  </div>

  <div class="card">
    <label for="browsePeer">Browse files on a device</label>
    <div class="row">
      <div>
        <select id="browsePeer"><option value="">Refresh to load devices…</option></select>
      </div>
      <div style="flex:0 0 auto; align-self:end;">
        <button class="ghost" id="browseGo">Browse</button>
      </div>
    </div>
    <div class="crumbs" id="crumbs"></div>
    <table>
      <thead>
        <tr><th></th><th>Name</th><th>Size</th><th>Modified</th><th class="pick"></th></tr>
      </thead>
      <tbody id="entries"></tbody>
    </table>
    <div class="empty" id="empty" hidden>This folder is empty.</div>
    <button id="fetchBtn" disabled>Fetch 0 files</button>
    <p class="hint">Pick files from a device's shared folder. The device must have the daemon running and the folder inside its browse root.</p>
  </div>

  <div class="card">
    <label>Status</label>
    <pre id="out">Ready.</pre>
  </div>
</main>
<script>
const GAUR_TOKEN = "__GAUR_TOKEN__";
const $ = (id) => document.getElementById(id);
const peer = $("peer");
const browsePeer = $("browsePeer");
const out = $("out");
const crumbs = $("crumbs");
const entriesBody = $("entries");
const empty = $("empty");
const fetchBtn = $("fetchBtn");

let browse = { target: "", path: "", entries: [], selected: new Set() };

function option(value, text) {
  const o = document.createElement("option");
  o.value = value;
  o.textContent = text;
  return o;
}

function fillPeers(select, peers) {
  select.replaceChildren();
  if (!peers.length) {
    select.appendChild(option("", "No other devices online"));
    return;
  }
  for (const p of peers) {
    select.appendChild(option(p.id, p.name + " (" + p.id + ")"));
  }
}

async function refresh() {
  out.textContent = "Loading devices…";
  try {
    const r = await fetch("/api/peers");
    const j = await r.json();
    const peers = JSON.parse(j.output || "[]");
    const others = peers.filter(p => !p.is_self);
    fillPeers(peer, others);
    fillPeers(browsePeer, others);
    out.textContent = j.output || "No devices.";
  } catch (e) {
    out.textContent = "Could not load devices: " + e;
  }
}

async function postJSON(url, body) {
  out.textContent = "Working…";
  const r = await fetch(url, { method:"POST", headers:{"Content-Type":"application/json","X-Gaur-Token":GAUR_TOKEN}, body: JSON.stringify(body) });
  const j = await r.json();
  out.textContent = j.output || j.status || "Done.";
}

function humanSize(n) {
  if (n < 1024) return n + " B";
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let v = n;
  for (const u of units) {
    v = v / 1024;
    if (v < 1024) return v.toFixed(1) + " " + u;
  }
  return (v / 1024).toFixed(1) + " PiB";
}

function formatModified(ms) {
  if (!ms) return "—";
  return new Date(ms).toLocaleString();
}

function joinPath(base, name) {
  return base ? base + "/" + name : name;
}

function renderCrumbs() {
  crumbs.replaceChildren();
  const root = document.createElement("button");
  root.className = "ghost";
  root.textContent = "/";
  root.onclick = () => loadBrowse("");
  crumbs.appendChild(root);
  if (!browse.path) return;
  let acc = "";
  for (const part of browse.path.split("/")) {
    acc = joinPath(acc, part);
    const sep = document.createElement("span");
    sep.textContent = "/";
    crumbs.appendChild(sep);
    const b = document.createElement("button");
    b.className = "ghost";
    b.textContent = part;
    const target = acc;
    b.onclick = () => loadBrowse(target);
    crumbs.appendChild(b);
  }
}

function renderEntries() {
  entriesBody.replaceChildren();
  for (const e of browse.entries) {
    const tr = document.createElement("tr");
    const icon = document.createElement("td");
    icon.textContent = e.dir ? "📁" : "📄";
    const nameCell = document.createElement("td");
    const label = document.createElement("span");
    label.textContent = e.name + (e.dir ? "/" : "");
    if (e.dir) {
      label.className = "link";
      label.onclick = () => loadBrowse(joinPath(browse.path, e.name));
    }
    nameCell.appendChild(label);
    const sizeCell = document.createElement("td");
    sizeCell.textContent = e.dir ? "—" : humanSize(e.size);
    const modCell = document.createElement("td");
    modCell.textContent = formatModified(e.modified);
    const pickCell = document.createElement("td");
    pickCell.className = "pick";
    if (!e.dir) {
      const cb = document.createElement("input");
      cb.type = "checkbox";
      const path = joinPath(browse.path, e.name);
      cb.checked = browse.selected.has(path);
      cb.onchange = () => {
        if (cb.checked) browse.selected.add(path); else browse.selected.delete(path);
        updateFetchButton();
      };
      pickCell.appendChild(cb);
    }
    tr.append(icon, nameCell, sizeCell, modCell, pickCell);
    entriesBody.appendChild(tr);
  }
  empty.hidden = browse.entries.length !== 0;
  updateFetchButton();
}

function updateFetchButton() {
  let total = 0;
  for (const e of browse.entries) {
    if (!e.dir && browse.selected.has(joinPath(browse.path, e.name))) total += e.size;
  }
  const n = browse.selected.size;
  fetchBtn.disabled = n === 0;
  fetchBtn.textContent = "Fetch " + n + " file" + (n === 1 ? "" : "s") + " (" + humanSize(total) + ")";
}

async function loadBrowse(path) {
  const target = browsePeer.value;
  if (!target) { out.textContent = "Pick a device first."; return; }
  out.textContent = "Loading " + (path || "/") + "…";
  try {
    const r = await fetch("/api/browse", { method:"POST", headers:{"Content-Type":"application/json","X-Gaur-Token":GAUR_TOKEN}, body: JSON.stringify({ target: target, path: path }) });
    let j;
    try { j = await r.json(); } catch (e) { j = { error: "invalid response" }; }
    if (!r.ok || j.error) {
      out.textContent = "Browse failed: " + (j.error || r.status);
      return;
    }
    browse = { target: target, path: j.path || "", entries: j.entries || [], selected: new Set() };
    renderCrumbs();
    renderEntries();
    out.textContent = j.truncated ? "Listing truncated to 1000 entries." : "Ready.";
  } catch (e) {
    out.textContent = "Browse failed: " + e;
  }
}

async function fetchSelected() {
  const paths = Array.from(browse.selected);
  if (!paths.length) return;
  out.textContent = "Fetching " + paths.length + " file(s)…";
  try {
    const r = await fetch("/api/fetch", { method:"POST", headers:{"Content-Type":"application/json","X-Gaur-Token":GAUR_TOKEN}, body: JSON.stringify({ target: browse.target, paths: paths }) });
    const j = await r.json();
    out.textContent = j.output || (j.ok ? "Done." : "Fetch failed.");
    if (j.ok) {
      browse.selected = new Set();
      renderEntries();
    }
  } catch (e) {
    out.textContent = "Fetch failed: " + e;
  }
}

$("refresh").onclick = refresh;
$("browseGo").onclick = () => loadBrowse("");
$("fetchBtn").onclick = fetchSelected;
$("sendText").onclick = () => {
  if (!peer.value) return out.textContent = "Pick a device first.";
  if (!$("text").value.trim()) return out.textContent = "Type something first.";
  postJSON("/api/send-text", { target: peer.value, text: $("text").value });
};
$("sendFile").onclick = async () => {
  if (!peer.value) return out.textContent = "Pick a device first.";
  const f = $("file").files[0];
  if (!f) return out.textContent = "Choose a file first.";
  out.textContent = "Uploading and sending…";
  const fd = new FormData();
  fd.append("target", peer.value);
  fd.append("file", f);
  const r = await fetch("/api/send-file", { method:"POST", headers:{"X-Gaur-Token":GAUR_TOKEN}, body: fd });
  const j = await r.json();
  out.textContent = j.output || j.status;
};
refresh();
</script>
</body>
</html>`
