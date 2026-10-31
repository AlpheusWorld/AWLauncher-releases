use super::*;
use egui::{Align2, FontId, Pos2, Rect, Sense, Stroke};

fn text(ui: &egui::Ui, pos: Pos2, value: &str, size: f32, weight: &str, color: Color32) {
    raw_text(ui, pos, &translations::text(value), size, weight, color);
}
fn raw_text(ui: &egui::Ui, pos: Pos2, value: &str, size: f32, weight: &str, color: Color32) {
    let family = if weight.is_empty() {
        FontFamily::Proportional
    } else {
        FontFamily::Name(weight.into())
    };
    ui.painter().text(
        pos,
        Align2::LEFT_TOP,
        value,
        FontId::new(size, family),
        color,
    );
}
fn box_at(origin: Pos2, x: f32, y: f32, w: f32, h: f32) -> Rect {
    Rect::from_min_size(origin + Vec2::new(x, y), Vec2::new(w.max(0.0), h.max(0.0)))
}
fn button(
    ui: &mut egui::Ui,
    id: &str,
    rect: Rect,
    label: &str,
    fill: Color32,
    color: Color32,
) -> bool {
    let response = ui.interact(rect, ui.id().with(id), Sense::click());
    let fill = if response.hovered() {
        fill.gamma_multiply(1.08)
    } else {
        fill
    };
    ui.painter().rect_filled(rect, 6.0, fill);
    ui.painter().text(
        rect.center(),
        Align2::CENTER_CENTER,
        translations::ui_text(ui, label),
        FontId::new(14.0, FontFamily::Name("aw-semibold".into())),
        color,
    );
    response.clicked()
}

fn icon_button_width(ui: &egui::Ui, label: &str) -> f32 {
    ui.painter()
        .layout_no_wrap(
            translations::ui_text(ui, label),
            FontId::new(14.0, FontFamily::Name("aw-semibold".into())),
            Color32::WHITE,
        )
        .size()
        .x
        + 66.0
}

impl App {
    pub(super) fn draw_icon(&self, ui: &egui::Ui, name: &str, rect: Rect, color: Color32) {
        if let Some(icon) = self.nav_icons.get(name) {
            ui.painter().image(
                icon.id(),
                rect,
                Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                color,
            );
        }
    }
    pub(super) fn icon_button(
        &self,
        ui: &mut egui::Ui,
        id: impl std::hash::Hash,
        rect: Rect,
        label: &str,
        icon: &str,
        fill: Color32,
        color: Color32,
    ) -> bool {
        self.icon_button_response(ui, id, rect, label, icon, fill, color)
            .clicked()
    }
    pub(super) fn icon_button_response(
        &self,
        ui: &mut egui::Ui,
        id: impl std::hash::Hash,
        rect: Rect,
        label: &str,
        icon: &str,
        fill: Color32,
        color: Color32,
    ) -> egui::Response {
        let response = ui.interact(rect, ui.id().with(id), Sense::click());
        ui.painter().rect_filled(
            rect,
            6.0,
            if response.hovered() {
                fill.gamma_multiply(1.08)
            } else {
                fill
            },
        );
        let text = self.tr(label);
        let font = FontId::new(14.0, FontFamily::Name("aw-semibold".into()));
        let text_width = ui
            .painter()
            .layout_no_wrap(text.clone(), font.clone(), color)
            .size()
            .x;
        let left = rect.center().x - (text_width + 30.0) * 0.5;
        self.draw_icon(
            ui,
            icon,
            Rect::from_center_size(egui::pos2(left + 10.0, rect.center().y), Vec2::splat(20.0)),
            color,
        );
        ui.painter().text(
            egui::pos2(left + 30.0, rect.center().y),
            Align2::LEFT_CENTER,
            text,
            font,
            color,
        );
        response
    }
}

