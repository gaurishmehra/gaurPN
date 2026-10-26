# gaurPN

A custom Android client for Outline (Shadowsocks) with in-tunnel end-to-end encrypted file sharing, per-app split tunneling, and LAN bypass. Built with Material 3 dark UI, a Quick Settings tile, and a protected Go tun2socks engine.

## Why this exists

I like the official Outline app. I do not like using it. Every session, I tap through screens, read labels I already know, and stare at an interface that looks the same on every phone. So I kept the part Jigsaw got right (the Go tunnel and its protected sockets) and rewrote everything you touch.

gaurPN consolidates control into one dark screen, one status ring, and one button. It toggles from Quick Settings, reconnects when the network drops, and stays out of your way. On top of the core VPN, it integrates tools missing from the stock client: direct peer-to-peer file transfer running inside the tunnel without local Wi-Fi leakage, per-app split tunneling, and LAN bypass for local network devices.

## What it does

### VPN Core
- **Protected Go Engine**: Runs Jigsaw's lwIP TUN stack and Shadowsocks proxy via a custom Go JNI wrapper (`go/outlinev`).
- **Loop-Free Sockets**: Binds every outbound socket to `VpnService.protect()` using `intra/protect.MakeDialer`, preventing routing loops.
- **System DNS**: Resolves DNS queries through protected system resolvers without leaks.
- **Reliable Lifecycle**: Automatic reconnect with exponential backoff, battery-optimization exemption shortcut, and optional start-on-boot.
- **Quick Settings Tile**: Instant two-way toggle from the system notification shade.
- **ss:// Import**: Deep link and clipboard parser supporting SIP002 URIs, percent-encoded userinfo, IPv6 host brackets, legacy base64 strings, and port bounds checking.

### In-Tunnel File Sharing (`gaur-share`)
- **Zero Local Wi-Fi Leakage**: All traffic stays inside the Shadowsocks tunnel. No mDNS, no Bonjour, no ARP broadcasts, and no UDP hole punching on your local network.
- **Double Encryption**: Outer Shadowsocks AEAD (`chacha20-ietf-poly1305` or `aes-256-gcm`) wraps an inner layer of ephemeral ECDH (NIST P-256) key exchange, HKDF-SHA256 session key derivation, and authenticated AES-256-GCM chunked streaming (64 KB chunks with sequential nonces).
- **Zero-Knowledge Dual-Key Architecture**: Derives separate relay presence keys ($K_{relay}$) and local peer authentication keys ($K_{peer}$), with HMAC-SHA256 authentication tags preventing relay Man-In-The-Middle attacks and safety number verification.
- **Path Traversal & Collision Protection**: Automatic filename sanitization, staging to `.part` files, and automatic non-destructive collision resolution preserving seamless file auto-acceptance.
- **Zero-Knowledge VPS Relay**: The relay daemon binds only to `127.0.0.1:9876` on your VPS. It pipes streams in memory via `io.Pipe`, never writes payloads to disk, and requires pre-shared cluster token authentication.
- **Android Integration**: Live peer cards on the home screen, native file picker, progress notifications, and automatic saving to the public `Downloads` directory.
- **Desktop Companion Client**: Single standalone Go binary for Linux, macOS, and Windows with `daemon`, `list`, and `send` commands.

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
│  gaur-relay (Go daemon listening on 127.0.0.1:9876 loopback only)                                     │
│    ├── /ws: Authenticated WebSocket presence and signaling (pre-shared cluster token)                │
│    ├── /pipe: Zero-copy in-memory io.Pipe stream forwarder (no disk writes, zero payload inspection) │
│    └── /health: Loopback health status                                                                │
└───────────────────────────────────────────────────▲───────────────────────────────────────────────────┘
                                                    │
                                     Encrypted Shadowsocks Tunnel
                                                    │
┌───────────────────────────────────────────────────┴───────────────────────────────────────────────────┐
│  Desktop Client (gaur-share on Linux / macOS / Windows)                                               │
│    ├── gaur-share daemon: Runs background receiver, notifies via desktop alerts                      │
│    ├── gaur-share list: Discovers active tunnel cluster peers                                         │
│    └── gaur-share send <target> <file>: Ephemeral key exchange + AES-256-GCM chunked stream           │
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

Run `gaur-relay` as a systemd service (`/etc/systemd/system/gaur-relay.service`):
```ini
[Unit]
Description=gaurPN In-Tunnel File Sharing Relay
After=network.target

[Service]
Type=simple
User=ubuntu
ExecStart=/opt/gaur-relay/gaur-relay -bind 127.0.0.1:9876 -token-file /etc/gaur-relay/cluster.key
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
  "relay": "127.0.0.1:9876"
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
gaur-share send "Pixel 8" ~/Documents/notes.pdf
```

#### On Android (`gaurPN`)
1. In gaurPN, edit your active server profile to set your **Cluster Token** and relay address (`127.0.0.1:9876`).
2. When connected to the VPN, the **Cluster Devices** card displays online peers.
3. Tap any peer to pick a file and send it. Incoming files stream straight into your device's `Downloads` folder.

For full protocol specifications, see [docs/FILE_SHARING.md](docs/FILE_SHARING.md).

---

## Security Model

- **Zero Telemetry**: No trackers, no analytics SDKs, no third-party network calls.
- **Local Storage**: Access keys and cluster tokens stay on your device in Jetpack DataStore.
- **Zero-Knowledge Relay**: The relay server runs on loopback inside the VPN server, processes streams in memory, and possesses no keys to decrypt inner payloads.
- **Isolated Transport**: File transfers do not touch local LAN broadcast or multicast domains, protecting peer visibility on public Wi-Fi networks.

---

## License

This project is licensed under the [MIT License](LICENSE).

Upstream `outline-go-tun2socks` is licensed under the Apache License 2.0.
