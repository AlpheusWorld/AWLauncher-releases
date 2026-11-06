use crate::{read_json, response_json, secrets, write_json, Context, Result};
use serde_json::{json, Value};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
pub fn store(cx: &Context) -> Result<Value> {
    let path = cx.root.join("accounts.json");
    if !path.exists() {
        return Ok(json!({"accounts":[],"selectedUuid":null}));
    }
    let data = read_json(&path)?;
    if !data["accounts"].is_array() {
        return Err("Повреждено хранилище аккаунтов".into());
    }
    Ok(data)
}
pub fn public(store: &Value) -> Value {
    let accounts = store["accounts"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let selected = accounts
        .iter()
        .find(|account| account["uuid"] == store["selectedUuid"])
        .or_else(|| accounts.first());
    Value::Array(accounts.iter().map(|account| {
        let mut public = json!({"id":account["uuid"],"name":account["name"],"type":account["type"],"selected":selected.is_some_and(|value|value["uuid"]==account["uuid"])});
        if account["type"] == "MICROSOFT" {
            public["skinModel"] = json!(account["skinModel"].as_str().unwrap_or("CLASSIC"));
            public["capes"] = account["capes"].as_array().cloned().map(Value::Array).unwrap_or_else(||json!([]));
            public["capeId"] = account["capeId"].clone();
        }
        public
    }).collect())
}
fn save(cx: &Context, data: &Value) -> Result<()> {
    write_json(&cx.root.join("accounts.json"), data)
}
pub fn upsert(cx: &Context, account: Value, select: bool) -> Result<Value> {
    let mut data = store(cx)?;
    let list = data["accounts"]
        .as_array_mut()
        .ok_or("Нет списка аккаунтов")?;
    if let Some(old) = list.iter_mut().find(|a| a["uuid"] == account["uuid"]) {
        *old = account.clone();
    } else {
        list.push(account.clone());
    }
    if select {
        data["selectedUuid"] = account["uuid"].clone();
    }
    save(cx, &data)?;
    Ok(data)
}
pub fn handle(cx: &Context, r: &Value) -> Result<Value> {
    let mut data = store(cx)?;
    match r["op"].as_str().unwrap_or("") {
        "accountSelect" => {
            let selected = &r["file"];
            if !data["accounts"]
                .as_array()
                .unwrap()
                .iter()
                .any(|a| a["uuid"] == *selected)
            {
                return Err("Аккаунт больше не найден".into());
            }
            data["selectedUuid"] = selected.clone();
            save(cx, &data)?;
        }
        "accountOffline" => {
            let name = r["title"].as_str().unwrap_or("").trim();
            if !(3..=16).contains(&name.len())
                || !name.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
            {
                return Err("Ник: 3–16 латинских букв, цифр или подчёркиваний".into());
            }
            // Java UUID.nameUUIDFromBytes hashes the name without a UUID namespace.
            use md5::{Digest, Md5};
            use uuid::Builder;
            let digest: [u8; 16] = Md5::digest(format!("OfflinePlayer:{name}").as_bytes()).into();
            let id = Builder::from_md5_bytes(digest)
                .into_uuid()
                .simple()
                .to_string();
            data = upsert(
                cx,
                json!({"uuid":id,"name":name,"type":"OFFLINE","accessToken":"0","refreshToken":"","expiresAt":0,"skinModel":"CLASSIC","capes":[]}),
                true,
            )?;
        }
        "accountRemove" => {
            let id = &r["file"];
            data["accounts"]
                .as_array_mut()
                .unwrap()
                .retain(|a| &a["uuid"] != id);
            if &data["selectedUuid"] == id {
                data["selectedUuid"] = data["accounts"][0]["uuid"].clone();
            }
            save(cx, &data)?;
        }
        "accountMicrosoft" => {
            let account = if r["options"]["loginMethod"] == "device" && client_id(cx).is_ok() {
                login_device_configured(cx)?
            } else {
                login(cx)?
            };
            data = upsert(cx, account, true)?;
        }
        _ => return Err("Неизвестная операция аккаунта".into()),
    }
    Ok(if r["options"]["returnAccounts"] == true {
        public(&data)
    } else {
        json!("Аккаунты обновлены")
    })
}
fn client_id(cx: &Context) -> Result<String> {
    [
        std::env::var("AW_MS_CLIENT_ID").ok(),
        std::fs::read_to_string(cx.root.join("client_id.txt")).ok(),
        option_env!("AW_MS_CLIENT_ID").map(str::to_owned),
    ]
    .into_iter()
    .flatten()
    .find(|v| uuid::Uuid::parse_str(v.trim()).is_ok())
    .map(|s| s.trim().to_owned())
    .ok_or(
        "Для нативного входа укажи Application ID в AW_MS_CLIENT_ID или .aw/client_id.txt".into(),
    )
}
fn login(cx: &Context) -> Result<Value> {
    let id = match client_id(cx) {
        Ok(id) => id,
        Err(_) => return crate::title_auth::login(cx),
    };
    let tokens = crate::pkce::login(cx, &id)?;
    finish(cx, &tokens)
}
fn login_device_configured(cx: &Context) -> Result<Value> {
    let id = client_id(cx)?;
    cx.stage("Получение кода Microsoft");
    let client = cx.client()?;
    let device = response_json(
        client
            .post("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode")
            .form(&[
                ("client_id", id.as_str()),
                ("scope", "XboxLive.signin offline_access"),
            ])
            .send()
            .map_err(|_| "Нет связи с Microsoft")?,
    )?;
    (cx.event)(
        json!({"type":"login","data":{"code":device["user_code"],"url":device["verification_uri"]}}),
    );
    let deadline = Instant::now()
        + Duration::from_secs(device["expires_in"].as_u64().unwrap_or(900).min(1800));
    let mut interval = device["interval"].as_u64().unwrap_or(5).max(1);
    loop {
        for _ in 0..interval * 10 {
            cx.check()?;
            if Instant::now() >= deadline {
                return Err("Код Microsoft истёк".into());
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        let response = client
            .post("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
            .form(&[
                ("client_id", id.as_str()),
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                (
                    "device_code",
                    device["device_code"]
                        .as_str()
                        .ok_or("Microsoft не вернул код")?,
                ),
            ])
            .send()
            .map_err(|_| "Нет связи с Microsoft")?;
        let success = response.status().is_success();
        let value: Value = response
            .json()
            .map_err(|_| "Некорректный ответ Microsoft")?;
        if success {
            return finish(cx, &value);
        }
        match value["error"].as_str() {
            Some("authorization_pending") => {}
            Some("slow_down") => interval += 5,
            _ => return Err("Microsoft отклонил вход; запроси новый код".into()),
        }
    }
}
fn finish(cx: &Context, ms: &Value) -> Result<Value> {
    let token = ms["access_token"]
        .as_str()
        .ok_or("Microsoft не вернул токен")?;
    cx.stage("Вход в Xbox Live");
    let client = cx.client()?;
    let xbl=response_json(client.post("https://user.auth.xboxlive.com/user/authenticate").json(&json!({"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":format!("d={token}")},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"})).send().map_err(|_|"Нет связи с Xbox Live")?)?;
    let xsts=response_json(client.post("https://xsts.auth.xboxlive.com/xsts/authorize").json(&json!({"Properties":{"SandboxId":"RETAIL","UserTokens":[xbl["Token"]]},"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"})).send().map_err(|_|"Нет связи с Xbox Live")?)?;
    let hash = xsts["DisplayClaims"]["xui"][0]["uhs"]
        .as_str()
        .ok_or("Xbox Live не вернул профиль")?;
    let xt = xsts["Token"].as_str().ok_or("Xbox Live не вернул токен")?;
    let refresh = ms["refresh_token"]
        .as_str()
        .filter(|s| !s.is_empty())
        .ok_or("Microsoft не вернул токен обновления")?;
    minecraft_account(cx, refresh, &format!("XBL3.0 x={hash};{xt}"))
}
pub(crate) fn minecraft_account(cx: &Context, refresh: &str, identity: &str) -> Result<Value> {
    let client = cx.client()?;
    let mc = response_json(
        client
            .post("https://api.minecraftservices.com/authentication/login_with_xbox")
            .json(&json!({"identityToken":identity}))
            .send()
            .map_err(|_| "Нет связи с Minecraft")?,
    )?;
    let access = mc["access_token"]
        .as_str()
        .ok_or("Minecraft не вернул токен")?;
    let ent = response_json(
        client
            .get("https://api.minecraftservices.com/entitlements/mcstore")
            .bearer_auth(access)
            .send()
            .map_err(|_| "Нет связи с Minecraft")?,
    )?;
    if ent["items"].as_array().is_none_or(|a| a.is_empty()) {
        return Err("На аккаунте нет Minecraft Java".into());
    }
    let profile = response_json(
        client
            .get("https://api.minecraftservices.com/minecraft/profile")
            .bearer_auth(access)
            .send()
            .map_err(|_| "Нет связи с Minecraft")?,
    )?;
    let mut a = json!({"uuid":profile["id"],"name":profile["name"],"type":"MICROSOFT","accessToken":secrets::protect(access)?,"refreshToken":secrets::protect(refresh)?,"expiresAt":now()+mc["expires_in"].as_u64().unwrap_or(3600)*1000});
    appearance(&mut a, &profile)?;
    Ok(a)
}
pub fn ready(cx: &Context, mut a: Value) -> Result<Value> {
    if a["type"] == "OFFLINE" {
        return Ok(a);
    }
    if a["expiresAt"].as_u64().unwrap_or(0) < now() + 60_000 {
        let refresh = secrets::reveal(a["refreshToken"].as_str().unwrap_or(""))?;
        if let Some(token) = refresh.strip_prefix(crate::title_auth::PREFIX) {
            let renewed = crate::title_auth::refresh(cx, token)?;
            if renewed["uuid"] != a["uuid"] {
                return Err("Microsoft вернул другой аккаунт".into());
            }
            upsert(cx, renewed.clone(), false)?;
            let mut ready = renewed;
            ready["accessToken"] = json!(secrets::reveal(
                ready["accessToken"].as_str().unwrap_or("")
            )?);
            return Ok(ready);
        }
        let id = client_id(cx)?;
        let token = response_json(
            cx.client()?
                .post("https://login.microsoftonline.com/consumers/oauth2/v2.0/token")
                .form(&[
                    ("client_id", id.as_str()),
                    ("refresh_token", refresh.as_str()),
                    ("grant_type", "refresh_token"),
                    ("scope", "XboxLive.signin offline_access"),
                ])
                .send()
                .map_err(|_| "Нет связи с Microsoft")?,
        )?;
        let mut token = token;
        if token["refresh_token"].is_null() {
            token["refresh_token"] = json!(refresh);
        }
        let renewed = finish(cx, &token)?;
        if renewed["uuid"] != a["uuid"] {
            return Err("Microsoft вернул другой аккаунт".into());
        }
        upsert(cx, renewed.clone(), false)?;
        a = renewed;
    }
    a["accessToken"] = json!(secrets::reveal(a["accessToken"].as_str().unwrap_or(""))?);
    Ok(a)
}
pub fn selected(cx: &Context) -> Result<Value> {
    let data = store(cx)?;
    let accounts = data["accounts"].as_array().unwrap();
    let a = accounts
        .iter()
        .find(|a| a["uuid"] == data["selectedUuid"])
        .or_else(|| accounts.first())
        .ok_or("Добавь аккаунт в настройках")?;
    ready(cx, a.clone())
}
pub fn appearance(a: &mut Value, p: &Value) -> Result<()> {
    if p["id"] != a["uuid"] || p["name"].as_str().unwrap_or("").is_empty() {
        return Err("Профиль Minecraft не совпадает с аккаунтом".into());
    }
    a["name"] = p["name"].clone();
    let active = p["skins"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|s| s["state"] == "ACTIVE");
    a["skinUrl"] = active.map(|s| s["url"].clone()).unwrap_or(Value::Null);
    a["skinModel"] = json!(if active.is_some_and(|s| s["variant"]
        .as_str()
        .is_some_and(|s| s.eq_ignore_ascii_case("slim")))
    {
        "SLIM"
    } else {
        "CLASSIC"
    });
    a["capes"] = p["capes"].as_array().map(|a| json!(a)).unwrap_or(json!([]));
    a["capeId"] = p["capes"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|c| c["state"] == "ACTIVE")
        .map(|c| c["id"].clone())
        .unwrap_or(Value::Null);
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn public_never_contains_credentials() {
        let p = public(
            &json!({"selectedUuid":"a","accounts":[{"uuid":"a","accessToken":"secret","refreshToken":"secret2","name":"n","type":"MICROSOFT"}]}),
        );
        assert!(!p.to_string().contains("secret"));
        assert_eq!(p[0]["selected"], true);
    }
}
