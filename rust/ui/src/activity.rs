use chrono::{Datelike, Duration, Local, NaiveDate, TimeZone};
use flate2::read::GzDecoder;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    fs,
    io::{self, BufRead, BufReader, Read},
    path::{Path, PathBuf},
    sync::OnceLock,
};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub instance: String,
    pub version_id: String,
    pub loader: String,
    pub start: i64,
    pub end: i64,
    #[serde(default)]
    pub server: Option<String>,
    #[serde(default)]
    pub pack: Option<String>,
    #[serde(default)]
    pub build_id: Option<String>,
    #[serde(default)]
    pub build_name: Option<String>,
    #[serde(default)]
    pub launcher_owned: bool,
}
#[derive(Clone)]
pub struct Profile {
    pub id: String,
    pub name: String,
    pub version: String,
    pub loader: String,
    pub dir: PathBuf,
    pub pack: bool,
    pub native: bool,
    pub imported_ms: i64,
    pub imported_at: Option<i64>,
}
#[derive(Serialize, Deserialize)]
struct CachedLog {
    bytes: u64,
    modified: u128,
    session: Option<Session>,
}
#[derive(Default, Clone)]
pub struct Stats {
    pub total_ms: i64,
    pub week_ms: i64,
    pub last_week_ms: i64,
    pub streak: usize,
    pub best_streak: usize,
    pub days: BTreeMap<NaiveDate, i64>,
    pub recent: Vec<Session>,
    pub imported_ms: i64,
    pub longest_ms: i64,
    pub versions: Vec<(String, String, i64)>,
    pub servers: Vec<(String, i64)>,
}

pub fn native_profiles(roots: &[(PathBuf, bool)], existing: &[Profile]) -> Vec<Profile> {
    let mut known: HashSet<PathBuf> = existing.iter().map(|p| p.dir.clone()).collect();
    let mut output = Vec::new();
    for (root, launcher_owned) in roots {
        let Ok(entries) = fs::read_dir(root) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if !entry
                .file_type()
                .map(|t| t.is_dir() && !t.is_symlink())
                .unwrap_or(false)
                || !path.join("logs").is_dir()
                || !known.insert(path.clone())
            {
                continue;
            }
            if !launcher_owned
                && !path.join("aw-instance.json").is_file()
                && !path.join("aw-pack.json").is_file()
            {
                continue;
            }
            let Some(id) = entry.file_name().to_str().map(str::to_owned) else {
                continue;
            };
            let (version, loader) = id
                .rsplit_once('-')
                .and_then(|(version, suffix)| {
                    let lower = suffix.to_lowercase();
                    ["fabric", "forge", "neoforge", "quilt"]
                        .contains(&lower.as_str())
                        .then(|| (version.to_owned(), lower.to_uppercase()))
                })
                .unwrap_or_else(|| (id.clone(), "VANILLA".to_owned()));
            output.push(Profile {
                id: id.clone(),
                name: id,
                version,
                loader,
                dir: path,
                pack: false,
                native: true,
                imported_ms: 0,
                imported_at: None,
            });
        }
    }
    output
}

fn date_for_file(path: &Path) -> Option<NaiveDate> {
    static ROLLED: OnceLock<Regex> = OnceLock::new();
    let name = path.file_name()?.to_str()?;
    if name == "latest.log" {
        return Some(
            chrono::DateTime::<Local>::from(fs::metadata(path).ok()?.modified().ok()?).date_naive(),
        );
    }
    let capture = ROLLED
        .get_or_init(|| Regex::new(r"^(\d{4})-(\d{2})-(\d{2})-\d+\.log\.gz$").unwrap())
        .captures(name)?;
    NaiveDate::from_ymd_opt(
        capture[1].parse().ok()?,
        capture[2].parse().ok()?,
        capture[3].parse().ok()?,
    )
}

