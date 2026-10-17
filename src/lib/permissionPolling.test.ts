import assert from "node:assert/strict";
import { pollPermissions, type PermissionSnapshot } from "./permissionPolling";

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
let resolveCheck: ((status: PermissionSnapshot) => void) | undefined;
let calls = 0;
let statuses = 0;
let completions = 0;
const stop = pollPermissions({
  check: () => {
    calls++;
    return new Promise((resolve) => {
      resolveCheck = resolve;
    });
  },
  onStatus: () => {
    statuses++;
  },
  onComplete: () => {
    completions++;
  },
  onError: () => assert.fail("unexpected error"),
  intervalMs: 1,
  completionDelayMs: 1,
});
await pause(20);
assert.equal(calls, 1, "a slow check must not accumulate more checks");
stop();
resolveCheck?.({ accessibility: true, microphone: true });
await pause(20);
assert.equal(statuses, 0, "late results after unmount must be ignored");
assert.equal(completions, 0);

const cancelComplete = pollPermissions({
  check: async () => ({ accessibility: true, microphone: true }),
  onStatus: () => {
    statuses++;
  },
  onComplete: () => {
    completions++;
  },
  onError: () => assert.fail("unexpected error"),
  completionDelayMs: 30,
});
await pause(5);
cancelComplete();
await pause(40);
assert.equal(completions, 0, "unmount cancels scheduled completion");

let errors = 0;
const stopErrors = pollPermissions({
  check: async () => {
    throw new Error("permission service unavailable");
  },
  onStatus: () => assert.fail("unexpected status"),
  onComplete: () => assert.fail("unexpected completion"),
  onError: (_, count) => {
    errors = count;
  },
  intervalMs: 1,
});
await pause(30);
assert.equal(errors, 3, "consecutive errors stop polling");
stopErrors();

const stopGranted = pollPermissions({
  check: async () => ({ accessibility: true, microphone: true }),
  onStatus: () => {},
  onComplete: () => {
    completions++;
  },
  onError: () => assert.fail("unexpected error"),
  intervalMs: 1,
  completionDelayMs: 1,
});
await pause(30);
assert.equal(completions, 1, "pre-existing grants complete once");
stopGranted();
console.log("permission polling: all assertions passed");
