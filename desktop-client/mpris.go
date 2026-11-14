package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
)

const mprisPath = dbus.ObjectPath("/org/mpris/MediaPlayer2")

// MediaState is the sealed media_state payload from an Android device.
type MediaState struct {
	SenderID    string
	SenderName  string
	SessionID   string
	App         string
	Title       string
	Artist      string
	Album       string
	DurationMs  int64
	PositionMs  int64
	Rate        float64
	Playing     bool
	CanPlay     bool
	CanPause    bool
	CanNext     bool
	CanPrev     bool
	CanSeek     bool
	Artwork     string
	ArtworkHash string
}

func mediaStateFromInner(inner map[string]interface{}) MediaState {
	return MediaState{
		SenderID:    stringField(inner, "sender_id"),
		SenderName:  stringField(inner, "sender_name"),
		SessionID:   stringField(inner, "session_id"),
		App:         stringField(inner, "app"),
		Title:       stringField(inner, "title"),
		Artist:      stringField(inner, "artist"),
		Album:       stringField(inner, "album"),
		DurationMs:  numberToInt64(inner["duration_ms"]),
		PositionMs:  numberToInt64(inner["position_ms"]),
		Rate:        floatField(inner, "rate", 1.0),
		Playing:     boolField(inner, "playing"),
		CanPlay:     boolField(inner, "can_play"),
		CanPause:    boolField(inner, "can_pause"),
		CanNext:     boolField(inner, "can_next"),
		CanPrev:     boolField(inner, "can_prev"),
		CanSeek:     boolField(inner, "can_seek"),
		Artwork:     stringField(inner, "artwork"),
		ArtworkHash: stringField(inner, "artwork_hash"),
	}
}

func stringField(m map[string]interface{}, key string) string {
	v, _ := m[key].(string)
	return v
}

func boolField(m map[string]interface{}, key string) bool {
	v, _ := m[key].(bool)
	return v
}

func floatField(m map[string]interface{}, key string, def float64) float64 {
	switch v := m[key].(type) {
	case float64:
		return v
	case int64:
		return float64(v)
	case int:
		return float64(v)
	default:
		return def
	}
}

// MprisManager exports one local MPRIS player per remote device. Quickshell's
// existing media widget discovers these like any other player.
type MprisManager struct {
	conn    *dbus.Conn
	send    func(sourceID, sessionID, command string, positionMs int64, volume float64)
	mu      sync.Mutex
	players map[string]*MprisPlayer
}

func NewMprisManager(send func(sourceID, sessionID, command string, positionMs int64, volume float64)) (*MprisManager, error) {
	conn, err := dbus.SessionBus()
	if err != nil {
		return nil, err
	}
	return &MprisManager{
		conn:    conn,
		send:    send,
		players: make(map[string]*MprisPlayer),
	}, nil
}

func (m *MprisManager) Update(state MediaState) {
	if state.SenderID == "" {
		return
	}
	m.mu.Lock()
	player := m.players[state.SenderID]
	if player == nil {
		player = newMprisPlayer(m.conn, state.SenderID, state.SenderName, m.send)
		if err := player.export(); err != nil {
			log.Printf("[MPRIS] Could not export player for %s: %v", state.SenderID, err)
			m.mu.Unlock()
			return
		}
		m.players[state.SenderID] = player
		log.Printf("[MPRIS] Exported %s for %s", player.busName, state.SenderName)
	}
	m.mu.Unlock()
	player.Update(state)
}

type MprisPlayer struct {
	conn       *dbus.Conn
	busName    string
	sourceID   string
	sourceName string
	send       func(sourceID, sessionID, command string, positionMs int64, volume float64)

	mu          sync.Mutex
	sessionID   string
	app         string
	title       string
	artist      string
	album       string
	durationMs  int64
	positionMs  int64
	rate        float64
	playing     bool
	canPlay     bool
	canPause    bool
	canNext     bool
	canPrev     bool
	canSeek     bool
	artworkPath string
	artworkHash string
	updatedAt   time.Time
	volume      float64
	trackID     dbus.ObjectPath
}

