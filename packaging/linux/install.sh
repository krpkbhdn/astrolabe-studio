#!/bin/sh
# Installs the unpacked tar.gz as a native app: menu entry, icon and launcher link. Run with sudo or --system for all users.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
app_src="$here/Astrolabe Studio"
icon_src="$here/icon.png"

system=0
case "${1:-}" in
    "") ;;
    --system) system=1 ;;
    *) echo "usage: $0 [--system]" >&2; exit 2 ;;
esac
[ "$(id -u)" = 0 ] && system=1

if [ "$system" = 1 ]; then
    opt=/opt; bin=/usr/local/bin; share=/usr/share
else
    opt=$HOME/.local/opt; bin=$HOME/.local/bin; share=$HOME/.local/share
fi
dest=$opt/astrolabe-studio
icon_dir=$share/icons/hicolor/512x512/apps
apps_dir=$share/applications

[ -x "$app_src/bin/Astrolabe Studio" ] || { echo "Run this script from the unpacked archive: '$app_src/bin/Astrolabe Studio' not found." >&2; exit 1; }
[ -f "$icon_src" ] || { echo "icon.png not found next to the script." >&2; exit 1; }

mkdir -p "$opt" "$bin" "$icon_dir" "$apps_dir"
case "$dest" in
    */astrolabe-studio) ;;
    *) echo "refusing to replace '$dest'" >&2; exit 1 ;;
esac
# Copy first and swap afterwards, so a failed copy never leaves the user without an app.
rm -rf "$dest.new" "$dest.old"
cp -a "$app_src" "$dest.new" || { rm -rf "$dest.new"; echo "Copying the app failed; the installed version is unchanged." >&2; exit 1; }
if [ -e "$dest" ] || [ -L "$dest" ]; then
    mv "$dest" "$dest.old" || { rm -rf "$dest.new"; echo "Could not move the installed version aside." >&2; exit 1; }
fi
if ! mv "$dest.new" "$dest"; then
    rm -rf "$dest.new" "$dest"
    if [ -e "$dest.old" ] || [ -L "$dest.old" ]; then mv "$dest.old" "$dest" || true; fi
    echo "Installing failed; restored the previous version." >&2
    exit 1
fi
rm -rf "$dest.old"
ln -sf "$dest/bin/Astrolabe Studio" "$bin/astrolabe-studio"
cp "$icon_src" "$icon_dir/astrolabe-studio.png"

# Desktop Entry spec: inside a quoted Exec argument \ " ` $ need a backslash, the string layer doubles every backslash, and % is %%.
exec_path=$(printf '%s' "$dest/bin/Astrolabe Studio" | sed -e 's/[\\"`$]/\\&/g' -e 's/\\/\\\\/g' -e 's/%/%%/g')

cat > "$apps_dir/astrolabe-studio.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Astrolabe Studio
Comment=Run and manage local Claude Code sessions
Exec="$exec_path"
Icon=astrolabe-studio
Terminal=false
StartupWMClass=astrolabe-studio
Categories=Development;
DESKTOP

if command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database "$apps_dir" >/dev/null 2>&1 || true; fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then gtk-update-icon-cache -q "$share/icons/hicolor" >/dev/null 2>&1 || true; fi

echo "Installed to $dest"
case ":$PATH:" in
    *":$bin:"*) ;;
    *) echo "Warning: $bin is not in PATH, so the 'astrolabe-studio' command will not be found." >&2 ;;
esac
