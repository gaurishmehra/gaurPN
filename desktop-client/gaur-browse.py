#!/usr/bin/env python3
"""gaur-browse — native GTK/libadwaita pull-transfer picker for gaurPN.

Opens a system-themed window (automatic dark/light + accent, like any other
libadwaita app) that browses a cluster device's files and fetches whatever
the user picks. All protocol work is delegated to the gaur-share CLI
(`browse --json`, `fetch`, `list --json`) — this file contains no crypto
and no relay code.

Usage:
    gaur-browse [target]     # target = peer name or device id (optional)

Exit codes: 0 normally; 1 if no peers/target could be resolved.
"""

import json
import subprocess
import sys
import threading
from datetime import datetime

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

GAUR_SHARE = "gaur-share"
BROWSE_TIMEOUT = 30
FETCH_TIMEOUT = 30 * 60

FILE_ICONS = {
    "png jpg jpeg gif webp bmp heic avif": "image-x-generic",
    "mp4 mkv webm mov avi": "video-x-generic",
    "mp3 flac wav ogg m4a opus": "audio-x-generic",
    "pdf": "application-pdf",
    "zip tar gz xz 7z rar bz2": "application-x-archive",
    "txt md csv json log conf": "text-x-generic",
    "apk": "android-package-archive",
    "doc docx odt rtf": "x-office-document",
    "xls xlsx ods": "x-office-spreadsheet",
    "ppt pptx odp": "x-office-presentation",
}
ICON_MAP = {ext: icon for exts, icon in FILE_ICONS.items() for ext in exts.split()}

ERROR_TEXT = {
    "denied": "Device refused the request",
    "not_found": "Path not found",
    "rate_limited": "Slow down — too many requests",
    "unsupported": "Not supported on that device",
    "busy": "Device is busy — try again shortly",
    "expired": "Request expired",
    "too_large": "Too much data — fetch less at once",
    "not_allowed": "This device is not trusted for browsing",
    "declined": "Request declined",
    "picker_unavailable": "Picker unavailable",
}


def run_cli(args, timeout):
    """Runs gaur-share and returns (stdout, exit_code). Never raises."""
    try:
        proc = subprocess.run(
            [GAUR_SHARE, *args], capture_output=True, text=True, timeout=timeout
        )
        return proc.stdout, proc.returncode
    except Exception as exc:  # timeout, binary missing, ...
        return f"error: {exc}", -1


def human_size(n):
    if n < 1024:
        return f"{n} B"
    value = float(n)
    for unit in ("KiB", "MiB", "GiB", "TiB"):
        value /= 1024.0
        if value < 1024:
            return f"{value:.1f} {unit}"
    return f"{value:.1f} PiB"


def icon_for(name, is_dir):
    if is_dir:
        return "folder"
    return ICON_MAP.get(name.rsplit(".", 1)[-1].lower() if "." in name else "", "text-x-generic")


class Entry:
    __slots__ = ("name", "size", "modified", "is_dir")

    def __init__(self, name, size, modified, is_dir):
        self.name = name
        self.size = size
        self.modified = modified
        self.is_dir = is_dir

    @property
    def subtitle(self):
        if self.is_dir:
            return "Folder"
        date = ""
        if self.modified:
            try:
                date = datetime.fromtimestamp(self.modified / 1000).strftime("  ·  %b %d, %Y")
            except (ValueError, OSError, OverflowError):
                date = ""
        return f"{human_size(self.size)}{date}"