func newMprisPlayer(conn *dbus.Conn, sourceID, sourceName string, send func(sourceID, sessionID, command string, positionMs int64, volume float64)) *MprisPlayer {
	if sourceName == "" {
		sourceName = sourceID
	}
	return &MprisPlayer{
		conn:       conn,
		busName:    "org.mpris.MediaPlayer2.gaurpn." + sanitizeBusName(sourceID),
		sourceID:   sourceID,
		sourceName: sourceName,
		send:       send,
		rate:       1.0,
		volume:     1.0,
		trackID:    dbus.ObjectPath("/org/gaurpn/track/unknown"),
	}
}

func (p *MprisPlayer) export() error {
	owned := false
	name := p.busName
	for i := 0; i < 10; i++ {
		reply, err := p.conn.RequestName(name, dbus.NameFlagDoNotQueue)
		if err != nil {
			return err
		}
		if reply == dbus.RequestNameReplyPrimaryOwner || reply == dbus.RequestNameReplyAlreadyOwner {
			p.busName = name
			owned = true
			break
		}
		name = fmt.Sprintf("%s_%d", p.busName, i+2)
	}
	if !owned {
		return fmt.Errorf("MPRIS bus name is already owned")
	}
	for _, iface := range []string{
		"org.mpris.MediaPlayer2",
		"org.mpris.MediaPlayer2.Player",
		"org.freedesktop.DBus.Properties",
		"org.freedesktop.DBus.Introspectable",
	} {
		if err := p.conn.Export(p, mprisPath, iface); err != nil {
			return err
		}
	}
	return nil
}

func (p *MprisPlayer) Update(state MediaState) {
	p.mu.Lock()
	p.sessionID = state.SessionID
	p.app = state.App
	p.title = state.Title
	p.artist = state.Artist
	p.album = state.Album
	p.durationMs = state.DurationMs
	p.positionMs = state.PositionMs
	p.rate = state.Rate
	if p.rate <= 0 {
		p.rate = 1.0
	}
	p.playing = state.Playing
	p.canPlay = state.CanPlay
	p.canPause = state.CanPause
	p.canNext = state.CanNext
	p.canPrev = state.CanPrev
	p.canSeek = state.CanSeek
	p.updatedAt = time.Now()
	p.trackID = trackIDFor(state.SessionID, state.Title)
	if state.Artwork != "" && state.ArtworkHash != p.artworkHash {
		if path, err := writeArtwork(p.sourceID, state.Artwork); err == nil {
			p.artworkPath = path
			p.artworkHash = state.ArtworkHash
		}
	} else if state.Artwork == "" {
		p.artworkPath = ""
		p.artworkHash = ""
	}
	changed := map[string]dbus.Variant{
		"PlaybackStatus": dbus.MakeVariant(p.playbackStatusLocked()),
		"Metadata":       dbus.MakeVariant(p.metadataLocked()),
		"CanGoNext":      dbus.MakeVariant(p.canNext),
		"CanGoPrevious":  dbus.MakeVariant(p.canPrev),
		"CanPlay":        dbus.MakeVariant(p.canPlay),
		"CanPause":       dbus.MakeVariant(p.canPause),
		"CanSeek":        dbus.MakeVariant(p.canSeek),
	}
	p.mu.Unlock()
	p.emitChanged(changed)
}

func (p *MprisPlayer) playbackStatusLocked() string {
	if p.playing {
		return "Playing"
	}
	if p.title != "" || p.positionMs > 0 {
		return "Paused"
	}
	return "Stopped"
}

func (p *MprisPlayer) positionLocked() int64 {
	position := p.positionMs
	if p.playing {
		position += int64(time.Since(p.updatedAt).Milliseconds()) * int64(math.Max(p.rate, 0))
	}
	if p.durationMs > 0 && position > p.durationMs {
		position = p.durationMs
	}
	if position < 0 {
		position = 0
	}
	return position * 1000
}

