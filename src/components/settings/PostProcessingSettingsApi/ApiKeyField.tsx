import React, { useState } from "react";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import { Input } from "../../ui/Input";

interface ApiKeyFieldProps {
  configured: boolean;
  onBlur: (value: string) => void;
  disabled: boolean;
  placeholder?: string;
  className?: string;
}

export const ApiKeyField = ({
  configured,
  onBlur,
  disabled,
  placeholder,
  className = "",
}: ApiKeyFieldProps) => {
  const { t } = useTranslation();
  const [draft, setDraft] = useState("");
  return (
    <>
      <Input
        type="password"
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={() => {
          if (draft.trim()) onBlur(draft);
          setDraft("");
        }}
        placeholder={configured ? "••••••••" : placeholder}
        variant="compact"
        disabled={disabled}
        className={`flex-1 min-w-[320px] ${className}`}
      />
      {configured && (
        <button
          type="button"
          aria-label={t("common.clear")}
          title={t("common.clear")}
          disabled={disabled}
          onClick={() => onBlur("")}
        >
          <X size={16} aria-hidden="true" />
        </button>
      )}
    </>
  );
};
