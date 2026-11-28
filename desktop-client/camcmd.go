package main

// The "cam" command: watch a trusted Android phone's camera live.
//
// Wire spec (camera stream v1, pinned byte-for-byte against the Android
// client — do not deviate):
//
//	request    sealed kind "request"; inner carries kind "camera",
//	           expires_ts = now+120000ms, and a gaur-request-grant-v1 grant
//	session    session_id = hex(HMAC-SHA256(K_peer, "gaur-cam-session-v1:"+request_id))
//	           K_cam      = HMAC-SHA256(K_peer, "gaur-camera-v1:"+session_id)
//	video      the relay's existing /pipe endpoint: the phone POSTs
//	           transfer_id=<session_id>&role=sender, the desktop GETs
//	           transfer_id=<session_id>&role=receiver&flush=1
//	frame      [4-byte BE length L][12-byte nonce][L-28 ciphertext]
//	           [16-byte GCM tag], AES-256-GCM under K_cam with a fresh nonce
//	           per frame; AAD = "cam:v1:<session_id>:<frame_index>", the index
//	           counting frames from 0 (frame 0 is the HEVC csd)
//	controls   sealed kind "cam_control" over the same /ws link; inner
//	           {"request_id","session_id","action","on"?,"tier"?,"ts"} with
//	           action torch(on) | lens | tier | stop
//	teardown   event-driven: the phone closes its POST body, the relay closes
//	           the receiver GET, the desktop sees stream EOF. No timers.
//
// The relay never sees anything but the session id and ciphertext: the frames
// are end-to-end encrypted between phone and desktop, like every other
// payload in the cluster.

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"regexp"
	"slices"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

// Pinned wire values for camera stream v1. Both sides derive these
// independently; changing any label, length, or ordering breaks parity with
// the Android client.
const (
	// camRequestTTL pins expires_ts: the phone's Accept/Decline notification
	// is valid for exactly 120 seconds.
	camRequestTTL = 120 * time.Second
	// camMaxFrameLen is the wire cap on L. A record header promising more
	// than 8 MiB aborts the stream instead of being buffered.
	camMaxFrameLen = 8 << 20
	// camFrameHeaderSize is the 4-byte big-endian length prefix.
	camFrameHeaderSize = 4
	// camNonceSize and camTagSize are the AES-256-GCM constants of the frame
	// record; camFrameOverhead is what L adds over the plaintext.
	camNonceSize     = 12
	camTagSize       = 16
	camFrameOverhead = camNonceSize + camTagSize
	// camControlWriteTimeout bounds one control envelope write so a stalled
	// relay link cannot wedge the key loop or the SIGINT exit path.
	camControlWriteTimeout = 3 * time.Second
	// camPlayerDrainTimeout bounds the wait for the player to exit after its
	// stdin closes; a player that ignores EOF is killed rather than hanging
	// the CLI open. It is local process management, not protocol state.
	camPlayerDrainTimeout = 5 * time.Second
	// camRedialFloor is the minimum spacing between pipe dials. The real
	// relay already waits out its 60-second rendezvous window before the
	// 504, so the floor only stops a hot re-dial loop against a broken relay.
	camRedialFloor = time.Second
)

var (
	// errCamRendezvousTimeout marks a relay 504: the phone has not opened the
	// sender side of the pipe within the relay's rendezvous window.
	errCamRendezvousTimeout = errors.New("pipe rendezvous timeout")
	// errCamRequestExpired marks the accept window closing without a stream.
	errCamRequestExpired = errors.New("camera request expired")
)

// camTierSpec is one pinned quality tier. The geometry is documentary for the
// desktop — the phone owns the encoder — but the tier names are wire values,
// and the numbers are pinned so tests can assert them.
type camTierSpec struct {
	Width, Height, FPS, Bitrate int
}

// camTierSpecs is the pinned v1 tier table. The default (and key "1") is high.
var camTierSpecs = map[string]camTierSpec{
	"high":     {Width: 1920, Height: 1080, FPS: 30, Bitrate: 8000000},
	"balanced": {Width: 1280, Height: 720, FPS: 30, Bitrate: 3500000},
	"low":      {Width: 640, Height: 360, FPS: 30, Bitrate: 500000},
}

