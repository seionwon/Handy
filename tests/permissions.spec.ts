import { test, expect } from "@playwright/test";

const fixture = "/tests/fixtures/permissions.html";

// This fixture mocks every native command. It never asks for OS permissions,
// installs input hooks, or sends synthetic keyboard/mouse input.
test("pre-existing grants complete without initializing input during onboarding", async ({
  page,
}) => {
  await page.goto(fixture + "?granted");
  await page.waitForFunction("Boolean(window.permissionHarness)");
  await expect
    .poll(() => page.evaluate("window.permissionHarness.completed"))
    .toBe(1);
  const calls: string[] = await page.evaluate("window.permissionHarness.calls");
  expect(calls.some((command) => command.startsWith("initialize_"))).toBe(
    false,
  );
});

test("a listed but denied app keeps an actionable settings button", async ({
  page,
}) => {
  await page.goto(fixture);
  await page.getByRole("button", { name: "Open System Settings" }).click();
  await expect(page.getByText(/Keep the existing Handy entry/)).toBeVisible();
  await page.getByRole("button", { name: "Open System Settings" }).click();
  await expect
    .poll(() =>
      page.evaluate(
        "window.permissionHarness.calls.filter(c => c === 'open_accessibility_settings').length",
      ),
    )
    .toBe(2);
  const calls: string[] = await page.evaluate("window.permissionHarness.calls");
  expect(
    calls.filter((command) =>
      command.endsWith("request_accessibility_permission"),
    ),
  ).toEqual([]);
  await page.evaluate("window.permissionHarness.accessibility = true");
  await expect
    .poll(() => page.evaluate("window.permissionHarness.completed"))
    .toBe(1);
});

test("slow permission checks do not accumulate overlapping polls", async ({
  page,
}) => {
  // StrictMode intentionally starts and cancels its initial check once.
  // After those first checks settle, later polls must remain sequential.
  await page.goto(fixture + "?delay=1200");
  await expect(
    page.getByRole("button", { name: "Open System Settings" }),
  ).toBeVisible();
  await page.evaluate(
    "window.permissionHarness.maxInFlight = window.permissionHarness.inFlight",
  );
  await page.waitForTimeout(3600);
  expect(await page.evaluate("window.permissionHarness.maxInFlight")).toBe(1);
});

test("unmount cancels completion from an outstanding permission check", async ({
  page,
}) => {
  await page.goto(fixture + "?granted&delay=1000");
  await page.waitForFunction("Boolean(window.permissionHarness)");
  await expect
    .poll(() => page.evaluate("window.permissionHarness.inFlight"))
    .toBeGreaterThan(0);
  await page.evaluate("window.permissionHarness.unmount()");
  await page.waitForTimeout(1500);
  expect(await page.evaluate("window.permissionHarness.completed")).toBe(0);
});

test("microphone permission uses the safe native request command and remains retryable", async ({
  page,
}) => {
  await page.goto(fixture + "?granted&microphoneDenied");
  await page.getByRole("button", { name: "Grant Permission" }).click();
  await page.getByRole("button", { name: "Open System Settings" }).click();
  await expect
    .poll(() =>
      page.evaluate(
        "window.permissionHarness.calls.filter(c => c === 'request_microphone_permission').length",
      ),
    )
    .toBe(2);
});

test("permission previews remain inert", async ({ page }) => {
  await page.goto(fixture + "?preview");
  await page.getByRole("button", { name: "Open System Settings" }).click();
  expect(await page.evaluate("window.permissionHarness.calls")).toEqual([]);
  expect(await page.evaluate("window.permissionHarness.completed")).toBe(0);
});

