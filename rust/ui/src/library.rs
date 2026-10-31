use std::{
    fs, io,
    io::Write,
    path::{Component, Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ContentFile {
    pub name: String,
    pub category: &'static str,
    pub enabled: bool,
    pub world: Option<String>,
    pub relative: String,
}

pub fn safe_name(name: &str) -> bool {
    !name.trim().is_empty()
        && name.chars().count() <= 100
        && name != "."
        && name != ".."
        && !name.ends_with(['.', ' '])
        && !name.chars().any(|c| c < ' ' || "<>:\"/\\|?*".contains(c))
        && Path::new(name).components().count() == 1
        && matches!(
            Path::new(name).components().next(),
            Some(Component::Normal(_))
        )
        && !reserved(name)
}

fn reserved(name: &str) -> bool {
    let first = name
        .split('.')
        .next()
        .unwrap_or("")
        .trim_end()
        .to_ascii_uppercase();
    matches!(first.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ((first.starts_with("COM") || first.starts_with("LPT"))
            && matches!(
                &first[3..],
                "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" | "¹" | "²" | "³"
            ))
}

pub fn build_dir(root: &Path, id: &str, directory: Option<&str>, pack: bool) -> Option<PathBuf> {
    if !safe_name(id) {
        return None;
    }
    if pack {
        return Some(root.join("packs").join(id));
    }
    let root = root.join("builds");
    let legacy = root.join(format!("aw-build-{id}"));
    if legacy.symlink_metadata().is_ok() || directory.is_none() {
        return Some(legacy);
    }
    directory
        .filter(|name| safe_name(name))
        .map(|name| root.join(name))
}

pub fn scan_content(game_dir: &Path) -> io::Result<Vec<ContentFile>> {
    let mut found = Vec::new();
    let packs: Vec<String> = fs::read_to_string(game_dir.join("options.txt"))
        .ok()
        .and_then(|text| {
            text.lines()
                .find_map(|line| line.strip_prefix("resourcePacks:").map(str::to_owned))
        })
        .and_then(|line| serde_json::from_str::<Vec<String>>(&line).ok())
        .unwrap_or_default();
    let properties: std::collections::HashMap<String, String> =
        fs::read(game_dir.join("config/iris.properties"))
            .ok()
            .and_then(|bytes| javaproperties::parse(&bytes).ok())
            .unwrap_or_default()
            .into_iter()
            .collect();
    let shader = properties
        .get("shaderPack")
        .filter(|_| properties.get("enableShaders").map(String::as_str) != Some("false"));
    for (folder, category, extension) in [
        ("mods", "Моды", ".jar"),
        ("shaderpacks", "Шейдеры", ".zip"),
        ("resourcepacks", "Ресурспаки", ".zip"),
    ] {
        let entries = match fs::read_dir(game_dir.join(folder)) {
            Ok(entries) => entries,
            Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
            Err(error) => return Err(error),
        };
        for entry in entries {
            let entry = entry?;
            let file_type = entry.file_type()?;
            if file_type.is_symlink() || (!file_type.is_file() && !file_type.is_dir()) {
                continue;
            }
            let name = entry.file_name().to_string_lossy().into_owned();
            let lower = name.to_lowercase();
            let disabled = lower.ends_with(".disabled");
            let is_pack_dir = file_type.is_dir() && folder != "mods";
            let is_file = file_type.is_file()
                && (lower.ends_with(extension)
                    || (folder == "mods" && lower.ends_with(".jar.disabled")));
            if is_pack_dir || is_file {
                let enabled = match folder {
                    "mods" => !disabled,
                    "shaderpacks" => shader == Some(&name),
                    "resourcepacks" => packs.iter().any(|value| value == &format!("file/{name}")),
                    _ => false,
                };
                found.push(ContentFile {
                    relative: format!("{folder}/{name}"),
                    name,
                    category,
                    enabled,
                    world: None,
                });
            }
        }
    }
    scan_data_packs(game_dir, &mut found)?;
    found.sort_by_key(|item| item.name.to_lowercase());
    Ok(found)
}

fn scan_data_packs(game_dir: &Path, found: &mut Vec<ContentFile>) -> io::Result<()> {
    let saves = game_dir.join("saves");
    match fs::symlink_metadata(&saves) {
        Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => {}
        Ok(_) => return Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    }
    for world in fs::read_dir(saves)? {
        let world = world?;
        let kind = world.file_type()?;
        if !kind.is_dir() || kind.is_symlink() {
            continue;
        }
        let valid_level = fs::symlink_metadata(world.path().join("level.dat"))
            .map(|meta| meta.is_file() && !meta.file_type().is_symlink())
            .unwrap_or(false);
        if !valid_level {
            continue;
        }
        let world_name = world.file_name().to_string_lossy().into_owned();
        for (folder, active) in [("datapacks", true), ("aw-disabled-datapacks", false)] {
            let root = world.path().join(folder);
            if !fs::symlink_metadata(&root)
                .map(|m| m.is_dir() && !m.file_type().is_symlink())
                .unwrap_or(false)
            {
                continue;
            }
            for entry in fs::read_dir(root)? {
                let entry = entry?;
                let kind = entry.file_type()?;
                if kind.is_symlink() {
                    continue;
                }
                let name = entry.file_name().to_string_lossy().into_owned();
                let valid_file =
                    kind.is_file() && (name.ends_with(".zip") || name.ends_with(".zip.disabled"));
                let valid_dir = kind.is_dir()
                    && fs::symlink_metadata(entry.path().join("pack.mcmeta"))
                        .map(|meta| meta.is_file() && !meta.file_type().is_symlink())
                        .unwrap_or(false);
                if valid_file || valid_dir {
                    let enabled = active && !name.ends_with(".disabled");
                    found.push(ContentFile {
                        relative: format!("saves/{world_name}/{folder}/{name}"),
                        name,
                        category: "Датапаки",
                        enabled,
                        world: Some(world_name.clone()),
                    });
                }
            }
        }
    }
    Ok(())
}

static TEMP_ID: AtomicU64 = AtomicU64::new(0);

/// Update only the resourcePacks option, preserving all other Minecraft options.
#[cfg(test)]
pub fn set_resource_pack(game_dir: &Path, name: &str, enable: bool) -> io::Result<()> {
    if !safe_name(name) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Invalid pack name",
        ));
    }
    ensure_content_exists(game_dir, "resourcepacks", name)?;
    let file = game_dir.join("options.txt");
    if fs::symlink_metadata(&file)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Options file is a symlink",
        ));
    }
    let text = match fs::read_to_string(&file) {
        Ok(text) => text,
        Err(error) if error.kind() == io::ErrorKind::NotFound => String::new(),
        Err(error) => return Err(error),
    };
    let lines: Vec<&str> = text.lines().collect();
    let current: Vec<String> = lines
        .iter()
        .find_map(|line| line.strip_prefix("resourcePacks:"))
        .and_then(|value| serde_json::from_str(value).ok())
        .unwrap_or_else(|| vec!["vanilla".to_owned()]);
    let entry = format!("file/{name}");
    let mut next: Vec<String> = current
        .iter()
        .filter(|value| **value != entry)
        .cloned()
        .collect();
    if enable {
        next.push(entry);
    }
    if next == current {
        return Ok(());
    }
    let updated = format!(
        "resourcePacks:{}",
        serde_json::to_string(&next).map_err(io::Error::other)?
    );
    let mut output: Vec<String> = lines
        .iter()
        .map(|line| {
            if line.starts_with("resourcePacks:") {
                updated.clone()
            } else {
                (*line).to_owned()
            }
        })
        .collect();
    if !lines.iter().any(|line| line.starts_with("resourcePacks:")) {
        output.push(updated);
    }
    let bytes = format!("{}\n", output.join("\n"));
    atomic_write(&file, bytes.as_bytes())
}

