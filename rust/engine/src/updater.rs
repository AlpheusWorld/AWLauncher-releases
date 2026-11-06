use crate::{err, write_bytes, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::{json, Value};
use std::{
    fs,
    io::Read,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{Duration, Instant},
};
const KEY: &str = include_str!("../../../src/main/resources/update/public-key.txt");
fn bytes(cx: &Context, url: &str, limit: usize) -> Result<Vec<u8>> {
    let mut url = reqwest::Url::parse(url).map_err(|_| "Некорректный адрес обновлений")?;
    for _ in 0..6 {
        cx.check()?;
        if url.scheme() != "https" || !url.username().is_empty() || url.password().is_some() {
            return Err("Обновления доступны только по HTTPS".into());
        }
        let response = cx
            .client()?
            .get(url.clone())
            .header("Cache-Control", "no-cache")
            .send()
            .map_err(|_| "Нет связи с сервером обновлений")?;
        if response.status().is_redirection() {
            url = url
                .join(
                    response
                        .headers()
                        .get("Location")
                        .and_then(|s| s.to_str().ok())
                        .ok_or("Нет адреса перенаправления")?,
                )
                .map_err(err)?;
            continue;
        }
        if !response.status().is_success() {
            return Err(format!(
                "Сервер обновлений: HTTP {}",
                response.status().as_u16()
            ));
        }
        let mut b = Vec::new();
        response
            .take(limit as u64 + 1)
            .read_to_end(&mut b)
            .map_err(err)?;
        if b.len() > limit {
            return Err("Описание обновления слишком большое".into());
        }
        return Ok(b);
    }
    Err("Слишком много перенаправлений".into())
}
pub fn newer(remote: &str, local: &str) -> bool {
    let parse = |s: &str| {
        let (core, suffix) = s
            .trim()
            .trim_start_matches('v')
            .split_once('-')
            .map(|(a, b)| (a, Some(b)))
            .unwrap_or((s.trim().trim_start_matches('v'), None));
        (
            core.split('.')
                .map(|n| n.parse::<u64>().unwrap_or(0))
                .collect::<Vec<_>>(),
            suffix.is_some(),
        )
    };
    let (a, pre_a) = parse(remote);
    let (b, pre_b) = parse(local);
    for i in 0..a.len().max(b.len()) {
        let x = a.get(i).copied().unwrap_or(0);
        let y = b.get(i).copied().unwrap_or(0);
        if x != y {
            return x > y;
        }
    }
    pre_b && !pre_a
}
pub fn verify_signed_manifest(document: &[u8], signature: &[u8], encoded_key: &str) -> Result<()> {
    let key = STANDARD
        .decode(encoded_key.trim())
        .map_err(|_| "Некорректный ключ обновлений")?;
    if key.len() != 44
        || key[..12]
            != [
                0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
            ]
    {
        return Err("Некорректный ключ Ed25519".into());
    }
    ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, &key[12..])
        .verify(document, signature)
        .map_err(|_| "Подпись обновления недействительна".into())
}
fn manifest(cx: &Context) -> Result<Value> {
    let url=std::env::var("AW_UPDATE_URL").ok().or_else(||fs::read_to_string(cx.root.join("update_url.txt")).ok()).or_else(||option_env!("AW_UPDATE_URL").map(str::to_owned)).unwrap_or("https://github.com/AlpheusWorld/AWLauncher-releases/releases/latest/download/update.json".into());
    let url = url.trim();
    let raw = bytes(cx, url, 65536)?;
    let v: Value = serde_json::from_slice(&raw).map_err(|_| "Некорректный фид обновлений")?;
    let name = v["version"]
        .as_str()
        .filter(|s| {
            !s.is_empty()
                && s.len() <= 32
                && s.bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b".+-".contains(&b))
        })
        .ok_or("Некорректная версия обновления")?;
    let _ = name;
    let download = reqwest::Url::parse(v["url"].as_str().ok_or("Нет установщика")?)
        .map_err(|_| "Некорректный адрес установщика")?;
    if download.scheme() != "https"
        || !download.username().is_empty()
        || download.password().is_some()
    {
        return Err("Установщик должен раздаваться по HTTPS".into());
    }
    if !v["sha256"]
        .as_str()
        .is_some_and(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
        || v["size"].as_u64().is_none_or(|n| n == 0 || n > 2147483648)
    {
        return Err("Нет корректной суммы или размера обновления".into());
    }
    if !KEY.trim().is_empty() {
        let feed = reqwest::Url::parse(url).map_err(err)?;
        let mut default = feed.clone();
        default.set_path(&format!("{}.sig", feed.path()));
        let signature_url = v["signatureUrl"].as_str().unwrap_or(default.as_str());
        let signature_address = reqwest::Url::parse(signature_url).map_err(err)?;
        if signature_address.scheme() != "https"
            || signature_address.host_str() != feed.host_str()
            || !signature_address.username().is_empty()
            || signature_address.password().is_some()
        {
            return Err("Некорректный адрес подписи обновления".into());
        }
        let signature = bytes(cx, signature_url, 1024)?;
        let signature = STANDARD
            .decode(String::from_utf8(signature).map_err(err)?.trim())
            .map_err(|_| "Некорректная подпись обновления")?;
        verify_signed_manifest(&raw, &signature, KEY)?;
    } else if !option_env!("AW_SIGNER_THUMBPRINT")
        .is_some_and(|s| s.len() == 40 && s.bytes().all(|b| b.is_ascii_hexdigit()))
    {
        return Err("Проверка подписи обновлений не настроена".into());
    }
    Ok(v)
}
pub fn execute(cx: &Context, r: &Value) -> Result<Value> {
    cx.stage("Проверяю обновления");
    let v = manifest(cx)?;
    if !newer(v["version"].as_str().unwrap(), "1.0.55") {
        return Ok(json!({"message":"Установлена последняя версия"}));
    }
    if r["op"] == "checkUpdate" {
        return Ok(
            json!({"available":v["version"],"message":format!("Доступна версия {}",v["version"].as_str().unwrap())}),
        );
    }
    crate::reinstall::no_games(cx)?;
    if r["games"].as_u64().unwrap_or(0) > 0 {
        return Err("Закрой Minecraft перед обновлением".into());
    }
    let url = v["url"].as_str().unwrap();
    let ext = Path::new(reqwest::Url::parse(url).map_err(err)?.path())
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("msi")
        .to_owned();
    if cfg!(windows) && !matches!(ext.as_str(), "exe" | "msi")
        || !cfg!(windows) && !matches!(ext.as_str(), "deb" | "rpm")
    {
        return Err("Обновление для другой ОС".into());
    }
    let root = cx.root.join("cache/update").join(format!(
        "{}-{}",
        v["version"].as_str().unwrap(),
        uuid::Uuid::new_v4()
    ));
    fs::create_dir_all(&root).map_err(err)?;
    let installer = root.join(format!("installer.{ext}"));
    cx.stage("Скачиваю обновление");
    cx.download(url, &installer, None, v["size"].as_u64())?;
    use sha2::{Digest, Sha256};
    let mut file = fs::File::open(&installer).map_err(err)?;
    let mut digest = Sha256::new();
    let mut buf = [0; 65536];
    loop {
        cx.check()?;
        let n = file.read(&mut buf).map_err(err)?;
        if n == 0 {
            break;
        }
        digest.update(&buf[..n]);
    }
    if format!("{:x}", digest.finalize()) != v["sha256"].as_str().unwrap().to_ascii_lowercase() {
        let _ = fs::remove_file(&installer);
        return Err("Контрольная сумма установщика не совпадает".into());
    }
    drop(file);
    cx.check()?;
    #[cfg(windows)]
    windows_handoff(cx, &root, &installer, v["version"].as_str().unwrap())?;
    #[cfg(not(windows))]
    linux_handoff(&root, &installer)?;
    Ok(json!({"installing":true,"message":"Установка обновления начинается"}))
}
fn executable() -> Result<PathBuf> {
    let exe = std::env::current_exe().map_err(err)?;
    if exe
        .parent()
        .is_some_and(|p| p.file_name().is_some_and(|n| n == "app"))
    {
        Ok(exe
            .parent()
            .unwrap()
            .parent()
            .unwrap()
            .join("AWLauncher.exe"))
    } else {
        Ok(exe)
    }
}
#[cfg(windows)]
fn windows_handoff(cx: &Context, dir: &Path, installer: &Path, version: &str) -> Result<()> {
    use std::os::windows::process::CommandExt;
    let signer = option_env!("AW_SIGNER_THUMBPRINT")
        .unwrap_or("")
        .replace(' ', "")
        .to_uppercase();
    if !signer.is_empty() {
        let script="$s=Get-AuthenticodeSignature -LiteralPath $env:AW_INSTALLER; if($s.Status.ToString() -ne 'Valid' -or $s.SignerCertificate.Thumbprint.Replace(' ','').ToUpperInvariant() -ne $env:AW_SIGNER){exit 10}";
        let status = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", script])
            .env("AW_INSTALLER", installer)
            .env("AW_SIGNER", &signer)
            .creation_flags(0x08000000)
            .status()
            .map_err(err)?;
        if !status.success() {
            return Err("Подпись установщика не прошла проверку".into());
        }
    }
    for (name, bytes) in [
        (
            "splash.cs",
            include_bytes!("../../../src/main/resources/update/splash.cs").as_slice(),
        ),
        (
            "splash.manifest",
            include_bytes!("../../../src/main/resources/update/splash.manifest").as_slice(),
        ),
        (
            "app.ico",
            include_bytes!("../../../branding/AWLauncher.ico").as_slice(),
        ),
        (
            "wordmark.png",
            include_bytes!("../../../src/main/resources/brand/wordmark.png").as_slice(),
        ),
        (
            "Onest-Regular.ttf",
            include_bytes!("../../../src/main/resources/fonts/Onest-Regular.ttf").as_slice(),
        ),
        (
            "Onest-Bold.ttf",
            include_bytes!("../../../src/main/resources/fonts/Onest-Bold.ttf").as_slice(),
        ),
    ] {
        write_bytes(&dir.join(name), bytes)?;
    }
    let compiler = PathBuf::from(std::env::var_os("SystemRoot").unwrap_or("C:/Windows".into()))
        .join("Microsoft.NET/Framework64/v4.0.30319/csc.exe");
    let helper = dir.join("AWLauncher.Update.exe");
    let log = fs::File::create(dir.join("compile.log")).map_err(err)?;
    let status = Command::new(compiler)
        .args([
            "/nologo",
            "/target:winexe",
            "/platform:x64",
            "/optimize+",
            "/main:AwUpdateSplash",
            "/reference:System.Windows.Forms.dll",
            "/reference:System.Drawing.dll",
        ])
        .arg(format!("/out:{}", helper.display()))
        .arg(format!("/win32icon:{}", dir.join("app.ico").display()))
        .arg(format!(
            "/win32manifest:{}",
            dir.join("splash.manifest").display()
        ))
        .arg(dir.join("splash.cs"))
        .stdout(log.try_clone().map_err(err)?)
        .stderr(log)
        .creation_flags(0x08000000)
        .status()
        .map_err(err)?;
    if !status.success() {
        return Err(format!(
            "Не удалось подготовить окно обновления: {}",
            dir.display()
        ));
    }
    let app = executable()?;
    let msi = installer.extension().is_some_and(|e| e == "msi");
    let arguments = if msi {
        format!(
            "/i \"{}\" /qn /norestart INSTALLDIR=\"{}\" /l*v \"{}\"",
            installer.display(),
            app.parent().unwrap().display(),
            dir.join("install.log").display()
        )
    } else {
        String::new()
    };
    let binary = if msi {
        PathBuf::from(std::env::var_os("SystemRoot").unwrap_or("C:/Windows".into()))
            .join("System32/msiexec.exe")
    } else {
        installer.into()
    };
    let ready = dir.join("ready");
    let mut child = Command::new(helper)
        .arg("--run-base64")
        .arg(std::process::id().to_string())
        .arg(binary)
        .arg(STANDARD.encode(arguments))
        .arg(app)
        .arg(dir)
        .arg(version)
        .arg(&ready)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .creation_flags(0x08000000)
        .spawn()
        .map_err(err)?;
    let deadline = Instant::now() + Duration::from_secs(30);
    while !ready.exists() {
        if cx.check().is_err()
            || Instant::now() > deadline
            || child.try_wait().map_err(err)?.is_some()
        {
            let _ = child.kill();
            let _ = child.wait();
            return Err(format!("Окно обновления не ответило: {}", dir.display()));
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    Ok(())
}
#[cfg(not(windows))]
fn linux_handoff(dir: &Path, installer: &Path) -> Result<()> {
    let ext = installer.extension().and_then(|s| s.to_str()).unwrap_or("");
    let (manager, action) = if ext == "deb" {
        ("/usr/bin/dpkg", "--install")
    } else {
        ("/usr/bin/rpm", "--upgrade")
    };
    if !Path::new(manager).exists() || !Path::new("/usr/bin/pkexec").exists() {
        return Err("Для обновления нужны pkexec и менеджер пакетов".into());
    }
    let script="while kill -0 \"$1\" 2>/dev/null; do sleep 1; done; /usr/bin/pkexec \"$2\" \"$3\" \"$4\" > \"$5\" 2>&1; \"$6\"";
    Command::new("/bin/sh")
        .arg("-c")
        .arg(script)
        .arg("aw-update")
        .arg(std::process::id().to_string())
        .arg(manager)
        .arg(action)
        .arg(installer)
        .arg(dir.join("install.log"))
        .arg(executable()?)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(err)?;
    Ok(())
}
