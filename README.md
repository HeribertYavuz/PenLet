# penlet

**Turn your S Pen–equipped Android device into an absolute-position graphics tablet on Linux.**

No network involved — just USB + `adb reverse` + a TCP loopback tunnel, with
`TCP_NODELAY` enabled for low latency. Suitable for **osu!**, **drawing**, and **CAD**.

> 🇹🇷 Türkçe: [README.tr.md](README.tr.md)

```
[S Pen] ──► penlet app (127.0.0.1:40118, TCP_NODELAY)
               │  adb reverse (USB tunnel)
               ▼
          penlet daemon (/dev/uinput → virtual tablet)
               │
               ▼
       libinput / Wayland / X11 → osu!, Krita, FreeCAD, ...
```

## ⚠️ Linux only

penlet is **Linux-only, by design**. It builds a virtual input device through
the kernel's `uinput` subsystem — a Linux-specific interface with no Windows
equivalent. **Windows is not supported and will not be supported.** Ports to
other platforms are out of scope.

## Components

| Directory     | Role |
|---------------|------|
| `android/`    | penlet app — area editor + captures S Pen `MotionEvent` (touch + hover), sends 5-byte frames |
| `daemon/`     | `penlet.c` — receives frames, creates a `uinput` absolute tablet (passthrough) |
| `penlet-gui/` | *(experimental)* Rust/egui area editor for the Linux side — superseded by the in-app editor |
| `scripts/`    | `run.sh` (start), `verify.sh` (is it recognized as a tablet?) |

## Protocol

5-byte frame, big-endian:

```
[uint16 x][uint16 y][uint8 flags]
  x, y   : normalized to 0..32767
  flags  : bit0 = pen down
```

Pressure and tilt are deliberately omitted — unnecessary for osu!, and dropping
them reduces latency. (If you need pressure for drawing, see *Roadmap* below.)

## Requirements

- Linux with `uinput` support (present in virtually every mainstream kernel)
- `adb` (Android platform tools)
- A C compiler (`cc` or `gcc`)
- `libinput` (optional, for `verify.sh`)
- Rust + Cargo (optional, only for the area GUI)
- An Android device with a **real S Pen / EMR stylus** (capacitive styluses are ignored)

### Installing dependencies

| Distro | Command |
|--------|---------|
| Arch / Artix / Manjaro | `sudo pacman -S android-tools gcc libinput` |
| Debian / Ubuntu / Mint | `sudo apt install adb build-essential libinput-tools` |
| Fedora | `sudo dnf install android-tools gcc libinput-utils` |
| openSUSE | `sudo zypper install android-tools gcc libinput-tools` |
| Gentoo | `sudo emerge dev-util/android-tools dev-libs/libinput` |
| Void | `sudo xbps-install -S android-tools gcc libinput` |
| Alpine | `sudo apk add android-tools build-base libinput` |
| NixOS | `nix-shell -p android-tools gcc libinput` |

`run.sh` checks for missing tools and prints the right command for your distro.

## Setup

### 1. Make the scripts executable

If you downloaded an archive (rather than cloning), the executable bit may be lost:

```sh
chmod +x scripts/run.sh scripts/verify.sh android/gradlew
```

> **This step is required.** Without it the scripts will not run.
> Cloning from git preserves the executable bit, so this is only needed for archives.

### 2. Set up permissions (optional — only for the experimental Linux GUI)

```sh
./scripts/setup-permissions.sh
```

**You can skip this.** It is only needed if you want the experimental Linux area
GUI. It installs a udev rule granting access to `/dev/uinput`, letting the daemon
run as your **normal user** instead of root.

> **Why this matters:** if the daemon runs under `sudo`, the GUI (running as your
> normal user) cannot send it `SIGHUP` — a normal user may not signal a root-owned
> process. Live area reloading would silently fail. Running unprivileged is also
> simply the safer default.

**Log out and back in** afterwards (or run `newgrp uinput`) for the group change
to take effect. Verify with:

```sh
groups              # should list 'uinput'
ls -l /dev/uinput   # group: uinput, mode: crw-rw----
```

Without this step penlet still works, but `run.sh` falls back to `sudo` and the
GUI's live reload is disabled.

### 3. Build & install the Android app

Open the `android/` directory in Android Studio and hit **Run**.
USB debugging must be enabled on the device.

The manifest already declares the `INTERNET` permission — Android requires it
even for loopback connections.

### 4. Start the daemon

```sh
./scripts/run.sh
```

This loads `uinput`, sets up the `adb reverse` tunnel, builds the daemon if
needed, and starts it. Then open the **penlet** app on your device — it should
report a successful connection.

