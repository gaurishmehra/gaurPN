# Desktop integration

Optional helpers for running `gaur-share` as a background receiver and sending
files from a file manager. None of these are required by the Android app or the
relay.

## Linux

### File manager actions

1. Install the picker script somewhere in `PATH`:

   ```bash
   install -m 0755 contrib/linux/gaur-share-send.sh ~/.local/bin/gaur-share-send.sh
   ```

2. **Nautilus**: link it into the scripts directory.

   ```bash
   mkdir -p ~/.local/share/nautilus/scripts
   ln -sf ~/.local/bin/gaur-share-send.sh \
     ~/.local/share/nautilus/scripts/"Send with gaurPN"
   ```

3. **Dolphin**: install the service menu.

   ```bash
   mkdir -p ~/.local/share/kio/servicemenus
   install -m 0644 contrib/linux/gaur-share-send.desktop \
     ~/.local/share/kio/servicemenus/gaur-share-send.desktop
   ```

The picker needs `zenity` or `kdialog` and `python3`. It reads `gaur-share list
-json`, so the VPN must be connected and the cluster token configured.

### Autostart (systemd user unit)

```bash
mkdir -p ~/.config/systemd/user
cp contrib/linux/gaur-share-daemon.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now gaur-share-daemon.service
```

The unit uses `-clipboard-source=socket` because Quickshell owns the clipboard
event source. On a machine without Quickshell, change it to `poll` or `off`.

## macOS

```bash
mkdir -p ~/Library/LaunchAgents
cp contrib/macos/com.gaurish.gaur-share.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.gaurish.gaur-share.plist
```

The plist assumes `/usr/local/bin/gaur-share` and polling clipboard mode.
`pngpaste` is required for clipboard images.

## Windows

Create a Startup shortcut with PowerShell:

```powershell
$startup = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup"
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut("$startup\gaur-share.lnk")
$link.TargetPath = "$env:LOCALAPPDATA\gaur-share\gaur-share.exe"
$link.Arguments = "daemon"
$link.Save()
```

For a "Send with gaurPN" context menu, add a registry entry under
`HKCU\Software\Classes\*\shell\gaurPN\command` pointing at
`gaur-share.exe send <device> "%1"`. The target device must be online; the CLI
does not prompt.
