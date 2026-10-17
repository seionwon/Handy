interface AccessibilityPermissionActions {
  check: () => Promise<boolean>;
  openSettings: () => Promise<
    { status: "ok"; data: null } | { status: "error"; error: string }
  >;
}

/** Request native consent without also opening a second settings window. */
export async function reuseOrRequestAccessibility({
  check,
  request,
}: {
  check: () => Promise<boolean>;
  request: AccessibilityPermissionActions["openSettings"];
}): Promise<boolean> {
  if (await check()) return true;
  const result = await request();
  if (result.status === "error") throw new Error(result.error);
  return check();
}

/** Recheck a grant that may have changed since the permission card was drawn. */
export async function reuseOrOpenAccessibility({
  check,
  openSettings,
}: AccessibilityPermissionActions): Promise<boolean> {
  return reuseOrRequestAccessibility({ check, request: openSettings });
}
