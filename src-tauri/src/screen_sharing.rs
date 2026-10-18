//! Clipboard handoff to Apple's Screen Sharing client (Mac-to-Mac sessions).
//!
//! A local pasteboard read is not a receipt from the remote application. With
//! shared clipboard enabled, refresh the same session's activation before
//! forwarding Cmd+V; the client's ordinary Paste menu is disabled for its
//! remote canvas. With sharing disabled, request Send Clipboard first.
//! Never toggle the user's clipboard setting.
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

mod focus_panel;
mod shared;

/// Exercise the real focus path without reading or modifying clipboard data,
/// posting keys, starting the microphone, or changing any permission.
pub(crate) fn check_focus(app: tauri::AppHandle) -> Result<(), String> {
    let target = ScreenSharingTarget::frontmost()?
        .ok_or("Screen Sharing focus check requires the existing session in front")?;
    if !target.uses_shared_clipboard()? {
        return Err("Screen Sharing focus check requires shared clipboard ON".into());
    }
    let settings = crate::settings::get_settings(&app);
    shared::check_focus(target, app, &settings)
}

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

    fn press(&self, command: &NSString) -> Result<(), String> {
        // SAFETY: self is an AX menu item and AXPress is a live CFString.
        let status = unsafe { AXUIElementPerformAction(self.0, string_ref(ns_string!("AXPress"))) };
        if status != 0 {
            return Err(format!(
                "Screen Sharing: {command} failed (AX error {status}). The action may already have started; check the remote text before retrying."
            ));
        }
        Ok(())
    }
}

/// Manual-mode actions cannot change Use Shared Clipboard.
trait ClipboardTarget {
    fn ensure_focused(&self) -> Result<(), String>;
    fn shared_clipboard_enabled(&self) -> Result<bool, String>;
    fn send_clipboard(&self) -> Result<(), String>;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PasteMode {
    Shared,
    Manual,
}

impl PasteMode {
    fn capture(target: &impl ClipboardTarget) -> Result<Self, String> {
        target.ensure_focused()?;
        let mode = if target.shared_clipboard_enabled()? {
            Self::Shared
        } else {
            Self::Manual
        };
        target.ensure_focused()?;
        Ok(mode)
    }

    fn ensure_current(self, target: &impl ClipboardTarget) -> Result<(), String> {
        target.ensure_focused()?;
        if target.shared_clipboard_enabled()? != (self == Self::Shared) {
            return Err("Screen Sharing: clipboard sharing changed during dictation. The transcription is available in History; retry with the same connection and clipboard setting.".into());
        }
        target.ensure_focused()
    }

    fn prepare(self, target: &impl ClipboardTarget) -> Result<(), String> {
        self.ensure_current(target)?;
        if self == Self::Shared {
            return Err(
                "Screen Sharing: shared clipboard requires asynchronous session refresh".into(),
            );
        }
        target.send_clipboard()
    }

    fn paste(
        self,
        target: &impl ClipboardTarget,
        send_keys: impl FnOnce() -> Result<(), String>,
    ) -> Result<(), String> {
        self.ensure_current(target)?;
        if self == Self::Shared {
            return Err(
                "Screen Sharing: shared clipboard requires asynchronous session refresh".into(),
            );
        }
        send_keys()
    }
}

pub(crate) struct ScreenSharingPaste<'a> {
    target: &'a ScreenSharingTarget,
    mode: PasteMode,
}

impl ScreenSharingPaste<'_> {
    pub(crate) fn prepare(&self) -> Result<(), String> {
        self.mode.prepare(self.target)
    }

    pub(crate) fn paste(
        &self,
        send_keys: impl FnOnce() -> Result<(), String>,
    ) -> Result<(), String> {
        self.mode.paste(self.target, send_keys)
    }
}

