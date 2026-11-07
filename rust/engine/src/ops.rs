use crate::{
    builds, content, err, history, packs, read_json, storage, write_bytes, write_json, Context,
    Result,
};
use base64::Engine;
use serde_json::{json, Value};
use std::{
    collections::HashSet,
    fs,
    io::Cursor,
    path::{Path, PathBuf},
};
pub fn read_only(op: &str) -> bool {
    matches!(
        op,
        "launcherState"
            | "ping"
            | "content"
            | "contentLocal"
            | "installedProjects"
            | "history"
            | "project"
            | "versions"
            | "accountFace"
            | "accountSkinState"
            | "accountSkinTextures"
            | "accountSkinRead"
            | "buildEditor"
            | "buildLoaderVersions"
            | "buildVersions"
            | "buildDeleteInfo"
            | "buildExportChoices"
            | "buildPickPath"
            | "buildPickIcon"
            | "buildMigrationGet"
            | "checkUpdate"
    )
}
fn dir(p: &Value) -> Result<PathBuf> {
    Ok(PathBuf::from(p["dir"].as_str().ok_or("Нет папки сборки")?))
}
fn settings_write(cx: &Context, s: &Value) -> Result<()> {
    write_json(&cx.root.join("launcher.json"), s)
}
fn image(source: &Path, target: &Path) -> Result<(String, String)> {
    let mut reader = image::ImageReader::open(source)
        .map_err(err)?
        .with_guessed_format()
        .map_err(err)?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(16384);
    limits.max_image_height = Some(16384);
    limits.max_alloc = Some(192 * 1024 * 1024);
    reader.limits(limits);
    let value = reader
        .decode()
        .map_err(|_| "Не удалось прочитать изображение")?
        .thumbnail(512, 512);
    let mut bytes = Cursor::new(Vec::new());
    value
        .write_to(&mut bytes, image::ImageFormat::Png)
        .map_err(err)?;
    let name = format!("aw-image-{}.png", uuid::Uuid::new_v4());
    write_bytes(&target.join(&name), bytes.get_ref())?;
    Ok((
        name,
        format!(
            "data:image/png;base64,{}",
            base64::engine::general_purpose::STANDARD.encode(bytes.get_ref())
        ),
    ))
}
fn groups(cx: &Context, r: &Value) -> Result<Value> {
    let mut s = builds::settings(cx)?;
    let mut groups = s["libraryGroups"].as_array().cloned().unwrap_or_default();
    let action = r["options"]["action"].as_str().unwrap_or("list");
    let name = r["title"].as_str().unwrap_or("").trim();
    let id = r["project"].as_str().unwrap_or("");
    if matches!(action, "create" | "rename")
        && (name.is_empty()
            || name.chars().count() > 40
            || groups.iter().any(|g| {
                g["id"] != id
                    && g["name"]
                        .as_str()
                        .is_some_and(|s| s.eq_ignore_ascii_case(name))
            }))
    {
        return Err("Группа должна иметь уникальное название до 40 символов".into());
    }
    match action {
        "create" => groups.push(json!({"id":uuid::Uuid::new_v4().to_string(),"name":name})),
        "rename" => {
            let g = groups
                .iter_mut()
                .find(|g| g["id"] == id)
                .ok_or("Группа не найдена")?;
            g["name"] = json!(name);
        }
        "remove" => {
            groups.retain(|g| g["id"] != id);
            let state = builds::state(cx)?;
            for p in state["builds"].as_array().unwrap() {
                let d = dir(p)?;
                let mut o = builds::options(&d)?;
                if o["groupId"] == id {
                    o["groupId"] = Value::Null;
                    write_json(&d.join(builds::INSTANCE), &o)?;
                }
            }
        }
        "" | "list" => return Ok(json!(groups)),
        _ => return Err("Неизвестное действие группы".into()),
    };
    s["libraryGroups"] = json!(groups);
    settings_write(cx, &s)?;
    Ok(json!(groups))
}
fn save_options(cx: &Context, p: &Value, r: &Value) -> Result<Value> {
    let d = dir(p)?;
    let incoming = r["options"].as_object().ok_or("Нет параметров сборки")?;
    let mut options = builds::options(&d)?;
    for (key, value) in incoming {
        match key.as_str() {
            "memoryMb" if !value.is_null() => {
                if !value.as_u64().is_some_and(|n| (512..=1048576).contains(&n)) {
                    return Err("Некорректный объём памяти".into());
                }
            }
            "windowWidth" | "windowHeight" if !value.is_null() => {
                let min = if key == "windowWidth" { 320 } else { 200 };
                if !value.as_u64().is_some_and(|n| (min..=16384).contains(&n)) {
                    return Err("Некорректный размер окна".into());
                }
            }
            "jvmArgs" if !value.is_null() => {
                crate::game::split_args(value.as_str().ok_or("Некорректные аргументы JVM")?)?;
            }
            "javaPath" if !value.is_null() => {
                let path = PathBuf::from(value.as_str().ok_or("Некорректный путь Java")?);
                if !path.as_os_str().is_empty() {
                    crate::game::validate_java(&path, 0)?;
                }
            }
            "iconPreset" if !value.is_null() => {
                if !matches!(
                    value.as_str(),
                    Some(
                        "grass"
                            | "crystal"
                            | "craft"
                            | "fox"
                            | "anvil"
                            | "compass"
                            | "flame"
                            | "planet"
                    )
                ) {
                    return Err("Выбери существующую иконку".into());
                }
            }
            "iconBackground" if !value.is_null() => {
                if !matches!(
                    value.as_str(),
                    Some("emerald" | "blue" | "violet" | "rose" | "amber" | "cyan" | "slate")
                ) {
                    return Err("Выбери существующий фон".into());
                }
            }
            "groupId" if !value.is_null() => {
                if !builds::settings(cx)?["libraryGroups"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .any(|g| g["id"] == *value)
                {
                    return Err("Группа больше не найдена".into());
                }
            }
            "loaderVersion" if !value.is_null() => {
                if !value.as_str().is_some_and(|s| {
                    s.len() <= 80
                        && s.bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b".+_-".contains(&b))
                }) {
                    return Err("Некорректная версия Loader".into());
                }
            }
            _ => {}
        }
        if matches!(
            key.as_str(),
            "memoryMb"
                | "windowWidth"
                | "windowHeight"
                | "jvmArgs"
                | "javaPath"
                | "fullscreen"
                | "favorite"
                | "historyIncludeWorlds"
                | "iconPreset"
                | "iconBackground"
                | "groupId"
                | "loaderVersion"
        ) {
            options[key] = value.clone();
        }
    }
    if incoming.contains_key("iconPreset") {
        options["iconImage"] = Value::Null;
        options["iconUrl"] = Value::Null;
    }
    if options["windowWidth"].is_null() != options["windowHeight"].is_null() {
        return Err("Укажи ширину и высоту окна".into());
    }
    write_json(&d.join(builds::INSTANCE), &options)?;
    Ok(json!("Настройки сборки сохранены"))
}
pub fn execute(cx: &Context, r: &Value) -> Result<Value> {
    let op = r["op"].as_str().ok_or("Нет операции")?;
    let key = r["target"].as_str().unwrap_or("");
    match op {
        "ping" => return Ok(json!({"protocol":1,"version":"1.0.56"})),
        "launcherState" => return builds::state(cx),
        "versions" => return crate::versions::list(cx),
        "checkUpdate" | "installUpdate" => return crate::updater::execute(cx, r),
        "accountOffline" | "accountMicrosoft" | "accountRemove" | "accountSelect" => {
            return crate::accounts::handle(cx, r)
        }
        op if op.starts_with("accountSkin") => return crate::skins::handle(cx, r),
        "accountFace" => return crate::skins::account_face(cx),
        "buildGroups" => return groups(cx, r),
        "createBuild" => {
            let game = r["version"].as_str().ok_or("Выбери Minecraft")?;
            if !crate::versions::list(cx)?
                .as_array()
                .unwrap()
                .iter()
                .any(|v| v["id"] == game)
            {
                return Err("Версия Minecraft не найдена".into());
            }
            let loader = r["kind"].as_str().unwrap_or("VANILLA").to_uppercase();
            if loader != "VANILLA"
                && crate::versions::loaders(cx, &json!({"version":game,"loader":loader}))?
                    .as_array()
                    .is_none_or(|a| a.is_empty())
            {
                return Err("Нет доступных выпусков Loader".into());
            }
            builds::create(cx, r["title"].as_str().unwrap_or(""), game, &loader, None)?;
            return Ok(json!("Сборка создана"));
        }
        "project" => {
            let profile = if key.is_empty() {
                None
            } else {
                Some(builds::target(cx, key)?)
            };
            return content::project_view(cx, r, profile.as_ref());
        }
        "install" if r["kind"] == "modpack" => return packs::install(cx, r, None),
        "importPack" => return packs::import_file(cx, r),
        "importProfile" => return crate::profile_import::import(cx, r),
        _ => {}
    }
    let p = builds::target(cx, key)?;
    let d = dir(&p)?;
    match op {
        "launch" => return crate::game::launch(cx, &p),
        "buildLoaderVersions" => return crate::versions::loaders(cx, &p),
        "buildVersions" => return crate::versions::migration_versions(cx, &p),
        "content" => return Ok(json!(content::scan_display(cx, &p)?)),
        "contentLocal" => return Ok(json!(content::scan_local(cx, &p)?)),
        "installedProjects" => return Ok(json!(content::scan(cx, &p, false, true)?)),
        "history" => return history::list(&d),
        "buildEditor" => {
            let (total, recommended) = builds::memory();
            let pack = if key.starts_with("pack:") {
                Some(read_json(&d.join("aw-pack.json"))?)
            } else {
                None
            };
            return Ok(
                json!({"options":builds::options(&d)?,"loader":p["loader"].as_str().unwrap().to_uppercase(),"game":p["version"],"canRename":key.starts_with("build:"),"downloadAllowed":pack.as_ref().is_none_or(|p|p["customGameVersion"]==true),"profileLoaderVersion":p["loaderVersion"],"memoryLimit":(total-1024).max(512),"recommendedMemory":recommended,"memoryPresets":([2048,4096,6144,8192,12288,16384].into_iter().filter(|m|*m<=(total-2048).max(4096)).collect::<Vec<_>>())}),
            );
        }
        "buildDeleteInfo" => {
            return Ok(
                json!({"bytes":storage::size(&d)?,"worlds":d.join("saves").is_dir(),"trash":true}),
            )
        }
        "buildExportChoices" => return Ok(json!(packs::export_choices(&d)?)),
        "buildPickPath" => {
            let picker = rfd::FileDialog::new();
            let result = if r["kind"] == "export" {
                picker
                    .set_title("Сохранить сборку")
                    .set_file_name(format!("{}.mrpack", r["title"].as_str().unwrap_or("Build")))
                    .save_file()
            } else {
                picker.set_title("Выбрать Java").pick_file()
            };
            return Ok(result
                .map(|p| json!({"path":p,"exists":p.exists()}))
                .unwrap_or(Value::Null));
        }
        "buildPickIcon" => {
            let Some(source) = rfd::FileDialog::new()
                .set_title("Иконка сборки")
                .add_filter("Изображения", &["png", "jpg", "jpeg", "webp"])
                .pick_file()
            else {
                return Ok(Value::Null);
            };
            let (name, preview) = image(&source, &cx.root.join("cache/native-icons"))?;
            return Ok(json!({"image":name,"preview":preview}));
        }
        "buildMigrationGet" => return crate::migration::get(cx, r),
        _ => {}
    }
    crate::reinstall::ensure_idle(cx, Some(&d))?;
    match op {
        "saveOptions" => save_options(cx, &p, r),
        "rename" => {
            let id = key
                .strip_prefix("build:")
                .ok_or("Переименование доступно для локальных сборок")?;
            let name = r["title"].as_str().unwrap_or("").trim();
            if name.is_empty() || name.chars().count() > 100 || name.chars().any(char::is_control) {
                return Err("Некорректное название".into());
            }
            let mut rows = builds::list(cx)?;
            if rows.iter().any(|b| {
                b["id"] != id
                    && b["name"]
                        .as_str()
                        .is_some_and(|n| n.eq_ignore_ascii_case(name))
            }) {
                return Err("Название уже занято".into());
            }
            rows.iter_mut()
                .find(|b| b["id"] == id)
                .ok_or("Сборка не найдена")?["name"] = json!(name);
            builds::save(cx, &rows)?;
            Ok(json!("Сборка переименована"))
        }
        "duplicate" => {
            let name = r["title"].as_str().unwrap_or("Копия сборки");
            let b = builds::create(
                cx,
                name,
                p["version"].as_str().unwrap(),
                &p["loader"].as_str().unwrap().to_uppercase(),
                p["loaderVersion"].as_str(),
            )?;
            let target = builds::directory(cx, &b)?;
            let names = storage::names(&d)?
                .into_iter()
                .filter(|n| {
                    !matches!(
                        n.as_str(),
                        "accounts.json"
                            | "launcher_accounts.json"
                            | "launcher_profiles.json"
                            | "launcher_msa_credentials.bin"
                            | "aw-migration-backups"
                    )
                })
                .collect::<Vec<_>>();
            if let Err(e) = storage::copy(cx, &d, &target, &names) {
                let mut rows = builds::list(cx)?;
                rows.retain(|row| row["id"] != b["id"]);
                builds::save(cx, &rows)?;
                return Err(e);
            }
            Ok(json!("Копия сборки создана"))
        }
        "buildSaveIcon" => {
            let mut o = builds::options(&d)?;
            let reset = r["options"]["reset"] == true;
            let image_name = if reset {
                Value::Null
            } else if r["options"]["keepCurrent"] == true {
                o["iconImage"].clone()
            } else if let Some(name) = r["options"]["image"].as_str() {
                let source = storage::path(&cx.root.join("cache/native-icons"), name)?;
                json!(image(&source, &d)?.0)
            } else {
                Value::Null
            };
            o["iconImage"] = image_name.clone();
            o["iconPreset"] = if reset || !image_name.is_null() {
                Value::Null
            } else {
                r["options"]["symbol"].clone()
            };
            o["iconBackground"] = if reset {
                Value::Null
            } else {
                r["options"]["background"].clone()
            };
            o["iconUrl"] = Value::Null;
            let validate = json!({"options":{"iconPreset":o["iconPreset"],"iconBackground":o["iconBackground"]}});
            save_options(cx, &p, &validate)?;
            write_json(&d.join(builds::INSTANCE), &o)?;
            Ok(o)
        }
        "importIcon" => {
            let Some(source) = rfd::FileDialog::new()
                .add_filter("Изображения", &["png", "jpg", "jpeg", "webp"])
                .pick_file()
            else {
                return Ok(Value::Null);
            };
            let mut o = builds::options(&d)?;
            o["iconImage"] = json!(image(&source, &d)?.0);
            o["iconPreset"] = Value::Null;
            o["iconUrl"] = Value::Null;
            write_json(&d.join(builds::INSTANCE), &o)?;
            Ok(json!("Иконка обновлена"))
        }
        "buildDownload" | "buildReinstall" => {
            if key.starts_with("pack:") {
                let pack = read_json(&d.join("aw-pack.json"))?;
                if pack["customGameVersion"] != true {
                    if op == "buildDownload" {
                        return Err("Файлы модпака устанавливаются вместе со сборкой".into());
                    }
                    return packs::install(cx, r, Some(&pack));
                }
            }
            if op == "buildReinstall" {
                let mut local = p.clone();
                local["key"] = json!("build:reinstall");
                crate::reinstall::run(cx, &local)
            } else {
                crate::game::install(cx, &p)
            }
        }
        "buildDelete" => {
            if r["options"]["confirmed"] != true {
                return Err("Подтверди удаление сборки".into());
            }
            let root = if key.starts_with("pack:") {
                builds::roots(cx)?.1
            } else {
                builds::roots(cx)?.0
            };
            if d.parent() != Some(root.as_path()) {
                return Err("Некорректная папка сборки".into());
            }
            storage::path(
                &root,
                d.file_name()
                    .unwrap()
                    .to_str()
                    .ok_or("Некорректная папка")?,
            )?;
            trash::delete(&d).map_err(|_| "Не удалось переместить сборку в корзину")?;
            if let Some(id) = key.strip_prefix("build:") {
                let mut rows = builds::list(cx)?;
                rows.retain(|b| b["id"] != id);
                builds::save(cx, &rows)?;
            }
            let mut s = builds::settings(cx)?;
            for k in ["lastBuildId", "lastPack"] {
                if s[k].as_str() == key.split_once(':').map(|(_, id)| id) {
                    s[k] = Value::Null;
                }
            }
            settings_write(cx, &s)?;
            Ok(json!("Сборка удалена"))
        }
        "buildExport" | "export" => packs::export(cx, &p, r),
        "install" => content::install(cx, &p, r),
        "toggle" | "remove" => content::toggle(cx, &p, r),
        "updateItem" => {
            let rows = content::scan(cx, &p, true, true)?;
            let item = rows
                .iter()
                .find(|v| v["file"] == r["file"])
                .ok_or("Файл больше не найден")?;
            let id = item["update"]
                .as_str()
                .filter(|s| !s.is_empty())
                .ok_or("Проект уже обновлён")?;
            let mut request = r.clone();
            request["op"] = json!("install");
            request["project"] = item["project"].clone();
            request["version"] = json!(id);
            request["title"] = item["title"].clone();
            let path = Path::new(item["file"].as_str().unwrap());
            if item["kind"] == "datapack" {
                request["world"] = json!(path
                    .components()
                    .nth(1)
                    .unwrap()
                    .as_os_str()
                    .to_string_lossy());
            }
            content::install(cx, &p, &request)?;
            if item["enabled"] == false {
                let fresh = content::scan(cx, &p, false, false)?;
                if let Some(i) = fresh.iter().find(|i| i["project"] == item["project"]) {
                    content::toggle(
                        cx,
                        &p,
                        &json!({"op":"toggle","file":i["file"],"enabled":false}),
                    )?;
                }
            }
            Ok(json!("Проект обновлён"))
        }
        "importFiles" => {
            let sources = if let Some(a) = r["options"]["files"].as_array() {
                a.iter()
                    .filter_map(Value::as_str)
                    .map(PathBuf::from)
                    .collect()
            } else {
                match rfd::FileDialog::new()
                    .set_title("Добавить файлы")
                    .pick_files()
                {
                    Some(p) => p,
                    None => return Ok(Value::Null),
                }
            };
            let kind = r["kind"].as_str().unwrap_or("mod");
            let folder = match kind {
                "mod" => "mods".to_owned(),
                "shader" => "shaderpacks".into(),
                "resourcepack" => "resourcepacks".into(),
                "datapack" => {
                    let world = r["world"]
                        .as_str()
                        .filter(|s| storage::filename(s))
                        .ok_or("Выбери мир")?;
                    if !storage::path(&d, &format!("saves/{world}/level.dat"))?.is_file() {
                        return Err("Мир не найден".into());
                    }
                    format!("saves/{world}/datapacks")
                }
                _ => return Err("Неизвестный контент".into()),
            };
            history::record(
                cx,
                &p,
                "Добавление локальных файлов",
                || {
                    let mut names = HashSet::new();
                    for source in sources {
                        cx.check()?;
                        let name = source
                            .file_name()
                            .and_then(|s| s.to_str())
                            .filter(|s| storage::filename(s))
                            .ok_or("Некорректный файл")?;
                        if !source.is_file()
                            || !name.to_lowercase().ends_with(if kind == "mod" {
                                ".jar"
                            } else {
                                ".zip"
                            })
                            || !names.insert(name.to_lowercase())
                        {
                            return Err("Выбери разные файлы подходящего типа".into());
                        }
                        let target = storage::path(&d, &format!("{folder}/{name}"))?;
                        if target.exists() {
                            return Err(format!("Файл {name} уже существует"));
                        }
                        fs::create_dir_all(target.parent().unwrap()).map_err(err)?;
                        fs::copy(source, &target).map_err(err)?;
                        if kind == "datapack" {
                            content::normalize_datapack(cx, &target)?;
                        }
                    }
                    crate::mod_compat::check(&d)?;
                    Ok(json!("Файлы добавлены"))
                },
            )
        }
        "snapshot" => {
            history::create(cx, &p, "Сохранённое состояние", true)?;
            Ok(json!("Состояние сохранено"))
        }
        "restore" => {
            let id = r["snapshot"].as_str().ok_or("Выбери снимок")?;
            history::restore(cx, &p, id, r["options"]["restoreWorlds"] == true, |entry| {
                // A modpack's version is stored in aw-pack.json, which the restore already replaced.
                if key.starts_with("pack:")
                    || entry["gameVersion"] == p["version"]
                        && entry["loaderVersion"] == p["loaderVersion"]
                {
                    return Ok(());
                }
                builds::change_version(
                    cx,
                    key,
                    p["version"].as_str().unwrap(),
                    entry["gameVersion"].as_str().unwrap(),
                    entry["loaderVersion"].as_str(),
                )
            })?;
            Ok(json!("Состояние восстановлено"))
        }
        "buildMigrationAnalyze" | "buildMigrationApply" => crate::migration::execute(cx, &p, r),
        "shortcut" => shortcut(&p, r),
        _ => Err(format!("Неизвестная операция: {op}")),
    }
}
fn shortcut(p: &Value, r: &Value) -> Result<Value> {
    let name = r["title"]
        .as_str()
        .filter(|s| storage::filename(s))
        .unwrap_or("Minecraft");
    let exe = std::env::current_exe().map_err(err)?;
    let root = exe.parent().ok_or("Нет папки приложения")?;
    let exe = if root.file_name().is_some_and(|n| n == "app") {
        root.parent().unwrap().join("AWLauncher.exe")
    } else {
        exe
    };
    let home = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .ok_or("Нет домашней папки")?;
    let desktop = PathBuf::from(home).join("Desktop");
    fs::create_dir_all(&desktop).map_err(err)?;
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let script="$w=New-Object -ComObject WScript.Shell; $s=$w.CreateShortcut($env:AW_SHORTCUT_PATH); $s.TargetPath=$env:AW_SHORTCUT_EXE; $s.Arguments=$env:AW_SHORTCUT_ARGS; $s.WorkingDirectory=[IO.Path]::GetDirectoryName($env:AW_SHORTCUT_EXE); $s.Save()";
        let status = std::process::Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", script])
            .env("AW_SHORTCUT_PATH", desktop.join(format!("{name}.lnk")))
            .env("AW_SHORTCUT_EXE", &exe)
            .env(
                "AW_SHORTCUT_ARGS",
                format!("--launch-build {}", p["key"].as_str().unwrap()),
            )
            .creation_flags(0x08000000)
            .status()
            .map_err(err)?;
        if !status.success() {
            return Err("Не удалось создать ярлык".into());
        }
    }
    #[cfg(not(windows))]
    {
        let text = format!(
            "[Desktop Entry]\nType=Application\nName={name}\nExec=\"{}\" --launch-build {}\n",
            exe.display(),
            p["key"].as_str().unwrap()
        );
        write_bytes(&desktop.join(format!("{name}.desktop")), text.as_bytes())?;
    }
    Ok(json!("Ярлык создан"))
}
