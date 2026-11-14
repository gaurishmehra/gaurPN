//go:build unix

package main

import "syscall"

// withUmask runs fn with the process umask narrowed to 0o077 and restores the
// previous umask when it returns. Anything fn creates on the filesystem (such
// as the clipboard Unix socket) is inaccessible to other local users from the
// moment it appears, which closes the create-then-chmod race.
func withUmask(fn func() error) error {
	prev := syscall.Umask(0o077)
	defer syscall.Umask(prev)
	return fn()
}
