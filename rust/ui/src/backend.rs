use std::{path::Path, process::Command};

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
                    .arg(format!(
                        "{}{}{}",
                        app.join("*").display(),
                        if cfg!(windows) { ";" } else { ":" },
                        app.display()
                    ));
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
