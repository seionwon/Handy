import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { platform } from "@tauri-apps/plugin-os";
import { relaunch } from "@tauri-apps/plugin-process";
import {
  checkAccessibilityPermission,
  checkMicrophonePermission,
} from "tauri-plugin-macos-permissions-api";
import { notify } from "@/stores/noticeStore";
import { commands } from "@/bindings";
import { useSettingsStore } from "@/stores/settingsStore";
import { pollPermissions } from "@/lib/permissionPolling";
import { reuseOrRequestAccessibility } from "@/lib/accessibilityPermission";

type PermissionStatus = "checking" | "needed" | "waiting" | "granted";
type PermissionPlatform = "macos" | "windows" | "other";

export interface Permissions {
  platform: PermissionPlatform;
  microphone: PermissionStatus;
  accessibility: PermissionStatus;
  /** The first check is done. */
  checked: boolean;
  /** Something still needs allowing (decided by the first check). */
  needed: boolean;
  allGranted: boolean;
  /** A native permission action is currently in flight. */
  requesting: boolean;
  /** Asked once already: asking again opens the OS settings instead. */
  microphoneAsked: boolean;
  requestMicrophone: () => Promise<void>;
  requestAccessibility: () => Promise<void>;
  recheck: () => void;
  repairAccessibility: (onBeforeRestart?: () => void) => Promise<void>;
  restart: (onBeforeRestart?: () => void) => Promise<void>;
}

const currentPlatform = (): PermissionPlatform => {
  const p = platform();
  return p === "macos" ? "macos" : p === "windows" ? "windows" : "other";
};

interface UsePermissionsOptions {
  /** Called once all permissions are granted (for standalone returning screen). */
  onAutoComplete?: () => void;
}

/**
 * Read-only, sequential permission polling and safe native request/recovery
 * actions. Checking permissions never installs input hooks, requests prompts,
 * or modifies TCC grants without an explicit user recovery action.
 */
