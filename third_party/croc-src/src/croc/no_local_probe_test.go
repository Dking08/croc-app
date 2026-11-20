package croc

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/schollz/croc/v11/src/comm"
	"github.com/schollz/croc/v11/src/pakekey"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

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

// App releases up to v6.1.0 answer the local probe with a "no-local" decline
// when local transfers are disabled. The receiver must fall back to the relay
// instead of failing the transfer.
func TestReceiveFallsBackToRelayWhenPeerDeclinesLocalProbe(t *testing.T) {
	relay := startTestRelay(t, 1)
	noLocal, err := json.Marshal(SimpleMessage{Kind: "no-local"})
	require.NoError(t, err)
	address, declined := startProbeReplyProxy(t, relay.address, noLocal)
	receiveDir := t.TempDir()
	t.Chdir(receiveDir)
	payload := []byte("a declined local probe must fall back to the relay\n")
	source := filepath.Join(t.TempDir(), "declined.txt")
	require.NoError(t, os.WriteFile(source, payload, 0o600))
	files, folders, folderCount, err := GetFilesInfo([]string{source}, false, false, nil)
	require.NoError(t, err)

	options := Options{
		IsSender: true, SharedSecret: "probe-declined", RelayAddress: address,
		RelayPassword: "pass123", NoPrompt: true, DisableLocal: true,
		DisableClipboard: true, Curve: "p256", Transport: TransportRelay,
	}
	sender, err := newTestClient(options)
	require.NoError(t, err)
	options.IsSender, options.Transport = false, TransportAuto
	// Force the public-relay probe without running multicast discovery.
	options.TestFlag = true
	receiver, err := newTestClient(options)
	require.NoError(t, err)
	defer cancelClients(sender, receiver)

	results := make(chan error, 2)
	go func() { results <- sender.Send(files, folders, folderCount) }()
	go func() { results <- receiver.Receive() }()
	waitForTransferResults(t, results, 2, 10*time.Second, sender, receiver)
	select {
	case <-declined:
	default:
		t.Fatal("transfer did not exercise a declined local probe")
	}
	received, err := os.ReadFile(filepath.Join(receiveDir, "declined.txt"))
	require.NoError(t, err)
	require.Equal(t, payload, received)
}

// A croc v10 sender answers the local probe with a pake2 that carries only
// Bytes and Kind. The receiver must report the PAKE version mismatch (which
// the app uses to offer its legacy engine), not a missing salt.
func TestReceiveReportsVersionMismatchForV10ProbeReply(t *testing.T) {
	relay := startTestRelay(t, 1)
	v10Reply, err := json.Marshal(struct {
		Bytes []byte
		Kind  string
	}{Bytes: []byte("v10 pake2"), Kind: "pake2"})
	require.NoError(t, err)
	address, replaced := startProbeReplyProxy(t, relay.address, v10Reply)
	t.Chdir(t.TempDir())
	source := filepath.Join(t.TempDir(), "v10.txt")
	require.NoError(t, os.WriteFile(source, []byte("v10\n"), 0o600))
	files, folders, folderCount, err := GetFilesInfo([]string{source}, false, false, nil)
	require.NoError(t, err)

	options := Options{
		IsSender: true, SharedSecret: "probe-v10", RelayAddress: address,
		RelayPassword: "pass123", NoPrompt: true, DisableLocal: true,
		DisableClipboard: true, Curve: "p256", Transport: TransportRelay,
	}
	sender, err := newTestClient(options)
	require.NoError(t, err)
	options.IsSender, options.Transport = false, TransportAuto
	options.TestFlag = true
	receiver, err := newTestClient(options)
	require.NoError(t, err)
	defer cancelClients(sender, receiver)

	go func() { _ = sender.Send(files, folders, folderCount) }()
	received := make(chan error, 1)
	go func() { received <- receiver.Receive() }()
	select {
	case err = <-received:
	case <-time.After(10 * time.Second):
		t.Fatal("receiver did not fail on the v10 probe reply")
	}
	select {
	case <-replaced:
	default:
		t.Fatal("transfer did not exercise the v10 probe reply")
	}
	var versionErr incompatiblePakeVersionError
	require.ErrorAs(t, err, &versionErr)
	require.Equal(t, 0, versionErr.got)
	require.Contains(t, err.Error(), "unsupported PAKE protocol version")
}

// Replace the first pake2 reply relayed to a client with reply, such as the
// "no-local" decline a v6.1.0 app sender sends. Everything else passes through
// unchanged.
func startProbeReplyProxy(t *testing.T, relayAddress string, reply []byte) (string, <-chan struct{}) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	ctx, cancel := context.WithCancel(t.Context())
	declined := make(chan struct{}, 1)
	var workers sync.WaitGroup
	workers.Go(func() {
		for {
			client, err := listener.Accept()
			if err != nil {
				return
			}
			workers.Go(func() {
				defer client.Close()
				upstream, err := (&net.Dialer{}).DialContext(ctx, "tcp", relayAddress)
				if err != nil {
					return
				}
				defer upstream.Close()
				stopClose := context.AfterFunc(ctx, func() {
					client.Close()
					upstream.Close()
				})
				defer stopClose()
				workers.Go(func() {
					defer upstream.Close()
					defer client.Close()
					_, _ = io.Copy(upstream, client)
				})

				reader := comm.New(upstream)
				for {
					data, err := reader.Receive()
					if err != nil {
						return
					}
					var probe SimpleMessage
					if json.Unmarshal(data, &probe) == nil && probe.Kind == "pake2" {
						select {
						case declined <- struct{}{}:
							data = reply
						default:
						}
					}
					frame := make([]byte, 8+len(data))
					copy(frame, comm.MAGIC_BYTES)
					binary.LittleEndian.PutUint32(frame[4:8], uint32(len(data)))
					copy(frame[8:], data)
					if _, err := client.Write(frame); err != nil {
						return
					}
				}
			})
		}
	})
	t.Cleanup(func() {
		cancel()
		listener.Close()
		done := make(chan struct{})
		go func() {
			workers.Wait()
			close(done)
		}()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("probe reply proxy did not stop")
		}
	})
	return listener.Addr().String(), declined
}
