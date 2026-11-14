# In-Tunnel End-to-End Encrypted File Sharing (gaur-share)

`gaur-share` is a zero-leak, zero-knowledge, peer-to-peer file transfer system operating 100% inside your Outline Shadowsocks VPN tunnel.

---

## 1. Security & Privacy Guarantees

* **Zero Wi-Fi Leakage:** No local network broadcasts, no mDNS/Bonjour, no ARP probes, and no raw internet UDP hole punching. All traffic flows strictly into the VPN's TUN interface.
* **Double-Layer Encryption:**
  1. *Outer Layer:* Shadowsocks AEAD (`chacha20-ietf-poly1305` or `aes-256-gcm`). Hides everything from local Wi-Fi, ISP, and cellular carriers.
  2. *Inner Layer:* End-to-End Ephemeral Key Exchange (**NIST P-256 / secp256r1 ECDH**) + Key Derivation (**HKDF-SHA256**) + Authenticated Streaming (**AES-256-GCM**).
* **Zero-Knowledge Dual-Key Architecture:**
  - The shared cluster token $T$ is never sent in raw form to the relay.
  - Clients derive $K_{relay} = \text{hex}(\text{HMAC-SHA256}(T, \text{"gaur-relay-auth-v1"}))$ for relay authentication and presence.
  - Clients derive $K_{peer} = \text{HMAC-SHA256}(T, \text{"gaur-peer-auth-v1"})$ kept strictly on end-devices.
  - Peer offers and accepts are signed with $K_{peer}$ via HMAC-SHA256 tags binding the transfer ID, recipient ID, and ephemeral public keys, preventing malicious or compromised relays from performing active Man-In-The-Middle (MITM) key swaps.
* **Nonce Integrity:** Legacy v1 streams use a strict sequential 96-bit counter. The v2 chunked transport uses the chunk index as the nonce and binds it in the AAD, so chunks may arrive out of order but cannot be reordered, dropped, duplicated, or moved between transfers.
* **Safety Numbers:** Ephemeral public keys and transfer IDs generate a deterministic 30-digit numeric fingerprint (6 blocks of 5 decimal digits) for out-of-band identity verification.
* **Path Traversal & Collision Protection:** Filenames are sanitized against directory traversal attacks, received streams are staged into hidden `.part` files until GCM tag verification succeeds, and duplicate names resolve automatically to numbered files (`file (1).ext`) without interrupting automatic file acceptance.
* **Zero-Knowledge VPS:** The relay daemon on the VPS binds exclusively to the loopback alias `198.51.100.1:9876` and cannot decrypt file contents. It stages opaque ciphertext chunks on disk so a dropped transfer can resume; chunks are evicted by TTL and a byte cap.

---

## 1a. Resumable Chunked Transport (v2)

Large transfers no longer ride a single sequential stream. Files are split
into 1 MiB chunks, each encrypted independently, and staged by the relay so a
dropped connection resumes instead of restarting.

### Identity and manifest

- `file_hash = SHA-256(plaintext file)`.
- `transfer_id = "tx-" + hex(HMAC-SHA256(K_peer, "gaur-transfer-id-v1" || file_hash))[:32]`.
  The same bytes always resume the same transfer, and the relay never sees the
  raw content hash.
- `session_id` is random per send attempt. The relay keys chunks by
  `(transfer_id, session_id, index)`, so a retry cannot collide with stale
  chunks from a previous attempt.
- The offer carries `chunk_size`, `chunk_count`, `file_hash`, and the
  per-chunk SHA-256 list. The offer auth tag binds all of them:
  `offer:v2:<transfer_id>:<receiver_id>:<sender_pub>:<file_hash>:<chunk_count>:<chunk_size>`.

### Per-chunk encryption

- Nonce: 12 bytes, four zero bytes followed by the big-endian chunk index.
- AAD: `chunk:v2:<transfer_id>:<session_id>:<index>:<chunk_count>:<file_size>`.
- AES-256-GCM with the ephemeral ECDH session key.
- The receiver verifies GCM, the chunk length, and the per-chunk SHA-256 before
  writing at `index * chunk_size`.

Because the index is in the nonce and the AAD, chunks may arrive out of order,
but the relay cannot reorder them, move them between transfers, or replay a
chunk from another session.

### Relay endpoints

