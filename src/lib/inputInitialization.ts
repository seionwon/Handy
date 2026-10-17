type InitializationResult =
  | { status: "ok"; data: null }
  | { status: "error"; error: string };

interface InputCommands {
  initializeEnigo: () => Promise<InitializationResult>;
  initializeShortcuts: () => Promise<InitializationResult>;
}

/** Coalesce concurrent callers and allow retry after a reported IPC failure. */
export function createInputInitializer(commands: InputCommands) {
  let initialized = false;
  let pending: Promise<void> | undefined;
  return function initialize(): Promise<void> {
    if (initialized) return Promise.resolve();
    if (pending) return pending;
    pending = (async () => {
      for (const command of [
        commands.initializeEnigo,
        commands.initializeShortcuts,
      ]) {
        const result = await command();
        if (result.status === "error") throw new Error(result.error);
      }
      initialized = true;
    })().finally(() => {
      pending = undefined;
    });
    return pending;
  };
}
