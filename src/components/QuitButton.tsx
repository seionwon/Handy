import { exit } from "@tauri-apps/plugin-process";
import { Power } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "./ui/Button";

export default function QuitButton({
  disabled = false,
}: {
  disabled?: boolean;
}) {
  const { t } = useTranslation();

  const handleQuit = () => {
    // Same AppHandle::exit path as the tray's Quit action, rather than closing
    // a window (which leaves Handy running) or killing the process.
    void exit(0).catch((error) =>
      console.error("Failed to quit Handy:", error),
    );
  };

  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      className="flex items-center gap-1.5"
      disabled={disabled}
      onClick={handleQuit}
    >
      <Power size={14} aria-hidden="true" />
      {t("tray.quit")}
    </Button>
  );
}