impl App {
    pub(super) fn exact_home(&mut self, ui: &mut egui::Ui) {
        let body_width = ui.available_width();
        let compact = ui.available_height() < 700.0;
        let pad = if compact {
            12.0
        } else if body_width < 620.0 {
            16.0
        } else {
            36.0
        };
        let gap = if compact { 10.0 } else { 22.0 };
        let width = (body_width - 2.0 * pad).min(1298.0).max(0.0);
        let wide = body_width >= 1050.0;
        let title_h = if compact { 34.0 } else { 46.0 };
        let card_h = if wide {
            if compact {
                300.0
            } else {
                368.0
            }
        } else {
            280.0
        };
        let cards_extra = if wide { 0.0 } else { 132.0 + gap };
        let total = pad * 2.0
            + title_h
            + gap
            + card_h
            + cards_extra
            + gap
            + 28.0
            + gap
            + self.builds.len().min(3) as f32 * 76.0;
        egui::ScrollArea::vertical()
            .auto_shrink([false, false])
            .show(ui, |ui| {
                let (canvas, _) =
                    ui.allocate_exact_size(Vec2::new(body_width, total), Sense::hover());
                let origin = canvas.min
                    + Vec2::new(pad + (body_width - 2.0 * pad - width).max(0.0) / 2.0, pad);
                let p = self.palette;
                text(
                    ui,
                    origin,
                    "Играть",
                    if compact { 26.0 } else { 38.0 },
                    "aw-bold",
                    p.text,
                );
                let link = box_at(origin, width - 105.0, 4.0, 105.0, 32.0);
                if button(ui, "home-all-top", link, "Все сборки  →", p.bg, p.accent) {
                    self.navigate(Screen::Library);
                }
                let y = title_h + gap;
                let main_w = if wide { width - 306.0 } else { width };
                let card = box_at(origin, 0.0, y, main_w, card_h);
                ui.painter().rect_filled(card, 10.0, p.high);
                let inset = if compact { 14.0 } else { 24.0 };
                let inner = card.shrink(inset);
                text(
                    ui,
                    inner.min,
                    "Выбранная сборка",
                    12.0,
                    "aw-semibold",
                    p.muted,
                );
                let build = self
                    .selected
                    .as_ref()
                    .and_then(|key| self.builds.iter().find(|b| &b.key() == key))
                    .cloned();
                if let Some(build) = build {
                    let version_ready = home_dir()
                        .map(|home| {
                            home.join(".aw/versions")
                                .join(&build.version_id)
                                .join(format!("{}.jar", build.version_id))
                                .is_file()
                        })
                        .unwrap_or(false);
                    let status = if version_ready {
                        "Готова к игре"
                    } else {
                        "Не установлена"
                    };
                    ui.painter().text(
                        inner.right_top(),
                        Align2::RIGHT_TOP,
                        translations::text(status),
                        FontId::proportional(12.0),
                        p.muted,
                    );
                    let bottom_y = card.bottom() - inset - 52.0;
                    let mid_top = inner.top() + 25.0;
                    let mid_bottom = bottom_y - 16.0;
                    let title_y = (mid_top + (mid_bottom - mid_top - 118.0) / 2.0).max(mid_top);
                    raw_text(
                        ui,
                        egui::pos2(inner.left(), title_y),
                        &build.name,
                        if compact { 24.0 } else { 38.0 },
                        "aw-semibold",
                        p.text,
                    );
                    let last = build
                        .last_played
                        .and_then(chrono::DateTime::from_timestamp_millis)
                        .map(|time| {
                            format!(
                                "Последний запуск: {}",
                                time.with_timezone(&chrono::Local).format("%d.%m.%Y")
                            )
                        })
                        .unwrap_or_else(|| "Ещё не запускалась".into());
                    text(
                        ui,
                        egui::pos2(inner.left(), title_y + 51.0),
                        &last,
                        13.0,
                        "",
                        p.muted,
                    );
                    let play_label = if version_ready {
                        "Играть"
                    } else {
                        "Установить и играть"
                    };
                    let play_w = icon_button_width(ui, play_label);
                    let play = Rect::from_min_size(
                        egui::pos2(inner.left(), title_y + 77.0),
                        Vec2::new(play_w, 44.0),
                    );
                    if self.icon_button(
                        ui,
                        "home-play",
                        play,
                        play_label,
                        "play",
                        p.accent,
                        p.on_accent,
                    ) {
                        self.open_backend(Some(&build));
                    }
                    let settings = Rect::from_min_size(
                        play.right_top() + Vec2::new(10.0, 0.0),
                        Vec2::new(108.0, 44.0),
                    );
                    if button(ui, "home-settings", settings, "Настроить", p.high, p.text) {
                        self.open_instance(&build);
                    }
                    ui.painter().rect_stroke(
                        settings,
                        6.0,
                        Stroke::new(1.0_f32, p.muted.gamma_multiply(0.25)),
                        egui::StrokeKind::Inside,
                    );
                    if main_w >= 390.0 {
                        let size = if main_w >= 600.0 { 120.0 } else { 88.0 };
                        let image_rect = Rect::from_center_size(
                            egui::pos2(
                                inner.right() - size / 2.0,
                                (mid_top + mid_bottom) / 2.0 - 6.0,
                            ),
                            Vec2::splat(size),
                        );
                        ui.painter()
                            .rect_filled(image_rect, 8.0, Color32::from_rgb(42, 75, 112));
                        if let Some(texture) = self.artwork(&build) {
                            ui.painter().image(
                                texture.id(),
                                image_rect,
                                Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                                Color32::WHITE,
                            );
                        }
                        ui.painter().text(
                            image_rect.center_bottom() + Vec2::new(0.0, 7.0),
                            Align2::CENTER_TOP,
                            translations::text("Изменить иконку"),
                            FontId::proportional(11.0),
                            p.muted,
                        );
                    }
                    ui.painter().line_segment(
                        [
                            egui::pos2(inner.left(), bottom_y),
                            egui::pos2(inner.right(), bottom_y),
                        ],
                        Stroke::new(1.0_f32, p.muted.gamma_multiply(0.2)),
                    );
                    let count = if main_w >= 500.0 { 3 } else { 2 };
                    let col = (inner.width() - 48.0) / count as f32;
                    for (i, (label, value)) in [
                        ("ВЕРСИЯ", build.version_id.as_str()),
                        ("ЗАГРУЗЧИК", loader_label(&build.loader)),
                        ("СОДЕРЖИМОЕ", "—"),
                    ]
                    .iter()
                    .take(count)
                    .enumerate()
                    {
                        text(
                            ui,
                            egui::pos2(inner.left() + col * i as f32, bottom_y + 16.0),
                            label,
                            11.0,
                            "aw-semibold",
                            p.muted,
                        );
                        text(
                            ui,
                            egui::pos2(inner.left() + col * i as f32, bottom_y + 37.0),
                            value,
                            14.0,
                            "",
                            p.text,
                        );
                    }
                } else {
                    text(
                        ui,
                        inner.min + Vec2::new(0.0, 65.0),
                        "Выбери сборку",
                        24.0,
                        "aw-semibold",
                        p.text,
                    );
                }
                let side_x = if wide { main_w + 16.0 } else { 0.0 };
                let side_y = if wide { y } else { y + card_h + gap };
                let side_w = if wide { 290.0 } else { (width - 12.0) / 2.0 };
                let download_h = if wide { card_h - 124.0 } else { 132.0 };
                let downloads = box_at(origin, side_x, side_y, side_w, download_h);
                ui.painter().rect_filled(downloads, 10.0, p.panel);
                if ui
                    .interact(
                        downloads,
                        ui.id().with("home-downloads-open"),
                        Sense::click(),
                    )
                    .clicked()
                {
                    self.navigate(Screen::Downloads);
                }
                text(
                    ui,
                    downloads.min + Vec2::splat(20.0),
                    "Загрузки",
                    14.0,
                    "aw-semibold",
                    p.text,
                );
                text(
                    ui,
                    downloads.min + Vec2::new(20.0, 52.0),
                    self.downloads
                        .items
                        .iter()
                        .find(|item| item.status.pending())
                        .map(|item| item.title.as_str())
                        .unwrap_or("Загрузок пока нет"),
                    13.0,
                    "",
                    p.muted,
                );
                let activity = box_at(
                    origin,
                    if wide { side_x } else { side_w + 12.0 },
                    if wide {
                        side_y + download_h + 12.0
                    } else {
                        side_y
                    },
                    side_w,
                    if wide { 112.0 } else { 132.0 },
                );
                ui.painter().rect_filled(activity, 10.0, p.panel);
                if ui
                    .interact(activity, ui.id().with("home-activity-open"), Sense::click())
                    .clicked()
                {
                    self.activity_target = None;
                    self.navigate(Screen::Activity);
                    self.refresh_activity(ui.ctx());
                }
                text(
                    ui,
                    activity.min + Vec2::new(20.0, 20.0),
                    "Время в игре",
                    12.0,
                    "",
                    p.muted,
                );
                let time = self
                    .activity
                    .as_ref()
                    .map(|stats| {
                        format!(
                            "{} ч {} мин",
                            stats.week_ms / 3_600_000,
                            (stats.week_ms / 60_000) % 60
                        )
                    })
                    .unwrap_or_else(|| "…".into());
                text(
                    ui,
                    activity.min + Vec2::new(20.0, 45.0),
                    &time,
                    20.0,
                    "aw-semibold",
                    p.text,
                );
                text(
                    ui,
                    activity.min + Vec2::new(20.0, 77.0),
                    "Эта неделя",
                    13.0,
                    "",
                    p.muted,
                );
                let list_y = y + card_h + cards_extra + gap;
                text(
                    ui,
                    origin + Vec2::new(0.0, list_y),
                    "Мои сборки",
                    18.0,
                    "aw-semibold",
                    p.text,
                );
                if button(
                    ui,
                    "home-all-bottom",
                    box_at(origin, width - 105.0, list_y, 105.0, 28.0),
                    "Все сборки  →",
                    p.bg,
                    p.accent,
                ) {
                    self.navigate(Screen::Library);
                }
                let list_top = list_y + 28.0 + gap;
                let rows: Vec<_> = self.builds.iter().take(3).cloned().collect();
                let list = box_at(origin, 0.0, list_top, width, rows.len() as f32 * 76.0);
                ui.painter().rect_filled(list, 10.0, p.panel);
                for (i, build) in rows.iter().enumerate() {
                    let row = box_at(origin, 16.0, list_top + i as f32 * 76.0, width - 32.0, 76.0);
                    if i > 0 {
                        ui.painter().line_segment(
                            [row.left_top(), row.right_top()],
                            Stroke::new(1.0_f32, p.muted.gamma_multiply(0.15)),
                        );
                    }
                    let art =
                        Rect::from_min_size(row.min + Vec2::new(0.0, 15.0), Vec2::splat(46.0));
                    ui.painter()
                        .rect_filled(art, 8.0, Color32::from_rgb(42, 75, 112));
                    if let Some(texture) = self.artwork(build) {
                        ui.painter().image(
                            texture.id(),
                            art,
                            Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                            Color32::WHITE,
                        );
                    }
                    raw_text(
                        ui,
                        row.min + Vec2::new(58.0, 17.0),
                        &build.name,
                        14.0,
                        "aw-semibold",
                        p.text,
                    );
                    text(
                        ui,
                        row.min + Vec2::new(58.0, 40.0),
                        &format!("{} · {}", build.version_id, loader_label(&build.loader)),
                        13.0,
                        "",
                        p.muted,
                    );
                    text(
                        ui,
                        row.right_top() + Vec2::new(-16.0, 24.0),
                        "›",
                        20.0,
                        "",
                        p.muted,
                    );
                    if ui
                        .interact(row, ui.id().with(build.key()), Sense::click())
                        .clicked()
                    {
                        self.open_instance(build);
                    }
                }
            });
    }
}
pub(super) fn loader_label(loader: &str) -> &str {
    match loader {
        "FABRIC" => "Fabric",
        "FORGE" => "Forge",
        "NEOFORGE" => "NeoForge",
        "QUILT" => "Quilt",
        _ => "Vanilla",
    }
}

