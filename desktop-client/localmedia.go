package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"log"
	"net/url"
	"os"
	"strings"
	"sync"

	"github.com/godbus/dbus/v5"
)

const mprisPrefix = "org.mpris.MediaPlayer2."

// MediaBroadcaster sends sealed media_state messages to every online peer.
// Other desktops ignore source_kind=desktop; phones display it.
type MediaBroadcaster struct {
	mu            sync.Mutex
	cfg           Config
	client        *wsClient
	peerCryptoKey []byte
	peers         []string
}

func (b *MediaBroadcaster) Set(client *wsClient, key []byte) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.client = client
	b.peerCryptoKey = append([]byte(nil), key...)
}

func (b *MediaBroadcaster) SetPeers(peers []string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.peers = append([]string(nil), peers...)
}

func (b *MediaBroadcaster) Send(inner map[string]interface{}) {
	b.mu.Lock()
	client := b.client
	key := append([]byte(nil), b.peerCryptoKey...)
	peers := append([]string(nil), b.peers...)
	cfg := b.cfg
	b.mu.Unlock()
	if client == nil || len(key) == 0 || len(peers) == 0 {
		return
	}
	payload := make(map[string]interface{}, len(inner)+3)
	for k, v := range inner {
		payload[k] = v
	}
	payload["sender_id"] = cfg.DeviceID
	payload["sender_name"] = cfg.Name
	payload["source_kind"] = "desktop"
	for _, peer := range peers {
		envelope, err := sealEnvelope(key, peer, "media_state", payload)
		if err != nil {
			log.Printf("[MPRIS] Could not seal desktop media for %s: %v", peer, err)
			continue
		}
		if err := client.WriteJSON(envelope); err != nil {
			log.Printf("[MPRIS] Could not send desktop media to %s: %v", peer, err)
		}
	}
}

func peerIDs(entries []peerFileEntry) []string {
	ids := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsSelf && entry.ID != "" {
			ids = append(ids, entry.ID)
		}
	}
	return ids
}

type localMediaState struct {
	BusName     string
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
	TrackID     dbus.ObjectPath
}

// LocalMediaWatcher observes local MPRIS players and mirrors the active one
// to the cluster. It explicitly ignores the gaurpn.* players that represent
// remote phones, which is the loop guard for desktop <-> phone media.
type LocalMediaWatcher struct {
	conn        *dbus.Conn
	cfg         Config
	broadcaster *MediaBroadcaster

	mu        sync.Mutex
	activeBus string
	lastSig   string
	trackIDs  map[string]dbus.ObjectPath
}

func NewLocalMediaWatcher(cfg Config, broadcaster *MediaBroadcaster) (*LocalMediaWatcher, error) {
	conn, err := dbus.SessionBus()
	if err != nil {
		return nil, err
	}
	watcher := &LocalMediaWatcher{
		conn:        conn,
		cfg:         cfg,
		broadcaster: broadcaster,
		trackIDs:    make(map[string]dbus.ObjectPath),
	}
	go watcher.loop()
	return watcher, nil
}

func (w *LocalMediaWatcher) loop() {
	_ = w.conn.AddMatchSignal(
		dbus.WithMatchInterface("org.freedesktop.DBus.Properties"),
		dbus.WithMatchMember("PropertiesChanged"),
	)
	_ = w.conn.AddMatchSignal(
		dbus.WithMatchInterface("org.freedesktop.DBus"),
		dbus.WithMatchMember("NameOwnerChanged"),
	)
	channel := make(chan *dbus.Signal, 32)
	w.conn.Signal(channel)
	w.Refresh()
	for signal := range channel {
		if w.relevant(signal) {
			w.Refresh()
		}
	}
}

func (w *LocalMediaWatcher) relevant(signal *dbus.Signal) bool {
	switch signal.Name {
	case "org.freedesktop.DBus.Properties.PropertiesChanged":
		// signal.Sender is the unique bus name (:1.42), not the MPRIS
		// well-known name. Scope by path/interface instead.
		if signal.Path != mprisPath {
			return false
		}
		if len(signal.Body) >= 1 {
			iface, _ := signal.Body[0].(string)
			if iface != "org.mpris.MediaPlayer2.Player" {
				return false
			}
		}
		if len(signal.Body) >= 2 {
			if changed, ok := signal.Body[1].(map[string]dbus.Variant); ok {
				for key := range changed {
					if key != "Position" {
						return true
					}
				}
				return false
			}
		}
		return true
	case "org.freedesktop.DBus.NameOwnerChanged":
		if len(signal.Body) == 0 {
			return false
		}
		name, _ := signal.Body[0].(string)
		return strings.HasPrefix(name, mprisPrefix) &&
			!strings.HasPrefix(name, "org.mpris.MediaPlayer2.gaurpn.")
	default:
		return false
	}
}

// ForceRefresh clears the dedupe signature and resends the current state.
// Used when the peer list becomes known after the initial D-Bus read.
func (w *LocalMediaWatcher) ForceRefresh() {
	w.mu.Lock()
	w.lastSig = ""
	w.mu.Unlock()
	w.Refresh()
}

