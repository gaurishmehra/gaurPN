package main

import (
	"errors"
	"log"
	"math"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
)

// UPower's DisplayDevice is the aggregate battery the desktop shell shows.
const (
	upowerBusName     = "org.freedesktop.UPower"
	upowerDeviceIface = "org.freedesktop.UPower.Device"
	upowerPropsIface  = "org.freedesktop.DBus.Properties"
	upowerDevicePath  = dbus.ObjectPath("/org/freedesktop/UPower/devices/DisplayDevice")
)

// UPower enum values pinned by the D-Bus interface. State 0 (unknown) and 6
// (pending discharge) are deliberately absent: both mean "no usable battery
// reading" for the v3.2 status blob.
const (
	upowerTypeBattery = 2

	upowerStateCharging      = 1
	upowerStateDischarging   = 2
	upowerStateEmpty         = 3
	upowerStateFullyCharged  = 4
	upowerStatePendingCharge = 5
)

const (
	statusBatteryDelta = 2
	statusMinInterval  = 30 * time.Second

	// Receivers drop status blobs whose authenticated timestamp is older
	// than 10 minutes, and a battery that is not changing — a laptop parked
	// at full — stops emitting UPower events long before that. Instead of a
	// timer, an unchanged reading is re-advertised when presence traffic
	// proves someone is listening: each peers broadcast the publisher
	// receives triggers at most one repaint per this interval.
	statusRepaintInterval = 5 * time.Minute
)

// batteryReading is one decoded UPower sample. Present is false when the
// machine has no battery (or UPower has no percentage yet).
type batteryReading struct {
	Battery  int
	Charging bool
	Present  bool
}

// upowerDecision maps the DisplayDevice Percentage/State/Type properties to a
// publishable reading. It is pure so the no-battery rules are table-testable
// without a system bus. Charging is exactly State 1 or 4; a 0% reading is
// only trusted while charging, which is the pinned "no battery" heuristic.
func upowerDecision(deviceType, state int, percentage float64) (battery int, charging bool, ok bool) {
	if deviceType != upowerTypeBattery {
		return 0, false, false
	}
	switch state {
	case upowerStateCharging, upowerStateDischarging, upowerStateEmpty,
		upowerStateFullyCharged, upowerStatePendingCharge:
	default:
		return 0, false, false
	}
	if math.IsNaN(percentage) || math.IsInf(percentage, 0) {
		return 0, false, false
	}
	battery = int(math.Round(percentage))
	if battery < 0 {
		battery = 0
	}
	if battery > 100 {
		battery = 100
	}
	if battery == 0 && state != upowerStateCharging {
		return 0, false, false
	}
	charging = state == upowerStateCharging || state == upowerStateFullyCharged
	return battery, charging, true
}

// readUPowerReading reads the DisplayDevice properties over the system bus.
// A missing property is not an error: it means UPower has no battery to
// report, and the caller publishes nothing.
func readUPowerReading(conn *dbus.Conn) (batteryReading, error) {
	if conn == nil {
		return batteryReading{}, errors.New("no system bus")
	}
	var props map[string]dbus.Variant
	if err := conn.Object(upowerBusName, upowerDevicePath).Call(
		upowerPropsIface+".GetAll", 0, upowerDeviceIface,
	).Store(&props); err != nil {
		return batteryReading{}, err
	}
	percentage, ok := props["Percentage"]
	if !ok {
		return batteryReading{}, nil
	}
	state, ok := props["State"]
	if !ok {
		return batteryReading{}, nil
	}
	deviceType, ok := props["Type"]
	if !ok {
		return batteryReading{}, nil
	}
	battery, charging, present := upowerDecision(
		int(variantUint32(deviceType)), int(variantUint32(state)), variantFloat(percentage),
	)
	return batteryReading{Battery: battery, Charging: charging, Present: present}, nil
}

// variantUint32 reads the unsigned 32-bit properties UPower uses for State
// and Type, tolerating the numeric variants other D-Bus implementations emit.
func variantUint32(value dbus.Variant) uint32 {
	switch n := value.Value().(type) {
	case uint32:
		return n
	case int32:
		return uint32(n)
	case uint64:
		return uint32(n)
	case int64:
		return uint32(n)
	case float64:
		return uint32(n)
	default:
		return 0
	}
}

// upowerSignalRelevant matches only the DisplayDevice PropertiesChanged
// signal; battery_BAT0 and the other per-device paths are ignored.
func upowerSignalRelevant(signal *dbus.Signal) bool {
	if signal == nil {
		return false
	}
	return signal.Name == upowerPropsIface+".PropertiesChanged" &&
		signal.Path == upowerDevicePath
}

