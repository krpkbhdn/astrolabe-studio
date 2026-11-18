#!/bin/sh
# Removes what install.sh put in place (user and system mode); ~/.astrolabe-studio (the data) is never touched.
set -eu

remove_dir() {
    [ -n "$1" ] || return 0
    case "$1" in
        */astrolabe-studio) [ -d "$1" ] && rm -rf "$1" ;;
    esac
    return 0
}

for root in "$HOME/.local" /usr; do
    if [ "$root" = /usr ]; then
        opt=/opt; bin=/usr/local/bin; share=/usr/share
    else
        opt=$root/opt; bin=$root/bin; share=$root/share
    fi
    remove_dir "$opt/astrolabe-studio"
    rm -f "$bin/astrolabe-studio" "$share/applications/astrolabe-studio.desktop" \
        "$share/icons/hicolor/512x512/apps/astrolabe-studio.png" 2>/dev/null || true
    if [ "$root" != /usr ]; then
        rmdir "$opt" "$bin" "$share/applications" "$share/icons/hicolor/512x512/apps" "$share/icons/hicolor/512x512" \
            "$share/icons/hicolor" "$share/icons" "$share" "$root" 2>/dev/null || true
    fi
done
echo "Astrolabe Studio removed (your data in ~/.astrolabe-studio is kept)."
