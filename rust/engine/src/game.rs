use crate::{err, read_json, relative, write_json, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, HashSet},
    fs,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};
const MANIFEST: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

// Reuse a successful SHA-1 check only while the same file metadata is unchanged.
// Missing, modified or replaced files are checked again by Context::download.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
struct AssetStamp {
    size: u64,
    modified_ns: u64,
    created_ns: Option<u64>,
}

fn asset_stamp(path: &Path, expected_size: Option<u64>) -> Option<AssetStamp> {
    let metadata = fs::metadata(path).ok()?;
    if !metadata.is_file() || expected_size.is_some_and(|size| size != metadata.len()) {
        return None;
    }
    let timestamp = |time: std::time::SystemTime| {
        u64::try_from(time.duration_since(std::time::UNIX_EPOCH).ok()?.as_nanos()).ok()
    };
    Some(AssetStamp {
        size: metadata.len(),
        modified_ns: timestamp(metadata.modified().ok()?)?,
        created_ns: metadata.created().ok().and_then(timestamp),
    })
}
pub fn os() -> &'static str {
    if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    }
}
pub fn rules(value: &Value, features: &HashMap<String, bool>) -> bool {
    let Some(rules) = value.as_array().filter(|a| !a.is_empty()) else {
        return true;
    };
    let mut allowed = false;
    for rule in rules {
        let o = &rule["os"];
        if o["name"].as_str().is_some_and(|s| s != os()) {
            continue;
        }
        if o["arch"].as_str().is_some_and(|s| {
            s != if cfg!(target_arch = "x86") {
                "x86"
            } else if cfg!(target_arch = "aarch64") {
                "arm64"
            } else {
                "amd64"
            }
        }) {
            continue;
        }
        // OS version rules must be evaluated against an actual OS version, not ignored.
        if let Some(pattern) = o["version"].as_str() {
            let version = os_version();
            if !regex::Regex::new(pattern).is_ok_and(|r| r.is_match(&version)) {
                continue;
            }
        }
        if rule["features"].as_object().is_some_and(|m| {
            m.iter().any(|(k, v)| {
                features.get(k).copied().unwrap_or(false) != v.as_bool().unwrap_or(false)
            })
        }) {
            continue;
        }
        allowed = rule["action"] == "allow";
    }
    allowed
}
fn os_version() -> String {
    std::env::var("AW_OS_VERSION").unwrap_or_else(|_| {
        if cfg!(windows) {
            "10.0".into()
        } else {
            Command::new("uname")
                .arg("-r")
                .output()
                .ok()
                .map(|o| String::from_utf8_lossy(&o.stdout).trim().into())
                .unwrap_or_default()
        }
    })
}
pub fn split_args(text: &str) -> Result<Vec<String>> {
    let mut result = Vec::new();
    let mut word = String::new();
    let mut quote = None;
    let mut started = false;
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\\' if quote.is_some() && chars.peek().copied() == quote => {
                word.push(chars.next().unwrap());
                started = true;
            }
            '\'' | '"' => {
                if quote == Some(c) {
                    quote = None;
                } else if quote.is_none() {
                    quote = Some(c);
                } else {
                    word.push(c);
                }
                started = true;
            }
            c if c.is_whitespace() && quote.is_none() => {
                if started {
                    result.push(std::mem::take(&mut word));
                    started = false;
                }
            }
            _ => {
                word.push(c);
                started = true;
            }
        }
    }
    if quote.is_some() {
        return Err("Закрой кавычки в аргументах JVM".into());
    }
    if started {
        result.push(word);
    }
    Ok(result)
}
pub fn maven(name: &str) -> Result<String> {
    let (name, ext) = name.split_once('@').unwrap_or((name, "jar"));
    let p: Vec<_> = name.split(':').collect();
    if !(3..=4).contains(&p.len())
        || p.iter()
            .any(|s| s.is_empty() || s.contains('/') || s.contains('\\'))
    {
        return Err("Некорректная Maven-координата".into());
    }
    Ok(format!(
        "{}/{}/{}/{}-{}{}.{}",
        p[0].replace('.', "/"),
        p[1],
        p[2],
        p[1],
        p[2],
        p.get(3).map(|c| format!("-{c}")).unwrap_or_default(),
        ext
    ))
}
pub fn merge(child: Value, mut parent: Value) -> Value {
    for (k, v) in child.as_object().into_iter().flatten() {
        match k.as_str() {
            "inheritsFrom" => {}
            "libraries" => {
                let mut a = v.as_array().cloned().unwrap_or_default();
                a.extend(parent[k].as_array().cloned().unwrap_or_default());
                parent[k] = json!(a);
            }
            "arguments" => {
                for kind in ["jvm", "game"] {
                    let mut a = v[kind].as_array().cloned().unwrap_or_default();
                    a.extend(parent[k][kind].as_array().cloned().unwrap_or_default());
                    parent[k][kind] = json!(a);
                }
            }
            _ => {
                parent[k] = v.clone();
            }
        }
    }
    parent.as_object_mut().unwrap().remove("inheritsFrom");
    parent
}
fn resolve(cx: &Context, id: &str, seen: &mut HashSet<String>) -> Result<Value> {
    if seen.len() > 32 || !seen.insert(id.into()) {
        return Err("Цикл наследования версий Minecraft".into());
    }
    let dir = relative(&cx.root.join("versions"), id)?;
    let path = dir.join(format!("{id}.json"));
    if !path.exists() {
        let manifest = cx.json(MANIFEST)?;
        let reference = manifest["versions"]
            .as_array()
            .and_then(|a| a.iter().find(|v| v["id"] == id))
            .ok_or("Версия не найдена в списке Mojang")?;
        cx.download(
            reference["url"].as_str().ok_or("Нет URL версии")?,
            &path,
            reference["sha1"].as_str(),
            None,
        )?;
    }
    let child = read_json(&path)?;
    if let Some(parent) = child["inheritsFrom"].as_str() {
        let base = resolve(cx, parent, seen)?;
        Ok(merge(child, base))
    } else {
        Ok(child)
    }
}
fn artifact(cx: &Context, reference: &Value, path: &Path) -> Result<()> {
    cx.download(
        reference["url"]
            .as_str()
            .ok_or("В метаданных нет URL файла")?,
        path,
        reference["sha1"].as_str(),
        reference["size"].as_u64(),
    )
}
fn java_exe(dir: &Path) -> PathBuf {
    if cfg!(windows) {
        let p = dir.join("bin/javaw.exe");
        if p.exists() {
            p
        } else {
            dir.join("bin/java.exe")
        }
    } else {
        dir.join("bin/java")
    }
}
pub(crate) fn validate_java(path: &Path, major: u64) -> Result<PathBuf> {
    let p = if path.is_dir() {
        java_exe(path)
    } else {
        path.to_path_buf()
    };
    let p = p.canonicalize().map_err(|_| "Файл Java не найден")?;
    if !p.is_file()
        || !matches!(
            p.file_name()
                .and_then(|s| s.to_str())
                .map(str::to_ascii_lowercase)
                .as_deref(),
            Some("java" | "java.exe" | "javaw.exe")
        )
    {
        return Err("Выбери исполняемый файл Java или папку Java".into());
    }
    let got = p.parent().and_then(Path::parent).and_then(java_major);
    if got.is_none_or(|v| v < major) {
        return Err(format!("Нужна Java {major} или новее"));
    }
    Ok(p)
}
fn java_major(home: &Path) -> Option<u64> {
    let text = fs::read_to_string(home.join("release")).ok()?;
    let version = text
        .lines()
        .find_map(|s| s.strip_prefix("JAVA_VERSION="))?
        .trim()
        .trim_matches('"');
    if let Some(legacy) = version.strip_prefix("1.") {
        legacy.split('.').next()?.parse().ok()
    } else {
        version
            .chars()
            .take_while(char::is_ascii_digit)
            .collect::<String>()
            .parse()
            .ok()
    }
}
fn local_java(major: u64) -> Option<PathBuf> {
    let mut homes = Vec::new();
    for key in ["JAVA_HOME", "JDK_HOME"] {
        if let Some(home) = std::env::var_os(key) {
            homes.push(PathBuf::from(home));
        }
    }
    let mut roots = Vec::new();
    if let Some(home) = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" }) {
        let home = PathBuf::from(home);
        roots.extend([home.join(".jdks"), home.join(".gradle/jdks")]);
    }
    if cfg!(windows) {
        for key in ["ProgramFiles", "ProgramFiles(x86)"] {
            if let Some(base) = std::env::var_os(key) {
                for vendor in ["Java", "Eclipse Adoptium", "Microsoft", "BellSoft"] {
                    roots.push(PathBuf::from(&base).join(vendor));
                }
            }
        }
    } else {
        roots.push(PathBuf::from("/usr/lib/jvm"));
    }
    for root in roots {
        if let Ok(entries) = fs::read_dir(root) {
            homes.extend(entries.flatten().map(|e| e.path()).filter(|p| p.is_dir()));
        }
    }
    homes.into_iter().find_map(|home| {
        if java_major(&home) == Some(major) {
            validate_java(&home, major).ok()
        } else {
            None
        }
    })
}
fn java(cx: &Context, version: &Value, options: &Value) -> Result<PathBuf> {
    let major = version["javaVersion"]["majorVersion"].as_u64().unwrap_or(8);
    if let Some(path) = options["javaPath"].as_str().filter(|s| !s.is_empty()) {
        return validate_java(Path::new(path), major);
    }
    let component = version["javaVersion"]["component"]
        .as_str()
        .unwrap_or(if major >= 21 {
            "java-runtime-delta"
        } else if major >= 17 {
            "java-runtime-gamma"
        } else if major >= 16 {
            "java-runtime-alpha"
        } else {
            "jre-legacy"
        });
    let dir = relative(&cx.root.join("runtimes"), component)?;
    if dir.join(".aw-runtime").exists() {
        if let Ok(p) = validate_java(&dir, major) {
            return Ok(p);
        }
    }
    let install = (|| {
        cx.stage("Установка Java");
        let all=cx.json("https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json")?;
        let platform = if cfg!(windows) {
            if cfg!(target_arch = "aarch64") {
                "windows-arm64"
            } else {
                "windows-x64"
            }
        } else if cfg!(target_os = "macos") {
            "mac-os"
        } else {
            "linux"
        };
        let reference = &all[platform][component][0]["manifest"];
        let manifest = cx.json(
            reference["url"]
                .as_str()
                .ok_or("Java для этой платформы недоступна")?,
        )?;
        let files = manifest["files"]
            .as_object()
            .ok_or("Некорректный манифест Java")?;
        for (name, file) in files {
            cx.check()?;
            let path = relative(&dir, name)?;
            match file["type"].as_str() {
                Some("directory") => fs::create_dir_all(path).map_err(err)?,
                Some("file") => {
                    artifact(cx, &file["downloads"]["raw"], &path)?;
                    #[cfg(unix)]
                    if file["executable"] == true {
                        use std::os::unix::fs::PermissionsExt;
                        fs::set_permissions(path, fs::Permissions::from_mode(0o755))
                            .map_err(err)?;
                    }
                }
                Some("link") => {} // Targets can occur later in the manifest.
                _ => return Err("Неизвестный тип файла Java".into()),
            }
        }
        let runtime_root = dir.canonicalize().map_err(err)?;
        let mut links: Vec<_> = files
            .iter()
            .filter(|(_, file)| file["type"] == "link")
            .collect();
        while !links.is_empty() {
            let mut pending = Vec::new();
            let before = links.len();
            for (name, file) in links {
                cx.check()?;
                let path = relative(&dir, name)?;
                let target = file["target"].as_str().ok_or("Некорректная ссылка Java")?;
                let parent = path.parent().ok_or("Некорректный путь Java")?;
                let source = match parent.join(target).canonicalize() {
                    Ok(source) => source,
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                        pending.push((name, file));
                        continue;
                    }
                    Err(e) => return Err(err(e)),
                };
                if !source.starts_with(&runtime_root) {
                    return Err("Ссылка Java выходит за каталог".into());
                }
                if !path.exists() {
                    fs::create_dir_all(parent).map_err(err)?;
                    #[cfg(unix)]
                    std::os::unix::fs::symlink(target, path).map_err(err)?;
                    #[cfg(windows)]
                    fs::copy(source, path).map_err(err)?;
                }
            }
            if pending.len() == before {
                return Err("Не найдено назначение ссылки Java".into());
            }
            links = pending;
        }
        let p = validate_java(&dir, major)?;
        crate::write_bytes(&dir.join(".aw-runtime"), b"rust-engine")?;
        Ok(p)
    })();
    match install {
        Ok(p) => Ok(p),
        Err(e) => {
            if let Some(p) = local_java(major) {
                return Ok(p);
            }
            Err(e)
        }
    }
}
pub(crate) fn installed_loader(cx: &Context, base: &Value, kind: &str, pin: Option<&str>) -> Option<Value> {
    let game = base["id"].as_str()?;
    let mut found = Vec::new();
    for entry in fs::read_dir(cx.root.join("versions")).ok()?.flatten() {
        let id = entry.file_name().to_string_lossy().to_string();
        let path = entry.path().join(format!("{id}.json"));
        let matches = match kind {
            "FABRIC" | "QUILT" => {
                id.starts_with(&format!("{}-loader-", kind.to_ascii_lowercase()))
                    && id.ends_with(&format!("-{game}"))
                    && pin.is_none_or(|pin| {
                        id == format!("{}-loader-{pin}-{game}", kind.to_ascii_lowercase())
                    })
            }
            "FORGE" => {
                id.to_ascii_lowercase()
                    .starts_with(&format!("{game}-forge"))
                    && pin.is_none_or(|pin| loader_id_matches(&id, kind, game, pin))
            }
            "NEOFORGE" => {
                id.starts_with("neoforge-")
                    && pin.is_none_or(|pin| loader_id_matches(&id, kind, game, pin))
            }
            _ => false,
        };
        if !matches
            || matches!(kind, "FORGE" | "NEOFORGE") && !entry.path().join("aw-installed").is_file()
        {
            continue;
        }
        let Ok(profile) = read_json(&path) else {
            continue;
        };
        if profile["inheritsFrom"] != game || profile["id"] != id {
            continue;
        }
        found.push((path.metadata().and_then(|m| m.modified()).ok(), profile));
    }
    found
        .into_iter()
        .max_by_key(|(time, _)| *time)
        .map(|(_, profile)| merge(profile, base.clone()))
}
fn loader(
    cx: &Context,
    base: &Value,
    kind: &str,
    pin: Option<&str>,
    java: &Path,
    prefer_installed: bool,
) -> Result<Value> {
    if kind == "VANILLA" {
        return Ok(base.clone());
    }
    let game = base["id"].as_str().ok_or("Нет ID версии")?;
    let cached = installed_loader(cx, base, kind, pin);
    if pin.is_some() || prefer_installed {
        if let Some(profile) = &cached {
            return Ok(profile.clone());
        }
    }
    cx.stage("Установка загрузчика");
    if kind == "FABRIC" || kind == "QUILT" {
        let host = if kind == "FABRIC" {
            "https://meta.fabricmc.net/v2"
        } else {
            "https://meta.quiltmc.org/v3"
        };
        let latest = if pin.is_none() {
            match cx.json(&format!("{host}/versions/loader/{game}")) {
                Ok(list) => Some(list),
                Err(e) => return cached.ok_or(e),
            }
        } else {
            None
        };
        let version = pin
            .or_else(|| {
                latest
                    .as_ref()
                    .and_then(|v| v[0]["loader"]["version"].as_str())
            })
            .ok_or("Загрузчик недоступен для версии")?;
        let profile = cx.json(&format!(
            "{host}/versions/loader/{game}/{version}/profile/json"
        ))?;
        let id = profile["id"]
            .as_str()
            .ok_or("Загрузчик не вернул профиль")?;
        let path = relative(&cx.root.join("versions"), id)?.join(format!("{id}.json"));
        write_json(&path, &profile)?;
        return Ok(merge(profile, base.clone()));
    }
    if !matches!(kind, "FORGE" | "NEOFORGE") {
        return Err("Неизвестный загрузчик".into());
    }
    let version = if let Some(v) = pin {
        v.to_owned()
    } else {
        let rows = match crate::versions::loaders(cx, &json!({"loader":kind,"version":game})) {
            Ok(rows) => rows,
            Err(e) => return cached.ok_or(e),
        };
        let rows = rows.as_array().ok_or("Некорректный список загрузчиков")?;
        let chosen = rows
            .iter()
            .find(|v| v["recommended"] == true)
            .or_else(|| rows.iter().find(|v| v["stable"] == true))
            .or_else(|| rows.first());
        match chosen.and_then(|v| v["version"].as_str()) {
            Some(v) => v.to_owned(),
            None => return cached.ok_or("Загрузчик недоступен для этой версии Minecraft".into()),
        }
    };
    // Validate before using the version in a URL or filesystem path.
    relative(Path::new("loader"), &version)?;
    for entry in fs::read_dir(cx.root.join("versions")).map_err(err)? {
        let entry = entry.map_err(err)?;
        let id = entry.file_name().to_string_lossy().to_string();
        if loader_id_matches(&id, kind, game, &version)
            && entry.path().join("aw-installed").is_file()
        {
            return resolve(cx, &id, &mut HashSet::new());
        }
    }
    let url = if kind == "FORGE" {
        format!("https://maven.minecraftforge.net/net/minecraftforge/forge/{game}-{version}/forge-{game}-{version}-installer.jar")
    } else {
        format!("https://maven.neoforged.net/releases/net/neoforged/neoforge/{version}/neoforge-{version}-installer.jar")
    };
    let installer = relative(
        &cx.root.join("cache/installers"),
        url.rsplit('/').next().unwrap(),
    )?;
    cx.download(&url, &installer, None, None)?;
    if let Some(profile) = crate::legacy_forge::install(cx, &installer)? {
        let id = profile["id"].as_str().ok_or("Нет ID Forge")?;
        crate::write_bytes(
            &relative(&cx.root.join("versions"), id)?.join("aw-installed"),
            b"",
        )?;
        return Ok(merge(profile, base.clone()));
    }
    let stub = cx.root.join("launcher_profiles.json");
    if !stub.exists() {
        write_json(
            &stub,
            &json!({"profiles":{},"selectedProfile":"","clientToken":"","version":3}),
        )?;
    }
    let output = fs::File::create(installer.with_extension("log")).map_err(err)?;
    let mut command = Command::new(java);
    command
        .arg("-jar")
        .arg(&installer)
        .arg("--installClient")
        .arg(&cx.root)
        .current_dir(&cx.root)
        .stdin(Stdio::null())
        .stderr(output.try_clone().map_err(err)?)
        .stdout(output);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let mut child = command.spawn().map_err(err)?;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(900);
    loop {
        if let Err(e) = cx.check() {
            let _ = child.kill();
            let _ = child.wait();
            return Err(e);
        }
        if let Some(status) = child.try_wait().map_err(err)? {
            if !status.success() {
                return Err(
                    "Установщик загрузчика завершился с ошибкой; смотри лог в cache/installers"
                        .into(),
                );
            }
            break;
        }
        if std::time::Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err("Время установки загрузчика истекло".into());
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    for entry in fs::read_dir(cx.root.join("versions")).map_err(err)? {
        let entry = entry.map_err(err)?;
        let id = entry.file_name().to_string_lossy().to_string();
        let matches = loader_id_matches(&id, kind, game, &version);
        if matches {
            crate::write_bytes(&entry.path().join("aw-installed"), b"")?;
            return resolve(cx, &id, &mut HashSet::new());
        }
    }
    Err("Установщик завершился, но профиль загрузчика не найден".into())
}
fn loader_id_matches(id: &str, kind: &str, game: &str, version: &str) -> bool {
    if kind == "NEOFORGE" {
        id == format!("neoforge-{version}")
    } else {
        id.to_ascii_lowercase()
            .starts_with(&format!("{game}-forge"))
            && (id.ends_with(&format!("-{version}")) || id.ends_with(&format!("Forge{version}")))
    }
}
pub(crate) fn neoforge_game(version: &str) -> Option<String> {
    if version.contains('+') {
        return None;
    }
    let numbers = version
        .split('-')
        .next()?
        .split('.')
        .map(str::parse::<u32>)
        .collect::<std::result::Result<Vec<_>, _>>()
        .ok()?;
    let major = *numbers.first()?;
    let minor = *numbers.get(1)?;
    Some(if major >= 26 {
        let patch = *numbers.get(2)?;
        if patch == 0 {
            format!("{major}.{minor}")
        } else {
            format!("{major}.{minor}.{patch}")
        }
    } else if minor == 0 {
        format!("1.{major}")
    } else {
        format!("1.{major}.{minor}")
    })
}
fn extract(jar: &Path, dest: &Path, exclude: &Value) -> Result<()> {
    let mut zip = zip::ZipArchive::new(fs::File::open(jar).map_err(err)?).map_err(err)?;
    for i in 0..zip.len() {
        let mut f = zip.by_index(i).map_err(err)?;
        let name = f.name().to_owned();
        if f.is_dir()
            || name.starts_with("META-INF/")
            || exclude
                .as_array()
                .into_iter()
                .flatten()
                .any(|v| v.as_str().is_some_and(|p| name.starts_with(p)))
        {
            continue;
        }
        if !(name.ends_with(".dll")
            || name.ends_with(".so")
            || name.ends_with(".dylib")
            || name.ends_with(".jnilib"))
        {
            continue;
        }
        let path = relative(dest, &name)?;
        fs::create_dir_all(path.parent().unwrap()).map_err(err)?;
        std::io::copy(&mut f, &mut fs::File::create(path).map_err(err)?).map_err(err)?;
    }
    Ok(())
}
fn expanded(
    args: &Value,
    features: &HashMap<String, bool>,
    replacements: &HashMap<String, String>,
) -> Vec<String> {
    let mut out = Vec::new();
    for arg in args.as_array().into_iter().flatten() {
        let values = if let Some(s) = arg.as_str() {
            vec![s]
        } else if rules(&arg["rules"], features) {
            if let Some(s) = arg["value"].as_str() {
                vec![s]
            } else {
                arg["value"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter_map(Value::as_str)
                    .collect()
            }
        } else {
            Vec::new()
        };
        for s in values {
            let mut s = s.to_owned();
            for (k, v) in replacements {
                s = s.replace(&format!("${{{k}}}"), v);
            }
            out.push(s);
        }
    }
    out
}
pub fn launch(cx: &Context, profile: &Value) -> Result<Value> {
    run(cx, profile, true)
}
pub fn install(cx: &Context, profile: &Value) -> Result<Value> {
    run(cx, profile, false)
}
fn run(cx: &Context, profile: &Value, launch_game: bool) -> Result<Value> {
    let game = profile["version"].as_str().ok_or("Нет версии игры")?;
    let kind = profile["loader"]
        .as_str()
        .unwrap_or("VANILLA")
        .to_uppercase();
    let dir = PathBuf::from(profile["dir"].as_str().ok_or("Нет папки сборки")?);
    fs::create_dir_all(&dir).map_err(err)?;
    if launch_game {
        // An unreadable server list must not prevent Minecraft from launching.
        let _ = crate::server_list::remove_legacy_default(&dir);
    }
    crate::mod_compat::retire_managed(&dir)?;
    if launch_game && kind != "VANILLA" {
        crate::mod_compat::check(&dir)?;
    }
    let options_path = dir.join("aw-instance.json");
    let options = if options_path.exists() {
        read_json(&options_path)?
    } else {
        json!({})
    };
    let settings_path = cx.root.join("launcher.json");
    let settings = if settings_path.exists() {
        read_json(&settings_path)?
    } else {
        json!({})
    };
    cx.stage("Чтение версии Minecraft");
    let base = resolve(cx, game, &mut HashSet::new())?;
    let java = java(cx, &base, &options)?;
    let loader_pin = options["loaderVersion"]
        .as_str()
        .or(profile["loaderVersion"].as_str());
    if matches!(kind.as_str(), "FORGE" | "NEOFORGE")
        && (!launch_game || installed_loader(cx, &base, &kind, loader_pin).is_none())
    {
        let mut vanilla = profile.clone();
        vanilla["loader"] = json!("VANILLA");
        run(cx, &vanilla, false)?;
    }
    let version = loader(
        cx,
        &base,
        &kind,
        loader_pin,
        &java,
        launch_game,
    )?;
    let id = version["id"].as_str().ok_or("Нет ID версии")?;
    let natives = relative(&cx.root.join("natives"), id)?;
    fs::create_dir_all(&natives).map_err(err)?;
    cx.stage("Загрузка библиотек и ресурсов");
    let mut classpath = Vec::new();
    let mut seen = HashSet::new();
    let features = HashMap::new();
    for lib in version["libraries"].as_array().into_iter().flatten() {
        cx.check()?;
        if lib["clientreq"] == false || !rules(&lib["rules"], &features) {
            continue;
        }
        let name = lib["name"].as_str().ok_or("Нет имени библиотеки")?;
        let parts: Vec<_> = name.split(':').collect();
        let key = format!(
            "{}:{}:{}",
            parts.first().unwrap_or(&""),
            parts.get(1).unwrap_or(&""),
            parts.get(3).unwrap_or(&"")
        );
        if seen.insert(key) {
            let a = &lib["downloads"]["artifact"];
            if a.is_object()
                || lib["downloads"].is_null()
                    && lib["natives"].as_object().is_none_or(|o| o.is_empty())
            {
                let relative_name = a["path"]
                    .as_str()
                    .map(str::to_owned)
                    .unwrap_or(maven(name)?);
                let path = relative(&cx.root.join("libraries"), &relative_name)?;
                if a.is_object() {
                    artifact(cx, a, &path)?;
                } else {
                    let url = format!(
                        "{}/{}",
                        lib["url"]
                            .as_str()
                            .unwrap_or("https://libraries.minecraft.net")
                            .trim_end_matches('/'),
                        relative_name
                    );
                    cx.download(&url, &path, None, None)?;
                }
                if name.contains(":natives-") {
                    extract(&path, &natives, &lib["extract"]["exclude"])?;
                }
                classpath.push(path);
            }
        }
        if let Some(classifier) = lib["natives"][os()].as_str() {
            let classifier = classifier.replace(
                "${arch}",
                if cfg!(target_pointer_width = "64") {
                    "64"
                } else {
                    "32"
                },
            );
            let a = &lib["downloads"]["classifiers"][&classifier];
            let relative_name = a["path"]
                .as_str()
                .map(str::to_owned)
                .unwrap_or(maven(&format!("{name}:{classifier}"))?);
            let path = relative(&cx.root.join("libraries"), &relative_name)?;
            artifact(cx, a, &path)?;
            extract(&path, &natives, &lib["extract"]["exclude"])?;
        }
    }
    // Forge/NeoForge's ignoreList refers to the profile JAR's filename. Using the
    // parent's filename adds a second Minecraft module to the transforming layer.
    let jar = relative(&cx.root.join("versions"), id)?.join(format!("{id}.jar"));
    if id != game && !jar.exists() {
        let base_jar = relative(&cx.root.join("versions"), game)?.join(format!("{game}.jar"));
        if base_jar.is_file() {
            fs::create_dir_all(jar.parent().unwrap()).map_err(err)?;
            fs::copy(base_jar, &jar).map_err(err)?;
        }
    }
    artifact(cx, &version["downloads"]["client"], &jar)?;
    classpath.push(jar);
    let asset_id = version["assetIndex"]["id"]
        .as_str()
        .or(version["assets"].as_str())
        .unwrap_or("legacy");
    let mut assets = cx.root.join("assets");
    if version["assetIndex"].is_object() {
        let index_path = relative(&assets.join("indexes"), &format!("{asset_id}.json"))?;
        artifact(cx, &version["assetIndex"], &index_path)?;
        let index = read_json(&index_path)?;
        let materialized = if index["map_to_resources"] == true {
            Some(dir.join("resources"))
        } else if index["virtual"] == true || asset_id == "legacy" || asset_id == "pre-1.6" {
            Some(relative(&assets.join("virtual"), asset_id)?)
        } else {
            None
        };
        let objects = index["objects"]
            .as_object()
            .ok_or("Некорректный список ресурсов")?;
        let mut jobs = HashMap::new();
        for obj in objects.values() {
            let hash = obj["hash"]
                .as_str()
                .filter(|s| s.len() == 40 && s.bytes().all(|c| c.is_ascii_hexdigit()))
                .ok_or("Некорректный хеш ресурса")?;
            let key = format!("{}/{}", &hash[..2], hash);
            let path = relative(&assets.join("objects"), &key)?;
            jobs.entry(hash.to_owned()).or_insert((
                format!("https://resources.download.minecraft.net/{key}"),
                path,
                obj["size"].as_u64(),
            ));
        }
        let jobs: Vec<_> = jobs.into_iter().collect();
        let verification_path = relative(
            &cx.root.join("cache/verified-assets"),
            &format!("{asset_id}.json"),
        )?;
        let verified: HashMap<String, AssetStamp> = fs::read(&verification_path)
            .ok()
            .filter(|bytes| bytes.len() <= 16 * 1024 * 1024)
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        let verified_now = std::sync::Mutex::new(HashMap::with_capacity(jobs.len()));
        let defaults = (std::thread::available_parallelism()
            .map(usize::from)
            .unwrap_or(2)
            * 4)
        .clamp(8, 24);
        let concurrency = settings["downloadConcurrency"]
            .as_u64()
            .filter(|n| *n > 0)
            .map(|n| n.clamp(1, 64) as usize)
            .unwrap_or(defaults);
        let next = std::sync::atomic::AtomicUsize::new(0);
        let complete = std::sync::atomic::AtomicUsize::new(0);
        let failure = std::sync::Mutex::new(None);
        std::thread::scope(|scope| {
            for _ in 0..concurrency.min(jobs.len()) {
                let jobs = &jobs;
                let next = &next;
                let complete = &complete;
                let failure = &failure;
                let verified = &verified;
                let verified_now = &verified_now;
                scope.spawn(move || loop {
                    if failure.lock().unwrap().is_some() { break; }
                    let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    let Some((hash, (url, path, size))) = jobs.get(i) else { break; };
                    let before = asset_stamp(path, *size);
                    if before.as_ref().is_none_or(|stamp| verified.get(hash) != Some(stamp)) {
                        if let Err(error) = cx.download(url, path, Some(hash), *size) {
                            let mut first = failure.lock().unwrap();
                            if first.is_none() { *first = Some(error); }
                            break;
                        }
                    }
                    let after = asset_stamp(path, *size);
                    if before.is_none() || before == after {
                        if let Some(stamp) = after {
                            verified_now.lock().unwrap().insert(hash.clone(), stamp);
                        }
                    }
                    let count = complete.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
                    if count % 64 == 0 || count == jobs.len() {
                        (cx.event)(json!({"type":"progress","data":{"fraction":count as f64/jobs.len() as f64,"currentFile":hash}}));
                    }
                });
            }
        });
        if let Some(error) = failure.into_inner().map_err(err)? {
            return Err(error);
        }
        let verified_now = verified_now.into_inner().map_err(err)?;
        if verified_now != verified {
            // Failure to persist the optimisation must never prevent game launch.
            let _ = write_json(&verification_path, &json!(verified_now));
        }
        for (name, obj) in objects {
            if let Some(target) = &materialized {
                let hash = obj["hash"].as_str().unwrap();
                let path = relative(&assets.join("objects"), &format!("{}/{}", &hash[..2], hash))?;
                let dest = relative(target, name)?;
                fs::create_dir_all(dest.parent().unwrap()).map_err(err)?;
                fs::copy(path, dest).map_err(err)?;
            }
        }
        if index["map_to_resources"] != true
            && (index["virtual"] == true || asset_id == "legacy" || asset_id == "pre-1.6")
        {
            assets = materialized.unwrap();
        }
    }
    if !launch_game {
        return Ok(json!("Версия установлена"));
    }
    let account = crate::accounts::selected(cx)?;
    let memory = options["memoryMb"]
        .as_u64()
        .or(settings["memoryMb"].as_u64())
        .unwrap_or(2048);
    if !(512..=1048576).contains(&memory) {
        return Err("Некорректный объём памяти".into());
    }
    let mut args = vec![
        format!("-Xmx{memory}M"),
        format!("-Xms{}M", (memory / 2).max(512)),
        "-Dlog4j2.formatMsgNoLookups=true".into(),
        "-XX:-HeapDumpOnOutOfMemoryError".into(),
    ];
    args.extend(split_args(
        options["jvmArgs"]
            .as_str()
            .or(settings["jvmArgs"].as_str())
            .unwrap_or(""),
    )?);
    let cp = std::env::join_paths(classpath)
        .map_err(err)?
        .to_string_lossy()
        .to_string();
    let token = account["accessToken"].as_str().unwrap_or("0");
    let uuid = account["uuid"].as_str().ok_or("Нет UUID аккаунта")?;
    let replacements: HashMap<String, String> = [
        ("natives_directory", natives.to_string_lossy().to_string()),
        ("launcher_name", "AWLauncher".into()),
        ("launcher_version", "1.0.56".into()),
        ("classpath", cp.clone()),
        (
            "classpath_separator",
            if cfg!(windows) { ";" } else { ":" }.into(),
        ),
        (
            "library_directory",
            cx.root.join("libraries").to_string_lossy().into(),
        ),
        ("version_name", id.into()),
        (
            "version_type",
            version["type"].as_str().unwrap_or("release").into(),
        ),
        (
            "auth_player_name",
            account["name"].as_str().unwrap_or("").into(),
        ),
        ("auth_uuid", uuid.into()),
        ("auth_access_token", token.into()),
        ("auth_session", format!("token:{token}:{uuid}")),
        ("game_directory", dir.to_string_lossy().into()),
        ("assets_root", assets.to_string_lossy().into()),
        ("game_assets", assets.to_string_lossy().into()),
        ("assets_index_name", asset_id.into()),
        (
            "user_type",
            if account["type"] == "MICROSOFT" {
                "msa"
            } else {
                "legacy"
            }
            .into(),
        ),
        ("user_properties", "{}".into()),
        ("auth_xuid", "".into()),
        ("clientid", "".into()),
        (
            "resolution_width",
            options["windowWidth"].as_u64().unwrap_or(854).to_string(),
        ),
        (
            "resolution_height",
            options["windowHeight"].as_u64().unwrap_or(480).to_string(),
        ),
    ]
    .into_iter()
    .map(|(k, v)| (k.into(), v))
    .collect();
    let features = HashMap::from([(
        "has_custom_resolution".into(),
        options["windowWidth"].is_number() && options["windowHeight"].is_number(),
    )]);
    let jvm = expanded(&version["arguments"]["jvm"], &features, &replacements);
    if jvm.is_empty() {
        args.extend([
            format!("-Djava.library.path={}", natives.display()),
            "-cp".into(),
            cp,
        ]);
    } else {
        args.extend(jvm);
    }
    if version["logging"]["client"]["file"].is_object() {
        let reference = &version["logging"]["client"]["file"];
        let log = relative(
            &cx.root.join("assets/log_configs"),
            reference["id"].as_str().unwrap_or("client.xml"),
        )?;
        artifact(cx, reference, &log)?;
        args.push(
            version["logging"]["client"]["argument"]
                .as_str()
                .unwrap_or("-Dlog4j.configurationFile=${path}")
                .replace("${path}", &log.to_string_lossy()),
        );
    }
    args.push(
        version["mainClass"]
            .as_str()
            .ok_or("Нет главного класса игры")?
            .into(),
    );
    let game_args = if version["arguments"]["game"].is_array() {
        version["arguments"]["game"].clone()
    } else {
        json!(split_args(
            version["minecraftArguments"].as_str().unwrap_or("")
        )?)
    };
    args.extend(expanded(&game_args, &features, &replacements));
    if features["has_custom_resolution"] {
        for (flag, key, min) in [
            ("--width", "windowWidth", 320),
            ("--height", "windowHeight", 200),
        ] {
            let size = options[key].as_u64().unwrap();
            if !(min..=16384).contains(&size) {
                return Err("Некорректный размер окна".into());
            }
            if !args.iter().any(|a| a == flag) {
                args.extend([flag.into(), size.to_string()]);
            }
        }
    }
    cx.check()?;
    cx.stage("Запуск Minecraft");
    if let Some(fullscreen) = options["fullscreen"].as_bool() {
        let path = dir.join("options.txt");
        let text = if path.exists() {
            fs::read_to_string(&path).map_err(err)?
        } else {
            String::new()
        };
        let mut lines: Vec<String> = text
            .lines()
            .filter(|s| !s.starts_with("fullscreen:"))
            .map(str::to_owned)
            .collect();
        lines.push(format!("fullscreen:{fullscreen}"));
        crate::write_bytes(&path, format!("{}\n", lines.join("\n")).as_bytes())?;
    }
    let log = fs::File::create(dir.join("latest-game.log")).map_err(err)?;
    let mut command = Command::new(java);
    command
        .args(args)
        .current_dir(dir)
        .stdin(Stdio::null())
        .stderr(log.try_clone().map_err(err)?)
        .stdout(log);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
    let mut child = command.spawn().map_err(err)?;
    let pid = child.id();
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(json!({"pid":pid,"message":"Игра запущена"}))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn verified_asset_stamp_changes_when_a_file_is_modified_or_replaced() {
        let dir = std::env::temp_dir().join(format!("aw-asset-stamp-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        let file = dir.join("object");
        fs::write(&file, b"first").unwrap();
        let saved = asset_stamp(&file, Some(5)).unwrap();
        assert_eq!(asset_stamp(&file, Some(5)), Some(saved.clone()));
        assert!(asset_stamp(&file, Some(6)).is_none());
        std::thread::sleep(std::time::Duration::from_millis(25));
        fs::write(&file, b"other").unwrap();
        assert_ne!(asset_stamp(&file, Some(5)), Some(saved));
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn install_persists_verified_asset_and_invalidates_changed_file() {
        let root = std::env::temp_dir().join(format!("aw-asset-cache-{}", uuid::Uuid::new_v4()));
        let version_dir = root.join("versions/fixture");
        let runtime = root.join("runtimes/test-runtime");
        fs::create_dir_all(runtime.join("bin")).unwrap();
        fs::write(runtime.join(".aw-runtime"), b"ok").unwrap();
        fs::write(runtime.join("release"), "JAVA_VERSION=\"21\"\n").unwrap();
        fs::write(java_exe(&runtime), b"unused").unwrap();
        fs::create_dir_all(&version_dir).unwrap();
        fs::write(version_dir.join("fixture.jar"), b"game").unwrap();
        let asset_temp = root.join("asset.tmp");
        fs::write(&asset_temp, b"asset").unwrap();
        let digest = crate::storage::hash(&asset_temp).unwrap();
        let object = root.join("assets/objects").join(&digest[..2]).join(&digest);
        fs::create_dir_all(object.parent().unwrap()).unwrap();
        fs::rename(&asset_temp, &object).unwrap();
        write_json(&root.join("assets/indexes/test.json"),
            &json!({"objects":{"asset.dat":{"hash":digest,"size":5}}})).unwrap();
        write_json(&version_dir.join("fixture.json"), &json!({
            "id":"fixture", "javaVersion":{"majorVersion":8,"component":"test-runtime"},
            "downloads":{"client":{"url":"https://unused.invalid/game.jar","size":4}},
            "assetIndex":{"id":"test","url":"https://unused.invalid/assets.json"},
            "libraries":[]
        })).unwrap();
        let cx = Context { root: root.clone(), cancelled: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)), event: &|_| {} };
        install(&cx, &json!({"version":"fixture","loader":"VANILLA","dir":root.join("instance")})).unwrap();
        let cache: HashMap<String, AssetStamp> = serde_json::from_slice(&fs::read(root.join("cache/verified-assets/test.json")).unwrap()).unwrap();
        let stamp = cache.get(&digest).unwrap();
        assert_eq!(asset_stamp(&object, Some(5)).as_ref(), Some(stamp));
        std::thread::sleep(std::time::Duration::from_millis(25));
        fs::write(&object, b"other").unwrap();
        assert_ne!(asset_stamp(&object, Some(5)).as_ref(), Some(stamp));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn launch_reuses_installed_fabric_profile_without_network() {
        let root = std::env::temp_dir().join(format!("aw-loader-cache-{}", uuid::Uuid::new_v4()));
        let id = "fabric-loader-0.99-fixture";
        write_json(
            &root.join("versions").join(id).join(format!("{id}.json")),
            &json!({"id":id,"inheritsFrom":"fixture","mainClass":"cached.Main"}),
        )
        .unwrap();
        let cx = Context {
            root: root.clone(),
            cancelled: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
            event: &|_| {},
        };
        let version = loader(
            &cx,
            &json!({"id":"fixture"}),
            "FABRIC",
            None,
            Path::new("unused"),
            true,
        )
        .unwrap();
        assert_eq!(version["mainClass"], "cached.Main");
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn loader_identity_is_exact_and_handles_new_version_scheme() {
        assert_eq!(neoforge_game("21.1.265").as_deref(), Some("1.21.1"));
        assert_eq!(neoforge_game("26.3.0.5").as_deref(), Some("26.3"));
        assert_eq!(neoforge_game("26.3.1.2").as_deref(), Some("26.3.1"));
        assert!(loader_id_matches(
            "1.7.10-Forge10.13.4.1614-1.7.10",
            "FORGE",
            "1.7.10",
            "10.13.4.1614-1.7.10"
        ));
        assert!(!loader_id_matches(
            "1.21.1-forge-52.1.20",
            "FORGE",
            "1.21.1",
            "52.1.2"
        ));
    }
    #[test]
    fn quoted_jvm() {
        assert_eq!(
            split_args("-Dpath=\"a b\" '' -Xmx2G").unwrap(),
            vec!["-Dpath=a b", "", "-Xmx2G"]
        );
        assert!(split_args("'oops").is_err());
    }
    #[test]
    fn rule_order_and_features() {
        let r = json!([{"action":"allow"},{"action":"disallow","features":{"is_demo_user":true}}]);
        assert!(rules(&r, &HashMap::new()));
        assert!(!rules(&r, &HashMap::from([("is_demo_user".into(), true)])));
    }
    #[test]
    fn inherit_child_library_first() {
        let v = merge(
            json!({"id":"child","libraries":[{"name":"new"}],"arguments":{"game":["c"]},"inheritsFrom":"base"}),
            json!({"id":"base","mainClass":"Main","libraries":[{"name":"old"}],"arguments":{"game":["b"]}}),
        );
        assert_eq!(v["libraries"][0]["name"], "new");
        assert_eq!(v["mainClass"], "Main");
        assert!(v.get("inheritsFrom").is_none());
    }
    #[test]
    fn reject_traversal() {
        assert!(relative(Path::new("root"), "../outside").is_err());
        assert!(maven("g:a:1").is_ok());
        assert!(maven("g:a:../1").is_err());
    }
}
