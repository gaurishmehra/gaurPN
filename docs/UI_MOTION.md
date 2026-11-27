# UI and motion

The Android client uses one visual language, "Bench": graphite planes, warm
hairlines, a single amber live accent, IBM Plex Sans for interface copy, and
Plex Mono for measured values. Motion follows the same rule as the layout: it
explains state, then stops.

This document is the reference for any UI change. `ui/Motion.kt`,
`ui/BenchComponents.kt`, and `ui/Theme.kt` are the implementation.

Three layers carry the language, and each has exactly one source of truth:

| Layer | Source | Rule |
| --- | --- | --- |
| Type | `BenchType` | Six roles. No literal `fontSize` in a screen. |
| Shape | `MaterialTheme.shapes` | Three radii in use: 6, 8, 16 dp. No ad-hoc corners. |
| Icon | `res/drawable/ic_*.xml` | One grammar: 24 dp grid, 1.8 dp stroke, round caps and joins, no fills. |

## Motion tokens

| Token | Value | Used for |
| --- | --- | --- |
| `Instant` | 90 ms | input acknowledgement |
| `Quick` | 160 ms | tints, checkmarks, short label swaps |
| `Base` | 240 ms | in-place state changes |
| `Medium` | 320 ms | a block of content settling |
| `Enter` | 360 ms | arrivals, expand |
| `Exit` | 220 ms | departures, collapse (always shorter than enter) |
| `Screen` | 400 ms | whole-surface navigation |
| `Settle` | 760 ms | the connection dial's one sweep |

Duration is never the only carrier. Every animated change also carries at
least 8 dp of travel or 2% of scale, because plenty of phones ship with the
animator scale at 0.5x and a change that is only a clock is invisible there.

There are four curves and no others. The legacy M2 `Decelerate` and
`Accelerate` were removed: they are the tell of a Compose app built before
Material 3.

- `EmphasizedDecelerate` `(0.05, 0.7, 0.1, 1)` for entrances.
- `EmphasizedAccelerate` `(0.3, 0, 0.8, 0.15)` for exits.
- `Standard` `(0.2, 0, 0, 1)` for colour and layout.
- `Linear` only for continuous rotation.

Springs: `spatial()` is `0.85 / 380` (about 1% overshoot — weight, not bounce),
`spatialFast()` is `0.9 / 700` for short travel inside a row, `press()` is
critically damped and very stiff so it answers the finger instantly, and
`pop()` is `0.55 / 460` for marks that land (the connected dial).

## Rules

1. **One direction per transition.** Hierarchy moves slide horizontally in the
   direction of travel. Top-level tabs slide along the tab order. Siblings at
   the same depth crossfade with a 2% scale, because neither is closer than
   the other.
2. **Exit faster than enter.** A dismissal should feel like relief.
3. **No idle loops.** The Home dial sweeps once on connect and then stops.
   The only repeating animations are the connecting comet and loading
   shimmers; both exist only while that state is active, and both consult
   reduced motion.
4. **No decorative motion.** Every animation answers "what changed?". If it
   does not, delete it.
5. **Read animated values in the draw phase.** Use `graphicsLayer` or
   `Canvas`/`drawBehind`. Do not read per-frame animation state in
   composition.
6. **Respect reduced motion.** Compose scales finite animations, but infinite
   ones bypass the animator scale. `rememberMotionEnabled()` guards shimmers
   and the connecting orbit.
7. **Motion must not hide a state.** The connection ring, the phase word, the
   error banner, and the transfer title all state the truth before they
   animate.
8. **Long travel, short fade.** A screen is only half-visible for a very short
   time. Fades run 100–110 ms while the slide runs 400 ms, so the incoming
   surface is opaque before the eye can resolve the outgoing one behind it.
   Sibling screens use fade-through — the outgoing surface is gone before the
   incoming one starts — never a crossfade of two readable screens.
9. **Never animate a layout dimension on a navigation surface.** Animating the
   size of the nav bar or a bottom bar re-measures the whole screen every
   frame; that is what a dropped frame during navigation actually is. Those
   surfaces snap their size and fade, and the screen transition carries the
   perceived change.
10. **Entrances must not recompose.** Staggered arrivals animate an
   `Animatable` read inside `graphicsLayer`. Driving them from an
   `animateFloatAsState` whose target is a boolean recomposes the whole
   section the moment the flag flips, which lands as one stall per section
   right in the middle of a transition.

## Interaction map

