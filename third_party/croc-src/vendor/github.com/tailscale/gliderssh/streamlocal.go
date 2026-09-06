package ssh

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"

	gossh "golang.org/x/crypto/ssh"
)

const (
	forwardedUnixChannelType = "forwarded-streamlocal@openssh.com"
)

// directStreamLocalChannelData data struct as specified in OpenSSH's protocol
// extensions document, Section 2.4.
// https://cvsweb.openbsd.org/src/usr.bin/ssh/PROTOCOL?annotate=HEAD
type directStreamLocalChannelData struct {
	SocketPath string

	Reserved1 string
	Reserved2 uint32
}

// DirectStreamLocalHandler provides Unix forwarding from client -> server. It
// can be enabled by adding it to the server's ChannelHandlers under
// `direct-streamlocal@openssh.com`.
//
// Unix socket support on Windows is not widely available, so this handler may
// not work on all Windows installations and is not tested on Windows.
func DirectStreamLocalHandler(srv *Server, _ *gossh.ServerConn, newChan gossh.NewChannel, ctx Context) {
	var d directStreamLocalChannelData
	err := gossh.Unmarshal(newChan.ExtraData(), &d)
	if err != nil {
		_ = newChan.Reject(gossh.ConnectionFailed, "error parsing direct-streamlocal data: "+err.Error())
		return
	}

	if srv.LocalUnixForwardingCallback == nil {
		_ = newChan.Reject(gossh.Prohibited, "unix forwarding is disabled")
		return
	}
	dconn, err := srv.LocalUnixForwardingCallback(ctx, d.SocketPath)
	if err != nil {
		if errors.Is(err, ErrRejected) {
			_ = newChan.Reject(gossh.Prohibited, rejectedMessage(err))
			return
		}
		_ = newChan.Reject(gossh.ConnectionFailed, fmt.Sprintf("dial unix socket %q: %v", d.SocketPath, err))
		return
	}

	ch, reqs, err := newChan.Accept()
	if err != nil {
		_ = dconn.Close()
		return
	}
	go gossh.DiscardRequests(reqs)

	bicopy(ctx, ch, dconn)
}

// remoteUnixForwardRequest describes the extra data sent in a
// streamlocal-forward@openssh.com containing the socket path to bind to.
type remoteUnixForwardRequest struct {
	SocketPath string
}

// remoteUnixForwardChannelData describes the data sent as the payload in the new
// channel request when a Unix connection is accepted by the listener.
//
// See OpenSSH PROTOCOL, Section 2.4 "forwarded-streamlocal@openssh.com":
// https://github.com/openssh/openssh-portable/blob/master/PROTOCOL
//
// See also the client-side struct in x/crypto/ssh (forwardedStreamLocalPayload):
// https://cs.opensource.google/go/x/crypto/+/master:ssh/streamlocal.go
type remoteUnixForwardChannelData struct {
	SocketPath string
	Reserved   string
}

// forwardKey identifies a forwarded Unix socket scoped to a specific
// SSH session, preventing cross-session collisions and ensuring that
// one session cannot cancel another session's forward.
type forwardKey struct {
	sessionID string
	addr      string
}

// ForwardedUnixHandler can be enabled by creating a ForwardedUnixHandler and
// adding the HandleSSHRequest callback to the server's RequestHandlers under
// `streamlocal-forward@openssh.com` and
// `cancel-streamlocal-forward@openssh.com`
//
// Unix socket support on Windows is not widely available, so this handler may
// not work on all Windows installations and is not tested on Windows.
type ForwardedUnixHandler struct {
	sync.Mutex
	forwards map[forwardKey]net.Listener
}

