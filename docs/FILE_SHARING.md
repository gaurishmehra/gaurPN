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
* **Sequential Nonce Integrity:** Every 64 KB chunk uses a 96-bit nonce containing a strict sequential counter (`[00 00 00 00][counter]`). Receivers reject any out-of-order, dropped, or duplicated chunks.
* **Safety Numbers:** Ephemeral public keys and transfer IDs generate a deterministic 30-digit numeric fingerprint (6 blocks of 5 decimal digits) for out-of-band identity verification.
* **Path Traversal & Collision Protection:** Filenames are sanitized against directory traversal attacks, received streams are staged into hidden `.part` files until GCM tag verification succeeds, and duplicate names resolve automatically to numbered files (`file (1).ext`) without interrupting automatic file acceptance.
* **In-Memory Zero-Knowledge VPS:** The relay daemon on the VPS binds exclusively to `127.0.0.1:9876` (localhost loopback). It pipes streams in memory via `io.Pipe`, never writes payloads to disk, and cannot decrypt file contents.

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
User=ubuntu
ExecStart=/opt/gaur-relay/gaur-relay -bind 127.0.0.1:9876 -token-env GAURPN_CLUSTER_TOKEN
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
  "relay_address": "127.0.0.1:9876",
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
gaur-share send "Pixel 8" ~/Documents/report.pdf
```
*Output:*
```text
Target located: Pixel 8 (phone-a81f3b)
Sent transfer offer for 'report.pdf' (14.20 MB). Waiting for acceptance...
Transfer accepted! Ephemeral E2EE session verified. Safety number: 63877 21084 56392 35848 28620 54908
Streaming: [██████████████████████████████] 100.0% (14.2/14.2 MB) - 48.2 MB/s
Transfer successfully completed in 0.3s!
```

---

## 4. Android Client (`gaurPN`)

1. Open `gaurPN` and connect to your Outline VPN.
2. In **Settings**, tap **File Sharing Settings** to set your device name and paste your `Cluster Token`.
3. On the **Home Screen**, a **"Cluster Devices"** card appears below the VPN status ring showing online peers.
4. Tap any device to pick a file and initiate instant transfer.
5. Incoming transfers display an Android notification with progress and an action to open the file directly from `Downloads/`.
