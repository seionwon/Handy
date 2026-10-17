//! Remove temporary installer apps from app search once the installed app runs.
//! Only marked, read-only Handy disk images are eligible; disks are never forced.

use plist::Value;
use std::path::{Component, Path, PathBuf};
use std::process::Command;

fn field<'a>(value: &'a Value, key: &str) -> Option<&'a Value> {
    value.as_dictionary()?.get(key)
}

fn installed_bundle(executable: &Path, user_applications: Option<&Path>) -> bool {
    let Some(bundle) = executable.ancestors().nth(3) else {
        return false;
    };
    bundle.file_name().is_some_and(|name| name == "Handy.app")
        && bundle.parent().is_some_and(|parent| {
            parent == Path::new("/Applications") || Some(parent) == user_applications
        })
}

fn installer_mounts(info: &Value, volumes: &Path) -> Vec<PathBuf> {
    let Some(images) = field(info, "images").and_then(Value::as_array) else {
        return Vec::new();
    };
    images
        .iter()
        .filter(|image| {
            field(image, "writeable").and_then(Value::as_boolean) == Some(false)
                // hdiutil omits this key for ordinary user disk images.
                && field(image, "system-image").and_then(Value::as_boolean) != Some(true)
                && field(image, "image-path")
                    .and_then(Value::as_string)
                    .is_some_and(|path| Path::new(path).extension().is_some_and(|ext| ext == "dmg"))
        })
        .filter_map(|image| field(image, "system-entities").and_then(Value::as_array))
        .flatten()
        .filter_map(|entity| field(entity, "mount-point").and_then(Value::as_string))
        .map(PathBuf::from)
        .filter(|mount| {
            mount.parent() == Some(volumes)
                && !mount
                    .components()
                    .any(|c| matches!(c, Component::ParentDir))
        })
        .collect()
}

fn is_handy_installer(mount: &Path) -> bool {
    let marker_path = mount.join(".handy-installer.json");
    let Ok(marker_info) = std::fs::symlink_metadata(&marker_path) else {
        return false;
    };
    if !marker_info.is_file() || marker_info.len() > 4096 {
        return false;
    }
    let marker = std::fs::read(marker_path)
        .ok()
        .and_then(|data| serde_json::from_slice::<serde_json::Value>(&data).ok());
    if !marker.is_some_and(|marker| {
        marker["identifier"] == "com.pais.handy" && marker["installer_format"] == 1
    }) {
        return false;
    }
    let app = mount.join("Handy.app");
    if !std::fs::symlink_metadata(&app).is_ok_and(|entry| entry.is_dir()) {
        return false;
    }
    Value::from_file(app.join("Contents/Info.plist"))
        .ok()
        .and_then(|info| {
            field(&info, "CFBundleIdentifier")
                .and_then(Value::as_string)
                .map(str::to_owned)
        })
        .is_some_and(|identifier| identifier == "com.pais.handy")
}

