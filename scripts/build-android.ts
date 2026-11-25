#!/usr/bin/env bun
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { basename, join, resolve, sep } from "node:path";

const root = resolve(import.meta.dir, "..");
const android = join(root, "android");
const outputs = join(android, "app/build/outputs");
const version = JSON.parse(
  readFileSync(join(root, "package.json"), "utf8"),
).version;
const fileName = `Handy-v${version}.apk`;
const fingerprint = readFileSync(
  join(android, "release-signing.sha256"),
  "utf8",
).trim();
if (!/^[0-9a-f]{64}$/.test(fingerprint)) {
  throw new Error("Invalid Handy release certificate fingerprint.");
}
const expected = process.env.HANDY_ANDROID_SIGNER_SHA256;
if (
  expected !== undefined &&
  expected.replace(/:/g, "").toLowerCase() !== fingerprint
) {
  throw new Error(
    "HANDY_ANDROID_SIGNER_SHA256 differs from the Handy release certificate.",
  );
}
const windows = process.platform === "win32";
if (!process.argv.includes("--publish-only")) {
  const signingKeys = [
    "HANDY_ANDROID_KEYSTORE",
    "HANDY_ANDROID_STORE_PASSWORD",
    "HANDY_ANDROID_KEY_ALIAS",
    "HANDY_ANDROID_KEY_PASSWORD",
  ];
  if (signingKeys.some((key) => process.env[key] !== undefined)) {
    for (const key of signingKeys) {
      if (!process.env[key])
        throw new Error(`Missing release signing setting ${key}`);
    }
  }
  const result = spawnSync(
    windows ? "cmd.exe" : "gradle",
    windows
      ? ["/d", "/c", "gradle.bat -p android :app:assembleRelease"]
      : ["-p", "android", ":app:assembleRelease"],
    { cwd: root, stdio: "inherit" },
  );
  if (result.status !== 0) process.exit(result.status ?? 1);
}

const release = join(outputs, "apk/release");
const metadata = JSON.parse(
  readFileSync(join(release, "output-metadata.json"), "utf8"),
);
const item = metadata.elements?.find(
  (it: { outputFile: string }) => basename(it.outputFile) === fileName,
);
if (metadata.variantName !== "release" || !item || item.versionName !== version)
  throw new Error("Missing matching release APK");
const listed = resolve(release, item.outputFile);
if (listed !== join(root, fileName) && listed !== join(release, fileName))
  throw new Error("Unexpected release APK location");
const inputRecord = join(release, "package-input.txt");
let source = listed;
if (existsSync(inputRecord)) {
  source = resolve(release, readFileSync(inputRecord, "utf8").trim());
  if (
    !source.startsWith(join(android, "app/build") + sep) ||
    basename(source) !== fileName + ".part"
  )
    throw new Error("Unexpected release package input");
}
const sdk =
  process.env.ANDROID_HOME ??
  process.env.ANDROID_SDK_ROOT ??
  "/opt/homebrew/share/android-commandlinetools";
const tools = join(sdk, "build-tools");
const dir = readdirSync(tools)
  .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
  .find((v) => existsSync(join(tools, v, windows ? "aapt.exe" : "aapt")));
if (!dir) throw new Error("Android SDK build-tools are required");
function inspect(command: string, args: string[]) {
  const sdkBin = join(tools, dir!);
  const jarSigner = windows && command === "apksigner";
  const binary = jarSigner
    ? "java"
    : join(sdkBin, windows ? command + ".exe" : command);
  const toolArgs = jarSigner
    ? ["-jar", join(sdkBin, "lib", "apksigner.jar"), ...args]
    : args;
  const result = spawnSync(binary, toolArgs, {
    encoding: "utf8",
  });
  if (result.status !== 0)
    throw new Error(`${command} could not verify the release APK`);
  return result.stdout;
}

// Verify the same staged bytes that will become visible to the download hub.
mkdirSync(outputs, { recursive: true });
const stage = mkdtempSync(join(outputs, ".handy-release-"));
try {
  const apk = join(stage, "package.part");
  copyFileSync(source, apk);
  const signature = inspect("apksigner", [
    "verify",
    "--verbose",
    "--print-certs",
    apk,
  ]);
  const signers = [
    ...signature.matchAll(
      /^Signer #\d+ certificate SHA-256 digest:\s*([\da-f]+)$/gim,
    ),
  ];
  if (
    signers.length !== 1 ||
    signers[0][1].toLowerCase() !== fingerprint ||
    /certificate DN:.*\bCN=Android Debug\b/i.test(signature)
  )
    throw new Error("Unexpected APK release signer");
  const badging = inspect("aapt", ["dump", "badging", apk]);
  const pkg =
    /^package: name='com\.pais\.handy' versionCode='(\d+)' versionName='([^']+)'/m.exec(
      badging,
    );
  if (
    !pkg ||
    pkg[2] !== version ||
    Number(pkg[1]) !== item.versionCode ||
    /^application-debuggable/m.test(badging)
  )
    throw new Error("APK is debuggable or has unexpected package metadata");
  const sha256 = createHash("sha256").update(readFileSync(apk)).digest("hex");
  const marker = join(stage, "verified-release.json");
  writeFileSync(
    marker,
    JSON.stringify({
      version,
      versionCode: item.versionCode,
      fileName,
      sha256,
    }),
  );
  // Keep verified web downloads available across local builds without listing a second APK.
  const verified = join(outputs, "verified-apks");
  mkdirSync(verified, { recursive: true });
  renameSync(apk, join(verified, sha256 + ".bin"));
  renameSync(marker, join(outputs, "verified-release.json"));
  console.log(`Verified Android release ${fileName} (${sha256})`);
} finally {
  rmSync(stage, { recursive: true, force: true });
}
