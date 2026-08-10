/**
 * Serialises outbound API calls and keeps a floor between them.
 *
 * Daikin documents a polling floor of one request per thermostat every three
 * minutes; the platform enforces that separately. This queue exists for the
 * other half of the problem — HomeKit fires several characteristic writes at
 * once (mode, then target temperature, then a threshold), and Home app refreshes
 * hit every accessory in the same tick. Without serialisation those go out in
 * parallel and trip the API's own rate limiting.
 */
export class RequestQueue {
  private tail: Promise<unknown> = Promise.resolve();
  private lastStartedAt = 0;
  private pausedUntil = 0;

  constructor(private readonly minIntervalMs: number) {}

  /** Runs `task` after every previously queued task, respecting the floor. */
  run<T>(task: () => Promise<T>): Promise<T> {
    const result = this.tail.then(async () => {
      await this.waitForSlot();
      this.lastStartedAt = Date.now();
      return task();
    });

    // Keep the chain alive even when a task rejects, otherwise one failed
    // request would reject every request queued behind it.
    this.tail = result.catch(() => undefined);
    return result;
  }

  /**
   * Holds every queued request for `ms`, used when the API answers 429 with a
   * Retry-After. Extends an existing pause, never shortens it.
   */
  pauseFor(ms: number): void {
    this.pausedUntil = Math.max(this.pausedUntil, Date.now() + ms);
  }

  private async waitForSlot(): Promise<void> {
    for (;;) {
      const now = Date.now();
      const waitForPause = this.pausedUntil - now;
      const waitForFloor = this.lastStartedAt + this.minIntervalMs - now;
      const wait = Math.max(waitForPause, waitForFloor);
      if (wait <= 0) {
        return;
      }
      await sleep(wait);
    }
  }
}

export function sleep(ms: number): Promise<void> {
  // Deliberately *not* unref'd. These waits are short (a rate-limit pause or a
  // retry backoff) and something is always awaiting them. Unref'ing lets Node
  // exit mid-backoff when nothing else holds the event loop, which silently
  // abandons the retry and — in the probe CLI — exits 0 on a failed run.
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}
