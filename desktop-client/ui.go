package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"time"
)

// runUI serves a localhost-only control panel. Every action shells out to the
// same binary with the same config, so the UI cannot drift from the CLI's
// crypto or relay behavior. It binds 127.0.0.1 and never exposes the token.
func runUI(cfg Config, addr string) {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, uiHTML)
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

	ln, err := net.Listen("tcp", addr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "[FATAL] Could not start UI: %v\n", err)
		os.Exit(1)
	}
	url := "http://" + ln.Addr().String()
	fmt.Printf("gaur-share UI: %s\n", url)
	fmt.Println("Local only. Press Ctrl-C to stop.")
	openFile(url)
	if err := http.Serve(ln, mux); err != nil {
		fmt.Fprintf(os.Stderr, "[FATAL] UI stopped: %v\n", err)
		os.Exit(1)
	}
}

func runCLI(cfg Config, timeout time.Duration, args ...string) (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	base := []string{
		"-relay", cfg.RelayAddr,
		"-key-file", cfg.TokenFile,
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
  main { max-width: 760px; margin: 0 auto; padding: 40px 20px 80px; }
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
    <label>Status</label>
    <pre id="out">Ready.</pre>
  </div>
</main>
<script>
const $ = (id) => document.getElementById(id);
const peer = $("peer");
const out = $("out");

async function refresh() {
  out.textContent = "Loading devices…";
  try {
    const r = await fetch("/api/peers");
    const j = await r.json();
    const peers = JSON.parse(j.output || "[]");
    peer.innerHTML = "";
    const others = peers.filter(p => !p.IsSelf);
    if (!others.length) {
      peer.innerHTML = '<option value="">No other devices online</option>';
    } else {
      for (const p of others) {
        const o = document.createElement("option");
        o.value = p.Name;
        o.textContent = p.Name + " (" + p.ID + ")";
        peer.appendChild(o);
      }
    }
    out.textContent = j.output || "No devices.";
  } catch (e) {
    out.textContent = "Could not load devices: " + e;
  }
}

async function postJSON(url, body) {
  out.textContent = "Working…";
  const r = await fetch(url, { method:"POST", headers:{"Content-Type":"application/json"}, body: JSON.stringify(body) });
  const j = await r.json();
  out.textContent = j.output || j.status;
}

$("refresh").onclick = refresh;
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
  const r = await fetch("/api/send-file", { method:"POST", body: fd });
  const j = await r.json();
  out.textContent = j.output || j.status;
};
refresh();
</script>
</body>
</html>`
