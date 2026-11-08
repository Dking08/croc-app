package ssh

import (
	"context"
	"net"
	"sync/atomic"
	"time"
)

type serverConn struct {
	net.Conn

	idleTimeout   time.Duration
	maxDeadline   time.Time
	closeCanceler context.CancelFunc

	handshakeDeadline atomic.Pointer[time.Time] // during the handshake; nil once it completes
}

func (c *serverConn) Write(p []byte) (n int, err error) {
	if c.idleTimeout > 0 {
		c.updateDeadline()
	}
	n, err = c.Conn.Write(p)
	if _, isNetErr := err.(net.Error); isNetErr && c.closeCanceler != nil {
		c.closeCanceler()
	}
	return
}

func (c *serverConn) Read(b []byte) (n int, err error) {
	if c.idleTimeout > 0 {
		c.updateDeadline()
	}
	n, err = c.Conn.Read(b)
	if _, isNetErr := err.(net.Error); isNetErr && c.closeCanceler != nil {
		c.closeCanceler()
	}
	return
}

func (c *serverConn) Close() (err error) {
	err = c.Conn.Close()
	if c.closeCanceler != nil {
		c.closeCanceler()
	}
	return
}

func (c *serverConn) updateDeadline() {
	deadline := c.maxDeadline

	// handshakeDeadline is nil once the handshake completes.
	if hsDeadline := c.handshakeDeadline.Load(); hsDeadline != nil && (deadline.IsZero() || hsDeadline.Before(deadline)) {
		deadline = *hsDeadline
	}

	if c.idleTimeout > 0 {
		idleDeadline := time.Now().Add(c.idleTimeout)
		if deadline.IsZero() || idleDeadline.Before(deadline) {
			deadline = idleDeadline
		}
	}

	c.Conn.SetDeadline(deadline)
}