func (w *LocalMediaWatcher) Refresh() {
	var names []string
	if err := w.conn.BusObject().Call("org.freedesktop.DBus.ListNames", 0).Store(&names); err != nil {
		return
	}
	candidates := make([]string, 0)
	for _, name := range names {
		if strings.HasPrefix(name, mprisPrefix) &&
			!strings.HasPrefix(name, "org.mpris.MediaPlayer2.gaurpn.") {
			candidates = append(candidates, name)
		}
	}

	states := make([]*localMediaState, 0, len(candidates))
	for _, name := range candidates {
		state, err := w.readPlayer(name)
		if err != nil {
			continue
		}
		states = append(states, state)
	}
	var active *localMediaState
	// Prefer the browser integration when both it and the raw browser player
	// are present: it carries the richer metadata and artwork.
	for _, state := range states {
		if strings.Contains(strings.ToLower(state.BusName), "plasma-browser-integration") {
			active = state
			break
		}
	}
	if active == nil {
		for _, state := range states {
			if state.Playing {
				active = state
				break
			}
		}
	}
	if active == nil && len(states) > 0 {
		active = states[0]
	}
	// Browser integrations often hold the artwork while the browser player
	// holds the transport. Borrow matching art rather than showing a blank.
	if active != nil && active.Artwork == "" {
		for _, state := range states {
			if state.Artwork == "" {
				continue
			}
			if titlesMatch(state.Title, active.Title) ||
				(state.Artist != "" && strings.EqualFold(state.Artist, active.Artist)) {
				active.Artwork = state.Artwork
				active.ArtworkHash = state.ArtworkHash
				log.Printf("[MPRIS] Borrowed artwork from %s for %s", state.BusName, active.BusName)
				break
			}
		}
	}

	if active == nil {
		w.mu.Lock()
		changed := w.lastSig != "stopped"
		w.lastSig = "stopped"
		w.activeBus = ""
		w.mu.Unlock()
		if changed {
			w.broadcaster.Send(map[string]interface{}{
				"stopped": true,
				"playing": false,
				"title":   "",
			})
		}
		return
	}

	signature := strings.Join([]string{
		active.BusName, active.Title, active.Artist, active.Album,
		boolString(active.Playing), active.ArtworkHash,
	}, "\x00")
	w.mu.Lock()
	if signature == w.lastSig {
		w.mu.Unlock()
		return
	}
	w.lastSig = signature
	w.activeBus = active.BusName
	w.trackIDs[active.BusName] = active.TrackID
	w.mu.Unlock()

	log.Printf("[MPRIS] Desktop media: %q — %s (playing=%v)", active.Title, active.App, active.Playing)
	w.broadcaster.Send(map[string]interface{}{
		"session_id":   active.BusName,
		"app":          active.App,
		"title":        active.Title,
		"artist":       active.Artist,
		"album":        active.Album,
		"duration_ms":  active.DurationMs,
		"position_ms":  active.PositionMs,
		"rate":         active.Rate,
		"playing":      active.Playing,
		"can_play":     active.CanPlay,
		"can_pause":    active.CanPause,
		"can_next":     active.CanNext,
		"can_prev":     active.CanPrev,
		"can_seek":     active.CanSeek,
		"artwork":      active.Artwork,
		"artwork_hash": active.ArtworkHash,
	})
}

func (w *LocalMediaWatcher) readPlayer(busName string) (*localMediaState, error) {
	obj := w.conn.Object(busName, mprisPath)
	var props map[string]dbus.Variant
	if err := obj.Call("org.freedesktop.DBus.Properties.GetAll", 0,
		"org.mpris.MediaPlayer2.Player").Store(&props); err != nil {
		return nil, err
	}
	var identity dbus.Variant
	_ = obj.Call("org.freedesktop.DBus.Properties.Get", 0,
		"org.mpris.MediaPlayer2", "Identity").Store(&identity)

	metadata := variantMap(props["Metadata"])
	state := &localMediaState{
		BusName:    busName,
		App:        variantString(identity),
		Title:      variantString(metadata["xesam:title"]),
		Artist:     strings.Join(variantStringSlice(metadata["xesam:artist"]), ", "),
		Album:      variantString(metadata["xesam:album"]),
		DurationMs: variantInt64(metadata["mpris:length"]) / 1000,
		PositionMs: variantInt64(props["Position"]) / 1000,
		Rate:       variantFloat(props["Rate"]),
		Playing:    variantString(props["PlaybackStatus"]) == "Playing",
		CanPlay:    variantBool(props["CanPlay"]),
		CanPause:   variantBool(props["CanPause"]),
		CanNext:    variantBool(props["CanGoNext"]),
		CanPrev:    variantBool(props["CanGoPrevious"]),
		CanSeek:    variantBool(props["CanSeek"]),
		TrackID:    variantObjectPath(metadata["mpris:trackid"]),
	}
	if state.App == "" {
		state.App = strings.TrimPrefix(busName, mprisPrefix)
	}
	if state.Rate <= 0 {
		state.Rate = 1.0
	}
	state.Artwork, state.ArtworkHash = artworkFromURL(variantString(metadata["mpris:artUrl"]))
	return state, nil
}

