package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"
)

// Browse/fetch limits. The path and entry caps are pinned by the v3.2.1 wire
// format; the byte cap is a local policy so one fetch cannot pin the machine
// for hours. fetchMaxTotalBytes is a package var so tests can shrink it
// without writing multi-gigabyte fixtures; the shipped value is 4 GiB.
const (
	browsePathMaxLen   = 1024
	browseSegmentMax   = 255
	browseMaxEntries   = 1000
	browseReadBatch    = 256
	fetchMaxPaths      = 32
	requestExpirySkew  = 7 * time.Minute
	browseLimiterSlots = 256
)

var fetchMaxTotalBytes int64 = 4 << 30

// browseGlobalRate and browseGlobalBurst bound accepted browses across every
// requester: at most 10 per second cluster-wide. They are package vars so
// tests can shrink the budget without waiting on the wall clock.
var (
	browseGlobalRate  = 10.0
	browseGlobalBurst = 10.0
)

// browseWorkerLimit bounds concurrent browse handlers. Each listing touches
// the filesystem, so an attacker who rotates requester ids must not be able to
// spawn unbounded scans. Package var so tests can shrink the pool.
var browseWorkerLimit = 2

// browseWorkerPool is the semaphore behind browseWorkerLimit. A handler that
// cannot acquire a slot is answered "rate_limited" without any scan work. A
// mutex-backed counter (rather than a channel) lets tests reset the pool
// without racing a handler that is still unwinding.
type browseWorkerPool struct {
	mu     sync.Mutex
	limit  int
	active int
}

func (p *browseWorkerPool) acquire() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.active >= p.limit {
		return false
	}
	p.active++
	return true
}

func (p *browseWorkerPool) release() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.active > 0 {
		p.active--
	}
}

func (p *browseWorkerPool) reset(limit int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.limit = limit
	p.active = 0
}

var browseWorkers = &browseWorkerPool{limit: browseWorkerLimit}

// browseScanHook is nil in production. Tests set it to hold a handler inside
// its worker slot while they probe the concurrency cap.
var browseScanHook func()

// requestNow is the clock behind the request expiry checks. It is a package
// var so tests can cross a deadline without sleeping.
var requestNow = time.Now

// fetchBusy is the daemon's one-fetch-at-a-time guard. Each sealed envelope is
// handled in its own goroutine, so a second fetch that arrives while a send
// subprocess is running is declined "busy". The slot is released when the
// subprocess completes, not when the request is validated.
var fetchBusy atomic.Bool

func acquireFetchSlot() bool { return fetchBusy.CompareAndSwap(false, true) }
func releaseFetchSlot()      { fetchBusy.Store(false) }

// startFetchSend is a package var so tests can drive the handler without a
// real send subprocess.
var startFetchSend = launchFetchSend

// singleLineDisplayName strips line breaks, control characters, and Unicode
// format characters (Cf: zero-width joiners, bidi/RTL overrides, BOM) from a
// peer-supplied display name. Names arrive inside the sealed envelope and are
// shown in log lines, so they must not be able to forge extra log lines or
// reorder text. Only the local rendering changes; the wire value is untouched.
func singleLineDisplayName(raw string) string {
	var b strings.Builder
	b.Grow(len(raw))
	for _, r := range raw {
		switch {
		case r == '\u2028' || r == '\u2029': // Unicode line/paragraph separators
			b.WriteRune(' ')
		case r < 0x20 || r == 0x7f || (r >= 0x80 && r <= 0x9f):
			b.WriteRune(' ')
		case unicode.Is(unicode.Cf, r):
			// Drop format characters outright: a space would still leave a
			// visible gap for a zero-width joiner.
		default:
			b.WriteRune(r)
		}
	}
	return strings.TrimSpace(b.String())
}

// peerLabel renders a peer for log lines: the sanitized display name plus a
// short prefix of its stable device id, so two devices sharing a name (the
// default is "Lilac") stay distinguishable. The id is peer-supplied too and is
// sanitized before it is shown.
func peerLabel(name, id string) string {
	name = singleLineDisplayName(name)
	id = singleLineDisplayName(id)
	if name == "" {
		name = "unknown device"
	}
	if id == "" {
		return name
	}
	if name == id {
		// The name fell back to the id; repeating it as a suffix is noise.
		return id
	}
	short := id
	if runes := []rune(short); len(runes) > 8 {
		short = string(runes[:8]) + "…"
	}
	return fmt.Sprintf("%s (%s)", name, short)
}

// deviceIDPattern is the relay's connection charset. Critically it excludes
// commas, so a crafted requester_id of "a,b" can never become two targets if
// it is fed to splitTargets.
var deviceIDPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

