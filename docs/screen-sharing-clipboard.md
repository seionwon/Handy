# Screen Sharing clipboard modes

This revision adds an ON-preserving paste route. **Live Mac-to-Mac validation is pending.** The automated tests verify command selection, cancellation, and the absence of duplicate fallback actions; they do not establish that a remote application received fresh clipboard data.

## Behavior

| Use Shared Clipboard | Handy action after writing eager clipboard data                                                                                 |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| ON                   | Invoke Screen Sharing's localized **Edit → Paste** action once through Accessibility. Do not send an additional paste shortcut. |
| OFF                  | Invoke localized **Edit → Send Clipboard**, wait, then send the paste shortcut.                                                 |

The ON route addresses the possibility that forwarding a key event alone uses the client's previous remote clipboard. The client owns the native Paste action, so actual transfer behavior must be checked against the macOS versions and connection types in use.

Handy only reads the sharing checkbox. Its command interface exposes **Send Clipboard** and **Paste**; it cannot toggle sharing or activate another app. The same foreground process, focused window, and clipboard mode are checked again before acting. A changed local clipboard cancels the paste, and newer user copies are not overwritten during restoration.

Handy retains the minimum 500 ms pre-paste and 1,500 ms post-paste waits, preserving longer configured delays. These are timing allowances, not remote receipts. Reliable Paste remains bypassed for Screen Sharing.

A native menu action can time out after starting. Apple documents this ambiguity for [AXUIElementPerformAction](https://developer.apple.com/documentation/applicationservices/1462091-axuielementperformaction). Handy propagates the error without retrying or falling back to a synthetic shortcut. Inspect the remote text before retrying manually.

## Automated coverage

The `screen_sharing::tests` tests exercise both clipboard modes with a mock target, without requesting permissions, installing event taps, manipulating the clipboard, or sending real input:

- Shared-mode pastes never call Send Clipboard or the synthetic-key path.
- Two successive shared-mode operations each request their own native paste.
- A failed or timed-out native paste is attempted once and has no key fallback.
- Manual-mode transfers precede the paste shortcut; failed preparation stops the transaction.
- Losing focus or changing clipboard mode aborts the operation.
- An unfocused target is rejected before clipboard modification.
- Network timing minima preserve larger user settings.

The surrounding clipboard transaction tests check restoration when preparation or paste fails. These mocks cannot establish the native menu's remote clipboard semantics.

## Manual validation

Use Apple's Screen Sharing app on the local Mac, connect to another Mac in Control Mode, and select Handy's **Clipboard (Cmd+V)** paste method. Install a new build from this revision; previously generated DMGs retain the older OFF requirement.

1. Keep **Use Shared Clipboard ON** and confirm it stays checked throughout every step.
2. In a remote editor, dictate two different sentences without switching away from Screen Sharing. Compare both with History; verify the second does not repeat the first.
3. Copy different text in a local app, return to the remote editor, and paste normally. Dictate a third sentence afterwards.
4. Repeat with Korean using a Korean-capable recognition model, testing both repeated and different sentences.
5. Repeat in the remote applications actually used, and under a slower connection. Increase paste delays if needed.
6. Change the local foreground app or remote session window during the wait. Confirm cancellation and that Handy does not paste into the new target.
7. Switch clipboard mode during the wait. Confirm cancellation and that Handy leaves the new setting unchanged.
8. With sharing OFF, verify the existing Send Clipboard route still works.

If ON mode fails, record both macOS versions, Standard/High Performance connection type, target app, configured delays, and whether manual **Edit → Paste** reads fresh text while Screen Sharing stays frontmost. Redact dictated text in logs. Keep the ON path marked as awaiting live validation until this check succeeds.