impl App {
    pub(super) fn exact_title(&mut self, ui: &mut egui::Ui) {
        let (rect, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 58.0), Sense::hover());
        let o = rect.min;
        let width = rect.width();
        let p = self.palette;
        ui.painter()
            .rect_filled(box_at(o, 0.0, 0.0, 214.0, 58.0), 0.0, p.sidebar);
        if let Some(logo) = &self.logo {
            ui.painter().image(
                logo.id(),
                box_at(o, 24.0, 20.0, 18.0, 18.0),
                Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                Color32::WHITE,
            );
        }
        text(
            ui,
            o + Vec2::new(50.0, 18.0),
            "AW",
            18.0,
            "aw-bold",
            p.accent,
        );
        text(
            ui,
            o + Vec2::new(77.0, 18.0),
            "Launcher",
            18.0,
            "aw-bold",
            p.text,
        );
        let back = box_at(o, 214.0, 13.0, 32.0, 32.0);
        let forward = box_at(o, 246.0, 13.0, 32.0, 32.0);
        for (r, id, left, enabled) in [
            (back, "back", true, !self.previous_screens.is_empty()),
            (forward, "forward", false, !self.next_screens.is_empty()),
        ] {
            if ui.interact(r, ui.id().with(id), Sense::click()).clicked() && enabled {
                if left {
                    self.back();
                } else {
                    self.forward();
                }
            }
            let c = r.center();
            let color = if enabled {
                p.muted
            } else {
                p.muted.gamma_multiply(0.35)
            };
            self.draw_icon(
                ui,
                if left { "back" } else { "forward" },
                Rect::from_center_size(c, Vec2::splat(17.0)),
                color,
            );
        }
        let title = match self.screen {
            Screen::Home => "Играть",
            Screen::Library => "Сборки",
            Screen::Instance => self
                .selected
                .as_ref()
                .and_then(|key| self.builds.iter().find(|b| &b.key() == key))
                .map(|b| b.name.as_str())
                .unwrap_or("Сборка"),
            Screen::Screenshots => "Скриншоты",
            Screen::Activity => "Активность",
            Screen::Notices => "Уведомления",
            Screen::Catalog => "Каталог",
            Screen::Accounts => "Аккаунты",
            Screen::Downloads => "Загрузки",
            Screen::Project => self
                .project_hit
                .as_ref()
                .map(|h| h.title.as_str())
                .unwrap_or("Проект"),
        };
        let title_x = if width >= 900.0 {
            text(
                ui,
                o + Vec2::new(290.0, 21.0),
                "AWLauncher  ›",
                13.0,
                "",
                p.muted,
            );
            386.0
        } else {
            290.0
        };
        let title = if matches!(self.screen, Screen::Instance | Screen::Project) {
            title.to_owned()
        } else {
            self.tr(title)
        };
        raw_text(
            ui,
            o + Vec2::new(title_x, 20.0),
            &title,
            15.0,
            "aw-bold",
            p.text,
        );
        let drag = ui.interact(
            box_at(o, 278.0, 0.0, (width - 278.0 - 350.0).max(0.0), 57.0),
            ui.id().with("drag"),
            Sense::drag(),
        );
        if drag.drag_started() {
            ui.ctx().send_viewport_cmd(egui::ViewportCommand::StartDrag);
        }
        if width >= 900.0 {
            let status_text = self.tr(
                if self
                    .downloads
                    .items
                    .iter()
                    .any(|item| item.status.pending())
                {
                    "Выполняется операция"
                } else if self.running_games.is_empty() {
                    "Нет активных сборок"
                } else {
                    "Игра запущена"
                },
            );
            let status_width = (ui
                .painter()
                .layout_no_wrap(status_text.clone(), FontId::proportional(13.0), p.muted)
                .size()
                .x
                + 32.0)
                .min(210.0);
            let status = box_at(o, width - 146.0 - status_width, 14.0, status_width, 30.0);
            ui.painter().rect_filled(status, 6.0, p.panel);
            ui.painter()
                .circle_filled(status.left_center() + Vec2::new(13.0, 0.0), 3.0, p.muted);
            ui.painter()
                .with_clip_rect(status.shrink2(Vec2::new(10.0, 0.0)))
                .text(
                    status.left_center() + Vec2::new(22.0, 0.0),
                    Align2::LEFT_CENTER,
                    status_text,
                    FontId::proportional(13.0),
                    p.muted,
                );
            let version = box_at(o, status.left() - o.x - 67.0, 19.0, 55.0, 20.0);
            ui.painter().rect_filled(version, 6.0, p.high);
            ui.painter().text(
                version.center(),
                Align2::CENTER_CENTER,
                "v1.0.50",
                FontId::new(11.0, FontFamily::Name("aw-semibold".into())),
                p.muted,
            );
        }
        for (i, id) in ["minimize", "maximize", "close"].iter().enumerate() {
            let r = box_at(o, width - 138.0 + i as f32 * 46.0, 0.0, 46.0, 57.0);
            let response = ui.interact(r, ui.id().with(id), Sense::click());
            if response.hovered() {
                ui.painter().rect_filled(
                    r,
                    0.0,
                    if *id == "close" {
                        Color32::from_rgb(170, 40, 40)
                    } else {
                        p.high
                    },
                );
            }
            let c = r.center();
            let stroke = Stroke::new(1.2_f32, p.muted);
            match *id {
                "minimize" => {
                    ui.painter()
                        .line_segment([c + Vec2::new(-5.0, 2.0), c + Vec2::new(5.0, 2.0)], stroke);
                }
                "maximize" => {
                    ui.painter().rect_stroke(
                        Rect::from_center_size(c, Vec2::splat(10.0)),
                        0.0,
                        stroke,
                        egui::StrokeKind::Inside,
                    );
                }
                _ => {
                    ui.painter()
                        .line_segment([c + Vec2::new(-4.0, -4.0), c + Vec2::new(4.0, 4.0)], stroke);
                    ui.painter()
                        .line_segment([c + Vec2::new(-4.0, 4.0), c + Vec2::new(4.0, -4.0)], stroke);
                }
            }
            if response.clicked() {
                match *id {
                    "minimize" => ui
                        .ctx()
                        .send_viewport_cmd(egui::ViewportCommand::Minimized(true)),
                    "maximize" => {
                        let max = ui.ctx().input(|i| i.viewport().maximized.unwrap_or(false));
                        ui.ctx()
                            .send_viewport_cmd(egui::ViewportCommand::Maximized(!max));
                    }
                    _ => ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close),
                }
            }
        }
        ui.painter().line_segment(
            [rect.left_bottom(), rect.right_bottom()],
            Stroke::new(1.0_f32, p.muted.gamma_multiply(0.25)),
        );
    }
}

