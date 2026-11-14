package main

import (
	"log"
	"sync"
	"time"
)

// replayCapacity bounds how many message ids the guard remembers. When the
// table is full the oldest id is evicted (FIFO), so a duplicate is guaranteed
// to be caught as long as fewer than replayCapacity distinct messages arrived
// in between. Command kinds additionally have a freshness window, so an old
// replay that fell out of the table still cannot act.
const replayCapacity = 2048

// mediaCommandFreshnessMs is how long a sealed media_command stays
// executable. It matches the Android MediaSessionBridge check exactly: a
// one-sided "older than" compare, in milliseconds.
const mediaCommandFreshnessMs = 30_000

// ReplayGuard rejects duplicate messages (bounded msg_id memory) and, for
// command kinds, messages older than a per-kind freshness window. The daemon
// shares one guard across all inbound paths; one-shot CLI commands never
// receive, so they need none.
type ReplayGuard struct {
	mu     sync.Mutex
	seen   map[string]struct{}
	order  []string // insertion order, for FIFO eviction
	now    func() int64
	warned map[string]bool
}

func NewReplayGuard() *ReplayGuard {
	return &ReplayGuard{
		seen:   make(map[string]struct{}, replayCapacity),
		order:  make([]string, 0, 256),
		now:    func() int64 { return time.Now().UnixMilli() },
		warned: make(map[string]bool),
	}
}

// Seen returns true when msgID was already recorded; otherwise it records it.
// An empty id is never recorded.
func (g *ReplayGuard) Seen(msgID string) bool {
	if msgID == "" {
		return false
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.seen == nil {
		g.seen = make(map[string]struct{}, replayCapacity)
	}
	if _, dup := g.seen[msgID]; dup {
		return true
	}
	if len(g.seen) >= replayCapacity {
		oldest := g.order[0]
		g.order = g.order[1:]
		delete(g.seen, oldest)
	}
	g.seen[msgID] = struct{}{}
	g.order = append(g.order, msgID)
	return false
}

// Fresh reports whether a message of the given kind is still acceptable given
// its timestamp in Unix milliseconds. Only command kinds have a window;
// everything else is always fresh. The media_command window is one-sided to
// match Android: a sender clock running behind does not drop the command, but
// anything older than 30 seconds is ignored, so a mailbox replay cannot pause
// or skip a track later.
func (g *ReplayGuard) Fresh(kind string, ts int64) bool {
	if kind != "media_command" {
		return true
	}
	g.mu.Lock()
	now := g.now()
	g.mu.Unlock()
	return now-ts <= mediaCommandFreshnessMs
}

// Allow applies the duplicate and freshness rules to one inbound envelope.
// Envelopes without a msg_id are allowed for compatibility with legacy
// senders; the gap is logged once per kind.
func (g *ReplayGuard) Allow(kind, msgID string, ts int64) bool {
	if msgID == "" {
		g.warnMissingID(kind)
		return true
	}
	if g.Seen(msgID) {
		return false
	}
	return g.Fresh(kind, ts)
}

func (g *ReplayGuard) warnMissingID(kind string) {
	g.mu.Lock()
	first := !g.warned[kind]
	g.warned[kind] = true
	g.mu.Unlock()
	if first {
		log.Printf("[REPLAY] %s envelope has no msg_id; accepting without replay protection", kind)
	}
}

// replayGuard protects every inbound daemon path. It is reset when the daemon
// starts so each run begins with an empty table.
var replayGuard = NewReplayGuard()

// envelopeRelayTs prefers the relay's receive timestamp and falls back to the
// sender's clock, mirroring the Android sealed handler. The relay stamps
// relay_ts when it first receives a message, so a mailbox replay carries the
// original receive time and expires like the live delivery.
func envelopeRelayTs(envelope map[string]interface{}) int64 {
	if v, ok := envelope["relay_ts"]; ok && v != nil {
		if n := numberToInt64(v); n != 0 {
			return n
		}
	}
	return numberToInt64(envelope["ts"])
}
