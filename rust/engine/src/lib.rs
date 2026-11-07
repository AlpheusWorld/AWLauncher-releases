//! Native game and account services. Credentials never cross the WebView boundary.
pub mod accounts;
pub mod builds;
pub mod content;
pub mod dependencies;
pub mod game;
pub mod history;
mod legacy_forge;
pub mod migration;
mod mod_compat;
pub mod ops;
pub mod packs;
mod pkce;
mod profile_import;
pub mod reinstall;
mod secrets;
mod server_list;
pub mod skins;
pub mod storage;
mod title_auth;
pub mod updater;
pub mod versions;
use serde_json::{json, Value};
use std::{
    fs,
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Duration,
};
pub type Result<T> = std::result::Result<T, String>;
/// Uses the same byte-range locks as the JVM engine on Windows.
pub fn operation_locks(root: &Path) -> Result<Vec<fs::File>> {
    #[cfg(windows)]
    use fs2::FileExt;
    fs::create_dir_all(root).map_err(err)?;
    ["native-operation.lock", "launcher.lock"]
        .into_iter()
        .map(|name| {
            let file = fs::OpenOptions::new()
                .create(true)
                .truncate(false)
                .read(true)
                .write(true)
                .open(root.join(name))
                .map_err(err)?;
            #[cfg(windows)]
            file.try_lock_exclusive()
                .map_err(|_| "Дождись завершения другой операции лаунчера")?;
            #[cfg(unix)]
            {
                use std::os::fd::AsRawFd;
                let mut lock: libc::flock = unsafe { std::mem::zeroed() };
                lock.l_type = libc::F_WRLCK as _;
                lock.l_whence = libc::SEEK_SET as _;
                lock.l_start = 0;
                lock.l_len = 0;
                if unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETLK, &lock) } < 0 {
                    return Err("Дождись завершения другой операции лаунчера".into());
                }
            }
            Ok(file)
        })
        .collect()
}
pub struct Context<'a> {
    pub root: PathBuf,
    pub cancelled: Arc<AtomicBool>,
    pub event: &'a (dyn Fn(Value) + Sync),
}
impl Context<'_> {
    pub fn check(&self) -> Result<()> {
        if self.cancelled.load(Ordering::Relaxed) {
            Err("Операция отменена".into())
        } else {
            Ok(())
        }
    }
    pub fn stage(&self, text: &str) {
        (self.event)(json!({"type":"stage","data":text}));
    }
    pub fn client(&self) -> Result<reqwest::blocking::Client> {
        if std::env::var("AW_ENGINE_OFFLINE").as_deref() == Ok("1") {
            return Err("Сеть отключена для офлайн-проверки".into());
        }
        static CLIENT: std::sync::OnceLock<Result<reqwest::blocking::Client>> =
            std::sync::OnceLock::new();
        CLIENT
            .get_or_init(|| {
                reqwest::blocking::Client::builder()
                    .user_agent("AWLauncher/1.0.56")
                    .connect_timeout(Duration::from_secs(20))
                    .timeout(Duration::from_secs(120))
                    .redirect(reqwest::redirect::Policy::none())
                    .build()
                    .map_err(|_| "Не удалось создать HTTP-клиент".into())
            })
            .clone()
    }
    pub fn json(&self, url: &str) -> Result<Value> {
        self.check()?;
        response_json(
            self.client()?
                .get(url)
                .send()
                .map_err(|_| "Нет связи с сервером")?,
        )
    }
    pub fn download(
        &self,
        url: &str,
        dest: &Path,
        hash: Option<&str>,
        size: Option<u64>,
    ) -> Result<()> {
        let mut failure = String::new();
        for attempt in 0..3 {
            self.check()?;
            match self.download_once(url, dest, hash, size) {
                Ok(()) => return Ok(()),
                Err(e) => failure = e,
            }
            if attempt < 2 {
                for _ in 0..(attempt + 1) * 5 {
                    self.check()?;
                    std::thread::sleep(Duration::from_millis(100));
                }
            }
        }
        Err(failure)
    }
    fn download_once(
        &self,
        url: &str,
        dest: &Path,
        hash: Option<&str>,
        size: Option<u64>,
    ) -> Result<()> {
        use sha1::{Digest, Sha1};
        self.check()?;
        let valid = |p: &Path| -> bool {
            let Ok(mut f) = fs::File::open(p) else {
                return false;
            };
            if size.is_some_and(|s| s > 0 && f.metadata().map(|m| m.len()).ok() != Some(s)) {
                return false;
            }
            if let Some(expected) = hash.filter(|s| !s.is_empty()) {
                let mut h = Sha1::new();
                let mut buf = [0; 65536];
                loop {
                    match f.read(&mut buf) {
                        Ok(0) => break,
                        Ok(n) => h.update(&buf[..n]),
                        Err(_) => return false,
                    }
                }
                format!("{:x}", h.finalize()) == expected
            } else {
                true
            }
        };
        if valid(dest) {
            return Ok(());
        }
        if !url.starts_with("https://") {
            return Err("Разрешены только HTTPS-загрузки".into());
        }
        fs::create_dir_all(dest.parent().ok_or("Нет папки назначения")?).map_err(err)?;
        let tmp = dest.with_extension(format!("{}.part", uuid::Uuid::new_v4()));
        let result = (|| {
            let mut next = reqwest::Url::parse(url).map_err(|_| "Некорректный адрес загрузки")?;
            let mut redirects = 0;
            let mut response = loop {
                // Public downloads may redirect; authenticated requests never follow redirects.
                self.check()?;
                let result = self
                    .client()?
                    .get(next.clone())
                    .send()
                    .map_err(|_| "Ошибка загрузки")?;
                if !result.status().is_redirection() {
                    break result;
                }
                let location = result
                    .headers()
                    .get(reqwest::header::LOCATION)
                    .and_then(|v| v.to_str().ok())
                    .ok_or("Сервер не вернул адрес перенаправления")?;
                next = next
                    .join(location)
                    .map_err(|_| "Некорректное перенаправление")?;
                if next.scheme() != "https"
                    || !next.username().is_empty()
                    || next.password().is_some()
                {
                    return Err("Небезопасное перенаправление загрузки".into());
                }
                redirects += 1;
                if redirects > 5 {
                    return Err("Слишком много перенаправлений загрузки".into());
                }
            };
            if !response.status().is_success() {
                return Err(format!(
                    "Сервер загрузки: HTTP {}",
                    response.status().as_u16()
                ));
            }
            let mut out = fs::File::create(&tmp).map_err(err)?;
            let mut buf = [0; 65536];
            let mut completed = 0u64;
            let mut digest = Sha1::new();
            loop {
                self.check()?;
                let n = response.read(&mut buf).map_err(err)?;
                if n == 0 {
                    break;
                }
                out.write_all(&buf[..n]).map_err(err)?;
                if hash.is_some_and(|value| !value.is_empty()) {
                    digest.update(&buf[..n]);
                }
                completed += n as u64;
            }
            out.sync_all().map_err(err)?;
            drop(out);
            if size.is_some_and(|expected| expected > 0 && completed != expected)
                || hash
                    .filter(|value| !value.is_empty())
                    .is_some_and(|expected| format!("{:x}", digest.finalize()) != expected)
            {
                return Err("Скачанный файл не прошёл проверку".into());
            }
            replace(&tmp, dest)?;
            (self.event)(
                json!({"type":"progress","data":{"completedBytes":completed,"currentFile":dest.file_name().unwrap_or_default().to_string_lossy()}}),
            );
            Ok(())
        })();
        let _ = fs::remove_file(tmp);
        result
    }
}
pub fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}
pub fn response_json(response: reqwest::blocking::Response) -> Result<Value> {
    if !response.status().is_success() {
        return Err(format!("Сервис вернул HTTP {}", response.status().as_u16()));
    }
    let mut bytes = Vec::new();
    response
        .take(4 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(err)?;
    if bytes.len() > 4 * 1024 * 1024 {
        return Err("Слишком большой ответ сервера".into());
    }
    serde_json::from_slice(&bytes).map_err(|_| "Некорректный ответ сервера".into())
}
pub fn relative(root: &Path, name: &str) -> Result<PathBuf> {
    let p = Path::new(name);
    if name.is_empty()
        || name.contains('\\')
        || name.contains(':')
        || p.components().any(|c| !matches!(c, Component::Normal(_)))
    {
        return Err("Некорректный путь в метаданных".into());
    }
    Ok(root.join(p))
}
pub fn read_json(path: &Path) -> Result<Value> {
    serde_json::from_slice(&fs::read(path).map_err(err)?).map_err(err)
}
fn replace(src: &Path, dest: &Path) -> Result<()> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        let a: Vec<u16> = src.as_os_str().encode_wide().chain(Some(0)).collect();
        let b: Vec<u16> = dest.as_os_str().encode_wide().chain(Some(0)).collect();
        unsafe {
            if windows_sys::Win32::Storage::FileSystem::MoveFileExW(
                a.as_ptr(),
                b.as_ptr(),
                windows_sys::Win32::Storage::FileSystem::MOVEFILE_REPLACE_EXISTING
                    | windows_sys::Win32::Storage::FileSystem::MOVEFILE_WRITE_THROUGH,
            ) == 0
            {
                return Err(err(std::io::Error::last_os_error()));
            }
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        fs::rename(src, dest).map_err(err)
    }
}
pub fn write_json(path: &Path, value: &Value) -> Result<()> {
    write_bytes(path, &serde_json::to_vec_pretty(value).map_err(err)?)
}
pub fn write_bytes(path: &Path, bytes: &[u8]) -> Result<()> {
    fs::create_dir_all(path.parent().ok_or("Нет папки")?).map_err(err)?;
    let tmp = path.with_extension(format!("{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut f = fs::File::create(&tmp).map_err(err)?;
        f.write_all(bytes).map_err(err)?;
        f.sync_all().map_err(err)?;
        drop(f);
        replace(&tmp, path)
    })();
    let _ = fs::remove_file(tmp);
    result
}