// deriveCamSessionID returns the camera session id both sides compute from
// the request id: the full hex HMAC under K_peer, in the style of
// DeriveSessionID but without its 16-char truncation — the pipe endpoint and
// K_cam both bind to the whole value.
func deriveCamSessionID(peerCryptoKey []byte, requestID string) string {
	mac := hmac.New(sha256.New, peerCryptoKey)
	mac.Write([]byte("gaur-cam-session-v1:" + requestID))
	return hex.EncodeToString(mac.Sum(nil))
}

// camFrameKey derives K_cam, the AES-256-GCM key for the frame stream. It is
// domain-separated from the session id, so the relay-visible identifier can
// never be used as key material.
func camFrameKey(peerCryptoKey []byte, sessionID string) []byte {
	mac := hmac.New(sha256.New, peerCryptoKey)
	mac.Write([]byte("gaur-camera-v1:" + sessionID))
	return mac.Sum(nil)
}

// camFrameAAD binds one frame to its session and index. The index is the
// decimal count of frames sent so far, so the receiver's count must equal the
// sender's or authentication fails.
func camFrameAAD(sessionID string, frameIndex int64) []byte {
	return []byte(fmt.Sprintf("cam:v1:%s:%d", sessionID, frameIndex))
}

// sealCamFrame encrypts one frame into the pinned wire record. The caller
// supplies a fresh nonce per frame. Used by the tests to pin the format the
// phone emits; the desktop is the receiving side.
func sealCamFrame(key, nonce, plaintext []byte, sessionID string, frameIndex int64) ([]byte, error) {
	if len(nonce) != camNonceSize {
		return nil, fmt.Errorf("camera frame nonce is %d bytes, want %d", len(nonce), camNonceSize)
	}
	if uint64(len(plaintext))+camFrameOverhead > camMaxFrameLen {
		return nil, fmt.Errorf("camera frame of %d bytes exceeds the %d MiB wire cap", len(plaintext), camMaxFrameLen>>20)
	}
	aead, err := NewAEAD(key)
	if err != nil {
		return nil, err
	}
	sealed := aead.Seal(nil, nonce, plaintext, camFrameAAD(sessionID, frameIndex))
	record := make([]byte, camFrameHeaderSize, camFrameHeaderSize+camNonceSize+len(sealed))
	binary.BigEndian.PutUint32(record, uint32(camNonceSize+len(sealed)))
	record = append(record, nonce...)
	record = append(record, sealed...)
	return record, nil
}

// parseCamFrame splits one complete frame record into its nonce and sealed
// body. The record shape is pinned: [4-byte BE length L][12-byte nonce]
// [L-28 ciphertext][16-byte GCM tag], so L must cover at least the nonce and
// tag, must never exceed the 8 MiB cap, and the record must be exactly as
// long as L promises.
func parseCamFrame(record []byte) (nonce, sealed []byte, err error) {
	if len(record) < camFrameHeaderSize {
		return nil, nil, fmt.Errorf("camera frame record is %d bytes, too short for a length prefix", len(record))
	}
	length := binary.BigEndian.Uint32(record[:camFrameHeaderSize])
	if length > camMaxFrameLen {
		return nil, nil, fmt.Errorf("camera frame length %d exceeds the %d MiB wire cap", length, camMaxFrameLen>>20)
	}
	if length < camFrameOverhead {
		return nil, nil, fmt.Errorf("camera frame length %d is below the %d-byte frame overhead", length, camFrameOverhead)
	}
	if len(record) != camFrameHeaderSize+int(length) {
		return nil, nil, fmt.Errorf("camera frame record is %d bytes, want exactly %d", len(record), camFrameHeaderSize+int(length))
	}
	return record[camFrameHeaderSize : camFrameHeaderSize+camNonceSize],
		record[camFrameHeaderSize+camNonceSize:], nil
}

// openCamFrame decrypts one frame body under the caller's receive count. A
// nonce of the wrong length is rejected explicitly: GCM panics on one. The
// AAD carries the index, so a frame sealed for any other position fails here.
func openCamFrame(key, nonce, sealed []byte, sessionID string, frameIndex int64) ([]byte, error) {
	if len(nonce) != camNonceSize {
		return nil, fmt.Errorf("camera frame nonce is %d bytes, want %d", len(nonce), camNonceSize)
	}
	aead, err := NewAEAD(key)
	if err != nil {
		return nil, err
	}
	return aead.Open(nil, nonce, sealed, camFrameAAD(sessionID, frameIndex))
}

