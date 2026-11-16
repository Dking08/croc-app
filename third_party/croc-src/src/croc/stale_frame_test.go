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

// Stock croc >= 11.6.0 receivers treat any timely non-pake2 reply to their
// local probe as fatal, so a sender with local transfers disabled must still
// complete the probe PAKE and answer "ips?" with an empty endpoint list.
func TestSenderWaitForHandshakeAnswersProbeWhenLocalDisabled(t *testing.T) {
	clientConn, serverConn := net.Pipe()
	t.Cleanup(func() {
		_ = clientConn.Close()
		_ = serverConn.Close()
	})

	clientComm := comm.New(clientConn)
	serverComm := comm.New(serverConn)

	const passphrase = "test-passphrase"
	const curve = "p256"
	client := &Client{
		Options: Options{
			DisableLocal: true,
			RoomName:     "test-room",
		},
		pakePassphrase: passphrase,
	}
	client.stop = newStop(context.Background())

	done := make(chan error, 1)
	go func() {
		done <- client.senderWaitForHandshake(clientComm)
	}()

	A, err := pakekey.Init([]byte(passphrase), 0, curve, pakekey.PurposeLocalProbe, "test-room")
	require.NoError(t, err)
	initiator := A.Bytes()
	pake1Bytes, err := json.Marshal(SimpleMessage{
		Bytes:   initiator,
		Kind:    "pake1",
		Version: pakekey.ProtocolVersion,
		Curve:   curve,
	})
	require.NoError(t, err)
	require.NoError(t, serverComm.Send(pake1Bytes))

	respBytes, err := serverComm.Receive()
	require.NoError(t, err)
	var resp SimpleMessage
	require.NoError(t, json.Unmarshal(respBytes, &resp))
	require.Equal(t, "pake2", resp.Kind)
	require.Equal(t, curve, resp.Curve)
	require.Len(t, resp.Bytes2, pakekey.SaltSize)

	require.NoError(t, A.Update(resp.Bytes))
	sharedKey, err := A.SessionKey()
	require.NoError(t, err)
	keys, err := pakekey.Derive(sharedKey, pakekey.Context{
		Purpose:   pakekey.PurposeLocalProbe,
		Room:      "test-room",
		Curve:     curve,
		Initiator: initiator,
		Responder: resp.Bytes,
		Salt:      resp.Bytes2,
	})
	require.NoError(t, err)

	req, err := encryptLocalProbePayload(keys.EncryptionKey, ipRequest)
	require.NoError(t, err)
	require.NoError(t, serverComm.Send(req))
	encIPs, err := serverComm.Receive()
	require.NoError(t, err)
	plainIPs, err := decryptLocalProbePayload(keys.EncryptionKey, encIPs)
	require.NoError(t, err)
	var ips []string
	require.NoError(t, json.Unmarshal(plainIPs, &ips))
	assert.Empty(t, ips, "no local endpoints may be advertised when local transfers are disabled")

	require.NoError(t, serverComm.Send(handshakeRequest))
	select {
	case err := <-done:
		assert.NoError(t, err)
	case <-time.After(5 * time.Second):
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