func (p *MprisPlayer) metadataLocked() map[string]dbus.Variant {
	metadata := map[string]dbus.Variant{
		"mpris:trackid": dbus.MakeVariant(p.trackID),
		"xesam:title":   dbus.MakeVariant(p.title),
		"xesam:artist":  dbus.MakeVariant([]string{p.artist}),
		"xesam:album":   dbus.MakeVariant(p.album),
	}
	if p.durationMs > 0 {
		metadata["mpris:length"] = dbus.MakeVariant(p.durationMs * 1000)
	}
	if p.artworkPath != "" {
		metadata["mpris:artUrl"] = dbus.MakeVariant("file://" + p.artworkPath)
	}
	return metadata
}

func (p *MprisPlayer) emitChanged(changed map[string]dbus.Variant) {
	_ = p.conn.Emit(
		mprisPath,
		"org.freedesktop.DBus.Properties.PropertiesChanged",
		"org.mpris.MediaPlayer2.Player",
		changed,
		[]string{},
	)
}

func (p *MprisPlayer) emitPlaybackStatus() {
	p.mu.Lock()
	status := p.playbackStatusLocked()
	p.mu.Unlock()
	p.emitChanged(map[string]dbus.Variant{
		"PlaybackStatus": dbus.MakeVariant(status),
	})
}

func (p *MprisPlayer) Get(iface, name string) (dbus.Variant, *dbus.Error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	switch iface {
	case "org.mpris.MediaPlayer2":
		switch name {
		case "Identity":
			return dbus.MakeVariant("gaurPN · " + p.sourceName), nil
		case "CanQuit", "CanRaise", "HasTrackList":
			return dbus.MakeVariant(false), nil
		case "SupportedUriSchemes", "SupportedMimeTypes":
			return dbus.MakeVariant([]string{}), nil
		}
	case "org.mpris.MediaPlayer2.Player":
		switch name {
		case "PlaybackStatus":
			return dbus.MakeVariant(p.playbackStatusLocked()), nil
		case "LoopStatus":
			return dbus.MakeVariant("None"), nil
		case "Rate":
			return dbus.MakeVariant(p.rate), nil
		case "Shuffle":
			return dbus.MakeVariant(false), nil
		case "Metadata":
			return dbus.MakeVariant(p.metadataLocked()), nil
		case "Volume":
			return dbus.MakeVariant(p.volume), nil
		case "Position":
			return dbus.MakeVariant(p.positionLocked()), nil
		case "MinimumRate", "MaximumRate":
			return dbus.MakeVariant(1.0), nil
		case "CanGoNext":
			return dbus.MakeVariant(p.canNext), nil
		case "CanGoPrevious":
			return dbus.MakeVariant(p.canPrev), nil
		case "CanPlay":
			return dbus.MakeVariant(p.canPlay), nil
		case "CanPause":
			return dbus.MakeVariant(p.canPause), nil
		case "CanSeek":
			return dbus.MakeVariant(p.canSeek), nil
		case "CanControl":
			return dbus.MakeVariant(true), nil
		}
	}
	return dbus.Variant{}, invalidArgs("unknown property " + name)
}

func (p *MprisPlayer) GetAll(iface string) (map[string]dbus.Variant, *dbus.Error) {
	var names []string
	switch iface {
	case "org.mpris.MediaPlayer2":
		names = []string{
			"Identity", "CanQuit", "CanRaise", "HasTrackList",
			"SupportedUriSchemes", "SupportedMimeTypes",
		}
	case "org.mpris.MediaPlayer2.Player":
		names = []string{
			"PlaybackStatus", "LoopStatus", "Rate", "Shuffle", "Metadata",
			"Volume", "Position", "MinimumRate", "MaximumRate",
			"CanGoNext", "CanGoPrevious", "CanPlay", "CanPause", "CanSeek",
			"CanControl",
		}
	default:
		return map[string]dbus.Variant{}, nil
	}
	out := make(map[string]dbus.Variant, len(names))
	for _, name := range names {
		if value, err := p.Get(iface, name); err == nil {
			out[name] = value
		}
	}
	return out, nil
}

