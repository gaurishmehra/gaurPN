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
- The legacy v1 tag also binds its offer metadata (since v3.1):
  `offer:v1:<transfer_id>:<receiver_id>:<sender_pub>:<base64(file_name)>:<file_size>`,
  computed over the raw pre-sanitization filename. Both current clients verify
  this format; peers older than v3.1 that sign the shorter `offer:` tag are
  rejected.

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
| `PUT` | `/chunk?transfer_id=&session_id=&index=&device_id=&cap=` | Stage one ciphertext chunk |
| `GET` | `/chunk?transfer_id=&session_id=&index=&device_id=&cap=` | Fetch one ciphertext chunk |
| `GET` | `/chunk/status?transfer_id=&session_id=&count=` | List staged indices |
| `DELETE` | `/transfer?transfer_id=&session_id=&device_id=&cap=` | Drop a completed transfer |

All endpoints require `X-Gaur-Token` (the derived relay token). The token may
also arrive as a `token` query parameter from pre-v3.1 clients; current clients
always send the header so the token never appears in a URL. Chunks are
ciphertext; the relay cannot read them. The store is a cache, not durable
storage: it is cleared on relay restart, evicted after `-chunk-ttl` (default
24h), and bounded by `-chunk-max-bytes` (default 8 GiB) as a hard cap — once
the store is full, `PUT` for a transfer without staged chunks is refused with
507, while in-flight transfers may always finish. Set `-chunk-dir=""` to keep
chunks in memory only. The relay refuses to start when `-chunk-dir` names a
non-empty directory without its `.gaur-relay-chunks` marker, so a mistyped
path never wipes unrelated data.

### Transfer ownership (v3.1)

Chunk and transfer requests carry two extra params: `device_id` (the caller)
and `cap = hex(HMAC-SHA256(K_peer, "gaur-cap-v1:" + transfer_id + ":" + session_id))`.

- The first `PUT` for a transfer records its creator's `device_id` as the owner.
- Later requests are allowed for the owner, for any request with a valid `cap`
  (all cluster members can compute it), or — for legacy clients that send
  neither param — in "open mode" with a deprecation log. Once an owner exists,
  legacy open-mode access is refused with 403.
- This is relay-side policy, not a cryptographic boundary: every cluster member
  holds `K_peer` and can mint caps. Per-device identity keys (protocol v4) are
  the planned real fix.