class BrowseWindow(Adw.ApplicationWindow):
    def __init__(self, app, target, peer_label):
        super().__init__(application=app)
        self.target = target
        self.peer_label = peer_label
        self.path = ""                      # current path
        self.stack = []                     # navigation stack of paths
        self.selected = {}                  # full path -> Entry (global selection)
        self.loading = False

        self.set_default_size(760, 560)
        self.set_title("gaurPN")

        # ── Header ──
        self.back_button = Gtk.Button(icon_name="go-previous-symbolic")
        self.back_button.set_tooltip_text("Up")
        self.back_button.connect("clicked", lambda *_: self.go_up())

        self.title_label = Gtk.Label()
        self.title_label.add_css_class("heading")
        self.title_label.set_ellipsize(3)  # PANGO_ELLIPSIZE_MIDDLE

        self.spinner = Gtk.Spinner()
        self.spinner.set_valign(Gtk.Align.CENTER)

        header = Adw.HeaderBar()
        header.pack_start(self.back_button)
        header.pack_start(self.spinner)
        header.set_title_widget(self.title_label)

        # ── Content ──
        self.list_box = Gtk.ListBox()
        self.list_box.set_valign(Gtk.Align.START)
        self.list_box.add_css_class("boxed-list")
        self.list_box.set_margin_top(12)
        self.list_box.set_margin_bottom(12)
        self.list_box.set_margin_start(12)
        self.list_box.set_margin_end(12)

        scrolled = Gtk.ScrolledWindow()
        scrolled.set_child(self.list_box)
        scrolled.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scrolled.set_vexpand(True)

        self.placeholder = Adw.StatusPage()
        self.placeholder.set_icon_name("folder-open-symbolic")

        self.content_stack = Gtk.Stack()
        self.content_stack.add_named(scrolled, "list")
        self.content_stack.add_named(self.placeholder, "empty")
        self.content_stack.set_visible_child_name("list")

        # ── Fetch bar ──
        self.fetch_label = Gtk.Label()
        self.fetch_label.set_halign(Gtk.Align.START)
        self.fetch_label.set_hexpand(True)
        self.fetch_button = Gtk.Button()
        self.fetch_button.add_css_class("suggested-action")
        self.fetch_button.connect("clicked", lambda *_: self.start_fetch())

        bar_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        bar_box.append(self.fetch_label)
        bar_box.append(self.fetch_button)
        bar_box.set_margin_start(12)
        bar_box.set_margin_end(12)
        bar_box.set_margin_bottom(12)

        self.fetch_revealer = Gtk.Revealer(child=bar_box, transition_type=Gtk.RevealerTransitionType.SLIDE_UP)
        self.fetch_revealer.set_reveal_child(False)

        self.toast_overlay = Adw.ToastOverlay(child=self.content_stack)

        toolbar = Adw.ToolbarView()
        toolbar.add_top_bar(header)
        toolbar.add_bottom_bar(self.fetch_revealer)
        toolbar.set_content(self.toast_overlay)
        self.set_content(toolbar)

        self.browse("")

    # ── CLI glue (threaded) ──

    def browse(self, path):
        if self.loading:
            return
        self.loading = True
        self.spinner.start()
        self.title_label.set_text(self.path_title(path))
        args = ["browse", self.target, "--json"]
        if path:
            args += ["--", path]

        def work():
            out, code = run_cli(args, BROWSE_TIMEOUT)
            GLib.idle_add(self.on_browsed, path, out, code)

        threading.Thread(target=work, daemon=True).start()

    def on_browsed(self, path, out, code):
        self.loading = False
        self.spinner.stop()
        if code != 0:
            self.toast(ERROR_TEXT.get(out.strip().rsplit("error:", 1)[-1].strip()
                                      if "error:" in out else out.strip(), "Device didn't respond"))
            return
        try:
            data = json.loads(out)
        except json.JSONDecodeError:
            self.toast("Device didn't respond")
            return
        if data.get("error"):
            self.toast(ERROR_TEXT.get(data["error"], "Device didn't respond"))
            return
        entries = [Entry(e["name"], e.get("size", 0), e.get("modified", 0), e.get("dir", False))
                   for e in data.get("entries", [])]
        self.path = data.get("path", path)
        self.stack.append(self.path)
        self.populate(entries)

    # ── UI ──

    def path_title(self, path):
        if not path:
            return self.peer_label
        return f"{self.peer_label}  ·  {path.replace('/', ' / ')}"

    def full_path(self, entry):
        return f"{self.path}/{entry.name}" if self.path else entry.name

    def populate(self, entries):
        self.list_box.remove_all()
        self.back_button.set_visible(len(self.stack) > 1)

        if not entries:
            self.placeholder.set_title("Nothing here")
            self.placeholder.set_description("This folder is empty.")
            self.content_stack.set_visible_child_name("empty")
        else:
            self.content_stack.set_visible_child_name("list")

        for entry in entries:
            row = Adw.ActionRow(title=entry.name, subtitle=entry.subtitle)
            row.add_prefix(Gtk.Image(icon_name=icon_for(entry.name, entry.is_dir)))

            if entry.is_dir:
                row.set_activatable(True)
                row.connect("activated", lambda *_r, e=entry: self.browse(self.full_path(e)))
                chevron = Gtk.Image(icon_name="go-next-symbolic")
                chevron.set_valign(Gtk.Align.CENTER)
                row.add_suffix(chevron)
            else:
                check = Gtk.CheckButton(valign=Gtk.Align.CENTER)
                check.set_active(self.full_path(entry) in self.selected)
                check.connect(
                    "toggled",
                    lambda _b, e=entry: self.toggle(e),
                )
                row.add_suffix(check)
            self.list_box.append(row)

        self.title_label.set_text(self.path_title(self.path))
        self.update_fetch_bar()

    def toggle(self, entry):
        key = self.full_path(entry)
        if key in self.selected:
            self.selected.pop(key, None)
        else:
            self.selected[key] = entry
        self.update_fetch_bar()

    def update_fetch_bar(self):
        count = len(self.selected)
        if count:
            total = sum(e.size for e in self.selected.values())
            self.fetch_label.set_text(f"{count} file{'s' if count > 1 else ''} selected  ·  {human_size(total)}")
            self.fetch_button.set_label("Fetch")
            self.fetch_revealer.set_reveal_child(True)
        else:
            self.fetch_revealer.set_reveal_child(False)

    def go_up(self):
        if len(self.stack) > 1:
            self.stack.pop()
            target = self.stack[-1]
            self.stack.pop()  # browse() re-appends
            self.browse(target)

    # ── Fetch ──

    def start_fetch(self):
        if not self.selected:
            return
        paths = list(self.selected.keys())
        count = len(paths)
        self.fetch_button.set_sensitive(False)
        self.spinner.start()
        self.toast(f"Fetching {count} file{'s' if count > 1 else ''}…")

        def work():
            out, code = run_cli(["fetch", self.target, "--", *paths], FETCH_TIMEOUT)
            GLib.idle_add(self.on_fetched, out, code, count)

        threading.Thread(target=work, daemon=True).start()

    def on_fetched(self, out, code, count):
        self.spinner.stop()
        self.fetch_button.set_sensitive(True)
        ok = code == 0
        received = out.count("Received ")
        if ok:
            self.selected.clear()
            self.update_fetch_bar()
            toast = Adw.Toast.new(f"Saved {received or count} file{'s' if (received or count) > 1 else ''} to gaur-inbox")
            toast.set_button_label("Open")
            toast.connect(
                "button-clicked",
                lambda *_: subprocess.Popen(["xdg-open", f"{__import__('os').path.expanduser('~')}/Downloads/gaur-inbox"]),
            )
            self.toast_overlay.add_toast(toast)
        else:
            first = next((l for l in out.splitlines() if l.strip()), "Fetch failed")
            self.toast(ERROR_TEXT.get(first.strip(), first.strip()[:120]))

    def toast(self, text):
        self.toast_overlay.add_toast(Adw.Toast.new(text))


