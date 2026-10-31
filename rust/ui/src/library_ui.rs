use super::visual::loader_label;
use super::*;
use egui::{Align2, FontId, Pos2, Rect, Sense};

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
fn label(ui: &egui::Ui, pos: Pos2, text: &str, size: f32, bold: bool, color: Color32) {
    ui.painter()
        .text(pos, Align2::LEFT_CENTER, text, font(size, bold), color);
}
fn text_width(ui: &egui::Ui, text: &str) -> f32 {
    ui.painter()
        .layout_no_wrap(text.to_owned(), font(14.0, true), Color32::WHITE)
        .size()
        .x
}
fn plain_button(
    ui: &mut egui::Ui,
    id: impl std::hash::Hash,
    rect: Rect,
    text: &str,
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
    ui.painter().text(
        rect.center(),
        Align2::CENTER_CENTER,
        text,
        font(14.0, true),
        color,
    );
    response
}

impl App {
    pub(super) fn library_page(&mut self, ui: &mut egui::Ui) {
        let p = self.palette;
        let width = ui.available_width().min(1298.0);
        let (canvas, _) = ui.allocate_exact_size(
            Vec2::new(ui.available_width(), ui.available_height()),
            Sense::hover(),
        );
        let origin = canvas.min + Vec2::new((canvas.width() - width) * 0.5, 0.0);
        let right = origin.x + width;
        let header = Rect::from_min_size(origin + Vec2::new(0.0, 4.0), Vec2::new(width, 44.0));
        label(
            ui,
            header.left_center(),
            &self.tr("Мои сборки"),
            26.0,
            true,
            p.text,
        );
        let new_width = text_width(ui, &self.tr("Новая сборка")) + 66.0;
        let group_width = text_width(ui, &self.tr("Группы")) + 66.0;
        let new_rect = Rect::from_min_size(
            egui::pos2(right - new_width, header.top()),
            Vec2::new(new_width, 44.0),
        );
        let groups = Rect::from_min_size(
            egui::pos2(new_rect.left() - 12.0 - group_width, header.top()),
            Vec2::new(group_width, 44.0),
        );
        if self.icon_button(
            ui,
            "library-new",
            new_rect,
            "Новая сборка",
            "add",
            p.accent,
            p.on_accent,
        ) {
            self.create_build_open = true;
            self.create_name.clear();
            if let Some(backend) = &self.backend {
                self.create_engine = tasks::start(
                    backend,
                    &serde_json::json!({"op":"versions"}),
                    ui.ctx().clone(),
                )
                .ok();
            }
        }
        if self.icon_button(
            ui,
            "library-groups",
            groups,
            "Группы",
            "folder",
            p.high,
            p.text,
        ) {
            self.library_groups_open = true;
        }

        let filter_text = self.tr("Фильтры");
        let filter_width = text_width(ui, &filter_text) + 36.0;
        let filter_rect = Rect::from_min_size(
            egui::pos2(right - filter_width, origin.y + 64.0),
            Vec2::new(filter_width, 44.0),
        );
        if plain_button(
            ui,
            "library-filter",
            filter_rect,
            &filter_text,
            p.high,
            p.text,
        )
        .clicked()
        {
            self.library_filters_open = !self.library_filters_open;
        }
        let search = Rect::from_min_size(
            origin + Vec2::new(0.0, 66.0),
            Vec2::new((width - filter_width - 8.0).max(60.0), 40.0),
        );
        ui.painter().rect_filled(search, 6.0, p.high);
        ui.painter().rect_stroke(
            search,
            6.0,
            egui::Stroke::new(1.0_f32, p.outline),
            egui::StrokeKind::Inside,
        );
        self.draw_icon(
            ui,
            "search",
            Rect::from_center_size(
                search.left_center() + Vec2::new(26.0, 0.0),
                Vec2::splat(18.0),
            ),
            p.muted,
        );
        let input = Rect::from_min_max(
            search.min + Vec2::new(46.0, 8.0),
            search.max - Vec2::new(12.0, 6.0),
        );
        let mut search_ui = ui.new_child(egui::UiBuilder::new().max_rect(input));
        search_ui.set_clip_rect(input);
        search_ui.add_sized(
            input.size(),
            egui::TextEdit::singleline(&mut self.query)
                .frame(false)
                .margin(egui::Margin::ZERO)
                .font(font(14.0, false))
                .hint_text(translations::ui_text(ui, "Поиск сборок…")),
        );

        let mut heading_y = 174.0;
        if self.library_filters_open {
            let filter_area =
                Rect::from_min_size(origin + Vec2::new(0.0, 124.0), Vec2::new(width, 100.0));
            let mut controls = ui.new_child(egui::UiBuilder::new().max_rect(filter_area));
            controls.set_clip_rect(filter_area);
            controls.horizontal_wrapped(|ui| {
                egui::ComboBox::from_id_salt("library-order")
                    .selected_text(self.tr(if self.recent_first {
                        "Недавно играли"
                    } else {
                        "Название"
                    }))
                    .show_ui(ui, |ui| {
                        for (text, value) in [("Название", false), ("Недавно играли", true)]
                        {
                            ui.selectable_value(
                                &mut self.recent_first,
                                value,
                                translations::ui_text(ui, text),
                            );
                        }
                    });
                egui::ComboBox::from_id_salt("library-loader")
                    .selected_text(
                        self.loader_filter
                            .as_deref()
                            .map(loader_label)
                            .map(str::to_owned)
                            .unwrap_or_else(|| self.tr("Все загрузчики")),
                    )
                    .show_ui(ui, |ui| {
                        ui.selectable_value(
                            &mut self.loader_filter,
                            None,
                            translations::ui_text(ui, "Все загрузчики"),
                        );
                        for loader in ["VANILLA", "FABRIC", "FORGE", "NEOFORGE", "QUILT"] {
                            ui.selectable_value(
                                &mut self.loader_filter,
                                Some(loader.to_owned()),
                                loader_label(loader),
                            );
                        }
                    });
                ui.toggle_value(
                    &mut self.library_installed_only,
                    translations::ui_text(ui, "Скачанные"),
                );
                ui.toggle_value(
                    &mut self.favorites_only,
                    translations::ui_text(ui, "Избранное"),
                );
                let groups = self
                    .preferences
                    .values
                    .get("libraryGroups")
                    .and_then(|v| v.as_array())
                    .cloned()
                    .unwrap_or_default();
                let selected = groups
                    .iter()
                    .find(|g| g["id"].as_str() == self.library_group_filter.as_deref())
                    .and_then(|g| g["name"].as_str())
                    .map(str::to_owned)
                    .unwrap_or_else(|| self.tr("Все группы"));
                egui::ComboBox::from_id_salt("library-group-filter")
                    .selected_text(selected)
                    .show_ui(ui, |ui| {
                        ui.selectable_value(
                            &mut self.library_group_filter,
                            None,
                            translations::ui_text(ui, "Все группы"),
                        );
                        for group in groups {
                            if let (Some(id), Some(name)) =
                                (group["id"].as_str(), group["name"].as_str())
                            {
                                ui.selectable_value(
                                    &mut self.library_group_filter,
                                    Some(id.to_owned()),
                                    name,
                                );
                            }
                        }
                    });
            });
            heading_y = 124.0 + controls.min_rect().height().min(100.0) + 16.0;
        } else {
            let recent =
                Rect::from_min_size(origin + Vec2::new(0.0, 124.0), Vec2::new(110.0, 32.0));
            label(
                ui,
                recent.left_center(),
                &self.tr("Недавнее"),
                14.0,
                true,
                p.muted,
            );
            let arrow = Rect::from_center_size(
                recent.left_center() + Vec2::new(text_width(ui, &self.tr("Недавнее")) + 16.0, 0.0),
                Vec2::splat(20.0),
            );
            self.draw_icon(ui, "chevron-down", arrow, p.muted);
            if ui
                .interact(recent, ui.id().with("library-recent"), Sense::click())
                .clicked()
            {
                self.library_recent_open = !self.library_recent_open;
            }
            let activity_text = self.tr("Активность →");
            let activity = Rect::from_min_size(
                egui::pos2(right - text_width(ui, &activity_text), recent.top()),
                Vec2::new(text_width(ui, &activity_text), 32.0),
            );
            label(
                ui,
                activity.left_center(),
                &activity_text,
                13.0,
                false,
                p.accent,
            );
            if ui
                .interact(activity, ui.id().with("library-activity"), Sense::click())
                .clicked()
            {
                self.navigate(Screen::Activity);
                self.activity_target = None;
                self.activity = None;
                self.activity_scan = None;
                self.refresh_activity(&ui.ctx().clone());
            }
            if self.library_recent_open {
                let mut recent_builds: Vec<_> = self
                    .builds
                    .iter()
                    .filter(|b| b.last_played.is_some())
                    .cloned()
                    .collect();
                recent_builds.sort_by(|a, b| b.last_played.cmp(&a.last_played));
                let rect =
                    Rect::from_min_size(origin + Vec2::new(0.0, 166.0), Vec2::new(width, 80.0));
                if let Some(build) = recent_builds.first() {
                    let mut child = ui.new_child(egui::UiBuilder::new().max_rect(rect));
                    child.set_clip_rect(rect);
                    if self.exact_library_row(&mut child, build) {
                        self.open_instance(build);
                    }
                } else {
                    label(
                        ui,
                        rect.left_center(),
                        &self.tr("Здесь появятся последние миры и серверы"),
                        13.0,
                        false,
                        p.muted,
                    );
                }
                heading_y += 90.0;
            }
        }
        let filter = self.query.trim().to_lowercase();
        let mut builds: Vec<_> = self
            .builds
            .iter()
            .filter(|build| {
                (filter.is_empty()
                    || build.name.to_lowercase().contains(&filter)
                    || build.version_id.to_lowercase().contains(&filter))
                    && (!self.favorites_only || build.favorite)
                    && self
                        .loader_filter
                        .as_ref()
                        .is_none_or(|loader| &build.loader == loader)
                    && self
                        .library_group_filter
                        .as_ref()
                        .is_none_or(|id| build.group_id.as_ref() == Some(id))
                    && (!self.library_installed_only
                        || library::build_dir(
                            &self.game_root,
                            &build.id,
                            build.directory_name.as_deref(),
                            build.pack,
                        )
                        .is_some_and(|dir| dir.is_dir()))
            })
            .cloned()
            .collect();
        builds.sort_by(|a, b| {
            b.favorite
                .cmp(&a.favorite)
                .then_with(|| {
                    if self.recent_first {
                        b.last_played.cmp(&a.last_played)
                    } else {
                        std::cmp::Ordering::Equal
                    }
                })
                .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
        });
        label(
            ui,
            origin + Vec2::new(0.0, heading_y + 10.0),
            &self.tr("Все сборки"),
            15.0,
            true,
            p.text,
        );
        ui.painter().text(
            egui::pos2(right, origin.y + heading_y + 10.0),
            Align2::RIGHT_CENTER,
            builds.len().to_string(),
            font(13.0, false),
            p.muted,
        );
        let viewport = Rect::from_min_max(
            origin + Vec2::new(0.0, heading_y + 34.0),
            egui::pos2(right, canvas.bottom()),
        );
        let mut list = ui.new_child(egui::UiBuilder::new().max_rect(viewport));
        list.set_clip_rect(viewport);
        list.spacing_mut().item_spacing.y = 2.0;
        egui::ScrollArea::vertical()
            .id_salt("library-instances")
            .auto_shrink([false, false])
            .show(&mut list, |ui| {
                if builds.is_empty() {
                    ui.label(self.tr("Ничего не нашлось"));
                }
                for build in &builds {
                    if self.exact_library_row(ui, build) {
                        self.open_instance(build);
                    }
                }
            });
    }

