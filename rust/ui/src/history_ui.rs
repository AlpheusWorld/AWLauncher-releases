use super::*;
impl App {
    pub(super) fn refresh_history(&mut self, build: &Build) {
        self.history_engine = None;
        match self
            .backend
            .as_ref()
            .ok_or_else(|| "Не найден встроенный движок".to_owned())
            .and_then(|backend| {
                tasks::start(
                    backend,
                    &serde_json::json!({"op":"history","target":build.key()}),
                    self.context.clone(),
                )
            }) {
            Ok(process) => self.history_engine = Some(process),
            Err(error) => self.message = Some(error),
        }
    }
    pub(super) fn history_page(&mut self, ui: &mut egui::Ui, build: &Build) {
        let events: Vec<_> = self
            .history_engine
            .as_ref()
            .map(|engine| engine.rx.try_iter().collect())
            .unwrap_or_default();
        for event in events {
            match event {
                tasks::Event::Data(event) if event["type"] == "done" => {
                    self.history_rows = event["data"].as_array().cloned().unwrap_or_default();
                    self.message = None;
                }
                tasks::Event::Data(event) if event["type"] == "failed" => {
                    self.message = event["data"].as_str().map(str::to_owned)
                }
                tasks::Event::Exited(_) => self.history_engine = None,
                _ => {}
            }
        }
        let pending = self
            .downloads
            .items
            .iter()
            .any(|i| i.status.pending() && i.request["target"] == build.key());
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    !pending,
                    egui::Button::new(translations::text("Сохранить состояние")),
                )
                .clicked()
            {
                self.downloads.enqueue(
                    format!("Сохранение {}", build.name),
                    serde_json::json!({"op":"snapshot","target":build.key()}),
                );
            }
            if ui
                .add_enabled(
                    self.history_engine.is_none(),
                    egui::Button::new(translations::text("Обновить")),
                )
                .clicked()
            {
                self.refresh_history(build);
            }
            if ui
                .add_enabled(
                    !pending && !self.history_rows.is_empty(),
                    egui::Button::new(translations::text("Вернуть рабочее состояние")),
                )
                .clicked()
            {
                self.history_restore = self
                    .history_rows
                    .first()
                    .and_then(|row| row["id"].as_str())
                    .map(str::to_owned);
            }
        });
        ui.label(RichText::new(translations::text("Перед восстановлением текущее состояние сохраняется отдельно. Миры восстанавливаются только при явном выборе.")).color(self.palette.muted).size(12.0));
        if self.history_engine.is_some() {
            ui.label(translations::text("Читаю историю…"));
        }
        egui::ScrollArea::vertical().show(ui, |ui| {
            for row in &self.history_rows {
                egui::Frame::NONE
                    .fill(self.palette.panel)
                    .corner_radius(8.0)
                    .inner_margin(14.0)
                    .show(ui, |ui| {
                        ui.set_min_width((ui.available_width() - 28.0).max(0.0));
                        ui.label(
                            RichText::new(translations::text(
                                row["title"].as_str().unwrap_or("Состояние сборки"),
                            ))
                            .family(FontFamily::Name("aw-semibold".into())),
                        );
                        let date = row["createdAt"]
                            .as_i64()
                            .and_then(chrono::DateTime::from_timestamp_millis)
                            .map(|date| {
                                date.with_timezone(&chrono::Local)
                                    .format("%d.%m.%Y %H:%M")
                                    .to_string()
                            })
                            .unwrap_or_default();
                        ui.label(
                            RichText::new(translations::text(format!(
                                "{} · {} · {} МБ",
                                date,
                                row["gameVersion"].as_str().unwrap_or(""),
                                row["sizeBytes"].as_u64().unwrap_or(0) / 1024 / 1024
                            )))
                            .color(self.palette.muted),
                        );
                        if let Some(changes) = row["changes"].as_array() {
                            for change in changes.iter().take(5) {
                                ui.label(format!(
                                    "{}: {} → {}",
                                    change["title"].as_str().unwrap_or(""),
                                    change["before"].as_str().unwrap_or("—"),
                                    change["after"].as_str().unwrap_or("—")
                                ));
                            }
                        }
                        if ui
                            .add_enabled(
                                !pending,
                                egui::Button::new(translations::text("Восстановить")),
                            )
                            .clicked()
                        {
                            self.history_restore = row["id"].as_str().map(str::to_owned);
                        }
                    });
                ui.add_space(8.0);
            }
        });
        if self.history_rows.is_empty() && self.history_engine.is_none() {
            ui.label(translations::text("История пока пуста"));
        }
        if let Some(id) = self.history_restore.clone() {
            egui::Window::new(translations::text("Восстановить состояние сборки?")).anchor(egui::Align2::CENTER_CENTER,Vec2::ZERO).collapsible(false).resizable(false).show(ui.ctx(),|ui|{
            ui.label(translations::text("Текущее состояние сохранится отдельным снимком. Прогресс миров не изменится."));ui.horizontal(|ui|{if ui.button(translations::text("Отмена")).clicked(){self.history_restore=None;}if ui.button(translations::text("Восстановить")).clicked(){self.downloads.enqueue(format!("Восстановление {}",build.name),serde_json::json!({"op":"restore","target":build.key(),"snapshot":id}));self.history_restore=None;}});
        });
        }
    }
}