// camFrameReader decodes the length-prefixed frame stream coming off the
// relay pipe. count is the receiver's frame tally; every frame must have been
// sealed for exactly that index, so a relay cannot reorder, drop, or splice
// frames without failing authentication. The body buffer is reused across
// frames to keep the steady state allocation-free.
type camFrameReader struct {
	br        *bufio.Reader
	key       []byte
	sessionID string
	count     int64
	body      []byte
}

func newCamFrameReader(body io.Reader, key []byte, sessionID string) *camFrameReader {
	return &camFrameReader{
		br:        bufio.NewReaderSize(body, 256*1024),
		key:       key,
		sessionID: sessionID,
	}
}

// next returns the next frame's plaintext, io.EOF on a clean stream end
// (frame boundary), and an error for anything else. An oversize length or an
// authentication failure is terminal: the caller aborts with exit 4.
func (r *camFrameReader) next() ([]byte, error) {
	var head [camFrameHeaderSize]byte
	if _, err := io.ReadFull(r.br, head[:]); err != nil {
		if errors.Is(err, io.EOF) {
			return nil, io.EOF
		}
		return nil, fmt.Errorf("camera stream read failed: %w", err)
	}
	length := binary.BigEndian.Uint32(head[:])
	if length > camMaxFrameLen {
		return nil, fmt.Errorf("camera frame length %d exceeds the %d MiB wire cap", length, camMaxFrameLen>>20)
	}
	total := camFrameHeaderSize + int(length)
	if cap(r.body) < total {
		r.body = make([]byte, total)
	}
	record := r.body[:total]
	copy(record, head[:])
	if _, err := io.ReadFull(r.br, record[camFrameHeaderSize:]); err != nil {
		return nil, fmt.Errorf("camera stream ended mid-frame: %w", err)
	}
	nonce, sealed, err := parseCamFrame(record)
	if err != nil {
		return nil, err
	}
	plain, err := openCamFrame(r.key, nonce, sealed, r.sessionID, r.count)
	if err != nil {
		return nil, fmt.Errorf("camera frame %d failed authentication: %w", r.count, err)
	}
	r.count++
	return plain, nil
}

// camControl is one sealed cam_control envelope's inner payload. The pinned
// shape is {"request_id","session_id","action","on"?,"tier"?,"ts"}; the phone
// applies a control only when request_id and session_id match its live
// session and drops anything else silently.
type camControl struct {
	requestID string
	sessionID string
	action    string // "torch", "lens", "tier", "stop"
	on        bool
	hasOn     bool
	tier      string
	hasTier   bool
}

// newCamControl builds the control for one action. on is read only for
// torch; tier only for the tier action.
func newCamControl(requestID, sessionID, action string, on bool, tier string) (camControl, error) {
	ctl := camControl{requestID: requestID, sessionID: sessionID, action: action}
	switch action {
	case "torch":
		ctl.hasOn, ctl.on = true, on
	case "lens", "stop":
	case "tier":
		if _, ok := camTierSpecs[tier]; !ok {
			return camControl{}, fmt.Errorf("unknown camera tier %q", tier)
		}
		ctl.hasTier, ctl.tier = true, tier
	default:
		return camControl{}, fmt.Errorf("unknown camera action %q", action)
	}
	return ctl, nil
}

// inner is the wire payload. ts is minted here so every envelope is fresh.
func (c camControl) inner() map[string]interface{} {
	inner := map[string]interface{}{
		"request_id": c.requestID,
		"session_id": c.sessionID,
		"action":     c.action,
		"ts":         time.Now().UnixMilli(),
	}
	if c.hasOn {
		inner["on"] = c.on
	}
	if c.hasTier {
		inner["tier"] = c.tier
	}
	return inner
}

// parseCamControl validates a cam_control inner payload against the pinned
// v1 shape: torch carries a boolean on, tier carries one of the pinned tier
// names, lens and stop carry neither. It mirrors the phone's acceptance
// check minus the live-session match, which only the phone can make.
func parseCamControl(inner map[string]interface{}) (camControl, bool) {
	ctl := camControl{
		requestID: envelopeString(inner, "request_id"),
		sessionID: envelopeString(inner, "session_id"),
		action:    envelopeString(inner, "action"),
	}
	if ctl.requestID == "" || ctl.sessionID == "" {
		return camControl{}, false
	}
	onRaw, hasOn := inner["on"]
	tierRaw, hasTier := inner["tier"]
	switch ctl.action {
	case "torch":
		on, ok := onRaw.(bool)
		if !hasOn || !ok || hasTier {
			return camControl{}, false
		}
		ctl.hasOn, ctl.on = true, on
	case "lens", "stop":
		if hasOn || hasTier {
			return camControl{}, false
		}
	case "tier":
		tier, ok := tierRaw.(string)
		if !hasTier || hasOn || !ok {
			return camControl{}, false
		}
		if _, valid := camTierSpecs[tier]; !valid {
			return camControl{}, false
		}
		ctl.hasTier, ctl.tier = true, tier
	default:
		return camControl{}, false
	}
	return ctl, true
}

