#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
#[path = "../../ui/src/activity.rs"]
mod activity;
#[path = "../../ui/src/backend.rs"]
mod backend;
#[path = "../../ui/src/catalog.rs"]
mod catalog;
#[path = "../../ui/src/files.rs"]
mod files;
#[path = "../../ui/src/library.rs"]
mod library;
#[path = "../../ui/src/logs.rs"]
mod logs;
#[path = "../../ui/src/screenshot_files.rs"]
mod screenshot_files;
#[path = "../../ui/src/settings.rs"]
mod settings;
use base64::Engine;
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs,
    io::{BufRead, BufReader, Write},
    path::{Component, Path, PathBuf},
    sync::{Arc, Mutex},
};
use tauri::{ipc::Channel, Manager};
mod discord;
#[path = "../../ui/src/translation_core.rs"]
mod translation_core;

#[tauri::command]
async fn translate_text(language: String, values: Vec<String>) -> Result<Vec<String>, String> {
    if values.len() > 512 || values.iter().map(String::len).sum::<usize>() > 65536 {
        return Err("Слишком большой запрос перевода".into());
    }
    tauri::async_runtime::spawn_blocking(move || {
        values
            .iter()
            .map(|v| translation_core::translate(&language, v))
            .collect()
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
async fn open_url(url: String) -> Result<(), String> {
    let url = reqwest::Url::parse(&url).map_err(|_| "Некорректная ссылка")?;
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.host_str().is_none()
    {
        return Err("Разрешены только HTTPS-ссылки".into());
    }
    #[cfg(windows)]
    let mut command = std::process::Command::new("explorer.exe");
    #[cfg(not(windows))]
    let mut command = std::process::Command::new("xdg-open");
    command
        .arg(url.as_str())
        .spawn()
        .map(|_| ())
        .map_err(|e| e.to_string())
}

fn home_dir() -> Option<PathBuf> {
    std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).map(PathBuf::from)
}
#[tauri::command]
fn running_games(pids: Vec<u32>) -> Vec<u32> {
    pids.into_iter()
        .filter(|pid| backend::process_alive(*pid))
        .collect()
}
#[derive(Default)]
struct State {
    discord: discord::Presence,
    startup_target: Mutex<Option<String>>,
    profiles: Mutex<HashMap<String, Value>>,
    tasks: Mutex<HashMap<String, Arc<std::sync::atomic::AtomicBool>>>,
    engine: Mutex<()>,
    image_decoder: Mutex<()>,
}
fn data_root() -> Result<PathBuf, String> {
    Ok(home_dir().ok_or("Не найдена домашняя папка")?.join(".aw"))
}
fn profile(state: &State, key: &str) -> Result<Value, String> {
    state
        .profiles
        .lock()
        .map_err(|e| e.to_string())?
        .get(key)
        .cloned()
        .ok_or("Сборка больше не найдена".into())
}
fn directory(state: &State, key: &str) -> Result<PathBuf, String> {
    Ok(PathBuf::from(
        profile(state, key)?["dir"]
            .as_str()
            .ok_or("Нет папки сборки")?,
    ))
}
fn safe_path(root: &Path, relative: &str) -> Result<PathBuf, String> {
    let relative = Path::new(relative);
    if relative
        .components()
        .any(|c| !matches!(c, Component::Normal(_)))
    {
        return Err("Некорректный путь".into());
    }
    let root = root.canonicalize().map_err(|e| e.to_string())?;
    let path = root
        .join(relative)
        .canonicalize()
        .map_err(|e| e.to_string())?;
    if !path.starts_with(&root) {
        return Err("Файл находится вне папки сборки".into());
    }
    Ok(path)
}
fn run_task(
    state: &State,
    id: &str,
    request: &Value,
    events: &Channel<Value>,
) -> Result<Value, String> {
    if id.is_empty() || id.len() > 128 {
        return Err("Некорректный номер операции".into());
    }
    if serde_json::to_vec(request)
        .map_err(|e| e.to_string())?
        .len()
        > 65536
    {
        return Err("Слишком большой запрос".into());
    }
    let read_only = aw_engine::ops::read_only(request["op"].as_str().unwrap_or(""));
    let _engine = if read_only {
        None
    } else {
        Some(state.engine.lock().map_err(|e| e.to_string())?)
    };
    let root = data_root()?;
    let _locks = if read_only {
        Vec::new()
    } else {
        aw_engine::operation_locks(&root)?
    };
    let cancelled = Arc::new(std::sync::atomic::AtomicBool::new(false));
    {
        let mut tasks = state.tasks.lock().map_err(|e| e.to_string())?;
        if tasks.contains_key(id) {
            return Err("Такая операция уже выполняется".into());
        }
        tasks.insert(id.to_owned(), cancelled.clone());
    }
    let event = |value| {
        if events.send(value).is_err() {
            cancelled.store(true, std::sync::atomic::Ordering::Relaxed);
        }
    };
    let context = aw_engine::Context {
        root,
        cancelled: cancelled.clone(),
        event: &event,
    };
    let result = aw_engine::ops::execute(&context, request);
    state.tasks.lock().map_err(|e| e.to_string())?.remove(id);
    let _ = events.send(match &result {
        Ok(value) => json!({"type":"done","data":value}),
        Err(_) if cancelled.load(std::sync::atomic::Ordering::Relaxed) => {
            json!({"type":"cancelled"})
        }
        Err(message) => json!({"type":"failed","data":message}),
    });
    result
}

#[tauri::command]
async fn request(
    app: tauri::AppHandle,
    id: String,
    request: Value,
    events: Channel<Value>,
) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let state = app.state::<State>();
        let mut result = run_task(&state, &id, &request, &events)?;
        if request["op"] == "launcherState" {
            if let Some(target) = state
                .startup_target
                .lock()
                .map_err(|e| e.to_string())?
                .take()
            {
                result["startupTarget"] = json!(target);
            }
            let mut profiles = state.profiles.lock().map_err(|e| e.to_string())?;
            profiles.clear();
            if let Some(builds) = result["builds"].as_array_mut() {
                for build in builds {
                    if let Some(dir) = build["dir"].as_str() {
                        let count = library::scan_content(Path::new(dir))
                            .map(|items| items.len())
                            .unwrap_or(0);
                        build["contentCount"] = json!(count);
                    }
                    if let Some(key) = build["key"].as_str() {
                        profiles.insert(key.to_owned(), build.clone());
                    }
                    if let Some(object) = build.as_object_mut() {
                        object.remove("dir");
                    }
                }
            }
            let store = settings::Store::load(data_root()?.join("launcher.json"));
            result["totalMemory"] = json!(store.total_mb);
            result["recommendedMemory"] = json!(settings::recommended(store.total_mb));
            if std::env::var_os("AW_SMOKE_READY").as_deref() == Some(std::ffi::OsStr::new("1")) {
                eprintln!("AW_SMOKE: launcher state returned to WebView");
            }
        }
        if request["op"] == "launch" {
            if let Some(pid) = result["pid"].as_u64().and_then(|id| u32::try_from(id).ok()) {
                if let Some(key) = request["target"].as_str() {
                    if let Ok(build) = profile(&state, key) {
                        state.discord.game_started(pid, build);
                    }
                }
            }
            let keep_open = settings::Store::load(data_root()?.join("launcher.json"))
                .boolean("keepLauncherOpen", false);
            if !keep_open {
                if let (Some(pid), Some(window)) = (
                    result["pid"]
                        .as_u64()
                        .and_then(|pid| u32::try_from(pid).ok()),
                    app.get_webview_window("main"),
                ) {
                    let _ = window.hide();
                    std::thread::spawn(move || {
                        while backend::process_alive(pid) {
                            std::thread::sleep(std::time::Duration::from_secs(2));
                        }
                        let _ = window.show();
                        let _ = window.set_focus();
                    });
                }
            }
        }
        Ok(result)
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
fn cancel(state: tauri::State<State>, id: String) -> Result<(), String> {
    if let Some(cancelled) = state.tasks.lock().map_err(|e| e.to_string())?.get(&id) {
        cancelled.store(true, std::sync::atomic::Ordering::Relaxed);
    }
    Ok(())
}
#[tauri::command]
async fn save_setting(key: String, value: Value) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let mut store = settings::Store::load(data_root()?.join("launcher.json"));
        if store.set(&key, value) {
            Ok(())
        } else {
            Err(store.error.unwrap_or("Настройка не сохранена".into()))
        }
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn local_data(
    app: tauri::AppHandle,
    kind: String,
    target: Option<String>,
    relative: Option<String>,
) -> Result<Value, String> {
    tauri::async_runtime::spawn_blocking(move||{
        let state=app.state::<State>();
        let target=target.as_deref().unwrap_or("");
        let relative=relative.as_deref().unwrap_or("");
        let root=data_root()?;
        match kind.as_str() {
            "resetCache"=>{let mut store=settings::Store::load(root.join("launcher.json"));if store.reset_verify_cache(){Ok(Value::Null)}else{Err(store.error.unwrap_or_default())}},
            "openRoot"=>files::show_folder(&root,Path::new("")).map(|_|Value::Null).map_err(|e|e.to_string()),
            "files"|"worlds"|"openFolder"=>{
                let dir=directory(&state,target)?;
                let relative=if kind=="worlds" {PathBuf::from("saves")}else{PathBuf::from(relative)};
                if kind=="worlds"&&!dir.join("saves").exists(){return Ok(json!([]));}
                if kind=="openFolder" {return files::show_folder(&dir,&relative).map(|_|Value::Null).map_err(|e|e.to_string());}
                let entries=files::list(&dir,&relative).map_err(|e|e.to_string())?;
                Ok(json!(entries.iter().map(|e|json!({"name":e.name,"relative":e.relative,"directory":e.directory,"bytes":e.bytes})).collect::<Vec<_>>()))
            },
            "log"=>logs::read(&directory(&state,target)?,&home_dir().unwrap_or_default().to_string_lossy()).map(|v|json!(v)).map_err(|e|e.to_string()),
            "screenshots"=>{
                let profiles=state.profiles.lock().map_err(|e|e.to_string())?.clone();
                let folders:Vec<_>=profiles.iter().filter(|(key,_)|target.is_empty()||target==key.as_str()).map(|(key,p)|screenshot_files::Folder{key:key.clone(),title:p["name"].as_str().unwrap_or("").into(),dir:PathBuf::from(p["dir"].as_str().unwrap_or(""))}).collect();
                let shots:Vec<_>=screenshot_files::scan(&folders).iter().map(|s|json!({"target":s.key,"title":s.title,"file":format!("screenshots/{}",s.path.file_name().unwrap_or_default().to_string_lossy()),"name":s.path.file_name().unwrap_or_default().to_string_lossy(),"modified":s.modified.duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64})).collect();
                Ok(json!(shots))
            },
            "activity"=>{
                let profiles=state.profiles.lock().map_err(|e|e.to_string())?.clone();
                let mut profiles:Vec<_>=profiles.iter().map(|(key,p)|activity::Profile{id:key.split_once(':').unwrap().1.into(),name:p["name"].as_str().unwrap_or("").into(),version:p["version"].as_str().unwrap_or("").into(),loader:p["loader"].as_str().unwrap_or("").into(),dir:PathBuf::from(p["dir"].as_str().unwrap_or("")),pack:key.starts_with("pack:"),native:false,imported_ms:p["importedMs"].as_i64().unwrap_or(0),imported_at:p["importedAt"].as_i64()}).collect();
                profiles.extend(activity::native_profiles(&[(root.join("instances"),true)],&profiles));
                let mut sessions=activity::collect(&root.join("activity.json"),&root.join("cache/rust-ui-activity.json"),&root.join("cache/rust-ui-activity-index.json"),&profiles).map_err(|e|e.to_string())?;
                if !target.is_empty() {
                    let (kind,id)=target.split_once(':').ok_or("Некорректная сборка")?;
                    sessions.retain(|s|if kind=="build"{s.build_id.as_deref()==Some(id)}else{s.instance==id});
                    profiles.retain(|p|p.id==id&&p.pack==(kind=="pack"));
                }
                serde_json::to_value(activity::stats(&sessions,&profiles,chrono::Local::now().date_naive())).map_err(|e|e.to_string())
            },
            _=>Err("Неизвестная операция".into())
        }
    }).await.map_err(|e|e.to_string())?
}
#[tauri::command]
async fn image_data(
    app: tauri::AppHandle,
    target: String,
    file: String,
    large: bool,
) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let state = app.state::<State>();
        let _decoder = state.image_decoder.lock().map_err(|e| e.to_string())?;
        let path = safe_path(&directory(&state, &target)?, &file)?;
        if fs::metadata(&path).map_err(|e| e.to_string())?.len() > 64 * 1024 * 1024 {
            return Err("Изображение слишком большое".into());
        }
        let mut reader = image::ImageReader::open(path)
            .map_err(|e| e.to_string())?
            .with_guessed_format()
            .map_err(|e| e.to_string())?;
        let mut limits = image::Limits::default();
        limits.max_image_width = Some(16384);
        limits.max_image_height = Some(16384);
        limits.max_alloc = Some(192 * 1024 * 1024);
        reader.limits(limits);
        let size = if large { 1920 } else { 384 };
        let image = reader
            .decode()
            .map_err(|e| e.to_string())?
            .thumbnail(size, size);
        let mut bytes = std::io::Cursor::new(Vec::new());
        let mime = if file.starts_with("screenshots/") {
            image::codecs::jpeg::JpegEncoder::new_with_quality(
                &mut bytes,
                if large { 90 } else { 82 },
            )
            .encode_image(&image)
            .map_err(|e| e.to_string())?;
            "image/jpeg"
        } else {
            image
                .write_to(&mut bytes, image::ImageFormat::Png)
                .map_err(|e| e.to_string())?;
            "image/png"
        };
        Ok(format!(
            "data:{mime};base64,{}",
            base64::engine::general_purpose::STANDARD.encode(bytes.into_inner())
        ))
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn search(
    query: String,
    kind: String,
    offset: u32,
    curseforge: bool,
    game: Option<String>,
    loader: Option<String>,
    sort: String,
) -> Result<catalog::Page, String> {
    tauri::async_runtime::spawn_blocking(move || {
        catalog::search(
            &query,
            &kind,
            offset,
            curseforge,
            game.as_deref(),
            loader.as_deref(),
            &sort,
        )
    })
    .await
    .map_err(|e| e.to_string())?
}
fn main() {
    let args: Vec<_> = std::env::args_os().collect();
    if args.get(1).is_some_and(|a| a == "--native-task") {
        native_cli();
        return;
    }
    let value_after = |flag: &str| {
        args.windows(2)
            .find(|a| a[0] == flag)
            .map(|a| a[1].to_string_lossy().to_string())
    };
    let startup_target = value_after("--launch-build")
        .or_else(|| value_after("--build").map(|v| format!("build:{v}")))
        .or_else(|| value_after("--pack").map(|v| format!("pack:{v}")));
    tauri::Builder::default()
        .setup(|app| {
            if let Ok(root) = data_root() {
                app.state::<State>().discord.start(root);
            }
            Ok(())
        })
        .manage(State {
            startup_target: Mutex::new(startup_target),
            ..Default::default()
        })
        .invoke_handler(tauri::generate_handler![
            request,
            cancel,
            save_setting,
            local_data,
            image_data,
            search,
            translate_text,
            open_url,
            running_games
        ])
        .on_window_event(|window, event| {
            if matches!(event, tauri::WindowEvent::Destroyed) {
                window.state::<State>().discord.stop();
                if let Ok(tasks) = window.state::<State>().tasks.lock() {
                    for cancelled in tasks.values() {
                        cancelled.store(true, std::sync::atomic::Ordering::Relaxed);
                    }
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("Run AWLauncher");
}
fn native_cli() {
    use std::sync::atomic::{AtomicBool, Ordering};
    let mut reader = BufReader::new(std::io::stdin());
    let mut line = String::new();
    if reader.read_line(&mut line).is_err() || line.len() > 65536 {
        return;
    }
    let cancelled = Arc::new(AtomicBool::new(false));
    let signal = cancelled.clone();
    std::thread::spawn(move || {
        let mut command = String::new();
        while reader.read_line(&mut command).unwrap_or(0) > 0 {
            if command.trim() == "cancel" {
                signal.store(true, Ordering::Relaxed);
                break;
            }
            command.clear();
        }
    });
    let event = |value: Value| {
        let _ = writeln!(std::io::stdout().lock(), "AW_RPC {value}");
    };
    let result = (|| {
        let request: Value = serde_json::from_str(&line).map_err(|_| "Некорректный запрос")?;
        let root = std::env::var_os("AW_NATIVE_DATA_HOME")
            .map(PathBuf::from)
            .or_else(home_dir)
            .ok_or("Нет домашней папки")?
            .join(".aw");
        let _locks = if aw_engine::ops::read_only(request["op"].as_str().unwrap_or("")) {
            Vec::new()
        } else {
            aw_engine::operation_locks(&root)?
        };
        let context = aw_engine::Context {
            root,
            cancelled: cancelled.clone(),
            event: &event,
        };
        aw_engine::ops::execute(&context, &request)
    })();
    event(match result {
        Ok(data) => json!({"type":"done","data":data}),
        Err(_) if cancelled.load(Ordering::Relaxed) => json!({"type":"cancelled"}),
        Err(error) => json!({"type":"failed","data":error}),
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn image_paths_stay_in_selected_profile() {
        let root = std::env::temp_dir().join(format!("aw-tauri-path-{}", std::process::id()));
        fs::create_dir_all(root.join("screenshots")).unwrap();
        fs::write(root.join("screenshots/test.png"), b"image").unwrap();
        assert!(safe_path(&root, "screenshots/test.png").is_ok());
        for path in [
            "../outside.png",
            "screenshots/../../outside.png",
            "/outside.png",
        ] {
            assert!(safe_path(&root, path).is_err());
        }
        fs::remove_dir_all(root).unwrap();
    }
}
