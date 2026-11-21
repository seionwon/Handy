import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { createHash } from "crypto";
import { createReadStream, existsSync, readFileSync, statSync } from "fs";
import type { IncomingMessage, ServerResponse } from "http";
import { resolve } from "path";

// This desktop app only needs a local dev server. In particular, never expose
// Vite's module-fetch WebSocket to the LAN via TAURI_DEV_HOST (GHSA-p9ff-h696-f583).
const host = "127.0.0.1";

// Serve the latest built Android APK with installable MIME headers in both dev
// and preview modes so CodeMemo's Run button always delivers a fresh APK.
const androidApkHeadersPlugin = (): Plugin => {
  const gradleApkPath = resolve(
    __dirname,
    "android/app/build/outputs/apk/debug/app-debug.apk",
  );
  const publicApkPath = resolve(
    __dirname,
    "public/downloads/Handy-Android.apk",
  );
  const pkgPath = resolve(__dirname, "package.json");
  let cachedMeta: { key: string; json: string } | null = null;

  const pickApkPath = (): string | null => {
    const hasGradle = existsSync(gradleApkPath);
    const hasPublic = existsSync(publicApkPath);
    if (hasGradle && hasPublic) {
      return statSync(gradleApkPath).mtimeMs >= statSync(publicApkPath).mtimeMs
        ? gradleApkPath
        : publicApkPath;
    }
    if (hasGradle) return gradleApkPath;
    if (hasPublic) return publicApkPath;
    return null;
  };

  const getApkMetaJson = (apkFile: string): string => {
    const st = statSync(apkFile);
    const key = `${apkFile}:${st.size}:${st.mtimeMs}`;
    if (cachedMeta && cachedMeta.key === key) {
      return cachedMeta.json;
    }
    const pkg = JSON.parse(readFileSync(pkgPath, "utf8")) as {
      version: string;
    };
    const sha256 = createHash("sha256")
      .update(readFileSync(apkFile))
      .digest("hex");
    const json = JSON.stringify(
      {
        version: pkg.version,
        packageId: "com.pais.handy",
        fileName: `Handy-${pkg.version}-android.apk`,
        downloadUrl: "/downloads/Handy-Android.apk",
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

  const middleware = (
    req: IncomingMessage,
    res: ServerResponse,
    next: () => void,
  ) => {
    const rawUrl = req.url ?? "";
    const pathname = rawUrl.split("?")[0];

    if (pathname === "/downloads/android-apk-meta.json") {
      const apkFile = pickApkPath();
      if (apkFile) {
        const body = getApkMetaJson(apkFile);
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

    if (pathname === "/downloads/Handy-Android.apk") {
      const apkFile = pickApkPath();
      if (apkFile) {
        const st = statSync(apkFile);
        res.statusCode = 200;
        res.setHeader(
          "Content-Type",
          "application/vnd.android.package-archive",
        );
        res.setHeader(
          "Content-Disposition",
          'attachment; filename="Handy-Android.apk"',
        );
        res.setHeader("Content-Length", st.size);
        res.setHeader("Cache-Control", "no-store");
        if (req.method === "HEAD") {
          res.end();
        } else {
          createReadStream(apkFile).pipe(res);
        }
        return;
      }
    }

    if (/\.apk$/i.test(pathname)) {
      res.setHeader("Content-Type", "application/vnd.android.package-archive");
      res.setHeader(
        "Content-Disposition",
        'attachment; filename="Handy-Android.apk"',
      );
    }
    next();
  };

  return {
    name: "handy-android-apk-headers",
    configureServer(server) {
      server.middlewares.use(middleware);
    },
    configurePreviewServer(server) {
      server.middlewares.use(middleware);
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
