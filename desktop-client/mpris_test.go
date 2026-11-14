package main

import (
	"testing"

	"github.com/godbus/dbus/v5"
)

func TestMprisExportAndProperties(t *testing.T) {
	manager, err := NewMprisManager(func(sourceID, sessionID, command string, positionMs int64, volume float64) {})
	if err != nil {
		t.Skipf("no session bus: %v", err)
	}
	manager.Update(MediaState{
		SenderID:   "test-device",
		SenderName: "Test Phone",
		SessionID:  "session-1",
		App:        "Test",
		Title:      "Song",
		Artist:     "Artist",
		Album:      "Album",
		DurationMs: 180000,
		PositionMs: 1000,
		Rate:       1.0,
		Playing:    true,
		CanPlay:    true,
		CanPause:   true,
		CanNext:    true,
		CanPrev:    true,
		CanSeek:    true,
	})

	conn, err := dbus.SessionBus()
	if err != nil {
		t.Skipf("no session bus: %v", err)
	}
	name := "org.mpris.MediaPlayer2.gaurpn.test_device"
	var owned bool
	if err := conn.BusObject().Call("org.freedesktop.DBus.NameHasOwner", 0, name).Store(&owned); err != nil {
		t.Fatalf("NameHasOwner: %v", err)
	}
	if !owned {
		t.Fatalf("%s is not owned", name)
	}

	obj := conn.Object(name, "/org/mpris/MediaPlayer2")
	var status dbus.Variant
	if err := obj.Call("org.freedesktop.DBus.Properties.Get", 0,
		"org.mpris.MediaPlayer2.Player", "PlaybackStatus").Store(&status); err != nil {
		t.Fatalf("Get PlaybackStatus: %v", err)
	}
	if status.Value() != "Playing" {
		t.Fatalf("PlaybackStatus = %v", status.Value())
	}

	var metadata dbus.Variant
	if err := obj.Call("org.freedesktop.DBus.Properties.Get", 0,
		"org.mpris.MediaPlayer2.Player", "Metadata").Store(&metadata); err != nil {
		t.Fatalf("Get Metadata: %v", err)
	}
	fields, ok := metadata.Value().(map[string]dbus.Variant)
	if !ok {
		t.Fatalf("Metadata type = %T", metadata.Value())
	}
	if fields["xesam:title"].Value() != "Song" {
		t.Fatalf("title = %v", fields["xesam:title"].Value())
	}
}

// TestMprisSeekMethodRemainsOnWire pins the ExportWithMap remap: the Go
// method is SeekByOffset (vet's stdmethods check rejects a Seek that does
// not match io.Seeker), but the D-Bus method must stay exactly
// org.mpris.MediaPlayer2.Player.Seek with one int64 ("x") argument.
func TestMprisSeekMethodRemainsOnWire(t *testing.T) {
	manager, err := NewMprisManager(func(sourceID, sessionID, command string, positionMs int64, volume float64) {})
	if err != nil {
		t.Skipf("no session bus: %v", err)
	}
	manager.Update(MediaState{
		SenderID:   "seek-test-device",
		SenderName: "Seek Phone",
		SessionID:  "session-1",
		App:        "Test",
		Title:      "Song",
		DurationMs: 180000,
		PositionMs: 1000,
		Playing:    true,
		CanSeek:    true,
	})

	conn, err := dbus.SessionBus()
	if err != nil {
		t.Skipf("no session bus: %v", err)
	}
	obj := conn.Object("org.mpris.MediaPlayer2.gaurpn.seek_test_device", "/org/mpris/MediaPlayer2")
	// MPRIS Seek takes the offset in microseconds: +500 ms.
	if err := obj.Call("org.mpris.MediaPlayer2.Player.Seek", 0, int64(500*1000)).Store(); err != nil {
		t.Fatalf("Seek must remain callable on the wire with one int64 arg: %v", err)
	}

	var position dbus.Variant
	if err := obj.Call("org.freedesktop.DBus.Properties.Get", 0,
		"org.mpris.MediaPlayer2.Player", "Position").Store(&position); err != nil {
		t.Fatalf("Get Position: %v", err)
	}
	got, ok := position.Value().(int64)
	if !ok {
		t.Fatalf("Position type = %T, want int64", position.Value())
	}
	// 1000 ms + 500 ms offset, in microseconds.
	if got < 1500*1000 {
		t.Fatalf("Position = %d, want at least %d after a +500ms seek", got, 1500*1000)
	}
}
