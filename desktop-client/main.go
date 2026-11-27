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

// splitDoubleDash separates the arguments before a "--" sentinel from the
// positionals after it. Go's flag package stops parsing at the first positional
// argument, so a path that begins with "-" needs an explicit sentinel; this
// helper keeps the sentinel out of the FlagSet and appends the tail after
// parsing. Only the first sentinel is special.
func splitDoubleDash(args []string) (head, tail []string) {
	for i, arg := range args {
		if arg == "--" {
			return args[:i], args[i+1:]
		}
	}
	return args, nil
}

func main() {
	var cfg Config
	var jsonOutput bool
	defaultName, defaultRelay := loadSettingsFromConfig()
	flag.StringVar(&cfg.RelayAddr, "relay", defaultRelay, "In-tunnel relay address (host:port)")
	flag.StringVar(&cfg.TokenFile, "key-file", defaultTokenFile(), "Path to cluster secret key file")
	flag.StringVar(&cfg.DeviceID, "device-id", generateDeviceID(), "Unique device identifier")
	flag.StringVar(&cfg.Name, "name", defaultName, "Human readable device name")
	flag.StringVar(&cfg.InboxDir, "inbox", defaultInboxDir(), "Directory where incoming files are saved")
	flag.StringVar(&cfg.BrowseRoot, "browse-root", defaultBrowseRoot(), "Root directory exposed to browse and fetch requests")
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
		fmt.Println("  gaur-share browse <device> [path] [--json]  List files a device shares")
		fmt.Println("  gaur-share fetch <device> <path> [path...]  Fetch files from a device")
		fmt.Println("  gaur-share screenshot <device>              Ask a device for a screenshot")
		fmt.Println("  gaur-share send-text <device> <text|->       Send a text or link (use - for stdin)")
		fmt.Println("  gaur-share pair <gaur://pair?...>           Pair using the QR URI from gaur-relay -init")
		fmt.Println("  gaur-share ui [--open] [-addr 127.0.0.1:9877] Local browser UI for peers, browsing, and files")
		fmt.Println("  gaur-share notify <device> <title> [body]   Send a notification to a device")
		fmt.Println("  gaur-share notify --all <title> [body]      Send a notification to every online device")
		fmt.Println("  gaur-share ask <device> <reason...>         Ask a device to approve an action")
		fmt.Println("  gaur-share cam [--loopback /dev/videoN] [--player <cmd>] <device>  Stream a device's camera")
		fmt.Println("  gaur-share open <device> <url|file|pkg>     Open a URL, file, or app on a device")
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
		code := 130
		if args[0] == "daemon" {
			writePeersFile(cfg, nil)
			stopDaemonStatus()
			// A send subprocess for a fetch outlives the handler that
			// started it; os.Exit skips its context cancel, so kill it here.
			killFetchSubprocesses()
			// Unlink the socket the daemon is listening on so Quickshell's
			// next dial does not hit a stale file. Only the socket source
			// ever creates it.
			if cfg.Clipboard && cfg.ClipboardSource == "socket" {
				removeClipboardSocket(cfg)
			}
			code = 0
		}
		os.Exit(code)
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
		sendRequestMeta := sendFlags.String("request-meta", "", "Internal: <request_id>:<grant>:<expires_ts> for a pull request")
		if err := sendFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		rest := sendFlags.Args()
		if *sendRequestMeta != "" {
			if *sendTo == "" {
				log.Fatalf("--request-meta requires --to")
			}
			meta, err := parseRequestMeta(*sendRequestMeta)
			if err != nil {
				log.Fatalf("[FATAL] %v", err)
			}
			cfg.RequestMeta = meta
		}
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
	case "request":
		// The picker-based pull request is gone: browsing replaced it. Exit 2
		// (usage error) so a launcher can tell it apart from a failed transfer.
		fmt.Fprintln(os.Stderr, `request is now browse/fetch — run "gaur-share ui" for the picker UI`)
		os.Exit(2)
	case "browse":
		browseFlags := flag.NewFlagSet("browse", flag.ExitOnError)
		browseJSON := browseFlags.Bool("json", false, "Output the listing as JSON")
		head, tail := splitDoubleDash(args[1:])
		if err := browseFlags.Parse(browseArgsFromFlags(head)); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		browseRest := append(append([]string(nil), browseFlags.Args()...), tail...)
		if len(browseRest) < 1 || len(browseRest) > 2 {
			log.Fatalf("Usage: gaur-share browse <device> [path] [--json]")
		}
		browsePath := ""
		if len(browseRest) == 2 {
			browsePath = browseRest[1]
		}
		// Honor the global -json as well as the subcommand flag.
		browseJSONOut := *browseJSON || jsonOutput
		if err := runBrowse(cfg, browseRest[0], browsePath, browseJSONOut); err != nil {
			// In JSON mode runBrowse already printed the error object; the
			// human mode gets the pinned one-line "error: <reason>" form.
			if !browseJSONOut {
				fmt.Fprintln(os.Stderr, "error: "+singleLineDisplayName(err.Error()))
			}
			os.Exit(1)
		}
	case "fetch":
		fetchFlags := flag.NewFlagSet("fetch", flag.ExitOnError)
		head, tail := splitDoubleDash(args[1:])
		if err := fetchFlags.Parse(head); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		fetchRest := append(append([]string(nil), fetchFlags.Args()...), tail...)
		if len(fetchRest) < 2 {
			log.Fatalf("Usage: gaur-share fetch <device> <path> [path...]")
		}
		if err := runFetch(cfg, fetchRest[0], fetchRest[1:]); err != nil {
			fmt.Fprintln(os.Stderr, "error: "+singleLineDisplayName(err.Error()))
			os.Exit(1)
		}
	case "screenshot":
		shotFlags := flag.NewFlagSet("screenshot", flag.ExitOnError)
		head, tail := splitDoubleDash(args[1:])
		if err := shotFlags.Parse(head); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		shotRest := append(append([]string(nil), shotFlags.Args()...), tail...)
		if len(shotRest) != 1 {
			log.Fatalf("Usage: gaur-share screenshot <device>")
		}
		if err := runScreenshot(cfg, shotRest[0]); err != nil {
			fmt.Fprintln(os.Stderr, "error: "+singleLineDisplayName(err.Error()))
			os.Exit(1)
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
		uiAddr := uiFlags.String("addr", defaultUIAddr, "Local address for the UI")
		uiOpen := uiFlags.Bool("open", false, "Open the UI in the default browser")
		if err := uiFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		if err := runUI(cfg, *uiAddr, *uiOpen); err != nil {
			fmt.Fprintln(os.Stderr, err.Error())
			os.Exit(1)
		}
	case "notify":
		notifyFlags := flag.NewFlagSet("notify", flag.ExitOnError)
		notifyAll := notifyFlags.Bool("all", false, "Send to every online device")
		notifyIcon := notifyFlags.String("icon", "", "Icon file (PNG, max 64 KiB)")
		notifyApp := notifyFlags.String("app", "", "Source app display name")
		notifyPkg := notifyFlags.String("package", "", "Source Android package for duplicate suppression")
		if err := notifyFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		rest := notifyFlags.Args()
		if *notifyAll {
			if len(rest) < 1 {
				log.Fatalf("Usage: gaur-share notify --all [-app name] [-package pkg] <title> [body...]")
			}
			runNotifyAll(
				cfg,
				rest[0],
				strings.Join(rest[1:], " "),
				*notifyIcon,
				*notifyApp,
				*notifyPkg,
			)
		} else {
			if len(rest) < 2 {
				log.Fatalf("Usage: gaur-share notify [-app name] [-package pkg] <device> <title> [body...]")
			}
			runNotify(
				cfg,
				rest[0],
				rest[1],
				strings.Join(rest[2:], " "),
				*notifyIcon,
				*notifyApp,
				*notifyPkg,
			)
		}
	case "ask":
		askFlags := flag.NewFlagSet("ask", flag.ExitOnError)
		askJSON := askFlags.Bool("json", false, "Output the result as JSON")
		askTimeoutFlag := askFlags.Duration("timeout", 0, "Approval wait, default 2m, range 5s-5m")
		if err := askFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		rest := askFlags.Args()
		if len(rest) < 2 {
			fmt.Fprintln(os.Stderr, "Usage: gaur-share ask [--timeout 2m] <device> <reason...>")
			os.Exit(2)
		}
		if *askTimeoutFlag > 0 {
			askTimeout = *askTimeoutFlag
		}
		jsonOut := *askJSON || jsonOutput
		if err := runAsk(cfg, rest[0], strings.Join(rest[1:], " "), jsonOut); err != nil {
			printControlError(err, jsonOut)
		}
	case "cam":
		camFlags := flag.NewFlagSet("cam", flag.ExitOnError)
		camLoopback := camFlags.String("loopback", "", "Write the stream to a v4l2 loopback device (e.g. /dev/video10) instead of a player window")
		camPlayerFlag := camFlags.String("player", "", "Custom player command; receives the HEVC stream on stdin")
		if err := camFlags.Parse(args[1:]); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		rest := camFlags.Args()
		if len(rest) != 1 {
			fmt.Fprintln(os.Stderr, "Usage: gaur-share cam [--loopback /dev/videoN] [--player <cmd>] <device>")
			os.Exit(2)
		}
		if err := runCam(cfg, rest[0], *camLoopback, *camPlayerFlag); err != nil {
			camExit(err)
		}
	case "open":
		openFlags := flag.NewFlagSet("open", flag.ExitOnError)
		openJSON := openFlags.Bool("json", false, "Output the result as JSON")
		openApp := openFlags.String("app", "", "Launch this Android package instead of autodetecting")
		head, tail := splitDoubleDash(args[1:])
		if err := openFlags.Parse(head); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		rest := append(append([]string(nil), openFlags.Args()...), tail...)
		jsonOut := *openJSON || jsonOutput
		if *openApp != "" {
			if len(rest) != 1 {
				fmt.Fprintln(os.Stderr, "Usage: gaur-share open --app <package> <device>")
				os.Exit(2)
			}
			if err := runOpen(cfg, rest[0], "", *openApp, jsonOut); err != nil {
				printControlError(err, jsonOut)
			}
		} else {
			if len(rest) != 2 {
				fmt.Fprintln(os.Stderr, "Usage: gaur-share open <device> <url|file|package>")
				os.Exit(2)
			}
			if err := runOpen(cfg, rest[0], rest[1], "", jsonOut); err != nil {
				printControlError(err, jsonOut)
			}
		}
	case "trust":
		if len(args) < 2 {
			log.Fatalf("Usage: gaur-share trust <device-id-or-name>")
		}
		id, name := resolveTrustTarget(args[1])
		if err := trustDevice(id, name); err != nil {
			log.Fatalf("[FATAL] %v", err)
		}
		fmt.Printf("Trusted %s (%s)\n", singleLineDisplayName(name), singleLineDisplayName(id))
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
			fmt.Printf("Revoked %s (%s)\n", singleLineDisplayName(name), singleLineDisplayName(id))
		} else {
			fmt.Printf("%s was not trusted\n", singleLineDisplayName(name))
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
		log.Fatalf("Unknown command: %s. Valid commands: daemon, list, send, browse, fetch, screenshot, send-text, pair, ui, notify, ask, cam, open, trust, untrust, trusted, trust-mode", args[0])
	}
}
