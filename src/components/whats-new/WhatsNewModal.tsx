import React, { Suspense, lazy } from "react";
import { useTranslation } from "react-i18next";
import { Dialog } from "../ui";
import type { ReleaseNote } from "./releaseNotes";

// The Markdown renderer is large and only needed when release notes show.
const MarkdownContent = lazy(() =>
  import("./MarkdownContent").then((m) => ({ default: m.MarkdownContent })),
);

interface WhatsNewModalProps {
  note: ReleaseNote;
  open: boolean;
  onDismiss: () => void;
}

export const WhatsNewModal: React.FC<WhatsNewModalProps> = ({
  note,
  open,
  onDismiss,
}) => {
  const { t } = useTranslation();

  return (
    <Dialog
      open={open}
      title={t("whatsNew.title", { version: note.version })}
      closeLabel={t("common.close")}
      onOpenChange={(nextOpen) => {
        if (!nextOpen) onDismiss();
      }}
    >
      <Suspense fallback={null}>
        <MarkdownContent markdown={note.markdown} />
      </Suspense>
    </Dialog>
  );
};
