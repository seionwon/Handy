//! Clipboard handoff to Apple's Screen Sharing client (Mac-to-Mac sessions).
//!
//! A local pasteboard read is not a receipt from the remote application. Use
//! actual pasteboard data and request an explicit transfer for every paste.
//! Automatic sharing alone can keep stale remote data while the client stays
//! frontmost. Allow time for the network before Cmd+V or restoring the data.
//! Called on the main thread, like the other macOS paste implementations.

use log::info;
use objc2::rc::Retained;
use objc2_app_kit::NSWorkspace;
use objc2_foundation::{ns_string, NSBundle, NSString};
use std::ffi::c_void;
use std::ptr;

const BUNDLE_ID: &str = "com.apple.ScreenSharing";
const TRANSFER_DELAY_MS: u64 = 500;
const RESTORE_DELAY_MS: u64 = 1500;

type CfRef = *const c_void;

#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" {
    fn AXUIElementCreateApplication(pid: i32) -> CfRef;
    fn AXUIElementCopyAttributeValue(element: CfRef, attribute: CfRef, value: *mut CfRef) -> i32;
    fn AXUIElementPerformAction(element: CfRef, action: CfRef) -> i32;
    fn AXUIElementSetMessagingTimeout(element: CfRef, seconds: f32) -> i32;
    fn AXUIElementGetTypeID() -> usize;
}

#[link(name = "CoreFoundation", kind = "framework")]
unsafe extern "C" {
    fn CFRelease(value: CfRef);
    fn CFRetain(value: CfRef) -> CfRef;
    fn CFEqual(left: CfRef, right: CfRef) -> u8;
    fn CFGetTypeID(value: CfRef) -> usize;
    fn CFArrayGetTypeID() -> usize;
    fn CFArrayGetCount(array: CfRef) -> isize;
    fn CFArrayGetValueAtIndex(array: CfRef, index: isize) -> CfRef;
    fn CFStringGetTypeID() -> usize;
    fn CFStringGetLength(value: CfRef) -> isize;
    static kCFBooleanTrue: CfRef;
}

fn string_ref(value: &NSString) -> CfRef {
    // NSString and CFString are toll-free bridged. The caller keeps it alive.
    ptr::from_ref(value).cast()
}

/// An owned, non-null Core Foundation reference returned by Create/Copy/Retain.
struct CfValue(CfRef);

impl Drop for CfValue {
    fn drop(&mut self) {
        // SAFETY: Every CfValue owns exactly one retained, non-null reference.
        unsafe { CFRelease(self.0) };
    }
}

impl CfValue {
    fn attribute(&self, name: &NSString) -> Result<Self, String> {
        let mut value = ptr::null();
        // SAFETY: self is an AXUIElement, name is a live CFString, and value is
        // valid output storage. Successful Copy calls transfer ownership.
        let status = unsafe { AXUIElementCopyAttributeValue(self.0, string_ref(name), &mut value) };
        if status != 0 || value.is_null() {
            return Err(format!(
                "Screen Sharing: cannot read {name} (AX error {status})"
            ));
        }
        Ok(Self(value))
    }

    fn children(&self) -> Result<Vec<Self>, String> {
        let children = self.attribute(ns_string!("AXChildren"))?;
        // SAFETY: children is a live CF object; check its type before array APIs.
        unsafe {
            if CFGetTypeID(children.0) != CFArrayGetTypeID() {
                return Err("Screen Sharing: AXChildren is not an array".into());
            }
            let mut result = Vec::new();
            for index in 0..CFArrayGetCount(children.0) {
                let child = CFArrayGetValueAtIndex(children.0, index);
                if !child.is_null() && CFGetTypeID(child) == AXUIElementGetTypeID() {
                    result.push(Self(CFRetain(child)));
                }
            }
            Ok(result)
        }
    }