// sendCamControl seals one cam_control envelope and writes it on the
// session's relay link — the same link that carried the request. The write is
// deadline-bounded so a stalled relay cannot wedge the caller.
func sendCamControl(sess *requesterSession, ctl camControl) error {
	envelope, err := sealEnvelope(sess.peerCryptoKey, sess.targetID, "cam_control", ctl.inner())
	if err != nil {
		return fmt.Errorf("could not seal cam_control: %w", err)
	}
	_ = sess.client.conn.SetWriteDeadline(time.Now().Add(camControlWriteTimeout))
	defer sess.client.conn.SetWriteDeadline(time.Time{})
	if err := sess.client.WriteJSON(envelope); err != nil {
		return fmt.Errorf("could not send cam_control: %w", err)
	}
	return nil
}

// camSession is the state one cam command carries from request to teardown.
type camSession struct {
	sess      *requesterSession
	requestID string
	sessionID string
	frameKey  []byte
	expiresTs int64
}

// runCam asks a trusted phone for its camera, pulls the encrypted HEVC
// stream, and pipes the decrypted frames into a player. Exit status mirrors
// ask: 0 clean end, 1 declined, 2 usage, 3 request expired without a stream,
// 4 transport error; SIGINT exits 130 after a best-effort stop control.
func runCam(cfg Config, targetQuery, loopDev, playerOverride string) error {
	targetQuery = strings.TrimSpace(targetQuery)
	if targetQuery == "" {
		return usageControlError("cam needs a target device")
	}
	// Resolve the player before the phone is asked: a desktop that cannot
	// display the stream should fail fast instead of ringing the phone.
	player, err := buildCamPlayer(loopDev, playerOverride)
	if err != nil {
		return err
	}

	sess, err := openRequesterSession(cfg, targetQuery)
	if err != nil {
		return controlErrorf(4, "error", "", "%v", err)
	}
	defer sess.close()

	requestID, err := NewRequestID()
	if err != nil {
		return controlErrorf(4, "error", "", "could not create camera request id: %v", err)
	}
	expiresTs := time.Now().Add(camRequestTTL).UnixMilli()
	grant := ComputeRequestGrant(sess.peerCryptoKey, requestID, sess.requesterID, sess.targetID, expiresTs)

	cam := &camSession{
		sess:      sess,
		requestID: requestID,
		sessionID: deriveCamSessionID(sess.peerCryptoKey, requestID),
		expiresTs: expiresTs,
	}
	cam.frameKey = camFrameKey(sess.peerCryptoKey, cam.sessionID)

	term := enterCamRawMode()
	defer term.restore()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// The pump starts before the request goes out so a decline can never be
	// missed, and keeps the link drained for the whole session.
	declinedCh := make(chan string, 1)
	wsErrCh := make(chan error, 1)
	go camWSPump(ctx, sess, requestID, declinedCh, wsErrCh)

	inner := sess.baseInner(requestID)
	inner["kind"] = "camera"
	inner["expires_ts"] = expiresTs
	inner["grant"] = grant
	if err := sess.seal("request", inner); err != nil {
		return controlErrorf(4, "error", "", "%v", err)
	}

	camTakeOverSignals(ctx, sess, cam, term.restore)

	fmt.Printf("Waiting for %s to accept the camera request...\n", peerLabel(sess.targetName, sess.targetID))

	resp, err := camAwaitStream(ctx, cfg, cam, declinedCh, wsErrCh)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	fmt.Printf("Streaming camera from %s — keys: t torch, f flip lens, 1/2/3 tier, q stop\n", sess.targetName)
	return camPumpStream(ctx, resp.Body, sess, cam, player, wsErrCh)
}