func (p *MprisPlayer) Set(iface, name string, value dbus.Variant) *dbus.Error {
	if iface != "org.mpris.MediaPlayer2.Player" {
		return invalidArgs("unknown interface " + iface)
	}
	switch name {
	case "Volume":
		volume, ok := value.Value().(float64)
		if !ok {
			return invalidArgs("volume must be a double")
		}
		volume = math.Max(0, math.Min(1, volume))
		p.mu.Lock()
		p.volume = volume
		p.mu.Unlock()
		p.send(p.sourceID, p.sessionID, "volume", 0, volume)
		p.emitChanged(map[string]dbus.Variant{"Volume": dbus.MakeVariant(volume)})
		return nil
	case "LoopStatus", "Shuffle", "Rate":
		return nil
	}
	return invalidArgs("property is not writable: " + name)
}

func (p *MprisPlayer) Play() *dbus.Error {
	p.optimistic(true)
	p.send(p.sourceID, p.sessionID, "play", 0, 0)
	return nil
}

func (p *MprisPlayer) Pause() *dbus.Error {
	p.optimistic(false)
	p.send(p.sourceID, p.sessionID, "pause", 0, 0)
	return nil
}

func (p *MprisPlayer) PlayPause() *dbus.Error {
	p.mu.Lock()
	playing := p.playing
	p.mu.Unlock()
	if playing {
		return p.Pause()
	}
	return p.Play()
}

func (p *MprisPlayer) Next() *dbus.Error {
	p.send(p.sourceID, p.sessionID, "next", 0, 0)
	return nil
}

func (p *MprisPlayer) Previous() *dbus.Error {
	p.send(p.sourceID, p.sessionID, "previous", 0, 0)
	return nil
}

func (p *MprisPlayer) Stop() *dbus.Error {
	p.mu.Lock()
	p.positionMs = 0
	p.playing = false
	p.updatedAt = time.Now()
	p.mu.Unlock()
	p.emitPlaybackStatus()
	p.send(p.sourceID, p.sessionID, "stop", 0, 0)
	return nil
}

func (p *MprisPlayer) Seek(offset int64) *dbus.Error {
	p.mu.Lock()
	position := p.positionLocked()/1000 + offset/1000
	if position < 0 {
		position = 0
	}
	p.positionMs = position
	p.updatedAt = time.Now()
	p.mu.Unlock()
	_ = p.conn.Emit(mprisPath, "org.mpris.MediaPlayer2.Player.Seeked", position*1000)
	p.send(p.sourceID, p.sessionID, "seek", position, 0)
	return nil
}

func (p *MprisPlayer) SetPosition(trackID dbus.ObjectPath, position int64) *dbus.Error {
	positionMs := position / 1000
	p.mu.Lock()
	p.positionMs = positionMs
	p.updatedAt = time.Now()
	p.mu.Unlock()
	_ = p.conn.Emit(mprisPath, "org.mpris.MediaPlayer2.Player.Seeked", position)
	p.send(p.sourceID, p.sessionID, "seek", positionMs, 0)
	return nil
}

func (p *MprisPlayer) OpenUri(uri string) *dbus.Error {
	return invalidArgs("OpenUri is not supported")
}

func (p *MprisPlayer) Raise() *dbus.Error {
	return nil
}

func (p *MprisPlayer) Quit() *dbus.Error {
	return nil
}

func (p *MprisPlayer) optimistic(playing bool) {
	p.mu.Lock()
	if playing != p.playing {
		p.positionMs = p.positionLocked() / 1000
		p.playing = playing
		p.updatedAt = time.Now()
	}
	p.mu.Unlock()
	p.emitPlaybackStatus()
}

func (p *MprisPlayer) Introspect() (string, *dbus.Error) {
	return mprisIntrospectionXML, nil
}

func invalidArgs(message string) *dbus.Error {
	return dbus.NewError("org.freedesktop.DBus.Error.InvalidArgs", []any{message})
}

func sanitizeBusName(value string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(value) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' {
			b.WriteRune(r)
		} else {
			b.WriteByte('_')
		}
	}
	out := b.String()
	if out == "" {
		out = "device"
	}
	if out[0] >= '0' && out[0] <= '9' {
		out = "d_" + out
	}
	return out
}

func trackIDFor(sessionID, title string) dbus.ObjectPath {
	sum := sha256.Sum256([]byte(sessionID + "\x00" + title))
	return dbus.ObjectPath("/org/gaurpn/track/" + hex.EncodeToString(sum[:8]))
}

