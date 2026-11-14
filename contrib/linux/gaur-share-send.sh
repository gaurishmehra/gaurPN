#!/usr/bin/env bash
# Send selected files through the gaurPN tunnel from a file manager.
#
# Nautilus passes paths in NAUTILUS_SCRIPT_SELECTED_FILE_PATHS; Dolphin passes
# them as arguments. Install this script in PATH, then link it into
# ~/.local/share/nautilus/scripts/ or use the Dolphin .desktop file.
set -euo pipefail

if [ "$#" -eq 0 ] && [ -n "${NAUTILUS_SCRIPT_SELECTED_FILE_PATHS:-}" ]; then
  mapfile -t files <<< "${NAUTILUS_SCRIPT_SELECTED_FILE_PATHS}"
  set -- "${files[@]}"
fi

if [ "$#" -eq 0 ]; then
  exit 0
fi

if ! command -v gaur-share >/dev/null 2>&1; then
  command -v notify-send >/dev/null 2>&1 && \
    notify-send "gaurPN" "gaur-share is not installed or not in PATH"
  exit 1
fi

json="$(gaur-share list -json 2>/dev/null || echo '[]')"
mapfile -t peers < <(
  printf '%s' "$json" | python3 -c '
import json, sys
try:
    peers = json.load(sys.stdin)
except Exception:
    peers = []
for p in peers:
    if not p.get("IsSelf"):
        print(p.get("Name", ""))
' | sed '/^$/d'
)

if [ "${#peers[@]}" -eq 0 ]; then
  command -v notify-send >/dev/null 2>&1 && \
    notify-send "gaurPN" "No other cluster devices are online"
  exit 1
fi

if command -v zenity >/dev/null 2>&1; then
  target="$(printf '%s\n' "${peers[@]}" | zenity --list \
    --title="Send with gaurPN" --column="Device" --height=320 --width=360)"
elif command -v kdialog >/dev/null 2>&1; then
  target="$(printf '%s\n' "${peers[@]}" | kdialog \
    --title "Send with gaurPN" --combobox "Device:" --default "${peers[0]}")"
else
  command -v notify-send >/dev/null 2>&1 && \
    notify-send "gaurPN" "Install zenity or kdialog to pick a device"
  exit 1
fi

[ -n "${target:-}" ] || exit 0
exec gaur-share send "$target" "$@"
