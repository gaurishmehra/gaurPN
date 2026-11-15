# gaurPN Hardening & Quality Plan

> **STATUS: COMPLETE + DEPLOYED + VERIFIED LIVE** — all phases landed on `main`:
> Phase 0 (WIP committed, 4 slices) · Phase 1 settings IA (`80098a6`) ·
> Phase 2 security, 22 items (`6b2b615`..`00b9fa5`) · Phase 3 reliability, 14 items
> (`7ee4c36`..`d77b548`) · Phase 4 medium, 14 items (`6124a79`..`4f56ccd`) ·
> Phase 5 quality + CI (see git log). Final gates: 109 Android JVM tests,
> relay `go test -race` + vet clean, desktop `go test -race` + vet fully clean.
>
> **v3.2 feature wave (later)**: pull transfers rebuilt as browse+fetch with a
> native GTK picker (`gaur-browse`), encrypted battery/charging telemetry, and
> safe URL opening — two adversarial security reviews passed, all findings
> fixed; Android serves full shared storage behind All-files access
> (`Storage` root). Live-verified end-to-end: battery in peers.json, complete
> Storage listings, and a fetch with matching md5 on both devices. Current
> counts: 308 Android tests, 461 desktop cases, relay -race clean.
>
> **Deployed 2026-11-15** (relay md5 `4faf1df0…` on oracle-1, desktop md5
> `c9877f38…` in `~/.local/bin`, APK v0.4.0 on device) and verified end-to-end:
> sealed text desktop→phone (notification payload confirmed via dumpsys), 3 MiB
> urandom file transfer with matching MD5 `caef105e…` on both ends, MPRIS
> mirroring live, chunk ownership active (no open-mode logs), transfer cleanup
> confirmed (`chunk_bytes: 0`), zero crashes in logcat, settings UI confirmed
> on-device. Not live-tested: mid-transfer airplane-mode resume (covered by
> the desktop fake-relay integration suite instead) and QR pairing (unit-tested).

Execution contract for the fix-up pass. Phases run in order; each phase ends with
the full test matrix green and a push. Features are explicitly out of scope here
(separate conversation after this lands).

**Phase 0 (done):** WIP committed in 4 slices and pushed
(`ce642bc` relay, `362e501` desktop, `8319054` android, `e28d6de` docs).

---

## Ground rules (apply to every phase)

1. **Wire changes ship together.** Any envelope/endpoint change lands in
   `server/relay`, `desktop-client`, and `app` in the same push, with
   `docs/FILE_SHARING.md` + `README.md` updated in the same commit series.
2. **Backward compat:** installed APKs and the old desktop binary keep working
   during each transition. New relay rules accept legacy requests (open mode)
   and log a deprecation; new clients always send the new fields.
3. **Quickshell contracts stay stable:** `peers.json` schema, clipboard socket
   NDJSON shape, and CLI flags do not change in this plan.
4. **Test matrix (gate for every phase):**
   - `./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug`
   - `cd server/relay && go test -race ./... && go vet ./...`
   - `cd desktop-client && go test -race ./... && go vet ./...`
5. **Commit style:** `fix(security): ...`, `feat(ui): ...`, `fix: ...`,
   `refactor: ...`, `docs: ...` — one commit per coherent cluster, push per phase.
6. **No new dependencies** except test-only. No telemetry, no storage, no
   plaintext on the relay — per AGENTS.md invariants.

Sizing: **S** ≤ half day, **M** ~a day, **L** multi-day.

---

## Phase 1 — Android Settings information architecture (`feat(ui)`)

**Problem:** `SettingsScreen.kt` is one ~940-line composable with 5 inline
dialogs and a flat list of 20+ rows (`ui/SettingsScreen.kt:80-1022`).

**Target structure** (all under `ui/settings/`):