The relay also enforces: one sender and one receiver per `/pipe` transfer (a
second connection claiming an occupied role gets 409), `device_id` matching
`^[A-Za-z0-9._-]{1,64}$` and names ≤128 bytes on `/ws` (400 otherwise), a
`-max-peers` cap (default 32; a reconnecting device still replaces itself),
a `-chunk-max-bytes` hard cap (new transfers get 507 once the store is full;
in-flight transfers may finish), a `-mailbox-max-target-bytes` per-device
mailbox cap (default 4 MiB; a target's own oldest entries are evicted first),
a per-IP auth-failure budget (10 failures/minute → 429 with `Retry-After`),
a startup bind guard that refuses to listen on any address not assigned
to a loopback interface, and WebSocket liveness deadlines: the relay pings
every client every 30 s (standard clients auto-pong), drops a connection
that neither sends traffic nor answers pings within 75 s, and bounds every
write to a client by a 15 s deadline so a stalled client cannot block its
correspondents. SIGTERM drains in-flight requests for up to 10 s, closes
WebSocket peers cleanly, and then exits.

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
ExecStart=/opt/gaur-relay/gaur-relay -bind 198.51.100.1:9876 -token-env GAURPN_CLUSTER_TOKEN -chunk-dir /var/lib/gaur-relay/chunks -chunk-max-bytes 8589934592 -mailbox-ttl 1h -mailbox-max-bytes 16777216 -mailbox-max-target-bytes 4194304
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
  "kind": "offer" | "complete" | "text" | "notification" | "notification_action" | "media_state" | "media_command" | "browse" | "browse_listing" | "fetch" | "request" | "request_declined" | "ask" | "ask_response" | "open" | "open_result" | "cam_control",
  "nonce": "<base64>",
  "data": "<base64>"
}
```

- Key: `K_seal = HMAC-SHA256(K_peer, "gaur-seal-v3:" + kind)`.
- AAD: `seal:v3:<target_id>:<msg_id>:<ts>:<kind>`.
- The inner JSON includes `sender_id`, `sender_name`, and the payload.
- The relay does **not** add `from_id`/`from_name` for `sealed` messages, and
  strips them if a client includes them. It adds only `relay_ts` and forwards
  to `target_id`.
- Receivers drop any sealed envelope whose `target_id` does not match their own
  device id (defense against a malicious relay redirecting mail), and dedup by
  `msg_id` so a replayed envelope is processed once. `media_command`
  envelopes additionally expire 30 s after their `relay_ts` (§13).
- If the target is offline, the relay stores the envelope in an in-memory
  mailbox with a TTL (default 1h), a global byte cap (default 16 MiB), and a
  per-target byte cap (`-mailbox-max-target-bytes`, default 4 MiB) so one
  device cannot monopolize the queue. On reconnect,
  the mailbox is drained to the device with ack-based delivery: an entry is
  removed only after the relay's write succeeds, so a rejoin that dies
  mid-drain loses nothing, and a sealed envelope whose direct write to a
  present-but-unwritable peer fails is re-queued instead of dropped. The
  re-queued envelope keeps its original `relay_ts`, which — combined with the
  receivers' `msg_id` dedupe — makes the resulting at-least-once delivery
  safe.
- The mailbox is cleared on relay restart. Chunks staged for a transfer are also
  cleared on restart.

### v3 file transfer

- `file_key = HMAC-SHA256(K_peer, "gaur-shared-file-v1:" + transfer_id)`.
- Chunks are encrypted with `file_key` using the same v2 chunk AAD and nonce
  scheme.
- `session_id` is deterministic, unlike v2: the first 16 hex chars of
  `HMAC-SHA256(K_peer, "gaur-session-v3:" + transfer_id)`. Because the same
  file always maps to the same transfer id, a retried send addresses the
  chunks it staged on the previous attempt instead of re-uploading from
  zero. Android and desktop derive it identically.
- Before uploading, the sender asks `GET /chunk/status` which chunk indices
  are already staged and uploads only the rest; on any error it fails open
  to a full upload. The receiver mirrors the v2 resume logic: `.part` and
  `.state` are reused when they match the offer's `file_hash`, and kept for
  resume when a download dies mid-flight (an integrity failure still clears
  them, since that state is poisoned).
- The sender uploads the chunks that are not already staged, then sends a
  sealed `offer` to each target.
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
  actions prompt with `zenity` or `kdialog`. Updates to the same Android
  notification reuse a stable `notify-send -r` id, so a chat that rewrites its
  entry replaces the desktop bubble instead of stacking another one.
- `gaur-share notify [-app name] [-package pkg] <device> <title> [body]` sends a
  sealed notification from the desktop to a device. `--all` fans it out. `-app`
  labels the source; `-package` lets the receiver suppress the mirror when that
  app is installed there and already posted its own copy.
- Android requires **Notification access** (Settings → Notifications → Share
  notifications). The desktop daemon uses `notify-send`.
- Group summaries are skipped to avoid duplicate notifications.
- A receiver drops a mirrored notification when the source app already lives
  on it: Android checks whether the package is installed and enabled, and the
  desktop checks whether a matching process is running. Either way the local
  app posts the notification itself, so the mirrored copy would be a duplicate.
  Desktop-originated payloads without a package are always shown; the
  Quickshell forwarder maps ZapFast/WhatsApp/Karere to `com.whatsapp`, Telegram
  to `org.telegram.messenger`, and Signal to `org.thoughtcrime.securesms`, so
  the phone drops the copy when it has the same app. A suppressed Android
  notification answers with `notification_ack` status `suppressed`; that is
  expected, not a delivery failure.
- Rebranded desktop clients are matched by an explicit alias table because the
  process name shares nothing with the Android label. WhatsApp
  (`com.whatsapp`, including `com.whatsapp.w4b`) maps to the Linux clients
  ZapFast (`zapfast`) and Karere (`karere`).
- Presence is app-level. A notification from a second account that exists only
  on the source device is also skipped on a receiver that has the app
  installed or running.
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

---

## 14. Requests (Pull Transfers)

Everything else in this document is push. Pull transfers let a device browse
another cluster device's files in its own UI and fetch what it picks.

### Protocol (v3.2.1)

- Sealed kind `browse` (requester → target), inner JSON:
  `{"request_id","requester_id","origin_id"?,"requester_name","target_id","path","ts"}`.
  `path` is `""` for the root listing, otherwise relative segments joined by
  `/` (no leading slash, no `..`, no control characters, ≤1024 UTF-16 units,
  each segment ≤255).
- Sealed kind `browse_listing` (target → requester), inner:
  `{"request_id","path","entries":[{"name","size","modified","dir"}...],"truncated"?,​"error"?,"ts"}`.
  Entries are dirs-first, case-insensitive, capped at 1000 (`truncated` set
  when cut). `error` (mutually exclusive with entries) is one of
  `denied|not_found|rate_limited|unsupported`.
- Sealed kind `fetch` (requester → target), inner:
  `{"request_id","requester_id","origin_id"?,"requester_name","target_id","paths":[...],"expires_ts","grant","ts"}`.
  `paths` is 1–32 relative paths from a prior listing; the grant is the
  existing `"gaur-request-grant-v1"` HMAC. Declines use the sealed
  `request_declined` kind with reasons
  `declined|busy|expired|unsupported|not_allowed|not_found|too_large`.
- The target streams each fetched file as a normal sealed v3 offer carrying
  the echoed `request_id` + `grant`; the requester auto-accepts (CLI pending
  state) or trust-accepts (Android) exactly like the old request flow.

### What the target serves

- **Desktop**: a browse root (flag `-browse-root`, default `$HOME`).
  Dotfiles and symlinks are hidden; paths are resolved (`EvalSymlinks`) and
  containment-checked before listing or fetch; the listing stops reading at
  the entry cap. A legacy `request` kind always declines `unsupported`.
- **Android**: media collections as virtual roots — `Pictures`, `DCIM`,
  `Download`, `Documents`, `Movies`, `Music` — listed via MediaStore
  (`RELATIVE_PATH` prefix grouping, SQL LIMIT, LIKE-escaped). A file's fetch
  path is its `RELATIVE_PATH + DISPLAY_NAME`. When the user grants **All
  files access** (Settings → Transfers → All files access,
  `MANAGE_EXTERNAL_STORAGE`), a seventh root `Storage` appears that serves
  the entire shared-storage volume via the File API — including other apps'
  non-media files — with the same dotfile/symlink/containment rules as the
  desktop. Without it, Android 13+ media-visibility rules limit the media
  collections to what the provider exposes (other apps' non-media files
  stay invisible).

### Authorization — read this

Browse and fetch are **trusted-only capabilities**: the claimed origin
(`origin_id`, falling back to `requester_id`) must be in the target's
trusted list (`gaur-share trust` on the desktop, Settings → Trusted devices
on Android), regardless of the only-trusted toggle. Trust a device once,
then it can browse. Every accepted fetch posts a notification on the target
(peer and file count only — never names). Under the shared-token model the
identity claim is still forgeable by any cluster member (the seal proves
membership, the trust list decides which claims to believe); per-device
identity keys (protocol v4) are the planned hard boundary.

### Availability limits

- Browse: per-origin 2/sec, global 10/sec, at most 2 concurrent listing
  workers per target; excess browses answer `rate_limited` without doing
  filesystem work.
- Fetch: one active fetch per target (busy), one accepted fetch per origin
  per 30 s, ≤32 paths, ≤4 GiB total (both ends enforce), 30-minute send
  bound, and a receive-side cap on the requester.
- Requester UIs: Android gets a full-screen browser (breadcrumb, multi-select,
  download bar); the desktop gets a native GTK/libadwaita window
  (`gaur-browse [target]`, installed from `desktop-client/gaur-browse.py`)
  that follows the system theme, plus the `browse`/`fetch`/`screenshot` CLI
  commands. The old `gaur-share request` command is gone; `screenshot <target>`
  remains the quick action for the newest phone screenshot.

---

## 15. Peer Status Telemetry (battery/charging)

Cluster presence carries encrypted battery and charging state so you can see
whether the phone will survive a transfer before sending one.

- `K_status = HMAC-SHA256(K_peer, "gaur-status-v1")`.
- Payload (manually serialized, byte-identical on all platforms):
  `{"battery":<0-100>,"charging":<true|false>,"ts":<epoch-ms>}`.
- AES-256-GCM with a fresh 12-byte nonce, AAD `"status:v1:" + device_id`
  (binds the blob to its device — blobs cannot be swapped between peers).
- The relay-native frame `{"type":"status","v":1,"status":"<base64(nonce||ct||tag)>"}
  is NOT sealed: the relay stores the opaque blob per peer (rate-limited to
  one frame per device per 2 seconds) and includes it in the peers broadcast
  as an optional `"status"` field. The relay never learns the battery level;
  it validates only length (≤160 chars) and base64 shape.
