#!/usr/bin/env bun
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
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
const gradleOutputFile = basename(apkPath);

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
const fileName = `Handy-v${pkg.version}-android.apk`;

const meta = {
  version: pkg.version,
  versionCode,
  packageId: "com.pais.handy",
  fileName,
  downloadUrl: `/downloads/${fileName}`,
  relativeDownloadUrl: `downloads/${fileName}`,
  latestAliasUrl: "/downloads/Handy-Android.apk",
  gradleOutputFile,
  sizeBytes,
  sha256,
  minSdk: 26,
  targetSdk: 36,
  abis: ["arm64-v8a", "x86_64"],
  builtAt: new Date().toISOString(),
};

// Keep published APKs outside `public/` so `tauri build` never bundles a 25MB
// Android APK into the macOS/Windows/Linux desktop app.
const legacyPublicDownloads = join(root, "public", "downloads");
if (existsSync(legacyPublicDownloads)) {
  rmSync(legacyPublicDownloads, { recursive: true, force: true });
}

const publishDirs = [join(androidDir, "downloads")];
if (existsSync(join(root, "dist"))) {
  publishDirs.push(join(root, "dist", "downloads"));
}

for (const dir of publishDirs) {
  mkdirSync(dir, { recursive: true });
  for (const existing of readdirSync(dir)) {
    if (existing.endsWith(".apk") && existing !== fileName) {
      rmSync(join(dir, existing), { force: true });
    }
  }
  copyFileSync(apkPath, join(dir, fileName));
  writeFileSync(
    join(dir, "android-apk-meta.json"),
    JSON.stringify(meta, null, 2) + "\n",
    "utf8",
  );
}

console.log(
  `Published ${fileName} (v${pkg.version}, ${(sizeBytes / 1048576).toFixed(2)} MB, sha256=${sha256.slice(0, 12)}…)`,
);
console.log(`  Gradle output: ${apkPath}`);
console.log(`  Download URL : /downloads/${fileName}`);
