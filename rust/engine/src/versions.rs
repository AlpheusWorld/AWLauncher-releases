use crate::{err, read_json, Context, Result};
use serde_json::{json, Value};
use std::{collections::HashSet, io::Read};
const MANIFEST: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const FORGE: &str = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
const NEOFORGE: &str =
    "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
fn maven(cx: &Context, url: &str) -> Result<Vec<String>> {
    let response = cx
        .client()?
        .get(url)
        .send()
        .map_err(|_| "Нет связи с каталогом загрузчиков")?;
    if !response.status().is_success() {
        return Err("Каталог загрузчиков временно недоступен".into());
    }
    let mut bytes = Vec::new();
    response
        .take(4 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(err)?;
    if bytes.len() > 4 * 1024 * 1024 {
        return Err("Каталог загрузчиков слишком большой".into());
    }
    let text = String::from_utf8(bytes).map_err(err)?;
    let pattern = regex::Regex::new(r"<version>([^<]+)</version>").unwrap();
    Ok(pattern
        .captures_iter(&text)
        .map(|c| c[1].to_owned())
        .collect())
}
fn host(kind: &str) -> Option<&'static str> {
    match kind {
        "FABRIC" => Some("https://meta.fabricmc.net/v2"),
        "QUILT" => Some("https://meta.quiltmc.org/v3"),
        _ => None,
    }
}
fn stable(version: &str) -> bool {
    let v = version.to_ascii_lowercase();
    !["alpha", "beta", "pre", "rc"].iter().any(|p| v.contains(p))
}
fn forge_supported(game: &str) -> bool {
    let Ok(v) = game
        .split('.')
        .map(str::parse::<u64>)
        .collect::<std::result::Result<Vec<_>, _>>()
    else {
        return false;
    };
    (
        v.first().copied().unwrap_or(0),
        v.get(1).copied().unwrap_or(0),
        v.get(2).copied().unwrap_or(0),
    ) >= (1, 7, 10)
}
fn newest(values: &mut [Value]) {
    values.sort_by(|a, b| {
        let key = |v: &Value| {
            v["version"]
                .as_str()
                .unwrap_or("")
                .split('-')
                .next()
                .unwrap_or("")
                .split('.')
                .map(|n| n.parse::<u64>().unwrap_or(0))
                .collect::<Vec<_>>()
        };
        key(b)
            .cmp(&key(a))
            .then_with(|| (a["stable"] != true).cmp(&(b["stable"] != true)))
    });
}
pub fn list(cx: &Context) -> Result<Value> {
    let manifest = cx.json(MANIFEST)?;
    if !manifest["versions"].is_array() {
        return Err("Некорректный список версий Minecraft".into());
    }
    Ok(manifest["versions"].clone())
}
pub fn loaders(cx: &Context, profile: &Value) -> Result<Value> {
    let kind = profile["loader"]
        .as_str()
        .unwrap_or("VANILLA")
        .to_ascii_uppercase();
    let game = profile["version"].as_str().ok_or("Нет версии Minecraft")?;
    if kind == "FORGE" && !forge_supported(game) {
        return Ok(json!([]));
    }
    let mut values = if let Some(host) = host(&kind) {
        let rows = cx.json(&format!("{host}/versions/loader/{game}"))?;
        rows.as_array().ok_or("Некорректный список загрузчиков")?.iter().filter_map(|r|r["loader"]["version"].as_str().map(|v|json!({"version":v,"label":v,"stable":r["loader"]["stable"]==true||stable(v)}))).collect::<Vec<_>>()
    } else if kind == "FORGE" {
        let prefix = format!("{game}-");
        let promos = cx.json(
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json",
        )?;
        let recommended = promos["promos"][format!("{game}-recommended")].as_str();
        maven(cx,FORGE)?.iter().filter_map(|v|v.strip_prefix(&prefix)).map(|v|json!({"version":v,"label":if recommended==Some(v){format!("{v} · рекомендуемая")}else{v.into()},"stable":stable(v),"recommended":recommended==Some(v)})).collect()
    } else if kind == "NEOFORGE" {
        maven(cx, NEOFORGE)?
            .into_iter()
            .filter(|v| crate::game::neoforge_game(v).as_deref() == Some(game))
            .map(|v| json!({"label":v,"stable":stable(&v),"version":v}))
            .collect()
    } else if kind == "VANILLA" {
        Vec::new()
    } else {
        return Err("Неизвестный загрузчик".into());
    };
    newest(&mut values);
    let mut seen = HashSet::new();
    values.retain(|v| seen.insert(v["version"].as_str().unwrap_or("").to_owned()));
    Ok(json!(values))
}
fn supported(cx: &Context, kind: &str) -> Result<HashSet<String>> {
    if let Some(host) = host(kind) {
        let list = cx.json(&format!("{host}/versions/game"))?;
        return Ok(list
            .as_array()
            .ok_or("Некорректный список версий загрузчика")?
            .iter()
            .filter_map(|v| v["version"].as_str().map(str::to_owned))
            .collect());
    }
    match kind {
        "FORGE" => Ok(maven(cx, FORGE)?
            .into_iter()
            .filter_map(|v| v.split_once('-').map(|(game, _)| game.to_owned()))
            .filter(|v| forge_supported(v))
            .collect()),
        "NEOFORGE" => Ok(maven(cx, NEOFORGE)?
            .iter()
            .filter_map(|v| crate::game::neoforge_game(v))
            .collect()),
        "VANILLA" => Ok(HashSet::new()),
        _ => Err("Неизвестный загрузчик".into()),
    }
}
pub fn migration_versions(cx: &Context, profile: &Value) -> Result<Value> {
    let kind = profile["loader"]
        .as_str()
        .unwrap_or("VANILLA")
        .to_ascii_uppercase();
    let supported = supported(cx, &kind)?;
    let settings_path = cx.root.join("launcher.json");
    let settings = if settings_path.is_file() {
        read_json(&settings_path)?
    } else {
        json!({})
    };
    let values = list(cx)?;
    Ok(json!(values
        .as_array()
        .unwrap()
        .iter()
        .filter(|v| v["id"] != profile["version"]
            && (kind == "VANILLA" || v["id"].as_str().is_some_and(|id| supported.contains(id)))
            && match v["type"].as_str() {
                Some("release") => true,
                Some("snapshot") => settings["showSnapshots"] == true,
                Some("old_alpha" | "old_beta") => settings["showOldVersions"] == true,
                _ => false,
            })
        .map(|v| v["id"].clone())
        .collect::<Vec<_>>()))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn loader_sorting_is_numeric_and_prefers_releases() {
        let mut values = vec![
            json!({"version":"0.20.1","stable":true}),
            json!({"version":"0.9.9","stable":true}),
            json!({"version":"0.20.1-beta","stable":false}),
        ];
        newest(&mut values);
        assert_eq!(values[0]["version"], "0.20.1");
        assert_eq!(values[2]["version"], "0.9.9");
    }
}
