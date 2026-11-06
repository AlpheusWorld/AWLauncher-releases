use crate::{err, Result};
use base64::{engine::general_purpose::STANDARD, Engine};
pub fn protect(secret: &str) -> Result<String> {
    if secret.is_empty() {
        return Ok(String::new());
    }
    #[cfg(windows)]
    {
        Ok(format!(
            "dpapi:{}",
            STANDARD.encode(crypt(secret.as_bytes(), true)?)
        ))
    }
    #[cfg(not(windows))]
    {
        use aes_gcm::{
            aead::{Aead, Payload},
            Aes256Gcm, KeyInit,
        };
        let key = linux_key(true)?;
        let cipher = Aes256Gcm::new_from_slice(&key).map_err(err)?;
        let mut nonce = [0; 12];
        getrandom::getrandom(&mut nonce).map_err(err)?;
        let mut data = nonce.to_vec();
        data.extend(
            cipher
                .encrypt(
                    (&nonce).into(),
                    Payload {
                        msg: secret.as_bytes(),
                        aad: b"secret-service:",
                    },
                )
                .map_err(err)?,
        );
        Ok(format!("secret-service:{}", STANDARD.encode(data)))
    }
}
pub fn reveal(secret: &str) -> Result<String> {
    if let Some(data) = secret.strip_prefix("plain:") {
        return String::from_utf8(STANDARD.decode(data).map_err(err)?).map_err(err);
    }
    if secret.starts_with("unavailable:") {
        return Err("Войди через Microsoft заново".into());
    }
    if let Some(data) = secret.strip_prefix("dpapi:") {
        #[cfg(windows)]
        {
            return String::from_utf8(crypt(&STANDARD.decode(data).map_err(err)?, false)?)
                .map_err(err);
        }
        #[cfg(not(windows))]
        {
            let _ = data;
            return Err("Токен защищён на другом компьютере".into());
        }
    }
    if let Some(data) = secret.strip_prefix("secret-service:") {
        #[cfg(not(windows))]
        {
            use aes_gcm::{
                aead::{Aead, Payload},
                Aes256Gcm, KeyInit,
            };
            let bytes = STANDARD.decode(data).map_err(err)?;
            if bytes.len() < 28 {
                return Err("Повреждён токен".into());
            }
            let cipher = Aes256Gcm::new_from_slice(&linux_key(false)?).map_err(err)?;
            return String::from_utf8(
                cipher
                    .decrypt(
                        (&bytes[..12]).into(),
                        Payload {
                            msg: &bytes[12..],
                            aad: b"secret-service:",
                        },
                    )
                    .map_err(err)?,
            )
            .map_err(err);
        }
        #[cfg(windows)]
        {
            let _ = data;
            return Err("Токен защищён на другом компьютере".into());
        }
    }
    Ok(secret.into())
}
#[cfg(windows)]
fn crypt(data: &[u8], encrypt: bool) -> Result<Vec<u8>> {
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::Cryptography::{
            CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
        },
    };
    let input = CRYPT_INTEGER_BLOB {
        cbData: data.len().try_into().map_err(err)?,
        pbData: data.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    unsafe {
        let ok = if encrypt {
            CryptProtectData(
                &input,
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptUnprotectData(
                &input,
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        if ok == 0 {
            return Err("Не удалось открыть защищённое хранилище токенов".into());
        }
        let bytes = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData as _);
        Ok(bytes)
    }
}
#[cfg(not(windows))]
fn linux_key(create: bool) -> Result<Vec<u8>> {
    use std::{
        io::Write,
        process::{Command, Stdio},
    };
    let attrs = [
        "application",
        "awlauncher",
        "purpose",
        "token-encryption-v1",
    ];
    let lookup = || -> Result<String> {
        let out = Command::new("secret-tool")
            .arg("lookup")
            .args(attrs)
            .output()
            .map_err(|_| "Установи libsecret-tools и открой хранилище ключей")?;
        Ok(String::from_utf8(out.stdout).map_err(err)?.trim().into())
    };
    let mut value = lookup()?;
    if value.is_empty() && create {
        let mut key = [0; 32];
        getrandom::getrandom(&mut key).map_err(err)?;
        let encoded = STANDARD.encode(key);
        let mut child = Command::new("secret-tool")
            .args(["store", "--label=AWLauncher token encryption"])
            .args(attrs)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(err)?;
        child
            .stdin
            .take()
            .ok_or("Нет входного потока keyring")?
            .write_all(encoded.as_bytes())
            .map_err(err)?;
        if !child.wait().map_err(err)?.success() {
            return Err("Хранилище ключей заблокировано".into());
        }
        value = lookup()?;
    }
    let key = STANDARD.decode(value).map_err(err)?;
    if key.len() != 32 {
        return Err("Ключ токенов недоступен".into());
    }
    Ok(key)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn protected_roundtrip() {
        let s = protect("sensitive-test-value").unwrap();
        assert!(!s.contains("sensitive-test-value"));
        assert_eq!(reveal(&s).unwrap(), "sensitive-test-value");
    }
}
