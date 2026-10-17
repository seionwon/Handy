//! Handy-keys based keyboard shortcut implementation
//!
//! This module provides an alternative to Tauri's global-shortcut plugin
//! using the handy-keys library for more control over keyboard events.
//!
//! ## Architecture
//!
//! The implementation uses a dedicated manager thread that owns the `HotkeyManager`:
//!
//! ```text
//! ┌─────────────────┐     commands      ┌──────────────────────┐
//! │   Main Thread   │ ───────────────▶ │   Manager Thread     │
//! │                 │   (via channel)   │                      │
//! │ - register()    │                   │ - owns HotkeyManager │
//! │ - unregister()  │                   │ - polls for events   │
//! └─────────────────┘                   │ - dispatches actions │
//!                                       └──────────────────────┘
//! ```
//!
//! This design ensures thread-safety since `HotkeyManager` is only accessed
//! from a single thread. Commands (register/unregister) are sent via an mpsc
//! channel and responses are synchronously awaited.
//!
//! ## Recording Mode
//!
//! For UI key capture, a separate `KeyboardListener` is created on-demand and
//! polled from a dedicated recording thread. Events are emitted to the frontend
//! via Tauri's event system.

use handy_keys::{Hotkey, HotkeyId, HotkeyManager, HotkeyState, KeyboardListener};
use log::{debug, error, info};
use serde::Serialize;
use specta::Type;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter, Manager};

use crate::settings::{self, get_settings, ShortcutBinding};

use super::handler::handle_shortcut_event;

/// Commands that can be sent to the hotkey manager thread
enum ManagerCommand {
    Register {
        binding_id: String,
        hotkey_string: String,
        response: Sender<Result<(), String>>,
    },
    Unregister {
        binding_id: String,
        response: Sender<Result<(), String>>,
    },
    Shutdown,
}

/// State for the handy-keys shortcut manager
pub struct HandyKeysState {
    /// Channel to send commands to the manager thread (wrapped in Mutex for Sync)
    command_sender: Mutex<Sender<ManagerCommand>>,
    /// Handle to the manager thread (wrapped in Mutex for Sync, allows proper join on drop)
    thread_handle: Mutex<Option<JoinHandle<()>>>,
    recording: Mutex<Option<RecordingSession>>,
}

struct RecordingSession {
    running: Arc<AtomicBool>,
    thread: JoinHandle<()>,
}

const MANAGER_TIMEOUT: Duration = Duration::from_secs(5);
static INITIALIZATION_LOCK: Mutex<()> = Mutex::new(());

fn manager_response<T>(receiver: Receiver<T>) -> Result<T, String> {
    receiver
        .recv_timeout(MANAGER_TIMEOUT)
        .map_err(|e| format!("Keyboard manager did not respond: {e}"))
}

/// Key event sent to frontend during recording mode
#[derive(Debug, Clone, Serialize, Type)]
pub struct FrontendKeyEvent {
    /// Currently pressed modifier keys
    pub modifiers: Vec<String>,
    /// The key that was pressed (if any)
    pub key: Option<String>,
    /// Whether this is a key down event
    pub is_key_down: bool,
    /// The full hotkey string (e.g., "option+space")
    pub hotkey_string: String,
}

impl HandyKeysState {
    /// Create a new HandyKeysState
    pub fn new(app: AppHandle) -> Result<Self, String> {
        let (cmd_tx, cmd_rx) = mpsc::channel::<ManagerCommand>();
        let (ready_tx, ready_rx) = mpsc::channel();

        // Start the manager thread
        let app_clone = app.clone();
        let thread_handle = thread::spawn(move || {
            Self::manager_thread(cmd_rx, app_clone, ready_tx);
        });

        // Never publish an unusable state or wait indefinitely for an OS API.
        manager_response(ready_rx)??;

        Ok(Self {
            command_sender: Mutex::new(cmd_tx),
            thread_handle: Mutex::new(Some(thread_handle)),
            recording: Mutex::new(None),
        })
    }

