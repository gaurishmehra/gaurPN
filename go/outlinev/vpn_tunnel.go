//go:build android

package outlinev

import (
	ssout "github.com/Jigsaw-Code/outline-go-tun2socks/outline/shadowsocks"
	outlinetun2socks "github.com/Jigsaw-Code/outline-go-tun2socks/outline/tun2socks"
)

// ConnectTunnel reads packets from the TUN device fd and routes them through a
// Shadowsocks proxy. Does not take ownership of the fd: the caller must close
// it after calling Tunnel.Disconnect(). Returns the bound outline.Tunnel.
//
// Android-only: ConnectShadowsocksTunnel lives in tun2socks/tunnel_android.go,
// so it only exists when compiling for GOOS=android.
func ConnectTunnel(fd int, configJSON string, p Protector, isUDPEnabled bool) (outlinetun2socks.Tunnel, error) {
	client, err := makeClient(configJSON, p)
	if err != nil {
		return nil, err
	}
	// shadowsocks.Client is defined as `type Client outline.Client`, so the
	// pointer types are directly convertible (identical underlying types).
	ssClient := (*ssout.Client)(client)
	return outlinetun2socks.ConnectShadowsocksTunnel(fd, ssClient, isUDPEnabled)
}