// statusThrottle is the event-driven publish gate: a reading is sent only
// when the battery moved by at least 2 points or charging flipped, and never
// more than once per 30 seconds. It keeps the last *sent* reading, so a
// change that arrives inside the window is sent by the next event after the
// window instead of being lost.
type statusThrottle struct {
	have     bool
	battery  int
	charging bool
	lastSent time.Time
}

func (t *statusThrottle) shouldSend(battery int, charging bool, now time.Time) bool {
	if !t.have {
		t.force(battery, charging, now)
		return true
	}
	if charging == t.charging && absInt(battery-t.battery) < statusBatteryDelta {
		return false
	}
	if now.Sub(t.lastSent) < statusMinInterval {
		return false
	}
	t.force(battery, charging, now)
	return true
}

// repaintDue reports whether an unchanged reading is due a re-advertisement:
// the event-driven gates above would otherwise never fire for a battery
// sitting at the same level, and peers drop blobs whose timestamp ages past
// the freshness window. Repaints are requested only when presence traffic
// shows a consumer exists; this check is what keeps them bounded.
func (t *statusThrottle) repaintDue(now time.Time) bool {
	return t.have && now.Sub(t.lastSent) >= statusRepaintInterval
}

// force records a reading as sent. The reconnect resend uses it so every new
// relay connection carries the current status once, regardless of throttle.
func (t *statusThrottle) force(battery int, charging bool, now time.Time) {
	t.have = true
	t.battery = battery
	t.charging = charging
	t.lastSent = now
}

