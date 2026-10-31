pub use crate::translation_core::{set_language, text, translate};

pub fn ui_text(ui: &eframe::egui::Ui, value: &str) -> String {
    let language = ui
        .ctx()
        .data(|data| data.get_temp::<String>(eframe::egui::Id::new("aw-language")))
        .unwrap_or_else(|| "EN".into());
    translate(&language, value)
}
impl super::App {
    pub(super) fn play_time_label(&self, millis: i64) -> String {
        let minutes = millis.max(0) / 60_000;
        if minutes < 1 {
            self.tr("< 1 мин")
        } else if minutes < 60 {
            self.tr("{0} мин").replace("{0}", &minutes.to_string())
        } else if minutes % 60 == 0 {
            self.tr("{0} ч").replace("{0}", &(minutes / 60).to_string())
        } else {
            self.tr("{0} ч {1} мин")
                .replace("{0}", &(minutes / 60).to_string())
                .replace("{1}", &(minutes % 60).to_string())
        }
    }
    pub(super) fn tr(&self, value: &str) -> String {
        translate(&self.preferences.string("language", "EN"), value)
    }
}
