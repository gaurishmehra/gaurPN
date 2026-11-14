//go:build unix

package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"log"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// syncBuffer is a concurrency-safe log sink for tests that capture logs from
// server goroutines.
type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// TestClipboardSocketServerServesSameUser dials the clipboard socket server
// in-process and asserts the connection is served: the peer-credential check
// must accept a same-uid connection and the message handler must process the
// line. The oversized payload is chosen because its rejection is logged only
// after the connection passed the credential check.
func TestClipboardSocketServerServesSameUser(t *testing.T) {
	cfg := Config{ClipboardSocket: filepath.Join(t.TempDir(), "clipboard.sock")}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	buf := &syncBuffer{}
	old := log.Writer()
	log.SetOutput(buf)

	go runClipboardSocketServer(ctx, cfg, nil, nil, &peerStore{})

	deadline := time.Now().Add(3 * time.Second)
	for {
		if _, err := os.Stat(cfg.ClipboardSocket); err == nil {
			break
		}
		if time.Now().After(deadline) {
			log.SetOutput(old)
			t.Fatal("clipboard socket never appeared")
		}
		time.Sleep(10 * time.Millisecond)
	}

	conn, err := net.Dial("unix", cfg.ClipboardSocket)
	if err != nil {
		log.SetOutput(old)
		t.Fatal(err)
	}
	oversized := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte("a"), 1024*1024+1))
	line := `{"kind":"text","mime":"text/plain","data":"` + oversized + `"}` + "\n"
	if _, err := conn.Write([]byte(line)); err != nil {
		log.SetOutput(old)
		t.Fatal(err)
	}

	deadline = time.Now().Add(3 * time.Second)
	for {
		if strings.Contains(buf.String(), "Rejecting oversized text") {
			break
		}
		if time.Now().After(deadline) {
			log.SetOutput(old)
			t.Fatalf("connection was not served; log so far: %s", buf.String())
		}
		time.Sleep(10 * time.Millisecond)
	}

	_ = conn.Close()
	cancel()
	// The deferred socket removal happens after the server goroutine stops
	// logging, so once the file is gone it is safe to restore the logger.
	deadline = time.Now().Add(3 * time.Second)
	for {
		if _, err := os.Stat(cfg.ClipboardSocket); os.IsNotExist(err) {
			break
		}
		if time.Now().After(deadline) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	log.SetOutput(old)
}

// TestShutdownCleanupRemovesClipboardSocket pins REL-14: a stale socket file
// is replaced by the listening socket at startup, and the SIGTERM cleanup
// unlinks that live socket so Quickshell's next dial does not hit a dead
// file.
func TestShutdownCleanupRemovesClipboardSocket(t *testing.T) {
	dir := t.TempDir()
	sockPath := filepath.Join(dir, "clipboard.sock")

	// A stale socket left behind by a previous daemon run.
	if err := os.WriteFile(sockPath, []byte("stale"), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg := Config{ClipboardSocket: sockPath}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go runClipboardSocketServer(ctx, cfg, nil, nil, &peerStore{})

	// The server removes the stale file and listens on the same path.
	deadline := time.Now().Add(3 * time.Second)
	for {
		if fi, err := os.Lstat(sockPath); err == nil && fi.Mode()&os.ModeSocket != 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("socket server did not replace the stale file")
		}
		time.Sleep(10 * time.Millisecond)
	}

	// The signal-handler cleanup: unlink the socket the daemon listens on.
	removeClipboardSocket(cfg)
	if _, err := os.Lstat(sockPath); !os.IsNotExist(err) {
		t.Fatalf("clipboard socket must be removed on shutdown, stat err: %v", err)
	}

	// Removing a socket that is already gone is a no-op, not an error.
	removeClipboardSocket(cfg)
}