// isValidDeviceID reports whether id is a well-formed device id.
func isValidDeviceID(id string) bool { return deviceIDPattern.MatchString(id) }

// browseEntry is one listing row. The JSON tags are the pinned wire names.
type browseEntry struct {
	Name     string `json:"name"`
	Size     int64  `json:"size"`
	Modified int64  `json:"modified"`
	Dir      bool   `json:"dir"`
}

// validBrowsePath reports whether path is a well-formed browse path: empty for
// the root, otherwise relative segments joined by "/" with no leading or
// trailing slash, no empty/dot/dotdot segments, no control characters, and at
// most 1024 UTF-16 units. The shape mirrors RequestCodec.isBrowsePath on
// Android byte for byte; target-side policy (dotfiles, Windows separators) is
// applied separately in resolveBrowseTarget so a requester never rejects a
// path a different target would serve.
func validBrowsePath(path string) bool {
	if path == "" {
		return true
	}
	// Count UTF-16 code units, not UTF-8 bytes, to match Kotlin's
	// String.length on non-ASCII input.
	if utf16Length(path) > browsePathMaxLen {
		return false
	}
	if strings.HasPrefix(path, "/") || strings.HasSuffix(path, "/") {
		return false
	}
	for _, r := range path {
		if r < 0x20 || (r >= 0x7f && r <= 0x9f) {
			return false
		}
	}
	for _, segment := range strings.Split(path, "/") {
		if segment == "" || segment == "." || segment == ".." {
			return false
		}
		// Mirror validBrowseEntryName: one path segment may not exceed the
		// filesystem's 255 UTF-16 unit name limit. Without this, a requester
		// could send a path no target could ever serve (or that a Windows
		// target would silently reinterpret).
		if utf16Length(segment) > browseSegmentMax {
			return false
		}
	}
	return true
}

// browsePathPolicyOK applies the target's local policy on top of the wire
// shape: dotfiles and dot-directories are hidden from listings, so a hidden
// entry must not be reachable by guessing its name. On Windows a colon (drive
// letters, NTFS alternate data streams) or a backslash (a path separator
// there, a file-name character on Linux) is refused too.
func browsePathPolicyOK(path string) bool {
	if path == "" {
		return true
	}
	for _, segment := range strings.Split(path, "/") {
		if strings.HasPrefix(segment, ".") {
			return false
		}
		if runtime.GOOS == "windows" &&
			(strings.ContainsRune(segment, ':') || strings.ContainsRune(segment, '\\')) {
			return false
		}
	}
	return true
}

// browseErrorReasons is the pinned browse_listing error enum. Anything else a
// peer sends is untrusted text and is reported as the generic "denied".
var browseErrorReasons = map[string]bool{
	"denied":       true,
	"not_found":    true,
	"rate_limited": true,
	"unsupported":  true,
}

// sanitizeBrowseError validates a peer-supplied browse error against the
// pinned enum and strips anything that could forge output.
func sanitizeBrowseError(raw string) string {
	reason := singleLineDisplayName(raw)
	if !browseErrorReasons[reason] {
		return "denied"
	}
	return reason
}

// resolvedBrowseRoot resolves the configured browse root through symlinks and
// verifies it is a directory. Resolving once means every later containment
// check compares against the real root, so a symlinked home directory or a
// root that is itself a link cannot be used to escape.
func resolvedBrowseRoot(cfg Config) (string, error) {
	root := strings.TrimSpace(cfg.BrowseRoot)
	if root == "" {
		root = defaultBrowseRoot()
	}
	resolved, err := filepath.EvalSymlinks(root)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return "", err
	}
	if !info.IsDir() {
		return "", fmt.Errorf("browse root is not a directory")
	}
	return resolved, nil
}

// resolveBrowseTarget validates a relative browse path and resolves it inside
// the browse root. It returns the absolute path and an empty reason on
// success, or a pinned browse_listing error reason. Symlinks are resolved
// before the containment check, so a link that points outside the root is
// reported as not_found rather than followed or confirmed to exist.
func resolveBrowseTarget(cfg Config, rel string) (string, string) {
	if !validBrowsePath(rel) || !browsePathPolicyOK(rel) {
		return "", "denied"
	}
	root, err := resolvedBrowseRoot(cfg)
	if err != nil {
		return "", "denied"
	}
	joined := root
	if rel != "" {
		joined = filepath.Join(root, filepath.FromSlash(rel))
	}
	resolved, err := filepath.EvalSymlinks(joined)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return "", "not_found"
		}
		return "", "denied"
	}
	if !IsPathInsideDirectory(resolved, root) {
		// An escaped symlink is reported exactly like a missing path: the
		// requester learns nothing about what exists outside the root. Fetch
		// validation already collapses every resolution failure to
		// not_found; browse must not be a more informative oracle.
		return "", "not_found"
	}
	// A Windows 8.3 alias (or a symlink) can resolve to a dotfile even when
	// the requested segment did not start with ".". The policy hides
	// dotfiles, so check the resolved base too. The root itself is exempt:
	// -browse-root may legitimately point at a dot-directory.
	if rel != "" && strings.HasPrefix(filepath.Base(resolved), ".") {
		return "", "denied"
	}
	return resolved, ""
}

