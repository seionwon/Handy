//! Public AppKit activation that works for a background dictation app.
//!
//! On macOS 27, both NSRunningApplication::activateWithOptions and NSApp's
//! activate request were denied in an activation-only native probe. Opening
//! this already-running bundle with NSWorkspace succeeded, including when
//! its Reopen handler did not show a window. Returning to the original app
//! must yield activation first on macOS 14+.

use block2::RcBlock;
use objc2::{runtime::NSObjectProtocol, sel, MainThreadMarker};
use objc2_app_kit::{
    NSApplication, NSApplicationActivationOptions, NSRunningApplication, NSWorkspace,
    NSWorkspaceOpenConfiguration,
};
use objc2_foundation::{NSBundle, NSError};
use std::sync::mpsc::{self, Receiver};

pub(super) fn request_self_activation() -> Result<Receiver<Result<(), String>>, String> {
    let bundle = NSBundle::mainBundle();
    if bundle
        .bundleIdentifier()
        .is_none_or(|id| id.to_string() != "com.pais.handy")
    {
        return Err("Screen Sharing: session refresh requires the installed Handy app".into());
    }
    let configuration = NSWorkspaceOpenConfiguration::configuration();
    configuration.setActivates(true);
    configuration.setCreatesNewApplicationInstance(false);
    configuration.setAllowsRunningApplicationSubstitution(false);
    configuration.setAddsToRecentItems(false);
    configuration.setPromptsUserIfNeeded(false);
    let expected_pid = std::process::id() as i32;
    let (sender, receiver) = mpsc::sync_channel(1);
    let completion = RcBlock::new(move |app: *mut NSRunningApplication, error: *mut NSError| {
        // SAFETY: AppKit supplies nullable, live objects for the duration of the
        // callback. Only scalar values leave this callback, never ObjC pointers.
        let result = unsafe {
            match app.as_ref() {
                Some(app) if app.processIdentifier() == expected_pid => Ok(()),
                Some(_) => Err("Screen Sharing: activation returned another Handy process".into()),
                None => Err(format!(
                    "Screen Sharing: opening the running app failed ({})",
                    error.as_ref().map_or(0, |e| e.code())
                )),
            }
        };
        let _ = sender.try_send(result);
    });
    NSWorkspace::sharedWorkspace().openApplicationAtURL_configuration_completionHandler(
        &bundle.bundleURL(),
        &configuration,
        Some(&completion),
    );
    Ok(receiver)
}

pub(super) fn return_to_session(target: &NSRunningApplication) -> Result<(), String> {
    let main_thread =
        MainThreadMarker::new().ok_or("Screen Sharing activation requires the main thread")?;
    let app = NSApplication::sharedApplication(main_thread);
    if app.respondsToSelector(sel!(yieldActivationToApplication:)) {
        app.yieldActivationToApplication(target);
    }
    if !target.activateWithOptions(NSApplicationActivationOptions::empty()) {
        return Err("Screen Sharing: could not return to the original session".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn an_unbundled_test_process_cannot_open_an_arbitrary_app() {
        assert!(super::request_self_activation().is_err());
    }
}
