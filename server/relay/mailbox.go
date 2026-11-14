package main

import (
	"sync"
	"time"
)

type mailboxEntry struct {
	data    []byte
	expires time.Time
}

// Mailbox holds opaque sealed envelopes for peers that are currently offline.
// It is deliberately dumb: it never parses the payload, only the target id and
// size. Entries are evicted by TTL and a global byte cap.
type Mailbox struct {
	mu       sync.Mutex
	entries  map[string][]mailboxEntry
	maxBytes int64
	ttl      time.Duration
	bytes    int64
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
		return
	}
	for m.bytes+int64(len(data)) > m.maxBytes {
		if !m.evictOldestLocked() {
			return
		}
	}
	m.entries[target] = append(m.entries[target], mailboxEntry{
		data:    data,
		expires: time.Now().Add(m.ttl),
	})
	m.bytes += int64(len(data))
}

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
