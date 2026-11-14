package main

import (
	"context"
	"fmt"
	"log"
	"os/exec"
	"runtime"
	"time"
)

func notifyUser(title, message string) {
	switch runtime.GOOS {
	case "linux":
		// "--" ends option parsing so a title or message beginning with "-"
		// is delivered as text, never read as a notify-send option.
		_ = exec.Command("notify-send", "-a", "gaurPN", "--", title, message).Run()
	case "darwin":
		script := fmt.Sprintf(`display notification "%s" with title "%s"`,
			escapeAppleScriptString(message), escapeAppleScriptString(title))
		_ = exec.Command("osascript", "-e", script).Run()
	}
}

// openCommand builds the platform-specific command that opens a file with its
// default application. The context bounds the launcher so a hung opener
// cannot linger; it does not affect the already-completed transfer.
func openCommand(ctx context.Context, path string) *exec.Cmd {
	switch runtime.GOOS {
	case "darwin":
		return exec.CommandContext(ctx, "open", path)
	case "windows":
		return exec.CommandContext(ctx, "cmd", "/c", "start", "", path)
	default:
		return exec.CommandContext(ctx, "xdg-open", path)
	}
}

// openFile launches the platform file opener without blocking the caller.
// Failures are logged and never affect the completed transfer.
func openFile(path string) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	cmd := openCommand(ctx, path)
	go func() {
		defer cancel()
		if err := cmd.Start(); err != nil {
			log.Printf("[RECV] Failed to open '%s': %v", path, err)
			return
		}
		// Reap the launcher so it does not linger as a zombie.
		_ = cmd.Wait()
	}()
}