- Receivers drop blobs whose authenticated `ts` is older than 10 minutes.
- Publishers are event-driven; nothing is polled or timer-driven: Android
  listens to `ACTION_BATTERY_CHANGED` while the relay connection is up; the
  desktop daemon watches UPower's `PropertiesChanged` on the system bus
  (battery-less desktops publish nothing). One wrinkle exists on both
  platforms: a battery that has not changed in 5 minutes (a laptop parked
  at full, plugged in) no longer produces events, while consumers decode
  blobs against the 10-minute window. The fix rides existing events rather
  than adding a timer: whenever a publisher receives a peers broadcast —
  proof the cluster is listening — it re-advertises its unchanged battery
  if the last advertisement is 5 minutes old or older. A quiet cluster
  publishes nothing; a device that joins later sends its own status, whose
  fan-out triggers everyone else's repaint, so its view heals within a
  round trip.
- The desktop daemon decrypts blobs and republishes them in
  `~/.cache/gaur-share/peers.json` as optional `"battery"` and `"charging"`
  fields per peer (schema addition; the file is 0600). Android shows them on
  the cluster device cards.

---

## 16. URL Handling on Text Shares

Shared text that validates as a URL gets an open action:

- Validation (identical on both platforms): trim, ≤2048 UTF-16 units, reject
  control chars/space/`" < > \ ^ \` { | }`, scheme must be `http` or `https`,
  host non-empty. The canonical parsed form is what gets opened — never the
  raw text. No other scheme (`steam:`, `file:`, `javascript:`, custom
  protocol handlers) is ever passed to a launcher, so a malicious peer cannot
  trigger app launches.
