# penlet

**S Pen'li Android cihazını Linux'ta mutlak konumlu grafik tabletine dönüştürür.**

Ağ kullanılmaz — sadece USB + `adb reverse` + TCP loopback tüneli, düşük gecikme
için `TCP_NODELAY` açık. **osu!**, **çizim** ve **CAD** için uygundur.

> 🇬🇧 English: [README.md](README.md)

```
[S Pen] ──► penlet app (127.0.0.1:40118, TCP_NODELAY)
               │  adb reverse (USB tüneli)
               ▼
          penlet daemon (/dev/uinput → sanal tablet)
               │
               ▼
       libinput / Wayland / X11 → osu!, Krita, FreeCAD, ...
```

## ⚠️ Sadece Linux

penlet **tasarım gereği yalnızca Linux'u destekler.** Sanal giriş cihazını
çekirdeğin `uinput` altsistemi üzerinden oluşturur; bu Linux'a özgü bir arayüzdür
ve Windows'ta karşılığı yoktur. **Windows desteği yoktur ve olmayacaktır.**
Diğer platformlara port etmek projenin kapsamı dışındadır.

## Bileşenler

| Klasör        | Görevi |
|---------------|--------|
| `android/`    | penlet uygulaması — area editörü + S Pen `MotionEvent` (dokunma + hover) yakalar, 5 baytlık çerçeve gönderir |
| `daemon/`     | `penlet.c` — çerçeveleri alır, `uinput` mutlak tablet oluşturur (passthrough) |
| `penlet-gui/` | *(deneysel)* Linux tarafı Rust/egui area editörü — yerini uygulama içi editör aldı |
| `scripts/`    | `run.sh` (başlat), `verify.sh` (tablet olarak tanınıyor mu?) |

## Protokol

5 baytlık çerçeve, big-endian:

```
[uint16 x][uint16 y][uint8 flags]
  x, y   : 0..32767 aralığına normalize
  flags  : bit0 = kalem değdi (pen down)
```

Basınç ve eğim bilinçli olarak dışarıda bırakıldı — osu! için gereksiz ve
çıkarılması gecikmeyi düşürüyor. (Çizim için basınç isterseniz *Yol haritası*na bakın.)

## Gereksinimler

- `uinput` destekli Linux (pratikte her yaygın çekirdekte var)
- `adb` (Android platform araçları)
- Bir C derleyicisi (`cc` veya `gcc`)
- `libinput` (opsiyonel, `verify.sh` için)
- Rust + Cargo (opsiyonel, sadece area GUI için)
- **Gerçek S Pen / EMR kalemi** olan bir Android cihaz (kapasitif kalemler yok sayılır)

### Bağımlılıkların kurulumu

| Dağıtım | Komut |
|---------|-------|
| Arch / Artix / Manjaro | `sudo pacman -S android-tools gcc libinput` |
| Debian / Ubuntu / Mint | `sudo apt install adb build-essential libinput-tools` |
| Fedora | `sudo dnf install android-tools gcc libinput-utils` |
| openSUSE | `sudo zypper install android-tools gcc libinput-tools` |
| Gentoo | `sudo emerge dev-util/android-tools dev-libs/libinput` |
| Void | `sudo xbps-install -S android-tools gcc libinput` |
| Alpine | `sudo apk add android-tools build-base libinput` |
| NixOS | `nix-shell -p android-tools gcc libinput` |

`run.sh` eksik araçları kontrol eder ve dağıtımınıza uygun komutu yazdırır.

## Kurulum

### 1. Scriptleri çalıştırılabilir yapın

Arşiv indirdiyseniz (klonlamak yerine) çalıştırma biti kaybolmuş olabilir:

```sh
chmod +x scripts/run.sh scripts/verify.sh android/gradlew
```

> **Bu adım zorunludur.** Yapılmazsa scriptler çalışmaz.
> Git ile klonladığınızda çalıştırma biti korunur; bu adım yalnızca arşivler için gereklidir.

### 2. İzinleri ayarlayın (opsiyonel — yalnızca deneysel Linux GUI için)

```sh
./scripts/setup-permissions.sh
```

