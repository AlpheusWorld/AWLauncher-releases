use aw_engine::{accounts, game, write_json, Context};
use serde_json::json;
use std::{
    fs,
    path::PathBuf,
    process::Command,
    sync::{atomic::AtomicBool, Arc},
    time::{Duration, Instant},
};
struct Temp(PathBuf);
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
#[test]
#[ignore = "requires AW_ENGINE_TEST_JDK pointing to a JDK; starts an isolated fixture JVM"]
fn launch_uses_native_arguments_and_existing_data() {
    let jdk = PathBuf::from(std::env::var_os("AW_ENGINE_TEST_JDK").expect("AW_ENGINE_TEST_JDK"));
    let temp = Temp(std::env::temp_dir().join(format!("aw-engine-test-{}", uuid::Uuid::new_v4())));
    fs::create_dir_all(&temp.0).unwrap();
    let root = temp.0.join("data");
    let dir = temp.0.join("game with spaces");
    fs::create_dir_all(&dir).unwrap();
    let source = temp.0.join("Game.java");
    fs::write(
        &source,
        r#"import java.nio.file.*;
public class Game {
    public static void main(String[] args) throws Exception {
        Path pending = Path.of("captured.tmp");
        Files.writeString(pending, System.getProperty("fixture.test") + "\n" + String.join("\n", args));
        Files.move(pending, Path.of("captured.txt"), StandardCopyOption.ATOMIC_MOVE);
    }
}"#,
    ).unwrap();
    let tool = |name: &str| {
        jdk.join("bin").join(if cfg!(windows) {
            format!("{name}.exe")
        } else {
            name.into()
        })
    };
    assert!(Command::new(tool("javac"))
        .arg(&source)
        .status()
        .unwrap()
        .success());
    let jar = root.join("versions/fixture/fixture.jar");
    fs::create_dir_all(jar.parent().unwrap()).unwrap();
    assert!(Command::new(tool("jar"))
        .arg("cf")
        .arg(&jar)
        .arg("-C")
        .arg(&temp.0)
        .arg("Game.class")
        .status()
        .unwrap()
        .success());
    let event = |_| {};
    let cx = Context {
        root: root.clone(),
        cancelled: Arc::new(AtomicBool::new(false)),
        event: &event,
    };
    accounts::handle(&cx, &json!({"op":"accountOffline","title":"Notch"})).unwrap();
    assert_eq!(
        accounts::selected(&cx).unwrap()["uuid"],
        "b50ad385829d3141a2167e7d7539ba7f"
    );
    write_json(&dir.join("aw-instance.json"),&json!({"javaPath":tool("java"),"memoryMb":512,"jvmArgs":"-Dfixture.test=\"quoted value\"","windowWidth":1000,"windowHeight":700})).unwrap();
    write_json(&root.join("versions/fixture/fixture.json"),&json!({"id":"fixture","type":"release","mainClass":"Game","javaVersion":{"majorVersion":8},"downloads":{"client":{"url":"https://unused.invalid/game.jar","size":fs::metadata(&jar).unwrap().len()}},"arguments":{"game":["${auth_player_name}","${auth_uuid}","${auth_access_token}","${game_directory}"]},"libraries":[]})).unwrap();
    let result = game::launch(
        &cx,
        &json!({"version":"fixture","loader":"Vanilla","dir":dir}),
    )
    .unwrap();
    assert!(result["pid"].as_u64().unwrap() > 0);
    let deadline = Instant::now() + Duration::from_secs(15);
    let captured = dir.join("captured.txt");
    while !captured.exists() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(50));
    }
    let text = fs::read_to_string(captured).unwrap_or_else(|error| {
        panic!(
            "Java did not write the launch arguments: {error}\n{}",
            fs::read_to_string(dir.join("latest-game.log")).unwrap_or_default()
        )
    });
    assert!(
        text.starts_with("quoted value\nNotch\nb50ad385829d3141a2167e7d7539ba7f\n0\n"),
        "Unexpected Java launch arguments: {text:?}"
    );
    assert!(text.contains("--width\n1000\n--height\n700"));
}
#[test]
fn corrupt_accounts_are_not_replaced() {
    let temp = Temp(std::env::temp_dir().join(format!("aw-engine-test-{}", uuid::Uuid::new_v4())));
    fs::create_dir_all(&temp.0).unwrap();
    fs::write(temp.0.join("accounts.json"), b"broken").unwrap();
    let cx = Context {
        root: temp.0.clone(),
        cancelled: Arc::new(AtomicBool::new(false)),
        event: &|_| {},
    };
    assert!(accounts::handle(&cx, &json!({"op":"accountOffline","title":"Player"})).is_err());
    assert_eq!(fs::read(temp.0.join("accounts.json")).unwrap(), b"broken");
}