    /// The main manager thread - owns the HotkeyManager and processes commands
    fn manager_thread(
        cmd_rx: Receiver<ManagerCommand>,
        app: AppHandle,
        ready: Sender<Result<(), String>>,
    ) {
        info!("handy-keys manager thread started");

        // Create the HotkeyManager in this thread
        // On macOS the patched library uses a passive ListenOnly tap. A stalled
        // app must never hold up keyboard or mouse delivery to other apps.
        #[cfg(target_os = "macos")]
        let manager = HotkeyManager::new();
        #[cfg(not(target_os = "macos"))]
        let manager = HotkeyManager::new_with_blocking();
        let manager = match manager {
            Ok(m) => m,
            Err(e) => {
                error!("Failed to create HotkeyManager: {}", e);
                let _ = ready.send(Err(e.to_string()));
                return;
            }
        };
        if ready.send(Ok(())).is_err() {
            return;
        }

        // Maps binding IDs to HotkeyIds and hotkey strings
        let mut binding_to_hotkey: HashMap<String, HotkeyId> = HashMap::new();
        let mut hotkey_to_binding: HashMap<HotkeyId, (String, String)> = HashMap::new(); // (binding_id, hotkey_string)

        loop {
            // Check for hotkey events (non-blocking)
            for _ in 0..64 {
                let Some(event) = manager.try_recv() else {
                    break;
                };
                if let Some((binding_id, hotkey_string)) = hotkey_to_binding.get(&event.id) {
                    debug!(
                        "handy-keys event: binding={}, hotkey={}, state={:?}",
                        binding_id, hotkey_string, event.state
                    );
                    let is_pressed = event.state == HotkeyState::Pressed;
                    handle_shortcut_event(&app, binding_id, hotkey_string, is_pressed);
                }
            }

            // Check for commands (non-blocking with timeout)
            match cmd_rx.recv_timeout(Duration::from_millis(20)) {
                Ok(cmd) => match cmd {
                    ManagerCommand::Register {
                        binding_id,
                        hotkey_string,
                        response,
                    } => {
                        let result = Self::do_register(
                            &manager,
                            &mut binding_to_hotkey,
                            &mut hotkey_to_binding,
                            &binding_id,
                            &hotkey_string,
                        );
                        let _ = response.send(result);
                    }
                    ManagerCommand::Unregister {
                        binding_id,
                        response,
                    } => {
                        let result = Self::do_unregister(
                            &manager,
                            &mut binding_to_hotkey,
                            &mut hotkey_to_binding,
                            &binding_id,
                        );
                        let _ = response.send(result);
                    }
                    ManagerCommand::Shutdown => {
                        info!("handy-keys manager thread shutting down");
                        break;
                    }
                },
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    // No command, continue
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    info!("Command channel disconnected, shutting down");
                    break;
                }
            }
        }