// camWSPump keeps the requester's relay link drained while the camera
// request is outstanding. It acts only on a sealed request_declined answering
// this exact request id; peer-list traffic and other kinds are ignored, and
// anything the link carries after acceptance is ignored too. Teardown stays
// event-driven: no read deadline, the loop ends when the link closes or the
// command's context does.
func camWSPump(ctx context.Context, sess *requesterSession, requestID string, declinedCh chan<- string, wsErrCh chan<- error) {
	for {
		_, msgBytes, err := sess.client.ReadMessage()
		if err != nil {
			select {
			case wsErrCh <- err:
			case <-ctx.Done():
			}
			return
		}
		var envelope map[string]interface{}
		if json.Unmarshal(msgBytes, &envelope) != nil {
			continue
		}
		if envelopeString(envelope, "type") != "sealed" {
			continue
		}
		// The seal key is shared cluster-wide, so only envelopes addressed
		// to this ephemeral requester identity may be opened here.
		if envelopeString(envelope, "target_id") != sess.requesterID {
			continue
		}
		kind, inner, err := openSealed(sess.peerCryptoKey, envelope)
		if err != nil || kind != "request_declined" {
			continue
		}
		if envelopeString(inner, "request_id") != requestID {
			continue
		}
		reason := sanitizeDeclineReason(envelopeString(inner, "reason"))
		select {
		case declinedCh <- reason:
		case <-ctx.Done():
		}
		return
	}
}

// camStreamClient has no timeout on purpose: a connected stream legitimately
// camStreamClient is the receiver's dedicated client. It never times out: a
// session runs for hours, and each rendezvous attempt is bounded by the
// relay's own 504. The request context cancels the dial when the command
// ends. The dialer bounds both socket buffers: a default multi-megabyte
// receive buffer silently stages seconds of camera frames in the kernel
// when the reader falls behind, and the viewer watches the past. 256 KiB
// keeps the desktop's share of the live latency budget under half a second
// at stream bitrates, matching the relay's low-latency pipe and the
// phone's bounded send buffer.
var camStreamClient = &http.Client{
	Transport: &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			d := &net.Dialer{
				Control: func(network, address string, c syscall.RawConn) error {
					_ = c.Control(func(fd uintptr) {
						setCamSocketBuffers(int(fd))
					})
					return nil
				},
			}
			conn, err := d.DialContext(ctx, network, addr)
			if err != nil {
				return nil, err
			}
			if tcp, ok := conn.(*net.TCPConn); ok {
				_ = tcp.SetNoDelay(true)
			}
			return conn, nil
		},
	},
}

// camSocketBufferBytes bounds the receiver's kernel socket queue. Best
// effort: a stack that refuses the size keeps its default rather than
// failing the connection.
const camSocketBufferBytes = 256 * 1024

// camPipeURL is the desktop's receiver address on the relay's existing /pipe
// endpoint; transfer_id is the derived camera session id.
func camPipeURL(cfg Config, sessionID string) string {
	return fmt.Sprintf("http://%s/pipe?transfer_id=%s&role=receiver&flush=1",
		cfg.RelayAddr, url.QueryEscape(sessionID))
}

