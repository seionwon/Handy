import assert from "node:assert/strict";
import { checkStartup } from "./startup";

let returning = true;
let accessibility = true;
let microphone = true;
let retired = false;
let permissionChecks = 0;
const options = {
  platform: "macos",
  commands: {
    getAppSettings: async () => ({
      status: "ok" as const,
      data: { onboarding_completed: returning, selected_model: "test-model" },
    }),
    getRetiredModels: async () => (retired ? [{ id: "test-model" }] : []),
    getWindowsMicrophonePermissionStatus: async () => ({
      supported: true,
      overall_access: microphone ? "allowed" : "denied",
    }),
  },
  checkAccessibility: async () => {
    permissionChecks++;
    return accessibility;
  },
  checkMicrophone: async () => microphone,
};

assert.equal((await checkStartup(options)).step, "done");
returning = false;
assert.equal(
  (await checkStartup(options)).step,
  "model",
  "new settings with existing grants skip permission onboarding",
);
for (const hasCompletedOnboarding of [true, false]) {
  returning = hasCompletedOnboarding;
  accessibility = false;
  assert.equal((await checkStartup(options)).step, "accessibility");
  accessibility = true;
  microphone = false;
  assert.equal((await checkStartup(options)).step, "accessibility");
  microphone = true;
}
returning = true;
retired = true;
assert.equal((await checkStartup(options)).step, "migration");
assert.equal((await checkStartup(options)).migrationModelId, "test-model");
accessibility = false;
assert.equal(
  (await checkStartup(options)).step,
  "accessibility",
  "a retired model does not bypass a denied grant",
);
const failedCheck = await checkStartup({
  ...options,
  checkAccessibility: async () => {
    throw new Error("query unavailable");
  },
});
assert.equal(
  failedCheck.step,
  "accessibility",
  "query errors must not allow input initialization",
);
assert.equal(
  failedCheck.isReturningUser,
  true,
  "a query error must not restart model onboarding",
);

retired = false;
const previousChecks = permissionChecks;
assert.equal(
  (await checkStartup({ ...options, platform: "linux" })).step,
  "done",
);
microphone = false;
assert.equal(
  (await checkStartup({ ...options, platform: "windows" })).step,
  "accessibility",
);
assert.equal(permissionChecks, previousChecks, "macOS queries stay macOS-only");
console.log("startup permission routing: all assertions passed");
