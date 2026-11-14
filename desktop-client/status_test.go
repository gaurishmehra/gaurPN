package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/godbus/dbus/v5"
)

func TestUPowerDecision(t *testing.T) {
	cases := []struct {
		name       string
		deviceType int
		state      int
		percentage float64
		battery    int
		charging   bool
		ok         bool
	}{
		{"charging", upowerTypeBattery, upowerStateCharging, 73, 73, true, true},
		{"fully charged", upowerTypeBattery, upowerStateFullyCharged, 100, 100, true, true},
		{"discharging", upowerTypeBattery, upowerStateDischarging, 55, 55, false, true},
		{"pending charge is not charging", upowerTypeBattery, upowerStatePendingCharge, 40, 40, false, true},
		{"rounds to nearest point", upowerTypeBattery, upowerStateDischarging, 54.6, 55, false, true},
		{"clamps above 100", upowerTypeBattery, upowerStateDischarging, 140, 100, false, true},
		{"clamps below 0 while charging", upowerTypeBattery, upowerStateCharging, -4, 0, true, true},
		{"zero while charging is a real battery", upowerTypeBattery, upowerStateCharging, 0, 0, true, true},
		{"zero while discharging is no battery", upowerTypeBattery, upowerStateDischarging, 0, 0, false, false},
		{"empty is no battery", upowerTypeBattery, upowerStateEmpty, 0, 0, false, false},
		{"unknown state is no battery", upowerTypeBattery, 0, 50, 0, false, false},
		{"pending discharge is no battery", upowerTypeBattery, 6, 50, 0, false, false},
		{"line power is not a battery", 1, upowerStateCharging, 100, 0, false, false},
		{"unknown device type", 0, upowerStateDischarging, 50, 0, false, false},
		{"NaN percentage", upowerTypeBattery, upowerStateDischarging, math.NaN(), 0, false, false},
		{"infinite percentage", upowerTypeBattery, upowerStateDischarging, math.Inf(1), 0, false, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			battery, charging, ok := upowerDecision(tc.deviceType, tc.state, tc.percentage)
			if battery != tc.battery || charging != tc.charging || ok != tc.ok {
				t.Fatalf("upowerDecision(%d, %d, %v) = (%d, %v, %v), want (%d, %v, %v)",
					tc.deviceType, tc.state, tc.percentage,
					battery, charging, ok, tc.battery, tc.charging, tc.ok)
			}
		})
	}
}

