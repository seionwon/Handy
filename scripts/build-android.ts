#!/usr/bin/env bun
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dir, "..");
const androidDir = join(root, "android");
const apkPath = join(
  androidDir,
  "app",
  "build",
  "outputs",
  "apk",
  "debug",
  "app-debug.apk",
);
const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf8")) as {
  version: string;
};

const skipGradle = process.argv.includes("--publish-only");

if (!skipGradle) {
  const gradle = spawnSync("gradle", ["-p", androidDir, "assembleDebug"], {
    stdio: "inherit",
    env: process.env,
  });
  if (gradle.status !== 0) {
    process.exit(gradle.status ?? 1);
  }
}

if (!existsSync(apkPath)) {
  console.error(`Built APK not found at ${apkPath}`);
  process.exit(1);
}

// Verify APK signature with apksigner when Android build-tools are present.
const sdkRoot =
  process.env.ANDROID_HOME ??
  process.env.ANDROID_SDK_ROOT ??
  "/opt/homebrew/share/android-commandlinetools";
const buildToolsDir = join(sdkRoot, "build-tools");
if (existsSync(buildToolsDir)) {
  const versions = readdirSync(buildToolsDir).sort().reverse();
  const apksigner = versions
    .map((v) => join(buildToolsDir, v, "apksigner"))
    .find((p) => existsSync(p));
  if (apksigner) {
    const verify = spawnSync(apksigner, ["verify", "--verbose", apkPath], {
      stdio: "inherit",
    });
    if (verify.status !== 0) {
      console.error("APK signature verification failed");
      process.exit(verify.status ?? 1);
    }
  }
}

const bytes = readFileSync(apkPath);
const sha256 = createHash("sha256").update(bytes).digest("hex");
const sizeBytes = statSync(apkPath).size;

const meta = {
  version: pkg.version,
  packageId: "com.pais.handy",
  fileName: `Handy-${pkg.version}-android.apk`,
  downloadUrl: "/downloads/Handy-Android.apk",
  sizeBytes,
  sha256,
  minSdk: 26,
  targetSdk: 36,
  abis: ["arm64-v8a", "x86_64"],
  builtAt: new Date().toISOString(),
};

const publishDirs = [join(root, "public", "downloads")];
if (existsSync(join(root, "dist"))) {
  publishDirs.push(join(root, "dist", "downloads"));
}

for (const dir of publishDirs) {
  mkdirSync(dir, { recursive: true });
  copyFileSync(apkPath, join(dir, "Handy-Android.apk"));
  writeFileSync(
    join(dir, "android-apk-meta.json"),
    JSON.stringify(meta, null, 2) + "\n",
    "utf8",
  );
}

console.log(
  `Published Android APK (${(sizeBytes / 1048576).toFixed(2)} MB, sha256=${sha256.slice(0, 12)}…)`,
);