        info!("handy-keys manager thread stopped");
    }

    /// Register a hotkey
    fn do_register(
        manager: &HotkeyManager,
        binding_to_hotkey: &mut HashMap<String, HotkeyId>,
        hotkey_to_binding: &mut HashMap<HotkeyId, (String, String)>,
        binding_id: &str,
        hotkey_string: &str,
    ) -> Result<(), String> {
        let hotkey: Hotkey = hotkey_string
            .parse()
            .map_err(|e| format!("Failed to parse hotkey '{}': {}", hotkey_string, e))?;

        if let Some(id) = binding_to_hotkey.get(binding_id) {
            if hotkey_to_binding
                .get(id)
                .is_some_and(|(_, raw)| raw == hotkey_string)
            {
                return Ok(());
            }
            return Err(format!(
                "Binding '{binding_id}' must be unregistered before replacement"
            ));
        }

        let id = manager
            .register(hotkey)
            .map_err(|e| format!("Failed to register hotkey: {}", e))?;

        binding_to_hotkey.insert(binding_id.to_string(), id);
        hotkey_to_binding.insert(id, (binding_id.to_string(), hotkey_string.to_string()));

        debug!(
            "Registered handy-keys shortcut: {} -> {:?}",
            binding_id, hotkey
        );
        Ok(())
    }

    /// Unregister a hotkey
    fn do_unregister(
        manager: &HotkeyManager,
        binding_to_hotkey: &mut HashMap<String, HotkeyId>,
        hotkey_to_binding: &mut HashMap<HotkeyId, (String, String)>,
        binding_id: &str,
    ) -> Result<(), String> {
        if let Some(id) = binding_to_hotkey.get(binding_id).copied() {
            manager
                .unregister(id)
                .map_err(|e| format!("Failed to unregister hotkey: {}", e))?;
            hotkey_to_binding.remove(&id);
            binding_to_hotkey.remove(binding_id);
            debug!("Unregistered handy-keys shortcut: {}", binding_id);
        }
        Ok(())
    }

    /// Register a shortcut binding
    pub fn register(&self, binding: &ShortcutBinding) -> Result<(), String> {
        let (tx, rx) = mpsc::channel();
        self.command_sender
            .lock()
            .map_err(|_| "Failed to lock command_sender")?
            .send(ManagerCommand::Register {
                binding_id: binding.id.clone(),
                hotkey_string: binding.current_binding.clone(),
                response: tx,
            })
            .map_err(|_| "Failed to send register command")?;

        manager_response(rx)?
    }

    /// Unregister a shortcut binding
    pub fn unregister(&self, binding: &ShortcutBinding) -> Result<(), String> {
        let (tx, rx) = mpsc::channel();
        self.command_sender
            .lock()
            .map_err(|_| "Failed to lock command_sender")?
            .send(ManagerCommand::Unregister {
                binding_id: binding.id.clone(),
                response: tx,
            })
            .map_err(|_| "Failed to send unregister command")?;

        manager_response(rx)?
    }

    /// Capture on a dedicated thread with a fresh cancellation flag per session.
    pub fn start_recording(&self, app: &AppHandle, _binding_id: String) -> Result<(), String> {
        let mut session = self
            .recording
            .lock()
            .map_err(|_| "Failed to lock recording")?;
        if session
            .as_ref()
            .is_some_and(|s| s.running.load(Ordering::SeqCst))
        {
            return Err("Already recording".into());
        }
        if let Some(previous) = session.take() {
            let _ = previous.thread.join();
        }
        let listener = KeyboardListener::new()
            .map_err(|e| format!("Failed to create keyboard listener: {e}"))?;
        super::suspend_all_shortcuts(app);
        let running = Arc::new(AtomicBool::new(true));
        let thread_running = Arc::clone(&running);
        let app = app.clone();
        let thread = thread::spawn(move || {
            let started = Instant::now();
            while thread_running.load(Ordering::SeqCst)
                && started.elapsed() < Duration::from_secs(30)
            {
                // Stop global key capture as soon as Handy loses focus. Never
                // forward another app's typing to any webview.
                if !app
                    .get_webview_window("main")
                    .is_some_and(|w| w.is_focused().unwrap_or(false))
                {
                    break;
                }
                match listener.recv_timeout(Duration::from_millis(100)) {
                    Ok(key_event) => {
                        if !thread_running.load(Ordering::SeqCst)
                            || !app
                                .get_webview_window("main")
                                .is_some_and(|w| w.is_focused().unwrap_or(false))
                        {
                            break;
                        }
                        let frontend_event = FrontendKeyEvent {
                            modifiers: modifiers_to_strings(key_event.modifiers),
                            key: key_event.key.map(|k| k.to_string().to_lowercase()),
                            is_key_down: key_event.is_key_down,
                            hotkey_string: key_event
                                .as_hotkey()
                                .map(|h| h.to_handy_string())
                                .unwrap_or_default(),
                        };
                        let _ = app.emit_to("main", "handy-keys-event", &frontend_event);
                    }
                    Err(handy_keys::Error::Timeout) => {}
                    Err(_) => break,
                }
            }
            drop(listener);
            if thread_running.swap(false, Ordering::SeqCst) {
                // Timeout, focus loss, or listener failure: recover without
                // relying on a still-responsive frontend to stop capture.
                super::resume_all_shortcuts(&app);
                let _ = app.emit_to("main", "handy-keys-recording-stopped", ());
            }
        });
        *session = Some(RecordingSession { running, thread });
        Ok(())
    }

    pub fn stop_recording(&self) -> Result<(), String> {
        let mut session = self
            .recording
            .lock()
            .map_err(|_| "Failed to lock recording")?;
        if let Some(session) = session.take() {
            session.running.store(false, Ordering::SeqCst);
            let _ = session.thread.join();
        }
        Ok(())
    }
}

impl Drop for HandyKeysState {
    fn drop(&mut self) {
        if let Ok(session) = self.recording.get_mut() {
            if let Some(session) = session.take() {
                session.running.store(false, Ordering::SeqCst);
            }
        }
        if let Ok(sender) = self.command_sender.get_mut() {
            let _ = sender.send(ManagerCommand::Shutdown);
        }
        // Shutdown must not hold the main thread hostage to OS event APIs.
        if let Ok(handle) = self.thread_handle.get_mut() {
            if let Some(handle) = handle.take().filter(|h| h.is_finished()) {
                let _ = handle.join();
            }
        }
    }
}

/// Convert handy-keys Modifiers to a list of strings
fn modifiers_to_strings(modifiers: handy_keys::Modifiers) -> Vec<String> {
    let mut result = Vec::new();

    if modifiers.contains(handy_keys::Modifiers::CTRL) {
        result.push("ctrl".to_string());
    }
    if modifiers.contains(handy_keys::Modifiers::OPT) {
        #[cfg(target_os = "macos")]
        result.push("option".to_string());
        #[cfg(not(target_os = "macos"))]
        result.push("alt".to_string());
    }
    if modifiers.contains(handy_keys::Modifiers::SHIFT) {
        result.push("shift".to_string());
    }
    if modifiers.contains(handy_keys::Modifiers::CMD) {
        #[cfg(target_os = "macos")]
        result.push("command".to_string());
        #[cfg(not(target_os = "macos"))]
        result.push("super".to_string());
    }
    if modifiers.contains(handy_keys::Modifiers::FN) {
        result.push("fn".to_string());
    }

    result
}

