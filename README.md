# gaurPN

**One private network for every device you own.**

gaurPN is a self-hosted ecosystem that keeps your phone, laptop, and desktop connected to each other — and to the open internet — no matter where you are, without surrendering a single byte of visibility to anyone else. A custom Android VPN client, a desktop companion, and a zero-knowledge relay on your own VPS work together as one system: your traffic tunnels through Shadowsocks to a server you control, and everything shared between your devices is end-to-end encrypted before it ever leaves them.

No accounts. No telemetry. No third-party servers. Just your devices, your tunnel, your keys.

## The ecosystem

Three surfaces, one network:

- **gaurPN (Android)** — the VPN client and your always-on link back to the cluster. Connects with one tap or a Quick Settings tile, reconnects on its own when the network drops, and routes every packet through a protected Go tunnel engine. On top of the VPN it carries the cluster experience: live peer cards, file transfers, clipboard sync, notification mirroring, and media control.
- **gaur-share (Linux / macOS / Windows)** — a single standalone Go binary that plugs any desktop into the same cluster. It receives files, sends text and links, mirrors notifications, syncs the clipboard, and lets you browse and pull files from your other devices — driven by a CLI, a local browser UI, or your desktop shell.
- **gaur-relay (your VPS)** — a tiny Go daemon bound to loopback on your own server, reachable only through the tunnel. It routes opaque ciphertext between your devices and holds sealed envelopes for offline ones. It has no keys, cannot decrypt anything, and forgets everything on a TTL.

Everything between them rides one Shadowsocks tunnel. Your devices find each other through it, share through it, and stay reachable through it — at home, on hotel Wi-Fi, or on mobile data — and nothing about that sharing ever touches a LAN broadcast domain or a cloud service.

## What it does

### VPN Core
- **Protected Go Engine**: Runs Jigsaw's lwIP TUN stack and Shadowsocks proxy via a custom Go JNI wrapper (`go/outlinev`).
- **Loop-Free Sockets**: Binds every outbound socket to `VpnService.protect()` using `intra/protect.MakeDialer`, preventing routing loops.
- **System DNS**: Resolves DNS queries through protected system resolvers without leaks.
- **Reliable Lifecycle**: Automatic reconnect with exponential backoff, battery-optimization exemption shortcut, and optional start-on-boot.
- **Quick Settings Tile**: Instant two-way toggle from the system notification shade.
- **ss:// Import**: Deep link and clipboard parser supporting SIP002 URIs, percent-encoded userinfo, IPv6 host brackets, legacy base64 strings, and port bounds checking.
- **Kill Switch**: Keeps the TUN interface up while reconnecting, so packets are dropped instead of escaping directly during the backoff window.
- **DNS Through the Tunnel**: Queries go to AdGuard Home on the VPS over the Shadowsocks tunnel, not to a public resolver. The default is `10.0.0.182`; it is configurable and forced into the tunnel with a host route.
- **IPv6-Aware LAN Bypass**: When LAN access is enabled, IPv4 private ranges and IPv6 link-local/ULA/multicast stay local while global IPv6 unicast remains tunneled.