**Bu adımı atlayabilirsiniz.** Yalnızca deneysel Linux area GUI'sini kullanmak
isterseniz gereklidir. `/dev/uinput` erişimi veren bir udev kuralı kurar; böylece
daemon root yerine **normal kullanıcınız** olarak çalışır.

> **Neden önemli:** daemon `sudo` ile çalışırsa, normal kullanıcı olarak çalışan
> GUI ona `SIGHUP` gönderemez — normal bir kullanıcı, root'a ait bir sürece sinyal
> yollayamaz. Canlı area yenileme sessizce çalışmaz. Ayrıca yetkisiz çalıştırmak
> güvenlik açısından da doğru olandır.

Sonrasında **çıkış yapıp tekrar giriş yapın** (ya da `newgrp uinput` çalıştırın)
ki grup değişikliği etkili olsun. Doğrulama:

```sh
groups              # 'uinput' listede olmalı
ls -l /dev/uinput   # grup: uinput, mod: crw-rw----
```

Bu adım yapılmazsa penlet yine çalışır, ancak `run.sh` `sudo`'ya düşer ve GUI'nin
canlı yenileme özelliği devre dışı kalır.

### 3. Android uygulamasını derleyin ve kurun

Android Studio'da `android/` klasörünü açın ve **Run**'a basın.
Cihazda USB hata ayıklama açık olmalıdır.

Manifest'te `INTERNET` izni zaten tanımlı — Android bunu loopback bağlantıları
için bile şart koşuyor.

### 4. Daemon'ı başlatın

```sh
./scripts/run.sh
```

Bu komut `uinput`'u yükler, `adb reverse` tünelini kurar, gerekirse daemon'ı
derler ve başlatır. Ardından cihazda **penlet** uygulamasını açın — bağlantının
kurulduğunu bildirmelidir.

## Tablet alanı — uygulama içinde ayarlanır

Area seçimi **tamamen Android uygulamasında** yapılır. İzin yok, root yok, sinyal
yok — alanı kalemle, elinizin zaten bulunduğu yerde çizersiniz.

Uygulamanın tek bir düğmeyle geçilen iki modu vardır:

| Mod | Davranış |
|-----|----------|
| **AYAR (SETUP)** | Alanı ayarlarsınız. Girdi PC'ye **gitmez.** Parmak da kalem de çalışır. |
| **AKTİF (ACTIVE)** | Alan kilitlenir. Yalnızca **alan içindeki** S Pen girdisi gönderilir. Panel gizlenir (temiz ekran) — geri dönmek için ekrana uzun basın. |

### Alanı belirlemenin üç yolu, hepsi aynı anda kullanılabilir

1. **Çiz** — boş alanda sürükleyerek yeni dikdörtgen çizin
2. **Taşı / boyutlandır** — alanın içinde sürükleyip taşıyın; köşe tutamacını sürükleyip boyutlandırın
3. **Sayısal** — X / Y / W / H değerlerini yüzde olarak girip *uygula*'ya basın

Alan **otomatik kaydedilir** ve sonraki açılışta geri yüklenir.

### Görüntü seçenekleri

- **AMOLED modu** — tam siyah zemin (`#000000`); OLED panellerde göz ve pil dostu
- **Tam ekran** — sistem çubukları gizli, immersive
- **Desen** — ızgara, nokta veya hiçbiri (tek düğmeyle döner)
- **Arka plan görseli** — herhangi bir görsel veya GIF karesi arka plan olarak yüklenebilir (yalnızca görsel amaçlı; girdiyi etkilemez)

### Area eşlemesi nasıl çalışır

Seçtiğiniz dikdörtgen, PC ekranının tamamına yayılır. Küçük bir alan, kalemi daha
az hareket ettirerek tüm ekranı gezmenizi sağlar — yani daha yüksek etkin
hassasiyet. osu! için genelde istenen budur.

Eşleme **cihaz üzerinde** uygulanır; daemon koordinatları olduğu gibi geçirir.

## Linux area GUI (deneysel)

`penlet-gui/`, Linux tarafı için Rust/egui ile yazılmış bir area editörüdür.
**Deneyseldir** ve artık önerilen yol değildir — yukarıdaki uygulama içi editör
onun yerini almıştır.

