use crate::{accounts, err, response_json, write_bytes, write_json, Context, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
use serde_json::{json, Value};
use std::{
    fs,
    io::{Cursor, Read},
};
const PROFILE: &str = "https://api.minecraftservices.com/minecraft/profile";
pub fn account_face(cx: &Context) -> Result<Value> {
    let store = accounts::store(cx)?;
    let Some(owner) = store["accounts"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|a| a["uuid"] == store["selectedUuid"])
    else {
        return Ok(Value::Null);
    };
    let Some(url) = owner["skinUrl"].as_str() else {
        return Ok(Value::Null);
    };
    let bytes = match texture(cx, url) {
        Ok(b) => b,
        Err(_) => return Ok(Value::Null),
    };
    let texture = image::load_from_memory_with_format(&bytes, image::ImageFormat::Png)
        .map_err(err)?
        .to_rgba8();
    if texture.width() != 64 || texture.height() < 32 {
        return Ok(Value::Null);
    }
    let mut pixels = Vec::new();
    for y in 0..8 {
        for x in 0..8 {
            let base = texture.get_pixel(8 + x, 8 + y).0;
            let hat = texture.get_pixel(40 + x, 8 + y).0;
            let a = hat[3] as u32;
            let rgb = [0, 1, 2].map(|i| (hat[i] as u32 * a + base[i] as u32 * (255 - a)) / 255);
            pixels.push((0xff000000u32 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]) as i32);
        }
    }
    (cx.event)(json!({"type":"accountFace","data":{"width":8,"height":8,"pixels":pixels}}));
    Ok(Value::Null)
}
fn presets() -> Result<Vec<Value>> {
    let mut values: Vec<Value> = serde_json::from_str(include_str!(
        "../../../src/main/resources/skins/catalog.json"
    ))
    .map_err(err)?;
    for v in &mut values {
        v["id"] = json!(format!(
            "preset:{}:{}",
            v["section"].as_str().ok_or("Нет раздела скина")?,
            v["hash"]
                .as_str()
                .or(v["sha256"].as_str())
                .ok_or("Нет хеша скина")?
        ));
    }
    Ok(values)
}
fn preset_texture(cx: &Context, p: &Value) -> Result<Vec<u8>> {
    if let Some(hash) = p["hash"].as_str() {
        return texture(
            cx,
            &format!("https://textures.minecraft.net/texture/{hash}"),
        );
    }
    use sha2::{Digest, Sha256};
    let url = p["archiveUrl"].as_str().ok_or("Нет архива скина")?;
    let parsed = reqwest::Url::parse(url).map_err(err)?;
    if parsed.scheme() != "https"
        || !matches!(
            parsed.host_str(),
            Some("minecraft.net" | "www.minecraft.net")
        )
    {
        return Err("Неизвестный источник архива".into());
    }
    let hash = p["sha256"].as_str().ok_or("Нет хеша архива")?;
    let path = cx
        .root
        .join("cache/skin-archives")
        .join(format!("{hash}.zip"));
    if !path.exists() {
        cx.download(url, &path, None, None)?;
    }
    let bytes = fs::read(&path).map_err(err)?;
    if format!("{:x}", Sha256::digest(&bytes)) != hash {
        let _ = fs::remove_file(path);
        return Err("Архив скинов не прошёл проверку".into());
    }
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).map_err(err)?;
    let member = zip
        .by_name(p["member"].as_str().ok_or("Нет файла в архиве")?)
        .map_err(err)?;
    let mut bytes = Vec::new();
    member
        .take(1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(err)?;
    Ok(decode(&png(&bytes))?.0)
}
fn decode(source: &str) -> Result<(Vec<u8>, String)> {
    let raw = source
        .strip_prefix("data:image/png;base64,")
        .ok_or("Выбери PNG скина")?;
    if raw.len() > 1400000 {
        return Err("Скин больше 1 МБ".into());
    }
    let bytes = STANDARD.decode(raw).map_err(err)?;
    let reader = image::ImageReader::with_format(Cursor::new(&bytes), image::ImageFormat::Png);
    let (w, h) = reader
        .into_dimensions()
        .map_err(|_| "Не удалось прочитать PNG")?;
    if w != 64 || ![32, 64].contains(&h) {
        return Err("Скин должен быть 64×64 или 64×32".into());
    }
    let image = image::load_from_memory_with_format(&bytes, image::ImageFormat::Png)
        .map_err(err)?
        .to_rgba8();
    let model = if h == 64 && (20..32).all(|y| (54..56).all(|x| image.get_pixel(x, y)[3] == 0)) {
        "SLIM"
    } else {
        "CLASSIC"
    };
    let mut png = Cursor::new(Vec::new());
    image
        .write_to(&mut png, image::ImageFormat::Png)
        .map_err(err)?;
    Ok((png.into_inner(), model.into()))
}
fn png(bytes: &[u8]) -> String {
    format!("data:image/png;base64,{}", STANDARD.encode(bytes))
}
fn id(value: &str) -> Result<&str> {
    if uuid::Uuid::parse_str(value).is_ok_and(|u| u.to_string() == value) {
        Ok(value)
    } else {
        Err("Некорректный ID скина".into())
    }
}
fn list(cx: &Context) -> Result<Vec<Value>> {
    let path = cx.root.join("skins/library.json");
    if !path.exists() {
        return Ok(Vec::new());
    }
    let bytes = fs::read(&path).map_err(err)?;
    if bytes.len() > 4 * 1024 * 1024 {
        return Err("Библиотека скинов слишком большая".into());
    }
    let entries: Vec<Value> = serde_json::from_slice(&bytes).map_err(err)?;
    let mut seen = std::collections::HashSet::new();
    for entry in &entries {
        let key = id(entry["id"].as_str().ok_or("Нет ID скина")?)?;
        if !seen.insert(key) {
            return Err("Дублирующийся ID скина".into());
        }
    }
    Ok(entries)
}
fn texture_source(cx: &Context, url: &str) -> Result<(reqwest::Url, std::path::PathBuf)> {
    let mut parsed = reqwest::Url::parse(url).map_err(|_| "Некорректный адрес текстуры")?;
    if !matches!(parsed.scheme(), "http" | "https")
        || parsed.host_str() != Some("textures.minecraft.net")
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return Err("Неизвестный источник текстуры".into());
    }
    parsed
        .set_scheme("https")
        .map_err(|_| "Некорректный адрес текстуры")?;
    let hash = parsed
        .path()
        .strip_prefix("/texture/")
        .filter(|s| (32..=64).contains(&s.len()) && s.bytes().all(|b| b.is_ascii_hexdigit()))
        .ok_or("Некорректный хеш текстуры")?;
    let path = cx
        .root
        .join("cache/skin-textures")
        .join(format!("{hash}.png"));
    Ok((parsed, path))
}
fn texture_cached(cx: &Context, url: &str) -> Result<Vec<u8>> {
    let (_, path) = texture_source(cx, url)?;
    let mut bytes = Vec::new();
    fs::File::open(path)
        .map_err(err)?
        .take(1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(err)?;
    if bytes.len() > 1024 * 1024 || !bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Err("Некорректная текстура в кэше".into());
    }
    Ok(bytes)
}
fn texture(cx: &Context, url: &str) -> Result<Vec<u8>> {
    if let Ok(bytes) = texture_cached(cx, url) {
        return Ok(bytes);
    }
    let (url, path) = texture_source(cx, url)?;
    let response = cx
        .client()?
        .get(url)
        .send()
        .map_err(|_| "Нет связи с сервером текстур")?;
    if !response.status().is_success() {
        return Err("Не удалось скачать текстуру".into());
    }
    let mut bytes = Vec::new();
    response
        .take(1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(err)?;
    if bytes.len() > 1024 * 1024 {
        return Err("Текстура слишком большая".into());
    }
    if !bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Err("Сервер вернул некорректный PNG".into());
    }
    write_bytes(&path, &bytes)?;
    Ok(bytes)
}
fn summary(cx: &Context, owner: &Value, cached_only: bool) -> Result<Value> {
    let mut result = json!({"id":owner["uuid"],"name":owner["name"],"model":owner["skinModel"],"capes":owner["capes"],"capeId":owner["capeId"]});
    if let Some(url) = owner["skinUrl"].as_str() {
        if let Ok(bytes) = if cached_only {
            texture_cached(cx, url)
        } else {
            texture(cx, url)
        } {
            result["png"] = json!(png(&bytes));
        }
    }
    Ok(result)
}
fn profile(cx: &Context, account: &Value) -> Result<Value> {
    let p = response_json(
        cx.client()?
            .get(PROFILE)
            .bearer_auth(account["accessToken"].as_str().ok_or("Нет токена")?)
            .send()
            .map_err(|_| "Нет связи с Minecraft")?,
    )?;
    let mut check = account.clone();
    accounts::appearance(&mut check, &p)?;
    Ok(p)
}
pub fn handle(cx: &Context, r: &Value) -> Result<Value> {
    let op = r["op"].as_str().unwrap_or("");
    let mut entries = list(cx)?;
    if op == "accountSkinRead" {
        let source = if let Some(png) = r["options"]["png"].as_str() {
            png.to_owned()
        } else {
            cx.check()?;
            let Some(path) = rfd::FileDialog::new()
                .set_title("Выбрать PNG скина")
                .add_filter("PNG", &["png"])
                .pick_file()
            else {
                return Ok(Value::Null);
            };
            let mut bytes = Vec::new();
            fs::File::open(path)
                .map_err(err)?
                .take(1024 * 1024 + 1)
                .read_to_end(&mut bytes)
                .map_err(err)?;
            if bytes.len() > 1024 * 1024 {
                return Err("Скин больше 1 МБ".into());
            }
            png(&bytes)
        };
        let (bytes, model) = decode(&source)?;
        return Ok(json!({"png":png(&bytes),"model":model}));
    }
    let store = accounts::store(cx)?;
    let owner = store["accounts"]
        .as_array()
        .unwrap()
        .iter()
        .find(|a| a["uuid"] == r["file"] && a["type"] == "MICROSOFT")
        .ok_or("Выбери лицензионный аккаунт Minecraft")?
        .clone();
    let index = cx.root.join("skins/library.json");
    match op {
        "accountSkinState" => {
            Ok(json!({"profile":summary(cx,&owner,true)?,"saved":entries,"presets":presets()?}))
        }
        "accountSkinSave" => {
            let (bytes, _) = decode(r["options"]["png"].as_str().ok_or("Выбери текстуру")?)?;
            let model = r["kind"]
                .as_str()
                .filter(|s| matches!(*s, "CLASSIC" | "SLIM"))
                .ok_or("Выбери модель рук")?;
            let cape = &r["options"]["capeId"];
            if !cape.is_null()
                && !owner["capes"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .any(|c| c["id"] == *cape)
            {
                return Err("Этот плащ недоступен аккаунту".into());
            }
            let existing = r["project"].as_str().filter(|s| !s.is_empty());
            let key = existing
                .map(str::to_owned)
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
            id(&key)?;
            if existing.is_some() && !entries.iter().any(|e| e["id"] == key) {
                return Err("Скин удалён из библиотеки".into());
            }
            let name = r["title"]
                .as_str()
                .unwrap_or("")
                .trim()
                .chars()
                .take(80)
                .collect::<String>();
            let item = json!({"id":key,"name":if name.is_empty(){"Мой скин"}else{&name},"model":model,"capeId":cape});
            let path = cx.root.join("skins").join(format!("{key}.png"));
            let previous = fs::read(&path).ok();
            write_bytes(&path, &bytes)?;
            if let Some(old) = entries.iter_mut().find(|e| e["id"] == key) {
                *old = item;
            } else {
                entries.push(item);
            }
            if let Err(e) = write_json(&index, &json!(entries)) {
                if let Some(bytes) = previous {
                    let _ = write_bytes(&path, &bytes);
                } else {
                    let _ = fs::remove_file(path);
                }
                return Err(e);
            }
            Ok(json!({"saved":entries,"selected":key}))
        }
        "accountSkinRemove" => {
            let key = id(r["project"].as_str().ok_or("Нет ID")?)?;
            entries.retain(|e| e["id"] != key);
            write_json(&index, &json!(entries))?;
            let _ = fs::remove_file(cx.root.join("skins").join(format!("{key}.png")));
            Ok(json!(entries))
        }
        "accountSkinMove" => {
            let key = r["project"].as_str().ok_or("Нет ID")?;
            if let Some(i) = entries.iter().position(|e| e["id"] == key) {
                let e = entries.remove(i);
                let dest = r["options"]["position"].as_u64().ok_or("Выбери позицию")? as usize;
                entries.insert(dest.min(entries.len()), e);
                write_json(&index, &json!(entries))?;
            }
            Ok(json!(entries))
        }
        "accountSkinTextures" => {
            let ids = r["options"]["ids"]
                .as_array()
                .filter(|a| (1..=12).contains(&a.len()))
                .ok_or("Слишком много текстур")?;
            let mut result = Vec::new();
            for key in ids {
                cx.check()?;
                let key = key.as_str().ok_or("Нет ID")?;
                let bytes = if let Some(cape) = key.strip_prefix("cape:") {
                    let c = owner["capes"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .find(|c| c["id"] == cape)
                        .ok_or("Плащ недоступен")?;
                    texture(cx, c["url"].as_str().ok_or("Нет URL плаща")?)
                } else if entries.iter().any(|e| e["id"] == key) {
                    fs::read(cx.root.join("skins").join(format!("{}.png", id(key)?))).map_err(err)
                } else if let Some(preset) = presets()?.iter().find(|p| p["id"] == key) {
                    preset_texture(cx, preset)
                } else {
                    Err("Скин не найден".into())
                };
                result.push(match bytes {
                    Ok(b) => json!({"id":key,"png":png(&b)}),
                    Err(e) => json!({"id":key,"error":e}),
                });
            }
            Ok(json!(result))
        }
        "accountSkinProfile" | "accountSkinApply" => {
            let account = accounts::ready(cx, owner.clone())?;
            let token = account["accessToken"].as_str().ok_or("Нет токена")?;
            let mut p = profile(cx, &account)?;
            if op == "accountSkinApply" {
                let client = cx.client()?;
                if r["options"]["changeSkin"] != false {
                    let request = if let Some(source) = r["options"]["png"].as_str() {
                        let (bytes, _) = decode(source)?;
                        let model = match r["kind"].as_str() {
                            Some("CLASSIC") => "classic",
                            Some("SLIM") => "slim",
                            _ => return Err("Выбери модель рук".into()),
                        };
                        let part = reqwest::blocking::multipart::Part::bytes(bytes)
                            .file_name("skin.png")
                            .mime_str("image/png")
                            .map_err(err)?;
                        client.post(format!("{PROFILE}/skins")).multipart(
                            reqwest::blocking::multipart::Form::new()
                                .text("variant", model)
                                .part("file", part),
                        )
                    } else {
                        client.delete(format!("{PROFILE}/skins/active"))
                    };
                    let response = request
                        .bearer_auth(token)
                        .send()
                        .map_err(|_| "Нет связи с Minecraft")?;
                    if !response.status().is_success() {
                        return Err(format!(
                            "Minecraft отклонил скин: HTTP {}",
                            response.status().as_u16()
                        ));
                    }
                }
                if r["options"]["changeCape"] == true {
                    let cape = &r["options"]["capeId"];
                    if !cape.is_null()
                        && !p["capes"]
                            .as_array()
                            .into_iter()
                            .flatten()
                            .any(|c| c["id"] == *cape)
                    {
                        return Err("Этот плащ недоступен аккаунту".into());
                    }
                    let request = if cape.is_null() {
                        client.delete(format!("{PROFILE}/capes/active"))
                    } else {
                        client
                            .put(format!("{PROFILE}/capes/active"))
                            .json(&json!({"capeId":cape}))
                    };
                    let response = request
                        .bearer_auth(token)
                        .send()
                        .map_err(|_| "Нет связи с Minecraft")?;
                    if !response.status().is_success() {
                        return Err(format!(
                            "Minecraft отклонил плащ: HTTP {}",
                            response.status().as_u16()
                        ));
                    }
                }
                p = profile(cx, &account)?;
            }
            // Persist appearance over the encrypted account, never the in-memory bearer.
            let mut saved = accounts::store(cx)?["accounts"]
                .as_array()
                .unwrap()
                .iter()
                .find(|a| a["uuid"] == owner["uuid"])
                .ok_or("Аккаунт удалён")?
                .clone();
            accounts::appearance(&mut saved, &p)?;
            let data = accounts::upsert(cx, saved.clone(), false)?;
            Ok(json!({"profile":summary(cx,&saved,false)?,"accounts":accounts::public(&data)}))
        }
        _ => Err("Неизвестное действие скина".into()),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn account_state_uses_cached_texture_without_revealing_tokens() {
        let root = std::env::temp_dir().join(format!("aw-skin-cache-{}", uuid::Uuid::new_v4()));
        let cx = Context {
            root: root.clone(),
            cancelled: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
            event: &|_| {},
        };
        let hash = "ab".repeat(32);
        let image = image::RgbaImage::new(64, 64);
        let mut out = Cursor::new(Vec::new());
        image.write_to(&mut out, image::ImageFormat::Png).unwrap();
        write_bytes(
            &root.join("cache/skin-textures").join(format!("{hash}.png")),
            out.get_ref(),
        )
        .unwrap();
        write_json(&root.join("accounts.json"),&json!({"accounts":[{"uuid":"test-account","type":"MICROSOFT","name":"Player","accessToken":"dpapi:unreadable-secret","refreshToken":"unavailable:","skinUrl":format!("http://textures.minecraft.net/texture/{hash}"),"skinModel":"CLASSIC","capes":[]}]})).unwrap();
        let value = handle(&cx, &json!({"op":"accountSkinState","file":"test-account"})).unwrap();
        assert!(value["profile"]["png"]
            .as_str()
            .unwrap()
            .starts_with("data:image/png;base64,"));
        assert!(!value.to_string().contains("unreadable-secret"));
        let (source, _) = texture_source(
            &cx,
            &format!("http://textures.minecraft.net/texture/{hash}"),
        )
        .unwrap();
        assert_eq!(source.scheme(), "https");
        assert!(texture_source(
            &cx,
            &format!("https://user:password@textures.minecraft.net/texture/{hash}")
        )
        .is_err());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn reject_oversize_and_wrong_dimensions() {
        assert!(decode("data:image/png;base64,AAAA").is_err());
        let image = image::RgbaImage::new(128, 128);
        let mut bytes = Cursor::new(Vec::new());
        image.write_to(&mut bytes, image::ImageFormat::Png).unwrap();
        assert!(decode(&png(bytes.get_ref())).is_err());
    }
    #[test]
    fn model_and_normalization() {
        let image = image::RgbaImage::new(64, 64);
        let mut bytes = Cursor::new(Vec::new());
        image.write_to(&mut bytes, image::ImageFormat::Png).unwrap();
        let (out, model) = decode(&png(bytes.get_ref())).unwrap();
        assert_eq!(model, "SLIM");
        assert!(out.starts_with(b"\x89PNG"));
    }
}
