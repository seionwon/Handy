import assert from "node:assert/strict";
import { generateQrMatrix } from "./qrMatrix";

const v1 = generateQrMatrix("https://handy.app");
assert.equal(v1.length, 21, "short URL fits Version 1 (21x21)");
assert.equal(v1[0].length, 21);
// Top-left finder corners must be dark
assert.equal(v1[0][0], true);
assert.equal(v1[0][6], true);
assert.equal(v1[6][0], true);
assert.equal(v1[3][3], true);
// Dark module at (size - 8, 8)
assert.equal(v1[13][8], true);

const longUrl =
  "https://example-lan-proxy.local:8443/downloads/Handy-Android.apk?token=1234567890abcdef";
const vLarger = generateQrMatrix(longUrl);
assert.ok(vLarger.length >= 33 && vLarger.length <= 57);
assert.equal(vLarger.length, vLarger[0].length);
assert.equal(vLarger[vLarger.length - 8][8], true);

console.log("qrMatrix: all assertions passed");
