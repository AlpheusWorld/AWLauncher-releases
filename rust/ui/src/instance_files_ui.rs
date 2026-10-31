use super::*;
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
fn width(ui: &egui::Ui, text: &str, bold: bool) -> f32 {
    ui.painter()
        .layout_no_wrap(text.to_owned(), font(14.0, bold), Color32::WHITE)
        .size()
        .x
}
fn label(ui: &egui::Ui, rect: Rect, text: &str, size: f32, color: Color32) {
    let mut job =
        egui::text::LayoutJob::simple_singleline(text.to_owned(), font(size, false), color);
    job.wrap.max_width = rect.width().max(0.0);
    job.wrap.max_rows = 1;
    job.wrap.break_anywhere = true;
    let galley = ui.fonts(|fonts| fonts.layout_job(job));
    ui.painter()
        .with_clip_rect(rect.intersect(ui.clip_rect()))
        .galley(
            egui::pos2(rect.left(), rect.center().y - galley.size().y * 0.5),
            galley,
            color,
        );
}
fn button(ui: &mut egui::Ui, id: impl std::hash::Hash, rect: Rect, text: &str, p: Palette) -> bool {
    let response = ui.interact(rect, ui.id().with(id), Sense::click());
    ui.painter().rect_filled(
        rect,
        6.0,
        if response.hovered() {
            p.outline
        } else {
            p.high
        },
    );
    ui.painter().text(
        rect.center(),
        Align2::CENTER_CENTER,
        text,
        font(14.0, true),
        p.text,
    );
    response.clicked()
}
fn size_label(bytes: u64) -> String {
    let gib = 1024_u64.pow(3);
    let mib = 1024_u64.pow(2);
    if bytes >= gib {
        format!("{:.1} ГБ", bytes as f64 / gib as f64).replace('.', ",")
    } else if bytes >= mib {
        format!("{:.0} МБ", bytes as f64 / mib as f64)
    } else if bytes >= 1024 {
        format!("{:.0} КБ", bytes as f64 / 1024.0)
    } else {
        format!("{bytes} Б")
    }
}

impl App {
    fn open_files_folder(&mut self, build: &Build, relative: &Path) {
        let result = self
            .file_root(build)
            .ok_or_else(|| "Некорректный путь сборки".to_owned())
            .and_then(|root| {
                files::show_folder(&root, relative).map_err(|error| error.to_string())
            });
        if let Err(error) = result {
            self.message = Some(format!("Не удалось открыть папку: {error}"));
        }
    }
    pub(super) fn instance_files(&mut self, ui: &mut egui::Ui, build: &Build) {
        let p = self.palette;
        let worlds = self.instance_tab == InstanceTab::Worlds;
        ui.spacing_mut().item_spacing.y = 12.0;
        let (toolbar, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 48.0), Sense::hover());
        let refresh = Rect::from_min_size(
            toolbar.right_top() - Vec2::new(48.0, 0.0),
            Vec2::splat(48.0),
        );
        let open_text = self.tr("Открыть папку");
        let open_width = width(ui, &open_text, true) + 66.0;
        let open = Rect::from_min_size(
            egui::pos2(refresh.left() - 8.0 - open_width, toolbar.top() + 2.0),
            Vec2::new(open_width, 44.0),
        );
        let mut left = toolbar.left();
        let mut go_back = false;
        if !self.file_relative.as_os_str().is_empty() {
            let text = self.tr("Назад");
            let back_width = width(ui, &text, true) + 36.0;
            let back = Rect::from_min_size(
                toolbar.min + Vec2::new(0.0, 2.0),
                Vec2::new(back_width, 44.0),
            );
            go_back = button(ui, "files-parent", back, &text, p);
            left = back.right() + 8.0;
        }
        let search = Rect::from_min_max(
            egui::pos2(left, toolbar.top() + 4.0),
            egui::pos2((open.left() - 8.0).max(left), toolbar.top() + 44.0),
        );
        ui.painter().rect_filled(search, 6.0, p.high);
        ui.painter().rect_stroke(
            search,
            6.0,
            Stroke::new(1.0_f32, p.outline.gamma_multiply(0.75)),
            egui::StrokeKind::Inside,
        );
        let show_icon = search.width() >= 80.0;
        if show_icon {
            self.draw_icon(
                ui,
                "search",
                Rect::from_center_size(
                    search.left_center() + Vec2::new(27.0, 0.0),
                    Vec2::splat(18.0),
                ),
                p.muted,
            );
        }
        let input = Rect::from_min_max(
            search.min + Vec2::new(if show_icon { 46.0 } else { 8.0 }, 8.0),
            search.max - Vec2::new(10.0, 6.0),
        );
        if input.width() > 0.0 {
            let hint = self.tr(if worlds {
                "Поиск миров…"
            } else {
                "Поиск файлов…"
            });
            let mut child = ui.new_child(egui::UiBuilder::new().max_rect(input));
            child.set_clip_rect(search.intersect(ui.clip_rect()));
            child.add_sized(
                input.size(),
                egui::TextEdit::singleline(&mut self.file_query)
                    .frame(false)
                    .margin(egui::Margin::ZERO)
                    .font(font(14.0, false))
                    .hint_text(hint),
            );
        }
        if self.icon_button(
            ui,
            "files-open-current",
            open,
            "Открыть папку",
            "folder",
            p.high,
            p.text,
        ) {
            let relative = self.file_relative.clone();
            self.open_files_folder(build, &relative);
        }
        self.draw_icon(
            ui,
            "refresh",
            Rect::from_center_size(refresh.center(), Vec2::splat(24.0)),
            p.muted,
        );
        if ui
            .interact(refresh, ui.id().with("files-refresh"), Sense::click())
            .on_hover_text(self.tr("Перезагрузить"))
            .clicked()
        {
            self.refresh_files(build);
        }
        if go_back {
            self.file_relative.pop();
            self.file_query.clear();
            self.refresh_files(build);
        }