func writeArtwork(sourceID, encoded string) (string, error) {
	data, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return "", err
	}
	cache, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(cache, "gaur-share")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	path := filepath.Join(dir, "artwork-"+sanitizeBusName(sourceID)+".png")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

const mprisIntrospectionXML = `<!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
<node>
  <interface name="org.mpris.MediaPlayer2">
    <method name="Raise"/>
    <method name="Quit"/>
    <property name="Identity" type="s" access="read"/>
    <property name="CanQuit" type="b" access="read"/>
    <property name="CanRaise" type="b" access="read"/>
    <property name="HasTrackList" type="b" access="read"/>
    <property name="SupportedUriSchemes" type="as" access="read"/>
    <property name="SupportedMimeTypes" type="as" access="read"/>
  </interface>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="Next"/>
    <method name="Previous"/>
    <method name="Pause"/>
    <method name="PlayPause"/>
    <method name="Stop"/>
    <method name="Play"/>
    <method name="Seek"><arg name="Offset" type="x" direction="in"/></method>
    <method name="SetPosition">
      <arg name="TrackId" type="o" direction="in"/>
      <arg name="Position" type="x" direction="in"/>
    </method>
    <method name="OpenUri"><arg name="Uri" type="s" direction="in"/></method>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="LoopStatus" type="s" access="readwrite"/>
    <property name="Rate" type="d" access="readwrite"/>
    <property name="Shuffle" type="b" access="readwrite"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Volume" type="d" access="readwrite"/>
    <property name="Position" type="x" access="read"/>
    <property name="MinimumRate" type="d" access="read"/>
    <property name="MaximumRate" type="d" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
    <property name="CanPlay" type="b" access="read"/>
    <property name="CanPause" type="b" access="read"/>
    <property name="CanSeek" type="b" access="read"/>
    <property name="CanControl" type="b" access="read"/>
    <signal name="Seeked"><arg name="Position" type="x"/></signal>
  </interface>
  <interface name="org.freedesktop.DBus.Properties">
    <method name="Get">
      <arg name="interface" type="s" direction="in"/>
      <arg name="property" type="s" direction="in"/>
      <arg name="value" type="v" direction="out"/>
    </method>
    <method name="GetAll">
      <arg name="interface" type="s" direction="in"/>
      <arg name="properties" type="a{sv}" direction="out"/>
    </method>
    <method name="Set">
      <arg name="interface" type="s" direction="in"/>
      <arg name="property" type="s" direction="in"/>
      <arg name="value" type="v" direction="in"/>
    </method>
    <signal name="PropertiesChanged">
      <arg name="interface" type="s"/>
      <arg name="changed" type="a{sv}"/>
      <arg name="invalidated" type="as"/>
    </signal>
  </interface>
</node>`

// CommandSender holds the current relay connection so MPRIS method calls can
// send sealed media_command messages to the phone.
type CommandSender struct {
	mu            sync.Mutex
	cfg           Config
	client        *wsClient
	peerCryptoKey []byte
}

func (s *CommandSender) Set(client *wsClient, key []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.client = client
	s.peerCryptoKey = append([]byte(nil), key...)
}

func (s *CommandSender) Send(sourceID, sessionID, command string, positionMs int64, volume float64) {
	s.mu.Lock()
	client := s.client
	key := append([]byte(nil), s.peerCryptoKey...)
	cfg := s.cfg
	s.mu.Unlock()
	if client == nil || len(key) == 0 {
		return
	}
	log.Printf("[MPRIS] Command %s -> %s", command, sourceID)
	inner := map[string]interface{}{
		"sender_id":   cfg.DeviceID,
		"sender_name": cfg.Name,
		"session_id":  sessionID,
		"command":     command,
	}
	if command == "seek" {
		inner["position_ms"] = positionMs
	}
	if command == "volume" {
		inner["volume"] = volume
	}
	envelope, err := sealEnvelope(key, sourceID, "media_command", inner)
	if err != nil {
		log.Printf("[MPRIS] Could not seal %s: %v", command, err)
		return
	}
	if err := client.WriteJSON(envelope); err != nil {
		log.Printf("[MPRIS] Could not send %s: %v", command, err)
	}
}
