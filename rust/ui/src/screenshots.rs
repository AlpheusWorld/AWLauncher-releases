use eframe::egui::ColorImage;
use std::{collections::HashSet, fs, io, path::PathBuf, sync::mpsc, thread, time::SystemTime};

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

pub fn decode(path: &PathBuf, max_side: u32) -> io::Result<ColorImage> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.is_file() || metadata.file_type().is_symlink() || metadata.len() > 32 * 1024 * 1024
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Invalid or oversized image",
        ));
    }
    let mut reader = image::ImageReader::open(path)?.with_guessed_format()?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(8192);
    limits.max_image_height = Some(8192);
    limits.max_alloc = Some(96 * 1024 * 1024);
    reader.limits(limits);
    let image = reader
        .decode()
        .map_err(io::Error::other)?
        .thumbnail(max_side, max_side)
        .to_rgba8();
    Ok(ColorImage::from_rgba_unmultiplied(
        [image.width() as usize, image.height() as usize],
        image.as_raw(),
    ))
}

pub struct Loader {
    tx: mpsc::Sender<(PathBuf, u32)>,
    pub rx: mpsc::Receiver<(PathBuf, u32, Result<ColorImage, String>)>,
}
impl Loader {
    pub fn new(ctx: eframe::egui::Context) -> Self {
        let (tx, requests) = mpsc::channel::<(PathBuf, u32)>();
        let (results, rx) = mpsc::channel();
        thread::spawn(move || {
            while let Ok((path, side)) = requests.recv() {
                let result = decode(&path, side).map_err(|e| e.to_string());
                if results.send((path, side, result)).is_err() {
                    break;
                }
                ctx.request_repaint();
            }
        });
        Self { tx, rx }
    }
    pub fn request(&self, path: PathBuf, side: u32) {
        let _ = self.tx.send((path, side));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn finds_only_supported_files_and_deduplicates_folders() {
        let root = std::env::temp_dir().join(format!("aw-shots-test-{}", std::process::id()));
        fs::create_dir_all(root.join("screenshots")).unwrap();
        fs::write(root.join("screenshots/a.png"), b"png").unwrap();
        fs::write(root.join("screenshots/b.jpg"), b"jpg").unwrap();
        fs::write(root.join("screenshots/ignored.txt"), b"x").unwrap();
        let folder = Folder {
            key: "test".into(),
            title: "Тест".into(),
            dir: root.clone(),
        };
        assert_eq!(scan(&[folder.clone(), folder]).len(), 2);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn invalid_image_rejected() {
        let path = std::env::temp_dir().join(format!("aw-bad-shot-{}.png", std::process::id()));
        fs::write(&path, b"not image").unwrap();
        assert!(decode(&path, 320).is_err());
        fs::remove_file(path).unwrap();
    }
    #[test]
    fn real_png_is_loaded_and_scaled() {
        let path = std::env::temp_dir().join(format!("aw-good-shot-{}.png", std::process::id()));
        let image = image::RgbaImage::from_pixel(800, 400, image::Rgba([10, 20, 30, 255]));
        image.save(&path).unwrap();
        let decoded = decode(&path, 200).unwrap();
        assert_eq!(decoded.size, [200, 100]);
        fs::remove_file(path).unwrap();
    }
}
