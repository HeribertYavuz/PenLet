// penlet.c — S Pen -> uinput sanal tableti (osu! / Wayland icin)
//
// Android app'ten TCP loopback (adb reverse) uzerinden 5-byte frame alir:
//   [uint16 x_be][uint16 y_be][uint8 flags]
//   flags bit0 = pen down (touch)
// ve bunu absolute bir uinput "pen tablet" cihazina cevirir.
//
// osu! icin tasarim: basinc/tilt YOK. Sadece mutlak X/Y + touch.
// Cihaz BTN_TOOL_PEN + BTN_TOUCH + ABS_X/ABS_Y ile tanimlanir ki
// libinput onu "tablet tool" olarak gorsun (mouse degil).
//
// Derleme:  cc -O2 -o penlet penlet.c
// Calistirma: sudo ./penlet        (root: /dev/uinput erisimi icin)

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>

#define PORT      40118
#define AXIS_MAX  32767      // cikis ham araligi
#define FRAME_LEN 5

// ---- Tablet area yapilandirmasi ----
// Telefon ekraninin hangi dikdortgen bolgesi kullanilacak (0.0-1.0 normalize).
// area_x0/y0 = sol-ust kose, area_x1/y1 = sag-alt kose.
// Ornek: sadece ortadaki %60'i kullan -> x0=0.2 x1=0.8 y0=0.2 y1=0.8
// keep_aspect=1 ise telefon area en:boy orani korunur (osu! 4:3 icin fit).
typedef struct {
    double ax0, ay0, ax1, ay1;  // tablet area (telefon uzerinde), 0..1
    int    keep_aspect;         // 1 = aspect ratio koru (letterbox), 0 = ger
    double target_aspect;       // korunacak en:boy (osu! playfield ~ 4:3 = 1.333)
} config_t;

static config_t cfg = {
    .ax0 = 0.0, .ay0 = 0.0, .ax1 = 1.0, .ay1 = 1.0,
    .keep_aspect = 0, .target_aspect = 4.0/3.0
};

// Config dosyasini oku (basit key=value). Bulunamazsa varsayilan kullanilir.
static void load_config(const char *path) {
    FILE *f = fopen(path, "r");
    if (!f) {
        fprintf(stderr, "[penlet] config '%s' yok, varsayilan tam-ekran area.\n", path);
        return;
    }
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '#' || *p == '\n' || *p == '\0') continue;   // yorum/bos
        char key[64]; double val;
        if (sscanf(p, "%63[^=]=%lf", key, &val) == 2) {
            // key sonundaki bosluklari kirp
            char *e = key + strlen(key) - 1;
            while (e > key && (*e == ' ' || *e == '\t')) *e-- = '\0';
            if      (!strcmp(key, "area_x0")) cfg.ax0 = val;
            else if (!strcmp(key, "area_y0")) cfg.ay0 = val;
            else if (!strcmp(key, "area_x1")) cfg.ax1 = val;
            else if (!strcmp(key, "area_y1")) cfg.ay1 = val;
            else if (!strcmp(key, "keep_aspect")) cfg.keep_aspect = (int)val;
            else if (!strcmp(key, "target_aspect")) cfg.target_aspect = val;
        }
    }
    fclose(f);
    fprintf(stderr, "[penlet] config: area=[%.3f,%.3f -> %.3f,%.3f] keep_aspect=%d aspect=%.3f\n",
            cfg.ax0, cfg.ay0, cfg.ax1, cfg.ay1, cfg.keep_aspect, cfg.target_aspect);
}

// Gelen normalize telefon konumunu (0..1) tablet area'ya gore cikis eksenine map et.
// Donen deger 0..AXIS_MAX. Area disi noktalar kenara klamplenir.
static void map_point(double nx, double ny, int *ox, int *oy) {
    double aw = cfg.ax1 - cfg.ax0;
    double ah = cfg.ay1 - cfg.ay0;
    if (aw <= 0) aw = 1e-6;
    if (ah <= 0) ah = 1e-6;

    // telefon area'sina gore yerel koordinat (0..1)
    double lx = (nx - cfg.ax0) / aw;
    double ly = (ny - cfg.ay0) / ah;

    if (cfg.keep_aspect) {
        // area en:boy oranini hedef aspect'e sabitle (fit/letterbox).
        // area orani hedeften genisse yatayda, darsa dikeyde ortala.
        double area_aspect = aw / ah;   // not: 0..1 normalize oldugu icin
        // burada telefonun fiziksel oranini bilmedigimizden, aspect'i
        // dogrudan cikis aralik oranina uygulariz: cikisi hedef aspecte gomeriz.
        // basit yaklasim: ciktinin genislik/yukseklik oranini target_aspect yap.
        // lx/ly zaten 0..1; hedef orana gore birini kis.
        if (area_aspect > cfg.target_aspect) {
            // fazla genis -> yatayi kis, ortala
            double scale = cfg.target_aspect / area_aspect;
            lx = 0.5 + (lx - 0.5) * scale;
        } else {
            // fazla dar -> dikeyi kis, ortala
            double scale = area_aspect / cfg.target_aspect;
            ly = 0.5 + (ly - 0.5) * scale;
        }
    }

    // klamp 0..1
    if (lx < 0) lx = 0;
    if (lx > 1) lx = 1;
    if (ly < 0) ly = 0;
    if (ly > 1) ly = 1;

    *ox = (int)(lx * AXIS_MAX);
    *oy = (int)(ly * AXIS_MAX);
}