Kullanmak için udev kuralı (daemon'ın yetkisiz çalışabilmesi) ve `SIGHUP` sinyali
gerekir. Yine de denemek isterseniz:

```sh
./scripts/setup-permissions.sh   # udev kuralı, tek seferlik; çıkış/giriş yapın
cd penlet-gui && cargo build --release
./target/release/penlet-gui ../daemon/area.conf
```

> Hem uygulama alanı hem de `area.conf` varsayılan dışı değerlerdeyse eşlemeler
> **üst üste biner.** Bilerek istemiyorsanız `area.conf`'u tam ekran varsayılanında
> bırakın.

## Doğrulama

```sh
./scripts/verify.sh
```

`penlet` adlı cihaz **Capabilities: tablet** göstermelidir. Bunun yerine
pointer/mouse görünüyorsa area eşlemesi beklendiği gibi çalışmaz — daemon'ın
`INPUT_PROP_POINTER` kullandığını kontrol edin.

## Kullanım alanları

**osu!** — Hover takibiyle birlikte mutlak konumlandırma. Parmak girdisi
reddedilir, dolayısıyla istenmeyen dokunuşlar olmaz. Alanı zevkinize göre
ayarlayın; küçük alanlar jump ağırlıklı oyuna uygundur.

**Çizim (Krita, GIMP)** — Konum ve tıklama için doğrudan çalışır. Basınç şu an
iletilmiyor (bkz. *Yol haritası*).

**CAD (FreeCAD, KiCad)** — Mutlak konumlandırma hassas yerleştirme için rahattır;
küçük bir alan, elinizi fazla hareket ettirmeden ince kontrol sağlar.

## Bilinen sınırlar

- **Gecikme.** USB üzerinden loopback ~1–2 ms ekler, ancak asıl darboğaz S Pen
  sayısallaştırıcısının örnekleme hızı (~120 Hz) ve Android'in giriş hattıdır.
  Adanmış bir tabletten (2–5 ms) daha yavaş hissettirir. Gündelik oyun ve çizim
  için gayet iyi; rekabetçi osu! oyuncuları farkı hisseder.
- **Parmak reddi.** Yalnızca `TOOL_TYPE_STYLUS` olayları gönderilir; parmaklar yok sayılır.
- **Ekran açık kalmalı.** Uygulama yalnızca ön plandayken yakalama yapar
  (`FLAG_KEEP_SCREEN_ON` etkin).
- **`adb reverse` sıfırlanır** her USB çıkar-tak işleminde — `run.sh` her seferinde yeniden kurar.
- **Basınç/eğim yok** mevcut protokolde.

## Sorun giderme

| Belirti | Çözüm |
|---------|-------|
| Uygulama bağlantı kuramıyor | Daemon çalışıyor mu? `adb reverse --list` çıktısında `tcp:40118` var mı? Uygulamayı tamamen kapatıp yeniden açın. |
| Scriptler çalışmıyor | `chmod +x scripts/*.sh` — bkz. *Kurulum* adım 1. |
| İmleç hareket etmiyor | Gerçek S Pen kullanın. Parmak ve kapasitif kalemler tasarım gereği yok sayılır. |
| osu! tableti görmüyor | `verify.sh` çalıştırın; `tablet` yazmalı. Yazmıyorsa daemon'da `INPUT_PROP_POINTER`'ı kontrol edin. |
| GUI "pid dosyası yok" diyor | Daemon çalışıyor olmalı — `area.conf.pid` dosyasını o oluşturur. |
| GUI "daemon'a sinyal gönderilemedi" | Daemon root olarak çalışıyor. `./scripts/setup-permissions.sh` çalıştırın, çıkış/giriş yapın, sonra root'suz başlatın. |
| `bind: Address already in use` | Zaten bir daemon çalışıyor: `pkill -f daemon/penlet` (sudo ile başlatıldıysa başına `sudo` ekleyin). |
| `/dev/uinput` yok | `sudo modprobe uinput`. Kalıcı hale getirmek için `/etc/modules-load.d/uinput.conf`. |

## Yol haritası

- Basınç ve eğim (opsiyonel; çizim/CAD için — osu! gecikmesini düşük tutmak adına varsayılan kapalı)
- Yapılandırılabilir döndürme (rotation)
- Systemd/OpenRC servis dosyaları

## Lisans

MIT