func (h *ForwardedUnixHandler) HandleSSHRequest(ctx Context, srv *Server, req *gossh.Request) (bool, []byte) {
	h.Lock()
	if h.forwards == nil {
		h.forwards = make(map[forwardKey]net.Listener)
	}
	h.Unlock()
	conn, ok := ctx.Value(ContextKeyConn).(*gossh.ServerConn)
	if !ok {
		// TODO: log cast failure
		return false, nil
	}

	switch req.Type {
	case "streamlocal-forward@openssh.com":
		var reqPayload remoteUnixForwardRequest
		err := gossh.Unmarshal(req.Payload, &reqPayload)
		if err != nil {
			// TODO: log parse failure
			return false, nil
		}

		if srv.ReverseUnixForwardingCallback == nil {
			return false, []byte("unix forwarding is disabled")
		}

		addr := reqPayload.SocketPath
		key := forwardKey{
			sessionID: ctx.SessionID(),
			addr:      addr,
		}

		// Use a nil sentinel to claim the key while the callback runs,
		// preventing a concurrent request from racing past the check.
		h.Lock()
		_, ok := h.forwards[key]
		if ok {
			h.Unlock()
			// In cases where ExitOnForwardFailure=yes is set, returning
			// false here will cause the connection to be closed. To avoid
			// this, and to match OpenSSH behavior, we silently ignore
			// the second forward request.
			// TODO: log duplicate forward
			return true, nil
		}
		h.forwards[key] = nil // placeholder; claimed
		h.Unlock()

		ln, err := srv.ReverseUnixForwardingCallback(ctx, addr)
		if err != nil {
			h.Lock()
			delete(h.forwards, key)
			h.Unlock()
			if errors.Is(err, ErrRejected) {
				return false, []byte(rejectedMessage(err))
			}
			// TODO: log unix listen failure
			return false, nil
		}

		h.Lock()
		h.forwards[key] = ln
		h.Unlock()

		// Use the connection-scoped context for bicopy so active data
		// transfers survive listener shutdown. The derived context is
		// only used for the accept loop lifecycle.
		connCtx := ctx
		ctx, cancel := context.WithCancel(ctx)
		go func() {
			<-ctx.Done()
			_ = ln.Close()
		}()
		go func() {
			defer cancel()

			for {
				c, err := ln.Accept()
				if err != nil {
					// closed below
					break
				}
				payload := gossh.Marshal(&remoteUnixForwardChannelData{
					SocketPath: addr,
				})

				go func() {
					ch, reqs, err := conn.OpenChannel(forwardedUnixChannelType, payload)
					if err != nil {
						_ = c.Close()
						return
					}
					go gossh.DiscardRequests(reqs)
					bicopy(connCtx, ch, c)
				}()
			}

			h.Lock()
			ln2, ok := h.forwards[key]
			if ok && ln2 == ln {
				delete(h.forwards, key)
			}
			h.Unlock()
			_ = ln.Close()
		}()

		return true, nil

	case "cancel-streamlocal-forward@openssh.com":
		var reqPayload remoteUnixForwardRequest
		err := gossh.Unmarshal(req.Payload, &reqPayload)
		if err != nil {
			// TODO: log parse failure
			return false, nil
		}
		key := forwardKey{
			sessionID: ctx.SessionID(),
			addr:      reqPayload.SocketPath,
		}
		h.Lock()
		ln, ok := h.forwards[key]
		if ok {
			delete(h.forwards, key)
		}
		h.Unlock()
		if ok {
			_ = ln.Close()
		}
		return true, nil

	default:
		return false, nil
	}
}

// rejectedMessage returns a user-facing rejection message. If err is a bare
// ErrRejected (no wrapping context), it returns the generic "unix forwarding
// is disabled" for backward compatibility. Wrapped errors (e.g. rejectionError)
// return their descriptive message.
func rejectedMessage(err error) string {
	if err == ErrRejected { //nolint:errorlint // intentional identity check
		return "unix forwarding is disabled"
	}
	return err.Error()
}

// rejectionError wraps ErrRejected with a descriptive reason for the SSH
// client. It satisfies errors.Is(err, ErrRejected) so that handlers send
// the rejection as "administratively prohibited" with the descriptive message.
type rejectionError struct {
	reason string
}

func (e *rejectionError) Error() string { return e.reason }
func (e *rejectionError) Unwrap() error { return ErrRejected }

// UnixForwardingOptions configures the behavior of
// NewLocalUnixForwardingCallback and NewReverseUnixForwardingCallback.
type UnixForwardingOptions struct {
	// AllowAll, if true, permits any absolute socket path without directory
	// restrictions. AllowedDirectories and DeniedPrefixes are ignored when
	// set. Basic sanitization (absolute path, length, filepath.Clean) is
	// still applied.
	AllowAll bool

	// AllowedDirectories is the list of directory prefixes under which
	// socket paths are permitted. Paths are cleaned with filepath.Clean
	// before prefix matching. Ignored when AllowAll is true.
	// When AllowAll is false and AllowedDirectories is empty, all
	// requests are denied.
	AllowedDirectories []string

	// DeniedPrefixes is an optional denylist applied after the allowlist.
	// Useful for excluding sensitive sub-paths within allowed directories
	// (e.g. /run/user/1000/systemd/ within /run/user/1000/).
	// Ignored when AllowAll is true.
	DeniedPrefixes []string

	// BindUnlink controls whether an existing socket file is removed
	// before binding (reverse forwarding only). Only socket-type files
	// are removed; regular files are left in place and the listen will
	// fail with EADDRINUSE. Default: false.
	// Matches OpenSSH's StreamLocalBindUnlink (default: no).
	BindUnlink bool

	// BindMask is the umask applied when creating listening sockets
	// (reverse forwarding only). The resulting socket permission is
	// 0666 &^ BindMask. If nil, defaults to 0177 (socket permission
	// 0600, owner read/write only).
	// Matches OpenSSH's StreamLocalBindMask.
	BindMask *os.FileMode

	// PathValidator is an optional additional validation function called
	// after built-in checks pass. Return an error wrapping ErrRejected
	// (or a *rejectionError) for "administratively prohibited" semantics,
	// or any other error for "connection failed."
	PathValidator func(ctx Context, socketPath string) error
}