for (const view of ["onboarding", "legacy"]) {
  test(`${view} reuses permission enabled since the last UI check`, async ({
    page,
  }) => {
    await page.goto(fixture + (view === "legacy" ? "?legacy" : ""));
    const button = page.getByRole("button", { name: "Open System Settings" });
    await expect(button).toBeVisible();
    await page.evaluate("window.permissionHarness.accessibility = true");
    await button.click();
    await expect(button).toHaveCount(0);
    const calls: string[] = await page.evaluate(
      "window.permissionHarness.calls",
    );
    expect(calls).not.toContain("open_accessibility_settings");
  });

  test(`${view} opens one permission pane without a second system prompt`, async ({
    page,
  }) => {
    await page.goto(
      fixture + `?requestDelay=500${view === "legacy" ? "&legacy" : ""}`,
    );
    const button = page.getByRole("button", { name: "Open System Settings" });
    await expect(button).toBeVisible();
    await button.evaluate((element) => {
      (element as HTMLButtonElement).click();
      (element as HTMLButtonElement).click();
    });
    await expect(button).toBeDisabled();
    await expect(button).toBeEnabled();
    const calls: string[] = await page.evaluate(
      "window.permissionHarness.calls",
    );
    expect(
      calls.filter((command) => command === "open_accessibility_settings"),
    ).toHaveLength(1);
    expect(
      calls.some((command) =>
        command.endsWith("request_accessibility_permission"),
      ),
    ).toBe(false);
  });
}

test("legacy card stays hidden while an existing grant is checked", async ({
  page,
}) => {
  await page.goto(fixture + "?legacy&granted&delay=1000");
  await page.waitForFunction("Boolean(window.permissionHarness)");
  await expect
    .poll(() => page.evaluate("window.permissionHarness.inFlight"))
    .toBeGreaterThan(0);
  await expect(
    page.getByRole("button", { name: "Open System Settings" }),
  ).toHaveCount(0);
  await expect
    .poll(() => page.evaluate("window.permissionHarness.inFlight"))
    .toBe(0);
  await expect(
    page.getByRole("button", { name: "Open System Settings" }),
  ).toHaveCount(0);
});

test("legacy card refreshes the existing grant when Handy regains focus", async ({
  page,
}) => {
  await page.goto(fixture + "?legacy");
  const button = page.getByRole("button", { name: "Open System Settings" });
  await expect(button).toBeVisible();
  await page.evaluate(() => {
    (
      window as unknown as { permissionHarness: { accessibility: boolean } }
    ).permissionHarness.accessibility = true;
    window.dispatchEvent(new Event("focus"));
  });
  await expect(button).toHaveCount(0);
  expect(await page.evaluate("window.permissionHarness.calls")).not.toContain(
    "open_accessibility_settings",
  );
});

test("Check Again reads the current grant without reopening settings", async ({
  page,
}) => {
  await page.goto(fixture);
  await expect(page.getByRole("button", { name: "Check Again" })).toBeVisible();
  await page.evaluate("window.permissionHarness.accessibility = true");
  await page.getByRole("button", { name: "Check Again" }).click();
  await expect
    .poll(() => page.evaluate("window.permissionHarness.completed"))
    .toBe(1);
  expect(await page.evaluate("window.permissionHarness.calls")).not.toContain(
    "open_accessibility_settings",
  );
});

test("permission recovery uses one normal restart and never initializes input", async ({
  page,
}) => {
  await page.goto(fixture);
  await page.getByRole("button", { name: "Open System Settings" }).click();
  const restart = page.getByRole("button", { name: "Restart Handy" });
  await expect(restart).toBeVisible();
  await restart.evaluate((element) => {
    (element as HTMLButtonElement).click();
    (element as HTMLButtonElement).click();
  });
  await expect
    .poll(() =>
      page.evaluate(
        "window.permissionHarness.calls.filter(c => c === 'plugin:process|restart').length",
      ),
    )
    .toBe(1);
  expect(
    await page.evaluate(
      "window.permissionHarness.calls.some(c => c.startsWith('initialize_'))",
    ),
  ).toBe(false);
});

test("permission recovery stays reachable at Handy's minimum window size", async ({
  page,
}) => {
  await page.setViewportSize({ width: 680, height: 570 });
  await page.goto(fixture);
  await page.getByRole("button", { name: "Open System Settings" }).click();
  await page.mouse.move(340, 480);
  await page.mouse.wheel(0, 1000);
  await expect(
    page.getByRole("button", { name: "Restart Handy" }),
  ).toBeInViewport({ ratio: 1 });
  await page.mouse.wheel(0, -1000);
  await expect(
    page.getByRole("button", { name: "Quit", exact: true }),
  ).toBeInViewport({ ratio: 1 });
});