pub(crate) fn atomic_write(file: &Path, bytes: &[u8]) -> io::Result<()> {
    if fs::symlink_metadata(file)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Target is a symlink",
        ));
    }
    let parent = file
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Missing parent"))?;
    fs::create_dir_all(parent)?;
    let tmp = parent.join(format!(
        ".aw-settings-{}-{}.tmp",
        std::process::id(),
        TEMP_ID.fetch_add(1, Ordering::Relaxed)
    ));
    let mut writer = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&tmp)?;
    let result = writer.write_all(bytes).and_then(|_| writer.sync_all());
    drop(writer);
    if let Err(error) = result {
        let _ = fs::remove_file(&tmp);
        return Err(error);
    }
    if let Err(error) = fs::rename(&tmp, file) {
        let _ = fs::remove_file(&tmp);
        return Err(error);
    }
    Ok(())
}

/// Keep all other Iris properties and mirror the existing launcher's shader selection.
#[cfg(test)]
pub fn set_shader(game_dir: &Path, name: &str, enable: bool) -> io::Result<()> {
    if !safe_name(name) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Invalid shader name",
        ));
    }
    ensure_content_exists(game_dir, "shaderpacks", name)?;
    let file = game_dir.join("config/iris.properties");
    if fs::symlink_metadata(&file)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Iris settings are a symlink",
        ));
    }
    let mut properties: std::collections::HashMap<String, String> = match fs::read(&file) {
        Ok(bytes) => javaproperties::parse(&bytes)
            .map_err(io::Error::other)?
            .into_iter()
            .collect(),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Default::default(),
        Err(error) => return Err(error),
    };
    if enable {
        properties.insert("shaderPack".to_owned(), name.to_owned());
    }
    properties.insert("enableShaders".to_owned(), enable.to_string());
    let mut output = String::new();
    let mut entries: Vec<_> = properties.iter().collect();
    entries.sort_by_key(|(key, _)| *key);
    for (key, value) in entries {
        output.push_str(&escape_property(key));
        output.push('=');
        output.push_str(&escape_property(value));
        output.push('\n');
    }
    atomic_write(&file, output.as_bytes())
}

