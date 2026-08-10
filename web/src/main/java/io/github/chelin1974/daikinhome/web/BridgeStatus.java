package io.github.chelin1974.daikinhome.web;

import org.springframework.stereotype.Component;

/**
 * Why the bridge isn't running, if it isn't.
 *
 * <p>Separate from {@link BridgeLifecycle} so the controllers and the health check can read
 * it without depending on the thing that starts HomeKit servers — which keeps them testable
 * without mDNS.
 */
@Component
public class BridgeStatus {

  private volatile String lastError;
  private volatile boolean permanentlyFailed;

  /** Why the bridge is not running, or null when it is. */
  public String lastError() {
    return lastError;
  }

  /** True when the failure will not fix itself — bad credentials, typically. */
  public boolean isPermanentlyFailed() {
    return permanentlyFailed;
  }

  void clear() {
    this.lastError = null;
    this.permanentlyFailed = false;
  }

  void recoverable(String message) {
    this.lastError = message;
  }

  void permanent(String message) {
    this.lastError = message;
    this.permanentlyFailed = true;
  }
}