```
SettingsScreen.kt        — router: section cards, screen navigation
SettingsComponents.kt    — SettingsSection card, SettingsRow, GaurSwitch (kills the 12× SwitchDefaults duplication)
sections/
  ConnectionSection.kt    — keep-alive, start on boot, block-on-reconnect, battery
  NetworkSection.kt      — DNS entry, split tunneling entry
  ClusterSection.kt      — device name, relay address, cluster key, trusted devices entry, transfers entry
  AppearanceSection.kt   — theme
screens/
  DnsScreen.kt           — mode selector + custom resolver editor (moves existing dialog :888)
  ClipboardSyncScreen.kt — enable, overlay permission, sensitive clips, per-peer exclusions, last activity (moves :456-570)
  NotificationsScreen.kt  — share notifications, access, own-notification filter, channel (moves :572-670)
  MediaScreen.kt         — share media controls, media session, access (moves :672-750)
  TransfersScreen.kt     — auto-open, transfer history entry (moves :423-455)
dialogs/
  TextEditDialog.kt      — shared device-name / relay / token / DNS editor (dedupes 4 dialogs)
  ThemeDialog.kt, LanWarningDialog.kt
```

- Keep `MainActivity`'s int-based routing; add screen constants. No ViewModels
  (matches existing state hoisting via repositories).
- `GaurSwitch` wrapper removes the 12× `SwitchDefaults.colors(...)` repetition.
- Settings count on the home screen drops from ~24 rows to ~12 rows in 4 cards.

**Tests**
- Build gate: `assembleDebug` + all existing unit tests green.
- New `SettingsRouteTest` (pure): route constants are unique, every row action
  maps to a route — JVM-only, no Robolectric (matches repo test style).
- Manual smoke checklist item A1 (see bottom).

**Commit:** `feat(ui): restructure settings into sections and submenus`

---

## Phase 2 — Security (`fix(security)`)

### Android app

| ID | Fix | Where | Size |
|----|-----|-------|------|
| SEC-1 | **Pairing confirmation.** Extract pure `PairingRequest.parse(uri)`; `handlePairingIntent` shows a confirmation dialog (relay addr, masked token, DNS, name) before writing anything. | `MainActivity.kt:297-322` | S |
| SEC-2 | **Trust gate on unsealed paths.** Extract pure `InboundPolicy(senderId, kind, trustSet, onlyTrusted)`; enforce in `handleClipboardUpdate`, `handleNotificationEvent`, `handleNotificationAction` (text already checks at `:434`). Sealed path already checks (`:916`). | `RelayManager.kt:327,512,551` | S |
| SEC-3 | **FileProvider scope.** `external-path path="."` → `path="Download/"`. | `res/xml/file_paths.xml` | S |
| SEC-4 | **Cleartext containment.** (a) `network_security_config.xml`: base cleartext off, cleartext on for the relay host; verify on device before landing. (b) Pure `isInTunnelRelayHost(host)` guard in `RelayManager`/settings: refuse `ws://` to public hosts with explicit override. | `AndroidManifest.xml:27`, `RelayManager.kt:68` | M |
| SEC-5 | **Lock-screen privacy.** `VISIBILITY_SECRET` on text-share and mirrored notifications (media stays public). | `TextShareNotifications.kt:50`, `NotificationMirrorManager.kt:276` | S |
| SEC-6 | **Token out of URLs.** WS auth via `X-Gaur-Token` header (relay accepts it already, `relay/main.go:240-242`); same for v1 pipe URLs both platforms. Relay keeps query fallback for old clients. | `RelayManager.kt:136`, `desktop/main.go:289,939`, `relay/main.go:365` | M |
| SEC-7 | **Secrets at rest.** `SecretBox` interface + AndroidKeyStore AES-GCM impl; `KeyRepository` wraps cluster token + SS passwords, migrates plaintext on first read. Injectable fake for tests. | `KeyRepository.kt:36,319` | L |
| SEC-8 | **Stable key IDs.** `AccessKey.id` uses SHA-256(password) hex instead of `String.hashCode()`; one-time migration remaps stored active ID. | `AccessKey.kt:15` | S |
| SEC-9 | **v1 offer tag binding.** Bind `file_name`, `file_size`, chunk params into the v1 offer tag (v2 already does). Desktop v1 receiver verifies the same string. Wire-breaking for old pairs — acceptable, v1 is legacy; document in FILE_SHARING.md. | `RelayManager.kt:1114`, `desktop/main.go:852` | S |

