use std::{collections::HashSet, fs, path::PathBuf, time::SystemTime};

#[derive(Clone, Debug)]
pub struct Folder {
    pub key: String,
    pub title: String,
    pub dir: PathBuf,
}
#[derive(Clone, Debug)]
pub struct Shot {
    pub path: PathBuf,
    pub key: String,
    pub title: String,
    pub modified: SystemTime,
    pub bytes: u64,
}

pub fn scan(folders: &[Folder]) -> Vec<Shot> {
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for folder in folders {
        if !seen.insert(folder.dir.clone()) {
            continue;
        }
        let dir = folder.dir.join("screenshots");
        if !fs::symlink_metadata(&dir)
            .map(|m| m.is_dir() && !m.file_type().is_symlink())
            .unwrap_or(false)
        {
            continue;
        }
        let Ok(entries) = fs::read_dir(dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let extension = path
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("")
                .to_lowercase();
            if !matches!(extension.as_str(), "png" | "jpg" | "jpeg" | "webp") {
                continue;
            }
            let Ok(metadata) = fs::symlink_metadata(&path) else {
                continue;
            };
            if !metadata.is_file() || metadata.file_type().is_symlink() {
                continue;
            }
            out.push(Shot {
                path,
                key: folder.key.clone(),
                title: folder.title.clone(),
                modified: metadata.modified().unwrap_or(SystemTime::UNIX_EPOCH),
                bytes: metadata.len(),
            });
        }
    }
    out.sort_by(|a, b| {
        b.modified
            .cmp(&a.modified)
            .then_with(|| a.path.cmp(&b.path))
    });
    out
}
