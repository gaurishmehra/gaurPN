//go:build !unix

package main

// withUmask is a no-op where the platform has no umask. The explicit chmod
// after listen remains the only guard there.
func withUmask(fn func() error) error {
	return fn()
}