fn line_time(line: &str, date: NaiveDate) -> Option<i64> {
    static TIME: OnceLock<Regex> = OnceLock::new();
    static FORGE: OnceLock<Regex> = OnceLock::new();
    let simple = TIME.get_or_init(|| Regex::new(r"^\[(\d{2}):(\d{2}):(\d{2})").unwrap());
    let forge = FORGE.get_or_init(|| {
        Regex::new(r"^\[(\d{2})([A-Za-z]{3})(\d{4}) (\d{2}):(\d{2}):(\d{2})").unwrap()
    });
    let (date, h, m, s) = if let Some(c) = simple.captures(line) {
        (
            date,
            c[1].parse().ok()?,
            c[2].parse().ok()?,
            c[3].parse().ok()?,
        )
    } else if let Some(c) = forge.captures(line) {
        let months = [
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
        ];
        let month = months
            .iter()
            .position(|m| *m == c[2].to_lowercase())
            .map(|i| i as u32 + 1);
        let date = month
            .and_then(|m| NaiveDate::from_ymd_opt(c[3].parse().ok()?, m, c[1].parse().ok()?))
            .unwrap_or(date);
        (
            date,
            c[4].parse().ok()?,
            c[5].parse().ok()?,
            c[6].parse().ok()?,
        )
    } else {
        return None;
    };
    Local
        .from_local_datetime(&date.and_hms_opt(h, m, s)?)
        .earliest()
        .map(|v| v.timestamp_millis())
}

fn parse_log(path: &Path, profile: &Profile) -> io::Result<Option<Session>> {
    let Some(date) = date_for_file(path) else {
        return Ok(None);
    };
    let input = fs::File::open(path)?;
    let input: Box<dyn Read> = if path.extension().is_some_and(|ext| ext == "gz") {
        Box::new(GzDecoder::new(input))
    } else {
        Box::new(input)
    };
    let mut reader = BufReader::new(input);
    static CONNECT: OnceLock<Regex> = OnceLock::new();
    let connect =
        CONNECT.get_or_init(|| Regex::new(r"]: Connecting to ([^\s,]+), \d+\s*$").unwrap());
    let (mut first, mut last, mut server) = (None, None, None);
    let mut buffer = Vec::new();
    loop {
        buffer.clear();
        if reader.read_until(b'\n', &mut buffer)? == 0 {
            break;
        }
        let line = String::from_utf8_lossy(&buffer);
        let Some(time) = line_time(&line, date) else {
            continue;
        };
        first.get_or_insert(time);
        last = Some(time);
        if let Some(c) = connect.captures(&line) {
            server = Some(c[1].to_owned());
        }
    }
    let (Some(start), Some(mut end)) = (first, last) else {
        return Ok(None);
    };
    if end < start {
        end += 86_400_000;
    }
    if end - start < 60_000 {
        return Ok(None);
    }
    Ok(Some(Session {
        instance: if profile.pack || profile.native {
            profile.id.clone()
        } else {
            format!("aw-build-{}", profile.id)
        },
        version_id: profile.version.clone(),
        loader: profile.loader.clone(),
        start,
        end,
        server,
        pack: profile.pack.then(|| profile.name.clone()),
        build_id: (!profile.pack && !profile.native).then(|| profile.id.clone()),
        build_name: (!profile.pack && !profile.native).then(|| profile.name.clone()),
        launcher_owned: true,
    }))
}

