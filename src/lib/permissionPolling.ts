export interface PermissionSnapshot {
  accessibility: boolean;
  microphone: boolean;
}

interface PermissionPollingOptions {
  check: () => Promise<PermissionSnapshot>;
  onStatus: (status: PermissionSnapshot) => void;
  onComplete: () => void;
  onError: (error: unknown, consecutiveErrors: number) => void;
  intervalMs?: number;
  completionDelayMs?: number;
}

/** Read-only, sequential permission polling with cancellation of late results. */
export function pollPermissions({
  check,
  onStatus,
  onComplete,
  onError,
  intervalMs = 1000,
  completionDelayMs = 300,
}: PermissionPollingOptions): () => void {
  let cancelled = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let errors = 0;
  const poll = async () => {
    try {
      const status = await check();
      if (cancelled) return;
      errors = 0;
      onStatus(status);
      if (status.accessibility && status.microphone) {
        timer = setTimeout(() => {
          if (!cancelled) onComplete();
        }, completionDelayMs);
        return;
      }
    } catch (error) {
      if (cancelled) return;
      onError(error, ++errors);
      if (errors >= 3) return;
    }
    timer = setTimeout(poll, intervalMs);
  };
  void poll();
  return () => {
    cancelled = true;
    clearTimeout(timer);
  };
}
