#!/bin/sh
# penlet baslatici (Artix / OpenRC uyumlu)
# - uinput modulunu yukler
# - adb reverse tuneli kurar (her USB replug'da resetlenir, o yuzden burada)
# - daemon'i root ile calistirir
set -e
PORT=40118

echo "[*] uinput modulu yukleniyor..."
sudo modprobe uinput 2>/dev/null || echo "  (uinput zaten yuklu ya da kernel'e gomulu)"

echo "[*] telefon bekleniyor (USB debugging acik olmali)..."
adb wait-for-device

echo "[*] adb reverse tcp:$PORT -> tcp:$PORT"
adb reverse tcp:$PORT tcp:$PORT

DIR="$(dirname "$0")/../daemon"
if [ ! -x "$DIR/penlet" ]; then
    echo "[*] daemon derleniyor..."
    cc -O2 -o "$DIR/penlet" "$DIR/penlet.c"
fi

echo "[*] Simdi telefonda 'penlet' app'ini ac."
echo "[*] daemon baslatiliyor (root)... (area: $DIR/area.conf)"
sudo "$DIR/penlet" "$DIR/area.conf"
