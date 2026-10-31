#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod activity;
mod activity_visual;
mod catalog;
mod catalog_ui;
mod downloads_ui;
mod files;
mod history_ui;
mod instance_content_ui;
mod instance_dialogs;
mod instance_files_ui;
mod library;
mod library_ui;
mod logs;
mod project_markup;
mod project_ui;
mod remote_images;
mod screenshots;
mod settings;
mod settings_assets;
mod settings_ui;
mod tasks;
mod translations;
mod visual;

use eframe::egui::{self, Color32, FontData, FontDefinitions, FontFamily, RichText, Vec2};
use serde::Deserialize;
use std::{
    collections::{HashMap, HashSet},
    env, fs,
    path::{Path, PathBuf},
    process::Command,
};

const BG: Color32 = Color32::from_rgb(17, 23, 19);
const SIDEBAR: Color32 = Color32::from_rgb(21, 27, 23);
const PANEL: Color32 = Color32::from_rgb(26, 33, 27);
const ACCENT: Color32 = Color32::from_rgb(196, 231, 169);
const TEXT: Color32 = Color32::from_rgb(239, 241, 233);
const MUTED: Color32 = Color32::from_rgb(162, 176, 161);

#[derive(Clone, Copy, Debug, PartialEq)]
enum ThemeMode {
    System,
    Dark,
    Light,
    Oled,
}

#[derive(Clone, Copy, PartialEq)]
struct Palette {
    bg: Color32,
    sidebar: Color32,
    panel: Color32,
    high: Color32,
    outline: Color32,
    accent: Color32,
    on_accent: Color32,
    text: Color32,
    muted: Color32,
    selected: Color32,
    dark: bool,
}

impl Palette {
    fn for_theme(mode: ThemeMode, system_light: bool) -> Self {
        let light = mode == ThemeMode::Light || (mode == ThemeMode::System && system_light);
        if light {
            Self {
                bg: Color32::from_rgb(244, 245, 246),
                sidebar: Color32::from_rgb(235, 237, 239),
                panel: Color32::WHITE,
                high: Color32::from_rgb(232, 236, 238),
                outline: Color32::from_rgb(208, 215, 218),
                accent: Color32::from_rgb(21, 154, 91),
                on_accent: Color32::WHITE,
                text: Color32::from_rgb(23, 32, 36),
                muted: Color32::from_rgb(104, 121, 128),
                selected: Color32::from_rgb(211, 234, 220),
                dark: false,
            }
        } else if mode == ThemeMode::Oled {
            Self {
                bg: Color32::BLACK,
                sidebar: Color32::from_rgb(11, 14, 15),
                panel: Color32::from_rgb(17, 22, 24),
                high: Color32::from_rgb(28, 35, 38),
                outline: Color32::from_rgb(43, 52, 56),
                accent: ACCENT,
                on_accent: Color32::from_rgb(30, 48, 32),
                text: TEXT,
                muted: MUTED,
                selected: Color32::from_rgb(35, 46, 37),
                dark: true,
            }
        } else {
            Self {
                bg: BG,
                sidebar: SIDEBAR,
                panel: PANEL,
                high: Color32::from_rgb(38, 49, 38),
                outline: Color32::from_rgb(53, 67, 54),
                accent: ACCENT,
                on_accent: Color32::from_rgb(30, 48, 32),
                text: TEXT,
                muted: MUTED,
                selected: Color32::from_rgb(42, 57, 41),
                dark: true,
            }
        }
    }
}

#[derive(Clone, Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
struct Build {
    id: String,
    name: String,
    version_id: String,
    loader: String,
    #[serde(default)]
    directory_name: Option<String>,
    #[serde(default)]
    last_played: Option<i64>,
    #[serde(default)]
    imported_play_time_millis: i64,
    #[serde(default)]
    imported_at: Option<i64>,
    #[serde(skip)]
    pack: bool,
    #[serde(skip)]
    project_id: Option<String>,
    #[serde(skip)]
    release_id: Option<String>,
    #[serde(skip)]
    icon_preset: Option<String>,
    #[serde(skip)]
    icon_background: Option<String>,
    #[serde(skip)]
    group_id: Option<String>,
    #[serde(skip)]
    favorite: bool,
    #[serde(skip)]
    play_time_millis: i64,
}

