# Screen Sharing clipboard modes

**0.9.11 failed with shared clipboard ON in actual use.** The installed app logged `Screen Sharing: Edit > Paste is disabled` twice on 2026-10-17. The local Paste menu is not a usable remote-canvas paste action. Its mocked command-routing tests did not detect that native behavior.

**0.9.12 also failed in actual use.** On 2026-10-17, the installed app repeatedly logged `Handy could not refresh session activation` before publishing clipboard text. Calling `NSRunningApplication.activateWithOptions` for Handy returned false. The earlier state-machine tests did not exercise that native request.

**0.9.13 uses `NSWorkspace` to open the existing Handy process, then yields activation back to the same session. Live remote text receipt remains unverified.**

## Behavior

| Use Shared Clipboard | Handy action                                                                                                                                    |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| ON                   | Briefly activate Handy, publish fresh text after activation, return to the same Screen Sharing window, wait, then send the paste shortcut once. |
| OFF                  | Publish text, invoke localized **Edit → Send Clipboard**, wait, then send the paste shortcut.                                                   |

The Screen Sharing client inspected on this Mac publishes shared clipboard promises when its session window becomes key. An in-place clipboard write while the same window remains key need not refresh those promises. The new route reproduces copying in a local app and returning to Screen Sharing. It uses public `NSWorkspace` and cooperative `NSApplication`/`NSRunningApplication` activation APIs; it does not modify Apple's app, call its private clipboard APIs, toggle the sharing checkbox, or inject mouse movements.

The activation flow follows [Apple’s cooperative activation guidance](https://developer.apple.com/documentation/appkit/passing-control-from-one-app-to-another-with-cooperative-activation).

Both **Paste** and **Send Clipboard** can be disabled with sharing ON. Neither menu is used by the new ON path. Ordinary local-to-remote copy/paste remains available because sharing stays ON throughout.

## Transaction and safety

- Capture the original process, window, clipboard mode, and clipboard contents before requesting activation.
- Open the existing bundle with `NSWorkspace`, with new instances and running-app substitution disabled. Require a completion naming the current PID, consume the matching Reopen without changing settings/tray visibility, and observe Handy actually becoming foreground before publishing text. All three must occur within the activation deadline.
- On macOS 14+, yield activation to the original Screen Sharing process before requesting its activation. Earlier macOS versions use the original activation API.
- Request return to the original session only while Handy remains the active app. Observe that the same window really returned before starting the transfer delay.
- Bound each activation phase to two seconds. Cancel before paste on a different foreground app, changed session window, clipboard mode change, intervening copy, or dictation cancellation. Preserve newer user copies.
- Keep the main run loop free between phases. A worker schedules short main-thread checks; it never holds AppKit or Accessibility objects. Retain the sharing checkbox for the transaction instead of repeatedly traversing another process's full menu tree.
- Apply at least 500 ms after the session returns before paste, and 1,500 ms after injection before restoring clipboard contents. Honor larger user delays. These delays allow transfer; they are not a remote receipt.
- Send the paste chord once. If injection reports an error after partial delivery, keep the restoration wait and report failure without retrying or switching input routes.
- Finish clipboard handling and optional auto-submit in the asynchronous transaction. Auto-submit requires the same focused session, unchanged clipboard and mode, and no cancellation. A newer user copy is never overwritten.
- Reject an overlapping shared paste before it alters the clipboard. Its transcription remains in History.

Reliable Paste remains bypassed for Screen Sharing. A local pasteboard read is not proof that a remote app consumed the new text.

## Automated coverage

The native Rust tests exercise the activation state machine and manual transfer through mocks, without requesting permissions, activating real apps, changing the system clipboard, or posting input. They cover observed activation versus request acceptance, return timeouts, user focus changes, per-dictation refresh, a single paste, preservation of longer delays, rejection of shared mode in the synchronous path, and OFF-mode transfer and cancellation.

For 0.9.13, 16 Screen Sharing tests, eight clipboard transaction tests, three key-release tests, and four process-replacement tests passed (31 total). Regression coverage includes same-process completion/Reopen gating, suppression of exactly one requested Reopen, and rejection of activation from unbundled test processes. Clipboard transaction and key-release tests cover the surrounding input path. They include restoration after preparation/key failures and key release after partial errors or panics. Lint, formatting, and release clippy also passed; clippy reported only the existing model-manager formatting warnings. None of these tests establishes live transfer behavior of Apple's client, network latency, or the remote application's paste handling.

## Native activation verification

An activation-only AppKit probe on this Mac (macOS 27.0.1) reproduced the rejection of direct self-activation. `NSWorkspace.openApplicationAtURL` activated the same process successfully, including with a Reopen handler that shows no window. Cooperative return to the original foreground app also succeeded. The probe read no clipboard text and posted no input.

The installed **0.9.13** passed the production focus diagnostic on 2026-10-17 with **Use Shared Clipboard ON**. The log confirmed the same running Handy process, consumption of its own Reopen, and observed return to the original Screen Sharing window. Its GUI PID stayed unchanged during the check. No clipboard text or input was sent. During installation, 0.9.13 also normally replaced the running 0.9.12 process and initialized input and shortcuts using the existing permission grant.

Handy exposes a diagnostic that uses the production activation path against the already-connected, foreground Screen Sharing window with sharing ON:

```sh
/Applications/Handy.app/Contents/MacOS/handy --check-screen-sharing-focus
```

Handy must already be running. The command forwards to that process and changes focus briefly; it skips clipboard snapshots, clipboard revision checks, publication/restoration, paste keys, microphone use, and auto-submit. A successful round trip logs `focus check passed` with no transcript or connection address. It verifies native focus handling only. It cannot establish fresh remote text receipt.

## Live validation still required

Use a 0.9.13 build with Apple's Screen Sharing app connected to another Mac in Control Mode and Handy's **Clipboard (Cmd+V)** method.

1. Keep **Use Shared Clipboard ON** throughout.
2. In an empty remote editor, dictate two different sentences. Each operation should briefly activate Handy and return to the same remote window. Compare both results with History; the second must not repeat the first.
3. Copy different text in a local app and paste into the remote editor normally. Dictate another sentence afterwards.
4. Repeat with Korean using a Korean-capable model and in the target applications and connection types actually used.
5. Switch to another local app, change remote windows, cancel, or copy other text before paste. Verify cancellation and preservation of the newer copy.
6. With sharing OFF, verify the existing Send Clipboard path still works.

If this fails, retain the non-content `[screen-sharing]` phase/error logs, both macOS versions, connection type, target app, and configured delays. Do not log dictated text or remote connection addresses. A log saying the chord was sent is not evidence the remote field received fresh text.