pub(crate) struct ScreenSharingTarget {
    pid: i32,
    application: CfValue,
    window: CfValue,
    bundle: Retained<NSBundle>,
    shared_clipboard_item: std::cell::OnceCell<CfValue>,
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
            shared_clipboard_item: std::cell::OnceCell::new(),
        }))
    }

    /// Do not paste/submit into a different local app or another remote session.
    pub(crate) fn ensure_focused(&self) -> Result<(), String> {
        let frontmost = NSWorkspace::sharedWorkspace().frontmostApplication();
        if frontmost.is_none_or(|app| app.processIdentifier() != self.pid) {
            return Err("Screen Sharing lost focus before text could be sent".into());
        }
        self.ensure_same_window()?;
        if NSWorkspace::sharedWorkspace()
            .frontmostApplication()
            .is_none_or(|app| app.processIdentifier() != self.pid)
        {
            return Err("Screen Sharing lost focus while checking its window".into());
        }
        Ok(())
    }

    fn ensure_same_window(&self) -> Result<(), String> {
        // An inactive app may expose only its main window. When frontmost,
        // require the actual focused window, including any intervening sheet.
        let window = if NSWorkspace::sharedWorkspace()
            .frontmostApplication()
            .is_some_and(|app| app.processIdentifier() == self.pid)
        {
            self.application.attribute(ns_string!("AXFocusedWindow"))?
        } else {
            self.application
                .attribute(ns_string!("AXFocusedWindow"))
                .or_else(|_| self.application.attribute(ns_string!("AXMainWindow")))?
        };
        // SAFETY: Both references are retained AX window objects.
        if unsafe { CFEqual(window.0, self.window.0) } == 0 {
            return Err("Screen Sharing changed windows before text could be sent".into());
        }
        Ok(())
    }

    pub(crate) fn uses_shared_clipboard(&self) -> Result<bool, String> {
        Ok(PasteMode::capture(self)? == PasteMode::Shared)
    }

    /// The main run loop must remain free during activation and transfer.
    pub(crate) fn paste_shared(
        self,
        text: String,
        app: tauri::AppHandle,
        settings: &crate::settings::AppSettings,
    ) -> Result<(), String> {
        shared::start(self, text, app, settings)
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

    /// Capture the route before changing the clipboard, then keep it stable
    /// through transfer and paste. Both ON and OFF settings are preserved.
    pub(crate) fn clipboard_paste(&self) -> Result<ScreenSharingPaste<'_>, String> {
        let mode = PasteMode::capture(self)?;
        if mode == PasteMode::Shared {
            return Err("Screen Sharing: clipboard mode changed before manual transfer".into());
        }
        Ok(ScreenSharingPaste { target: self, mode })
    }
}

impl ClipboardTarget for ScreenSharingTarget {
    fn ensure_focused(&self) -> Result<(), String> {
        ScreenSharingTarget::ensure_focused(self)
    }

    fn shared_clipboard_enabled(&self) -> Result<bool, String> {
        // Keep the retained checkbox for this short transaction, rather than
        // traversing the entire other process's menu tree on every timer tick.
        if self.shared_clipboard_item.get().is_none() {
            let Some(item) = self.menu_item(
                ns_string!("100521.title"),
                ns_string!("Use Shared Clipboard"),
            )?
            else {
                return Ok(false);
            };
            let _ = self.shared_clipboard_item.set(item);
        }
        let item = self
            .shared_clipboard_item
            .get()
            .ok_or("Screen Sharing: clipboard menu disappeared")?;
        if !item.attribute(ns_string!("AXEnabled"))?.is_true() {
            return Err("Screen Sharing: clipboard sharing is unavailable for this window; connect to a Mac in Control Mode".into());
        }
        Ok(item
            .attribute(ns_string!("AXMenuItemMarkChar"))
            .is_ok_and(|mark| mark.is_nonempty_string()))
    }

