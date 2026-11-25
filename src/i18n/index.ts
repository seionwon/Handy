import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import { locale } from "@tauri-apps/plugin-os";
import english from "./locales/en/translation.json";
import { LANGUAGE_METADATA } from "./languages";
import { commands } from "@/bindings";
import { initializeRTL } from "@/lib/utils/rtl";

// English is available on first render. The other dictionaries ship as local
// assets that i18next loads when a window first needs the language.
const localeModules = import.meta.glob<Record<string, unknown>>(
  ["./locales/*/translation.json", "!./locales/en/translation.json"],
  { import: "default" },
);
const localeCodes = [
  "en",
  ...Object.keys(localeModules).map((path) => path.split("/")[2]),
];

// Build supported languages list from discovered locales + metadata
export const SUPPORTED_LANGUAGES = localeCodes
  .map((code) => {
    const meta = LANGUAGE_METADATA[code];
    if (!meta) {
      console.warn(`Missing metadata for locale "${code}" in languages.ts`);
      return { code, name: code, nativeName: code, priority: undefined };
    }
    return {
      code,
      name: meta.name,
      nativeName: meta.nativeName,
      priority: meta.priority,
    };
  })
  .sort((a, b) => {
    // Sort by priority first (lower = higher), then alphabetically
    if (a.priority !== undefined && b.priority !== undefined) {
      return a.priority - b.priority;
    }
    if (a.priority !== undefined) return -1;
    if (b.priority !== undefined) return 1;
    return a.name.localeCompare(b.name);
  });

export type SupportedLanguageCode = string;

// Check if a language code is supported
export const getSupportedLanguage = (
  langCode: string | null | undefined,
): SupportedLanguageCode | null => {
  if (!langCode) return null;

  const normalized = langCode.toLowerCase().replace(/_/g, "-");
  const subtags = normalized.split("-");
  const language = subtags[0];
  const isHant = subtags.includes("hant");
  const isHans = subtags.includes("hans");
  const isTraditionalRegion = ["tw", "hk", "mo"].some((region) =>
    subtags.includes(region),
  );

  // Try exact match first
  let supported = SUPPORTED_LANGUAGES.find(
    (lang) => lang.code.toLowerCase() === normalized,
  );
  if (!supported) {
    let fallback = language;
    if (language === "zh" && (isHant || (!isHans && isTraditionalRegion))) {
      fallback = "zh-tw";
    } else if (language === "yue") {
      // Cantonese uses Traditional Chinese unless explicitly tagged as Hans.
      fallback = isHans ? "zh" : "zh-tw";
    }
    supported = SUPPORTED_LANGUAGES.find(
      (lang) => lang.code.toLowerCase() === fallback,
    );
  }
  return supported ? supported.code : null;
};

// Initialize i18n with English as default
// Language will be synced from settings after init
i18n
  .use({
    type: "backend",
    async read(language: string, namespace: string) {
      if (language === "en" && namespace === "translation") return english;
      const load = localeModules[`./locales/${language}/${namespace}.json`];
      if (!load)
        throw new Error(
          `Missing bundled translations for ${language}/${namespace}`,
        );
      return load();
    },
  })
  .use(initReactI18next)
  .init({
    resources: { en: { translation: english } },
    partialBundledLanguages: true,
    lng: "en",
    fallbackLng: "en",
    interpolation: {
      escapeValue: false, // React already escapes values
    },
    react: {
      useSuspense: false, // Disable suspense for SSR compatibility
    },
  });

// While a dictionary loads, i18n.language still describes the previous one.
let requestedLanguage = i18n.language;
i18n.on("languageChanging", (language) => {
  requestedLanguage = language;
});
i18n.on("languageChanged", initializeRTL);

let latestSettingsRead = 0;
export const syncLanguageFromSettings = async (
  settings = commands.getAppSettings(),
) => {
  const read = ++latestSettingsRead;
  try {
    const result = await settings;
    const saved = result.status === "ok" ? result.data.app_language : undefined;
    const supported = getSupportedLanguage(saved || (await locale()));
    if (
      read === latestSettingsRead &&
      supported &&
      (supported !== i18n.language || supported !== requestedLanguage)
    ) {
      await i18n.changeLanguage(supported);
    }
  } catch (e) {
    console.warn("Failed to sync language from settings:", e);
  }
};

export const initialLanguageReady = syncLanguageFromSettings();

// Re-export RTL utilities for convenience
export { getLanguageDirection, isRTLLanguage } from "@/lib/utils/rtl";

export default i18n;