static int uifd = -1;
static int listen_fd = -1;
static int client_fd = -1;
static char g_cfgpath[512] = "area.conf";   // reload icin saklanan yol
static volatile sig_atomic_t g_reload = 0;   // SIGHUP -> 1

static void emit(int fd, int type, int code, int val) {
    struct input_event ev = {0};
    ev.type = type; ev.code = code; ev.value = val;
    // zaman damgasi kernel tarafindan doldurulur (0 birakmak guvenli)
    ssize_t n = write(fd, &ev, sizeof(ev));
    (void)n;   // uinput'a yazma hatasi tek event icin kritik degil
}

static int setup_uinput(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) { perror("open /dev/uinput"); return -1; }

    // Event tipleri
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);

    // Tablet imzasi: pen tool + touch. Bu ikisi olmadan libinput mouse sanir.
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_PEN);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    // osu! tik icin sol-click esdegeri; bazi Wine yapilandirmalari BTN_LEFT bekler
    ioctl(fd, UI_SET_KEYBIT, BTN_STYLUS);

    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);

    // INPUT_PROP_POINTER: harici grafik tableti gibi (Wacom tarzi).
    // osu!lazer bunu "Tablet" paneline koyar ve area editoru acilir.
    // (INPUT_PROP_DIRECT dokunmatik EKRAN demek olur, area editoru cikmaz.)
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_POINTER);

    struct uinput_abs_setup axs = {0};
    axs.code = ABS_X;
    axs.absinfo.minimum = 0;
    axs.absinfo.maximum = AXIS_MAX;
    axs.absinfo.resolution = 100;   // birim/mm — osu! icin kritik degil
    ioctl(fd, UI_ABS_SETUP, &axs);

    axs.code = ABS_Y;
    ioctl(fd, UI_ABS_SETUP, &axs);

    struct uinput_setup usetup = {0};
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor  = 0xDE33;     // "deku" :)
    usetup.id.product = 0x0501;     // S Pen
    usetup.id.version = 1;
    strcpy(usetup.name, "penlet");

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) { perror("UI_DEV_SETUP"); close(fd); return -1; }
    if (ioctl(fd, UI_DEV_CREATE) < 0)         { perror("UI_DEV_CREATE"); close(fd); return -1; }

    return fd;
}

static void cleanup(int sig) {
    (void)sig;
    if (uifd >= 0) { ioctl(uifd, UI_DEV_DESTROY); close(uifd); }
    if (client_fd >= 0) close(client_fd);
    if (listen_fd >= 0) close(listen_fd);
    fprintf(stderr, "\n[penlet] temizlendi, cikiliyor.\n");
    exit(0);
}

// SIGHUP: GUI "Kaydet"e basinca gonderir -> config'i yeniden oku.
// Handler icinde sadece bayrak set et (fopen handler'da guvensiz).
static void on_sighup(int sig) {
    (void)sig;
    g_reload = 1;
}

static int setup_socket(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return -1; }
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(PORT);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); // sadece 127.0.0.1 — adb reverse buraya baglar

    if (bind(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) { perror("bind"); close(fd); return -1; }
    if (listen(fd, 1) < 0) { perror("listen"); close(fd); return -1; }
    return fd;
}

