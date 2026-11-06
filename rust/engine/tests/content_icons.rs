use aw_engine::{content, write_json, Context};
use serde_json::json;
use std::{
    fs,
    path::PathBuf,
    sync::{atomic::AtomicBool, Arc},
};

struct Fixture(PathBuf);
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[test]
#[ignore = "Uses the public Modrinth catalog"]
fn installed_content_without_cached_icon_loads_project_icon() {
    let fixture =
        Fixture(std::env::temp_dir().join(format!("aw-content-icon-{}", uuid::Uuid::new_v4())));
    let dir = fixture.0.join("profile");
    fs::create_dir_all(dir.join("mods")).unwrap();
    fs::write(dir.join("mods/fixture.jar"), b"fixture").unwrap();
    write_json(
        &dir.join("aw-instance.json"),
        &json!({"catalogMods":[
            {"fileName":"mods/fixture.jar","projectId":"P7dR8mSH","title":"Fabric API"}
        ]}),
    )
    .unwrap();
    let context = Context {
        root: fixture.0.clone(),
        cancelled: Arc::new(AtomicBool::new(false)),
        event: &|_| {},
    };
    let items = content::scan_display(
        &context,
        &json!({"dir":dir,"version":"1.21.1","loader":"Fabric"}),
    )
    .unwrap();
    assert_eq!(items.len(), 1);
    assert!(
        items[0]["icon"]
            .as_str()
            .is_some_and(|url| url.starts_with("https://cdn.modrinth.com/")),
        "{items:?}"
    );
}