- Desktop: the text notification gains an "Open in browser" action running
  `xdg-open <canonical-url>` as a single argv (no shell, at most two
  concurrent action notifications, plain fallback beyond).
- Android: the existing Open action on text-share notifications opens the
  validated canonical URL via `ACTION_VIEW` and is hidden when validation
  fails (Copy still works).

---

## 17. Approval Requests

`gaur-share ask <device> <reason...>` sends a sealed `ask` to one explicitly
trusted device. The phone shows a high-importance notification with **Approve**
and **Deny** actions. The requester keeps its ephemeral `reqdev-…` connection
open and waits for the sealed `ask_response`.

```json
{
  "request_id": "req-<16 hex>",
  "requester_id": "reqdev-<pid>-<hex>",
  "origin_id": "<stable desktop id>",
  "requester_name": "Laptop",
  "target_id": "<phone id>",
  "reason": "Deploy to prod?",
  "expires_ts": 1730000000000,
  "grant": "<v3.2 request grant>",
  "ts": 1730000000000
}
```

```json
{
  "request_id": "req-<16 hex>",
  "responder_id": "<phone id>",
  "decision": "approved" | "denied",
  "reason": "user_denied" | "expired" | "busy" | "not_allowed" | "notifications_blocked",
  "grant": "<echo>",
  "ts": 1730000000000
}
```

- The response is addressed to `requester_id`, not the stable origin. The
  grant is verified against the request id, requester, target, and expiry.
- `origin_id` is the trust-list key. The phone requires that device to be
  explicitly trusted; an untrusted requester gets a `not_allowed` response
  rather than silence.
- The reason is trimmed, limited to 200 characters, and rejected if it
  contains control characters. It is never logged.
- Default lifetime is 120 s (`--timeout`, clamp 5 s–5 min). The notification
  uses `setTimeoutAfter` for its remaining lifetime; the registry also drops
  expired entries lazily. The relay mailbox's 1 h TTL is not a security
  boundary.
- Android keeps one live approval per origin, a 30 s per-origin cooldown, and
  a bounded pending map. A concurrent request is answered `busy`.
