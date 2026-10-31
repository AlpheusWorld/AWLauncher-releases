use serde::Deserialize;
use std::{io::Read, time::Duration};

#[derive(Clone, Debug, Deserialize)]
pub struct Hit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub author: String,
    #[serde(default)]
    pub downloads: u64,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub categories: Vec<String>,
}
#[derive(Clone, Debug, Deserialize)]
pub struct Page {
    #[serde(default)]
    pub hits: Vec<Hit>,
    #[serde(default)]
    pub total_hits: u64,
}

pub fn project_url(hit: &Hit, kind: &str, curseforge: bool) -> Option<String> {
    if curseforge {
        let id = hit.project_id.strip_prefix("cf:")?;
        if id.is_empty() || !id.bytes().all(|c| c.is_ascii_digit()) {
            return None;
        }
        return Some(format!("https://www.curseforge.com/projects/{id}"));
    }
    if !matches!(
        kind,
        "mod" | "modpack" | "resourcepack" | "shader" | "datapack"
    ) {
        return None;
    }
    let slug = if hit.slug.is_empty() {
        &hit.project_id
    } else {
        &hit.slug
    };
    if slug.is_empty()
        || !slug
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'-' | b'_'))
    {
        return None;
    }
    Some(format!("https://modrinth.com/{kind}/{slug}"))
}

pub fn search(
    query: &str,
    kind: &str,
    offset: u32,
    curseforge: bool,
    game: Option<&str>,
    loader: Option<&str>,
    sort: &str,
) -> Result<Page, String> {
    if !matches!(
        kind,
        "mod" | "modpack" | "resourcepack" | "shader" | "datapack"
    ) {
        return Err("Неизвестный тип проекта".into());
    }
    if !matches!(sort, "downloads" | "relevance" | "follows" | "updated") {
        return Err("Неизвестная сортировка каталога".into());
    }
    if curseforge {
        return curse_search(query, kind, offset, game, loader, sort);
    }
    let facets = serde_json::json!([[format!("project_type:{kind}")]]);
    let mut groups = facets.as_array().cloned().unwrap_or_default();
    if let Some(game) = game {
        groups.push(serde_json::json!([format!("versions:{game}")]));
    }
    if let Some(loader) = loader {
        let category = match kind {
            "shader" => "iris",
            "resourcepack" => "minecraft",
            "datapack" => "datapack",
            _ => loader,
        };
        groups.push(serde_json::json!([format!("categories:{category}")]));
    }
    if kind == "mod" {
        groups.push(serde_json::json!([
            "client_side:required",
            "client_side:optional"
        ]));
    }
    let facets = serde_json::Value::Array(groups).to_string();
    let client = reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(20))
        .redirect(reqwest::redirect::Policy::limited(4))
        .user_agent("AlpheusWorld/AWLauncher-Rust-preview")
        .build()
        .map_err(|e| e.to_string())?;
    let response = client
        .get("https://api.modrinth.com/v2/search")
        .query(&[
            ("query", query.trim()),
            ("facets", facets.as_str()),
            ("index", sort),
            ("offset", &offset.to_string()),
            ("limit", "20"),
        ])
        .send()
        .and_then(|r| r.error_for_status())
        .map_err(|e| e.to_string())?;
    let mut bytes = Vec::new();
    response
        .take(2 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| e.to_string())?;
    if bytes.len() > 2 * 1024 * 1024 {
        return Err("Ответ каталога слишком большой".into());
    }
    serde_json::from_slice(&bytes).map_err(|e| e.to_string())
}