func TestUPowerSignalRelevant(t *testing.T) {
	cases := []struct {
		name string
		sig  *dbus.Signal
		want bool
	}{
		{
			name: "display device properties changed",
			sig: &dbus.Signal{
				Name: "org.freedesktop.DBus.Properties.PropertiesChanged",
				Path: upowerDevicePath,
			},
			want: true,
		},
		{
			name: "per-battery path is ignored",
			sig: &dbus.Signal{
				Name: "org.freedesktop.DBus.Properties.PropertiesChanged",
				Path: dbus.ObjectPath("/org/freedesktop/UPower/devices/battery_BAT0"),
			},
			want: false,
		},
		{
			name: "other signal member is ignored",
			sig: &dbus.Signal{
				Name: "org.freedesktop.DBus.Properties.Get",
				Path: upowerDevicePath,
			},
			want: false,
		},
		{name: "nil signal", sig: nil, want: false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := upowerSignalRelevant(tc.sig); got != tc.want {
				t.Fatalf("upowerSignalRelevant = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestStatusThrottle(t *testing.T) {
	base := time.UnixMilli(1730000000000)
	var throttle statusThrottle

	if !throttle.shouldSend(50, false, base) {
		t.Fatal("first reading must be sent")
	}
	if throttle.shouldSend(51, false, base.Add(31*time.Second)) {
		t.Fatal("a one-point change must not be sent")
	}
	if !throttle.shouldSend(52, false, base.Add(32*time.Second)) {
		t.Fatal("a two-point change after the window must be sent")
	}
	if throttle.shouldSend(60, false, base.Add(40*time.Second)) {
		t.Fatal("a change inside the 30-second window must be dropped")
	}
	// The baseline is the last sent reading, so the deferred change is sent
	// by the next event once the window has elapsed.
	if !throttle.shouldSend(60, false, base.Add(63*time.Second)) {
		t.Fatal("a deferred change must be sent after the window")
	}
	if throttle.shouldSend(60, false, base.Add(80*time.Second)) {
		t.Fatal("an unchanged reading must not be sent")
	}
	if !throttle.shouldSend(60, true, base.Add(100*time.Second)) {
		t.Fatal("a charging flip after the window must be sent")
	}
	if throttle.shouldSend(60, true, base.Add(110*time.Second)) {
		t.Fatal("an unchanged charging state must not be sent")
	}
	if !throttle.shouldSend(58, true, base.Add(140*time.Second)) {
		t.Fatal("a two-point drop must be sent")
	}
}

type fakeStatusWriter struct {
	mu     sync.Mutex
	frames []map[string]interface{}
}

func (w *fakeStatusWriter) WriteJSON(v interface{}) error {
	frame, _ := v.(map[string]interface{})
	w.mu.Lock()
	w.frames = append(w.frames, frame)
	w.mu.Unlock()
	return nil
}

func (w *fakeStatusWriter) snapshot() []map[string]interface{} {
	w.mu.Lock()
	defer w.mu.Unlock()
	return append([]map[string]interface{}(nil), w.frames...)
}

func TestStatusPublisherPublishesAndSuppresses(t *testing.T) {
	_, peerKey := DeriveSubKeys("status-publisher-token")
	now := time.UnixMilli(1730000000000)
	reading := batteryReading{Battery: 73, Charging: true, Present: true}

	p := &StatusPublisher{
		cfg:           Config{DeviceID: "desktop-1"},
		peerCryptoKey: peerKey,
		read:          func(*dbus.Conn) (batteryReading, error) { return reading, nil },
		now:           func() time.Time { return now },
	}
	writer := &fakeStatusWriter{}
	p.SetWriter(writer)

	frames := writer.snapshot()
	if len(frames) != 1 {
		t.Fatalf("initial publish produced %d frames, want 1", len(frames))
	}
	if frames[0]["type"] != "status" || frames[0]["v"] != 1 {
		t.Fatalf("frame = %v", frames[0])
	}
	blob, _ := frames[0]["status"].(string)
	battery, charging, ts, err := DecodeStatusBlob(DeriveStatusKey(peerKey), "desktop-1", blob)
	if err != nil {
		t.Fatalf("decode published blob: %v", err)
	}
	if battery != 73 || !charging || ts != now.UnixMilli() {
		t.Fatalf("published reading = (%d, %v, %d), want (73, true, %d)", battery, charging, ts, now.UnixMilli())
	}

	// The same reading is throttled.
	p.refresh()
	if got := len(writer.snapshot()); got != 1 {
		t.Fatalf("unchanged reading produced %d frames, want 1", got)
	}

	// A real change after the window is published.
	now = now.Add(31 * time.Second)
	reading = batteryReading{Battery: 75, Charging: true, Present: true}
	p.refresh()
	if got := len(writer.snapshot()); got != 2 {
		t.Fatalf("changed reading produced %d frames, want 2", got)
	}

	// A reconnect resend bypasses the throttle exactly once.
	p.SetWriter(writer)
	if got := len(writer.snapshot()); got != 3 {
		t.Fatalf("reconnect resend produced %d frames, want 3", got)
	}
}

func TestStatusPublisherNoBatteryPublishesNothing(t *testing.T) {
	_, peerKey := DeriveSubKeys("status-publisher-token")
	now := time.UnixMilli(1730000000000)
	reading := batteryReading{Present: false}
	p := &StatusPublisher{
		cfg:           Config{DeviceID: "desktop-1"},
		peerCryptoKey: peerKey,
		read:          func(*dbus.Conn) (batteryReading, error) { return reading, nil },
		now:           func() time.Time { return now },
	}
	writer := &fakeStatusWriter{}
	p.SetWriter(writer)
	p.refresh()
	if got := len(writer.snapshot()); got != 0 {
		t.Fatalf("no-battery machine published %d frames, want 0", got)
	}
	if _, ok := p.Current(); ok {
		t.Fatal("no-battery machine must not report a current reading")
	}

	// A battery that disappears must not keep resending the last sample on
	// every reconnect, and one that reappears is published fresh.
	reading = batteryReading{Battery: 60, Charging: false, Present: true}
	p.refresh()
	if got := len(writer.snapshot()); got != 1 {
		t.Fatalf("battery appearance published %d frames, want 1", got)
	}
	reading = batteryReading{Present: false}
	p.refresh()
	p.SetWriter(writer)
	if got := len(writer.snapshot()); got != 1 {
		t.Fatalf("reconnect after battery loss resent a stale sample (%d frames)", got)
	}
	reading = batteryReading{Battery: 60, Charging: false, Present: true}
	now = now.Add(time.Minute)
	p.refresh()
	if got := len(writer.snapshot()); got != 2 {
		t.Fatalf("battery reappearance published %d frames, want 2", got)
	}
}

func TestPeerFileEntryBatteryOmitempty(t *testing.T) {
	known, err := json.Marshal(peerFileEntry{
		ID: "phone-1", Name: "Lavander", Status: "online",
		Battery: intPtr(0), Charging: boolPtr(false),
	})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(known), `"battery":0`) {
		t.Fatalf("known 0%% battery was omitted: %s", known)
	}
	if !strings.Contains(string(known), `"charging":false`) {
		t.Fatalf("known not-charging flag was omitted: %s", known)
	}

	unknown, err := json.Marshal(peerFileEntry{ID: "phone-2", Name: "Other", Status: "online"})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(unknown), `"battery"`) || strings.Contains(string(unknown), `"charging"`) {
		t.Fatalf("unknown telemetry must be omitted: %s", unknown)
	}
}

func TestBuildPeerEntriesDecodesStatus(t *testing.T) {
	_, peerKey := DeriveSubKeys("status-peers-token")
	blob, err := EncodeStatusBlob(DeriveStatusKey(peerKey), "phone-1", 73, true, time.Now().UnixMilli())
	if err != nil {
		t.Fatal(err)
	}
	peersRaw := []interface{}{
		map[string]interface{}{"id": "phone-1", "name": "Lavander", "status": blob},
		map[string]interface{}{"id": "desktop-1", "name": "Desktop"},
		map[string]interface{}{"id": "phone-2", "name": "Other", "status": "not-base64"},
	}
	var warned []string
	entries := buildPeerEntries(
		Config{DeviceID: "desktop-1", Name: "Desktop"}, peerKey, peersRaw,
		func(id string, err error) { warned = append(warned, id) },
	)
	if len(entries) != 3 {
		t.Fatalf("entries = %d, want 3", len(entries))
	}

	phone := entries[0]
	if phone.Battery == nil || *phone.Battery != 73 || phone.Charging == nil || !*phone.Charging {
		t.Fatalf("phone-1 telemetry = %+v", phone)
	}
	if !entries[1].IsSelf {
		t.Fatal("desktop-1 must be marked as self")
	}
	if entries[1].Battery != nil || entries[1].Charging != nil {
		t.Fatalf("self entry must not decode its own echoed blob: %+v", entries[1])
	}
	if entries[2].Battery != nil || entries[2].Charging != nil {
		t.Fatalf("malformed blob must be skipped: %+v", entries[2])
	}
	if len(warned) != 1 || warned[0] != "phone-2" {
		t.Fatalf("warned = %v, want [phone-2]", warned)
	}
}

func TestApplyOwnStatus(t *testing.T) {
	entries := []peerFileEntry{
		{ID: "phone-1", Name: "Lavander"},
		{ID: "desktop-1", Name: "Desktop", IsSelf: true},
		// A second machine with the same display name (the default is
		// "Lilac") must keep its own telemetry, not inherit ours.
		{ID: "desktop-2", Name: "Desktop", IsSelf: true},
	}
	applyOwnStatus(entries, "desktop-1", batteryReading{Battery: 88, Charging: true, Present: true}, true)
	if entries[1].Battery == nil || *entries[1].Battery != 88 || entries[1].Charging == nil || !*entries[1].Charging {
		t.Fatalf("self entry = %+v", entries[1])
	}
	if entries[0].Battery != nil {
		t.Fatalf("other entry must not be touched: %+v", entries[0])
	}
	if entries[2].Battery != nil || entries[2].Charging != nil {
		t.Fatalf("same-name peer must not inherit our reading: %+v", entries[2])
	}

	// No local battery: leave the self entry unknown.
	unknown := []peerFileEntry{{ID: "desktop-1", IsSelf: true}}
	applyOwnStatus(unknown, "desktop-1", batteryReading{}, false)
	if unknown[0].Battery != nil || unknown[0].Charging != nil {
		t.Fatalf("no-battery self entry = %+v", unknown[0])
	}

	// An empty device id never matches anything.
	blank := []peerFileEntry{{ID: "", IsSelf: true}}
	applyOwnStatus(blank, "", batteryReading{Battery: 50, Present: true}, true)
	if blank[0].Battery != nil {
		t.Fatalf("empty device id matched: %+v", blank[0])
	}
}

// TestWarnStatusOnce pins the one-log-line-per-device rule for malformed
// status blobs.
func TestWarnStatusOnce(t *testing.T) {
	var buf bytes.Buffer
	old := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(old)

	s := &daemonSession{}
	s.warnStatusOnce("phone-1", errors.New("bad blob"))
	s.warnStatusOnce("phone-1", errors.New("still bad"))
	s.warnStatusOnce("phone-2", errors.New("bad blob"))

	if got := strings.Count(buf.String(), "phone-1"); got != 1 {
		t.Fatalf("phone-1 logged %d times, want 1: %q", got, buf.String())
	}
	if got := strings.Count(buf.String(), "phone-2"); got != 1 {
		t.Fatalf("phone-2 logged %d times, want 1: %q", got, buf.String())
	}
}

// TestDaemonPeersFileCarriesStatus is the end-to-end telemetry path: a peer
// sends an encrypted status frame, the fake relay stores and rebroadcasts it,
// and the daemon decodes it into peers.json.
func TestDaemonPeersFileCarriesStatus(t *testing.T) {
	f := newFakeRelay(t)
	relayToken, peerKey := DeriveSubKeys(f.Token)
	dir := t.TempDir()
	cfg := Config{
		RelayAddr: f.URL,
		DeviceID:  "desktop-1",
		Name:      "Desktop",
		InboxDir:  filepath.Join(dir, "inbox"),
		PeersFile: filepath.Join(dir, "peers.json"),
		Clipboard: false,
	}
	session := &daemonSession{
		cfg:             cfg,
		relayToken:      relayToken,
		peerCryptoKey:   peerKey,
		sender:          &CommandSender{cfg: cfg},
		broadcaster:     &MediaBroadcaster{cfg: cfg},
		store:           &peerStore{},
		activeTransfers: &sync.Map{},
	}
	done := make(chan bool, 1)
	go func() { done <- session.runOnce() }()
	waitFor(t, 5*time.Second, func() bool { return f.hasPeer(cfg.DeviceID) })

	phoneCfg := Config{RelayAddr: f.URL, DeviceID: "phone-1", Name: "Lavander"}
	phoneConn, err := connectWS(phoneCfg, relayToken)
	if err != nil {
		t.Fatalf("phone connect: %v", err)
	}
	defer phoneConn.Close()

	blob, err := EncodeStatusBlob(DeriveStatusKey(peerKey), "phone-1", 73, true, time.Now().UnixMilli())
	if err != nil {
		t.Fatalf("EncodeStatusBlob: %v", err)
	}
	if err := phoneConn.WriteJSON(map[string]interface{}{"type": "status", "v": 1, "status": blob}); err != nil {
		t.Fatalf("send status: %v", err)
	}

	waitFor(t, 5*time.Second, func() bool {
		data, err := os.ReadFile(cfg.PeersFile)
		if err != nil {
			return false
		}
		var payload struct {
			Peers []peerFileEntry `json:"peers"`
		}
		if json.Unmarshal(data, &payload) != nil {
			return false
		}
		for _, p := range payload.Peers {
			if p.ID == "phone-1" {
				return p.Battery != nil && *p.Battery == 73 &&
					p.Charging != nil && *p.Charging
			}
		}
		return false
	})

	f.killConn(cfg.DeviceID)
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("daemon session did not wind down after the relay connection closed")
	}
}

// --- D5: status blob hardening ---

func TestDecodeStatusBlobLengthCap(t *testing.T) {
	_, peerKey := DeriveSubKeys("status-cap-token")
	key := DeriveStatusKey(peerKey)

	_, _, _, err := DecodeStatusBlob(key, "phone-1", strings.Repeat("A", statusBlobMaxEncoded+1))
	if err == nil || !strings.Contains(err.Error(), "cap") {
		t.Fatalf("oversized blob error = %v, want a cap rejection", err)
	}

	// A blob exactly at the cap passes the length gate and fails later (it is
	// not valid ciphertext), which proves the cap is not off by one.
	_, _, _, err = DecodeStatusBlob(key, "phone-1", strings.Repeat("A", statusBlobMaxEncoded))
	if err == nil || strings.Contains(err.Error(), "cap") {
		t.Fatalf("at-cap blob error = %v, want an authentication failure", err)
	}
}

func TestStatusBlobFresh(t *testing.T) {
	now := time.Now().UnixMilli()
	cases := []struct {
		name string
		ts   int64
		want bool
	}{
		{"now", now, true},
		{"just inside the window", now - statusFreshnessMs, true},
		{"just outside the window", now - statusFreshnessMs - 1, false},
		{"an hour old", now - 60*60*1000, false},
		{"clock skew in the future", now + 60*1000, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := statusBlobFresh(tc.ts, now); got != tc.want {
				t.Fatalf("statusBlobFresh(%d, %d) = %v, want %v", tc.ts, now, got, tc.want)
			}
		})
	}
}

