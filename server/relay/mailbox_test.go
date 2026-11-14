package main

import (
	"bytes"
	"sort"
	"sync"
	"testing"
	"time"
)

func TestMailboxPutTake(t *testing.T) {
	m := NewMailbox(time.Hour, 1<<20)
	m.Put("a", []byte("one"))
	m.Put("a", []byte("two"))
	m.Put("b", []byte("three"))

	got := m.Take("a")
	if len(got) != 2 || string(got[0]) != "one" || string(got[1]) != "two" {
		t.Fatalf("unexpected entries: %q", got)
	}
	if len(m.Take("a")) != 0 {
		t.Fatal("Take should drain the target")
	}
	if len(m.Take("b")) != 1 {
		t.Fatal("other targets must be untouched")
	}
}

func TestMailboxTTL(t *testing.T) {
	m := NewMailbox(10*time.Millisecond, 1<<20)
	m.Put("a", []byte("x"))
	time.Sleep(20 * time.Millisecond)
	if len(m.Take("a")) != 0 {
		t.Fatal("expired entry should be pruned")
	}
}

func TestMailboxByteCap(t *testing.T) {
	m := NewMailbox(time.Hour, 10)
	m.Put("a", []byte("12345"))
	m.Put("b", []byte("67890"))
	m.Put("c", []byte("abcde"))

	_, bytes := m.Stats()
	if bytes > 10 {
		t.Fatalf("mailbox bytes = %d, cap is 10", bytes)
	}
}

func TestMailboxOversizedEntryDropped(t *testing.T) {
	m := NewMailbox(time.Hour, 10)
	m.Put("a", bytes.Repeat([]byte("x"), 11))
	if _, queued := m.Stats(); queued != 0 {
		t.Fatalf("oversized entry was stored: %d bytes queued", queued)
	}
}

func TestMailboxPutEvictsOldestForNewcomer(t *testing.T) {
	m := NewMailbox(time.Hour, 10)
	m.Put("a", []byte("12345"))
	m.Put("b", []byte("67890")) // exactly at cap
	m.Put("c", []byte("abcde")) // must evict the oldest entry ("a")

	if len(m.Take("a")) != 0 {
		t.Fatal("oldest entry should have been evicted for the newcomer")
	}
	if len(m.Take("b")) != 1 || len(m.Take("c")) != 1 {
		t.Fatal("newer entries must survive the eviction")
	}
}

func TestMailboxDrainDeliversAndRemoves(t *testing.T) {
	m := NewMailbox(time.Hour, 1<<20)
	m.Put("a", []byte("one"))
	m.Put("a", []byte("two"))

	var got [][]byte
	m.Drain("a", func(data []byte) bool {
		got = append(got, append([]byte(nil), data...))
		return true
	})
	if len(got) != 2 || string(got[0]) != "one" || string(got[1]) != "two" {
		t.Fatalf("drained = %q, want [one two] in FIFO order", got)
	}
	if len(m.Take("a")) != 0 {
		t.Fatal("acked entries must be removed")
	}
}

func TestMailboxDrainStopsAtFirstFailure(t *testing.T) {
	m := NewMailbox(time.Hour, 1<<20)
	m.Put("a", []byte("one"))
	m.Put("a", []byte("two"))
	m.Put("a", []byte("three"))

	calls := 0
	m.Drain("a", func(data []byte) bool {
		calls++
		return false // every delivery fails
	})
	if calls != 1 {
		t.Fatalf("deliver called %d times, want 1 (drain stops at the first failure)", calls)
	}
	remaining := m.Take("a")
	if len(remaining) != 3 || string(remaining[0]) != "one" || string(remaining[2]) != "three" {
		t.Fatalf("failed drain must keep all entries in order, got %q", remaining)
	}
}

func TestMailboxDrainPartialFailureKeepsOrder(t *testing.T) {
	m := NewMailbox(time.Hour, 1<<20)
	m.Put("a", []byte("one"))
	m.Put("a", []byte("two"))
	m.Put("a", []byte("three"))

	acked := 0
	m.Drain("a", func(data []byte) bool {
		acked++
		return acked == 1 // first write succeeds, second fails
	})
	remaining := m.Take("a")
	if len(remaining) != 2 || string(remaining[0]) != "two" || string(remaining[1]) != "three" {
		t.Fatalf("undelivered entries = %q, want [two three] in order", remaining)
	}
}

func TestMailboxDrainSerializesPerTarget(t *testing.T) {
	m := NewMailbox(time.Hour, 1<<20)
	m.Put("a", []byte("one"))
	m.Put("a", []byte("two"))

	var mu sync.Mutex
	var seen []string
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			m.Drain("a", func(data []byte) bool {
				mu.Lock()
				seen = append(seen, string(data))
				mu.Unlock()
				time.Sleep(10 * time.Millisecond) // widen the race window
				return true
			})
		}()
	}
	wg.Wait()

	sort.Strings(seen)
	if len(seen) != 2 || seen[0] != "one" || seen[1] != "two" {
		t.Fatalf("deliveries = %q, want each entry exactly once", seen)
	}
}
