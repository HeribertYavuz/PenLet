#!/bin/sh
# Cihazin TABLET olarak taninip taninmadigini dogrula (mouse degil!)
# osu! area kalibrasyonu ancak "tablet tool" gorunurse calisir.
echo "=== libinput cihaz listesi ==="
echo "Asagida 'penlet' -> Capabilities: tablet gorunmeli."
echo "Eger pointer/mouse gorunuyorsa BTN_TOOL_PEN eksik demektir."
echo
sudo libinput list-devices 2>/dev/null | grep -A6 -i "penlet" || \
    echo "libinput-tools kurulu degil. Artix: sudo pacman -S libinput"
echo
echo "=== ham evdev testi (Ctrl-C ile cik) ==="
echo "Kalemi gezdirince ABS_X/ABS_Y, deginince BTN_TOUCH akmali:"
echo "  sudo libinput debug-events --show-keycodes"
