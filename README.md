# gaurPN

A custom, native Android client for Outline (Shadowsocks) with Material 3 UI,
a home-screen widget, a Quick Settings tile, and a protected tun2socks engine.

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

## Build

Prereqs: JDK 21+ (a pinned copy lives in `.devcache/`, referenced from
`gradle.properties`), Android SDK with `android-35` + build-tools 35, NDK r27+,
and Go 1.22+.

1. **Engine AAR** (requires NDK + gomobile; done once, AAR cached via git LFS-free copy):

   ```bash
   cd third_party/outline-go-tun2socks             # vendored clone
   # installs gomobile locally:
   GOBIN=$PWD/bin go install golang.org/x/mobile/cmd/gomobile@latest
   GOBIN=$PWD/bin go install golang.org/x/mobile/cmd/gobind@latest
   ANDROID_HOME=/opt/android-sdk ANDROID_NDK_HOME=<ndk> ./bin/gomobile init
   cd ../../go/outlinev
   ../../third_party/outline-go-tun2socks/bin/gomobile bind \
     -target=android -androidapi 24 -ldflags '-w' \
     -o ../../app/libs/tun2socks.aar \
     -javapkg=dev.gaurish.outlinelite . \
     ../../third_party/outline-go-tun2socks/outline/tun2socks \
     ../../third_party/outline-go-tun2socks/outline/shadowsocks
   ```

2. **App**: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

3. **Tests**: `./gradlew :app:testDebugUnitTest` (ss:// parser coverage)

## Using it

- Get an access key (`ss://…`) from your Outline Manager and open the link on
  your phone — the app imports it. Or tap **＋** and paste the key.
- Pick a server, hit **Connect**, allow the VPN permission prompt once.
- Pin **gaurPN** in Quick Settings for a 1-tap toggle; drop the widget on
  your home screen for status + toggle.

## Why the custom wrapper?

The stock `outline.aar` dials the proxy with unprotected sockets, so the VPN's
own traffic would loop back through itself. `go/outlinev` wraps the SDK's
`TCPEndpoint`/`UDPEndpoint` with `intra/protect.MakeDialer(protector)`, whose
`net.Dialer.Control` hook calls back into Kotlin → `VpnService.protect()`
before every outbound connect, and routes DNS through the phone's protected
system resolvers. Same trick Intra uses; applied to plain Shadowsocks.