export const usePermissions = (
  preview: boolean,
  options?: UsePermissionsOptions,
): Permissions => {
  const { t } = useTranslation();
  const refreshAudioDevices = useSettingsStore((s) => s.refreshAudioDevices);
  const refreshOutputDevices = useSettingsStore((s) => s.refreshOutputDevices);
  const [os] = useState(currentPlatform);
  const [microphone, setMicrophone] = useState<PermissionStatus>("checking");
  const [accessibility, setAccessibility] =
    useState<PermissionStatus>("checking");
  const [needed, setNeeded] = useState(false);
  const [microphoneAsked, setMicrophoneAsked] = useState(false);
  const [pollingAttempt, setPollingAttempt] = useState(0);
  const [requesting, setRequesting] = useState(false);

  const initialDecidedRef = useRef(false);
  const requestInFlightRef = useRef(false);
  const onAutoCompleteRef = useRef(options?.onAutoComplete);
  onAutoCompleteRef.current = options?.onAutoComplete;

  const isMacOS = os === "macos";
  const isWindows = os === "windows";
  const allGranted = isMacOS
    ? accessibility === "granted" && microphone === "granted"
    : isWindows
      ? microphone === "granted"
      : true;

  const checkSnapshot = useCallback(async () => {
    if (isMacOS) {
      const [acc, mic] = await Promise.all([
        checkAccessibilityPermission(),
        checkMicrophonePermission(),
      ]);
      return { accessibility: acc, microphone: mic };
    }
    if (isWindows) {
      const status = await commands.getWindowsMicrophonePermissionStatus();
      return {
        accessibility: true,
        microphone: !status.supported || status.overall_access !== "denied",
      };
    }
    return { accessibility: true, microphone: true };
  }, [isMacOS, isWindows]);

  // Permission checks are strictly read-only and sequential. Only App or TryIt
  // initializes input after permissions are verified.
  useEffect(() => {
    if (preview) {
      setAccessibility(isMacOS ? "needed" : "granted");
      setMicrophone(os === "other" ? "granted" : "needed");
      setNeeded(os !== "other");
      return;
    }
    if (os === "other") {
      setAccessibility("granted");
      setMicrophone("granted");
      setNeeded(false);
      onAutoCompleteRef.current?.();
      return;
    }

    return pollPermissions({
      check: checkSnapshot,
      onStatus: ({ accessibility: acc, microphone: mic }) => {
        if (!initialDecidedRef.current) {
          initialDecidedRef.current = true;
          setNeeded(!(acc && mic));
        }
        setAccessibility((prev) =>
          acc ? "granted" : prev === "waiting" ? "waiting" : "needed",
        );
        setMicrophone((prev) =>
          mic ? "granted" : prev === "waiting" ? "waiting" : "needed",
        );
      },
      onComplete: () => {
        if (onAutoCompleteRef.current) {
          onAutoCompleteRef.current();
        } else {
          void Promise.all([
            refreshAudioDevices(),
            refreshOutputDevices(),
          ]).catch(() => {});
        }
      },
      onError: (error, consecutiveErrors) => {
        console.error("Failed to check permissions:", error);
        if (consecutiveErrors >= 3) {
          if (!initialDecidedRef.current) {
            initialDecidedRef.current = true;
            setNeeded(true);
          }
          setAccessibility((prev) => (prev === "granted" ? prev : "needed"));
          setMicrophone((prev) => (prev === "granted" ? prev : "needed"));
          notify({ title: t("onboarding.permissions.errors.checkFailed") });
        }
      },
    });
  }, [
    checkSnapshot,
    isMacOS,
    os,
    pollingAttempt,
    preview,
    refreshAudioDevices,
    refreshOutputDevices,
    t,
  ]);

  const recheck = useCallback(() => {
    if (preview) return;
    setPollingAttempt((attempt) => attempt + 1);
  }, [preview]);

  const requestAccessibility = useCallback(async () => {
    if (preview || requestInFlightRef.current) return;
    requestInFlightRef.current = true;
    setRequesting(true);
    try {
      const granted = await reuseOrRequestAccessibility({
        check: checkAccessibilityPermission,
        request:
          accessibility === "waiting"
            ? commands.openAccessibilitySettings
            : commands.requestAccessibilityPermission,
      });
      setAccessibility(granted ? "granted" : "waiting");
      setPollingAttempt((attempt) => attempt + 1);
    } catch (error) {
      console.error("Failed to request accessibility permission:", error);
      notify({ title: t("onboarding.permissions.errors.requestFailed") });
    } finally {
      requestInFlightRef.current = false;
      setRequesting(false);
    }
  }, [accessibility, preview, t]);

  const requestMicrophone = useCallback(async () => {
    if (preview || requestInFlightRef.current) return;
    requestInFlightRef.current = true;
    setRequesting(true);
    try {
      const result = isWindows
        ? await commands.openMicrophonePrivacySettings()
        : await commands.requestMicrophonePermission();
      if (result.status === "error") throw new Error(result.error);
      setMicrophoneAsked(true);
      setMicrophone("waiting");
      setPollingAttempt((attempt) => attempt + 1);
    } catch (error) {
      console.error("Failed to request microphone permission:", error);
      notify({ title: t("onboarding.permissions.errors.requestFailed") });
    } finally {
      requestInFlightRef.current = false;
      setRequesting(false);
    }
  }, [isWindows, preview, t]);

  const repairAccessibility = useCallback(
    async (onBeforeRestart?: () => void) => {
      if (preview || requestInFlightRef.current) return;
      requestInFlightRef.current = true;
      setRequesting(true);
      try {
        const result = await commands.repairAccessibilityPermission();
        if (result.status === "error") throw new Error(result.error);
        if (result.data) {
          onBeforeRestart?.();
          await relaunch();
        } else {
          setPollingAttempt((attempt) => attempt + 1);
        }
      } catch (error) {
        console.error("Failed to repair Handy permission:", error);
        notify({ title: t("onboarding.permissions.errors.requestFailed") });
      } finally {
        requestInFlightRef.current = false;
        setRequesting(false);
      }
    },
    [preview, t],
  );

  const restart = useCallback(
    async (onBeforeRestart?: () => void) => {
      if (preview || requestInFlightRef.current) return;
      requestInFlightRef.current = true;
      setRequesting(true);
      try {
        onBeforeRestart?.();
        await relaunch();
      } catch (error) {
        console.error("Failed to restart Handy:", error);
        notify({ title: t("onboarding.permissions.errors.requestFailed") });
      } finally {
        requestInFlightRef.current = false;
        setRequesting(false);
      }
    },
    [preview, t],
  );

  return {
    platform: os,
    microphone,
    accessibility,
    checked: microphone !== "checking" && accessibility !== "checking",
    needed,
    allGranted,
    requesting,
    microphoneAsked,
    requestMicrophone,
    requestAccessibility,
    recheck,
    repairAccessibility,
    restart,
  };
};