    fn string_equals(&self, expected: &NSString) -> bool {
        // SAFETY: Both arguments are live CF objects; CFEqual checks their types.
        unsafe { CFEqual(self.0, string_ref(expected)) != 0 }
    }

    fn is_true(&self) -> bool {
        // SAFETY: self is live and kCFBooleanTrue is an immortal framework value.
        unsafe { CFEqual(self.0, kCFBooleanTrue) != 0 }
    }

    fn is_nonempty_string(&self) -> bool {
        // SAFETY: Check the type before using the CFString API.
        unsafe { CFGetTypeID(self.0) == CFStringGetTypeID() && CFStringGetLength(self.0) > 0 }
    }

    fn press(&self) -> Result<(), String> {
        // SAFETY: self is an AX menu item and AXPress is a live CFString.
        let status = unsafe { AXUIElementPerformAction(self.0, string_ref(ns_string!("AXPress"))) };
        if status != 0 {
            return Err(format!(
                "Screen Sharing: clipboard transfer failed (AX error {status})"
            ));
        }
        Ok(())
    }
}

fn require_explicit_clipboard_transfer(send_enabled: bool, shared: bool) -> Result<(), String> {
    if send_enabled {
        Ok(())
    } else if shared {
        Err("Screen Sharing: turn off Edit > Use Shared Clipboard so Edit > Send Clipboard is enabled, then retry. Automatic sharing can paste an earlier transcription while Screen Sharing stays in front. The new transcription is available in History.".into())
    } else {
        Err("Screen Sharing clipboard transfer is unavailable. Connect to a Mac in Control Mode and make sure Edit > Send Clipboard is enabled. The transcription is available in History.".into())
    }
}

pub(crate) struct ScreenSharingTarget {
    pid: i32,
    application: CfValue,
    window: CfValue,
    bundle: Retained<NSBundle>,
}

impl ScreenSharingTarget {
    /// Only intercept Apple's foreground client; other VNC clients are unchanged.
    pub(crate) fn frontmost() -> Result<Option<Self>, String> {
        let Some(app) = NSWorkspace::sharedWorkspace().frontmostApplication() else {
            return Ok(None);
        };
        if app
            .bundleIdentifier()
            .is_none_or(|id| id.to_string() != BUNDLE_ID)
        {
            return Ok(None);
        }
        let bundle = app
            .bundleURL()
            .and_then(|url| NSBundle::bundleWithURL(&url))
            .ok_or("Screen Sharing: cannot locate application resources")?;
        let pid = app.processIdentifier();
        // SAFETY: pid comes from NSRunningApplication. Create returns ownership.
        let application = unsafe { AXUIElementCreateApplication(pid) };
        if application.is_null() {
            return Err("Screen Sharing: cannot create accessibility application".into());
        }
        let application = CfValue(application);
        // SAFETY: application is a valid AXUIElement. Bound an unresponsive client.
        unsafe { AXUIElementSetMessagingTimeout(application.0, 1.0) };
        let window = application.attribute(ns_string!("AXFocusedWindow"))?;
        Ok(Some(Self {
            pid,
            application,
            window,
            bundle,
        }))
    }

    /// Do not paste/submit into a different local app or another remote session.
    pub(crate) fn ensure_focused(&self) -> Result<(), String> {
        let frontmost = NSWorkspace::sharedWorkspace().frontmostApplication();
        if frontmost.is_none_or(|app| app.processIdentifier() != self.pid) {
            return Err("Screen Sharing lost focus before text could be sent".into());
        }
        let window = self.application.attribute(ns_string!("AXFocusedWindow"))?;
        // SAFETY: Both references are retained AX window objects.
        if unsafe { CFEqual(window.0, self.window.0) } == 0 {
            return Err("Screen Sharing changed windows before text could be sent".into());
        }
        Ok(())
    }

