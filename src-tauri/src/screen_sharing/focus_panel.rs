//! Refresh the session's key window without activating another application.
//!
//! A transparent, nonactivating panel causes the original window to resign
//! and regain key status while Screen Sharing keeps the menu bar and Space.
//! It never receives mouse events, changes cursor capture, or posts input.

use objc2::{rc::Retained, MainThreadMarker, MainThreadOnly};
use objc2_app_kit::{
    NSBackingStoreType, NSColor, NSFloatingWindowLevel, NSPanel, NSWindowAnimationBehavior,
    NSWindowCollectionBehavior, NSWindowStyleMask,
};
use objc2_foundation::{NSPoint, NSProcessInfo, NSRect, NSSize};

pub(super) struct FocusPanel(Retained<NSPanel>);

impl FocusPanel {
    pub(super) fn new() -> Result<Self, String> {
        let main_thread = MainThreadMarker::new()
            .ok_or("Screen Sharing window refresh requires the main thread")?;
        let panel = NSPanel::initWithContentRect_styleMask_backing_defer(
            NSPanel::alloc(main_thread),
            NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(1.0, 1.0)),
            NSWindowStyleMask::Titled | NSWindowStyleMask::NonactivatingPanel,
            NSBackingStoreType::Buffered,
            false,
        );
        // SAFETY: Retained owns the panel; closing must not release it again.
        unsafe { panel.setReleasedWhenClosed(false) };
        panel.setOpaque(false);
        panel.setBackgroundColor(Some(&NSColor::clearColor()));
        panel.setAlphaValue(0.0);
        panel.setHasShadow(false);
        panel.setIgnoresMouseEvents(true);
        panel.setHidesOnDeactivate(false);
        panel.setBecomesKeyOnlyIfNeeded(false);
        panel.setAnimationBehavior(NSWindowAnimationBehavior::None);
        panel.setLevel(NSFloatingWindowLevel);
        panel.setRestorable(false);
        panel.setExcludedFromWindowsMenu(true);
        let mut behavior = NSWindowCollectionBehavior::CanJoinAllSpaces
            | NSWindowCollectionBehavior::FullScreenAuxiliary
            | NSWindowCollectionBehavior::Transient
            | NSWindowCollectionBehavior::IgnoresCycle;
        if NSProcessInfo::processInfo()
            .operatingSystemVersion()
            .majorVersion
            >= 13
        {
            behavior |= NSWindowCollectionBehavior::CanJoinAllApplications;
        }
        panel.setCollectionBehavior(behavior);
        Ok(Self(panel))
    }

    pub(super) fn request(&self) {
        self.0.makeKeyAndOrderFront(None);
    }

    pub(super) fn is_key(&self) -> bool {
        self.0.isKeyWindow()
    }

    pub(super) fn release(&self) {
        self.0.orderOut(None);
    }
}

impl Drop for FocusPanel {
    fn drop(&mut self) {
        self.release();
    }
}