// validateSocketPath checks that socketPath is safe according to opts.
// It returns the cleaned path on success. Returned errors wrap ErrRejected
// so that handlers report them as "administratively prohibited" with a
// descriptive message.
func validateSocketPath(socketPath string, opts UnixForwardingOptions) (string, error) {
	if !filepath.IsAbs(socketPath) {
		return "", &rejectionError{reason: "socket path must be absolute"}
	}

	cleaned := filepath.Clean(socketPath)

	if strings.ContainsRune(cleaned, 0) {
		return "", &rejectionError{reason: "socket path contains NUL byte"}
	}

	if len(cleaned) >= maxSunPathLen {
		return "", &rejectionError{
			reason: fmt.Sprintf("socket path too long (%d >= %d)", len(cleaned), maxSunPathLen),
		}
	}

	if !opts.AllowAll {
		if len(opts.AllowedDirectories) == 0 {
			return "", &rejectionError{
				reason: fmt.Sprintf("socket path %q is not in an allowed directory", cleaned),
			}
		}

		allowed := false
		for _, dir := range opts.AllowedDirectories {
			prefix := filepath.Clean(dir)
			if !strings.HasSuffix(prefix, string(filepath.Separator)) {
				prefix += string(filepath.Separator)
			}
			if strings.HasPrefix(cleaned, prefix) {
				allowed = true
				break
			}
		}
		if !allowed {
			return "", &rejectionError{
				reason: fmt.Sprintf("socket path %q is not in an allowed directory", cleaned),
			}
		}

		for _, denied := range opts.DeniedPrefixes {
			prefix := filepath.Clean(denied)
			if cleaned == prefix || strings.HasPrefix(cleaned, prefix+string(filepath.Separator)) {
				return "", &rejectionError{
					reason: fmt.Sprintf("socket path %q is denied", cleaned),
				}
			}
		}
	}

	return cleaned, nil
}

// validateAndResolveSocketPath validates socketPath lexically, then resolves
// its symlinks and re-checks that the real destination is still within
// opts.AllowedDirectories and not excluded by opts.DeniedPrefixes. It returns
// the resolved path to dial.
//
// The lexical check alone is insufficient because the kernel connects to the
// symlink target: a symlink inside an allowed directory could otherwise
// redirect the connection to a socket outside it (e.g. a link in the user's
// home dir pointing at /var/run/docker.sock). The allow/deny entries are
// resolved as well, so legitimately symlinked directories (e.g. Linux's
// /var/run -> /run, or macOS's /tmp -> /private/tmp) continue to match.
func validateAndResolveSocketPath(socketPath string, opts UnixForwardingOptions) (string, error) {
	cleaned, err := validateSocketPath(socketPath, opts)
	if err != nil {
		return "", err
	}
	if opts.AllowAll {
		return cleaned, nil
	}

	resolved, err := filepath.EvalSymlinks(cleaned)
	if err != nil {
		return "", err
	}
	if resolved == cleaned {
		// No symlinks were involved; the lexical check already
		// authorized this exact path.
		return cleaned, nil
	}

	opts.AllowedDirectories = resolvePrefixes(opts.AllowedDirectories)
	opts.DeniedPrefixes = resolvePrefixes(opts.DeniedPrefixes)
	if _, err := validateSocketPath(resolved, opts); err != nil {
		return "", err
	}
	return resolved, nil
}

// validateAndResolveListenPath validates socketPath, then resolves symlinks in its
// parent directory and re-checks that the real bind location is still within
// opts.AllowedDirectories and not excluded by opts.DeniedPrefixes. It returns the
// resolved path to bind.
//
// Unlike validateAndResolveSocketPath, the socket file itself does not yet have to exist.
func validateAndResolveListenPath(socketPath string, opts UnixForwardingOptions) (string, error) {
	cleaned, err := validateSocketPath(socketPath, opts)
	if err != nil {
		return "", err
	} else if opts.AllowAll {
		return cleaned, nil
	}

	dir := filepath.Dir(cleaned)
	resolvedDir, err := filepath.EvalSymlinks(dir) // evaluate on directory since socket may not exist yet
	if err != nil {
		return "", err
	} else if resolvedDir == dir {
		// No symlinks in the parent, the lexical check already validated this exact path
		return cleaned, nil
	}

	resolved := filepath.Join(resolvedDir, filepath.Base(cleaned))
	opts.AllowedDirectories = resolvePrefixes(opts.AllowedDirectories)
	opts.DeniedPrefixes = resolvePrefixes(opts.DeniedPrefixes)
	if _, err := validateSocketPath(resolved, opts); err != nil {
		return "", err
	}
	// Technically a symlink wont be able to bind(), but for clarity and defense in depth check it
	if info, err := os.Lstat(resolved); err == nil && info.Mode().Type() == os.ModeSymlink {
		return "", &rejectionError{reason: fmt.Sprintf("socket path %q is a symlink", resolved)}
	}

	return resolved, nil
}