### Desktop client

| ID | Fix | Where | Size |
|----|-----|-------|------|
| SEC-10 | **Sealed target check.** `openSealed` + `handleIncomingSealed` require `target_id == cfg.DeviceID`. | `sealed.go:88-125,297-322` | S |
| SEC-11 | **Replay protection.** `ReplayGuard`: msg_id LRU dedup + freshness windows (media commands 30 s per docs; text/notifications bounded staleness). Wire into text, notification, sealed, media-command handlers. | `main.go:752-819`, `localmedia.go:342-391` | M |
| SEC-12 | **Local UI hardening.** Host header allowlist (bind addr), Origin check (reject cross-origin), 403s. Kills DNS-rebinding + browser form CSRF. | `ui.go:19-100` | M |
| SEC-13 | **notify-send arg injection.** `--` separator + leading-dash rejection. | `notification.go:202-291`, `main.go:240-248` | S |
| SEC-14 | **Token file perms.** Warn + refuse world/group-readable key file. | `main.go:199-209` | S |
| SEC-15 | **Clipboard socket.** chmod immediately after listen; SO_PEERCRED same-UID check on Linux. Message shape unchanged (Quickshell contract). | `main.go:571-626` | M |

### Relay

| ID | Fix | Where | Size |
|----|-----|-------|------|
| SEC-16 | **Transfer ownership.** First PUT records creator `device_id`; chunk GET/PUT/DELETE/status allowed for creator or a `cap` param = `HMAC(K_peer, "gaur-cap-v1:"+tx+":"+session)` (relay derives K_peer from the master token it already holds). Legacy requests without owner/cap stay open-mode + deprecation log. Honest boundary documented: cluster members can still forge caps — full fix is per-device keys (v4 feature, later). | `relay/main.go:499-594` | L |
| SEC-17 | **Pipe role guard.** One sender + one receiver per `transfer_id`; duplicate role → 409. | `relay/main.go:374-392` | S |
| SEC-18 | **`/health` race.** Use `peersSnapshot()` under the lock. | `relay/main.go:677` | S |
| SEC-19 | **Input limits.** `device_id` ≤ 64, `name` ≤ 128, max peers (flag, default 32), auth-failure token bucket → 429. | `relay/main.go:237-275` | M |
| SEC-20 | **Strip sender identity on sealed forward** (defense-in-depth; relay currently forwards client-supplied `from_id` verbatim). | `relay/main.go:320-336` | S |
| SEC-21 | **loadToken error handling** — log read errors, fail fast when neither file nor env is present. | `relay/main.go:597-605` | S |
| SEC-22 | **Bind guard.** Refuse to start unless the bind IP is assigned to a loopback interface (covers `198.51.100.1` on `lo`; `0.0.0.0` hard-fails). Enforces the "loopback only" invariant in code. | `relay/main.go:617` | S |

**Tests (Phase 2)**
- JVM: `PairingRequestTest` (parse/reject vectors), `InboundPolicyTest` (table:
  trusted/untrusted × sealed/unsealed × only-trusted on/off), `AccessKeyTest`
  (id stability, uniqueness, migration), `isInTunnelRelayHostTest`,
  `SettingsRouteTest` stays green.
- Relay: header-auth on `/ws` + `/pipe`; sealed forward strips identity and
  stamps only `relay_ts`; owner/cap access matrix (creator ✓, cap ✓, other
  device ✗, legacy open-mode ✓); duplicate pipe role → 409; `/health` under
  churn with `-race`; limiter + caps; bind-guard unit test over fake interfaces.
- Desktop: `sealed_test` misdelivery case; `ReplayGuardTest` table; UI server
  httptest (Origin/Host spoof → 403, legit → 200); notify-send arg builder;
  token perm check; socket peer-cred (linux).
- Docs: FILE_SHARING.md gains "v3.1 integrity rules" + owner/cap section;
  README security model bullets updated.

