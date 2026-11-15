# gaurPN

Your phone, your laptop, your desktop. One private network.

gaurPN turns a Shadowsocks VPS into a home for every device you carry. The Android app is the VPN. The desktop binary is the companion. The relay on your server routes ciphertext between them. Connect once and your devices find each other through the tunnel: send files, sync the clipboard, mirror notifications, control media, pull a screenshot off your phone while you sit at your desk. Every payload is end-to-end encrypted before it leaves the device, and the relay in the middle holds no keys.

## The pieces

| | Runs on | Job |
|---|---|---|
| **gaurPN** | Android | Shadowsocks VPN with a Quick Settings tile, per-app split tunneling, and LAN bypass, plus the cluster UI: peer cards, transfers, clipboard, notifications |
| **gaur-share** | Linux, macOS, Windows | One Go binary that sends and receives files, text, notifications, clipboard, and media commands. CLI, local web UI, or your desktop shell |
| **gaur-relay** | Your VPS | Loopback-only daemon that routes ciphertext between your devices and queues sealed envelopes for offline ones. TTL and byte caps evict everything |

## Why it exists

Your devices should talk to each other through a server you pay for. gaurPN keeps that promise: the relay sees transfer IDs and ciphertext, files stream from sender to receiver with no plaintext at the hop, and nothing backs up to anyone's cloud. DNS goes through AdGuard Home on your VPS, so no public resolver logs your queries. There are no accounts because there is nothing to sign into. The cluster token lives on your server and your devices, and that is the whole user database.

## Highlights

- **One tunnel for everything.** The VPN, file transfers, clipboard, and notifications ride the same Shadowsocks connection. No mDNS, no LAN broadcasts, no extra ports.
- **End-to-end encrypted.** Ephemeral ECDH (P-256), HKDF-SHA256, AES-256-GCM with per-chunk AAD. The relay stores bytes it cannot read.
- **Sealed sender.** The relay routes by target and kind. It never learns who sent a file, what the file is named, or what is inside.
- **Resumable.** Transfers are keyed by `HMAC(K_peer, sha256(file))`. Drop your connection mid-transfer and the retry uploads only the missing chunks. "Sent" appears after the receiver decrypted and saved the file, never before.
- **Works while you're away.** Send to a device that is offline; the relay holds a sealed envelope in a TTL-capped mailbox and delivers on reconnect.
- **Browse and pull.** Open any trusted device's files from your screen and fetch what you want. Every fetch notifies the target.
- **Clipboard that follows you.** Copy on the phone, paste on the laptop. Text and images, newest clip wins, passwords and OTP codes skipped by default.
- **Zero telemetry.** No trackers, no analytics, no third-party calls. Keys stay in Android Keystore and never leave your devices.

---

## Feature reference

### VPN core

- **Protected Go engine**: Jigsaw's lwIP TUN stack and Shadowsocks proxy through a custom JNI wrapper (`go/outlinev`). Every outbound socket binds via `VpnService.protect()`, so the VPN's own packets never loop back into the tunnel.
- **Kill switch**: the TUN interface stays up during reconnects and drops traffic instead of leaking around the tunnel.
- **DNS through the tunnel**: AdGuard Home on your VPS answers every query; a host route forces it in. Default `10.0.0.182`, configurable.
- **Reliable lifecycle**: reconnect with exponential backoff, battery-optimization exemption shortcut, optional start on boot.
- **`ss://` import**: SIP002 URIs, percent-encoded userinfo, IPv6 host brackets, legacy base64 strings, port bounds checking, from clipboard or deep link.
- **Per-app split tunneling**: exclude banking apps, local streaming, games. Searchable installed-app list, stored in DataStore.
- **IPv6-aware LAN bypass**: RFC 1918, link-local, and ULA ranges stay local while global IPv6 unicast stays tunneled. Reach printers, NAS, and TVs without disconnecting.

### Sharing and sync

- **Files**: chunked AES-256-GCM streaming (1 MiB chunks, index-bound nonces, per-chunk AAD), resumable through a `.part` file and chunk bitmap, signed completion receipts, sanitized filenames, non-destructive collision handling. Incoming files land in `Downloads`.
- **Text and links**: from Android's Sharesheet or `gaur-share send-text`. Receivers get Copy/Open actions, and Open validates `http`/`https` so a hostile peer can never fire other protocol handlers.
- **Dual-key zero-knowledge design**: separate relay presence keys ($K_{relay}$) and peer authentication keys ($K_{peer}$), HMAC-SHA256 tags binding the transfer ID, recipient, ephemeral keys, and the v2 manifest. This blocks relay man-in-the-middle attacks and enables safety-number verification.
- **Trusted devices**: pin peers by stable ID on Android or with `gaur-share trust`. Auto-accept only from trusted peers, revoke without rotating the cluster token. The trust gate covers files, text, clipboard, notifications, and notification actions.
- **Broadcast**: one upload reaches every online device; each recipient unwraps the shared content key. Folders send as flat batches.
- **Offline delivery**: sealed envelopes in a TTL/byte-capped mailbox, ack-based, so a failed write re-queues instead of dropping.
- **Notification mirroring**: Android notifications appear on every cluster device with the app icon, action buttons, and inline replies. Desktop notifications mirror back through `gaur-share notify --all`. The app ignores its own notifications to prevent loops, and shared content hides on the lock screen.
- **Media control (MPRIS)**: Android media sessions appear as local players on the desktop. Play, pause, skip, seek, volume. Position interpolates locally; commands are sealed and expire after 30 seconds.
- **Pull transfers**: browse a trusted device's home directory (desktop) or media collections (phone; grant All files access for full shared storage) and fetch what you pick. Browsing is trusted-only and rate-limited, grants expire in five minutes, and every accepted fetch posts a notification on the target.
- **Peer status**: battery and charging state ride inside encrypted presence updates, so you know a device can survive a transfer before you send one.
- **Transfer history**: local-only list of sent and received files and text, with open, retry, resend, and cancel.

