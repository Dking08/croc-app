package croc

import (
	"context"
	"encoding/json"
	"net"
	"testing"
	"time"

	"github.com/schollz/croc/v11/src/comm"
	"github.com/schollz/croc/v11/src/message"
	"github.com/schollz/croc/v11/src/pakekey"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestIsStaleControlFrame(t *testing.T) {
	assert.True(t, isStaleControlFrame(ipRequest))
	assert.True(t, isStaleControlFrame(handshakeRequest))

	pake1JSON, err := json.Marshal(SimpleMessage{Kind: "pake1", Version: pakekey.ProtocolVersion})
	require.NoError(t, err)
	assert.True(t, isStaleControlFrame(pake1JSON))

	pake2JSON, err := json.Marshal(SimpleMessage{Kind: "pake2", Version: pakekey.ProtocolVersion})
	require.NoError(t, err)
	assert.True(t, isStaleControlFrame(pake2JSON))

	noLocalJSON, err := json.Marshal(SimpleMessage{Kind: "no-local"})
	require.NoError(t, err)
	assert.True(t, isStaleControlFrame(noLocalJSON))

	// A real PAKE transfer message (compressed) is not a stale frame
	validPAKE, err := message.Encode(nil, message.Message{Type: message.TypePAKE})
	require.NoError(t, err)
	assert.False(t, isStaleControlFrame(validPAKE))

	// Arbitrary bytes
	assert.False(t, isStaleControlFrame([]byte("unknown-data")))
}

func TestSenderWaitForHandshakeDeclinesWhenLocalDisabled(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	t.Cleanup(func() {
		_ = clientConn.Close()
		_ = serverConn.Close()
	})

	clientComm := comm.New(clientConn)
	serverComm := comm.New(serverConn)

	client := &Client{
		Options: Options{
			DisableLocal: true,
			RoomName:     "test-room",
		},
	}
	client.stop = newStop(context.Background())

	done := make(chan error, 1)
	go func() {
		done <- client.senderWaitForHandshake(clientComm)
	}()

	// Peer sends pake1
	pake1Msg := SimpleMessage{
		Kind:    "pake1",
		Version: pakekey.ProtocolVersion,
		Curve:   "p256",
	}
	pake1Bytes, err := json.Marshal(pake1Msg)
	require.NoError(t, err)
	require.NoError(t, serverComm.Send(pake1Bytes))

	// Sender should immediately reply with no-local
	respBytes, err := serverComm.Receive()
	require.NoError(t, err)

	var resp SimpleMessage
	require.NoError(t, json.Unmarshal(respBytes, &resp))
	assert.Equal(t, "no-local", resp.Kind)

	// Now peer sends handshakeRequest
	require.NoError(t, serverComm.Send(handshakeRequest))

	select {
	case err := <-done:
		assert.NoError(t, err)
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for senderWaitForHandshake to complete")
	}
}

func TestProcessMessageDiscardsStaleFrameWhenKeyNil(t *testing.T) {
	client := &Client{}
	client.stop = newStop(context.Background())

	pake2Msg := SimpleMessage{
		Kind:    "pake2",
		Version: pakekey.ProtocolVersion,
	}
	pake2Bytes, err := json.Marshal(pake2Msg)
	require.NoError(t, err)

	attempt := &transferAttemptState{}
	// When c.Key is nil, stale control frame should be discarded with done=false, err=nil
	done, err := client.processMessage(pake2Bytes, attempt)
	assert.False(t, done)
	assert.NoError(t, err)
}