pub fn cleanup_installers() {
    let Ok(executable) = std::env::current_exe() else {
        return;
    };
    let user_applications =
        std::env::var_os("HOME").map(|home| PathBuf::from(home).join("Applications"));
    if !installed_bundle(&executable, user_applications.as_deref()) {
        return;
    }
    std::thread::spawn(|| {
        let output = match Command::new("/usr/bin/hdiutil")
            .args(["info", "-plist"])
            .output()
        {
            Ok(output) if output.status.success() => output,
            _ => return,
        };
        let Ok(info) = Value::from_reader(std::io::Cursor::new(output.stdout)) else {
            return;
        };
        for mount in installer_mounts(&info, Path::new("/Volumes")) {
            if !is_handy_installer(&mount) {
                continue;
            }
            let app = mount.join("Handy.app");
            let _ = Command::new("/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister")
                .arg("-u").arg(&app).output();
            match Command::new("/usr/bin/hdiutil")
                .arg("detach")
                .arg(&mount)
                .output()
            {
                Ok(output) if output.status.success() => {
                    log::info!("Ejected Handy installer: {}", mount.display())
                }
                _ => log::info!(
                    "Handy installer remains mounted (possibly busy): {}",
                    mount.display()
                ),
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn image(writeable: bool, system: bool, filename: &str, mount: &str) -> Value {
        let mut entity = plist::Dictionary::new();
        entity.insert("mount-point".into(), Value::String(mount.into()));
        let mut image = plist::Dictionary::new();
        image.insert("writeable".into(), Value::Boolean(writeable));
        image.insert("system-image".into(), Value::Boolean(system));
        image.insert("image-path".into(), Value::String(filename.into()));
        image.insert(
            "system-entities".into(),
            Value::Array(vec![Value::Dictionary(entity)]),
        );
        let mut info = plist::Dictionary::new();
        info.insert(
            "images".into(),
            Value::Array(vec![Value::Dictionary(image)]),
        );
        Value::Dictionary(info)
    }

    #[test]
    fn only_installed_app_may_eject_installers() {
        let home_apps = Path::new("/Users/test/Applications");
        assert!(installed_bundle(
            Path::new("/Applications/Handy.app/Contents/MacOS/handy"),
            Some(home_apps)
        ));
        assert!(installed_bundle(
            Path::new("/Users/test/Applications/Handy.app/Contents/MacOS/handy"),
            Some(home_apps)
        ));
        for path in [
            "/Volumes/Handy/Handy.app/Contents/MacOS/handy",
            "/tmp/Handy.app/Contents/MacOS/handy",
            "/Applications/Other.app/Contents/MacOS/handy",
        ] {
            assert!(!installed_bundle(Path::new(path), Some(home_apps)));
        }
    }

    #[test]
    fn only_read_only_nonsystem_dmg_mounts_are_candidates() {
        let volumes = Path::new("/Volumes");
        assert_eq!(
            installer_mounts(
                &image(false, false, "/tmp/Handy.dmg", "/Volumes/Handy"),
                volumes
            ),
            vec![PathBuf::from("/Volumes/Handy")]
        );
        let mut ordinary_image = image(false, false, "/tmp/Handy.dmg", "/Volumes/Handy");
        ordinary_image
            .as_dictionary_mut()
            .unwrap()
            .get_mut("images")
            .unwrap()
            .as_array_mut()
            .unwrap()[0]
            .as_dictionary_mut()
            .unwrap()
            .remove("system-image");
        assert_eq!(
            installer_mounts(&ordinary_image, volumes),
            vec![PathBuf::from("/Volumes/Handy")]
        );
        for info in [
            image(true, false, "/tmp/Handy.dmg", "/Volumes/Handy"),
            image(false, true, "/tmp/Handy.dmg", "/Volumes/Handy"),
            image(false, false, "/tmp/Other.sparsebundle", "/Volumes/Handy"),
            image(false, false, "/tmp/Handy.dmg", "/Applications"),
            image(false, false, "/tmp/Handy.dmg", "/Volumes/../Applications"),
        ] {
            assert!(installer_mounts(&info, volumes).is_empty());
        }
    }

    #[test]
    fn installer_requires_both_marker_and_matching_bundle() {
        let volume = tempfile::tempdir().unwrap();
        let root = volume.path();
        assert!(!is_handy_installer(root));
        std::fs::write(
            root.join(".handy-installer.json"),
            r#"{"identifier":"com.pais.handy","installer_format":1}"#,
        )
        .unwrap();
        assert!(!is_handy_installer(root));
        std::fs::create_dir_all(root.join("Handy.app/Contents")).unwrap();
        let mut info = plist::Dictionary::new();
        info.insert(
            "CFBundleIdentifier".into(),
            Value::String("other.application".into()),
        );
        Value::Dictionary(info.clone())
            .to_file_xml(root.join("Handy.app/Contents/Info.plist"))
            .unwrap();
        assert!(!is_handy_installer(root));
        info.insert(
            "CFBundleIdentifier".into(),
            Value::String("com.pais.handy".into()),
        );
        Value::Dictionary(info)
            .to_file_xml(root.join("Handy.app/Contents/Info.plist"))
            .unwrap();
        assert!(is_handy_installer(root));
        std::fs::remove_dir_all(root.join("Handy.app")).unwrap();
        std::os::unix::fs::symlink("/Applications/Handy.app", root.join("Handy.app")).unwrap();
        assert!(!is_handy_installer(root));
    }
}
