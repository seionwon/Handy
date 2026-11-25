import React from "react";
import ReactDOM from "react-dom/client";

const isTauriRuntime =
  typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;

async function bootstrap() {
  const rootEl = document.getElementById("root") as HTMLElement;

  if (!isTauriRuntime) {
    const { default: AndroidInstallPage } = await import(
      "./components/android/AndroidInstallPage"
    );
    ReactDOM.createRoot(rootEl).render(
      <React.StrictMode>
        <AndroidInstallPage />
      </React.StrictMode>,
    );
    return;
  }

  const [
    { platform },
    { default: App },
    { installCompatShims },
    { applyTheme, getStoredTheme, syncThemeFromSettings },
    { initialLanguageReady },
    { useModelStore },
    { useNoticeStore },
    { useTranscriptionJobsStore },
  ] = await Promise.all([
    import("@tauri-apps/plugin-os"),
    import("./App"),
    import("./lib/compat"),
    import("./lib/utils/theme"),
    import("./i18n"),
    import("./stores/modelStore"),
    import("./stores/noticeStore"),
    import("./stores/transcriptionJobsStore"),
  ]);

  installCompatShims();

  // Set platform before render so CSS can scope per-platform (e.g. scrollbar styles)
  document.documentElement.dataset.platform = platform();

  // Apply the last-known theme synchronously before render to avoid a flash of
  // the wrong palette, then reconcile with the persisted setting once it loads.
  applyTheme(getStoredTheme());
  syncThemeFromSettings();

  useModelStore.getState().initialize();
  useTranscriptionJobsStore.getState().initialize();
  useNoticeStore.getState().initialize();

  await initialLanguageReady;
  ReactDOM.createRoot(rootEl).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
}

void bootstrap();
