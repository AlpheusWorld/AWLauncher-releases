use serde_json::Value;
use std::{
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::{mpsc, Arc, Mutex},
};

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

pub enum Event {
    Data(Value),
    Exited(bool),
}
pub struct Running {
    pub rx: mpsc::Receiver<Event>,
    input: Arc<Mutex<std::process::ChildStdin>>,
}
impl Running {
    pub fn cancel(&self) {
        if let Ok(mut input) = self.input.lock() {
            let _ = input.write_all(b"cancel\n");
            let _ = input.flush();
        }
    }
}
impl Drop for Running {
    fn drop(&mut self) {
        self.cancel();
    }
}
pub fn start(
    backend: &Path,
    request: &Value,
    ctx: eframe::egui::Context,
) -> Result<Running, String> {
    let mut command = backend_command(backend)?;
    command.env(
        "AW_NATIVE_GAMES",
        request["games"].as_u64().unwrap_or(1).to_string(),
    );
    command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    let mut child = command.spawn().map_err(|e| e.to_string())?;
    let mut stdin = child.stdin.take().ok_or("Движок не открыл входной поток")?;
    let bytes = serde_json::to_vec(request).map_err(|e| e.to_string())?;
    stdin
        .write_all(&bytes)
        .and_then(|_| stdin.write_all(b"\n"))
        .and_then(|_| stdin.flush())
        .map_err(|e| e.to_string())?;
    let input = Arc::new(Mutex::new(stdin));
    let stdout = child
        .stdout
        .take()
        .ok_or("Движок не открыл выходной поток")?;
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines() {
            let Ok(line) = line else { break };
            if let Some(json) = line.strip_prefix("AW_RPC ") {
                if let Ok(data) = serde_json::from_str::<Value>(json) {
                    if tx.send(Event::Data(data)).is_err() {
                        break;
                    }
                    ctx.request_repaint();
                }
            }
        }
        let success = child.wait().map(|s| s.success()).unwrap_or(false);
        let _ = tx.send(Event::Exited(success));
        ctx.request_repaint();
    });
    Ok(Running { rx, input })
}
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Status {
    Waiting,
    Running,
    Cancelling,
    Done,
    Failed,
    Cancelled,
}
impl Status {
    pub fn pending(self) -> bool {
        matches!(self, Self::Waiting | Self::Running | Self::Cancelling)
    }
}
pub struct Item {
    pub title: String,
    pub request: Value,
    pub status: Status,
    pub stage: String,
    pub fraction: f32,
    pub bytes: u64,
    pub total: u64,
    pub speed: u64,
    pub error: Option<String>,
    pub login: Option<(String, String)>,
    pub result: Option<Value>,
}
#[derive(Default)]
pub struct Queue {
    pub items: Vec<Item>,
    pub paused: bool,
    pub active: Option<(usize, Running)>,
    pub revision: u64,
}
impl Queue {
    pub fn enqueue(&mut self, title: String, request: Value) {
        if self
            .items
            .iter()
            .any(|i| i.status.pending() && i.request == request)
        {
            return;
        }
        self.items.push(Item {
            title,
            request,
            status: Status::Waiting,
            stage: String::new(),
            fraction: 0.0,
            bytes: 0,
            total: 0,
            speed: 0,
            error: None,
            login: None,
            result: None,
        });
    }
    pub fn cancel(&mut self, index: usize) {
        if let Some(item) = self.items.get_mut(index) {
            match item.status {
                Status::Waiting => item.status = Status::Cancelled,
                Status::Running => {
                    item.status = Status::Cancelling;
                    if let Some((active, process)) = &self.active {
                        if *active == index {
                            process.cancel();
                        }
                    }
                }
                _ => {}
            }
        }
    }
    pub fn retry(&mut self, index: usize) {
        if let Some(item) = self.items.get(index) {
            if self.items.iter().enumerate().any(|(other, row)| {
                other != index && row.status.pending() && row.request == item.request
            }) {
                return;
            }
        }
        if let Some(item) = self.items.get_mut(index) {
            if matches!(item.status, Status::Failed | Status::Cancelled) {
                item.status = Status::Waiting;
                item.error = None;
                item.login = None;
                item.result = None;
                item.fraction = 0.0;
                item.stage.clear();
            }
        }
    }
    pub fn clear(&mut self) {
        if self.active.is_none() {
            self.items.retain(|item| item.status.pending());
        }
    }
    pub fn tick(&mut self, backend: Option<&PathBuf>, ctx: &eframe::egui::Context) {
        let mut finished = false;
        if let Some((index, running)) = &self.active {
            while let Ok(event) = running.rx.try_recv() {
                let item = &mut self.items[*index];
                match event {
                    Event::Data(data) => match data["type"].as_str().unwrap_or("") {
                        "stage" => item.stage = data["data"].as_str().unwrap_or("").into(),
                        "progress" => {
                            let p = &data["data"];
                            item.fraction = p["fraction"].as_f64().unwrap_or(0.0) as f32;
                            item.bytes = p["completedBytes"].as_u64().unwrap_or(0);
                            item.total = p["totalBytes"].as_u64().unwrap_or(0);
                            item.speed = p["bytesPerSecond"].as_u64().unwrap_or(0);
                            item.stage = p["currentFile"].as_str().unwrap_or("").into();
                        }
                        "done" => {
                            item.result = Some(data["data"].clone());
                            item.status = Status::Done;
                            item.fraction = 1.0;
                            item.stage = "Завершено".into();
                            self.revision += 1;
                        }
                        "failed" => {
                            item.status = Status::Failed;
                            item.error = Some(
                                data["data"]
                                    .as_str()
                                    .unwrap_or("Операция не выполнена")
                                    .into(),
                            );
                        }
                        "cancelled" => item.status = Status::Cancelled,
                        "login" => {
                            if let (Some(code), Some(url)) =
                                (data["data"]["code"].as_str(), data["data"]["url"].as_str())
                            {
                                if reqwest::Url::parse(url).ok().is_some_and(|url| {
                                    url.scheme() == "https"
                                        && matches!(
                                            url.host_str(),
                                            Some(
                                                "microsoft.com"
                                                    | "www.microsoft.com"
                                                    | "login.microsoftonline.com"
                                            )
                                        )
                                }) {
                                    item.login = Some((code.into(), url.into()));
                                }
                            }
                        }
                        _ => {}
                    },
                    Event::Exited(ok) => {
                        if item.status == Status::Cancelling {
                            item.status = Status::Cancelled;
                        } else if item.status.pending() {
                            item.status = Status::Failed;
                            item.error = Some(
                                if ok {
                                    "Движок завершился без результата"
                                } else {
                                    "Движок завершился с ошибкой"
                                }
                                .into(),
                            );
                        }
                        finished = true;
                    }
                }
            }
        }
        if finished {
            self.active = None;
        }
        if self.active.is_none() && !self.paused {
            if let Some(index) = self.items.iter().position(|i| i.status == Status::Waiting) {
                let result = backend
                    .ok_or_else(|| "Не найден встроенный движок лаунчера".to_owned())
                    .and_then(|p| start(p, &self.items[index].request, ctx.clone()));
                match result {
                    Ok(process) => {
                        self.items[index].status = Status::Running;
                        self.active = Some((index, process));
                    }
                    Err(error) => {
                        self.items[index].status = Status::Failed;
                        self.items[index].error = Some(error);
                    }
                }
            }
        }
        if self.active.is_some() {
            ctx.request_repaint_after(std::time::Duration::from_millis(100));
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn queue_deduplicates_and_cancels_waiting() {
        let mut q = Queue::default();
        let request = json!({"op":"install","project":"x"});
        q.enqueue("X".into(), request.clone());
        q.enqueue("X".into(), request);
        assert_eq!(q.items.len(), 1);
        q.cancel(0);
        assert_eq!(q.items[0].status, Status::Cancelled);
        q.retry(0);
        assert_eq!(q.items[0].status, Status::Waiting);
    }
    #[test]
    fn unbundled_backend_is_rejected() {
        assert!(backend_command(Path::new("/missing/AWLauncher.exe")).is_err());
    }
    #[test]
    #[ignore = "Requires the packaged application"]
    fn packaged_engine_protocol_smoke() {
        let backend =
            std::env::var_os("AW_NATIVE_ENGINE_TEST").expect("Path to packaged AWLauncher.exe");
        let running = start(
            Path::new(&backend),
            &json!({"op":"ping"}),
            eframe::egui::Context::default(),
        )
        .unwrap();
        let mut done = false;
        loop {
            match running
                .rx
                .recv_timeout(std::time::Duration::from_secs(20))
                .unwrap()
            {
                Event::Data(event) if event["type"] == "done" => {
                    assert_eq!(event["data"]["protocol"], 1);
                    assert_eq!(event["data"]["version"], "1.0.50");
                    done = true;
                }
                Event::Data(event) if event["type"] == "failed" => {
                    panic!("Engine failure: {}", event["data"])
                }
                Event::Exited(success) => {
                    assert!(success);
                    assert!(done);
                    break;
                }
                _ => {}
            }
        }
    }
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
