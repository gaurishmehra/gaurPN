package main

import (
	"fmt"
	"testing"
	"time"
)

func TestReplayGuardRejectsDuplicateMsgID(t *testing.T) {
	g := NewReplayGuard()
	now := time.Now().UnixMilli()
	if !g.Allow("text", "msg-1", now) {
		t.Fatal("first delivery must be allowed")
	}
	if g.Allow("text", "msg-1", now) {
		t.Fatal("a duplicate msg_id must be rejected")
	}
	if !g.Allow("text", "msg-2", now) {
		t.Fatal("a new msg_id must be allowed")
	}
}

func TestReplayGuardEvictsOldestAtCapacity(t *testing.T) {
	g := NewReplayGuard()
	g.now = func() int64 { return 1_000_000 }
	for i := 0; i < replayCapacity+1; i++ {
		if !g.Allow("text", fmt.Sprintf("msg-%d", i), 0) {
			t.Fatalf("insert %d must be allowed", i)
		}
	}
	// Ids in the middle of the flood are still remembered.
	if !g.Seen("msg-1000") {
		t.Fatal("recent ids must still be remembered")
	}
	// The oldest id was evicted, so it counts as new again...
	if g.Seen("msg-0") {
		t.Fatal("the oldest id must have been evicted after replayCapacity+1 inserts")
	}
	// ...and is now recorded once more.
	if !g.Seen("msg-0") {
		t.Fatal("after eviction the id must count as new")
	}
}

func TestReplayGuardMediaCommandFreshness(t *testing.T) {
	g := NewReplayGuard()
	now := time.Now().UnixMilli()

	cases := []struct {
		name string
		kind string
		ts   int64
		want bool
	}{
		{"fresh media command", "media_command", now, true},
		{"media command at the boundary", "media_command", now - mediaCommandFreshnessMs, true},
		{"media command past the window", "media_command", now - mediaCommandFreshnessMs - 1, false},
		{"media command without a timestamp", "media_command", 0, false},
		{"future-dated media command", "media_command", now + time.Hour.Milliseconds(), true},
		{"old text has no freshness window", "text", now - time.Hour.Milliseconds(), true},
		{"old notification has no freshness window", "notification", now - time.Hour.Milliseconds(), true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := g.Fresh(tc.kind, tc.ts); got != tc.want {
				t.Fatalf("Fresh(%q, %d) = %v, want %v", tc.kind, tc.ts, got, tc.want)
			}
		})
	}
}

func TestReplayGuardFreshMediaCommandAllowedAndStaleRejected(t *testing.T) {
	g := NewReplayGuard()
	now := time.Now().UnixMilli()
	if !g.Allow("media_command", "cmd-fresh", now) {
		t.Fatal("a fresh media command must be accepted")
	}
	if g.Allow("media_command", "cmd-stale", now-mediaCommandFreshnessMs-1) {
		t.Fatal("a media command older than 30 seconds must be rejected")
	}
}

func TestReplayGuardAllowsMissingMsgID(t *testing.T) {
	g := NewReplayGuard()
	now := time.Now().UnixMilli()
	if !g.Allow("text", "", now) {
		t.Fatal("a missing msg_id must be allowed for legacy senders")
	}
	if !g.Allow("text", "", now) {
		t.Fatal("a missing msg_id is always allowed (legacy path)")
	}
	// The empty id is never recorded, so it cannot poison real ids.
	if !g.Allow("text", "msg-real", now) {
		t.Fatal("a real msg_id must still be allowed")
	}
}

func TestHandleIncomingTextDropsReplay(t *testing.T) {
	peerKey := []byte("peer-key-material")
	cfg := Config{DeviceID: "desktop-1"}
	msgID := "text-replay-1"
	// Record the id as if the message had already been delivered once.
	replayGuard.Seen(msgID)

	// The envelope is structurally valid but carries undecryptable bytes: the
	// replay check must fire before decryption, so the garbage is never
	// touched and the authentication-failure path is never reached. The
	// stable sender identity is this device so the trust filter (which reads
	// the developer's real config) cannot interfere with the test.
	envelope := map[string]interface{}{
		"type":      "text_message",
		"v":         float64(1),
		"sender_id": cfg.DeviceID,
		"from_id":   "phone-1",
		"from_name": "Pixel",
		"msg_id":    msgID,
		"ts":        float64(time.Now().UnixMilli()),
		"kind":      "text",
		"nonce":     "bm9uY2UtdGV4dC0x",
		"data":      "aW52YWxpZA==",
	}

	out := captureLog(func() {
		handleIncomingText(cfg, peerKey, envelope)
	})
	if want := "Dropped replayed text message"; !contains(out, want) {
		t.Fatalf("expected %q in log, got: %s", want, out)
	}
	if contains(out, "Authentication failed") {
		t.Fatalf("replay must be dropped before decryption: %s", out)
	}
}
