package main

import (
	"log"
	"sync"
	"time"
)

// Mailbox defaults. Centralized here so the flags in main and the
// NewHub fallback cannot drift from the constructor's own defaults.
const (
	defaultMailboxTTL            = time.Hour
	defaultMailboxMaxBytes       = 16 << 20
	defaultMailboxMaxTargetBytes = 4 << 20
)

// mailboxSweepInterval is how often the background sweeper prunes
// expired entries. Package var so tests can shrink it; the sweeper
// copies the current value when Start is called.
var mailboxSweepInterval = 30 * time.Second

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
// target id and size. Entries are evicted by TTL, a global byte cap, and
// a per-target byte cap so one offline device cannot monopolize the
// queue.
type Mailbox struct {
	mu             sync.Mutex
	entries        map[string][]mailboxEntry
	maxBytes       int64
	maxTargetBytes int64
	ttl            time.Duration
	bytes          int64
	seq            uint64
	// drains holds one *sync.Mutex per target so Drain calls for the
	// same device serialize: a reconnecting device can never race its
	// own dying connection into duplicate deliveries.
	drains sync.Map // target -> *sync.Mutex
	// done stops the background sweeper. Start and Close are
	// once-guarded so repeated calls are safe and Close works even
	// before Start.
	done      chan struct{}
	startOnce sync.Once
	closeOnce sync.Once
}

func NewMailbox(ttl time.Duration, maxBytes, maxTargetBytes int64) *Mailbox {
	if ttl <= 0 {
		ttl = defaultMailboxTTL
	}
	if maxBytes <= 0 {
		maxBytes = defaultMailboxMaxBytes
	}
	if maxTargetBytes <= 0 {
		maxTargetBytes = defaultMailboxMaxTargetBytes
	}
	return &Mailbox{
		entries:        make(map[string][]mailboxEntry),
		maxBytes:       maxBytes,
		maxTargetBytes: maxTargetBytes,
		ttl:            ttl,
		done:           make(chan struct{}),
	}
}

// Start launches the background TTL sweeper. Pruning is otherwise lazy
// (it runs on Put/Take/Stats), so entries for a target nobody touches
// would linger in memory until the next health poll; the sweeper drops
// them on its own. The Hub starts it and stops it in Close.
func (m *Mailbox) Start() {
	m.startOnce.Do(func() {
		// Copy the interval synchronously: reading the package var
		// inside the goroutine would race with tests that shrink it
		// after Start returns but before the goroutine is first
		// scheduled.
		interval := mailboxSweepInterval
		go m.sweepLoop(interval)
	})
}

// Close stops the background sweeper. Safe to call more than once and
// before Start.
func (m *Mailbox) Close() {
	m.closeOnce.Do(func() {
		close(m.done)
	})
}

func (m *Mailbox) sweepLoop(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			m.mu.Lock()
			m.pruneLocked()
			m.mu.Unlock()
		case <-m.done:
			return
		}
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
	if int64(len(data)) > m.maxTargetBytes {
		// Same rule per target: one device may not queue a single
		// entry that alone exhausts its per-target budget.
		log.Printf("[MAILBOX] Dropped %d-byte entry for %s: exceeds %d-byte per-target cap", len(data), target, m.maxTargetBytes)
		return
	}
	// Per-target cap: evict this target's earliest-expiry entries until
	// the newcomer fits, so one offline device cannot monopolize the
	// mailbox. Other targets are untouched at this stage.
	for m.targetBytesLocked(target)+int64(len(data)) > m.maxTargetBytes {
		if !m.evictTargetOldestLocked(target) {
			return
		}
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

// targetBytesLocked reports the bytes currently queued for target.
// Callers must hold m.mu.
func (m *Mailbox) targetBytesLocked(target string) int64 {
	var n int64
	for _, entry := range m.entries[target] {
		n += int64(len(entry.data))
	}
	return n
}

// evictTargetOldestLocked drops target's earliest-expiring entry and
// reports whether one existed. Callers must hold m.mu.
func (m *Mailbox) evictTargetOldestLocked(target string) bool {
	entries := m.entries[target]
	if len(entries) == 0 {
		return false
	}
	oldest := 0
	for i, entry := range entries {
		if entry.expires.Before(entries[oldest].expires) {
			oldest = i
		}
	}
	m.bytes -= int64(len(entries[oldest].data))
	entries = append(entries[:oldest], entries[oldest+1:]...)
	if len(entries) == 0 {
		delete(m.entries, target)
	} else {
		m.entries[target] = entries
	}
	return true
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
