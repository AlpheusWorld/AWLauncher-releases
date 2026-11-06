//! Live OAuth and Xbox proof-of-possession/SISU protocol.
//! Protocol reference: PrismarineJS/prismarine-auth (MIT), see THIRD_PARTY.md.
use crate::{accounts, response_json, Context, Result};
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use p256::ecdsa::{signature::Signer, Signature, SigningKey};
use serde_json::{json, Value};
use std::{
    io::Read,
    time::{Duration, Instant},
};
const TITLE: &str = "00000000402b5328";
#[cfg(test)]
mod network_tests {
    use super::*;
    #[test]
    #[ignore = "contacts Xbox device auth without a user account"]
    fn xbox_accepts_device_proof_signature() {
        let cx = Context {
            root: std::env::temp_dir().join("aw-device-probe"),
            cancelled: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
            event: &|_| {},
        };
        let key = SigningKey::random(&mut p256::elliptic_curve::rand_core::OsRng);
        let response=signed(&cx,&key,"https://device.auth.xboxlive.com/device/authenticate",json!({"Properties":{"AuthMethod":"ProofOfPossession","Id":format!("{{{}}}",uuid::Uuid::new_v4()),"DeviceType":"Win32","Version":"10.0.0","ProofKey":proof(&key)},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"})).expect("Xbox device-auth request must be accepted");
        assert!(response["Token"].as_str().is_some_and(|s| !s.is_empty()));
    }
}
const SCOPE: &str = "service::user.auth.xboxlive.com::MBI_SSL";
const TOKEN: &str = "https://login.live.com/oauth20_token.srf";
pub const PREFIX: &str = "aw-ms-device:";
fn signature_input(path: &str, body: &[u8], timestamp: u64) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.extend(1u32.to_be_bytes());
    bytes.push(0);
    bytes.extend(timestamp.to_be_bytes());
    bytes.push(0);
    for part in [b"POST".as_slice(), path.as_bytes(), b"".as_slice(), body] {
        bytes.extend_from_slice(part);
        bytes.push(0);
    }
    bytes
}
fn signature_header(key: &SigningKey, path: &str, body: &[u8], timestamp: u64) -> String {
    let signature: Signature = key.sign(&signature_input(path, body, timestamp));
    let mut bytes = Vec::new();
    bytes.extend(1u32.to_be_bytes());
    bytes.extend(timestamp.to_be_bytes());
    bytes.extend_from_slice(&signature.to_bytes());
    STANDARD.encode(bytes)
}
fn proof(key: &SigningKey) -> Value {
    let point = key.verifying_key().to_encoded_point(false);
    json!({"kty":"EC","crv":"P-256","alg":"ES256","use":"sig","x":URL_SAFE_NO_PAD.encode(point.x().unwrap()),"y":URL_SAFE_NO_PAD.encode(point.y().unwrap())})
}
fn signed(cx: &Context, key: &SigningKey, url: &str, payload: Value) -> Result<Value> {
    cx.check()?;
    let body = serde_json::to_vec(&payload).map_err(|_| "Не удалось подготовить запрос Xbox")?;
    let parsed = reqwest::Url::parse(url).map_err(|_| "Некорректный endpoint Xbox")?;
    let time = (accounts::now() / 1000 + 11_644_473_600) * 10_000_000;
    let response = cx
        .client()?
        .post(url)
        .header(
            "Signature",
            signature_header(key, parsed.path(), &body, time),
        )
        .header("x-xbl-contract-version", "1")
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .body(body)
        .send()
        .map_err(|_| "Нет связи с Xbox Live")?;
    if !response.status().is_success() {
        let status = response.status().as_u16();
        let mut bytes = Vec::new();
        let _ = response.take(65536).read_to_end(&mut bytes);
        let error: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
        return Err(match error["XErr"].as_u64() {
            Some(2148916233) => "Создай профиль Xbox на xbox.com и повтори вход".into(),
            Some(2148916238 | 2148916229) => {
                "Для аккаунта нужно разрешение семейной группы Microsoft".into()
            }
            Some(2148916234) => "Прими условия Xbox на xbox.com и повтори вход".into(),
            _ => format!("Xbox Live отклонил вход (HTTP {status})"),
        });
    }
    response_json(response)
}
fn finish(cx: &Context, ms: &Value) -> Result<Value> {
    let access = ms["access_token"]
        .as_str()
        .ok_or("Microsoft не вернул токен")?;
    let refresh = ms["refresh_token"]
        .as_str()
        .filter(|s| !s.is_empty())
        .ok_or("Microsoft не вернул токен обновления")?;
    cx.stage("Авторизация устройства Xbox");
    let key = SigningKey::random(&mut p256::elliptic_curve::rand_core::OsRng);
    let jwk = proof(&key);
    let device = signed(
        cx,
        &key,
        "https://device.auth.xboxlive.com/device/authenticate",
        json!({"Properties":{"AuthMethod":"ProofOfPossession","Id":format!("{{{}}}",uuid::Uuid::new_v4()),"DeviceType":"Win32","Version":"10.0.0","ProofKey":jwk},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}),
    )?;
    cx.stage("Вход в Xbox Live");
    let sisu = signed(
        cx,
        &key,
        "https://sisu.xboxlive.com/authorize",
        json!({"AccessToken":format!("t={access}"),"AppId":TITLE,"DeviceToken":device["Token"],"Sandbox":"RETAIL","UseModernGamertag":true,"SiteName":"user.auth.xboxlive.com","RelyingParty":"rp://api.minecraftservices.com/","ProofKey":jwk}),
    )?;
    let token = &sisu["AuthorizationToken"];
    let hash = token["DisplayClaims"]["xui"][0]["uhs"]
        .as_str()
        .ok_or("Xbox Live не вернул профиль")?;
    let token = token["Token"].as_str().ok_or("Xbox Live не вернул токен")?;
    accounts::minecraft_account(
        cx,
        &format!("{PREFIX}{refresh}"),
        &format!("XBL3.0 x={hash};{token}"),
    )
}
pub fn refresh(cx: &Context, refresh: &str) -> Result<Value> {
    cx.stage("Обновление сессии Microsoft");
    let mut value = response_json(
        cx.client()?
            .post(TOKEN)
            .form(&[
                ("client_id", TITLE),
                ("scope", SCOPE),
                ("grant_type", "refresh_token"),
                ("refresh_token", refresh),
            ])
            .send()
            .map_err(|_| "Нет связи с Microsoft")?,
    )?;
    if value["refresh_token"].is_null() {
        value["refresh_token"] = json!(refresh);
    }
    finish(cx, &value)
}
pub fn login(cx: &Context) -> Result<Value> {
    cx.stage("Получение кода Microsoft");
    let client = cx.client()?;
    let response = client
        .post("https://login.live.com/oauth20_connect.srf")
        .form(&[
            ("client_id", TITLE),
            ("scope", SCOPE),
            ("response_type", "device_code"),
        ])
        .send()
        .map_err(|_| "Нет связи с Microsoft")?;
    let cookies = response
        .headers()
        .get_all(reqwest::header::SET_COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .filter_map(|s| s.split(';').next())
        .collect::<Vec<_>>()
        .join("; ");
    let device = response_json(response)?;
    let url = device["verification_uri"]
        .as_str()
        .or(device["verification_url"].as_str())
        .ok_or("Microsoft не вернул страницу подтверждения")?;
    (cx.event)(json!({"type":"login","data":{"code":device["user_code"],"url":url}}));
    cx.stage("Подтверди вход в браузере");
    let deadline = Instant::now()
        + Duration::from_secs(device["expires_in"].as_u64().unwrap_or(900).min(1800));
    let mut interval = device["interval"].as_u64().unwrap_or(5).clamp(1, 30);
    loop {
        for _ in 0..interval * 10 {
            cx.check()?;
            if Instant::now() >= deadline {
                return Err("Код Microsoft истёк — запроси новый".into());
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        let response = client
            .post(TOKEN)
            .header(reqwest::header::COOKIE, &cookies)
            .form(&[
                ("client_id", TITLE),
                (
                    "device_code",
                    device["device_code"]
                        .as_str()
                        .ok_or("Microsoft не вернул код устройства")?,
                ),
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
            ])
            .send()
            .map_err(|_| "Нет связи с Microsoft")?;
        let success = response.status().is_success();
        let data: Value = response
            .json()
            .map_err(|_| "Некорректный ответ Microsoft")?;
        if success && data["access_token"].is_string() {
            return finish(cx, &data);
        }
        match data["error"].as_str() {
            Some("authorization_pending") => {}
            Some("slow_down") => interval = (interval + 5).min(60),
            _ => return Err("Microsoft отклонил вход — запроси новый код".into()),
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::signature::Verifier;
    #[test]
    fn signatures_bind_exact_body_and_path() {
        let key = SigningKey::from_bytes((&[7u8; 32]).into()).unwrap();
        let body = br#"{"test":"value"}"#;
        let header = STANDARD
            .decode(signature_header(&key, "/authorize", body, 12345))
            .unwrap();
        assert_eq!(header.len(), 76);
        assert_eq!(&header[..4], &1u32.to_be_bytes());
        let sig = Signature::from_slice(&header[12..]).unwrap();
        assert!(key
            .verifying_key()
            .verify(&signature_input("/authorize", body, 12345), &sig)
            .is_ok());
        assert!(key
            .verifying_key()
            .verify(&signature_input("/other", body, 12345), &sig)
            .is_err());
        assert!(key
            .verifying_key()
            .verify(&signature_input("/authorize", b"changed", 12345), &sig)
            .is_err());
    }
    #[test]
    fn proof_exposes_only_public_coordinates() {
        let key = SigningKey::from_bytes((&[9u8; 32]).into()).unwrap();
        let p = proof(&key);
        assert!(p.get("d").is_none());
        assert_eq!(
            URL_SAFE_NO_PAD
                .decode(p["x"].as_str().unwrap())
                .unwrap()
                .len(),
            32
        );
    }
}
