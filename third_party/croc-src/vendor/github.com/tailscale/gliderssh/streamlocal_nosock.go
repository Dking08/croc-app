//go:build plan9 || js || wasip1

package ssh

import "errors"

// maxSunPathLen is set to the common Linux default (108) on platforms that
// lack Unix domain sockets. The value is used only by validateSocketPath;
// on unsupported platforms requests are rejected before reaching the kernel.
var maxSunPathLen = 108

// unixSocketsAvailable indicates whether the current platform supports Unix
// domain sockets. This is used to provide user-friendly error messages at
// runtime on unsupported platforms.
const unixSocketsAvailable = false

// unlink is a stub for platforms without Unix domain socket support.
func unlink(_ string) error {
	return errors.New("unix domain sockets are not supported on this platform")
}
