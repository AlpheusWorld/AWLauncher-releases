fn resource(id: &str) -> Option<&'static str> {
    match id {
        "EN" => Some(include_str!("../../../src/main/resources/i18n/en.json")),
        "EN_GB" => Some(include_str!("../../../src/main/resources/i18n/en-GB.json")),
        "ES" => Some(include_str!("../../../src/main/resources/i18n/es.json")),
        "ES_MX" => Some(include_str!("../../../src/main/resources/i18n/es-MX.json")),
        "DE" => Some(include_str!("../../../src/main/resources/i18n/de-DE.json")),
        "FR" => Some(include_str!("../../../src/main/resources/i18n/fr-FR.json")),
        "PT_BR" => Some(include_str!("../../../src/main/resources/i18n/pt-BR.json")),
        "PT" => Some(include_str!("../../../src/main/resources/i18n/pt-PT.json")),
        "IT" => Some(include_str!("../../../src/main/resources/i18n/it-IT.json")),
        "NL" => Some(include_str!("../../../src/main/resources/i18n/nl-NL.json")),
        "PL" => Some(include_str!("../../../src/main/resources/i18n/pl-PL.json")),
        "TR" => Some(include_str!("../../../src/main/resources/i18n/tr-TR.json")),
        "ZH_CN" => Some(include_str!("../../../src/main/resources/i18n/zh-CN.json")),
        "ZH_TW" => Some(include_str!("../../../src/main/resources/i18n/zh-TW.json")),
        "JA" => Some(include_str!("../../../src/main/resources/i18n/ja-JP.json")),
        "ID" => Some(include_str!("../../../src/main/resources/i18n/id-ID.json")),
        "FA" => Some(include_str!("../../../src/main/resources/i18n/fa-IR.json")),
        "CS" => Some(include_str!("../../../src/main/resources/i18n/cs-CZ.json")),
        "VI" => Some(include_str!("../../../src/main/resources/i18n/vi-VN.json")),
        "KO" => Some(include_str!("../../../src/main/resources/i18n/ko-KR.json")),
        "UK" => Some(include_str!("../../../src/main/resources/i18n/uk-UA.json")),
        "AR" => Some(include_str!("../../../src/main/resources/i18n/ar-SA.json")),
        "HU" => Some(include_str!("../../../src/main/resources/i18n/hu-HU.json")),
        "SV" => Some(include_str!("../../../src/main/resources/i18n/sv-SE.json")),
        "RO" => Some(include_str!("../../../src/main/resources/i18n/ro-RO.json")),
        "EL" => Some(include_str!("../../../src/main/resources/i18n/el-GR.json")),
        "DA" => Some(include_str!("../../../src/main/resources/i18n/da-DK.json")),
        "FI" => Some(include_str!("../../../src/main/resources/i18n/fi-FI.json")),
        "HE" => Some(include_str!("../../../src/main/resources/i18n/he-IL.json")),
        "SK" => Some(include_str!("../../../src/main/resources/i18n/sk-SK.json")),
        "TH" => Some(include_str!("../../../src/main/resources/i18n/th-TH.json")),
        "BG" => Some(include_str!("../../../src/main/resources/i18n/bg-BG.json")),
        "HR" => Some(include_str!("../../../src/main/resources/i18n/hr-HR.json")),
        "SR" => Some(include_str!("../../../src/main/resources/i18n/sr-RS.json")),
        "NB" => Some(include_str!("../../../src/main/resources/i18n/nb-NO.json")),
        "LT" => Some(include_str!("../../../src/main/resources/i18n/lt-LT.json")),
        "SL" => Some(include_str!("../../../src/main/resources/i18n/sl-SI.json")),
        "CA" => Some(include_str!("../../../src/main/resources/i18n/ca-ES.json")),
        "ET" => Some(include_str!("../../../src/main/resources/i18n/et-EE.json")),
        "LV" => Some(include_str!("../../../src/main/resources/i18n/lv-LV.json")),
        "BN" => Some(include_str!("../../../src/main/resources/i18n/bn-BD.json")),
        "HI" => Some(include_str!("../../../src/main/resources/i18n/hi-IN.json")),
        "BS" => Some(include_str!("../../../src/main/resources/i18n/bs-BA.json")),
        "AZ" => Some(include_str!("../../../src/main/resources/i18n/az-AZ.json")),
        "KA" => Some(include_str!("../../../src/main/resources/i18n/ka-GE.json")),
        "IS" => Some(include_str!("../../../src/main/resources/i18n/is-IS.json")),
        "MS" => Some(include_str!("../../../src/main/resources/i18n/ms-MY.json")),
        "KK" => Some(include_str!("../../../src/main/resources/i18n/kk-KZ.json")),
        "HY" => Some(include_str!("../../../src/main/resources/i18n/hy-AM.json")),
        _ => None,
    }
}

