//! Keep automatic clipboard sharing ON and refresh the original session.
//!
//! Screen Sharing publishes clipboard promises when its session becomes key;
//! changing the pasteboard while its canvas remains key need not refresh them.
//! Its Edit > Paste item belongs to local responders and can be disabled even
//! while remote keyboard input works. Use public AppKit window APIs to briefly
//! borrow key-window focus with a nonactivating panel, then release it back
//! to the SAME session and send the paste chord once. Screen Sharing remains
//! the foreground application, including while its menu bar is hidden.
//! All native objects stay on the main thread. The worker only schedules ticks,
//! so AppKit can actually deliver activation changes between the phases.

use super::{ClipboardTarget, PasteMode, ScreenSharingTarget};
use crate::clipboard::send_return_key;
use crate::input::{self, EnigoState};
use crate::managers::audio::AudioRecordingManager;
use crate::settings::{AppSettings, AutoSubmitKey, ClipboardHandling, PasteMethod};
use log::{error, info, warn};
use objc2_app_kit::{NSPasteboard, NSWorkspace};
use std::cell::RefCell;
use std::sync::{mpsc, Arc};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_clipboard_manager::ClipboardExt;

const FOCUS_TIMEOUT: Duration = Duration::from_secs(2);
const TICK_INTERVAL: Duration = Duration::from_millis(50);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Focus {
    Panel,
    Session,
    Other,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Phase {
    AwaitingPanel,
    AwaitingSession,
    Transferring,
    Restoring,
}

#[derive(Debug, PartialEq, Eq)]
enum Action {
    Wait,
    ReleasePanel,
    Paste,
    Finish,
}

struct Refresh {
    phase: Phase,
    since: Instant,
    transfer_delay: Duration,
    restore_delay: Duration,
}

impl Refresh {
    fn new(now: Instant, before_ms: u64, after_ms: u64) -> Self {
        let (before_ms, after_ms) = super::paste_delays(before_ms, after_ms);
        Self {
            phase: Phase::AwaitingPanel,
            since: now,
            transfer_delay: Duration::from_millis(before_ms),
            restore_delay: Duration::from_millis(after_ms),
        }
    }

    fn advance(&mut self, now: Instant, focus: Focus) -> Result<Action, String> {
        let elapsed = now.duration_since(self.since);
        if matches!(self.phase, Phase::AwaitingPanel | Phase::AwaitingSession)
            && elapsed >= FOCUS_TIMEOUT
        {
            return Err(format!(
                "Screen Sharing: {:?} window focus timed out; no paste was sent",
                self.phase
            ));
        }
        match (self.phase, focus) {
            (Phase::AwaitingPanel, Focus::Panel) => {
                self.phase = Phase::AwaitingSession;
                self.since = now;
                Ok(Action::ReleasePanel)
            }
            (Phase::AwaitingSession, Focus::Session) => {
                self.phase = Phase::Transferring;
                self.since = now;
                Ok(Action::Wait)
            }
            (Phase::AwaitingPanel, Focus::Session) | (Phase::AwaitingSession, Focus::Panel) => {
                Ok(Action::Wait)
            }
            (Phase::Transferring, Focus::Session) => {
                if elapsed >= self.transfer_delay {
                    self.phase = Phase::Restoring;
                    self.since = now;
                    Ok(Action::Paste)
                } else {
                    Ok(Action::Wait)
                }
            }
            (Phase::Restoring, _) => Ok(if elapsed >= self.restore_delay {
                Action::Finish
            } else {
                Action::Wait
            }),
            _ => Err(
                "Screen Sharing: focus changed during clipboard refresh; paste cancelled".into(),
            ),
        }
    }
}

struct Pending {
    id: u64,
    target: ScreenSharingTarget,
    refresh: Refresh,
    revision: isize,
    text: String,
    published: bool,
    saved_text: Option<String>,
    saved_image: Option<tauri::image::Image<'static>>,
    paste_method: PasteMethod,
    auto_submit: bool,
    auto_submit_key: AutoSubmitKey,
    keep_transcript: bool,
    paste_error: Option<String>,
    cancellation: Option<(Arc<AudioRecordingManager>, u64)>,
    focus_only: bool,
    panel: super::focus_panel::FocusPanel,
}

// AX and AppKit objects must never cross to the scheduling worker.
thread_local! {
    static PENDING: RefCell<Option<Pending>> = const { RefCell::new(None) };
    static NEXT_ID: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

impl Pending {
    fn clipboard_unchanged(&self) -> bool {
        self.focus_only || NSPasteboard::generalPasteboard().changeCount() == self.revision
    }

    fn cancelled(&self) -> bool {
        self.cancellation
            .as_ref()
            .is_some_and(|(manager, generation)| manager.was_cancelled_since(*generation))
    }

    fn focus(&self) -> Focus {
        if NSWorkspace::sharedWorkspace()
            .frontmostApplication()
            .is_none_or(|app| app.processIdentifier() != self.target.pid)
        {
            Focus::Other
        } else if self.panel.is_key() {
            Focus::Panel
        } else {
            Focus::Session
        }
    }

    fn ensure_target(&self) -> Result<(), String> {
        if self.focus() == Focus::Other {
            return Err("Screen Sharing: user changed apps; paste cancelled".into());
        }
        self.target.ensure_same_window()?;
        if !self.target.shared_clipboard_enabled()? {
            return Err("Screen Sharing: clipboard mode changed before paste".into());
        }
        Ok(())
    }

    fn send_paste(&self, app: &AppHandle) -> Result<(), String> {
        if self.focus_only {
            return Err("Screen Sharing: focus checks cannot send input".into());
        }
        PasteMode::Shared.ensure_current(&self.target)?;
        if self.cancelled() || !self.clipboard_unchanged() {
            return Err("Screen Sharing: clipboard or dictation changed before paste".into());
        }
        let enigo = app
            .try_state::<EnigoState>()
            .ok_or("Enigo state not initialized")?;
        let mut enigo = enigo.0.try_lock().map_err(|e| e.to_string())?;
        match self.paste_method {
            PasteMethod::CtrlV => input::send_paste_ctrl_v(&mut enigo, 100),
            PasteMethod::CtrlShiftV => input::send_paste_ctrl_shift_v(&mut enigo, 100),
            _ => Err("Invalid Screen Sharing paste method".into()),
        }
    }

    fn tick(&mut self, app: &AppHandle) -> Result<bool, String> {
        if self.refresh.phase != Phase::Restoring {
            if self.cancelled() {
                return Err("Screen Sharing: dictation cancelled before paste".into());
            }
            if !self.clipboard_unchanged() {
                return Err(
                    "Screen Sharing: clipboard changed before paste; new copy preserved".into(),
                );
            }
        }
        let previous_phase = self.refresh.phase;
        let now = Instant::now();
        let phase_ms = now.duration_since(self.refresh.since).as_millis();
        match self.refresh.advance(now, self.focus())? {
            Action::Wait => {
                if previous_phase == Phase::AwaitingSession
                    && self.refresh.phase == Phase::Transferring
                {
                    self.ensure_target()?;
                    info!("[screen-sharing] original window focus restored after {phase_ms} ms; Screen Sharing stayed foreground");
                    if self.focus_only {
                        info!("[screen-sharing] focus check passed: sharing ON, same foreground app, panel released; no clipboard text or keys sent");
                        return Ok(true);
                    }
                }
                Ok(false)
            }
            Action::ReleasePanel => {
                // Do not query another app's AX menu/window while the panel
                // owns key focus. Those IPC calls can stall. Validate the
                // original window immediately before borrowing, then again
                // after release and immediately before paste.
                if self.cancelled() || !self.clipboard_unchanged() {
                    return Err(
                        "Screen Sharing: clipboard or dictation changed before publication".into(),
                    );
                }
                // Publish only AFTER the original session window resigned key status.
                // This matches copying in a local app and returning to VNC;
                // its old promise cleanup cannot consume this change first.
                if !self.focus_only {
                    app.clipboard()
                        .write_text(&self.text)
                        .map_err(|e| e.to_string())?;
                    self.revision = NSPasteboard::generalPasteboard().changeCount();
                    self.published = true;
                }
                // A lost panel focus means the user intervened before release.
                if self.focus() != Focus::Panel {
                    return Err(
                        "Screen Sharing: user changed focus before release; paste cancelled".into(),
                    );
                }
                self.panel.release();
                info!("[screen-sharing] released nonactivating panel after {phase_ms} ms; sharing remains ON");
                Ok(false)
            }
            Action::Paste => {
                // An error may follow partial key injection. Report it once;
                // neither retry nor fall back to another text-input route.
                self.paste_error = self.send_paste(app).err();
                self.refresh.since = Instant::now();
                if self.paste_error.is_some() {
                    warn!("[screen-sharing] paste chord failed; waiting before guarded clipboard restoration, without retry");
                } else {
                    info!("[screen-sharing] shared session refreshed; paste chord sent once");
                }
                Ok(false)
            }
            Action::Finish => match self.paste_error.take() {
                Some(error) => Err(error),
                None => Ok(true),
            },
        }
    }

    fn finish(self, app: &AppHandle, success: bool) {
        self.panel.release();
        if !self.focus_only
            && success
            && self.auto_submit
            && !self.cancelled()
            && self.clipboard_unchanged()
        {
            if let Err(error) = PasteMode::Shared
                .ensure_current(&self.target)
                .and_then(|()| {
                    let enigo = app
                        .try_state::<EnigoState>()
                        .ok_or("Enigo state not initialized")?;
                    let mut enigo = enigo.0.try_lock().map_err(|e| e.to_string())?;
                    if self.cancelled() || !self.clipboard_unchanged() {
                        return Err(
                            "Screen Sharing: clipboard or dictation changed before auto-submit"
                                .into(),
                        );
                    }
                    send_return_key(&mut enigo, self.auto_submit_key)
                })
            {
                warn!("[screen-sharing] auto-submit cancelled: {error}");
            }
        }
        if self.published && self.clipboard_unchanged() && !(success && self.keep_transcript) {
            let clipboard = app.clipboard();
            let restored = if let Some(text) = &self.saved_text {
                clipboard.write_text(text)
            } else if let Some(image) = &self.saved_image {
                clipboard.write_image(image)
            } else {
                clipboard.clear()
            };
            if let Err(error) = restored {
                warn!("[screen-sharing] could not restore clipboard: {error}");
            }
        }
    }
}

fn tick(id: u64, app: &AppHandle) -> bool {
    PENDING.with(|slot| {
        let mut slot = slot.borrow_mut();
        let Some(pending) = slot.as_mut().filter(|pending| pending.id == id) else {
            return false;
        };
        match pending.tick(app) {
            Ok(false) => true,
            outcome => {
                if let Err(error) = &outcome {
                    error!("Failed to paste transcription: {error}");
                    let _ = app.emit("paste-error", ());
                }
                if let Some(pending) = slot.take() {
                    pending.finish(app, outcome.is_ok());
                }
                false
            }
        }
    })
}

pub(super) fn start(
    target: ScreenSharingTarget,
    text: String,
    app: AppHandle,
    settings: &AppSettings,
) -> Result<(), String> {
    start_inner(target, Some(text), app, settings)
}

pub(super) fn check_focus(
    target: ScreenSharingTarget,
    app: AppHandle,
    settings: &AppSettings,
) -> Result<(), String> {
    start_inner(target, None, app, settings)
}

fn start_inner(
    target: ScreenSharingTarget,
    text: Option<String>,
    app: AppHandle,
    settings: &AppSettings,
) -> Result<(), String> {
    if PENDING.with(|slot| slot.borrow().is_some()) {
        return Err(
            "Screen Sharing: the previous paste is still finishing; transcription saved in History"
                .into(),
        );
    }
    PasteMode::Shared.ensure_current(&target)?;
    crate::permissions::ensure_accessibility()?;
    let focus_only = text.is_none();
    let (revision, saved_text, saved_image) = if focus_only {
        (0, None, None)
    } else {
        let clipboard = app.clipboard();
        let revision = NSPasteboard::generalPasteboard().changeCount();
        let saved_text = clipboard.read_text().ok().filter(|text| !text.is_empty());
        let saved_image = if saved_text.is_none() {
            clipboard.read_image().ok().map(|image| image.to_owned())
        } else {
            None
        };
        if NSPasteboard::generalPasteboard().changeCount() != revision {
            return Err(
                "Screen Sharing: clipboard changed while preparing; paste cancelled".into(),
            );
        }
        (revision, saved_text, saved_image)
    };
    let id = NEXT_ID.with(|next| {
        let id = next.get().wrapping_add(1);
        next.set(id);
        id
    });
    let cancellation = app
        .try_state::<Arc<AudioRecordingManager>>()
        .map(|manager| {
            let generation = manager.cancel_generation();
            (Arc::clone(&manager), generation)
        });
    let pending = Pending {
        id,
        target,
        refresh: Refresh::new(
            Instant::now(),
            settings.paste_delay_ms,
            settings.paste_delay_after_ms,
        ),
        revision,
        text: text.unwrap_or_default(),
        published: false,
        saved_text,
        saved_image,
        paste_method: settings.paste_method,
        auto_submit: settings.auto_submit,
        auto_submit_key: settings.auto_submit_key,
        keep_transcript: settings.clipboard_handling == ClipboardHandling::CopyToClipboard,
        paste_error: None,
        cancellation,
        focus_only,
        panel: super::focus_panel::FocusPanel::new()?,
    };
    PasteMode::Shared.ensure_current(&pending.target)?;
    PENDING.with(|slot| *slot.borrow_mut() = Some(pending));
    PENDING.with(|slot| {
        if let Some(pending) = slot.borrow().as_ref() {
            pending.panel.request();
        }
    });
    info!("[screen-sharing] requested nonactivating key-window refresh; sharing stays ON; focus-only check: {focus_only}");

    let worker_app = app.clone();
    if let Err(error) = std::thread::Builder::new()
        .name("screen-sharing-paste".into())
        .spawn(move || loop {
            std::thread::sleep(TICK_INTERVAL);
            let (sender, receiver) = mpsc::sync_channel(1);
            let tick_app = worker_app.clone();
            if worker_app
                .run_on_main_thread(move || {
                    let _ = sender.send(tick(id, &tick_app));
                })
                .is_err()
                || receiver.recv() != Ok(true)
            {
                break;
            }
        })
    {
        PENDING.with(|slot| {
            if let Some(pending) = slot.borrow_mut().take() {
                pending.finish(&app, false);
            }
        });
        return Err(format!("Could not schedule Screen Sharing paste: {error}"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ready_refresh(start: Instant) -> Refresh {
        Refresh::new(start, 0, 0)
    }

    #[test]
    fn shared_paste_requires_an_observed_round_trip_and_transfer_delay() {
        let start = Instant::now();
        let mut refresh = ready_refresh(start);
        assert_eq!(refresh.advance(start, Focus::Session), Ok(Action::Wait));
        assert_eq!(
            refresh.advance(start, Focus::Panel),
            Ok(Action::ReleasePanel)
        );
        assert_eq!(refresh.advance(start, Focus::Panel), Ok(Action::Wait));
        let returned = start + Duration::from_millis(100);
        assert_eq!(refresh.advance(returned, Focus::Session), Ok(Action::Wait));
        assert_eq!(
            refresh.advance(returned + Duration::from_millis(499), Focus::Session),
            Ok(Action::Wait)
        );
        assert_eq!(
            refresh.advance(returned + Duration::from_millis(500), Focus::Session),
            Ok(Action::Paste)
        );
        assert_eq!(
            refresh.advance(returned + Duration::from_millis(501), Focus::Session),
            Ok(Action::Wait)
        );
    }

    #[test]
    fn activation_request_acceptance_without_actual_activation_never_authorizes_paste() {
        let start = Instant::now();
        let mut refresh = ready_refresh(start);
        assert_eq!(refresh.advance(start, Focus::Session), Ok(Action::Wait));
        assert!(refresh
            .advance(start + FOCUS_TIMEOUT, Focus::Session)
            .is_err());
    }

    #[test]
    fn failed_return_to_session_times_out_without_pasting() {
        let start = Instant::now();
        let mut refresh = ready_refresh(start);
        assert_eq!(
            refresh.advance(start, Focus::Panel),
            Ok(Action::ReleasePanel)
        );
        assert!(refresh
            .advance(start + FOCUS_TIMEOUT, Focus::Panel)
            .is_err());
    }

    #[test]
    fn user_focus_changes_cancel_all_phases_before_paste() {
        let start = Instant::now();
        for phase in [
            Phase::AwaitingPanel,
            Phase::AwaitingSession,
            Phase::Transferring,
        ] {
            let mut refresh = Refresh::new(start, 0, 0);
            refresh.phase = phase;
            assert!(refresh.advance(start, Focus::Other).is_err());
        }
    }

    #[test]
    fn longer_user_delays_are_preserved_and_paste_happens_once() {
        let start = Instant::now();
        let mut refresh = Refresh::new(start, 2_000, 3_000);
        refresh.phase = Phase::Transferring;
        assert_eq!(
            refresh.advance(start + Duration::from_millis(1_999), Focus::Session),
            Ok(Action::Wait)
        );
        assert_eq!(
            refresh.advance(start + Duration::from_millis(2_000), Focus::Session),
            Ok(Action::Paste)
        );
        assert_eq!(
            refresh.advance(start + Duration::from_millis(4_999), Focus::Other),
            Ok(Action::Wait)
        );
        assert_eq!(
            refresh.advance(start + Duration::from_millis(5_000), Focus::Other),
            Ok(Action::Finish)
        );
    }

    #[test]
    fn two_dictations_each_require_their_own_activation_refresh() {
        let start = Instant::now();
        for _ in 0..2 {
            let mut refresh = ready_refresh(start);
            assert_eq!(refresh.advance(start, Focus::Session), Ok(Action::Wait));
            assert_eq!(
                refresh.advance(start, Focus::Panel),
                Ok(Action::ReleasePanel)
            );
            assert_eq!(refresh.advance(start, Focus::Session), Ok(Action::Wait));
            assert_eq!(
                refresh.advance(start + Duration::from_millis(500), Focus::Session),
                Ok(Action::Paste)
            );
        }
    }

    #[test]
    fn a_panel_that_regains_focus_during_transfer_cancels_without_pasting() {
        let start = Instant::now();
        let mut refresh = Refresh::new(start, 0, 0);
        refresh.phase = Phase::Transferring;
        assert!(refresh
            .advance(start + Duration::from_secs(1), Focus::Panel)
            .is_err());
    }
}
