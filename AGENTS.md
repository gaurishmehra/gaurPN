# AGENTS.md

Guidance for agents working in this repository. Read this before changing code, build scripts, or deployment.

## What this is

gaurPN is a custom Android client for Outline/Shadowsocks with three cooperating surfaces:

1. **Android app** (`app/`) — Jetpack Compose UI, `VpnService` tunnel, Quick Settings tile, in-tunnel file sharing and clipboard sync.
2. **Relay** (`server/relay/`) — zero-knowledge Go daemon that runs on the VPS, bound to loopback, reachable only through the Shadowsocks tunnel.
3. **Desktop client** (`desktop-client/`) — single Go binary (`gaur-share`) with `daemon`, `list`, and `send` commands, plus a clipboard socket for the Quickshell shell.

`docs/FILE_SHARING.md` is the protocol reference. `README.md` is the user-facing overview. When behavior changes, update both.

## Environment facts

- **Production VPS:** `ssh oracle-1`. This alias is the only sanctioned route to the server. Do not hardcode IPs or invent hostnames.
- **Homelab already runs Nextcloud and Bitwarden.** File storage/sync, photo backup, notes, and password management are explicitly out of scope. Do not add them, and do not turn the relay into a storage service. Sharing here is ephemeral transport: ciphertext staged for resume, evicted by TTL and byte cap, no accounts, no plaintext, no retention.
- **Desktop UI lives at `/home/gaurish/.config/quickshell`.** It is a separate repo/config, not part of this tree. This repo ships the `gaur-share` binary and the contracts Quickshell consumes. Any change to those contracts requires a matching change in the Quickshell config.

## Repository map

```
app/                     Android app (Kotlin, Compose M3, DataStore)
  src/main/kotlin/dev/gaurish/outlinelite/
    tunnel/              VpnService, route partitioning, LAN bypass
    transfer/            Relay client, crypto, chunk transport, clipboard sync
    ui/                  Home, settings, split tunnel, cluster sharing
    tile/, boot/         Quick Settings tile, boot receiver
go/outlinev/             Go JNI wrapper around outline-go-tun2socks
server/relay/            gaur-relay daemon
desktop-client/          gaur-share CLI
docs/FILE_SHARING.md     Wire protocol and deployment details
third_party/             Upstream clone, created during build (gitignored)
```

## Build and test

Android (JDK 21, SDK 35, NDK r27+, Go 1.22+):

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
```

Go surfaces:

```bash
cd server/relay && go test ./...
cd desktop-client && go test ./...
```

The Android build needs `app/libs/tun2socks.aar`, generated with `gomobile bind` from `go/outlinev` and a fresh `third_party/outline-go-tun2socks` clone. The exact command is in the README. The `.aar` is not committed.

`gradle.properties` pins `org.gradle.java.home`; `local.properties` pins the SDK path. Do not commit either.

## Deployment

Relay to `oracle-1`:

```bash
cd server/relay
GOOS=linux GOARCH=amd64 go build -ldflags "-s -w" -o gaur-relay .
scp gaur-relay oracle-1:/tmp/gaur-relay
ssh oracle-1 'sudo install -m 0755 /tmp/gaur-relay /opt/gaur-relay/gaur-relay && sudo systemctl restart gaur-relay'
ssh oracle-1 'systemctl status gaur-relay --no-pager && journalctl -u gaur-relay -n 50 --no-pager'
```

The cluster token lives at `/etc/gaur-relay/cluster.key` (mode `600`) or in `GAURPN_CLUSTER_TOKEN`. Never print it, copy it into the repo, or pass it on a command line that lands in shell history.

Desktop binary:

```bash
cd desktop-client
go build -ldflags "-s -w" -o gaur-share .
install -m 0755 gaur-share ~/.local/bin/gaur-share
```

Quickshell runs it from `~/.local/bin/gaur-share` when the VPN is active. Restart the shell or the daemon after replacing the binary.

## Quickshell constraints (non-negotiable)

The desktop shell must stay idle-cheap: no periodic CPU wakeups, no steady-state timers, no polling loops. Follow the existing patterns:

- **Event sources only.** Clipboard sync is driven by inotify on the cliphist BoltDB (`FileView` with `blockAllReads: true`) and pushed to the daemon over `$XDG_CACHE_HOME/gaur-share/clipboard.sock`. Peer state is read from `~/.cache/gaur-share/peers.json`, which the daemon rewrites on change.
- **Never read the cliphist DB.** It is tens of megabytes of binary Bolt pages. Watch it; do not load it.
- **No `repeat: true` timers.** Retry timers are single-shot and only start after a real failure. The daemon process runs only while `Theme.vpnActive` is true.
- **Never use `-clipboard-source=poll` from Quickshell.** `poll` exists for standalone use on machines without an event source. Socket mode is the contract.
- **Do not add QML-side file scanning, directory walks, or shell-outs on a timer.** If new state is needed, have the Go daemon publish it as a file or socket message and watch that.

Integration points, all outside this repo:

| Path | Role |
| --- | --- |
| `~/.config/quickshell/panels/DashboardPanel.qml` | Spawns `gaur-share -clipboard-source=socket daemon` while VPN is active |
| `~/.config/quickshell/services/ClipboardSync.qml` | Watches cliphist, writes NDJSON `{kind, mime, data}` to the socket |
| `~/.config/quickshell/dashboard/pages/SharePage.qml` | Watches `~/.cache/gaur-share/peers.json` |
| `~/.config/quickshell/scripts/share_picker.py` | Reads peers and invokes `gaur-share send -open` |

Keep these stable: the peers JSON schema, the clipboard socket message shape, and the CLI flags. Changing any of them means editing the Quickshell config in the same piece of work.

## Security invariants

- Relay binds `127.0.0.1` only. Clients reach it through the Shadowsocks tunnel; never expose it on a public interface.
- The relay holds no keys. It sees ciphertext, transfer IDs, and routing metadata only. Do not add server-side decryption, content inspection, or plaintext logging.
- Inner payloads stay end-to-end encrypted: ephemeral ECDH P-256, HKDF-SHA256, AES-256-GCM with index-bound nonces and per-chunk AAD.
- Sanitize filenames, stage to `.part`, and resolve collisions without overwriting.
- No telemetry, analytics, or third-party network calls. Ever.
- Never log tokens, keys, plaintext clipboards, or file contents. Do not commit `cluster.key`, `config.json` with secrets, or `.env` files.

## Conventions

- Go: standard library first, small dependency surface, table-driven tests. Run `go test ./...` in each module before finishing.
- Kotlin: Compose Material 3, DataStore for persistence, no new UI frameworks.
- Protocol changes: bump the version, keep older clients working where practical, and update `docs/FILE_SHARING.md` plus the Android and desktop implementations together. The two clients must stay wire-compatible.
- Commits follow the existing style: `feat(share): ...`, `fix: ...`, `docs: ...`.
- Verify Android changes with `assembleDebug` and the unit tests. Verify relay changes with `go test` and, when deployed, a `systemctl status` check over `ssh oracle-1`.

## Known gotchas

- The first Android build fails without the generated `tun2socks.aar`; regenerate it after any `go/outlinev` change.
- Android 10+ blocks background clipboard reads. The app uses a brief 1x1 focusable overlay; the system privacy toast cannot be suppressed without being the default IME or using root.
- Desktop clipboard image sync needs `wl-clipboard`/`xclip` (Linux), `pngpaste` (macOS), or PowerShell (Windows).
- `$cache` in the repo root is a stray artifact and is gitignored. Leave it alone.
- Do not run destructive git commands against uncommitted work; this tree frequently carries in-progress changes across all three surfaces.
