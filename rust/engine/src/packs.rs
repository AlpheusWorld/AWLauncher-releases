use crate::{builds, content, err, history, read_json, storage, write_json, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
};
fn json_member(zip: &mut zip::ZipArchive<fs::File>, name: &str) -> Result<Value> {
    let member = zip
        .by_name(name)
        .map_err(|_| format!("В архиве нет {name}"))?;
    let mut bytes = Vec::new();
    member
        .take(16 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(err)?;
    if bytes.len() > 16 * 1024 * 1024 {
        return Err("Манифест слишком большой".into());
    }
    serde_json::from_slice(&bytes).map_err(err)
}
fn safe(name: &str) -> Result<String> {
    let name = name.replace('\\', "/");
    if matches!(
        name.split('/')
            .next()
            .map(str::to_ascii_lowercase)
            .as_deref(),
        Some(
            "aw-build-history"
                | "aw-migration-backups"
                | "aw-pack.json"
                | "aw-instance.json"
                | "accounts.json"
                | "launcher_accounts.json"
                | "launcher_msa_credentials.bin"
                | "launcher_profiles.json"
        )
    ) {
        return Err("Недопустимый путь модпака".into());
    }
    storage::path(Path::new("archive"), &name)?;
    Ok(name)
}
fn allowed(url: &str) -> bool {
    reqwest::Url::parse(url).is_ok_and(|u| {
        u.scheme() == "https"
            && u.username().is_empty()
            && u.password().is_none()
            && matches!(
                u.host_str(),
                Some(
                    "cdn.modrinth.com"
                        | "github.com"
                        | "raw.githubusercontent.com"
                        | "gitlab.com"
                        | "edge.forgecdn.net"
                        | "mediafilez.forgecdn.net"
                        | "media.forgecdn.net"
                        | "awlauncher.zarplaykayt.chatgpt.site"
                )
            )
    })
}
fn dependencies(index: &Value) -> Result<(String, String, Value)> {
    let d = &index["dependencies"];
    let game = d["minecraft"]
        .as_str()
        .filter(|s| !s.is_empty())
        .ok_or("В модпаке нет версии Minecraft")?
        .to_owned();
    let active: Vec<_> = [
        ("fabric-loader", "FABRIC"),
        ("quilt-loader", "QUILT"),
        ("forge", "FORGE"),
        ("neoforge", "NEOFORGE"),
    ]
    .into_iter()
    .filter(|(k, _)| d[*k].is_string())
    .collect();
    if active.len() > 1 {
        return Err("В модпаке несколько загрузчиков".into());
    }
    Ok(if let Some((key, kind)) = active.first() {
        (game, kind.to_string(), d[*key].clone())
    } else {
        (game, "VANILLA".into(), Value::Null)
    })
}
pub fn unpack(cx: &Context, file: &Path, stage: &Path) -> Result<(Value, Vec<String>)> {
    let mut zip = zip::ZipArchive::new(fs::File::open(file).map_err(err)?).map_err(err)?;
    if zip.len() > 100000 {
        return Err("В архиве слишком много файлов".into());
    }
    let (index, prefixes) = if zip.by_name("modrinth.index.json").is_ok() {
        let index = json_member(&mut zip, "modrinth.index.json")?;
        if index["game"] != "minecraft" || index["formatVersion"] != 1 {
            return Err("Неподдерживаемый формат .mrpack".into());
        }
        (
            index,
            vec!["overrides/".to_owned(), "client-overrides/".to_owned()],
        )
    } else {
        let manifest = json_member(&mut zip, "manifest.json")?;
        if manifest["manifestType"] != "minecraftModpack" || manifest["manifestVersion"] != 1 {
            return Err("Это не модпак CurseForge".into());
        }
        let mut deps = json!({"minecraft":manifest["minecraft"]["version"]});
        let loaders = manifest["minecraft"]["modLoaders"]
            .as_array()
            .cloned()
            .unwrap_or_default();
        let selected = loaders
            .iter()
            .find(|l| l["primary"] == true)
            .or_else(|| loaders.first());
        if let Some(loader) = selected {
            let id = loader["id"].as_str().ok_or("Нет загрузчика")?;
            let (kind, pin) = id.split_once('-').ok_or("Нет версии загрузчика")?;
            let key = match kind {
                "fabric" => "fabric-loader",
                "quilt" => "quilt-loader",
                "forge" => "forge",
                "neoforge" => "neoforge",
                _ => return Err("Неподдерживаемый загрузчик".into()),
            };
            deps[key] = json!(pin);
        }
        let mut files = Vec::new();
        for f in manifest["files"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|f| f["required"] != false)
        {
            cx.check()?;
            let v = content::version(cx, &format!("cf:{}:{}", f["projectID"], f["fileID"]))?;
            let remote = content::primary(&v)?;
            let p = content::project(cx, v["project_id"].as_str().unwrap())?;
            let folder = match p["project_type"].as_str() {
                Some("resourcepack") => "resourcepacks",
                Some("shader") => "shaderpacks",
                _ => "mods",
            };
            let name = remote["filename"].as_str().ok_or("Нет файла мода")?;
            files.push(json!({"path":format!("{folder}/{name}"),"hashes":remote["hashes"],"downloads":[remote["url"]],"fileSize":remote["size"],"catalogVersion":v}));
        }
        let prefix = manifest["overrides"].as_str().unwrap_or("overrides");
        safe(prefix)?;
        (
            json!({"dependencies":deps,"files":files}),
            vec![format!("{prefix}/")],
        )
    };
    dependencies(&index)?;
    let mut seen = HashSet::new();
    let mut downloaded = Vec::new();
    for f in index["files"]
        .as_array()
        .ok_or("Нет списка файлов модпака")?
    {
        cx.check()?;
        if f["env"]["client"] == "unsupported" {
            continue;
        }
        let name = safe(f["path"].as_str().ok_or("Нет пути файла")?)?;
        if !seen.insert(name.to_ascii_lowercase()) {
            return Err("Повторяющийся файл в модпаке".into());
        }
        let target = storage::path(stage, &name)?;
        let hash = f["hashes"]["sha1"].as_str();
        if hash.is_none() && f["hashes"]["sha512"].as_str().is_none() {
            return Err("У файла модпака нет контрольной суммы".into());
        }
        let mut result = Err("Нет разрешённых адресов файла".into());
        for url in f["downloads"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
            .filter(|u| allowed(u))
        {
            result = cx.download(url, &target, hash, f["fileSize"].as_u64());
            if result.is_ok() {
                break;
            }
        }
        result?;
        if let Some(expected) = f["hashes"]["sha512"].as_str() {
            use sha2::{Digest, Sha512};
            let mut file = fs::File::open(&target).map_err(err)?;
            let mut h = Sha512::new();
            let mut bytes = [0; 65536];
            loop {
                let n = file.read(&mut bytes).map_err(err)?;
                if n == 0 {
                    break;
                }
                h.update(&bytes[..n]);
            }
            if format!("{:x}", h.finalize()) != expected {
                return Err("Файл модпака не прошёл SHA-512".into());
            }
        }
        downloaded.push(name);
    }
    let mut total = 0u64;
    for prefix in prefixes {
        for i in 0..zip.len() {
            cx.check()?;
            let mut member = zip.by_index(i).map_err(err)?;
            let raw = member.name().replace('\\', "/");
            let Some(name) = raw.strip_prefix(&prefix) else {
                continue;
            };
            if name.is_empty() || member.is_dir() {
                continue;
            }
            let name = safe(name)?;
            if member.unix_mode().is_some_and(|m| m & 0o170000 == 0o120000) {
                return Err("В архиве символическая ссылка".into());
            }
            total = total
                .checked_add(member.size())
                .ok_or("Архив слишком большой")?;
            if total > 8 * 1024 * 1024 * 1024 {
                return Err("Распакованный архив больше 8 ГБ".into());
            }
            let dest = storage::path(stage, &name)?;
            fs::create_dir_all(dest.parent().unwrap()).map_err(err)?;
            let mut out = fs::File::create(dest).map_err(err)?;
            let mut bytes = [0; 65536];
            loop {
                cx.check()?;
                let n = member.read(&mut bytes).map_err(err)?;
                if n == 0 {
                    break;
                }
                out.write_all(&bytes[..n]).map_err(err)?;
            }
        }
    }
    for f in index["files"].as_array().into_iter().flatten() {
        if f["catalogVersion"].is_object() {
            let v = &f["catalogVersion"];
            let name = Path::new(f["path"].as_str().unwrap())
                .file_name()
                .unwrap()
                .to_string_lossy();
            content::remember(
                stage,
                v,
                &name,
                v["name"].as_str().unwrap_or(&name),
                f["hashes"]["sha1"].as_str().unwrap_or(""),
            )?;
        }
    }
    Ok((index, downloaded))
}
fn pack_id(cx: &Context, project: &str, slug: &str) -> Result<String> {
    if let Some((p, _)) = builds::packs(cx)?
        .iter()
        .find(|(p, _)| p["projectId"] == project)
    {
        return Ok(p["id"].as_str().unwrap().into());
    }
    let base = regex::Regex::new("[^a-z0-9._-]+")
        .unwrap()
        .replace_all(&slug.to_ascii_lowercase(), "-")
        .trim_matches(['-', '.'])
        .to_owned();
    let base = if base.is_empty() {
        "pack".to_owned()
    } else {
        base
    };
    let mut id = base.clone();
    let root = builds::roots(cx)?.1;
    let mut n = 1;
    while root.join(&id).exists() {
        n += 1;
        id = format!("{base}-{n}");
    }
    Ok(id)
}
pub fn install(cx: &Context, r: &Value, reinstall: Option<&Value>) -> Result<Value> {
    let project = reinstall
        .and_then(|p| p["projectId"].as_str())
        .or(r["project"].as_str())
        .ok_or("Нет проекта модпака")?;
    let v = if let Some(id) = reinstall
        .and_then(|p| p["versionId"].as_str())
        .or(r["version"].as_str())
    {
        content::version(cx, id)?
    } else {
        let rows = content::versions(cx, project, &[], None)?;
        content::pick(&rows)
            .ok_or("У модпака нет выпусков")?
            .clone()
    };
    if v["project_id"] != project {
        return Err("Выпуск другого проекта".into());
    }
    let f = content::primary(&v)?;
    let name = f["filename"]
        .as_str()
        .filter(|s| storage::filename(s))
        .ok_or("Некорректный архив")?;
    let id = if let Some(p) = reinstall {
        p["id"].as_str().unwrap().to_owned()
    } else {
        pack_id(cx, project, r["slug"].as_str().unwrap_or(project))?
    };
    let root = builds::roots(cx)?.1;
    let dir = storage::path(&root, &id)?;
    crate::reinstall::ensure_idle(cx, Some(&dir))?;
    let previous = if dir.join("aw-pack.json").exists() {
        Some(read_json(&dir.join("aw-pack.json"))?)
    } else {
        None
    };
    let file = cx
        .root
        .join("cache/packs")
        .join(format!("{}-{name}", uuid::Uuid::new_v4()));
    cx.stage("Скачиваю модпак");
    cx.download(
        f["url"].as_str().ok_or("Нет архива")?,
        &file,
        f["hashes"]["sha1"].as_str(),
        f["size"].as_u64(),
    )?;
    let stage = cx
        .root
        .join("cache")
        .join(format!("pack-stage-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&stage).map_err(err)?;
    let result = (|| {
        if dir.join(builds::INSTANCE).exists() {
            fs::copy(dir.join(builds::INSTANCE), stage.join(builds::INSTANCE)).map_err(err)?;
        }
        let (index, files) = unpack(cx, &file, &stage)?;
        let (game, loader, pin) = dependencies(&index)?;
        let p = content::project(cx, project)?;
        if previous.is_some() {
            for name in ["options.txt", "servers.dat"] {
                if dir.join(name).exists() {
                    let _ = storage::remove(&stage, name);
                }
            }
            let (all, _) = storage::inventory(&stage, &storage::names(&stage)?)?;
            for name in all.keys().filter(|n| n.starts_with("saves/")) {
                if storage::path(&dir, name)?.exists() {
                    storage::remove(&stage, name)?;
                }
            }
        }
        let manifest = json!({"id":id,"title":reinstall.map(|p|p["title"].clone()).unwrap_or_else(||r["title"].as_str().filter(|s|!s.is_empty()).map(|s|json!(s)).unwrap_or(p["title"].clone())),"version":v["version_number"],"gameVersion":game,"loader":loader,"loaderVersion":pin,"projectId":project,"versionId":v["id"],"iconUrl":p["icon_url"],"files":files,"customGameVersion":false});
        write_json(&stage.join("aw-pack.json"), &manifest)?;
        fs::create_dir_all(&dir).map_err(err)?;
        let stale: Vec<String> = previous
            .as_ref()
            .into_iter()
            .flat_map(|p| p["files"].as_array().into_iter().flatten())
            .filter_map(Value::as_str)
            .filter(|n| !files.iter().any(|f| f == n))
            .map(str::to_owned)
            .collect();
        let apply = || storage::overlay(cx, &stage, &dir, &stale, || Ok(()));
        if let Some(old) = &previous {
            let profile = json!({"dir":dir,"version":old["gameVersion"],"loader":builds::loader_label(old["loader"].as_str().unwrap_or("VANILLA")),"loaderVersion":old["loaderVersion"]});
            history::record(cx, &profile, "Обновление модпака", apply)?;
        } else {
            apply()?;
        }
        Ok(json!("Модпак установлен"))
    })();
    let _ = fs::remove_file(file);
    if stage.exists() {
        let _ = storage::remove(
            &cx.root,
            &format!("cache/{}", stage.file_name().unwrap().to_string_lossy()),
        );
    }
    result
}
pub fn export(cx: &Context, profile: &Value, r: &Value) -> Result<Value> {
    let dir = PathBuf::from(profile["dir"].as_str().unwrap());
    let destination = if let Some(file) = r["file"].as_str().filter(|s| !s.is_empty()) {
        PathBuf::from(file)
    } else {
        match rfd::FileDialog::new()
            .add_filter("Modrinth modpack", &["mrpack"])
            .set_file_name(format!("{}.mrpack", r["title"].as_str().unwrap_or("build")))
            .save_file()
        {
            Some(p) => p,
            None => return Ok(Value::Null),
        }
    };
    if !destination.is_absolute()
        || destination.starts_with(&dir)
        || destination.extension().and_then(|e| e.to_str()) != Some("mrpack")
    {
        return Err("Выбери .mrpack вне папки сборки".into());
    }
    if destination.exists() && r["options"]["overwrite"] != true {
        return Err("Подтверди замену архива".into());
    }
    let choices = export_choices(&dir)?;
    let selected: Vec<String> = r["options"]["selected"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(Value::as_str)
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_else(|| choices.clone());
    if selected.iter().any(|n| !choices.contains(n)) {
        return Err("Список файлов изменился".into());
    }
    let (files, dirs) = storage::inventory(&dir, &selected)?;
    let candidates: Vec<_> = files
        .iter()
        .filter(|(path, _)| {
            let mut parts = path.split('/');
            matches!(parts.next(), Some("mods" | "resourcepacks" | "shaderpacks"))
                && parts
                    .next()
                    .is_some_and(|name| !name.to_ascii_lowercase().ends_with(".disabled"))
                && parts.next().is_none()
        })
        .collect();
    let mut indexed = serde_json::Map::new();
    for batch in candidates.chunks(100) {
        cx.check()?;
        let hashes: Vec<_> = batch.iter().map(|(_, hash)| *hash).collect();
        let versions = cx.client().and_then(|client| {
            let response = client
                .post("https://api.modrinth.com/v2/version_files")
                .json(&json!({"hashes":hashes,"algorithm":"sha1"}))
                .send()
                .map_err(|_| "Нет связи с Modrinth")?;
            crate::response_json(response)
        });
        if let Ok(versions) = versions {
            for (name, hash) in batch {
                if let Some(file) = versions[hash.as_str()]["files"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .find(|file| {
                        file["hashes"]["sha1"] == hash.as_str()
                            && file["hashes"]["sha512"].as_str().is_some_and(|sha| {
                                sha.len() == 128 && sha.bytes().all(|b| b.is_ascii_hexdigit())
                            })
                            && file["url"]
                                .as_str()
                                .is_some_and(|url| url.starts_with("https://cdn.modrinth.com/"))
                    })
                {
                    indexed.insert(name.to_string(), json!({"path":name,"hashes":file["hashes"],"downloads":[file["url"]],"fileSize":fs::metadata(storage::path(&dir,name)?).map_err(err)?.len()}));
                }
            }
        }
    }
    let mut deps = json!({"minecraft":profile["version"]});
    let loader = profile["loader"]
        .as_str()
        .unwrap_or("Vanilla")
        .to_uppercase();
    if loader != "VANILLA" {
        let opts = builds::options(&dir)?;
        let pin = opts["loaderVersion"]
            .as_str()
            .or(profile["loaderVersion"].as_str())
            .map(str::to_owned)
            .or_else(|| {
                crate::versions::loaders(cx, profile)
                    .ok()
                    .and_then(|v| v[0]["version"].as_str().map(str::to_owned))
            })
            .ok_or("Укажи версию Loader перед экспортом")?;
        deps[match loader.as_str() {
            "FABRIC" => "fabric-loader",
            "QUILT" => "quilt-loader",
            "FORGE" => "forge",
            _ => "neoforge",
        }] = json!(pin);
    }
    let tmp = destination.with_extension(format!("{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        let mut zip = zip::ZipWriter::new(fs::File::create(&tmp).map_err(err)?);
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        zip.start_file("modrinth.index.json", options)
            .map_err(err)?;
        zip.write_all(json!({"formatVersion":1,"game":"minecraft","versionId":"1.0.0","name":r["title"].as_str().unwrap_or("Build"),"dependencies":deps,"files":indexed.values().collect::<Vec<_>>()}).to_string().as_bytes()).map_err(err)?;
        for directory in dirs {
            zip.add_directory(format!("overrides/{directory}/"), options)
                .map_err(err)?;
        }
        for (name, _) in files {
            cx.check()?;
            if indexed.contains_key(&name) {
                continue;
            }
            zip.start_file(format!("overrides/{name}"), options)
                .map_err(err)?;
            std::io::copy(
                &mut fs::File::open(storage::path(&dir, &name)?).map_err(err)?,
                &mut zip,
            )
            .map_err(err)?;
        }
        zip.finish().map_err(err)?;
        crate::replace(&tmp, &destination)?;
        Ok(json!("Сборка экспортирована"))
    })();
    let _ = fs::remove_file(tmp);
    result
}
pub fn export_choices(dir: &Path) -> Result<Vec<String>> {
    Ok(storage::names(dir)?
        .into_iter()
        .filter(|n| {
            !matches!(
                n.as_str(),
                "aw-pack.json"
                    | "aw-instance.json"
                    | "aw-build-history"
                    | "aw-migration-backups"
                    | "accounts.json"
                    | "launcher_accounts.json"
                    | "launcher_msa_credentials.bin"
                    | "launcher_profiles.json"
            )
        })
        .collect())
}
pub fn import_file(cx: &Context, r: &Value) -> Result<Value> {
    let source = if let Some(file) = r["file"].as_str() {
        PathBuf::from(file)
    } else {
        match rfd::FileDialog::new()
            .set_title("Импорт модпака")
            .add_filter("Модпаки", &["mrpack", "zip"])
            .pick_file()
        {
            Some(path) => path,
            None => return Ok(Value::Null),
        }
    };
    if !source.is_file()
        || !matches!(
            source
                .extension()
                .and_then(|e| e.to_str())
                .map(str::to_lowercase)
                .as_deref(),
            Some("mrpack" | "zip")
        )
    {
        return Err("Выбери .mrpack или .zip модпак".into());
    }
    let stage = cx
        .root
        .join("cache")
        .join(format!("pack-import-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&stage).map_err(err)?;
    let result = (|| {
        let (index, _) = unpack(cx, &source, &stage)?;
        let (game, loader, pin) = dependencies(&index)?;
        let raw = r["title"]
            .as_str()
            .filter(|s| !s.is_empty())
            .or(index["name"].as_str())
            .or(source.file_stem().and_then(|s| s.to_str()))
            .unwrap_or("Импортированная сборка");
        let mut title = raw
            .chars()
            .filter(|c| !c.is_control())
            .take(90)
            .collect::<String>();
        if title.trim().is_empty() {
            title = "Импортированная сборка".into();
        }
        let base = title.clone();
        let existing = builds::list(cx)?;
        let mut n = 1;
        while existing.iter().any(|b| {
            b["name"]
                .as_str()
                .is_some_and(|s| s.to_lowercase() == title.to_lowercase())
        }) {
            n += 1;
            title = format!("{base} ({n})");
        }
        let build = builds::create(cx, &title, &game, &loader, pin.as_str())?;
        let dir = builds::directory(cx, &build)?;
        let applied = storage::overlay(cx, &stage, &dir, &[], || Ok(()));
        if let Err(error) = applied {
            let mut rows = builds::list(cx)?;
            rows.retain(|b| b["id"] != build["id"]);
            builds::save(cx, &rows)?;
            return Err(error);
        }
        Ok(json!("Модпак импортирован"))
    })();
    let _ = storage::remove(
        &cx.root,
        &format!("cache/{}", stage.file_name().unwrap().to_string_lossy()),
    );
    result
}
