# Screen Sharing clipboard modes

**0.9.11 failed with shared clipboard ON in actual use.** The installed app logged `Screen Sharing: Edit > Paste is disabled` twice on 2026-10-17. The local Paste menu is not a usable remote-canvas paste action. Its mocked command-routing tests did not detect that native behavior.

**0.9.12 replaces that route with an asynchronous session activation refresh. Live remote input with this revision is still unverified.** Automated tests establish sequencing and cancellation, not receipt by a remote app.

## Behavior

| Use Shared Clipboard | Handy action                                                                                                                                    |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| ON                   | Briefly activate Handy, publish fresh text after activation, return to the same Screen Sharing window, wait, then send the paste shortcut once. |
| OFF                  | Publish text, invoke localized **Edit → Send Clipboard**, wait, then send the paste shortcut.                                                   |

The Screen Sharing client inspected on this Mac publishes shared clipboard promises when its session window becomes key. An in-place clipboard write while the same window remains key need not refresh those promises. The new route reproduces copying in a local app and returning to Screen Sharing. It uses public `NSRunningApplication` activation APIs; it does not modify Apple's app, call its private clipboard APIs, toggle the sharing checkbox, or inject mouse movements.

Both **Paste** and **Send Clipboard** can be disabled with sharing ON. Neither menu is used by the new ON path. Ordinary local-to-remote copy/paste remains available because sharing stays ON throughout.

## Transaction and safety

- Capture the original process, window, clipboard mode, and clipboard contents before requesting activation.
- Observe that Handy really became active before publishing text. An accepted activation request alone never authorizes paste.
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

For 0.9.12, 13 Screen Sharing tests, eight clipboard transaction tests, and three key-release tests passed (24 total). They include restoration after preparation/key failures and key release after partial errors or panics. Lint, formatting, and release clippy also passed; clippy reported only the existing model-manager formatting warnings. None of these tests establishes live transfer behavior of Apple's client, network latency, or the remote application's paste handling.

## Live validation still required

Use a 0.9.12 build with Apple's Screen Sharing app connected to another Mac in Control Mode and Handy's **Clipboard (Cmd+V)** method.

1. Keep **Use Shared Clipboard ON** throughout.
2. In an empty remote editor, dictate two different sentences. Each operation should briefly activate Handy and return to the same remote window. Compare both results with History; the second must not repeat the first.
3. Copy different text in a local app and paste into the remote editor normally. Dictate another sentence afterwards.
4. Repeat with Korean using a Korean-capable model and in the target applications and connection types actually used.
5. Switch to another local app, change remote windows, cancel, or copy other text before paste. Verify cancellation and preservation of the newer copy.
6. With sharing OFF, verify the existing Send Clipboard path still works.

If this fails, retain the non-content `[screen-sharing]` phase/error logs, both macOS versions, connection type, target app, and configured delays. Do not log dictated text or remote connection addresses. A log saying the chord was sent is not evidence the remote field received fresh text.
