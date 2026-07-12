#!/bin/sh
# penlet — baslatici / launcher
#   1. loads uinput module
#   2. sets up adb reverse tunnel (resets on every USB replug)
#   3. compiles daemon (if needed) and runs it as root
#
# Distro-agnostic: only POSIX sh + adb + a C compiler are required.

set -e
PORT=40118
DIR="$(cd "$(dirname "$0")/.." && pwd)"

# --- dependency check (distro-agnostic) ---
missing=""
command -v adb >/dev/null 2>&1 || missing="$missing adb"
if ! command -v cc >/dev/null 2>&1 && ! command -v gcc >/dev/null 2>&1; then
    missing="$missing cc/gcc"
fi

if [ -n "$missing" ]; then
    echo "ERROR: missing tool(s):$missing"
    echo
    echo "Install (pick your distro):"
    echo "  Arch/Artix/Manjaro : sudo pacman -S android-tools gcc"
    echo "  Debian/Ubuntu/Mint : sudo apt install adb build-essential"
    echo "  Fedora             : sudo dnf install android-tools gcc"
    echo "  openSUSE           : sudo zypper install android-tools gcc"
    echo "  Gentoo             : sudo emerge dev-util/android-tools"
    echo "  Void               : sudo xbps-install -S android-tools gcc"
    echo "  Alpine             : sudo apk add android-tools build-base"
    echo "  NixOS              : nix-shell -p android-tools gcc"
    exit 1
fi

# --- uinput module ---
echo "[penlet] checking uinput module..."
if [ ! -e /dev/uinput ]; then
    sudo modprobe uinput 2>/dev/null || true
fi
if [ ! -e /dev/uinput ]; then
    echo "ERROR: /dev/uinput not found. Kernel uinput support is required."
    echo "  sudo modprobe uinput"
    echo "  Persist: echo uinput | sudo tee /etc/modules-load.d/uinput.conf"
    exit 1
fi

# --- device ---
echo "[penlet] waiting for device (USB debugging must be enabled)..."
adb wait-for-device

# --- reverse tunnel ---
echo "[penlet] adb reverse tcp:$PORT -> tcp:$PORT"
adb reverse tcp:$PORT tcp:$PORT

# --- build ---
CC="${CC:-}"
if [ -z "$CC" ]; then
    if command -v cc >/dev/null 2>&1; then CC=cc; else CC=gcc; fi
fi
if [ ! -x "$DIR/daemon/penlet" ] || [ "$DIR/daemon/penlet.c" -nt "$DIR/daemon/penlet" ]; then
    echo "[penlet] building daemon..."
    "$CC" -O2 -o "$DIR/daemon/penlet" "$DIR/daemon/penlet.c"
fi

# --- kill any stale daemon holding the port ---
if pgrep -f "$DIR/daemon/penlet" >/dev/null 2>&1; then
    echo "[penlet] a daemon is already running; stopping it..."
    pkill -f "$DIR/daemon/penlet" 2>/dev/null || sudo pkill -f "$DIR/daemon/penlet" 2>/dev/null || true
    sleep 1
fi

echo "[penlet] Now open the 'penlet' app on your device."
echo "[penlet] starting daemon... (area: $DIR/daemon/area.conf)"

# Prefer running WITHOUT root: the GUI (normal user) can only send SIGHUP
# to a daemon owned by the same user. Requires the udev rule --
# run ./scripts/setup-permissions.sh once.
if [ -w /dev/uinput ]; then
    exec "$DIR/daemon/penlet" "$DIR/daemon/area.conf"
else
    echo
    echo "WARNING: no write access to /dev/uinput -- falling back to sudo."
    echo "  The GUI's live-reload (SIGHUP) will NOT work in this mode,"
    echo "  because a normal user cannot signal a root-owned process."
    echo
    echo "  Fix it once with:  ./scripts/setup-permissions.sh"
    echo "  (then log out and back in)"
    echo
    exec sudo "$DIR/daemon/penlet" "$DIR/daemon/area.conf"
fi