| Method | Path | Purpose |
|---|---|---|
| `PUT` | `/chunk?transfer_id=&session_id=&index=` | Stage one ciphertext chunk |
| `GET` | `/chunk?transfer_id=&session_id=&index=` | Fetch one ciphertext chunk |
| `GET` | `/chunk/status?transfer_id=&session_id=&count=` | List staged indices |
| `DELETE` | `/transfer?transfer_id=&session_id=` | Drop a completed transfer |

All endpoints require `X-Gaur-Token` (the derived relay token). Chunks are
ciphertext; the relay cannot read them. The store is a cache, not durable
storage: it is cleared on relay restart, evicted after `-chunk-ttl` (default
24h), and bounded by `-chunk-max-bytes` (default 8 GiB). Set
`-chunk-dir=""` to keep chunks in memory only.

### Resume flow

1. Sender hashes the file and sends the v2 offer.
2. Receiver loads `.part` and `.state` (a JSON chunk bitmap) from its inbox,
   or starts fresh, then accepts with `have: [indices already on disk]`.
3. Sender derives the session key and uploads only the missing chunks.
4. Receiver downloads missing chunks, verifies each one, writes at its offset,
   and persists the bitmap every 8 chunks or every second.
5. Receiver verifies the whole-file SHA-256, renames `.part` to the final name,
   and sends a signed `transfer_complete` receipt.
6. Sender reports success only after verifying the receipt. On failure it
   retries up to three times with a new `session_id`; the receiver cancels a
   superseded attempt so two downloaders never race the same `.part` file.

Limits: 1 MiB plaintext chunks, 4 MiB maximum ciphertext chunk, 65,536 chunks
per transfer (64 GiB at 1 MiB), and 4 concurrent chunk workers per side.

---

## 2. Server Setup (`oracle-1`)

The server runs `gaur-relay`, a single lightweight Go binary managed via `systemd`.

### Systemd Service Configuration
File path: `/etc/systemd/system/gaur-relay.service`

```ini
[Unit]
Description=GaurPN In-Tunnel File Sharing Relay
After=network.target

[Service]
Type=simple
# The relay only needs privileges to create the loopback alias. On a hardened
# host, create 198.51.100.1/32 with a separate oneshot unit and run this
# service as a dedicated user instead.
User=root
ExecStartPre=-/usr/sbin/ip addr replace 198.51.100.1/32 dev lo
ExecStart=/opt/gaur-relay/gaur-relay -bind 198.51.100.1:9876 -token-env GAURPN_CLUSTER_TOKEN -chunk-dir /var/lib/gaur-relay/chunks -chunk-max-bytes 8589934592 -mailbox-ttl 1h -mailbox-max-bytes 16777216
StateDirectory=gaur-relay
Environment="GAURPN_CLUSTER_TOKEN=YOUR_PRE_SHARED_SECRET_TOKEN"
Restart=always
RestartSec=3
LimitNOFILE=65535
MemoryMax=64M

[Install]
WantedBy=multi-user.target
```

### Server Management Commands
```bash
# Check service status
sudo systemctl status gaur-relay

# View live relay logs (shows connection handshakes without any payload inspection)
sudo journalctl -u gaur-relay -f
```

---

## 3. Laptop / Desktop Setup (Linux, macOS, Windows)

The companion client is a single binary: `gaur-share`.

### Step 1: Install the Binary
* **Linux (x86_64 / ARM64):**
  ```bash
  sudo cp gaur-share /usr/local/bin/gaur-share
  sudo chmod +x /usr/local/bin/gaur-share
  ```
* **macOS (Apple Silicon / Intel):**
  ```bash
  sudo cp gaur-share /usr/local/bin/gaur-share
  sudo chmod +x /usr/local/bin/gaur-share
  ```
