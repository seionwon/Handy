import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { createHash } from "crypto";
import {
  createReadStream,
  existsSync,
  readdirSync,
  readFileSync,
  statSync,
} from "fs";
import type { IncomingMessage, ServerResponse } from "http";
import type { AddressInfo } from "net";
import { networkInterfaces } from "os";
import { basename, join, resolve } from "path";

// This desktop app only needs a local dev server. In particular, never expose
// Vite's module-fetch WebSocket to the LAN via TAURI_DEV_HOST (GHSA-p9ff-h696-f583).
const host = "127.0.0.1";

interface ResolvedApkInfo {
  apkFile: string;
  gradleOutputFile: string;
  version: string;
  versionCode: number;
  fileName: string;
  downloadPath: string;
}

// Serve the latest built Android APK with versioned filename and installable
// MIME headers in both dev and preview modes so CodeMemo's Run button always
// exposes a versioned download link.
const androidApkHeadersPlugin = (): Plugin => {
  const debugApkDir = resolve(__dirname, "android/app/build/outputs/apk/debug");
  const publishedDownloadsDir = resolve(__dirname, "android/downloads");
  const distDownloadsDir = resolve(__dirname, "dist/downloads");
  const pkgPath = resolve(__dirname, "package.json");
  let cachedMeta: { key: string; json: string } | null = null;

  const readPackageVersion = (): string => {
    const pkg = JSON.parse(readFileSync(pkgPath, "utf8")) as {
      version: string;
    };
    return pkg.version;
  };

  const resolveLatestApk = (): ResolvedApkInfo | null => {
    const version = readPackageVersion();
    const fileName = `Handy-v${version}-android.apk`;
    const downloadPath = `/downloads/${fileName}`;
    const candidates: Array<{
      path: string;
      gradleOutputFile: string;
      versionCode: number;
    }> = [];

    const metadataPath = join(debugApkDir, "output-metadata.json");
    if (existsSync(metadataPath)) {
      try {
        const meta = JSON.parse(readFileSync(metadataPath, "utf8")) as {
          elements?: Array<{ outputFile?: string; versionCode?: number }>;
        };
        const first = meta.elements?.[0];
        if (first?.outputFile) {
          const gradlePath = join(debugApkDir, first.outputFile);
          if (existsSync(gradlePath)) {
            candidates.push({
              path: gradlePath,
              gradleOutputFile: first.outputFile,
              versionCode: first.versionCode ?? 1001,
            });
          }
        }
      } catch {
        // Ignore malformed metadata and fall back to directory scan.
      }
    }

    if (candidates.length === 0 && existsSync(debugApkDir)) {
      const debugApks = readdirSync(debugApkDir)
        .filter((f) => f.endsWith(".apk"))
        .map((f) => join(debugApkDir, f))
        .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs);
      if (debugApks.length > 0) {
        candidates.push({
          path: debugApks[0],
          gradleOutputFile: basename(debugApks[0]),
          versionCode: 1001,
        });
      }
    }

    for (const dir of [publishedDownloadsDir, distDownloadsDir]) {
      const versioned = join(dir, fileName);
      if (existsSync(versioned)) {
        candidates.push({
          path: versioned,
          gradleOutputFile: `Handy-v${version}-debug.apk`,
          versionCode: 1001,
        });
      }
    }

    if (candidates.length === 0) return null;
    candidates.sort(
      (a, b) => statSync(b.path).mtimeMs - statSync(a.path).mtimeMs,
    );
    const winner = candidates[0];
    return {
      apkFile: winner.path,
      gradleOutputFile: winner.gradleOutputFile,
      version,
      versionCode: winner.versionCode,
      fileName,
      downloadPath,
    };
  };

  const getApkMetaJson = (info: ResolvedApkInfo): string => {
    const st = statSync(info.apkFile);
    const key = `${info.apkFile}:${info.version}:${st.size}:${st.mtimeMs}`;
    if (cachedMeta && cachedMeta.key === key) {
      return cachedMeta.json;
    }
    const sha256 = createHash("sha256")
      .update(readFileSync(info.apkFile))
      .digest("hex");
    const json = JSON.stringify(
      {
        version: info.version,
        versionCode: info.versionCode,
        packageId: "com.pais.handy",
        fileName: info.fileName,
        downloadUrl: info.downloadPath,
        relativeDownloadUrl: `downloads/${info.fileName}`,
        latestAliasUrl: "/downloads/Handy-Android.apk",
        gradleOutputFile: info.gradleOutputFile,
        sizeBytes: st.size,
        sha256,
        minSdk: 26,
        targetSdk: 36,
        abis: ["arm64-v8a", "x86_64"],
        builtAt: st.mtime.toISOString(),
      },
      null,
      2,
    );
    cachedMeta = { key, json };
    return json;
  };

  const streamApk = (
    req: IncomingMessage,
    res: ServerResponse,
    info: ResolvedApkInfo,
  ) => {
    const st = statSync(info.apkFile);
    res.statusCode = 200;
    res.setHeader("Content-Type", "application/vnd.android.package-archive");
    res.setHeader(
      "Content-Disposition",
      `attachment; filename="${info.fileName}"`,
    );
    res.setHeader("Content-Length", st.size);
    res.setHeader("Cache-Control", "no-store");
    if (req.method === "HEAD") {
      res.end();
    } else {
      createReadStream(info.apkFile).pipe(res);
    }
  };

  const middleware = (
    req: IncomingMessage,
    res: ServerResponse,
    next: () => void,
  ) => {
    const rawUrl = req.url ?? "";
    const pathname = rawUrl.split("?")[0];

    if (
      pathname === "/downloads/android-apk-meta.json" ||
      pathname.endsWith("/downloads/android-apk-meta.json")
    ) {
      const info = resolveLatestApk();
      if (info) {
        const body = getApkMetaJson(info);
        res.statusCode = 200;
        res.setHeader("Content-Type", "application/json; charset=utf-8");
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Content-Length", Buffer.byteLength(body));
        if (req.method === "HEAD") {
          res.end();
        } else {
          res.end(body);
        }
        return;
      }
    }

    if (
      pathname === "/downloads/Handy-Android.apk" ||
      pathname.endsWith("/downloads/Handy-Android.apk")
    ) {
      const info = resolveLatestApk();
      if (info) {
        const prefix = pathname.slice(
          0,
          pathname.length - "/downloads/Handy-Android.apk".length,
        );
        res.statusCode = 302;
        res.setHeader("Location", `${prefix}${info.downloadPath}`);
        res.setHeader("Cache-Control", "no-store");
        res.end();
        return;
      }
    }

    const info = resolveLatestApk();
    if (
      info &&
      (pathname === info.downloadPath || pathname.endsWith(info.downloadPath))
    ) {
      streamApk(req, res, info);
      return;
    }

    if (/\.apk$/i.test(pathname)) {
      const fallbackName = info?.fileName ?? basename(pathname);
      res.setHeader("Content-Type", "application/vnd.android.package-archive");
      res.setHeader(
        "Content-Disposition",
        `attachment; filename="${fallbackName}"`,
      );
    }
    next();
  };

  const logApkUrls = (address: string | AddressInfo | null) => {
    const info = resolveLatestApk();
    if (!info || !address || typeof address === "string") return;
    const { port, address: bindAddr } = address;
    const displayHost =
      bindAddr === "::" || bindAddr === "0.0.0.0" ? "127.0.0.1" : bindAddr;
    console.log(
      `  ➜  Android APK (v${info.version}): http://${displayHost}:${port}${info.downloadPath}`,
    );
    if (bindAddr === "::" || bindAddr === "0.0.0.0") {
      const nets = networkInterfaces();
      for (const list of Object.values(nets)) {
        for (const item of list ?? []) {
          if (item.family === "IPv4" && !item.internal) {
            console.log(
              `  ➜  Android APK (LAN):    http://${item.address}:${port}${info.downloadPath}`,
            );
          }
        }
      }
    }
  };

  return {
    name: "handy-android-apk-headers",
    configureServer(server) {
      server.middlewares.use(middleware);
      server.httpServer?.once("listening", () => {
        logApkUrls(server.httpServer?.address() ?? null);
      });
    },
    configurePreviewServer(server) {
      server.middlewares.use(middleware);
      server.httpServer?.once("listening", () => {
        logApkUrls(server.httpServer?.address() ?? null);
      });
    },
  };
};

// https://vitejs.dev/config/
export default defineConfig(async () => ({
  plugins: [react(), tailwindcss(), androidApkHeadersPlugin()],

  // Path aliases
  resolve: {
    alias: {
      "@": resolve(__dirname, "./src"),
      "@/bindings": resolve(__dirname, "./src/bindings.ts"),
    },
  },

  // Multiple entry points: the main app and the overlay
  build: {
    rollupOptions: {
      input: {
        main: resolve(__dirname, "index.html"),
        overlay: resolve(__dirname, "src/overlay/index.html"),
      },
    },
  },

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host,
    hmr: {
      protocol: "ws",
      host: "localhost",
      port: 1421,
    },
    watch: {
      // 3. tell vite to ignore watching `src-tauri` and root `android` (not `src/components/android`)
      ignored: ["**/src-tauri/**", `${resolve(__dirname, "android")}/**`],
    },
  },
}));
