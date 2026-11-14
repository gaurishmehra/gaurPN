package main

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"
)

const maxClipboardImageBytes = 8 * 1024 * 1024

// ClipboardPayload is the decrypted clipboard item exchanged between devices.
type ClipboardPayload struct {
	Kind string // "text" or "image"
	Mime string
	Data []byte
	Hash string
}

// clipboardState is shared by the poller and the WebSocket receive loop so a
// remote update is not immediately echoed back as a local copy.
var (
	clipboardStateMu       sync.Mutex
	clipboardApplyMu       sync.Mutex
	clipboardInitialized   bool
	clipboardLastHash      string
	clipboardLastTs        int64
	clipboardSuppressUntil int64
	clipboardSuppressKind  string
)

func clipboardHash(kind, mime string, data []byte) string {
	h := make([]byte, 0, len(kind)+len(mime)+len(data)+2)
	h = append(h, kind...)
	h = append(h, 0)
	h = append(h, mime...)
	h = append(h, 0)
	h = append(h, data...)
	return Sha256Hex(h)
}

func readClipboard() (ClipboardPayload, error) {
	if data, mime, err := readClipboardImage(); err == nil && len(data) > 0 {
		if len(data) > maxClipboardImageBytes {
			return ClipboardPayload{}, fmt.Errorf("clipboard image is too large (%d bytes)", len(data))
		}
		return ClipboardPayload{
			Kind: "image",
			Mime: mime,
			Data: data,
			Hash: clipboardHash("image", mime, data),
		}, nil
	}

	text, err := readClipboardText()
	if err != nil {
		return ClipboardPayload{}, err
	}
	if text == "" {
		return ClipboardPayload{}, nil
	}
	data := []byte(text)
	if len(data) > 1024*1024 {
		return ClipboardPayload{}, fmt.Errorf("clipboard text is too large (%d bytes)", len(data))
	}
	return ClipboardPayload{
		Kind: "text",
		Mime: "text/plain",
		Data: data,
		Hash: clipboardHash("text", "text/plain", data),
	}, nil
}

func writeClipboard(p ClipboardPayload) error {
	switch p.Kind {
	case "text":
		return writeClipboardText(string(p.Data))
	case "image":
		return writeClipboardImage(p.Data, p.Mime)
	default:
		return fmt.Errorf("unknown clipboard kind %q", p.Kind)
	}
}

func runClipboardCommand(timeout time.Duration, stdin []byte, name string, args ...string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	if stdin != nil {
		cmd.Stdin = bytes.NewReader(stdin)
	}
	return cmd.Output()
}

// runClipboardWrite runs a clipboard writer without capturing stdout. wl-copy
// forks a child that keeps the inherited stdout pipe open, so cmd.Output()
// would block until the child exits (never). cmd.Run() waits only for the
// parent, which is what we want.
func runClipboardWrite(timeout time.Duration, stdin []byte, name string, args ...string) error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	if stdin != nil {
		cmd.Stdin = bytes.NewReader(stdin)
	}
	return cmd.Run()
}

func isWayland() bool {
	return os.Getenv("WAYLAND_DISPLAY") != ""
}

func readClipboardText() (string, error) {
	switch runtime.GOOS {
	case "linux":
		if isWayland() {
			if out, err := runClipboardCommand(2*time.Second, nil, "wl-paste", "--no-newline"); err == nil {
				return string(out), nil
			}
		}
		if out, err := runClipboardCommand(2*time.Second, nil, "xclip", "-selection", "clipboard", "-o"); err == nil {
			return string(out), nil
		}
		out, err := runClipboardCommand(2*time.Second, nil, "xsel", "--clipboard", "--output")
		return string(out), err
	case "darwin":
		out, err := runClipboardCommand(2*time.Second, nil, "pbpaste")
		return string(out), err
	case "windows":
		out, err := runClipboardCommand(3*time.Second, nil, "powershell", "-NoProfile", "-Command", "Get-Clipboard -Raw")
		return strings.TrimRight(string(out), "\r\n"), err
	default:
		return "", fmt.Errorf("unsupported OS %s", runtime.GOOS)
	}
}

func writeClipboardText(text string) error {
	data := []byte(text)
	switch runtime.GOOS {
	case "linux":
		if isWayland() {
			if err := runClipboardWrite(2*time.Second, data, "wl-copy"); err == nil {
				return nil
			}
		}
		if err := runClipboardWrite(2*time.Second, data, "xclip", "-selection", "clipboard", "-i"); err == nil {
			return nil
		}
		return runClipboardWrite(2*time.Second, data, "xsel", "--clipboard", "--input")
	case "darwin":
		return runClipboardWrite(2*time.Second, data, "pbcopy")
	case "windows":
		return runClipboardWrite(3*time.Second, data, "powershell", "-NoProfile", "-Command", "Set-Clipboard -Value ([Console]::In.ReadToEnd())")
	default:
		return fmt.Errorf("unsupported OS %s", runtime.GOOS)
	}
}

