use super::*;
use egui::{Align2, FontId, Pos2, Rect, Sense, Stroke};
const PAGE: u32 = 20;
const CARD_H: f32 = 243.0;
const GAP: f32 = 12.0;
fn font(size: f32, bold: bool) -> FontId {
    FontId::new(
        size,
        if bold {
            FontFamily::Name("aw-semibold".into())
        } else {
            FontFamily::Proportional
        },
    )
}
fn width(ui: &egui::Ui, text: &str, size: f32, bold: bool) -> f32 {
    ui.painter()
        .layout_no_wrap(text.to_owned(), font(size, bold), Color32::WHITE)
        .size()
        .x
}
fn draw_text(ui: &egui::Ui, pos: Pos2, text: &str, size: f32, bold: bool, color: Color32) {
    ui.painter()
        .text(pos, Align2::LEFT_TOP, text, font(size, bold), color);
}
fn clipped(
    ui: &egui::Ui,
    rect: Rect,
    text: &str,
    size: f32,
    bold: bool,
    color: Color32,
    lines: usize,
) {
    let mut job = egui::text::LayoutJob::simple(
        text.to_owned(),
        font(size, bold),
        color,
        rect.width().max(0.0),
    );
    job.wrap.max_rows = lines;
    job.wrap.break_anywhere = false;
    job.wrap.overflow_character = Some('…');
    job.sections[0].format.line_height = Some(if size == 13.0 { 18.0 } else { 20.0 });
    let galley = ui.fonts(|fonts| fonts.layout_job(job));
    ui.painter()
        .with_clip_rect(rect.intersect(ui.clip_rect()))
        .galley(rect.min, galley, color);
}
fn chip(
    ui: &mut egui::Ui,
    id: impl std::hash::Hash,
    rect: Rect,
    text: &str,
    selected: bool,
    p: Palette,
) -> egui::Response {
    let response = ui.interact(rect, ui.id().with(id), Sense::click());
    ui.painter().rect_filled(
        rect,
        6.0,
        if selected || response.hovered() {
            p.high
        } else {
            p.panel
        },
    );
    if selected {
        ui.painter().rect_stroke(
            rect,
            6.0,
            Stroke::new(1.0_f32, p.accent.gamma_multiply(0.35)),
            egui::StrokeKind::Inside,
        );
    }
    ui.painter().text(
        rect.center(),
        Align2::CENTER_CENTER,
        text,
        font(14.0, true),
        if selected { p.accent } else { p.text },
    );
    response
}
fn button(
    ui: &mut egui::Ui,
    id: impl std::hash::Hash,
    rect: Rect,
    text: &str,
    fill: Color32,
    color: Color32,
    enabled: bool,
    p: Palette,
) -> egui::Response {
    let response = ui.interact(
        rect,
        ui.id().with(id),
        if enabled {
            Sense::click()
        } else {
            Sense::hover()
        },
    );
    ui.painter().rect_filled(
        rect,
        6.0,
        if !enabled {
            p.high
        } else if response.hovered() {
            fill.gamma_multiply(1.08)
        } else {
            fill
        },
    );
    ui.painter().text(
        rect.center(),
        Align2::CENTER_CENTER,
        text,
        font(14.0, true),
        if enabled {
            color
        } else {
            p.muted.gamma_multiply(0.55)
        },
    );
    response
}
fn count(value: u64) -> String {
    if value >= 1_000_000 {
        let value = format!("{:.1}", value as f64 / 1_000_000.0).replace('.', ",");
        format!("{} млн", value.trim_end_matches(",0"))
    } else if value >= 1000 {
        format!("{} тыс.", value / 1000)
    } else {
        value.to_string()
    }
}
fn columns(width: f32) -> usize {
    if width >= 1000.0 {
        4
    } else if width >= 500.0 {
        2
    } else {
        1
    }
}
fn page_count(total: u64) -> u32 {
    total.div_ceil(PAGE as u64).max(1).min(u32::MAX as u64) as u32
}
impl App {
    pub(super) fn start_catalog_search(&mut self, ctx: &egui::Context) {
        self.catalog_request = None;
        self.catalog_due = None;
        self.catalog_scroll_reset = true;
        if self.catalog_installed {
            return;
        }
        let query = self.catalog_query.clone();
        let kind = self.catalog_kind;
        let offset = self.catalog_offset;
        let curse = self.catalog_curseforge;
        let sort = self.catalog_sort;
        let compatible = if kind == "modpack" {
            None
        } else {
            self.catalog_target
                .as_ref()
                .or(self.selected.as_ref())
                .and_then(|key| self.builds.iter().find(|b| &b.key() == key))
                .map(|b| (b.version_id.clone(), b.loader.to_lowercase()))
        };
        let key = format!("{curse}\0{kind}\0{sort}\0{query}\0{offset}\0{compatible:?}");
        self.catalog_request_key = key.clone();
        if let Some((time, page)) = self
            .catalog_cache
            .get(&key)
            .filter(|(time, _)| time.elapsed() < std::time::Duration::from_secs(300))
        {
            let _ = time;
            self.catalog_page = Some(page.clone());
            return;
        }
        self.catalog_page = None;
        let (tx, rx) = std::sync::mpsc::channel();
        let repaint = ctx.clone();
        std::thread::spawn(move || {
            let result = catalog::search(
                &query,
                kind,
                offset,
                curse,
                compatible.as_ref().map(|v| v.0.as_str()),
                compatible.as_ref().map(|v| v.1.as_str()),
                sort,
            );
            let _ = tx.send(result);
            repaint.request_repaint();
        });
        self.catalog_request = Some(rx);
    }
    pub(super) fn catalog_page(&mut self, ui: &mut egui::Ui) {
        if self.catalog_target.is_none() {
            self.catalog_target = self.selected.clone();
        }
        if let Some(result) = self
            .catalog_request
            .as_ref()
            .map(|request| request.try_recv())
        {
            match result {
                Ok(Ok(page)) => {
                    self.catalog_request = None;
                    if self.catalog_cache.len() >= 8 {
                        self.catalog_cache.clear();
                    }
                    self.catalog_cache.insert(
                        self.catalog_request_key.clone(),
                        (std::time::Instant::now(), page.clone()),
                    );
                    self.catalog_page = Some(page);
                    self.message = None;
                }
                Ok(Err(error)) => {
                    self.catalog_request = None;
                    self.message = Some(format!("Не удалось загрузить каталог: {error}"));
                }
                Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    self.catalog_request = None;
                    self.message = Some(self.tr("Не удалось загрузить каталог"));
                }
                _ => {}
            }
        }
        if let Some(due) = self.catalog_due {
            if std::time::Instant::now() >= due {
                self.start_catalog_search(&ui.ctx().clone());
            } else {
                ui.ctx().request_repaint_after(
                    due.saturating_duration_since(std::time::Instant::now()),
                );
            }
        }
        let p = self.palette;
        let (canvas, _) = ui.allocate_exact_size(
            Vec2::new(ui.available_width(), ui.available_height()),
            Sense::hover(),
        );
        let origin = canvas.min;
        let w = canvas.width();
        let right = canvas.right();
        let mut reload = false;
        ui.painter().text(
            origin + Vec2::new(0.0, 22.0),
            Align2::LEFT_CENTER,
            self.tr("Каталог"),
            FontId::new(18.0, FontFamily::Name("aw-bold".into())),
            p.text,
        );
        let target = self
            .catalog_target
            .as_ref()
            .and_then(|key| self.builds.iter().find(|build| &build.key() == key))
            .cloned();
        if let Some(target) = &target {
            let text = self.tr("Для {0}").replace("{0}", &target.name);
            let tw = width(ui, &text, 14.0, true) + 66.0;
            let rect =
                Rect::from_min_size(egui::pos2(right - tw, origin.y + 1.0), Vec2::new(tw, 44.0));
            let response = self.icon_button_response(
                ui,
                "catalog-target",
                rect,
                &text,
                "chevron-down",
                p.high,
                p.text,
            );
            let popup = ui.id().with("catalog-target-menu");
            if response.clicked() {
                ui.memory_mut(|memory| memory.toggle_popup(popup));
            }
            let builds = self.builds.clone();
            egui::popup_below_widget(
                ui,
                popup,
                &response,
                egui::PopupCloseBehavior::CloseOnClickOutside,
                |ui| {
                    for build in builds {
                        if ui
                            .selectable_label(
                                self.catalog_target.as_ref() == Some(&build.key()),
                                &build.name,
                            )
                            .clicked()
                        {
                            self.catalog_target = Some(build.key());
                            self.catalog_world.clear();
                            if self.catalog_kind != "modpack" || self.catalog_installed {
                                reload = true;
                            }
                            ui.memory_mut(|memory| memory.close_popup());
                        }
                    }
                },
            );
        }
        let tabs = Rect::from_min_size(origin + Vec2::new(0.0, 50.0), Vec2::new(w, 44.0));
        let mut tabs_ui = ui.new_child(egui::UiBuilder::new().max_rect(tabs));
        tabs_ui.set_clip_rect(tabs);
        egui::ScrollArea::horizontal()
            .id_salt("catalog-tabs")
            .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden)
            .show(&mut tabs_ui, |ui| {
                let mut x = ui.cursor().left();
                for (label, kind, installed) in [
                    ("Модпаки", "modpack", false),
                    ("Моды", "mod", false),
                    ("Шейдеры", "shader", false),
                    ("Ресурспаки", "resourcepack", false),
                    ("Датапаки", "datapack", false),
                    ("Установленные", "mod", true),
                ] {
                    let text = self.tr(label);
                    let tw = width(ui, &text, 14.0, true) + 28.0;
                    let rect = Rect::from_min_size(egui::pos2(x, tabs.top()), Vec2::new(tw, 44.0));
                    let selected = self.catalog_installed == installed
                        && (installed || self.catalog_kind == kind);
                    ui.painter().text(
                        rect.center(),
                        Align2::CENTER_CENTER,
                        text,
                        font(14.0, true),
                        if selected { p.text } else { p.muted },
                    );
                    if ui
                        .interact(
                            rect,
                            ui.id().with(("catalog-tab", kind, installed)),
                            Sense::click(),
                        )
                        .clicked()
                        && !selected
                    {
                        self.catalog_kind = kind;
                        self.catalog_installed = installed;
                        self.catalog_offset = 0;
                        self.catalog_query.clear();
                        self.catalog_page = None;
                        reload = true;
                    }
                    x += tw + 8.0;
                }
                if !self.catalog_installed {
                    for (name, curse) in [("Modrinth", false), ("CurseForge", true)] {
                        let tw = width(ui, name, 14.0, true) + 32.0;
                        let rect = Rect::from_min_size(
                            egui::pos2(x, tabs.top() + 7.0),
                            Vec2::new(tw, 30.0),
                        );
                        if chip(
                            ui,
                            ("provider", curse),
                            rect,
                            name,
                            self.catalog_curseforge == curse,
                            p,
                        )
                        .clicked()
                            && self.catalog_curseforge != curse
                        {
                            self.catalog_curseforge = curse;
                            if curse && self.catalog_sort == "follows" {
                                self.catalog_sort = "downloads";
                            }
                            self.catalog_offset = 0;
                            reload = true;
                        }
                        x += tw + 8.0;
                    }
                }
                ui.allocate_exact_size(Vec2::new(x - tabs.left(), 44.0), Sense::hover());
            });
        let mut content_y = 100.0;
        if self.catalog_kind != "modpack" || self.catalog_installed {
            if let Some(target) = &target {
                if self.content_target.as_ref() != Some(&target.key()) {
                    self.refresh_content_details(target);
                }
            }
            let updates: Vec<_> = self
                .content
                .iter()
                .filter(|file| {
                    self.content_metadata
                        .get(file.relative.trim_end_matches(".disabled"))
                        .is_some_and(|item| !item.update.is_empty())
                })
                .cloned()
                .collect();
            let row_height = if self.catalog_installed && !updates.is_empty() {
                44.0
            } else {
                36.0
            };
            let row =
                Rect::from_min_size(origin + Vec2::new(0.0, content_y), Vec2::new(w, row_height));
            if let Some(target) = &target {
                draw_text(
                    ui,
                    row.min + Vec2::new(0.0, 9.0),
                    &self.tr("Для {0}").replace("{0}", &target.name),
                    13.0,
                    false,
                    p.muted,
                );
                if self.content_target.as_ref() != Some(&target.key()) {
                    self.refresh_content_details(target);
                }
                let folder =
                    Rect::from_min_size(row.right_top() - Vec2::new(36.0, 0.0), Vec2::splat(36.0));
                self.draw_icon(ui, "folder", folder.shrink(8.0), p.muted);
                if self.catalog_installed && !updates.is_empty() {
                    let text = self
                        .tr("Обновить все · {0}")
                        .replace("{0}", &updates.len().to_string());
                    let tw = width(ui, &text, 14.0, true) + 66.0;
                    let rect = Rect::from_min_size(
                        egui::pos2(folder.left() - 8.0 - tw, row.top()),
                        Vec2::new(tw, 44.0),
                    );
                    if self.icon_button(
                        ui,
                        "catalog-update-all",
                        rect,
                        &text,
                        "refresh",
                        p.high,
                        p.text,
                    ) {
                        for file in &updates {
                            self.downloads.enqueue(self.tr("Обновить"),serde_json::json!({"op":"updateItem","target":target.key(),"kind":content_type(file.category),"file":file.relative}));
                        }
                    }
                }
                if ui
                    .interact(folder, ui.id().with("catalog-open-folder"), Sense::click())
                    .clicked()
                {
                    if let Some(root) = library::build_dir(
                        &self.game_root,
                        &target.id,
                        target.directory_name.as_deref(),
                        target.pack,
                    ) {
                        let dir = if self.catalog_installed {
                            ""
                        } else {
                            match self.catalog_kind {
                                "mod" => "mods",
                                "shader" => "shaderpacks",
                                "resourcepack" => "resourcepacks",
                                _ => "",
                            }
                        };
                        if let Err(error) = files::show_folder(&root, Path::new(dir)) {
                            self.message = Some(error.to_string());
                        }
                    }
                }
            }
            content_y += row_height + 4.0;
        }
        if self.catalog_installed {
            self.catalog_request = None;
            self.catalog_due = None;
            if let Some(target) = &target {
                self.catalog_installed_list(
                    ui,
                    Rect::from_min_max(origin + Vec2::new(0.0, content_y), canvas.max),
                    target,
                );
            } else {
                clipped(
                    ui,
                    Rect::from_min_size(
                        origin + Vec2::new(24.0, content_y + 24.0),
                        Vec2::new(w - 48.0, 80.0),
                    ),
                    &self.tr("Выбери версию или сборку на экране «Играть»."),
                    14.0,
                    false,
                    p.muted,
                    3,
                );
            }
        } else {
            let missing_target = self.catalog_kind != "modpack" && target.is_none();
            let incompatible = self.catalog_kind == "mod"
                && target
                    .as_ref()
                    .is_some_and(|target| target.loader == "VANILLA");
            let shaders_incompatible = self.catalog_kind == "shader"
                && target
                    .as_ref()
                    .is_some_and(|target| !matches!(target.loader.as_str(), "FABRIC" | "NEOFORGE"));
            if shaders_incompatible {
                clipped(ui,Rect::from_min_size(origin+Vec2::new(24.0,content_y+24.0),Vec2::new(w-48.0,80.0)),&self.tr("Шейдеры работают через Iris, а для Forge его нет. Выбери версию с Fabric или NeoForge."),14.0,false,p.muted,3);
                return;
            }
            if missing_target || incompatible {
                draw_text(ui,origin+Vec2::new(14.0,content_y+18.0),&self.tr(if incompatible{"Для модов сначала выбери или создай сборку с Fabric, Forge, NeoForge или Quilt."}else{"Выбери версию или сборку на экране «Играть»."}),14.0,false,p.muted);
                return;
            }
            let search_h = if self.catalog_kind == "modpack" {
                34.0
            } else {
                40.0
            };
            let search =
                Rect::from_min_size(origin + Vec2::new(0.0, content_y), Vec2::new(w, search_h));
            ui.painter().rect_filled(search, 6.0, p.high);
            ui.painter().rect_stroke(
                search,
                6.0,
                Stroke::new(1.0_f32, p.outline.gamma_multiply(0.75)),
                egui::StrokeKind::Inside,
            );
            self.draw_icon(
                ui,
                "search",
                Rect::from_center_size(
                    search.left_center() + Vec2::new(27.0, 0.0),
                    Vec2::splat(18.0),
                ),
                p.muted,
            );
            let hint = self.tr(match self.catalog_kind {
                "mod" => "Найти мод: Sodium, JEI, Xaero's Minimap…",
                "shader" => "Найти шейдер: Complementary, BSL, Photon…",
                "resourcepack" => "Найти ресурспак: Fresh Animations, Faithful…",
                "datapack" => "Найти датапак: Terralith, Incendium…",
                _ => "Найти сборку: Fabulously Optimized, Cobblemon…",
            });
            let input = Rect::from_min_max(
                search.min + Vec2::new(46.0, (search_h - 20.0) * 0.5),
                search.max
                    - Vec2::new(
                        if self.catalog_query.is_empty() {
                            12.0
                        } else {
                            40.0
                        },
                        2.0,
                    ),
            );
            let mut child = ui.new_child(egui::UiBuilder::new().max_rect(input));
            child.set_clip_rect(search);
            let response = child.add_sized(
                input.size(),
                egui::TextEdit::singleline(&mut self.catalog_query)
                    .frame(false)
                    .margin(egui::Margin::ZERO)
                    .font(font(14.0, false))
                    .hint_text(hint),
            );
            if response.changed() {
                self.catalog_request = None;
                self.catalog_page = None;
                self.catalog_offset = 0;
                self.catalog_due =
                    Some(std::time::Instant::now() + std::time::Duration::from_millis(350));
                ui.ctx()
                    .request_repaint_after(std::time::Duration::from_millis(350));
            }
            if response.lost_focus() && ui.input(|input| input.key_pressed(egui::Key::Enter)) {
                reload = true;
            }
            if !self.catalog_query.is_empty() {
                let clear = Rect::from_center_size(
                    search.right_center() - Vec2::new(20.0, 0.0),
                    Vec2::new(36.0, search_h),
                );
                let center = clear.center();
                for direction in [-1.0, 1.0] {
                    ui.painter().line_segment(
                        [
                            center + Vec2::new(-4.0, -4.0 * direction),
                            center + Vec2::new(4.0, 4.0 * direction),
                        ],
                        Stroke::new(1.5_f32, p.muted),
                    );
                }
                if ui
                    .interact(clear, ui.id().with("catalog-clear-search"), Sense::click())
                    .clicked()
                {
                    self.catalog_query.clear();
                    self.catalog_offset = 0;
                    reload = true;
                }
            }
            content_y += search_h + 4.0;
            let sort_rect =
                Rect::from_min_size(origin + Vec2::new(0.0, content_y), Vec2::new(w, 30.0));
            let mut sort_ui = ui.new_child(egui::UiBuilder::new().max_rect(sort_rect));
            sort_ui.set_clip_rect(sort_rect);
            egui::ScrollArea::horizontal()
                .id_salt("catalog-sort")
                .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden)
                .show(&mut sort_ui, |ui| {
                    let mut x = ui.cursor().left();
                    for (sort, label) in [
                        ("downloads", "По загрузкам"),
                        ("relevance", "По совпадению"),
                        ("follows", "По подписчикам"),
                        ("updated", "По обновлению"),
                    ] {
                        if self.catalog_curseforge && sort == "follows" {
                            continue;
                        }
                        let text = self.tr(label);
                        let tw = width(ui, &text, 14.0, true) + 32.0;
                        let rect = Rect::from_min_size(
                            egui::pos2(x, sort_rect.top()),
                            Vec2::new(tw, 30.0),
                        );
                        if chip(
                            ui,
                            ("catalog-sort", sort),
                            rect,
                            &text,
                            self.catalog_sort == sort,
                            p,
                        )
                        .clicked()
                            && self.catalog_sort != sort
                        {
                            self.catalog_sort = sort;
                            self.catalog_offset = 0;
                            reload = true;
                        }
                        x += tw + 6.0;
                    }
                    let count = self
                        .catalog_page
                        .as_ref()
                        .map(|page| page.total_hits)
                        .unwrap_or(0);
                    let text = self.tr("Найдено: {0}").replace("{0}", &count.to_string());
                    draw_text(
                        ui,
                        egui::pos2(x, sort_rect.top() + 7.0),
                        &text,
                        13.0,
                        false,
                        p.muted,
                    );
                    ui.allocate_exact_size(
                        Vec2::new(x - sort_rect.left() + width(ui, &text, 13.0, false), 30.0),
                        Sense::hover(),
                    );
                });
            content_y += 34.0;
            let footer = Rect::from_min_size(
                egui::pos2(origin.x, canvas.bottom() - 44.0),
                Vec2::new(w, 44.0),
            );
            let viewport = Rect::from_min_max(
                origin + Vec2::new(0.0, content_y),
                egui::pos2(right, (footer.top() - 8.0).max(origin.y + content_y)),
            );
            let busy = self.catalog_request.is_some() || self.catalog_due.is_some();
            if busy {
                draw_text(
                    ui,
                    viewport.min + Vec2::new(14.0, 18.0),
                    &self.tr("Ищу в каталоге…"),
                    14.0,
                    false,
                    p.muted,
                );
            } else if let Some(page) = &self.catalog_page {
                let hits = page.hits.clone();
                let count = page.total_hits;
                if hits.is_empty() {
                    draw_text(
                        ui,
                        viewport.min + Vec2::new(14.0, 18.0),
                        &self.tr("Ничего не нашлось. Попробуй другое название."),
                        14.0,
                        false,
                        p.muted,
                    );
                }
                let cols = columns(viewport.width());
                let card_w = (viewport.width() - GAP * (cols - 1) as f32) / cols as f32;
                let mut list = ui.new_child(egui::UiBuilder::new().max_rect(viewport));
                list.set_clip_rect(viewport);
                list.spacing_mut().item_spacing = Vec2::ZERO;
                let mut scroll = egui::ScrollArea::vertical()
                    .id_salt("catalog-grid")
                    .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden)
                    .auto_shrink([false, false]);
                if self.catalog_scroll_reset {
                    scroll = scroll.vertical_scroll_offset(0.0);
                    self.catalog_scroll_reset = false;
                }
                scroll.show_rows(
                    &mut list,
                    CARD_H + GAP,
                    hits.len().div_ceil(cols),
                    |ui, range| {
                        for row in range {
                            let (rect, _) = ui.allocate_exact_size(
                                Vec2::new(viewport.width(), CARD_H + GAP),
                                Sense::hover(),
                            );
                            for col in 0..cols {
                                if let Some(hit) = hits.get(row * cols + col) {
                                    self.catalog_card(
                                        ui,
                                        Rect::from_min_size(
                                            rect.min + Vec2::new(col as f32 * (card_w + GAP), 0.0),
                                            Vec2::new(card_w, CARD_H),
                                        ),
                                        hit,
                                    );
                                }
                            }
                        }
                    },
                );
                let mut pagination_ui = ui.new_child(egui::UiBuilder::new().max_rect(footer));
                pagination_ui.set_clip_rect(footer);
                egui::ScrollArea::horizontal()
                    .id_salt("catalog-pagination")
                    .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden)
                    .show(&mut pagination_ui, |ui| {
                        let footer = Rect::from_min_size(ui.cursor().min, Vec2::new(w, 44.0));
                        let current = self.catalog_offset / PAGE;
                        let pages = page_count(count);
                        let first = current.saturating_sub(2);
                        let mut x = footer.left();
                        let previous =
                            Rect::from_min_size(egui::pos2(x, footer.top()), Vec2::new(58.0, 44.0));
                        if button(
                            ui,
                            "catalog-previous",
                            previous,
                            "←",
                            p.high,
                            p.text,
                            current > 0,
                            p,
                        )
                        .clicked()
                            && current > 0
                        {
                            self.catalog_offset = (current - 1) * PAGE;
                            reload = true;
                        }
                        x += 64.0;
                        for page in first..(first + 5).min(pages) {
                            let text = (page + 1).to_string();
                            let tw = width(ui, &text, 14.0, true) + 32.0;
                            let rect = Rect::from_min_size(
                                egui::pos2(x, footer.top() + 7.0),
                                Vec2::new(tw, 30.0),
                            );
                            if chip(ui, ("catalog-page", page), rect, &text, current == page, p)
                                .clicked()
                                && current != page
                            {
                                self.catalog_offset = page * PAGE;
                                reload = true;
                            }
                            x += tw + 6.0;
                        }
                        let text = format!("/ {pages}");
                        let tw = width(ui, &text, 14.0, false);
                        ui.painter().text(
                            egui::pos2(x, footer.center().y),
                            Align2::LEFT_CENTER,
                            text,
                            font(14.0, false),
                            p.muted,
                        );
                        x += tw + 10.0;
                        let next =
                            Rect::from_min_size(egui::pos2(x, footer.top()), Vec2::new(58.0, 44.0));
                        if button(
                            ui,
                            "catalog-next",
                            next,
                            "→",
                            p.high,
                            p.text,
                            current + 1 < pages,
                            p,
                        )
                        .clicked()
                            && current + 1 < pages
                        {
                            self.catalog_offset = (current + 1) * PAGE;
                            reload = true;
                        }
                        ui.allocate_exact_size(
                            Vec2::new(x - footer.left() + 58.0, 44.0),
                            Sense::hover(),
                        );
                    });
            }
        }
        if reload {
            self.catalog_scroll_reset = true;
            if self.catalog_installed {
                if let Some(target) = &target {
                    self.refresh_content_details(target);
                }
            } else {
                self.start_catalog_search(&ui.ctx().clone());
            }
        }
    }
    fn catalog_card(&mut self, ui: &mut egui::Ui, rect: Rect, hit: &catalog::Hit) {
        let p = self.palette;
        ui.painter().rect_filled(rect, 6.0, p.panel);
        ui.painter().rect_stroke(
            rect,
            6.0,
            Stroke::new(1.0_f32, p.outline),
            egui::StrokeKind::Inside,
        );
        let o = rect.min + Vec2::splat(14.0);
        let inner = rect.width() - 28.0;
        let icon = Rect::from_min_size(o, Vec2::splat(42.0));
        ui.painter().rect_filled(icon, 6.0, p.high);
        if let Some(image) = hit
            .icon_url
            .as_deref()
            .and_then(|url| self.remote_images.get(ui.ctx(), url))
        {
            egui::Image::new(&image)
                .corner_radius(4.0)
                .paint_at(ui, icon);
        } else {
            self.draw_icon(ui, "extension", icon.shrink(10.0), p.muted);
        }
        clipped(
            ui,
            Rect::from_min_size(o + Vec2::new(52.0, 0.0), Vec2::new(inner - 52.0, 20.0)),
            &hit.title,
            14.0,
            true,
            p.text,
            1,
        );
        clipped(
            ui,
            Rect::from_min_size(o + Vec2::new(52.0, 20.0), Vec2::new(inner - 52.0, 18.0)),
            &format!("by {}", hit.author),
            13.0,
            false,
            p.muted,
            1,
        );
        let description = if hit.description.is_empty() {
            self.tr("Описание отсутствует")
        } else {
            hit.description.clone()
        };
        clipped(
            ui,
            Rect::from_min_size(o + Vec2::new(0.0, 52.0), Vec2::new(inner, 36.0)),
            &description,
            13.0,
            false,
            if p.dark {
                Color32::from_rgb(196, 206, 192)
            } else {
                Color32::from_rgb(77, 91, 98)
            },
            2,
        );
        let mut x = o.x;
        for category in hit.categories.iter().take(2) {
            let tw = width(ui, category, 12.0, true) + 20.0;
            let tag = Rect::from_min_size(
                egui::pos2(x, o.y + 98.0),
                Vec2::new(tw.min((rect.right() - 14.0 - x).max(0.0)), 24.0),
            );
            // Precompose the source UI's 14% tint in sRGB: egui otherwise
            // blends this translucent fill in linear space and makes it lighter.
            let channel =
                |base: u8, text: u8| (base as f32 * 0.86 + text as f32 * 0.14).round() as u8;
            ui.painter().rect_filled(
                tag,
                6.0,
                Color32::from_rgb(
                    channel(p.panel.r(), p.muted.r()),
                    channel(p.panel.g(), p.muted.g()),
                    channel(p.panel.b(), p.muted.b()),
                ),
            );
            clipped(
                ui,
                tag.shrink2(Vec2::new(10.0, 4.0)),
                category,
                12.0,
                true,
                p.muted,
                1,
            );
            x += tw + 4.0;
        }
        draw_text(
            ui,
            o + Vec2::new(0.0, 132.0),
            &format!("↓ {}", self.tr(&count(hit.downloads))),
            13.0,
            false,
            p.muted,
        );
        ui.painter().line_segment(
            [o + Vec2::new(0.0, 160.0), o + Vec2::new(inner, 160.0)],
            Stroke::new(1.0_f32, p.outline),
        );
        let action = Rect::from_min_size(o + Vec2::new(0.0, 171.0), Vec2::new(inner, 44.0));
        let pending = self
            .downloads
            .items
            .iter()
            .any(|item| item.status.pending() && item.request["project"] == hit.project_id);
        let installed_pack = self
            .builds
            .iter()
            .find(|build| {
                build.pack && build.project_id.as_deref() == Some(hit.project_id.as_str())
            })
            .cloned();
        let installed_content = self.catalog_kind != "modpack"
            && self.catalog_kind != "datapack"
            && self
                .content_metadata
                .values()
                .any(|item| item.project == hit.project_id);
        if pending || installed_content {
            ui.painter().text(
                action.center(),
                Align2::CENTER_CENTER,
                self.tr(if pending {
                    "В очереди"
                } else {
                    "Установлен"
                }),
                font(13.0, false),
                if pending { p.muted } else { p.accent },
            );
        } else {
            let text = self.tr(if installed_pack.is_some() {
                "Играть"
            } else {
                "Установить"
            });
            if button(
                ui,
                ("catalog-install", &hit.project_id),
                action,
                &text,
                p.accent,
                p.on_accent,
                true,
                p,
            )
            .clicked()
            {
                if let Some(build) = installed_pack {
                    self.open_backend(Some(&build));
                } else if self.catalog_kind == "modpack" {
                    self.open_project(hit.clone());
                    self.project_tab = 1;
                } else {
                    self.install_catalog_hit(hit.clone());
                }
            }
        }
        let project = Rect::from_min_max(
            rect.min + Vec2::splat(1.0),
            egui::pos2(rect.right() - 1.0, action.top() - 10.0),
        );
        if ui
            .interact(
                project,
                ui.id().with(("catalog-project", &hit.project_id)),
                Sense::click(),
            )
            .clicked()
        {
            self.open_project(hit.clone());
        }
    }

    fn catalog_installed_list(&mut self, ui: &mut egui::Ui, viewport: Rect, target: &Build) {
        let p = self.palette;
        ui.painter().rect_filled(viewport, 8.0, p.panel);
        ui.painter().rect_stroke(
            viewport,
            8.0,
            Stroke::new(1.0_f32, p.outline),
            egui::StrokeKind::Inside,
        );
        if self.content_engine.is_some() && self.content_metadata.is_empty() {
            ui.painter().text(
                viewport.center(),
                Align2::CENTER_CENTER,
                self.tr("Смотрю, что стоит в {0}…")
                    .replace("{0}", &target.name),
                font(14.0, false),
                p.muted,
            );
            return;
        }
        if self.content.is_empty() {
            clipped(
                ui,
                Rect::from_min_size(
                    viewport.center() - Vec2::new((viewport.width() - 48.0) * 0.5, 30.0),
                    Vec2::new(viewport.width() - 48.0, 60.0),
                ),
                &self
                    .tr("Пока ничего не установлено. Найди моды, шейдеры и ресурспаки в каталоге."),
                14.0,
                false,
                p.muted,
                3,
            );
            return;
        }
        let mut list = ui.new_child(egui::UiBuilder::new().max_rect(viewport));
        list.set_clip_rect(viewport);
        list.spacing_mut().item_spacing = Vec2::ZERO;
        let busy = self
            .downloads
            .items
            .iter()
            .any(|item| item.status.pending() && item.request["target"] == target.key());
        egui::ScrollArea::vertical().id_salt(("catalog-installed",target.key())).auto_shrink([false,false]).show(&mut list,|ui|{
     for kind in ["Моды","Шейдеры","Ресурспаки","Датапаки"]{
       let mut files:Vec<_>=self.content.iter().filter(|file|file.category==kind&&(kind!="Моды"||target.loader!="VANILLA")).cloned().collect();
       files.sort_by_key(|file|self.content_metadata.get(file.relative.trim_end_matches(".disabled")).map(|item|item.title.as_str()).unwrap_or(&file.name).to_lowercase());
       if files.is_empty(){continue;}
       let(header,_)=ui.allocate_exact_size(Vec2::new(ui.available_width(),30.0),Sense::hover());draw_text(ui,header.min+Vec2::new(14.0,14.0),&format!("{} · {}",self.tr(kind).to_uppercase(),files.len()),11.0,true,p.muted);
       for file in files{
         let metadata=self.content_metadata.get(file.relative.trim_end_matches(".disabled")).cloned().unwrap_or_default();let updated=!metadata.update.is_empty();
         let height=if updated{77.0}else{68.0};let(row,_)=ui.allocate_exact_size(Vec2::new(ui.available_width(),height),Sense::hover());
         let delete=Rect::from_center_size(egui::pos2(row.right()-36.0,row.center().y),Vec2::splat(36.0));let toggle=Rect::from_center_size(egui::pos2(delete.left()-30.0,row.center().y),Vec2::new(52.0,32.0));
         let update_text=self.tr("Обновить");let update_w=width(ui,&update_text,14.0,true)+66.0;let update_rect=Rect::from_min_size(egui::pos2(toggle.left()-8.0-update_w,row.center().y-22.0),Vec2::new(update_w,44.0));
         let end=if updated{update_rect.left()-12.0}else{toggle.left()-12.0};let icon=Rect::from_center_size(row.left_center()+Vec2::new(36.0,0.0),Vec2::splat(48.0));
         let color=if file.enabled{p.text}else{p.text.gamma_multiply(0.55)};let muted=if file.enabled{p.muted}else{p.muted.gamma_multiply(0.55)};
         if let Some(image)=(!metadata.icon.is_empty()).then(||self.remote_images.get(ui.ctx(),&metadata.icon)).flatten(){egui::Image::new(&image).corner_radius(4.0).tint(if file.enabled{Color32::WHITE}else{Color32::from_white_alpha(115)}).paint_at(ui,icon);}else{self.draw_icon(ui,"extension",icon.shrink(10.0),muted);}
         let title=if metadata.title.is_empty(){file.name.trim_end_matches(".disabled").trim_end_matches(".jar").trim_end_matches(".zip").to_owned()}else{metadata.title.clone()};
         clipped(ui,Rect::from_min_size(egui::pos2(row.left()+72.0,row.top()+if updated{10.0}else{14.0}),Vec2::new((end-row.left()-72.0).max(0.0),21.0)),&title,15.0,true,color,1);
         let byline=if metadata.version.is_empty(){file.name.clone()}else{format!("{} · {}",metadata.version,file.name)};
         clipped(ui,Rect::from_min_size(egui::pos2(row.left()+72.0,row.top()+if updated{31.0}else{35.0}),Vec2::new((end-row.left()-72.0).max(0.0),18.0)),&byline,13.0,false,muted,1);
         if updated {draw_text(ui,egui::pos2(row.left()+72.0,row.top()+49.0),&self.tr("Доступно обновление"),13.0,false,p.accent);if self.icon_button(ui,(&file.relative,"catalog-update"),update_rect,"Обновить","refresh",p.high,p.text)&&!busy{self.downloads.enqueue(self.tr("Обновить"),serde_json::json!({"op":"updateItem","target":target.key(),"kind":content_type(file.category),"file":file.relative}));}}
         if instance_content_ui::toggle(ui,&format!("catalog-toggle:{}:{}",target.key(),file.relative),toggle,file.enabled,!busy,p){self.downloads.enqueue(self.tr("Изменение состояния проекта"),serde_json::json!({"op":"toggle","target":target.key(),"kind":content_type(file.category),"file":file.relative,"enabled":!file.enabled}));}
         self.draw_icon(ui,"delete",delete.shrink(8.0),Color32::from_rgb(255,180,171));if ui.interact(delete,ui.id().with((&file.relative,"catalog-delete")),Sense::click()).clicked()&&!busy{self.content_delete=Some((target.key(),file.relative.clone(),content_type(file.category).into(),title.clone()));}
         let click=Rect::from_min_max(row.min,egui::pos2(end,row.bottom()));if !metadata.project.is_empty()&&ui.interact(click,ui.id().with((&file.relative,"installed-project")),Sense::click()).clicked(){self.catalog_kind=content_type(file.category);self.open_project(catalog::Hit{project_id:metadata.project,slug:String::new(),title,description:String::new(),author:String::new(),downloads:0,icon_url:Some(metadata.icon),categories:Vec::new()});}
       }
     }
   });
    }
    fn install_catalog_hit(&mut self, hit: catalog::Hit) {
        let request = serde_json::json!({"op":"install","project":hit.project_id,"title":hit.title,"slug":hit.slug,"kind":self.catalog_kind,"target":self.catalog_target.clone().unwrap_or_default(),"world":self.catalog_world});
        if self.catalog_kind == "datapack" && self.catalog_world.is_empty() {
            self.project_hit = Some(hit);
            self.catalog_datapack_request = Some(request);
            self.catalog_datapack_dialog = true;
            return;
        }
        self.downloads
            .enqueue(format!("Установка {}", hit.title), request);
    }
    pub(super) fn catalog_datapack_picker(&mut self, ctx: &egui::Context) {
        if !self.catalog_datapack_dialog {
            return;
        }
        let mut open = true;
        egui::Window::new(self.tr("Выбери мир для датапака"))
            .open(&mut open)
            .anchor(Align2::CENTER_CENTER, Vec2::ZERO)
            .collapsible(false)
            .default_width(520.0)
            .show(ctx, |ui| {
                ui.label(self.tr(
                    "Датапак устанавливается в выбранный мир. После установки открой мир заново",
                ));
                let worlds: Vec<_> = self
                    .catalog_target
                    .as_ref()
                    .and_then(|key| self.builds.iter().find(|build| &build.key() == key))
                    .and_then(|build| {
                        library::build_dir(
                            &self.game_root,
                            &build.id,
                            build.directory_name.as_deref(),
                            build.pack,
                        )
                    })
                    .map(|dir| {
                        std::fs::read_dir(dir.join("saves"))
                            .into_iter()
                            .flatten()
                            .flatten()
                            .filter(|entry| entry.path().join("level.dat").is_file())
                            .map(|entry| entry.file_name().to_string_lossy().into_owned())
                            .collect()
                    })
                    .unwrap_or_default();
                if worlds.is_empty() {
                    ui.label(self.tr("Сначала создай мир в этой сборке или перенеси существующий"));
                }
                for world in worlds {
                    if ui.button(&world).clicked() {
                        self.catalog_world = world;
                        self.catalog_datapack_dialog = false;
                        if let Some(mut request) = self.catalog_datapack_request.take() {
                            request["world"] =
                                serde_json::Value::String(self.catalog_world.clone());
                            let title = request["title"].as_str().unwrap_or("").to_owned();
                            self.downloads
                                .enqueue(format!("Установка {title}"), request);
                        } else if let Some(hit) = self.project_hit.clone() {
                            self.install_catalog_hit(hit);
                        }
                    }
                }
            });
        if !open {
            self.catalog_datapack_dialog = false;
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pagination_counts_partial_last_page() {
        assert_eq!(page_count(0), 1);
        assert_eq!(page_count(20), 1);
        assert_eq!(page_count(21), 2);
        assert_eq!(page_count(19108), 956);
    }
}
