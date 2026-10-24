// Package outlinev exposes a small, protected-socket wrapper over the Outline
// SDK (github.com/Jigsaw-Code/outline-go-tun2socks) for building Android VPN
// clients with gomobile.
//
// The critical difference from building the stock outline.aar is that every
// socket opened here goes through a Protector (Android VpnService.protect()),
// which prevents the tunnel's own traffic from being routed back through the
// VPN (an endless routing loop).
//
// Bind with:
//
//	gomobile bind -target=android -o ../../app/libs/tun2socks.aar \
//	  -javapkg=dev.gaurish.outlinelite . ../../third_party/outline-go-tun2socks/outline/tun2socks \
//	  ../../third_party/outline-go-tun2socks/outline/shadowsocks
package outlinev

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"time"

	protect "github.com/Jigsaw-Code/outline-go-tun2socks/intra/protect"
	outline "github.com/Jigsaw-Code/outline-go-tun2socks/outline"
	connectivity "github.com/Jigsaw-Code/outline-go-tun2socks/outline/connectivity"
	ssout "github.com/Jigsaw-Code/outline-go-tun2socks/outline/shadowsocks"
	"github.com/Jigsaw-Code/outline-sdk/transport"
	shadowsocks "github.com/Jigsaw-Code/outline-sdk/transport/shadowsocks"
)

// Protector is the Go-side contract for Android's VpnService.protect().
// Implemented on the Kotlin side. Every socket the tunnel opens must be
// protected, otherwise the VPN routes its own traffic through itself.
type Protector interface {
	// Protect marks the socket fd to bypass the VPN. Returns whether it succeeded.
	Protect(socket int32) bool
	// GetResolvers returns a comma-separated list of system DNS resolver IPs,
	// used by the Go resolver to resolve names directly (bypassing the VPN).
	GetResolvers() string
}

// ssConfig mirrors the ShadowsocksSessionConfig JSON used by the Outline client.
type ssConfig struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Password string `json:"password"`
	Method   string `json:"method"`
}

// parseConfig parses and validates a Shadowsocks configuration JSON.
func parseConfig(configJSON string) (ssConfig, error) {
	var c ssConfig
	if err := json.Unmarshal([]byte(configJSON), &c); err != nil {
		return c, fmt.Errorf("failed to parse configuration JSON: %w", err)
	}
	if len(c.Host) == 0 {
		return c, errors.New("must provide a host name or IP address")
	}
	if c.Port <= 0 || c.Port > 65535 {
		return c, errors.New("port must be within range [1..65535]")
	}
	if len(c.Method) == 0 {
		return c, errors.New("must provide an encryption cipher method")
	}
	if len(c.Password) == 0 {
		return c, errors.New("must provide a password")
	}
	return c, nil
}

// resolveProxyAddress resolves the proxy host with the protected dialer's
// resolver (system resolvers, protected), preferring IPv4 addresses.
func resolveProxyAddress(d *net.Dialer, host string, port int) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	ips, err := d.Resolver.LookupIPAddr(ctx, host)
	if err != nil {
		return "", fmt.Errorf("failed to resolve proxy address %q: %w", host, err)
	}
	var ip net.IP
	for _, a := range ips {
		if a.IP.To4() != nil {
			ip = a.IP
			break
		}
	}
	if ip == nil && len(ips) > 0 {
		ip = ips[0].IP
	}
	if ip == nil {
		return "", fmt.Errorf("no IP addresses found for %q", host)
	}
	return net.JoinHostPort(ip.String(), fmt.Sprint(port)), nil
}

// makeClient creates an Outline client whose transport endpoints dial through
// the protected dialer, so tunnel traffic bypasses the VPN interface.
func makeClient(configJSON string, p Protector) (*outline.Client, error) {
	if p == nil {
		return nil, errors.New("protector is required")
	}
	c, err := parseConfig(configJSON)
	if err != nil {
		return nil, err
	}
	d := protect.MakeDialer(p)
	proxyAddress, err := resolveProxyAddress(d, c.Host, c.Port)
	if err != nil {
		return nil, err
	}
	cryptoKey, err := shadowsocks.NewEncryptionKey(c.Method, c.Password)
	if err != nil {
		return nil, fmt.Errorf("failed to create Shadowsocks cipher: %w", err)
	}
	streamDialer, err := shadowsocks.NewStreamDialer(&transport.TCPEndpoint{Dialer: *d, Address: proxyAddress}, cryptoKey)
	if err != nil {
		return nil, fmt.Errorf("failed to create StreamDialer: %w", err)
	}
	packetListener, err := shadowsocks.NewPacketListener(&transport.UDPEndpoint{Dialer: *d, Address: proxyAddress}, cryptoKey)
	if err != nil {
		return nil, fmt.Errorf("failed to create PacketListener: %w", err)
	}
	return &outline.Client{StreamDialer: streamDialer, PacketListener: packetListener}, nil
}

// CheckConnectivity tests whether the proxy relays TCP and UDP traffic under
// the current network. Returns one of the ssout error-code constants
// (ssout.NoError == 0 on success).
func CheckConnectivity(configJSON string, p Protector) (int, error) {
	client, err := makeClient(configJSON, p)
	if err != nil {
		return ssout.IllegalConfiguration, err
	}
	code, err := connectivity.CheckConnectivity(client)
	return code.Number(), err
}

// CheckServerReachable tests whether the server is reachable over TCP through
// the protected dialer. Returns nil on success.
func CheckServerReachable(configJSON string, p Protector) error {
	c, err := parseConfig(configJSON)
	if err != nil {
		return err
	}
	if p == nil {
		return errors.New("protector is required")
	}
	d := protect.MakeDialer(p)
	address, err := resolveProxyAddress(d, c.Host, c.Port)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, err := d.DialContext(ctx, "tcp", address)
	if err != nil {
		return fmt.Errorf("server at %s is unreachable: %w", address, err)
	}
	conn.Close()
	return nil
}
