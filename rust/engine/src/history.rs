use crate::{accounts, builds, err, read_json, storage, write_json, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};
fn content_items(cx: &Context, profile: &Value) -> Result<Vec<Value>> {
    Ok(crate::content::scan(cx,profile,false,false)?.into_iter().map(|i|{let path=i["file"].as_str().unwrap_or("");let parent=Path::new(path).parent().unwrap_or(Path::new("")).to_string_lossy();let project=i["project"].as_str().filter(|s|!s.is_empty()).unwrap_or(path);json!({"key":format!("{}:{project}:{parent}",i["kind"].as_str().unwrap_or("file")),"path":path,"title":i["title"],"version":i["version"]})}).collect())
}
pub const FOLDER: &str = "aw-build-history";
thread_local! {static ACTIVE:std::cell::RefCell<std::collections::HashSet<PathBuf>>=std::cell::RefCell::new(std::collections::HashSet::new());}
struct RecordGuard(PathBuf);
impl Drop for RecordGuard {
    fn drop(&mut self) {
        ACTIVE.with(|set| {
            set.borrow_mut().remove(&self.0);
        });
    }
}
const EXCLUDED: &[&str] = &[
    FOLDER,
    "aw-migration-backups",
    "logs",
    "crash-reports",
    "screenshots",
    "assets",
    "libraries",
    "versions",
    "natives",
    ".cache",
    "cache",
    ".fabric",
    ".quilt",
    "latest-game.log",
    ".git",
    "accounts.json",
    "launcher_accounts.json",
    "launcher_msa_credentials.bin",
    "launcher_profiles.json",
];
fn allowed(name: &str) -> bool {
    if name.eq_ignore_ascii_case("saves") {
        return name == "saves";
    }
    let parts: Vec<_> = name.split('/').collect();
    if parts.len() == 1 {
        return storage::filename(name) && !EXCLUDED.iter().any(|s| s.eq_ignore_ascii_case(name));
    }
    parts.len() == 3
        && parts[0] == "saves"
        && storage::filename(parts[1])
        && matches!(parts[2], "datapacks" | "aw-disabled-datapacks")
}
fn roots(dir: &Path, worlds: bool) -> Result<Vec<String>> {
    let mut names: BTreeSet<String> = [
        "mods",
        "config",
        "defaultconfigs",
        "resourcepacks",
        "shaderpacks",
        "options.txt",
        "optionsof.txt",
        "optionsshaders.txt",
        "iris.properties",
        builds::INSTANCE,
        "aw-pack.json",
    ]
    .into_iter()
    .map(str::to_owned)
    .collect();
    for name in storage::names(dir)? {
        if name != "saves" && allowed(&name) {
            names.insert(name);
        }
    }
    if worlds {
        names.insert("saves".into());
    } else {
        for name in storage::names(&dir.join("saves"))? {
            let world = storage::path(&dir.join("saves"), &name)?;
            if world.is_dir() {
                for folder in ["datapacks", "aw-disabled-datapacks"] {
                    names.insert(format!("saves/{name}/{folder}"));
                }
            }
        }
    }
    Ok(names.into_iter().collect())
}
fn location(dir: &Path, id: &str) -> Result<PathBuf> {
    storage::id(id)?;
    storage::path(dir, &format!("{FOLDER}/{id}"))
}
pub fn read(dir: &Path, id: &str) -> Result<Value> {
    let entry = read_json(&location(dir, id)?.join("snapshot.json"))?;
    if entry["id"] != id || !entry["gameVersion"].is_string() {
        return Err("Некорректный снимок".into());
    }
    let names: Vec<&str> = entry["roots"]
        .as_array()
        .ok_or("Нет папок снимка")?
        .iter()
        .map(|v| {
            v.as_str()
                .ok_or_else(|| "Некорректный путь снимка".to_owned())
        })
        .collect::<Result<_>>()?;
    if names.iter().any(|n| !allowed(n))
        || names.iter().any(|a| {
            names
                .iter()
                .any(|b| a != b && a.starts_with(&format!("{b}/")))
        })
        || names
            .iter()
            .map(|s| s.to_lowercase())
            .collect::<BTreeSet<_>>()
            .len()
            != names.len()
        || names.contains(&"saves") != (entry["includesWorlds"] == true)
    {
        return Err("Некорректный состав снимка".into());
    }
    for name in entry["files"]
        .as_object()
        .ok_or("Нет файлов снимка")?
        .keys()
        .map(String::as_str)
        .chain(
            entry["directories"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str),
        )
    {
        storage::path(&location(dir, id)?.join("data"), name)?;
        if !names
            .iter()
            .any(|root| name == *root || name.starts_with(&format!("{root}/")))
        {
            return Err("Файл вне снимка".into());
        }
    }
    Ok(entry)
}
pub fn list(dir: &Path) -> Result<Value> {
    let root = dir.join(FOLDER);
    let mut entries = Vec::new();
    for name in storage::names(&root)? {
        if storage::id(&name).is_ok() {
            if let Ok(entry) = read(dir, &name) {
                entries.push(entry);
            }
        }
    }
    entries.sort_by_key(|e| std::cmp::Reverse(e["createdAt"].as_u64().unwrap_or(0)));
    Ok(json!(entries))
}
pub fn create(cx: &Context, profile: &Value, title: &str, completed: bool) -> Result<Value> {
    let dir = PathBuf::from(profile["dir"].as_str().ok_or("Нет папки сборки")?);
    fs::create_dir_all(&dir).map_err(err)?;
    let worlds = builds::options(&dir)?["historyIncludeWorlds"] == true;
    let names = roots(&dir, worlds)?;
    let before = storage::inventory(&dir, &names)?;
    let id = uuid::Uuid::new_v4().to_string();
    let pending = storage::path(&dir, &format!("{FOLDER}/.{id}"))?;
    let data = pending.join("data");
    fs::create_dir_all(&data).map_err(err)?;
    let result = (|| {
        storage::copy(cx, &dir, &data, &names)?;
        if storage::inventory(&dir, &names)? != before
            || storage::inventory(&data, &names)? != before
        {
            return Err("Сборка изменилась при сохранении снимка".into());
        }
        let size: u64 = before
            .0
            .keys()
            .map(|name| fs::metadata(data.join(name)).map(|m| m.len()).unwrap_or(0))
            .sum();
        let entry = json!({"id":id,"createdAt":accounts::now(),"title":title,"gameVersion":profile["version"],"loader":profile["loader"].as_str().unwrap_or("Vanilla").to_uppercase(),"loaderVersion":profile["loaderVersion"],"includesWorlds":worlds,"roots":names,"files":before.0,"directories":before.1,"items":content_items(cx,profile)?,"changes":[],"completed":completed,"targetGameVersion":profile["version"],"sizeBytes":size,"interrupted":false});
        write_json(&pending.join("snapshot.json"), &entry)?;
        fs::rename(&pending, location(&dir, &id)?).map_err(err)?;
        Ok(entry)
    })();
    if result.is_err() {
        let _ = storage::remove(&dir, &format!("{FOLDER}/.{id}"));
    }
    result
}
pub fn finish(cx: &Context, profile: &Value, mut entry: Value, interrupted: bool) -> Result<()> {
    let dir = PathBuf::from(profile["dir"].as_str().ok_or("Нет папки")?);
    let names: Vec<String> = entry["roots"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(Value::as_str)
        .map(str::to_owned)
        .chain(roots(&dir, entry["includesWorlds"] == true)?)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect();
    let now = storage::inventory(&dir, &names)?;
    let before: BTreeMap<String, String> =
        serde_json::from_value(entry["files"].clone()).map_err(err)?;
    let finalize = Context {
        root: cx.root.clone(),
        cancelled: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
        event: cx.event,
    };
    let old_items = entry["items"].as_array().cloned().unwrap_or_default();
    let new_items = content_items(&finalize, profile)?;
    let mut changes = Vec::new();
    let mut represented = BTreeSet::new();
    let keys: BTreeSet<String> = old_items
        .iter()
        .chain(&new_items)
        .filter_map(|i| i["key"].as_str().map(str::to_owned))
        .collect();
    for key in keys {
        let old = old_items.iter().find(|i| i["key"] == key);
        let new = new_items.iter().find(|i| i["key"] == key);
        let changed =
            old.map(|v| (&v["version"], &v["path"])) != new.map(|v| (&v["version"], &v["path"]));
        if changed {
            let title = new
                .or(old)
                .map(|v| v["title"].clone())
                .unwrap_or(json!(key));
            changes.push(json!({"title":title,"before":old.map(|v|v["version"].as_str().filter(|s|!s.is_empty()).unwrap_or("Установлен")),"after":new.map(|v|v["version"].as_str().filter(|s|!s.is_empty()).unwrap_or("Установлен"))}));
            for item in old.into_iter().chain(new) {
                if let Some(path) = item["path"].as_str() {
                    represented.insert(path.to_owned());
                }
            }
        }
    }
    for name in before.keys().chain(now.0.keys()).collect::<BTreeSet<_>>() {
        if before.get(name) != now.0.get(name) && !represented.contains(name) {
            changes.push(json!({"title":name,"before":before.get(name).map(|_|"Сохранённая версия"),"after":now.0.get(name).map(|_|"Изменённая версия")}));
        }
    }
    let current = profile["key"]
        .as_str()
        .and_then(|key| builds::target(cx, key).ok())
        .unwrap_or_else(|| profile.clone());
    entry["targetGameVersion"] = current["version"].clone();
    if entry["gameVersion"] != current["version"] {
        changes.push(
            json!({"title":"Minecraft","before":entry["gameVersion"],"after":current["version"]}),
        );
    }
    entry["changes"] = json!(changes);
    entry["completed"] = json!(true);
    entry["interrupted"] = json!(interrupted);
    write_json(
        &location(&dir, entry["id"].as_str().unwrap())?.join("snapshot.json"),
        &entry,
    )
}
pub fn record<T>(
    cx: &Context,
    profile: &Value,
    title: &str,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    let directory = PathBuf::from(profile["dir"].as_str().ok_or("Нет папки сборки")?);
    if ACTIVE.with(|set| set.borrow().contains(&directory)) {
        return action();
    }
    let entry = create(cx, profile, title, false)?;
    ACTIVE.with(|set| {
        set.borrow_mut().insert(directory.clone());
    });
    let _guard = RecordGuard(directory);
    let result = action();
    let finished = finish(cx, profile, entry, result.is_err());
    match (result, finished) {
        (Ok(v), Ok(())) => Ok(v),
        (Err(e), _) => Err(e),
        (_, Err(e)) => Err(e),
    }
}
pub fn restore(
    cx: &Context,
    profile: &Value,
    id: &str,
    worlds: bool,
    commit: impl FnOnce(&Value) -> Result<()>,
) -> Result<()> {
    let dir = PathBuf::from(profile["dir"].as_str().ok_or("Нет папки")?);
    let entry = read(&dir, id)?;
    if entry["loader"].as_str().unwrap_or("")
        != profile["loader"].as_str().unwrap_or("").to_uppercase()
    {
        return Err("Снимок относится к другому загрузчику".into());
    }
    if worlds && entry["includesWorlds"] != true {
        return Err("В снимке нет миров".into());
    }
    let data = location(&dir, id)?.join("data");
    let original_roots: Vec<String> =
        serde_json::from_value(entry["roots"].clone()).map_err(err)?;
    let expected_files: BTreeMap<String, String> =
        serde_json::from_value(entry["files"].clone()).map_err(err)?;
    let expected_dirs: Vec<String> =
        serde_json::from_value(entry["directories"].clone()).map_err(err)?;
    if storage::inventory(&data, &original_roots)? != (expected_files, expected_dirs) {
        return Err("Файлы снимка повреждены".into());
    }
    let mut selected: BTreeSet<String> = original_roots
        .into_iter()
        .filter(|r| worlds || r != "saves")
        .collect();
    if !worlds && entry["includesWorlds"] == true {
        selected.extend(
            roots(&data, false)?
                .into_iter()
                .filter(|r| r.starts_with("saves/")),
        );
    }
    selected.extend(roots(&dir, worlds)?);
    let selected: Vec<_> = selected.into_iter().collect();
    let before = create(cx, profile, "Перед откатом", true)?;
    let tx_name = format!("{FOLDER}/.restore-{}", uuid::Uuid::new_v4());
    let tx = storage::path(&dir, &tx_name)?;
    let prepared = tx.join("prepared");
    let previous = tx.join("previous");
    fs::create_dir_all(&prepared).map_err(err)?;
    fs::create_dir_all(&previous).map_err(err)?;
    storage::copy(cx, &data, &prepared, &selected)?;
    cx.check()?;
    let mut moved = Vec::new();
    let mut placed = Vec::new();
    let result = (|| {
        for name in &selected {
            let target = storage::path(&dir, name)?;
            if target.exists() {
                let old = storage::path(&previous, name)?;
                fs::create_dir_all(old.parent().unwrap()).map_err(err)?;
                fs::rename(&target, old).map_err(err)?;
                moved.push(name.clone());
            }
            let source = storage::path(&prepared, name)?;
            if source.exists() {
                fs::create_dir_all(target.parent().unwrap()).map_err(err)?;
                fs::rename(source, target).map_err(err)?;
                placed.push(name.clone());
            }
        }
        commit(&entry)
    })();
    if let Err(error) = result {
        let rollback = (|| {
            for name in placed.iter().rev() {
                storage::remove(&dir, name)?;
            }
            for name in moved.iter().rev() {
                let target = storage::path(&dir, name)?;
                fs::create_dir_all(target.parent().unwrap()).map_err(err)?;
                fs::rename(storage::path(&previous, name)?, target).map_err(err)?;
            }
            Ok::<_, String>(())
        })();
        if rollback.is_err() {
            return Err(format!(
                "{error}. Резервные файлы сохранены: {}",
                tx.display()
            ));
        }
        let _ = storage::remove(&dir, &tx_name);
        let _ = finish(cx, profile, before, true);
        return Err(error);
    }
    storage::remove(&dir, &tx_name)?;
    finish(cx, profile, before, false)?;
    Ok(())
}
