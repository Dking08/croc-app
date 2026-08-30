//go:build !plan9 && !js && !wasip1

package ssh

import (
	"errors"
	"syscall"
)

// maxSunPathLen is the maximum length of a Unix domain socket path on the
// current platform, derived from the kernel's sockaddr_un.sun_path field.
// This is 108 on Linux and 104 on macOS/BSD.
var maxSunPathLen = len(syscall.RawSockaddrUnix{}.Path)

// unixSocketsAvailable indicates whether the current platform supports Unix
// domain sockets. This is used to provide user-friendly error messages at
// runtime on unsupported platforms.
const unixSocketsAvailable = true

// unlink removes files and unlike os.Remove, directories are kept.
func unlink(path string) error {
	// Ignore EINTR like os.Remove, see ignoringEINTR in os/file_posix.go
	// for more details.
	for {
		err := syscall.Unlink(path)
		if !errors.Is(err, syscall.EINTR) {
			return err
		}
	}
}