func (w *LocalMediaWatcher) Execute(inner map[string]interface{}) {
	command := stringField(inner, "command")
	busName := stringField(inner, "session_id")
	if busName == "" {
		w.mu.Lock()
		busName = w.activeBus
		w.mu.Unlock()
	}
	if busName == "" || strings.HasPrefix(busName, "org.mpris.MediaPlayer2.gaurpn.") {
		return
	}
	log.Printf(
		"[MPRIS] Local command %s from %s -> %s",
		command, stringField(inner, "sender_id"), busName,
	)
	obj := w.conn.Object(busName, mprisPath)
	var err error
	switch command {
	case "play":
		err = obj.Call("org.mpris.MediaPlayer2.Player.Play", 0).Err
	case "pause":
		err = obj.Call("org.mpris.MediaPlayer2.Player.Pause", 0).Err
	case "play_pause":
		err = obj.Call("org.mpris.MediaPlayer2.Player.PlayPause", 0).Err
	case "next":
		err = obj.Call("org.mpris.MediaPlayer2.Player.Next", 0).Err
	case "previous":
		err = obj.Call("org.mpris.MediaPlayer2.Player.Previous", 0).Err
	case "stop":
		err = obj.Call("org.mpris.MediaPlayer2.Player.Stop", 0).Err
	case "seek":
		w.mu.Lock()
		trackID := w.trackIDs[busName]
		w.mu.Unlock()
		if trackID == "" {
			return
		}
		position := numberToInt64(inner["position_ms"]) * 1000
		err = obj.Call("org.mpris.MediaPlayer2.Player.SetPosition", 0,
			trackID, position).Err
	case "volume":
		volume := floatField(inner, "volume", 1.0)
		err = obj.Call("org.freedesktop.DBus.Properties.Set", 0,
			"org.mpris.MediaPlayer2.Player", "Volume",
			dbus.MakeVariant(volume)).Err
	}
	if err != nil {
		log.Printf("[MPRIS] Local command %s failed: %v", command, err)
	}
}

func variantString(value dbus.Variant) string {
	s, _ := value.Value().(string)
	return s
}

func variantBool(value dbus.Variant) bool {
	b, _ := value.Value().(bool)
	return b
}

func variantInt64(value dbus.Variant) int64 {
	switch n := value.Value().(type) {
	case int64:
		return n
	case int32:
		return int64(n)
	case uint64:
		return int64(n)
	case float64:
		return int64(n)
	default:
		return 0
	}
}

func variantFloat(value dbus.Variant) float64 {
	switch n := value.Value().(type) {
	case float64:
		return n
	case int64:
		return float64(n)
	case int32:
		return float64(n)
	default:
		return 0
	}
}

func variantStringSlice(value dbus.Variant) []string {
	switch list := value.Value().(type) {
	case []string:
		return list
	case []interface{}:
		out := make([]string, 0, len(list))
		for _, item := range list {
			if s, ok := item.(string); ok {
				out = append(out, s)
			}
		}
		return out
	default:
		return nil
	}
}

func variantMap(value dbus.Variant) map[string]dbus.Variant {
	m, _ := value.Value().(map[string]dbus.Variant)
	return m
}

func variantObjectPath(value dbus.Variant) dbus.ObjectPath {
	path, _ := value.Value().(dbus.ObjectPath)
	return path
}

func boolString(value bool) string {
	if value {
		return "1"
	}
	return "0"
}

func titlesMatch(a, b string) bool {
	left := normalizedTitle(a)
	right := normalizedTitle(b)
	if left == "" || right == "" {
		return false
	}
	return left == right || strings.Contains(left, right) || strings.Contains(right, left)
}

func normalizedTitle(title string) string {
	value := strings.ToLower(strings.TrimSpace(title))
	if strings.HasPrefix(value, "(") {
		if index := strings.Index(value, ")"); index > 0 && index < 6 {
			value = strings.TrimSpace(value[index+1:])
		}
	}
	for _, suffix := range []string{" - youtube", " | youtube", " - youtube music"} {
		value = strings.TrimSuffix(value, suffix)
	}
	return strings.Trim(value, " \"'“”‘’")
}

func artworkFromURL(rawURL string) (string, string) {
	if rawURL == "" {
		return "", ""
	}
	parsed, err := url.Parse(rawURL)
	if err != nil || parsed.Scheme != "file" {
		return "", ""
	}
	data, err := os.ReadFile(parsed.Path)
	if err != nil || len(data) > 128*1024 {
		return "", ""
	}
	sum := sha256.Sum256(data)
	hash := hex.EncodeToString(sum[:8])
	return base64.StdEncoding.EncodeToString(data), hash
}
