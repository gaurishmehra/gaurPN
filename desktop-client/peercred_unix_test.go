//go:build linux

package main

import (
	"net"
	"path/filepath"
	"testing"
)

// TestSameUserConnAcceptsSameUID exercises the real SO_PEERCRED path over a
// kernel-mediated socket: a connection from this process (same uid) passes.
//
// Cross-uid rejection cannot be tested in-process without setuid or root;
// the compare against os.Getuid() is covered by inspection and by the
// non-Unix-connection rejection below.
func TestSameUserConnAcceptsSameUID(t *testing.T) {
	path := filepath.Join(t.TempDir(), "cred.sock")
	ln, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	type acceptResult struct {
		conn net.Conn
		err  error
	}
	accepted := make(chan acceptResult, 1)
	go func() {
		conn, err := ln.Accept()
		accepted <- acceptResult{conn: conn, err: err}
	}()

	client, err := net.Dial("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()

	res := <-accepted
	if res.err != nil {
		t.Fatal(res.err)
	}
	defer res.conn.Close()
	if err := sameUserConn(res.conn); err != nil {
		t.Fatalf("same-uid peer must be accepted: %v", err)
	}
}

func TestSameUserConnRejectsNonUnixConn(t *testing.T) {
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()
	if err := sameUserConn(c1); err == nil {
		t.Fatal("a non-Unix connection must be rejected")
	}
}
