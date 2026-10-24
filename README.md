# gaurPN

A custom Android client for **Outline (Shadowsocks)** — Material 3 dark UI,
Quick Settings tile, background keep-alive, protected tun2socks engine.

## Why this exists

The official Outline app works well but its UI is, honestly, bad. gaurPN keeps
everything that makes Outline solid — the Go tunnel, protected sockets, AEAD
ciphers — and replaces **all** of the app chrome with a UI that respects its
users: calm dark neutrals, one accent, Material You on Android 12+, no
congratulatory onboarding, no pointless chrome.

Same engine. Better UI. That's the whole pitch.

## What it does

- **Connect/disconnect** with adaptive phone + tablet layouts (window size class)
- **Animated status ring** with live uptime
- **Quick Settings tile**: 1-tap toggle from anywhere
- **Background keep-alive**: auto-reconnect with backoff (5 attempts),
  battery-optimization exemption prompt, start-on-boot toggle
- **ss:// import**: open access keys straight from Outline Manager, or paste
  them; parser with unit tests (IPV6, percent-encoded + legacy base64 userinfo,
  plugin rejection, port validation)

## Architecture

```
┌─────────────────────── Android app (Kotlin + Compose M3) ───────────────────────┐
│  MainActivity ─ ui/ (adaptive phone/tablet, animated status ring)              │
│  TunnelService (VpnService) ── tunnel/                                          │
│     └── outlinev.connectTunnel(fd, ssConfig, Protector, udp)                    │
│            └── libgojni.so (Go: lwIP TUN stack + Shadowsocks)                    │
│                  └── every outbound socket ← Protector.protect(fd)              │
│                     = VpnService.protect() → no routing loop                    │
│  QsTileService ─ tile/          WidgetReceiver (Glance) ─ widget/               │
│  BootReceiver ─ boot/           KeyRepository (DataStore) ─ data/               │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Why the custom Go wrapper?

The stock `outline.aar` dials the proxy with unprotected sockets, so the VPN's
own traffic would loop back through itself. `go/outlinev` wraps the SDK's
`TCPEndpoint`/`UDPEndpoint` with `intra/protect.MakeDialer(protector)`, whose
`net.Dialer.Control` hook calls back into Kotlin → `VpnService.protect()`
before every outbound connect, and routes DNS through the phone's protected
system resolvers. Same trick [Intra](https://github.com/Jigsaw-Code/Intra)
uses; applied to plain Shadowsocks.

## Building

Requires JDK 21, Android SDK (android-35, build-tools 35), NDK r27+, Go 1.22+.

> `third_party/` and the built AAR are not committed — fetch and build them
> first. `gradle.properties` pins `org.gradle.java.home`; adjust it to your
> JDK path (or delete the line to use your default JDK).

1. **Fetch the engine** (do this from the repo root):

   ```bash
   git clone --depth 1 https://github.com/Jigsaw-Code/outline-go-tun2socks.git \
     third_party/outline-go-tun2socks
   cd third_party/outline-go-tun2socks
   GOBIN=$PWD/bin go install golang.org/x/mobile/cmd/gomobile@latest
   GOBIN=$PWD/bin go install golang.org/x/mobile/cmd/gobind@latest
   ./bin/gomobile init
   cd ../../go/outlinev
   ```

2. **Build the AAR** (`app/libs/tun2socks.aar`):

   ```bash
   ANDROID_HOME=<sdk> ANDROID_NDK_HOME=<ndk> \
   ../../third_party/outline-go-tun2socks/bin/gomobile bind \
     -target=android -androidapi 24 -ldflags '-w' \
     -o ../../app/libs/tun2socks.aar \
     -javapkg=dev.gaurish.outlinelite . \
     ../../third_party/outline-go-tun2socks/outline/tun2socks \
     ../../third_party/outline-go-tun2socks/outline/shadowsocks
   ```

3. **App + tests**:

   ```bash
   ./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
   ./gradlew :app:testDebugUnitTest        # ss:// parser tests
   ```

## Using it

- Get an access key (`ss://…`) from your Outline Manager and open the link on
  your phone — the app imports it. Or tap **+** and paste the key.
- Pick a server, hit **Connect**, allow the VPN permission prompt once.
- Pin the **gaurPN** tile in Quick Settings for a 1-tap toggle anywhere.
- Settings (gear icon): keep-alive auto-reconnect, start-on-boot, and the
  battery-optimization exemption so the VPN never turns off in the background.

## Notes

- Requires an Outline/Shadowsocks server you control (any Outline Manager or
  compatible `ss://` endpoint). gaurPN does not come with servers and does
  not talk to any third party.
- No analytics, no accounts, no logging. Keys stay on device.
- Not affiliated with Jigsaw/Google's Outline; the engine is theirs and this
  repo is a UI layer over it.
