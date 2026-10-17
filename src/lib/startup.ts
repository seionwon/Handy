interface StartupCommands {
  getAppSettings: () => Promise<
    | {
        status: "ok";
        data: {
          onboarding_completed?: boolean;
          selected_model?: string;
        };
      }
    | { status: "error"; error: string }
  >;
  getRetiredModels: () => Promise<{ id: string }[]>;
  getWindowsMicrophonePermissionStatus: () => Promise<{
    supported: boolean;
    overall_access: string;
  }>;
}

interface StartupOptions {
  commands: StartupCommands;
  platform: string;
  checkAccessibility: () => Promise<boolean>;
  checkMicrophone: () => Promise<boolean>;
}

/** Decide onboarding from current grants; no permission requests or input hooks. */
export async function checkStartup({
  commands,
  platform,
  checkAccessibility,
  checkMicrophone,
}: StartupOptions) {
  const settings = await commands.getAppSettings();
  if (settings.status === "error") throw new Error(settings.error);
  const isReturningUser = settings.data.onboarding_completed === true;

  const permissionCheck = async () => {
    if (platform === "macos") {
      const [accessibility, microphone] = await Promise.all([
        checkAccessibility(),
        checkMicrophone(),
      ]);
      return accessibility && microphone;
    }
    if (platform === "windows") {
      const status = await commands.getWindowsMicrophonePermissionStatus();
      return !status.supported || status.overall_access !== "denied";
    }
    return true;
  };
  const [permissionsGranted, retired] = await Promise.all([
    permissionCheck().catch((error) => {
      // Preserve returning-user/model state while a failed permission query
      // is retried by onboarding. Never treat a query failure as a grant.
      console.warn("Failed to check startup permissions:", error);
      return false;
    }),
    isReturningUser ? commands.getRetiredModels() : Promise.resolve([]),
  ]);
  const migrationModelId =
    retired.find((model) => model.id === settings.data.selected_model)?.id ??
    null;
  const step = !permissionsGranted
    ? ("accessibility" as const)
    : !isReturningUser
      ? ("model" as const)
      : migrationModelId
        ? ("migration" as const)
        : ("done" as const);
  return { isReturningUser, migrationModelId, step };
}
