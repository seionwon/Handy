//! Explicit macOS permission actions. Checking permission never installs an
//! input hook, requests a prompt, or modifies the system's privacy database.

pub fn ensure_accessibility() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        #[link(name = "ApplicationServices", kind = "framework")]
        extern "C" {
            fn AXIsProcessTrusted() -> bool;
        }
        // SAFETY: system query with no pointers or side effects.
        if !unsafe { AXIsProcessTrusted() } {
            return Err("Accessibility permission is required for the input system".into());
        }
    }
    Ok(())
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

/// Opening the pane also works when a denied or stale Handy entry already
/// exists and macOS declines to display another permission prompt.
#[tauri::command]
#[specta::specta]
pub async fn open_accessibility_settings() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    return tauri::async_runtime::spawn_blocking(|| open_privacy_panel("Privacy_Accessibility"))
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
