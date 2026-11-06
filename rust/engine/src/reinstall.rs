use crate::{err, read_json, relative, Context, Result};
use serde_json::{json, Value};
#[cfg(windows)]
use std::process::{Command, Stdio};
use std::{
    fs,
    path::{Path, PathBuf},
};

/// Restore the previous version directories if download/install fails or is cancelled.
struct Backup {
    entries: Vec<(PathBuf, PathBuf)>,
    staging: PathBuf,
    committed: bool,
}
impl Drop for Backup {
    fn drop(&mut self) {
        if !self.committed {
            for (original, backup) in self.entries.iter().rev() {
                if original.exists() {
                    let _ = fs::remove_dir_all(original);
                }
                let _ = fs::rename(backup, original);
            }
        }
        // Never remove a failed rollback's backup. It may be the only complete copy.
        if self.committed || self.entries.iter().all(|(_, backup)| !backup.exists()) {
            let _ = fs::remove_dir_all(&self.staging);
        }
    }
}
pub fn no_games(cx: &Context) -> Result<()> {
    ensure_idle(cx, None)
}
pub fn ensure_idle(cx: &Context, directory: Option<&Path>) -> Result<()> {
    cx.check()?;
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let script = r#"$ErrorActionPreference='Stop'; $root=$env:AW_GAME_DIR_TO_CHECK; $games=Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -Property CommandLine; foreach($game in $games){if($game.CommandLine -match '(?:^|\s)"?--gameDir"?(?:\s+|=)(?:"([^"]+)"|(\S+))'){ $path=if($Matches[1]){$Matches[1]}else{$Matches[2]}; if([string]::IsNullOrEmpty($root) -or [string]::Equals([IO.Path]::GetFullPath($path),$root,[StringComparison]::OrdinalIgnoreCase)){exit 31}}}; exit 0"#;
        let mut child = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", script])
            .env(
                "AW_GAME_DIR_TO_CHECK",
                directory
                    .map(|p| p.to_string_lossy().to_string())
                    .unwrap_or_default(),
            )
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .map_err(|_| "Не удалось проверить процессы Minecraft")?;
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
        loop {
            if let Some(status) = child.try_wait().map_err(err)? {
                return match status.code() {
                    Some(0) => Ok(()),
                    Some(31) => Err("Закрой Minecraft перед переустановкой".into()),
                    _ => Err("Не удалось проверить процессы Minecraft".into()),
                };
            }
            if cx.check().is_err() || std::time::Instant::now() > deadline {
                let _ = child.kill();
                let _ = child.wait();
                return Err("Проверка процессов Minecraft не завершилась".into());
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
    }
    #[cfg(not(windows))]
    {
        let processes =
            fs::read_dir("/proc").map_err(|_| "Не удалось проверить процессы Minecraft")?;
        for entry in processes.flatten() {
            if let Ok(bytes) = fs::read(entry.path().join("cmdline")) {
                let args: Vec<_> = bytes.split(|b| *b == 0).collect();
                if args.windows(2).any(|a| {
                    a[0] == b"--gameDir"
                        && directory.is_none_or(|d| {
                            PathBuf::from(String::from_utf8_lossy(a[1]).to_string())
                                .canonicalize()
                                .ok()
                                == d.canonicalize().ok()
                        })
                }) {
                    return Err("Закрой Minecraft перед переустановкой".into());
                }
            }
        }
        Ok(())
    }
}
fn owns(id: &str, document: &Value, game: &str, kind: &str) -> bool {
    match kind {
        "VANILLA" => id == game,
        "FABRIC" => id.starts_with("fabric-loader-") && id.ends_with(&format!("-{game}")),
        "QUILT" => id.starts_with("quilt-loader-") && id.ends_with(&format!("-{game}")),
        "FORGE" => id
            .to_ascii_lowercase()
            .starts_with(&format!("{game}-forge")),
        "NEOFORGE" => id.starts_with("neoforge-") && document["inheritsFrom"] == game,
        _ => false,
    }
}
fn backup(cx: &Context, profile: &Value) -> Result<Backup> {
    let game = profile["version"].as_str().ok_or("Нет версии Minecraft")?;
    let kind = profile["loader"]
        .as_str()
        .ok_or("Нет загрузчика")?
        .to_ascii_uppercase();
    if !matches!(
        kind.as_str(),
        "VANILLA" | "FABRIC" | "QUILT" | "FORGE" | "NEOFORGE"
    ) {
        return Err("Неизвестный загрузчик".into());
    }
    let versions = cx.root.join("versions");
    fs::create_dir_all(&versions).map_err(err)?;
    let staging = cx
        .root
        .join("cache")
        .join(format!("reinstall-{}", uuid::Uuid::new_v4()));
    fs::create_dir_all(&staging).map_err(err)?;
    let mut backup = Backup {
        entries: Vec::new(),
        staging,
        committed: false,
    };
    for entry in fs::read_dir(&versions).map_err(err)? {
        let entry = entry.map_err(err)?;
        if !entry.file_type().map_err(err)?.is_dir() {
            continue;
        }
        let id = entry.file_name().to_string_lossy().to_string();
        let document = read_json(&entry.path().join(format!("{id}.json"))).unwrap_or(Value::Null);
        if !owns(&id, &document, game, &kind) {
            continue;
        }
        for category in ["versions", "natives"] {
            let root = cx.root.join(category);
            let path = relative(&root, &id)?;
            if !path.exists() {
                continue;
            }
            // Both roots are fixed and canonicalized before any move or recursive cleanup.
            if fs::symlink_metadata(&path)
                .map_err(err)?
                .file_type()
                .is_symlink()
                || !path
                    .canonicalize()
                    .map_err(err)?
                    .starts_with(root.canonicalize().map_err(err)?)
            {
                return Err("Папка версии выходит за каталог лаунчера".into());
            }
            let destination = backup.staging.join(category).join(&id);
            fs::create_dir_all(destination.parent().unwrap()).map_err(err)?;
            fs::rename(&path, &destination).map_err(err)?;
            backup.entries.push((path, destination));
        }
    }
    Ok(backup)
}
pub fn run(cx: &Context, profile: &Value) -> Result<Value> {
    if profile["key"]
        .as_str()
        .is_some_and(|s| s.starts_with("pack:"))
    {
        return Err("Модпак переустанавливается через менеджер модпаков".into());
    }
    no_games(cx)?;
    cx.stage("Сохранение предыдущих файлов Minecraft");
    let mut backup = backup(cx, profile)?;
    crate::game::install(cx, profile)?;
    backup.committed = true;
    Ok(json!("Сборка переустановлена"))
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{atomic::AtomicBool, Arc};
    #[test]
    fn failed_install_restores_versions_and_natives() {
        let root = std::env::temp_dir().join(format!("aw-rollback-{}", uuid::Uuid::new_v4()));
        let event = |_| {};
        let cx = Context {
            root: root.clone(),
            cancelled: Arc::new(AtomicBool::new(false)),
            event: &event,
        };
        crate::write_json(
            &root.join("versions/1.7.10-Forge-test/1.7.10-Forge-test.json"),
            &json!({"id":"1.7.10-Forge-test","inheritsFrom":"1.7.10"}),
        )
        .unwrap();
        crate::write_bytes(&root.join("natives/1.7.10-Forge-test/old.dll"), b"old").unwrap();
        let profile = json!({"version":"1.7.10","loader":"Forge"});
        {
            let _backup = backup(&cx, &profile).unwrap();
            assert!(!root.join("versions/1.7.10-Forge-test").exists());
            crate::write_bytes(&root.join("versions/1.7.10-Forge-test/new.tmp"), b"partial")
                .unwrap();
        }
        assert!(root
            .join("versions/1.7.10-Forge-test/1.7.10-Forge-test.json")
            .exists());
        assert!(!root.join("versions/1.7.10-Forge-test/new.tmp").exists());
        assert_eq!(
            fs::read(root.join("natives/1.7.10-Forge-test/old.dll")).unwrap(),
            b"old"
        );
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn ownership_does_not_touch_other_games() {
        assert!(!owns("1.21.1-forge-47", &json!({}), "1.21", "FORGE"));
        assert!(!owns(
            "neoforge-21.1",
            &json!({"inheritsFrom":"1.21.1"}),
            "1.21",
            "NEOFORGE"
        ));
    }
}
