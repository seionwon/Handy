import assert from "node:assert/strict";
import { mockIPC } from "@tauri-apps/api/mocks";
import { createServer } from "vite";
import korean from "../src/i18n/locales/ko/translation.json";

// Vite resolves the same locale glob as the app, without starting a web server.
const vite = await createServer({
  server: { middlewareMode: true, ws: false, watch: null },
});
const attributes = new Map<string, string>();
Object.assign(globalThis, {
  window: {},
  document: {
    documentElement: { setAttribute: attributes.set.bind(attributes) },
  },
});
let saved = "ko-KR";
let delay: Promise<void> | undefined;
mockIPC(async (command) => {
  if (command === "plugin:os|locale") return "zh-Hant-HK";
  assert.equal(command, "get_app_settings");
  const settings = { app_language: saved };
  await delay;
  return settings;
});

try {
  const {
    default: i18n,
    initialLanguageReady,
    syncLanguageFromSettings,
    SUPPORTED_LANGUAGES,
  } = (await vite.ssrLoadModule(
    "/src/i18n/index.ts",
  )) as typeof import("../src/i18n");
  await initialLanguageReady;
  assert.deepEqual(Object.keys(i18n.store.data).sort(), ["en", "ko"]);
  assert.equal(i18n.t("appLanguage.title"), korean.appLanguage.title);
  assert(SUPPORTED_LANGUAGES.some((language) => language.code === "ar"));

  await Promise.all([i18n.changeLanguage("fr"), i18n.changeLanguage("ar")]);
  assert.equal(attributes.get("lang"), "ar");
  assert.equal(attributes.get("dir"), "rtl");

  saved = "";
  await syncLanguageFromSettings();
  assert.equal(i18n.language, "zh-TW");
  assert.deepEqual(i18n.languages, ["zh-TW", "zh", "en"]);
  assert.equal(attributes.get("dir"), "ltr");

  // Simulate an older overlay settings read arriving after a newer one.
  let release!: () => void;
  delay = new Promise((resolve) => {
    release = resolve;
  });
  saved = "de";
  const older = syncLanguageFromSettings();
  delay = undefined;
  saved = "ko";
  await syncLanguageFromSettings();
  release();
  await older;
  assert.equal(i18n.language, "ko");
  assert(!i18n.hasResourceBundle("de", "translation"));

  const switching = i18n.changeLanguage("ja");
  await syncLanguageFromSettings();
  await switching;
  assert.equal(
    i18n.language,
    "ko",
    "a pending switch can be superseded by the current locale",
  );
  console.log("Locale loading and switching checks passed.");
} finally {
  await vite.close();
}
