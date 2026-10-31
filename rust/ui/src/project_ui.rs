use super::*;
use chrono::Datelike;
use egui::{Align2, FontId, Rect, Sense, Stroke};
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
fn soft(p: Palette) -> Color32 {
    if p.dark {
        Color32::from_rgb(196, 206, 192)
    } else {
        Color32::from_rgb(77, 91, 98)
    }
}
fn str_field<'a>(value: &'a serde_json::Value, key: &str) -> &'a str {
    value[key].as_str().unwrap_or("")
}
fn strings(value: &serde_json::Value, key: &str) -> Vec<String> {
    value[key]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|v| v.as_str().map(str::to_owned))
        .collect()
}
fn text_width(ui: &egui::Ui, text: &str, size: f32, bold: bool) -> f32 {
    ui.painter()
        .layout_no_wrap(text.to_owned(), font(size, bold), Color32::WHITE)
        .size()
        .x
}
fn label(
    ui: &egui::Ui,
    rect: Rect,
    text: &str,
    size: f32,
    bold: bool,
    color: Color32,
    rows: usize,
) {
    let mut job = egui::text::LayoutJob::simple(
        text.to_owned(),
        font(size, bold),
        color,
        rect.width().max(0.0),
    );
    job.wrap.max_rows = rows;
    job.wrap.break_anywhere = false;
    job.sections[0].format.line_height = Some(if size >= 20.0 {
        28.0
    } else if size == 15.0 {
        22.0
    } else {
        20.0
    });
    let galley = ui.fonts(|fonts| fonts.layout_job(job));
    ui.painter()
        .with_clip_rect(rect.intersect(ui.clip_rect()))
        .galley(rect.min, galley, color);
}
fn button(
    ui: &mut egui::Ui,
    id: impl std::hash::Hash,
    rect: Rect,
    text: &str,
    p: Palette,
    selected: bool,
    enabled: bool,
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
        if selected {
            p.high
        } else if response.hovered() {
            p.outline
        } else {
            p.high
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
        if !enabled {
            p.muted.gamma_multiply(0.5)
        } else if selected {
            p.accent
        } else {
            p.text
        },
    );
    response
}
fn flow(ui: &mut egui::Ui, text: &str, size: f32, bold: bool, color: Color32, line: f32) {
    let mut job = egui::text::LayoutJob::simple(
        text.to_owned(),
        font(size, bold),
        color,
        ui.available_width(),
    );
    job.sections[0].format.line_height = Some(line);
    ui.add(egui::Label::new(job).wrap().selectable(true));
}
fn section(ui: &mut egui::Ui, text: &str, p: Palette) {
    flow(ui, text, 15.0, true, p.text, 21.0);
    ui.add_space(8.0);
}
fn panel(ui: &mut egui::Ui, rect: Rect, p: Palette) {
    ui.painter().rect_filled(rect, 10.0, p.panel);
}
fn external_icon(ui: &egui::Ui, rect: Rect, color: Color32) {
    let s = rect.width() / 24.0;
    let point = |x: f32, y: f32| rect.min + Vec2::new(x * s, y * s);
    let stroke = Stroke::new(1.8_f32 * s, color);
    for (a, b) in [
        ((12., 4.), (4., 4.)),
        ((4., 4.), (4., 20.)),
        ((4., 20.), (20., 20.)),
        ((20., 20.), (20., 12.)),
        ((13., 3.), (21., 3.)),
        ((21., 3.), (21., 11.)),
        ((21., 3.), (10., 14.)),
    ] {
        ui.painter()
            .line_segment([point(a.0, a.1), point(b.0, b.1)], stroke);
    }
}
fn short_count(app: &App, value: u64) -> String {
    let text = if value >= 1_000_000 {
        format!(
            "{} млн",
            format!("{:.1}", value as f64 / 1_000_000.0).trim_end_matches(".0")
        )
    } else if value >= 1000 {
        format!("{} тыс.", value / 1000)
    } else {
        value.to_string()
    };
    app.tr(&text)
}
fn date_label(app: &App, iso: &str) -> String {
    let Some(date) =
        chrono::NaiveDate::parse_from_str(iso.get(..10).unwrap_or(iso), "%Y-%m-%d").ok()
    else {
        return iso.to_owned();
    };
    let months = [
        "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
    ];
    format!(
        "{} {}{}",
        date.day(),
        app.tr(months[date.month0() as usize]),
        if date.year() == chrono::Local::now().year() {
            String::new()
        } else {
            format!(" {}", date.year())
        }
    )
}
fn image_uv(size: Vec2, target: Vec2) -> Rect {
    let ratio = size.x / size.y;
    let target_ratio = target.x / target.y;
    if ratio > target_ratio {
        let pad = (1.0 - target_ratio / ratio) * 0.5;
        Rect::from_min_max(egui::pos2(pad, 0.0), egui::pos2(1.0 - pad, 1.0))
    } else {
        let pad = (1.0 - ratio / target_ratio) * 0.5;
        Rect::from_min_max(egui::pos2(0.0, pad), egui::pos2(1.0, 1.0 - pad))
    }
}
impl App {
    pub(super) fn open_project(&mut self, hit: catalog::Hit) {
        self.project_data = None;
        self.project_engine = None;
        self.project_tab = 0;
        self.project_game_filter.clear();
        self.project_loader_filter.clear();
        self.project_image_index = None;
        self.project_release = None;
        self.message = None;
        let request = serde_json::json!({"op":"project","project":hit.project_id,"kind":self.catalog_kind,"target":""});
        match self
            .backend
            .as_ref()
            .ok_or_else(|| "Не найден движок проекта".to_owned())
            .and_then(|backend| tasks::start(backend, &request, self.context.clone()))
        {
            Ok(engine) => self.project_engine = Some(engine),
            Err(error) => self.message = Some(error),
        }
        self.project_hit = Some(hit);
        self.navigate(Screen::Project);
    }
    fn project_allowed(&self) -> bool {
        self.catalog_kind == "modpack"
            || self
                .catalog_target
                .as_ref()
                .and_then(|key| self.builds.iter().find(|build| &build.key() == key))
                .is_some_and(|target| match self.catalog_kind {
                    "mod" => target.loader != "VANILLA",
                    "shader" => matches!(target.loader.as_str(), "FABRIC" | "QUILT" | "NEOFORGE"),
                    _ => true,
                })
    }
    fn compatible_release(&self, version: &serde_json::Value) -> bool {
        let files = version["files"].as_array().into_iter().flatten();
        if self.catalog_kind == "modpack" {
            return files.into_iter().any(|file| {
                let name = str_field(file, "filename");
                !str_field(file, "url").is_empty()
                    && if self.catalog_curseforge {
                        name.ends_with(".zip")
                    } else {
                        name.ends_with(".mrpack")
                    }
            });
        }
        if !files
            .into_iter()
            .any(|file| !str_field(file, "url").is_empty())
        {
            return false;
        }
        let Some(target) = self
            .catalog_target
            .as_ref()
            .and_then(|key| self.builds.iter().find(|build| &build.key() == key))
        else {
            return false;
        };
        if !strings(version, "game_versions").contains(&target.version_id) {
            return false;
        }
        let loaders = strings(version, "loaders");
        match self.catalog_kind {
            "shader" => loaders
                .iter()
                .any(|loader| matches!(loader.as_str(), "iris" | "optifine")),
            "resourcepack" => loaders.iter().any(|loader| loader == "minecraft"),
            "datapack" => loaders.iter().any(|loader| loader == "datapack"),
            _ => loaders.iter().any(|loader| {
                loader.eq_ignore_ascii_case(&target.loader)
                    || (target.loader == "QUILT" && loader == "fabric")
            }),
        }
    }
    fn installed_release(&self, hit: &catalog::Hit, version: &serde_json::Value) -> bool {
        if self.catalog_kind == "modpack" {
            self.builds.iter().any(|build| {
                build.pack
                    && build.project_id.as_deref() == Some(hit.project_id.as_str())
                    && build.release_id.as_deref() == Some(str_field(version, "id"))
            })
        } else {
            self.content_metadata.values().any(|item| {
                item.project == hit.project_id
                    && !item.version.is_empty()
                    && item.version == str_field(version, "version_number")
            })
        }
    }
    fn project_base(&self, hit: &catalog::Hit) -> String {
        catalog::project_url(hit, self.catalog_kind, self.catalog_curseforge)
            .unwrap_or_else(|| "https://modrinth.com".into())
    }
    pub(super) fn project_page(&mut self, ui: &mut egui::Ui) {
        let events: Vec<_> = self
            .project_engine
            .as_ref()
            .map(|engine| engine.rx.try_iter().collect())
            .unwrap_or_default();
        for event in events {
            match event {
                tasks::Event::Data(event) if event["type"] == "done" => {
                    self.project_data = Some(event["data"].clone());
                    self.message = None;
                }
                tasks::Event::Data(event) if event["type"] == "failed" => {
                    self.message = event["data"].as_str().map(str::to_owned)
                }
                tasks::Event::Exited(_) => self.project_engine = None,
                _ => {}
            }
        }
        let Some(hit) = self.project_hit.clone() else {
            ui.label(self.tr("Проект больше не выбран"));
            return;
        };
        let p = self.palette;
        let (canvas, _) = ui.allocate_exact_size(
            Vec2::new(ui.available_width(), ui.available_height()),
            Sense::hover(),
        );
        let o = canvas.min + Vec2::new(0.0, 1.0);
        let w = canvas.width();
        let back_text = self.tr("Назад в каталог");
        let back = Rect::from_min_size(
            o,
            Vec2::new(text_width(ui, &back_text, 14.0, true) + 36.0, 44.0),
        );
        if button(ui, "project-back", back, &back_text, p, false, true).clicked() {
            self.navigate(Screen::Catalog);
            return;
        }
        let source = if self.catalog_curseforge {
            "CurseForge"
        } else {
            "Modrinth"
        };
        let open_text = self.tr("Открыть на {0}").replace("{0}", source);
        let open_width = text_width(ui, &open_text, 14.0, true) + 66.0;
        let open = Rect::from_min_size(
            egui::pos2(canvas.right() - open_width, o.y),
            Vec2::new(open_width, 44.0),
        );
        if button(ui, "project-website", open, "", p, false, true).clicked() {
            ui.ctx()
                .open_url(egui::OpenUrl::new_tab(self.project_base(&hit)));
        }
        let group_left = open.center().x - (text_width(ui, &open_text, 14.0, true) + 30.0) * 0.5;
        external_icon(
            ui,
            Rect::from_center_size(
                egui::pos2(group_left + 10.0, open.center().y),
                Vec2::splat(20.0),
            ),
            p.text,
        );
        ui.painter().text(
            egui::pos2(group_left + 30.0, open.center().y),
            Align2::LEFT_CENTER,
            open_text,
            font(14.0, true),
            p.text,
        );
        let data = self.project_data.clone();
        let empty = serde_json::Value::Null;
        let project = data.as_ref().map(|data| &data["project"]).unwrap_or(&empty);
        let allowed = self.project_allowed();
        let busy = self
            .downloads
            .items
            .iter()
            .any(|item| item.status.pending() && item.request["project"] == hit.project_id);
        let title = if str_field(project, "title").is_empty() {
            &hit.title
        } else {
            str_field(project, "title")
        };
        let description = if str_field(project, "description").is_empty() {
            &hit.description
        } else {
            str_field(project, "description")
        };
        let hero = Rect::from_min_size(o + Vec2::new(0.0, 58.0), Vec2::new(w, 116.0));
        panel(ui, hero, p);
        let icon = Rect::from_min_size(hero.min + Vec2::splat(20.0), Vec2::splat(76.0));
        ui.painter().rect_filled(icon, 4.0, p.high);
        let icon_url = project["icon_url"].as_str().or(hit.icon_url.as_deref());
        if let Some(image) = icon_url.and_then(|url| self.remote_images.get(ui.ctx(), url)) {
            egui::Image::new(&image)
                .corner_radius(4.0)
                .paint_at(ui, icon);
        } else {
            self.draw_icon(ui, "extension", icon.shrink(20.0), p.muted);
        }
        let installed = self
            .builds
            .iter()
            .any(|build| build.project_id.as_deref() == Some(hit.project_id.as_str()))
            || self
                .content_metadata
                .values()
                .any(|item| item.project == hit.project_id);
        let install_text = self.tr(if busy {
            "Ставлю…"
        } else if installed {
            "Выбрать версию"
        } else {
            "Установить"
        });
        let install_w = text_width(ui, &install_text, 14.0, true) + 66.0;
        let install = Rect::from_min_size(
            egui::pos2(hero.right() - 20.0 - install_w, hero.center().y - 22.0),
            Vec2::new(install_w, 44.0),
        );
        if allowed && !busy && data.is_some() {
            if self.icon_button(
                ui,
                "project-install",
                install,
                &install_text,
                "download",
                p.accent,
                p.on_accent,
            ) {
                self.project_tab = 1;
            }
        } else {
            button(
                ui,
                "project-install-disabled",
                install,
                &install_text,
                p,
                false,
                false,
            );
        }
        let left = icon.right() + 18.0;
        let text_w = (install.left() - 18.0 - left).max(0.0);
        label(
            ui,
            Rect::from_min_size(egui::pos2(left, hero.top() + 22.0), Vec2::new(text_w, 28.0)),
            title,
            22.0,
            true,
            p.text,
            1,
        );
        label(
            ui,
            Rect::from_min_size(egui::pos2(left, hero.top() + 56.0), Vec2::new(text_w, 40.0)),
            description,
            14.0,
            false,
            soft(p),
            1,
        );
        let downloads = project["downloads"].as_u64().unwrap_or(hit.downloads);
        let followers = project["followers"].as_u64().unwrap_or(0);
        let mut meta = vec![self
            .tr("{0} скачиваний")
            .replace("{0}", &short_count(self, downloads))];
        if !self.catalog_curseforge {
            meta.push(
                self.tr("{0} подписчиков")
                    .replace("{0}", &short_count(self, followers)),
            );
        }
        if !hit.author.is_empty() {
            meta.push(self.tr("от {0}").replace("{0}", &hit.author));
        }
        let mut x = left;
        for text in meta {
            let tw = text_width(ui, &text, 13.0, false);
            label(
                ui,
                Rect::from_min_size(
                    egui::pos2(x, hero.top() + 80.0),
                    Vec2::new(tw.min((install.left() - 18.0 - x).max(0.0)), 18.0),
                ),
                &text,
                13.0,
                false,
                p.muted,
                1,
            );
            x += tw + 16.0;
        }
        let mut body_y = 188.0;
        if !allowed || self.catalog_kind != "modpack" {
            let note = if !allowed {
                self.tr("Для установки сначала выбери совместимую сборку в каталоге")
            } else {
                let name = self
                    .catalog_target
                    .as_ref()
                    .and_then(|key| self.builds.iter().find(|build| &build.key() == key))
                    .map(|build| build.name.as_str())
                    .unwrap_or("");
                self.tr("Для {0}").replace("{0}", name)
            };
            label(
                ui,
                Rect::from_min_size(o + Vec2::new(0.0, body_y), Vec2::new(w, 18.0)),
                &note,
                13.0,
                false,
                if allowed {
                    p.muted
                } else {
                    Color32::from_rgb(238, 177, 109)
                },
                1,
            );
            body_y += 32.0;
        }
        let sidebar_width = if w >= 650.0 { 220.0 } else { 0.0 };
        if sidebar_width > 0.0 {
            let side = Rect::from_min_max(
                o + Vec2::new(0.0, body_y),
                egui::pos2(o.x + sidebar_width, canvas.bottom()),
            );
            panel(ui, side, p);
            let inner = side.shrink(20.0);
            let mut child = ui.new_child(egui::UiBuilder::new().max_rect(inner));
            child.set_clip_rect(inner);
            egui::ScrollArea::vertical()
                .id_salt(("project-info", &hit.project_id))
                .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden)
                .auto_shrink([false, false])
                .show(&mut child, |ui| {
                    ui.set_width(inner.width());
                    ui.spacing_mut().item_spacing.y = 14.0;
                    section(ui, &self.tr("Совместимость"), p);
                    flow(ui, &self.tr("Версии Minecraft"), 15.0, true, p.text, 22.0);
                    let versions = strings(project, "game_versions");
                    let versions = versions
                        .iter()
                        .skip(versions.len().saturating_sub(16))
                        .cloned()
                        .collect::<Vec<_>>()
                        .join(", ");
                    flow(
                        ui,
                        if versions.is_empty() {
                            "—"
                        } else {
                            &versions
                        },
                        15.0,
                        false,
                        soft(p),
                        22.0,
                    );
                    flow(ui, &self.tr("Загрузчики"), 15.0, true, p.text, 22.0);
                    let loaders = strings(project, "loaders").join(", ");
                    flow(
                        ui,
                        if loaders.is_empty() { "—" } else { &loaders },
                        15.0,
                        false,
                        soft(p),
                        22.0,
                    );
                    section(ui, &self.tr("Ссылки"), p);
                    for (name, key) in [
                        ("Исходный код", "source_url"),
                        ("Сообщить об ошибке", "issues_url"),
                        ("Вики", "wiki_url"),
                        ("Discord", "discord_url"),
                    ] {
                        if let Some(url) = project_markup::safe_url(str_field(project, key)) {
                            let text = self.tr(name);
                            let response = ui.add(
                                egui::Label::new(
                                    egui::RichText::new(text).size(15.0).color(p.accent),
                                )
                                .sense(Sense::click()),
                            );
                            if response.clicked() {
                                ui.ctx().open_url(egui::OpenUrl::new_tab(url));
                            }
                        }
                    }
                    section(ui, &self.tr("Информация"), p);
                    flow(ui, &self.tr("Лицензия"), 15.0, true, p.text, 22.0);
                    let license = str_field(&project["license"], "id");
                    flow(
                        ui,
                        if license.is_empty() { "—" } else { license },
                        15.0,
                        false,
                        soft(p),
                        22.0,
                    );
                    flow(
                        ui,
                        &strings(project, "categories").join(" · "),
                        15.0,
                        false,
                        p.muted,
                        22.0,
                    );
                });
        }
        let body = Rect::from_min_max(
            o + Vec2::new(
                if sidebar_width > 0.0 {
                    sidebar_width + 14.0
                } else {
                    0.0
                },
                body_y,
            ),
            canvas.max,
        );
        panel(ui, body, p);
        let inner = body.shrink(20.0);
        let mut x = inner.left();
        for (index, name) in ["Описание", "Версии", "Галерея"].iter().enumerate()
        {
            let text = self.tr(name);
            let tw = text_width(ui, &text, 14.0, true) + 32.0;
            let rect = Rect::from_min_size(egui::pos2(x, inner.top()), Vec2::new(tw, 44.0));
            let selected = self.project_tab == index;
            let response = ui.interact(rect, ui.id().with(("project-tab", index)), Sense::click());
            if selected {
                ui.painter().rect_filled(rect, 6.0, p.high);
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
            if response.clicked() {
                self.project_tab = index;
            }
            x += tw + 8.0;
        }
        let content = Rect::from_min_max(inner.min + Vec2::new(0.0, 58.0), inner.max);
        let mut child = ui.new_child(egui::UiBuilder::new().max_rect(content));
        child.set_clip_rect(content);
        if let Some(error) = self.message.clone().filter(|_| data.is_none()) {
            flow(&mut child, &self.tr(&error), 14.0, false, p.muted, 20.0);
            if child.button(self.tr("Повторить")).clicked() {
                let tab = self.project_tab;
                self.open_project(hit.clone());
                self.project_tab = tab;
            }
        } else if let Some(data) = &data {
            match self.project_tab {
                0 => {
                    let text = if str_field(project, "body").is_empty() {
                        description
                    } else {
                        str_field(project, "body")
                    };
                    self.project_description(&mut child, &hit, text, "project-description");
                }
                1 => self.project_versions(&mut child, &hit, &data, allowed, busy),
                _ => self.project_gallery(&mut child, project, &hit),
            }
        } else {
            flow(
                &mut child,
                &self.tr("Загружаю проект…"),
                14.0,
                false,
                p.muted,
                20.0,
            );
        }
    }
    fn project_description(
        &mut self,
        ui: &mut egui::Ui,
        hit: &catalog::Hit,
        body: &str,
        salt: &str,
    ) {
        let base = self.project_base(hit);
        let document = self
            .project_markup
            .get(ui.ctx(), self.backend.as_ref(), body, &base);
        match document {
            None => {
                ui.label(self.tr("Загружаю…"));
            }
            Some(Err(error)) => {
                ui.label(self.tr(&error));
                if ui.button(self.tr("Повторить")).clicked() {
                    self.project_markup.retry();
                }
            }
            Some(Ok(nodes)) => {
                egui::ScrollArea::vertical()
                    .id_salt((salt, &hit.project_id, body.len()))
                    .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden)
                    .auto_shrink([false, false])
                    .show(ui, |ui| {
                        ui.set_max_width(ui.available_width());
                        if nodes.is_empty() {
                            ui.label(self.tr("Автор пока не добавил описание"));
                        } else {
                            project_markup::render(
                                ui,
                                &nodes,
                                &mut self.remote_images,
                                self.palette,
                            );
                        }
                    });
            }
        }
    }
    fn project_versions(
        &mut self,
        ui: &mut egui::Ui,
        hit: &catalog::Hit,
        data: &serde_json::Value,
        allowed: bool,
        busy: bool,
    ) {
        let p = self.palette;
        let versions = data["versions"].as_array().cloned().unwrap_or_default();
        let mut games = Vec::new();
        let mut loaders = Vec::new();
        for version in &versions {
            for game in strings(version, "game_versions") {
                if !games.contains(&game) {
                    games.push(game);
                }
            }
            for loader in strings(version, "loaders") {
                if !loaders.contains(&loader) {
                    loaders.push(loader);
                }
            }
        }
        ui.spacing_mut().item_spacing.y = 14.0;
        ui.horizontal_wrapped(|ui| {
            for (label, values, game) in [
                ("Все версии", &games, true),
                ("Все загрузчики", &loaders, false),
            ] {
                let selected = if game {
                    &self.project_game_filter
                } else {
                    &self.project_loader_filter
                };
                let text = if selected.is_empty() {
                    self.tr(label)
                } else {
                    selected.clone()
                };
                egui::ComboBox::from_id_salt(("project-filter", game))
                    .selected_text(text)
                    .show_ui(ui, |ui| {
                        let selected = if game {
                            &mut self.project_game_filter
                        } else {
                            &mut self.project_loader_filter
                        };
                        ui.selectable_value(selected, String::new(), translations::text(label));
                        for value in values {
                            ui.selectable_value(selected, value.clone(), value);
                        }
                    });
            }
        });
        let visible: Vec<_> = versions
            .into_iter()
            .filter(|version| {
                let games = strings(version, "game_versions");
                let loaders = strings(version, "loaders");
                (self.project_game_filter.is_empty() || games.contains(&self.project_game_filter))
                    && (self.project_loader_filter.is_empty()
                        || loaders.contains(&self.project_loader_filter))
                    && (self.preferences.boolean("showOldVersions", false)
                        || !matches!(str_field(version, "version_type"), "alpha" | "beta"))
                    && (self.preferences.boolean("showSnapshots", false)
                        || games.is_empty()
                        || games
                            .iter()
                            .any(|game| game.chars().all(|c| c.is_ascii_digit() || c == '.')))
            })
            .collect();
        if visible.is_empty() {
            ui.label(self.tr("Нет версий с такими фильтрами"));
            return;
        }
        egui::ScrollArea::vertical()
            .id_salt((
                "project-versions",
                &hit.project_id,
                &self.project_game_filter,
                &self.project_loader_filter,
            ))
            .auto_shrink([false, false])
            .show(ui, |ui| {
                ui.spacing_mut().item_spacing.y = 4.0;
                for version in &visible {
                    let (row, _) = ui
                        .allocate_exact_size(Vec2::new(ui.available_width(), 72.0), Sense::hover());
                    ui.painter().rect_filled(row, 8.0, p.high);
                    let compatible = self.compatible_release(version);
                    let installed = self.installed_release(hit, version);
                    let downloadable = version["files"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .any(|file| !str_field(file, "url").is_empty());
                    let action_text = self.tr(if installed {
                        "Установлен"
                    } else if !downloadable {
                        "Загрузка недоступна"
                    } else if !compatible {
                        "Несовместима"
                    } else {
                        "Установить"
                    });
                    let tw = text_width(ui, &action_text, 14.0, true) + 66.0;
                    let action = Rect::from_min_size(
                        egui::pos2(row.right() - 12.0 - tw, row.center().y - 22.0),
                        Vec2::new(tw, 44.0),
                    );
                    let title = if str_field(version, "name").is_empty() {
                        str_field(version, "version_number")
                    } else {
                        str_field(version, "name")
                    };
                    label(
                        ui,
                        Rect::from_min_size(
                            row.min + Vec2::new(12.0, 12.0),
                            Vec2::new((action.left() - row.left() - 24.0).max(0.0), 22.0),
                        ),
                        title,
                        15.0,
                        true,
                        p.text,
                        1,
                    );
                    let channel = self.tr(match str_field(version, "version_type") {
                        "release" => "Релиз",
                        "beta" => "Бета",
                        "alpha" => "Альфа",
                        other => other,
                    });
                    let metadata = format!(
                        "{} · {} · {} · {}",
                        channel,
                        strings(version, "game_versions").join(", "),
                        strings(version, "loaders").join(", "),
                        date_label(self, str_field(version, "date_published"))
                    );
                    label(
                        ui,
                        Rect::from_min_size(
                            row.min + Vec2::new(12.0, 38.0),
                            Vec2::new((action.left() - row.left() - 24.0).max(0.0), 18.0),
                        ),
                        &metadata,
                        13.0,
                        false,
                        p.muted,
                        1,
                    );
                    if allowed && !busy && compatible && !installed {
                        if self.icon_button(
                            ui,
                            ("version-install", str_field(version, "id")),
                            action,
                            &action_text,
                            "download",
                            p.high,
                            p.text,
                        ) {
                            self.install_project_version(hit, version);
                        }
                    } else {
                        button(
                            ui,
                            ("version-disabled", str_field(version, "id")),
                            action,
                            &action_text,
                            p,
                            false,
                            false,
                        );
                    }
                    let click =
                        Rect::from_min_max(row.min, egui::pos2(action.left() - 12.0, row.bottom()));
                    if ui
                        .interact(
                            click,
                            ui.id().with(("version-details", str_field(version, "id"))),
                            Sense::click(),
                        )
                        .clicked()
                    {
                        self.project_release = Some(version.clone());
                    }
                }
            });
    }
    fn install_project_version(&mut self, hit: &catalog::Hit, version: &serde_json::Value) {
        let request = serde_json::json!({"op":"install","project":hit.project_id,"title":hit.title,"slug":hit.slug,"kind":self.catalog_kind,"target":self.catalog_target.clone().unwrap_or_default(),"world":self.catalog_world,"version":version["id"]});
        if self.catalog_kind == "datapack" && self.catalog_world.is_empty() {
            self.catalog_datapack_request = Some(request);
            self.catalog_datapack_dialog = true;
            return;
        }
        self.downloads
            .enqueue(format!("Установка {}", hit.title), request);
    }
    fn gallery(project: &serde_json::Value) -> Vec<serde_json::Value> {
        let mut gallery: Vec<_> = project["gallery"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|entry| str_field(entry, "title") != "__mc_server_banner__")
            .cloned()
            .collect();
        gallery.sort_by_key(|entry| entry["ordering"].as_i64().unwrap_or(0));
        gallery
    }
    fn project_gallery(
        &mut self,
        ui: &mut egui::Ui,
        project: &serde_json::Value,
        hit: &catalog::Hit,
    ) {
        let gallery = Self::gallery(project);
        if gallery.is_empty() {
            ui.label(self.tr("Автор пока не добавил изображения"));
            return;
        }
        let p = self.palette;
        egui::ScrollArea::vertical()
            .id_salt(("project-gallery", &hit.project_id))
            .auto_shrink([false, false])
            .show(ui, |ui| {
                let cols = ((ui.available_width() + 14.0) / 244.0).floor().max(1.0) as usize;
                let card_w = (ui.available_width() - (cols - 1) as f32 * 14.0) / cols as f32;
                for row in gallery.chunks(cols).enumerate() {
                    let (rect, _) = ui.allocate_exact_size(
                        Vec2::new(ui.available_width(), 232.0),
                        Sense::hover(),
                    );
                    for (col, entry) in row.1.iter().enumerate() {
                        let card = Rect::from_min_size(
                            rect.min + Vec2::new(col as f32 * (card_w + 14.0), 0.0),
                            Vec2::new(card_w, 216.0),
                        );
                        ui.painter().rect_filled(card, 8.0, p.high);
                        let image = Rect::from_min_size(card.min, Vec2::new(card_w, 150.0));
                        if let Some(texture) =
                            self.remote_images.media(ui.ctx(), str_field(entry, "url"))
                        {
                            egui::Image::new(&texture)
                                .uv(image_uv(texture.size_vec2(), image.size()))
                                .fit_to_exact_size(image.size())
                                .corner_radius(8.0)
                                .paint_at(ui, image);
                        } else {
                            label(
                                ui,
                                image.shrink(12.0),
                                &self.tr("Загружаю изображение…"),
                                13.0,
                                false,
                                p.muted,
                                2,
                            );
                        }
                        label(
                            ui,
                            Rect::from_min_size(
                                card.min + Vec2::new(12.0, 162.0),
                                Vec2::new(card_w - 24.0, 22.0),
                            ),
                            str_field(entry, "title"),
                            14.0,
                            true,
                            p.text,
                            1,
                        );
                        label(
                            ui,
                            Rect::from_min_size(
                                card.min + Vec2::new(12.0, 188.0),
                                Vec2::new(card_w - 24.0, 18.0),
                            ),
                            &date_label(self, str_field(entry, "created")),
                            13.0,
                            false,
                            p.muted,
                            1,
                        );
                        if ui
                            .interact(
                                card,
                                ui.id().with(("gallery-image", row.0 * cols + col)),
                                Sense::click(),
                            )
                            .clicked()
                        {
                            self.project_image_index = Some(row.0 * cols + col);
                        }
                    }
                }
            });
    }
    pub(super) fn project_dialogs(&mut self, ctx: &egui::Context) {
        if ctx.input(|input| input.key_pressed(egui::Key::Escape)) {
            self.project_image_index = None;
            self.project_release = None;
        }
        if let Some(index) = self.project_image_index {
            let gallery = self
                .project_data
                .as_ref()
                .map(|data| Self::gallery(&data["project"]))
                .unwrap_or_default();
            if let Some(entry) = gallery.get(index) {
                let mut open = true;
                let title = if str_field(entry, "title").is_empty() {
                    self.tr("Галерея")
                } else {
                    str_field(entry, "title").to_owned()
                };
                egui::Window::new(title)
                    .open(&mut open)
                    .anchor(Align2::CENTER_CENTER, Vec2::ZERO)
                    .collapsible(false)
                    .resizable(false)
                    .default_width(900.0_f32.min(ctx.screen_rect().width() - 40.0))
                    .show(ctx, |ui| {
                        if let Some(texture) =
                            self.remote_images.media(ui.ctx(), str_field(entry, "url"))
                        {
                            let size = texture.size_vec2();
                            let factor =
                                (ui.available_width() / size.x).min(450.0 / size.y).min(1.0);
                            ui.add(egui::Image::new(&texture).fit_to_exact_size(size * factor));
                        } else {
                            ui.label(self.tr("Загружаю изображение…"));
                        }
                        if !str_field(entry, "description").is_empty() {
                            ui.label(str_field(entry, "description"));
                        }
                        ui.horizontal(|ui| {
                            if ui
                                .add_enabled(index > 0, egui::Button::new(self.tr("Предыдущее")))
                                .clicked()
                            {
                                self.project_image_index = Some(index - 1);
                            }
                            if ui
                                .add_enabled(
                                    index + 1 < gallery.len(),
                                    egui::Button::new(self.tr("Следующее")),
                                )
                                .clicked()
                            {
                                self.project_image_index = Some(index + 1);
                            }
                        });
                    });
                if !open {
                    self.project_image_index = None;
                }
            } else {
                self.project_image_index = None;
            }
        }
        if let Some(version) = self.project_release.clone() {
            let Some(hit) = self.project_hit.clone() else {
                return;
            };
            let mut open = true;
            let title = if str_field(&version, "name").is_empty() {
                str_field(&version, "version_number")
            } else {
                str_field(&version, "name")
            };
            egui::Window::new(title)
                .open(&mut open)
                .anchor(Align2::CENTER_CENTER, Vec2::ZERO)
                .collapsible(false)
                .default_size(Vec2::new(
                    900.0_f32.min(ctx.screen_rect().width() - 40.0),
                    600.0_f32.min(ctx.screen_rect().height() - 40.0),
                ))
                .show(ctx, |ui| {
                    ui.label(format!(
                        "{} · {}",
                        strings(&version, "game_versions").join(", "),
                        strings(&version, "loaders").join(", ")
                    ));
                    ui.separator();
                    let body = str_field(&version, "changelog");
                    if body.is_empty() {
                        ui.label(self.tr("Список изменений отсутствует"));
                    } else {
                        let height = (ui.available_height() - 90.0).max(80.0);
                        ui.allocate_ui_with_layout(
                            Vec2::new(ui.available_width(), height),
                            egui::Layout::top_down(egui::Align::Min),
                            |ui| {
                                self.project_description(ui, &hit, body, "project-changelog");
                            },
                        );
                    }
                    ui.separator();
                    for file in version["files"].as_array().into_iter().flatten() {
                        ui.label(str_field(file, "filename"));
                    }
                    let allowed = self.project_allowed()
                        && self.compatible_release(&version)
                        && !self.installed_release(&hit, &version)
                        && !self.downloads.items.iter().any(|item| {
                            item.status.pending() && item.request["project"] == hit.project_id
                        });
                    if ui
                        .add_enabled(allowed, egui::Button::new(self.tr("Установить")))
                        .clicked()
                    {
                        self.install_project_version(&hit, &version);
                        self.project_release = None;
                    }
                });
            if !open {
                self.project_release = None;
            }
        }
    }
}
