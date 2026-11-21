import React, { useEffect, useRef } from "react";
import { Trans, useTranslation } from "react-i18next";
import { Keyboard, Mic } from "lucide-react";
import { Button } from "../../ui/Button";
import { TextLink } from "../../ui/TextLink";
import { DoneMark } from "../../shared/DoneMark";
import QuitButton from "../../QuitButton";
import { OnboardingShell, ScreenHeader, ScreenMain } from "../OnboardingShell";
import { useOnboardingStore } from "../onboardingStore";
import type { Permissions as PermissionsState } from "../usePermissions";

interface PermissionRowProps {
  icon: React.ReactNode;
  title: string;
  why: string;
  status: PermissionsState["microphone"];
  action: string;
  disabled: boolean;
  /** Additional guidance shown while waiting for OS approval. */
  hint?: string;
  onRequest: () => void;
}

/** One permission row; keeps an actionable settings button while waiting. */
const PermissionRow: React.FC<PermissionRowProps> = ({
  icon,
  title,
  why,
  status,
  action,
  disabled,
  hint,
  onRequest,
}) => {
  const { t } = useTranslation();
  const waiting = status === "waiting";
  return (
    <div className="grid grid-cols-[46px_1fr_minmax(112px,auto)] items-center gap-[18px] py-[18px] [&+&]:border-t [&+&]:border-line">
      {/* A label, not a key: neutral, and "Allowed" alone shows the grant. */}
      <span className="grid size-[46px] place-items-center rounded-[11px] bg-wash text-ink-2">
        {icon}
      </span>
      <div>
        <b className="block font-display text-row font-[650] leading-[1.3]">
          {title}
        </b>
        <p className="mt-[2px] mb-0 text-small leading-[1.45] text-pretty text-ink-2">
          {why}
        </p>
        {waiting && hint && (
          <p className="mt-[4px] mb-0 text-small leading-[1.45] text-pretty text-text">
            {hint}
          </p>
        )}
      </div>
      {status === "granted" ? (
        <span className="inline-flex items-center justify-self-end gap-[6px] text-small font-semibold text-moss">
          <DoneMark />
          {t("onboarding.permissions.allowed")}
        </span>
      ) : (
        <Button
          variant="primary"
          size="md"
          disabled={disabled}
          onClick={onRequest}
          className="justify-self-end"
        >
          {action}
        </Button>
      )}
    </div>
  );
};

interface PermissionsProps {
  permissions: PermissionsState;
  /** In the flow: its place and Back. Standalone (a returning user): neither. */
  progress?: { index: number; count: number };
  /** Inert preview mode (no native requests or restarts). */
  preview?: boolean;
  onBack?: () => void;
  onContinue: () => void;
}

/** Screen 2: microphone, plus Accessibility on macOS, with explicit recovery. */
export const Permissions: React.FC<PermissionsProps> = ({
  permissions,
  progress,
  preview: previewProp,
  onBack,
  onContinue,
}) => {
  const { t } = useTranslation();
  const store = useOnboardingStore();
  const preview = previewProp ?? store.preview;
  const isMac = permissions.platform === "macos";
  const initialRequestRef = useRef(false);

  // Request macOS Accessibility consent once when this screen opens with a
  // missing grant, without simultaneously opening a duplicate settings pane.
  useEffect(() => {
    if (
      preview ||
      !isMac ||
      permissions.accessibility !== "needed" ||
      initialRequestRef.current
    )
      return;
    initialRequestRef.current = true;
    void permissions.requestAccessibility();
  }, [isMac, permissions, preview]);

  const markWelcomeBack = () => {
    if (progress) store.set({ step: "permissions", welcomeBack: true });
  };

  return (
    <OnboardingShell
      progress={progress}
      pill={<QuitButton disabled={preview} />}
      onBack={onBack}
      hint={
        permissions.allGranted
          ? ""
          : t(
              isMac
                ? "onboarding.permissions.hintBoth"
                : "onboarding.permissions.hintMicrophone",
            )
      }
      primary={
        <Button
          variant="primary"
          size="lg"
          disabled={!permissions.allGranted}
          onClick={() => {
            if (store.welcomeBack) store.set({ welcomeBack: false });
            onContinue();
          }}
        >
          {t("onboarding.shell.continue")}
        </Button>
      }
    >
      <ScreenMain
        header={
          <ScreenHeader
            above={
              store.welcomeBack && (
                <div className="flex animate-rise items-center justify-center gap-[10px] text-small font-semibold text-moss">
                  <DoneMark />
                  {t("onboarding.permissions.welcomeBack")}
                </div>
              )
            }
            title={t(
              isMac
                ? "onboarding.permissions.titleMac"
                : "onboarding.permissions.titleWindows",
            )}
            sub={t(
              isMac
                ? "onboarding.permissions.subtitleMac"
                : "onboarding.permissions.subtitleWindows",
            )}
          />
        }
      >
        <div className="flex w-full flex-col rounded-panel border border-line-strong px-[22px] py-[4px] text-start">
          <PermissionRow
            icon={<Mic size={20} aria-hidden="true" />}
            title={t("onboarding.permissions.microphone.name")}
            why={t("onboarding.permissions.microphone.why")}
            status={permissions.microphone}
            disabled={permissions.requesting}
            action={t(
              !isMac ||
                permissions.microphone === "waiting" ||
                permissions.microphoneAsked
                ? "onboarding.permissions.openSettings"
                : "onboarding.permissions.allow",
            )}
            hint={t(
              isMac
                ? "onboarding.permissions.turnOnMac"
                : "onboarding.permissions.turnOnWindows",
            )}
            onRequest={() => void permissions.requestMicrophone()}
          />
          {isMac && (
            <PermissionRow
              icon={<Keyboard size={20} aria-hidden="true" />}
              title={t("onboarding.permissions.accessibility.name")}
              why={t("onboarding.permissions.accessibility.why")}
              status={permissions.accessibility}
              disabled={permissions.requesting}
              action={t(
                permissions.accessibility === "waiting"
                  ? "onboarding.permissions.openSettings"
                  : "onboarding.permissions.allow",
              )}
              hint={t("onboarding.permissions.accessibility.existingEntryHint")}
              onRequest={() => void permissions.requestAccessibility()}
            />
          )}
        </div>

        {!preview && (
          <div className="mt-[16px] flex flex-wrap items-center justify-center gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={permissions.requesting}
              onClick={permissions.recheck}
            >
              {t("onboarding.permissions.recheck")}
            </Button>
            {isMac && permissions.accessibility === "waiting" && (
              <>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={permissions.requesting}
                  title={t("onboarding.permissions.repairHint")}
                  onClick={() =>
                    void permissions.repairAccessibility(markWelcomeBack)
                  }
                >
                  {t("onboarding.permissions.repair")}
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={permissions.requesting}
                  onClick={() => void permissions.restart(markWelcomeBack)}
                >
                  {t("onboarding.permissions.restart")}
                </Button>
              </>
            )}
          </div>
        )}

        {!preview && isMac && permissions.accessibility === "waiting" && (
          <p className="mt-[10px] mb-0 text-small text-ink-3 text-pretty">
            {t("onboarding.permissions.repairHint")}
          </p>
        )}

        {isMac && permissions.accessibility === "granted" && (
          <p className="mt-[14px] mb-0 text-small text-ink-3">
            <Trans
              i18nKey="onboarding.permissions.relaunch"
              components={{
                relaunch: (
                  <TextLink
                    onClick={() => void permissions.restart(markWelcomeBack)}
                  />
                ),
              }}
            />
          </p>
        )}
      </ScreenMain>
    </OnboardingShell>
  );
};
