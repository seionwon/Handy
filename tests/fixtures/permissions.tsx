import React from "react";
import { createRoot } from "react-dom/client";
import { mockIPC } from "@tauri-apps/api/mocks";
import i18next from "i18next";
import { I18nextProvider } from "react-i18next";
import AccessibilityOnboarding from "../../src/components/onboarding/AccessibilityOnboarding";
import AccessibilityPermissions from "../../src/components/AccessibilityPermissions";
import translations from "../../src/i18n/locales/en/translation.json";

const query = new URLSearchParams(location.search);
const state = {
  accessibility: query.has("granted"),
  microphone: !query.has("microphoneDenied"),
  calls: [] as string[],
  completed: 0,
  inFlight: 0,
  maxInFlight: 0,
  unmount: () => {},
};
Object.assign(window, {
  __TAURI_OS_PLUGIN_INTERNALS__: { platform: "macos", os_type: "macos" },
  permissionHarness: state,
});
mockIPC(async (command) => {
  state.calls.push(command);
  if (command.endsWith("check_accessibility_permission")) {
    state.maxInFlight = Math.max(state.maxInFlight, ++state.inFlight);
    await new Promise((resolve) =>
      setTimeout(resolve, Number(query.get("delay") ?? 0)),
    );
    state.inFlight--;
    return state.accessibility;
  }
  if (command.endsWith("check_microphone_permission")) return state.microphone;
  if (command === "plugin:process|restart") return null;
  if (
    ["open_accessibility_settings", "request_microphone_permission"].includes(
      command,
    )
  ) {
    await new Promise((resolve) =>
      setTimeout(resolve, Number(query.get("requestDelay") ?? 0)),
    );
    return null;
  }
  throw new Error(
    `Unexpected native command in permission fixture: ${command}`,
  );
});
const i18n = i18next.createInstance();
await i18n.init({
  lng: "en",
  resources: { en: { translation: translations } },
});
const root = createRoot(document.getElementById("root")!);
state.unmount = () => root.unmount();
root.render(
  <React.StrictMode>
    <I18nextProvider i18n={i18n}>
      {query.has("legacy") ? (
        <AccessibilityPermissions />
      ) : (
        <AccessibilityOnboarding
          preview={query.has("preview")}
          onComplete={() => {
            state.completed++;
          }}
        />
      )}
    </I18nextProvider>
  </React.StrictMode>,
);