**Commits:** `fix(security): android pairing, trust gate, and storage hardening`
· `fix(security): desktop replay, sealed target, and ui auth`
· `fix(security): relay ownership caps, role guard, limits, and bind guard`
· `docs: protocol v3.1 integrity rules`

---

## Phase 3 — High reliability (`fix: ...`)

| ID | Fix | Where | Size |
|----|-----|-------|------|
| REL-1 | **Desktop half-open hang.** `SetReadDeadline(60 s)` in the daemon read loop; ping failure closes the conn so the reconnect loop takes over (ping every 20 s ⇒ 3 missed pongs). | `desktop/main.go:341-354,373` | M |
| REL-2 | **Relay WS deadlines.** Server ping every 30 s, `SetReadDeadline(75 s)`, `SetWriteDeadline(15 s)` → stale peers drop, blocked writers unblock. | `relay/main.go:296-358` | M |
| REL-3 | **Android WS reconnect.** Exponential backoff 2 s→30 s with jitter (pure `Backoff` calc), single-flight `connectRelay` (atomic CAS kills the double-socket race at `:131`). | `RelayManager.kt:131,173-182` | S |
| REL-4 | **v3 resume.** Sealed offers reuse the v2 `.part`/bitmap machinery (same file ⇒ same content-addressed `transfer_id`), both platforms; reset state only on `file_hash` mismatch. Biggest user-visible fix: no more restart-from-zero. | `RelayManager.kt:1004-1009,1081`, `sealed.go:410-415` | L |
| REL-5 | **Mailbox re-queue.** Delivery is ack-based; failed WS write re-queues at head. Sealed forward failure → mailbox. | `relay/main.go:281-283,330-334` | M |
| REL-6 | **Relay graceful shutdown.** SIGTERM/SIGINT → `srv.Shutdown(10 s)` → `hub.Close()` + `chunks.Close()`; no `log.Fatalf` path skips defers. | `relay/main.go:654,693-695` | S |
| REL-7 | **TunnelService memory model.** `@Volatile` on `userStopped`, `activeConfig`, `failedAttempts`, `probeFailures`; `goTunnel` confined or atomic; `START_REDELIVER_INTENT` only for the connect action. | `TunnelService.kt:56-84,150` | S |
| REL-8 | **Cancel transfers on tunnel stop.** `RelayManager.stop()` cancels `sendJob` + `activeTransfers` via a small `TransferRegistry` (map + cancel, injectable for tests); history rows marked cancelled. | `RelayManager.kt:2006-2016` | M |
| REL-9 | **v1 pipe receive cancellable** — run under the registry's cancel scope instead of a bare OkHttp enqueue. | `RelayManager.kt:1193` | S |
| REL-10 | **Notification ID space.** 24-bit mask + per-transfer stable base from `transferId` hash — no cross-transfer collisions. | `RelayManager.kt:1953-1956` | S |
| REL-11 | **`onlyTrustedEnabled` caching.** Cache config with mtime invalidation instead of re-reading config.json per message. | `desktop/trust.go:151-189` | S |
| REL-12 | **`runSendV3` retries.** 3 attempts with fresh `session_id`; replace `log.Fatalf` on transfer failure with error return. | `sealed.go:184-186` | S |
| REL-13 | **peers.json error handling** — log write failures, keep daemon alive. Schema unchanged. | `desktop/main.go:155-167` | S |
| REL-14 | **Clipboard socket lifecycle.** Unlink on shutdown + startup (stale socket today). | `desktop/main.go:580,1896-1905` | S |

**Tests (Phase 3)**
- **New `relayharness` test package (relay):** spin the real handlers via
  `httptest` — this becomes the shared integration harness.
- Desktop integration tests against the harness: half-open server → daemon
  reconnects ≤ deadline; v3 send → kill mid-download → resume → hash verified;
  `runSendV3` retry count; peers.json failure logs.
- Relay tests: WS ping/deadline drop; mailbox ack/re-queue; graceful shutdown
  drains; `-race` across all.