### Staying connected across devices (`gaur-share`)
- **Zero Local Wi-Fi Leakage**: All cluster traffic stays inside the Shadowsocks tunnel. No mDNS, no Bonjour, no ARP broadcasts, and no UDP hole punching on your local network.
- **Double Encryption**: Outer Shadowsocks AEAD (`chacha20-ietf-poly1305` or `aes-256-gcm`) wraps an inner layer of ephemeral ECDH (NIST P-256) key exchange, HKDF-SHA256 session key derivation, and authenticated AES-256-GCM chunked streaming (1 MiB chunks, index-bound nonces, per-chunk AAD).
- **Content-Addressed Resume**: Transfers are keyed by `HMAC(K_peer, sha256(file))`, so the same bytes always resume the same transfer. The receiver keeps a `.part` file and a chunk bitmap in `Downloads`; a dropped connection re-uploads only the missing chunks. A signed completion receipt means "Sent" only after the receiver decrypted and saved the file.
- **Zero-Knowledge Dual-Key Architecture**: Derives separate relay presence keys ($K_{relay}$) and local peer authentication keys ($K_{peer}$), with HMAC-SHA256 authentication tags binding the transfer ID, recipient ID, ephemeral public keys, and the v2 file manifest, preventing relay Man-In-The-Middle attacks and enabling safety number verification.
- **Path Traversal & Collision Protection**: Automatic filename sanitization, staging to `.part` files, and automatic non-destructive collision resolution preserving seamless file auto-acceptance.
- **Zero-Knowledge VPS Relay**: The relay daemon binds only to the loopback alias `198.51.100.1:9876` on your VPS and requires pre-shared cluster token authentication. It stages opaque ciphertext chunks on disk for resume and holds sealed envelopes for offline peers; it possesses no keys and cannot decrypt them. Chunks and mailbox entries are evicted by TTL and byte caps.
- **Android Integration**: Live peer cards on the home screen, native file picker, progress notifications, and automatic saving to the public `Downloads` directory.
- **Text and Link Sharing**: Share a URL or snippet from Android's Sharesheet or `gaur-share send-text`; the receiver gets a notification with Copy/Open instead of a silent clipboard overwrite.
- **Transfer History**: Local-only list of sent/received files and text, with open, retry, resend, and cancel.
- **Trusted Devices**: Pin devices by stable ID on Android or with `gaur-share trust`, auto-accept only from trusted peers, and revoke without rotating the cluster token.
- **Broadcast and Folders**: Send to every online peer at once from the Android share dialog, and send a folder as a flat batch from the desktop client.
- **Sealed Sender (v3)**: New transfers, text, and notifications use a sealed envelope. The relay sees only the target, timestamp, and kind — not the sender, file name, or payload.
- **Offline Delivery**: Queue a file, text, or notification for a device that is offline. The relay holds only ciphertext in a TTL/byte-capped mailbox and delivers it on reconnect.
- **One Upload, Many Recipients**: Send a file to every online device with a single upload; each recipient unwraps the shared content key.
- **Notification Mirroring**: Android notifications appear on every cluster device with the app icon, action buttons, and inline replies. The app ignores its own notifications to prevent loops. Quickshell forwards desktop notifications to every online device with `gaur-share notify --all`; gaurPN-tagged notifications are skipped.
- **Media Control (MPRIS)**: Android media sessions appear as local MPRIS players on the desktop, so Quickshell's media widget can play, pause, skip, seek, and set volume. Position is interpolated locally; commands are sealed and expire after 30 seconds.
- **Request (Pull) Transfers)**: Browse any cluster device's files from your own screen and fetch what you pick — long-press a peer card on Android for the full-screen file browser, or press Request in Quickshell to open the native GTK picker. Targets serve their home directory (desktop) or media collections (phone; grant All files access in the app to expose the phone's full shared storage). Browsing is trusted-only, rate-limited, and every fetch notifies the target.
- **Peer Telemetry**: Battery and charging state travel end-to-end encrypted inside presence updates — the relay stores only opaque ciphertext — so the desktop and the phone's cluster cards show whether a device can survive a transfer before you send one.
- **Safe URL Opening**: Shared links get an "Open in browser" action on the desktop and an Open action on Android, restricted to validated `http`/`https` URLs — a malicious peer can never trigger other protocol handlers.
- **Desktop Companion Client**: Single standalone Go binary for Linux, macOS, and Windows with `daemon`, `list`, `send`, `send-text`, `notify`, `pair`, and `ui` commands.

