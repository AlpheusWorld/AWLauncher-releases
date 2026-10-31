use std::{
    fs, io,
    path::{Component, Path, PathBuf},
};

#[derive(Clone, Debug)]
pub struct Entry {
    pub name: String,
    pub relative: PathBuf,
    pub directory: bool,
    pub bytes: u64,
}

pub fn list(root: &Path, relative: &Path) -> io::Result<Vec<Entry>> {
    let root_meta = match fs::symlink_metadata(root) {
        Ok(meta) => meta,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(err) => return Err(err),
    };
    if !root_meta.is_dir() || root_meta.file_type().is_symlink() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Invalid folder",
        ));
    }
    let mut current = root.to_path_buf();
    for component in relative.components() {
        let Component::Normal(part) = component else {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Invalid relative path",
            ));
        };
        current.push(part);
        let metadata = fs::symlink_metadata(&current)?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Invalid folder",
            ));
        }
    }
    let mut entries = Vec::new();
    for entry in fs::read_dir(current)? {
        let entry = entry?;
        let metadata = fs::symlink_metadata(entry.path())?;
        if metadata.file_type().is_symlink() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        entries.push(Entry {
            name,
            relative: relative.join(entry.file_name()),
            directory: metadata.is_dir(),
            bytes: if metadata.is_dir() { 0 } else { metadata.len() },
        });
    }
    entries.sort_by(|a, b| {
        b.directory
            .cmp(&a.directory)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    Ok(entries)
}

pub fn show_folder(root: &Path, relative: &Path) -> io::Result<()> {
    list(root, relative)?; // Validate every path component before invoking the file manager.
    let folder = root.join(relative);
    #[cfg(windows)]
    let mut command = std::process::Command::new("explorer.exe");
    #[cfg(not(windows))]
    let mut command = std::process::Command::new("xdg-open");
    command.arg(folder).spawn().map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lists_and_sorts_within_root_and_rejects_escape() {
        let root = std::env::temp_dir().join(format!("aw-file-test-{}", std::process::id()));
        fs::create_dir_all(root.join("world")).unwrap();
        fs::write(root.join("a.txt"), b"abc").unwrap();
        fs::write(root.join("world/level.dat"), b"level").unwrap();
        let entries = list(&root, Path::new("")).unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].name, "world");
        assert_eq!(entries[1].bytes, 3);
        assert_eq!(
            list(&root, Path::new("world")).unwrap()[0].name,
            "level.dat"
        );
        assert!(list(&root, Path::new("../outside")).is_err());
        assert!(list(&root, Path::new("world/../../outside")).is_err());
        fs::remove_dir_all(root).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn ignores_symbolic_links_and_refuses_linked_directories() {
        use std::os::unix::fs::symlink;
        let root = std::env::temp_dir().join(format!("aw-symlink-test-{}", std::process::id()));
        fs::create_dir_all(root.join("normal")).unwrap();
        symlink(root.join("normal"), root.join("shortcut")).unwrap();
        let entries = list(&root, Path::new("")).unwrap();
        assert_eq!(entries.len(), 1);
        assert!(list(&root, Path::new("shortcut")).is_err());
        fs::remove_dir_all(root).unwrap();
    }
}
