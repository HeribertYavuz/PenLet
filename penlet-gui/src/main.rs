// penlet-gui: penlet tablet area editoru
//
// Telefon ekranini temsil eden bir dikdortgen gosterir. Icinde fareyle
// bir "area" kutusu cizersin (surukle) ya da mevcut kutuyu tasirsin/boyutlandirirsin.
// "Kaydet" -> area.conf'a yazar + daemon'a SIGHUP gonderir (canli reload).
//
// Derleme:  cargo build --release
// Calistirma: ./target/release/penlet-gui [area.conf yolu]
//
// Not: daemon PID'i "<conf>.pid" dosyasindan okunur, ona SIGHUP atilir.

use eframe::egui;
use std::fs;
use std::path::PathBuf;

#[derive(Clone, Copy)]
struct Area { x0: f32, y0: f32, x1: f32, y1: f32 } // 0..1 normalize

struct App {
    conf_path: PathBuf,
    area: Area,
    keep_aspect: bool,
    target_aspect: f32,
    drag_start: Option<egui::Pos2>, // yeni kutu cizerken baslangic
    status: String,
}

impl App {
    fn new(conf_path: PathBuf) -> Self {
        let mut app = App {
            conf_path,
            area: Area { x0: 0.0, y0: 0.0, x1: 1.0, y1: 1.0 },
            keep_aspect: false,
            target_aspect: 4.0 / 3.0,
            drag_start: None,
            status: String::new(),
        };
        app.load();
        app
    }

    // Mevcut config'i oku (varsa) ki GUI acilinca son ayari gostersin.
    fn load(&mut self) {
        if let Ok(txt) = fs::read_to_string(&self.conf_path) {
            for line in txt.lines() {
                let l = line.trim();
                if l.starts_with('#') || l.is_empty() { continue; }
                if let Some((k, v)) = l.split_once('=') {
                    let k = k.trim();
                    let v = v.trim();
                    match k {
                        "area_x0" => if let Ok(f) = v.parse() { self.area.x0 = f; },
                        "area_y0" => if let Ok(f) = v.parse() { self.area.y0 = f; },
                        "area_x1" => if let Ok(f) = v.parse() { self.area.x1 = f; },
                        "area_y1" => if let Ok(f) = v.parse() { self.area.y1 = f; },
                        "keep_aspect" => self.keep_aspect = v.starts_with('1'),
                        "target_aspect" => if let Ok(f) = v.parse() { self.target_aspect = f; },
                        _ => {}
                    }
                }
            }
        }
    }

    // area.conf'a yaz + daemon'a SIGHUP gonder.
    fn save_and_reload(&mut self) {
        // normalize sirala (x0<x1, y0<y1)
        let (x0, x1) = (self.area.x0.min(self.area.x1), self.area.x0.max(self.area.x1));
        let (y0, y1) = (self.area.y0.min(self.area.y1), self.area.y0.max(self.area.y1));
        self.area = Area { x0, y0, x1, y1 };

        let content = format!(
            "# penlet area (GUI tarafindan yazildi)\n\
             area_x0 = {:.4}\n\
             area_y0 = {:.4}\n\
             area_x1 = {:.4}\n\
             area_y1 = {:.4}\n\
             keep_aspect = {}\n\
             target_aspect = {:.3}\n",
            x0, y0, x1, y1,
            if self.keep_aspect { 1 } else { 0 },
            self.target_aspect
        );

        match fs::write(&self.conf_path, content) {
            Ok(_) => {
                // daemon PID'ini oku, SIGHUP gonder
                let pidfile = format!("{}.pid", self.conf_path.display());
                match fs::read_to_string(&pidfile) {
                    Ok(pidtxt) => {
                        if let Ok(pid) = pidtxt.trim().parse::<i32>() {
                            let rc = unsafe { libc_kill(pid, 1 /*SIGHUP*/) };
                            if rc == 0 {
                                self.status = format!("Kaydedildi + daemon guncellendi (pid {}).", pid);
                            } else {
                                self.status = format!("Kaydedildi ama daemon'a sinyal gitmedi (pid {}? calisiyor mu?).", pid);
                            }
                        } else {
                            self.status = "Kaydedildi. (pid dosyasi okunamadi)".into();
                        }
                    }
                    Err(_) => {
                        self.status = "Kaydedildi. (daemon pid dosyasi yok — daemon calisiyor mu?)".into();
                    }
                }
            }
            Err(e) => { self.status = format!("Yazma hatasi: {}", e); }
        }
    }
}

