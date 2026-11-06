use crate::{content, Context, Result};
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet, VecDeque};
fn project(v: &Value) -> Result<&str> {
    v["project_id"]
        .as_str()
        .ok_or("Нет ID проекта зависимости".into())
}
pub fn compatible(v: &Value, game: &str, platforms: &[String]) -> bool {
    v["game_versions"]
        .as_array()
        .into_iter()
        .flatten()
        .any(|g| g == game)
        && v["loaders"]
            .as_array()
            .into_iter()
            .flatten()
            .any(|l| platforms.iter().any(|p| l == p))
        && content::primary(v).is_ok_and(|f| {
            f["filename"]
                .as_str()
                .is_some_and(|s| crate::storage::filename(s) && s.to_lowercase().ends_with(".jar"))
        })
}
pub fn resolve_with(
    roots: &[Value],
    installed: &[Value],
    compatible: impl Fn(&Value) -> bool,
    mut detail: impl FnMut(&str) -> Result<Value>,
    mut candidates: impl FnMut(&str) -> Result<Vec<Value>>,
) -> Result<Vec<Value>> {
    let mut fixed = BTreeMap::new();
    for root in roots {
        if !compatible(root) {
            return Err("Выпуск мода несовместим с этой сборкой".into());
        }
        if fixed
            .insert(project(root)?.to_owned(), root.clone())
            .is_some()
        {
            return Err("Выбрано несколько выпусков одного проекта".into());
        }
    }
    let mut previous = BTreeMap::<String, String>::new();
    for _ in 0..32 {
        let mut chosen = fixed.clone();
        let mut queue: VecDeque<Value> = roots.iter().cloned().collect();
        let mut pins: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
        while let Some(parent) = queue.pop_front() {
            for dependency in parent["dependencies"]
                .as_array()
                .into_iter()
                .flatten()
                .filter(|d| d["dependency_type"] == "required")
            {
                let pinned = dependency["version_id"]
                    .as_str()
                    .map(&mut detail)
                    .transpose()?;
                let id = dependency["project_id"]
                    .as_str()
                    .or_else(|| pinned.as_ref().and_then(|v| v["project_id"].as_str()))
                    .ok_or("У обязательной зависимости нет проекта")?
                    .to_owned();
                if let Some(v) = &pinned {
                    if project(v)? != id || !compatible(v) {
                        return Err("Закреплённая зависимость несовместима со сборкой".into());
                    }
                    pins.entry(id.clone())
                        .or_default()
                        .insert(v["id"].as_str().ok_or("У зависимости нет версии")?.into());
                }
                if chosen.contains_key(&id) {
                    continue;
                }
                if chosen.len() >= 256 {
                    return Err("Слишком много связанных зависимостей".into());
                }
                let preferred = previous.get(&id).map(|v| detail(v)).transpose()?.or(pinned);
                let existing = if preferred.is_none() {
                    installed
                        .iter()
                        .filter(|i| i["project"] == id && i["enabled"] == true)
                        .filter_map(|i| i["versionId"].as_str())
                        .find_map(|version| detail(version).ok().filter(|v| compatible(v)))
                } else {
                    None
                };
                let selected = if let Some(v) = preferred.or(existing) {
                    v
                } else {
                    let rows = candidates(&id)?;
                    content::pick(
                        &rows
                            .iter()
                            .filter(|v| compatible(v))
                            .cloned()
                            .collect::<Vec<_>>(),
                    )
                    .cloned()
                    .ok_or("Нет подходящего выпуска обязательной зависимости")?
                };
                if project(&selected)? != id || !compatible(&selected) {
                    return Err("Неверный выпуск зависимости".into());
                }
                chosen.insert(id, selected.clone());
                queue.push_back(selected);
            }
        }
        if pins.values().any(|p| p.len() > 1) {
            return Err("Моды требуют разные закреплённые версии одной зависимости".into());
        }
        let mismatches: Vec<_> = pins
            .iter()
            .filter(|(id, versions)| {
                chosen.get(*id).and_then(|v| v["id"].as_str())
                    != versions.first().map(String::as_str)
            })
            .collect();
        if mismatches.iter().any(|(id, _)| fixed.contains_key(*id)) {
            return Err("Выбранный выпуск конфликтует с обязательной зависимостью".into());
        }
        if mismatches.is_empty() {
            for value in chosen.values() {
                for d in value["dependencies"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter(|d| d["dependency_type"] == "incompatible")
                {
                    if d["project_id"].as_str().is_some_and(|id| {
                        chosen.contains_key(id)
                            || installed.iter().any(|i| {
                                i["project"] == id
                                    && i["enabled"] == true
                                    && !chosen.contains_key(id)
                            })
                    }) {
                        return Err("В сборке есть несовместимая зависимость".into());
                    }
                }
            }
            return Ok(chosen.into_values().collect());
        }
        previous = pins
            .into_iter()
            .filter_map(|(id, versions)| versions.first().cloned().map(|v| (id, v)))
            .collect();
    }
    Err("Не удалось согласовать зависимости модов".into())
}
pub fn resolve(
    cx: &Context,
    profile: &Value,
    roots: &[Value],
    installed: &[Value],
) -> Result<Vec<Value>> {
    let platforms = content::loaders("mod", profile["loader"].as_str().unwrap_or(""));
    let game = profile["version"].as_str().ok_or("Нет версии Minecraft")?;
    resolve_with(
        roots,
        installed,
        |v| compatible(v, game, &platforms),
        |id| {
            cx.check()?;
            content::version(cx, id)
        },
        |id| {
            cx.check()?;
            content::versions(cx, id, &platforms, Some(game))
        },
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn v(id: &str, p: &str, deps: Value) -> Value {
        json!({"id":id,"project_id":p,"dependencies":deps})
    }
    #[test]
    fn dependency_pin_overrides_a_previously_selected_latest_version() {
        let root = v(
            "root",
            "root",
            json!([{"project_id":"a","dependency_type":"required"},{"project_id":"b","dependency_type":"required"}]),
        );
        let a = v("a-new", "a", json!([]));
        let old = v("a-old", "a", json!([]));
        let b = v(
            "b",
            "b",
            json!([{"project_id":"a","version_id":"a-old","dependency_type":"required"}]),
        );
        let result = resolve_with(
            &[root],
            &[],
            |_| true,
            |id| {
                if id == "a-old" {
                    Ok(old.clone())
                } else {
                    Err("unknown".into())
                }
            },
            |id| {
                Ok(if id == "a" {
                    vec![a.clone()]
                } else {
                    vec![b.clone()]
                })
            },
        )
        .unwrap();
        assert!(result.iter().any(|v| v["id"] == "a-old"));
        assert!(!result.iter().any(|v| v["id"] == "a-new"));
    }
    #[test]
    fn incompatible_exact_pins_are_rejected() {
        let root = v(
            "root",
            "root",
            json!([{"project_id":"a","version_id":"one","dependency_type":"required"},{"project_id":"a","version_id":"two","dependency_type":"required"}]),
        );
        assert!(resolve_with(
            &[root],
            &[],
            |_| true,
            |id| Ok(v(id, "a", json!([]))),
            |_| Ok(vec![])
        )
        .is_err());
    }
}