class PickerApp(Adw.Application):
    def __init__(self, target):
        super().__init__(application_id="dev.gaurish.gaurpn.browse",
                         flags=Gio.ApplicationFlags.NON_UNIQUE)
        self.target = target

    def do_activate(self):
        if self.target:
            self.open_window(self.target, self.target)
            return
        # No target given: resolve peers first.
        out, code = run_cli(["list", "--json"], 10)
        peers = []
        if code == 0:
            try:
                peers = [p for p in json.loads(out) if not p.get("is_self")]
            except json.JSONDecodeError:
                peers = []
        if not peers:
            dialog = Adw.AlertDialog(heading="No devices online",
                                     body="Connect a cluster device and try again.")
            dialog.choose(self.props.active_window, None, lambda *_: self.quit())
            return
        if len(peers) == 1:
            self.open_window(peers[0]["id"], peers[0].get("name", peers[0]["id"]))
            return
        self.show_peer_picker(peers)

    def show_peer_picker(self, peers):
        win = Adw.ApplicationWindow(application=self)
        win.set_default_size(420, 300)
        win.set_title("gaurPN — pick a device")

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        header = Adw.HeaderBar()
        toolbar = Adw.ToolbarView()
        toolbar.add_top_bar(header)

        lb = Gtk.ListBox()
        lb.add_css_class("boxed-list")
        lb.set_margin_top(12)
        lb.set_margin_bottom(12)
        lb.set_margin_start(12)
        lb.set_margin_end(12)
        for p in peers:
            row = Adw.ActionRow(title=p.get("name", p["id"]), subtitle=p["id"])
            row.add_prefix(Gtk.Image(icon_name="phone-symbolic" if "android" in p["id"] else "computer-symbolic"))
            row.set_activatable(True)
            row.connect("activated", lambda *_r, peer=p: self.open_window(peer["id"], peer.get("name", peer["id"])))
            lb.append(row)
        scrolled = Gtk.ScrolledWindow(child=lb)
        toolbar.set_content(scrolled)
        box.append(toolbar)
        win.set_content(box)
        win.present()

    def open_window(self, target, label):
        BrowseWindow(self, target, label).present()


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else None
    app = PickerApp(target)
    app.run(None)


if __name__ == "__main__":
    main()