// camDialStream performs one receiver GET. The token rides in a header, not
// the URL. A 504 is the relay's rendezvous timeout, not an error to surface.
func camDialStream(ctx context.Context, cfg Config, relayToken, sessionID string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, camPipeURL(cfg, sessionID), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Gaur-Token", relayToken)
	resp, err := camStreamClient.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode == http.StatusOK {
		return resp, nil
	}
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
	_ = resp.Body.Close()
	if resp.StatusCode == http.StatusGatewayTimeout {
		return nil, errCamRendezvousTimeout
	}
	return nil, fmt.Errorf("relay returned HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
}

// camAwaitStream dials the pipe until the phone opens the sender side or the
// accept window closes. The relay bounds one rendezvous attempt at its own
// 60-second timeout with a 504; the desktop re-dials on each 504 until
// expires_ts and never starts a dial after it. A decline arriving on the
// relay link wins over any dial outcome.
func camAwaitStream(
	ctx context.Context,
	cfg Config,
	cam *camSession,
	declinedCh <-chan string,
	wsErrCh <-chan error,
) (*http.Response, error) {
	streamCh := make(chan *http.Response, 1)
	dialErrCh := make(chan error, 1)

	go func() {
		for {
			started := time.Now()
			resp, err := camDialStream(ctx, cfg, cam.sess.relayToken, cam.sessionID)
			if err == nil {
				select {
				case streamCh <- resp:
				case <-ctx.Done():
					_ = resp.Body.Close()
				}
				return
			}
			if errors.Is(err, errCamRendezvousTimeout) {
				if time.Now().UnixMilli() >= cam.expiresTs {
					err = errCamRequestExpired
				} else {
					if elapsed := time.Since(started); elapsed < camRedialFloor {
						select {
						case <-time.After(camRedialFloor - elapsed):
						case <-ctx.Done():
						}
					}
					continue
				}
			}
			select {
			case dialErrCh <- err:
			case <-ctx.Done():
			}
			return
		}
	}()

	for {
		select {
		case resp := <-streamCh:
			return resp, nil
		case err := <-dialErrCh:
			return nil, camDialFailure(err, cam.sess.targetName)
		case reason := <-declinedCh:
			return nil, controlErrorf(1, "declined", reason, "camera request declined")
		case err := <-wsErrCh:
			return nil, controlErrorf(4, "error", "", "relay link lost while waiting: %v", err)
		}
	}
}

// camDialFailure maps a dialer outcome to its pinned exit code: expiry
// without a stream is the timeout exit (3), anything else a transport
// failure (4).
func camDialFailure(err error, targetName string) error {
	if errors.Is(err, errCamRequestExpired) {
		return controlErrorf(3, "timeout", "", "camera request expired before %s started the stream", targetName)
	}
	return controlErrorf(4, "error", "", "%v", err)
}

// camFrameResult is one decrypted frame off the pump goroutine, or the
// terminal error that ended the stream.
type camFrameResult struct {
	data []byte
	err  error
}

// camPumpFrames decrypts every frame of the pipe body and forwards the
// plaintext records on ch. It stops at the first error, including the clean
// io.EOF at a frame boundary.
func camPumpFrames(ctx context.Context, body io.Reader, cam *camSession, ch chan<- camFrameResult) {
	reader := newCamFrameReader(body, cam.frameKey, cam.sessionID)
	for {
		data, err := reader.next()
		result := camFrameResult{data: data, err: err}
		select {
		case ch <- result:
		case <-ctx.Done():
			return
		}
		if err != nil {
			return
		}
	}
}

// camPumpStream decrypts the frame stream into the player and ends the
// command. Clean stream EOF and player exit both print the stats line and
// return nil (exit 0); a framing or authentication failure aborts with exit
// 4 after killing the player.
func camPumpStream(
	ctx context.Context,
	body io.Reader,
	sess *requesterSession,
	cam *camSession,
	player camPlayer,
	wsErrCh <-chan error,
) error {
	cmd, stdin, err := startCamPlayer(player)
	if err != nil {
		return controlErrorf(4, "error", "", "could not start %s: %v", player.name, err)
	}

	// Controls ride the same relay link that carried the request; when it
	// dies the video keeps flowing but keys stop working.
	controlsDead := &atomic.Bool{}
	go camKeyLoop(os.Stdin, sess, cam, controlsDead)

	frameCh := make(chan camFrameResult, 4)
	go camPumpFrames(ctx, body, cam, frameCh)
	playerCh := make(chan error, 1)
	go func() { playerCh <- cmd.Wait() }()

	started := time.Now()
	var frames int
	var payload int64
	playerGone := false
	stats := func() {
		// Closing stdin is the player's own stream end: mpv and ffmpeg both
		// exit on their own; anything that ignores EOF is killed after the
		// drain window instead of hanging the CLI open. When the player
		// already exited there is nothing left to wait for — the single
		// exit report was consumed by the select below.
		_ = stdin.Close()
		if !playerGone {
			select {
			case <-playerCh:
			case <-time.After(camPlayerDrainTimeout):
				_ = cmd.Process.Kill()
				<-playerCh
			}
		}
		fmt.Println(camStatsLine(frames, payload, time.Since(started)))
	}

	for {
		select {
		case res := <-frameCh:
			if res.err != nil {
				if errors.Is(res.err, io.EOF) {
					stats()
					return nil
				}
				_ = cmd.Process.Kill()
				<-playerCh
				return controlErrorf(4, "error", "", "%v", res.err)
			}
			frames++
			payload += int64(len(res.data))
			if _, err := stdin.Write(res.data); err != nil {
				// The player went away (window closed). The phone notices
				// through the relay as the receiver GET drops; nothing more
				// is sent.
				stats()
				return nil
			}
		case <-playerCh:
			playerGone = true
			stats()
			return nil
		case err := <-wsErrCh:
			controlsDead.Store(true)
			fmt.Fprintf(os.Stderr, "control channel lost (%v); keys are disabled, stream continues\n", err)
		}
	}
}

// camKeyLoop turns raw keypresses into sealed cam_control envelopes while the
// stream runs: t toggles the torch, f flips the lens, 1/2/3 switch the
// quality tier, q stops the session. Unknown bytes — including the newlines
// a cooked terminal insists on — are ignored. The desktop owns the torch
// alternation because the phone reports nothing back: each press flips the
// state this side last requested.
func camKeyLoop(in io.Reader, sess *requesterSession, cam *camSession, dead *atomic.Bool) {
	torchOn := false
	buf := make([]byte, 1)
	for {
		if _, err := in.Read(buf); err != nil {
			return
		}
		var (
			ctl camControl
			err error
		)
		switch buf[0] {
		case 't', 'T':
			torchOn = !torchOn
			ctl, err = newCamControl(cam.requestID, cam.sessionID, "torch", torchOn, "")
		case 'f', 'F':
			ctl, err = newCamControl(cam.requestID, cam.sessionID, "lens", false, "")
		case '1':
			ctl, err = newCamControl(cam.requestID, cam.sessionID, "tier", false, "high")
		case '2':
			ctl, err = newCamControl(cam.requestID, cam.sessionID, "tier", false, "balanced")
		case '3':
			ctl, err = newCamControl(cam.requestID, cam.sessionID, "tier", false, "low")
		case 'q', 'Q':
			ctl, err = newCamControl(cam.requestID, cam.sessionID, "stop", false, "")
		default:
			continue
		}
		if err != nil {
			continue // unreachable: every case passes a pinned action
		}
		if dead.Load() {
			fmt.Fprintln(os.Stderr, "control channel lost; key ignored")
			continue
		}
		if err := sendCamControl(sess, ctl); err != nil {
			dead.Store(true)
			fmt.Fprintln(os.Stderr, "control channel lost; further keys are disabled")
		}
	}
}

// camTakeOverSignals reroutes SIGINT and SIGTERM for the life of the cam
// command. main's global handler exits 130 on the spot; a live camera stream
// must first deliver the sealed "stop" control so the phone tears its session
// down instead of streaming into the relay's buffer until the idle sweep,
// and only then exit with the conventional interrupt code.
func camTakeOverSignals(ctx context.Context, sess *requesterSession, cam *camSession, restore func()) {
	sigCh := make(chan os.Signal, 1)
	signal.Reset(syscall.SIGINT, syscall.SIGTERM)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		select {
		case <-sigCh:
			if ctl, err := newCamControl(cam.requestID, cam.sessionID, "stop", false, ""); err == nil {
				_ = sendCamControl(sess, ctl)
			}
			restore()
			os.Exit(130)
		case <-ctx.Done():
			signal.Stop(sigCh)
		}
	}()
}