    pub(super) fn library_groups_dialog(&mut self, ctx: &egui::Context) {
        if !self.library_groups_open {
            return;
        }
        let mut open = true;
        egui::Window::new(self.tr("Группы")).open(&mut open).anchor(Align2::CENTER_CENTER,Vec2::ZERO).collapsible(false).resizable(false).default_width(440.0).show(ctx,|ui|{
            if ui.selectable_label(self.library_group_filter.is_none(),self.tr("Все группы")).clicked(){self.library_group_filter=None;self.library_groups_open=false;}
            let mut groups=self.preferences.values.get("libraryGroups").and_then(|v|v.as_array()).cloned().unwrap_or_default();
            for group in &groups {if let(Some(id),Some(name))=(group["id"].as_str(),group["name"].as_str()){if ui.selectable_label(self.library_group_filter.as_deref()==Some(id),name).clicked(){self.library_group_filter=Some(id.to_owned());self.library_groups_open=false;}}}
            ui.separator();ui.label(self.tr("Новая группа"));
            ui.text_edit_singleline(&mut self.library_group_name);
            let name=self.library_group_name.trim();
            let valid=!name.is_empty()&&name.chars().count()<=80&&!groups.iter().any(|group|group["name"].as_str().is_some_and(|old|old.to_lowercase()==name.to_lowercase()));
            if ui.add_enabled(valid,egui::Button::new(self.tr("Создать"))).clicked(){
                groups.push(serde_json::json!({"id":format!("aw-group-{}",chrono::Utc::now().timestamp_nanos_opt().unwrap_or_default()),"name":name}));
                if self.preferences.set("libraryGroups",serde_json::Value::Array(groups)){self.library_group_name.clear();}
            }
            if let Some(error)=&self.preferences.error{ui.colored_label(Color32::LIGHT_RED,error);}
        });
        if !open {
            self.library_groups_open = false;
        }
    }
}
