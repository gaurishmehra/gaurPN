//go:build unix

package main

import (
	"net"
	"os"
	"path/filepath"
	"syscall"
	"testing"
)

func TestWithUmaskNarrowsAndRestores(t *testing.T) {
	prev := syscall.Umask(0o027)
	defer syscall.Umask(prev)

	var inside int
	if err := withUmask(func() error {
		// Reading the umask requires setting it; set it to the same narrowed
		// value the helper should have installed.
		inside = syscall.Umask(0o077)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if inside != 0o077 {
		t.Fatalf("umask inside helper = %o, want 077", inside)
	}
	if after := syscall.Umask(0o027); after != 0o027 {
		t.Fatalf("umask after helper = %o, want 027 (previous value restored)", after)
	}
}

func TestUnixSocketCreatedUnderUmaskIsPrivate(t *testing.T) {
	path := filepath.Join(t.TempDir(), "clip.sock")
	var ln net.Listener
	if err := withUmask(func() error {
		l, err := net.Listen("unix", path)
		if err != nil {
			return err
		}
		ln = l
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perms := info.Mode().Perm(); perms&0o077 != 0 {
		t.Fatalf("socket mode = %04o; other users could connect before the explicit chmod", perms)
	}
}
