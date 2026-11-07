use aw_engine::{builds, content, history, read_json, storage, write_bytes, write_json, Context};
use serde_json::{json, Value};
use std::{
    fs,
    path::PathBuf,
    sync::{atomic::AtomicBool, Arc},
};
struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!("aw-services-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&p).unwrap();
        Self(p)
    }
    fn cx(&self) -> Context<'_> {
        Context {
            root: self.0.clone(),
            cancelled: Arc::new(AtomicBool::new(false)),
            event: &|_| {},
        }
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn profile(dir: &std::path::Path) -> Value {
    json!({"dir":dir,"version":"1.21.1","loader":"Vanilla","loaderVersion":null})
}
#[test]
fn local_content_list_is_available_without_catalog_or_archive_parsing() {
    let tmp = Temp::new();
    let dir = tmp.0.join("game");
    write_bytes(&dir.join("mods/example.jar.disabled"), b"not a zip").unwrap();
    write_json(&dir.join("aw-instance.json"), &json!({"catalogMods":[{
        "fileName":"mods/example.jar", "projectId":"project-1", "title":"Example",
        "versionNumber":"1.2", "iconUrl":"https://cdn.example/icon.png"
    }]})).unwrap();
    let items = content::scan_local(&tmp.cx(), &profile(&dir)).unwrap();
    assert_eq!(items.len(), 1);
    assert_eq!(items[0]["title"], "Example");
    assert_eq!(items[0]["project"], "project-1");
    assert_eq!(items[0]["icon"], "https://cdn.example/icon.png");
    assert_eq!(items[0]["enabled"], false);
    assert_eq!(items[0]["update"], "");
}
#[test]
fn state_preserves_legacy_directories_and_never_exposes_secrets() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let custom = tmp.0.join("custom");
    write_json(
        &tmp.0.join("launcher.json"),
        &json!({"customGameDir":custom,"language":"RU"}),
    )
    .unwrap();
    let id = uuid::Uuid::new_v4().to_string();
    let legacy = custom.join("builds").join(format!("aw-build-{id}"));
    fs::create_dir_all(&legacy).unwrap();
    write_json(&tmp.0.join("local-builds.json"),&json!([{"id":id,"name":"Моя сборка","versionId":"1.21.1","loader":"VANILLA","directoryName":"Новое имя","importedPlayTimeMillis":2000,"importedAt":2000}])).unwrap();
    write_json(
        &tmp.0.join("activity.json"),
        &json!([{"buildId":id,"start":1000,"end":1500},{"buildId":id,"start":3000,"end":4000}]),
    )
    .unwrap();
    write_json(&tmp.0.join("accounts.json"),&json!({"selectedUuid":"one","accounts":[{"uuid":"one","name":"Player","type":"MICROSOFT","accessToken":"protected-sensitive","refreshToken":"also-sensitive"}]})).unwrap();
    let state = builds::state(&cx).unwrap();
    assert_eq!(state["builds"][0]["dir"], json!(legacy));
    assert_eq!(state["builds"][0]["playTime"], 3000);
    assert_eq!(state["settings"]["language"], "RU");
    assert!(!state.to_string().contains("sensitive"));
}
#[test]
fn overlay_rolls_back_when_metadata_commit_fails() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let source = tmp.0.join("stage");
    let target = tmp.0.join("game");
    write_bytes(&source.join("mods/a.jar"), b"new").unwrap();
    write_bytes(&target.join("mods/a.jar"), b"old").unwrap();
    write_bytes(&target.join("config/keep.txt"), b"user").unwrap();
    assert!(
        storage::overlay(&cx, &source, &target, &[], || Err("metadata failed".into())).is_err()
    );
    assert_eq!(fs::read(target.join("mods/a.jar")).unwrap(), b"old");
    assert_eq!(fs::read(target.join("config/keep.txt")).unwrap(), b"user");
}
#[test]
fn snapshot_restores_content_and_datapacks_without_reverting_world_chunks() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let d = tmp.0.join("game");
    write_bytes(&d.join("mods/a.jar"), b"old").unwrap();
    write_bytes(&d.join("saves/World/region/chunks.dat"), b"world-before").unwrap();
    write_bytes(&d.join("saves/World/datapacks/a.zip"), b"datapack-before").unwrap();
    let p = profile(&d);
    let entry = history::create(&cx, &p, "Snapshot", true).unwrap();
    fs::write(d.join("mods/a.jar"), b"new").unwrap();
    fs::write(d.join("saves/World/region/chunks.dat"), b"world-after").unwrap();
    fs::write(d.join("saves/World/datapacks/a.zip"), b"datapack-after").unwrap();
    write_bytes(&d.join("config/new.txt"), b"added").unwrap();
    history::restore(&cx, &p, entry["id"].as_str().unwrap(), false, |_| Ok(())).unwrap();
    assert_eq!(fs::read(d.join("mods/a.jar")).unwrap(), b"old");
    assert_eq!(
        fs::read(d.join("saves/World/datapacks/a.zip")).unwrap(),
        b"datapack-before"
    );
    assert_eq!(
        fs::read(d.join("saves/World/region/chunks.dat")).unwrap(),
        b"world-after"
    );
    assert!(!d.join("config/new.txt").exists());
}
#[test]
fn damaged_snapshot_is_rejected_before_any_game_files_change() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let d = tmp.0.join("game");
    write_bytes(&d.join("mods/a.jar"), b"before").unwrap();
    let p = profile(&d);
    let entry = history::create(&cx, &p, "Snapshot", true).unwrap();
    let id = entry["id"].as_str().unwrap();
    fs::write(
        d.join("aw-build-history").join(id).join("data/mods/a.jar"),
        b"tampered",
    )
    .unwrap();
    fs::write(d.join("mods/a.jar"), b"current").unwrap();
    assert!(history::restore(&cx, &p, id, false, |_| Ok(())).is_err());
    assert_eq!(fs::read(d.join("mods/a.jar")).unwrap(), b"current");
}
#[test]
fn snapshot_cannot_smuggle_worlds_through_case_alias() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let d = tmp.0.join("game");
    fs::create_dir_all(&d).unwrap();
    let p = profile(&d);
    let mut entry = history::create(&cx, &p, "Snapshot", true).unwrap();
    let id = entry["id"].as_str().unwrap().to_owned();
    entry["roots"] = json!(["SAVES"]);
    entry["includesWorlds"] = json!(false);
    write_json(
        &d.join("aw-build-history").join(&id).join("snapshot.json"),
        &entry,
    )
    .unwrap();
    assert!(history::read(&d, &id).is_err());
}
#[test]
fn restore_rolls_back_when_version_commit_fails() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let d = tmp.0.join("game");
    write_bytes(&d.join("mods/a.jar"), b"old").unwrap();
    let p = profile(&d);
    let entry = history::create(&cx, &p, "Snapshot", true).unwrap();
    fs::write(d.join("mods/a.jar"), b"current").unwrap();
    assert!(
        history::restore(&cx, &p, entry["id"].as_str().unwrap(), false, |_| Err(
            "version commit failed".into()
        ))
        .is_err()
    );
    assert_eq!(fs::read(d.join("mods/a.jar")).unwrap(), b"current");
}
#[test]
fn mod_toggle_can_reenable_existing_disabled_files() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let d = tmp.0.join("game");
    write_bytes(&d.join("mods/a.jar.disabled"), b"jar").unwrap();
    let p = profile(&d);
    let reply=content::toggle(&cx,&p,&json!({"op":"toggle","file":"mods/a.jar.disabled","enabled":true,"options":{"returnItem":true}})).unwrap();
    assert_eq!(reply["file"], "mods/a.jar");
    assert!(d.join("mods/a.jar").exists());
    assert!(!d.join("mods/a.jar.disabled").exists());
    assert!(!history::list(&d).unwrap().as_array().unwrap().is_empty());
}
#[test]
fn datapack_toggle_reenables_a_disabled_zip_in_the_active_folder() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let d = tmp.0.join("game");
    write_bytes(&d.join("saves/World/level.dat"), b"level").unwrap();
    write_bytes(&d.join("saves/World/datapacks/a.zip.disabled"), b"zip").unwrap();
    let p = profile(&d);
    let result=content::toggle(&cx,&p,&json!({"op":"toggle","file":"saves/World/datapacks/a.zip.disabled","enabled":true,"options":{"returnItem":true}})).unwrap();
    assert_eq!(result["file"], "saves/World/datapacks/a.zip");
    assert!(d.join("saves/World/datapacks/a.zip").exists());
}
#[test]
fn storage_rejects_alias_and_parent_paths() {
    let tmp = Temp::new();
    for name in [
        "../outside",
        "mods/../file",
        "mods/./file",
        "mods/NUL.jar",
        "mods/name.",
        "saves//World",
    ] {
        assert!(storage::path(&tmp.0, name).is_err(), "{name}");
    }
}
#[test]
fn empty_native_startup_does_not_create_or_rewrite_accounts() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let state = aw_engine::ops::execute(&cx, &json!({"op":"launcherState"})).unwrap();
    assert!(state["builds"].as_array().unwrap().is_empty());
    assert!(!tmp.0.join("accounts.json").exists());
    assert!(!tmp.0.join("local-builds.json").exists());
}
#[test]
fn migration_commits_version_and_can_be_restored_without_network() {
    let tmp = Temp::new();
    let cx = tmp.cx();
    let b = builds::create(&cx, "Migration fixture", "1.21", "VANILLA", None).unwrap();
    let key = format!("build:{}", b["id"].as_str().unwrap());
    let d = builds::directory(&cx, &b).unwrap();
    write_json(&d.join("aw-instance.json"), &json!({"favorite":true})).unwrap();
    write_bytes(&d.join("mods/local.jar.disabled"), b"disabled").unwrap();
    write_bytes(&d.join("saves/World/region/chunks.dat"), b"world").unwrap();
    let runtime = tmp.0.join("runtimes/fixture-runtime");
    write_bytes(
        &runtime.join(if cfg!(windows) {
            "bin/java.exe"
        } else {
            "bin/java"
        }),
        b"",
    )
    .unwrap();
    write_bytes(&runtime.join("release"), b"JAVA_VERSION=\"21.0.0\"\n").unwrap();
    write_bytes(&runtime.join(".aw-runtime"), b"fixture").unwrap();
    let jar = tmp.0.join("versions/1.21.1/1.21.1.jar");
    write_bytes(&jar, b"jar").unwrap();
    let hash = storage::hash(&jar).unwrap();
    write_json(&tmp.0.join("versions/1.21.1/1.21.1.json"),&json!({"id":"1.21.1","mainClass":"Unused","javaVersion":{"majorVersion":21,"component":"fixture-runtime"},"downloads":{"client":{"url":"https://unused.invalid/client.jar","size":3,"sha1":hash}},"libraries":[]})).unwrap();
    let (mut fingerprint, _) = storage::inventory(
        &d,
        &[
            "mods".into(),
            "aw-instance.json".into(),
            "aw-pack.json".into(),
        ],
    )
    .unwrap();
    fingerprint.insert("mods/".into(), "present".into());
    let id = uuid::Uuid::new_v4().to_string();
    write_json(&tmp.0.join("cache/native-migrations").join(format!("{id}.json")),&json!({"key":key,"source":"1.21","target":"1.21.1","loader":"VANILLA","mods":[],"fingerprint":fingerprint})).unwrap();
    let before = builds::target(&cx, &key).unwrap();
    let result = aw_engine::migration::execute(
        &cx,
        &before,
        &json!({"op":"buildMigrationApply","target":key,"snapshot":id,"options":{"keep":[]}}),
    )
    .unwrap();
    assert!(result.as_str().unwrap().contains("1.21.1"));
    let current = builds::target(&cx, &key).unwrap();
    assert_eq!(current["version"], "1.21.1");
    assert_eq!(
        fs::read(d.join("saves/World/region/chunks.dat")).unwrap(),
        b"world"
    );
    let entries = history::list(&d).unwrap();
    let snapshot = &entries[0];
    assert_eq!(snapshot["targetGameVersion"], "1.21.1");
    history::restore(
        &cx,
        &current,
        snapshot["id"].as_str().unwrap(),
        false,
        |entry| {
            builds::change_version(
                &cx,
                &key,
                "1.21.1",
                entry["gameVersion"].as_str().unwrap(),
                None,
            )
        },
    )
    .unwrap();
    assert_eq!(builds::target(&cx, &key).unwrap()["version"], "1.21");
    assert_eq!(
        read_json(&d.join("aw-instance.json")).unwrap(),
        json!({"favorite":true})
    );
}
