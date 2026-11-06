use std::{path::Path, process::Command};

pub fn backend_command(backend: &Path) -> Result<Command, String> {
    if !backend.is_file() {
        return Err("Исполняемый файл AWLauncher не найден".into());
    }
    let mut command = Command::new(backend);
    command.arg("--native-task");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000);
    }
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
