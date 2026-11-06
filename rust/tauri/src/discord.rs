//! Desktop Discord RPC. The worker owns the connection and never blocks Tauri commands.
use serde_json::{json, Value};
use std::{
    fs,
    io::{self, Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};

const DEFAULT_APP_ID: &str = "1555994295134326945";
const SITE: &str = "https://awlauncher.ru/";
const CONNECTION_LOG: &str = r"^Connecting to ([A-Za-z0-9.\-:\[\]]{1,253}), (\d+)";

#[cfg(windows)]
type Pipe = fs::File;
#[cfg(unix)]
type Pipe = std::os::unix::net::UnixStream;

fn connect(index: u32) -> io::Result<Pipe> {
    #[cfg(windows)]
    {
        fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(format!(r"\\.\pipe\discord-ipc-{index}"))
    }
    #[cfg(unix)]
    {
        let roots = ["XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"]
            .into_iter()
            .filter_map(std::env::var_os)
            .map(PathBuf::from)
            .filter(|p| p.is_absolute())
            .chain(std::iter::once(PathBuf::from("/tmp")));
        for root in roots {
            for dir in [root.clone(), root.join("app/com.discordapp.Discord")] {
                if let Ok(socket) = Pipe::connect(dir.join(format!("discord-ipc-{index}"))) {
                    socket.set_write_timeout(Some(Duration::from_secs(2)))?;
                    return Ok(socket);
                }
            }
        }
        Err(io::ErrorKind::NotFound.into())
    }
}
fn read_until(pipe: &mut Pipe, mut bytes: &mut [u8], deadline: Instant) -> io::Result<()> {
    while !bytes.is_empty() {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(io::ErrorKind::TimedOut.into());
        }
        #[cfg(windows)]
        let limit = {
            use std::os::windows::io::AsRawHandle;
            use windows_sys::Win32::System::Pipes::PeekNamedPipe;
            let mut available = 0;
            let ok = unsafe {
                PeekNamedPipe(
                    pipe.as_raw_handle() as _,
                    std::ptr::null_mut(),
                    0,
                    std::ptr::null_mut(),
                    &mut available,
                    std::ptr::null_mut(),
                )
            };
            if ok == 0 {
                return Err(io::Error::last_os_error());
            }
            if available == 0 {
                std::thread::sleep(remaining.min(Duration::from_millis(20)));
                continue;
            }
            bytes.len().min(available as usize)
        };
        #[cfg(unix)]
        let limit = {
            pipe.set_read_timeout(Some(remaining))?;
            bytes.len()
        };
        let read = pipe.read(&mut bytes[..limit])?;
        if read == 0 {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        bytes = &mut bytes[read..];
    }
    Ok(())
}
fn send(pipe: &mut impl Write, op: u32, value: &Value) -> io::Result<()> {
    let body = serde_json::to_vec(value)?;
    let mut frame = Vec::with_capacity(8 + body.len());
    frame.extend(op.to_le_bytes());
    frame.extend((body.len() as u32).to_le_bytes());
    frame.extend(body);
    pipe.write_all(&frame)
}
fn wait(pipe: &mut Pipe, accept: impl Fn(u32, &Value) -> bool) -> io::Result<()> {
    let deadline = Instant::now() + Duration::from_secs(2);
    loop {
        let mut header = [0; 8];
        read_until(pipe, &mut header, deadline)?;
        let op = u32::from_le_bytes(header[..4].try_into().unwrap());
        let length = u32::from_le_bytes(header[4..].try_into().unwrap()) as usize;
        if length > 256 * 1024 || op == 2 {
            return Err(io::ErrorKind::InvalidData.into());
        }
        let mut body = vec![0; length];
        read_until(pipe, &mut body, deadline)?;
        let value: Value = serde_json::from_slice(&body)?;
        if value["evt"] == "ERROR" {
            return Err(io::ErrorKind::ConnectionRefused.into());
        }
        if op == 3 {
            send(pipe, 4, &value)?;
        } else if accept(op, &value) {
            return Ok(());
        }
    }
}
fn open(app_id: &str) -> io::Result<Pipe> {
    for index in 0..10 {
        if let Ok(mut pipe) = connect(index) {
            send(&mut pipe, 0, &json!({"v":1,"client_id":app_id}))?;
            wait(&mut pipe, |op, value| op == 1 && value["evt"] == "READY")?;
            return Ok(pipe);
        }
    }
    Err(io::ErrorKind::NotFound.into())
}
fn publish(pipe: &mut Pipe, activity: &Value) -> io::Result<()> {
    let nonce = uuid::Uuid::new_v4().to_string();
    send(
        pipe,
        1,
        &json!({"cmd":"SET_ACTIVITY","args":{"pid":std::process::id(),"activity":activity},"nonce":nonce}),
    )?;
    wait(pipe, |op, value| {
        op == 1 && value["nonce"] == nonce && value["cmd"] == "SET_ACTIVITY"
    })
}

#[derive(Default)]
pub struct Presence {
    games: Arc<Mutex<Vec<Game>>>,
    stop: Arc<AtomicBool>,
}
impl Presence {
    pub fn game_started(&self, pid: u32, profile: Value) {
        if let Ok(mut games) = self.games.lock() {
            games.push(Game::new(pid, profile));
        }
    }
    pub fn stop(&self) {
        self.stop.store(true, Ordering::Relaxed);
    }
    pub fn start(&self, root: PathBuf) {
        let stop = self.stop.clone();
        let games = self.games.clone();
        std::thread::spawn(move || worker(&root, games, stop));
    }
}
struct Game {
    pid: u32,
    profile: Value,
    start: u64,
    offset: u64,
    partial: Vec<u8>,
    mode: Mode,
}
#[derive(Debug, PartialEq, Eq)]
enum Mode {
    Menu,
    Singleplayer,
    Server(String),
}
impl Game {
    fn new(pid: u32, profile: Value) -> Self {
        Self {
            pid,
            profile,
            start: aw_engine::accounts::now() / 1000,
            offset: 0,
            partial: Vec::new(),
            mode: Mode::Menu,
        }
    }
    fn poll(&mut self, pattern: &regex::Regex) {
        let Some(dir) = self.profile["dir"].as_str() else {
            return;
        };
        let Ok(mut log) = fs::File::open(Path::new(dir).join("latest-game.log")) else {
            return;
        };
        if log.metadata().is_ok_and(|m| m.len() < self.offset) {
            self.offset = 0;
            self.partial.clear();
            self.mode = Mode::Menu;
        }
        if log.seek(SeekFrom::Start(self.offset)).is_err() {
            return;
        }
        let before = self.partial.len();
        if let Ok(bytes) = log.take(512 * 1024).read_to_end(&mut self.partial) {
            self.offset += bytes as u64;
            if let Some(end) = self.partial.iter().rposition(|b| *b == b'\n') {
                for line in String::from_utf8_lossy(&self.partial[..=end]).lines() {
                    if let Some(mode) = mode(line, pattern) {
                        self.mode = mode;
                    }
                }
                self.partial.drain(..=end);
            }
            if self.partial.len() > 512 * 1024 {
                self.partial.clear();
            }
        } else {
            self.partial.truncate(before);
        }
    }
}
fn mode(line: &str, connect: &regex::Regex) -> Option<Mode> {
    if line.contains("[CHAT]") {
        return None;
    }
    let message = line
        .split_once("<![CDATA[")
        .or_else(|| line.split_once("]: "))
        .map(|(_, text)| text)?;
    if let Some(parts) = connect.captures(message) {
        let host = parts[1].trim_end_matches('.').to_ascii_lowercase();
        let port: u16 = parts[2].parse().ok()?;
        return Some(Mode::Server(if port == 25565 {
            host
        } else {
            format!("{host}:{port}")
        }));
    }
    let lowercase = message.to_ascii_lowercase();
    if lowercase.starts_with("starting integrated minecraft server") {
        return Some(Mode::Singleplayer);
    }
    if [
        "stopping server",
        "disconnecting from server",
        "disconnected from server",
    ]
    .iter()
    .any(|s| lowercase.starts_with(s))
    {
        return Some(Mode::Menu);
    }
    None
}
fn card(game: Option<&Game>, options: &crate::settings::Store, opened: u64) -> Value {
    if !options.boolean("discordPresence", true) {
        return Value::Null;
    }
    let mut buttons = vec![json!({"label":"Сайт AWLauncher","url":SITE})];
    if let Some(game) = game {
        let version = format!(
            "Minecraft {} · {}",
            game.profile["version"].as_str().unwrap_or(""),
            game.profile["loader"].as_str().unwrap_or("Vanilla")
        );
        let show_instance = options.boolean("discordShowInstance", true);
        let name = show_instance
            .then(|| game.profile["name"].as_str())
            .flatten()
            .unwrap_or(&version);
        let (description, image) = match &game.mode {
            Mode::Singleplayer => ("Одиночная игра".into(), "singleplayer"),
            Mode::Server(address) if options.boolean("discordShowServer", false) => {
                (format!("Сервер {address}"), "multiplayer")
            }
            Mode::Server(_) => ("Сетевая игра".into(), "multiplayer"),
            Mode::Menu => ("В меню Minecraft".into(), "playing"),
        };
        if show_instance
            && game.profile["key"]
                .as_str()
                .is_some_and(|s| s.starts_with("pack:"))
        {
            if let Some(dir) = game.profile["dir"].as_str() {
                if let Ok(pack) = aw_engine::read_json(&Path::new(dir).join("aw-pack.json")) {
                    if let Some(id) = pack["projectId"].as_str() {
                        let url = if let Some(number) =
                            id.strip_prefix("cf:").and_then(|s| s.parse::<u64>().ok())
                        {
                            Some(format!("https://www.curseforge.com/projects/{number}"))
                        } else if !id.is_empty() && id.bytes().all(|b| b.is_ascii_alphanumeric()) {
                            Some(format!("https://modrinth.com/modpack/{id}"))
                        } else {
                            None
                        };
                        if let Some(url) = url {
                            buttons.push(json!({"label":"Открыть сборку","url":url}));
                        }
                    }
                }
            }
        }
        let state = format!("{description} · {version}");
        return json!({"details":clamp(name),"state":clamp(&state),"timestamps":{"start":game.start},"assets":{"large_image":"awlauncher","large_text":clamp(&version),"small_image":image},"buttons":buttons});
    }
    if !options.boolean("discordShowLauncher", true) {
        return Value::Null;
    }
    json!({"details":"В лаунчере","state":"Выбирает сборку","timestamps":{"start":opened},"assets":{"large_image":"awlauncher"},"buttons":buttons})
}
fn clamp(text: &str) -> &str {
    let mut end = text.len().min(127);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}
fn worker(root: &Path, games: Arc<Mutex<Vec<Game>>>, stop: Arc<AtomicBool>) {
    let app_id = std::env::var("AW_DISCORD_APP_ID")
        .ok()
        .into_iter()
        .chain(fs::read_to_string(root.join("discord_app_id.txt")).ok())
        .chain(option_env!("AW_DISCORD_APP_ID").map(str::to_owned))
        .map(|id| id.trim().to_owned())
        .find(|id| (17..=20).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_digit()))
        .unwrap_or_else(|| DEFAULT_APP_ID.into());
    let opened = aw_engine::accounts::now() / 1000;
    let connect = regex::Regex::new(CONNECTION_LOG).unwrap();
    let mut pipe = None;
    let mut shown = Value::Null;
    let mut reconnect = Instant::now();
    let mut last = None::<Instant>;
    while !stop.load(Ordering::Relaxed) {
        let settings = crate::settings::Store::load(root.join("launcher.json"));
        let desired = match games.lock() {
            Ok(mut games) => {
                games.retain(|g| crate::backend::process_alive(g.pid));
                let current = games.last_mut().map(|g| {
                    g.poll(&connect);
                    &*g
                });
                card(current, &settings, opened)
            }
            Err(_) => break,
        };
        if pipe.is_none() && !desired.is_null() && Instant::now() >= reconnect {
            pipe = open(&app_id).ok();
            reconnect = Instant::now() + Duration::from_secs(15);
        }
        if let Some(connection) = pipe.as_mut() {
            let elapsed = last.map(|time| time.elapsed()).unwrap_or(Duration::MAX);
            if desired != shown && (desired.is_null() || elapsed >= Duration::from_secs(5))
                || elapsed >= Duration::from_secs(15)
            {
                if publish(connection, &desired).is_ok() {
                    shown = desired;
                    last = Some(Instant::now());
                } else {
                    pipe = None;
                    shown = Value::Null;
                    last = None;
                }
            }
        }
        std::thread::sleep(Duration::from_secs(1));
    }
    if let Some(mut pipe) = pipe {
        let _ = send(
            &mut pipe,
            1,
            &json!({"cmd":"SET_ACTIVITY","args":{"pid":std::process::id(),"activity":null},"nonce":"shutdown"}),
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn protocol_header_is_little_endian() {
        let mut frame = Vec::new();
        send(&mut frame, 1, &json!({"v":1})).unwrap();
        assert_eq!(&frame[..8], &[1, 0, 0, 0, 7, 0, 0, 0]);
        assert_eq!(&frame[8..], br#"{"v":1}"#);
    }
    #[test]
    fn chat_cannot_set_a_discord_server() {
        let pattern = regex::Regex::new(CONNECTION_LOG).unwrap();
        assert_eq!(
            mode(
                "[00:00:00] [Render thread/INFO]: Connecting to Example.com, 25565",
                &pattern
            ),
            Some(Mode::Server("example.com".into()))
        );
        assert_eq!(
            mode(
                "[00:00:00] [Render thread/INFO]: [CHAT] Connecting to Example.com, 25565",
                &pattern
            ),
            None
        );
    }
}