    fn menu_item(&self, key: &NSString, fallback: &NSString) -> Result<Option<CfValue>, String> {
        // Resolve the app's own translations, including Korean, instead of
        // assuming English menu titles or a fixed menu position.
        let title = self.bundle.localizedStringForKey_value_table(
            key,
            Some(fallback),
            Some(ns_string!("MainMenu")),
        );
        let edit_title = self.bundle.localizedStringForKey_value_table(
            ns_string!("169.title"),
            Some(ns_string!("Edit")),
            Some(ns_string!("MainMenu")),
        );
        let menu_bar = self.application.attribute(ns_string!("AXMenuBar"))?;
        for menu_bar_item in menu_bar.children()? {
            if !menu_bar_item
                .attribute(ns_string!("AXTitle"))
                .is_ok_and(|name| {
                    name.string_equals(&edit_title) || name.string_equals(ns_string!("Edit"))
                })
            {
                continue;
            }
            for menu in menu_bar_item.children()? {
                for item in menu.children()? {
                    if item.attribute(ns_string!("AXTitle")).is_ok_and(|name| {
                        name.string_equals(&title) || name.string_equals(fallback)
                    }) {
                        return Ok(Some(item));
                    }
                }
            }
        }
        Ok(None)
    }

    pub(crate) fn send_clipboard(&self) -> Result<(), String> {
        self.ensure_focused()?;
        let send = self.menu_item(ns_string!("281.title"), ns_string!("Send Clipboard"))?;
        let send_enabled = send.as_ref().is_some_and(|item| {
            item.attribute(ns_string!("AXEnabled"))
                .is_ok_and(|value| value.is_true())
        });
        let shared = if send_enabled {
            false
        } else {
            self.menu_item(
                ns_string!("100521.title"),
                ns_string!("Use Shared Clipboard"),
            )?
            .is_some_and(|item| {
                item.attribute(ns_string!("AXEnabled"))
                    .is_ok_and(|value| value.is_true())
                    && item
                        .attribute(ns_string!("AXMenuItemMarkChar"))
                        .is_ok_and(|mark| mark.is_nonempty_string())
            })
        };
        self.ensure_focused()?;
        require_explicit_clipboard_transfer(send_enabled, shared)?;
        let send = send.ok_or("Screen Sharing: Send Clipboard menu item is unavailable")?;
        send.press()?;
        info!("[screen-sharing] requested clipboard transfer to remote Mac");
        Ok(())
    }
}

pub(crate) fn paste_delays(before_ms: u64, after_ms: u64) -> (u64, u64) {
    (
        before_ms.max(TRANSFER_DELAY_MS),
        after_ms.max(RESTORE_DELAY_MS),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn manual_clipboard_sharing_allows_an_explicit_transfer() {
        assert!(require_explicit_clipboard_transfer(true, false).is_ok());
    }

    #[test]
    fn automatic_sharing_alone_cannot_confirm_a_fresh_transfer() {
        let error = require_explicit_clipboard_transfer(false, true).unwrap_err();
        assert!(error.contains("Use Shared Clipboard"));
        assert!(error.contains("Send Clipboard"));
    }

    #[test]
    fn an_enabled_send_item_is_required_for_every_transcript() {
        // A foreground client can retain the first remote clipboard while the
        // local clipboard changes. A checked sharing menu must not authorize
        // the next paste after the explicit transfer becomes unavailable.
        assert!(require_explicit_clipboard_transfer(true, true).is_ok());
        assert!(require_explicit_clipboard_transfer(false, true).is_err());
    }

    #[test]
    fn unavailable_clipboard_transfer_must_not_paste_stale_remote_text() {
        assert!(require_explicit_clipboard_transfer(false, false).is_err());
    }

    #[test]
    fn remote_delays_allow_time_for_transfer_and_preserve_longer_user_delays() {
        assert_eq!(paste_delays(60, 60), (500, 1500));
        assert_eq!(paste_delays(2000, 3000), (2000, 3000));
    }
}
