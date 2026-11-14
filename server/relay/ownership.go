package main

import (
	"log"
	"sync"
)

// maxTrackedTransfers bounds the ownership and open-mode-log tables. Chunks
// are ephemeral (TTL + byte cap), so owners are too; the bound is a defence
// against a client that touches many keys without completing transfers.
const maxTrackedTransfers = 10000

// Ownership records which device created each staged transfer, plus which
// transfer keys have already emitted the legacy open-mode deprecation log.
//
// It is relay-side policy state, not a cryptographic boundary: any cluster
// member holding the master token can mint the capability tags that bypass
// it. Per-device keys (protocol v4) are the real fix. This table exists so
// well-behaved members do not accidentally step on each other's transfers.
//
// Both tables are FIFO-bounded and cleaned up alongside the transfer data:
// ChunkStore.removeLocked calls Forget whenever a transfer is deleted or
// evicted, so ownership never outlives its chunks.
type Ownership struct {
	mu         sync.Mutex
	max        int
	owners     map[string]string // transfer key -> device id
	ownersFIFO []string          // insertion order for FIFO eviction
	logged     map[string]bool   // transfer key -> deprecation already logged
	loggedFIFO []string
}

func NewOwnership(max int) *Ownership {
	if max <= 0 {
		max = maxTrackedTransfers
	}
	return &Ownership{
		max:    max,
		owners: make(map[string]string),
		logged: make(map[string]bool),
	}
}

// Record makes device the owner of key if no owner exists yet. Ownership is
// write-once: once recorded it is never overwritten.
func (o *Ownership) Record(key, device string) {
	if key == "" || device == "" {
		return
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	if _, exists := o.owners[key]; exists {
		return
	}
	for len(o.owners) >= o.max {
		if !o.evictOldestLocked(o.owners, &o.ownersFIFO) {
			break
		}
	}
	o.owners[key] = device
	o.ownersFIFO = append(o.ownersFIFO, key)
}

// Owner returns the recorded owner of key, if any.
func (o *Ownership) Owner(key string) (string, bool) {
	o.mu.Lock()
	defer o.mu.Unlock()
	owner, ok := o.owners[key]
	return owner, ok
}

// NoteOpenMode logs, at most once per transfer key, that a legacy client
// accessed the transfer with neither ownership nor capability credentials.
func (o *Ownership) NoteOpenMode(key string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.logged[key] {
		return
	}
	for len(o.logged) >= o.max {
		if !o.evictOldestLocked(o.logged, &o.loggedFIFO) {
			break
		}
	}
	o.logged[key] = true
	o.loggedFIFO = append(o.loggedFIFO, key)
	log.Printf("[CHUNK] Legacy open-mode access for %s", key)
}

// Forget drops all ownership and log state for key. Called when the
// transfer's chunks are deleted or evicted.
func (o *Ownership) Forget(key string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	delete(o.owners, key)
	delete(o.logged, key)
}

// evictOldestLocked pops the front of the FIFO queue, deleting its map entry
// if still present, and reports whether an actual entry was evicted. Stale
// queue entries (keys removed by Forget) are skipped.
func (o *Ownership) evictOldestLocked[V any](m map[string]V, fifo *[]string) bool {
	for len(*fifo) > 0 {
		key := (*fifo)[0]
		*fifo = (*fifo)[1:]
		if _, exists := m[key]; exists {
			delete(m, key)
			return true
		}
	}
	return false
}
