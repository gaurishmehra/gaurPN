//go:build !linux

package main

import "net"

// sameUserConn is an allow-all stub on platforms without SO_PEERCRED. The
// 0600 socket mode remains the access guard there.
func sameUserConn(net.Conn) error {
	return nil
}
