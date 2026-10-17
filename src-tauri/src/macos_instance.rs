//! Start the requested build rather than forwarding its launch to stale code.
//! Only GUI Handy instances are asked to quit, using normal AppKit termination.

use core_foundation::{
    base::{CFType, CFTypeRef, TCFType},
    data::CFData,
    dictionary::{CFDictionary, CFDictionaryRef},
    number::CFNumber,
    string::{CFString, CFStringRef},
    url::{CFURLRef, CFURL},
};
use objc2::MainThreadMarker;
use objc2_app_kit::{NSAlert, NSRunningApplication};
use objc2_foundation::NSString;
use std::{
    path::{Path, PathBuf},
    sync::OnceLock,
    time::{Duration, Instant},
};

static STARTED_IMAGE: OnceLock<(PathBuf, Vec<u8>)> = OnceLock::new();

#[link(name = "Security", kind = "framework")]
extern "C" {
    static kSecGuestAttributePid: CFStringRef;
    static kSecCodeInfoUnique: CFStringRef;
    fn SecCodeCopyGuestWithAttributes(
        host: CFTypeRef,
        attributes: CFDictionaryRef,
        flags: u32,
        code: *mut CFTypeRef,
    ) -> i32;
    fn SecCodeCheckValidity(code: CFTypeRef, flags: u32, requirement: CFTypeRef) -> i32;
    fn SecCodeCopySigningInformation(
        code: CFTypeRef,
        flags: u32,
        information: *mut CFDictionaryRef,
    ) -> i32;
    fn SecStaticCodeCreateWithPath(path: CFURLRef, flags: u32, code: *mut CFTypeRef) -> i32;
}

fn signature(code: &CFType) -> Result<Vec<u8>, String> {
    let mut information = std::ptr::null();
    // SAFETY: a retained code object is passed, and the create-rule dictionary
    // is owned until all data has been copied. A missing/mistyped hash fails.
    unsafe {
        let status = SecCodeCopySigningInformation(code.as_CFTypeRef(), 0, &mut information);
        if status != 0 || information.is_null() {
            return Err(format!("Cannot read code signature ({status})"));
        }
        let information = CFDictionary::<CFString, CFType>::wrap_under_create_rule(information);
        let key = CFString::wrap_under_get_rule(kSecCodeInfoUnique);
        information
            .find(&key)
            .and_then(|value| value.downcast::<CFData>())
            .map(|data| data.bytes().to_vec())
            .filter(|hash| !hash.is_empty())
            .ok_or_else(|| "Code signature has no build identifier".to_owned())
    }
}

fn running_signature(pid: i32) -> Result<Vec<u8>, String> {
    let mut code = std::ptr::null();
    // SAFETY: the PID is a scalar from NSRunningApplication. The dictionary
    // outlives the call and the returned object is managed under the create rule.
    unsafe {
        let attributes = CFDictionary::from_CFType_pairs(&[(
            CFString::wrap_under_get_rule(kSecGuestAttributePid),
            CFNumber::from(pid),
        )]);
        let status = SecCodeCopyGuestWithAttributes(
            std::ptr::null(),
            attributes.as_concrete_TypeRef(),
            0,
            &mut code,
        );
        if status != 0 || code.is_null() {
            return Err(format!("Cannot identify running Handy {pid} ({status})"));
        }
        let code = CFType::wrap_under_create_rule(code);
        // Signing info can come from disk. Dynamic validation also detects an
        // older process whose bundle was replaced in place by the new build.
        let status = SecCodeCheckValidity(code.as_CFTypeRef(), 0, std::ptr::null());
        if status != 0 {
            return Err(format!("Running image differs from its bundle ({status})"));
        }
        signature(&code)
    }
}

fn disk_signature(bundle: &Path) -> Result<Vec<u8>, String> {
    let url = CFURL::from_path(bundle, true).ok_or("Cannot resolve Handy bundle")?;
    let mut code = std::ptr::null();
    // SAFETY: the URL remains alive and the returned code object is retained.
    unsafe {
        let status = SecStaticCodeCreateWithPath(url.as_concrete_TypeRef(), 0, &mut code);
        if status != 0 || code.is_null() {
            return Err(format!("Cannot identify installed Handy ({status})"));
        }
        signature(&CFType::wrap_under_create_rule(code))
    }
}