// validBrowseEntryName mirrors RequestCodec.isEntryName on Android: one safe
// path segment, never empty, "." or "..", never containing a separator or a
// control character, and at most 255 UTF-16 units. A name that fails this rule
// would make an Android requester reject the whole listing, so the desktop
// target skips it instead of emitting an unparseable row.
func validBrowseEntryName(name string) bool {
	if name == "" || utf16Length(name) > browseSegmentMax {
		return false
	}
	if name == "." || name == ".." {
		return false
	}
	for _, r := range name {
		if r == '/' || r < 0x20 || (r >= 0x7f && r <= 0x9f) {
			return false
		}
	}
	return true
}

// browseDirReader is the subset of *os.File that browseDirectory needs. It is
// an interface so tests can count ReadDir batches without materializing a
// multi-thousand-entry directory twice.
type browseDirReader interface {
	ReadDir(n int) ([]fs.DirEntry, error)
}

// readBrowseEntries reads one directory in fixed-size batches and stops as
// soon as it has browseMaxEntries+1 accepted rows. A directory with a million
// entries therefore costs at most ~1001 accepted entries plus one batch of
// readdir work, not a full materialization and sort. The returned slice may
// hold browseMaxEntries+1 rows; the caller trims it and sets truncated.
func readBrowseEntries(dir browseDirReader) ([]browseEntry, bool, error) {
	entries := make([]browseEntry, 0, browseMaxEntries+1)
	for {
		batch, err := dir.ReadDir(browseReadBatch)
		for _, de := range batch {
			name := de.Name()
			if strings.HasPrefix(name, ".") || !validBrowseEntryName(name) {
				continue
			}
			// Symlinks are skipped entirely, whether they point at a file or
			// a directory: following one would either leak a target outside
			// the root or make the listing disagree with fetch validation.
			if de.Type()&fs.ModeSymlink != 0 {
				continue
			}
			info, infoErr := de.Info()
			if infoErr != nil {
				// A file that vanished between ReadDir and Info is skipped;
				// the rest of the listing is still useful.
				continue
			}
			if info.Mode()&fs.ModeSymlink != 0 {
				continue
			}
			entry := browseEntry{
				Name:     name,
				Modified: info.ModTime().UnixMilli(),
				Dir:      info.IsDir(),
			}
			// The Android codec requires non-negative size and mtime. A
			// pre-1970 mtime or an odd filesystem size must not make the
			// whole listing unparseable on the other end.
			if entry.Modified < 0 {
				entry.Modified = 0
			}
			if !entry.Dir {
				entry.Size = info.Size()
				if entry.Size < 0 {
					entry.Size = 0
				}
			}
			entries = append(entries, entry)
			if len(entries) > browseMaxEntries {
				return entries, true, nil
			}
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				return entries, false, nil
			}
			return nil, false, err
		}
	}
}

// sortBrowseEntries orders a listing: directories before files, names
// case-insensitively, and a stable case-sensitive tiebreak (README vs readme).
func sortBrowseEntries(entries []browseEntry) {
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].Dir != entries[j].Dir {
			return entries[i].Dir
		}
		lowerI, lowerJ := strings.ToLower(entries[i].Name), strings.ToLower(entries[j].Name)
		if lowerI != lowerJ {
			return lowerI < lowerJ
		}
		return entries[i].Name < entries[j].Name
	})
}

// browseDirectory lists one resolved directory: dotfiles, dot-directories,
// and symlinks are skipped, directories sort before files, names sort
// case-insensitively, and the result is capped at browseMaxEntries with
// truncated set when the cap cut the listing. Directory sizes are 0; file
// sizes and mtimes come from the entry's lstat info.
func browseDirectory(absPath string) ([]browseEntry, bool, string) {
	dir, err := os.Open(absPath)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, false, "not_found"
		}
		return nil, false, "denied"
	}
	defer dir.Close()

	entries, truncated, err := readBrowseEntries(dir)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, false, "not_found"
		}
		return nil, false, "denied"
	}
	sortBrowseEntries(entries)
	if truncated {
		entries = entries[:browseMaxEntries]
	}
	return entries, truncated, ""
}

