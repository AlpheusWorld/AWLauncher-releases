use crate::{err, response_json, Context, Result};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    process::{Command, Stdio},
    time::{Duration, Instant},
};
fn random(size: usize) -> Result<String> {
    let mut bytes = vec![0; size];
    getrandom::getrandom(&mut bytes).map_err(err)?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}
fn challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}
fn callback(target: &str, state: &str) -> Result<Option<String>> {
    if !target.starts_with("/?") {
        return Ok(None);
    }
    let url = reqwest::Url::parse(&format!("http://localhost{target}"))
        .map_err(|_| "Некорректный ответ авторизации")?;
    let mut fields = HashMap::new();
    for (k, v) in url.query_pairs() {
        if fields.insert(k.to_string(), v.to_string()).is_some() {
            return Err("Повторяющийся параметр авторизации".into());
        }
    }
    if fields.get("state").map(String::as_str) != Some(state) {
        return Err("Не совпал параметр state — вход отклонён".into());
    }
    if fields.contains_key("error") {
        return Err("Microsoft отменил вход".into());
    }
    Ok(fields.remove("code").filter(|s| !s.is_empty()))
}
fn receive(mut stream: TcpStream, state: &str) -> Result<Option<String>> {
    stream
        .set_read_timeout(Some(Duration::from_secs(1)))
        .map_err(err)?;
    stream
        .set_write_timeout(Some(Duration::from_secs(1)))
        .map_err(err)?;
    let mut bytes = Vec::new();
    let deadline = Instant::now() + Duration::from_secs(2);
    let mut byte = [0];
    while bytes.len() < 8192 {
        if Instant::now() > deadline {
            return Ok(None);
        }
        match stream.read(&mut byte) {
            Ok(0) => break,
            Ok(_) => {
                bytes.push(byte[0]);
                if bytes.ends_with(b"\r\n\r\n") {
                    break;
                }
            }
            Err(_) => return Ok(None),
        }
    }
    if bytes.len() >= 8192 {
        return Ok(None);
    }
    let text = String::from_utf8_lossy(&bytes);
    let first = text.lines().next().unwrap_or("");
    let mut parts = first.split_whitespace();
    if parts.next() != Some("GET") {
        return Ok(None);
    }
    let result = callback(parts.next().unwrap_or(""), state);
    let (status, body) = match &result {
        Ok(Some(_)) => (200, "Вход выполнен. Вернись в AWLauncher."),
        Ok(None) => (404, "Страница не найдена."),
        Err(_) => (400, "Вход не подтверждён. Вернись в AWLauncher."),
    };
    let response=format!("HTTP/1.1 {status} Response\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: {}\r\n\r\n{body}",body.len());
    let _ = stream.write_all(response.as_bytes());
    result
}
pub fn login(cx: &Context, id: &str) -> Result<Value> {
    let verifier = random(64)?;
    let state = random(24)?;
    let listener = TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))
        .map_err(|_| "Не удалось открыть локальный порт для входа")?;
    listener.set_nonblocking(true).map_err(err)?;
    let redirect = format!(
        "http://localhost:{}",
        listener.local_addr().map_err(err)?.port()
    );
    let mut url =
        reqwest::Url::parse("https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize")
            .unwrap();
    url.query_pairs_mut().extend_pairs([
        ("client_id", id),
        ("response_type", "code"),
        ("redirect_uri", redirect.as_str()),
        ("scope", "XboxLive.signin offline_access"),
        ("code_challenge", challenge(&verifier).as_str()),
        ("code_challenge_method", "S256"),
        ("state", state.as_str()),
        ("prompt", "select_account"),
    ]);
    cx.stage("Открываю браузер для входа");
    let browser = if cfg!(windows) {
        "explorer.exe"
    } else if cfg!(target_os = "macos") {
        "open"
    } else {
        "xdg-open"
    };
    let mut child = Command::new(browser)
        .arg(url.as_str())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|_| "Не удалось открыть браузер")?;
    std::thread::spawn(move || {
        let _ = child.wait();
    });
    let deadline = Instant::now() + Duration::from_secs(300);
    let code = loop {
        cx.check()?;
        if Instant::now() >= deadline {
            return Err("Время ожидания Microsoft-входа истекло".into());
        }
        match listener.accept() {
            Ok((stream, peer)) => {
                if !peer.ip().is_loopback() {
                    continue;
                }
                if let Some(code) = receive(stream, &state)? {
                    break code;
                }
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(100))
            }
            Err(_) => return Err("Не удалось получить ответ Microsoft".into()),
        }
    };
    cx.check()?;
    cx.stage("Обмен кода на токен");
    response_json(
        cx.client()?
            .post("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
            .form(&[
                ("client_id", id),
                ("code", code.as_str()),
                ("grant_type", "authorization_code"),
                ("redirect_uri", redirect.as_str()),
                ("code_verifier", verifier.as_str()),
                ("scope", "XboxLive.signin offline_access"),
            ])
            .send()
            .map_err(|_| "Нет связи с Microsoft")?,
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rfc7636_challenge() {
        assert_eq!(
            challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
    }
    #[test]
    fn callback_rejects_state_and_duplicates() {
        assert!(callback("/?code=abc&state=wrong", "right").is_err());
        assert!(callback("/?code=abc&state=right&state=wrong", "right").is_err());
        assert_eq!(
            callback("/?code=a%2Bb&state=right", "right").unwrap(),
            Some("a+b".into())
        );
        assert!(callback("/favicon.ico", "right").unwrap().is_none());
        assert!(callback("/?error=access_denied&state=right", "right").is_err());
    }
}