impl App {
    pub(super) fn exact_library_row(&mut self, ui: &mut egui::Ui, build: &Build) -> bool {
        let p = self.palette;
        let (row, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 80.0), Sense::hover());
        ui.painter().rect_filled(row, 6.0, p.panel);
        let art = Rect::from_min_size(row.min + Vec2::new(16.0, 14.0), Vec2::splat(52.0));
        let background = build
            .icon_background
            .as_deref()
            .unwrap_or(match build.loader.as_str() {
                "FABRIC" => "blue",
                "FORGE" => "amber",
                "QUILT" => "violet",
                _ => "emerald",
            });
        let tint = match background {
            "amber" => Color32::from_rgb(112, 82, 40),
            "violet" => Color32::from_rgb(82, 54, 110),
            "emerald" => Color32::from_rgb(28, 83, 62),
            _ => Color32::from_rgb(42, 75, 112),
        };
        ui.painter().rect_filled(art, 8.0, tint);
        if let Some(texture) = self.artwork(build) {
            egui::Image::new(texture)
                .corner_radius(8.0)
                .paint_at(ui, art);
        }
        ui.painter().rect_stroke(
            art,
            8.0,
            Stroke::new(1.0_f32, Color32::from_white_alpha(31)),
            egui::StrokeKind::Inside,
        );
        raw_text(
            ui,
            row.min + Vec2::new(80.0, 20.0),
            &build.name,
            14.0,
            "aw-semibold",
            p.text,
        );
        let group = self
            .preferences
            .values
            .get("libraryGroups")
            .and_then(|v| v.as_array())
            .and_then(|groups| {
                groups
                    .iter()
                    .find(|g| g["id"].as_str() == build.group_id.as_deref())
            })
            .and_then(|group| group["name"].as_str());
        let subtitle = format!(
            "{} {}{}",
            loader_label(&build.loader),
            build.version_id,
            group.map(|name| format!(" · {name}")).unwrap_or_default()
        );
        text(
            ui,
            row.min + Vec2::new(80.0, 44.0),
            &subtitle,
            13.0,
            "",
            p.muted,
        );
        let play_width = icon_button_width(ui, "Играть");
        let play = Rect::from_min_size(
            egui::pos2(row.right() - 54.0 - play_width, row.top() + 18.0),
            Vec2::new(play_width, 44.0),
        );
        let selected = self.selected.as_ref() == Some(&build.key());
        if self.icon_button(
            ui,
            ("library-play", build.key()),
            play,
            "Играть",
            "play",
            if selected { p.accent } else { p.high },
            if selected { p.on_accent } else { p.text },
        ) {
            self.open_backend(Some(build));
        }
        let favorite = Rect::from_center_size(
            egui::pos2(play.left() - 28.0, row.center().y),
            Vec2::splat(32.0),
        );
        self.draw_icon(
            ui,
            "star",
            Rect::from_center_size(favorite.center(), Vec2::splat(17.0)),
            if build.favorite {
                Color32::from_rgb(238, 177, 109)
            } else {
                p.muted
            },
        );
        if ui
            .interact(
                favorite,
                ui.id().with(("favorite", build.key())),
                Sense::click(),
            )
            .clicked()
        {
            self.downloads.enqueue(self.tr("Избранное"),serde_json::json!({"op":"saveOptions","target":build.key(),"options":{"favorite":!build.favorite}}));
        }
        let menu = Rect::from_center_size(
            egui::pos2(row.right() - 27.0, row.center().y),
            Vec2::splat(30.0),
        );
        for y in [-4.0, 0.0, 4.0] {
            ui.painter()
                .circle_filled(menu.center() + Vec2::new(0.0, y), 1.5, p.muted);
        }
        if ui
            .interact(
                menu,
                ui.id().with(("instance-menu", build.key())),
                Sense::click(),
            )
            .clicked()
        {
            self.instance_menu = Some(build.key());
        }
        let click = Rect::from_min_max(row.min, egui::pos2(favorite.left() - 12.0, row.bottom()));
        ui.interact(
            click,
            ui.id().with(("open-build", build.key())),
            Sense::click(),
        )
        .clicked()
    }
}
impl App {
    pub(super) fn exact_instance_header(&mut self, ui: &mut egui::Ui, build: &Build) {
        let p = self.palette;
        let (rect, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 90.0), Sense::hover());
        let art = Rect::from_min_size(rect.min + Vec2::new(0.0, 6.0), Vec2::splat(64.0));
        ui.painter()
            .rect_filled(art, 8.0, Color32::from_rgb(42, 75, 112));
        if let Some(texture) = self.artwork(build) {
            ui.painter().image(
                texture.id(),
                art,
                Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                Color32::WHITE,
            );
        }
        raw_text(
            ui,
            rect.min + Vec2::new(78.0, 0.0),
            &build.name,
            22.0,
            "aw-bold",
            p.text,
        );
        text(
            ui,
            rect.min + Vec2::new(78.0, 34.0),
            &format!(
                "{} {} · {}",
                loader_label(&build.loader),
                build.version_id,
                self.play_time_label(build.play_time_millis)
            ),
            13.0,
            "",
            p.muted,
        );
        let last = build
            .last_played
            .and_then(chrono::DateTime::from_timestamp_millis)
            .map(|time| {
                use chrono::Datelike;
                let local = time.with_timezone(&chrono::Local);
                let months = [
                    "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя",
                    "дек",
                ];
                let month = self.tr(months[local.month0() as usize]);
                let suffix = if local.year() == chrono::Local::now().year() {
                    String::new()
                } else {
                    format!(" {}", local.year())
                };
                self.tr("Последний запуск: {0}")
                    .replace("{0}", &format!("{} {month}{suffix}", local.day()))
            })
            .unwrap_or_else(|| self.tr("Ещё не запускалась"));
        text(
            ui,
            rect.min + Vec2::new(78.0, 58.0),
            &last,
            13.0,
            "",
            p.muted,
        );
        let play_width = icon_button_width(ui, "Играть");
        let play = Rect::from_min_size(
            rect.right_top() + Vec2::new(-96.0 - play_width, 18.0),
            Vec2::new(play_width, 44.0),
        );
        if self.icon_button(
            ui,
            "instance-play",
            play,
            "Играть",
            "play",
            p.accent,
            p.on_accent,
        ) {
            self.open_backend(Some(build));
        }
        if let Some(icon) = self.nav_icons.get("settings") {
            ui.painter().image(
                icon.id(),
                Rect::from_center_size(
                    rect.right_top() + Vec2::new(-64.0, 40.0),
                    Vec2::splat(21.0),
                ),
                Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                p.muted,
            );
        }
        for dy in [-5.0, 0.0, 5.0] {
            ui.painter().circle_filled(
                rect.right_top() + Vec2::new(-14.0, 40.0 + dy),
                2.0,
                p.muted,
            );
        }
        if ui
            .interact(
                Rect::from_center_size(
                    rect.right_top() + Vec2::new(-64.0, 40.0),
                    Vec2::splat(38.0),
                ),
                ui.id().with("instance-options"),
                Sense::click(),
            )
            .clicked()
        {
            self.open_instance_options(build);
        }
        if ui
            .interact(
                Rect::from_center_size(
                    rect.right_top() + Vec2::new(-14.0, 40.0),
                    Vec2::splat(30.0),
                ),
                ui.id().with("instance-actions"),
                Sense::click(),
            )
            .clicked()
        {
            self.instance_menu = Some(build.key());
        }
        ui.painter().line_segment(
            [rect.left_bottom(), rect.right_bottom()],
            Stroke::new(1.0_f32, p.muted.gamma_multiply(0.25)),
        );
    }
}
impl App {
    pub(super) fn exact_accounts(&mut self, ui: &mut egui::Ui) {
        let p = self.palette;
        let w = ui.available_width();
        let auth = self
            .downloads
            .items
            .iter()
            .rev()
            .find(|item| {
                item.request["op"]
                    .as_str()
                    .is_some_and(|op| op.starts_with("account"))
            })
            .map(|item| {
                (
                    item.status,
                    item.stage.clone(),
                    item.login.clone(),
                    item.error.clone(),
                )
            });
        let h = (self.account_previews.len() as f32 * 62.0 + 470.0).max(ui.available_height());
        egui::ScrollArea::vertical().show(ui, |ui| {
            let (canvas, _) = ui.allocate_exact_size(Vec2::new(w, h), Sense::hover());
            let o = canvas.min;
            text(ui, o, "Аккаунты", 20.0, "aw-bold", p.text);
            ui.painter().text(
                o + Vec2::new(w, 4.0),
                Align2::RIGHT_TOP,
                self.account_previews.len().to_string(),
                FontId::new(14.0, FontFamily::Name("aw-semibold".into())),
                p.muted,
            );
            text(
                ui,
                o + Vec2::new(0.0, 42.0),
                "Выбранный аккаунт используется при запуске Minecraft",
                13.0,
                "",
                p.muted,
            );
            let card = box_at(
                o,
                0.0,
                72.0,
                w,
                self.account_previews.len() as f32 * 62.0 + 12.0,
            );
            ui.painter().rect_filled(card, 8.0, p.panel);
            for (i, (name, kind, selected)) in self.account_previews.iter().enumerate() {
                let y = card.top() + 14.0 + i as f32 * 62.0;
                let avatar =
                    Rect::from_min_size(egui::pos2(card.left() + 18.0, y), Vec2::splat(44.0));
                ui.painter().rect_filled(avatar, 14.0, p.high);
                ui.painter().text(
                    avatar.center(),
                    Align2::CENTER_CENTER,
                    name.chars().next().unwrap_or('A'),
                    FontId::new(20.0, FontFamily::Name("aw-bold".into())),
                    p.accent,
                );
                raw_text(
                    ui,
                    egui::pos2(card.left() + 74.0, y + 3.0),
                    name,
                    15.0,
                    "aw-semibold",
                    p.text,
                );
                text(
                    ui,
                    egui::pos2(card.left() + 74.0, y + 25.0),
                    if kind == "OFFLINE" {
                        "Офлайн-профиль"
                    } else {
                        "Microsoft"
                    },
                    13.0,
                    "",
                    p.muted,
                );
                let badge = Rect::from_min_size(
                    egui::pos2(card.right() - 180.0, y + 10.0),
                    Vec2::new(76.0, 23.0),
                );
                ui.painter().rect_filled(
                    badge,
                    6.0,
                    if kind == "OFFLINE" {
                        Color32::from_rgb(55, 48, 32)
                    } else {
                        p.selected
                    },
                );
                ui.painter().text(
                    badge.center(),
                    Align2::CENTER_CENTER,translations::text(if kind == "OFFLINE" {
                        "Офлайн"
                    } else {
                        "Лицензия"
                    }),
                    FontId::new(12.0, FontFamily::Name("aw-semibold".into())),
                    if kind == "OFFLINE" {
                        Color32::from_rgb(238, 177, 109)
                    } else {
                        p.accent
                    },
                );
                if let Some(id)=self.account_ids.get(i){
                    let delete=Rect::from_center_size(egui::pos2(card.right()-28.0,y+22.0),Vec2::splat(28.0));
                    if ui.interact(delete,ui.id().with(("remove-account",i)),Sense::click()).clicked(){self.account_remove=Some(id.clone());}
                    let c=delete.center();let stroke=Stroke::new(1.2_f32,p.muted);ui.painter().rect_stroke(Rect::from_center_size(c+Vec2::new(0.0,2.0),Vec2::new(8.0,10.0)),1.0,stroke,egui::StrokeKind::Inside);ui.painter().line_segment([c+Vec2::new(-5.0,-5.0),c+Vec2::new(5.0,-5.0)],stroke);
                    let choose=Rect::from_min_max(egui::pos2(card.left()+16.0,y),egui::pos2(card.right()-50.0,y+48.0));
                    if !selected && ui.interact(choose,ui.id().with(("choose-account",i)),Sense::click()).clicked(){self.downloads.enqueue("Выбор аккаунта".into(),serde_json::json!({"op":"accountSelect","file":id}));}
                }
                if *selected {
                    let c = egui::pos2(card.right() - 78.0, y + 22.0);
                    ui.painter().line_segment(
                        [c + Vec2::new(-5.0, 0.0), c + Vec2::new(-1.0, 4.0)],
                        Stroke::new(1.5_f32, p.accent),
                    );
                    ui.painter().line_segment(
                        [c + Vec2::new(-1.0, 4.0), c + Vec2::new(6.0, -4.0)],
                        Stroke::new(1.5_f32, p.accent),
                    );
                }
            }
            let base = card.bottom() + 18.0;
            ui.painter().line_segment(
                [egui::pos2(o.x, base), egui::pos2(o.x + w, base)],
                Stroke::new(1.0_f32, p.muted.gamma_multiply(0.25)),
            );
            text(
                ui,
                egui::pos2(o.x, base + 18.0),
                "Добавить аккаунт",
                15.0,
                "aw-semibold",
                p.text,
            );
            if button(
                ui,
                "account-microsoft-tab",
                Rect::from_min_size(egui::pos2(o.x, base + 60.0), Vec2::new(96.0, 44.0)),
                "Microsoft",
                if !self.account_offline_tab {
                    p.selected
                } else {
                    p.panel
                },
                if !self.account_offline_tab {
                    p.accent
                } else {
                    p.text
                },
            ) {
                self.account_offline_tab = false;
            }
            if button(
                ui,
                "account-offline-tab",
                Rect::from_min_size(egui::pos2(o.x + 104.0, base + 60.0), Vec2::new(86.0, 44.0)),
                "Офлайн",
                if self.account_offline_tab {
                    p.selected
                } else {
                    p.panel
                },
                if self.account_offline_tab {
                    p.accent
                } else {
                    p.text
                },
            ) {
                self.account_offline_tab = true;
            }
            text(
                ui,
                egui::pos2(o.x, base + 124.0),
                if self.account_offline_tab {
                    "Имя игрока для офлайн-профиля"
                } else {
                    "Подтверди вход по коду в браузере Microsoft"
                },
                14.0,
                "",
                p.text,
            );
            if self.account_offline_tab {
                ui.put(
                    Rect::from_min_size(egui::pos2(o.x, base + 154.0), Vec2::new(280.0, 40.0)),
                    egui::TextEdit::singleline(&mut self.offline_name).hint_text(translations::text("Имя игрока")),
                );
            } else {
                text(
                    ui,
                    egui::pos2(o.x, base + 157.0),
                    "Нужен аккаунт с Minecraft Java Edition",
                    13.0,
                    "",
                    p.muted,
                );
            }
            let label=if self.account_offline_tab{"Добавить аккаунт"}else{"Войти через Microsoft"};
            let action=box_at(o,0.0,base-o.y+188.0,190.0,44.0);
            let busy=auth.as_ref().is_some_and(|v|v.0.pending());
            if !busy && button(ui,"account-auth",action,label,p.accent,p.on_accent){
                self.downloads.enqueue(if self.account_offline_tab{"Добавление аккаунта"}else{"Вход через Microsoft"}.into(),serde_json::json!({"op":if self.account_offline_tab{"accountOffline"}else{"accountMicrosoft"},"title":self.offline_name}));
            }
            if busy {ui.painter().rect_filled(action,6.0,p.high);text(ui,action.min+Vec2::new(12.0,12.0),"Ожидаю подтверждения…",13.0,"",p.muted);}
            if let Some((status,stage,login,error))=&auth{
                if let Some((code,url))=login{
                    text(ui,egui::pos2(o.x,base+246.0),code,24.0,"aw-semibold",p.accent);
                    ui.put(box_at(o,0.0,base-o.y+284.0,300.0,32.0),egui::Hyperlink::from_label_and_url(translations::text("Открыть страницу Microsoft"),url));
                    if button(ui,"copy-login-code",box_at(o,310.0,base-o.y+280.0,145.0,36.0),"Скопировать код",p.high,p.text){ui.ctx().copy_text(code.clone());}
                }else if status.pending(){text(ui,egui::pos2(o.x,base+244.0),stage,13.0,"",p.muted);}
                if let Some(error)=error{text(ui,egui::pos2(o.x,base+324.0),error,13.0,"",Color32::LIGHT_RED);}
            }

        });
    }
}
impl App {
    pub(super) fn exact_settings(&mut self, ctx: &egui::Context) {
        if ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
            self.settings_open = false;
            return;
        }
        let screen = ctx.screen_rect();
        let size = Vec2::new(
            980.0_f32.min(screen.width() - 32.0),
            680.0_f32.min(screen.height() - 32.0),
        );
        let origin = screen.center() - size / 2.0;
        egui::Area::new(egui::Id::new("settings-dim"))
            .order(egui::Order::Middle)
            .fixed_pos(screen.min)
            .show(ctx, |ui| {
                let (rect, response) = ui.allocate_exact_size(screen.size(), Sense::click());
                ui.painter()
                    .rect_filled(rect, 0.0, Color32::from_black_alpha(122));
                if response.clicked() {
                    self.settings_open = false;
                }
            });
        let labels = [
            (
                "Аккаунты",
                "Выбранный аккаунт используется при запуске Minecraft",
                "account",
            ),
            ("Общие", "Поведение лаунчера", "settings"),
            ("Игра", "Память", "settings"),
            ("Внешний вид", "Внешний вид", "image"),
            ("Язык", "Язык приложения", "notices"),
            ("Загрузки", "Загрузка и целостность", "download"),
            ("Discord", "Discord Rich Presence", "friends"),
            ("О лаунчере", "Версия лаунчера", "notices"),
        ];
        let p = self.palette;
        egui::Area::new(egui::Id::new("settings-overlay"))
            .order(egui::Order::Foreground)
            .fixed_pos(origin)
            .show(ctx, |ui| {
                let (r, _) = ui.allocate_exact_size(size, Sense::click());
                let o = r.min;
                let compact = size.x < 700.0;
                let rail = if compact { 0.0 } else { 248.0 };
                let header_y = if compact { 100.0 } else { 0.0 };
                ui.painter().rect_filled(r, 10.0, p.panel);
                if !compact {
                    ui.painter()
                        .rect_filled(box_at(o, 0.0, 0.0, rail, size.y), 10.0, p.sidebar);
                    ui.painter().rect_stroke(
                        r,
                        10.0,
                        Stroke::new(1.0_f32, p.outline),
                        egui::StrokeKind::Inside,
                    );
                    ui.painter().line_segment(
                        [o + Vec2::new(rail, 0.0), o + Vec2::new(rail, size.y)],
                        Stroke::new(1.0_f32, p.outline),
                    );
                    text(
                        ui,
                        o + Vec2::new(16.0, 16.0),
                        "Настройки",
                        18.0,
                        "aw-semibold",
                        p.text,
                    );
                    ui.put(
                        box_at(o, 16.0, 50.0, rail - 32.0, 40.0),
                        egui::TextEdit::singleline(&mut self.settings_search)
                            .margin(Vec2::new(14.0, 10.0))
                            .hint_text(translations::text("Найти раздел")),
                    );
                    let query = self.settings_search.to_lowercase();
                    let mut y = 104.0;
                    for (index, (label, hint, icon)) in labels.iter().enumerate() {
                        let translated_label = self.tr(label);
                        let translated_hint = self.tr(hint);
                        let label = translated_label.as_str();
                        let hint = translated_hint.as_str();
                        if !query.is_empty()
                            && !label.to_lowercase().contains(&query)
                            && !hint.to_lowercase().contains(&query)
                        {
                            continue;
                        }
                        let item = box_at(o, 16.0, y, rail - 32.0, 56.0);
                        if index == self.settings_section {
                            ui.painter().rect_filled(item, 6.0, p.high);
                        }
                        if let Some(texture) = self.nav_icons.get(*icon) {
                            ui.painter().image(
                                texture.id(),
                                Rect::from_center_size(
                                    item.left_center() + Vec2::new(21.0, 0.0),
                                    Vec2::splat(18.0),
                                ),
                                Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                                p.muted,
                            );
                        }
                        text(
                            ui,
                            item.min + Vec2::new(42.0, 8.0),
                            label,
                            14.0,
                            "aw-semibold",
                            p.text,
                        );
                        let short = if hint.chars().count() > 25 {
                            format!("{}…", hint.chars().take(25).collect::<String>())
                        } else {
                            hint.to_string()
                        };
                        text(
                            ui,
                            item.min + Vec2::new(42.0, 29.0),
                            &short,
                            13.0,
                            "",
                            p.muted,
                        );
                        if ui
                            .interact(item, ui.id().with(index), Sense::click())
                            .clicked()
                        {
                            self.settings_section = index;
                        }
                        y += 58.0;
                    }
                    text(
                        ui,
                        o + Vec2::new(16.0, size.y - 30.0),
                        "AWLauncher 1.0.50",
                        13.0,
                        "",
                        p.muted,
                    );
                } else {
                    text(
                        ui,
                        o + Vec2::new(16.0, 12.0),
                        "Настройки",
                        18.0,
                        "aw-semibold",
                        p.text,
                    );
                    let tab_rect = box_at(o, 16.0, 44.0, size.x - 32.0, 44.0);
                    let mut tabs = ui.new_child(
                        egui::UiBuilder::new()
                            .max_rect(tab_rect)
                            .layout(egui::Layout::left_to_right(egui::Align::Center)),
                    );
                    tabs.set_clip_rect(tab_rect);
                    egui::ScrollArea::horizontal().show(&mut tabs, |ui| {
                        ui.horizontal(|ui| {
                            for (index, (label, _, _)) in labels.iter().enumerate() {
                                if ui
                                    .add_sized(
                                        [100.0, 44.0],
                                        egui::Button::new(
                                            RichText::new(translations::text(*label)).color(
                                                if index == self.settings_section {
                                                    p.accent
                                                } else {
                                                    p.text
                                                },
                                            ),
                                        )
                                        .fill(
                                            if index == self.settings_section {
                                                p.high
                                            } else {
                                                p.panel
                                            },
                                        ),
                                    )
                                    .clicked()
                                {
                                    self.settings_section = index;
                                }
                            }
                        });
                    });
                    ui.painter().line_segment(
                        [o + Vec2::new(0.0, 99.0), o + Vec2::new(size.x, 99.0)],
                        Stroke::new(1.0_f32, p.outline),
                    );
                }
                let (label, hint, _) = labels[self.settings_section];
                text(
                    ui,
                    o + Vec2::new(rail + if compact { 16.0 } else { 24.0 }, header_y + 12.0),
                    label,
                    18.0,
                    "aw-semibold",
                    p.text,
                );
                text(
                    ui,
                    o + Vec2::new(rail + if compact { 16.0 } else { 24.0 }, header_y + 37.0),
                    hint,
                    13.0,
                    "",
                    p.muted,
                );
                let close = box_at(o, size.x - 48.0, header_y + 12.0, 36.0, 36.0);
                if button(ui, "settings-close", close, "×", p.panel, p.muted) {
                    self.settings_open = false;
                }
                ui.painter().line_segment(
                    [
                        o + Vec2::new(rail, header_y + 64.0),
                        o + Vec2::new(size.x, header_y + 64.0),
                    ],
                    Stroke::new(1.0_f32, p.outline),
                );
                let pad = if compact { 16.0 } else { 24.0 };
                let content = box_at(
                    o,
                    rail + pad,
                    header_y + 64.0 + pad,
                    size.x - rail - 2.0 * pad,
                    size.y - header_y - 64.0 - 2.0 * pad,
                );
                let mut body = ui.new_child(
                    egui::UiBuilder::new()
                        .max_rect(content)
                        .layout(egui::Layout::top_down(egui::Align::Min)),
                );
                body.set_clip_rect(content);
                self.settings_body(&mut body);
            });
    }
}
impl App {
    pub(super) fn exact_footer(&mut self, ui: &mut egui::Ui) {
        let p = self.palette;
        let (rect, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 56.0), Sense::hover());
        let avatar = Rect::from_min_size(rect.min + Vec2::new(4.0, 8.0), Vec2::splat(36.0));
        ui.painter().rect_filled(avatar, 11.0, p.high);
        let initial = self
            .account_label
            .as_ref()
            .and_then(|a| a.0.chars().next())
            .unwrap_or('A');
        ui.painter().text(
            avatar.center(),
            Align2::CENTER_CENTER,
            initial.to_string(),
            FontId::new(16.0, FontFamily::Name("aw-semibold".into())),
            p.accent,
        );
        if let Some(face) = ui
            .ctx()
            .data(|memory| memory.get_temp::<egui::TextureHandle>(egui::Id::new("account-face")))
        {
            egui::Image::new(&face)
                .corner_radius(11.0)
                .paint_at(ui, avatar);
        }
        raw_text(
            ui,
            rect.min + Vec2::new(50.0, 10.0),
            &self
                .account_label
                .as_ref()
                .map(|a| a.0.clone())
                .unwrap_or_else(|| self.tr("Аккаунты")),
            14.0,
            "aw-semibold",
            p.text,
        );
        text(
            ui,
            rect.min + Vec2::new(50.0, 30.0),
            &self.tr(self
                .account_label
                .as_ref()
                .map(|a| a.1.as_str())
                .unwrap_or("Не выбран")),
            11.0,
            "aw-semibold",
            p.muted,
        );
        let gear = Rect::from_center_size(
            rect.right_center() - Vec2::new(18.0, 0.0),
            Vec2::splat(30.0),
        );
        if let Some(icon) = self.nav_icons.get("settings") {
            ui.painter().image(
                icon.id(),
                Rect::from_center_size(gear.center(), Vec2::splat(18.0)),
                Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                p.muted,
            );
        }
        if ui
            .interact(gear, ui.id().with("footer-settings"), Sense::click())
            .clicked()
        {
            self.settings_open = true;
        }
        let account = Rect::from_min_max(rect.min, egui::pos2(gear.left(), rect.bottom()));
        if ui
            .interact(account, ui.id().with("footer-account"), Sense::click())
            .clicked()
        {
            self.navigate(Screen::Accounts);
        }
    }
}