impl Build {
    fn key(&self) -> String {
        format!("{}:{}", if self.pack { "pack" } else { "build" }, self.id)
    }
    fn play_arguments(&self) -> Vec<String> {
        vec![
            "--play".into(),
            self.version_id.clone(),
            "--loader".into(),
            self.loader.to_lowercase(),
            if self.pack {
                "--pack".into()
            } else {
                "--build".into()
            },
            self.id.clone(),
        ]
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pack {
    #[serde(default)]
    project_id: Option<String>,
    #[serde(default)]
    version_id: Option<String>,
    title: String,
    game_version: String,
    loader: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SessionStamp {
    instance: String,
    start: i64,
    #[serde(default)]
    end: i64,
    #[serde(default)]
    build_id: Option<String>,
}

fn update_last_played(builds: &mut [Build], history: &str) {
    let Ok(sessions) = serde_json::from_str::<Vec<SessionStamp>>(history) else {
        return;
    };
    let mut local = HashMap::<String, i64>::new();
    let mut packs = HashMap::<String, i64>::new();
    let mut unique = HashMap::<(String, i64), SessionStamp>::new();
    for session in sessions {
        let key = (session.instance.clone(), session.start);
        if let Some(previous) = unique.get_mut(&key) {
            previous.end = previous.end.max(session.end);
            if previous.build_id.is_none() {
                previous.build_id = session.build_id;
            }
        } else {
            unique.insert(key, session);
        }
    }
    let mut durations = HashMap::<String, (i64, i64)>::new();
    for session in unique.into_values() {
        let key = session
            .build_id
            .as_ref()
            .map(|id| format!("build:{id}"))
            .unwrap_or_else(|| format!("pack:{}", session.instance));
        if let Some(build) = builds.iter().find(|build| build.key() == key) {
            let totals = durations.entry(key).or_default();
            totals.0 = totals
                .0
                .saturating_add(session.end.saturating_sub(session.start).max(0));
            totals.1 = totals.1.saturating_add(
                session
                    .end
                    .min(build.imported_at.unwrap_or(0))
                    .saturating_sub(session.start)
                    .max(0),
            );
        }
        packs
            .entry(session.instance)
            .and_modify(|time| *time = (*time).max(session.start))
            .or_insert(session.start);
        if let Some(id) = session.build_id {
            local
                .entry(id)
                .and_modify(|time| *time = (*time).max(session.start))
                .or_insert(session.start);
        }
    }
    for build in builds {
        let (duration, historical) = durations.get(&build.key()).copied().unwrap_or_default();
        build.play_time_millis =
            duration.saturating_add((build.imported_play_time_millis - historical).max(0));
        if let Some(time) = if build.pack {
            packs.get(&build.id)
        } else {
            local.get(&build.id)
        } {
            build.last_played = Some(build.last_played.unwrap_or(i64::MIN).max(*time));
        }
    }
}

fn read_packs(root: &Path) -> Vec<Build> {
    let Ok(entries) = fs::read_dir(root) else {
        return Vec::new();
    };
    let mut packs: Vec<Build> = entries
        .flatten()
        .filter_map(|entry| {
            if !entry.path().is_dir() {
                return None;
            }
            let id = entry.file_name().into_string().ok()?;
            let text = fs::read_to_string(entry.path().join("aw-pack.json")).ok()?;
            let pack: Pack = serde_json::from_str(&text).ok()?;
            Some(Build {
                id,
                name: pack.title,
                version_id: pack.game_version,
                loader: pack.loader,
                directory_name: None,
                last_played: None,
                imported_play_time_millis: 0,
                imported_at: None,
                pack: true,
                project_id: pack.project_id,
                release_id: pack.version_id,
                icon_preset: None,
                icon_background: None,
                group_id: None,
                favorite: false,
                play_time_millis: 0,
            })
        })
        .collect();
    packs.sort_by_key(|pack| pack.name.to_lowercase());
    packs
}

fn read_builds(path: &Path) -> Result<Vec<Build>, String> {
    let text = match fs::read_to_string(path) {
        Ok(text) => text,
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(err) => return Err(format!("Не удалось открыть список сборок: {err}")),
    };
    let builds: Vec<Build> = serde_json::from_str(&text)
        .map_err(|err| format!("Не удалось прочитать список сборок: {err}"))?;
    Ok(builds
        .into_iter()
        .filter(|b| !b.id.is_empty() && !b.version_id.is_empty())
        .collect())
}

fn home_dir() -> Option<PathBuf> {
    env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).map(PathBuf::from)
}

fn backend_path() -> Option<PathBuf> {
    let args: Vec<_> = env::args_os().collect();
    args.windows(2)
        .find(|v| v[0] == "--backend")
        .map(|v| PathBuf::from(&v[1]))
        .or_else(|| env::var_os("AW_LAUNCHER_BACKEND").map(PathBuf::from))
        .or_else(|| {
            env::current_exe().ok()?.parent().map(|p| {
                p.join(if cfg!(windows) {
                    "AWLauncher.exe"
                } else {
                    "AWLauncher"
                })
            })
        })
        .filter(|path| path.is_file())
        .or_else(|| {
            env::var_os("LOCALAPPDATA")
                .map(PathBuf::from)
                .map(|root| root.join("Programs/AWLauncher/AWLauncher.exe"))
                .filter(|path| path.is_file())
        })
}

#[derive(Clone, Copy, PartialEq)]
enum Screen {
    Home,
    Library,
    Instance,
    Screenshots,
    Activity,
    Notices,
    Catalog,
    Accounts,
    Downloads,
    Project,
}

#[derive(Clone, Deserialize)]
struct Notice {
    id: i64,
    level: String,
    text: String,
    title: Option<String>,
    #[serde(default)]
    at: i64,
    #[serde(default)]
    count: u32,
}
#[derive(Deserialize, Default)]
struct Notices {
    #[serde(default)]
    seen: i64,
    #[serde(default)]
    items: Vec<Notice>,
}

#[derive(Clone, Copy, PartialEq)]
enum InstanceTab {
    Content,
    Files,
    Worlds,
    Journal,
    Statistics,
    History,
}

struct App {
    context: egui::Context,
    screen: Screen,
    previous_screens: Vec<Screen>,
    next_screens: Vec<Screen>,
    theme_mode: ThemeMode,
    palette: Palette,
    palette_applied: bool,
    game_root: PathBuf,
    query: String,
    content: Vec<library::ContentFile>,
    content_filter: &'static str,
    content_selected: HashSet<String>,
    content_descending: bool,
    content_scroll_reset: bool,
    content_metadata: HashMap<String, ContentMetadata>,
    content_engine: Option<tasks::Running>,
    content_delete: Option<(String, String, String, String)>,
    content_delete_many: bool,
    history_rows: Vec<serde_json::Value>,
    history_engine: Option<tasks::Running>,
    history_restore: Option<String>,
    instance_settings: Option<String>,
    instance_menu: Option<String>,
    instance_name: String,
    instance_memory: String,
    instance_java: String,
    instance_jvm: String,
    instance_width: String,
    instance_height: String,
    instance_fullscreen: Option<bool>,
    instance_favorite: bool,
    instance_world_history: bool,
    create_build_open: bool,
    create_name: String,
    create_version: String,
    create_loader: String,
    create_versions: Vec<serde_json::Value>,
    create_engine: Option<tasks::Running>,
    create_search: String,
    import_world_target: Option<String>,
    import_type_target: Option<String>,
    import_world: String,
    instance_tab: InstanceTab,
    file_relative: PathBuf,
    file_entries: Vec<files::Entry>,
    file_query: String,
    log_query: String,
    log_lines: Vec<String>,
    log_scan: Option<std::sync::mpsc::Receiver<Result<Vec<String>, String>>>,
    log_last_scan: std::time::Instant,
    favorites_only: bool,
    recent_first: bool,
    library_filters_open: bool,
    library_recent_open: bool,
    library_installed_only: bool,
    library_groups_open: bool,
    library_group_filter: Option<String>,
    library_group_name: String,
    loader_filter: Option<String>,
    builds_path: PathBuf,
    packs_path: PathBuf,
    backend: Option<PathBuf>,
    builds: Vec<Build>,
    selected: Option<String>,
    message: Option<String>,
    logo: Option<egui::TextureHandle>,
    icons: HashMap<String, egui::TextureHandle>,
    nav_icons: HashMap<String, egui::TextureHandle>,
    shots: Vec<screenshots::Shot>,
    shot_query: String,
    shot_group: Option<String>,
    shot_selected: Option<PathBuf>,
    shot_loader: screenshots::Loader,
    shot_images: HashMap<(PathBuf, u32), egui::TextureHandle>,
    shot_requested: HashSet<(PathBuf, u32)>,
    shot_errors: HashSet<(PathBuf, u32)>,
    shot_scan: Option<std::sync::mpsc::Receiver<Vec<screenshots::Shot>>>,
    shot_last_scan: std::time::Instant,
    activity: Option<activity::Stats>,
    activity_scan: Option<std::sync::mpsc::Receiver<Result<activity::Stats, String>>>,
    activity_week_offset: i64,
    activity_target: Option<String>,
    activity_last_scan: std::time::Instant,
    notices: Notices,
    account_label: Option<(String, String)>,
    account_previews: Vec<(String, String, bool)>,
    account_ids: Vec<String>,
    account_remove: Option<String>,
    account_offline_tab: bool,
    offline_name: String,
    settings_open: bool,
    settings_section: usize,
    settings_search: String,
    preferences: settings::Store,
    memory_edit: String,
    jvm_edit: String,
    language_search: String,
    settings_notice: Option<String>,
    catalog_query: String,
    catalog_kind: &'static str,
    catalog_curseforge: bool,
    catalog_target: Option<String>,
    catalog_world: String,
    downloads: tasks::Queue,
    downloads_revision: u64,
    running_games: Vec<u32>,
    handled_games: HashSet<u32>,
    project_hit: Option<catalog::Hit>,
    project_data: Option<serde_json::Value>,
    project_engine: Option<tasks::Running>,
    project_tab: usize,
    project_markup: project_markup::Documents,
    project_game_filter: String,
    project_loader_filter: String,
    project_image_index: Option<usize>,
    project_release: Option<serde_json::Value>,
    catalog_datapack_request: Option<serde_json::Value>,
    remote_images: remote_images::Images,
    catalog_offset: u32,
    catalog_sort: &'static str,
    catalog_installed: bool,
    catalog_datapack_dialog: bool,
    catalog_due: Option<std::time::Instant>,
    catalog_scroll_reset: bool,
    catalog_cache: HashMap<String, (std::time::Instant, catalog::Page)>,
    catalog_request_key: String,
    content_target: Option<String>,
    catalog_page: Option<catalog::Page>,
    catalog_request: Option<std::sync::mpsc::Receiver<Result<catalog::Page, String>>>,
}

impl App {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let mut fonts = FontDefinitions::default();
        for (name, bytes) in [
            (
                "aw-semibold",
                include_bytes!("../../../src/main/resources/fonts/Onest-SemiBold.ttf").as_slice(),
            ),
            (
                "aw-bold",
                include_bytes!("../../../src/main/resources/fonts/Onest-Bold.ttf").as_slice(),
            ),
        ] {
            fonts
                .font_data
                .insert(name.into(), FontData::from_static(bytes).into());
            fonts
                .families
                .insert(FontFamily::Name(name.into()), vec![name.into()]);
        }
        fonts.font_data.insert(
            "onest".into(),
            FontData::from_static(include_bytes!(
                "../../../src/main/resources/fonts/Onest-Regular.ttf"
            ))
            .into(),
        );
        fonts.font_data.insert(
            "noto".into(),
            FontData::from_static(include_bytes!(
                "../../../src/main/resources/fonts/NotoSans-Regular.ttf"
            ))
            .into(),
        );
        fonts
            .families
            .get_mut(&FontFamily::Proportional)
            .unwrap()
            .insert(0, "noto".into());
        fonts
            .families
            .get_mut(&FontFamily::Proportional)
            .unwrap()
            .insert(0, "onest".into());
        settings_assets::fallback_fonts(&mut fonts);
        settings_assets::system_fallback_fonts(&mut fonts);
        let fallbacks = fonts.families[&FontFamily::Proportional].clone();
        for (family, chain) in &mut fonts.families {
            if matches!(family, FontFamily::Name(_)) {
                for fallback in &fallbacks {
                    if !chain.contains(fallback) {
                        chain.push(fallback.clone());
                    }
                }
            }
        }
        cc.egui_ctx.set_fonts(fonts);
        let logo = image::load_from_memory(include_bytes!("../../../branding/AWLauncher-mark.png"))
            .ok()
            .map(|image| {
                let image = image.to_rgba8();
                let size = [image.width() as usize, image.height() as usize];
                cc.egui_ctx.load_texture(
                    "aw-logo",
                    egui::ColorImage::from_rgba_unmultiplied(size, image.as_raw()),
                    egui::TextureOptions::LINEAR,
                )
            });
        let images: [(&str, &[u8]); 9] = [
            (
                "cube",
                include_bytes!("../../../src/main/resources/instance-icons/cube.png"),
            ),
            (
                "grass",
                include_bytes!("../../../src/main/resources/instance-icons/grass.png"),
            ),
            (
                "crystal",
                include_bytes!("../../../src/main/resources/instance-icons/crystal.png"),
            ),
            (
                "anvil",
                include_bytes!("../../../src/main/resources/instance-icons/anvil.png"),
            ),
            (
                "craft",
                include_bytes!("../../../src/main/resources/instance-icons/craft.png"),
            ),
            (
                "compass",
                include_bytes!("../../../src/main/resources/instance-icons/compass.png"),
            ),
            (
                "flame",
                include_bytes!("../../../src/main/resources/instance-icons/flame.png"),
            ),
            (
                "fox",
                include_bytes!("../../../src/main/resources/instance-icons/fox.png"),
            ),
            (
                "planet",
                include_bytes!("../../../src/main/resources/instance-icons/planet.png"),
            ),
        ];
        let icons = images
            .into_iter()
            .filter_map(|(name, bytes)| {
                let image = image::load_from_memory(bytes).ok()?.to_rgba8();
                let size = [image.width() as usize, image.height() as usize];
                Some((
                    name.to_owned(),
                    cc.egui_ctx.load_texture(
                        name,
                        egui::ColorImage::from_rgba_unmultiplied(size, image.as_raw()),
                        egui::TextureOptions::LINEAR,
                    ),
                ))
            })
            .collect();
        let nav_files: [(&str, &[u8]); 26] = [
            ("play", include_bytes!("../assets/nav/play.png")),
            ("layers", include_bytes!("../assets/nav/layers.png")),
            ("search", include_bytes!("../assets/nav/search.png")),
            ("download", include_bytes!("../assets/nav/download.png")),
            ("friends", include_bytes!("../assets/nav/friends.png")),
            ("image", include_bytes!("../assets/nav/image.png")),
            ("activity", include_bytes!("../assets/nav/activity.png")),
            ("notices", include_bytes!("../assets/nav/notices.png")),
            ("settings", include_bytes!("../assets/nav/settings.png")),
            ("account", include_bytes!("../assets/nav/account.png")),
            ("clock", include_bytes!("../assets/nav/clock.png")),
            ("calendar", include_bytes!("../assets/nav/calendar.png")),
            ("flame", include_bytes!("../assets/nav/flame.png")),
            ("trophy", include_bytes!("../assets/nav/trophy.png")),
            ("server", include_bytes!("../assets/nav/server.png")),
            ("history", include_bytes!("../assets/nav/history.png")),
            ("folder", include_bytes!("../assets/nav/folder.png")),
            ("file", include_bytes!("../assets/nav/file.png")),
            ("extension", include_bytes!("../assets/nav/extension.png")),
            ("delete", include_bytes!("../assets/nav/delete.png")),
            ("refresh", include_bytes!("../assets/nav/refresh.png")),
            ("back", include_bytes!("../assets/nav/back.png")),
            ("forward", include_bytes!("../assets/nav/forward.png")),
            ("add", include_bytes!("../assets/nav/add.png")),
            ("star", include_bytes!("../assets/nav/star.png")),
            (
                "chevron-down",
                include_bytes!("../assets/nav/chevron-down.png"),
            ),
        ];
        let mut nav_icons: HashMap<String, egui::TextureHandle> = nav_files
            .into_iter()
            .filter_map(|(name, bytes)| {
                let image = image::load_from_memory(bytes).ok()?.to_rgba8();
                let size = [image.width() as usize, image.height() as usize];
                Some((
                    name.to_owned(),
                    cc.egui_ctx.load_texture(
                        format!("nav-{name}"),
                        egui::ColorImage::from_rgba_unmultiplied(size, image.as_raw()),
                        egui::TextureOptions::LINEAR,
                    ),
                ))
            })
            .collect();
        for (id, bytes) in settings_assets::flags() {
            if let Ok(image) = image::load_from_memory(bytes) {
                let image = image.to_rgba8();
                let size = [image.width() as usize, image.height() as usize];
                nav_icons.insert(
                    format!("flag:{id}"),
                    cc.egui_ctx.load_texture(
                        format!("flag:{id}"),
                        egui::ColorImage::from_rgba_unmultiplied(size, image.as_raw()),
                        egui::TextureOptions::LINEAR,
                    ),
                );
            }
        }
        let mut style = (*cc.egui_ctx.style()).clone();
        style.visuals = egui::Visuals::dark();
        style.visuals.override_text_color = Some(TEXT);
        style.visuals.widgets.inactive.bg_fill = PANEL;
        style.visuals.widgets.hovered.bg_fill = Color32::from_rgb(53, 67, 54);
        style.visuals.selection.bg_fill = ACCENT;
        style.spacing.item_spacing = Vec2::new(10.0, 10.0);
        style.text_styles.insert(
            egui::TextStyle::Body,
            egui::FontId::new(14.0, FontFamily::Proportional),
        );
        style.text_styles.insert(
            egui::TextStyle::Button,
            egui::FontId::new(14.0, FontFamily::Proportional),
        );
        style.text_styles.insert(
            egui::TextStyle::Small,
            egui::FontId::new(12.0, FontFamily::Proportional),
        );
        cc.egui_ctx.set_style(style);
        let builds_path = home_dir()
            .unwrap_or_default()
            .join(".aw")
            .join("local-builds.json");
        let aw = home_dir().unwrap_or_default().join(".aw");
        let account_label = fs::read(aw.join("accounts.json"))
            .ok()
            .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
            .and_then(|store| {
                let accounts = store.get("accounts")?.as_array()?;
                let selected = store.get("selectedUuid").and_then(|value| value.as_str());
                let account = accounts
                    .iter()
                    .find(|account| account.get("uuid").and_then(|v| v.as_str()) == selected)
                    .or_else(|| accounts.first())?;
                Some((
                    account.get("name")?.as_str()?.to_owned(),
                    if account.get("type").and_then(|v| v.as_str()) == Some("OFFLINE") {
                        "Офлайн".into()
                    } else {
                        "Microsoft".into()
                    },
                ))
            });
        let account_previews = fs::read(aw.join("accounts.json"))
            .ok()
            .and_then(|bytes| serde_json::from_slice::<serde_json::Value>(&bytes).ok())
            .map(|store| {
                store
                    .get("accounts")
                    .and_then(|a| a.as_array())
                    .into_iter()
                    .flatten()
                    .filter_map(|a| {
                        Some((
                            a.get("name")?.as_str()?.to_owned(),
                            a.get("type")?.as_str()?.to_owned(),
                            a.get("uuid")?.as_str()
                                == store.get("selectedUuid").and_then(|v| v.as_str()),
                        ))
                    })
                    .collect()
            })
            .unwrap_or_default();
        let settings = fs::read_to_string(aw.join("launcher.json"))
            .ok()
            .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok());
        let theme_mode = match settings
            .as_ref()
            .and_then(|v| v.get("themeMode"))
            .and_then(|v| v.as_str())
        {
            Some("DARK") => ThemeMode::Dark,
            Some("LIGHT") => ThemeMode::Light,
            Some("OLED") => ThemeMode::Oled,
            _ => ThemeMode::System,
        };
        let initial_selected = settings.as_ref().and_then(|value| {
            value
                .get("lastBuildId")
                .and_then(|v| v.as_str())
                .map(|id| format!("build:{id}"))
                .or_else(|| {
                    value
                        .get("lastPack")
                        .and_then(|v| v.as_str())
                        .map(|id| format!("pack:{id}"))
                })
        });
        let custom_root = settings.as_ref().and_then(|v| {
            v.get("customGameDir")?
                .as_str()
                .filter(|s| !s.trim().is_empty())
                .map(PathBuf::from)
        });
        let game_root = custom_root.unwrap_or_else(|| aw.join("instances"));
        let packs_path = game_root.join("packs");
        let preferences = settings::Store::load(aw.join("launcher.json"));
        let memory_edit = preferences
            .number("memoryMb", settings::recommended(preferences.total_mb))
            .to_string();
        let jvm_edit = preferences.string("jvmArgs", settings::JVM_ARGS);
        let mut app = Self {
            context: cc.egui_ctx.clone(),
            screen: Screen::Home,
            previous_screens: Vec::new(),
            next_screens: Vec::new(),
            theme_mode,
            palette: Palette::for_theme(theme_mode, false),
            palette_applied: false,
            game_root,
            query: String::new(),
            content: Vec::new(),
            content_filter: "Всё",
            content_selected: HashSet::new(),
            content_descending: false,
            content_scroll_reset: false,
            content_metadata: HashMap::new(),
            content_engine: None,
            content_delete: None,
            content_delete_many: false,
            history_rows: Vec::new(),
            history_engine: None,
            history_restore: None,
            instance_settings: None,
            instance_menu: None,
            instance_name: String::new(),
            instance_memory: String::new(),
            instance_java: String::new(),
            instance_jvm: String::new(),
            instance_width: String::new(),
            instance_height: String::new(),
            instance_fullscreen: None,
            instance_favorite: false,
            instance_world_history: false,
            create_build_open: false,
            create_name: String::new(),
            create_version: String::new(),
            create_loader: "VANILLA".into(),
            create_versions: Vec::new(),
            create_engine: None,
            create_search: String::new(),
            import_world_target: None,
            import_type_target: None,
            import_world: String::new(),
            instance_tab: InstanceTab::Content,
            file_relative: PathBuf::new(),
            file_entries: Vec::new(),
            file_query: String::new(),
            log_query: String::new(),
            log_lines: Vec::new(),
            log_scan: None,
            log_last_scan: std::time::Instant::now(),
            favorites_only: false,
            recent_first: false,
            library_filters_open: false,
            library_recent_open: false,
            library_installed_only: false,
            library_groups_open: false,
            library_group_filter: None,
            library_group_name: String::new(),
            loader_filter: None,
            builds_path,
            packs_path,
            backend: backend_path(),
            builds: vec![],
            selected: initial_selected,
            message: None,
            logo,
            icons,
            nav_icons,
            shots: Vec::new(),
            shot_query: String::new(),
            shot_group: None,
            shot_selected: None,
            shot_loader: screenshots::Loader::new(cc.egui_ctx.clone()),
            shot_images: HashMap::new(),
            shot_requested: HashSet::new(),
            shot_errors: HashSet::new(),
            shot_scan: None,
            shot_last_scan: std::time::Instant::now(),
            activity: None,
            activity_scan: None,
            activity_week_offset: 0,
            activity_target: None,
            activity_last_scan: std::time::Instant::now(),
            notices: Notices::default(),
            account_label,
            account_previews,
            account_ids: Vec::new(),
            account_remove: None,
            account_offline_tab: false,
            offline_name: String::new(),
            settings_open: false,
            settings_section: 1,
            settings_search: String::new(),
            preferences,
            memory_edit,
            jvm_edit,
            language_search: String::new(),
            settings_notice: None,
            catalog_query: String::new(),
            catalog_kind: "modpack",
            catalog_curseforge: false,
            catalog_target: None,
            catalog_world: String::new(),
            downloads: tasks::Queue::default(),
            downloads_revision: 0,
            running_games: Vec::new(),
            handled_games: HashSet::new(),
            project_hit: None,
            project_data: None,
            project_engine: None,
            project_tab: 0,
            project_markup: project_markup::Documents::default(),
            project_game_filter: String::new(),
            project_loader_filter: String::new(),
            project_image_index: None,
            project_release: None,
            catalog_datapack_request: None,
            remote_images: remote_images::Images::new(cc.egui_ctx.clone(), backend_path()),
            catalog_offset: 0,
            catalog_sort: "downloads",
            catalog_installed: false,
            catalog_datapack_dialog: false,
            catalog_due: None,
            catalog_scroll_reset: true,
            catalog_cache: HashMap::new(),
            catalog_request_key: String::new(),
            content_target: None,
            catalog_page: None,
            catalog_request: None,
        };
        app.reload();
        app.reload_accounts();
        if let Some(backend) = &app.backend {
            app.content_engine = tasks::start(
                backend,
                &serde_json::json!({"op":"accountFace"}),
                cc.egui_ctx.clone(),
            )
            .ok();
        }
        let args: Vec<String> = env::args().collect();
        if args.iter().any(|a| a == "--play") {
            let value = |name: &str| {
                args.windows(2)
                    .find(|pair| pair[0] == name)
                    .map(|pair| pair[1].clone())
            };
            let key = value("--build")
                .map(|id| format!("build:{id}"))
                .or_else(|| value("--pack").map(|id| format!("pack:{id}")));
            let build = key
                .as_ref()
                .and_then(|key| app.builds.iter().find(|b| &b.key() == key))
                .or_else(|| {
                    let version = value("--play")?;
                    app.builds.iter().find(|b| b.version_id == version)
                })
                .cloned();
            if let Some(build) = build {
                app.selected = Some(build.key());
                app.open_backend(Some(&build));
            } else {
                app.message =
                    Some("Сборка из ярлыка больше не найдена. Выбери её в библиотеке".into());
            }
        }
        if let Some(home) = home_dir() {
            let dir = home.join(".aw/logs");
            let _ = std::fs::create_dir_all(&dir);
            if let Ok(mut log) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(dir.join("native-ui.log"))
            {
                use std::io::Write;
                let _ = writeln!(log, "startup: native launcher ready 1.0.50");
            }
        }
        app
    }

