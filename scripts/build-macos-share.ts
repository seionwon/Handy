// Run on macOS: bun scripts/build-macos-share.ts
// To repackage the app from a completed build: add --package-only.
// Add --arm64-only for Apple Silicon; the default builds both Mac architectures.
// Requires Bun, Rust targets, CMake and Xcode/Command Line Tools.
import {
  chmod,
  copyFile,
  mkdir,
  mkdtemp,
  rm,
  readdir,
  writeFile,
} from "node:fs/promises";
import { constants } from "node:fs";
import { join, resolve } from "node:path";
import { prepareMacosBuildOutput } from "./macos-build-layout";
import { signWithLocalIdentity } from "./macos-local-signing";

if (process.platform !== "darwin") {
  throw new Error("The macOS installer must be built on macOS.");
}

const args = process.argv.slice(2);
if (args.some((arg) => !["--package-only", "--arm64-only"].includes(arg))) {
  throw new Error(
    "Usage: bun scripts/build-macos-share.ts [--package-only] [--arm64-only]",
  );
}
const packageOnly = args.includes("--package-only");
const arm64Only = args.includes("--arm64-only");
const target = arm64Only ? "aarch64-apple-darwin" : "universal-apple-darwin";

const root = resolve(import.meta.dir, "..");
await prepareMacosBuildOutput(root);
const config = await Bun.file(join(root, "src-tauri/tauri.conf.json")).json();
const shareConfig = await Bun.file(
  join(root, "src-tauri/tauri.macos-share.conf.json"),
).json();
const signingIdentity = process.env.HANDY_MACOS_SIGNING_IDENTITY?.trim();
if (signingIdentity) {
  if (signingIdentity === "-") {
    throw new Error(
      "Ad-hoc signing changes Handy's privacy identity on every rebuild. Select a certificate or use the persistent local signing default.",
    );
  }
  shareConfig.bundle.macOS.signingIdentity = signingIdentity;
}
const output = join(root, "src-tauri/target/share");
const filename = `Handy-ScreenSharing-${config.version}-${arm64Only ? "arm64" : "universal"}.pkg`;
const destination = join(output, filename);

// Keep clang, Swift, CMake and Rust on the same SDK, including on machines
// where Command Line Tools and the selected Xcode have different versions.
const sdkLookup = Bun.spawnSync([
  "xcrun",
  "--sdk",
  "macosx",
  "--show-sdk-path",
]);
if (sdkLookup.exitCode !== 0) {
  throw new Error("Cannot locate the selected macOS SDK with xcrun.");
}
const sdk = sdkLookup.stdout.toString().trim();

// Never silently overwrite a previously shared installer/checksum.
if (
  (await Bun.file(destination).exists()) ||
  (await Bun.file(`${destination}.sha256`).exists())
) {
  throw new Error(
    `Installer already exists; move it aside first: ${destination}`,
  );
}

async function run(command: string[], cwd = root) {
  console.log(`> ${command.join(" ")}`);
  const child = Bun.spawn(command, {
    cwd,
    env: {
      ...process.env,
      SDKROOT: sdk,
      MACOSX_DEPLOYMENT_TARGET: "11.0",
      // Do not pick up unrelated Homebrew library search paths from the shell.
      LDFLAGS: "",
      // Keep the CPU path portable; do not use the build machine's extensions.
      TRANSCRIBE_CMAKE_ARGS: `-DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DCMAKE_OSX_SYSROOT=${sdk}`,
    },
    stdin: "inherit",
    stdout: "inherit",
    stderr: "inherit",
  });
  const status = await child.exited;
  if (status !== 0) {
    throw new Error(`Command exited with ${status}: ${command[0]}`);
  }
}

if (!packageOnly) {
  await run([
    "bun",
    "run",
    "tauri",
    "build",
    "--target",
    target,
    "--bundles",
    "app",
    "--features",
    "disable-updater",
    "--config",
    JSON.stringify(shareConfig),
    "--ci",
    "--",
    "--locked",
  ]);
}

const app = join(
  root,
  `src-tauri/target/${target}/release/bundle/macos/Handy.app`,
);
await run([
  "lipo",
  join(app, "Contents/MacOS/handy"),
  "-verify_arch",
  ...(arm64Only ? ["arm64"] : ["arm64", "x86_64"]),
]);
// Include the current install guide even when --package-only reuses a build
// made without tauri.macos-share.conf.json. Sign only after adding resources.
await copyFile(
  join(root, "packaging/macos/INSTALL-KO.txt"),
  join(app, "Contents/Resources/INSTALL-KO.txt"),
);
await copyFile(
  join(root, "LICENSE"),
  join(app, "Contents/Resources/LICENSE.txt"),
);
if (!signingIdentity) await signWithLocalIdentity(root, app);
else {
  await run([
    "/usr/bin/codesign",
    "--force",
    "--sign",
    signingIdentity,
    "--options",
    "runtime",
    "--entitlements",
    join(root, "src-tauri/Entitlements.plist"),
    app,
  ]);
}
await run(["codesign", "--verify", "--deep", "--strict", app]);
const finalRequirement = Bun.spawnSync(["/usr/bin/codesign", "-d", "-r-", app]);
const requirementText =
  finalRequirement.stdout.toString() + finalRequirement.stderr.toString();
if (
  finalRequirement.exitCode !== 0 ||
  !requirementText.includes("certificate") ||
  !requirementText.includes("com.pais.handy")
) {
  throw new Error(
    "Refusing to distribute Handy without a certificate-bound signing identity.",
  );
}