// resolvePrefixes returns prefixes with each entry's symlinks resolved.
// Entries that cannot be resolved (e.g. they do not exist) are passed
// through unchanged so they still participate in lexical matching.
func resolvePrefixes(prefixes []string) []string {
	out := slices.Clone(prefixes)
	for i, p := range prefixes {
		if r, err := filepath.EvalSymlinks(p); err == nil {
			out[i] = r
		}
	}
	return out
}

// NewLocalUnixForwardingCallback returns a LocalUnixForwardingCallback that
// validates socket paths against the provided options before dialing.
// Path validation errors are reported to the SSH client as
// "administratively prohibited" rejections with descriptive messages.
func NewLocalUnixForwardingCallback(opts UnixForwardingOptions) LocalUnixForwardingCallback {
	if !unixSocketsAvailable {
		return func(_ Context, _ string) (net.Conn, error) {
			return nil, &rejectionError{reason: "unix domain socket forwarding is not supported on this platform"}
		}
	}
	return func(ctx Context, socketPath string) (net.Conn, error) {
		resolved, err := validateAndResolveSocketPath(socketPath, opts)
		if err != nil {
			return nil, err
		}
		if opts.PathValidator != nil {
			if err := opts.PathValidator(ctx, resolved); err != nil {
				return nil, err
			}
		}

		var d net.Dialer
		return d.DialContext(ctx, "unix", resolved)
	}
}

// NewReverseUnixForwardingCallback returns a ReverseUnixForwardingCallback
// that validates socket paths against the provided options before listening.
//
// Unlike a bare net.Listen, this callback:
//   - Validates the socket path against allow/deny lists
//   - Does not create parent directories
//   - Applies a restrictive permission mask (default 0177 / mode 0600)
//   - Only unlinks existing socket files when BindUnlink is true (not
//     regular files or directories)
func NewReverseUnixForwardingCallback(opts UnixForwardingOptions) ReverseUnixForwardingCallback {
	if !unixSocketsAvailable {
		return func(_ Context, _ string) (net.Listener, error) {
			return nil, &rejectionError{reason: "unix domain socket forwarding is not supported on this platform"}
		}
	}
	return func(ctx Context, socketPath string) (net.Listener, error) {
		cleaned, err := validateAndResolveListenPath(socketPath, opts)
		if err != nil {
			return nil, err
		}
		if opts.PathValidator != nil {
			if err := opts.PathValidator(ctx, cleaned); err != nil {
				return nil, err
			}
		}

		if opts.BindUnlink {
			// Only unlink if the existing file is a socket or does
			// not exist. Regular files and directories are left in
			// place so that net.Listen fails with EADDRINUSE rather
			// than silently deleting user data.
			if info, serr := os.Lstat(cleaned); serr == nil {
				if info.Mode().Type() == os.ModeSocket {
					if uerr := unlink(cleaned); uerr != nil && !errors.Is(uerr, fs.ErrNotExist) {
						return nil, fmt.Errorf("failed to unlink existing socket %q: %w", cleaned, uerr)
					}
				}
			}
		}

		lc := &net.ListenConfig{}
		ln, err := lc.Listen(ctx, "unix", cleaned)
		if err != nil {
			return nil, fmt.Errorf("failed to listen on unix socket %q: %w", cleaned, err)
		}

		// Apply socket permission mask. Default 0177 (mode 0600),
		// matching OpenSSH's StreamLocalBindMask.
		mask := os.FileMode(0o177)
		if opts.BindMask != nil {
			mask = *opts.BindMask
		}
		mode := os.FileMode(0o666) &^ mask
		if err := os.Chmod(cleaned, mode); err != nil {
			_ = ln.Close()
			return nil, fmt.Errorf("failed to set permissions on socket %q: %w", cleaned, err)
		}

		return ln, nil
	}
}

// UserSocketDirectories returns common socket directory prefixes for a user,
// suitable for use as UnixForwardingOptions.AllowedDirectories. The returned
// list includes the user's home directory, /tmp, and the XDG runtime
// directory (/run/user/<uid>).
func UserSocketDirectories(homeDir string, uid string) []string {
	return []string{
		homeDir,
		"/tmp",
		filepath.Join("/run/user", uid),
	}
}