// resolveFetchFiles validates every requested path with the browse containment
// rules and requires each to be an existing regular file. Any invalid path
// becomes "not_found"; the caller never learns which one failed. The total
// size is capped so a single fetch cannot pin the send subprocess for hours.
func resolveFetchFiles(cfg Config, paths []string) ([]string, string) {
	files := make([]string, 0, len(paths))
	var total int64
	for _, rel := range paths {
		abs, reason := resolveBrowseTarget(cfg, rel)
		if reason != "" {
			return nil, "not_found"
		}
		info, err := os.Stat(abs)
		if err != nil || !info.Mode().IsRegular() {
			return nil, "not_found"
		}
		total += info.Size()
		if total > fetchMaxTotalBytes {
			return nil, "too_large"
		}
		files = append(files, abs)
	}
	return files, ""
}

// browseRateLimiter enforces "at most two accepted browse frames per requester
// per second". The key is the trust id (origin_id when present), so an
// ephemeral requester cannot rotate connection ids to bypass the limit. The
// table is bounded like the replay guard so peer churn cannot grow it without
// limit.
type browseRateLimiter struct {
	mu      sync.Mutex
	entries map[string]*browseRateEntry
	order   []string
	now     func() time.Time
}

type browseRateEntry struct {
	windowStart time.Time
	count       int
}

// browseRateWindow is a package var so tests can shrink the window.
var browseRateWindow = time.Second

func newBrowseRateLimiter() *browseRateLimiter {
	return &browseRateLimiter{
		entries: make(map[string]*browseRateEntry, browseLimiterSlots),
		now:     time.Now,
	}
}

// Allow records one accepted frame for key and reports whether it fits inside
// the current window. A rejected frame does not consume budget.
func (l *browseRateLimiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	entry := l.entries[key]
	if entry == nil {
		if len(l.entries) >= browseLimiterSlots {
			if len(l.order) > 0 {
				delete(l.entries, l.order[0])
				l.order = l.order[1:]
			} else {
				l.entries = make(map[string]*browseRateEntry, browseLimiterSlots)
			}
		}
		entry = &browseRateEntry{windowStart: now}
		l.entries[key] = entry
		l.order = append(l.order, key)
	}
	if now.Sub(entry.windowStart) >= browseRateWindow {
		entry.windowStart = now
		entry.count = 0
	}
	if entry.count >= 2 {
		return false
	}
	entry.count++
	return true
}

func (l *browseRateLimiter) reset() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.entries = make(map[string]*browseRateEntry, browseLimiterSlots)
	l.order = nil
}

var browseLimiter = newBrowseRateLimiter()

// browseTokenBucket is a classic token bucket. It is the cluster-wide browse
// budget: the per-trust-id window alone is bypassed by rotating the claimed
// origin, so a global ceiling bounds the total scan rate.
type browseTokenBucket struct {
	mu     sync.Mutex
	tokens float64
	last   time.Time
	now    func() time.Time
	rate   float64
	burst  float64
}

func newBrowseTokenBucket(rate, burst float64) *browseTokenBucket {
	return &browseTokenBucket{
		tokens: burst,
		last:   time.Now(),
		now:    time.Now,
		rate:   rate,
		burst:  burst,
	}
}

// Allow consumes one token when available. A rejected call consumes nothing.
func (b *browseTokenBucket) Allow() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	now := b.now()
	if elapsed := now.Sub(b.last).Seconds(); elapsed > 0 {
		b.tokens += elapsed * b.rate
		if b.tokens > b.burst {
			b.tokens = b.burst
		}
		b.last = now
	}
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func (b *browseTokenBucket) reset(rate, burst float64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.rate = rate
	b.burst = burst
	b.tokens = burst
	b.last = b.now()
}

var browseGlobalLimiter = newBrowseTokenBucket(browseGlobalRate, browseGlobalBurst)

// acquireBrowseWorker takes one of the browseWorkerLimit slots. It never
// blocks: a full pool is reported to the requester immediately.
func acquireBrowseWorker() bool { return browseWorkers.acquire() }

func releaseBrowseWorker() { browseWorkers.release() }

// fetchOriginWindow is the minimum spacing between accepted fetches from one
// origin. Package var so tests can shrink it.
var fetchOriginWindow = 30 * time.Second