## Tablet area — configured in the app

Area selection lives **entirely in the Android app**. No permissions, no root,
no signaling — you draw the area with your pen, right where your hand already is.

The app has two modes, toggled with a single button:

| Mode | Behaviour |
|------|-----------|
| **SETUP** | Adjust the area. Input is **not** sent to the PC. Both finger and pen work. |
| **ACTIVE** | Area is locked. Only S Pen input **inside the area** is sent. Panel hides for a clean screen — long-press to return. |

### Three ways to set the area, all available at once

1. **Draw** — drag on empty space to draw a new rectangle
2. **Move / resize** — drag inside the area to move it; drag a corner handle to resize
3. **Numeric** — type X / Y / W / H as percentages and hit *apply*

The area is **saved automatically** and restored on next launch.

### Display options

- **AMOLED mode** — true black background (`#000000`), easy on OLED panels and battery
- **Fullscreen** — immersive, no system bars
- **Pattern** — grid, dots, or none (cycles with one button)
- **Background image** — load any image or GIF frame as a visual backdrop (purely cosmetic; it does not affect input)

### How area mapping works

The rectangle you select is stretched to fill the entire PC screen. A smaller area
means you cover the whole screen with less pen movement — higher effective
sensitivity, which is usually what you want for osu!.

Mapping is applied **on the device**; the daemon simply passes the coordinates
through.

## Linux area GUI (experimental)

`penlet-gui/` is a Rust/egui area editor for the Linux side. It is **experimental**
and no longer the recommended path — the in-app editor above supersedes it.

It requires a udev rule (so the daemon can run unprivileged) and `SIGHUP` signaling.
If you want it anyway:

```sh
./scripts/setup-permissions.sh   # udev rule, one-time; log out and back in
cd penlet-gui && cargo build --release
./target/release/penlet-gui ../daemon/area.conf
```

> If both the app area and `area.conf` are set to non-default values, the mappings
> compose (i.e. they stack). Keep `area.conf` at full-screen defaults unless you
> know you want that.

## Verifying

```sh
./scripts/verify.sh
```

The device named `penlet` should report **Capabilities: tablet**. If it shows up
as a pointer/mouse instead, area mapping will not behave correctly — check that
the daemon uses `INPUT_PROP_POINTER`.

## Use cases

**osu!** — Absolute positioning with hover tracking. Finger input is rejected, so
you won't get stray taps. Configure the area to taste; smaller areas suit
jump-heavy play.

**Drawing (Krita, GIMP)** — Works out of the box for position and click. Pressure
is not currently transmitted (see *Roadmap*).

**CAD (FreeCAD, KiCad)** — Absolute positioning is comfortable for precise
placement; a small area gives you fine control without moving your hand much.

## Known limitations

- **Latency.** Loopback over USB adds ~1–2 ms, but the S Pen digitizer's sampling
  rate (~120 Hz) and Android's input pipeline dominate. Expect it to feel slower
  than a dedicated tablet (2–5 ms). Fine for casual play and drawing; competitive
  osu! players will notice.
- **Finger rejection.** Only `TOOL_TYPE_STYLUS` events are sent; fingers are ignored.
- **Screen must stay on.** The app only captures while it's in the foreground
  (`FLAG_KEEP_SCREEN_ON` is set).
- **`adb reverse` resets** on every USB replug — `run.sh` re-establishes it each time.
- **No pressure/tilt** in the current protocol.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| App reports no connection | Is the daemon running? Check `adb reverse --list` for `tcp:40118`. Force-close and reopen the app. |
| Scripts won't run | `chmod +x scripts/*.sh` — see *Setup* step 1. |
| Cursor doesn't move | Use a real S Pen. Fingers and capacitive styluses are ignored by design. |
| osu! doesn't see a tablet | Run `verify.sh`; it should report `tablet`. If not, check `INPUT_PROP_POINTER` in the daemon. |
| GUI says "no pid file" | The daemon must be running — it creates `area.conf.pid`. |
| GUI: "could not signal daemon" | The daemon is running as root. Run `./scripts/setup-permissions.sh`, log out/in, then start it unprivileged. |
| `bind: Address already in use` | Another daemon is already running: `pkill -f daemon/penlet` (add `sudo` if it was started with sudo). |
| `/dev/uinput` missing | `sudo modprobe uinput`. Persist with `/etc/modules-load.d/uinput.conf`. |

## Roadmap

- Pressure and tilt (optional, for drawing/CAD — off by default to keep osu! latency low)
- Configurable rotation
- Systemd/OpenRC service units

## License

MIT
