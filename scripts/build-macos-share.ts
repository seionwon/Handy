// Run on macOS: bun scripts/build-macos-share.ts
// To repackage the app from a completed build: add --package-only.
// Requires Bun, Rust (arm64 + x86_64 targets), CMake and Xcode/Command Line Tools.
import { chmod, copyFile, mkdir, mkdtemp, symlink } from "node:fs/promises";
import { join, resolve } from "node:path";

if (process.platform !== "darwin") {
  throw new Error("The macOS installer must be built on macOS.");
}

const args = process.argv.slice(2);
if (args.some((arg) => arg !== "--package-only")) {
  throw new Error("Usage: bun scripts/build-macos-share.ts [--package-only]");
}
const packageOnly = args.includes("--package-only");

const root = resolve(import.meta.dir, "..");
const config = await Bun.file(join(root, "src-tauri/tauri.conf.json")).json();
const output = join(root, "src-tauri/target/share");
const filename = `Handy-ScreenSharing-${config.version}-universal.dmg`;
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
if (await Bun.file(destination).exists()) {
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
    "universal-apple-darwin",
    "--bundles",
    "app",
    "--features",
    "disable-updater",
    "--config",
    "src-tauri/tauri.macos-share.conf.json",
    "--ci",
    "--",
    "--locked",
  ]);
}

const app = join(
  root,
  "src-tauri/target/universal-apple-darwin/release/bundle/macos/Handy.app",
);
await run([
  "lipo",
  join(app, "Contents/MacOS/handy"),
  "-verify_arch",
  "arm64",
  "x86_64",
]);
await run(["codesign", "--verify", "--deep", "--strict", app]);

await mkdir(output, { recursive: true });
// Retain staging under ignored build output for inspection/reproduction.
const staging = await mkdtemp(join(output, ".staging-"));
// mkdtemp defaults to 0700; the image root must be readable by other users too.
await chmod(staging, 0o755);
await run(["ditto", app, join(staging, "Handy.app")]);
await symlink("/Applications", join(staging, "Applications"));
await copyFile(join(root, "LICENSE"), join(staging, "LICENSE.txt"));
await copyFile(
  join(root, "packaging/macos/INSTALL-KO.txt"),
  join(staging, "INSTALL-KO.txt"),
);
await run([
  "hdiutil",
  "create",
  "-volname",
  "Handy Screen Sharing",
  "-srcfolder",
  staging,
  "-format",
  "UDZO",
  "-fs",
  "HFS+",
  destination,
]);
await run(["hdiutil", "verify", destination]);

const digest = new Bun.CryptoHasher("sha256")
  .update(await Bun.file(destination).arrayBuffer())
  .digest("hex");
await Bun.write(`${destination}.sha256`, `${digest}  ${filename}\n`);
await copyFile(
  join(root, "packaging/macos/INSTALL-KO.txt"),
  join(output, "INSTALL-KO.txt"),
);
console.log(`\nShare this installer: ${destination}\nSHA-256: ${digest}`);