// fetchOriginLimiter enforces one accepted fetch per origin per
// fetchOriginWindow. A rotating requester id cannot reset the budget because
// the key is the stable trust id. The table is bounded like the browse limiter
// so peer churn cannot grow it without limit.
type fetchOriginBudget struct {
	mu      sync.Mutex
	entries map[string]time.Time
	order   []string
	now     func() time.Time
}

func newFetchOriginBudget() *fetchOriginBudget {
	return &fetchOriginBudget{
		entries: make(map[string]time.Time, browseLimiterSlots),
		now:     time.Now,
	}
}

// Allow records one accepted fetch for key and reports whether the origin is
// outside its cooldown window.
func (l *fetchOriginBudget) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	if last, ok := l.entries[key]; ok {
		if now.Sub(last) < fetchOriginWindow {
			return false
		}
	} else {
		if len(l.entries) >= browseLimiterSlots {
			if len(l.order) > 0 {
				delete(l.entries, l.order[0])
				l.order = l.order[1:]
			} else {
				l.entries = make(map[string]time.Time, browseLimiterSlots)
			}
		}
		l.order = append(l.order, key)
	}
	l.entries[key] = now
	return true
}

func (l *fetchOriginBudget) reset() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.entries = make(map[string]time.Time, browseLimiterSlots)
	l.order = nil
}

var fetchOriginLimiter = newFetchOriginBudget()

// resetBrowseLimits clears the three browse/fetch rate layers. The daemon calls
// it at startup; tests call it between fixtures. The limiter objects are reset
// in place (under their own locks) rather than replaced, so a handler that is
// still unwinding cannot race a package-var swap.
func resetBrowseLimits() {
	browseLimiter.reset()
	browseGlobalLimiter.reset(browseGlobalRate, browseGlobalBurst)
	fetchOriginLimiter.reset()
}

// resetBrowseState clears the rate layers and the worker semaphore. It must
// only run when no browse handler is active.
func resetBrowseState() {
	resetBrowseLimits()
	browseWorkers.reset(browseWorkerLimit)
}

// sendBrowseListing seals one browse_listing reply. On success entries is
// always present (an empty directory is []), and truncated is only sent when
// true. On error entries is omitted: the two are mutually exclusive.
func sendBrowseListing(
	peerCryptoKey []byte,
	client *wsClient,
	requesterID, requestID, path string,
	entries []browseEntry,
	truncated bool,
	reason string,
) {
	inner := map[string]interface{}{
		"request_id": requestID,
		"path":       path,
		"ts":         time.Now().UnixMilli(),
	}
	if reason != "" {
		inner["error"] = reason
	} else {
		if entries == nil {
			entries = []browseEntry{}
		}
		inner["entries"] = entries
		if truncated {
			inner["truncated"] = true
		}
	}
	envelope, err := sealEnvelope(peerCryptoKey, requesterID, "browse_listing", inner)
	if err != nil {
		log.Printf("[BROWSE] Could not seal listing for %s: %v", requestID, err)
		return
	}
	if client != nil {
		if err := client.WriteJSON(envelope); err != nil {
			log.Printf("[BROWSE] Could not send listing for %s: %v", requestID, err)
		}
	}
}