#[cfg(test)]
fn ensure_content_exists(game_dir: &Path, category: &str, name: &str) -> io::Result<()> {
    let path = game_dir.join(category).join(name);
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() || !(metadata.is_file() || metadata.is_dir()) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Pack is not a regular file or directory",
        ));
    }
    Ok(())
}

#[cfg(test)]
fn escape_property(value: &str) -> String {
    let mut output = String::new();
    for code in value.encode_utf16() {
        match code {
            0x20 | 0x21 | 0x23 | 0x3a | 0x3d | 0x5c => {
                output.push('\\');
                output.push(char::from_u32(code as u32).unwrap());
            }
            0x09 => output.push_str("\\t"),
            0x0a => output.push_str("\\n"),
            0x0c => output.push_str("\\f"),
            0x0d => output.push_str("\\r"),
            0x21..=0x7e => output.push(char::from_u32(code as u32).unwrap()),
            _ => output.push_str(&format!("\\u{code:04X}")),
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unsafe_paths_are_rejected() {
        for name in ["", "..", ".", "x/y", "x\\y", "NUL", "COM1.txt", "trailing."] {
            assert!(!safe_name(name), "{name}");
        }
        assert!(safe_name("Сборка 1"));
        assert!(build_dir(Path::new("root"), "../bad", None, false).is_none());
    }
    #[test]
    fn scans_enabled_and_disabled_without_touching_files() {
        let dir = std::env::temp_dir().join(format!("aw-ui-test-{}", std::process::id()));
        fs::create_dir_all(dir.join("mods")).unwrap();
        fs::write(dir.join("mods/a.jar"), b"a").unwrap();
        fs::write(dir.join("mods/b.jar.disabled"), b"b").unwrap();
        fs::write(dir.join("mods/ignored.txt"), b"c").unwrap();
        fs::create_dir_all(dir.join("resourcepacks/Обычный пак")).unwrap();
        fs::create_dir_all(dir.join("shaderpacks/Выключенный пак.disabled")).unwrap();
        fs::write(
            dir.join("options.txt"),
            "resourcePacks:[\"vanilla\",\"file/Обычный пак\"]\n",
        )
        .unwrap();
        let items = scan_content(&dir).unwrap();
        assert_eq!(items.len(), 4);
        assert!(items[0].enabled);
        assert!(!items[1].enabled);
        assert!(
            !items
                .iter()
                .find(|v| v.name.starts_with("Выключенный"))
                .unwrap()
                .enabled
        );
        assert!(
            items
                .iter()
                .find(|v| v.name.starts_with("Обычный"))
                .unwrap()
                .enabled
        );
        fs::create_dir_all(dir.join("config")).unwrap();
        fs::write(dir.join("shaderpacks/test pack.zip"), b"shader").unwrap();
        fs::write(
            dir.join("config/iris.properties"),
            "enableShaders=true\nshaderPack=test\\u0020pack.zip\n",
        )
        .unwrap();
        assert!(
            scan_content(&dir)
                .unwrap()
                .iter()
                .find(|v| v.name == "test pack.zip")
                .unwrap()
                .enabled
        );
        fs::write(
            dir.join("config/iris.properties"),
            "enableShaders=false\nshaderPack=test\\u0020pack.zip\n",
        )
        .unwrap();
        assert!(
            !scan_content(&dir)
                .unwrap()
                .iter()
                .find(|v| v.name == "test pack.zip")
                .unwrap()
                .enabled
        );
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn resource_pack_selection_preserves_other_options() {
        let dir = std::env::temp_dir().join(format!("aw-ui-pack-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        fs::create_dir_all(dir.join("resourcepacks")).unwrap();
        fs::write(dir.join("resourcepacks/one.zip"), b"one").unwrap();
        fs::write(dir.join("resourcepacks/тест.zip"), b"two").unwrap();
        fs::write(
            dir.join("options.txt"),
            "fullscreen:true\nresourcePacks:[\"vanilla\",\"file/one.zip\"]\nviewDistance:12\n",
        )
        .unwrap();
        set_resource_pack(&dir, "тест.zip", true).unwrap();
        let updated = fs::read_to_string(dir.join("options.txt")).unwrap();
        assert!(updated.contains("fullscreen:true\n"));
        assert!(updated.contains("viewDistance:12\n"));
        assert!(updated.contains("file/тест.zip"));
        set_resource_pack(&dir, "one.zip", false).unwrap();
        let updated = fs::read_to_string(dir.join("options.txt")).unwrap();
        assert!(!updated.contains("file/one.zip"));
        assert!(updated.contains("file/тест.zip"));
        assert!(set_resource_pack(&dir, "../bad", true).is_err());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn shader_selection_preserves_iris_properties() {
        let dir = std::env::temp_dir().join(format!("aw-ui-shader-test-{}", std::process::id()));
        fs::create_dir_all(dir.join("config")).unwrap();
        fs::create_dir_all(dir.join("shaderpacks")).unwrap();
        fs::write(dir.join("shaderpacks/тест.zip"), b"shader").unwrap();
        fs::write(
            dir.join("config/iris.properties"),
            b"otherSetting=unchanged\nenableShaders=false\n",
        )
        .unwrap();
        set_shader(&dir, "тест.zip", true).unwrap();
        assert!(
            scan_content(&dir)
                .unwrap()
                .iter()
                .find(|item| item.name == "тест.zip")
                .unwrap()
                .enabled
        );
        let properties: std::collections::HashMap<String, String> =
            javaproperties::parse(&fs::read(dir.join("config/iris.properties")).unwrap())
                .unwrap()
                .into_iter()
                .collect();
        assert_eq!(
            properties.get("otherSetting").map(String::as_str),
            Some("unchanged")
        );
        set_shader(&dir, "тест.zip", false).unwrap();
        assert!(
            !scan_content(&dir)
                .unwrap()
                .iter()
                .find(|item| item.name == "тест.zip")
                .unwrap()
                .enabled
        );
        fs::write(dir.join("shaderpacks/test😀.zip"), b"shader").unwrap();
        set_shader(&dir, "test😀.zip", true).unwrap();
        assert!(
            scan_content(&dir)
                .unwrap()
                .iter()
                .find(|item| item.name == "test😀.zip")
                .unwrap()
                .enabled
        );
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn java_properties_escaping_uses_utf16() {
        assert_eq!(
            escape_property("тест 😀"),
            "\\u0442\\u0435\\u0441\\u0442\\ \\uD83D\\uDE00"
        );
    }

    #[test]
    fn datapacks_are_collected_from_worlds_and_keep_their_status() {
        let dir = std::env::temp_dir().join(format!("aw-ui-world-test-{}", std::process::id()));
        let world = dir.join("saves/World");
        fs::create_dir_all(world.join("datapacks")).unwrap();
        fs::create_dir_all(world.join("aw-disabled-datapacks")).unwrap();
        fs::write(world.join("level.dat"), b"level").unwrap();
        fs::write(world.join("datapacks/on.zip"), b"pack").unwrap();
        fs::write(world.join("aw-disabled-datapacks/off.zip"), b"pack").unwrap();
        fs::create_dir_all(dir.join("saves/not-a-world/datapacks")).unwrap();
        fs::write(dir.join("saves/not-a-world/datapacks/ignored.zip"), b"pack").unwrap();
        let items = scan_content(&dir).unwrap();
        assert_eq!(items.len(), 2);
        assert!(items.iter().find(|v| v.name == "on.zip").unwrap().enabled);
        assert!(!items.iter().find(|v| v.name == "off.zip").unwrap().enabled);
        assert!(items.iter().all(|v| v.world.as_deref() == Some("World")));
        fs::remove_dir_all(dir).unwrap();
    }
}