/// Validate a shortcut string for the HandyKeys implementation.
/// HandyKeys is more permissive: allows modifier-only combos and the fn key.
pub fn validate_shortcut(raw: &str) -> Result<(), String> {
    if raw.trim().is_empty() {
        return Err("Shortcut cannot be empty".into());
    }
    // HandyKeys accepts modifier-only, key-only, and modifier+key combos
    // Just verify the string is parseable
    raw.parse::<Hotkey>()
        .map(|_| ())
        .map_err(|e| format!("Invalid shortcut for HandyKeys: {}", e))
}

/// Initialize handy-keys shortcuts
pub fn init_shortcuts(app: &AppHandle) -> Result<(), String> {
    let _guard = INITIALIZATION_LOCK
        .lock()
        .map_err(|_| "Keyboard initialization lock poisoned")?;
    if app.try_state::<HandyKeysState>().is_some() {
        return Ok(());
    }
    let state = HandyKeysState::new(app.clone())?;

    let default_bindings = settings::get_default_settings().bindings;
    let user_settings = settings::load_or_create_app_settings(app);

    // Register all bindings except cancel (which is dynamic)
    for (id, default_binding) in default_bindings {
        if id == "cancel" {
            continue;
        }
        // Skip post-processing shortcut when the feature is disabled
        if id == "transcribe_with_post_process" && !user_settings.post_process_enabled {
            continue;
        }

        let binding = user_settings
            .bindings
            .get(&id)
            .cloned()
            .unwrap_or(default_binding);

        if let Err(e) = state.register(&binding) {
            error!(
                "Failed to register handy-keys shortcut {} during init: {}",
                id, e
            );
            return Err(e);
        }
    }

    app.manage(state);
    info!("handy-keys shortcuts initialized");
    Ok(())
}

/// Register a shortcut
pub fn register_shortcut(app: &AppHandle, binding: ShortcutBinding) -> Result<(), String> {
    let state = app
        .try_state::<HandyKeysState>()
        .ok_or("HandyKeysState not initialized")?;
    state.register(&binding)
}

/// Unregister a shortcut
pub fn unregister_shortcut(app: &AppHandle, binding: ShortcutBinding) -> Result<(), String> {
    let state = app
        .try_state::<HandyKeysState>()
        .ok_or("HandyKeysState not initialized")?;
    state.unregister(&binding)
}

/// Start key recording mode
#[tauri::command]
#[specta::specta]
pub async fn start_handy_keys_recording(
    app: AppHandle,
    window: tauri::WebviewWindow,
    binding_id: String,
) -> Result<(), String> {
    if window.label() != "main" || !window.is_focused().unwrap_or(false) {
        return Err("Shortcut capture requires the focused main window".into());
    }
    tauri::async_runtime::spawn_blocking(move || start_recording_impl(app, binding_id))
        .await
        .map_err(|e| e.to_string())?
}

fn start_recording_impl(app: AppHandle, binding_id: String) -> Result<(), String> {
    let settings = get_settings(&app);
    if settings.keyboard_implementation != settings::KeyboardImplementation::HandyKeys {
        return Err("handy-keys is not the active keyboard implementation".into());
    }

    // While Secure Input is active the tap receives no KeyDown/KeyUp, so the
    // recorder would silently capture just the modifier and overwrite the
    // binding with it (issue #1578). Refuse instead; the frontend maps this
    // marker to a localized explanation, and the noted impact makes the
    // warning banner appear with the full story.
    if crate::secure_input::is_enabled_now() {
        crate::secure_input::note_recorder_blocked(&app);
        return Err("secure-input-active".into());
    }

    let state = app
        .try_state::<HandyKeysState>()
        .ok_or("HandyKeysState not initialized")?;

    if !settings.bindings.contains_key(&binding_id) {
        return Err("Unknown shortcut binding".into());
    }
    state.start_recording(&app, binding_id)
}

/// Stop key recording mode
#[tauri::command]
#[specta::specta]
pub async fn stop_handy_keys_recording(app: AppHandle) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let Some(state) = app.try_state::<HandyKeysState>() else {
            return Ok(());
        };

        // Restore shortcuts from settings regardless of how recording ended.
        // A commit has already registered the new binding via change_binding;
        // re-registering it here fails cleanly and is ignored.
        let result = state.stop_recording();
        super::resume_all_shortcuts(&app);
        result
    })
    .await
    .map_err(|e| e.to_string())?
}