// handleIncomingBrowse is the daemon side of a browse request. It applies the
// trust gate, request-id dedup, and per-trust-id rate limit before touching
// the filesystem, then lists one directory inside the browse root. Errors are
// sealed back as browse_listing with the pinned reason enum.
func handleIncomingBrowse(
	cfg Config,
	peerCryptoKey []byte,
	client *wsClient,
	inner map[string]interface{},
) {
	requestID := envelopeString(inner, "request_id")
	requesterID := envelopeString(inner, "requester_id")
	originID := envelopeString(inner, "origin_id")
	requesterName := envelopeString(inner, "requester_name")
	targetID := envelopeString(inner, "target_id")

	path := ""
	if raw, ok := inner["path"]; ok {
		value, isString := raw.(string)
		if !isString {
			log.Printf("[BROWSE] Dropped browse with a non-string path from %s", singleLineDisplayName(requesterID))
			return
		}
		path = value
	} else {
		// The field is required; a missing path is malformed, not a root
		// browse. Android's RequestCodec.parseBrowse applies the same rule.
		log.Printf("[BROWSE] Dropped browse without a path from %s", singleLineDisplayName(requesterID))
		return
	}

	if !isRequestID(requestID) || requesterID == "" || targetID == "" {
		log.Printf("[BROWSE] Dropped malformed browse from %s", singleLineDisplayName(requesterID))
		return
	}
	// The requester id is the reply address; a value outside the relay's
	// charset cannot be routed and must not reach sealEnvelope.
	if !isValidDeviceID(requesterID) {
		log.Printf("[BROWSE] Dropped browse with an invalid requester id from %s", singleLineDisplayName(requesterID))
		return
	}
	if !isValidDeviceID(targetID) || (originID != "" && !isValidDeviceID(originID)) {
		sendBrowseListing(peerCryptoKey, client, requesterID, requestID, path, nil, false, "denied")
		return
	}
	if targetID != cfg.DeviceID {
		log.Printf("[BROWSE] Dropped browse for %s, not this device (%s)",
			singleLineDisplayName(targetID), cfg.DeviceID)
		return
	}
	if requesterID == cfg.DeviceID || originID == cfg.DeviceID {
		log.Printf("[BROWSE] Dropped self-addressed browse %s", requestID)
		return
	}

	trustID := requesterID
	if originID != "" {
		trustID = originID
	}
	requesterName = singleLineDisplayName(requesterName)
	if requesterName == "" {
		requesterName = singleLineDisplayName(requesterID)
	}
	if requesterName == "" {
		requesterName = "unknown device"
	}
	requesterLabel := peerLabel(requesterName, trustID)

	// Browse/fetch is a consent capability: trust a device once
	// (`gaur-share trust <name>`) and it can browse. This is unconditional —
	// the only-trusted setting no longer gates these kinds. Per-device keys
	// (v4) will replace the claim-based check; until then origin_id is only a
	// claim, so an untrusted device must not get a listing even with
	// only-trusted off. The reply is the same "denied" an untrusted device
	// always saw, with no hint about the root's contents.
	if !isTrustedDevice(trustID) {
		sendBrowseListing(peerCryptoKey, client, requesterID, requestID, path, nil, false, "denied")
		log.Printf("[BROWSE] denied %s from untrusted %s", requestID, requesterLabel)
		return
	}

	// A duplicate request id is dropped silently: the first delivery is
	// already being served, and a second listing would confuse the
	// requester's single pending record.
	if replayGuard.Seen("browse:" + requestID) {
		return
	}

	// Layer 1: a cluster-wide worker cap. A third concurrent browse is
	// answered immediately, before any filesystem work, so a flood cannot
	// multiply scans even if every frame carries a distinct requester id.
	if !acquireBrowseWorker() {
		sendBrowseListing(peerCryptoKey, client, requesterID, requestID, path, nil, false, "rate_limited")
		log.Printf("[BROWSE] rate_limited %s from %s (worker cap)", requestID, requesterLabel)
		return
	}
	defer releaseBrowseWorker()

	// Layer 2: a cluster-wide token bucket. The per-origin window below is
	// bypassed by rotating the claimed origin; the global ceiling bounds the
	// total accepted browse rate across all requesters.
	if !browseGlobalLimiter.Allow() {
		sendBrowseListing(peerCryptoKey, client, requesterID, requestID, path, nil, false, "rate_limited")
		log.Printf("[BROWSE] rate_limited %s from %s (global budget)", requestID, requesterLabel)
		return
	}

	// Layer 3: the per-trust-id window. It caps one device's sustained rate
	// even when the global bucket has tokens to spare.
	if !browseLimiter.Allow(trustID) {
		sendBrowseListing(peerCryptoKey, client, requesterID, requestID, path, nil, false, "rate_limited")
		log.Printf("[BROWSE] rate_limited %s from %s", requestID, requesterLabel)
		return
	}

	if browseScanHook != nil {
		browseScanHook()
	}

	absPath, reason := resolveBrowseTarget(cfg, path)
	if reason != "" {
		sendBrowseListing(peerCryptoKey, client, requesterID, requestID, path, nil, false, reason)
		return
	}
	entries, truncated, reason := browseDirectory(absPath)
	if reason != "" {
		sendBrowseListing(peerCryptoKey, client, requesterID, requestID, path, nil, false, reason)
		return
	}
	sendBrowseListing(peerCryptoKey, client, requesterID, requestID, path, entries, truncated, "")
	log.Printf("[BROWSE] served %s to %s (%d entries)", requestID, requesterLabel, len(entries))
}