    fn navigate(&mut self, screen: Screen) {
        if self.screen == screen {
            return;
        }
        self.previous_screens.push(self.screen);
        if self.previous_screens.len() > 32 {
            self.previous_screens.remove(0);
        }
        self.next_screens.clear();
        self.screen = screen;
    }

    fn back(&mut self) {
        if let Some(screen) = self.previous_screens.pop() {
            self.next_screens.push(self.screen);
            self.screen = screen;
        }
    }

    fn forward(&mut self) {
        if let Some(screen) = self.next_screens.pop() {
            self.previous_screens.push(self.screen);
            if self.previous_screens.len() > 32 {
                self.previous_screens.remove(0);
            }
            self.screen = screen;
        }
    }

    fn reload(&mut self) {
        match read_builds(&self.builds_path) {
            Ok(mut builds) => {
                builds.extend(read_packs(&self.packs_path));
                if let Some(root) = self.builds_path.parent() {
                    let mut sessions = Vec::<serde_json::Value>::new();
                    for file in [
                        root.join("activity.json"),
                        root.join("cache/rust-ui-activity.json"),
                    ] {
                        if let Ok(history) = fs::read_to_string(file) {
                            if let Ok(items) =
                                serde_json::from_str::<Vec<serde_json::Value>>(&history)
                            {
                                sessions.extend(items);
                            }
                        }
                    }
                    update_last_played(
                        &mut builds,
                        &serde_json::to_string(&sessions).unwrap_or_default(),
                    );
                }
                for build in &mut builds {
                    let options = library::build_dir(
                        &self.game_root,
                        &build.id,
                        build.directory_name.as_deref(),
                        build.pack,
                    )
                    .and_then(|dir| fs::read_to_string(dir.join("aw-instance.json")).ok())
                    .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok());
                    build.icon_preset = options
                        .as_ref()
                        .and_then(|v| v.get("iconPreset")?.as_str().map(str::to_owned));
                    build.icon_background = options
                        .as_ref()
                        .and_then(|v| v.get("iconBackground")?.as_str().map(str::to_owned));
                    build.group_id = options
                        .as_ref()
                        .and_then(|v| v.get("groupId")?.as_str().map(str::to_owned));
                    build.favorite = options
                        .as_ref()
                        .and_then(|v| v.get("favorite")?.as_bool())
                        .unwrap_or(false);
                }
                if !builds
                    .iter()
                    .any(|b| Some(&b.key()) == self.selected.as_ref())
                {
                    self.selected = builds.first().map(Build::key);
                }
                self.builds = builds;
                self.message = None;
            }
            Err(error) => self.message = Some(error),
        }
    }
    fn reload_accounts(&mut self) {
        if let Some(root) = self.builds_path.parent() {
            if let Ok(bytes) = std::fs::read(root.join("accounts.json")) {
                if let Ok(store) = serde_json::from_slice::<serde_json::Value>(&bytes) {
                    let selected = store["selectedUuid"].as_str();
                    let mut ids = Vec::new();
                    let mut rows = Vec::new();
                    for account in store["accounts"].as_array().into_iter().flatten() {
                        if let (Some(id), Some(name), Some(kind)) = (
                            account["uuid"].as_str(),
                            account["name"].as_str(),
                            account["type"].as_str(),
                        ) {
                            ids.push(id.to_owned());
                            rows.push((name.to_owned(), kind.to_owned(), Some(id) == selected));
                        }
                    }
                    self.account_label = rows
                        .iter()
                        .find(|row| row.2)
                        .or_else(|| rows.first())
                        .map(|row| {
                            (
                                row.0.clone(),
                                if row.1 == "OFFLINE" {
                                    "Офлайн".into()
                                } else {
                                    "Microsoft".into()
                                },
                            )
                        });
                    self.account_previews = rows;
                    self.account_ids = ids;
                }
            }
        }
    }

    fn refresh_screenshots(&mut self, ctx: &egui::Context) {
        if self.shot_scan.is_some() {
            return;
        }
        self.shot_last_scan = std::time::Instant::now();
        let folders: Vec<_> = self
            .builds
            .iter()
            .filter_map(|build| {
                let dir = library::build_dir(
                    &self.game_root,
                    &build.id,
                    build.directory_name.as_deref(),
                    build.pack,
                )?;
                Some(screenshots::Folder {
                    key: build.key(),
                    title: build.name.clone(),
                    dir,
                })
            })
            .collect();
        let (tx, rx) = std::sync::mpsc::channel();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let _ = tx.send(screenshots::scan(&folders));
            ctx.request_repaint();
        });
        self.shot_scan = Some(rx);
    }

    fn refresh_activity(&mut self, ctx: &egui::Context) {
        if self.activity_scan.is_some() {
            return;
        }
        self.activity_last_scan = std::time::Instant::now();
        let profiles: Vec<_> = self
            .builds
            .iter()
            .filter_map(|build| {
                let dir = library::build_dir(
                    &self.game_root,
                    &build.id,
                    build.directory_name.as_deref(),
                    build.pack,
                )?;
                Some(activity::Profile {
                    id: build.id.clone(),
                    name: build.name.clone(),
                    version: build.version_id.clone(),
                    loader: build.loader.clone(),
                    dir,
                    pack: build.pack,
                    native: false,
                    imported_ms: build.imported_play_time_millis,
                    imported_at: build.imported_at,
                })
            })
            .collect();
        let history = self
            .builds_path
            .parent()
            .unwrap_or_else(|| Path::new("."))
            .join("activity.json");
        let aw_root = self
            .builds_path
            .parent()
            .unwrap_or_else(|| Path::new("."))
            .to_path_buf();
        let sidecar = aw_root.join("cache/rust-ui-activity.json");
        let activity_index = aw_root.join("cache/rust-ui-activity-index.json");
        let roots = vec![
            (aw_root.join("instances"), true),
            (self.game_root.clone(), false),
        ];
        let target = self.activity_target.clone();
        let (tx, rx) = std::sync::mpsc::channel();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let mut profiles = profiles;
            profiles.extend(activity::native_profiles(&roots, &profiles));
            let result = activity::collect(&history, &sidecar, &activity_index, &profiles)
                .map(|sessions| {
                    if let Some(key) = target {
                        let (kind, id) = key.split_once(':').unwrap_or(("", ""));
                        let selected: Vec<_> = sessions
                            .into_iter()
                            .filter(|s| {
                                if kind == "build" {
                                    s.build_id.as_deref() == Some(id)
                                } else {
                                    s.instance == id
                                }
                            })
                            .collect();
                        let imports: Vec<_> = profiles
                            .into_iter()
                            .filter(|p| p.id == id && p.pack == (kind == "pack"))
                            .collect();
                        activity::stats(&selected, &imports, chrono::Local::now().date_naive())
                    } else {
                        activity::stats(&sessions, &profiles, chrono::Local::now().date_naive())
                    }
                })
                .map_err(|error| error.to_string());
            let _ = tx.send(result);
            ctx.request_repaint();
        });
        self.activity_scan = Some(rx);
    }

    fn read_notices(&mut self) {
        let Some(root) = self.builds_path.parent() else {
            return;
        };
        match fs::read(root.join("notifications.json")) {
            Ok(bytes) => match serde_json::from_slice::<Notices>(&bytes) {
                Ok(mut notices) => {
                    notices
                        .items
                        .sort_by_key(|n| std::cmp::Reverse(if n.at == 0 { n.id } else { n.at }));
                    notices.items.truncate(60);
                    self.notices = notices;
                    self.message = None;
                }
                Err(error) => {
                    self.message = Some(format!("Не удалось прочитать уведомления: {error}"))
                }
            },
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                self.notices = Notices::default()
            }
            Err(error) => self.message = Some(format!("Не удалось прочитать уведомления: {error}")),
        }
    }

    fn notices_page(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.heading(
                RichText::new(translations::text("Уведомления"))
                    .size(28.0)
                    .family(FontFamily::Name("aw-semibold".into())),
            );
            if ui.button(translations::text("Обновить")).clicked() {
                self.read_notices();
            }
        });
        ui.add_space(20.0);
        if self.notices.items.is_empty() {
            ui.heading(translations::text("Пока тихо"));
            ui.label(
                RichText::new(translations::text(
                    "Если игра упадёт или мод не обновится, здесь будет понятно, что случилось.",
                ))
                .color(self.palette.muted),
            );
            return;
        }
        egui::ScrollArea::vertical().show(ui, |ui| {
            for notice in &self.notices.items {
                egui::Frame::NONE
                    .fill(self.palette.panel)
                    .corner_radius(8.0)
                    .inner_margin(18.0)
                    .show(ui, |ui| {
                        ui.set_min_width((ui.available_width() - 36.0).max(0.0));
                        let color = match notice.level.as_str() {
                            "ERROR" => Color32::from_rgb(241, 138, 138),
                            "WARNING" => Color32::from_rgb(238, 177, 109),
                            _ => self.palette.accent,
                        };
                        ui.horizontal(|ui| {
                            ui.label(RichText::new("●").color(color));
                            ui.label(
                                RichText::new(translations::text(
                                    notice.title.as_deref().unwrap_or(&notice.text),
                                ))
                                .family(FontFamily::Name("aw-semibold".into()))
                                .size(16.0),
                            );
                            if notice.id > self.notices.seen {
                                ui.label(RichText::new("●").color(self.palette.accent));
                            }
                        });
                        if notice.title.is_some() {
                            ui.label(translations::text(&notice.text));
                        }
                        if notice.count > 1 {
                            ui.label(
                                RichText::new(translations::text(format!(
                                    "Повторений: {}",
                                    notice.count
                                )))
                                .color(self.palette.muted),
                            );
                        }
                    });
            }
        });
    }

    fn activity_page(&mut self, ui: &mut egui::Ui) {
        if self.activity_last_scan.elapsed() >= std::time::Duration::from_secs(60) {
            self.refresh_activity(ui.ctx());
        }
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_secs(60));
        if let Some(Ok(result)) = self.activity_scan.as_ref().map(|rx| rx.try_recv()) {
            match result {
                Ok(stats) => {
                    self.activity = Some(stats);
                    self.message = None;
                }
                Err(error) => {
                    self.message = Some(format!("Не удалось загрузить активность: {error}"))
                }
            }
            self.activity_scan = None;
        }
        self.exact_activity(ui);
    }

    fn poll_screenshots(&mut self, ctx: &egui::Context) {
        if let Some(Ok(shots)) = self.shot_scan.as_ref().map(|rx| rx.try_recv()) {
            let old: HashMap<_, _> = self
                .shots
                .iter()
                .map(|s| (s.path.clone(), (s.modified, s.bytes)))
                .collect();
            let unchanged: HashSet<_> = shots
                .iter()
                .filter(|s| old.get(&s.path) == Some(&(s.modified, s.bytes)))
                .map(|s| s.path.clone())
                .collect();
            self.shot_images
                .retain(|(path, _), _| unchanged.contains(path));
            self.shot_requested
                .retain(|(path, _)| unchanged.contains(path));
            self.shot_errors
                .retain(|(path, _)| unchanged.contains(path));
            self.shots = shots;
            self.shot_scan = None;
        }
        while let Ok((path, side, result)) = self.shot_loader.rx.try_recv() {
            if let Ok(image) = result {
                if self.shot_images.len() >= 96 {
                    if let Some(old) = self.shot_images.keys().next().cloned() {
                        self.shot_images.remove(&old);
                        self.shot_requested.remove(&old);
                    }
                }
                let texture = ctx.load_texture(
                    format!("shot-{}-{side}", path.display()),
                    image,
                    egui::TextureOptions::LINEAR,
                );
                self.shot_images.insert((path, side), texture);
            } else {
                self.shot_errors.insert((path, side));
            }
        }
    }

    fn request_shot(&mut self, path: PathBuf, size: u32) {
        if self.shot_requested.insert((path.clone(), size)) {
            self.shot_loader.request(path, size);
        }
    }

    fn screenshots_page(&mut self, ui: &mut egui::Ui) {
        if self.shot_last_scan.elapsed() >= std::time::Duration::from_secs(5) {
            self.refresh_screenshots(ui.ctx());
        }
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_secs(5));
        ui.horizontal(|ui| {
            ui.heading(
                RichText::new(translations::text("Скриншоты"))
                    .size(28.0)
                    .family(FontFamily::Name("aw-semibold".into())),
            );
            if ui.button(translations::text("Обновить список")).clicked() {
                self.refresh_screenshots(ui.ctx());
            }
        });
        ui.add_space(10.0);
        if self.shot_scan.is_some() {
            ui.label(RichText::new(translations::text("Загружаю…")).color(self.palette.muted));
        }
        egui::ComboBox::from_id_salt("shot-group")
            .selected_text(
                self.shot_group
                    .as_ref()
                    .and_then(|key| self.builds.iter().find(|b| &b.key() == key))
                    .map(|b| b.name.clone())
                    .unwrap_or_else(|| translations::text("Все сборки")),
            )
            .show_ui(ui, |ui| {
                ui.selectable_value(&mut self.shot_group, None, translations::text("Все сборки"));
                for build in &self.builds {
                    ui.selectable_value(&mut self.shot_group, Some(build.key()), &build.name);
                }
            });
        ui.add(
            egui::TextEdit::singleline(&mut self.shot_query)
                .hint_text(translations::text("Поиск скриншотов…"))
                .desired_width(ui.available_width()),
        );
        let query = self.shot_query.to_lowercase();
        let visible: Vec<_> = self
            .shots
            .iter()
            .filter(|shot| {
                self.shot_group
                    .as_ref()
                    .map(|key| key == &shot.key)
                    .unwrap_or(true)
                    && (query.is_empty()
                        || shot.title.to_lowercase().contains(&query)
                        || shot
                            .path
                            .file_name()
                            .unwrap_or_default()
                            .to_string_lossy()
                            .to_lowercase()
                            .contains(&query))
            })
            .cloned()
            .collect();
        if let Some(path) = self.shot_selected.clone() {
            if ui.input(|i| i.key_pressed(egui::Key::Escape))
                || ui.button(translations::text("Все скриншоты")).clicked()
            {
                self.shot_selected = None;
                return;
            }
            let index = visible.iter().position(|shot| shot.path == path);
            if let Some(index) = index {
                let key = (path.clone(), 1280);
                if let Some(texture) = self.shot_images.get(&key) {
                    let avail = Vec2::new(
                        ui.available_width(),
                        (ui.available_height() - 90.0).max(100.0),
                    );
                    let size = texture.size_vec2();
                    let scale = (avail.x / size.x).min(avail.y / size.y).min(1.0);
                    ui.add(egui::Image::new(texture).fit_to_exact_size(size * scale));
                } else if self.shot_errors.contains(&key) {
                    ui.label(translations::text("Не удалось открыть снимок"));
                } else {
                    self.request_shot(path.clone(), 1280);
                    ui.label(translations::text("Загружаю снимок…"));
                }
                ui.label(path.file_name().unwrap_or_default().to_string_lossy());
                ui.label(
                    RichText::new(format!(
                        "{} · {} / {}",
                        visible[index].title,
                        index + 1,
                        visible.len()
                    ))
                    .color(self.palette.muted),
                );
                ui.horizontal(|ui| {
                    let prev = ui
                        .add_enabled(
                            index > 0,
                            egui::Button::new(translations::text("Предыдущее")),
                        )
                        .clicked()
                        || ui.input(|i| i.key_pressed(egui::Key::ArrowLeft));
                    let next = ui
                        .add_enabled(
                            index + 1 < visible.len(),
                            egui::Button::new(translations::text("Следующее")),
                        )
                        .clicked()
                        || ui.input(|i| i.key_pressed(egui::Key::ArrowRight));
                    if prev && index > 0 {
                        self.shot_selected = Some(visible[index - 1].path.clone());
                    }
                    if next && index + 1 < visible.len() {
                        self.shot_selected = Some(visible[index + 1].path.clone());
                    }
                    if ui.button(translations::text("Открыть сборку")).clicked() {
                        if let Some(build) = self
                            .builds
                            .iter()
                            .find(|b| b.key() == visible[index].key)
                            .cloned()
                        {
                            self.shot_selected = None;
                            self.open_instance(&build);
                        }
                    }
                });
            } else {
                self.shot_selected = None;
            }
            return;
        }
        let columns = ((ui.available_width() / 200.0) as usize).clamp(1, 5);
        let cell = ((ui.available_width() - (columns.saturating_sub(1) as f32 * 10.0))
            / columns as f32)
            .max(90.0);
        let mut requests = Vec::new();
        if visible.is_empty() && self.shot_scan.is_none() {
            ui.label(translations::text("Скриншотов пока нет"));
        }
        let rows = visible.len().div_ceil(columns);
        egui::ScrollArea::vertical().show_rows(ui, cell * 0.58 + 52.0, rows, |ui, range| {
            for index in range {
                let row = &visible[index * columns..((index + 1) * columns).min(visible.len())];
                ui.horizontal_top(|ui| {
                    for shot in row {
                        ui.vertical(|ui| {
                            let key = (shot.path.clone(), 384);
                            let response = if let Some(texture) = self.shot_images.get(&key) {
                                ui.add(egui::ImageButton::new(
                                    egui::Image::new(texture)
                                        .fit_to_exact_size(Vec2::new(cell, cell * 0.58)),
                                ))
                            } else if self.shot_errors.contains(&key) {
                                ui.add_sized(
                                    [cell, cell * 0.58],
                                    egui::Button::new(translations::text(
                                        "Не удалось открыть снимок",
                                    )),
                                )
                            } else {
                                let response = ui.add_sized(
                                    [cell, cell * 0.58],
                                    egui::Button::new(translations::text("Загружаю…")),
                                );
                                if ui.is_rect_visible(response.rect) {
                                    requests.push(shot.path.clone());
                                }
                                response
                            };
                            if response.clicked() {
                                self.shot_selected = Some(shot.path.clone());
                            }
                            ui.label(
                                RichText::new(
                                    shot.path.file_name().unwrap_or_default().to_string_lossy(),
                                )
                                .family(FontFamily::Name("aw-semibold".into()))
                                .size(12.0),
                            );
                            ui.label(
                                RichText::new(&shot.title)
                                    .color(self.palette.muted)
                                    .size(11.0),
                            );
                        });
                    }
                });
            }
        });
        for path in requests {
            self.request_shot(path, 384);
        }
    }

    fn open_instance(&mut self, build: &Build) {
        self.history_engine = None;
        self.history_rows.clear();
        self.history_restore = None;
        self.content_engine = None;
        self.content_metadata.clear();
        self.content_target = Some(build.key());
        self.content_selected.clear();
        self.content_scroll_reset = true;
        self.selected = Some(build.key());
        self.query.clear();
        self.instance_tab = InstanceTab::Content;
        self.file_relative.clear();
        self.file_query.clear();
        self.log_query.clear();
        self.log_lines.clear();
        self.log_scan = None;
        self.content_filter = "Всё";
        self.content.clear();
        self.navigate(Screen::Instance);
        if let Some(backend) = &self.backend {
            self.content_engine = tasks::start(
                backend,
                &serde_json::json!({"op":"content","target":build.key()}),
                self.context.clone(),
            )
            .ok();
        }
        match library::build_dir(
            &self.game_root,
            &build.id,
            build.directory_name.as_deref(),
            build.pack,
        ) {
            Some(path) => match library::scan_content(&path) {
                Ok(content) => {
                    self.content = content;
                    self.message = None;
                }
                Err(err) => self.message = Some(format!("Не удалось прочитать контент: {err}")),
            },
            None => self.message = Some("Некорректный путь сборки".into()),
        }
    }

    fn refresh_log(&mut self, build: &Build, ctx: &egui::Context) {
        if self.log_scan.is_some() {
            return;
        }
        self.log_last_scan = std::time::Instant::now();
        let Some(dir) = library::build_dir(
            &self.game_root,
            &build.id,
            build.directory_name.as_deref(),
            build.pack,
        ) else {
            self.message = Some("Некорректный путь сборки".into());
            return;
        };
        let home = home_dir()
            .map(|path| path.to_string_lossy().into_owned())
            .unwrap_or_default();
        let (tx, rx) = std::sync::mpsc::channel();
        let ctx = ctx.clone();
        std::thread::spawn(move || {
            let _ = tx.send(logs::read(&dir, &home).map_err(|error| error.to_string()));
            ctx.request_repaint();
        });
        self.log_scan = Some(rx);
    }

    fn instance_log(&mut self, ui: &mut egui::Ui, build: &Build) {
        if let Some(Ok(result)) = self.log_scan.as_ref().map(|rx| rx.try_recv()) {
            match result {
                Ok(lines) => {
                    self.log_lines = lines;
                    self.message = None;
                }
                Err(error) => self.message = Some(format!("Не удалось прочитать журнал: {error}")),
            }
            self.log_scan = None;
        }
        if self.log_last_scan.elapsed() >= std::time::Duration::from_secs(2) {
            self.refresh_log(build, ui.ctx());
        }
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_secs(2));
        ui.add(
            egui::TextEdit::singleline(&mut self.log_query)
                .hint_text(translations::text("Поиск в журнале…"))
                .desired_width(ui.available_width()),
        );
        let query = self.log_query.to_lowercase();
        let shown: Vec<_> = self
            .log_lines
            .iter()
            .filter(|line| query.is_empty() || line.to_lowercase().contains(&query))
            .collect();
        egui::ScrollArea::vertical()
            .stick_to_bottom(true)
            .show_rows(ui, 17.0, shown.len(), |ui, range| {
                for index in range {
                    ui.label(egui::RichText::new(shown[index]).monospace().size(12.0));
                }
            });
        if self.log_lines.is_empty() && self.log_scan.is_none() {
            ui.label(translations::text("Журнал пока пуст"));
        }
    }

    fn file_root(&self, build: &Build) -> Option<PathBuf> {
        let root = library::build_dir(
            &self.game_root,
            &build.id,
            build.directory_name.as_deref(),
            build.pack,
        )?;
        Some(if self.instance_tab == InstanceTab::Worlds {
            root.join("saves")
        } else {
            root
        })
    }

    fn refresh_files(&mut self, build: &Build) {
        let result = self
            .file_root(build)
            .ok_or_else(|| "Некорректный путь сборки".to_owned())
            .and_then(|root| {
                files::list(&root, &self.file_relative).map_err(|error| error.to_string())
            });
        match result {
            Ok(entries) => {
                self.file_entries = entries;
                self.message = None;
            }
            Err(error) => {
                self.file_entries.clear();
                self.message = Some(format!("Не удалось прочитать папку: {error}"));
            }
        }
    }

    fn artwork(&self, build: &Build) -> Option<&egui::TextureHandle> {
        self.icons
            .get(build.icon_preset.as_deref().unwrap_or("cube"))
            .or_else(|| self.icons.get("cube"))
    }

    fn nav_item(
        &self,
        ui: &mut egui::Ui,
        icon: &str,
        label: &str,
        selected: bool,
        enabled: bool,
    ) -> bool {
        let (rect, response) = ui.allocate_exact_size(
            Vec2::new(ui.available_width(), 44.0),
            if enabled {
                egui::Sense::click()
            } else {
                egui::Sense::hover()
            },
        );
        let fill = if selected {
            self.palette.selected
        } else if enabled && response.hovered() {
            self.palette.high
        } else {
            self.palette.sidebar
        };
        ui.painter().rect_filled(rect, 6.0, fill);
        if selected {
            ui.painter().rect_filled(
                egui::Rect::from_min_size(
                    egui::pos2(rect.left(), rect.center().y - 9.0),
                    Vec2::new(3.0, 18.0),
                ),
                2.0,
                self.palette.accent,
            );
        }
        let color = if selected {
            self.palette.accent
        } else if enabled {
            self.palette.text
        } else {
            self.palette.muted
        };
        if let Some(texture) = self.nav_icons.get(icon) {
            ui.painter().image(
                texture.id(),
                egui::Rect::from_center_size(
                    egui::pos2(rect.left() + 26.0, rect.center().y),
                    Vec2::splat(20.0),
                ),
                egui::Rect::from_min_max(egui::Pos2::ZERO, egui::pos2(1.0, 1.0)),
                color,
            );
        }
        ui.painter().text(
            egui::pos2(rect.left() + 48.0, rect.center().y),
            egui::Align2::LEFT_CENTER,
            self.tr(label),
            egui::FontId::new(15.0, FontFamily::Name("aw-semibold".into())),
            color,
        );
        enabled && response.clicked()
    }

    fn open_backend(&mut self, build: Option<&Build>) {
        if let Some(build) = build {
            self.downloads.enqueue(
                format!("Запуск {}", build.name),
                serde_json::json!({"op":"launch","target":build.key()}),
            );
            return;
        }
        let Some(path) = &self.backend else {
            self.message = Some("Не найден существующий AWLauncher. Укажите его через --backend или AW_LAUNCHER_BACKEND.".into());
            return;
        };
        let mut command = Command::new(path);
        if let Some(build) = build {
            command.args(build.play_arguments());
        }
        if let Err(err) = command.spawn() {
            self.message = Some(format!("Не удалось открыть лаунчер: {err}"));
        }
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        translations::set_language(&self.preferences.string("language", "EN"));
        ctx.data_mut(|data| {
            data.insert_temp(
                egui::Id::new("aw-language"),
                self.preferences.string("language", "EN"),
            )
        });
        self.running_games.retain(|pid| tasks::process_alive(*pid));
        let content_events: Vec<_> = self
            .content_engine
            .as_ref()
            .map(|engine| engine.rx.try_iter().collect())
            .unwrap_or_default();
        for event in content_events {
            match event {
                tasks::Event::Data(data) if data["type"] == "accountFace" => {
                    let face = &data["data"];
                    if let (Some(w), Some(h), Some(pixels)) = (
                        face["width"].as_u64(),
                        face["height"].as_u64(),
                        face["pixels"].as_array(),
                    ) {
                        if w > 0 && h > 0 && w <= 64 && h <= 64 && pixels.len() == (w * h) as usize
                        {
                            let rgba: Vec<_> = pixels
                                .iter()
                                .flat_map(|value| {
                                    let argb = value.as_i64().unwrap_or(0) as u32;
                                    [
                                        (argb >> 16) as u8,
                                        (argb >> 8) as u8,
                                        argb as u8,
                                        (argb >> 24) as u8,
                                    ]
                                })
                                .collect();
                            let texture = ctx.load_texture(
                                "selected-account-face",
                                egui::ColorImage::from_rgba_unmultiplied(
                                    [w as usize, h as usize],
                                    &rgba,
                                ),
                                egui::TextureOptions::NEAREST,
                            );
                            ctx.data_mut(|memory| {
                                memory.insert_temp(egui::Id::new("account-face"), texture)
                            });
                        }
                    }
                }
                tasks::Event::Data(data) if data["type"] == "done" => {
                    if let Some(items) = data["data"].as_array() {
                        self.content_metadata = items
                            .iter()
                            .filter_map(|item| {
                                serde_json::from_value::<ContentMetadata>(item.clone()).ok()
                            })
                            .map(|item| (item.file.trim_end_matches(".disabled").to_owned(), item))
                            .collect();
                    }
                }
                tasks::Event::Data(data) if data["type"] == "failed" => {
                    self.message = Some(
                        data["data"]
                            .as_str()
                            .unwrap_or("Не удалось прочитать сведения о проектах")
                            .into(),
                    );
                }
                tasks::Event::Exited(_) => self.content_engine = None,
                _ => {}
            }
        }
        self.downloads.tick(self.backend.as_ref(), ctx);
        if self.downloads.items.iter().any(|item| {
            item.status == tasks::Status::Done
                && item.request["op"] == "installUpdate"
                && item
                    .result
                    .as_ref()
                    .is_some_and(|result| result["installing"] == true)
        }) {
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
        let launched: Vec<u32> = self
            .downloads
            .items
            .iter()
            .filter_map(|item| {
                if item.status == tasks::Status::Done && item.request["op"] == "launch" {
                    item.result
                        .as_ref()
                        .and_then(|r| r["pid"].as_u64())
                        .and_then(|pid| u32::try_from(pid).ok())
                } else {
                    None
                }
            })
            .collect();
        for pid in launched {
            if self.handled_games.insert(pid) && tasks::process_alive(pid) {
                self.running_games.push(pid);
                if !self.preferences.boolean("keepLauncherOpen", false) {
                    ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
                    let context = ctx.clone();
                    std::thread::spawn(move || {
                        while tasks::process_alive(pid) {
                            std::thread::sleep(std::time::Duration::from_secs(2));
                        }
                        context.send_viewport_cmd(egui::ViewportCommand::Visible(true));
                        context.request_repaint();
                    });
                }
            }
        }
        if !self.running_games.is_empty() {
            ctx.request_repaint_after(std::time::Duration::from_secs(2));
        }
        if self.downloads_revision != self.downloads.revision {
            self.downloads_revision = self.downloads.revision;
            self.reload();
            self.reload_accounts();
            let refresh_target = if matches!(self.screen, Screen::Catalog | Screen::Project) {
                self.catalog_target.as_ref().or(self.selected.as_ref())
            } else {
                self.selected.as_ref()
            };
            if let Some(build) = refresh_target
                .and_then(|key| self.builds.iter().find(|build| &build.key() == key))
                .cloned()
            {
                self.refresh_content_details(&build);
                if self.instance_tab == InstanceTab::History {
                    self.refresh_history(&build);
                }
            }
        }
        let system_light = ctx.system_theme() == Some(egui::Theme::Light);
        let palette = Palette::for_theme(self.theme_mode, system_light);
        if !self.palette_applied || self.palette != palette {
            self.palette = palette;
            let mut style = (*ctx.style()).clone();
            style.visuals = if palette.dark {
                egui::Visuals::dark()
            } else {
                egui::Visuals::light()
            };
            style.visuals.override_text_color = Some(palette.text);
            style.visuals.widgets.inactive.bg_fill = palette.panel;
            style.visuals.widgets.hovered.bg_fill = palette.high;
            style.visuals.selection.bg_fill = palette.selected;
            style.visuals.hyperlink_color = palette.accent;
            style.spacing.interact_size.y = 44.0;
            style.spacing.button_padding = Vec2::new(18.0, 10.0);
            style.visuals.widgets.inactive.corner_radius = egui::CornerRadius::same(6);
            style.visuals.widgets.hovered.corner_radius = egui::CornerRadius::same(6);
            style.visuals.widgets.active.corner_radius = egui::CornerRadius::same(6);
            style.visuals.widgets.inactive.bg_stroke = egui::Stroke::NONE;
            style.visuals.widgets.inactive.weak_bg_fill = palette.high;
            style.visuals.widgets.hovered.weak_bg_fill = palette.outline;
            style.visuals.extreme_bg_color = palette.high;
            ctx.set_style(style);
            self.palette_applied = true;
        }
        self.poll_screenshots(ctx);
        egui::TopBottomPanel::top("title")
            .exact_height(58.0)
            .frame(egui::Frame::NONE.fill(self.palette.bg))
            .show(ctx, |ui| {
                self.exact_title(ui);
            });
        egui::SidePanel::left("navigation")
            .exact_width(214.0)
            .frame(
                egui::Frame::NONE
                    .fill(self.palette.sidebar)
                    .inner_margin(8.0),
            )
            .show(ctx, |ui| {
                ui.spacing_mut().item_spacing.y = 6.0;
                ui.add_space(4.0);
                if self.nav_item(ui, "play", "Играть", self.screen == Screen::Home, true) {
                    self.navigate(Screen::Home);
                }
                if self.nav_item(
                    ui,
                    "layers",
                    "Мои сборки",
                    matches!(self.screen, Screen::Library | Screen::Instance),
                    true,
                ) {
                    self.navigate(Screen::Library);
                }
                if self.nav_item(
                    ui,
                    "search",
                    "Каталог",
                    matches!(self.screen, Screen::Catalog | Screen::Project),
                    true,
                ) {
                    self.navigate(Screen::Catalog);
                    if self.catalog_page.is_none() {
                        self.start_catalog_search(ctx);
                    }
                }
                if self.nav_item(
                    ui,
                    "download",
                    "Загрузки",
                    self.screen == Screen::Downloads,
                    true,
                ) {
                    self.navigate(Screen::Downloads);
                }
                ui.add_space(10.0);
                ui.label(
                    RichText::new(translations::text(format!("    {}", self.tr("ДРУГОЕ"))))
                        .color(self.palette.muted)
                        .size(11.0),
                );
                ui.add_space(4.0);
                self.nav_item(ui, "friends", "Друзья", false, false);
                if self.nav_item(
                    ui,
                    "image",
                    "Скриншоты",
                    self.screen == Screen::Screenshots,
                    true,
                ) {
                    self.navigate(Screen::Screenshots);
                    self.shot_group = None;
                    self.shot_selected = None;
                    self.refresh_screenshots(ctx);
                }
                if self.nav_item(
                    ui,
                    "activity",
                    "Активность",
                    self.screen == Screen::Activity,
                    true,
                ) {
                    self.navigate(Screen::Activity);
                    self.activity_target = None;
                    self.activity = None;
                    self.activity_scan = None;
                    self.activity_week_offset = 0;
                    self.refresh_activity(ctx);
                }
                if self.nav_item(
                    ui,
                    "notices",
                    "Уведомления",
                    self.screen == Screen::Notices,
                    true,
                ) {
                    self.navigate(Screen::Notices);
                    self.read_notices();
                }
                ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
                    self.exact_footer(ui);
                });
            });
        egui::CentralPanel::default()
            .frame(egui::Frame::NONE.fill(self.palette.bg).inner_margin(
                if self.screen == Screen::Home {
                    0.0
                } else {
                    24.0
                },
            ))
            .show(ctx, |ui| {
                let selected = self
                    .selected
                    .as_ref()
                    .and_then(|id| self.builds.iter().find(|b| &b.key() == id))
                    .cloned();
                match self.screen {
                    Screen::Home => self.exact_home(ui),
                    Screen::Library => self.library_page(ui),
                    Screen::Screenshots => self.screenshots_page(ui),
                    Screen::Activity => self.activity_page(ui),
                    Screen::Notices => self.notices_page(ui),
                    Screen::Catalog => self.catalog_page(ui),
                    Screen::Accounts => self.exact_accounts(ui),
                    Screen::Downloads => self.downloads_page(ui),
                    Screen::Project => self.project_page(ui),
                    Screen::Instance => {
                        if let Some(build) = selected {
                            ui.spacing_mut().item_spacing.y = 12.0;
                            self.exact_instance_header(ui, &build);
                            self.instance_tabs(ui, &build);
                            ui.spacing_mut().item_spacing.y = 10.0;
                            if self.instance_tab == InstanceTab::History {
                                self.history_page(ui, &build);
                            } else if self.instance_tab == InstanceTab::Statistics {
                                self.activity_page(ui);
                            } else if self.instance_tab == InstanceTab::Journal {
                                self.instance_log(ui, &build);
                            } else if self.instance_tab != InstanceTab::Content {
                                self.instance_files(ui, &build);
                            } else {
                                self.instance_content_table(ui, &build);
                            }
                        } else {
                            ui.label(translations::text("Сборка не найдена"));
                        }
                    }
                }
                if let Some(message) = &self.message {
                    ui.colored_label(Color32::LIGHT_RED, translations::text(message));
                }
            });
        if self.settings_open {
            self.exact_settings(ctx);
        }
        self.instance_dialogs(ctx);
        self.library_groups_dialog(ctx);
        self.catalog_datapack_picker(ctx);
        self.project_dialogs(ctx);
        self.content_remove_many_dialog(ctx);
        if let Some(id) = self.account_remove.clone() {
            egui::Window::new(translations::text("Удалить аккаунт из лаунчера?")).anchor(egui::Align2::CENTER_CENTER,Vec2::ZERO).collapsible(false).resizable(false).show(ctx,|ui|{
            ui.label(translations::text("Игровые сборки и аккаунт Microsoft сохранятся. Для входа в лаунчер потребуется авторизация заново."));ui.horizontal(|ui|{if ui.button(translations::text("Отмена")).clicked(){self.account_remove=None;}if ui.button(translations::text("Удалить")).clicked(){self.downloads.enqueue("Удаление аккаунта".into(),serde_json::json!({"op":"accountRemove","file":id}));self.account_remove=None;}});
        });
        }
        if let Some((target, file, kind, title)) = self.content_delete.clone() {
            egui::Window::new(translations::text("Удалить проект?")).anchor(egui::Align2::CENTER_CENTER,Vec2::ZERO).collapsible(false).resizable(false).show(ctx,|ui|{
                ui.label(&title);ui.label(translations::text("Состояние сборки будет сохранено в истории перед удалением."));
                ui.horizontal(|ui|{if ui.button(translations::text("Отмена")).clicked(){self.content_delete=None;}if ui.button(translations::text("Удалить")).clicked(){self.downloads.enqueue(format!("Удаление {title}"),serde_json::json!({"op":"remove","target":target,"file":file,"kind":kind}));self.content_delete=None;}});
            });
        }
    }
}

