#!/bin/sh
# penlet — verify that the virtual device is recognized as a TABLET (not a mouse).
# Area mapping only behaves correctly if libinput sees it as a tablet tool.

echo "=== libinput device list ==="
if ! command -v libinput >/dev/null 2>&1; then
    echo "libinput not installed. Install:"
    echo "  Arch/Artix   : sudo pacman -S libinput"
    echo "  Debian/Ubuntu: sudo apt install libinput-tools"
    echo "  Fedora       : sudo dnf install libinput-utils"
    echo "  openSUSE     : sudo zypper install libinput-tools"
    echo "  Void         : sudo xbps-install -S libinput"
    exit 1
fi

echo "Look for 'penlet' below -> Capabilities should say: tablet"
echo "If it says pointer/mouse instead, area mapping will not work as expected."
echo
sudo libinput list-devices 2>/dev/null | grep -A6 -i "penlet" || \
    echo "Device 'penlet' not found. Is the daemon running?"

echo
echo "=== raw event test (Ctrl-C to exit) ==="
echo "Hover the pen -> ABS_X/ABS_Y should stream. Touch -> BTN_TOUCH."
echo "  sudo libinput debug-events --show-keycodes"