func readClipboardImage() ([]byte, string, error) {
	switch runtime.GOOS {
	case "linux":
		if isWayland() {
			if types, err := runClipboardCommand(2*time.Second, nil, "wl-paste", "--list-types"); err == nil {
				for _, mime := range []string{"image/png", "image/jpeg", "image/webp"} {
					if strings.Contains(string(types), mime) {
						if data, err := runClipboardCommand(3*time.Second, nil, "wl-paste", "--type", mime); err == nil && len(data) > 0 {
							return data, mime, nil
						}
					}
				}
			}
		}
		if types, err := runClipboardCommand(2*time.Second, nil, "xclip", "-selection", "clipboard", "-t", "TARGETS", "-o"); err == nil {
			for _, mime := range []string{"image/png", "image/jpeg", "image/webp"} {
				if strings.Contains(string(types), mime) {
					if data, err := runClipboardCommand(3*time.Second, nil, "xclip", "-selection", "clipboard", "-t", mime, "-o"); err == nil && len(data) > 0 {
						return data, mime, nil
					}
				}
			}
		}
		return nil, "", fmt.Errorf("no image in clipboard")
	case "darwin":
		out, err := runClipboardCommand(3*time.Second, nil, "pngpaste", "-")
		if err != nil || len(out) == 0 {
			return nil, "", fmt.Errorf("pngpaste is unavailable or the clipboard has no image")
		}
		return out, "image/png", nil
	case "windows":
		script := `Add-Type -AssemblyName System.Windows.Forms; $img=[Windows.Forms.Clipboard]::GetImage(); if($img -ne $null){$p=Join-Path $env:TEMP ("gaur-clip-"+[guid]::NewGuid().ToString()+".png"); $img.Save($p,[Drawing.Imaging.ImageFormat]::Png); Write-Output $p}`
		out, err := runClipboardCommand(5*time.Second, nil, "powershell", "-NoProfile", "-STA", "-Command", script)
		if err != nil {
			return nil, "", err
		}
		path := strings.TrimSpace(string(out))
		if path == "" {
			return nil, "", fmt.Errorf("no image in clipboard")
		}
		defer os.Remove(path)
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, "", err
		}
		return data, "image/png", nil
	default:
		return nil, "", fmt.Errorf("unsupported OS %s", runtime.GOOS)
	}
}

func writeClipboardImage(data []byte, mime string) error {
	if len(data) > maxClipboardImageBytes {
		return fmt.Errorf("clipboard image is too large (%d bytes)", len(data))
	}
	switch runtime.GOOS {
	case "linux":
		if isWayland() {
			if err := runClipboardWrite(3*time.Second, data, "wl-copy", "--type", mime); err == nil {
				return nil
			}
		}
		return runClipboardWrite(3*time.Second, data, "xclip", "-selection", "clipboard", "-t", mime, "-i")
	case "darwin":
		ext, class := ".png", "PNGf"
		if strings.Contains(mime, "jpeg") {
			ext, class = ".jpg", "JPEG"
		}
		f, err := os.CreateTemp("", "gaur-clip-*"+ext)
		if err != nil {
			return err
		}
		defer os.Remove(f.Name())
		if _, err := f.Write(data); err != nil {
			f.Close()
			return err
		}
		if err := f.Close(); err != nil {
			return err
		}
		script := fmt.Sprintf(`set the clipboard to (read (POSIX file %q) as «class %s»)`, f.Name(), class)
		return runClipboardWrite(3*time.Second, nil, "osascript", "-e", script)
	case "windows":
		f, err := os.CreateTemp("", "gaur-clip-*.png")
		if err != nil {
			return err
		}
		defer os.Remove(f.Name())
		if _, err := f.Write(data); err != nil {
			f.Close()
			return err
		}
		if err := f.Close(); err != nil {
			return err
		}
		script := fmt.Sprintf(`Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; $img=[Drawing.Image]::FromFile('%s'); [Windows.Forms.Clipboard]::SetImage($img); $img.Dispose()`, strings.ReplaceAll(f.Name(), "'", "''"))
		return runClipboardWrite(5*time.Second, nil, "powershell", "-NoProfile", "-STA", "-Command", script)
	default:
		return fmt.Errorf("unsupported OS %s", runtime.GOOS)
	}
}