fn main() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1280.0, 780.0])
            .with_min_inner_size([600.0, 420.0])
            .with_decorations(false),
        ..Default::default()
    };
    eframe::run_native(
        "AWLauncher",
        options,
        Box::new(|cc| Ok(Box::new(App::new(cc)))),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn system_and_explicit_themes_match_launcher_palette() {
        assert_eq!(Palette::for_theme(ThemeMode::Oled, true).bg, Color32::BLACK);
        assert_eq!(
            Palette::for_theme(ThemeMode::Light, false).bg,
            Color32::from_rgb(244, 245, 246)
        );
        assert!(!Palette::for_theme(ThemeMode::System, true).dark);
        assert!(Palette::for_theme(ThemeMode::System, false).dark);
    }
    #[test]
    fn notifications_match_existing_storage_format() {
        let data: Notices = serde_json::from_str(r#"{"seen":10,"items":[{"id":11,"level":"ERROR","text":"Ошибка","title":"Игра","at":12,"count":2,"actions":["LOGS"]}]}"#).unwrap();
        assert_eq!(data.seen, 10);
        assert_eq!(data.items[0].text, "Ошибка");
        assert_eq!(data.items[0].count, 2);
    }
    #[test]
    fn last_played_combines_profiles_and_saved_sessions() {
        let mut builds: Vec<Build> = serde_json::from_str(r#"[{"id":"build-1","name":"Local","versionId":"1.21","loader":"FABRIC","lastPlayed":10},{"id":"pack-1","name":"Pack","versionId":"1.20","loader":"FORGE"}]"#).unwrap();
        builds[1].pack = true;
        update_last_played(
            &mut builds,
            r#"[{"instance":"local","start":100,"buildId":"build-1"},{"instance":"pack-1","start":200}]"#,
        );
        assert_eq!(builds[0].last_played, Some(100));
        assert_eq!(builds[1].last_played, Some(200));
    }
    #[test]
    fn header_playtime_merges_history_and_deducts_imported_sessions() {
        let mut builds:Vec<Build>=serde_json::from_str(r#"[{"id":"one","name":"World","versionId":"1.21","loader":"FABRIC","importedPlayTimeMillis":1000,"importedAt":1000}]"#).unwrap();
        update_last_played(
            &mut builds,
            r#"[{"instance":"aw-build-one","buildId":"one","start":0,"end":600},{"instance":"aw-build-one","buildId":"one","start":0,"end":800},{"instance":"aw-build-one","buildId":"one","start":1000,"end":1400}]"#,
        );
        assert_eq!(builds[0].play_time_millis, 1400);
        assert_eq!(builds[0].last_played, Some(1000));
    }
    #[test]
    fn build_json_is_compatible_with_launcher() {
        let b: Build = serde_json::from_str(
            r#"{"id":"abc","name":"Тест","versionId":"1.21","loader":"FABRIC","extra":true}"#,
        )
        .unwrap();
        assert_eq!(
            (b.name.as_str(), b.version_id.as_str(), b.loader.as_str()),
            ("Тест", "1.21", "FABRIC")
        );
        assert_eq!(
            b.play_arguments(),
            ["--play", "1.21", "--loader", "fabric", "--build", "abc"]
        );
        let pack = Build { pack: true, ..b };
        assert_eq!(pack.play_arguments()[4], "--pack");
    }
    #[test]
    fn absent_build_file_is_empty() {
        assert!(
            read_builds(&std::env::temp_dir().join("awlauncher-ui-missing-file-827402.json"))
                .unwrap()
                .is_empty()
        );
    }
    #[test]
    fn pack_manifest_has_launcher_fields() {
        let p: Pack =
            serde_json::from_str(r#"{"title":"Сборка","gameVersion":"26.3","loader":"FABRIC"}"#)
                .unwrap();
        assert_eq!(
            (p.title.as_str(), p.game_version.as_str(), p.loader.as_str()),
            ("Сборка", "26.3", "FABRIC")
        );
    }
}

fn content_type(category: &str) -> &str {
    match category {
        "Моды" => "mod",
        "Шейдеры" => "shader",
        "Ресурспаки" => "resourcepack",
        "Датапаки" => "datapack",
        _ => "mod",
    }
}

#[derive(Clone, Default, Deserialize)]
struct ContentMetadata {
    file: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    version: String,
    #[serde(default)]
    project: String,
    #[serde(default)]
    icon: String,
    #[serde(default)]
    update: String,
}
