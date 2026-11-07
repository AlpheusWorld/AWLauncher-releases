use crate::{err, relative, Context, Result};
use sha1::{Digest, Sha1};
use std::{
    collections::BTreeMap,
    fs,
    io::Read,
    path::{Path, PathBuf},
};
fn is_link(metadata: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        const REPARSE_POINT: u32 = 0x400;
        metadata.file_attributes() & REPARSE_POINT != 0
    }
    #[cfg(not(windows))]
    metadata.file_type().is_symlink()
}
pub fn path(root: &Path, name: &str) -> Result<PathBuf> {
    if name.split('/').any(|part| !filename(part)) {
        return Err("Некорректный путь файла".into());
    }
    let result = relative(root, name)?;
    let mut current = root.to_path_buf();
    if fs::symlink_metadata(root).is_ok_and(|m| is_link(&m)) {
        return Err("Папка не должна быть ссылкой".into());
    }
    for part in Path::new(name).components() {
        current.push(part);
        if fs::symlink_metadata(&current).is_ok_and(|m| is_link(&m)) {
            return Err("Символические ссылки недопустимы".into());
        }
    }
    Ok(result)
}
pub fn hash(file: &Path) -> Result<String> {
    let mut f = fs::File::open(file).map_err(err)?;
    let mut digest = Sha1::new();
    let mut bytes = [0; 65536];
    loop {
        let n = f.read(&mut bytes).map_err(err)?;
        if n == 0 {
            break;
        }
        digest.update(&bytes[..n]);
    }
    Ok(format!("{:x}", digest.finalize()))
}
pub fn inventory(root: &Path, names: &[String]) -> Result<(BTreeMap<String, String>, Vec<String>)> {
    let mut files = BTreeMap::new();
    let mut dirs = Vec::new();
    fn visit(
        root: &Path,
        name: &str,
        files: &mut BTreeMap<String, String>,
        dirs: &mut Vec<String>,
    ) -> Result<()> {
        let p = path(root, name)?;
        let meta = match fs::symlink_metadata(&p) {
            Ok(m) => m,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(e) => return Err(err(e)),
        };
        if meta.is_dir() {
            dirs.push(name.into());
            for entry in fs::read_dir(p).map_err(err)? {
                let entry = entry.map_err(err)?;
                visit(
                    root,
                    &format!("{name}/{}", entry.file_name().to_string_lossy()),
                    files,
                    dirs,
                )?;
            }
        } else if meta.is_file() {
            files.insert(name.into(), hash(&p)?);
        } else {
            return Err("Неподдерживаемый тип файла".into());
        }
        Ok(())
    }
    for name in names {
        visit(root, name, &mut files, &mut dirs)?;
    }
    dirs.sort();
    dirs.dedup();
    Ok((files, dirs))
}
pub fn names(root: &Path) -> Result<Vec<String>> {
    if !root.exists() {
        return Ok(Vec::new());
    }
    let mut out = Vec::new();
    for entry in fs::read_dir(root).map_err(err)? {
        let entry = entry.map_err(err)?;
        if is_link(&fs::symlink_metadata(entry.path()).map_err(err)?) {
            continue;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        path(root, &name)?;
        out.push(name);
    }
    out.sort();
    Ok(out)
}
pub fn copy(cx: &Context, source: &Path, target: &Path, names: &[String]) -> Result<()> {
    let (files, dirs) = inventory(source, names)?;
    copy_inventory(cx, source, target, &files, &dirs)
}

pub(crate) fn copy_inventory(
    cx: &Context,
    source: &Path,
    target: &Path,
    files: &BTreeMap<String, String>,
    dirs: &[String],
) -> Result<()> {
    for dir in dirs {
        cx.check()?;
        fs::create_dir_all(path(target, &dir)?).map_err(err)?;
    }
    for (name, expected) in files {
        cx.check()?;
        let dest = path(target, &name)?;
        fs::create_dir_all(dest.parent().unwrap()).map_err(err)?;
        fs::copy(path(source, &name)?, &dest).map_err(err)?;
        if hash(&dest)? != *expected {
            return Err("Файл изменился при копировании".into());
        }
    }
    Ok(())
}
pub fn remove(root: &Path, name: &str) -> Result<()> {
    let p = path(root, name)?;
    if !p.exists() {
        return Ok(());
    }
    if p.is_dir() {
        fs::remove_dir_all(p).map_err(err)
    } else {
        fs::remove_file(p).map_err(err)
    }
}
pub fn size(root: &Path) -> Result<u64> {
    let names = names(root)?;
    let (files, _) = inventory(root, &names)?;
    files.keys().try_fold(0, |sum, k| {
        Ok(sum + fs::metadata(path(root, k)?).map_err(err)?.len())
    })
}
pub fn atomic_copy(source: &Path, target: &Path) -> Result<()> {
    let parent = target.parent().ok_or("Нет папки назначения")?;
    fs::create_dir_all(parent).map_err(err)?;
    let tmp = parent.join(format!(".copy-{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| {
        fs::copy(source, &tmp).map_err(err)?;
        crate::replace(&tmp, target)
    })();
    let _ = fs::remove_file(tmp);
    result
}
pub fn id(value: &str) -> Result<&str> {
    if uuid::Uuid::parse_str(value).is_ok_and(|id| id.to_string() == value) {
        Ok(value)
    } else {
        Err("Некорректный ID".into())
    }
}
pub fn filename(name: &str) -> bool {
    !name.is_empty()
        && name.chars().count() <= 240
        && !name.ends_with(['.', ' '])
        && !name.chars().any(|c| c < ' ' || "<>:\"/\\|?*".contains(c))
        && !matches!(
            name.to_ascii_uppercase().split('.').next().unwrap_or(""),
            "CON"
                | "PRN"
                | "AUX"
                | "NUL"
                | "COM1"
                | "COM2"
                | "COM3"
                | "COM4"
                | "COM5"
                | "COM6"
                | "COM7"
                | "COM8"
                | "COM9"
                | "LPT1"
                | "LPT2"
                | "LPT3"
                | "LPT4"
                | "LPT5"
                | "LPT6"
                | "LPT7"
                | "LPT8"
                | "LPT9"
        )
}
pub fn overlay(
    cx: &Context,
    source: &Path,
    target: &Path,
    remove_names: &[String],
    commit: impl FnOnce() -> Result<()>,
) -> Result<()> {
    let names = names(source)?;
    let (files, dirs) = inventory(source, &names)?;
    let tx_name = format!("aw-migration-backups/.tx-{}", uuid::Uuid::new_v4());
    let tx = path(target, &tx_name)?;
    let prepared = tx.join("prepared");
    let previous = tx.join("previous");
    fs::create_dir_all(&prepared).map_err(err)?;
    fs::create_dir_all(&previous).map_err(err)?;
    let mut affected: std::collections::BTreeSet<String> = files
        .iter()
        .filter(|(name, expected)| {
            path(target, name).ok().and_then(|p| hash(&p).ok()).as_ref() != Some(*expected)
        })
        .map(|(name, _)| name.clone())
        .collect();
    affected.extend(remove_names.iter().cloned());
    for name in &affected {
        path(target, name)?;
    }
    // Prepare only files that will actually change. The staging tree often
    // contains every mod and config, even when installing just one project.
    for name in &affected {
        cx.check()?;
        if let Some(expected) = files.get(name) {
            let dest = path(&prepared, name)?;
            fs::create_dir_all(dest.parent().unwrap()).map_err(err)?;
            fs::copy(path(source, name)?, &dest).map_err(err)?;
            if hash(&dest)? != *expected {
                let _ = remove(target, &tx_name);
                return Err("Файл изменился при копировании".into());
            }
        }
    }
    if inventory(source, &names)? != (files.clone(), dirs.clone()) {
        let _ = remove(target, &tx_name);
        return Err("Сборка изменилась при подготовке файлов".into());
    }
    cx.check()?;
    let mut moved = Vec::new();
    let mut placed = Vec::new();
    let result = (|| {
        for name in &affected {
            let dest = path(target, name)?;
            if dest.exists() {
                let old = path(&previous, name)?;
                fs::create_dir_all(old.parent().unwrap()).map_err(err)?;
                fs::rename(&dest, old).map_err(err)?;
                moved.push(name.clone());
            }
            if files.contains_key(name) {
                fs::create_dir_all(dest.parent().unwrap()).map_err(err)?;
                fs::rename(path(&prepared, name)?, dest).map_err(err)?;
                placed.push(name.clone());
            }
        }
        for dir in &dirs {
            fs::create_dir_all(path(target, dir)?).map_err(err)?;
        }
        commit()
    })();
    if let Err(error) = result {
        let rollback = (|| {
            for name in placed.iter().rev() {
                remove(target, name)?;
            }
            for name in moved.iter().rev() {
                let dest = path(target, name)?;
                fs::create_dir_all(dest.parent().unwrap()).map_err(err)?;
                fs::rename(path(&previous, name)?, dest).map_err(err)?;
            }
            Ok::<_, String>(())
        })();
        if rollback.is_err() {
            return Err(format!("{error}. Резервные файлы: {}", tx.display()));
        }
        let _ = remove(target, &tx_name);
        return Err(error);
    }
    let _ = remove(target, &tx_name);
    Ok(())
}
