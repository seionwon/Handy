use anyhow::{bail, Result};
use std::path::{Component, Path, PathBuf};

/// Resolve only a direct WAV child of the recording directory. A history row
/// or IPC argument must never turn into arbitrary file reads or deletions.
pub fn resolve(recordings_dir: &Path, file_name: &str) -> Result<PathBuf> {
    let name = Path::new(file_name);
    if file_name.contains(['/', '\\', ':', '\0'])
        || !matches!(name.components().next(), Some(Component::Normal(_)))
        || name.components().count() != 1
        || name.extension().and_then(|s| s.to_str()) != Some("wav")
    {
        bail!("Invalid recording file name");
    }
    let root = recordings_dir.canonicalize()?;
    let path = root.join(name);
    match std::fs::symlink_metadata(&path) {
        Ok(metadata) => {
            if metadata.file_type().is_symlink() || !metadata.is_file() {
                bail!("Recording must be a regular WAV file");
            }
            let resolved = path.canonicalize()?;
            if resolved.parent() != Some(root.as_path()) {
                bail!("Recording path is outside the recording directory");
            }
            Ok(resolved)
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(path),
        Err(e) => Err(e.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_traversal_absolute_paths_and_non_audio_files() {
        let root = tempfile::tempdir().unwrap();
        for name in [
            "",
            "..",
            "../secret.wav",
            "/tmp/secret.wav",
            "sub/file.wav",
            "sub\\file.wav",
            "C:\\secret.wav",
            "secret:stream.wav",
            "settings.json",
            "file.wav/..",
        ] {
            assert!(resolve(root.path(), name).is_err(), "accepted {name:?}");
        }
    }

    #[test]
    fn accepts_recordings_and_missing_files_for_cleanup() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("recording.wav");
        std::fs::write(&path, b"audio").unwrap();
        assert_eq!(
            resolve(root.path(), "recording.wav").unwrap(),
            path.canonicalize().unwrap()
        );
        assert!(resolve(root.path(), "missing.wav").is_ok());
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlinks_to_files_outside_recordings() {
        let root = tempfile::tempdir().unwrap();
        let outside = tempfile::NamedTempFile::new().unwrap();
        std::os::unix::fs::symlink(outside.path(), root.path().join("escape.wav")).unwrap();
        assert!(resolve(root.path(), "escape.wav").is_err());
    }
}
