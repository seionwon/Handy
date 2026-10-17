import assert from "node:assert/strict";
import { createInputInitializer } from "./inputInitialization";

let release: (() => void) | undefined;
const gate = new Promise<void>((resolve) => {
  release = resolve;
});
let enigoCalls = 0;
let shortcutCalls = 0;
const initialize = createInputInitializer({
  initializeEnigo: async () => {
    enigoCalls++;
    await gate;
    return { status: "ok", data: null };
  },
  initializeShortcuts: async () => {
    shortcutCalls++;
    if (shortcutCalls === 1)
      return { status: "error", error: "permission denied" };
    return { status: "ok", data: null };
  },
});

const first = initialize();
assert.equal(initialize(), first, "concurrent calls share initialization");
assert.equal(enigoCalls, 1);
assert.equal(shortcutCalls, 0, "shortcuts wait for input readiness");
release?.();
await assert.rejects(first, /permission denied/);
await initialize();
assert.equal(enigoCalls, 2);
assert.equal(shortcutCalls, 2, "a reported IPC error can be retried");
await initialize();
assert.equal(shortcutCalls, 2, "successful initialization is idempotent");
console.log("input initialization: all assertions passed");
