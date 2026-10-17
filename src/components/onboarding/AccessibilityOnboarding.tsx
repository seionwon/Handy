import { useEffect, useState, useRef } from "react";
import { useTranslation } from "react-i18next";
import { platform } from "@tauri-apps/plugin-os";
import {
  checkAccessibilityPermission,
  checkMicrophonePermission,
} from "tauri-plugin-macos-permissions-api";
import { toast } from "sonner";
import { commands } from "@/bindings";
import { pollPermissions } from "@/lib/permissionPolling";
import HandyTextLogo from "../icons/HandyTextLogo";
import QuitButton from "../QuitButton";
import { Keyboard, Mic, Check, Loader2 } from "lucide-react";

interface AccessibilityOnboardingProps {
  onComplete: () => void;
  preview?: boolean;
}

type PermissionStatus = "checking" | "needed" | "waiting" | "granted";
type PermissionPlatform = "macos" | "windows" | "other";

interface PermissionsState {
  accessibility: PermissionStatus;
  microphone: PermissionStatus;
}

const AccessibilityOnboarding: React.FC<AccessibilityOnboardingProps> = ({
  onComplete,
  preview = false,
}) => {
  const { t } = useTranslation();
  const [permissionPlatform, setPermissionPlatform] =
    useState<PermissionPlatform | null>(null);
  const [permissions, setPermissions] = useState<PermissionsState>({
    accessibility: "checking",
    microphone: "checking",
  });
  const [pollingAttempt, setPollingAttempt] = useState(0);
  const [requesting, setRequesting] = useState(false);
  const requestInFlightRef = useRef(false);
  const onCompleteRef = useRef(onComplete);
  onCompleteRef.current = onComplete;

  const isMacOS = permissionPlatform === "macos";
  const isWindows = permissionPlatform === "windows";
  const showMicrophonePermission = isMacOS || isWindows;
  const showAccessibilityPermission = isMacOS;
  const allGranted =
    permissionPlatform !== null &&
    (isMacOS
      ? permissions.accessibility === "granted" &&
        permissions.microphone === "granted"
      : isWindows
        ? permissions.microphone === "granted"
        : true);

  // Permission checks are read-only. Only App initializes input after leaving
  // onboarding; checking a pre-existing grant must never install input hooks.
  useEffect(() => {
    const currentPlatform = platform();
    const nextPlatform: PermissionPlatform =
      currentPlatform === "macos"
        ? "macos"
        : currentPlatform === "windows"
          ? "windows"
          : "other";
    setPermissionPlatform(nextPlatform);
    if (preview) {
      setPermissions({
        accessibility: nextPlatform === "macos" ? "needed" : "granted",
        microphone: nextPlatform === "other" ? "granted" : "needed",
      });
      return;
    }

    return pollPermissions({
      check: async () => {
        if (nextPlatform === "macos") {
          const [accessibility, microphone] = await Promise.all([
            checkAccessibilityPermission(),
            checkMicrophonePermission(),
          ]);
          return { accessibility, microphone };
        }
        if (nextPlatform === "windows") {
          const status = await commands.getWindowsMicrophonePermissionStatus();
          return {
            accessibility: true,
            microphone: !status.supported || status.overall_access !== "denied",
          };
        }
        return { accessibility: true, microphone: true };
      },
      onStatus: ({ accessibility, microphone }) => {
        setPermissions((prev) => ({
          accessibility: accessibility
            ? "granted"
            : prev.accessibility === "waiting"
              ? "waiting"
              : "needed",
          microphone: microphone
            ? "granted"
            : prev.microphone === "waiting"
              ? "waiting"
              : "needed",
        }));
      },
      onComplete: () => onCompleteRef.current(),
      onError: (error, consecutiveErrors) => {
        console.error("Failed to check permissions:", error);
        if (consecutiveErrors >= 3) {
          setPermissions({ accessibility: "needed", microphone: "needed" });
          toast.error(t("onboarding.permissions.errors.checkFailed"));
        }
      },
    });
  }, [pollingAttempt, preview, t]);

  const handleGrantAccessibility = async () => {
    if (preview || requestInFlightRef.current) return;
    requestInFlightRef.current = true;
    setRequesting(true);
    try {
      // Open one settings pane directly. The plugin's permission prompt plus
      // this command presented two separate permission actions for one click.
      const result = await commands.openAccessibilitySettings();
      if (result.status === "error") throw new Error(result.error);
      setPermissions((prev) => ({ ...prev, accessibility: "waiting" }));
      setPollingAttempt((attempt) => attempt + 1);
    } catch (error) {
      console.error("Failed to request accessibility permission:", error);
      toast.error(t("onboarding.permissions.errors.requestFailed"));
    } finally {
      requestInFlightRef.current = false;
      setRequesting(false);
    }
  };

  const handleGrantMicrophone = async () => {
    if (preview || requestInFlightRef.current) return;
    requestInFlightRef.current = true;
    setRequesting(true);
    try {
      const result = isWindows
        ? await commands.openMicrophonePrivacySettings()
        : await commands.requestMicrophonePermission();
      if (result.status === "error") throw new Error(result.error);
      setPermissions((prev) => ({ ...prev, microphone: "waiting" }));
      setPollingAttempt((attempt) => attempt + 1);
    } catch (error) {
      console.error("Failed to request microphone permission:", error);
      toast.error(t("onboarding.permissions.errors.requestFailed"));
    } finally {
      requestInFlightRef.current = false;
      setRequesting(false);
    }
  };

  const isChecking =
    permissionPlatform === null ||
    (isMacOS &&
      permissions.accessibility === "checking" &&
      permissions.microphone === "checking") ||
    (isWindows && permissions.microphone === "checking");

  // Still checking platform/initial permissions
  if (isChecking) {
    return (
      <div className="h-screen w-full flex items-center justify-center">
        <Loader2 className="w-8 h-8 animate-spin text-text/50" />
      </div>
    );
  }

  // All permissions granted - show success briefly
  if (allGranted) {
    return (
      <div className="h-screen w-full flex flex-col items-center justify-center gap-4">
        <div className="p-4 rounded-full bg-emerald-500/20">
          <Check className="w-12 h-12 text-emerald-400" />
        </div>
        <p className="text-lg font-medium text-text">
          {t("onboarding.permissions.allGranted")}
        </p>
      </div>
    );
  }

  // Show permissions request screen
  return (
    <div className="relative h-screen w-full flex flex-col p-6 gap-6 items-center justify-center">
      <div className="absolute top-4 right-4">
        <QuitButton disabled={preview} />
      </div>
      <div className="flex flex-col items-center gap-2">
        <HandyTextLogo width={200} />
      </div>

      <div className="max-w-md w-full flex flex-col items-center gap-4">
        <div className="text-center mb-2">
          <h2 className="text-xl font-semibold text-text mb-2">
            {t("onboarding.permissions.title")}
          </h2>
          <p className="text-text/70">
            {t("onboarding.permissions.description")}
          </p>
        </div>

        {/* Microphone Permission Card */}
        {showMicrophonePermission && (
          <div className="w-full p-4 rounded-lg bg-white/5 border border-mid-gray/20">
            <div className="flex items-center gap-4">
              <div className="p-3 rounded-full bg-logo-primary/20 shrink-0">
                <Mic className="w-6 h-6 text-logo-primary" />
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="font-medium text-text">
                  {t("onboarding.permissions.microphone.title")}
                </h3>
                <p className="text-sm text-text/60 mb-3">
                  {t("onboarding.permissions.microphone.description")}
                </p>
                {permissions.microphone === "granted" ? (
                  <div className="flex items-center gap-2 text-emerald-400 text-sm">
                    <Check className="w-4 h-4" />
                    {t("onboarding.permissions.granted")}
                  </div>
                ) : (
                  <button
                    onClick={handleGrantMicrophone}
                    disabled={requesting}
                    className="px-4 py-2 rounded-lg bg-logo-primary hover:bg-logo-primary/90 text-white text-sm font-medium transition-colors"
                  >
                    {isWindows || permissions.microphone === "waiting"
                      ? t("accessibility.openSettings")
                      : t("onboarding.permissions.grant")}
                  </button>
                )}
              </div>
            </div>
          </div>
        )}

        {/* Accessibility Permission Card */}
        {showAccessibilityPermission && (
          <div className="w-full p-4 rounded-lg bg-white/5 border border-mid-gray/20">
            <div className="flex items-center gap-4">
              <div className="p-3 rounded-full bg-logo-primary/20 shrink-0">
                <Keyboard className="w-6 h-6 text-logo-primary" />
              </div>
              <div className="flex-1 min-w-0">
                <h3 className="font-medium text-text">
                  {t("onboarding.permissions.accessibility.title")}
                </h3>
                <p className="text-sm text-text/60 mb-3">
                  {t("onboarding.permissions.accessibility.description")}
                </p>
                {permissions.accessibility === "waiting" && (
                  <p className="text-sm text-text/60 mb-3">
                    {t(
                      "onboarding.permissions.accessibility.existingEntryHint",
                    )}
                  </p>
                )}
                {permissions.accessibility === "granted" ? (
                  <div className="flex items-center gap-2 text-emerald-400 text-sm">
                    <Check className="w-4 h-4" />
                    {t("onboarding.permissions.granted")}
                  </div>
                ) : (
                  <button
                    onClick={handleGrantAccessibility}
                    disabled={requesting}
                    className="px-4 py-2 rounded-lg bg-logo-primary hover:bg-logo-primary/90 text-white text-sm font-medium transition-colors"
                  >
                    {t("accessibility.openSettings")}
                  </button>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default AccessibilityOnboarding;
