Local patch of handy-keys 0.3.4 (MIT; see LICENSE).

On macOS, observation uses a ListenOnly event tap. Handy intentionally uses
the non-blocking manager, so it cannot suppress keyboard or mouse input.
Callbacks never wait for a state lock, respect user-requested tap disabling,
and keep Fn state across events. Self-generated paste/submit events cannot
trigger shortcuts. Tap creation has a deadline and cleans up if
the caller has already abandoned initialization. No tap-status polling is added.

Permission-dependent unit tests are opt-in. The remaining library unit tests
do not install event taps or inject input. Run with:

    cargo test --manifest-path vendor/handy-keys/Cargo.toml --lib

Keep this patch until upstream provides the same guarantees.