#[derive(Deserialize)]
struct Response<T> {
    data: T,
    #[serde(default)]
    pagination: Pagination,
}
#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pagination {
    #[serde(default)]
    total_count: u64,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Category {
    id: u64,
    #[serde(default)]
    slug: String,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CurseMod {
    id: u64,
    game_id: u64,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    summary: String,
    #[serde(default)]
    authors: Vec<Author>,
    #[serde(default)]
    download_count: f64,
    #[serde(default)]
    logo: Option<Asset>,
    #[serde(default)]
    categories: Vec<Category>,
}
#[derive(Deserialize)]
struct Asset {
    url: String,
}
#[derive(Deserialize)]
struct Author {
    #[serde(default)]
    name: String,
}

fn curse_search(
    query: &str,
    kind: &str,
    offset: u32,
    game: Option<&str>,
    loader: Option<&str>,
    sort: &str,
) -> Result<Page, String> {
    let client = reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(20))
        .redirect(reqwest::redirect::Policy::limited(4))
        .user_agent("AlpheusWorld/AWLauncher-Rust-preview")
        .build()
        .map_err(|e| e.to_string())?;
    let base = "https://awlauncher.zarplaykayt.chatgpt.site/api/curseforge";
    let read = |response: reqwest::blocking::Response| -> Result<Vec<u8>, String> {
        let mut bytes = Vec::new();
        response
            .error_for_status()
            .map_err(|e| e.to_string())?
            .take(2 * 1024 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(|e| e.to_string())?;
        if bytes.len() > 2 * 1024 * 1024 {
            return Err("Ответ каталога слишком большой".into());
        }
        Ok(bytes)
    };
    let response = client
        .get(format!("{base}/categories"))
        .query(&[("gameId", "432"), ("classesOnly", "true")])
        .send()
        .map_err(|e| e.to_string())?;
    let categories: Response<Vec<Category>> =
        serde_json::from_slice(&read(response)?).map_err(|e| e.to_string())?;
    let slugs: &[&str] = match kind {
        "mod" => &["mc-mods", "mods"],
        "modpack" => &["modpacks"],
        "resourcepack" => &["texture-packs", "resource-packs"],
        "shader" => &["shaders"],
        "datapack" => &["data-packs", "datapacks"],
        _ => return Err("Неизвестный тип проекта".into()),
    };
    let class = categories
        .data
        .iter()
        .find(|v| slugs.contains(&v.slug.as_str()))
        .ok_or_else(|| "В CurseForge нет этого раздела".to_owned())?
        .id
        .to_string();
    let offset_text = offset.to_string();
    let response = client
        .get(format!("{base}/mods/search"))
        .query(&[
            ("gameId", "432"),
            ("classId", &class),
            ("searchFilter", query.trim()),
            (
                "sortField",
                match sort {
                    "downloads" => "6",
                    "updated" => "3",
                    _ => "1",
                },
            ),
            ("sortOrder", "desc"),
            ("index", &offset_text),
            ("pageSize", "20"),
        ])
        .query(
            &game
                .map(|game| vec![("gameVersion", game)])
                .unwrap_or_default(),
        )
        .query(&if kind == "mod" && game.is_some() {
            loader
                .and_then(|loader| match loader {
                    "forge" => Some(1),
                    "fabric" => Some(4),
                    "quilt" => Some(5),
                    "neoforge" => Some(6),
                    _ => None,
                })
                .map(|id| vec![("modLoaderTypes", format!("[{id}]"))])
                .unwrap_or_default()
        } else {
            Vec::new()
        })
        .send()
        .map_err(|e| e.to_string())?;
    let page: Response<Vec<CurseMod>> =
        serde_json::from_slice(&read(response)?).map_err(|e| e.to_string())?;
    Ok(Page {
        total_hits: page.pagination.total_count.min(10_000),
        hits: page
            .data
            .into_iter()
            .filter(|m| m.game_id == 432)
            .map(|m| Hit {
                project_id: format!("cf:{}", m.id),
                slug: m.slug,
                title: m.name,
                description: m.summary,
                author: m
                    .authors
                    .into_iter()
                    .map(|a| a.name)
                    .collect::<Vec<_>>()
                    .join(", "),
                downloads: m.download_count.max(0.0) as u64,
                icon_url: m.logo.map(|asset| asset.url),
                categories: m
                    .categories
                    .into_iter()
                    .map(|category| category.slug)
                    .filter(|slug| !slug.is_empty())
                    .collect(),
            })
            .collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_modrinth_response() {
        let page: Page = serde_json::from_str(r#"{"hits":[{"project_id":"abc","slug":"example","title":"Example","author":"Author","downloads":123,"categories":["fabric","lightweight"]}],"total_hits":1}"#).unwrap();
        assert_eq!(page.hits[0].project_id, "abc");
        assert_eq!(page.hits[0].downloads, 123);
        assert_eq!(page.hits[0].categories, ["fabric", "lightweight"]);
        assert_eq!(page.total_hits, 1);
    }
    #[test]
    fn parses_curseforge_search_response() {
        let page: Response<Vec<CurseMod>> = serde_json::from_str(r#"{"data":[{"id":42,"gameId":432,"slug":"sample","name":"Sample","summary":"Text","downloadCount":100,"authors":[{"name":"Alex"}]}],"pagination":{"totalCount":1}}"#).unwrap();
        assert_eq!(page.data[0].id, 42);
        assert_eq!(page.data[0].authors[0].name, "Alex");
        assert_eq!(page.pagination.total_count, 1);
    }
    #[test]
    fn rejects_invalid_type_before_network() {
        assert!(search("", "invalid", 0, false, None, None, "downloads").is_err());
    }
    #[test]
    fn project_links_cannot_escape_expected_domains() {
        let mut hit: Hit =
            serde_json::from_str(r#"{"project_id":"abc","slug":"project-name","title":"X"}"#)
                .unwrap();
        assert_eq!(
            project_url(&hit, "mod", false).unwrap(),
            "https://modrinth.com/mod/project-name"
        );
        hit.slug = "../evil".into();
        assert!(project_url(&hit, "mod", false).is_none());
        hit.project_id = "cf:123".into();
        assert_eq!(
            project_url(&hit, "mod", true).unwrap(),
            "https://www.curseforge.com/projects/123"
        );
    }
    #[test]
    #[ignore = "Explicit metadata-only network smoke test"]
    fn live_catalogs_return_projects() {
        let modrinth = search(
            "sodium",
            "mod",
            0,
            false,
            Some("1.21.1"),
            Some("fabric"),
            "downloads",
        )
        .unwrap();
        assert!(!modrinth.hits.is_empty());
        let curse = search(
            "fabric api",
            "mod",
            0,
            true,
            Some("1.21.1"),
            Some("fabric"),
            "updated",
        )
        .unwrap();
        assert!(!curse.hits.is_empty());
        assert!(curse.hits.iter().all(|h| h.project_id.starts_with("cf:")));
    }
}
