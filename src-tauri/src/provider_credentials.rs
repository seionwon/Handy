use crate::settings::PostProcessProvider;

const SERVICE: &str = "com.pais.handy.post-processing";

pub(crate) fn origin(address: &str) -> Result<String, String> {
    let url = reqwest::Url::parse(address.trim()).map_err(|_| "Invalid provider URL")?;
    let local = matches!(url.host_str(), Some("127.0.0.1" | "localhost" | "[::1]"));
    if url.host().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || !(url.scheme() == "https" || (url.scheme() == "http" && local))
    {
        return Err("Use an HTTPS provider URL, or HTTP on localhost, without embedded credentials, query or fragment.".into());
    }
    Ok(url.origin().ascii_serialization())
}

fn account(provider: &PostProcessProvider) -> Result<String, String> {
    Ok(format!("{}@{}", provider.id, origin(&provider.base_url)?))
}

pub(crate) fn read(provider: &PostProcessProvider) -> Result<Option<String>, String> {
    native::read(&account(provider)?)
}

pub(crate) fn write(provider: &PostProcessProvider, value: &str) -> Result<(), String> {
    native::write(&account(provider)?, value)
}

#[cfg(target_os = "macos")]
mod native {
    use super::SERVICE;
    use security_framework::passwords::{
        delete_generic_password, get_generic_password, set_generic_password,
    };

    pub(super) fn read(account: &str) -> Result<Option<String>, String> {
        match get_generic_password(SERVICE, account) {
            Ok(bytes) => String::from_utf8(bytes)
                .map(Some)
                .map_err(|_| "Invalid Keychain entry".into()),
            Err(e) if e.code() == -25300 => Ok(None),
            Err(e) => Err(format!(
                "Could not read Handy's Keychain entry ({})",
                e.code()
            )),
        }
    }

    pub(super) fn write(account: &str, value: &str) -> Result<(), String> {
        let result = if value.is_empty() {
            delete_generic_password(SERVICE, account)
        } else {
            set_generic_password(SERVICE, account, value.as_bytes())
        };
        match result {
            Ok(()) => Ok(()),
            Err(e) if value.is_empty() && e.code() == -25300 => Ok(()),
            Err(e) => Err(format!(
                "Could not update Handy's Keychain entry ({})",
                e.code()
            )),
        }
    }
}

#[cfg(target_os = "windows")]
mod native {
    use super::SERVICE;
    use std::io::Error;
    use windows_sys::Win32::Security::Credentials::{
        CredDeleteW, CredFree, CredReadW, CredWriteW, CREDENTIALW, CRED_PERSIST_LOCAL_MACHINE,
        CRED_TYPE_GENERIC,
    };

    fn target(account: &str) -> Vec<u16> {
        format!("{SERVICE}/{account}")
            .encode_utf16()
            .chain(Some(0))
            .collect()
    }

    pub(super) fn read(account: &str) -> Result<Option<String>, String> {
        let target = target(account);
        let mut credential = std::ptr::null_mut();
        // CredReadW owns this allocation until CredFree. Copy the blob first.
        unsafe {
            if CredReadW(target.as_ptr(), CRED_TYPE_GENERIC, 0, &mut credential) == 0 {
                let e = Error::last_os_error();
                return if e.raw_os_error() == Some(1168) {
                    Ok(None)
                } else {
                    Err(e.to_string())
                };
            }
            let item = &*credential;
            let data = if item.CredentialBlobSize == 0 {
                Vec::new()
            } else {
                std::slice::from_raw_parts(item.CredentialBlob, item.CredentialBlobSize as usize)
                    .to_vec()
            };
            CredFree(credential.cast());
            String::from_utf8(data)
                .map(Some)
                .map_err(|_| "Invalid Credential Manager entry".into())
        }
    }

    pub(super) fn write(account: &str, value: &str) -> Result<(), String> {
        let mut target = target(account);
        let success = unsafe {
            if value.is_empty() {
                CredDeleteW(target.as_ptr(), CRED_TYPE_GENERIC, 0)
            } else {
                let item = CREDENTIALW {
                    Type: CRED_TYPE_GENERIC,
                    TargetName: target.as_mut_ptr(),
                    CredentialBlobSize: value
                        .len()
                        .try_into()
                        .map_err(|_| "API key is too long")?,
                    CredentialBlob: value.as_ptr().cast_mut(),
                    Persist: CRED_PERSIST_LOCAL_MACHINE,
                    ..std::mem::zeroed()
                };
                CredWriteW(&item, 0)
            }
        };
        if success != 0 {
            return Ok(());
        }
        let e = Error::last_os_error();
        if value.is_empty() && e.raw_os_error() == Some(1168) {
            Ok(())
        } else {
            Err(e.to_string())
        }
    }
}

#[cfg(target_os = "linux")]
mod native {
    use super::SERVICE;
    use std::{
        io::Write,
        process::{Command, Stdio},
    };

    fn command(verb: &str, account: &str) -> Command {
        let mut cmd = Command::new("secret-tool");
        cmd.arg(verb);
        if verb == "store" {
            cmd.arg("--label=Handy post-processing API key");
        }
        cmd.args(["service", SERVICE, "account", account]);
        cmd
    }

    pub(super) fn read(account: &str) -> Result<Option<String>, String> {
        let output = command("lookup", account)
            .output()
            .map_err(|_| "Install libsecret's secret-tool and unlock the desktop keyring")?;
        if output.status.success() {
            let key = String::from_utf8(output.stdout).map_err(|_| "Invalid keyring entry")?;
            Ok(Some(key.trim_end_matches('\n').to_owned()))
        } else if output.status.code() == Some(1) && output.stderr.is_empty() {
            Ok(None)
        } else {
            Err("Could not read the desktop keyring".into())
        }
    }

    pub(super) fn write(account: &str, value: &str) -> Result<(), String> {
        let mut child = command(if value.is_empty() { "clear" } else { "store" }, account)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|_| "Install libsecret's secret-tool and unlock the desktop keyring")?;
        if let Some(mut input) = child.stdin.take() {
            input
                .write_all(value.as_bytes())
                .map_err(|e| e.to_string())?;
        }
        if child.wait().map_err(|e| e.to_string())?.success() {
            Ok(())
        } else {
            Err("Could not update the desktop keyring".into())
        }
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
mod native {
    pub(super) fn read(_: &str) -> Result<Option<String>, String> {
        Ok(None)
    }
    pub(super) fn write(_: &str, _: &str) -> Result<(), String> {
        Err("Secure credential storage is unavailable".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn binds_credentials_to_the_provider_and_network_origin() {
        let mut provider = crate::settings::get_default_settings()
            .post_process_providers
            .remove(0);
        provider.base_url = "https://EXAMPLE.org:443/v1".into();
        let first = account(&provider).unwrap();
        provider.base_url = "https://example.org/v2".into();
        assert_eq!(first, account(&provider).unwrap());
        provider.base_url = "https://example.org:8443/v2".into();
        assert_ne!(first, account(&provider).unwrap());
        provider.base_url = "https://example.org/v2".into();
        provider.id = "another-provider".into();
        assert_ne!(first, account(&provider).unwrap());
    }

    #[test]
    fn permits_cleartext_only_for_loopback_providers() {
        for address in [
            "http://localhost:11434/v1",
            "http://127.0.0.1",
            "http://[::1]:8080",
        ] {
            assert!(origin(address).is_ok());
        }
        for address in [
            "http://example.org",
            "http://localhost.example.org",
            "https://user@example.org",
            "https://example.org?key=fixture",
            "file:///models",
        ] {
            assert!(origin(address).is_err());
        }
    }
}