### In-Tunnel Clipboard Sync
- **Latest Clip Wins**: Copy text or an image on any cluster device and it is pushed to every other online device. Clipboard updates are ordered by the relay's receive timestamp, so the newest copy wins even with clock skew.
- **Text and Images**: Android images are decoded, downscaled to 2048 px, and re-encoded before transfer. Desktop images use the native clipboard (`wl-clipboard`/`xclip` on Linux, `pngpaste` on macOS, PowerShell on Windows).
- **End-to-End Encrypted**: Clipboard payloads use AES-256-GCM with a key derived from the cluster token (`K_clipboard = HMAC-SHA256(K_peer, "gaur-clipboard-v1")`). The relay only sees ciphertext and routing metadata.
- **Echo and Replay Protection**: Content hashes suppress local echo, and relay timestamps reject stale or replayed updates. Sensitive clips (passwords/OTP) are skipped by default on Android; desktop clients cannot detect the platform sensitive flag, so disable desktop clipboard sync when handling secrets.
- **Per-Device Control**: Disable clipboard sync for specific peers, such as a work laptop, without turning it off everywhere.
- **Android Background Read**: Android 10+ only allows the focused app or default IME to read the clipboard. gaurPN uses a 1x1 focusable overlay while the screen is unlocked to read the clipboard, then removes it. Android may show its "pasted from clipboard" privacy toast when a new clip is captured.

### Network Control
- **Per-App Split Tunneling**: Exclude chosen apps (banking apps, local streaming apps, games) from the VPN tunnel. Includes a searchable installed app list.
- **Local Network (LAN) Bypass**: Partitioned IPv4 routing table excludes RFC 1918 subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), link-local (`169.254.0.0/16`), and loopback (`127.0.0.0/8`). Access local printers, NAS, and Smart TVs without disconnecting.

---

## Architecture

