package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
)

func main() {
	var cfg Config
	var jsonOutput bool
	defaultName, defaultRelay := loadSettingsFromConfig()
	flag.StringVar(&cfg.RelayAddr, "relay", defaultRelay, "In-tunnel relay address (host:port)")
	flag.StringVar(&cfg.TokenFile, "key-file", defaultTokenFile(), "Path to cluster secret key file")
	flag.StringVar(&cfg.DeviceID, "device-id", generateDeviceID(), "Unique device identifier")
	flag.StringVar(&cfg.Name, "name", defaultName, "Human readable device name")
	flag.StringVar(&cfg.InboxDir, "inbox", defaultInboxDir(), "Directory where incoming files are saved")
	flag.StringVar(&cfg.PeersFile, "peers-file", defaultPeersFile(), "Path to the peer state file watched by desktop shells")
	flag.BoolVar(&cfg.Clipboard, "clipboard", true, "Sync the desktop clipboard with cluster devices")
	flag.StringVar(&cfg.ClipboardSource, "clipboard-source", "poll", "Clipboard source: poll, socket, or off")
	flag.StringVar(&cfg.ClipboardSocket, "clipboard-socket", defaultClipboardSocket(), "Unix socket for Quickshell clipboard events")
	flag.StringVar(&cfg.LogFile, "log-file", "", "Append daemon logs to this file instead of stderr")
	flag.BoolVar(&jsonOutput, "json", false, "Output results as JSON")
	noOpen := false
	flag.BoolVar(&noOpen, "no-open", false, "Disable opening received files automatically")
	flag.BoolVar(&cfg.OnlyTrusted, "only-trusted", loadOnlyTrustedFromConfig(), "Reject offers from devices not in the trust list")
	flag.BoolVar(&cfg.Notifications, "notifications", true, "Display notifications mirrored from other devices")
	flag.Parse()

	cfg.AutoOpen = !noOpen

	setupLogFile(cfg)

	args := flag.Args()
	if len(args) == 0 {
		fmt.Println("gaur-share: In-Tunnel Zero-Knowledge File Sharing Client")
		fmt.Println("\nUsage:")
		fmt.Println("  gaur-share daemon                   Run background receiver daemon")
		fmt.Println("  gaur-share list [-json]             List all active cluster devices")
		fmt.Println("  gaur-share send [-open] <device> <file|dir>...  Send files or a folder (flat batch)")
		fmt.Println("  gaur-share send --all <file|dir>...          Upload once, send to every online device")
		fmt.Println("  gaur-share send --offline <device> <file>... Queue for a device that may be offline")
		fmt.Println("  gaur-share send --to <id,id> <file>...       Send to specific device IDs")
		fmt.Println("  gaur-share send-text <device> <text|->       Send a text or link (use - for stdin)")
		fmt.Println("  gaur-share pair <gaur://pair?...>           Pair using the QR URI from gaur-relay -init")
		fmt.Println("  gaur-share ui [-addr 127.0.0.1:0]           Local browser UI for peers, text, and files")
		fmt.Println("  gaur-share notify <device> <title> [body]   Send a notification to a device")
		fmt.Println("  gaur-share notify --all <title> [body]      Send a notification to every online device")
		fmt.Println("  gaur-share trust <device>                   Trust a device by id or name")
		fmt.Println("  gaur-share untrust <device>                 Revoke a trusted device")
		fmt.Println("  gaur-share trusted                          List trusted devices and mode")
		fmt.Println("  gaur-share trust-mode on|off                Only accept from trusted devices")
		fmt.Println("\nFlags:")
		flag.PrintDefaults()
		return
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigChan
		fmt.Println("\nShutting down gaur-share...")
		if args[0] == "daemon" {
			writePeersFile(cfg, nil)
			// Unlink the socket the daemon is listening on so Quickshell's
			// next dial does not hit a stale file. Only the socket source
			// ever creates it.
			if cfg.Clipboard && cfg.ClipboardSource == "socket" {
				removeClipboardSocket(cfg)
			}
		}
		os.Exit(0)
	}()

	switch args[0] {
	case "daemon":
		runDaemon(cfg)
	case "list":
		for _, a := range args[1:] {
			if a == "-json" || a == "--json" {
				jsonOutput = true
			}
		}
		if err := runList(cfg, jsonOutput); err != nil {
			// Straight to stderr, not via log: -log-file must not
			// swallow the failure a script needs to see.
			fmt.Fprintln(os.Stderr, "[FATAL] "+err.Error())
			os.Exit(1)
		}
	case "send":
		sendFlags := flag.NewFlagSet("send", flag.ExitOnError)
		openRequested := sendFlags.Bool("open", false, "Ask the receiving device to open the file on arrival")
		sendAll := sendFlags.Bool("all", false, "Send to every online device (v3, one upload)")
		sendOffline := sendFlags.Bool("offline", false, "Queue for a device that may be offline (v3)")
		sendTo := sendFlags.String("to", "", "Comma-separated device IDs (v3)")
		if err := sendFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		rest := sendFlags.Args()
		switch {
		case *sendAll:
			if len(rest) < 1 {
				log.Fatalf("Usage: gaur-share send --all <file|dir>...")
			}
			if err := runSendV3(cfg, nil, rest, false, *openRequested); err != nil {
				log.Fatalf("[FATAL] %v", err)
			}
		case *sendTo != "":
			if len(rest) < 1 {
				log.Fatalf("Usage: gaur-share send --to <id,id> <file|dir>...")
			}
			if err := runSendV3(cfg, splitTargets(*sendTo), rest, false, *openRequested); err != nil {
				log.Fatalf("[FATAL] %v", err)
			}
		case *sendOffline:
			if len(rest) < 2 {
				log.Fatalf("Usage: gaur-share send --offline <device-id> <file|dir>...")
			}
			if err := runSendV3(cfg, []string{rest[0]}, rest[1:], true, *openRequested); err != nil {
				log.Fatalf("[FATAL] %v", err)
			}
		default:
			if len(rest) < 2 {
				log.Fatalf("Usage: gaur-share send [-open] <target-device> <file-path> [file-path...]")
			}
			runSend(cfg, rest[0], rest[1:], *openRequested)
		}
	case "send-text":
		if len(args) < 3 {
			log.Fatalf("Usage: gaur-share send-text <target-device> <text|->")
		}
		runSendText(cfg, args[1], strings.Join(args[2:], " "))
	case "pair":
		uri := ""
		if len(args) >= 2 {
			uri = args[1]
		} else {
			data, err := io.ReadAll(io.LimitReader(os.Stdin, 8*1024))
			if err != nil {
				log.Fatalf("[FATAL] Could not read pairing URI: %v", err)
			}
			uri = string(data)
		}
		if err := runPair(cfg, uri); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
	case "ui":
		uiFlags := flag.NewFlagSet("ui", flag.ExitOnError)
		uiAddr := uiFlags.String("addr", "127.0.0.1:0", "Local address for the UI")
		if err := uiFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		runUI(cfg, *uiAddr)
	case "notify":
		notifyFlags := flag.NewFlagSet("notify", flag.ExitOnError)
		notifyAll := notifyFlags.Bool("all", false, "Send to every online device")
		notifyIcon := notifyFlags.String("icon", "", "Icon file (PNG, max 64 KiB)")
		if err := notifyFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		rest := notifyFlags.Args()
		if *notifyAll {
			if len(rest) < 1 {
				log.Fatalf("Usage: gaur-share notify --all <title> [body...]")
			}
			runNotifyAll(cfg, rest[0], strings.Join(rest[1:], " "), *notifyIcon)
		} else {
			if len(rest) < 2 {
				log.Fatalf("Usage: gaur-share notify <device> <title> [body...]")
			}
			runNotify(cfg, rest[0], rest[1], strings.Join(rest[2:], " "), *notifyIcon)
		}
	case "trust":
		if len(args) < 2 {
			log.Fatalf("Usage: gaur-share trust <device-id-or-name>")
		}
		id, name := resolveTrustTarget(args[1])
		if err := trustDevice(id, name); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		fmt.Printf("Trusted %s (%s)\n", name, id)
	case "untrust":
		if len(args) < 2 {
			log.Fatalf("Usage: gaur-share untrust <device-id-or-name>")
		}
		id, name := resolveTrustTarget(args[1])
		removed, err := untrustDevice(id)
		if err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		if removed {
			fmt.Printf("Revoked %s (%s)\n", name, id)
		} else {
			fmt.Printf("%s was not trusted\n", name)
		}
	case "trusted":
		devices, err := loadTrustedDevices()
		if err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		mode := "off"
		if onlyTrustedEnabled(cfg) {
			mode = "on"
		}
		fmt.Printf("Only trusted devices: %s\n", mode)
		if len(devices) == 0 {
			fmt.Println("No trusted devices.")
			return
		}
		for _, d := range devices {
			fmt.Printf("%-24s %s\n", d.ID, d.Name)
		}
	case "trust-mode":
		if len(args) < 2 || (args[1] != "on" && args[1] != "off") {
			log.Fatalf("Usage: gaur-share trust-mode on|off")
		}
		if err := setOnlyTrusted(args[1] == "on"); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		fmt.Printf("Only trusted devices: %s\n", args[1])
	default:
		log.Fatalf("Unknown command: %s. Valid commands: daemon, list, send, send-text, pair, ui, notify, trust, untrust, trusted, trust-mode", args[0])
	}
}
