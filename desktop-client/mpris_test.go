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
