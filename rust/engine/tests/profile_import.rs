use aw_engine::{builds, ops, Context};
use rusqlite::Connection;
use serde_json::json;
use std::{
    fs,
    path::PathBuf,
    sync::{atomic::AtomicBool, Arc},
};

struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("aw-profile-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&path).unwrap();
        Self(path)
    }
    fn context(&self) -> Context<'_> {
        Context {
            root: self.0.join("aw"),
            cancelled: Arc::new(AtomicBool::new(false)),
            event: &|_| {},
        }
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[test]
fn prism_import_keeps_loader_world_and_time_but_excludes_credentials() {
    let fixture = Fixture::new();
    let cx = fixture.context();
    let profile = fixture.0.join("Prism/instances/Example");
    let game = profile.join(".minecraft");
    fs::create_dir_all(game.join("saves/World")).unwrap();
    fs::write(
        profile.join("instance.cfg"),
        "name=Prism Example\ntotalTimePlayed=120\n",
    )
    .unwrap();
    fs::write(profile.join("mmc-pack.json"), r#"{"components":[{"uid":"net.minecraft","version":"1.21.1"},{"uid":"net.fabricmc.fabric-loader","version":"0.16.14"}]}"#).unwrap();
    fs::write(game.join("saves/World/level.dat"), b"test world").unwrap();
    fs::write(game.join("saves/launcher_accounts.json"), b"private").unwrap();
    assert_eq!(
        ops::execute(&cx, &json!({"op":"importProfile","file":profile})).unwrap(),
        "Профиль импортирован"
    );
    let list = builds::list(&cx).unwrap();
    let build = &list[0];
    assert_eq!(build["versionId"], "1.21.1");
    assert_eq!(build["loader"], "FABRIC");
    assert_eq!(build["loaderVersion"], "0.16.14");
    assert_eq!(build["importedPlayTimeMillis"], 120000);
    let imported = builds::directory(&cx, build).unwrap();
    assert_eq!(
        fs::read(imported.join("saves/World/level.dat")).unwrap(),
        b"test world"
    );
    assert!(!imported.join("saves/launcher_accounts.json").exists());
    assert!(game.join("saves/launcher_accounts.json").is_file());
}

#[test]
fn reads_modrinth_old_and_new_database_layouts() {
    let fixture = Fixture::new();
    let cx = fixture.context();
    for (index, schema) in [
        "CREATE TABLE profiles (id TEXT, name TEXT, game_version TEXT, loader TEXT, loader_version TEXT, last_played INTEGER, submitted_time_played INTEGER, recent_time_played INTEGER); INSERT INTO profiles VALUES ('Example','Original','1.21.1','fabric','0.16.14',1700000000,120,60);",
        "CREATE TABLE instances (name TEXT,path TEXT,icon_path TEXT,last_played INTEGER,submitted_time_played INTEGER,recent_time_played INTEGER,applied_content_set_id INTEGER); CREATE TABLE instance_content_sets (id INTEGER,game_version TEXT,loader TEXT,loader_version TEXT); INSERT INTO instances VALUES ('New','Example',NULL,1700000000,120,60,1); INSERT INTO instance_content_sets VALUES (1,'1.21.1','fabric','0.16.14');",
    ].into_iter().enumerate() {
        let app = fixture.0.join(format!("Modrinth-{index}"));
        fs::create_dir_all(app.join("profiles/Example")).unwrap();
        let db = Connection::open(app.join("app.db")).unwrap();
        db.execute_batch(schema).unwrap();
        drop(db);
        let before = fs::read(app.join("app.db")).unwrap();
        ops::execute(&cx,&json!({"op":"importProfile","file":app})).unwrap();
        assert_eq!(fs::read(app.join("app.db")).unwrap(), before);
        let list=builds::list(&cx).unwrap();
        let build=list.last().unwrap();
        assert_eq!(build["loaderVersion"], "0.16.14");
        assert_eq!(build["lastPlayed"], 1700000000000u64);
        assert_eq!(build["importedPlayTimeMillis"], 180000);
    }
}
