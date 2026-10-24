# gaurPN

A custom Android client for Outline (Shadowsocks). Material 3 dark UI, a Quick
Settings tile, background keep-alive, and a protected tun2socks engine.

## Why this exists

I like the official Outline app. I don't like using it. Every session, I tap
through screens, read labels I already know, and stare at an interface that
looks the same on every phone. So I kept the part Jigsaw got right (the Go
tunnel and its protected sockets) and rewrote everything you actually touch.

The result: one dark screen, one status ring, one button. It boots into the
tile, it reconnects itself, and it gets out of the way.

## What it does

- Connect and disconnect on adaptive phone and tablet layouts
- Animated status ring with live uptime
- Quick Settings tile that toggles instantly, both ways
- Auto-reconnect with backoff, battery-optimization exemption, start on boot
- ss:// import from Outline Manager or a paste box, with tested parsing
  (IPv6 hosts, percent-encoded userinfo, legacy base64, plugin rejection,
  port validation)

## Architecture

```
┌─────────────────────── Android app (Kotlin + Compose M3) ───────────────────────┐
│  MainActivity ─ ui/ (adaptive phone/tablet, animated status ring)              │
│  TunnelService (VpnService) ── tunnel/                                          │
│     └── outlinev.connectTunnel(fd, ssConfig, Protector, udp)                    │
│            └── libgojni.so (Go: lwIP TUN stack + Shadowsocks)                    │
│                  └── every outbound socket ← Protector.protect(fd)              │
│                     = VpnService.protect() → no routing loop                    │
│  QsTileService ─ tile/          BootReceiver ─ boot/                            │
│  Settings (keep-alive, boot, battery)           KeyRepository (DataStore) ─ data│
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Why the custom Go wrapper?

The stock `outline.aar` dials the proxy with unprotected sockets, so the VPN's
own traffic would loop back through itself. `go/outlinev` wraps the SDK's
`TCPEndpoint`/`UDPEndpoint` with `intra/protect.MakeDialer(protector)`. That
dialer installs a `net.Dialer.Control` hook, which calls into Kotlin and runs
`VpnService.protect()` before every outbound connect. DNS resolves through the
phone's protected system resolvers. [Intra](https://github.com/Jigsaw-Code/Intra)
does the same thing; I applied it to plain Shadowsocks.

## Building

You need JDK 21, Android SDK (android-35, build-tools 35), NDK r27+, and Go 1.22+.

`third_party/` and the built AAR are not committed. Fetch and build them first.
`gradle.properties` pins `org.gradle.java.home`; point it at your JDK or delete
the line and use your default.

1. Fetch the engine, from the repo root:

   ```bash
   git clone --depth 1 https://github.com/Jigsaw-Code/outline-go-tun2socks.git \
     third_party/outline-go-tun2socks
   cd third_party/outline-go-tun2socks
   GOBIN=$PWD/bin go install golang.org/x/mobile/cmd/gomobile@latest
   GOBIN=$PWD/bin go install golang.org/x/mobile/cmd/gobind@latest
   ./bin/gomobile init
   cd ../../go/outlinev
   ```

2. Build the AAR (`app/libs/tun2socks.aar`):

   ```bash
   ANDROID_HOME=<sdk> ANDROID_NDK_HOME=<ndk> \
   ../../third_party/outline-go-tun2socks/bin/gomobile bind \
     -target=android -androidapi 24 -ldflags '-w' \
     -o ../../app/libs/tun2socks.aar \
     -javapkg=dev.gaurish.outlinelite . \
     ../../third_party/outline-go-tun2socks/outline/tun2socks \
     ../../third_party/outline-go-tun2socks/outline/shadowsocks
   ```

3. App and tests:

   ```bash
   ./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
   ./gradlew :app:testDebugUnitTest        # ss:// parser tests
   ```

## Using it

- Get an access key (`ss://…`) from your Outline Manager and open the link on
  your phone. The app imports it. Or tap the + button and paste it.
- Pick a server, hit Connect, allow the VPN prompt once.
- Pin the gaurPN tile in Quick Settings for a 1-tap toggle anywhere.
- Settings (gear icon): keep-alive auto-reconnect, start on boot, and the
  battery exemption that keeps the VPN running in the background.

## Notes

- You need an Outline or Shadowsocks server you control. Any Outline Manager
  endpoint works. gaurPN ships no servers and talks to no third party.
- No analytics. No accounts. No logging. Keys stay on your device.
- Not affiliated with Jigsaw or Google. Their engine, my UI.

## License

MIT. The outline-go-tun2socks engine keeps its own Apache-2.0 license.