* **Windows:**
  Place `gaur-share.exe` in `C:\Program Files\gaur-share\` and add it to your `PATH`.

### Step 2: Configure Environment or Config File
Create `~/.config/gaur-share/config.json`:
```json
{
  "device_name": "Gaurish-Laptop",
  "relay_address": "198.51.100.1:9876",
  "cluster_token": "YOUR_PRE_SHARED_SECRET_TOKEN",
  "download_dir": "~/Downloads/gaur-inbox"
}
```

### Step 3: Usage

#### 1. Start the Background Daemon (Receives Files)
```bash
gaur-share daemon
```
* Incoming files are automatically decrypted and saved to `~/Downloads/gaur-inbox/`.
* Sends desktop notifications via `notify-send` (Linux), AppleScript (macOS), or Toast (Windows).

#### 2. List Online Devices
```bash
gaur-share list
```
*Output:*
```text
ONLINE DEVICES (IN-TUNNEL)
────────────────────────────────────────────────────────────
ID                    DEVICE NAME       STATUS      LAST SEEN
phone-a81f3b          Pixel 8           online      just now
laptop-92c10e         Gaurish-Laptop    online (you)just now
```

#### 3. Send a File to Phone
```bash
gaur-share send "Pixel 8" ~/Documents/report.pdf ~/Documents/notes.pdf
```
*Output:*
```text
Target located: Pixel 8 (phone-a81f3b)
Sent transfer offer for 'report.pdf' (14.20 MB). Waiting for acceptance...
Transfer accepted! Ephemeral E2EE session verified. Safety number: 63877 21084 56392 35848 28620 54908
Streaming: [██████████████████████████████] 100.0% (14.2/14.2 MB) - 48.2 MB/s
Transfer successfully completed in 0.3s!
```

#### 4. Send Text or a Link
```bash
gaur-share send-text "Pixel 8" "https://example.com/recipe"
echo "otp 482913" | gaur-share send-text "Pixel 8" -
```
The receiver gets a notification with **Copy** and, for URLs, **Open**.

#### 5. Send a Folder
```bash
gaur-share send "Pixel 8" ~/Pictures/holiday
```
Folder contents are sent as a flat batch; relative paths are not preserved yet.

#### 6. Pair from the Relay QR
```bash
gaur-share pair "gaur://pair?relay=198.51.100.1%3A9876&token=..."
```
The token is written to `~/.config/gaur-share/cluster.key` with mode `0600`.

#### 7. Local Browser UI
```bash
gaur-share ui
```
Binds `127.0.0.1`, prints the URL, and opens a page with peers, text sending,
and drag-and-drop file sending. Each action shells out to the same binary, so
the UI uses the same crypto and relay paths as the CLI.

---

## 4. Android Client (`gaurPN`)

1. Open `gaurPN` and connect to your Outline VPN.
2. In **Settings**, tap **File Sharing Settings** to set your device name and paste your `Cluster Token`.
3. On the **Home Screen**, a **"Cluster Devices"** card appears below the VPN status ring showing online peers.
4. Tap any device to pick a file and initiate instant transfer.
5. Incoming transfers display an Android notification with progress and an action to open the file directly from `Downloads/`.
6. Share a link or text from any app through the Sharesheet; the receiving device gets a notification with **Copy** and **Open**.
7. **Settings → Transfer history** reopens received files, retries failed sends, and resends text/links.
8. **Settings → Trusted devices** pins peers by stable ID and can reject offers from untrusted devices.

---

## 5. Clipboard Sync

Clipboard sync uses the same authenticated WebSocket as file sharing. Each device sends the newest local clipboard item to every other online peer; the relay forwards it without inspecting the payload.

### Wire Format

```json
{
  "type": "clipboard_update",
  "target_id": "peer-id",
  "msg_id": "clip-<uuid>",
  "ts": 1730000000000,
  "kind": "text" | "image",
  "mime": "text/plain" | "image/png" | "image/jpeg",
  "nonce": "<base64 12-byte GCM nonce>",
  "data": "<base64 AES-256-GCM ciphertext>"
}
```

The relay adds `from_id`, `from_name`, and `relay_ts` before forwarding. Receivers order updates by `relay_ts` (falling back to the sender's `ts` if the relay is older), so the most recent copy wins even when device clocks disagree.

### Encryption

- `K_peer = HMAC-SHA256(cluster_token, "gaur-peer-auth-v1")`
- `K_clipboard = HMAC-SHA256(K_peer, "gaur-clipboard-v1")`
- AES-256-GCM with a fresh 96-bit nonce per message.
- AAD: `clipboard:<from_id>:<msg_id>:<ts>:<kind>:<mime>`.

The AAD binds the routing metadata to the ciphertext. A modified `from_id`, timestamp, kind, or MIME type fails authentication. The relay cannot decrypt the payload.

### Android Background Read

Android 10+ only allows the focused app or the default IME to read the clipboard. gaurPN is neither, so `ClipboardSyncManager`:

1. Tries a direct read (works when gaurPN is foreground).
2. Otherwise adds a 1x1 focusable `TYPE_APPLICATION_OVERLAY` window, waits for the UID to become the focused window, reads `getPrimaryClip()`, and removes the window.
3. Skips polling while the screen is locked or off.
4. Suppresses the just-applied remote hash so it is not echoed back.

This is not silent: Android may show its "pasted from clipboard" privacy toast once per new clip. A default-IME, Shizuku, or root integration would avoid the toast and the focus flicker, but requires additional setup.

### Desktop Event Source

The daemon supports three sources via `-clipboard-source`:

- `socket`: Quickshell watches `~/.cache/cliphist/db` with `FileView` (inotify) and, on change, runs `cliphist list`/`cliphist decode` for the newest entry. It sends one JSON line to `$XDG_CACHE_HOME/gaur-share/clipboard.sock`; the daemon encrypts and forwards it. No polling.
- `poll`: the daemon reads the clipboard once per second. Kept for standalone/legacy use.
- `off`: disabled.

The included `~/.config/quickshell/services/ClipboardSync.qml` implements the socket source. It never reads the 65 MB BoltDB; `blockAllReads: true` keeps `FileView` to a pure inotify watcher. The only timers are one-shot debounce/retry timers, not periodic polls.

### Images

- Android decodes the clipboard image, downsamples it to at most 2048 px, and re-encodes as PNG (alpha) or JPEG. Maximum payload is 8 MiB.
- Received images are written to `filesDir/clipboard/` and exposed through a `FileProvider` URI so the pasting app can read them.
- Desktop image support uses `wl-paste`/`wl-copy`, `xclip`, `pngpaste`, or PowerShell depending on the OS.

### Echo and Replay Protection

- Every payload is hashed (SHA-256 over kind + MIME + bytes). A device ignores a local clip whose hash matches the last sent or last applied clip.
- A remote update is ignored when its `relay_ts` is not newer than the last applied update.
- Sensitive clips (`ClipDescription.EXTRA_IS_SENSITIVE`) are skipped on Android unless the user explicitly enables them. Desktop clients cannot detect this platform flag, so run the daemon with `-clipboard-source=off` on machines that handle secrets.

### Per-Device Exclusions

Clipboard broadcasts skip peers listed in the local exclusion set. Android exposes
this as a switch per online device under **Settings → Clipboard Sync**. The
exclusion is local; the relay is not told about it.

---

## 6. Text and Link Sharing

New clients send text and links as a sealed v3 `text` envelope (section 11), so
the relay does not see the sender. The older `text_message` format below remains
for compatibility.

Text and links use a one-shot message on the same authenticated WebSocket as
file transfers and clipboard sync. The relay forwards it and adds `from_id`,
`from_name`, and `relay_ts`; it never stores the payload.

```json
{
  "type": "text_message",
  "v": 1,
  "target_id": "peer-id",
  "sender_id": "stable-device-id",
  "msg_id": "text-<uuid>",
  "ts": 1730000000000,
  "kind": "text" | "url",
  "nonce": "<base64 12-byte GCM nonce>",
  "data": "<base64 AES-256-GCM ciphertext>"
}
```

- `K_text = HMAC-SHA256(K_peer, "gaur-text-v1")`, domain-separated from file
  transfers and clipboard sync.
- AAD: `text:<from_id>:<msg_id>:<ts>:<kind>`.
- Maximum plaintext: 64 KiB.
- `kind` is `url` when the text starts with `http://` or `https://`, otherwise
  `text`.