int main(int argc, char **argv) {
    signal(SIGINT, cleanup);
    signal(SIGTERM, cleanup);
    signal(SIGPIPE, SIG_IGN);

    // config yolu: 1. arguman, yoksa "area.conf"
    const char *cfgpath = (argc > 1) ? argv[1] : "area.conf";
    strncpy(g_cfgpath, cfgpath, sizeof(g_cfgpath) - 1);
    g_cfgpath[sizeof(g_cfgpath) - 1] = '\0';
    load_config(g_cfgpath);

    // SIGHUP -> canli config reload (GUI Kaydet'e basinca)
    signal(SIGHUP, on_sighup);

    // PID'i dosyaya yaz ki GUI 'kill -HUP <pid>' yapabilsin.
    // config dosyasiyla ayni dizine koy.
    {
        char pidpath[600];
        snprintf(pidpath, sizeof(pidpath), "%s.pid", g_cfgpath);
        FILE *pf = fopen(pidpath, "w");
        if (pf) { fprintf(pf, "%d\n", (int)getpid()); fclose(pf); }
    }

    uifd = setup_uinput();
    if (uifd < 0) { fprintf(stderr, "uinput kurulamadi (root? uinput modulu?)\n"); return 1; }

    listen_fd = setup_socket();
    if (listen_fd < 0) return 1;

    fprintf(stderr, "[penlet] hazir. Dinleniyor 127.0.0.1:%d\n", PORT);
    fprintf(stderr, "[penlet] Android app'i baslat, 'adb reverse tcp:%d tcp:%d' kurulu olmali.\n", PORT, PORT);

    uint8_t buf[FRAME_LEN * 256];
    size_t  have = 0;
    int last_down = -1;

    while (1) {
        fprintf(stderr, "[penlet] client bekleniyor...\n");
        client_fd = accept(listen_fd, NULL, NULL);
        if (client_fd < 0) { if (errno == EINTR) continue; perror("accept"); break; }

        // Nagle kapat — osu! latency icin sart
        int one = 1;
        setsockopt(client_fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        fprintf(stderr, "[penlet] baglandi.\n");
        have = 0;

        while (1) {
            if (g_reload) {           // SIGHUP geldi -> config'i tazele
                g_reload = 0;
                load_config(g_cfgpath);
                fprintf(stderr, "[penlet] config yeniden yuklendi.\n");
            }
            ssize_t n = read(client_fd, buf + have, sizeof(buf) - have);
            if (n <= 0) {
                if (n < 0 && errno == EINTR) continue;   // SIGHUP kesti, devam
                fprintf(stderr, "[penlet] baglanti koptu.\n"); break;
            }
            have += (size_t)n;

            size_t off = 0;
            while (have - off >= FRAME_LEN) {
                uint8_t *f = buf + off;
                int rawx = (f[0] << 8) | f[1];
                int rawy = (f[2] << 8) | f[3];
                int down = f[4] & 0x01;
                off += FRAME_LEN;

                // Area mapping ARTIK ANDROID APP'INDE yapiliyor: gelen x/y
                // zaten secili area icinde 0..AXIS_MAX'e normalize edilmis.
                // Daemon passthrough calisir. (area.conf sadece eski/deneysel
                // Linux GUI icin durur; varsayilan tam-ekran = kimlik esleme.)
                int x, y;
                if (cfg.ax0 == 0.0 && cfg.ay0 == 0.0 &&
                    cfg.ax1 == 1.0 && cfg.ay1 == 1.0 && !cfg.keep_aspect) {
                    x = rawx; y = rawy;              // passthrough (varsayilan)
                } else {
                    double nx = (double)rawx / AXIS_MAX;
                    double ny = (double)rawy / AXIS_MAX;
                    map_point(nx, ny, &x, &y);       // deneysel: ek esleme
                }

                // Kalem her zaman "menzilde" (hover dahil) — cursor surekli hareket etsin
                emit(uifd, EV_KEY, BTN_TOOL_PEN, 1);
                emit(uifd, EV_ABS, ABS_X, x);
                emit(uifd, EV_ABS, ABS_Y, y);

                if (down != last_down) {
                    emit(uifd, EV_KEY, BTN_TOUCH, down);
                    last_down = down;
                }
                emit(uifd, EV_SYN, SYN_REPORT, 0);
            }
            // artan yarim frame'i basa tasi
            if (off > 0 && off < have) memmove(buf, buf + off, have - off);
            have -= off;
        }
        close(client_fd);
        client_fd = -1;
        // baglanti kopunca pen'i menzilden cikar
        emit(uifd, EV_KEY, BTN_TOUCH, 0);
        emit(uifd, EV_KEY, BTN_TOOL_PEN, 0);
        emit(uifd, EV_SYN, SYN_REPORT, 0);
        last_down = -1;
    }

    cleanup(0);
    return 0;
}
