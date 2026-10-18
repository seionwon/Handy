// Local macOS integration check. Signs throwaway code; never executes it.
import assert from "node:assert/strict";
import {
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { prepareMacosBuildOutput } from "./macos-build-layout";
import { signWithLocalIdentity } from "./macos-local-signing";

if (process.platform !== "darwin")
  throw new Error("This signing integration test requires macOS.");
const root = resolve(import.meta.dir, "..");
await prepareMacosBuildOutput(root);
const temporary = await mkdtemp(join(tmpdir(), "handy-signing-test.noindex-"));
const directory = join(temporary, "identity");
const originalSdkRoot = process.env.SDKROOT;
const searchList = () =>
  Bun.spawnSync([
    "/usr/bin/security",
    "list-keychains",
    "-d",
    "user",
  ]).stdout.toString();
const originalSearchList = searchList();
try {
  // An inherited SDK from another toolchain must not override the selected SDK.
  process.env.SDKROOT = join(temporary, "stale-macos.sdk");
  const requirements: string[] = [];
  for (const version of [1, 2, 3]) {
    const app = join(temporary, `version${version}`, "Handy.app");
    await mkdir(join(app, "Contents/MacOS"), { recursive: true });
    await writeFile(
      join(app, "Contents/Info.plist"),
      `<?xml version="1.0"?><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>com.pais.handy</string><key>CFBundleExecutable</key><string>handy</string><key>CFBundlePackageType</key><string>APPL</string></dict></plist>`,
    );
    await writeFile(
      join(temporary, "fixture.c"),
      `int main(void) { return ${version}; }`,
    );
    const compile = Bun.spawnSync([
      "/usr/bin/xcrun",
      "--sdk",
      "macosx",
      "clang",
      join(temporary, "fixture.c"),
      "-o",
      join(app, "Contents/MacOS/handy"),
    ]);
    assert.equal(compile.exitCode, 0, compile.stderr.toString());
    if (version < 3) {
      requirements.push(await signWithLocalIdentity(root, app, directory));
    } else {
      const sign = Bun.spawnSync([
        "/usr/bin/codesign",
        "--force",
        "--sign",
        "-",
        "--identifier",
        "com.pais.handy",
        app,
      ]);
      assert.equal(sign.exitCode, 0);
      const expression = requirements[0].replace(/^designated => /, "");
      const check = Bun.spawnSync([
        "/usr/bin/codesign",
        "--verify",
        "--strict",
        `-R=${expression}`,
        app,
      ]);
      assert.notEqual(
        check.exitCode,
        0,
        "another signer with the same bundle ID must not satisfy the approved identity",
      );
    }
  }
  assert.equal(
    requirements[0],
    requirements[1],
    "changed app code must retain the same certificate-bound requirement",
  );
  assert.match(requirements[0], /certificate root = H"[0-9a-f]{40}"/);
  assert.equal((await lstat(directory)).mode & 0o777, 0o700);
  assert.equal(
    (await lstat(join(directory, "keychain-password"))).mode & 0o777,
    0o600,
  );
  const partial = join(temporary, "incomplete-identity");
  await mkdir(partial);
  await writeFile(join(partial, "identity.json"), "keep this identity");
  await assert.rejects(
    signWithLocalIdentity(root, join(temporary, "version1/Handy.app"), partial),
    /Incomplete local signing identity/,
  );
  assert.equal(
    await readFile(join(partial, "identity.json"), "utf8"),
    "keep this identity",
  );
  assert.equal(
    searchList(),
    originalSearchList,
    "user keychain search list must remain unchanged",
  );
  console.log(
    "Signing integration passed: explicit macOS SDK, update identity, signer rejection, private file modes, partial identity preservation, unchanged keychain search list.",
  );
} finally {
  if (originalSdkRoot === undefined) delete process.env.SDKROOT;
  else process.env.SDKROOT = originalSdkRoot;
  await rm(temporary, { recursive: true, force: true });
}