- Android shows a notification with **Copy** and, for URLs, **Open**. It does
  not silently replace the clipboard.
- The desktop daemon copies the text to the clipboard and raises a notification.
- Android's Sharesheet accepts `text/plain`; the desktop uses
  `gaur-share send-text <device> <text|->`.

---

## 7. Pairing and Onboarding

`gaur-relay -init` generates a 32-byte cluster token, writes it with mode `0600`,
and prints a QR containing:

```text
gaur://pair?relay=<host:port>&token=<cluster-token>&dns=<dns-server>&name=<optional>
```

- Scanning the QR with the system camera opens gaurPN through the `gaur` scheme.
  The app stores the relay address, token, and DNS server. No camera permission
  or in-app scanner is required.
- `gaur-share pair '<uri>'` writes the token to `~/.config/gaur-share/cluster.key`
  (mode `0600`) and updates `config.json` without putting the token in it.
- Restart the relay after `-init` so it loads the new token.
- To pair another device to the existing cluster without rotating the token,
  run `gaur-relay -qr -token-file <path> -pair-relay <addr> -pair-dns <addr>`.
- The URI contains the cluster secret. Treat it like a password and do not paste
  it into logs, chat, or issue reports.

---

## 8. Trusted Devices

Each install persists a stable device ID. The trust list is local to each peer:

- **Trust** adds a device ID and name to the local list.
- **Revoke** removes it. The device can still reach the relay with the cluster
  token, but this device will reject its offers when **Only accept from trusted
  devices** is enabled.
- Untrusted offers are rejected with `"reason": "untrusted"` and a local
  "Transfer blocked" notification.
- To remove a device's relay access entirely, rotate the cluster token and
  re-pair the remaining devices. Trust is peer-side; the token remains the
  cryptographic boundary.
- **Android**: Settings → Trusted devices.
- **Desktop**: `gaur-share trust <device>`, `gaur-share untrust <device>`,
  `gaur-share trusted`, and `gaur-share trust-mode on|off`. The daemon reads
  `~/.config/gaur-share/trusted.json` and `only_trusted` from `config.json`
  (or the `-only-trusted` flag). Both sides enforce the list for file offers
  and text messages.

---

## 9. DNS Through the Tunnel

The Android client sends DNS to AdGuard Home on the VPS instead of a public
resolver:

- AdGuard Home listens on the VPS private address `10.0.0.182:53` (in addition
  to `127.0.0.1:53`). The address is not publicly routable. With Docker Compose:

  ```yaml
  ports:
    - "127.0.0.1:53:53/tcp"
    - "127.0.0.1:53:53/udp"
    - "10.0.0.182:53:53/tcp"
    - "10.0.0.182:53:53/udp"
  ```
- The app adds `10.0.0.182` as a VPN DNS server and a `/32` host route. The host
  route is more specific than the LAN-bypass excludes, so the resolver stays
  reachable through the tunnel even when LAN access is enabled.
- Modes: **AdGuard** (default), **System** (the underlying network's resolvers,
  still through the tunnel), and **Custom** (comma-separated IPv4/IPv6).
- The relay and AdGuard are separate services. The relay never sees DNS
  queries; they are ordinary tunneled UDP to the VPS.

### Kill switch

While the tunnel is reconnecting, the TUN interface stays established with its
routes intact but no reader. Packets are dropped instead of falling back to the
physical network. The interface is released on a final disconnect, when the
user stops the VPN, or when the kill switch is disabled.

---

## 10. Desktop Web UI

`gaur-share ui` starts an HTTP server on `127.0.0.1` (random port by default),
prints the URL, and opens it in the browser. It provides:

- the online peer list,
- a text/link form,
- a file picker with drag-and-drop.

Each action shells out to the same `gaur-share` binary with the same config, so
the UI cannot bypass the CLI's encryption or relay behavior. Uploaded files are
staged in a temporary directory, sent, and deleted. The server binds loopback
only and never renders the cluster token.

---

## 11. Sealed v3 Envelopes, Offline Delivery, and One Upload

v3 messages use a sealed envelope:

```json
{
  "type": "sealed",
  "v": 3,
  "target_id": "device-id",
  "msg_id": "seal-<uuid>",
  "ts": 1730000000000,
  "kind": "offer" | "complete" | "text" | "notification" | "notification_action",
  "nonce": "<base64>",
  "data": "<base64>"
}
```

- Key: `K_seal = HMAC-SHA256(K_peer, "gaur-seal-v3:" + kind)`.
- AAD: `seal:v3:<target_id>:<msg_id>:<ts>:<kind>`.
- The inner JSON includes `sender_id`, `sender_name`, and the payload.
- The relay does **not** add `from_id`/`from_name` for `sealed` messages. It
  adds `relay_ts` and forwards to `target_id`.
- If the target is offline, the relay stores the envelope in an in-memory
  mailbox with a TTL (default 1h) and byte cap (default 16 MiB). On reconnect,
  the mailbox is drained to the device.
- The mailbox is cleared on relay restart. Chunks staged for a transfer are also
  cleared on restart.

