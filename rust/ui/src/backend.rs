use std::{ffi::OsString, path::Path, process::Command};

fn packaged_classpath(app: &Path, config: &str) -> Result<OsString, String> {
    let mut entries = Vec::new();
    let mut application = false;
    for line in config.lines().map(str::trim) {
        if line.starts_with('[') { application = line == "[Application]"; }
        if !application { continue; }
        if let Some(value) = line.strip_prefix("app.classpath=") {
            let expanded = value.replace("$APPDIR", &app.to_string_lossy());
            for path in std::env::split_paths(&expanded) {
                if !path.is_file() { return Err(format!("Не найден файл установленного лаунчера: {}", path.display())); }
                entries.push(path);
            }
        }
    }
    if entries.is_empty() { return Err("В конфигурации лаунчера отсутствует список файлов движка".into()); }
    entries.push(app.to_path_buf());
    std::env::join_paths(entries).map_err(|e| e.to_string())
}

pub fn java_base_command(backend: &Path) -> Result<Command, String> {
    let parent = backend.parent().ok_or("Не найден движок лаунчера")?;
    for root in [
        parent.to_path_buf(),
        parent.parent().unwrap_or(parent).to_path_buf(),
    ] {
        for (java, app) in [
            (root.join("runtime/bin/java.exe"), root.join("app")),
            (root.join("lib/runtime/bin/java"), root.join("lib/app")),
        ] {
            if java.is_file() && app.join("native-ui-protocol.txt").is_file() {
                let config = std::fs::read_to_string(app.join("AWLauncher.cfg"))
                    .map_err(|e| format!("Не удалось прочитать конфигурацию установленного лаунчера: {e}"))?;
                let classpath = packaged_classpath(&app, &config)?;
                let mut command = Command::new(java);
                command
                    .arg("-Xms16m")
                    .arg("-Xmx512m")
                    .arg("-Dfile.encoding=UTF-8")
                    .arg(format!("-Djpackage.app-path={}", backend.display()))
                    .arg("-Daw.native.backend=true")
                    .arg(format!("-Daw.native.pid={}", std::process::id()))
                    .arg(format!("-Dskiko.library.path={}", app.display()))
                    .arg("-cp")
                    .arg(classpath);
                if let Some(home) = crate::home_dir() {
                    command.env("AW_NATIVE_DATA_HOME", home);
                }
                #[cfg(windows)]
                {
                    use std::os::windows::process::CommandExt;
                    command.creation_flags(0x08000000);
                }
                return Ok(command);
            }
        }
    }
    Err("Для этой операции нужна встроенная сборка AWLauncher 1.0.50. Открой лаунчер из новой папки приложения".into())
}

pub fn backend_command(backend: &Path) -> Result<Command, String> {
    let mut command = java_base_command(backend)?;
    command.arg("ru.aw.launcher.MainKt").arg("--native-task");
    Ok(command)
}

pub fn process_alive(pid: u32) -> bool {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::{
            Foundation::CloseHandle,
            System::Threading::{
                GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
            },
        };
        let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if handle.is_null() {
            return false;
        }
        let mut code = 0;
        let success = GetExitCodeProcess(handle, &mut code);
        CloseHandle(handle);
        success != 0 && code == 259
    }
    #[cfg(not(windows))]
    {
        std::path::Path::new(&format!("/proc/{pid}")).exists()
    }
}

#[cfg(test)]
mod backend_classpath_tests {
    use super::*;
    #[test]
    fn uses_packaged_entries_even_when_an_older_jar_remains() {
        let root = std::env::temp_dir().join(format!("aw-classpath-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir(&root).unwrap();
        for name in ["AWLauncher-1.0.52.jar", "AWLauncher-1.0.54.jar", "dependency.jar"] { std::fs::write(root.join(name), b"").unwrap(); }
        let config = "[Application]\napp.classpath=$APPDIR/AWLauncher-1.0.54.jar\napp.classpath=$APPDIR/dependency.jar\n[JavaOptions]\njava-options=-Xmx512m\n";
        let value = packaged_classpath(&root, config).unwrap();
        let entries: Vec<_> = std::env::split_paths(&value).collect();
        assert_eq!(entries, vec![root.join("AWLauncher-1.0.54.jar"), root.join("dependency.jar"), root.clone()]);
        assert!(packaged_classpath(&root, "[Application]\napp.classpath=$APPDIR/missing.jar").is_err());
        assert!(packaged_classpath(&root, "[Application]\napp.mainclass=Main").is_err());
        std::fs::remove_dir_all(root).unwrap();
    }
}
