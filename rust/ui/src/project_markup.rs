use super::*;
use egui::{FontId, Sense, Stroke, TextFormat};
use std::{
    hash::{Hash, Hasher},
    io::Write,
    process::Stdio,
    sync::{mpsc, Arc},
};
#[derive(Clone, Default, serde::Deserialize, Debug)]
pub struct Node {
    #[serde(default)]
    pub tag: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub attrs: HashMap<String, String>,
    #[serde(default)]
    pub children: Vec<Node>,
}
#[derive(Default)]
pub struct Documents {
    cache: HashMap<u64, Result<Arc<Vec<Node>>, String>>,
    pending: HashMap<u64, mpsc::Receiver<Result<Vec<Node>, String>>>,
}
impl Documents {
    pub fn get(
        &mut self,
        ctx: &egui::Context,
        backend: Option<&PathBuf>,
        body: &str,
        base: &str,
    ) -> Option<Result<Arc<Vec<Node>>, String>> {
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        body.hash(&mut hasher);
        base.hash(&mut hasher);
        let key = hasher.finish();
        if let Some(result) = self.cache.get(&key) {
            return Some(result.clone());
        }
        if let Some(receiver) = self.pending.get(&key) {
            match receiver.try_recv() {
                Ok(result) => {
                    self.pending.remove(&key);
                    let result = result.map(Arc::new);
                    if self.cache.len() >= 8 {
                        self.cache.clear();
                    }
                    self.cache.insert(key, result.clone());
                    return Some(result);
                }
                Err(mpsc::TryRecvError::Disconnected) => {
                    self.pending.remove(&key);
                    let result = Err("Не удалось подготовить описание проекта".to_owned());
                    self.cache.insert(key, result.clone());
                    return Some(result);
                }
                _ => return None,
            }
        }
        let Some(backend) = backend.cloned() else {
            return Some(Err("Не найден встроенный движок".into()));
        };
        let (body, base, ctx) = (body.to_owned(), base.to_owned(), ctx.clone());
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            let result = (|| -> Result<Vec<Node>, String> {
                let mut command = tasks::java_base_command(&backend)?;
                command
                    .arg("-Xmx256m")
                    .arg("ru.aw.launcher.nativeui.ProjectMarkup")
                    .arg(base)
                    .stdin(Stdio::piped())
                    .stdout(Stdio::piped())
                    .stderr(Stdio::piped());
                let mut child = command.spawn().map_err(|error| error.to_string())?;
                let mut input = child
                    .stdin
                    .take()
                    .ok_or("Не удалось подготовить описание проекта")?;
                input
                    .write_all(body.as_bytes())
                    .map_err(|error| error.to_string())?;
                drop(input);
                let output = child
                    .wait_with_output()
                    .map_err(|error| error.to_string())?;
                if !output.status.success() || output.stdout.len() > 16 * 1024 * 1024 {
                    return Err("Не удалось подготовить описание проекта".into());
                }
                serde_json::from_slice(&output.stdout)
                    .map_err(|_| "Не удалось подготовить описание проекта".into())
            })();
            let _ = tx.send(result);
            ctx.request_repaint();
        });
        self.pending.insert(key, rx);
        None
    }
    pub fn retry(&mut self) {
        self.cache.retain(|_, value| value.is_ok());
    }
}
pub fn safe_url(value: &str) -> Option<String> {
    let url = reqwest::Url::parse(value).ok()?;
    if matches!(url.scheme(), "https" | "http")
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none()
    {
        Some(url.into())
    } else {
        None
    }
}
#[derive(Clone)]
struct Style {
    bold: bool,
    italic: bool,
    code: bool,
    strike: bool,
    link: Option<String>,
}
fn inline(
    node: &Node,
    style: &Style,
    job: &mut egui::text::LayoutJob,
    links: &mut Vec<(std::ops::Range<usize>, String)>,
    size: f32,
    line: f32,
    p: Palette,
) {
    if matches!(node.tag.as_str(), "img" | "ul" | "ol" | "pre" | "table") {
        return;
    }
    let mut style = style.clone();
    match node.tag.as_str() {
        "strong" | "b" => style.bold = true,
        "em" | "i" => style.italic = true,
        "code" => style.code = true,
        "s" | "del" | "strike" => style.strike = true,
        "a" => style.link = node.attrs.get("href").and_then(|url| safe_url(url)),
        _ => {}
    }
    let text = if node.tag == "br" {
        "\n"
    } else if node.tag == "#text" {
        &node.text
    } else {
        ""
    };
    if !text.is_empty() {
        let range = job.text.len()..job.text.len() + text.len();
        let color = if style.link.is_some() {
            p.accent
        } else if size > 14.0 {
            p.text
        } else if p.dark {
            Color32::from_rgb(196, 206, 192)
        } else {
            Color32::from_rgb(77, 91, 98)
        };
        let mut format = TextFormat {
            font_id: FontId::new(
                if style.code { 12.0 } else { size },
                if style.code {
                    FontFamily::Monospace
                } else if style.bold {
                    FontFamily::Name("aw-bold".into())
                } else {
                    FontFamily::Proportional
                },
            ),
            color,
            italics: style.italic,
            line_height: Some(line),
            ..Default::default()
        };
        if style.strike {
            format.strikethrough = Stroke::new(1.0_f32, color);
        }
        if style.link.is_some() {
            format.underline = Stroke::new(1.0_f32, color);
        }
        job.append(text, 0.0, format);
        if let Some(url) = style.link.clone() {
            links.push((range, url));
        }
    }
    for child in &node.children {
        inline(child, &style, job, links, size, line, p);
    }
}
fn paragraph(ui: &mut egui::Ui, nodes: &[Node], heading: u32, bold: bool, p: Palette) {
    let (size, line) = match heading {
        1 => (22.0, 28.0),
        2 => (20.0, 26.0),
        3..=6 => (18.0, 24.0),
        _ => (14.0, 20.0),
    };
    let mut job = egui::text::LayoutJob::default();
    job.wrap.max_width = ui.available_width().max(1.0);
    job.wrap.break_anywhere = false;
    let mut links = Vec::new();
    let style = Style {
        bold: bold || heading > 0,
        italic: false,
        code: false,
        strike: false,
        link: None,
    };
    for node in nodes {
        inline(node, &style, &mut job, &mut links, size, line, p);
    }
    if job.text.trim().is_empty() {
        return;
    }
    let text = job.text.clone();
    let galley = ui.fonts(|fonts| fonts.layout_job(job));
    let response = ui.add(egui::Label::new(galley.clone()).selectable(true).sense(
        if links.is_empty() {
            Sense::hover()
        } else {
            Sense::click_and_drag()
        },
    ));
    if let Some(pointer) = response.hover_pos() {
        let cursor = galley.cursor_from_pos(pointer - response.rect.min);
        let byte = text
            .char_indices()
            .nth(cursor.ccursor.index)
            .map(|(index, _)| index)
            .unwrap_or(text.len());
        if let Some((_, url)) = links
            .iter()
            .find(|(range, _)| byte >= range.start && byte <= range.end)
        {
            ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
            if response.clicked() {
                ui.ctx().open_url(egui::OpenUrl::new_tab(url));
            }
        }
    }
}
#[derive(Clone)]
struct Media {
    url: String,
    alt: String,
    link: Option<String>,
}
fn media_nodes(node: &Node, parent: Option<&str>, output: &mut Vec<Media>) {
    let link = if node.tag == "a" {
        node.attrs.get("href").map(String::as_str).or(parent)
    } else {
        parent
    };
    if node.tag == "img" {
        if let Some(url) = node
            .attrs
            .get("src")
            .filter(|url| url.starts_with("https://"))
        {
            output.push(Media {
                url: url.clone(),
                alt: node.attrs.get("alt").cloned().unwrap_or_default(),
                link: link.and_then(safe_url),
            });
        }
        return;
    }
    for child in &node.children {
        media_nodes(child, link, output);
    }
}
fn media(
    ui: &mut egui::Ui,
    item: &Media,
    images: &mut remote_images::Images,
    natural: bool,
    p: Palette,
) {
    if let Some(texture) = images.media(ui.ctx(), &item.url) {
        let size = texture.size_vec2();
        let width = if natural {
            size.x
        } else {
            size.x.min(ui.available_width())
        };
        let response = ui.add(
            egui::Image::new(&texture)
                .fit_to_exact_size(Vec2::new(width, width * size.y / size.x))
                .sense(if item.link.is_some() {
                    Sense::click()
                } else {
                    Sense::hover()
                }),
        );
        if let Some(url) = &item.link {
            response.clone().on_hover_text(&item.alt);
            if response.clicked() {
                ui.ctx().open_url(egui::OpenUrl::new_tab(url));
            }
        }
    } else {
        let width = if natural {
            160.0
        } else {
            ui.available_width().min(480.0)
        };
        let (rect, _) = ui.allocate_exact_size(Vec2::new(width, 80.0), Sense::hover());
        ui.painter().rect_filled(rect, 0.0, p.high);
        if images.failed(&item.url) {
            let text = translations::text("Повторить загрузку изображения");
            let hit = ui.interact(rect, ui.id().with((&item.url, "retry")), Sense::click());
            ui.painter().text(
                rect.center(),
                egui::Align2::CENTER_CENTER,
                text,
                FontId::proportional(13.0),
                p.accent,
            );
            if hit.clicked() {
                images.retry(&item.url);
            }
        } else {
            ui.painter().text(
                rect.center(),
                egui::Align2::CENTER_CENTER,
                translations::text("Загружаю изображение…"),
                FontId::proportional(13.0),
                p.muted,
            );
        }
    }
}
fn descendants<'a>(node: &'a Node, tag: &str, out: &mut Vec<&'a Node>) {
    for child in &node.children {
        if child.tag == tag {
            out.push(child);
        } else {
            descendants(child, tag, out);
        }
    }
}
fn block(
    ui: &mut egui::Ui,
    node: &Node,
    images: &mut remote_images::Images,
    p: Palette,
    id: &mut u64,
) {
    *id += 1;
    match node.tag.as_str() {
        "#text" => {
            if !node.text.trim().is_empty() {
                paragraph(ui, std::slice::from_ref(node), 0, false, p);
            }
        }
        "div" | "section" | "article" | "details" | "summary" | "tbody" | "thead" => {
            ui.scope(|ui| {
                ui.spacing_mut().item_spacing.y = 10.0;
                for child in &node.children {
                    block(ui, child, images, p, id);
                }
            });
        }
        "ul" | "ol" => {
            ui.scope(|ui| {
                ui.spacing_mut().item_spacing.y = 8.0;
                for (index, child) in node
                    .children
                    .iter()
                    .filter(|node| node.tag == "li")
                    .enumerate()
                {
                    ui.horizontal_top(|ui| {
                        ui.add_space(12.0);
                        ui.label(
                            egui::RichText::new(if node.tag == "ol" {
                                format!("{}.", index + 1)
                            } else {
                                "•".into()
                            })
                            .color(if p.dark {
                                Color32::from_rgb(196, 206, 192)
                            } else {
                                p.text
                            }),
                        );
                        ui.vertical(|ui| {
                            ui.set_max_width(ui.available_width());
                            block(ui, child, images, p, id);
                        });
                    });
                }
            });
        }
        "pre" => {
            egui::Frame::NONE
                .fill(p.high)
                .inner_margin(14.0)
                .show(ui, |ui| {
                    egui::ScrollArea::horizontal()
                        .id_salt(("project-code", *id))
                        .show(ui, |ui| {
                            ui.add(
                                egui::Label::new(
                                    egui::RichText::new(node.text.trim_end())
                                        .font(FontId::monospace(12.0))
                                        .color(p.text),
                                )
                                .selectable(true)
                                .wrap_mode(egui::TextWrapMode::Extend),
                            );
                        });
                });
        }
        "blockquote" => {
            egui::Frame::NONE
                .fill(p.high)
                .inner_margin(14.0)
                .show(ui, |ui| {
                    ui.spacing_mut().item_spacing.y = 8.0;
                    for child in &node.children {
                        block(ui, child, images, p, id);
                    }
                });
        }
        "table" => {
            let mut rows = Vec::new();
            descendants(node, "tr", &mut rows);
            egui::ScrollArea::horizontal()
                .id_salt(("project-table", *id))
                .show(ui, |ui| {
                    ui.spacing_mut().item_spacing = Vec2::ZERO;
                    for row in rows {
                        ui.horizontal_top(|ui| {
                            ui.spacing_mut().item_spacing.x = 0.0;
                            for cell in row
                                .children
                                .iter()
                                .filter(|node| matches!(node.tag.as_str(), "td" | "th"))
                            {
                                egui::Frame::NONE
                                    .fill(p.high)
                                    .inner_margin(12.0)
                                    .show(ui, |ui| {
                                        ui.set_width(146.0);
                                        paragraph(ui, &cell.children, 0, cell.tag == "th", p);
                                    });
                            }
                        });
                    }
                });
        }
        "img" => {
            let mut all = Vec::new();
            media_nodes(node, None, &mut all);
            for item in all {
                media(ui, &item, images, false, p);
            }
        }
        _ => {
            let heading = node
                .tag
                .strip_prefix('h')
                .and_then(|value| value.parse::<u32>().ok())
                .filter(|value| (1..=6).contains(value))
                .unwrap_or(0);
            paragraph(ui, &node.children, heading, false, p);
            let mut all = Vec::new();
            media_nodes(node, None, &mut all);
            if !all.is_empty() {
                if all.len() > 1 {
                    egui::ScrollArea::horizontal()
                        .id_salt(("project-images", *id))
                        .scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden)
                        .show(ui, |ui| {
                            ui.horizontal_top(|ui| {
                                ui.spacing_mut().item_spacing.x = 8.0;
                                for item in &all {
                                    media(ui, item, images, true, p);
                                }
                            });
                        });
                } else {
                    media(ui, &all[0], images, false, p);
                }
            }
            for child in node
                .children
                .iter()
                .filter(|child| matches!(child.tag.as_str(), "ul" | "ol" | "pre" | "table"))
            {
                block(ui, child, images, p, id);
            }
        }
    }
}
pub fn render(ui: &mut egui::Ui, nodes: &[Node], images: &mut remote_images::Images, p: Palette) {
    ui.spacing_mut().item_spacing.y = 16.0;
    let mut id = 0;
    for node in nodes {
        ui.scope(|ui| {
            ui.spacing_mut().item_spacing.y = 10.0;
            block(ui, node, images, p, &mut id);
        });
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn links_reject_local_files_and_executable_schemes() {
        assert!(safe_url("https://example.com/docs").is_some());
        for url in [
            "file:///C:/secret",
            "javascript:alert(1)",
            "https://user:secret@example.com",
        ] {
            assert!(safe_url(url).is_none());
        }
    }
    #[test]
    fn linked_badges_keep_image_and_target() {
        let node:Node=serde_json::from_str(r#"{"tag":"p","children":[{"tag":"a","attrs":{"href":"https://download.fo/install"},"children":[{"tag":"img","attrs":{"src":"https://i.ibb.co/badge.png","alt":"How to install"}}]}]}"#).unwrap();
        let mut images = Vec::new();
        media_nodes(&node, None, &mut images);
        assert_eq!(images.len(), 1);
        assert_eq!(
            images[0].link.as_deref(),
            Some("https://download.fo/install")
        );
        assert_eq!(images[0].alt, "How to install");
    }
}
