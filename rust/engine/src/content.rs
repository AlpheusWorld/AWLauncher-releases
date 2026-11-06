use crate::{builds, err, history, response_json, storage, write_json, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::{BTreeSet, HashMap, HashSet},
    fs,
    path::{Path, PathBuf},
};
#[path = "../../ui/src/library.rs"]
pub mod library;
const MR: &str = "https://api.modrinth.com/v2";
const CF: &str = "https://awlauncher.zarplaykayt.chatgpt.site/api/curseforge";
fn token(id: &str) -> Result<&str> {
    if !id.is_empty()
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
    {
        Ok(id)
    } else {
        Err("Некорректный ID каталога".into())
    }
}
fn cf_ids(id: &str) -> Result<Vec<u64>> {
    let ids = id
        .strip_prefix("cf:")
        .ok_or("Некорректный ID CurseForge")?
        .split(':')
        .map(|s| {
            s.parse::<u64>()
                .ok()
                .filter(|v| *v > 0)
                .ok_or("Некорректный ID CurseForge".into())
        })
        .collect::<Result<Vec<_>>>()?;
    if !(1..=2).contains(&ids.len()) {
        return Err("Некорректный ID CurseForge".into());
    }
    Ok(ids)
}
fn cf(cx: &Context, path: &str, query: &[(&str, String)]) -> Result<Value> {
    let key = std::env::var("AW_CURSEFORGE_API_KEY")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| fs::read_to_string(cx.root.join("curseforge_api_key.txt")).ok())
        .or_else(|| option_env!("AW_CURSEFORGE_API_KEY").map(str::to_owned))
        .unwrap_or_default();
    let base = if key.trim().is_empty() {
        CF
    } else {
        "https://api.curseforge.com/v1"
    };
    let mut request = cx.client()?.get(format!("{base}/{path}")).query(query);
    if !key.trim().is_empty() {
        request = request.header("x-api-key", key.trim());
    }
    let response = response_json(request.send().map_err(|_| "Нет связи с CurseForge")?)?;
    Ok(response)
}
fn cf_mod(cx: &Context, id: &str) -> Result<Value> {
    let id = cf_ids(id)?[0];
    let value = cf(cx, &format!("mods/{id}"), &[])?["data"].clone();
    if value["gameId"] != 432 || value["id"] != id {
        return Err("Проект CurseForge относится к другой игре".into());
    }
    Ok(value)
}
fn cf_kind(cx: &Context, p: &Value) -> Result<String> {
    let values = cf(
        cx,
        "categories",
        &[("gameId", "432".into()), ("classesOnly", "true".into())],
    )?;
    let slug = values["data"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|c| c["id"] == p["classId"])
        .and_then(|c| c["slug"].as_str())
        .ok_or("Неизвестный раздел CurseForge")?;
    Ok(match slug {
        "mc-mods" | "mods" => "mod",
        "modpacks" => "modpack",
        "texture-packs" | "resource-packs" => "resourcepack",
        "shaders" => "shader",
        "data-packs" | "datapacks" => "datapack",
        _ => return Err("Неизвестный раздел CurseForge".into()),
    }
    .into())
}
pub fn project(cx: &Context, id: &str) -> Result<Value> {
    if !id.starts_with("cf:") {
        return cx.json(&format!("{MR}/project/{}", token(id)?));
    }
    let p = cf_mod(cx, id)?;
    let kind = cf_kind(cx, &p)?;
    let description = cf(cx, &format!("mods/{}/description", p["id"]), &[])?["data"]
        .as_str()
        .unwrap_or("")
        .to_owned();
    Ok(
        json!({"id":format!("cf:{}",p["id"]),"slug":p["slug"],"title":p["name"],"description":p["summary"],"icon_url":p["logo"]["url"],"body":description,"project_type":kind,"downloads":p["downloadCount"],"gallery":p["screenshots"].as_array().into_iter().flatten().map(|s|json!({"url":s["url"],"title":s["title"],"description":s["description"]})).collect::<Vec<_>>(),"source_url":p["links"]["sourceUrl"],"issues_url":p["links"]["issuesUrl"],"license":{"id":"custom","name":"CurseForge"}}),
    )
}
fn cf_file(p: &Value, f: &Value, kind: &str) -> Result<Value> {
    if f["gameId"] != 432 || f["modId"] != p["id"] || f["isServerPack"] == true {
        return Err("Файл относится к другой игре или серверной сборке".into());
    }
    let mut files = Vec::new();
    if p["allowModDistribution"] != false && f["isAvailable"] != false {
        if let Some(url) = f["downloadUrl"].as_str() {
            let parsed = reqwest::Url::parse(url).map_err(err)?;
            if parsed.scheme() == "https"
                && matches!(
                    parsed.host_str(),
                    Some("edge.forgecdn.net" | "mediafilez.forgecdn.net" | "media.forgecdn.net")
                )
            {
                let sha = f["hashes"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .find(|h| h["algo"] == 1)
                    .map(|h| h["value"].clone())
                    .unwrap_or(Value::Null);
                files.push(json!({"url":url,"filename":f["fileName"],"size":f["fileLength"],"primary":true,"hashes":{"sha1":sha}}));
            }
        }
    }
    let all: Vec<String> = f["gameVersions"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(str::to_owned)
        .collect();
    let loaders: Vec<String> = match kind {
        "resourcepack" => vec!["minecraft".into()],
        "shader" => vec!["iris".into(), "optifine".into()],
        "datapack" => vec!["datapack".into()],
        _ => all
            .iter()
            .map(|s| s.to_lowercase())
            .filter(|s| matches!(s.as_str(), "forge" | "fabric" | "quilt" | "neoforge"))
            .collect(),
    };
    Ok(
        json!({"id":format!("cf:{}:{}",p["id"],f["id"]),"project_id":format!("cf:{}",p["id"]),"name":f["displayName"],"version_number":f["displayName"].as_str().filter(|s|!s.is_empty()).or(f["fileName"].as_str()),"version_type":match f["releaseType"].as_u64(){Some(1)=>"release",Some(2)=>"beta",_=>"alpha"},"date_published":f["fileDate"],"game_versions":all.iter().filter(|s|s.as_bytes().first().is_some_and(u8::is_ascii_digit)).collect::<Vec<_>>(),"loaders":loaders,"files":files,"dependencies":f["dependencies"].as_array().into_iter().flatten().filter(|d|matches!(d["relationType"].as_u64(),Some(2|3|5))).map(|d|json!({"project_id":format!("cf:{}",d["modId"]),"dependency_type":match d["relationType"].as_u64(){Some(2)=>"optional",Some(5)=>"incompatible",_=>"required"}})).collect::<Vec<_>>(),"changelog":"","downloads":f["downloadCount"]}),
    )
}
pub fn version(cx: &Context, id: &str) -> Result<Value> {
    if !id.starts_with("cf:") {
        return cx.json(&format!("{MR}/version/{}", token(id)?));
    }
    let ids = cf_ids(id)?;
    let file = *ids.get(1).ok_or("Не указан выпуск CurseForge")?;
    let p = cf_mod(cx, id)?;
    let kind = cf_kind(cx, &p)?;
    let f = cf(cx, &format!("mods/{}/files/{file}", ids[0]), &[])?;
    cf_file(&p, &f["data"], &kind)
}
pub fn loaders(kind: &str, loader: &str) -> Vec<String> {
    match kind {
        "resourcepack" => vec!["minecraft".into()],
        "datapack" => vec!["datapack".into()],
        "shader" => vec!["iris".into(), "optifine".into()],
        _ => match loader.to_uppercase().as_str() {
            "QUILT" => vec!["quilt".into(), "fabric".into()],
            "VANILLA" => Vec::new(),
            _ => vec![loader.to_lowercase()],
        },
    }
}
pub fn versions(
    cx: &Context,
    id: &str,
    platforms: &[String],
    game: Option<&str>,
) -> Result<Vec<Value>> {
    let mut rows = if !id.starts_with("cf:") {
        let mut request = cx
            .client()?
            .get(format!("{MR}/project/{}/version", token(id)?));
        if !platforms.is_empty() {
            request = request.query(&[("loaders", json!(platforms).to_string())]);
        }
        if let Some(game) = game {
            request = request.query(&[("game_versions", json!([game]).to_string())]);
        }
        let data = response_json(request.send().map_err(|_| "Нет связи с Modrinth")?)?;
        data.as_array()
            .ok_or("Некорректный список выпусков")?
            .clone()
    } else {
        let p = cf_mod(cx, id)?;
        let kind = cf_kind(cx, &p)?;
        let mut out = Vec::new();
        for offset in (0..10000).step_by(50) {
            cx.check()?;
            let mut q = vec![("index", offset.to_string()), ("pageSize", "50".into())];
            if let Some(game) = game {
                q.push(("gameVersion", game.into()));
            }
            let reply = cf(cx, &format!("mods/{}/files", cf_ids(id)?[0]), &q)?;
            let arr = reply["data"]
                .as_array()
                .ok_or("Некорректный ответ CurseForge")?;
            for f in arr {
                if let Ok(mapped) = cf_file(&p, f, &kind) {
                    if (game.is_none()
                        || mapped["game_versions"]
                            .as_array()
                            .into_iter()
                            .flatten()
                            .any(|v| Some(v.as_str().unwrap_or("")) == game))
                        && (platforms.is_empty()
                            || mapped["loaders"]
                                .as_array()
                                .into_iter()
                                .flatten()
                                .any(|v| platforms.iter().any(|p| v == p)))
                    {
                        out.push(mapped);
                    }
                }
            }
            if arr.len() < 50 {
                break;
            }
        }
        out
    };
    rows.sort_by_key(|v| std::cmp::Reverse(v["date_published"].as_str().unwrap_or("").to_owned()));
    Ok(rows)
}
pub fn pick(rows: &[Value]) -> Option<&Value> {
    rows.iter()
        .find(|v| v["version_type"] == "release")
        .or_else(|| rows.iter().find(|v| v["version_type"] == "beta"))
        .or_else(|| rows.first())
}
pub fn primary(v: &Value) -> Result<&Value> {
    let rows = v["files"].as_array().ok_or("В выпуске нет файлов")?;
    rows.iter()
        .find(|f| f["primary"] == true)
        .or_else(|| rows.first())
        .ok_or("Автор запретил распространение файла или файл недоступен".into())
}
fn metadata(cx: &Context, hash: &str) -> Result<Value> {
    cx.json(&format!(
        "{MR}/version_file/{}?algorithm=sha1",
        token(hash)?
    ))
}
fn folder(kind: &str) -> Result<&str> {
    match kind {
        "mod" => Ok("mods"),
        "shader" => Ok("shaderpacks"),
        "resourcepack" => Ok("resourcepacks"),
        "datapack" => Ok("datapacks"),
        _ => Err("Неизвестный тип контента".into()),
    }
}
pub fn normalize_datapack(cx: &Context, file: &Path) -> Result<()> {
    use std::io::{Read, Write};
    let mut zip = zip::ZipArchive::new(fs::File::open(file).map_err(err)?).map_err(err)?;
    if zip.len() > 10000 {
        return Err("Архив датапака слишком большой".into());
    }
    let mut names = Vec::new();
    let mut total = 0u64;
    for i in 0..zip.len() {
        let entry = zip.by_index(i).map_err(err)?;
        let name = entry.name().trim_end_matches('/');
        storage::path(Path::new("archive"), name)?;
        if entry.size() > 64 * 1024 * 1024
            || entry.unix_mode().is_some_and(|m| m & 0o170000 == 0o120000)
        {
            return Err("Небезопасная структура датапака".into());
        }
        total = total
            .checked_add(entry.size())
            .ok_or("Датапак слишком большой")?;
        if total > 256 * 1024 * 1024 {
            return Err("Датапак слишком большой".into());
        }
        if !entry.is_dir() {
            names.push(entry.name().to_owned());
        }
    }
    let metadata = if names.iter().any(|s| s == "pack.mcmeta") {
        Some("pack.mcmeta".to_owned())
    } else {
        let values: Vec<_> = names
            .iter()
            .filter(|s| {
                s.ends_with("/pack.mcmeta") && s.bytes().filter(|b| *b == b'/').count() == 1
            })
            .collect();
        if values.len() == 1 {
            Some(values[0].to_string())
        } else {
            None
        }
    }
    .ok_or("В архиве нет pack.mcmeta")?;
    {
        let mut bytes = Vec::new();
        zip.by_name(&metadata)
            .map_err(err)?
            .take(1024 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(err)?;
        if bytes.len() > 1024 * 1024
            || !serde_json::from_slice::<Value>(&bytes).is_ok_and(|v| v["pack"].is_object())
        {
            return Err("Некорректный pack.mcmeta".into());
        }
    }
    let prefix = metadata.strip_suffix("pack.mcmeta").unwrap();
    if names.iter().any(|s| !s.starts_with(prefix)) {
        return Err("В датапаке несколько корневых папок".into());
    }
    if prefix.is_empty() {
        return Ok(());
    }
    let normalized = file.with_extension(format!("{}.normalized", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut output = zip::ZipWriter::new(fs::File::create(&normalized).map_err(err)?);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        let mut count = 0u64;
        for name in names {
            cx.check()?;
            output
                .start_file(name.strip_prefix(prefix).unwrap(), options)
                .map_err(err)?;
            let mut member = zip.by_name(&name).map_err(err)?;
            let mut bytes = [0; 65536];
            loop {
                let n = member.read(&mut bytes).map_err(err)?;
                if n == 0 {
                    break;
                }
                count += n as u64;
                if count > 256 * 1024 * 1024 {
                    return Err("Датапак слишком большой".into());
                }
                output.write_all(&bytes[..n]).map_err(err)?;
            }
        }
        output.finish().map_err(err)?;
        drop(zip);
        crate::replace(&normalized, file)
    })();
    let _ = fs::remove_file(normalized);
    result
}
pub fn scan(cx: &Context, profile: &Value, updates: bool, identify: bool) -> Result<Vec<Value>> {
    let dir = PathBuf::from(profile["dir"].as_str().ok_or("Нет папки сборки")?);
    let options = builds::options(&dir)?;
    let managed = options["catalogMods"].as_array();
    let mut scanned = Vec::new();
    for item in library::scan_content(&dir).map_err(err)? {
        cx.check()?;
        let path = storage::path(&dir, &item.relative)?;
        let hash = if path.is_file() {
            storage::hash(&path)?
        } else {
            String::new()
        };
        scanned.push((item, hash));
    }
    let mut remotes = serde_json::Map::new();
    if identify {
        let hashes: Vec<_> = scanned
            .iter()
            .filter(|(item, hash)| {
                !hash.is_empty()
                    && !managed.into_iter().flatten().any(|m| {
                        m["sha1"] == *hash
                            || m["fileName"] == item.relative
                            || m["fileName"] == item.name
                    })
            })
            .map(|(_, hash)| hash.clone())
            .collect();
        for batch in hashes.chunks(500) {
            let result = (|| {
                response_json(
                    cx.client()?
                        .post(format!("{MR}/version_files"))
                        .json(&json!({"algorithm":"sha1","hashes":batch}))
                        .send()
                        .map_err(|_| "Нет связи с Modrinth")?,
                )
            })();
            if let Ok(value) = result {
                if let Some(values) = value.as_object() {
                    remotes.extend(values.clone());
                }
            }
        }
    }
    let mut latest = serde_json::Map::new();
    if updates {
        for (kind, category) in [
            ("mod", "Моды"),
            ("resourcepack", "Ресурспаки"),
            ("shader", "Шейдеры"),
            ("datapack", "Датапаки"),
        ] {
            let hashes: Vec<_> = scanned
                .iter()
                .filter(|(i, h)| {
                    i.category == category
                        && !h.is_empty()
                        && !managed.into_iter().flatten().any(|m| {
                            m["sha1"] == *h
                                && m["projectId"]
                                    .as_str()
                                    .is_some_and(|s| s.starts_with("cf:"))
                        })
                })
                .map(|(_, h)| h.clone())
                .collect();
            for batch in hashes.chunks(500) {
                let result = (|| {
                    response_json(cx.client()?.post(format!("{MR}/version_files/update")).json(&json!({"algorithm":"sha1","hashes":batch,"loaders":loaders(kind,profile["loader"].as_str().unwrap_or("Vanilla")),"game_versions":[profile["version"]]})).send().map_err(|_|"Нет связи с Modrinth")?)
                })();
                if let Ok(value) = result {
                    if let Some(values) = value.as_object() {
                        latest.extend(values.clone());
                    }
                }
            }
        }
    }
    let mut out = Vec::new();
    for (item, hash) in scanned {
        cx.check()?;
        let kind = match item.category {
            "Моды" => "mod",
            "Шейдеры" => "shader",
            "Ресурспаки" => "resourcepack",
            _ => "datapack",
        };
        let remembered = managed.into_iter().flatten().find(|m| {
            m["sha1"] == hash || m["fileName"] == item.relative || m["fileName"] == item.name
        });
        let remote = remotes.get(&hash);
        let project = remembered
            .map(|v| v["projectId"].clone())
            .or_else(|| remote.map(|v| v["project_id"].clone()))
            .unwrap_or(json!(""));
        let version_id = remembered
            .map(|v| v["versionId"].clone())
            .or_else(|| remote.map(|v| v["id"].clone()))
            .unwrap_or(Value::Null);
        let mut candidate = latest.get(&hash).cloned();
        if updates && project.as_str().is_some_and(|s| s.starts_with("cf:")) {
            if let Ok(rows) = versions(
                cx,
                project.as_str().unwrap(),
                &loaders(kind, profile["loader"].as_str().unwrap_or("Vanilla")),
                profile["version"].as_str(),
            ) {
                candidate = pick(&rows).cloned();
            }
        }
        let installed_date = remembered
            .and_then(|m| m["datePublished"].as_str())
            .or_else(|| remote.and_then(|v| v["date_published"].as_str()))
            .unwrap_or("");
        let update = candidate
            .as_ref()
            .filter(|v| {
                v["id"] != version_id
                    && v["files"].as_array().is_some_and(|a| !a.is_empty())
                    && v["date_published"].as_str().unwrap_or("") >= installed_date
            })
            .and_then(|v| v["id"].as_str())
            .unwrap_or("");
        out.push(json!({"file":item.relative,"title":remembered.and_then(|m|m["title"].as_str()).or_else(||remote.and_then(|v|v["name"].as_str())).unwrap_or(&item.name),"kind":kind,"version":remembered.map(|m|m["versionNumber"].clone()).or_else(||remote.map(|v|v["version_number"].clone())).unwrap_or(json!("")),"enabled":item.enabled,"project":project,"icon":remembered.map(|m|m["iconUrl"].clone()).unwrap_or(json!("")),"update":update,"sha1":hash,"versionId":version_id}));
    }
    Ok(out)
}

fn icon_url(value: &Value) -> Option<&str> {
    let value = value.as_str()?;
    let url = reqwest::Url::parse(value).ok()?;
    (url.scheme() == "https"
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none())
    .then_some(value)
}

/// Version lookups identify a project by file hash, but its icon lives in project metadata.
pub fn scan_display(cx: &Context, profile: &Value) -> Result<Vec<Value>> {
    let mut items = scan(cx, profile, true, true)?;
    let missing: BTreeSet<String> = items
        .iter()
        .filter(|item| icon_url(&item["icon"]).is_none())
        .filter_map(|item| {
            item["project"]
                .as_str()
                .filter(|id| !id.is_empty())
                .map(str::to_owned)
        })
        .collect();
    let modrinth: Vec<_> = missing.iter().filter(|id| token(id).is_ok()).collect();
    let mut icons = HashMap::new();
    for batch in modrinth.chunks(50) {
        cx.check()?;
        let response = cx.client().and_then(|client| {
            let response = client
                .get(format!("{MR}/projects"))
                .query(&[("ids", json!(batch).to_string())])
                .send()
                .map_err(|_| "Нет связи с Modrinth")?;
            response_json(response)
        });
        if let Ok(projects) = response {
            for project in projects.as_array().into_iter().flatten() {
                if let (Some(id), Some(icon)) =
                    (project["id"].as_str(), icon_url(&project["icon_url"]))
                {
                    icons.insert(id.to_owned(), icon.to_owned());
                }
            }
        }
    }
    for id in missing.iter().filter(|id| id.starts_with("cf:")) {
        cx.check()?;
        if let Ok(project) = cf_mod(cx, id) {
            if let Some(icon) = icon_url(&project["logo"]["url"]) {
                icons.insert(id.clone(), icon.to_owned());
            }
        }
    }
    for item in &mut items {
        if icon_url(&item["icon"]).is_none() {
            item["icon"] = item["project"]
                .as_str()
                .and_then(|id| icons.get(id))
                .map_or(Value::Null, |icon| json!(icon));
        }
    }
    Ok(items)
}

pub fn remember(dir: &Path, v: &Value, file: &str, title: &str, sha: &str) -> Result<()> {
    let mut opts = builds::options(dir)?;
    let mut rows = opts["catalogMods"].as_array().cloned().unwrap_or_default();
    let icon = rows
        .iter()
        .find(|row| row["projectId"] == v["project_id"] && icon_url(&row["iconUrl"]).is_some())
        .map(|row| row["iconUrl"].clone());
    rows.retain(|m| {
        m["fileName"] != file
            && !(m["projectId"] == v["project_id"]
                && m["fileName"].as_str().is_some_and(|old| {
                    !old.contains('/') || Path::new(old).parent() == Path::new(file).parent()
                }))
    });
    rows.push(json!({"projectId":v["project_id"],"versionId":v["id"],"fileName":file,"sha1":sha,"title":title,"versionNumber":v["version_number"],"datePublished":v["date_published"],"iconUrl":icon}));
    opts["catalogMods"] = json!(rows);
    write_json(&dir.join(builds::INSTANCE), &opts)
}
fn place_version(
    cx: &Context,
    profile: &Value,
    kind: &str,
    world: &str,
    v: &Value,
    title: &str,
) -> Result<()> {
    let dir = PathBuf::from(profile["dir"].as_str().ok_or("Нет папки")?);
    let project = v["project_id"].as_str().ok_or("Нет ID проекта")?;
    let f = primary(v)?;
    let name = f["filename"]
        .as_str()
        .filter(|s| storage::filename(s))
        .ok_or("Некорректное имя файла каталога")?;
    if !name
        .to_lowercase()
        .ends_with(if kind == "mod" { ".jar" } else { ".zip" })
    {
        return Err("Неподходящий файл контента".into());
    }
    let relative = if kind == "datapack" {
        if !storage::filename(world)
            || !storage::path(&dir, &format!("saves/{world}/level.dat"))?.is_file()
        {
            return Err("Выбери существующий мир".into());
        }
        format!("saves/{world}/{}/{name}", folder(kind)?)
    } else {
        format!("{}/{name}", folder(kind)?)
    };
    let staging = cx
        .root
        .join("cache/content")
        .join(format!("{}-{name}", uuid::Uuid::new_v4()));
    cx.download(
        f["url"].as_str().ok_or("Нет URL файла")?,
        &staging,
        f["hashes"]["sha1"].as_str(),
        f["size"].as_u64(),
    )?;
    if kind == "datapack" {
        normalize_datapack(cx, &staging)?;
    }
    if kind == "mod" {
        crate::mod_compat::validate_jar(&staging)?;
    }
    let previous = scan(cx, profile, false, false)?;
    let target = storage::path(&dir, &relative)?;
    if target.exists()
        && previous
            .iter()
            .any(|i| i["file"] == relative && i["project"] != project)
        && storage::hash(&target)? != storage::hash(&staging)?
    {
        return Err("Файл с таким именем принадлежит другому проекту".into());
    }
    fs::create_dir_all(target.parent().unwrap()).map_err(err)?;
    storage::atomic_copy(&staging, &target)?;
    let _ = fs::remove_file(staging);
    for old in previous.iter().filter(|old| {
        old["project"] == project
            && old["kind"] == kind
            && old["file"] != relative
            && Path::new(old["file"].as_str().unwrap_or("")).parent()
                == Path::new(&relative).parent()
    }) {
        let old = old["file"].as_str().unwrap();
        storage::remove(&dir, old)?;
    }
    remember(&dir, v, &relative, title, &storage::hash(&target)?)?;
    if kind == "shader" {
        library::set_shader(&dir, name, true).map_err(err)?;
    }
    if kind == "resourcepack" {
        library::set_resource_pack(&dir, name, true).map_err(err)?;
    }
    Ok(())
}
pub fn install_version(
    cx: &Context,
    profile: &Value,
    kind: &str,
    world: &str,
    v: &Value,
    title: &str,
    visiting: &mut HashSet<String>,
) -> Result<()> {
    if kind == "mod" {
        let installed = scan(cx, profile, false, false)?;
        let plan = crate::dependencies::resolve(cx, profile, &[v.clone()], &installed)?;
        for version in plan {
            let id = version["project_id"]
                .as_str()
                .ok_or("Нет проекта зависимости")?;
            if !visiting.insert(id.into()) {
                if scan(cx, profile, false, false)?
                    .iter()
                    .any(|i| i["project"] == id && i["versionId"] != version["id"])
                {
                    return Err("Моды требуют разные версии одной зависимости".into());
                }
                continue;
            } else {
                place_version(
                    cx,
                    profile,
                    "mod",
                    "",
                    &version,
                    if version["project_id"] == v["project_id"] {
                        title
                    } else {
                        version["name"].as_str().unwrap_or(id)
                    },
                )?;
            }
        }
        let dir = PathBuf::from(profile["dir"].as_str().unwrap());
        crate::mod_compat::check(&dir)?;
        return crate::mod_compat::check_dependencies(&dir);
    }
    if kind == "shader" {
        if !matches!(
            profile["loader"]
                .as_str()
                .unwrap_or("")
                .to_uppercase()
                .as_str(),
            "FABRIC" | "QUILT" | "NEOFORGE"
        ) {
            return Err("Шейдеры требуют Iris: выбери Fabric, Quilt или NeoForge".into());
        }
        if !scan(cx, profile, false, false)?
            .iter()
            .any(|i| i["project"] == "YL57xq9U" && i["enabled"] == true)
            && !crate::mod_compat::enabled_id(
                &PathBuf::from(profile["dir"].as_str().unwrap()),
                "iris",
            )
        {
            let rows = versions(
                cx,
                "YL57xq9U",
                &loaders("mod", profile["loader"].as_str().unwrap()),
                profile["version"].as_str(),
            )?;
            let iris = pick(&rows).ok_or("Нет совместимого Iris для этой сборки")?;
            install_version(cx, profile, "mod", "", iris, "Iris", visiting)?;
        }
    }
    place_version(cx, profile, kind, world, v, title)
}
pub fn install(cx: &Context, profile: &Value, r: &Value) -> Result<Value> {
    let kind = r["kind"].as_str().unwrap_or("mod");
    let project = r["project"].as_str().ok_or("Выбери проект")?;
    let v = if let Some(id) = r["version"].as_str() {
        version(cx, id)?
    } else {
        let rows = versions(
            cx,
            project,
            &loaders(kind, profile["loader"].as_str().unwrap_or("Vanilla")),
            profile["version"].as_str(),
        )?;
        pick(&rows).ok_or("Нет совместимого выпуска")?.clone()
    };
    if v["project_id"] != project {
        return Err("Выпуск относится к другому проекту".into());
    }
    if !v["game_versions"]
        .as_array()
        .into_iter()
        .flatten()
        .any(|g| Some(g.as_str().unwrap_or("")) == profile["version"].as_str())
    {
        return Err("Этот выпуск несовместим с выбранной версией Minecraft".into());
    }
    let dir = PathBuf::from(profile["dir"].as_str().unwrap());
    let mut names = vec!["mods".to_owned(), builds::INSTANCE.into()];
    match kind {
        "shader" => {
            names.extend(["shaderpacks".into(), "config".into()]);
        }
        "resourcepack" => names.extend(["resourcepacks".into(), "options.txt".into()]),
        "datapack" => {
            let world = r["world"]
                .as_str()
                .filter(|s| storage::filename(s))
                .ok_or("Выбери мир")?;
            names.extend([
                format!("saves/{world}/level.dat"),
                format!("saves/{world}/datapacks"),
                format!("saves/{world}/aw-disabled-datapacks"),
            ]);
        }
        _ => {}
    }
    let before = storage::inventory(&dir, &names)?;
    let stage_name = format!("aw-migration-backups/.content-{}", uuid::Uuid::new_v4());
    let stage = storage::path(&dir, &stage_name)?;
    let result = history::record(cx, profile, "Установка контента", || {
        fs::create_dir_all(&stage).map_err(err)?;
        storage::copy(cx, &dir, &stage, &names)?;
        let mut staged = profile.clone();
        staged["dir"] = json!(stage);
        install_version(
            cx,
            &staged,
            kind,
            r["world"].as_str().unwrap_or(""),
            &v,
            r["title"].as_str().unwrap_or("Проект"),
            &mut HashSet::new(),
        )?;
        if storage::inventory(&dir, &names)? != before {
            return Err("Сборка изменилась во время загрузки".into());
        }
        let after = storage::inventory(&stage, &storage::names(&stage)?)?;
        let remove = before
            .0
            .keys()
            .filter(|name| !after.0.contains_key(*name))
            .cloned()
            .collect::<Vec<_>>();
        storage::overlay(cx, &stage, &dir, &remove, || Ok(()))?;
        Ok(json!("Проект установлен"))
    });
    let _ = storage::remove(&dir, &stage_name);
    result
}

pub fn toggle(cx: &Context, profile: &Value, r: &Value) -> Result<Value> {
    let dir = PathBuf::from(profile["dir"].as_str().unwrap());
    let items = scan(cx, profile, false, false)?;
    let item = items
        .iter()
        .find(|i| i["file"] == r["file"])
        .ok_or("Файл больше не найден")?;
    let old = item["file"].as_str().unwrap();
    let enabled = r["enabled"] == true;
    history::record(
        cx,
        profile,
        if r["op"] == "remove" {
            "Удаление контента"
        } else {
            "Переключение контента"
        },
        || {
            if r["op"] == "remove" {
                storage::remove(&dir, old)?;
                return Ok(json!("Контент удалён"));
            }
            let kind = item["kind"].as_str().unwrap();
            let path = storage::path(&dir, old)?;
            let name = path.file_name().unwrap().to_string_lossy();
            let mut result = old.to_owned();
            match kind {
                "resourcepack" => library::set_resource_pack(&dir, &name, enabled).map_err(err)?,
                "shader" => library::set_shader(&dir, &name, enabled).map_err(err)?,
                _ => {
                    let target = if enabled {
                        old.replace("/aw-disabled-datapacks/", "/datapacks/")
                            .strip_suffix(".disabled")
                            .map(str::to_owned)
                            .unwrap_or_else(|| {
                                old.replace("/aw-disabled-datapacks/", "/datapacks/")
                            })
                    } else if kind == "datapack" && path.is_dir() {
                        old.replace("/datapacks/", "/aw-disabled-datapacks/")
                    } else if old.ends_with(".disabled") {
                        old.into()
                    } else {
                        format!("{old}.disabled")
                    };
                    if target != old {
                        let dest = storage::path(&dir, &target)?;
                        if dest.exists() {
                            return Err("Файл с таким именем уже существует".into());
                        }
                        fs::create_dir_all(dest.parent().unwrap()).map_err(err)?;
                        fs::rename(&path, &dest).map_err(err)?;
                        if kind == "mod" && enabled {
                            if let Err(error) = crate::mod_compat::check(&dir)
                                .and_then(|_| crate::mod_compat::check_dependencies(&dir))
                            {
                                let _ = fs::rename(&dest, &path);
                                let v = item["versionId"]
                                    .as_str()
                                    .filter(|s| !s.is_empty())
                                    .and_then(|id| version(cx, id).ok())
                                    .or_else(|| {
                                        item["sha1"]
                                            .as_str()
                                            .and_then(|hash| metadata(cx, hash).ok())
                                    });
                                if let Some(v) = v {
                                    let request = json!({"op":"install","kind":"mod","project":v["project_id"],"version":v["id"],"title":item["title"]});
                                    install(cx, profile, &request)?;
                                    let fresh = scan(cx, profile, false, false)?;
                                    if let Some(new) = fresh.iter().find(|i| {
                                        i["project"] == v["project_id"] && i["enabled"] == true
                                    }) {
                                        return Ok(if r["options"]["returnItem"] == true {
                                            json!({"file":new["file"],"enabled":true})
                                        } else {
                                            json!("Мод и его зависимости включены")
                                        });
                                    }
                                }
                                return Err(error);
                            }
                        }
                        result = target;
                    }
                }
            }
            Ok(if r["options"]["returnItem"] == true {
                json!({"file":result,"enabled":enabled})
            } else {
                json!("Состояние обновлено")
            })
        },
    )
}
pub fn project_view(cx: &Context, r: &Value, profile: Option<&Value>) -> Result<Value> {
    let id = r["project"].as_str().ok_or("Выбери проект")?;
    let p = project(cx, id)?;
    let platforms = profile
        .map(|p| {
            loaders(
                r["kind"].as_str().unwrap_or("mod"),
                p["loader"].as_str().unwrap_or(""),
            )
        })
        .unwrap_or_default();
    let rows = versions(
        cx,
        id,
        &platforms,
        profile.and_then(|p| p["version"].as_str()),
    )?;
    let body = p["body"].as_str().unwrap_or("");
    let html = if id.starts_with("cf:") {
        ammonia::clean(body)
    } else {
        let mut html = String::new();
        pulldown_cmark::html::push_html(
            &mut html,
            pulldown_cmark::Parser::new_ext(body, pulldown_cmark::Options::all()),
        );
        ammonia::clean(&html)
    };
    Ok(json!({"project":p,"versions":rows,"descriptionHtml":html}))
}
