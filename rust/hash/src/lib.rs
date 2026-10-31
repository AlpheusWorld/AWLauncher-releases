use sha1::{Digest, Sha1};
use sha2::Sha256;
use std::{io::{self, Read}, path::Path};

fn hash_file<D: Digest>(path: &Path) -> io::Result<Vec<u8>> {
    let mut file = std::fs::File::open(path)?;
    let mut hash = D::new();
    let mut buffer = [0u8; 65536];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 { break; }
        hash.update(&buffer[..count]);
    }
    Ok(hash.finalize().to_vec())
}

/// Returns 0 on success; invalid input, IO errors and panics return a nonzero status.
/// The caller must provide a valid UTF-8 path and a writable 20-byte output buffer.
unsafe fn hash<D: Digest>(path: *const u8, len: usize, out: *mut u8) -> i32 {
    if path.is_null() || out.is_null() || len == 0 || len > 1024 * 1024 { return 1; }
    std::panic::catch_unwind(|| {
        let bytes = std::slice::from_raw_parts(path, len);
        let path = std::str::from_utf8(bytes).map_err(|_| ())?;
        let hash = hash_file::<D>(Path::new(path)).map_err(|_| ())?;
        std::ptr::copy_nonoverlapping(hash.as_ptr(), out, hash.len());
        Ok::<(), ()>(())
    }).ok().and_then(Result::ok).map_or(1, |_| 0)
}

#[no_mangle]
pub unsafe extern "C" fn aw_sha1(path: *const u8, len: usize, out: *mut u8) -> i32 {
    hash::<Sha1>(path, len, out)
}

/// The caller must provide a writable 32-byte output buffer.
#[no_mangle]
pub unsafe extern "C" fn aw_sha256(path: *const u8, len: usize, out: *mut u8) -> i32 {
    hash::<Sha256>(path, len, out)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_null_and_missing_inputs() {
        let mut out = [0u8; 20];
        assert_ne!(unsafe { aw_sha1(std::ptr::null(), 1, out.as_mut_ptr()) }, 0);
        let path = b"aw-hash-file-that-does-not-exist";
        assert_ne!(unsafe { aw_sha1(path.as_ptr(), path.len(), out.as_mut_ptr()) }, 0);
        let mut out256 = [0u8; 32];
        assert_ne!(unsafe { aw_sha256(path.as_ptr(), path.len(), out256.as_mut_ptr()) }, 0);
    }
}