func absInt(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// statusWriter is the subset of wsClient the publisher needs. The interface
// keeps the UPower decision testable without a live relay connection.
type statusWriter interface {
	WriteJSON(v interface{}) error
}

// StatusPublisher watches UPower and publishes the encrypted status blob on
// the daemon's WebSocket. It is created once per daemon lifetime and gets a
// new writer on every reconnect.
type StatusPublisher struct {
	cfg           Config
	peerCryptoKey []byte
	conn          *dbus.Conn
	ownsConn      bool
	signals       chan *dbus.Signal
	stop          chan struct{}
	stopOnce      sync.Once
	read          func(*dbus.Conn) (batteryReading, error)
	now           func() time.Time

	mu         sync.Mutex
	writer     statusWriter
	throttle   statusThrottle
	last       batteryReading
	haveLast   bool
	warnedRead bool
}

// openStatusBus returns the system-bus connection the publisher watches. A
// private connection is preferred so Stop can close it without disturbing any
// other user of the process-wide shared bus; when a private connection cannot
// be opened, the shared bus is used and left open.
func openStatusBus() (*dbus.Conn, bool, error) {
	conn, err := dbus.SystemBusPrivate()
	if err == nil {
		if authErr := conn.Auth(nil); authErr == nil {
			if helloErr := conn.Hello(); helloErr == nil {
				return conn, true, nil
			}
		}
		_ = conn.Close()
	}
	shared, sharedErr := dbus.SystemBus()
	if sharedErr != nil {
		if err != nil {
			return nil, false, err
		}
		return nil, false, sharedErr
	}
	return shared, false, nil
}

// NewStatusPublisher connects to the system bus and probes UPower once. A
// machine without a system bus or without UPower gets a disabled feature and
// a single log line, never a daemon failure.
func NewStatusPublisher(cfg Config, peerCryptoKey []byte) (*StatusPublisher, error) {
	conn, ownsConn, err := openStatusBus()
	if err != nil {
		return nil, err
	}
	p := &StatusPublisher{
		cfg:           cfg,
		peerCryptoKey: append([]byte(nil), peerCryptoKey...),
		conn:          conn,
		ownsConn:      ownsConn,
		signals:       make(chan *dbus.Signal, 16),
		stop:          make(chan struct{}),
		read:          readUPowerReading,
		now:           time.Now,
	}
	// Probe once so a machine without UPower disables the feature instead of
	// logging on every signal.
	if _, err := p.read(conn); err != nil {
		if ownsConn {
			_ = conn.Close()
		}
		return nil, err
	}
	p.refresh()
	go p.loop()
	return p, nil
}

func (p *StatusPublisher) loop() {
	// A nil signal channel parks that select case: without a match rule no
	// PropertiesChanged ever arrives, but repaints (driven by peers traffic,
	// not by this loop) and reconnect resends still keep a present battery
	// from going stale.
	var signals <-chan *dbus.Signal
	if err := p.conn.AddMatchSignal(
		dbus.WithMatchObjectPath(upowerDevicePath),
		dbus.WithMatchInterface(upowerPropsIface),
		dbus.WithMatchMember("PropertiesChanged"),
	); err != nil {
		// Without the match rule no PropertiesChanged ever arrives. Keep the
		// publisher (reconnect resends and peers-driven repaints still work)
		// and say why once.
		log.Printf("[STATUS] Could not subscribe to UPower: %v", err)
	} else {
		p.conn.Signal(p.signals)
		defer p.conn.RemoveSignal(p.signals)
		signals = p.signals

		// Re-read after the match is installed so a change between the probe
		// and the subscription is not missed.
		p.refresh()
	}
	for {
		select {
		case <-p.stop:
			return
		case signal, ok := <-signals:
			if !ok {
				return
			}
			if upowerSignalRelevant(signal) {
				p.refresh()
			}
		}
	}
}

// RepaintIfStale re-advertises the current battery when presence traffic
// shows the cluster is listening and the last advertisement is ageing
// towards the receivers' freshness drop. Called from the daemon's inbound
// peers handler — the event source is the relay's fan-out, never a timer.
// A quiet cluster costs one mutex and a time compare per peers frame.
func (p *StatusPublisher) RepaintIfStale() {
	if p == nil {
		return
	}
	now := p.now()
	p.mu.Lock()
	due := p.haveLast && p.throttle.repaintDue(now)
	p.mu.Unlock()
	if !due {
		return
	}
	p.refresh()
}

// Stop ends the watcher and closes the bus connection when the publisher owns
// it. It is safe to call more than once.
func (p *StatusPublisher) Stop() {
	if p == nil {
		return
	}
	p.stopOnce.Do(func() {
		close(p.stop)
		if p.ownsConn && p.conn != nil {
			_ = p.conn.Close()
		}
	})
}

// SetWriter installs the current relay connection. A non-nil writer triggers
// one immediate resend of the last reading, which is the reconnect contract.
func (p *StatusPublisher) SetWriter(writer statusWriter) {
	if p == nil {
		return
	}
	p.mu.Lock()
	p.writer = writer
	last, haveLast := p.last, p.haveLast
	p.mu.Unlock()
	if writer == nil {
		return
	}
	if haveLast {
		p.publishReading(last, true)
		return
	}
	p.refresh()
}

// Current returns the last local reading for the daemon's own peers entry.
func (p *StatusPublisher) Current() (batteryReading, bool) {
	if p == nil {
		return batteryReading{}, false
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.last, p.haveLast
}

func (p *StatusPublisher) refresh() {
	reading, err := p.read(p.conn)
	if err != nil {
		p.warnRead(err)
		return
	}
	p.publishReading(reading, false)
}

func (p *StatusPublisher) warnRead(err error) {
	p.mu.Lock()
	first := !p.warnedRead
	p.warnedRead = true
	p.mu.Unlock()
	if first {
		log.Printf("[STATUS] UPower read failed: %v", err)
	}
}

// publishReading applies the throttle and writes one status frame. A nil
// writer still records the reading so the first reconnect can resend it.
func (p *StatusPublisher) publishReading(reading batteryReading, force bool) {
	if !reading.Present {
		// A machine that reports no battery must not keep resending the last
		// sample on every reconnect. Reset the baseline too, so a battery
		// that reappears is published as a fresh reading.
		p.mu.Lock()
		p.last = batteryReading{}
		p.haveLast = false
		p.throttle = statusThrottle{}
		p.mu.Unlock()
		return
	}
	now := p.now()
	p.mu.Lock()
	switch {
	case force:
		p.throttle.force(reading.Battery, reading.Charging, now)
	case p.throttle.shouldSend(reading.Battery, reading.Charging, now):
	case p.throttle.repaintDue(now):
		// Unchanged but ageing: refresh the stored blob's timestamp so
		// receivers keep seeing the battery.
		p.throttle.force(reading.Battery, reading.Charging, now)
	default:
		p.mu.Unlock()
		return
	}
	writer := p.writer
	p.last = reading
	p.haveLast = true
	p.mu.Unlock()
	if writer == nil {
		return
	}
	blob, err := EncodeStatusBlob(
		DeriveStatusKey(p.peerCryptoKey), p.cfg.DeviceID,
		reading.Battery, reading.Charging, now.UnixMilli(),
	)
	if err != nil {
		log.Printf("[STATUS] Could not encode status: %v", err)
		return
	}
	if err := writer.WriteJSON(map[string]interface{}{
		"type": "status", "v": 1, "status": blob,
	}); err != nil {
		log.Printf("[STATUS] Could not publish status: %v", err)
	}
}

// daemonStatus is the process-wide watcher the signal handler stops. The
// daemon is the only command that creates one.
var (
	daemonStatusMu sync.Mutex
	daemonStatus   *StatusPublisher
)

func setDaemonStatus(p *StatusPublisher) {
	daemonStatusMu.Lock()
	daemonStatus = p
	daemonStatusMu.Unlock()
}

func stopDaemonStatus() {
	daemonStatusMu.Lock()
	p := daemonStatus
	daemonStatus = nil
	daemonStatusMu.Unlock()
	if p != nil {
		p.Stop()
	}
}
