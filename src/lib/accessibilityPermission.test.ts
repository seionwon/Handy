import assert from "node:assert/strict";
import { reuseOrOpenAccessibility } from "./accessibilityPermission";

let opened = 0;
let granted = true;
const actions = {
  check: async () => granted,
  openSettings: async () => {
    opened++;
    return { status: "ok" as const, data: null };
  },
};
assert.equal(await reuseOrOpenAccessibility(actions), true);
assert.equal(opened, 0, "an existing grant must not reopen System Settings");

granted = false;
assert.equal(await reuseOrOpenAccessibility(actions), false);
assert.equal(opened, 1, "a denied grant opens just one settings pane");

assert.equal(
  await reuseOrOpenAccessibility({
    ...actions,
    openSettings: async () => {
      granted = true;
      return { status: "ok", data: null };
    },
  }),
  true,
  "a grant changed during the request is reused immediately",
);
await assert.rejects(
  reuseOrOpenAccessibility({
    ...actions,
    check: async () => {
      throw new Error("permission query failed");
    },
  }),
  /permission query failed/,
);
assert.equal(opened, 1, "a query failure is not a permission request");

granted = false;
await assert.rejects(
  reuseOrOpenAccessibility({
    ...actions,
    openSettings: async () => ({ status: "error", error: "settings failed" }),
  }),
  /settings failed/,
);
granted = true;
assert.equal(await reuseOrOpenAccessibility(actions), true);
assert.equal(opened, 1, "retry with a valid grant requires no request");
console.log("accessibility permission reuse: all assertions passed");
