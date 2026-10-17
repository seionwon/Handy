import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { type } from "@tauri-apps/plugin-os";
import { checkAccessibilityPermission } from "tauri-plugin-macos-permissions-api";
import { commands } from "@/bindings";
import { reuseOrOpenAccessibility } from "@/lib/accessibilityPermission";

const AccessibilityPermissions: React.FC = () => {
  const { t } = useTranslation();
  // Do not show a request button before reading the existing grant.
  const [hasAccessibility, setHasAccessibility] = useState<boolean | null>(
    null,
  );
  const [requesting, setRequesting] = useState(false);
  const requestInFlightRef = useRef(false);
  const mountedRef = useRef(false);
  const checkGenerationRef = useRef(0);
  const isMacOS = type() === "macos";

  useEffect(() => {
    if (!isMacOS) return;
    mountedRef.current = true;
    const check = async () => {
      const generation = ++checkGenerationRef.current;
      try {
        const granted = await checkAccessibilityPermission();
        if (mountedRef.current && generation === checkGenerationRef.current) {
          setHasAccessibility(granted);
        }
      } catch (error) {
        console.error("Failed to check accessibility permission:", error);
        if (mountedRef.current && generation === checkGenerationRef.current) {
          setHasAccessibility(false);
        }
      }
    };
    void check();
    // System Settings may have changed the grant while Handy was unfocused.
    window.addEventListener("focus", check);
    return () => {
      mountedRef.current = false;
      checkGenerationRef.current++;
      window.removeEventListener("focus", check);
    };
  }, [isMacOS]);

  const handleButtonClick = async () => {
    if (requestInFlightRef.current) return;
    requestInFlightRef.current = true;
    setRequesting(true);
    const generation = ++checkGenerationRef.current;
    try {
      const granted = await reuseOrOpenAccessibility({
        check: checkAccessibilityPermission,
        openSettings: commands.openAccessibilitySettings,
      });
      if (mountedRef.current && generation === checkGenerationRef.current) {
        setHasAccessibility(granted);
      }
    } catch (error) {
      console.error("Error requesting permissions:", error);
    } finally {
      requestInFlightRef.current = false;
      if (mountedRef.current) setRequesting(false);
    }
  };

  if (!isMacOS || hasAccessibility !== false) return null;

  return (
    <div className="p-4 w-full rounded-lg border border-mid-gray">
      <div className="flex justify-between items-center gap-2">
        <p className="text-sm font-medium">
          {t("accessibility.permissionsDescription")}
        </p>
        <button
          onClick={handleButtonClick}
          disabled={requesting}
          className="min-h-10 px-2 py-1 text-sm font-semibold bg-mid-gray/10 border border-mid-gray/80 hover:bg-logo-primary/10 rounded cursor-pointer hover:border-logo-primary"
        >
          {t("accessibility.openSettings")}
        </button>
      </div>
    </div>
  );
};

export default AccessibilityPermissions;
