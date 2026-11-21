#!/usr/bin/env bun
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  copyFileSync,
  existsSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
} from "node:fs";
import { basename, join, resolve } from "node:path";

const root = resolve(import.meta.dir, "..");
const androidDir = join(root, "android");
const debugApkDir = join(androidDir, "app", "build", "outputs", "apk", "debug");
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

function resolveBuiltApk(): { apkPath: string; versionCode: number } {
  const metadataPath = join(debugApkDir, "output-metadata.json");
  if (existsSync(metadataPath)) {
    const metadata = JSON.parse(readFileSync(metadataPath, "utf8")) as {
      elements?: Array<{ outputFile?: string; versionCode?: number }>;
    };
    const first = metadata.elements?.[0];
    if (first?.outputFile) {
      const candidate = join(debugApkDir, first.outputFile);
      if (existsSync(candidate)) {
        return {
          apkPath: candidate,
          versionCode: first.versionCode ?? 1001,
        };
      }
    }
  }

  if (existsSync(debugApkDir)) {
    const apks = readdirSync(debugApkDir)
      .filter((f) => f.endsWith(".apk"))
      .map((f) => join(debugApkDir, f))
      .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs);
    if (apks.length > 0) {
      return { apkPath: apks[0], versionCode: 1001 };
    }
  }

  console.error(`Built APK not found in ${debugApkDir}`);
  process.exit(1);
}

const { apkPath, versionCode } = resolveBuiltApk();
const fileName = `Handy-v${pkg.version}.apk`;
const rootApkPath = join(root, fileName);

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

// Match FocusTempo's exact layout: keep only the root APK and the Gradle debug
// output APK so CodeMemo's Run tab surfaces clean, non-duplicated versioned links.
for (const legacyDir of [
  join(root, "public", "downloads"),
  join(androidDir, "downloads"),
  join(root, "dist", "downloads"),
]) {
  if (existsSync(legacyDir)) {
    rmSync(legacyDir, { recursive: true, force: true });
  }
}

for (const existing of readdirSync(root)) {
  if (
    existing.startsWith("Handy-") &&
    existing.endsWith(".apk") &&
    existing !== fileName
  ) {
    rmSync(join(root, existing), { force: true });
  }
}

copyFileSync(apkPath, rootApkPath);

const bytes = readFileSync(rootApkPath);
const sha256 = createHash("sha256").update(bytes).digest("hex");
const sizeBytes = statSync(rootApkPath).size;

console.log(
  `Published ${fileName} (v${pkg.version}, versionCode=${versionCode}, ${(sizeBytes / 1048576).toFixed(2)} MB, sha256=${sha256.slice(0, 12)}…)`,
);
console.log(`  Project root : ${rootApkPath}`);
console.log(`  Gradle output: ${apkPath} (${basename(apkPath)})`);
console.log(`  Download URL : /downloads/${fileName}`);