// camTerminal is the raw-mode handle for the key loop. A zero value restores
// nothing.
type camTerminal struct {
	restoreFn func()
}

func (t camTerminal) restore() {
	if t.restoreFn != nil {
		t.restoreFn()
	}
}

// enterCamRawMode puts a terminal stdin into raw mode so single keypresses
// reach the control loop without Enter. The standard library has no portable
// termios, so this shells out to stty; anywhere stty is missing or stdin is
// not a terminal it is a no-op and keys need Enter instead. The saved state
// is restored on every exit path of the command.
func enterCamRawMode() camTerminal {
	info, err := os.Stdin.Stat()
	if err != nil || info.Mode()&os.ModeCharDevice == 0 {
		return camTerminal{}
	}
	saved, err := camStty("-g")
	if err != nil || saved == "" {
		return camTerminal{}
	}
	if _, err := camStty("raw", "-echo"); err != nil {
		return camTerminal{}
	}
	return camTerminal{restoreFn: func() { _, _ = camStty(saved) }}
}

// camStty runs stty against the command's own stdin.
func camStty(args ...string) (string, error) {
	cmd := exec.Command("stty", args...)
	cmd.Stdin = os.Stdin
	out, err := cmd.Output()
	return strings.TrimSpace(string(out)), err
}

