# Screen Sharing clipboard modes

The user confirmed fresh remote input with 0.9.14 on 2026-10-18, and reported window flicker plus temporarily unavailable menu-bar/mouse interaction. Versions 0.9.13–0.9.14 refreshed clipboard sharing by activating Handy and then Screen Sharing. That full-app round trip could disturb the session's window/menu/focus state.

**0.9.15 uses a transparent nonactivating panel to refresh only window key focus. Screen Sharing remains the foreground application and Use Shared Clipboard remains ON.** There is no `NSWorkspace` app-open request, application activation, activation-policy change, settings-window reopen, mouse injection or cursor-capture manipulation in this route.

## Behavior

| Use Shared Clipboard | Handy action                                                                                                                                                                                  |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ON                   | Briefly borrow window key focus with an invisible, mouse-transparent panel, publish text, hide the panel to restore the original window's key focus, wait, then send the paste shortcut once. |
| OFF                  | Publish text, invoke localized **Edit → Send Clipboard**, wait, then send the paste shortcut.                                                                                                 |

The Apple client inspected on this Mac refreshes shared clipboard promises when its session window becomes key. An in-place clipboard write while that window remains key can leave stale promises. The key-window round trip preserves that refresh while avoiding a full application activation round trip. Both **Paste** and **Send Clipboard** can be disabled with sharing ON; neither menu is used by the ON route.

The implementation uses public [nonactivating NSPanel behavior](https://developer.apple.com/documentation/appkit/nswindow/stylemask-swift.struct/nonactivatingpanel) and [window collection behaviors](https://developer.apple.com/documentation/appkit/nswindow/collectionbehavior-swift.struct). The panel has zero alpha, ignores mouse events, has no animation or shadow, and is excluded from the window menu and restoration. Its collection behavior supports joining the current Space and full-screen content; on macOS 13+ it also joins other apps' full-screen/Stage Manager sets. It never becomes the main window or changes Handy's Dock/tray policy.

## Transaction and bounds

- Capture the original Screen Sharing process, window, mode and clipboard. Revalidate the window/mode immediately before requesting panel focus.
- Observe that the panel actually has key focus while Screen Sharing remains foreground. A request alone never authorizes publication. Wait at most two seconds per focus phase.
- While the panel owns key focus, check only foreground identity, panel state, clipboard revision and cancellation. Do not perform another process's Accessibility queries in this interval; a stalled query must not prolong borrowed key focus.
- Publish text, then hide the panel. Verify the original focused session window and sharing mode after release, before starting the transfer delay.
- During waits, avoid repeated AX menu/window polling. Validate again immediately before paste and optional auto-submit. A switched app/window, changed clipboard/mode or cancellation prevents paste.
- Keep AppKit objects on the main thread. A worker schedules short checks and never holds those objects. The panel is hidden on every completion/error path and on drop; cleanup never activates Screen Sharing after the user changes apps.
- Wait at least 500 ms after return before paste and 1,500 ms after injection before clipboard restoration, respecting larger user delays. Send one chord, retain the restoration wait on partial input errors, and do not retry automatically.
- Preserve newer user copies. Reject overlapping shared pastes before changing the clipboard; their text remains in History.

Reliable Paste remains bypassed: a local pasteboard read cannot prove remote text receipt. The same-version reinstall PKG from 0.9.14 remains the distribution format.

## Verification

Fourteen Screen Sharing state/routing tests cover observed key focus, timeouts, changed apps, unexpected panel focus during transfer, per-dictation refresh, single injection, larger delays, and OFF-mode cancellation/routing. Clipboard transaction, key-release and process-replacement checks cover the surrounding path. The tests do not post input or change the clipboard.

A separate AppKit probe on this Mac confirmed that a nonactivating panel emits the original local window's resign-key and become-key notifications while its application stays foreground. With the actual connected Screen Sharing window, a fully transparent standard NSPanel under Regular activation policy became key and released it without changing foreground PID, Space, or session-window geometry. These probes did not read clipboard text or send keys/mouse input. The observed session was windowed; full-screen and Stage Manager use still require live validation.

The exact signed 0.9.15 PKG payload also passed the production GUI/AX focus check on 2026-10-18, running under Regular application policy with its existing approval. The panel released key focus after 62 ms and the original window was confirmed after another 56 ms. An independent NSWorkspace observer recorded no other app activation, zero Space changes and unchanged visible session-window bounds during this check. No clipboard data or input was sent. This validates the runtime focus path, separately from an attended Installer upgrade.

The production focus path can be checked with:

```sh
/Applications/Handy.app/Contents/MacOS/handy --check-screen-sharing-focus
```

Handy must already be running, and the existing Screen Sharing session must be foreground with sharing ON. The command forwards to that process and exercises the real panel/AX path, skipping clipboard snapshots/revision reads, publication/restoration, paste keys, microphone use and auto-submit. Success logs `focus check passed` and phase timings without transcript text or connection addresses.

**Native focus verification is not proof of fresh remote text receipt or resolution of the reported menu/mouse symptoms.** Those require actual use of the new version.

## Live checks

Keep sharing ON while dictating two different sentences in the same remote field. Compare with History and verify the second does not repeat the first. Alternate ordinary local-to-remote copy/paste with dictation, including Korean where used. Observe menu-bar edge reveal, mouse movement/clicks and window stability during processing, transfer and restoration. Repeat in the actual windowed/full-screen/Stage Manager configurations and target applications used.

Changing apps/windows, cancelling, or making another copy before paste should cancel or preserve the newer copy. Sharing OFF should retain the existing Send Clipboard behavior. Retain only non-content phase/error logs, OS versions, connection type and configured delays when diagnosing a failure. A log saying the chord was sent is not a remote receipt.
