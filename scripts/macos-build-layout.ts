import {
  lstat,
  mkdir,
  realpath,
  readlink,
  readdir,
  rename,
  symlink,
} from "node:fs/promises";
import { dirname, join, resolve, sep } from "node:path";

const registerTool =
  "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister";

async function statIfPresent(path: string) {
  try {
    return await lstat(path);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined;
    throw error;
  }
}

async function unregisterBuildCopies(target: string) {
  if (!(await statIfPresent(target))) return;
  const buildRoot = await realpath(target);
  // Unregister only copies generated in this repository. Never unregister the
  // installed app or rebuild the system-wide Launch Services database.
  const apps = [
    join(target, "release/bundle/macos/Handy.app"),
    ...[
      "aarch64-apple-darwin",
      "x86_64-apple-darwin",
      "universal-apple-darwin",
    ].map((triple) => join(target, triple, "release/bundle/macos/Handy.app")),
  ];
  const share = join(target, "share");
  if (await statIfPresent(share)) {
    for (const entry of await readdir(share, { withFileTypes: true })) {
      if (entry.isDirectory()) apps.push(join(share, entry.name, "Handy.app"));
    }
  }
  for (const app of apps) {
    const entry = await statIfPresent(app);
    if (!entry) continue;
    if (
      !entry.isDirectory() ||
      !(await realpath(app)).startsWith(buildRoot + sep)
    ) {
      console.warn(
        `Skipping build app symlink or path outside build output: ${app}`,
      );
      continue;
    }
    const result = Bun.spawnSync([registerTool, "-u", app]);
    if (result.exitCode !== 0) {
      console.warn(
        `Could not unregister build copy: ${app}\n${result.stderr.toString().trim()}`,
      );
    }
  }
}

/** Keep Cargo's existing paths while excluding its app copies from Spotlight. */
export async function prepareMacosBuildOutput(root: string) {
  if (process.platform !== "darwin") return;
  const target = join(root, "src-tauri/target");
  const excluded = join(root, "src-tauri/target.noindex");
  const current = await statIfPresent(target);
  const existingOutput = await statIfPresent(excluded);
  if (current?.isSymbolicLink()) {
    if (resolve(dirname(target), await readlink(target)) !== excluded) {
      throw new Error(`Unexpected Cargo target symlink: ${target}`);
    }
    if (existingOutput && !existingOutput.isDirectory()) {
      throw new Error(`Cargo output is not a directory: ${excluded}`);
    }
    // Cargo clean or an interrupted build may have removed just one side.
    if (!existingOutput) await mkdir(excluded, { recursive: true });
    return;
  }
  if (existingOutput) {
    if (current || !existingOutput.isDirectory()) {
      throw new Error(
        `Conflicting Cargo output paths; refusing to merge: ${target}, ${excluded}`,
      );
    }
    await symlink("target.noindex", target, "dir");
    return;
  }
  if (current && !current.isDirectory()) {
    throw new Error(`Cargo target is not a directory: ${target}`);
  }
  await unregisterBuildCopies(target);
  if (current) {
    await rename(target, excluded);
  } else {
    await mkdir(excluded, { recursive: true });
  }
  try {
    await symlink("target.noindex", target, "dir");
  } catch (error) {
    if (current) await rename(excluded, target);
    throw error;
  }
}

if (import.meta.main) {
  const args = process.argv.slice(2);
  if (args.some((arg) => arg !== "--clean-search")) {
    throw new Error(
      "Usage: bun scripts/macos-build-layout.ts [--clean-search]",
    );
  }
  if (process.platform === "darwin") {
    const root = resolve(import.meta.dir, "..");
    await prepareMacosBuildOutput(root);
    if (args.includes("--clean-search")) {
      await unregisterBuildCopies(join(root, "src-tauri/target"));
      await unregisterBuildCopies(join(root, "src-tauri/target.noindex"));
    }
    console.log("macOS build apps are stored under src-tauri/target.noindex.");
  }
}
