use eframe::egui::ColorImage;
use std::{fs, io, path::PathBuf, sync::mpsc, thread};

pub use crate::screenshot_files::*;

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