pub fn collect(
    history: &Path,
    sidecar: &Path,
    index_path: &Path,
    profiles: &[Profile],
) -> io::Result<Vec<Session>> {
    let cache_dir = sidecar
        .parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Missing cache directory"))?;
    fs::create_dir_all(cache_dir)?;
    let lock = fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .open(cache_dir.join("rust-ui-activity.lock"))?;
    lock.try_lock()
        .map_err(|error| io::Error::other(format!("Activity cache is locked: {error}")))?;
    let mut index: HashMap<String, CachedLog> = match fs::read(index_path) {
        Ok(bytes) => serde_json::from_slice(&bytes)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?,
        Err(error) if error.kind() == io::ErrorKind::NotFound => HashMap::new(),
        Err(error) => return Err(error),
    };
    let mut seen = HashSet::new();
    let known: HashSet<String> = profiles
        .iter()
        .map(|p| {
            if p.pack || p.native {
                p.id.clone()
            } else {
                format!("aw-build-{}", p.id)
            }
        })
        .collect();
    let mut sessions = HashMap::<(String, i64), Session>::new();
    for source in [sidecar, history] {
        match fs::read(source) {
            Ok(bytes) => match serde_json::from_slice::<Vec<Session>>(&bytes) {
                Ok(old) => {
                    for item in old.into_iter().filter(|s| {
                        s.launcher_owned
                            || s.build_id.is_some()
                            || s.pack.is_some()
                            || known.contains(&s.instance)
                    }) {
                        let key = (item.instance.clone(), item.start);
                        if let Some(previous) = sessions.get(&key) {
                            let mut merged = item;
                            merged.end = merged.end.max(previous.end);
                            if merged.server.is_none() {
                                merged.server = previous.server.clone();
                            }
                            sessions.insert(key, merged);
                        } else {
                            sessions.insert(key, item);
                        }
                    }
                }
                Err(error) if source == sidecar => {
                    return Err(io::Error::new(io::ErrorKind::InvalidData, error))
                }
                Err(_) => {}
            },
            Err(error) if source == sidecar && error.kind() != io::ErrorKind::NotFound => {
                return Err(error)
            }
            Err(_) => {}
        }
    }
    for profile in profiles {
        let Ok(files) = fs::read_dir(profile.dir.join("logs")) else {
            continue;
        };
        for entry in files.flatten() {
            let path = entry.path();
            if !entry
                .file_type()
                .map(|t| t.is_file() && !t.is_symlink())
                .unwrap_or(false)
            {
                continue;
            }
            if date_for_file(&path).is_none() {
                continue;
            }
            let Ok(metadata) = entry.metadata() else {
                continue;
            };
            let modified = metadata
                .modified()
                .ok()
                .and_then(|time| time.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|value| value.as_millis())
                .unwrap_or(0);
            let key = path.to_string_lossy().into_owned();
            seen.insert(key.clone());
            let new = if let Some(cached) = index
                .get(&key)
                .filter(|cached| cached.bytes == metadata.len() && cached.modified == modified)
            {
                cached.session.clone().map(|mut session| {
                    session.version_id = profile.version.clone();
                    session.loader = profile.loader.clone();
                    if !profile.pack && !profile.native {
                        session.build_id = Some(profile.id.clone());
                        session.build_name = Some(profile.name.clone());
                    }
                    session
                })
            } else {
                let Ok(parsed) = parse_log(&path, profile) else {
                    continue;
                };
                index.insert(
                    key,
                    CachedLog {
                        bytes: metadata.len(),
                        modified,
                        session: parsed.clone(),
                    },
                );
                parsed
            };
            let Some(new) = new else {
                continue;
            };
            let key = (new.instance.clone(), new.start);
            if let Some(old) = sessions.get_mut(&key) {
                old.end = old.end.max(new.end);
                old.server = new.server.or_else(|| old.server.clone());
                old.version_id = new.version_id;
                old.loader = new.loader;
                old.build_id = new.build_id.or_else(|| old.build_id.clone());
                old.build_name = new.build_name.or_else(|| old.build_name.clone());
                old.launcher_owned = true;
            } else {
                sessions.insert(key, new);
            }
        }
    }
    let mut result: Vec<_> = sessions.into_values().collect();
    result.sort_by_key(|s| s.start);
    let bytes = serde_json::to_vec(&result).map_err(io::Error::other)?;
    crate::library::atomic_write(sidecar, &bytes)?;
    index.retain(|key, _| seen.contains(key));
    let index_bytes = serde_json::to_vec(&index).map_err(io::Error::other)?;
    crate::library::atomic_write(index_path, &index_bytes)?;
    Ok(result)
}