- The prompt is `VISIBILITY_PRIVATE` with a generic public version, so the
  reason is hidden until unlock. Approve/Deny are immutable broadcast
  `PendingIntent`s; the receiver never starts an activity.
- The approval is advisory. It authorizes the caller's next action; it is not
  a cryptographic capability bound to a specific command.

Exit codes: `0` approved, `1` denied, `2` usage, `3` timeout, `4`
transport/unsupported/busy, `130` interrupted.

---

## 18. Handoff (Remote Open)

`gaur-share open <device> <url|file|package>` hands one actionable target to
an explicitly trusted device. URLs and package names use a sealed `open`;
files use the existing v3 chunk upload and sealed `offer` with an open
request.

### URL / package

```json
{
  "request_id": "req-<16 hex>",
  "requester_id": "reqdev-<pid>-<hex>",
  "origin_id": "<stable desktop id>",
  "requester_name": "Laptop",
  "target_id": "<phone id>",
  "open_kind": "url" | "package",
  "value": "https://example.com/x" | "com.example.app",
  "expires_ts": 1730000000000,
  "grant": "<grant>",
  "ts": 1730000000000
}
```

```json
{
  "request_id": "req-<16 hex>",
  "responder_id": "<phone id>",
  "status": "opened" | "notification" | "denied" | "unsupported" | "busy" | "failed",
  "reason": "not_allowed" | "expired" | "no_handler" | "invalid",
  "grant": "<echo>",
  "ts": 1730000000000
}
```

- URL values must pass the shared `http`/`https` validator (§16). No other
  scheme reaches an intent.
- Package values must match the Android package grammar and resolve to a
  launch intent; otherwise the result is `unsupported`.
- `ALLOW_REMOTE_OPEN` is on by default for explicitly trusted devices and can
  be disabled in Settings → Transfers.
- Requests are deduped by `request_id` and rate-limited to one per origin per
  2 s. Lifetime is 60 s.

### Files

The file is uploaded once through the v3 chunk path, then a sealed `offer`
adds:

- `open: true`
- `open_request_id: "req-…"`
- `reply_to: "reqdev-…"`
- `mime_type` (extension-derived, parameters stripped)

`sender_id` remains the stable origin so the existing offer trust gate is
unchanged; `reply_to` is a routing hint for the one-shot requester and is
validated as a device id. The target saves the file to `Downloads` exactly as
it does for any other transfer, sends `complete` to `reply_to`, then reports
`open_result` to the same id.

- Files are opened with a `FileProvider` `content://` URI,
  `ACTION_VIEW`, `setDataAndType`, `FLAG_GRANT_READ_URI_PERMISSION`, and
  `ClipData`. `file://` is never used.
- Android 10+ silently blocks background activity starts, and a foreground
  `VpnService` is not an exemption. When the app is not visible the handoff is
  delivered as a high-importance notification whose content intent and
  **Open** action are a system-sent `PendingIntent.getActivity`; that is the
  supported background path. The result status is `notification` and the CLI
  exits `0`.
- Android 15 no longer treats non-visible overlay windows as a launch
  exemption, so the handoff path does not rely on the clipboard overlay or
  `SYSTEM_ALERT_WINDOW`.
- If no viewer is installed, the notification still offers Open/Share; the
  open result is `notification` rather than a silent no-op.
- APKs are ordinary files. The system installer always requires user
  confirmation; gaurPN never auto-installs.
- Directories and multi-file batches are rejected by `open`; use `send`.
- This also fixes the v3 `send --to/--all -open` path: its `open` bit was
  previously ignored by Android, and MIME types are now carried in the offer.
  The legacy v2 `send -open` path uses the same background-safe display.

Exit codes: `0` opened or tap-to-open notification, `1` denied, `2` usage,
`3` timeout, `4` transport/unsupported/failure, `130` interrupted.

---

## 19. Camera Streaming

`gaur-share cam [--loopback /dev/videoN] [--player <cmd>] <device>` asks a trusted
Android phone for a live view of its camera and shows it on the desktop. The
phone answers from a notification; the stream is end-to-end encrypted HEVC and
the relay sees only the session id and ciphertext. Teardown is event-driven on
every side: the viewer closes, the receiver GET ends, the relay closes the
sender's pipe, and the phone stops the camera. No timers anywhere.

### Request and consent

- The request rides the existing sealed `request` envelope with `kind`
  `"camera"` and a 120 s accept window. The grant is the v3.2 request grant.
