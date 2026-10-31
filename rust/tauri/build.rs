use std::{fs, path::Path};
fn copy_tree(from: &Path, to: &Path) {
    fs::create_dir_all(to).expect("Create frontend assets");
    for entry in fs::read_dir(from).expect("Read frontend assets") {
        let entry = entry.expect("Read asset");
        let target = to.join(entry.file_name());
        if entry.file_type().expect("Asset type").is_dir() {
            copy_tree(&entry.path(), &target);
        } else {
            fs::copy(entry.path(), target).expect("Copy frontend asset");
        }
    }
}
fn main() {
    for path in [
        "web",
        "../ui/assets",
        "../../src/main/resources/fonts",
        "../../src/main/resources/instance-icons",
        "../../branding/AWLauncher-mark.png",
    ] {
        println!("cargo:rerun-if-changed={path}");
    }
    copy_tree(Path::new("web"), Path::new("dist"));
    copy_tree(Path::new("../ui/assets/nav"), Path::new("dist/assets/nav"));
    copy_tree(
        Path::new("../../src/main/resources/instance-icons"),
        Path::new("dist/assets/icons"),
    );
    fs::create_dir_all("dist/assets/fonts").unwrap();
    for weight in ["Regular", "Medium", "SemiBold", "Bold", "Black"] {
        fs::copy(
            format!("../../src/main/resources/fonts/Onest-{weight}.ttf"),
            format!("dist/assets/fonts/Onest-{weight}.ttf"),
        )
        .unwrap();
    }
    fs::copy("../../branding/AWLauncher-mark.png", "dist/assets/mark.png").unwrap();
    fs::copy("../ui/assets/languages.json", "dist/assets/languages.json").unwrap();
    copy_tree(
        Path::new("../../src/main/resources/flags"),
        Path::new("dist/assets/flags"),
    );
    copy_tree(
        Path::new("../../src/main/resources/i18n"),
        Path::new("dist/assets/i18n"),
    );
    tauri_build::build();
}
