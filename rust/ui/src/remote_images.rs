use eframe::egui;
use std::{
    collections::{HashMap, HashSet, VecDeque},
    io::{Read, Write},
    path::{Path, PathBuf},
    process::Stdio,
    sync::{mpsc, Arc, Mutex},
    time::Duration,
};
fn public_https(url: &reqwest::Url) -> bool {
    url.scheme() == "https"
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none_or(|port| port == 443)
}
fn allowed(url: &reqwest::Url) -> bool {
    public_https(url)
        && matches!(
            url.host_str(),
            Some(
                "cdn.modrinth.com"
                    | "media.forgecdn.net"
                    | "mediafilez.forgecdn.net"
                    | "edge.forgecdn.net"
            )
        )
}
fn allowed_media(url: &reqwest::Url) -> bool {
    allowed(url)
        || (public_https(url)
            && matches!(
                url.host_str(),
                Some(
                    "i.ibb.co"
                        | "img.youtube.com"
                        | "i.ytimg.com"
                        | "img.shields.io"
                        | "shields.io"
                        | "raw.githubusercontent.com"
                        | "user-images.githubusercontent.com"
                        | "private-user-images.githubusercontent.com"
                        | "media.githubusercontent.com"
                        | "objects.githubusercontent.com"
                        | "github.com"
                        | "i.imgur.com"
                        | "cdn.discordapp.com"
                        | "media.discordapp.net"
                )
            ))
}
#[derive(Clone, Hash, Eq, PartialEq)]
struct Key {
    url: String,
    edge: u32,
}
fn resize_image(decoded: image::DynamicImage, edge: u32) -> image::RgbaImage {
    if edge > 128 && decoded.width() <= edge && decoded.height() <= edge {
        decoded.to_rgba8()
    } else {
        decoded.thumbnail(edge, edge).to_rgba8()
    }
}
pub struct Images {
    tx: mpsc::SyncSender<Key>,
    rx: mpsc::Receiver<(Key, Option<egui::ColorImage>)>,
    cache: HashMap<Key, egui::TextureHandle>,
    pending: HashSet<Key>,
    failed: HashSet<Key>,
    recent: VecDeque<Key>,
}
fn svg_png(backend: &Path, bytes: &[u8]) -> Option<Vec<u8>> {
    let mut command = crate::tasks::java_base_command(backend).ok()?;
    command
        .arg("-Xmx256m")
        .arg("ru.aw.launcher.nativeui.ProjectMarkup")
        .arg("svg")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    let mut child = command.spawn().ok()?;
    let mut input = child.stdin.take()?;
    input.write_all(bytes).ok()?;
    drop(input);
    let output = child.wait_with_output().ok()?;
    if !output.status.success() || output.stdout.len() > 16 * 1024 * 1024 {
        return None;
    }
    Some(output.stdout)
}
impl Images {
    pub fn new(ctx: egui::Context, backend: Option<PathBuf>) -> Self {
        let (tx, input) = mpsc::sync_channel::<Key>(64);
        let (output, rx) = mpsc::channel();
        let input = Arc::new(Mutex::new(input));
        for _ in 0..3 {
            let input = input.clone();
            let output = output.clone();
            let ctx = ctx.clone();
            let backend = backend.clone();
            std::thread::spawn(move || {
                let client = reqwest::blocking::Client::builder()
                    .timeout(Duration::from_secs(20))
                    .connect_timeout(Duration::from_secs(8))
                    .user_agent("AlpheusWorld/AWLauncher/1.0.50")
                    .redirect(reqwest::redirect::Policy::custom(|attempt| {
                        if attempt.previous().len() < 4 && allowed_media(attempt.url()) {
                            attempt.follow()
                        } else {
                            attempt.stop()
                        }
                    }))
                    .build();
                loop {
                    let request = input.lock().ok().and_then(|input| input.recv().ok());
                    let Some(key) = request else {
                        break;
                    };
                    let image = (|| -> Option<egui::ColorImage> {
                        let parsed = reqwest::Url::parse(&key.url).ok()?;
                        if !(if key.edge > 128 {
                            allowed_media(&parsed)
                        } else {
                            allowed(&parsed)
                        }) {
                            return None;
                        }
                        let response = client
                            .as_ref()
                            .ok()?
                            .get(parsed)
                            .send()
                            .ok()?
                            .error_for_status()
                            .ok()?;
                        let limit = if key.edge > 128 {
                            16 * 1024 * 1024
                        } else {
                            1024 * 1024
                        };
                        let mut bytes = Vec::new();
                        response.take(limit + 1).read_to_end(&mut bytes).ok()?;
                        if bytes.len() > limit as usize {
                            return None;
                        }
                        if String::from_utf8_lossy(&bytes[..bytes.len().min(1024)]).contains("<svg")
                        {
                            bytes = svg_png(backend.as_ref()?, &bytes)?;
                        }
                        let mut reader = image::ImageReader::new(std::io::Cursor::new(bytes))
                            .with_guessed_format()
                            .ok()?;
                        let mut limits = image::Limits::default();
                        limits.max_image_width = Some(16384);
                        limits.max_image_height = Some(16384);
                        limits.max_alloc = Some(if key.edge > 128 {
                            160 * 1024 * 1024
                        } else {
                            24 * 1024 * 1024
                        });
                        reader.limits(limits);
                        let decoded = reader.decode().ok()?;
                        if decoded.width() as u64 * decoded.height() as u64 > 40_000_000 {
                            return None;
                        }
                        let image = resize_image(decoded, key.edge);
                        Some(egui::ColorImage::from_rgba_unmultiplied(
                            [image.width() as usize, image.height() as usize],
                            image.as_raw(),
                        ))
                    })();
                    if output.send((key, image)).is_err() {
                        break;
                    }
                    ctx.request_repaint();
                }
            });
        }
        Self {
            tx,
            rx,
            cache: HashMap::new(),
            pending: HashSet::new(),
            failed: HashSet::new(),
            recent: VecDeque::new(),
        }
    }
    pub fn poll(&mut self, ctx: &egui::Context) {
        while let Ok((key, image)) = self.rx.try_recv() {
            self.pending.remove(&key);
            if let Some(image) = image {
                let pixels = image.size[0] * image.size[1];
                while self.cache.len() >= 160
                    || self
                        .cache
                        .values()
                        .map(|image| image.size()[0] * image.size()[1])
                        .sum::<usize>()
                        + pixels
                        > 16_000_000
                {
                    let Some(old) = self.recent.pop_front() else {
                        break;
                    };
                    self.cache.remove(&old);
                }
                self.cache.insert(
                    key.clone(),
                    ctx.load_texture(
                        format!("{}:{}", key.edge, key.url),
                        image,
                        egui::TextureOptions::LINEAR,
                    ),
                );
                self.recent.push_back(key);
            } else {
                if self.failed.len() >= 512 {
                    self.failed.clear();
                }
                self.failed.insert(key);
            }
        }
    }
    fn request(
        &mut self,
        ctx: &egui::Context,
        url: &str,
        edge: u32,
    ) -> Option<egui::TextureHandle> {
        self.poll(ctx);
        let key = Key {
            url: url.to_owned(),
            edge,
        };
        if let Some(image) = self.cache.get(&key) {
            self.recent.retain(|old| old != &key);
            self.recent.push_back(key);
            return Some(image.clone());
        }
        if !self.failed.contains(&key)
            && !self.pending.contains(&key)
            && self.tx.try_send(key.clone()).is_ok()
        {
            self.pending.insert(key);
        }
        None
    }
    pub fn get(&mut self, ctx: &egui::Context, url: &str) -> Option<egui::TextureHandle> {
        self.request(ctx, url, 128)
    }
    pub fn media(&mut self, ctx: &egui::Context, url: &str) -> Option<egui::TextureHandle> {
        self.request(ctx, url, 1280)
    }
    pub fn failed(&self, url: &str) -> bool {
        self.failed.contains(&Key {
            url: url.to_owned(),
            edge: 1280,
        })
    }
    pub fn retry(&mut self, url: &str) {
        let key = Key {
            url: url.to_owned(),
            edge: 1280,
        };
        self.failed.remove(&key);
        self.pending.remove(&key);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn description_badges_keep_their_original_size() {
        let badge = resize_image(image::DynamicImage::new_rgba8(160, 34), 1280);
        assert_eq!(badge.dimensions(), (160, 34));
        let preview = resize_image(image::DynamicImage::new_rgba8(480, 360), 1280);
        assert_eq!(preview.dimensions(), (480, 360));
        let large = resize_image(image::DynamicImage::new_rgba8(2560, 1440), 1280);
        assert_eq!(large.dimensions(), (1280, 720));
    }
    #[test]
    fn icons_reject_local_and_unrelated_hosts() {
        assert!(allowed(
            &reqwest::Url::parse("https://cdn.modrinth.com/icon.png").unwrap()
        ));
        for url in [
            "http://cdn.modrinth.com/icon.png",
            "https://127.0.0.1/icon.png",
            "https://evil.invalid/icon.png",
            "https://user:secret@cdn.modrinth.com/icon.png",
        ] {
            assert!(!allowed(&reqwest::Url::parse(url).unwrap()));
        }
    }
    #[test]
    fn description_media_allows_original_badges_and_video_previews() {
        for url in [
            "https://i.ibb.co/image.png",
            "https://img.youtube.com/vi/test/hqdefault.jpg",
            "https://img.shields.io/badge/test.svg",
        ] {
            assert!(allowed_media(&reqwest::Url::parse(url).unwrap()));
        }
        for url in [
            "file:///C:/secret.png",
            "https://127.0.0.1/image.png",
            "https://cdn.modrinth.com:8443/image.png",
        ] {
            assert!(!allowed_media(&reqwest::Url::parse(url).unwrap()));
        }
    }
}
