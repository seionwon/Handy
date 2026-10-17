//! Clipboard handoff to Apple's Screen Sharing client (Mac-to-Mac sessions).
//!
//! A local pasteboard read is not a receipt from the remote application. With
//! shared clipboard enabled, invoke the client's native Paste menu action;
//! forwarding Cmd+V alone can paste stale remote data. With sharing disabled,
//! request Send Clipboard before sending the paste shortcut. Never toggle the
//! user's clipboard setting, and allow time before restoring the local data.
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ClipboardCommand {
    Send,
    Paste,
}

/// Restrict writable actions to Send Clipboard and Paste. In particular, this
/// interface cannot change Use Shared Clipboard or activate another window.
trait ClipboardTarget {
    fn ensure_focused(&self) -> Result<(), String>;
    fn shared_clipboard_enabled(&self) -> Result<bool, String>;
    fn press_clipboard_command(&self, command: ClipboardCommand) -> Result<(), String>;
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
        Ok(())
    }

    fn prepare(self, target: &impl ClipboardTarget) -> Result<(), String> {
        self.ensure_current(target)?;
        if self == Self::Manual {
            target.press_clipboard_command(ClipboardCommand::Send)?;
        }
        Ok(())
    }

    fn paste(
        self,
        target: &impl ClipboardTarget,
        send_keys: impl FnOnce() -> Result<(), String>,
    ) -> Result<(), String> {
        self.ensure_current(target)?;
        match self {
            // Use the client's paste action instead of forwarding a keystroke
            // directly to the remote session. Do not retry or fall back after
            // an AX error: a timed-out action may already have pasted the text.
            Self::Shared => target.press_clipboard_command(ClipboardCommand::Paste),
            Self::Manual => send_keys(),
        }
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

    /// Capture the route before changing the clipboard, then keep it stable
    /// through transfer and paste. Both ON and OFF settings are preserved.
    pub(crate) fn clipboard_paste(&self) -> Result<ScreenSharingPaste<'_>, String> {
        Ok(ScreenSharingPaste {
            target: self,
            mode: PasteMode::capture(self)?,
        })
    }
}

impl ClipboardTarget for ScreenSharingTarget {
    fn ensure_focused(&self) -> Result<(), String> {
        ScreenSharingTarget::ensure_focused(self)
    }

    fn shared_clipboard_enabled(&self) -> Result<bool, String> {
        let shared = self.menu_item(
            ns_string!("100521.title"),
            ns_string!("Use Shared Clipboard"),
        )?;
        // Without this menu, only the explicit Send Clipboard route is safe.
        Ok(shared.is_some_and(|item| {
            item.attribute(ns_string!("AXMenuItemMarkChar"))
                .is_ok_and(|mark| mark.is_nonempty_string())
        }))
    }