| Interaction | Treatment |
| --- | --- |
| Screen forward / back | 260 ms slide from the direction of travel + 220/150 ms fade |
| Top-level tab change | shared-axis slide along the tab order (20% in, 8% out), 260 ms |
| Same-depth siblings | 220/150 ms crossfade with a 2% scale, in place |
| Nav rail / bottom bar on Browse | 260 ms size + fade, synchronized with the screen |
| Home sections | one-shot 38 ms stagger, 8 dp lift, 220 ms fade |
| Home hero | ring, phase, server, action, and live readout share one surface plane lit from above; the readout only exists while connected |
| Home readout | session clock over a hairline, then Latency / Down / Up as labelled mono cells with hairline gutters |
| Connection phase | fade + 4 px vertical slide |
| Reconnecting | phase label + attempt number + next-retry countdown, updated only while resumed |
| Connect / Disconnect | one button; container, content, and border colours animate over 180 ms |
| Connection dial | connecting: a two-segment comet, 1.3 s per revolution, lifecycle-gated; connected: one 480 ms sweep + 1 s arrival halo, then static |
| Error banner | expand + fade in; retain the text through the collapse |
| Server selection | tint, dot colour, dot scale, and the word "Active" animate; list items use `animateItem` |
| History load | a 120 ms grace before any skeleton, then skeleton → list crossfade, so a fast local read never flashes |
| History row status | the outcome word rides the caption as a coloured span; colour and word animate independently |
| Section headers | one component, sentence case, 48 dp tall, optional trailing count or link |
| Browse folder change | horizontal slide in the direction of depth; cached listings reappear without a loading flash |
| Browse sort change | the whole listing reorders and returns to the top; folders are only pinned ahead of files for name sort |
| Browse sort | name, modified, or size, ascending or descending; rows animate to their new positions |
| Browse selection | tint + press scale; tapping a file starts selection |
| Settings sub-groups | expand/collapse from the top, 220/150 ms |
| Settings switches | optimistic thumb movement, reconciled when the write lands |
| Theme change | all color roles recolor over 300 ms; navigation state is preserved |
| Peer rows | keyed reveal; new devices fade in, removals collapse the section; Send and Browse are explicit buttons — Browse navigates directly, no confirm step |
| Relay link | `connecting`, `online`, `key rejected`, or `relay unreachable` in the Devices header |
| Transfer dialog | phase-keyed title/body crossfade; recipient named in the title; progress smoothed over 180 ms |
| Press | 2% shrink for rows and cards, 3% for buttons, plus a 10% ink ripple |
| Nav / bottom bar toggle | size snaps, 240/160 ms fade; the screen transition carries the change |
| Outcomes | confirm haptic on success/trust, reject haptic on failure/revoke |

## Status colour

The accent is a live signal, not decoration. It is spent on exactly two
things: the connection dial and the primary action.

- A transfer that is **running now** is amber.
- A transfer that **failed** is red.
- A transfer that is **finished** is quiet grey with the word "Done". Twenty
  amber "Done" labels make a list of history look like a list of alarms.
- Destructive controls ("Clear") are quiet at rest. The confirmation dialog
  carries the red.

Status is never encoded by colour alone: the word travels with the colour.

## Insets

The navigation bar owns the bottom inset. Screens stacked above it apply
`statusBarsPadding()` only; applying `systemBarsPadding()` there double-pads
and leaves a dead band above the bar. The rail layout adds
`navigationBarsPadding()` to the content beside the rail, because the rail
consumes its own inset and the screen does not inherit it.

## State coverage

A null flow read is not an empty list. Loading, empty, and error are distinct:

- `HistoryScreen` waits 120 ms before showing a skeleton: a local read that
  lands in a frame is not a loading state, and a one-frame flash is worse
  than a blank frame.
- `ServersScreen` shows a skeleton while keys load.
- `BrowseScreen` distinguishes loading, empty, and failure per directory.
- `SplitTunnelScreen` distinguishes loading, no matches, and the list.
- `ActivitySection` shows a skeleton until history loads.
- The tunnel separates first connect (`CONNECTING`) from backoff recovery
  (`RECONNECTING`) and reports the attempt plus the next retry.
- The relay link is separate from the tunnel: `AUTH_FAILED` and `UNREACHABLE`
  are named states, not an empty device list.

## Accessibility

- The connection phase and the error banner are polite live regions. The
  uptime readout is deliberately not.
- The connection dial is decorative; the phase word carries the state.
- Status is never encoded by colour alone: the ring shape, the word
  "Active", and the transfer phase title are redundant channels.
- Section headings carry `heading()`, so TalkBack can jump between groups.
  Clickable rows carry a role and a click label.
- Touch targets are at least 48 dp for primary actions, section-header links,
  and the row actions on History and Devices. Breadcrumbs in Browse are still
  the weakest point; fix them when that bar is next touched.

## Measured

Verified on a motorola edge 40 neo (1080x2400) with `dumpsys gfxinfo
framestats`, sampling tab switches and scrolling: frames that do draw run at
8–20 ms with 3–6 ms of GPU time, i.e. a clean 60 fps. The residual spikes are
100–150 ms CPU frames on the first composition of a screen, which is
composition cost, not animation cost, and does not show up in the GPU
timings.

## Deliberately not done

These are the known gaps, in priority order:

1. **Predictive back.** `PredictiveBackHandler` is available on this Compose
   version and is still unused; the int-based navigation guesses direction
   from depth. The manifest also has no
   `android:enableOnBackInvokedCallback="true"`.
2. **Non-modal transfer progress.** The transfer dialog is now hosted above
   navigation, so it survives screen changes, but it still blocks input. The
   next step is a dismissible progress surface with Open / Retry /
   waiting-for-accept.
3. **Cluster key naming and verification.** One noun ("cluster key"), a
   fingerprint shown on every device, and an explicit remove action.
4. **Adaptive state preservation.** The Browse path and sort survive
   recreation; Split tunneling still re-queries every app on a fold/unfold.
5. **Grouped settings rows.** Home groups its rows in one plane with
   hairlines; Settings still gives every row its own card. One card per group
   is the next structural change.
6. **Shared element, peer → Browse.** `SharedTransitionLayout` is available
   but experimental; add it only as a single hero moment.