        let (caption, _) =
            ui.allocate_exact_size(Vec2::new(ui.available_width(), 18.0), Sense::hover());
        let path = if self.file_relative.as_os_str().is_empty() {
            self.tr(if worlds {
                "Миры сборки"
            } else {
                "Папка сборки"
            })
        } else {
            self.file_relative.to_string_lossy().into_owned()
        };
        label(ui, caption, &path, 13.0, p.muted);
        let (viewport, _) = ui.allocate_exact_size(
            Vec2::new(ui.available_width(), ui.available_height().max(0.0)),
            Sense::hover(),
        );
        let mut list = ui.new_child(egui::UiBuilder::new().max_rect(viewport));
        list.set_clip_rect(viewport);
        list.spacing_mut().item_spacing = Vec2::ZERO;
        let query = self.file_query.trim().to_lowercase();
        let entries: Vec<_> = self
            .file_entries
            .iter()
            .filter(|file| query.is_empty() || file.name.to_lowercase().contains(&query))
            .cloned()
            .collect();
        let mut navigate = None;
        let mut show_folder = None;
        if self.file_entries.is_empty() {
            let icon =
                Rect::from_center_size(viewport.center() - Vec2::new(0.0, 48.0), Vec2::splat(44.0));
            self.draw_icon(ui, if worlds { "layers" } else { "folder" }, icon, p.muted);
            ui.painter().text(
                viewport.center() - Vec2::new(0.0, 3.0),
                Align2::CENTER_CENTER,
                self.tr(if worlds {
                    "Миров пока нет"
                } else {
                    "Папка пуста"
                }),
                font(18.0, true),
                p.text,
            );
            ui.painter().text(
                viewport.center() + Vec2::new(0.0, 28.0),
                Align2::CENTER_CENTER,
                self.tr(if worlds {
                    "Создай мир в игре или перенеси сборку"
                } else {
                    "Добавь файлы через проводник"
                }),
                font(13.0, false),
                p.muted,
            );
        } else {
            egui::ScrollArea::vertical()
                .id_salt((
                    "instance-file-rows",
                    build.key(),
                    worlds,
                    &self.file_relative,
                ))
                .auto_shrink([false, false])
                .show_rows(&mut list, 69.0, entries.len(), |ui, range| {
                    let folder_text = self.tr("Папка");
                    let folder_width = width(ui, &folder_text, true) + 36.0;
                    for index in range {
                        let file = &entries[index];
                        let (row, _) = ui.allocate_exact_size(
                            Vec2::new(ui.available_width(), 69.0),
                            Sense::hover(),
                        );
                        let content = Rect::from_min_max(row.min, row.max - Vec2::new(0.0, 1.0));
                        let action = Rect::from_min_size(
                            egui::pos2(content.right() - 12.0 - folder_width, content.top() + 12.0),
                            Vec2::new(folder_width, 44.0),
                        );
                        let metadata = if file.directory {
                            folder_text.clone()
                        } else {
                            translations::text(size_label(file.bytes))
                        };
                        let metadata_width = ui
                            .painter()
                            .layout_no_wrap(metadata.clone(), font(13.0, false), p.muted)
                            .size()
                            .x;
                        let metadata_rect = Rect::from_min_size(
                            egui::pos2(action.left() - 14.0 - metadata_width, content.top() + 12.0),
                            Vec2::new(metadata_width, 44.0),
                        );
                        let name = Rect::from_min_max(
                            content.min + Vec2::new(50.0, 12.0),
                            egui::pos2(metadata_rect.left() - 14.0, content.bottom() - 12.0),
                        );
                        let navigation = Rect::from_min_max(
                            content.min,
                            egui::pos2(action.left() - 7.0, content.bottom()),
                        );
                        let response = ui.interact(
                            navigation,
                            ui.id().with((&file.relative, "navigate")),
                            if file.directory {
                                Sense::click()
                            } else {
                                Sense::hover()
                            },
                        );
                        if file.directory && response.hovered() {
                            ui.painter().rect_filled(content, 8.0, p.panel);
                        }
                        if file.directory && response.clicked() {
                            navigate = Some(file.relative.clone());
                        }
                        self.draw_icon(
                            ui,
                            if file.directory { "folder" } else { "file" },
                            Rect::from_center_size(
                                content.left_center() + Vec2::new(24.0, 0.0),
                                Vec2::splat(24.0),
                            ),
                            p.muted,
                        );
                        label(ui, name, &file.name, 14.0, p.text);
                        label(ui, metadata_rect, &metadata, 13.0, p.muted);
                        if button(ui, (&file.relative, "show-folder"), action, &folder_text, p) {
                            show_folder = Some(if file.directory {
                                file.relative.clone()
                            } else {
                                self.file_relative.clone()
                            });
                        }
                        ui.painter().line_segment(
                            [
                                row.left_bottom() - Vec2::new(0.0, 0.5),
                                row.right_bottom() - Vec2::new(0.0, 0.5),
                            ],
                            Stroke::new(1.0_f32, p.outline),
                        );
                    }
                });
        }
        if let Some(relative) = show_folder {
            self.open_files_folder(build, &relative);
        }
        if let Some(relative) = navigate {
            self.file_relative = relative;
            self.file_query.clear();
            self.refresh_files(build);
        }
    }
}
