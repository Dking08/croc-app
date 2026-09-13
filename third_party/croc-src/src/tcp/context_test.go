package tcp

import (
	"context"
	"io"
	"net"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestConnectToTCPServerControlContextCancelsHandshake(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	t.Cleanup(func() { _ = listener.Close() })
	accepted := make(chan struct{})
	go func() {
		connection, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		close(accepted)
		defer connection.Close()
		_, _ = io.Copy(io.Discard, connection)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	started := time.Now()
	connection, _, _, _, err := ConnectToTCPServerControlContext(
		ctx, listener.Addr().String(), "pass", "room", time.Second,
	)
	require.ErrorIs(t, err, context.DeadlineExceeded)
	require.Nil(t, connection)
	require.Less(t, time.Since(started), time.Second)
	select {
	case <-accepted:
	case <-time.After(time.Second):
		t.Fatal("test server did not accept the relay connection")
	}
}

func TestConnectToTCPServerWithCapabilityContextCancelsFastHandshake(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	t.Cleanup(func() { _ = listener.Close() })
	accepted := make(chan struct{})
	closed := make(chan struct{})
	go func() {
		connection, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		close(accepted)
		defer connection.Close()
		_, _ = io.Copy(io.Discard, connection)
		close(closed)
	}()

	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		connection, _, _, _, connectErr := ConnectToTCPServerWithCapabilityContext(
			ctx, listener.Addr().String(), "pass", "room-0", "capability", time.Second,
		)
		if connection != nil {
			connection.Close()
		}
		result <- connectErr
	}()
	select {
	case <-accepted:
	case <-time.After(time.Second):
		t.Fatal("test server did not accept the fast admission connection")
	}
	started := time.Now()
	cancel()
	select {
	case connectErr := <-result:
		require.ErrorIs(t, connectErr, context.Canceled)
		require.Less(t, time.Since(started), 500*time.Millisecond)
	case <-time.After(500 * time.Millisecond):
		t.Fatal("fast admission handshake did not stop after cancellation")
	}
	select {
	case <-closed:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("canceled fast admission handshake did not close its connection")
	}
}