// handleIncomingFetch is the daemon side of a fetch request. It validates the
// grant, trust, expiry, busy state, every path, and the batch caps, then hands
// the resolved files to the existing send subprocess. The grant and file
// contents never enter a log line.
func handleIncomingFetch(
	cfg Config,
	peerCryptoKey []byte,
	client *wsClient,
	inner map[string]interface{},
) {
	requestID := envelopeString(inner, "request_id")
	requesterID := envelopeString(inner, "requester_id")
	originID := envelopeString(inner, "origin_id")
	requesterName := envelopeString(inner, "requester_name")
	targetID := envelopeString(inner, "target_id")
	expiresTs := envelopeInt64(inner, "expires_ts")
	grant := envelopeString(inner, "grant")

	pathsRaw, pathsOK := inner["paths"].([]interface{})
	if !pathsOK || len(pathsRaw) == 0 {
		log.Printf("[FETCH] Dropped fetch without paths from %s", singleLineDisplayName(requesterID))
		return
	}
	paths := make([]string, 0, len(pathsRaw))
	for _, raw := range pathsRaw {
		value, isString := raw.(string)
		if !isString {
			log.Printf("[FETCH] Dropped fetch with a non-string path from %s", singleLineDisplayName(requesterID))
			return
		}
		paths = append(paths, value)
	}

	if !isRequestID(requestID) || requesterID == "" || targetID == "" || expiresTs <= 0 ||
		!isLowerHex(grant, 64) {
		log.Printf("[FETCH] Dropped malformed fetch from %s", singleLineDisplayName(requesterID))
		return
	}
	if !isValidDeviceID(requesterID) || !isValidDeviceID(targetID) ||
		(originID != "" && !isValidDeviceID(originID)) {
		if isValidDeviceID(requesterID) {
			declineRequest(peerCryptoKey, client, requesterID,
				peerLabel("", requesterID), requestID, "not_allowed")
		} else {
			log.Printf("[FETCH] Dropped fetch with an invalid requester id from %s",
				singleLineDisplayName(requesterID))
		}
		return
	}
	requesterName = singleLineDisplayName(requesterName)
	if requesterName == "" {
		requesterName = singleLineDisplayName(requesterID)
	}
	if requesterName == "" {
		requesterName = "unknown device"
	}
	trustID := requesterID
	if originID != "" {
		trustID = originID
	}
	requesterLabel := peerLabel(requesterName, trustID)

	if targetID != cfg.DeviceID {
		log.Printf("[FETCH] Dropped fetch for %s, not this device (%s)",
			singleLineDisplayName(targetID), cfg.DeviceID)
		return
	}
	if requesterID == cfg.DeviceID || originID == cfg.DeviceID {
		log.Printf("[FETCH] Dropped self-addressed fetch %s", requestID)
		return
	}
	// Browse/fetch is a consent capability: trust a device once
	// (`gaur-share trust <name>`) and it can fetch. This is unconditional —
	// the only-trusted setting no longer gates these kinds. Per-device keys
	// (v4) will replace the claim-based check; until then origin_id is only a
	// claim, so an untrusted device must not pull files even with
	// only-trusted off.
	if !isTrustedDevice(trustID) {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "not_allowed")
		return
	}

	// A duplicate request id is dropped silently: the first delivery is
	// already being handled, and a second decline would confuse the
	// requester's single pending record.
	if replayGuard.Seen("fetch:" + requestID) {
		return
	}

	// now >= expires_ts is expired, the same boundary the requester applies.
	// A grant stamped further than the skew window into the future is
	// rejected too: the requester's clock cannot extend its own reach.
	now := requestNow().UnixMilli()
	if now >= expiresTs || expiresTs > now+int64(requestExpirySkew/time.Millisecond) {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "expired")
		return
	}

	if !acquireFetchSlot() {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "busy")
		return
	}
	// The slot covers the whole send subprocess, so one fetch session at a
	// time also bounds concurrent uploads. Every early return releases it.
	defer releaseFetchSlot()

	// One accepted fetch per origin per window. The global slot above is
	// released when the send subprocess exits, so without this a requester
	// could immediately start another full-batch upload. The key is the
	// stable trust id, not the rotating requester id. The budget is consumed
	// only after path validation, so a typo does not lock the origin out.
	if len(paths) > fetchMaxPaths {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "too_large")
		return
	}
	files, reason := resolveFetchFiles(cfg, paths)
	if reason != "" {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, reason)
		return
	}
	if !fetchOriginLimiter.Allow(trustID) {
		declineRequest(peerCryptoKey, client, requesterID, requesterLabel, requestID, "busy")
		return
	}

	meta := fmt.Sprintf("%s:%s:%d", requestID, grant, expiresTs)
	log.Printf("[FETCH] accepted %s from %s (%d file(s))", requestID, requesterLabel, len(files))
	// Every accepted fetch raises one notification before the upload starts.
	// The label is the sanitized name+id form; file names never appear. The
	// helper starts notify-send and returns, so a broken notification daemon
	// cannot block or fail the send.
	notifyFetchAccepted(requesterLabel, len(files))
	if err := startFetchSend(cfg, requesterID, meta, files); err != nil {
		log.Printf("[FETCH] send subprocess for %s failed: %v", requestID, err)
	}
}

