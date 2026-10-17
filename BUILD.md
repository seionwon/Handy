# Build Instructions

This guide covers how to set up the development environment and build Handy from source across different platforms.

## Prerequisites

### All Platforms

- [Rust](https://rustup.rs/) (latest stable)
- [Bun](https://bun.sh/) package manager
- [Tauri Prerequisites](https://tauri.app/start/prerequisites/)

### Platform-Specific Requirements

#### macOS

- Xcode Command Line Tools
- Install with: `xcode-select --install`

#### Windows

- Microsoft C++ Build Tools: Visual Studio 2019/2022 with C++ development
  tools, or Visual Studio Build Tools 2019/2022
- [CMake](https://cmake.org/download/) (must be on `PATH`):

  ```powershell
  winget install Kitware.CMake
  ```

- [Vulkan SDK](https://vulkan.lunarg.com/sdk/home) from LunarG — required to
  build the Vulkan GPU backend (`vulkan-shaders-gen` needs the SDK's headers
  and `glslc`):

  ```powershell
  winget install KhronosGroup.VulkanSDK
  ```

  Open a new terminal afterward so `VULKAN_SDK` is set.

> [!NOTE]
> Windows' 260-character path limit used to break the native Vulkan build in
> most checkouts. Since `transcribe-cpp` 0.1.3 the build works around it
> automatically (it compiles through a short NTFS junction — no admin rights
> or setup needed), so a normal checkout just builds. If you still hit
> path-limit errors, see
> [Windows build fails with path-limit errors](#windows-build-fails-with-path-limit-errors-msb3491--ftk1011--msb6003)
> in Troubleshooting.

#### Linux

- Build essentials
- ALSA development libraries
- Install with:

  ```bash
  # Ubuntu/Debian
  sudo apt update
  sudo apt install build-essential clang libclang-dev libevdev-dev libasound2-dev pkg-config libssl-dev libvulkan-dev vulkan-tools glslc spirv-headers glslang-tools libgtk-3-dev libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libgtk-layer-shell0 libgtk-layer-shell-dev patchelf cmake

  # Fedora/RHEL
  sudo dnf groupinstall "Development Tools"
  sudo dnf install alsa-lib-devel pkgconf openssl-devel vulkan-devel glslc \
    clang clang-devel libevdev-devel \
    spirv-headers-devel spirv-tools-devel glslang \
    gtk3-devel webkit2gtk4.1-devel libappindicator-gtk3-devel librsvg2-devel \
    gtk-layer-shell gtk-layer-shell-devel \
    cmake

  # Arch Linux
  sudo pacman -S base-devel clang libevdev shaderc spirv-headers glslang alsa-lib pkgconf openssl vulkan-devel \
    gtk3 webkit2gtk-4.1 libappindicator-gtk3 librsvg gtk-layer-shell \
    cmake
  ```

## Setup Instructions

### 1. Clone the Repository

```bash
git clone https://github.com/seionwon/Handy.git
cd Handy
```

This repository contains the Screen Sharing fork. The original project is
[cjpais/Handy](https://github.com/cjpais/Handy).

### 2. Install Dependencies

```bash
bun install
```

### 3. Start Dev Server

```bash
bun tauri dev
```

### 4. Build for Production

```bash
bun run tauri build
```

This compiles a release binary and generates platform-specific bundles (deb, rpm, AppImage on Linux; dmg on macOS; msi on Windows).

### macOS Screen Sharing distribution (Universal)

To package this modified build for both Apple Silicon and Intel Macs, install
CMake and both Rust targets, then run the packaging script from the repository:

```bash
rustup target add aarch64-apple-darwin x86_64-apple-darwin
bun scripts/build-macos-share.ts
```

With full Xcode installed, select it for this command without changing the
system-wide developer directory:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer bun scripts/build-macos-share.ts
```

The release DMG, SHA-256 checksum and Korean installation guide are written to
`src-tauri/target/share/`. The DMG contains `Handy.app`, an Applications shortcut,
the guide and the MIT license; personal settings, recordings and downloaded
speech models are not copied. Existing DMGs are not overwritten; move an old
installer aside before rebuilding the same version.
The packager creates a compressed APFS image, verifies its checksum, mounts it
read-only, and verifies the code signature both on the mounted app and on a
`ditto` copy. Do not substitute `hdiutil makehybrid -hfs`: it added FinderInfo
attributes that invalidated the app signature on the mounted image.
If this script already completed the app build and only the DMG packaging needs
retrying, use `bun scripts/build-macos-share.ts --package-only`. This reuses the
existing app and applies the distribution signature; do not use it to pick up
source changes. After successful packaging the generated app bundle is removed,
so `--package-only` is for retrying a failed packaging run, before that cleanup.
For an Apple Silicon installer only, add `--arm64-only` when building or
repackaging. Its filename ends in `-arm64.dmg`; the default remains Universal.

On macOS, the build hooks move Cargo output to `src-tauri/target.noindex/` and
leave a relative `target` symlink so existing commands keep working. Spotlight
excludes paths containing `.noindex`; see [Apple's documentation](https://developer.apple.com/documentation/xcode/locating-a-missing-debug-symbol-file).
DMG staging uses a `.noindex` folder, is removed after packaging (including on
failure), and includes an indexing exclusion marker for the temporary volume.
Successful packaging unregisters and removes the generated `.app` as well:
Launch Services can retain registrations even when Spotlight excludes a path.
Installed Handy also ejects marked read-only Handy installer volumes on startup.

This distribution targets macOS 11.0+, uses a persistent local signing certificate
by default (not Apple Developer ID signing or notarization), and requires recipients to approve the app in
Privacy & Security on first launch. See the included `INSTALL-KO.txt` and
[Apple's instructions](https://support.apple.com/ko-kr/102445). It retains Handy's
bundle identifier/settings location and replaces, rather than coexists with,
the official app. The `disable-updater` Cargo feature prevents upstream updates
from replacing the Screen Sharing changes, without modifying saved preferences.

For updates signed with your own existing signing identity, use the same
certificate and bundle identifier consistently:

```bash
HANDY_MACOS_SIGNING_IDENTITY="Developer ID Application: Your Name (TEAMID)" bun scripts/build-macos-share.ts
```

The certificate and its private key must already be available to `codesign`.
This option does not notarize the app or transfer a previous ad-hoc approval.
Without an external identity, the packager creates a dedicated keychain at
`~/Library/Application Support/HandyBuildSigning/` and reuses its certificate.
The directory is private (0700), and its keychain password is stored in a 0600
file. Passwords go to the helper over stdin. Temporary PEM/PKCS#12 private-key
files are removed and the dedicated keychain is locked after signing. The user
keychain search list is preserved; no trust anchors are added. Keep a private
backup of this directory, never commit or distribute it, and restore it when
moving the build to another Mac. An incomplete identity causes a build failure
instead of silently generating a replacement. CI requires a restored identity
or an explicit signing certificate.

The default designated requirement binds `com.pais.handy` to the certificate,
so changed binaries signed with this identity remain compatible. An update from
the previous ad-hoc builds requires one new user approval. Ad-hoc signing with
`HANDY_MACOS_SIGNING_IDENTITY=-` is rejected. See [Apple's code signing guide](https://developer.apple.com/library/archive/technotes/tn2206/).

On a development Mac, `bun run test:macos-signing` checks two different app
binaries against one temporary signing identity, rejects an unrelated signer,
checks private file permissions, and verifies that the user keychain search
list is unchanged. It does not execute the test apps or change TCC grants.

## Linux Install (from source)

The raw binary (`src-tauri/target/release/handy`) cannot run standalone — it needs Tauri resource files (tray icons, sounds, VAD model) to be co-located at the expected path.

**Install from the deb bundle** (works on any Linux distro):

```bash
cd /tmp
ar x /path/to/Handy/src-tauri/target/release/bundle/deb/Handy_*_amd64.deb data.tar.gz
tar xzf data.tar.gz
sudo cp usr/bin/handy /usr/bin/
sudo cp -a usr/lib/. /usr/lib/
sudo cp -r usr/share/icons/hicolor/* /usr/share/icons/hicolor/
sudo cp usr/share/applications/Handy.desktop /usr/share/applications/
```

The runtime libraries live in the app-private `/usr/lib/Handy/` (on the binary's rpath), so no `ldconfig` step is needed.

After subsequent rebuilds, copy the binary and any refreshed runtime libraries:

```bash
sudo cp src-tauri/target/release/handy /usr/bin/
sudo mkdir -p /usr/lib/Handy
sudo cp -a src-tauri/transcribe-libs/. /usr/lib/Handy/
```

Resources only need re-copying if they change upstream (new icons, sounds, models, etc.).

## Troubleshooting

### macOS Accessibility remains enabled after a local rebuild

Handy checks the running process's current grant and reuses it. Leave the existing
**System Settings > Privacy & Security > Accessibility** entry enabled. Quit Handy
normally, eject the installer, and reopen `/Applications/Handy.app` to ensure you
are using the installed version. Permission checks do not create input listeners,
request prompts, or reset privacy records.

Older installers and bare Tauri builds use ad-hoc `signingIdentity: "-"`. A rebuild can change the
code identity even while an old entry remains visibly enabled. If macOS rejects
the new identity, the app cannot reuse that old approval: the current version
needs approval in System Settings. Using a consistent signing identity for future
builds avoids changing to a new ad-hoc identity on every update; see
[Apple's signing requirements](https://developer.apple.com/documentation/technotes/tn3127-inside-code-signing-requirements).

In 0.9.10, denied onboarding calls `AXIsProcessTrustedWithOptions` with the prompt
option once per process. This registers the running identity with the native
consent flow instead of merely opening a settings URL. It does not simultaneously
open another settings window. The separate Open System Settings action remains
available afterward. Toggling an old entry does not necessarily refresh its code
requirement, so that is no longer the recovery instruction. The packager keeps
one certificate for future builds instead of changing the code-hash identity.

Version 0.9.11 adds an explicit recovery action in the main window. It rechecks
the grant and preserves a valid one; if denied, it invokes only
`/usr/bin/tccutil reset Accessibility com.pais.handy`, then normally restarts so
the native consent API can register the current identity. The command accepts no
app or service argument from the webview and rejects requests from other windows.
It does not grant permission or edit TCC databases directly.

GUI startup compares running Handy signatures before the single-instance plugin
can forward the new launch to old code. Different builds are normally terminated
and given up to eight seconds to exit; no force termination is used. The same
build and remote-control CLI flags retain their normal behavior. Finder reopen
also checks for an in-place replacement and restarts the changed installed app.

### GitHub push uses the wrong account

For a checkout that must use `seionwon` on GitHub, configure its credential helper
locally after signing that account into GitHub CLI:

```sh
gh auth login --hostname github.com
git config --local credential.https://github.com.username seionwon
git config --local --replace-all credential.https://github.com.helper ''
git config --local --add credential.https://github.com.helper '!gh auth git-credential'
git push origin main
```

The empty helper entry clears inherited helpers for this host in this checkout;
GitHub CLI supplies the selected username's token. It avoids a cached account
from macOS Keychain taking precedence and does not erase stored credentials or
change another checkout. The local helper configuration itself is not committed.

### Comparing macOS signing identities

For optional diagnosis, compare the designated requirements of the previous and rebuilt
bundles:

```bash
codesign -dr - /path/to/previous/Handy.app 2>&1
codesign -dr - /Applications/Handy.app 2>&1
```

An ad-hoc requirement contains a `cdhash`; a changed requirement confirms the rebuild is
not covered by the old grant. Do not reset a working grant merely because an
entry already exists.

See [issue #1618](https://github.com/cjpais/Handy/issues/1618) for the related onboarding
and stale-permission report.

### Duplicate Handy icons in macOS app search

Use the installed `/Applications/Handy.app` and eject the DMG after installation.
Build caches live in `target.noindex`. Successful packaging removes its generated
app bundle and staging copies. On installed-app startup, only read-only DMGs with
our installer marker and Handy bundle ID are eligible for automatic ejection;
unmarked volumes, writable images and system images are left alone. A busy volume
is not forcibly ejected. Earlier installers need to be ejected once manually.

If search still shows cached copies generated by this repository, run:

```bash
bun scripts/macos-build-layout.ts --clean-search
```

This unregisters only known Handy build/staging paths under this repository.
It leaves the installed app and the global Launch Services database in place.
Warnings mean those registrations could not be confirmed removed; recheck app
search in your own desktop session. Copies in Downloads, other Applications
folders, or other checkouts need to be handled separately after checking their
paths. An application cannot hide arbitrary copies installed elsewhere.

### AppImage build fails on Arch / rolling-release distros

`linuxdeploy` bundles its own `strip` binary which is too old to process system libraries built with newer toolchains on rolling-release distros (Arch, CachyOS, Manjaro, EndeavourOS).

The error from Tauri:

```
Bundling Handy_*_amd64.AppImage
failed to bundle project `failed to run linuxdeploy`
```

Tauri swallows the real linuxdeploy error. To see it, run linuxdeploy manually:

```bash
cd src-tauri/target/release/bundle/appimage
~/.cache/tauri/linuxdeploy-x86_64.AppImage --appimage-extract-and-run \
  --appdir Handy.AppDir --plugin gtk --output appimage
```

**Workaround:** The binary, deb, and rpm bundles all build fine — only the AppImage step fails. To skip it:

```bash
bun run tauri build -- --bundles deb
```

Then install using the deb extraction method above.

### Windows build fails with path-limit errors (`MSB3491` / `FTK1011` / `MSB6003`)

On Windows the native build can fail partway through `transcribe-cpp-sys` with
any of these (all the same root cause):

```
error MSB3491: Could not write lines to file "...VCTargetsPath.tlog\VCTargetsPath.lastbuildstate".
Path: ... exceeds the OS max path limit. The fully qualified file name must be less than 260 characters.
```

```
FileTracker : error FTK1011: could not create the new file tracking log file:
...\vulkan-shaders-gen-build\...\cmTC_xxxxx.tlog\link.write.1.tlog.
The system cannot find the path specified.
```

```
error MSB6003: The specified task executable "CL.exe" could not be run.
System.IO.DirectoryNotFoundException: Could not find a part of the path ...
```

This is **not** a code or toolchain problem — it's Windows' legacy 260-character
path limit (`MAX_PATH`), overflowed by the Vulkan shader generator's nested
CMake build tree on top of Cargo's already-deep
`target\release\build\<crate>-<hash>\out\build\...` directory.

Since `transcribe-cpp` 0.1.3 this is mitigated automatically: the native build
compiles through a short NTFS junction under `%LOCALAPPDATA%\tcs` (created
without admin rights), so a normal checkout builds with no setup. Enabling
Windows long paths does **not** reliably help here — MSBuild's native
`FileTracker` (`tracker.exe`) ignores the long-paths flag — which is why the
junction, not the registry flag, is the fix.

If you still see the errors above, junction creation was likely blocked
(filesystem or corporate policy) — the failing build's log then contains a
`transcribe-cpp-sys: could not create short build junction ...` warning — or
your checkout is deep enough to overflow even the shortened layout. Work
around either case with a short Cargo target directory:

```powershell
# Per-shell:
$env:CARGO_TARGET_DIR = "C:\h"

# Or persist it for all future terminals (note: redirects ALL your
# Rust projects' build output, not just Handy):
[Environment]::SetEnvironmentVariable('CARGO_TARGET_DIR', 'C:\h', 'User')
```

Artifacts then land in `C:\h\release\...` instead of the repo's
`src-tauri\target\`. Open a **new terminal** if you persisted the variable —
it is only picked up by freshly started processes. Then `bun run tauri dev`
and `bun run tauri build` work normally.

### Windows `tauri build` fails at bundling with `program not found`

If the build compiles all the way to `Built application at: ...\handy.exe` and
then fails with:

```
Signing C:\...\handy.exe with a custom signing command
failed to bundle project `program not found`
```

that's the code-signing step: `tauri.conf.json` configures a custom
`signCommand` (`trusted-signing-cli`, Azure Trusted Signing) that only exists
in the release CI environment. Local development doesn't need it:

```powershell
# Development (no bundling/signing at all):
bun run tauri dev

# Or compile a release binary without the installer/signing step:
bun run tauri build --no-bundle
```
