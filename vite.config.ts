import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { createHash } from "crypto";
import { createReadStream, existsSync, readFileSync, statSync } from "fs";
import type { IncomingMessage, ServerResponse } from "http";
import type { AddressInfo } from "net";
import { networkInterfaces } from "os";
import { resolve } from "path";

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

// The publisher writes this marker only after checking the release APK and its signer.
const androidApkHeadersPlugin = (): Plugin => {
  const marker = resolve(
    __dirname,
    "android/app/build/outputs/verified-release.json",
  );
  const version = JSON.parse(
    readFileSync(resolve(__dirname, "package.json"), "utf8"),
  ).version as string;
  const fileName = `Handy-v${version}.apk`;
  const legacyApk = resolve(__dirname, fileName);
  let cachedMeta: { key: string; json: string } | null = null;
  let verifiedFile = "";
  const resolveLatestApk = (): ResolvedApkInfo | null => {
    if (!existsSync(marker)) return null;
    try {
      const release = JSON.parse(readFileSync(marker, "utf8"));
      if (
        release.version !== version ||
        release.fileName !== fileName ||
        !Number.isInteger(release.versionCode) ||
        typeof release.sha256 !== "string" ||
        !/^[a-f0-9]{64}$/.test(release.sha256)
      )
        return null;
      const snapshot = resolve(
        __dirname,
        "android/app/build/outputs/verified-apks",
        release.sha256 + ".bin",
      );
      const apkFile = existsSync(snapshot) ? snapshot : legacyApk;
      const stat = statSync(apkFile);
      const stamp = `${stat.ino}:${stat.ctimeMs}:${stat.size}:${release.sha256}`;
      if (stamp !== verifiedFile) {
        if (
          createHash("sha256").update(readFileSync(apkFile)).digest("hex") !==
          release.sha256
        )
          return null;
        verifiedFile = stamp;
        cachedMeta = null;
      }
      return {
        apkFile,
        fileName,
        gradleOutputFile: fileName,
        version,
        versionCode: release.versionCode,
        downloadPath: `/downloads/${fileName}`,
      };
    } catch {
      return null;
    }
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
    let pathname: string;
    try {
      pathname = decodeURIComponent(rawUrl.split("?")[0]);
    } catch {
      res.statusCode = 400;
      res.end();
      return;
    }

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
        res.statusCode = 302;
        res.setHeader("Location", info.fileName);
        res.setHeader("Cache-Control", "no-store");
        res.end();
        return;
      }
    }

    const info = resolveLatestApk();
    if (
      info &&
      (pathname === info.downloadPath ||
        pathname.endsWith(info.downloadPath) ||
        pathname === `/${info.fileName}` ||
        pathname.endsWith(`/${info.fileName}`))
    ) {
      streamApk(req, res, info);
      return;
    }

    if (
      /\.apk(?:\.part)?$/i.test(pathname) ||
      pathname.includes("/verified-apks/") ||
      // Local release material is never part of the app or the install page.
      /^\/release\.local(?:\/|$)/i.test(pathname) ||
      /\/\.handy-(?:apk-|previous-|release-)/.test(pathname)
    ) {
      res.statusCode = 404;
      res.end();
      return;
    }
    if (pathname.endsWith("/downloads/android-apk-meta.json")) {
      res.statusCode = 404;
      res.end();
      return;
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
