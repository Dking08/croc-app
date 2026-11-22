package tcp

import (
	"encoding/binary"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/schollz/croc/v11/src/codephrase"
	"github.com/schollz/croc/v11/src/comm"
	"github.com/schollz/croc/v11/src/crypt"
	"github.com/schollz/pake/v3"
	"github.com/stretchr/testify/require"
)

var oversizedHandshakeFrames = []struct {
	name     string
	stage    string
	size     int
	password string
}{
	{name: "first message", stage: "first", size: 64*1024 + 1, password: "pass123"},
	{name: "reported 64 MiB first message", stage: "first", size: 64 * 1024 * 1024, password: "pass123"},
	{name: "salt", stage: "salt", size: 65, password: "pass123"},
	{name: "password", stage: "password", size: 4*1024 + 1, password: "pass123"},
	{name: "configured long password", stage: "password", size: 8*1024 + 28 + 1, password: strings.Repeat("p", 8*1024)},
	{name: "early room", stage: "early room", size: 1025, password: "pass123"},
	{name: "room after banner", stage: "room", size: 1025, password: "pass123"},
}

func handshakeTestHeader(size int) []byte {
	header := make([]byte, 8)
	copy(header, comm.MAGIC_BYTES)
	binary.LittleEndian.PutUint32(header[4:], uint32(size))
	return header
}

// Complete PAKE without sending the salt, so tests can stop at each read.
func handshakeTestKey(t *testing.T, c *comm.Comm, saltSize int) (key, salt []byte) {
	t.Helper()
	initiator, err := pake.InitCurve(weakKey, 0, "siec")
	require.NoError(t, err)
	require.NoError(t, c.Send(initiator.Bytes()))
	response, err := c.ReceiveWithDeadline(time.Now().Add(2 * time.Second))
	require.NoError(t, err)
	require.NoError(t, initiator.Update(response))
	shared, err := initiator.SessionKey()
	require.NoError(t, err)
	key, salt, err = crypt.New(shared, make([]byte, saltSize))
	require.NoError(t, err)
	return key, salt
}

func sendHandshakeTestPassword(t *testing.T, c *comm.Comm, key []byte, password string, followingFrame []byte) {
	t.Helper()
	encrypted, err := crypt.Encrypt([]byte(password), key)
	require.NoError(t, err)
	frame := append(handshakeTestHeader(len(encrypted)), encrypted...)
	frame = append(frame, followingFrame...)
	// A single write keeps an early room frame ready when the server enters
	// its 5 ms read window, without depending on client goroutine scheduling.
	n, err := c.Connection().Write(frame)
	require.NoError(t, err)
	require.Equal(t, len(frame), n)
}

func sendOversizedHandshakeHeader(t *testing.T, c *comm.Comm, stage string, size int, password string) {
	t.Helper()
	header := handshakeTestHeader(size)
	if stage != "first" {
		key, salt := handshakeTestKey(t, c, 8)
		if stage != "salt" {
			require.NoError(t, c.Send(salt))
			if stage != "password" {
				if stage == "early room" {
					sendHandshakeTestPassword(t, c, key, password, header)
					return
				}
				sendHandshakeTestPassword(t, c, key, password, nil)
				banner, err := c.ReceiveWithDeadline(time.Now().Add(2 * time.Second))
				require.NoError(t, err)
				_, err = crypt.Decrypt(banner, key)
				require.NoError(t, err)
			}
		}
	}
	// Deliberately send no body: rejection must follow from the header alone.
	n, err := c.Connection().Write(header)
	require.NoError(t, err)
	require.Equal(t, len(header), n)
}

type handshakeTestResult struct {
	handshakeResult
	err error
}

func startHandshakeTestPeer(t *testing.T, password string, earlyRoom bool) (*comm.Comm, <-chan handshakeTestResult) {
	t.Helper()
	s := newDefaultServer()
	s.password = password
	if earlyRoom {
		s.fastAdmission = readyCapabilitySet(t, 1)
		s.banner = "data-port"
	}
	serverConn, clientConn := net.Pipe()
	serverComm, client := comm.New(serverConn), comm.New(clientConn)
	result := make(chan handshakeTestResult, 1)
	finished := make(chan struct{})
	go func() {
		defer close(finished)
		defer serverConn.Close()
		deadline := time.Now().Add(5 * time.Second)
		handshake, err := s.clientHandshake(serverComm, deadline, deadline)
		result <- handshakeTestResult{handshakeResult: handshake, err: err}
	}()
	t.Cleanup(func() {
		clientConn.Close()
		serverConn.Close()
		s.stop.Cancel()
		select {
		case <-finished:
		case <-time.After(time.Second):
			t.Error("handshake goroutine did not exit")
		}
	})
	return client, result
}

