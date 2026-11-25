import { lazy } from "react";

// Screens the settings window doesn't draw at launch. Each is read from the
// bundle when first shown, so opening the window parses only the General tab.
const loaders = {
  history: () => import("./settings/history/HistorySettings"),
  models: () => import("./settings/models/ModelsSettings"),
  advanced: () => import("./settings/advanced/AdvancedSettings"),
  postProcessing: () =>
    import("./settings/post-processing/PostProcessingSettings"),
  about: () => import("./settings/about/AboutSettings"),
  debug: () => import("./settings/debug/DebugSettings"),
};

export const HistorySettings = lazy(() =>
  loaders.history().then((m) => ({ default: m.HistorySettings })),
);
export const ModelsSettings = lazy(() =>
  loaders.models().then((m) => ({ default: m.ModelsSettings })),
);
export const AdvancedSettings = lazy(() =>
  loaders.advanced().then((m) => ({ default: m.AdvancedSettings })),
);
export const PostProcessingSettings = lazy(() =>
  loaders.postProcessing().then((m) => ({ default: m.PostProcessingSettings })),
);
export const AboutSettings = lazy(() =>
  loaders.about().then((m) => ({ default: m.AboutSettings })),
);
export const DebugSettings = lazy(() =>
  loaders.debug().then((m) => ({ default: m.DebugSettings })),
);

export const OnboardingFlow = lazy(() =>
  import("./onboarding/OnboardingFlow").then((m) => ({
    default: m.OnboardingFlow,
  })),
);
export const ModelMigration = lazy(() => import("./onboarding/ModelMigration"));
export const ReturningPermissions = lazy(() =>
  import("./onboarding/StandaloneScreens").then((m) => ({
    default: m.ReturningPermissions,
  })),
);
export const ReplaceModel = lazy(() =>
  import("./onboarding/StandaloneScreens").then((m) => ({
    default: m.ReplaceModel,
  })),
);

/**
 * Reads the tabs a user can open, once the window is idle, so switching tabs
 * doesn't wait on a load. Debug is left for whoever turns debug mode on.
 */
export const preloadSettingsSections = () => {
  const { debug: _debug, ...sections } = loaders;
  for (const load of Object.values(sections)) {
    // A tab that fails to preload is loaded again when it is opened.
    void load().catch(() => {});
  }
};
