//go:build !windows

package main

import "syscall"

// setCamSocketBuffers bounds the kernel socket queues on POSIX systems.
// Best effort: a stack that refuses the size keeps its default rather than
// failing the connection.
func setCamSocketBuffers(fd int) {
	_ = syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_RCVBUF, camSocketBufferBytes)
	_ = syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_SNDBUF, camSocketBufferBytes)
}
