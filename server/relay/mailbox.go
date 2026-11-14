package main

import (
	"log"
	"sync"
	"time"
)

type mailboxEntry struct {
	// id identifies this exact entry, so delivery can acknowledge the
	// entry it actually delivered even while concurrent Puts, TTL
	// pruning, or byte-cap eviction reshape the queue underneath it.
	id      uint64
	data    []byte
	expires time.Time
}

// Mailbox holds opaque sealed envelopes for peers that are currently
// offline. It is deliberately dumb: it never parses the payload, only the
// target id and size. Entries are evicted by TTL and a global byte cap.
type Mailbox struct {
	mu       sync.Mutex
	entries  map[string][]mailboxEntry
	maxBytes int64
	ttl      time.Duration
	bytes    int64
	seq      uint64
	// drains holds one *sync.Mutex per target so Drain calls for the
	// same device serialize: a reconnecting device can never race its
	// own dying connection into duplicate deliveries.
	drains sync.Map // target -> *sync.Mutex
}

func NewMailbox(ttl time.Duration, maxBytes int64) *Mailbox {
	if ttl <= 0 {
		ttl = time.Hour
	}
	if maxBytes <= 0 {
		maxBytes = 16 << 20
	}
	return &Mailbox{
		entries:  make(map[string][]mailboxEntry),
		maxBytes: maxBytes,
		ttl:      ttl,
	}
}

func (m *Mailbox) Put(target string, data []byte) {
	if target == "" || len(data) == 0 {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pruneLocked()
	if int64(len(data)) > m.maxBytes {
		// A single entry larger than the cap can never fit; drop it
		// rather than evicting the whole queue for one message.
		log.Printf("[MAILBOX] Dropped %d-byte entry for %s: exceeds %d-byte cap", len(data), target, m.maxBytes)
		return
	}
	for m.bytes+int64(len(data)) > m.maxBytes {
		if !m.evictOldestLocked() {
			return
		}
	}
	m.seq++
	m.entries[target] = append(m.entries[target], mailboxEntry{
		id:      m.seq,
		data:    data,
		expires: time.Now().Add(m.ttl),
	})
	m.bytes += int64(len(data))
}

// Take removes and returns all queued entries for target in FIFO order.
func (m *Mailbox) Take(target string) [][]byte {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pruneLocked()
	entries := m.entries[target]
	if len(entries) == 0 {
		return nil
	}
	delete(m.entries, target)
	out := make([][]byte, 0, len(entries))
	for _, entry := range entries {
		m.bytes -= int64(len(entry.data))
		out = append(out, entry.data)
	}
	return out
}

// Drain delivers queued entries to deliver in FIFO order, removing each
// entry only after deliver reports success. It stops at the first
// failure, so undelivered entries — and anything queued meanwhile — stay
// intact and in order for the next join. deliver runs without the mailbox
// mutex held, so a slow or wedged client cannot stall concurrent Puts.
// Drains for one target are serialized.
func (m *Mailbox) Drain(target string, deliver func(data []byte) bool) {
	lock, _ := m.drains.LoadOrStore(target, &sync.Mutex{})
	drainMu := lock.(*sync.Mutex)
	drainMu.Lock()
	defer drainMu.Unlock()
	for {
		data, id, ok := m.peek(target)
		if !ok {
			return
		}
		if !deliver(data) {
			return
		}
		m.remove(target, id)
	}
}

// peek returns a copy of the oldest live entry for target and its id.
func (m *Mailbox) peek(target string) ([]byte, uint64, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pruneLocked()
	entries := m.entries[target]
	if len(entries) == 0 {
		return nil, 0, false
	}
	head := entries[0]
	data := make([]byte, len(head.data))
	copy(data, head.data)
	return data, head.id, true
}

// remove drops the entry with the given id and reports whether it was
// still queued. TTL pruning or byte-cap eviction may have removed it
// first, which is fine: an expired entry need not be delivered.
func (m *Mailbox) remove(target string, id uint64) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	entries := m.entries[target]
	for i, e := range entries {
		if e.id != id {
			continue
		}
		m.bytes -= int64(len(e.data))
		entries = append(entries[:i], entries[i+1:]...)
		if len(entries) == 0 {
			delete(m.entries, target)
		} else {
			m.entries[target] = entries
		}
		return true
	}
	return false
}

func (m *Mailbox) Stats() (peers int, bytes int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.pruneLocked()
	return len(m.entries), m.bytes
}

func (m *Mailbox) pruneLocked() {
	now := time.Now()
	for target, entries := range m.entries {
		kept := entries[:0]
		for _, entry := range entries {
			if entry.expires.After(now) {
				kept = append(kept, entry)
			} else {
				m.bytes -= int64(len(entry.data))
			}
		}
		if len(kept) == 0 {
			delete(m.entries, target)
		} else {
			m.entries[target] = kept
		}
	}
}

func (m *Mailbox) evictOldestLocked() bool {
	var oldestTarget string
	var oldestIndex int
	var oldest time.Time
	for target, entries := range m.entries {
		for i, entry := range entries {
			if oldest.IsZero() || entry.expires.Before(oldest) {
				oldest = entry.expires
				oldestTarget = target
				oldestIndex = i
			}
		}
	}
	if oldestTarget == "" {
		return false
	}
	entries := m.entries[oldestTarget]
	m.bytes -= int64(len(entries[oldestIndex].data))
	entries = append(entries[:oldestIndex], entries[oldestIndex+1:]...)
	if len(entries) == 0 {
		delete(m.entries, oldestTarget)
	} else {
		m.entries[oldestTarget] = entries
	}
	return true
}