### Clipboard sync

- **Newest clip wins** across devices, ordered by the relay's receive timestamp so clock skew cannot resurrect old clips.
- **Text and images**: Android downscales images to 2048 px before transfer; desktops use `wl-clipboard`/`xclip` (Linux), `pngpaste` (macOS), or PowerShell (Windows).
- **Encrypted**: AES-256-GCM with `K_clipboard = HMAC-SHA256(K_peer, "gaur-clipboard-v1")`. Content hashes suppress echo; relay timestamps reject replays.
- **Discretion**: sensitive clips (passwords, OTP) are skipped by default on Android, and you can disable sync per peer, such as a work laptop.

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
┌──────────────────────────────────── VPS (Shadowsocks Server) ─────────────────────────────────────────┐
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

The stock Android client dials the proxy with unprotected sockets. When a VPN routes all device traffic into its TUN interface, the VPN's own outbound packets loop back into the tunnel and die there.

`go/outlinev` wraps the upstream `TCPEndpoint` and `UDPEndpoint` with `intra/protect.MakeDialer(protector)`. The dialer sets a `net.Dialer.Control` hook that calls into Kotlin and runs `VpnService.protect(fd)` before every socket connects. Traffic to the proxy server bypasses the TUN interface and reaches the physical network interface. DNS resolves through the phone's protected system resolvers.

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

1. Copy an access key (`ss://...`) from your Shadowsocks server's manager.
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
- **Transfer Ownership**: Chunk endpoints carry a per-transfer capability tag (`HMAC(K_peer, "gaur-cap-v1:…")`) and the creator's device id, so cluster members cannot silently read, overwrite, or drop each other's staged transfers. A malicious relay cannot redirect sealed envelopes: receivers verify the target and dedup by message id, and media commands expire after 30 seconds.
- **Pairing Confirmation**: `gaur://pair` links show a confirmation dialog (relay, masked token, DNS) before anything is written; the app refuses relays on public hosts.
- **Isolated Transport**: File transfers do not touch local LAN broadcast or multicast domains, protecting peer visibility on public Wi-Fi networks.
- **DNS Privacy**: DNS queries are answered by AdGuard Home on the VPS through the tunnel. No public resolver sees them unless you explicitly choose System mode.
- **Kill Switch**: While reconnecting, the TUN stays up and drops traffic instead of falling back to the physical network.
- **Trusted Devices**: Auto-accept can be restricted to pinned device IDs on Android (Settings → Trusted devices) and on the desktop (`gaur-share trust` / `trust-mode`). Revoking removes the peer from this device's trust list; rotate the cluster token to remove its relay access entirely. The trust gate covers every inbound kind: files, text, clipboard, notifications, and notification actions.
- **Sealed Sender**: v3 messages omit the sender from the relay-visible envelope. The relay routes by target, timestamp, and kind only, and strips sender fields even if a client includes them.
- **Ephemeral Mailbox**: Offline messages are ciphertext with a TTL (default 1h) and byte caps (global and per-target); the relay never parses them, and delivery is ack-based so a failed write re-queues instead of dropping.
- **Notification Privacy**: Mirrored notifications are sealed; the relay sees only that a notification event exists. The source app ignores gaurPN's own notifications to prevent loops, and shared content is hidden from the lock screen.
- **Resumable Transfers**: v2 and v3 transfers resume from a `.part` file and chunk bitmap after a dropped connection or app restart. Senders skip chunks the relay still holds, receivers keep progress across attempts.
- **Request Security**: Pull transfers are sealed and grant-verified, expire in five minutes, and are rate-limited on the target. Browsing is a trusted-only capability: trust a device once, then it can browse; every accepted fetch posts a notification on the target. Under the shared-token model identity claims are advisory (the seal proves membership, the trust list decides which claims to believe); per-device identity keys (v4) are the planned hard boundary.

---

## License

This project is licensed under the [MIT License](LICENSE).

Upstream `outline-go-tun2socks` is licensed under the Apache License 2.0.