```
┌─────────────────────────────────────── Android Client (gaurPN) ───────────────────────────────────────┐
│                                                                                                       │
│  UI Layer (Jetpack Compose M3)                                                                        │
│    ├── HomeScreen: Animated status ring, live uptime, server selector, cluster peer cards             │
│    ├── SettingsScreen: Auto-reconnect, start on boot, LAN bypass toggle, battery whitelist            │
│    └── SplitTunnelScreen: Per-app package selection (stored via DataStore)                           │
│                                                                                                       │
│  Tunnel Engine (VpnService)                                                                           │
│    ├── LanBypassRoutes: Dynamic CIDR route assignment (0.0.0.0/0 vs RFC 1918 excluded subnets)       │
│    ├── outlinev.connectTunnel(fd, ssConfig, Protector, udp)                                           │
│    └── libgojni.so (Go lwIP TUN + Shadowsocks)                                                        │
│           └── Every outbound socket hooked via net.Dialer.Control -> VpnService.protect(fd)           │
│                                                                                                       │
│  In-Tunnel Sharing (RelayManager & Crypto)                                                            │
│    ├── Ephemeral ECDH (NIST P-256) + HKDF-SHA256 session key derivation                               │
│    └── Authenticated AES-256-GCM streaming pipe -> Saves to Android Downloads/                        │
└───────────────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                                    │
                                     Encrypted Shadowsocks Tunnel
                                                    │
                                                    ▼
┌──────────────────────────────────────── VPS (Outline Server) ─────────────────────────────────────────┐
│                                                                                                       │
│  Shadowsocks Server Daemon (e.g. outline-ss-server)                                                   │
│                                                                                                       │
│  AdGuard Home (DNS on 10.0.0.182:53, reachable only through the tunnel)                               │
│                                                                                                       │
│  gaur-relay (Go daemon listening on the 198.51.100.1:9876 loopback alias)                             │
│    ├── /ws: Authenticated WebSocket presence and signaling (pre-shared cluster token)                 │
│    ├── /chunk: Ciphertext chunk staging for resumable transfers (no keys, no plaintext)               │
│    ├── mailbox: Sealed envelopes for offline peers (TTL and byte capped)                              │
│    ├── /pipe: Legacy in-memory stream forwarder for v1 clients                                        │
│    └── /health: Loopback health status                                                                │
└───────────────────────────────────────────────────▲───────────────────────────────────────────────────┘
                                                    │
                                     Encrypted Shadowsocks Tunnel
                                                    │
┌───────────────────────────────────────────────────┴───────────────────────────────────────────────────┐
│  Desktop Client (gaur-share on Linux / macOS / Windows)                                               │
│    ├── gaur-share daemon: Runs background receiver, notifies via desktop alerts                      │
│    ├── gaur-share list: Discovers active tunnel cluster peers                                         │
│    └── gaur-share send <target> <file>...: Ephemeral key exchange + AES-256-GCM chunked stream        │
└───────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Why the custom Go wrapper?

The stock `outline.aar` dials the proxy using unprotected sockets. If an Android VPN routes all device traffic into its TUN interface, the VPN's own outbound packets loop back into the tunnel.

`go/outlinev` wraps the Outline SDK's `TCPEndpoint` and `UDPEndpoint` with `intra/protect.MakeDialer(protector)`. This dialer sets a `net.Dialer.Control` hook that calls into Kotlin and executes `VpnService.protect(fd)` before every socket connects. Traffic to the proxy server bypasses the TUN interface to reach the physical network interface. DNS resolves through the phone's protected system resolvers.

---

## Repository Structure

```
outline/
├── app/                  # Android application (Kotlin, Jetpack Compose, DataStore)
│   ├── src/main/kotlin/  # UI, tunnel service, key parsing, and in-tunnel transfer logic
│   └── src/test/         # Unit tests (URI parser, cryptography tests)
├── go/outlinev/          # Go JNI wrapper around outline-go-tun2socks with socket protection
├── server/relay/         # In-tunnel zero-copy relay daemon (gaur-relay)
├── desktop-client/       # Cross-platform CLI companion for Linux, macOS, and Windows (gaur-share)
├── docs/                 # Detailed architecture guides (docs/FILE_SHARING.md)
└── third_party/          # Upstream outline-go-tun2socks (cloned during build)
```

---

## Building

### Prerequisites

- Java Development Kit: JDK 21
- Android SDK: Platform API 35, Build-Tools 35.0.0
- Android NDK: r27 or newer
- Go: 1.22 or newer

`gradle.properties` sets `org.gradle.java.home`. Point it to your JDK 21 path or remove the line to use your system default.

### 1. Build the tun2socks Android Archive (`app/libs/tun2socks.aar`)

Fetch upstream `outline-go-tun2socks` and compile the Go JNI wrapper:

```bash
# Clone upstream Go engine
git clone --depth 1 https://github.com/Jigsaw-Code/outline-go-tun2socks.git \
  third_party/outline-go-tun2socks

# Install gomobile and gobind
cd third_party/outline-go-tun2socks
GOBIN=$PWD/bin go install golang.org/x/mobile/cmd/gomobile@latest
GOBIN=$PWD/bin go install golang.org/x/mobile/cmd/gobind@latest
./bin/gomobile init
cd ../../go/outlinev

# Generate tun2socks.aar
ANDROID_HOME=/path/to/android-sdk ANDROID_NDK_HOME=/path/to/android-ndk \
../../third_party/outline-go-tun2socks/bin/gomobile bind \
  -target=android -androidapi 24 -ldflags '-w' \
  -o ../../app/libs/tun2socks.aar \
  -javapkg=dev.gaurish.outlinelite . \
  ../../third_party/outline-go-tun2socks/outline/tun2socks \
  ../../third_party/outline-go-tun2socks/outline/shadowsocks
```

### 2. Build the Android App

Return to the repository root and run Gradle:

```bash
# Compile debug APK (outputs to app/build/outputs/apk/debug/app-debug.apk)
./gradlew :app:assembleDebug

