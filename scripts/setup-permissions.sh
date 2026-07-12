#!/bin/sh
# penlet — one-time permission setup / tek seferlik izin kurulumu
#
# Grants access to /dev/uinput without root, so the daemon can run as your
# normal user. This is required for the GUI's live-reload (SIGHUP) to work:
# a normal user cannot signal a root-owned process.
#
# /dev/uinput'a root olmadan erisim verir; daemon normal kullanici olarak
# calisir. GUI'nin canli yeniden yukleme (SIGHUP) ozelligi icin gereklidir:
# normal kullanici root'a ait bir surece sinyal gonderemez.

set -e
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "[penlet] installing udev rule..."
sudo cp "$DIR/99-penlet-uinput.rules" /etc/udev/rules.d/

echo "[penlet] creating 'uinput' group (if missing)..."
sudo groupadd -f uinput

echo "[penlet] adding user '$USER' to 'uinput' group..."
sudo usermod -aG uinput "$USER"

echo "[penlet] reloading udev rules..."
sudo udevadm control --reload-rules
sudo udevadm trigger

# ensure module is loaded now and on boot
sudo modprobe uinput 2>/dev/null || true
if [ ! -f /etc/modules-load.d/uinput.conf ]; then
    echo uinput | sudo tee /etc/modules-load.d/uinput.conf >/dev/null
    echo "[penlet] uinput will load on boot."
fi

echo
echo "=== DONE / TAMAM ==="
echo
echo "IMPORTANT: log out and back in (or run 'newgrp uinput') for the"
echo "group change to take effect."
echo
echo "ONEMLI: grup degisikliginin etkili olmasi icin cikis yapip tekrar"
echo "giris yapin (ya da 'newgrp uinput' calistirin)."
echo
echo "Verify / Dogrulama:"
echo "  groups              # 'uinput' listede olmali"
echo "  ls -l /dev/uinput   # grup: uinput, mod: crw-rw----"
