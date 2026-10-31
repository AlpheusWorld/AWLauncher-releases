use super::*;
impl App {
    pub(super) fn downloads_page(&mut self, ui: &mut egui::Ui) {
        ui.label(
            RichText::new(translations::text("Загрузки"))
                .family(FontFamily::Name("aw-semibold".into()))
                .size(26.0),
        );
        ui.add_space(16.0);
        ui.horizontal_wrapped(|ui| {
            if ui
                .button(translations::text(if self.downloads.paused {
                    "Продолжить очередь"
                } else {
                    "Приостановить очередь"
                }))
                .clicked()
            {
                self.downloads.paused = !self.downloads.paused;
            }
            if ui
                .add_enabled(
                    self.downloads.active.is_none(),
                    egui::Button::new(translations::text("Очистить завершённые")),
                )
                .clicked()
            {
                self.downloads.clear();
            }
        });
        if self.downloads.paused {
            ui.label(
                RichText::new(translations::text(
                    "Текущая задача завершится. Остальные ждут продолжения очереди",
                ))
                .color(self.palette.muted),
            );
        }
        ui.add_space(16.0);
        let count = self
            .downloads
            .items
            .iter()
            .filter(|i| i.status.pending())
            .count();
        ui.label(
            RichText::new(translations::text(format!("Очередь  ·  {count} активных"))).size(18.0),
        );
        ui.add_space(12.0);
        let mut cancel = None;
        let mut retry = None;
        egui::ScrollArea::vertical().show(ui, |ui| {
            if self.downloads.items.is_empty() {
                ui.add_space(70.0);
                ui.label(
                    RichText::new(translations::text("Загрузок пока нет"))
                        .family(FontFamily::Name("aw-semibold".into()))
                        .size(18.0),
                );
                ui.label(
                    RichText::new(translations::text(
                        "Добавляй моды и сборки из каталога — они загрузятся по очереди",
                    ))
                    .color(self.palette.muted),
                );
            }
            for (index, item) in self.downloads.items.iter().enumerate() {
                egui::Frame::NONE
                    .fill(if item.status == tasks::Status::Running {
                        self.palette.high
                    } else {
                        self.palette.panel
                    })
                    .corner_radius(8.0)
                    .inner_margin(16.0)
                    .show(ui, |ui| {
                        ui.set_min_width((ui.available_width() - 32.0).max(0.0));
                        ui.label(
                            RichText::new(translations::text(&item.title))
                                .family(FontFamily::Name("aw-semibold".into()))
                                .size(15.0),
                        );
                        let status = match item.status {
                            tasks::Status::Waiting => "В очереди",
                            tasks::Status::Running => {
                                if item.stage.is_empty() {
                                    "Подготовка"
                                } else {
                                    &item.stage
                                }
                            }
                            tasks::Status::Cancelling => "Отменяю загрузку…",
                            tasks::Status::Done => "Завершено",
                            tasks::Status::Cancelled => "Отменено",
                            tasks::Status::Failed => "Не удалось загрузить",
                        };
                        ui.label(RichText::new(translations::text(status)).color(
                            if item.status == tasks::Status::Failed {
                                Color32::LIGHT_RED
                            } else {
                                self.palette.muted
                            },
                        ));
                        if matches!(
                            item.status,
                            tasks::Status::Running | tasks::Status::Cancelling
                        ) {
                            ui.add(
                                egui::ProgressBar::new(item.fraction.clamp(0.0, 1.0))
                                    .fill(self.palette.accent)
                                    .show_percentage(),
                            );
                            ui.label(
                                RichText::new(translations::text(format!(
                                    "{} / {} МБ · {} КБ/с",
                                    item.bytes / 1024 / 1024,
                                    item.total / 1024 / 1024,
                                    item.speed / 1024
                                )))
                                .color(self.palette.muted),
                            );
                        }
                        if let Some(error) = &item.error {
                            ui.colored_label(Color32::LIGHT_RED, translations::text(error));
                        }
                        if let Some((code, url)) = &item.login {
                            ui.label(
                                RichText::new(code)
                                    .family(FontFamily::Name("aw-semibold".into()))
                                    .size(22.0),
                            );
                            ui.hyperlink_to(translations::text("Открыть страницу Microsoft"), url);
                            if ui.button(translations::text("Скопировать код")).clicked()
                            {
                                ui.ctx().copy_text(code.clone());
                            }
                        }
                        if matches!(item.status, tasks::Status::Waiting | tasks::Status::Running)
                            && ui.button(translations::text("Отменить")).clicked()
                        {
                            cancel = Some(index);
                        }
                        if matches!(
                            item.status,
                            tasks::Status::Failed | tasks::Status::Cancelled
                        ) && ui.button(translations::text("Повторить")).clicked()
                        {
                            retry = Some(index);
                        }
                    });
                ui.add_space(8.0);
            }
        });
        if let Some(index) = cancel {
            self.downloads.cancel(index);
        }
        if let Some(index) = retry {
            self.downloads.retry(index);
        }
    }
}
