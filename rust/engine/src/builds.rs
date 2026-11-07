use crate::{accounts, err, read_json, storage, write_json, Context, Result};
use serde_json::{json, Value};
use std::{
    fs,
    path::{Path, PathBuf},
};
pub const INSTANCE: &str = "aw-instance.json";
pub fn memory() -> (u64, u64) {
    #[cfg(windows)]
    let total = unsafe {
        let mut info: windows_sys::Win32::System::SystemInformation::MEMORYSTATUSEX =
            std::mem::zeroed();
        info.dwLength = std::mem::size_of_val(&info) as u32;
        if windows_sys::Win32::System::SystemInformation::GlobalMemoryStatusEx(&mut info) != 0 {
            info.ullTotalPhys / 1048576
        } else {
            8192
        }
    };
    #[cfg(not(windows))]
    let total = fs::read_to_string("/proc/meminfo")
        .ok()
        .and_then(|s| {
            s.lines().find_map(|l| {
                l.strip_prefix("MemTotal:")
                    .and_then(|s| s.split_whitespace().next())
                    .and_then(|s| s.parse::<u64>().ok())
            })
        })
        .map(|n| n / 1024)
        .unwrap_or(8192);
    let quarter = (((total + 512) / 1024 * 1024) / 4).clamp(2048, 8192);
    let recommended = [2048, 4096, 6144, 8192, 12288, 16384]
        .into_iter()
        .filter(|n| *n <= quarter)
        .max()
        .unwrap_or(2048)
        .min(total.saturating_sub(1024).max(512));
    (total, recommended)
}
pub fn settings(cx: &Context) -> Result<Value> {
    let path = cx.root.join("launcher.json");
    let mut defaults = json!({"memoryMb":2048,"jvmArgs":"-XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:G1HeapRegionSize=32M -XX:+DisableExplicitGC","showSnapshots":false,"showOldVersions":false,"onlyInstalled":false,"downloadConcurrency":0,"language":"EN","themeMode":"SYSTEM","keepLauncherOpen":false,"discordPresence":true,"discordShowLauncher":true,"discordShowInstance":true,"discordShowServer":false,"libraryGroups":[]});
    defaults["memoryMb"] = json!(memory().1);
    if path.exists() {
        let value = read_json(&path)?;
        for (k, v) in value.as_object().ok_or("Некорректные настройки")? {
            defaults[k] = v.clone();
        }
    }
    Ok(defaults)
}
pub fn roots(cx: &Context) -> Result<(PathBuf, PathBuf)> {
    let s = settings(cx)?;
    let base = if let Some(p) = s["customGameDir"].as_str().filter(|s| !s.is_empty()) {
        let p = PathBuf::from(p);
        if !p.is_absolute() {
            return Err("Папка игры должна быть абсолютной".into());
        }
        p
    } else {
        cx.root.join("instances")
    };
    Ok((base.join("builds"), base.join("packs")))
}
pub fn list(cx: &Context) -> Result<Vec<Value>> {
    let p = cx.root.join("local-builds.json");
    if !p.exists() {
        return Ok(Vec::new());
    }
    let value = read_json(&p)?;
    let rows = value.as_array().ok_or("Повреждён список сборок")?;
    let mut seen = std::collections::HashSet::new();
    Ok(rows
        .iter()
        .filter(|v| {
            v["id"]
                .as_str()
                .is_some_and(|id| storage::id(id).is_ok() && seen.insert(id.to_owned()))
        })
        .cloned()
        .collect())
}
pub fn save(cx: &Context, rows: &[Value]) -> Result<()> {
    write_json(&cx.root.join("local-builds.json"), &json!(rows))
}
pub fn directory(cx: &Context, b: &Value) -> Result<PathBuf> {
    let root = roots(cx)?.0;
    let id = storage::id(b["id"].as_str().ok_or("Нет ID сборки")?)?;
    let old = storage::path(&root, &format!("aw-build-{id}"))?;
    if old.exists() || b["directoryName"].is_null() {
        Ok(old)
    } else {
        let name = b["directoryName"]
            .as_str()
            .filter(|s| storage::filename(s))
            .ok_or("Некорректная папка сборки")?;
        storage::path(&root, name)
    }
}
pub fn packs(cx: &Context) -> Result<Vec<(Value, PathBuf)>> {
    let root = roots(cx)?.1;
    let mut out = Vec::new();
    if !root.exists() {
        return Ok(out);
    }
    for name in storage::names(&root)? {
        let dir = storage::path(&root, &name)?;
        if dir.is_dir() && dir.join("aw-pack.json").is_file() {
            let mut p = read_json(&dir.join("aw-pack.json"))?;
            if !p.is_object() || !p["gameVersion"].is_string() || !p["loader"].is_string() {
                return Err("Повреждён манифест модпака".into());
            }
            p["id"] = json!(name);
            out.push((p, dir));
        }
    }
    out.sort_by_key(|(p, _)| p["title"].as_str().unwrap_or("").to_lowercase());
    Ok(out)
}
pub fn options(dir: &Path) -> Result<Value> {
    let p = dir.join(INSTANCE);
    if p.exists() {
        let value = read_json(&p)?;
        if !value.is_object() {
            return Err("Повреждены настройки сборки".into());
        }
        Ok(value)
    } else {
        Ok(json!({}))
    }
}
pub fn loader_label(kind: &str) -> &str {
    match kind {
        "VANILLA" => "Vanilla",
        "FABRIC" => "Fabric",
        "QUILT" => "Quilt",
        "FORGE" => "Forge",
        "NEOFORGE" => "NeoForge",
        _ => kind,
    }
}
pub fn state(cx: &Context) -> Result<Value> {
    let sessions = if cx.root.join("activity.json").exists() {
        read_json(&cx.root.join("activity.json"))?
    } else {
        json!([])
    };
    let mut profiles = Vec::new();
    let mut add = |key: String,
                   name: Value,
                   game: Value,
                   kind: Value,
                   dir: PathBuf,
                   meta: &Value|
     -> Result<()> {
        let id = key.split_once(':').unwrap().1;
        let played: Vec<_> = sessions
            .as_array()
            .into_iter()
            .flatten()
            .filter(|s| {
                if key.starts_with("build:") {
                    s["buildId"] == id
                } else {
                    s["instance"] == id
                }
            })
            .collect();
        let imported_at = meta["importedAt"].as_u64().unwrap_or(0);
        let historical: u64 = played
            .iter()
            .map(|s| {
                s["end"]
                    .as_u64()
                    .unwrap_or(0)
                    .min(imported_at)
                    .saturating_sub(s["start"].as_u64().unwrap_or(0))
            })
            .sum();
        let duration: u64 = played
            .iter()
            .map(|s| {
                s["end"]
                    .as_u64()
                    .unwrap_or(0)
                    .saturating_sub(s["start"].as_u64().unwrap_or(0))
            })
            .sum();
        let loader = kind.as_str().unwrap_or("VANILLA");
        let version = game.as_str().ok_or("У сборки нет версии Minecraft")?;
        let ready = if loader == "VANILLA" {
            cx.root
                .join("versions")
                .join(version)
                .join(format!("{version}.json"))
                .is_file()
        } else {
            fs::read_dir(cx.root.join("versions"))
                .ok()
                .into_iter()
                .flatten()
                .flatten()
                .any(|e| {
                    let n = e.file_name().to_string_lossy().to_string();
                    match loader {
                        "FABRIC" => {
                            n.starts_with("fabric-loader-") && n.ends_with(&format!("-{version}"))
                        }
                        "QUILT" => {
                            n.starts_with("quilt-loader-") && n.ends_with(&format!("-{version}"))
                        }
                        "FORGE" => n.to_lowercase().starts_with(&format!("{version}-forge")),
                        "NEOFORGE" => {
                            n.starts_with("neoforge-")
                                && read_json(&e.path().join(format!("{n}.json")))
                                    .is_ok_and(|v| v["inheritsFrom"] == version)
                        }
                        _ => false,
                    }
                })
        };
        let last = played
            .iter()
            .filter_map(|s| s["start"].as_u64())
            .chain(meta["lastPlayed"].as_u64())
            .max();
        profiles.push(json!({"key":key,"name":name,"version":game,"loader":loader_label(loader),"loaderVersion":meta["loaderVersion"],"dir":dir,"options":options(&dir)?,"ready":ready,"lastPlayed":last,"playTime":duration+meta["importedPlayTimeMillis"].as_u64().unwrap_or(0).saturating_sub(historical),"importedMs":meta["importedPlayTimeMillis"].as_u64().unwrap_or(0),"importedAt":meta["importedAt"],"icon":meta["iconFile"].as_str().or(meta["iconUrl"].as_str())}));
        Ok(())
    };
    for b in list(cx)? {
        add(
            format!("build:{}", b["id"].as_str().unwrap()),
            b["name"].clone(),
            b["versionId"].clone(),
            b["loader"].clone(),
            directory(cx, &b)?,
            &b,
        )?;
    }
    for (p, dir) in packs(cx)? {
        add(
            format!("pack:{}", p["id"].as_str().unwrap()),
            p["title"].clone(),
            p["gameVersion"].clone(),
            p["loader"].clone(),
            dir,
            &p,
        )?;
    }
    let notices = if cx.root.join("notifications.json").exists() {
        read_json(&cx.root.join("notifications.json"))?
    } else {
        json!({"items":[]})
    };
    Ok(
        json!({"version":"1.0.56","settings":settings(cx)?,"builds":profiles,"accounts":accounts::public(&accounts::store(cx)?),"notices":notices}),
    )
}
pub fn target(cx: &Context, key: &str) -> Result<Value> {
    state(cx)?["builds"]
        .as_array()
        .unwrap()
        .iter()
        .find(|v| v["key"] == key)
        .cloned()
        .ok_or("Сборка больше не найдена".into())
}
pub fn create(
    cx: &Context,
    name: &str,
    version: &str,
    loader: &str,
    pin: Option<&str>,
) -> Result<Value> {
    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 || name.chars().any(char::is_control) {
        return Err("Укажи название сборки до 100 символов".into());
    }
    let mut rows = list(cx)?;
    if rows.iter().any(|b| {
        b["name"]
            .as_str()
            .is_some_and(|s| s.eq_ignore_ascii_case(name))
    }) {
        return Err("Сборка с таким названием уже существует".into());
    }
    if !matches!(
        loader,
        "VANILLA" | "FABRIC" | "QUILT" | "FORGE" | "NEOFORGE"
    ) {
        return Err("Неизвестный загрузчик".into());
    }
    let root = roots(cx)?.0;
    fs::create_dir_all(&root).map_err(err)?;
    let clean = name
        .chars()
        .map(|c| {
            if c.is_control() || "<>:\"/\\|?*".contains(c) {
                '_'
            } else {
                c
            }
        })
        .collect::<String>();
    let clean = clean.trim_end_matches(['.', ' ']).to_owned();
    let clean = if storage::filename(&clean) {
        clean
    } else {
        "Build".into()
    };
    let mut folder = clean.clone();
    let mut n = 1;
    while root.join(&folder).exists() {
        n += 1;
        folder = format!("{clean} ({n})");
    }
    let dir = storage::path(&root, &folder)?;
    fs::create_dir(&dir).map_err(err)?;
    let b = json!({"id":uuid::Uuid::new_v4().to_string(),"name":name,"versionId":version,"loader":loader,"loaderVersion":pin,"directoryName":folder});
    rows.push(b.clone());
    if let Err(e) = save(cx, &rows) {
        let _ = fs::remove_dir(dir);
        return Err(e);
    }
    Ok(b)
}
pub fn change_version(
    cx: &Context,
    key: &str,
    expected: &str,
    new: &str,
    pin: Option<&str>,
) -> Result<()> {
    let p = target(cx, key)?;
    if p["version"] != expected {
        return Err("Версия сборки уже изменилась".into());
    }
    if let Some(id) = key.strip_prefix("build:") {
        let mut rows = list(cx)?;
        let b = rows
            .iter_mut()
            .find(|b| b["id"] == id)
            .ok_or("Сборка не найдена")?;
        b["versionId"] = json!(new);
        b["loaderVersion"] = json!(pin);
        save(cx, &rows)
    } else {
        let dir = PathBuf::from(p["dir"].as_str().unwrap());
        let mut pack = read_json(&dir.join("aw-pack.json"))?;
        pack["gameVersion"] = json!(new);
        pack["loaderVersion"] = json!(pin);
        pack["customGameVersion"] = json!(true);
        write_json(&dir.join("aw-pack.json"), &pack)
    }
}