# Run unit tests
./gradlew :app:testDebugUnitTest
```

### 3. Build the In-Tunnel Server Relay (`gaur-relay`)

On your server or local machine:

```bash
cd server/relay
go build -ldflags "-s -w" -o gaur-relay .
```

### 4. Build the Desktop Companion Client (`gaur-share`)

```bash
cd desktop-client
go build -ldflags "-s -w" -o gaur-share .
```

To cross-compile for other platforms:

```bash
# Linux x86_64
GOOS=linux GOARCH=amd64 go build -ldflags "-s -w" -o dist/gaur-share-linux-amd64 .

# macOS Apple Silicon
GOOS=darwin GOARCH=arm64 go build -ldflags "-s -w" -o dist/gaur-share-darwin-arm64 .

# macOS Intel
GOOS=darwin GOARCH=amd64 go build -ldflags "-s -w" -o dist/gaur-share-darwin-amd64 .

# Windows x86_64
GOOS=windows GOARCH=amd64 go build -ldflags "-s -w" -o dist/gaur-share-windows-amd64.exe .
```

---

## Setup and Usage

### 1. Connecting to Shadowsocks

1. Copy an access key (`ss://...`) from your Outline Manager or Shadowsocks server.
2. Open gaurPN and tap **+** to paste the key, or open the link on your device to import it.
3. Tap **Connect** and confirm the system VPN permission dialog once.
4. Add the gaurPN tile to your Quick Settings panel for one-tap toggling.

### 2. Split Tunneling and LAN Bypass