// declineRequest seals a request_declined envelope back to the requester and
// logs the pinned one-line outcome. requesterLabel is already sanitized and
// carries the stable id. The grant and the selected files are never logged.
func declineRequest(
	peerCryptoKey []byte,
	client *wsClient,
	requesterID, requesterLabel, requestID, reason string,
) {
	inner := map[string]interface{}{
		"request_id": requestID,
		"reason":     reason,
		"ts":         time.Now().UnixMilli(),
	}
	envelope, err := sealEnvelope(peerCryptoKey, requesterID, "request_declined", inner)
	if err != nil {
		log.Printf("[FETCH] Could not seal decline for %s: %v", requestID, err)
		return
	}
	if client != nil {
		if err := client.WriteJSON(envelope); err != nil {
			log.Printf("[FETCH] Could not send decline for %s: %v", requestID, err)
		}
	}
	log.Printf("[FETCH] declined %s from %s (%s)", requestID, requesterLabel, reason)
}

// fetchSendArgs is the exact argv for the send subprocess: the daemon's relay
// identity first, then the send command with the request metadata and the
// resolved files. "--" terminates flag parsing so a path that begins with "-"
// is still treated as a file. exec.Command runs this vector directly; there is
// no shell.
func fetchSendArgs(cfg Config, requesterID, requestMeta string, files []string) []string {
	args := make([]string, 0, 12+len(files))
	// Forward the daemon's identity and relay settings: the offer's
	// sender_id must be the real device id the requester asked, and a
	// non-default relay/token must not silently fall back to config.json.
	if cfg.RelayAddr != "" {
		args = append(args, "-relay", cfg.RelayAddr)
	}
	if cfg.TokenFile != "" {
		args = append(args, "-key-file", cfg.TokenFile)
	}
	if cfg.DeviceID != "" {
		args = append(args, "-device-id", cfg.DeviceID)
	}
	if cfg.Name != "" {
		args = append(args, "-name", cfg.Name)
	}
	args = append(args, "send", "--to", requesterID, "--request-meta", requestMeta, "--")
	return append(args, files...)
}

// fetchSendTimeout bounds one send subprocess. A black-holed relay can stall an
// upload, but not forever; thirty minutes is generous for a tunnel upload and
// well inside the completion-timeout clamp. Package var so tests can shrink it.
var fetchSendTimeout = 30 * time.Minute

// fetchSendCmds tracks live send subprocesses so the daemon's shutdown path
// can kill them. os.Exit skips deferred cancels, so a context alone cannot
// clean them up.
var (
	fetchSendMu   sync.Mutex
	fetchSendCmds = make(map[*exec.Cmd]struct{})
)

// killFetchSubprocesses kills every send subprocess still running. It is
// called from the daemon's graceful-shutdown path. The lock is held across the
// kills so it cannot race a command that is mid-Start.
func killFetchSubprocesses() {
	fetchSendMu.Lock()
	defer fetchSendMu.Unlock()
	for cmd := range fetchSendCmds {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
	}
}

// runTrackedCommand starts cmd, registers it for the shutdown kill, and waits
// for it to finish. Registration happens under the same lock as Start, so the
// shutdown path either sees the command before it starts or after it is
// running — never a started-but-untracked process.
func runTrackedCommand(cmd *exec.Cmd) error {
	fetchSendMu.Lock()
	fetchSendCmds[cmd] = struct{}{}
	err := cmd.Start()
	if err != nil {
		delete(fetchSendCmds, cmd)
	}
	fetchSendMu.Unlock()
	if err != nil {
		return err
	}
	defer func() {
		fetchSendMu.Lock()
		delete(fetchSendCmds, cmd)
		fetchSendMu.Unlock()
	}()
	return cmd.Wait()
}

// launchFetchSend starts a send subprocess of this same binary. The subprocess
// owns the whole v3 upload and offer flow, so the daemon loop never has to
// grow transfer state for a fetch. stdout/stderr go to the daemon log; exec is
// used directly, with no shell. The context bounds the subprocess lifetime,
// and the command is tracked so daemon shutdown can kill it.
func launchFetchSend(cfg Config, requesterID, requestMeta string, files []string) error {
	exe, err := os.Executable()
	if err != nil {
		return fmt.Errorf("locate gaur-share binary: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), fetchSendTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, exe, fetchSendArgs(cfg, requesterID, requestMeta, files)...)
	cmd.Stdout = log.Writer()
	cmd.Stderr = log.Writer()
	return runTrackedCommand(cmd)
}
