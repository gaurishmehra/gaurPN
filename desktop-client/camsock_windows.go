//go:build windows

package main

import "syscall"

// setCamSocketBuffers bounds the kernel socket queues on Windows, where the
// descriptor is a syscall.Handle. Best effort: a stack that refuses the size
// keeps its default rather than failing the connection.
func setCamSocketBuffers(fd int) {
	_ = syscall.SetsockoptInt(syscall.Handle(fd), syscall.SOL_SOCKET, syscall.SO_RCVBUF, camSocketBufferBytes)
	_ = syscall.SetsockoptInt(syscall.Handle(fd), syscall.SOL_SOCKET, syscall.SO_SNDBUF, camSocketBufferBytes)
}
