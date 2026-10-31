use super::*;
use egui::{Align2, FontId, Pos2, Rect, Sense, Stroke};
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
fn label(ui: &egui::Ui, pos: Pos2, text: &str, size: f32, bold: bool, color: Color32, max: f32) {
    let painter = ui.painter().with_clip_rect(
        Rect::from_min_size(pos, Vec2::new(max.max(0.0), 24.0)).intersect(ui.clip_rect()),
    );
    let mut value = text.to_owned();
    let mut clipped = false;
    while !value.is_empty()
        && painter
            .layout_no_wrap(value.clone(), font(size, bold), color)
            .size()
            .x
            > max.max(0.0)
    {
        value.pop();
        clipped = true;
    }
    if clipped {
        value.push('…');
    }
    painter.text(pos, Align2::LEFT_TOP, value, font(size, bold), color);
}
pub(super) fn toggle(
    ui: &mut egui::Ui,
    id: &str,
    r: Rect,
    checked: bool,
    enabled: bool,
    p: Palette,
) -> bool {
    let color = if checked { p.accent } else { p.high };
    ui.painter().rect_filled(r, 16.0, color);
    if !checked {
        ui.painter().rect_stroke(
            r.shrink(1.0),
            15.0,
            Stroke::new(2.0_f32, p.outline),
            egui::StrokeKind::Inside,
        );
    }
    ui.painter().circle_filled(
        egui::pos2(r.left() + if checked { 36.0 } else { 16.0 }, r.center().y),
        if checked { 12.0 } else { 8.0 },
        if checked { p.on_accent } else { p.muted },
    );
    let response = ui.interact(
        r.expand2(Vec2::new(0.0, 8.0)),
        ui.id().with(id),
        Sense::click(),
    );
    enabled && response.clicked()
}
fn checkbox(
    ui: &mut egui::Ui,
    id: impl std::hash::Hash,
    hit: Rect,
    value: bool,
    enabled: bool,
    p: Palette,
) -> bool {
    let square = Rect::from_center_size(hit.center(), Vec2::splat(18.0));
    if value {
        ui.painter().rect_filled(square, 1.0, p.accent);
        let c = square.center();
        ui.painter().line_segment(
            [c + Vec2::new(-5.0, 0.0), c + Vec2::new(-1.0, 4.0)],
            Stroke::new(2.0_f32, p.on_accent),
        );
        ui.painter().line_segment(
            [c + Vec2::new(-1.0, 4.0), c + Vec2::new(6.0, -5.0)],
            Stroke::new(2.0_f32, p.on_accent),
        );
    } else {
        ui.painter().rect_stroke(
            square,
            1.0,
            Stroke::new(2.0_f32, p.muted),
            egui::StrokeKind::Inside,
        );
    }
    enabled && ui.interact(hit, ui.id().with(id), Sense::click()).clicked()
}
fn button(ui: &mut egui::Ui, id: &str, r: Rect, text: &str, color: Color32, fill: Color32) -> bool {
    let response = ui.interact(r, ui.id().with(id), Sense::click());
    ui.painter().rect_filled(
        r,
        6.0,
        if response.hovered() {
            fill.gamma_multiply(1.1)
        } else {
            fill
        },
    );
    ui.painter().text(
        r.center(),
        Align2::CENTER_CENTER,
        text,
        font(14.0, true),
        color,
    );
    response.clicked()
}
fn stable(file: &library::ContentFile) -> String {
    file.relative.trim_end_matches(".disabled").to_owned()
}
fn columns(width: f32) -> (f32, f32) {
    let available = (width - 236.0).max(0.0);
    (58.0 + available * 1.4 / 2.4 + 8.0, width - 162.0)
}
impl App {
    fn content_icon(&self, ui: &egui::Ui, name: &str, rect: Rect, color: Color32) {
        if let Some(icon) = self.nav_icons.get(name) {
            ui.painter().image(
                icon.id(),
                rect,
                Rect::from_min_max(Pos2::ZERO, egui::pos2(1.0, 1.0)),
                color,
            );
        }
    }
    pub(super) fn instance_tabs(&mut self, ui: &mut egui::Ui, build: &Build) {
        let p = self.palette;
        let (rect, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 44.0), Sense::hover());
        let mut x = rect.left();
        for (label_text, tab) in [
            ("Контент", Some(InstanceTab::Content)),
            ("Файлы", Some(InstanceTab::Files)),
            ("Миры", Some(InstanceTab::Worlds)),
            ("Журнал", Some(InstanceTab::Journal)),
            ("Статистика", Some(InstanceTab::Statistics)),
            ("Скриншоты", None),
            ("История", Some(InstanceTab::History)),
        ] {
            let text = self.tr(label_text);
            let w = ui
                .painter()
                .layout_no_wrap(text.clone(), font(14.0, true), p.text)
                .size()
                .x
                + 28.0;
            let r = Rect::from_min_size(egui::pos2(x, rect.top()), Vec2::new(w, 44.0));
            let selected = tab == Some(self.instance_tab);
            ui.painter().text(
                r.center(),
                Align2::CENTER_CENTER,
                text,
                font(14.0, true),
                if selected { p.text } else { p.muted },
            );
            if selected {
                ui.painter().line_segment(
                    [
                        r.left_bottom() - Vec2::new(0.0, 1.0),
                        r.right_bottom() - Vec2::new(0.0, 1.0),
                    ],
                    Stroke::new(2.0_f32, p.accent),
                );
            }
            if ui
                .interact(r, ui.id().with(label_text), Sense::click())
                .clicked()
            {
                if let Some(tab) = tab {
                    self.instance_tab = tab;
                    self.file_relative.clear();
                    self.file_query.clear();
                    match tab {
                        InstanceTab::Files | InstanceTab::Worlds => self.refresh_files(build),
                        InstanceTab::Journal => self.refresh_log(build, &ui.ctx().clone()),
                        InstanceTab::Statistics => {
                            self.activity_target = Some(build.key());
                            self.activity = None;
                            self.activity_scan = None;
                            self.activity_week_offset = 0;
                            self.refresh_activity(&ui.ctx().clone());
                        }
                        InstanceTab::History => self.refresh_history(build),
                        _ => {}
                    }
                } else {
                    self.shot_group = Some(build.key());
                    self.shot_selected = None;
                    self.shot_query.clear();
                    self.navigate(Screen::Screenshots);
                    self.refresh_screenshots(&ui.ctx().clone());
                }
            }
            x += w + 4.0;
        }
    }
    pub(super) fn instance_content_table(&mut self, ui: &mut egui::Ui, build: &Build) {
        let p = self.palette;
        let busy = self
            .downloads
            .items
            .iter()
            .any(|item| item.status.pending() && item.request["target"] == build.key());
        let (bar, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 44.0), Sense::hover());
        let add = self.tr("Добавить файлы");
        let find = self.tr("Найти проекты");
        let add_w = ui
            .painter()
            .layout_no_wrap(add.clone(), font(14.0, true), p.text)
            .size()
            .x
            + 66.0;
        let find_w = ui
            .painter()
            .layout_no_wrap(find.clone(), font(14.0, true), p.text)
            .size()
            .x
            + 36.0;
        let find_r = Rect::from_min_size(
            egui::pos2(bar.right() - find_w, bar.top()),
            Vec2::new(find_w, 44.0),
        );
        let add_r = Rect::from_min_size(
            find_r.min - Vec2::new(8.0 + add_w, 0.0),
            Vec2::new(add_w, 44.0),
        );
        let search = Rect::from_min_size(
            bar.min,
            Vec2::new((add_r.left() - bar.left() - 8.0).max(60.0), 40.0),
        );
        ui.painter().rect_filled(search, 6.0, p.high);
        ui.painter().rect_stroke(
            search,
            6.0,
            Stroke::new(1.0_f32, p.outline.gamma_multiply(0.75)),
            egui::StrokeKind::Inside,
        );
        self.content_icon(
            ui,
            "search",
            Rect::from_center_size(
                search.left_center() + Vec2::new(22.0, 0.0),
                Vec2::splat(18.0),
            ),
            p.muted,
        );
        let old_query = self.query.clone();
        let hint = self.tr("Поиск контента…");
        let input_rect = Rect::from_min_max(
            search.min + Vec2::new(36.0, 9.0),
            search.max - Vec2::new(12.0, 8.0),
        );
        let mut input_ui = ui.new_child(egui::UiBuilder::new().max_rect(input_rect));
        input_ui.add_sized(
            input_rect.size(),
            egui::TextEdit::singleline(&mut self.query)
                .frame(false)
                .margin(egui::Margin::ZERO)
                .font(font(14.0, false))
                .hint_text(hint),
        );
        if self.query != old_query {
            self.content_scroll_reset = true;
        }
        if self.icon_button(
            ui,
            "add-files",
            add_r,
            "Добавить файлы",
            "folder",
            p.high,
            p.text,
        ) && !busy
        {
            self.import_type_target = Some(build.key());
        }
        if button(ui, "find-projects", find_r, &find, p.on_accent, p.accent) && !busy {
            self.catalog_installed = false;
            self.catalog_target = Some(build.key());
            self.catalog_kind = content_type(self.content_filter);
            self.catalog_offset = 0;
            self.catalog_page = None;
            self.navigate(Screen::Catalog);
            self.start_catalog_search(&ui.ctx().clone());
        }
        let (filters, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 44.0), Sense::hover());
        let mut x = filters.left();
        let mut rows: Vec<_> = self
            .content
            .iter()
            .filter(|file| {
                let title = self
                    .content_metadata
                    .get(&stable(file))
                    .map(|m| m.title.as_str())
                    .unwrap_or(&file.name);
                (self.content_filter == "Всё" || file.category == self.content_filter)
                    && (self.query.trim().is_empty()
                        || format!("{} {}", title, file.name)
                            .to_lowercase()
                            .contains(&self.query.trim().to_lowercase()))
            })
            .cloned()
            .collect();
        rows.sort_by_key(|file| {
            self.content_metadata
                .get(&stable(file))
                .map(|m| m.title.as_str())
                .filter(|s| !s.is_empty())
                .unwrap_or(&file.name)
                .to_lowercase()
        });
        if self.content_descending {
            rows.reverse();
        }
        if self.content_selected.is_empty() {
            let chip_viewport =
                Rect::from_min_max(filters.min, filters.right_bottom() - Vec2::new(140.0, 0.0));
            let mut chips = ui.new_child(egui::UiBuilder::new().max_rect(chip_viewport));
            chips.set_clip_rect(chip_viewport);
            egui::ScrollArea::horizontal()
                .id_salt("content-filters")
                .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden)
                .show(&mut chips, |ui| {
                    let mut x = ui.cursor().left();
                    for category in ["Всё", "Моды", "Шейдеры", "Ресурспаки", "Датапаки"]
                    {
                        let text = self.tr(category);
                        let w = ui
                            .painter()
                            .layout_no_wrap(text.clone(), font(14.0, true), p.text)
                            .size()
                            .x
                            + 32.0;
                        let r =
                            Rect::from_min_size(egui::pos2(x, filters.top()), Vec2::new(w, 44.0));
                        if button(
                            ui,
                            category,
                            r,
                            &text,
                            if self.content_filter == category {
                                p.accent
                            } else {
                                p.text
                            },
                            if self.content_filter == category {
                                p.high
                            } else {
                                p.panel
                            },
                        ) {
                            self.content_filter = category;
                            self.content_scroll_reset = true;
                        }
                        if self.content_filter == category {
                            ui.painter().rect_stroke(
                                r,
                                6.0,
                                Stroke::new(1.0_f32, p.accent.gamma_multiply(0.3)),
                                egui::StrokeKind::Inside,
                            );
                        }
                        x += w + 6.0;
                    }
                    ui.allocate_exact_size(
                        Vec2::new((x - chip_viewport.left()).max(0.0), 44.0),
                        Sense::hover(),
                    );
                });
        } else {
            label(
                ui,
                filters.min + Vec2::new(0.0, 12.0),
                &format!("{}: {}", self.tr("Выбрано"), self.content_selected.len()),
                13.0,
                false,
                p.muted,
                130.0,
            );
            x += 130.0;
            for (op, enabled, text) in [
                ("toggle", true, "Включить"),
                ("toggle", false, "Выключить"),
                ("updateItem", false, "Обновить"),
            ] {
                let text = self.tr(text);
                let w = ui
                    .painter()
                    .layout_no_wrap(text.clone(), font(14.0, true), p.text)
                    .size()
                    .x
                    + 24.0;
                let r = Rect::from_min_size(egui::pos2(x, filters.top()), Vec2::new(w, 44.0));
                if button(
                    ui,
                    &format!("bulk-{op}-{enabled}"),
                    r,
                    &text,
                    p.text,
                    p.high,
                ) && !busy
                {
                    for file in self
                        .content
                        .iter()
                        .filter(|file| self.content_selected.contains(&stable(file)))
                    {
                        if op == "updateItem"
                            && !self
                                .content_metadata
                                .get(&stable(file))
                                .is_some_and(|m| !m.update.is_empty())
                        {
                            continue;
                        }
                        self.downloads.enqueue(text.clone(),serde_json::json!({"op":op,"target":build.key(),"kind":content_type(file.category),"file":file.relative,"enabled":enabled}));
                    }
                    self.content_selected.clear();
                }
                x += w + 8.0;
            }
            let delete_text = self.tr("Удалить");
            let delete_width = ui
                .painter()
                .layout_no_wrap(delete_text.clone(), font(14.0, true), p.text)
                .size()
                .x
                + 32.0;
            let delete_r =
                Rect::from_min_size(egui::pos2(x, filters.top()), Vec2::new(delete_width, 44.0));
            if button(
                ui,
                "bulk-delete",
                delete_r,
                &delete_text,
                Color32::from_rgb(255, 180, 171),
                p.high,
            ) && !busy
            {
                self.content_delete_many = true;
            }
            x += delete_width + 8.0;
            let r = Rect::from_min_size(egui::pos2(x, filters.top()), Vec2::new(36.0, 44.0));
            if button(ui, "clear-selection", r, "×", p.muted, p.panel) {
                self.content_selected.clear();
            }
        }
        let refresh = Rect::from_min_size(
            filters.right_top() - Vec2::new(44.0, 0.0),
            Vec2::splat(44.0),
        );
        if self.content_selected.is_empty() {
            self.content_icon(ui, "refresh", refresh.shrink(12.0), p.muted);
            if ui
                .interact(refresh, ui.id().with("content-refresh"), Sense::click())
                .clicked()
                && !busy
            {
                self.refresh_content_details(build);
            }
            let updates: Vec<_> = self
                .content
                .iter()
                .filter(|file| {
                    self.content_metadata
                        .get(&stable(file))
                        .is_some_and(|m| !m.update.is_empty())
                })
                .cloned()
                .collect();
            if !updates.is_empty() {
                let r = refresh.translate(Vec2::new(-44.0, 0.0));
                self.content_icon(ui, "download", r.shrink(12.0), p.accent);
                if ui
                    .interact(r, ui.id().with("update-all"), Sense::click())
                    .clicked()
                    && !busy
                {
                    for file in updates {
                        self.downloads.enqueue(self.tr("Обновить всё"),serde_json::json!({"op":"updateItem","target":build.key(),"kind":content_type(file.category),"file":file.relative}));
                    }
                }
            }
            let sort = refresh.translate(Vec2::new(-88.0, 0.0));
            for d in [-4.0, 0.0, 4.0] {
                ui.painter()
                    .circle_filled(sort.center() + Vec2::new(0.0, d), 1.5, p.muted);
            }
            let response = ui.interact(sort, ui.id().with("sort-content"), Sense::click());
            let popup = ui.id().with("content-sort-menu");
            if response.clicked() {
                ui.memory_mut(|memory| memory.toggle_popup(popup));
            }
            egui::popup_below_widget(
                ui,
                popup,
                &response,
                egui::PopupCloseBehavior::CloseOnClickOutside,
                |ui| {
                    for (text, descending) in [("Название (А–Я)", false), ("Название (Я–А)", true)]
                    {
                        if ui
                            .selectable_label(self.content_descending == descending, self.tr(text))
                            .clicked()
                        {
                            self.content_descending = descending;
                            self.content_scroll_reset = true;
                            ui.memory_mut(|memory| memory.close_popup());
                        }
                    }
                },
            );
        }
        let h = ui.available_height().max(90.0);
        let (table, _) = ui.allocate_exact_size(Vec2::new(ui.available_width(), h), Sense::hover());
        let w = table.width();
        let (version_x, actions_x) = columns(w);
        let narrow = w < 650.0;
        ui.painter().rect_filled(table, 8.0, p.bg);
        ui.painter().rect_filled(
            Rect::from_min_size(table.min, Vec2::new(w, 44.0)),
            6.0,
            p.high,
        );
        ui.painter().rect_stroke(
            table,
            8.0,
            Stroke::new(1.0_f32, p.outline),
            egui::StrokeKind::Inside,
        );
        let all = !rows.is_empty()
            && rows
                .iter()
                .all(|file| self.content_selected.contains(&stable(file)));
        let check = Rect::from_min_size(table.min + Vec2::new(10.0, 2.0), Vec2::splat(40.0));
        if checkbox(ui, "select-all", check, all, !busy && !rows.is_empty(), p) {
            for file in &rows {
                let key = stable(file);
                if all {
                    self.content_selected.remove(&key);
                } else {
                    self.content_selected.insert(key);
                }
            }
        }
        label(
            ui,
            table.min + Vec2::new(58.0, 13.0),
            &self
                .tr("Проекты ({0})")
                .replace("{0}", &rows.len().to_string()),
            14.0,
            true,
            p.muted,
            (version_x - 66.0).max(0.0),
        );
        if !narrow {
            label(
                ui,
                table.min + Vec2::new(version_x, 13.0),
                &self.tr("Версия"),
                14.0,
                true,
                p.muted,
                (actions_x - version_x - 8.0).max(0.0),
            );
        }
        label(
            ui,
            table.min + Vec2::new(actions_x, 13.0),
            &self.tr("Действия"),
            14.0,
            true,
            p.muted,
            140.0,
        );
        let viewport = Rect::from_min_max(
            table.min + Vec2::new(1.0, 46.0),
            table.max - Vec2::new(1.0, 2.0),
        );
        let mut list = ui.new_child(
            egui::UiBuilder::new()
                .max_rect(viewport)
                .layout(egui::Layout::top_down(egui::Align::Min)),
        );
        list.set_clip_rect(viewport);
        list.spacing_mut().item_spacing = Vec2::ZERO;
        let mut area = egui::ScrollArea::vertical()
            .id_salt(("content-table", build.key()))
            .auto_shrink([false, false]);
        if self.content_scroll_reset {
            area = area.vertical_scroll_offset(0.0);
            self.content_scroll_reset = false;
        }
        area.show_rows(&mut list,65.0,rows.len(),|ui,range|{
   if rows.is_empty(){ui.label(self.tr("Контент не найден"));}
   for index in range{let file=&rows[index];let metadata=self.content_metadata.get(&stable(file)).cloned().unwrap_or_default();let key=stable(file);let (row,_)=ui.allocate_exact_size(Vec2::new(viewport.width()-12.0,65.0),Sense::hover());let selected=self.content_selected.contains(&key);if selected{ui.painter().rect_filled(row,0.0,p.selected);}let c=Rect::from_min_size(row.min+Vec2::new(9.0,12.0),Vec2::splat(40.0));if checkbox(ui,(&key,"check"),c,selected,!busy,p){if selected{self.content_selected.remove(&key);}else{self.content_selected.insert(key.clone());}}
    let color=if file.enabled{p.text}else{mix(p.bg,p.text,0.55)};let muted=if file.enabled{p.muted}else{mix(p.bg,p.muted,0.55)};
    let art=Rect::from_min_size(row.min+Vec2::new(57.0,14.0),Vec2::splat(36.0));let image=if metadata.icon.is_empty(){None}else{self.remote_images.get(ui.ctx(),&metadata.icon)};
    if let Some(image)=image{egui::Image::new(&image).corner_radius(4.0).tint(if file.enabled{Color32::WHITE}else{Color32::from_white_alpha(140)}).paint_at(ui,art);}else{ui.painter().rect_filled(art,8.0,p.high);self.content_icon(ui,"extension",art.shrink(7.0),muted);}
    let title=if metadata.title.is_empty(){file.name.trim_end_matches(".disabled").trim_end_matches(".jar").trim_end_matches(".zip")}else{&metadata.title};let project_end=if narrow{actions_x-8.0}else{version_x-8.0};label(ui,row.min+Vec2::new(103.0,13.0),title,14.0,true,color,project_end-103.0);
    if !metadata.project.is_empty() {
      let project_rect=Rect::from_min_max(row.min+Vec2::new(57.0,0.0),egui::pos2(row.left()+project_end,row.bottom()));
      if ui.interact(project_rect,ui.id().with((&key,"project")),Sense::click()).clicked(){
        self.catalog_target=Some(build.key());self.catalog_kind=content_type(file.category);self.catalog_curseforge=metadata.project.starts_with("cf:");
        self.open_project(catalog::Hit{project_id:metadata.project.clone(),slug:String::new(),title:title.to_owned(),description:String::new(),author:String::new(),downloads:0,icon_url:Some(metadata.icon.clone()),categories:Vec::new()});
      }
    }
    label(ui,row.min+Vec2::new(103.0,36.0),&if narrow{metadata.version.clone()}else{self.tr(file.category)},13.0,false,muted,project_end-103.0);
    if !narrow{let version=if metadata.version.is_empty(){self.tr("Неизвестно")}else{metadata.version.clone()};label(ui,row.min+Vec2::new(version_x-1.0,13.0),&version,14.0,false,color,actions_x-version_x-8.0);label(ui,row.min+Vec2::new(version_x-1.0,36.0),&file.name,13.0,false,muted,actions_x-version_x-8.0);}
    let right=row.right()-10.0;let delete=Rect::from_center_size(egui::pos2(right-20.0,row.center().y),Vec2::splat(40.0));self.content_icon(ui,"delete",Rect::from_center_size(delete.center(),Vec2::splat(18.0)),p.muted);if ui.interact(delete,ui.id().with((&key,"delete")),Sense::click()).clicked()&&!busy{self.content_delete=Some((build.key(),file.relative.clone(),content_type(file.category).into(),title.into()));}
    let track=Rect::from_center_size(egui::pos2(right-66.0,row.center().y),Vec2::new(52.0,32.0));if toggle(ui,&key,track,file.enabled,!busy,p){self.downloads.enqueue(self.tr("Изменение состояния проекта"),serde_json::json!({"op":"toggle","target":build.key(),"file":file.relative,"kind":content_type(file.category),"enabled":!file.enabled}));}
    if !metadata.update.is_empty(){let update=Rect::from_center_size(egui::pos2(right-112.0,row.center().y),Vec2::splat(40.0));self.content_icon(ui,"download",update.shrink(11.0),p.accent);if ui.interact(update,ui.id().with((&key,"update")),Sense::click()).clicked()&&!busy{self.downloads.enqueue(self.tr("Обновить"),serde_json::json!({"op":"updateItem","target":build.key(),"file":file.relative,"kind":content_type(file.category)}));}}
    ui.painter().line_segment([row.left_bottom(),row.right_bottom()],Stroke::new(1.0_f32,p.outline));
   }
  });
    }
    pub(super) fn refresh_content_details(&mut self, build: &Build) {
        if self.content_target.as_ref() != Some(&build.key()) {
            self.content_metadata.clear();
        }
        self.content_target = Some(build.key());
        self.content_engine = None;
        if let Some(dir) = library::build_dir(
            &self.game_root,
            &build.id,
            build.directory_name.as_deref(),
            build.pack,
        ) {
            if let Ok(content) = library::scan_content(&dir) {
                self.content = content;
            }
        }
        if let Some(backend) = &self.backend {
            match tasks::start(
                backend,
                &serde_json::json!({"op":"content","target":build.key()}),
                self.context.clone(),
            ) {
                Ok(engine) => self.content_engine = Some(engine),
                Err(error) => self.message = Some(error),
            }
        }
    }
}
impl App {
    pub(super) fn content_remove_many_dialog(&mut self, ctx: &egui::Context) {
        if !self.content_delete_many {
            return;
        }
        let chosen: Vec<_> = self
            .content
            .iter()
            .filter(|file| self.content_selected.contains(&stable(file)))
            .cloned()
            .collect();
        egui::Window::new(self.tr("Удалить выбранные проекты?")).anchor(Align2::CENTER_CENTER,Vec2::ZERO).collapsible(false).resizable(false).default_width(470.0).show(ctx,|ui|{
            ui.label(self.tr("Будут удалены файлы выбранных модов, ресурспаков и шейдеров. Миры сохранятся"));
            egui::ScrollArea::vertical().max_height(200.0).show(ui,|ui|{for file in &chosen {ui.label(self.content_metadata.get(&stable(file)).map(|m|m.title.as_str()).filter(|s|!s.is_empty()).unwrap_or(&file.name));}});
            ui.horizontal(|ui|{if ui.button(self.tr("Отмена")).clicked(){self.content_delete_many=false;} if ui.button(self.tr("Удалить")).clicked(){if let Some(target)=&self.selected {for file in &chosen {self.downloads.enqueue(self.tr("Удалить"),serde_json::json!({"op":"remove","target":target,"file":file.relative,"kind":content_type(file.category)}));}}self.content_selected.clear();self.content_delete_many=false;}});
        });
    }
}
fn mix(base: Color32, color: Color32, f: f32) -> Color32 {
    let c = |a: u8, b: u8| (a as f32 * (1.0 - f) + b as f32 * f).round() as u8;
    Color32::from_rgb(
        c(base.r(), color.r()),
        c(base.g(), color.g()),
        c(base.b(), color.b()),
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn columns_stay_inside_table() {
        for width in [650.0, 1018.0, 1600.0] {
            let (v, a) = columns(width);
            assert!(v > 104.0 && v < a);
            assert!(a + 140.0 <= width);
        }
        let (v, _) = columns(1018.0);
        assert!((v - 522.1667).abs() < 0.1);
    }
}
