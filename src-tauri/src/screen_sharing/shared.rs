//! Keep automatic clipboard sharing ON and refresh the original session.
//!
//! Screen Sharing publishes clipboard promises when its session becomes key;
//! changing the pasteboard while its canvas remains key need not refresh them.
//! Its Edit > Paste item belongs to local responders and can be disabled even
//! while remote keyboard input works. Use public app activation APIs to briefly
//! activate Handy, return to the SAME session, then send the paste chord once.
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

const ACTIVATION_TIMEOUT: Duration = Duration::from_secs(2);
const TICK_INTERVAL: Duration = Duration::from_millis(50);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Focus {
    Handy,
    Session,
    Other,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Phase {
    AwaitingHandy,
    AwaitingSession,
    Transferring,
    Restoring,
}

#[derive(Debug, PartialEq, Eq)]
enum Action {
    Wait,
    ActivateSession,
    Paste,
    Finish,
}

struct Refresh {
    phase: Phase,
    since: Instant,
    transfer_delay: Duration,
    restore_delay: Duration,
    opened: bool,
    reopen_seen: bool,
}

impl Refresh {
    fn new(now: Instant, before_ms: u64, after_ms: u64) -> Self {
        let (before_ms, after_ms) = super::paste_delays(before_ms, after_ms);
        Self {
            phase: Phase::AwaitingHandy,
            since: now,
            transfer_delay: Duration::from_millis(before_ms),
            restore_delay: Duration::from_millis(after_ms),
            opened: false,
            reopen_seen: false,
        }
    }

    fn advance(&mut self, now: Instant, focus: Focus) -> Result<Action, String> {
        let elapsed = now.duration_since(self.since);
        if matches!(self.phase, Phase::AwaitingHandy | Phase::AwaitingSession)
            && elapsed >= ACTIVATION_TIMEOUT
        {
            return Err(format!(
                "Screen Sharing: {:?} activation timed out; no paste was sent",
                self.phase
            ));
        }
        match (self.phase, focus) {
            (Phase::AwaitingHandy, Focus::Handy) if self.opened && self.reopen_seen => {
                self.phase = Phase::AwaitingSession;
                self.since = now;
                Ok(Action::ActivateSession)
            }
            (Phase::AwaitingSession, Focus::Session) => {
                self.phase = Phase::Transferring;
                self.since = now;
                Ok(Action::Wait)
            }
            (Phase::AwaitingHandy, Focus::Session | Focus::Handy)
            | (Phase::AwaitingSession, Focus::Handy) => Ok(Action::Wait),
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
    activation_status: Option<mpsc::Receiver<Result<(), String>>>,
}

// AX and AppKit objects must never cross to the scheduling worker.
thread_local! {
    static PENDING: RefCell<Option<Pending>> = const { RefCell::new(None) };
    static NEXT_ID: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
    static SELF_REOPEN: std::cell::Cell<Option<u64>> = const { std::cell::Cell::new(None) };
    static REOPEN_SEEN: std::cell::Cell<Option<u64>> = const { std::cell::Cell::new(None) };
}

pub(super) fn consume_activation_reopen() -> bool {
    SELF_REOPEN.with(|requested| match requested.take() {
        Some(id) => {
            REOPEN_SEEN.with(|seen| seen.set(Some(id)));
            info!("[screen-sharing] consumed session-refresh reopen; settings window unchanged");
            true
        }
        None => false,
    })
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
        match NSWorkspace::sharedWorkspace().frontmostApplication() {
            Some(app) if app.processIdentifier() == self.target.pid => Focus::Session,
            Some(app) if app.processIdentifier() == std::process::id() as i32 => Focus::Handy,
            _ => Focus::Other,
        }
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
        if self.refresh.phase == Phase::AwaitingHandy && !self.refresh.opened {
            if let Some(status) = &self.activation_status {
                match status.try_recv() {
                    Ok(result) => {
                        result?;
                        self.refresh.opened = true;
                        info!("[screen-sharing] NSWorkspace confirmed the existing Handy process");
                    }
                    Err(mpsc::TryRecvError::Empty) => {}
                    Err(mpsc::TryRecvError::Disconnected) => {
                        return Err("Screen Sharing: app activation completion was lost".into())
                    }
                }
            }
        }
        self.refresh.reopen_seen = REOPEN_SEEN.with(|seen| seen.get() == Some(self.id));
        if self.refresh.phase != Phase::Restoring {
            if self.cancelled() {
                return Err("Screen Sharing: dictation cancelled before paste".into());
            }
            if !self.clipboard_unchanged() {
                return Err(
                    "Screen Sharing: clipboard changed before paste; new copy preserved".into(),
                );
            }
            self.target.ensure_same_window()?;
            if !self.target.shared_clipboard_enabled()? {
                return Err("Screen Sharing: clipboard mode changed before paste".into());
            }
        }
        let previous_phase = self.refresh.phase;
        match self.refresh.advance(Instant::now(), self.focus())? {
            Action::Wait => {
                if previous_phase == Phase::AwaitingSession
                    && self.refresh.phase == Phase::Transferring
                {
                    info!("[screen-sharing] observed original session return; waiting for clipboard transfer");
                    if self.focus_only {
                        info!("[screen-sharing] focus check passed: original window restored with sharing ON; no clipboard text or keys sent");
                        return Ok(true);
                    }
                }
                Ok(false)
            }
            Action::ActivateSession => {
                if self.cancelled() || !self.clipboard_unchanged() {
                    return Err(
                        "Screen Sharing: clipboard or dictation changed before publication".into(),
                    );
                }
                // Publish only AFTER the client has really resigned key status.
                // This matches copying in a local app and returning to VNC;
                // its old promise cleanup cannot consume this change first.
                if !self.focus_only {
                    app.clipboard()
                        .write_text(&self.text)
                        .map_err(|e| e.to_string())?;
                    self.revision = NSPasteboard::generalPasteboard().changeCount();
                    self.published = true;
                }
                // Only return while Handy still owns the activation we requested.
                // Never use ActivateIgnoringOtherApps or activate all windows.
                if self.focus() != Focus::Handy {
                    return Err(
                        "Screen Sharing: user changed focus before return; paste cancelled".into(),
                    );
                }
                super::activation::return_to_session(&self.target.running_app)?;
                info!(
                    "[screen-sharing] returning to the original session with shared clipboard ON"
                );
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
        for clear in [&SELF_REOPEN, &REOPEN_SEEN] {
            clear.with(|slot| {
                if slot.get() == Some(self.id) {
                    slot.set(None);
                }
            });
        }
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
        // If activation failed or the operation was cancelled during the brief
        // Handy phase, return only while Handy is still frontmost. A user who
        // switched elsewhere keeps their chosen app.
        if matches!(
            self.refresh.phase,
            Phase::AwaitingHandy | Phase::AwaitingSession
        ) && self.target.ensure_same_window().is_ok()
            && self.focus() == Focus::Handy
        {
            let _ = super::activation::return_to_session(&self.target.running_app);
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
        activation_status: None,
    };
    pending.target.ensure_focused()?;
    PENDING.with(|slot| *slot.borrow_mut() = Some(pending));
    SELF_REOPEN.with(|requested| requested.set(Some(id)));
    match super::activation::request_self_activation() {
        Ok(status) => PENDING.with(|slot| {
            if let Some(pending) = slot.borrow_mut().as_mut() {
                pending.activation_status = Some(status);
            }
        }),
        Err(error) => {
            PENDING.with(|slot| {
                if let Some(pending) = slot.borrow_mut().take() {
                    pending.finish(&app, false);
                }
            });
            return Err(error);
        }
    }
    info!("[screen-sharing] requested existing app through NSWorkspace; shared clipboard stays ON; focus-only check: {focus_only}");

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
        let mut refresh = Refresh::new(start, 0, 0);
        refresh.opened = true;
        refresh.reopen_seen = true;
        refresh
    }

    #[test]
    fn shared_paste_requires_an_observed_round_trip_and_transfer_delay() {
        let start = Instant::now();
        let mut refresh = ready_refresh(start);
        assert_eq!(refresh.advance(start, Focus::Session), Ok(Action::Wait));
        assert_eq!(
            refresh.advance(start, Focus::Handy),
            Ok(Action::ActivateSession)
        );
        assert_eq!(refresh.advance(start, Focus::Handy), Ok(Action::Wait));
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
            .advance(start + ACTIVATION_TIMEOUT, Focus::Session)
            .is_err());
    }

    #[test]
    fn failed_return_to_session_times_out_without_pasting() {
        let start = Instant::now();
        let mut refresh = ready_refresh(start);
        assert_eq!(
            refresh.advance(start, Focus::Handy),
            Ok(Action::ActivateSession)
        );
        assert!(refresh
            .advance(start + ACTIVATION_TIMEOUT, Focus::Handy)
            .is_err());
    }

    #[test]
    fn user_focus_changes_cancel_all_phases_before_paste() {
        let start = Instant::now();
        for phase in [
            Phase::AwaitingHandy,
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
                refresh.advance(start, Focus::Handy),
                Ok(Action::ActivateSession)
            );
            assert_eq!(refresh.advance(start, Focus::Session), Ok(Action::Wait));
            assert_eq!(
                refresh.advance(start + Duration::from_millis(500), Focus::Session),
                Ok(Action::Paste)
            );
        }
    }

    #[test]
    fn activation_waits_for_both_same_process_completion_and_consumed_reopen() {
        let start = Instant::now();
        for (opened, reopen_seen) in [(false, false), (true, false), (false, true)] {
            let mut refresh = Refresh::new(start, 0, 0);
            refresh.opened = opened;
            refresh.reopen_seen = reopen_seen;
            assert_eq!(refresh.advance(start, Focus::Handy), Ok(Action::Wait));
            assert_eq!(refresh.phase, Phase::AwaitingHandy);
            assert!(refresh
                .advance(start + ACTIVATION_TIMEOUT, Focus::Handy)
                .is_err());
        }
        let mut refresh = ready_refresh(start);
        assert_eq!(
            refresh.advance(start, Focus::Handy),
            Ok(Action::ActivateSession)
        );
    }

    #[test]
    fn only_one_requested_reopen_is_consumed_and_later_user_reopens_are_normal() {
        assert!(!consume_activation_reopen());
        SELF_REOPEN.with(|requested| requested.set(Some(123)));
        assert!(consume_activation_reopen());
        assert_eq!(REOPEN_SEEN.with(|seen| seen.get()), Some(123));
        assert!(!consume_activation_reopen());
        REOPEN_SEEN.with(|seen| seen.set(None));
    }
}
