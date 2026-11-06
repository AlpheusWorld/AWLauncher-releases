use crate::{accounts, builds, err, read_json, storage, Context, Result};
use serde_json::{json, Value};
use std::{
    fs,
    io::{BufRead, BufReader, Read},
    path::{Path, PathBuf},
};

const METADATA: &[&str] = &[
    "instance.cfg",
    "mmc-pack.json",
    "profile.json",
    "minecraftinstance.json",
    "instance.json",
    "manifest.json",
    "aw-pack.json",
];
const MARKERS: &[&str] = &[
    "mods",
    "saves",
    "options.txt",
    "servers.dat",
    "resourcepacks",
    "logs",
];

#[derive(Clone)]
struct Profile {
    path: PathBuf,
    name: String,
    version: Option<String>,
    loader: String,
    pin: Option<String>,
    played: u64,
    last: Option<u64>,
    launches: u64,
    icon: Option<PathBuf>,
}
fn file_json(path: &Path) -> Value {
    if !fs::symlink_metadata(path)
        .is_ok_and(|m| m.file_type().is_file() && m.len() <= 4 * 1024 * 1024)
    {
        return Value::Null;
    }
    read_json(path).unwrap_or(Value::Null)
}
fn dir(path: &Path) -> bool {
    fs::symlink_metadata(path).is_ok_and(|m| m.is_dir() && !m.file_type().is_symlink())
}
fn metadata(path: &Path) -> bool {
    METADATA.iter().any(|name| path.join(name).is_file())
}
fn playable(path: &Path) -> bool {
    MARKERS.iter().any(|name| path.join(name).exists())
}
fn text(v: &Value) -> Option<&str> {
    v.as_str().filter(|s| !s.trim().is_empty())
}
fn time(v: &Value) -> Option<u64> {
    if let Some(n) = v
        .as_u64()
        .or_else(|| v.as_str().and_then(|s| s.parse().ok()))
    {
        return (n > 0).then(|| {
            if n < 100_000_000_000 {
                n.saturating_mul(1000)
            } else {
                n
            }
        });
    }
    u64::try_from(
        chrono::DateTime::parse_from_rfc3339(v.as_str()?)
            .ok()?
            .timestamp_millis(),
    )
    .ok()
}
fn loader(name: Option<&str>) -> &'static str {
    let value = name.unwrap_or("").to_ascii_lowercase();
    for (prefix, kind) in [
        ("fabric", "FABRIC"),
        ("quilt", "QUILT"),
        ("neoforge", "NEOFORGE"),
        ("forge", "FORGE"),
    ] {
        if value.starts_with(prefix) {
            return kind;
        }
    }
    "VANILLA"
}
fn icon(path: PathBuf) -> Option<PathBuf> {
    fs::symlink_metadata(&path)
        .ok()
        .filter(|m| m.file_type().is_file() && m.len() <= 8 * 1024 * 1024)
        .map(|_| path)
}
fn detect(source: &Path) -> Profile {
    let path = [source.join(".minecraft"), source.join("minecraft")]
        .into_iter()
        .find(|p| dir(p))
        .unwrap_or_else(|| source.into());
    let cfg = fs::File::open(source.join("instance.cfg"))
        .ok()
        .and_then(|f| {
            let mut data = Vec::new();
            f.take(65536).read_to_end(&mut data).ok()?;
            let text = String::from_utf8(data).ok()?;
            Some(
                text.lines()
                    .filter(|line| !line.trim_start().starts_with('#'))
                    .filter_map(|line| line.split_once('='))
                    .map(|(key, value)| (key.trim().to_owned(), value.trim().to_owned()))
                    .collect::<Vec<_>>(),
            )
        })
        .unwrap_or_default();
    let cfg = |key| {
        cfg.iter()
            .find(|(name, _)| name == key)
            .map(|(_, value)| value.as_str())
    };
    let prism = file_json(&source.join("mmc-pack.json"));
    let components = prism["components"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let component = |uid| components.iter().find(|v| v["uid"] == uid);
    let prism_loader = [
        ("net.fabricmc.fabric-loader", "FABRIC"),
        ("org.quiltmc.quilt-loader", "QUILT"),
        ("net.minecraftforge", "FORGE"),
        ("net.neoforged", "NEOFORGE"),
    ]
    .into_iter()
    .find_map(|(uid, kind)| component(uid).map(|v| (v, kind)));
    let profile = file_json(&source.join("profile.json"));
    let modrinth = profile.get("metadata").unwrap_or(&profile);
    let mut curse = file_json(&source.join("minecraftinstance.json"));
    if curse.is_null() {
        curse = file_json(&source.join("manifest.json"));
    }
    let forge = &curse["baseModLoader"];
    let manifest_loaders = curse["minecraft"]["modLoaders"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let manifest_loader = manifest_loaders
        .iter()
        .find(|v| v["primary"] == true)
        .or_else(|| manifest_loaders.first())
        .and_then(|v| text(&v["id"]));
    let at = file_json(&source.join("instance.json"));
    let aw = file_json(&path.join("aw-pack.json"));
    let at_loader = &at["launcher"]["loaderVersion"];
    let mut version = component("net.minecraft")
        .and_then(|v| text(&v["version"]))
        .or(text(&modrinth["game_version"]))
        .or(text(&curse["minecraftVersion"]))
        .or(text(&forge["minecraftVersion"]))
        .or(text(&curse["minecraft"]["version"]))
        .or(text(&at["inheritsFrom"]))
        .or(text(&at["id"]))
        .or(text(&aw["gameVersion"]))
        .map(str::to_owned);
    let mut kind = prism_loader.map(|(_, kind)| kind).unwrap_or_else(|| {
        loader(
            text(&modrinth["loader"])
                .or(text(&forge["name"]))
                .or(manifest_loader)
                .or(text(&at_loader["type"])),
        )
    });
    let mut pin = prism_loader
        .and_then(|(v, _)| text(&v["version"]))
        .or(text(&modrinth["loader_version"]))
        .or(text(&modrinth["loader_version"]["id"]))
        .or(text(&forge["forgeVersion"]))
        .or(manifest_loader.and_then(|s| s.split_once('-').map(|(_, v)| v)))
        .or(text(&at_loader["version"]))
        .map(str::to_owned);
    if !aw.is_null() {
        kind = loader(text(&aw["loader"]));
        pin = text(&aw["loaderVersion"]).map(str::to_owned);
    }
    if version.is_none() {
        if let Ok(log) = fs::File::open(path.join("logs/latest.log")) {
            let pattern =
                regex::Regex::new(r"(?i)Loading Minecraft (\S+) with (Fabric|Quilt) Loader (\S+)")
                    .unwrap();
            for line in BufReader::new(log.take(512 * 1024))
                .lines()
                .take(250)
                .map_while(std::result::Result::ok)
            {
                if let Some(m) = pattern.captures(&line) {
                    version = Some(m[1].to_owned());
                    kind = loader(Some(&m[2]));
                    pin = Some(m[3].to_owned());
                    break;
                }
            }
        }
    }
    let name = cfg("name")
        .or(text(&modrinth["name"]))
        .or(text(&curse["name"]))
        .or(text(&at["launcher"]["name"]))
        .or(text(&aw["title"]))
        .or_else(|| source.file_name().and_then(|n| n.to_str()))
        .unwrap_or("Minecraft");
    let played = cfg("totalTimePlayed")
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or_else(|| {
            modrinth["submitted_time_played"]
                .as_u64()
                .unwrap_or(0)
                .saturating_add(modrinth["recent_time_played"].as_u64().unwrap_or(0))
        });
    let last = cfg("lastLaunchTime")
        .and_then(|s| s.parse::<u64>().ok())
        .filter(|n| *n > 0)
        .or_else(|| time(&modrinth["last_played"]))
        .or_else(|| time(&at["launcher"]["lastPlayed"]));
    let image = [
        source.join("icon.png"),
        source.join("instance.png"),
        path.join("icon.png"),
    ]
    .into_iter()
    .find_map(icon);
    Profile {
        path,
        name: name.into(),
        version,
        loader: kind.into(),
        pin,
        played: played.saturating_mul(1000),
        last,
        launches: at["launcher"]["numPlays"].as_u64().unwrap_or(0),
        icon: image,
    }
}
fn database(app: &Path) -> Result<Vec<Profile>> {
    use rusqlite::{types::ValueRef, Connection, OpenFlags};
    let db = Connection::open_with_flags(app.join("app.db"), OpenFlags::SQLITE_OPEN_READ_ONLY)
        .map_err(err)?;
    db.busy_timeout(std::time::Duration::from_secs(3))
        .map_err(err)?;
    let has = |name: &str| {
        db.query_row(
            "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type='table' AND name=?1)",
            [name],
            |row| row.get::<_, bool>(0),
        )
        .unwrap_or(false)
    };
    let query = if has("instances") && has("instance_content_sets") {
        "SELECT i.name,i.path,i.icon_path,i.last_played,i.submitted_time_played,i.recent_time_played,c.game_version,c.loader,c.loader_version FROM instances i JOIN instance_content_sets c ON c.id=i.applied_content_set_id".into()
    } else if has("profiles") {
        let mut stmt = db.prepare("PRAGMA table_info(profiles)").map_err(err)?;
        let columns = stmt
            .query_map([], |row| row.get::<_, String>(1))
            .map_err(err)?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(err)?;
        let field = |options: &[&'static str]| {
            options
                .iter()
                .copied()
                .find(|s| columns.iter().any(|c| c == s))
                .unwrap_or("NULL")
        };
        format!(
            "SELECT {} FROM profiles",
            [
                field(&["name"]),
                field(&["path", "id"]),
                field(&["icon_path"]),
                field(&["last_played"]),
                field(&["submitted_time_played"]),
                field(&["recent_time_played"]),
                field(&["game_version"]),
                field(&["loader", "mod_loader"]),
                field(&["loader_version", "mod_loader_version"])
            ]
            .join(",")
        )
    } else {
        return Ok(Vec::new());
    };
    let mut stmt = db.prepare(&query).map_err(err)?;
    let mut rows = stmt.query([]).map_err(err)?;
    let mut out = Vec::new();
    let root = app.join("profiles");
    while let Some(row) = rows.next().map_err(err)? {
        let s = |i| {
            row.get::<_, Option<String>>(i)
                .ok()
                .flatten()
                .filter(|s| !s.trim().is_empty())
        };
        let n = |i| row.get::<_, Option<u64>>(i).ok().flatten().unwrap_or(0);
        let Some(relative) = s(1) else { continue };
        let Ok(path) = storage::path(&root, &relative.replace('\\', "/")) else {
            continue;
        };
        if !dir(&path) {
            continue;
        }
        let last = match row.get_ref(3).map_err(err)? {
            ValueRef::Integer(n) => time(&json!(n)),
            ValueRef::Text(s) => time(&json!(String::from_utf8_lossy(s))),
            _ => None,
        };
        let image = s(2)
            .map(PathBuf::from)
            .and_then(|p| icon(if p.is_absolute() { p } else { app.join(p) }));
        out.push(Profile {
            name: s(0).unwrap_or(relative),
            path,
            version: s(6),
            loader: loader(s(7).as_deref()).into(),
            pin: s(8),
            played: n(4).saturating_add(n(5)).saturating_mul(1000),
            last,
            launches: 0,
            icon: image,
        });
    }
    Ok(out)
}
fn discover(root: &Path) -> Result<Vec<Profile>> {
    if !dir(root) {
        return Err("Выбери папку профиля или лаунчера".into());
    }
    if let Some(app) = root
        .ancestors()
        .take(4)
        .find(|p| p.join("app.db").is_file())
    {
        let found: Vec<_> = database(app)?
            .into_iter()
            .filter(|p| p.path.starts_with(root))
            .collect();
        if !found.is_empty() {
            return Ok(found);
        }
    }
    let root = if root
        .file_name()
        .is_some_and(|n| n == ".minecraft" || n == "minecraft")
    {
        root.parent().filter(|p| metadata(p)).unwrap_or(root)
    } else {
        root
    };
    if metadata(root)
        || playable(root)
        || playable(&root.join(".minecraft"))
        || playable(&root.join("minecraft"))
    {
        return Ok(vec![detect(root)]);
    }
    let mut candidates = std::collections::BTreeSet::new();
    for container in [
        root.join("instances"),
        root.join("profiles"),
        root.to_path_buf(),
    ] {
        for entry in fs::read_dir(container).into_iter().flatten().flatten() {
            let p = entry.path();
            if dir(&p)
                && (metadata(&p)
                    || [p.clone(), p.join(".minecraft"), p.join("minecraft")]
                        .iter()
                        .any(|p| playable(p)))
            {
                candidates.insert(p);
            }
        }
    }
    Ok(candidates.iter().map(|p| detect(p)).collect())
}
fn copy_profile(cx: &Context, source: &Path, destination: &Path) -> Result<()> {
    for name in storage::names(source)? {
        cx.check()?;
        let lower = name.to_ascii_lowercase();
        if matches!(
            lower.as_str(),
            "accounts.json"
                | "launcher_accounts.json"
                | "launcher_msa_credentials.bin"
                | "launcher_profiles.json"
                | "launcher_profiles_microsoft_store.json"
                | ".git"
                | "aw-pack.json"
                | "app.db"
                | "app.db-wal"
                | "app.db-shm"
                | "aw-build-history"
                | "aw-migration-backups"
        ) || lower.starts_with("_ias_accounts")
        {
            continue;
        }
        let src = storage::path(source, &name)?;
        let dst = storage::path(destination, &name)?;
        if src.is_dir() {
            fs::create_dir_all(&dst).map_err(err)?;
            copy_profile(cx, &src, &dst)?;
        } else if src.is_file() {
            fs::copy(src, dst).map_err(err)?;
        }
    }
    Ok(())
}
pub fn import(cx: &Context, request: &Value) -> Result<Value> {
    let selected = if let Some(path) = request["file"].as_str().filter(|s| !s.is_empty()) {
        PathBuf::from(path)
    } else {
        match rfd::FileDialog::new()
            .set_title("Выбрать папку профиля Minecraft")
            .pick_folder()
        {
            Some(path) => path,
            None => return Ok(Value::Null),
        }
    };
    let root = selected.canonicalize().map_err(err)?;
    let profiles = discover(&root)?;
    if profiles.is_empty() {
        return Err("В папке не найдены профили Minecraft".into());
    }
    if (profiles.len() > 1 || profiles[0].version.is_none())
        && request["project"].as_str().is_none_or(|s| s.is_empty())
    {
        return Ok(
            json!({"choose":profiles.iter().map(|p|json!({"id":p.path,"name":p.name,"version":p.version,"loader":p.loader})).collect::<Vec<_>>(),"source":root}),
        );
    }
    let profile = if let Some(id) = request["project"].as_str().filter(|s| !s.is_empty()) {
        profiles
            .iter()
            .find(|p| p.path == Path::new(id))
            .ok_or("Профиль больше не найден")?
    } else {
        &profiles[0]
    };
    let version = profile
        .version
        .as_deref()
        .or_else(|| request["version"].as_str())
        .filter(|v| storage::filename(v))
        .ok_or("Выбери версию Minecraft")?;
    let builds_root = builds::roots(cx)?.0;
    fs::create_dir_all(&builds_root).map_err(err)?;
    if builds_root
        .canonicalize()
        .map_err(err)?
        .starts_with(profile.path.canonicalize().map_err(err)?)
    {
        return Err("Папка сборок находится внутри исходного профиля".into());
    }
    let name = profile
        .name
        .trim()
        .chars()
        .filter(|c| !c.is_control())
        .take(80)
        .collect::<String>();
    let base = if name.is_empty() { "Minecraft" } else { &name };
    let rows = builds::list(cx)?;
    let title = (1..1000)
        .map(|n| {
            if n == 1 {
                base.to_owned()
            } else {
                format!("{base} ({n})")
            }
        })
        .find(|title| {
            !rows.iter().any(|b| {
                b["name"]
                    .as_str()
                    .is_some_and(|s| s.to_lowercase() == title.to_lowercase())
            })
        })
        .ok_or("Нет свободного имени сборки")?;
    cx.stage("Копирую профиль Minecraft");
    let build = builds::create(cx, &title, version, &profile.loader, profile.pin.as_deref())?;
    let target = builds::directory(cx, &build)?;
    let result = (|| {
        copy_profile(cx, &profile.path, &target)?;
        let mut rows = builds::list(cx)?;
        let saved = rows
            .iter_mut()
            .find(|b| b["id"] == build["id"])
            .ok_or("Сборка больше не найдена")?;
        saved["importedPlayTimeMillis"] = json!(profile.played);
        saved["importedAt"] = json!(accounts::now());
        saved["lastPlayed"] = json!(profile.last);
        saved["importedLaunchCount"] = json!(profile.launches);
        if let Some(icon) = &profile.icon {
            fs::copy(icon, target.join("aw-build-icon.png")).map_err(err)?;
            saved["iconFile"] = json!("aw-build-icon.png");
        }
        cx.check()?;
        builds::save(cx, &rows)?;
        Ok(json!("Профиль импортирован"))
    })();
    if result.is_err() {
        let mut rows = builds::list(cx)?;
        rows.retain(|b| b["id"] != build["id"]);
        builds::save(cx, &rows)?;
        if let Some(name) = target.file_name().and_then(|p| p.to_str()) {
            storage::remove(&builds_root, name)?;
        }
    }
    result
}