use std::{
    cell::RefCell,
    collections::HashMap,
    sync::{Arc, Mutex, OnceLock},
};
thread_local! {static ACTIVE:RefCell<String>=RefCell::new("EN".to_owned());}
pub fn set_language(id: &str) {
    ACTIVE.with(|language| {
        if language.borrow().as_str() != id {
            *language.borrow_mut() = id.to_owned();
        }
    });
}
pub fn text(value: impl AsRef<str>) -> String {
    ACTIVE.with(|language| translate(&language.borrow(), value.as_ref()))
}
struct Pattern {
    regex: regex::Regex,
    slots: Vec<String>,
    target: String,
    literal: String,
}
struct Registry {
    exact: HashMap<String, String>,
    folded: HashMap<String, String>,
    patterns: Vec<Pattern>,
    fragments: Vec<(String, String)>,
    results: Mutex<HashMap<String, String>>,
}
fn cyrillic(value: &str) -> bool {
    value.chars().any(|c| matches!(c, 'А'..='я' | 'Ё' | 'ё'))
}
fn registry(id: &str) -> Arc<Registry> {
    static MAPS: OnceLock<Mutex<HashMap<String, Arc<Registry>>>> = OnceLock::new();
    let mut maps = MAPS
        .get_or_init(|| Mutex::new(HashMap::new()))
        .lock()
        .unwrap();
    if let Some(registry) = maps.get(id) {
        return registry.clone();
    }
    let mut exact: HashMap<String, String> = serde_json::from_str(
        resource(id).unwrap_or(include_str!("../../../src/main/resources/i18n/en.json")),
    )
    .unwrap_or_default();
    let aliases: HashMap<String, String> =
        serde_json::from_str(include_str!("../assets/native-aliases.json"))
            .expect("Native translation aliases");
    for (source, canonical) in aliases {
        if let Some(target) = exact.get(&canonical).cloned() {
            exact.entry(source).or_insert(target);
        }
    }
    let extra: HashMap<String, String> =
        serde_json::from_str(include_str!("../assets/native-en.json"))
            .expect("Native interface translations");
    for (key, value) in extra {
        exact.entry(key).or_insert(value);
    }
    let slots = regex::Regex::new(r"\{(\d+)\}").unwrap();
    let mut entries: Vec<_> = exact.iter().collect();
    entries.sort_by(|a, b| {
        slots
            .replace_all(b.0, "")
            .len()
            .cmp(&slots.replace_all(a.0, "").len())
    });
    let mut folded = HashMap::new();
    let mut patterns = Vec::new();
    let mut fragments = Vec::new();
    for (source, target) in entries {
        let captures: Vec<_> = slots.captures_iter(source).collect();
        if captures.is_empty() {
            folded.insert(source.to_lowercase(), target.clone());
            if source.chars().count() > 3 {
                fragments.push((source.clone(), target.clone()));
            }
        } else {
            let mut expression = String::from("(?is)^");
            let mut offset = 0;
            let mut literal = String::new();
            let mut names = Vec::new();
            for capture in captures {
                let m = capture.get(0).unwrap();
                let part = &source[offset..m.start()];
                if part.len() > literal.len() {
                    literal = part.to_lowercase();
                }
                expression.push_str(&regex::escape(part));
                expression.push_str("(.*?)");
                names.push(capture[1].to_owned());
                offset = m.end();
            }
            let part = &source[offset..];
            if part.len() > literal.len() {
                literal = part.to_lowercase();
            }
            expression.push_str(&regex::escape(part));
            expression.push('$');
            patterns.push(Pattern {
                regex: regex::Regex::new(&expression).unwrap(),
                slots: names,
                target: target.clone(),
                literal,
            });
        }
    }
    let registry = Arc::new(Registry {
        exact,
        folded,
        patterns,
        fragments,
        results: Mutex::new(HashMap::new()),
    });
    if maps.len() >= 4 {
        maps.clear();
    }
    maps.insert(id.to_owned(), registry.clone());
    registry
}
fn apply(value: &str, id: &str, depth: usize) -> String {
    if depth > 3 || id == "RU" || value.is_empty() {
        return value.to_owned();
    }
    let registry = registry(id);
    if let Some(result) = registry.exact.get(value) {
        return result.clone();
    }
    let lowercase = value.to_lowercase();
    let upper = value.chars().any(char::is_alphabetic) && value == value.to_uppercase();
    if let Some(result) = registry.folded.get(&lowercase) {
        return if upper {
            result.to_uppercase()
        } else {
            result.clone()
        };
    }
    for pattern in &registry.patterns {
        if !lowercase.contains(&pattern.literal) {
            continue;
        }
        if let Some(captures) = pattern.regex.captures(value) {
            static SLOTS: OnceLock<regex::Regex> = OnceLock::new();
            let translated = SLOTS
                .get_or_init(|| regex::Regex::new(r"\{(\d+)\}").unwrap())
                .replace_all(&pattern.target, |slot: &regex::Captures| {
                    pattern
                        .slots
                        .iter()
                        .position(|name| name == &slot[1])
                        .and_then(|index| captures.get(index + 1))
                        .map(|capture| apply(capture.as_str(), id, depth + 1))
                        .unwrap_or_else(|| slot[0].to_owned())
                })
                .into_owned();
            return if upper {
                translated.to_uppercase()
            } else {
                translated
            };
        }
    }
    let mut result = value.to_owned();
    for (source, target) in &registry.fragments {
        if result.contains(source) {
            result = result.replace(source, target);
        }
    }
    if result == value && id != "EN" {
        apply(value, "EN", depth + 1)
    } else {
        result
    }
}
pub fn translate(id: &str, value: &str) -> String {
    if id == "RU" || !cyrillic(value) {
        return value.to_owned();
    }
    let registry = registry(id);
    if let Some(result) = registry.results.lock().unwrap().get(value).cloned() {
        return result;
    }
    let result = apply(value, id, 0);
    let mut results = registry.results.lock().unwrap();
    if results.len() >= 512 {
        results.clear();
    }
    results.insert(value.to_owned(), result.clone());
    result
}
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
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn original_labels_are_reused() {
        assert_eq!(translate("EN", "Контент"), "Content");
        assert_eq!(translate("RU", "Контент"), "Контент");
        assert_eq!(translate("EN", "Проекты ({0})"), "Projects ({0})");
    }
    #[test]
    fn formats_counts_units_and_dates() {
        assert_eq!(
            translate("EN", "Последний запуск: 30 окт"),
            "Last played: 30 Oct"
        );
        assert_eq!(translate("EN", "Проекты (80)"), "Projects (80)");
        assert_eq!(translate("EN", "1084 ч 28 мин"), "1084 h 28 min");
        assert_eq!(translate("EN", "ОБЩИЕ"), "GENERAL");
    }
    #[test]
    fn languages_use_original_dictionaries() {
        for (id, expected) in [("DE", "Inhalt"), ("FR", "Contenu"), ("RU", "Контент")] {
            assert_eq!(translate(id, "Контент"), expected);
        }
        assert_ne!(translate("JA", "Добавить файлы"), "Добавить файлы");
    }
    #[test]
    fn render_language_changes_without_restart() {
        set_language("EN");
        assert_eq!(text("Настройки"), "Settings");
        set_language("DE");
        assert_eq!(text("Настройки"), "Einstellung");
        set_language("RU");
        assert_eq!(text("Настройки"), "Настройки");
        set_language("EN");
    }
    #[test]
    fn native_messages_have_english_fallbacks() {
        let entries: HashMap<String, String> =
            serde_json::from_str(include_str!("../assets/native-en.json")).unwrap();
        for source in entries.keys() {
            assert!(
                !cyrillic(&translate("EN", source)),
                "Untranslated native label: {source}"
            );
        }
    }
    #[test]
    fn every_registered_language_loads_offline() {
        for language in crate::settings::languages() {
            let value = translate(&language.id, "Добавить файлы");
            assert!(!value.is_empty(), "Missing locale {}", language.id);
            if language.id != "RU" {
                assert_ne!(
                    value, "Добавить файлы",
                    "Missing translation for {}",
                    language.id
                );
            }
        }
    }
}