// TestBuildPeerEntriesDropsStaleStatus pins the freshness gate: an
// authenticated but replayed blob must not be shown as current telemetry.
func TestBuildPeerEntriesDropsStaleStatus(t *testing.T) {
	_, peerKey := DeriveSubKeys("status-stale-token")
	key := DeriveStatusKey(peerKey)
	stale, err := EncodeStatusBlob(key, "phone-1", 42, false, time.Now().Add(-11*time.Minute).UnixMilli())
	if err != nil {
		t.Fatal(err)
	}
	fresh, err := EncodeStatusBlob(key, "phone-2", 73, true, time.Now().UnixMilli())
	if err != nil {
		t.Fatal(err)
	}
	peersRaw := []interface{}{
		map[string]interface{}{"id": "phone-1", "name": "Old", "status": stale},
		map[string]interface{}{"id": "phone-2", "name": "New", "status": fresh},
	}
	var warned []string
	entries := buildPeerEntries(
		Config{DeviceID: "desktop-1", Name: "Desktop"}, peerKey, peersRaw,
		func(id string, err error) { warned = append(warned, id) },
	)
	if entries[0].Battery != nil || entries[0].Charging != nil {
		t.Fatalf("stale status must be dropped: %+v", entries[0])
	}
	if entries[1].Battery == nil || *entries[1].Battery != 73 {
		t.Fatalf("fresh status must be shown: %+v", entries[1])
	}
	if len(warned) != 1 || warned[0] != "phone-1" {
		t.Fatalf("warned = %v, want [phone-1]", warned)
	}
}

// TestWarnStatusOnceBounded pins the fixed-size warning table: a relay that
// advertises endless device ids cannot grow it without bound.
func TestWarnStatusOnceBounded(t *testing.T) {
	var buf bytes.Buffer
	old := log.Writer()
	log.SetOutput(&buf)
	defer log.SetOutput(old)

	s := &daemonSession{}
	total := statusWarnCapacity + 50
	for i := 0; i < total; i++ {
		s.warnStatusOnce(fmt.Sprintf("device-%03d", i), errors.New("bad blob"))
	}
	if got := len(s.statusWarned); got != statusWarnCapacity {
		t.Fatalf("warning table holds %d entries, want %d", got, statusWarnCapacity)
	}
	if s.statusWarned["device-000"] {
		t.Fatal("the oldest entry must be evicted")
	}
	newest := fmt.Sprintf("device-%03d", total-1)
	if !s.statusWarned[newest] {
		t.Fatalf("the newest entry %q must be retained", newest)
	}

	// A retained device still logs only once.
	before := strings.Count(buf.String(), newest)
	s.warnStatusOnce(newest, errors.New("still bad"))
	if got := strings.Count(buf.String(), newest); got != before {
		t.Fatalf("%s logged %d times, want %d", newest, got, before)
	}
}