await mkdir(output, { recursive: true });
const stagingRoot = join(output, ".staging.noindex");
await mkdir(stagingRoot, { recursive: true });
const staging = await mkdtemp(join(stagingRoot, "Handy-"));
const payloadApp = join(staging, "payload/Applications/Handy.app");
const expanded = join(staging, "expanded");
const expandedApp = join(expanded, "Handy.pkg/Payload/Applications/Handy.app");
try {
  await chmod(staging, 0o755);
  await mkdir(join(staging, "payload/Applications"), { recursive: true });
  await run(["ditto", app, payloadApp]);
  await run(["codesign", "--verify", "--deep", "--strict", payloadApp]);
  const resources = join(staging, "Resources");
  await mkdir(resources);
  await copyFile(join(root, "LICENSE"), join(resources, "LICENSE.txt"));
  await copyFile(
    join(root, "packaging/macos/INSTALL-KO.txt"),
    join(resources, "INSTALL-KO.txt"),
  );
  const distribution = await Bun.file(
    join(root, "packaging/macos/Distribution.xml"),
  ).text();
  if (!/^\d+\.\d+\.\d+$/.test(config.version)) {
    throw new Error("The macOS installer requires a numeric release version.");
  }
  const distributionPath = join(staging, "Distribution.xml");
  await Bun.write(
    distributionPath,
    distribution
      .replaceAll("@VERSION@", config.version)
      .replaceAll("@ARCHITECTURES@", arm64Only ? "arm64" : "arm64,x86_64"),
  );
  // Native Installer closes Handy before payload replacement. Keep the bundle
  // fixed to /Applications, atomically replace it, and allow same-version
  // reinstalls. No privileged shell scripts or post-install app launch.
  await run([
    "/usr/bin/pkgbuild",
    "--root",
    join(staging, "payload"),
    "--component-plist",
    join(root, "packaging/macos/components.plist"),
    "--identifier",
    "com.pais.handy.pkg",
    "--version",
    config.version,
    "--install-location",
    "/",
    "--ownership",
    "recommended",
    "--compression",
    "legacy",
    join(staging, "Handy.pkg"),
  ]);
  const archive = join(staging, filename);
  const installerIdentity = process.env.HANDY_MACOS_INSTALLER_IDENTITY?.trim();
  if (installerIdentity === "-") {
    throw new Error("An installer signing identity must be a certificate.");
  }
  await run([
    "/usr/bin/productbuild",
    "--distribution",
    distributionPath,
    "--resources",
    resources,
    "--package-path",
    staging,
    ...(installerIdentity ? ["--sign", installerIdentity] : []),
    archive,
  ]);
  await run(["/usr/sbin/pkgutil", "--expand-full", archive, expanded]);
  await run([
    "/usr/bin/codesign",
    "--verify",
    "--deep",
    "--strict",
    expandedApp,
  ]);
  const payload = join(expanded, "Handy.pkg/Payload");
  if (
    (await readdir(payload)).join() !== "Applications" ||
    (await readdir(join(payload, "Applications"))).join() !== "Handy.app"
  ) {
    throw new Error(
      "The installer payload must contain only /Applications/Handy.app.",
    );
  }
  await run([
    "bun",
    join(root, "scripts/verify-macos-package.ts"),
    expanded,
    config.version,
    arm64Only ? "arm64" : "arm64,x86_64",
  ]);
  // Have Installer parse the finished product without installing or quitting
  // anything. This also catches unsupported distribution metadata.
  const choices = Bun.spawnSync([
    "/usr/sbin/installer",
    "-pkg",
    archive,
    "-target",
    "/",
    "-showChoicesXML",
  ]);
  if (choices.exitCode !== 0) {
    throw new Error(
      `Installer could not read the archive: ${choices.stderr.toString().trim()}`,
    );
  }
  const extractedRequirement = Bun.spawnSync([
    "/usr/bin/codesign",
    "-d",
    "-r-",
    expandedApp,
  ]);
  const designatedRequirement = (output: string) =>
    output
      .split("\n")
      .find((line) => line.includes("designated =>"))
      ?.replace(/^#\s*/, "")
      .trim();
  if (
    extractedRequirement.exitCode !== 0 ||
    designatedRequirement(
      extractedRequirement.stdout.toString() +
        extractedRequirement.stderr.toString(),
    ) !== designatedRequirement(requirementText)
  ) {
    throw new Error(
      "The archived app's privacy identity changed during packaging.",
    );
  }

  const digest = new Bun.CryptoHasher("sha256")
    .update(await Bun.file(archive).arrayBuffer())
    .digest("hex");
  // Publish only a verified package; never overwrite a previous release.
  await copyFile(archive, destination, constants.COPYFILE_EXCL);
  await writeFile(`${destination}.sha256`, `${digest}  ${filename}\n`, {
    flag: "wx",
  });
  await copyFile(
    join(root, "packaging/macos/INSTALL-KO.txt"),
    join(output, "INSTALL-KO.txt"),
  );
  // The PKG has no mounted app to appear in search. Remove build/staging copies.
  const unregister = Bun.spawnSync([
    "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister",
    "-u",
    app,
  ]);
  if (unregister.exitCode !== 0)
    console.warn(
      "Could not unregister the generated Handy app before removing its build copy.",
    );
  await rm(app, { recursive: true });
  console.log(`\nShare this installer: ${destination}\nSHA-256: ${digest}`);
} finally {
  for (const temporaryApp of [payloadApp, expandedApp]) {
    Bun.spawnSync([
      "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister",
      "-u",
      temporaryApp,
    ]);
  }
  await rm(staging, { recursive: true, force: true });
}
