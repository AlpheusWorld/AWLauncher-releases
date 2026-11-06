//! Legacy Forge installer format (versionInfo + embedded Maven artifact).
use crate::{err, game, read_json, relative, write_bytes, write_json, Context, Result};
use serde_json::Value;
use std::{fs, io::Read, path::Path};

pub fn install(cx: &Context, installer: &Path) -> Result<Option<Value>> {
    let mut archive = zip::ZipArchive::new(fs::File::open(installer).map_err(err)?).map_err(err)?;
    let root: Value = {
        let member = match archive.by_name("install_profile.json") {
            Ok(m) => m,
            Err(zip::result::ZipError::FileNotFound) => return Ok(None),
            Err(e) => return Err(err(e)),
        };
        let mut bytes = Vec::new();
        member
            .take(2 * 1024 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(err)?;
        if bytes.len() > 2 * 1024 * 1024 {
            return Err("Профиль установщика Forge слишком большой".into());
        }
        serde_json::from_slice(&bytes).map_err(|_| "Повреждён профиль установщика Forge")?
    };
    let Some(profile) = root.get("versionInfo") else {
        return Ok(None);
    };
    if !profile["inheritsFrom"].is_string() {
        return Err("Поддерживается Legacy Forge начиная с Minecraft 1.7.10".into());
    }
    let id = profile["id"]
        .as_str()
        .filter(|s| !s.is_empty())
        .ok_or("У Forge нет ID профиля")?;
    if id.contains('/') {
        return Err("Некорректный ID профиля Forge".into());
    }
    let profile_path = relative(&cx.root.join("versions"), id)?.join(format!("{id}.json"));
    let coordinate = root["install"]["path"]
        .as_str()
        .ok_or("В установщике Forge нет Maven-координаты")?;
    let destination = relative(&cx.root.join("libraries"), &game::maven(coordinate)?)?;
    let member_name = root["install"]["filePath"]
        .as_str()
        .ok_or("В установщике Forge нет имени библиотеки")?;
    relative(Path::new("archive"), member_name)?;
    let mut member = archive
        .by_name(member_name)
        .map_err(|_| "В установщике Forge отсутствует библиотека")?;
    if member.size() > 256 * 1024 * 1024 {
        return Err("Библиотека Forge слишком большая".into());
    }
    let mut bytes = Vec::new();
    let mut buffer = [0; 65536];
    loop {
        cx.check()?;
        let n = member.read(&mut buffer).map_err(err)?;
        if n == 0 {
            break;
        }
        bytes.extend_from_slice(&buffer[..n]);
        if bytes.len() > 256 * 1024 * 1024 {
            return Err("Библиотека Forge слишком большая".into());
        }
    }
    // Commit the profile only after the embedded library is safely installed.
    write_bytes(&destination, &bytes)?;
    write_json(&profile_path, profile)?;
    Ok(Some(read_json(&profile_path)?))
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::{
        io::Write,
        sync::{atomic::AtomicBool, Arc},
    };
    fn fixture(root: &Path, id: &str, embedded: bool) -> std::path::PathBuf {
        fs::create_dir_all(root).unwrap();
        let path = root.join("installer.jar");
        let mut zip = zip::ZipWriter::new(fs::File::create(&path).unwrap());
        let options = zip::write::SimpleFileOptions::default();
        zip.start_file("install_profile.json", options).unwrap();
        zip.write_all(json!({"versionInfo":{"id":id,"inheritsFrom":"1.7.10","libraries":[{"name":"net.minecraftforge:forge:1.7.10-test"}]},"install":{"path":"net.minecraftforge:forge:1.7.10-test","filePath":"maven/forge.jar"}}).to_string().as_bytes()).unwrap();
        if embedded {
            zip.start_file("maven/forge.jar", options).unwrap();
            zip.write_all(b"test-embedded-jar").unwrap();
        }
        zip.finish().unwrap();
        path
    }
    #[test]
    fn extracts_embedded_library_before_committing_profile() {
        let root = std::env::temp_dir().join(format!("aw-legacy-{}", uuid::Uuid::new_v4()));
        let jar = fixture(&root, "1.7.10-Forge-test", true);
        let cx = Context {
            root: root.clone(),
            cancelled: Arc::new(AtomicBool::new(false)),
            event: &|_| {},
        };
        let profile = install(&cx, &jar).unwrap().unwrap();
        assert_eq!(profile["inheritsFrom"], "1.7.10");
        assert_eq!(
            fs::read(
                root.join("libraries/net/minecraftforge/forge/1.7.10-test/forge-1.7.10-test.jar")
            )
            .unwrap(),
            b"test-embedded-jar"
        );
        assert!(root
            .join("versions/1.7.10-Forge-test/1.7.10-Forge-test.json")
            .exists());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn missing_embedded_jar_and_unsafe_profile_leave_no_version() {
        let root = std::env::temp_dir().join(format!("aw-legacy-{}", uuid::Uuid::new_v4()));
        let cx = Context {
            root: root.clone(),
            cancelled: Arc::new(AtomicBool::new(false)),
            event: &|_| {},
        };
        assert!(install(&cx, &fixture(&root, "1.7.10-Forge-test", false)).is_err());
        assert!(!root.join("versions").exists());
        assert!(install(&cx, &fixture(&root, "../escape", true)).is_err());
        assert!(!root.join("versions").exists());
        fs::remove_dir_all(root).unwrap();
    }
}