fn should_replace(current_pid: i32, other_pid: i32, current: &[u8], other: Option<&[u8]>) -> bool {
    current_pid != other_pid && other != Some(current)
}

fn process_exists(pid: i32) -> bool {
    if pid <= 0 {
        return false;
    }
    // SAFETY: signal zero only queries existence; it sends no signal. AppKit's
    // isTerminated is cached until the main run loop runs, which has not yet
    // started here. Waiting on it could time out after a successful normal quit.
    (unsafe { libc::kill(pid, 0) == 0 })
        || std::io::Error::last_os_error().raw_os_error() != Some(libc::ESRCH)
}

pub fn replace_older_instances(args: &crate::CliArgs) -> Result<Vec<i32>, String> {
    if args.controls_existing_or_headless() {
        return Ok(Vec::new());
    }
    let executable = std::env::current_exe().map_err(|error| error.to_string())?;
    let Some(bundle) = executable
        .ancestors()
        .nth(3)
        .filter(|path| path.extension().is_some_and(|ext| ext == "app"))
    else {
        return Ok(Vec::new()); // Cargo/dev/test executables do not replace the app.
    };
    let pid = std::process::id() as i32;
    let current = running_signature(pid)?;
    let _ = STARTED_IMAGE.set((bundle.to_owned(), current.clone()));
    let mut replaced = Vec::new();
    let applications = NSRunningApplication::runningApplicationsWithBundleIdentifier(
        &NSString::from_str("com.pais.handy"),
    );
    for application in applications.iter() {
        let other_pid = application.processIdentifier();
        if other_pid == pid || !process_exists(other_pid) {
            continue;
        }
        let other = running_signature(other_pid).ok();
        if !should_replace(pid, other_pid, &current, other.as_deref()) {
            continue;
        }
        if !application.terminate() && process_exists(other_pid) {
            return Err(format!(
                "The previous Handy (PID {other_pid}) did not accept a normal quit request."
            ));
        }
        let deadline = Instant::now() + Duration::from_secs(8);
        while process_exists(other_pid) {
            if Instant::now() >= deadline {
                return Err(format!("The previous Handy (PID {other_pid}) has not finished quitting. The new app was not started in parallel."));
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        replaced.push(other_pid);
    }
    Ok(replaced)
}

/// Finder may send Reopen to the old process without launching the new binary.
pub fn installed_image_changed() -> bool {
    STARTED_IMAGE.get().is_some_and(|(bundle, original)| {
        disk_signature(bundle).is_ok_and(|current| current != *original)
    })
}

pub fn show_startup_error(error: &str) {
    eprintln!("Handy startup: {error}");
    if let Some(main_thread) = MainThreadMarker::new() {
        let alert = NSAlert::new(main_thread);
        alert.setMessageText(&NSString::from_str("Handy could not start the new version"));
        alert.setInformativeText(&NSString::from_str(error));
        alert.runModal();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use clap::Parser;

    #[test]
    fn replace_only_another_build_and_never_self() {
        assert!(!should_replace(1, 1, b"new", None));
        assert!(!should_replace(1, 2, b"new", Some(b"new")));
        assert!(should_replace(1, 2, b"new", Some(b"old")));
        assert!(should_replace(1, 2, b"new", None));
    }

    #[test]
    fn remote_commands_and_headless_jobs_preserve_running_app() {
        for flag in [
            "--toggle-transcription",
            "--toggle-post-process",
            "--cancel",
            "--check-screen-sharing-focus",
            "--list-models",
            "--list-devices",
        ] {
            assert!(crate::CliArgs::parse_from(["handy", flag]).controls_existing_or_headless());
        }
        assert!(
            crate::CliArgs::parse_from(["handy", "--transcribe-file", "sample.wav"])
                .controls_existing_or_headless()
        );
        assert!(!crate::CliArgs::parse_from(["handy"]).controls_existing_or_headless());
    }

    #[test]
    fn public_security_api_reads_this_process_without_input_hooks() {
        assert!(!running_signature(std::process::id() as i32)
            .unwrap()
            .is_empty());
    }

    #[test]
    fn termination_wait_uses_process_liveness_without_appkit_cache() {
        assert!(process_exists(std::process::id() as i32));
        assert!(!process_exists(i32::MAX));
        assert!(!process_exists(0));
    }
}
