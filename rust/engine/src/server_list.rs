use crate::{err, storage, write_bytes, Result};
use std::{fs, io::Read, path::Path};

/// Keep every retained NBT tag byte for byte, including data from newer clients.
pub fn remove_legacy_default(game: &Path) -> Result<bool> {
    let path = storage::path(game, "servers.dat")?;
    let file = match fs::File::open(&path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(err(error)),
    };
    let mut bytes = Vec::new();
    file.take(16 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(err)?;
    if bytes.len() > 16 * 1024 * 1024 {
        return Err("Список серверов слишком большой".into());
    }
    if let Some(updated) = filter(&bytes).map_err(|_| "Некорректный servers.dat")? {
        write_bytes(&path, &updated)?;
        return Ok(true);
    }
    Ok(false)
}

struct Nbt<'a> {
    data: &'a [u8],
    pos: usize,
}
impl<'a> Nbt<'a> {
    fn take(&mut self, length: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(length)?;
        let value = self.data.get(self.pos..end)?;
        self.pos = end;
        Some(value)
    }
    fn byte(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }
    fn text(&mut self) -> Option<&'a [u8]> {
        let size = u16::from_be_bytes(self.take(2)?.try_into().ok()?) as usize;
        self.take(size)
    }
    fn length(&mut self) -> Option<usize> {
        let count = i32::from_be_bytes(self.take(4)?.try_into().ok()?);
        usize::try_from(count)
            .ok()
            .filter(|n| *n <= self.data.len())
    }
    fn skip(&mut self, tag: u8, depth: usize) -> Option<()> {
        if depth > 64 {
            return None;
        }
        match tag {
            1 => {
                self.take(1)?;
            }
            2 => {
                self.take(2)?;
            }
            3 | 5 => {
                self.take(4)?;
            }
            4 | 6 => {
                self.take(8)?;
            }
            7 | 11 | 12 => {
                let size = self.length()?.checked_mul(match tag {
                    7 => 1,
                    11 => 4,
                    _ => 8,
                })?;
                self.take(size)?;
            }
            8 => {
                self.text()?;
            }
            9 => {
                let item = self.byte()?;
                let count = self.length()?;
                if item > 12 || item == 0 && count != 0 {
                    return None;
                }
                for _ in 0..count {
                    self.skip(item, depth + 1)?;
                }
            }
            10 => loop {
                let item = self.byte()?;
                if item == 0 {
                    break;
                }
                self.text()?;
                self.skip(item, depth + 1)?;
            },
            _ => return None,
        }
        Some(())
    }
    fn is_legacy(&mut self) -> Option<bool> {
        let (mut name, mut ip) = (None, None);
        loop {
            let tag = self.byte()?;
            if tag == 0 {
                break;
            }
            let key = self.text()?;
            match (tag, key) {
                (8, b"name") => name = Some(self.text()?),
                (8, b"ip") => ip = Some(self.text()?),
                (_, b"name") => {
                    name = None;
                    self.skip(tag, 1)?;
                }
                (_, b"ip") => {
                    ip = None;
                    self.skip(tag, 1)?;
                }
                _ => self.skip(tag, 1)?,
            }
        }
        let ip = ip
            .and_then(|ip| std::str::from_utf8(ip).ok())
            .unwrap_or("")
            .trim()
            .to_ascii_lowercase();
        let ip = ip.strip_suffix('.').unwrap_or(&ip);
        let ip = ip.strip_suffix(":25565").unwrap_or(ip);
        Some(name == Some(b"VirtusMine") && ip == "mc.virtusmine.fun")
    }
}
fn filter(bytes: &[u8]) -> std::result::Result<Option<Vec<u8>>, ()> {
    fn parse(bytes: &[u8]) -> Option<Option<Vec<u8>>> {
        let mut input = Nbt {
            data: bytes,
            pos: 0,
        };
        if input.byte()? != 10 {
            return None;
        }
        input.text()?;
        let mut updated = None;
        let mut found = false;
        loop {
            let tag = input.byte()?;
            if tag == 0 {
                return (input.pos == bytes.len()).then_some(updated);
            }
            let key = input.text()?;
            if tag != 9 || key != b"servers" {
                input.skip(tag, 1)?;
                continue;
            }
            if found {
                return None;
            }
            found = true;
            let item = input.byte()?;
            let count_start = input.pos;
            let count = input.length()?;
            if item != 10 {
                if count != 0 {
                    return None;
                }
                continue;
            }
            let mut kept = 0i32;
            let mut output = bytes[..count_start + 4].to_vec();
            for _ in 0..count {
                let start = input.pos;
                if !input.is_legacy()? {
                    kept += 1;
                    output.extend_from_slice(&bytes[start..input.pos]);
                }
            }
            if kept as usize != count {
                output[count_start..count_start + 4].copy_from_slice(&kept.to_be_bytes());
                output.extend_from_slice(&bytes[input.pos..]);
                updated = Some(output);
            }
        }
    }
    parse(bytes).ok_or(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removes_only_the_launcher_entry_and_keeps_unknown_fields() {
        fn string(out: &mut Vec<u8>, text: &[u8]) {
            out.extend((text.len() as u16).to_be_bytes());
            out.extend(text);
        }
        fn server(name: &[u8], ip: &[u8]) -> Vec<u8> {
            let mut out = vec![];
            for (key, value) in [(b"name".as_slice(), name), (b"ip", ip)] {
                out.push(8);
                string(&mut out, key);
                string(&mut out, value);
            }
            out.extend([1, 0, 1, b'x', 42, 0]);
            out
        }
        let keep = server(b"My server", b"mc.virtusmine.fun");
        let mut header = vec![10, 0, 0, 9];
        string(&mut header, b"servers");
        header.push(10);
        let mut bytes = header.clone();
        bytes.extend(2i32.to_be_bytes());
        bytes.extend(server(b"VirtusMine", b"MC.VIRTUSMINE.FUN:25565"));
        bytes.extend(&keep);
        bytes.push(0);
        header.extend(1i32.to_be_bytes());
        header.extend(keep);
        header.push(0);
        assert_eq!(filter(&bytes), Ok(Some(header.clone())));
        assert_eq!(filter(&header), Ok(None));
        assert_eq!(filter(&bytes[..bytes.len() - 1]), Err(()));
    }
}
