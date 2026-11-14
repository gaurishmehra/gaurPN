package main

import (
	"sync"
	"time"
)

type authFailEntry struct {
	count int
	start time.Time
}

// authLimiter is a fixed-window per-IP counter for failed auth attempts.
// An IP that exceeds the limit within the window is throttled with 429s
// until the window rolls over. Successful auths never touch it.
//
// Entries are cleaned two ways: lazily on access (an expired window resets)
// and by a periodic sweep that the hub's 30-second evictor ticker drives,
// so the map stays bounded even for IPs that never come back.
type authLimiter struct {
	mu      sync.Mutex
	limit   int
	window  time.Duration
	entries map[string]*authFailEntry
}

func newAuthLimiter(limit int, window time.Duration) *authLimiter {
	if limit <= 0 {
		limit = authFailureLimit
	}
	if window <= 0 {
		window = authFailureWindow
	}
	return &authLimiter{
		limit:   limit,
		window:  window,
		entries: make(map[string]*authFailEntry),
	}
}

// blocked reports whether ip has exhausted its failure budget in the current
// window.
func (l *authLimiter) blocked(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	e, ok := l.entries[ip]
	if !ok {
		return false
	}
	if time.Since(e.start) >= l.window {
		delete(l.entries, ip)
		return false
	}
	return e.count >= l.limit
}

// recordFailure counts one failed auth for ip and reports whether this
// failure exceeded the budget (the caller answers with 429 in that case).
func (l *authLimiter) recordFailure(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	e, ok := l.entries[ip]
	if !ok || now.Sub(e.start) >= l.window {
		e = &authFailEntry{start: now}
		l.entries[ip] = e
	}
	e.count++
	return e.count > l.limit
}

// sweep drops entries whose window has expired.
func (l *authLimiter) sweep() {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	for ip, e := range l.entries {
		if now.Sub(e.start) >= l.window {
			delete(l.entries, ip)
		}
	}
}