- JVM: `BackoffTest` (bounds, jitter, reset), `TransferRegistryTest`
  (register/cancel/concurrent), `notifIdFor` uniqueness across 10k transfers.
- Manual checklist A2–A4.

**Commits:** `fix: websocket liveness across relay, desktop, and android`
· `fix(share): resumable sealed v3 transfers`
· `fix: relay shutdown, mailbox delivery, and android transfer lifecycle`

---

## Phase 4 — Medium (`fix: ...`)

| ID | Fix | Where |
|----|-----|-------|
| MED-1 | Hard chunk byte-cap: reject PUTs for *new* transfers when the store is at cap (507); active transfers may finish (bounded by 65,536 chunks). | `relay/chunks.go:122-138` |
| MED-2 | Disk hygiene: remove `.tmp` on write failure; map I/O errors → 500/507, oversized → 413 (today everything is 400). | `relay/chunks.go:194-203`, `main.go:518-520` |
| MED-3 | `Present` refreshes `t.last` (status-only receivers no longer TTL-evicted mid-use). | `relay/chunks.go:254-268` |
| MED-4 | Mailbox background TTL sweeper (today pruning is lazy). | `relay/mailbox.go` |
| MED-5 | Mailbox per-target byte cap (flag, default 4 MiB). | `relay/mailbox.go` |
| MED-6 | `NewChunkStore` refuses to wipe a non-empty dir without a `.gaur-relay-chunks` marker; writes the marker on init. | `relay/chunks.go:67-72` |
| MED-7 | Desktop part/state files 0600, inbox 0700 (today 0644/0755). | `desktop/main.go:949,318` |
| MED-8 | `ResolveUniqueFile` TOCTOU → link-based commit (`os.Link` fails if target exists → next suffix), fallback loop for link-less filesystems. | `desktop/crypto.go:329-345` |
| MED-9 | `discoverTarget`: exact/prefix match, ambiguity → error listing candidates. | `desktop/main.go:1582-1584` |
| MED-10 | `runList` JSON mode reports errors (exit code + stderr) instead of `[]`. | `desktop/main.go:1294-1296` |
| MED-11 | Clipboard debug log redaction (clip descriptions/URIs at debug level). | `ClipboardSyncManager.kt:292-296` |
| MED-12 | SplitTunnelScreen: modern query API + lazy icon loading. | `SplitTunnelScreen.kt:78-101,84` |
| MED-13 | Verify on device: direct-path writes to public Downloads on API 30+; migrate to `MediaStore.Downloads` if flaky. Also confirm the `8.8.8.8` fallback in `TunnelService.kt:528` is a probe target, not a resolver. | `RelayManager.kt:1000` |
| MED-14 | `gaur-share ui -addr` non-loopback warning. | `desktop/ui.go:87` |

**Tests:** relay cap/eviction/sweeper/marker/error-mapping unit tests; desktop
perm assertions, concurrent-completion test for MED-8, discoverTarget
ambiguity table, runList error paths. Manual checklist A5.

**Commits:** `fix: relay storage caps, eviction, and error mapping`
· `fix: desktop file handling and target resolution`

---

## Phase 5 — Code quality & test infrastructure (`refactor: ...`)