- **Per-App Split Tunneling**: Open **Settings** (gear icon) -> **Split Tunneling**. Check the apps you want to bypass the VPN. Selected apps will route through your direct Wi-Fi or mobile network.
- **LAN Bypass**: Open **Settings** and toggle **Allow LAN Access**. Local devices (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`) remain reachable while internet traffic routes through the tunnel.

### 3. Setting Up In-Tunnel File Sharing

#### On the Server (`gaur-relay`)
Create a pre-shared cluster token:
```bash
openssl rand -hex 32 > /etc/gaur-relay/cluster.key
chmod 600 /etc/gaur-relay/cluster.key
```

Or generate the token and a pairing QR in one step:

```bash
sudo /opt/gaur-relay/gaur-relay -init \
  -token-file /etc/gaur-relay/cluster.key \
  -pair-relay 198.51.100.1:9876 \
  -pair-dns 10.0.0.182
```

Scan the QR with the phone camera. The `gaur://pair` link opens gaurPN and
stores the relay address, cluster token, and DNS server without typing the
secret. On the desktop, run `gaur-share pair '<uri>'` with the printed URI.

If the relay is already running, restart it after `-init` so it loads the new
token: `sudo systemctl restart gaur-relay`.

To pair another device to the existing cluster without rotating the token:

```bash
sudo /opt/gaur-relay/gaur-relay -qr \
  -token-file /etc/gaur-relay/cluster.key \
  -pair-relay 198.51.100.1:9876 \
  -pair-dns 10.0.0.182
```

Run `gaur-relay` as a systemd service (`/etc/systemd/system/gaur-relay.service`):
```ini
[Unit]
Description=gaurPN In-Tunnel File Sharing Relay
After=network.target

[Service]
Type=simple
# The relay only needs privileges to create the loopback alias. On a hardened
# host, create 198.51.100.1/32 with a separate oneshot unit and run this
# service as a dedicated user instead.
User=root
ExecStartPre=-/usr/sbin/ip addr replace 198.51.100.1/32 dev lo
ExecStart=/opt/gaur-relay/gaur-relay -bind 198.51.100.1:9876 -token-file /etc/gaur-relay/cluster.key -chunk-dir /var/lib/gaur-relay/chunks -chunk-max-bytes 8589934592 -mailbox-ttl 1h -mailbox-max-bytes 16777216 -mailbox-max-target-bytes 4194304
StateDirectory=gaur-relay
Restart=always
RestartSec=3
LimitNOFILE=65535
MemoryMax=64M

[Install]
WantedBy=multi-user.target
```

Enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now gaur-relay
```

#### On the Desktop (`gaur-share`)
Create `~/.config/gaur-share/config.json`:
```json
{
  "device_name": "My-Laptop",
  "relay": "198.51.100.1:9876"
}
```
Place your cluster token in `~/.config/gaur-share/cluster.key` or export `GAURPN_CLUSTER_TOKEN="your-secret-token"`.

Start the background receiver daemon:
```bash
gaur-share daemon
```

To list online devices and send a file:
```bash
# List active devices in the tunnel
gaur-share list

# Send a file to an Android device
gaur-share send "Pixel 8" ~/Documents/notes.pdf ~/Documents/report.pdf

# Send a link or a short text (use - to read stdin)
gaur-share send-text "Pixel 8" "https://example.com/recipe"
echo "otp 482913" | gaur-share send-text "Pixel 8" -

# Send a folder as a flat batch
gaur-share send "Pixel 8" ~/Pictures/holiday

# Pair from the relay's QR URI
gaur-share pair "gaur://pair?relay=198.51.100.1%3A9876&token=..."

# Local browser UI (peers, text, drag-and-drop files)
gaur-share ui

# Trust a device and require trust for incoming offers
gaur-share trust "Lavander"
gaur-share trust-mode on
gaur-share trusted

# Revoke a device
gaur-share untrust "Lavander"

# One upload, every online device
gaur-share send --all ~/Pictures/holiday

# Queue for a device that is offline (use its stable ID)
gaur-share send --offline android-897a85f2 ~/Documents/contract.pdf

# Send to specific device IDs
gaur-share send --to android-897a85f2,android-f3dd4a01 ~/Documents/report.pdf

# Send a notification to a device
gaur-share notify "Lavander" "Build finished" "The release APK is ready"

# Send a notification to every online device
gaur-share notify --all "Build finished" "The release APK is ready"

# Browse a device's files (trusted devices only) and fetch what you pick
gaur-share browse "Pixel 8"            # list the phone's media collections
gaur-share fetch "Pixel 8" "Pictures/Screenshots/Screenshot_01.png"

# Or use the native GTK window (Quickshell's Request button opens it)
gaur-browse "Pixel 8"

# Ask a phone for its latest screenshot (quick action)
gaur-share screenshot "Pixel 8"
```

#### On Android (`gaurPN`)
1. In gaurPN, edit your active server profile to set your **Cluster Token** and relay address (`198.51.100.1:9876`).
2. When connected to the VPN, the **Cluster Devices** card displays online peers.
3. Tap any peer to pick a file and send it. Incoming files stream straight into your device's `Downloads` folder.

### 4. Clipboard Sync

Clipboard sync rides the same authenticated WebSocket as file sharing. It is enabled per-device.

**Android**
1. Open **Settings → Clipboard Sync** and enable **Sync clipboard**.
2. Grant **Display over other apps** when prompted. Android 10+ does not allow a background app to read the clipboard; gaurPN briefly adds a 1x1 focusable overlay to read it, then removes it.
3. Copy text or an image on any device. The newest clip is sent to every other online cluster device.
4. Leave **Include sensitive clips** off unless you explicitly want passwords and OTP codes synced.

**Desktop**
The daemon has three clipboard sources:

- `-clipboard-source=socket` (recommended with Quickshell): Quickshell watches cliphist's BoltDB with inotify and sends new clips over a Unix socket. The daemon never polls the clipboard. This is what the included `~/.config/quickshell/services/ClipboardSync.qml` uses.
- `-clipboard-source=poll` (default for standalone use): the daemon polls the clipboard once per second. Use this only when no event source is available.
- `-clipboard-source=off` (or `-clipboard=false`): disable clipboard sync. Flags go before the subcommand: `gaur-share -clipboard-source=off daemon`.

The socket defaults to `$XDG_CACHE_HOME/gaur-share/clipboard.sock` (usually `~/.cache/gaur-share/clipboard.sock`).

- Linux: install `wl-clipboard` (Wayland) or `xclip`/`xsel` (X11). Image sync requires one of those tools.
- macOS: text works out of the box; install `pngpaste` for image sync.
- Windows: uses built-in PowerShell clipboard cmdlets.

Android will show its standard "pasted from clipboard" privacy toast when a new clip is captured. That indicator is enforced by the platform and cannot be suppressed without being the default IME or using root/Shizuku.

For full protocol specifications, see [docs/FILE_SHARING.md](docs/FILE_SHARING.md).

---

## Security Model

- **Zero Telemetry**: No trackers, no analytics SDKs, no third-party network calls.
- **Local Storage**: Access keys and cluster tokens stay on your device, wrapped with Android Keystore (AES-256-GCM) inside Jetpack DataStore; nothing is cloud-backed up.
- **Zero-Knowledge Relay**: The relay server runs on loopback inside the VPN server and possesses no keys to decrypt inner payloads. It stages opaque ciphertext chunks on disk for resume and evicts them by TTL and a byte cap. It refuses to start on a non-loopback bind, caps peers and input sizes, and throttles repeated auth failures.
- **Transfer Ownership**: Chunk endpoints carry a per-transfer capability tag (`HMAC(K_peer, "gaur-cap-v1:…")`) and the creator's device id, so cluster members cannot silently read, overwrite, or drop each other's staged transfers. A malicious relay cannot redirect sealed envelopes — receivers verify the target and dedup by message id, and media commands expire after 30 seconds.
- **Pairing Confirmation**: `gaur://pair` links show a confirmation dialog (relay, masked token, DNS) before anything is written; the app refuses relays on public hosts.
- **Isolated Transport**: File transfers do not touch local LAN broadcast or multicast domains, protecting peer visibility on public Wi-Fi networks.
- **DNS Privacy**: DNS queries are answered by AdGuard Home on the VPS through the tunnel. No public resolver sees them unless you explicitly choose System mode.
- **Kill Switch**: While reconnecting, the TUN stays up and drops traffic instead of falling back to the physical network.
- **Trusted Devices**: Auto-accept can be restricted to pinned device IDs on Android (Settings → Trusted devices) and on the desktop (`gaur-share trust` / `trust-mode`). Revoking removes the peer from this device's trust list; rotate the cluster token to remove its relay access entirely. The trust gate covers every inbound kind — files, text, clipboard, notifications, and notification actions.
- **Sealed Sender**: v3 messages omit the sender from the relay-visible envelope. The relay routes by target, timestamp, and kind only, and strips sender fields even if a client includes them.
- **Ephemeral Mailbox**: Offline messages are ciphertext with a TTL (default 1h) and byte caps (global and per-target); the relay never parses them, and delivery is ack-based so a failed write re-queues instead of dropping.
- **Notification Privacy**: Mirrored notifications are sealed; the relay sees only that a notification event exists. The source app ignores gaurPN's own notifications to prevent loops, and shared content is hidden from the lock screen.
- **Resumable Transfers**: v2 and v3 transfers resume from a `.part` file and chunk bitmap after a dropped connection or app restart — senders skip chunks the relay still holds, receivers keep progress across attempts.
- **Request Security**: Pull transfers are sealed and grant-verified, expire in five minutes, and are rate-limited on the target. Browsing is a trusted-only capability — trust a device once, then it can browse; every accepted fetch posts a notification on the target. Under the shared-token model identity claims are advisory (the seal proves membership, the trust list decides which claims to believe); per-device identity keys (v4) are the planned hard boundary.

---

## License

This project is licensed under the [MIT License](LICENSE).

Upstream `outline-go-tun2socks` is licensed under the Apache License 2.0.