- The target serves only explicitly trusted devices (the same rule as browse,
  fetch, ask, and open, independent of the only-trusted toggle), the setting
  **Settings → Camera → Allow camera requests** must be on, and the CAMERA
  runtime permission granted. Otherwise the answer is `not_allowed`.
- The phone shows a high-importance Accept/Decline notification that expires
  with the request. Accept starts a foreground service of type `camera`
  (the notification action tap is the Android 14+ user-interaction exemption
  for camera FGS start). **Auto-accept from trusted devices** is a separate
  opt-in.
- One live session per phone: the single request slot is held from accept
  until the stream service releases it. A second request answers `busy`.
  A per-origin 2 s cooldown applies.
- A target that does not know the `camera` kind answers `unsupported` instead
  of dropping the envelope, so new requesters never hang against old targets.
  This decline applies to every unknown request kind, not only camera.

### Session keys

Both sides derive the session deterministically from the request id:

- `session_id = hex(HMAC-SHA256(K_peer, "gaur-cam-session-v1:" + request_id))`
  (64 lowercase hex chars; also the `/pipe` transfer id).
- `K_cam = HMAC-SHA256(K_peer, "gaur-camera-v1:" + session_id)`.

### Transport

The stream rides the existing `/pipe` endpoint; nothing new is staged, stored,
or logged. The phone POSTs a chunked body as `role=sender`
(`/pipe?transfer_id=<session_id>&role=sender`); the desktop GETs as
`role=receiver` with `&flush=1`, which opts the response into a flush after
every write instead of the 100 ms throttle (file receivers are unaffected).
The receiver re-dials on the relay's 60 s rendezvous timeout until the request
expires. A stream that moves no bytes for 5 minutes is swept with the other
idle pipes.

### Frame format

```
[4-byte big-endian L][12-byte nonce][L-28 ciphertext][16-byte GCM tag]
```

- AES-256-GCM with `K_cam`, a fresh nonce per frame.
- AAD: `cam:v1:<session_id>:<frame_index>` — the index is the count of frames
  sent so far (equal to the count received), starting at 0.
- Frame 0 is the HEVC parameter sets (VPS/SPS/PPS, Annex-B); frames 1+ are
  encoder output buffers (Annex-B). `L` above 8 MiB aborts the stream.
- The phone keeps at most two frames in flight; a full queue drops the frame
  and requests a sync frame, so the stream recovers at the next keyframe
  instead of queuing latency.

### Live controls

The desktop sends sealed `cam_control` envelopes over its WebSocket:
`{"request_id","session_id","action","on"?,"tier"?,"ts"}` with `action`
`torch` (on/off), `lens` (front/back), `tier` (switch quality; the encoder
restarts, so a brief hitch is expected), or `stop`. The phone applies a
control only when both ids match the live session. Keys while streaming:
`t` torch, `f` flip lens, `1/2/3` tier, `q` stop.

### Quality tiers

| Tier | Resolution | FPS | Bitrate | Bits/pixel |
| --- | --- | --- | --- | --- |
| `high` (default) | 1920x1080 | 30 | 8 Mbps | 0.129 |
| `balanced` | 1280x720 | 30 | 3.5 Mbps | 0.127 |
| `low` | 640x360 | 30 | 500 kbps | 0.072 |

The default tier and lens are phone settings (**Settings → Camera**); the
desktop can switch the tier live. Expect roughly 85-150 ms glass-to-glass on
a 30 ms-RTT relay path (measured on the production VPS), scaling with the
link. Audio, recording, and a phone-side viewer UI are future work.

### Desktop viewer

- Default: `mpv --no-cache --profile=low-latency --demuxer-lavf-format=hevc
  --hwdec=auto -` reading HEVC from stdin.
- `--player <cmd>` runs your own command with the stream on stdin; a bare
  command name gets `-` appended, an override that already names `-` is used
  verbatim.
- `--loopback /dev/videoN` writes through
  `ffmpeg -f hevc -i - -pix_fmt yuv420p -f v4l2 <dev>` for use as a system
  webcam (requires the `v4l2loopback` module).
- Exit codes mirror `ask`: `0` clean end, `1` declined, `2` usage, `3` expired
  without accept, `4` transport/player failure, `130` interrupted.