pub fn stats(sessions: &[Session], profiles: &[Profile], today: NaiveDate) -> Stats {
    let mut result = Stats::default();
    let mut versions = HashMap::<String, (String, i64)>::new();
    let mut servers = HashMap::<String, i64>::new();
    for session in sessions {
        let mut from = session.start;
        while from < session.end {
            let Some(date) = Local
                .timestamp_millis_opt(from)
                .single()
                .map(|v| v.date_naive())
            else {
                break;
            };
            let Some(midnight) = date
                .succ_opt()
                .and_then(|next| next.and_hms_opt(0, 0, 0))
                .and_then(|t| Local.from_local_datetime(&t).earliest())
                .map(|v| v.timestamp_millis())
            else {
                break;
            };
            let until = session.end.min(midnight);
            if until <= from {
                break;
            }
            *result.days.entry(date).or_insert(0) += until - from;
            from = until;
        }
        let duration = session.end.saturating_sub(session.start).max(0);
        result.total_ms = result.total_ms.saturating_add(duration);
        result.longest_ms = result.longest_ms.max(duration);
        let label = session
            .build_name
            .clone()
            .or_else(|| session.pack.clone())
            .unwrap_or_else(|| {
                if session.loader == "VANILLA" {
                    session.version_id.clone()
                } else {
                    format!(
                        "{} {}",
                        session.version_id,
                        match session.loader.as_str() {
                            "FABRIC" => "Fabric",
                            "FORGE" => "Forge",
                            "NEOFORGE" => "NeoForge",
                            "QUILT" => "Quilt",
                            _ => session.loader.as_str(),
                        }
                    )
                }
            });
        let entry = versions.entry(label).or_insert((session.loader.clone(), 0));
        entry.1 = entry.1.saturating_add(duration);
        if let Some(server) = &session.server {
            let entry = servers.entry(server.to_lowercase()).or_default();
            *entry = entry.saturating_add(duration);
        }
    }
    for profile in profiles.iter().filter(|p| !p.pack && !p.native) {
        let historical: i64 = sessions
            .iter()
            .filter(|s| s.build_id.as_deref() == Some(profile.id.as_str()))
            .map(|s| (s.end.min(profile.imported_at.unwrap_or(0)) - s.start).max(0))
            .sum();
        let extra = (profile.imported_ms - historical).max(0);
        result.imported_ms += extra;
        if extra > 0 {
            let entry = versions
                .entry(profile.name.clone())
                .or_insert((profile.loader.clone(), 0));
            entry.1 = entry.1.saturating_add(extra);
        }
    }
    result.total_ms += result.imported_ms;
    let monday = today - Duration::days(today.weekday().num_days_from_monday() as i64);
    for (day, ms) in &result.days {
        if *day >= monday && *day <= today {
            result.week_ms += ms;
        }
        if *day >= monday - Duration::days(7) && *day < monday {
            result.last_week_ms += ms;
        }
    }
    let played: HashSet<_> = result
        .days
        .iter()
        .filter(|(_, m)| **m > 0)
        .map(|(d, _)| *d)
        .collect();
    let mut cursor = if played.contains(&today) {
        today
    } else {
        today - Duration::days(1)
    };
    while played.contains(&cursor) {
        result.streak += 1;
        cursor -= Duration::days(1);
    }
    let (mut previous, mut run) = (None, 0);
    for day in result.days.keys().filter(|day| played.contains(day)) {
        run = if previous == Some(*day - Duration::days(1)) {
            run + 1
        } else {
            1
        };
        result.best_streak = result.best_streak.max(run);
        previous = Some(*day);
    }
    let mut recent = sessions.to_vec();
    recent.sort_by_key(|s| std::cmp::Reverse(s.start));
    result.recent = recent.into_iter().take(12).collect();
    result.versions = versions
        .into_iter()
        .map(|(label, (loader, time))| (label, loader, time))
        .collect();
    result
        .versions
        .sort_by(|a, b| b.2.cmp(&a.2).then_with(|| a.0.cmp(&b.0)));
    result.versions.truncate(4);
    result.servers = servers.into_iter().collect();
    result
        .servers
        .sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    result.servers.truncate(4);
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parse_time_and_sessions() {
        let date = NaiveDate::from_ymd_opt(2026, 10, 30).unwrap();
        assert_eq!(
            line_time("[10:20:30] [main/INFO]", date).unwrap(),
            Local
                .from_local_datetime(&date.and_hms_opt(10, 20, 30).unwrap())
                .earliest()
                .unwrap()
                .timestamp_millis()
        );
        assert!(line_time("invalid", date).is_none());
    }
    #[test]
    fn stats_include_week_and_imports() {
        let today = Local::now().date_naive();
        let start = Local
            .from_local_datetime(&today.and_hms_opt(12, 0, 0).unwrap())
            .earliest()
            .unwrap()
            .timestamp_millis();
        let session = Session {
            instance: "x".into(),
            version_id: "1.21".into(),
            loader: "FABRIC".into(),
            start,
            end: start + 3_600_000,
            server: None,
            pack: None,
            build_id: Some("b".into()),
            build_name: None,
            launcher_owned: true,
        };
        let profile = Profile {
            id: "b".into(),
            name: "test".into(),
            version: "1.21".into(),
            loader: "FABRIC".into(),
            dir: PathBuf::new(),
            pack: false,
            native: false,
            imported_ms: 2_000,
            imported_at: None,
        };
        let stats = stats(&[session], &[profile], today);
        assert_eq!(stats.week_ms, 3_600_000);
        assert_eq!(stats.total_ms, 3_602_000);
        assert_eq!(stats.streak, 1);
    }

    #[test]
    fn rankings_merge_servers_and_imported_time_without_double_counting() {
        let today = Local::now().date_naive();
        let start = Local
            .from_local_datetime(&today.and_hms_opt(10, 0, 0).unwrap())
            .earliest()
            .unwrap()
            .timestamp_millis();
        let first = Session {
            instance: "aw-build-b".into(),
            version_id: "1.21".into(),
            loader: "FABRIC".into(),
            start,
            end: start + 3_600_000,
            server: Some("PLAY.Example.org".into()),
            pack: None,
            build_id: Some("b".into()),
            build_name: Some("Survival".into()),
            launcher_owned: true,
        };
        let second = Session {
            start: start + 7_200_000,
            end: start + 9_000_000,
            server: Some("play.example.org".into()),
            ..first.clone()
        };
        let profile = Profile {
            id: "b".into(),
            name: "Survival".into(),
            version: "1.21".into(),
            loader: "FABRIC".into(),
            dir: PathBuf::new(),
            pack: false,
            native: false,
            imported_ms: 7_200_000,
            imported_at: Some(first.end),
        };
        let result = stats(&[second.clone(), first.clone()], &[profile], today);
        assert_eq!(result.imported_ms, 3_600_000);
        assert_eq!(result.total_ms, 9_000_000);
        assert_eq!(
            result.versions,
            vec![("Survival".into(), "FABRIC".into(), 9_000_000)]
        );
        assert_eq!(result.servers, vec![("play.example.org".into(), 5_400_000)]);
        assert_eq!(result.recent[0].start, second.start);
    }

    #[test]
    fn session_crossing_midnight_is_split_by_local_day() {
        let day = Local::now().date_naive() - Duration::days(2);
        let next = day.succ_opt().unwrap();
        let start = Local
            .from_local_datetime(&day.and_hms_opt(23, 30, 0).unwrap())
            .earliest()
            .unwrap()
            .timestamp_millis();
        let end = Local
            .from_local_datetime(&next.and_hms_opt(0, 30, 0).unwrap())
            .earliest()
            .unwrap()
            .timestamp_millis();
        let session = Session {
            instance: "x".into(),
            version_id: "1.21".into(),
            loader: "VANILLA".into(),
            start,
            end,
            server: None,
            pack: None,
            build_id: None,
            build_name: None,
            launcher_owned: true,
        };
        let result = stats(&[session], &[], next);
        assert_eq!(result.days.get(&day), Some(&1_800_000));
        assert_eq!(result.days.get(&next), Some(&1_800_000));
        assert_eq!(result.streak, 2);
    }

    #[test]
    fn rotated_logs_are_preserved_without_rewriting_original_history() {
        use std::io::Write;
        let root = std::env::temp_dir().join(format!("aw-ui-activity-test-{}", std::process::id()));
        let profile_dir = root.join("build");
        fs::create_dir_all(profile_dir.join("logs")).unwrap();
        let archive = profile_dir.join("logs/2026-09-25-1.log.gz");
        let mut gzip = flate2::write::GzEncoder::new(
            fs::File::create(&archive).unwrap(),
            flate2::Compression::default(),
        );
        gzip.write_all(b"[10:00:00] [main/INFO]: start\n[10:40:00] [main/INFO]: stop\n")
            .unwrap();
        gzip.finish().unwrap();
        let profile = Profile {
            id: "id".into(),
            name: "Build".into(),
            version: "1.21".into(),
            loader: "FABRIC".into(),
            dir: profile_dir,
            pack: false,
            native: false,
            imported_ms: 0,
            imported_at: None,
        };
        let history = root.join("activity.json");
        let sidecar = root.join("cache/rust-ui-activity.json");
        fs::write(&history, "[]").unwrap();
        let index = root.join("cache/index.json");
        let first = collect(&history, &sidecar, &index, &[profile.clone()]).unwrap();
        assert_eq!(first.len(), 1);
        assert_eq!(first[0].end - first[0].start, 40 * 60_000);
        fs::remove_file(archive).unwrap();
        let retained = collect(&history, &sidecar, &index, &[profile]).unwrap();
        assert_eq!(retained.len(), 1);
        assert_eq!(fs::read_to_string(history).unwrap(), "[]");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn damaged_sidecar_is_not_overwritten() {
        let root =
            std::env::temp_dir().join(format!("aw-ui-bad-activity-test-{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let sidecar = root.join("history.json");
        fs::write(&sidecar, "not json").unwrap();
        assert!(collect(
            &root.join("missing.json"),
            &sidecar,
            &root.join("index.json"),
            &[]
        )
        .is_err());
        assert_eq!(fs::read_to_string(&sidecar).unwrap(), "not json");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn concurrent_cache_write_is_rejected() {
        let root = std::env::temp_dir().join(format!("aw-ui-lock-test-{}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let lock = fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .open(root.join("rust-ui-activity.lock"))
            .unwrap();
        lock.try_lock().unwrap();
        assert!(collect(
            &root.join("original.json"),
            &root.join("sidecar.json"),
            &root.join("index.json"),
            &[]
        )
        .is_err());
        assert!(!root.join("sidecar.json").exists());
        drop(lock);
        fs::remove_dir_all(root).unwrap();
    }
}