| ID | Item |
|----|------|
| QL-1 | **Decompose `RelayManager.kt` (2,049 lines)** into `RelayConnection` (WS lifecycle/backoff), `InboundRouter` (dispatch + `InboundPolicy`), `TransferEngine` (v1/v2/v3 offer flows), `OutboundSender`, `TransferRegistry`, `NotificationIds`. Facade keeps the public API; extracted units get unit tests. |
| QL-2 | **Split desktop `main.go` (2,045 lines)** → `daemon.go`, `send.go`, `receive.go`, `target.go`, `peers.go`. Behavior-free; existing tests stay green. |
| QL-3 | **Dedup:** relay sealed/legacy forward → one `forward()`; Android `ensureNotificationChannel` ×5 → shared helper; progress/speed formatting ×4 → helper; battery intent ×2; `onShare` lambda ×2; `TransferDialog` branches; desktop `collectSendMetas` vs `runSend` walk; `runNotify`/`runNotifyAll` boilerplate; V1/V2 receiver shared offer processor. |
| QL-4 | **Dead code purge:** `RelayManager.CHUNK_SIZE` (+ stale 256 KiB comment), `openInputStream`, `sendFile`, `ClipboardSyncManager.stop` (wire it or drop), `TransferState.Connecting` (produce or drop), desktop `uploadRetries`, `EncryptChunk/DecryptChunk`, `json.Number` branch, `min/max` shadowing builtins, `var _ = fmt.Sprintf` keepers, stale `desktop-client/gaur-share` binary from disk. |
| QL-5 | **`go vet` clean:** route MPRIS `Seek` through `ExportMethodTable` so the method name stops colliding with `stdmethods`. |
| QL-6 | **Magic numbers → named constants** in both Go modules + Kotlin (timeouts, caps, notification bases, suppression windows). |
| QL-7 | **Relay chunk-route mux extracted** so `main.go` and `chunks_test.go` share one router (no drift). |
| QL-8 | **Typed envelope access** on desktop where safe (sealed stays raw map for verbatim forwarding). |
| QL-9 | **CI workflow** (`.github/workflows/ci.yml`): JDK 21 + Android SDK unit tests, `go test -race` + `go vet` both modules, on push/PR. |
| QL-10 | **Docs sync pass:** FILE_SHARING.md + README reflect every behavior change made in Phases 1-5; version notes for v1 tag change and owner/cap. |

**Tests:** full matrix + `-race` in CI; coverage of extracted units; wire-format
golden vectors extended (v1 tag, cap tag) pinning Android ↔ desktop.

**Commits:** `refactor: decompose relay manager and desktop main`
· `refactor: dedup shared helpers and purge dead code`
· `ci: add test workflow` · `docs: sync protocol reference`

---

## Test matrix summary

| Suite | Command | New in this plan |
|-------|---------|------------------|
| Android unit | `./gradlew :app:testDebugUnitTest` | PairingRequest, InboundPolicy, AccessKey/migration, Backoff, TransferRegistry, notifIds, relay-host guard |
| Android build | `./gradlew :app:assembleDebug` | gate every phase |
| Relay | `go test -race ./...` + `go vet` | header auth, owner/cap matrix, role guard, deadlines, mailbox ack, health-under-churn, caps/sweeper/marker, shutdown drain, harness package |
| Desktop | `go test -race ./...` + `go vet` (clean after QL-5) | ReplayGuard, sealed misdelivery, UI auth, half-open reconnect, v3 resume integration, runSendV3 retry, discoverTarget, runList, concurrent rename |
| Cross-platform | golden wire vectors | v1 tag, cap tag pinned in both clients' tests |
| CI | `.github/workflows/ci.yml` | all of the above on push |

## On-device manual checklist (can't be automated here)

- **A1 Settings:** every section/submenu opens, every toggle persists across app restart, all 5 dialogs still work, split tunnel + trusted devices navigation.
- **A2 Liveness:** airplane-mode toggle mid-transfer → resume completes (v3); relay restart (`ssh oracle-1 'sudo systemctl restart gaur-relay'`) → phone + desktop reconnect ≤ 30 s, offline mailbox drains on reconnect.
- **A3 Security UX:** pairing link from camera → confirmation dialog appears; untrusted device cannot push clipboard/notification; lock screen hides mirrored text.
- **A4 Storage:** received file lands in `Download/`, opens from history; resume `.part` survives app kill.
- **A5 Medium:** notification progress shows distinct IDs for 2 parallel transfers; desktop `gaur-share ui` reachable from browser, cross-origin form POST rejected.

## Explicitly out of scope (next conversation)

Incoming-transfer prompts, safety-number UI, per-device identity keys (v4),
pull transfers, QR scanner, widget, gallery save, relay stats — the feature
roadmap from the audit. Per-device keys (v4) supersede the owner/cap trust
boundary noted in SEC-16.