### v3 file transfer

- `file_key = HMAC-SHA256(K_peer, "gaur-shared-file-v1:" + transfer_id)`.
- Chunks are encrypted with `file_key` using the same v2 chunk AAD and nonce
  scheme.
- The sender uploads every chunk once, then sends a sealed `offer` to each
  target.
- The offer inner JSON contains `sender_id`, `sender_name`, `transfer_id`,
  `session_id`, `file_name`, `file_size`, `chunk_size`, `chunk_count`,
  `file_hash`, `chunk_hashes`, and batch metadata.
- Each recipient downloads the shared ciphertext, verifies the whole-file hash,
  saves the file, and sends a sealed `complete` receipt.
- The sender does not need to be online while the recipient downloads.
- Because the same ciphertext is shared, the relay stores one copy for all
  recipients. Do not call `DELETE /transfer` after the first recipient; let the
  TTL/byte cap evict it.

### CLI

```bash
gaur-share send --all <file|dir>...             # one upload, every online device
gaur-share send --offline <device-id> <file>... # queue for an offline device
gaur-share send --to <id,id> <file>...          # specific device IDs
```

---

## 12. Notification Mirroring

Android's `NotificationListenerService` captures local notifications and sends
sealed `notification` envelopes to every cluster peer. The app's own package is
ignored, which is the loop guard: mirrored notifications are posted by gaurPN,
so they are never re-broadcast.

### Payload

```json
{
  "sender_id": "android-...",
  "sender_name": "Lavander",
  "event": "posted" | "removed",
  "key": "notification-key",
  "package": "com.example",
  "app": "Example",
  "title": "...",
  "text": "...",
  "big_text": "...",
  "subtext": "...",
  "category": "msg",
  "ongoing": false,
  "clearable": true,
  "ts": 1730000000000,
  "icon": "<base64 PNG, max 64 KiB>",
  "actions": [{"i": 0, "t": "Reply", "r": true}]
}
```

- Android receivers render the app icon, title, text, and action buttons. Reply
  actions use a `RemoteInput`; the reply is sealed back to the source device as a
  `notification_action`.
- The source device executes the original `PendingIntent` (with `RemoteInput`
  results for replies).
- The desktop daemon renders the notification with `notify-send`, including the
  icon and action buttons. A chosen action is sealed back to the source; reply
  actions prompt with `zenity` or `kdialog`.
- `gaur-share notify <device> <title> [body]` sends a sealed notification from
  the desktop to a device.
- Android requires **Notification access** (Settings → Notifications → Share
  notifications). The desktop daemon uses `notify-send`.
- Group summaries are skipped to avoid duplicate notifications.
- The relay sees only that a sealed `notification` envelope exists; the payload
  is encrypted.

### Desktop notifications

Quickshell's `NotificationServer.onNotification` forwards every notification
whose app name does not start with `gaurPN` to:

```bash
gaur-share notify --all <title> <body>
```

The CLI sends a sealed notification to every online device. gaurPN tags its own
and mirrored notifications with app name `gaurPN`, and Quickshell skips that
prefix to prevent a loop. The forwarder is event-driven and serialized through a
single `Process`; it does not poll.

---

## 13. Media Control (MPRIS)

Android's active media session is mirrored to the desktop as a local MPRIS
player. Quickshell's media widget discovers it like any other player.

- Android uses `MediaSessionManager` through the notification listener
  permission. Enable **Settings → Media → Share media controls** and grant
  notification access.
- Sealed `media_state` messages carry session id, app, title, artist, album,
  duration, position, rate, playing state, capabilities, and optional artwork.
- Sealed `media_command` messages carry play, pause, play_pause, next, previous,
  stop, seek, and volume. Commands older than 30 seconds are ignored, so a
  mailbox replay cannot pause or skip a track unexpectedly.
- The desktop daemon exports one MPRIS player per source device at
  `org.mpris.MediaPlayer2.gaurpn.<device>`.
- Position is sent on state changes only. The MPRIS `Position` property
  interpolates from the last position, rate, and timestamp; there is no polling
  across the tunnel.
- Artwork is sent once per hash, cached under `~/.cache/gaur-share/`, and exposed
  as `mpris:artUrl`.
- Test with `playerctl -l` and `playerctl -p <player> play-pause`.