    fn press_clipboard_command(&self, command: ClipboardCommand) -> Result<(), String> {
        let (key, title, mode) = match command {
            ClipboardCommand::Send => (
                ns_string!("281.title"),
                ns_string!("Send Clipboard"),
                PasteMode::Manual,
            ),
            ClipboardCommand::Paste => (
                ns_string!("171.title"),
                ns_string!("Paste"),
                PasteMode::Shared,
            ),
        };
        let item = self
            .menu_item(key, title)?
            .ok_or_else(|| format!("Screen Sharing: Edit > {title} is unavailable. Connect to a Mac in Control Mode. The transcription is available in History."))?;
        if !item.attribute(ns_string!("AXEnabled"))?.is_true() {
            return Err(format!("Screen Sharing: Edit > {title} is disabled. Check Control Mode and the focused remote text field. The transcription is available in History."));
        }
        // Menu lookup can take time. Recheck the same session and mode just
        // before performing the action; never toggle a setting to make it work.
        mode.ensure_current(self)?;
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
    use std::cell::{Cell, RefCell};

    struct MockTarget {
        shared: Cell<bool>,
        focused: Cell<bool>,
        failed_command: Option<ClipboardCommand>,
        commands: RefCell<Vec<ClipboardCommand>>,
    }

    impl MockTarget {
        fn new(shared: bool) -> Self {
            Self {
                shared: Cell::new(shared),
                focused: Cell::new(true),
                failed_command: None,
                commands: RefCell::new(Vec::new()),
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

        fn press_clipboard_command(&self, command: ClipboardCommand) -> Result<(), String> {
            self.commands.borrow_mut().push(command);
            if self.failed_command == Some(command) {
                Err("AX action timed out; it may already have run".into())
            } else {
                Ok(())
            }
        }
    }

    #[test]
    fn shared_clipboard_uses_native_paste_without_send_or_synthetic_keys() {
        let target = MockTarget::new(true);
        let mode = PasteMode::capture(&target).unwrap();
        let keys_sent = Cell::new(false);
        mode.prepare(&target).unwrap();
        assert!(target.commands.borrow().is_empty());
        mode.paste(&target, || {
            keys_sent.set(true);
            Ok(())
        })
        .unwrap();
        assert_eq!(*target.commands.borrow(), [ClipboardCommand::Paste]);
        assert!(!keys_sent.get());
        assert!(target.shared.get());
    }

    #[test]
    fn repeated_shared_transcriptions_each_request_a_new_native_paste() {
        let target = MockTarget::new(true);
        for _ in 0..2 {
            let mode = PasteMode::capture(&target).unwrap();
            mode.prepare(&target).unwrap();
            mode.paste(&target, || panic!("must not send Cmd+V"))
                .unwrap();
        }
        assert_eq!(
            *target.commands.borrow(),
            [ClipboardCommand::Paste, ClipboardCommand::Paste]
        );
        assert!(target.shared.get());
    }

    #[test]
    fn timed_out_native_paste_is_not_retried_or_followed_by_synthetic_keys() {
        let mut target = MockTarget::new(true);
        target.failed_command = Some(ClipboardCommand::Paste);
        let mode = PasteMode::capture(&target).unwrap();
        mode.prepare(&target).unwrap();
        let result = mode.paste(&target, || panic!("a fallback could paste twice"));
        assert!(result.is_err());
        assert_eq!(*target.commands.borrow(), [ClipboardCommand::Paste]);
        assert!(target.shared.get());
    }

    #[test]
    fn manual_clipboard_transfers_before_forwarding_the_paste_shortcut() {
        let target = MockTarget::new(false);
        let mode = PasteMode::capture(&target).unwrap();
        mode.prepare(&target).unwrap();
        mode.paste(&target, || {
            assert_eq!(*target.commands.borrow(), [ClipboardCommand::Send]);
            Ok(())
        })
        .unwrap();
        assert_eq!(*target.commands.borrow(), [ClipboardCommand::Send]);
        assert!(!target.shared.get());
    }

    #[test]
    fn failed_manual_transfer_stops_before_pasting() {
        let mut target = MockTarget::new(false);
        target.failed_command = Some(ClipboardCommand::Send);
        let mode = PasteMode::capture(&target).unwrap();
        let result = mode.prepare(&target);
        assert!(result.is_err());
        assert_eq!(*target.commands.borrow(), [ClipboardCommand::Send]);
    }

    #[test]
    fn clipboard_mode_changes_abort_instead_of_using_the_wrong_route() {
        for shared in [false, true] {
            let target = MockTarget::new(shared);
            let mode = PasteMode::capture(&target).unwrap();
            mode.prepare(&target).unwrap();
            target.commands.borrow_mut().clear();
            target.shared.set(!shared);
            let result = mode.paste(&target, || panic!("mode changed; must not paste"));
            assert!(result.unwrap_err().contains("clipboard sharing changed"));
            assert!(target.commands.borrow().is_empty());
        }
    }

    #[test]
    fn losing_focus_cancels_both_routes_before_the_paste() {
        for shared in [false, true] {
            let target = MockTarget::new(shared);
            let mode = PasteMode::capture(&target).unwrap();
            mode.prepare(&target).unwrap();
            target.commands.borrow_mut().clear();
            target.focused.set(false);
            let result = mode.paste(&target, || panic!("focus changed; must not paste"));
            assert!(result.is_err());
            assert!(target.commands.borrow().is_empty());
            assert_eq!(target.shared.get(), shared);
        }
    }

    #[test]
    fn an_unfocused_session_is_rejected_before_the_clipboard_is_changed() {
        let target = MockTarget::new(true);
        target.focused.set(false);
        assert!(PasteMode::capture(&target).is_err());
        assert!(target.commands.borrow().is_empty());
    }

    #[test]
    fn remote_delays_allow_time_for_transfer_and_preserve_longer_user_delays() {
        assert_eq!(paste_delays(60, 60), (500, 1500));
        assert_eq!(paste_delays(2000, 3000), (2000, 3000));
    }
}
