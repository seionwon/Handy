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
  rmdir,
  symlink,
} from "node:fs/promises";
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
const filename = `Handy-ScreenSharing-${config.version}-${arm64Only ? "arm64" : "universal"}.dmg`;
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
if (!signingIdentity) await signWithLocalIdentity(root, app);
else if (packageOnly) {
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
try {
  // mkdtemp defaults to 0700; the image root must be readable by other users too.
  await chmod(staging, 0o755);
  await run(["ditto", app, join(staging, "Handy.app")]);
  await run([
    "codesign",
    "--verify",
    "--deep",
    "--strict",
    join(staging, "Handy.app"),
  ]);
  await symlink("/Applications", join(staging, "Applications"));
  await copyFile(join(root, "LICENSE"), join(staging, "LICENSE.txt"));
  await copyFile(
    join(root, "packaging/macos/INSTALL-KO.txt"),
    join(staging, "INSTALL-KO.txt"),
  );
  // The installer volume is temporary; index the installed Applications copy.
  await Bun.write(join(staging, ".metadata_never_index"), "");
  await Bun.write(
    join(staging, ".handy-installer.json"),
    JSON.stringify({ identifier: "com.pais.handy", installer_format: 1 }),
  );
  await run([
    "hdiutil",
    "create",
    "-srcfolder",
    staging,
    "-volname",
    "Handy Screen Sharing",
    "-fs",
    "APFS",
    "-format",
    "UDZO",
    "-o",
    destination,
  ]);
  await run(["hdiutil", "verify", destination]);

  // Verify the actual mounted filesystem and the copied app, not just the
  // staging signature or image checksum. HFS makehybrid added FinderInfo to
  // every path, which invalidated an otherwise correctly signed app.
  const verificationMount = await mkdtemp(join(stagingRoot, "verify-volume-"));
  const verificationCopy = await mkdtemp(join(stagingRoot, "verify-copy-"));
  let attached = false;
  try {
    await run([
      "hdiutil",
      "attach",
      "-readonly",
      "-nobrowse",
      "-mountpoint",
      verificationMount,
      destination,
    ]);
    attached = true;
    await run([
      "codesign",
      "--verify",
      "--deep",
      "--strict",
      join(verificationMount, "Handy.app"),
    ]);
    await run([
      "ditto",
      join(verificationMount, "Handy.app"),
      join(verificationCopy, "Handy.app"),
    ]);
    await run([
      "codesign",
      "--verify",
      "--deep",
      "--strict",
      join(verificationCopy, "Handy.app"),
    ]);
  } finally {
    for (const directory of [verificationCopy, verificationMount]) {
      Bun.spawnSync([
        "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister",
        "-u",
        join(directory, "Handy.app"),
      ]);
    }
    if (attached) await run(["hdiutil", "detach", verificationMount]);
    await rm(verificationCopy, { recursive: true, force: true });
    // Never recursively delete a mount point if image detachment failed.
    await rmdir(verificationMount);
  }

  const digest = new Bun.CryptoHasher("sha256")
    .update(await Bun.file(destination).arrayBuffer())
    .digest("hex");
  await Bun.write(`${destination}.sha256`, `${digest}  ${filename}\n`);
  await copyFile(
    join(root, "packaging/macos/INSTALL-KO.txt"),
    join(output, "INSTALL-KO.txt"),
  );
  // Keep the verified installer as the distributable artifact. A second app
  // bundle in Cargo output can be registered in app search even in .noindex.
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
  Bun.spawnSync([
    "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister",
    "-u",
    join(staging, "Handy.app"),
  ]);
  await rm(staging, { recursive: true, force: true });
}
