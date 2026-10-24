module outlinev

go 1.18

require (
	github.com/Jigsaw-Code/outline-go-tun2socks v0.0.0-00010101000000-000000000000
	github.com/Jigsaw-Code/outline-sdk v0.0.7
)

require (
	github.com/eycorsican/go-tun2socks v1.16.11 // indirect
	github.com/shadowsocks/go-shadowsocks2 v0.1.5 // indirect
	golang.org/x/crypto v0.14.0 // indirect
	golang.org/x/mobile v0.0.0-20230906132913-2077a3224571 // indirect
	golang.org/x/sys v0.13.0 // indirect
)

// Use the vendored copy in third_party for build reproducibility.
replace github.com/Jigsaw-Code/outline-go-tun2socks => ../../third_party/outline-go-tun2socks
