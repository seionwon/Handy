interface AccessibilityPermissionActions {
  check: () => Promise<boolean>;
  openSettings: () => Promise<
    { status: "ok"; data: null } | { status: "error"; error: string }
  >;
}

/** Recheck a grant that may have changed since the permission card was drawn. */
export async function reuseOrOpenAccessibility({
  check,
  openSettings,
}: AccessibilityPermissionActions): Promise<boolean> {
  if (await check()) return true;
  const result = await openSettings();
  if (result.status === "error") throw new Error(result.error);
  return check();
}
