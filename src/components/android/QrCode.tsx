import React, { useMemo } from "react";
import { generateQrMatrix } from "./qrMatrix";

interface QrCodeProps {
  value: string;
  size?: number;
  label: string;
}

export const QrCode: React.FC<QrCodeProps> = ({ value, size = 188, label }) => {
  const { viewBox, pathD } = useMemo(() => {
    const matrix = generateQrMatrix(value);
    const n = matrix.length;
    const quiet = 4;
    const total = n + quiet * 2;
    const parts: string[] = [];
    for (let r = 0; r < n; r++) {
      for (let c = 0; c < n; c++) {
        if (matrix[r][c]) {
          parts.push(`M${c + quiet},${r + quiet}h1v1h-1z`);
        }
      }
    }
    return { viewBox: `0 0 ${total} ${total}`, pathD: parts.join("") };
  }, [value]);

  return (
    <svg
      role="img"
      aria-label={label}
      width={size}
      height={size}
      viewBox={viewBox}
      shapeRendering="crispEdges"
      className="rounded-xl bg-white p-1.5 shadow-sm border border-line"
    >
      <rect width="100%" height="100%" fill="#ffffff" />
      <path d={pathD} fill="#0f0f0f" />
    </svg>
  );
};