    fn send_clipboard(&self) -> Result<(), String> {
        let key = ns_string!("281.title");
        let title = ns_string!("Send Clipboard");
        let item = self
            .menu_item(key, title)?
            .ok_or_else(|| format!("Screen Sharing: Edit > {title} is unavailable. Connect to a Mac in Control Mode. The transcription is available in History."))?;
        if !item.attribute(ns_string!("AXEnabled"))?.is_true() {
            return Err(format!("Screen Sharing: Edit > {title} is disabled. Check Control Mode and the focused remote text field. The transcription is available in History."));
        }
        // Menu lookup can take time. Recheck the same session and mode just
        // before performing the action; never toggle a setting to make it work.
        PasteMode::Manual.ensure_current(self)?;
        self.ensure_focused()?;
        item.press(title)?;
        info!("[screen-sharing] invoked Edit > {title}");
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
    use std::cell::Cell;

    struct MockTarget {
        shared: Cell<bool>,
        focused: Cell<bool>,
        fail_transfer: bool,
        transfers: Cell<usize>,
    }

    impl MockTarget {
        fn new(shared: bool) -> Self {
            Self {
                shared: Cell::new(shared),
                focused: Cell::new(true),
                fail_transfer: false,
                transfers: Cell::new(0),
            }
        }
    }

    impl ClipboardTarget for MockTarget {
        fn ensure_focused(&self) -> Result<(), String> {
            if self.focused.get() {
                Ok(())
            } else {
                Err("Screen Sharing lost focus".into())
            }
        }

        fn shared_clipboard_enabled(&self) -> Result<bool, String> {
            Ok(self.shared.get())
        }

        fn send_clipboard(&self) -> Result<(), String> {
            self.transfers.set(self.transfers.get() + 1);
            if self.fail_transfer {
                Err("AX action timed out; it may already have run".into())
            } else {
                Ok(())
            }
        }
    }

    #[test]
    fn shared_mode_cannot_enter_the_synchronous_manual_path() {
        let target = MockTarget::new(true);
        let mode = PasteMode::capture(&target).unwrap();
        assert!(mode.prepare(&target).is_err());
        assert!(mode
            .paste(&target, || panic!("requires an activation refresh"))
            .is_err());
        assert_eq!(target.transfers.get(), 0);
        assert!(target.shared.get());
    }

    #[test]
    fn manual_clipboard_transfers_before_forwarding_the_paste_shortcut() {
        let target = MockTarget::new(false);
        let mode = PasteMode::capture(&target).unwrap();
        mode.prepare(&target).unwrap();
        mode.paste(&target, || {
            assert_eq!(target.transfers.get(), 1);
            Ok(())
        })
        .unwrap();
        assert_eq!(target.transfers.get(), 1);
        assert!(!target.shared.get());
    }

    #[test]
    fn failed_manual_transfer_stops_before_pasting() {
        let mut target = MockTarget::new(false);
        target.fail_transfer = true;
        let mode = PasteMode::capture(&target).unwrap();
        assert!(mode.prepare(&target).is_err());
        assert_eq!(target.transfers.get(), 1);
    }

    #[test]
    fn clipboard_mode_changes_abort_instead_of_using_the_wrong_route() {
        let target = MockTarget::new(false);
        let mode = PasteMode::capture(&target).unwrap();
        mode.prepare(&target).unwrap();
        target.shared.set(true);
        let result = mode.paste(&target, || panic!("mode changed; must not paste"));
        assert!(result.unwrap_err().contains("clipboard sharing changed"));
        assert_eq!(target.transfers.get(), 1);
    }

    #[test]
    fn losing_focus_cancels_before_the_paste() {
        let target = MockTarget::new(false);
        let mode = PasteMode::capture(&target).unwrap();
        mode.prepare(&target).unwrap();
        target.focused.set(false);
        assert!(mode
            .paste(&target, || panic!("focus changed; must not paste"))
            .is_err());
        assert_eq!(target.transfers.get(), 1);
    }

    #[test]
    fn an_unfocused_session_is_rejected_before_the_clipboard_is_changed() {
        let target = MockTarget::new(true);
        target.focused.set(false);
        assert!(PasteMode::capture(&target).is_err());
        assert_eq!(target.transfers.get(), 0);
    }

    #[test]
    fn remote_delays_allow_time_for_transfer_and_preserve_longer_user_delays() {
        assert_eq!(paste_delays(60, 60), (500, 1500));
        assert_eq!(paste_delays(2000, 3000), (2000, 3000));
    }
}
