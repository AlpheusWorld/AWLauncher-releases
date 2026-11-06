//! Explicit network smoke probe, isolated from the user's .aw directory.
use aw_engine::{accounts, game, Context};
use serde_json::json;
use std::{
    path::PathBuf,
    sync::{atomic::AtomicBool, Arc},
};
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() < 3 {
        eprintln!(
            "usage: smoke <absolute-test-root> <minecraft> <loader> [loader-version] [--launch]"
        );
        std::process::exit(2);
    }
    let root = PathBuf::from(&args[0]);
    assert!(root.is_absolute(), "test root must be absolute");
    assert!(
        root.file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .starts_with("aw-smoke"),
        "test root name must start with aw-smoke"
    );
    let event = |value: serde_json::Value| {
        if value["type"] == "stage" {
            println!("{}", value["data"]);
        }
    };
    let cx = Context {
        root: root.clone(),
        cancelled: Arc::new(AtomicBool::new(false)),
        event: &event,
    };
    let game_dir = root.join("games").join(format!("{}-{}", args[1], args[2]));
    if let Ok(java) = std::env::var("AW_ENGINE_TEST_JAVA") {
        aw_engine::write_json(
            &game_dir.join("aw-instance.json"),
            &json!({"javaPath":java,"memoryMb":1024}),
        )
        .unwrap();
    }
    let _locks = aw_engine::operation_locks(&root).unwrap();
    let mut profile =
        json!({"key":"build:smoke","version":args[1],"loader":args[2],"dir":game_dir});
    if let Some(pin) = args.get(3).filter(|s| !s.starts_with("--")) {
        profile["loaderVersion"] = json!(pin);
    }
    let result = if args.iter().any(|s| s == "--launch") {
        accounts::handle(&cx, &json!({"op":"accountOffline","title":"SmokeTest"}))
            .and_then(|_| game::launch(&cx, &profile))
    } else {
        game::install(&cx, &profile)
    };
    match result {
        Ok(value) => println!("SMOKE_RESULT {value}"),
        Err(e) => {
            eprintln!("SMOKE_FAILED {e}");
            std::process::exit(1);
        }
    }
}
