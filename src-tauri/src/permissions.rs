//! Explicit macOS permission actions. Checking permission never installs an
//! input hook, requests a prompt, or modifies the system's privacy database.

fn accessibility_granted() -> bool {
    #[cfg(target_os = "macos")]
    {
        #[link(name = "ApplicationServices", kind = "framework")]
        extern "C" {
            fn AXIsProcessTrusted() -> bool;
        }
        // SAFETY: system query with no pointers or side effects.
        unsafe { AXIsProcessTrusted() }
    }
    #[cfg(not(target_os = "macos"))]
    true
}

pub fn ensure_accessibility() -> Result<(), String> {
    if accessibility_granted() {
        Ok(())
    } else {
        Err("Accessibility permission is required for the input system".into())
    }
}

/// Register this running app with macOS and show its consent dialog once.
/// Opening a settings URL alone does not register a new signing identity.
/// Do not open the pane as well: macOS owns the dialog's Settings action.
#[tauri::command]
#[specta::specta]
pub async fn request_accessibility_permission() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        use std::sync::atomic::{AtomicBool, Ordering};
        static REQUESTED: AtomicBool = AtomicBool::new(false);
        if accessibility_granted() || REQUESTED.swap(true, Ordering::SeqCst) {
            return Ok(());
        }
        log::info!("Requesting macOS Accessibility approval for the current Handy process");
        tauri::async_runtime::spawn_blocking(|| {
            use core_foundation::{
                base::TCFType,
                boolean::CFBoolean,
                dictionary::CFDictionary,
                string::{CFString, CFStringRef},
            };
            #[link(name = "ApplicationServices", kind = "framework")]
            extern "C" {
                static kAXTrustedCheckOptionPrompt: CFStringRef;
                fn AXIsProcessTrustedWithOptions(
                    options: core_foundation::dictionary::CFDictionaryRef,
                ) -> u8;
            }
            // SAFETY: the framework owns the key; the dictionary and boolean
            // stay alive for the synchronous call. No event tap is created.
            unsafe {
                let key = CFString::wrap_under_get_rule(kAXTrustedCheckOptionPrompt);
                let options = CFDictionary::from_CFType_pairs(&[(key, CFBoolean::true_value())]);
                AXIsProcessTrustedWithOptions(options.as_concrete_TypeRef());
            }
        })
        .await
        .map_err(|error| error.to_string())?;
    }
    Ok(())
}

#[cfg(any(target_os = "macos", test))]
fn repair_if_denied(
    check: impl FnOnce() -> bool,
    reset: impl FnOnce() -> Result<(), String>,
) -> Result<bool, String> {
    if check() {
        return Ok(false);
    }
    reset()?;
    Ok(true)
}

/// Explicit recovery action for an obsolete or rejected approval. It can only
/// reset Handy's Accessibility entry, never another app or another service.
#[tauri::command]
#[specta::specta]
pub async fn repair_accessibility_permission(window: tauri::WebviewWindow) -> Result<bool, String> {
    if window.label() != "main" {
        return Err("Permission recovery is only available in the main window".into());
    }
    #[cfg(target_os = "macos")]
    return tauri::async_runtime::spawn_blocking(|| {
        repair_if_denied(accessibility_granted, || {
            let output = std::process::Command::new("/usr/bin/tccutil")
                .args(["reset", "Accessibility", "com.pais.handy"])
                .output().map_err(|error| error.to_string())?;
            if !output.status.success() {
                return Err(format!("Could not repair Handy's Accessibility approval: {}", String::from_utf8_lossy(&output.stderr).trim()));
            }
            log::info!("Reset Handy's rejected Accessibility approval using tccutil; a normal restart and new macOS consent are required");
            Ok(())
        })
    }).await.map_err(|error| error.to_string())?;
    #[cfg(not(target_os = "macos"))]
    Ok(false)
}

#[cfg(target_os = "macos")]
fn open_privacy_panel(panel: &str) -> Result<(), String> {
    let status = std::process::Command::new("/usr/bin/open")
        .arg(format!(
            "x-apple.systempreferences:com.apple.preference.security?{panel}"
        ))
        .status()
        .map_err(|e| format!("Failed to open System Settings: {e}"))?;
    if !status.success() {
        return Err("System Settings could not be opened".into());
    }
    Ok(())
}

/// Reuse a current grant, including one enabled while the UI was checking.
/// A denied grant opens the pane without creating another permission prompt.
#[tauri::command]
#[specta::specta]
pub async fn open_accessibility_settings() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    return tauri::async_runtime::spawn_blocking(|| {
        if accessibility_granted() {
            return Ok(());
        }
        open_privacy_panel("Privacy_Accessibility")
    })
    .await
    .map_err(|e| e.to_string())?;
    #[cfg(not(target_os = "macos"))]
    Ok(())
}

#[tauri::command]
#[specta::specta]
pub async fn request_microphone_permission() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    return tauri::async_runtime::spawn_blocking(|| {
        use block2::RcBlock;
        use objc2::{class, msg_send, runtime::Bool};
        use objc2_foundation::NSString;

        #[link(name = "AVFoundation", kind = "framework")]
        extern "C" {}

        let media_type = NSString::from_str("soun");
        // SAFETY: AVCaptureDevice's class methods accept this NSString. The
        // completion block has the documented BOOL ABI and captures nothing;
        // AVFoundation copies it for asynchronous delivery.
        unsafe {
            let status: i32 = msg_send![class!(AVCaptureDevice), authorizationStatusForMediaType: &*media_type];
            match status {
                0 => {
                    // The permissions plugin passes nil here. Apple's API
                    // requires a real block, including when no result is used.
                    let completion = RcBlock::new(|_granted: Bool| {});
                    let _: () = msg_send![class!(AVCaptureDevice), requestAccessForMediaType: &*media_type, completionHandler: &*completion];
                    Ok(())
                }
                3 => Ok(()),
                _ => open_privacy_panel("Privacy_Microphone"),
            }
        }
    })
    .await
    .map_err(|e| e.to_string())?;
    #[cfg(not(target_os = "macos"))]
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::repair_if_denied;
    #[test]
    fn valid_permission_is_preserved_without_reset() {
        assert_eq!(
            repair_if_denied(|| true, || panic!("must not reset a valid approval")),
            Ok(false)
        );
    }
    #[test]
    fn rejected_permission_runs_explicit_repair_once() {
        let mut calls = 0;
        assert_eq!(
            repair_if_denied(
                || false,
                || {
                    calls += 1;
                    Ok(())
                }
            ),
            Ok(true)
        );
        assert_eq!(calls, 1);
    }
    #[test]
    fn failed_repair_is_not_reported_as_success() {
        assert_eq!(
            repair_if_denied(|| false, || Err("reset failed".into())),
            Err("reset failed".into())
        );
    }
}
