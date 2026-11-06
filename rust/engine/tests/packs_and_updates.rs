use aw_engine::{packs, storage, write_bytes, Context};
use serde_json::{json, Value};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::{atomic::AtomicBool, Arc},
};
struct Temp(PathBuf);
impl Temp {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!("aw-pack-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&p).unwrap();
        Self(p)
    }
    fn cx(&self) -> Context<'_> {
        Context {
            root: self.0.clone(),
            cancelled: Arc::new(AtomicBool::new(false)),
            event: &|_| {},
        }
    }
}
impl Drop for Temp {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn zip_file(path: &Path, index: &Value, entries: &[(&str, &[u8])], curse: bool) {
    let mut zip = zip::ZipWriter::new(fs::File::create(path).unwrap());
    let options = zip::write::SimpleFileOptions::default();
    zip.start_file(
        if curse {
            "manifest.json"
        } else {
            "modrinth.index.json"
        },
        options,
    )
    .unwrap();
    zip.write_all(index.to_string().as_bytes()).unwrap();
    for (name, bytes) in entries {
        zip.start_file(*name, options).unwrap();
        zip.write_all(bytes).unwrap();
    }
    zip.finish().unwrap();
}
#[test]
fn export_and_import_preserve_unknown_files_without_credentials() {
    let temp = Temp::new();
    let cx = temp.cx();
    let dir = temp.0.join("game");
    write_bytes(&dir.join("mods/local.jar"), b"local").unwrap();
    write_bytes(&dir.join("config/custom.txt"), b"settings").unwrap();
    write_bytes(&dir.join("accounts.json"), b"private").unwrap();
    let output = temp.0.join("export.mrpack");
    packs::export(
        &cx,
        &json!({"dir":dir,"version":"1.21.1","loader":"Vanilla"}),
        &json!({"title":"Example","file":output}),
    )
    .unwrap();
    let stage = temp.0.join("stage");
    fs::create_dir_all(&stage).unwrap();
    let (index, _) = packs::unpack(&cx, &output, &stage).unwrap();
    assert_eq!(index["dependencies"]["minecraft"], "1.21.1");
    assert_eq!(fs::read(stage.join("mods/local.jar")).unwrap(), b"local");
    assert_eq!(
        fs::read(stage.join("config/custom.txt")).unwrap(),
        b"settings"
    );
    assert!(!stage.join("accounts.json").exists());
}
#[test]
fn zip_slip_is_rejected_before_live_files_are_touched() {
    let temp = Temp::new();
    let file = temp.0.join("bad.mrpack");
    zip_file(
        &file,
        &json!({"formatVersion":1,"game":"minecraft","dependencies":{"minecraft":"1.21.1"},"files":[]}),
        &[("overrides/../../escape", b"bad")],
        false,
    );
    let stage = temp.0.join("stage");
    assert!(packs::unpack(&temp.cx(), &file, &stage).is_err());
    assert!(!temp.0.join("escape").exists());
}
#[test]
fn curse_manifest_maps_primary_loader_and_overrides() {
    let temp = Temp::new();
    let file = temp.0.join("curse.zip");
    zip_file(
        &file,
        &json!({"manifestType":"minecraftModpack","manifestVersion":1,"minecraft":{"version":"1.21.1","modLoaders":[{"id":"neoforge-21.1.265","primary":true}]},"files":[],"overrides":"overrides"}),
        &[("overrides/config/a.txt", b"value")],
        true,
    );
    let stage = temp.0.join("stage");
    let (index, _) = packs::unpack(&temp.cx(), &file, &stage).unwrap();
    assert_eq!(index["dependencies"]["minecraft"], "1.21.1");
    assert_eq!(index["dependencies"]["neoforge"], "21.1.265");
    assert_eq!(fs::read(stage.join("config/a.txt")).unwrap(), b"value");
}
#[test]
fn sha512_is_checked_even_when_sha1_cached_file_is_valid() {
    use sha2::{Digest, Sha512};
    let temp = Temp::new();
    let stage = temp.0.join("stage");
    write_bytes(&stage.join("mods/a.jar"), b"abc").unwrap();
    let sha1 = storage::hash(&stage.join("mods/a.jar")).unwrap();
    let sha512 = format!("{:x}", Sha512::digest(b"different"));
    let file = temp.0.join("bad.mrpack");
    zip_file(
        &file,
        &json!({"formatVersion":1,"game":"minecraft","dependencies":{"minecraft":"1.21.1"},"files":[{"path":"mods/a.jar","hashes":{"sha1":sha1,"sha512":sha512},"fileSize":3,"downloads":["https://cdn.modrinth.com/unused.jar"]}]}),
        &[],
        false,
    );
    let error = packs::unpack(&temp.cx(), &file, &stage).unwrap_err();
    assert!(error.contains("SHA-512"));
}
#[test]
fn disallowed_download_host_is_never_requested() {
    let temp = Temp::new();
    let file = temp.0.join("bad.mrpack");
    zip_file(
        &file,
        &json!({"formatVersion":1,"game":"minecraft","dependencies":{"minecraft":"1.21.1"},"files":[{"path":"mods/a.jar","hashes":{"sha1":"abc"},"downloads":["https://127.0.0.1/private"]}]}),
        &[],
        false,
    );
    let error = packs::unpack(&temp.cx(), &file, &temp.0.join("stage")).unwrap_err();
    assert!(error.contains("разрешённых"));
}
#[test]
fn signed_update_proof_rejects_modified_documents() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    use ring::signature::{Ed25519KeyPair, KeyPair};
    let key = Ed25519KeyPair::from_seed_unchecked(&[7u8; 32]).unwrap();
    let document = br#"{"version":"2.0.0"}"#;
    let signature = key.sign(document);
    let mut public = vec![
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    ];
    public.extend(key.public_key().as_ref());
    let encoded = STANDARD.encode(public);
    assert!(
        aw_engine::updater::verify_signed_manifest(document, signature.as_ref(), &encoded).is_ok()
    );
    assert!(
        aw_engine::updater::verify_signed_manifest(b"changed", signature.as_ref(), &encoded)
            .is_err()
    );
    assert!(aw_engine::updater::verify_signed_manifest(document, &[0; 64], &encoded).is_err());
}
#[test]
fn versions_do_not_offer_downgrades() {
    assert!(aw_engine::updater::newer("1.0.55", "1.0.54"));
    assert!(!aw_engine::updater::newer("1.0.53", "1.0.54"));
    assert!(aw_engine::updater::newer("1.0.54", "1.0.54-beta"));
    assert!(!aw_engine::updater::newer("1.0.54-beta", "1.0.54"));
}
