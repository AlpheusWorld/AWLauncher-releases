use crate::{err, Context, Result};
use serde_json::Value;
use std::{cmp::Ordering, fs, io::Read, path::Path};
#[derive(Debug)]
struct Version {
    core: Vec<u64>,
    pre: Vec<String>,
}
impl Version {
    fn parse(text: &str) -> Option<Self> {
        let release = text.trim().split('+').next()?;
        let (core, pre) = release
            .split_once('-')
            .map(|(a, b)| (a, Some(b)))
            .unwrap_or((release, None));
        Some(Self {
            core: core
                .split('.')
                .map(str::parse)
                .collect::<std::result::Result<_, _>>()
                .ok()?,
            pre: pre
                .map(|s| s.split('.').map(str::to_owned).collect())
                .unwrap_or_default(),
        })
    }
    fn compare(&self, other: &Self) -> Ordering {
        for i in 0..self.core.len().max(other.core.len()) {
            let order = self
                .core
                .get(i)
                .unwrap_or(&0)
                .cmp(other.core.get(i).unwrap_or(&0));
            if order != Ordering::Equal {
                return order;
            }
        }
        match (self.pre.is_empty(), other.pre.is_empty()) {
            (true, false) => return Ordering::Greater,
            (false, true) => return Ordering::Less,
            _ => {}
        }
        for (a, b) in self.pre.iter().zip(&other.pre) {
            let order = match (a.parse::<u64>(), b.parse::<u64>()) {
                (Ok(a), Ok(b)) => a.cmp(&b),
                (Ok(_), Err(_)) => Ordering::Less,
                (Err(_), Ok(_)) => Ordering::Greater,
                _ => a.cmp(b),
            };
            if order != Ordering::Equal {
                return order;
            }
        }
        self.pre.len().cmp(&other.pre.len())
    }
    fn same(&self, other: &Self, count: usize) -> bool {
        (0..count).all(|i| self.core.get(i).unwrap_or(&0) == other.core.get(i).unwrap_or(&0))
    }
}
fn satisfies(version: &str, predicate: &str) -> bool {
    let terms: Vec<_> = predicate.split_whitespace().collect();
    let Some(v) = Version::parse(version) else {
        return terms
            .iter()
            .all(|p| *p == "*" || *p == version || *p == format!("={version}"));
    };
    terms.into_iter().all(|term| {
        if term == "*" {
            return true;
        }
        let op = [">=", "<=", ">", "<", "=", "~", "^"]
            .into_iter()
            .find(|p| term.starts_with(p))
            .unwrap_or("");
        let raw = &term[op.len()..];
        let parts: Vec<_> = raw.split('.').collect();
        if let Some(n) = parts.iter().position(|p| matches!(*p, "x" | "X" | "*")) {
            return parts[..n]
                .iter()
                .enumerate()
                .all(|(i, p)| p.parse::<u64>().ok() == Some(*v.core.get(i).unwrap_or(&0)));
        }
        let Some(t) = Version::parse(raw) else {
            return false;
        };
        let order = v.compare(&t);
        match op {
            ">=" => order != Ordering::Less,
            "<=" => order != Ordering::Greater,
            ">" => order == Ordering::Greater,
            "<" => order == Ordering::Less,
            "~" => order != Ordering::Less && v.same(&t, 2.min(t.core.len())),
            "^" => {
                order != Ordering::Less
                    && v.same(
                        &t,
                        t.core
                            .iter()
                            .position(|v| *v != 0)
                            .unwrap_or(t.core.len() - 1)
                            + 1,
                    )
            }
            _ => order == Ordering::Equal,
        }
    })
}
fn metadata(path: &Path) -> Result<Option<Value>> {
    let mut zip = zip::ZipArchive::new(fs::File::open(path).map_err(err)?).map_err(err)?;
    let member = match zip.by_name("fabric.mod.json") {
        Ok(m) => m,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => return Err(err(e)),
    };
    let mut bytes = Vec::new();
    member
        .take(1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(err)?;
    if bytes.len() > 1024 * 1024 {
        return Err("Описание мода слишком большое".into());
    }
    let value: Value = serde_json::from_slice(&bytes).map_err(err)?;
    if !value["id"].is_string() || !value["version"].is_string() {
        return Ok(None);
    }
    Ok(Some(value))
}
fn breaks(a: &Value, b: &Value) -> bool {
    let Some(map) = a["breaks"].as_object() else {
        return false;
    };
    std::iter::once(&b["id"])
        .chain(b["provides"].as_array().into_iter().flatten())
        .filter_map(Value::as_str)
        .any(|id| {
            map.get(id).is_some_and(|ranges| {
                std::iter::once(ranges)
                    .filter_map(Value::as_str)
                    .chain(
                        ranges
                            .as_array()
                            .into_iter()
                            .flatten()
                            .filter_map(Value::as_str),
                    )
                    .any(|r| satisfies(b["version"].as_str().unwrap_or(""), r))
            })
        })
}
pub fn check(game: &Path) -> Result<()> {
    let dir = game.join("mods");
    if !dir.is_dir() {
        return Ok(());
    }
    let mut mods = Vec::new();
    for entry in fs::read_dir(dir).map_err(err)? {
        let path = entry.map_err(err)?.path();
        if path.extension().is_some_and(|s| s == "jar") {
            if let Ok(Some(meta)) = metadata(&path) {
                mods.push(meta);
            }
        }
    }
    let mut errors = Vec::new();
    for (i, a) in mods.iter().enumerate() {
        for b in &mods[i + 1..] {
            if a["id"] != b["id"] && (breaks(a, b) || breaks(b, a)) {
                let label = |m: &Value| {
                    format!(
                        "{} {}",
                        m["name"].as_str().or(m["id"].as_str()).unwrap_or("Мод"),
                        m["version"]
                            .as_str()
                            .unwrap_or("")
                            .split('+')
                            .next()
                            .unwrap_or("")
                    )
                };
                errors.push(format!("{} несовместим с {}", label(a), label(b)));
                if errors.len() == 3 {
                    return Err(errors.join("\n"));
                }
            }
        }
    }
    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors.join("\n"))
    }
}
trait ReadSeek: std::io::Read + std::io::Seek {}
impl<T: std::io::Read + std::io::Seek> ReadSeek for T {}
pub fn validate_jar(path: &Path) -> Result<()> {
    all_metadata(path).map(|_| ())
}
pub fn enabled_id(game: &Path, id: &str) -> bool {
    fs::read_dir(game.join("mods"))
        .ok()
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|s| s == "jar"))
        .any(|e| all_metadata(&e.path()).is_ok_and(|mods| mods.iter().any(|m| m["id"] == id)))
}
fn all_metadata(path: &Path) -> Result<Vec<Value>> {
    fn parse(
        mut zip: zip::ZipArchive<Box<dyn ReadSeek>>,
        depth: usize,
        count: &mut usize,
        expanded: &mut usize,
    ) -> Result<Vec<Value>> {
        if depth > 8 || *count >= 512 {
            return Err("Слишком много вложенных модов".into());
        }
        *count += 1;
        fn member(
            zip: &mut zip::ZipArchive<Box<dyn ReadSeek>>,
            name: &str,
            limit: usize,
            expanded: &mut usize,
        ) -> Result<Option<Vec<u8>>> {
            let file = match zip.by_name(name) {
                Ok(file) => file,
                Err(zip::result::ZipError::FileNotFound) => return Ok(None),
                Err(e) => return Err(err(e)),
            };
            let mut bytes = Vec::new();
            file.take(limit as u64 + 1)
                .read_to_end(&mut bytes)
                .map_err(err)?;
            *expanded += bytes.len();
            if bytes.len() > limit || *expanded > 128 * 1024 * 1024 {
                return Err("Вложенные файлы мода слишком большие".into());
            }
            Ok(Some(bytes))
        }
        let Some(bytes) = member(&mut zip, "fabric.mod.json", 1024 * 1024, expanded)? else {
            return Ok(Vec::new());
        };
        let value: Value = serde_json::from_slice(&bytes).map_err(err)?;
        if value["environment"] == "server"
            || value["environment"]
                .as_array()
                .is_some_and(|values| !values.iter().any(|v| v == "*" || v == "client"))
        {
            return Ok(Vec::new());
        }
        if !value["id"].is_string() || !value["version"].is_string() {
            return Err("У мода отсутствуют ID или версия".into());
        }
        let mut out = vec![value.clone()];
        for nested in value["jars"].as_array().into_iter().flatten() {
            let name = nested["file"]
                .as_str()
                .ok_or("Некорректный вложенный мод")?;
            crate::storage::path(Path::new("archive"), name)?;
            let bytes = member(&mut zip, name, 64 * 1024 * 1024, expanded)?
                .ok_or("Отсутствует вложенный мод")?;
            out.extend(parse(
                zip::ZipArchive::new(Box::new(std::io::Cursor::new(bytes)) as Box<dyn ReadSeek>)
                    .map_err(err)?,
                depth + 1,
                count,
                expanded,
            )?);
        }
        Ok(out)
    }
    let zip =
        zip::ZipArchive::new(Box::new(fs::File::open(path).map_err(err)?) as Box<dyn ReadSeek>)
            .map_err(err)?;
    parse(zip, 0, &mut 0, &mut 0)
}
pub fn check_dependencies(cx: &Context, profile: &Value) -> Result<()> {
    let game = Path::new(profile["dir"].as_str().ok_or("Нет папки сборки")?);
    let dir = game.join("mods");
    if !dir.is_dir() {
        return Ok(());
    }
    let mut mods = Vec::new();
    for entry in fs::read_dir(dir).map_err(err)? {
        let path = entry.map_err(err)?.path();
        if path.extension().is_some_and(|s| s == "jar") {
            if let Ok(values) = all_metadata(&path) {
                mods.extend(values);
            }
        }
    }
    // Fabric and Quilt can ship mods inside their loader JAR. Use the same
    // installed profile chosen by the launcher, including a pinned version.
    let kind = profile["loader"]
        .as_str()
        .unwrap_or("")
        .to_ascii_uppercase();
    if matches!(kind.as_str(), "FABRIC" | "QUILT") {
        let options = crate::builds::options(game)?;
        let pin = options["loaderVersion"]
            .as_str()
            .or(profile["loaderVersion"].as_str());
        if let Some(loader) = crate::game::installed_loader(
            cx,
            &serde_json::json!({"id":profile["version"]}),
            &kind,
            pin,
        ) {
            for library in loader["libraries"].as_array().into_iter().flatten() {
                let Some(name) = library["name"].as_str() else {
                    continue;
                };
                if !name.starts_with("net.fabricmc:fabric-loader:")
                    && !name.starts_with("org.quiltmc:quilt-loader:")
                {
                    continue;
                }
                let relative = match library["downloads"]["artifact"]["path"].as_str() {
                    Some(path) => path.to_owned(),
                    None => crate::game::maven(name)?,
                };
                let path = crate::relative(&cx.root.join("libraries"), &relative)?;
                if let Ok(values) = all_metadata(&path) {
                    mods.extend(values);
                }
            }
        }
    }
    for value in &mods {
        for (id, ranges) in value["depends"].as_object().into_iter().flatten() {
            if matches!(
                id.as_str(),
                "minecraft" | "java" | "fabricloader" | "quilt_loader" | "forge" | "neoforge"
            ) {
                continue;
            }
            let predicates: Vec<&str> = ranges
                .as_str()
                .into_iter()
                .chain(
                    ranges
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(Value::as_str),
                )
                .collect();
            let available = mods
                .iter()
                .filter(|other| {
                    other["id"] == *id
                        || other["provides"]
                            .as_array()
                            .into_iter()
                            .flatten()
                            .any(|alias| alias == id)
                })
                .any(|other| {
                    predicates
                        .iter()
                        .any(|range| satisfies(other["version"].as_str().unwrap_or(""), range))
                });
            if !available {
                return Err(format!(
                    "{} требует {id} {}",
                    value["name"]
                        .as_str()
                        .or(value["id"].as_str())
                        .unwrap_or("Мод"),
                    predicates.join(" или ")
                ));
            }
        }
    }
    Ok(())
}
pub fn retire_managed(game: &Path) -> Result<()> {
    use sha2::{Digest, Sha256};
    let jar = game.join("mods/awassistant.jar");
    let record = game.join("config/awassistant-managed.json");
    for (path, max) in [(&jar, 16_777_216u64), (&record, 4096)] {
        let Ok(meta) = fs::symlink_metadata(path) else {
            return Ok(());
        };
        if !meta.file_type().is_file() || meta.len() == 0 || meta.len() > max {
            return Ok(());
        }
    }
    let Ok(value) = crate::read_json(&record) else {
        return Ok(());
    };
    let Some(expected) = value["sha256"]
        .as_str()
        .filter(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
    else {
        return Ok(());
    };
    if format!("{:x}", Sha256::digest(fs::read(&jar).map_err(err)?)) != expected
        || metadata(&jar)
            .ok()
            .flatten()
            .is_none_or(|v| v["id"] != "awassistant")
    {
        return Ok(());
    }
    let mut disabled = game.join("mods/awassistant.jar.disabled");
    let mut suffix = 0;
    while fs::symlink_metadata(&disabled).is_ok() {
        suffix += 1;
        disabled = game.join("mods").join(format!(
            "awassistant-retired-{}-{suffix}.jar.disabled",
            &expected[..12]
        ));
    }
    fs::rename(&jar, disabled)
        .map_err(|_| "Не удалось отключить старый встроенный мод — закрой Minecraft и повтори")?;
    let _ = fs::remove_file(record);
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::io::Write;

    fn jar(entries: &[(&str, Vec<u8>)]) -> Vec<u8> {
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        for (name, data) in entries {
            writer
                .start_file(*name, zip::write::SimpleFileOptions::default())
                .unwrap();
            writer.write_all(data).unwrap();
        }
        writer.finish().unwrap().into_inner()
    }

    #[test]
    fn fabric_loader_embedded_dependencies_use_the_installed_version() {
        let root = std::env::temp_dir().join(format!("aw-loader-deps-{}", uuid::Uuid::new_v4()));
        let game = root.join("instance");
        fs::create_dir_all(game.join("mods")).unwrap();
        let mod_jar = jar(&[("fabric.mod.json", serde_json::to_vec(&json!({
            "id":"lithium", "name":"Lithium", "version":"1.0", "depends":{"mixinextras":">=0.5.5"}
        })).unwrap())]);
        fs::write(game.join("mods/lithium.jar"), mod_jar).unwrap();
        let id = "fabric-loader-0.19.5-fixture";
        crate::write_json(&root.join("versions").join(id).join(format!("{id}.json")), &json!({
            "id":id, "inheritsFrom":"fixture", "libraries":[{"name":"net.fabricmc:fabric-loader:0.19.5"}]
        })).unwrap();
        let path =
            root.join("libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar");
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let make_loader = |version: &str| {
            let nested = jar(&[(
                "fabric.mod.json",
                serde_json::to_vec(&json!({"id":"mixinextras","version":version})).unwrap(),
            )]);
            jar(&[("fabric.mod.json", serde_json::to_vec(&json!({"id":"fabricloader","version":"0.19.5","jars":[{"file":"META-INF/jars/mixinextras.jar"}]})).unwrap()), ("META-INF/jars/mixinextras.jar", nested)])
        };
        let cx = Context {
            root: root.clone(),
            cancelled: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
            event: &|_| {},
        };
        let profile = json!({"dir":game,"version":"fixture","loader":"FABRIC"});
        fs::write(&path, make_loader("0.5.5")).unwrap();
        assert!(check_dependencies(&cx, &profile).is_ok());
        fs::write(&path, make_loader("0.5.4")).unwrap();
        assert!(check_dependencies(&cx, &profile)
            .unwrap_err()
            .contains("mixinextras"));
        fs::remove_file(&path).unwrap();
        assert!(check_dependencies(&cx, &profile).is_err());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn ranges_prerelease_and_aliases() {
        assert!(satisfies("1.2.3", ">=1.0 <2"));
        assert!(satisfies("1.2.3+build", "1.2.x"));
        assert!(satisfies("0.2.9", "^0.2.1"));
        assert!(!satisfies("0.3.0", "^0.2.1"));
        assert!(!satisfies("1.2.3-rc.1", ">=1.2.3"));
        assert!(satisfies("custom-snapshot", "custom-snapshot"));
        assert!(breaks(
            &json!({"breaks":{"alias":["<2.0"]}}),
            &json!({"id":"real","version":"1.0","provides":["alias"]})
        ));
    }
}
