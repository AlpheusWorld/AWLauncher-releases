use crate::{builds, content, err, history, read_json, storage, write_json, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, HashSet},
    fs,
    path::PathBuf,
};
fn file(cx: &Context, id: &str) -> Result<PathBuf> {
    storage::id(id)?;
    storage::path(&cx.root, &format!("cache/native-migrations/{id}.json"))
}
fn describe(id: &str, plan: &Value) -> Value {
    json!({"id":id,"source":plan["source"],"target":plan["target"],"failure":plan["failure"],"mods":plan["mods"].as_array().into_iter().flatten().map(|m|json!({"file":m["file"],"title":m["title"],"enabled":m["enabled"],"version":m["version"],"compatible":m["replacement"].is_object(),"replacement":m["replacement"]["version_number"],"reason":m["reason"]})).collect::<Vec<_>>()})
}
fn fingerprint(dir: &std::path::Path) -> Result<BTreeMap<String, String>> {
    let (mut files, _) = storage::inventory(
        dir,
        &[
            "mods".into(),
            builds::INSTANCE.into(),
            "aw-pack.json".into(),
        ],
    )?;
    files.insert(
        "mods/".into(),
        if dir.join("mods").exists() {
            "present"
        } else {
            "absent"
        }
        .into(),
    );
    Ok(files)
}
fn plan(cx: &Context, r: &Value) -> Result<Value> {
    let id = r["snapshot"].as_str().ok_or("Проверь моды ещё раз")?;
    let path = file(cx, id)?;
    if fs::metadata(&path).map_err(err)?.len() > 8 * 1024 * 1024 {
        return Err("План миграции слишком большой".into());
    }
    let p = read_json(&path)?;
    if p["key"] != r["target"] {
        return Err("Проверка относится к другой сборке".into());
    }
    Ok(p)
}
pub fn get(cx: &Context, r: &Value) -> Result<Value> {
    let p = plan(cx, r)?;
    Ok(describe(r["snapshot"].as_str().unwrap(), &p))
}
pub fn execute(cx: &Context, p: &Value, r: &Value) -> Result<Value> {
    let dir = PathBuf::from(p["dir"].as_str().unwrap());
    if r["op"] == "buildMigrationAnalyze" {
        let target = r["version"].as_str().ok_or("Выбери новую версию")?;
        if !crate::versions::migration_versions(cx, p)?
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v == target)
        {
            return Err("Выбери доступную версию Minecraft с этим загрузчиком".into());
        }
        let before = fingerprint(&dir)?;
        let mut mods = Vec::new();
        for item in content::scan(cx, p, false, true)?
            .into_iter()
            .filter(|i| i["kind"] == "mod")
        {
            cx.check()?;
            let replacement =
                if let Some(project) = item["project"].as_str().filter(|s| !s.is_empty()) {
                    content::versions(
                        cx,
                        project,
                        &content::loaders("mod", p["loader"].as_str().unwrap()),
                        Some(target),
                    )
                    .ok()
                    .and_then(|rows| content::pick(&rows).cloned())
                } else {
                    None
                };
            let file = std::path::Path::new(item["file"].as_str().unwrap())
                .file_name()
                .unwrap()
                .to_string_lossy()
                .to_string();
            let reason = if replacement.is_none() {
                json!("Совместимость не подтверждена или подходящего выпуска нет")
            } else {
                Value::Null
            };
            mods.push(json!({"file":file,"title":item["title"],"enabled":item["enabled"],"sha1":item["sha1"],"project":item["project"],"version":item["version"],"icon":item["icon"],"versionId":item["versionId"],"replacement":replacement,"reason":reason}));
        }
        if fingerprint(&dir)? != before {
            return Err("Сборка изменилась во время проверки".into());
        }
        let id = uuid::Uuid::new_v4().to_string();
        let plan = json!({"key":r["target"],"source":p["version"],"target":target,"loader":p["loader"].as_str().unwrap().to_uppercase(),"mods":mods,"fingerprint":before,"failure":null});
        write_json(&file(cx, &id)?, &plan)?;
        return Ok(describe(&id, &plan));
    }
    let mut stored = plan(cx, r)?;
    if stored["source"] != p["version"]
        || stored["loader"] != p["loader"].as_str().unwrap().to_uppercase()
    {
        return Err("Сборка уже изменилась".into());
    }
    let expected: BTreeMap<String, String> =
        serde_json::from_value(stored["fingerprint"].clone()).map_err(err)?;
    if fingerprint(&dir)? != expected {
        return Err("Сборка изменилась после проверки".into());
    }
    if let Some(disabled) = r["options"]["disableFailed"].as_str() {
        if stored["failure"]["file"] != disabled {
            return Err("Проверь моды ещё раз".into());
        }
        if let Some(item) = stored["mods"]
            .as_array_mut()
            .unwrap()
            .iter_mut()
            .find(|m| m["file"] == disabled)
        {
            item["replacement"] = Value::Null;
            item["enabled"] = json!(false);
            item["reason"] = json!("Отключён после ошибки");
        }
    }
    let keep: HashSet<String> = r["options"]["keep"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(str::to_owned)
        .collect();
    if keep.iter().any(|name| {
        !stored["mods"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["file"] == *name && m["enabled"] == true && !m["replacement"].is_object())
    }) {
        return Err("Некорректный список сохраняемых модов".into());
    }
    let tx_name = format!("aw-migration-backups/{}/prepared", uuid::Uuid::new_v4());
    let stage = storage::path(&dir, &tx_name)?;
    fs::create_dir_all(&stage).map_err(err)?;
    let result = history::record(cx, p, "Смена версии Minecraft", || {
        storage::copy(cx, &dir, &stage, &["mods".into(), builds::INSTANCE.into()])?;
        fs::create_dir_all(stage.join("mods")).map_err(err)?;
        let mut opts = builds::options(&stage)?;
        opts["javaPath"] = Value::Null;
        opts["loaderVersion"] = Value::Null;
        opts["blockedUpdates"] = json!([]);
        write_json(&stage.join(builds::INSTANCE), &opts)?;
        let mut next = p.clone();
        next["version"] = stored["target"].clone();
        next["loaderVersion"] = Value::Null;
        next["dir"] = json!(stage);
        cx.stage("Подготавливаю новую версию Minecraft");
        crate::game::install(cx, &next)?;
        for m in stored["mods"].as_array().unwrap() {
            let name = m["file"]
                .as_str()
                .filter(|s| storage::filename(s))
                .ok_or("Некорректный файл мода")?;
            let path = storage::path(&stage, &format!("mods/{name}"))?;
            if m["replacement"].is_object() {
                if path.exists() {
                    fs::remove_file(&path).map_err(err)?;
                }
            } else if m["enabled"] == true && !keep.contains(name)
                || m["enabled"] == false && !name.ends_with(".disabled")
            {
                let disabled = path.with_file_name(format!("{name}.disabled"));
                if path.exists() {
                    if disabled.exists() {
                        return Err("Совпадают имена отключённых модов".into());
                    }
                    fs::rename(path, disabled).map_err(err)?;
                }
            }
        }
        let mut visiting = HashSet::new();
        let items = stored["mods"].as_array().unwrap().clone();
        for m in items.iter().filter(|m| m["replacement"].is_object()) {
            cx.check()?;
            let v = &m["replacement"];
            let work = if m["enabled"] == true {
                content::install_version(
                    cx,
                    &next,
                    "mod",
                    "",
                    v,
                    m["title"].as_str().unwrap_or("Мод"),
                    &mut visiting,
                )
            } else {
                (|| {
                    let f = content::primary(v)?;
                    let name = f["filename"]
                        .as_str()
                        .filter(|s| storage::filename(s))
                        .ok_or("Некорректное имя мода")?;
                    let target = storage::path(&stage, &format!("mods/{name}.disabled"))?;
                    cx.download(
                        f["url"].as_str().ok_or("Нет URL")?,
                        &target,
                        f["hashes"]["sha1"].as_str(),
                        f["size"].as_u64(),
                    )?;
                    content::remember(
                        &stage,
                        v,
                        name,
                        m["title"].as_str().unwrap_or(name),
                        &storage::hash(&target)?,
                    )
                })()
            };
            if let Err(error) = work {
                stored["failure"] = json!({"file":m["file"],"title":m["title"],"message":error});
                write_json(&file(cx, r["snapshot"].as_str().unwrap())?, &stored)?;
                return Err(error);
            }
        }
        crate::mod_compat::check(&stage)?;
        crate::mod_compat::check_dependencies(cx, &next)?;
        if fingerprint(&dir)? != expected {
            return Err("Сборка изменилась во время загрузки".into());
        }
        cx.check()?;
        cx.stage("Применяю новую версию сборки");
        (cx.event)(json!({"type":"cancellable","data":false}));
        let old = storage::inventory(&dir, &["mods".into()])?.0;
        let fresh = storage::inventory(&stage, &["mods".into()])?.0;
        let remove = old
            .keys()
            .filter(|k| !fresh.contains_key(*k))
            .cloned()
            .collect::<Vec<_>>();
        storage::overlay(cx, &stage, &dir, &remove, || {
            builds::change_version(
                cx,
                r["target"].as_str().unwrap(),
                p["version"].as_str().unwrap(),
                stored["target"].as_str().unwrap(),
                None,
            )
        })?;
        Ok(json!(format!(
            "Сборка обновлена до Minecraft {}",
            stored["target"].as_str().unwrap()
        )))
    });
    let _ = storage::remove(&dir, &tx_name);
    match result {
        Ok(value) => {
            fs::remove_file(file(cx, r["snapshot"].as_str().unwrap())?).map_err(err)?;
            Ok(value)
        }
        Err(error) => {
            let current = plan(cx, r)?;
            if current["failure"].is_object() {
                Ok(
                    json!({"failed":true,"message":error,"plan":describe(r["snapshot"].as_str().unwrap(),&current)}),
                )
            } else {
                Err(error)
            }
        }
    }
}