// camPlayer is the resolved sink for the decrypted HEVC elementary stream.
type camPlayer struct {
	name string
	args []string
}

// camLoopbackPattern pins the v4l2 loopback device shape: the ffmpeg command
// writes into a real kernel video device node, not an arbitrary path.
var camLoopbackPattern = regexp.MustCompile(`^/dev/video[0-9]+$`)

// camPlayerArgs builds the player command line for the three output modes
// without touching the filesystem: the pinned low-latency mpv window by
// default, ffmpeg into a v4l2 loopback device for --loopback, or the
// caller's own command for --player, which receives the HEVC stream on stdin
// verbatim.
func camPlayerArgs(loopDev, playerOverride string) (camPlayer, error) {
	switch {
	case loopDev != "" && playerOverride != "":
		return camPlayer{}, usageControlError("--loopback and --player are mutually exclusive")
	case playerOverride != "":
		fields := strings.Fields(playerOverride)
		if len(fields) == 0 {
			return camPlayer{}, usageControlError("--player needs a command")
		}
		// A bare player command (e.g. "--player mpv") has no stdin
		// positional, so the player would print its usage and exit before
		// the first frame. Append "-" — the ffmpeg-family stdin target —
		// unless the override already names one, in which case the caller
		// knows exactly what they want.
		args := fields[1:]
		if !slices.Contains(args, "-") {
			args = append(args, "-")
		}
		return camPlayer{name: fields[0], args: args}, nil
	case loopDev != "":
		if !camLoopbackPattern.MatchString(loopDev) {
			return camPlayer{}, usageControlError("--loopback expects a /dev/videoN device node")
		}
		return camPlayer{
			name: "ffmpeg",
			args: []string{"-f", "hevc", "-i", "-", "-pix_fmt", "yuv420p", "-f", "v4l2", loopDev},
		}, nil
	default:
		return camPlayer{
			name: "mpv",
			args: []string{
				"--no-cache",
				"--profile=low-latency",
				// Untimed renders each frame the moment it arrives
				// instead of pacing to the stream's frame rate, which
				// alone was worth tens of milliseconds of lag.
				"--untimed",
				"--demuxer-lavf-format=hevc",
				"--demuxer-lavf-analyzeduration=0",
				"--demuxer-lavf-probesize=4096",
				"--hwdec=auto",
				"-",
			},
		}, nil
	}
}

// buildCamPlayer resolves the player executable after building its command
// line, so a missing player is a fast, clear failure.
func buildCamPlayer(loopDev, playerOverride string) (camPlayer, error) {
	player, err := camPlayerArgs(loopDev, playerOverride)
	if err != nil {
		return camPlayer{}, err
	}
	if _, err := exec.LookPath(player.name); err != nil {
		hint := "install it to view the camera stream (e.g. sudo apt install mpv)"
		if player.name == "ffmpeg" {
			hint = "install ffmpeg for --loopback output (e.g. sudo apt install ffmpeg)"
		}
		return camPlayer{}, controlErrorf(4, "error", "", "%s is not installed or not in PATH; %s", player.name, hint)
	}
	return player, nil
}

// startCamPlayer launches the player with the HEVC elementary stream on
// stdin. The player owns the terminal's output; the CLI adds only its own
// one-line status messages.
func startCamPlayer(player camPlayer) (*exec.Cmd, io.WriteCloser, error) {
	cmd := exec.Command(player.name, player.args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, nil, err
	}
	return cmd, stdin, nil
}

// camStatsLine is the short teardown summary: frames received (== decrypted),
// wall-clock seconds of the stream, and the approximate plaintext bitrate.
func camStatsLine(frames int, payloadBytes int64, elapsed time.Duration) string {
	secs := elapsed.Seconds()
	bitrateSecs := secs
	if bitrateSecs < 0.1 {
		bitrateSecs = 0.1
	}
	kbps := int64(float64(payloadBytes) * 8 / 1000 / bitrateSecs)
	return fmt.Sprintf("Camera stream ended: %d frames, %.1fs, ~%d kbps", frames, secs, kbps)
}

// camExit prints the cam command's failure form and exits. A decline prints
// the pinned bare word "declined" (exit 1); every other failure uses the
// control-error line with its mapped code.
func camExit(err error) {
	if controlExitCode(err) == 1 {
		fmt.Println("declined")
		os.Exit(1)
	}
	printControlError(err, false)
}
