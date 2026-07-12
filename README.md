# penlet

S Pen'li Android tabletini Linux'ta **mutlak konumlu grafik tableti** olarak
kullanmani saglayan kopru. Ag yok — sadece USB + `adb reverse` + TCP loopback.
Latency icin `TCP_NODELAY` acik. osu! ve cizim CAD icin uygundur!

```
[S Pen] -> penlet app (127.0.0.1:40118, TCP_NODELAY)
              | adb reverse (USB tuneli)
           penlet daemon (/dev/uinput -> sanal tablet)
              |
           libinput / Wayland -> osu!, Krita, ...
```

## Parcalar

| Klasor        | Ne yapar |
|---------------|----------|
| `android/`    | penlet app — S Pen MotionEvent (dokunma + hover) yakalar, 5-byte frame yollar |
| `daemon/`     | `penlet.c` — TCP frame alir, uinput absolute tablet yaratir, area mapping uygular |
| `penlet-gui/` | Rust/egui area editoru — fareyle area ciz, kaydet, canli uygula |
| `scripts/`    | `run.sh` (baslat), `verify.sh` (tablet olarak taniniyor mu) |

## Protokol

5-byte, big-endian:
```
[uint16 x][uint16 y][uint8 flags]
  x,y   : 0..32767 normalize
  flags : bit0 = pen down
```
Basinc/tilt yok — osu! icin gereksiz, cikarmak latency dusurur.

## Kurulum

### (Sadece Linux destekler Windows Desteği planlanmamıştır ve gelmeyecektir!). 
```sh
### Paket yöneticisinizden gerekli bu iki paketin kurun. 
android-tools libinput
cc -O2 -o daemon/penlet daemon/penlet.c
```

### Android
Android Studio ile `android/` klasorunu ac, telefona kur.
(USB debugging acik olmali. Manifest'te INTERNET izni var — loopback icin sart.)

### Calistir
```sh
./scripts/run.sh
```
Bu script: `uinput` yukler -> `adb reverse` kurar -> daemon'i baslatir.
Sonra telefonda **penlet** app'ini ac -> "BAGLANDI" gormeli.

## Tablet Area (GUI)

Area'yi gorsel ayarlamak icin:

```sh
cd penlet-gui
cargo build --release
./target/release/penlet-gui ../daemon/area.conf
```

- Dikdortgen icinde fareyle surukle -> yeni area
- "Tum ekran" / "Orta %60" hizli butonlari
- "Aspect koru (4:3)" -> daireler yamuksa isaretle
- **KAYDET & UYGULA** -> `area.conf`'a yazar + daemon'a SIGHUP gonderir

Daemon config'i **canli** yeniden okur; osu!'yu kapatmana gerek yok.

### Area mantigi
Telefonun sectigin dikdortgen bolgesi, ekranin tamamina yayilir.
Kucuk area = kalemi az oynatip tum ekrani gezersin (yuksek hassasiyet,
osu! icin ideal). `area.conf` elle de duzenlenebilir.

## Dogrulama

```sh
./scripts/verify.sh
```
`penlet` cihazi icin **Capabilities: tablet** gorunmeli.
Mouse/pointer gorunuyorsa area mapping beklendigi gibi calismaz.

## Bilinen sinirlar

- **Latency**: loopback+USB ~1-2ms, ama S Pen tarama hizi (~120Hz) + Android
  input pipeline darbogaz. Gercek bir cizim tabletinden (2-5ms) yavas hissedebilir.
- **Parmak reddi**: app sadece `TOOL_TYPE_STYLUS` dinler, parmak yok sayilir.
- **Ekran acik kalmali**: app onde ve ekran acikken calisir (`FLAG_KEEP_SCREEN_ON`).
- `adb reverse` her USB replug'da resetlenir — `run.sh` her seferinde yeniden kurar.

## Sorun giderme

| Belirti | Cozum |
|---------|-------|
| App "Baglanti yok" | daemon calisiyor mu? `adb reverse --list` -> `tcp:40118` var mi? App'i kapat-ac. |
| Imlec oynamiyor | Gercek S Pen kullan (parmak/kapasitif kalem yok sayilir). |
| osu! tablet gormuyor | `verify.sh` -> "tablet" cikmali. Cikmiyorsa daemon'da `INPUT_PROP_POINTER` kontrol et. |
| GUI "pid dosyasi yok" | Daemon calisiyor olmali; `area.conf.pid` uretir. |