// libc::kill'e minik FFI (libc crate'i cekmemek icin).
extern "C" { fn kill(pid: i32, sig: i32) -> i32; }
unsafe fn libc_kill(pid: i32, sig: i32) -> i32 { kill(pid, sig) }

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading("penlet — Tablet Area");
            ui.label("Asagidaki dikdortgen telefon ekranidir. Fareyle icinde yeni bir area ciz (surukle).");
            ui.add_space(6.0);

            // Telefon temsili tuval — 16:9 landscape varsayimi
            let avail = ui.available_size();
            let tw = (avail.x - 20.0).min(640.0).max(200.0);
            let th = tw * 9.0 / 16.0;
            let (rect, resp) = ui.allocate_exact_size(egui::vec2(tw, th), egui::Sense::click_and_drag());

            let painter = ui.painter_at(rect);
            // telefon zemini
            painter.rect_filled(rect, 4.0, egui::Color32::from_gray(30));
            painter.rect_stroke(rect, 4.0, egui::Stroke::new(1.0, egui::Color32::from_gray(90)));

            // norm <-> ekran donusumleri
            let to_screen = |nx: f32, ny: f32| egui::pos2(
                rect.min.x + nx * rect.width(),
                rect.min.y + ny * rect.height(),
            );
            let to_norm = |p: egui::Pos2| (
                ((p.x - rect.min.x) / rect.width()).clamp(0.0, 1.0),
                ((p.y - rect.min.y) / rect.height()).clamp(0.0, 1.0),
            );

            // fareyle yeni kutu cizme
            if let Some(pos) = resp.interact_pointer_pos() {
                if resp.drag_started() { self.drag_start = Some(pos); }
                if resp.dragged() {
                    if let Some(start) = self.drag_start {
                        let (sx, sy) = to_norm(start);
                        let (cx, cy) = to_norm(pos);
                        self.area = Area { x0: sx, y0: sy, x1: cx, y1: cy };
                    }
                }
                if resp.drag_released() { self.drag_start = None; }
            }

            // secili area'yi ciz
            let a0 = to_screen(self.area.x0.min(self.area.x1), self.area.y0.min(self.area.y1));
            let a1 = to_screen(self.area.x0.max(self.area.x1), self.area.y0.max(self.area.y1));
            let arect = egui::Rect::from_min_max(a0, a1);
            painter.rect_filled(arect, 2.0, egui::Color32::from_rgba_unmultiplied(80, 160, 255, 60));
            painter.rect_stroke(arect, 2.0, egui::Stroke::new(2.0, egui::Color32::from_rgb(80, 160, 255)));

            ui.add_space(10.0);

            // Sayisal ozet + hizli butonlar
            ui.horizontal(|ui| {
                ui.label(format!(
                    "area: [{:.2}, {:.2}] -> [{:.2}, {:.2}]",
                    self.area.x0.min(self.area.x1), self.area.y0.min(self.area.y1),
                    self.area.x0.max(self.area.x1), self.area.y0.max(self.area.y1)
                ));
            });
            ui.horizontal(|ui| {
                if ui.button("Tum ekran").clicked() {
                    self.area = Area { x0: 0.0, y0: 0.0, x1: 1.0, y1: 1.0 };
                }
                if ui.button("Orta %60").clicked() {
                    self.area = Area { x0: 0.2, y0: 0.2, x1: 0.8, y1: 0.8 };
                }
                ui.checkbox(&mut self.keep_aspect, "Aspect koru (4:3)");
            });

            ui.add_space(6.0);
            if ui.add(egui::Button::new(egui::RichText::new("KAYDET & UYGULA").size(16.0)))
                .clicked()
            {
                self.save_and_reload();
            }

            if !self.status.is_empty() {
                ui.add_space(6.0);
                ui.label(egui::RichText::new(&self.status).color(egui::Color32::LIGHT_GREEN));
            }
        });
    }
}

fn main() -> eframe::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let conf = if args.len() > 1 { PathBuf::from(&args[1]) } else { PathBuf::from("area.conf") };

    let opts = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default().with_inner_size([700.0, 560.0]),
        ..Default::default()
    };
    eframe::run_native(
        "penlet — area",
        opts,
        Box::new(move |_cc| Box::new(App::new(conf))),
    )
}
