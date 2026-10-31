use serde_json::{Map, Value};
use std::{
    fs, io,
    path::{Path, PathBuf},
};

pub const JVM_ARGS:&str="-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=32M -XX:+DisableExplicitGC";

pub struct Store {
    pub path: PathBuf,
    pub values: Map<String, Value>,
    pub error: Option<String>,
    pub total_mb: i64,
}
impl Store {
    pub fn load(path: PathBuf) -> Self {
        let read = read(&path);
        let (values, error) = match read {
            Ok(values) => (values, None),
            Err(error) => (Map::new(), Some(error.to_string())),
        };
        Self {
            path,
            values,
            error,
            total_mb: total_memory(),
        }
    }
    pub fn boolean(&self, key: &str, default: bool) -> bool {
        self.values
            .get(key)
            .and_then(Value::as_bool)
            .unwrap_or(default)
    }
    pub fn number(&self, key: &str, default: i64) -> i64 {
        self.values
            .get(key)
            .and_then(Value::as_i64)
            .unwrap_or(default)
    }
    pub fn string(&self, key: &str, default: &str) -> String {
        self.values
            .get(key)
            .and_then(Value::as_str)
            .unwrap_or(default)
            .to_owned()
    }
    pub fn set(&mut self, key: &str, value: Value) -> bool {
        let result = (|| -> io::Result<Map<String, Value>> {
            let parent = self
                .path
                .parent()
                .ok_or_else(|| io::Error::other("Нет папки настроек"))?;
            fs::create_dir_all(parent)?;
            let _lock = fs::OpenOptions::new()
                .read(true)
                .write(true)
                .create(true)
                .open(parent.join("launcher.lock"))?;
            _lock.try_lock().map_err(|_| {
                io::Error::other(
                    "Дождись завершения операции или закрой прежний экземпляр AWLauncher перед сохранением",
                )
            })?;
            let mut values = read(&self.path)?;
            validate(key, &value, self.total_mb)?;
            values.insert(key.to_owned(), value);
            let bytes = serde_json::to_vec_pretty(&values).map_err(io::Error::other)?;
            super::library::atomic_write(&self.path, &bytes)?;
            Ok(values)
        })();
        match result {
            Ok(values) => {
                self.values = values;
                self.error = None;
                true
            }
            Err(error) => {
                self.error = Some(error.to_string());
                false
            }
        }
    }
    pub fn reset_verify_cache(&mut self) -> bool {
        let result = (|| -> io::Result<()> {
            let parent = self
                .path
                .parent()
                .ok_or_else(|| io::Error::other("Нет папки настроек"))?;
            let lock = fs::OpenOptions::new()
                .read(true)
                .write(true)
                .create(true)
                .open(parent.join("launcher.lock"))?;
            lock.try_lock().map_err(|_| {
                io::Error::other("Дождись завершения операции или закрой прежний экземпляр AWLauncher перед сбросом кэша")
            })?;
            match fs::remove_file(parent.join("cache/verify.index")) {
                Ok(()) => Ok(()),
                Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
                Err(e) => Err(e),
            }
        })();
        match result {
            Ok(()) => {
                self.error = None;
                true
            }
            Err(e) => {
                self.error = Some(e.to_string());
                false
            }
        }
    }
}
fn read(path: &Path) -> io::Result<Map<String, Value>> {
    match fs::read(path) {
        Ok(bytes) => serde_json::from_slice::<Value>(&bytes)
            .map_err(io::Error::other)?
            .as_object()
            .cloned()
            .ok_or_else(|| io::Error::other("Настройки должны быть JSON-объектом")),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(Map::new()),
        Err(e) => Err(e),
    }
}
fn validate(key: &str, value: &Value, total: i64) -> io::Result<()> {
    let valid = match key {
        "keepLauncherOpen"
        | "friendsEnabled"
        | "showSnapshots"
        | "showOldVersions"
        | "discordPresence"
        | "discordShowLauncher"
        | "discordShowInstance"
        | "discordShowServer" => value.is_boolean(),
        "memoryMb" => value
            .as_i64()
            .is_some_and(|v| (512..=(total - 1024).max(512)).contains(&v)),
        "downloadConcurrency" => value
            .as_i64()
            .is_some_and(|v| v == 0 || (4..=32).contains(&v)),
        "themeMode" => value
            .as_str()
            .is_some_and(|v| ["SYSTEM", "DARK", "LIGHT", "OLED"].contains(&v)),
        "jvmArgs" => value
            .as_str()
            .is_some_and(|v| !v.contains('\0') && v.len() < 32768),
        "language" => value
            .as_str()
            .is_some_and(|id| languages().iter().any(|l| l.id == id)),
        "libraryGroups" => value.as_array().is_some_and(|groups| {
            let mut ids = std::collections::HashSet::new();
            let mut names = std::collections::HashSet::new();
            groups.len() <= 128
                && groups.iter().all(
                    |group| match (group["id"].as_str(), group["name"].as_str()) {
                        (Some(id), Some(name)) => {
                            !id.is_empty()
                                && id.len() <= 128
                                && !name.trim().is_empty()
                                && name.chars().count() <= 80
                                && !name.chars().any(char::is_control)
                                && ids.insert(id)
                                && names.insert(name.to_lowercase())
                        }
                        _ => false,
                    },
                )
        }),
        _ => false,
    };
    if valid {
        Ok(())
    } else {
        Err(io::Error::other("Некорректное значение настройки"))
    }
}
pub fn recommended(total: i64) -> i64 {
    let quarter = (((total + 512) / 1024 * 1024) / 4).clamp(2048, 8192);
    [2048, 4096, 6144, 8192, 12288, 16384]
        .into_iter()
        .filter(|v| *v <= (total - 2048).max(4096) && *v <= quarter)
        .max()
        .unwrap_or(2048)
        .clamp(512, (total - 1024).max(512))
}
fn total_memory() -> i64 {
    #[cfg(windows)]
    unsafe {
        let mut info: windows_sys::Win32::System::SystemInformation::MEMORYSTATUSEX =
            std::mem::zeroed();
        info.dwLength = std::mem::size_of_val(&info) as u32;
        if windows_sys::Win32::System::SystemInformation::GlobalMemoryStatusEx(&mut info) != 0 {
            return (info.ullTotalPhys / 1024 / 1024) as i64;
        }
    }
    #[cfg(target_os = "linux")]
    if let Ok(text) = fs::read_to_string("/proc/meminfo") {
        if let Some(kb) = text.lines().find_map(|line| {
            line.strip_prefix("MemTotal:")
                .and_then(|v| v.split_whitespace().next())
                .and_then(|v| v.parse::<i64>().ok())
        }) {
            return kb / 1024;
        }
    }
    8192
}
#[derive(serde::Deserialize)]
pub struct Language {
    pub id: String,
    pub label: String,
    pub tag: String,
}
pub fn languages() -> &'static Vec<Language> {
    static VALUES: std::sync::OnceLock<Vec<Language>> = std::sync::OnceLock::new();
    VALUES.get_or_init(|| {
        serde_json::from_str(include_str!("../assets/languages.json"))
            .expect("Bundled language registry")
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn group_updates_validate_names_and_preserve_existing_settings() {
        let dir = std::env::temp_dir().join(format!("aw-library-groups-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("launcher.json");
        fs::write(
            &path,
            r#"{"future":{"preserve":true},"lastBuildId":"selected"}"#,
        )
        .unwrap();
        let mut store = Store::load(path.clone());
        assert!(store.set(
            "libraryGroups",
            serde_json::json!([{"id":"one","name":"Fabric"}])
        ));
        let before = fs::read(&path).unwrap();
        assert!(!store.set(
            "libraryGroups",
            serde_json::json!([{"id":"one","name":"Fabric"},{"id":"two","name":"fabric"}])
        ));
        assert!(!store.set(
            "libraryGroups",
            serde_json::json!([{"id":"one","name":"\n"}])
        ));
        assert_eq!(fs::read(&path).unwrap(), before);
        assert_eq!(read(&path).unwrap()["future"]["preserve"], true);
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn edits_preserve_unknown_and_newer_fields() {
        let dir = std::env::temp_dir().join(format!("aw-settings-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("launcher.json");
        fs::write(
            &path,
            r#"{"customGameDir":"D:/Games","future":{"x":1},"themeMode":"DARK"}"#,
        )
        .unwrap();
        let mut store = Store::load(path.clone());
        assert!(store.set("themeMode", Value::from("LIGHT")));
        let doc = read(&path).unwrap();
        assert_eq!(doc["future"]["x"], 1);
        assert_eq!(doc["customGameDir"], "D:/Games");
        assert!(!store.set("memoryMb", Value::from(1)));
        fs::write(&path, "corrupt").unwrap();
        assert!(!store.set("themeMode", Value::from("OLED")));
        assert_eq!(fs::read_to_string(&path).unwrap(), "corrupt");
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn lock_prevents_conflicting_writer() {
        let dir = std::env::temp_dir().join(format!("aw-settings-lock-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let lock = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .open(dir.join("launcher.lock"))
            .unwrap();
        lock.try_lock().unwrap();
        let mut store = Store::load(dir.join("launcher.json"));
        assert!(!store.set("friendsEnabled", Value::from(false)));
        drop(lock);
        assert!(store.set("friendsEnabled", Value::from(false)));
        fs::remove_dir_all(dir).unwrap();
    }
}