func TestPendingHandshakeRejectsOversizedFrames(t *testing.T) {
	for _, tc := range oversizedHandshakeFrames {
		t.Run(tc.name, func(t *testing.T) {
			client, result := startHandshakeTestPeer(t, tc.password, tc.stage == "early room")
			sendOversizedHandshakeHeader(t, client, tc.stage, tc.size, tc.password)
			select {
			case got := <-result:
				require.ErrorIs(t, got.err, comm.ErrMessageTooLarge)
			case <-time.After(time.Second):
				t.Fatal("oversized frame waited for a body instead of rejecting its header")
			}
		})
	}
}

func TestPendingHandshakeOversizedFramesReleaseCapacity(t *testing.T) {
	for _, tc := range oversizedHandshakeFrames {
		t.Run(tc.name, func(t *testing.T) {
			opts := []serverOptsFunc{
				WithMaxPendingHandshakes(1), WithMaxPendingHandshakesPerSource(1),
				WithHandshakeTimeout(10 * time.Second), WithFirstMessageTimeout(10 * time.Second),
				func(s *server) error { s.password = tc.password; return nil },
			}
			if tc.stage == "early room" {
				opts = append(opts, WithFastAdmission(readyCapabilitySet(t, 1)), WithBanner("data-port"))
			}
			s, address, stop := startConfiguredTestServer(t, opts...)
			defer stop()
			client, err := comm.NewConnection(address)
			require.NoError(t, err)
			defer client.Close()
			waitForPendingHandshakes(t, s, 1)
			sendOversizedHandshakeHeader(t, client, tc.stage, tc.size, tc.password)
			waitForRawConnectionClose(t, client.Connection())
			waitForPendingHandshakes(t, s, 0)
			_, sources := pendingHandshakeCounts(s)
			require.Empty(t, sources)
			// Both global and per-source capacity must be immediately reusable.
			healthy, _, _, err := ConnectToTCPServer(address, tc.password, "healthy-room", time.Second)
			require.NoError(t, err)
			healthy.Close()
		})
	}
}

func TestPendingHandshakeMessageLimitsAllowValidFrames(t *testing.T) {
	for _, tc := range []struct {
		name      string
		password  string
		room      string
		code      string
		saltSize  int
		earlyRoom bool
	}{
		{name: "legacy room derivation", password: "pass123", code: "1234-alpha-bravo-charlie", saltSize: 8},
		{name: "current room derivation", password: "pass123", code: "poker-hedge-floss", saltSize: 8, earlyRoom: true},
		{name: "legacy boundary sizes", password: strings.Repeat("p", 4096-28), room: strings.Repeat("r", 1024-28), saltSize: 64},
		{name: "pipelined boundary sizes", password: strings.Repeat("p", 4096-28), room: strings.Repeat("r", 1024-28), saltSize: 64, earlyRoom: true},
		{name: "long configured password", password: strings.Repeat("p", 8192), room: "room", saltSize: 8},
	} {
		t.Run(tc.name, func(t *testing.T) {
			room := tc.room
			if tc.code != "" {
				components, err := codephrase.Parse(tc.code)
				require.NoError(t, err)
				room = components.RoomName
			}
			client, result := startHandshakeTestPeer(t, tc.password, tc.earlyRoom)
			key, salt := handshakeTestKey(t, client, tc.saltSize)
			require.NoError(t, client.Send(salt))
			encryptedRoom, err := crypt.Encrypt([]byte(room), key)
			require.NoError(t, err)
			var following []byte
			if tc.earlyRoom {
				following = append(handshakeTestHeader(len(encryptedRoom)), encryptedRoom...)
			}
			sendHandshakeTestPassword(t, client, key, tc.password, following)
			banner, err := client.ReceiveWithDeadline(time.Now().Add(2 * time.Second))
			require.NoError(t, err)
			_, err = crypt.Decrypt(banner, key)
			require.NoError(t, err)
			if !tc.earlyRoom {
				require.NoError(t, client.Send(encryptedRoom))
			}
			select {
			case got := <-result:
				require.NoError(t, got.err)
				require.Equal(t, room, got.room)
			case <-time.After(time.Second):
				t.Fatal("valid handshake did not finish")
			}
		})
	}
}
