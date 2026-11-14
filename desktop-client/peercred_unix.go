//go:build linux

package main

import (
	"fmt"
	"net"
	"os"
	"syscall"
)

// sameUserConn reports whether the peer of an accepted Unix socket connection
// runs as the same user as this process. SO_PEERCRED is filled in by the
// kernel at connect time and cannot be forged by the peer. Any failure to
// read the credential is treated as a mismatch.
func sameUserConn(conn net.Conn) error {
	unixConn, ok := conn.(*net.UnixConn)
	if !ok {
		return fmt.Errorf("connection is not a Unix socket")
	}
	raw, err := unixConn.SyscallConn()
	if err != nil {
		return fmt.Errorf("socket credentials unavailable: %w", err)
	}
	var credErr error
	if err := raw.Control(func(fd uintptr) {
		cred, err := syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
		if err != nil {
			credErr = fmt.Errorf("SO_PEERCRED unavailable: %w", err)
			return
		}
		if cred.Uid != uint32(os.Getuid()) {
			credErr = fmt.Errorf("peer uid %d does not match this user (%d)", cred.Uid, os.Getuid())
		}
	}); err != nil {
		return fmt.Errorf("socket credentials unavailable: %w", err)
	}
	return credErr
}
