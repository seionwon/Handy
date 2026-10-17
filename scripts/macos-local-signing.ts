import { createHash, randomBytes } from "node:crypto";
import {
  chmod,
  copyFile,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

interface LocalIdentity {
  certificateSha1: string;
  designatedRequirement?: string;
}

async function run(command: string[], input?: Uint8Array) {
  const child = Bun.spawn(command, {
    stdin: input ? new Response(input) : "ignore",
    stdout: "pipe",
    stderr: "pipe",
  });
  const [status, stdout, stderr] = await Promise.all([
    child.exited,
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
  ]);
  if (status !== 0) {
    // Never log stdin, key material, or a command containing a password.
    throw new Error(`${command[0]} failed (${status}): ${stderr.trim()}`);
  }
  return { stdout, stderr };
}

/** Keep a dedicated certificate across builds. No system trust settings change. */
export async function signWithLocalIdentity(
  root: string,
  app: string,
  signingDirectory?: string,
) {
  const directory =
    signingDirectory ??
    join(homedir(), "Library/Application Support/HandyBuildSigning");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const directoryInfo = await lstat(directory);
  if (
    !directoryInfo.isDirectory() ||
    directoryInfo.uid !== process.getuid?.()
  ) {
    throw new Error(
      "Handy's build signing directory must be owned by the current user and cannot be a symlink.",
    );
  }
  await chmod(directory, 0o700);
  const lock = join(directory, "signing.lock");
  await mkdir(lock, { mode: 0o700 });
  const keychain = join(directory, "HandyBuild.keychain-db");
  const secret = join(directory, "keychain-password");
  const certificate = join(directory, "certificate.pem");
  const metadata = join(directory, "identity.json");
  const helper = join(root, "src-tauri/target/signing-keychain");
  let unlocked = false;
  let fresh = false;
  let stage: string | undefined;
  try {
    await run([
      "/usr/bin/xcrun",
      "clang",
      "-Werror",
      "-Wno-deprecated-declarations",
      "-framework",
      "Security",
      "-framework",
      "CoreFoundation",
      join(root, "scripts/macos-signing-keychain.c"),
      "-o",
      helper,
    ]);
    const files = [keychain, secret, certificate, metadata];
    const exists = await Promise.all(
      files.map(async (path) => {
        try {
          const entry = await lstat(path);
          if (!entry.isFile() || entry.uid !== process.getuid?.()) {
            throw new Error(
              `Signing file must be owned by the current user and cannot be a symlink: ${path}`,
            );
          }
          return true;
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code === "ENOENT") return false;
          throw error;
        }
      }),
    );
    if (exists.some(Boolean) && !exists.every(Boolean)) {
      throw new Error(
        `Incomplete local signing identity at ${directory}. Restore its files; a replacement identity would break existing approvals.`,
      );
    }
    let identity: LocalIdentity;
    if (!exists.some(Boolean)) {
      if (["1", "true"].includes(process.env.CI ?? "")) {
        throw new Error(
          "CI must restore the existing Handy signing identity or provide HANDY_MACOS_SIGNING_IDENTITY. Creating a new identity per job would invalidate privacy approvals.",
        );
      }
      fresh = true;
      const password = Buffer.from(`${randomBytes(32).toString("hex")}\n`);
      await writeFile(secret, password, { mode: 0o600, flag: "wx" });
      stage = await mkdtemp(join(directory, ".prepare-"));
      await writeFile(
        join(stage, "openssl.cnf"),
        `[req]
prompt = no
distinguished_name = name
x509_extensions = extensions
[name]
CN = Handy Local Build
O = Handy Local Build
[extensions]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
`,
      );
      await run([
        "/usr/bin/openssl",
        "req",
        "-new",
        "-newkey",
        "rsa:3072",
        "-x509",
        "-nodes",
        "-days",
        "3650",
        "-sha256",
        "-config",
        join(stage, "openssl.cnf"),
        "-keyout",
        join(stage, "key.pem"),
        "-out",
        join(stage, "certificate.pem"),
      ]);
      await chmod(join(stage, "key.pem"), 0o600);
      await run(
        [
          "/usr/bin/openssl",
          "pkcs12",
          "-export",
          "-inkey",
          join(stage, "key.pem"),
          "-in",
          join(stage, "certificate.pem"),
          "-out",
          join(stage, "identity.p12"),
          "-passout",
          "stdin",
        ],
        password,
      );
      await run([helper, "create", keychain], password);
      unlocked = true;
      await run(
        [helper, "import", keychain, join(stage, "identity.p12")],
        password,
      );
      await copyFile(join(stage, "certificate.pem"), certificate);
      const pem = await readFile(certificate, "utf8");
      const der = Buffer.from(pem.replace(/-----[^-]+-----|\s/g, ""), "base64");
      identity = {
        certificateSha1: createHash("sha1").update(der).digest("hex"),
      };
      await writeFile(metadata, JSON.stringify(identity, null, 2) + "\n", {
        mode: 0o600,
        flag: "wx",
      });
      password.fill(0);
    } else {
      identity = JSON.parse(await readFile(metadata, "utf8"));
      if (!/^[0-9a-f]{40}$/.test(identity.certificateSha1)) {
        throw new Error("Invalid Handy local signing identity metadata.");
      }
      const password = await readFile(secret);
      await run([helper, "unlock", keychain], password);
      password.fill(0);
      unlocked = true;
    }
    await run([
      "/usr/bin/codesign",
      "--force",
      "--sign",
      identity.certificateSha1,
      "--keychain",
      keychain,
      "--timestamp=none",
      "--options",
      "runtime",
      "--entitlements",
      join(root, "src-tauri/Entitlements.plist"),
      app,
    ]);
    await run(["/usr/bin/codesign", "--verify", "--deep", "--strict", app]);
    const requirement = await run(["/usr/bin/codesign", "-d", "-r-", app]);
    const designatedRequirement = (requirement.stdout + requirement.stderr)
      .split("\n")
      .find((line) => line.includes("designated =>"))
      ?.trim()
      .replace(/^#\s*/, "");
    if (
      !designatedRequirement?.includes('identifier "com.pais.handy"') ||
      !designatedRequirement
        .toLowerCase()
        .includes(`certificate root = h"${identity.certificateSha1}"`)
    ) {
      throw new Error(
        "The signed app does not have Handy's certificate-bound identity.",
      );
    }
    if (
      identity.designatedRequirement &&
      identity.designatedRequirement.replace(/^#\s*/, "") !==
        designatedRequirement
    ) {
      throw new Error(
        "Handy's designated requirement changed unexpectedly; refusing to package an incompatible update.",
      );
    }
    await writeFile(
      metadata,
      JSON.stringify({ ...identity, designatedRequirement }, null, 2) + "\n",
      { mode: 0o600 },
    );
    console.log(
      `Signed with the persistent Handy Local Build identity: ${identity.certificateSha1}`,
    );
    return designatedRequirement;
  } catch (error) {
    if (fresh) {
      if (unlocked) {
        await run([helper, "lock", keychain]);
        unlocked = false;
      }
      // Only files created by this failed first setup may be removed. Never
      // silently replace an identity used by a previous successful build.
      for (const path of [keychain, secret, certificate, metadata]) {
        await rm(path, { force: true });
      }
    }
    throw error;
  } finally {
    try {
      if (unlocked) await run([helper, "lock", keychain]);
    } finally {
      if (stage) await rm(stage, { recursive: true, force: true });
      await rm(lock, { recursive: true });
    }
  }
}
