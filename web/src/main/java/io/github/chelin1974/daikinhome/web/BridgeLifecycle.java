package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.DaikinHomeBridge;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Starts the HomeKit bridge once the web application is up, and keeps trying if it can't.
 *
 * <p>Deliberately does not fail application startup when Daikin is unreachable. A service
 * whose health endpoint is unreachable because its dependency is down is a service you
 * cannot diagnose — better to come up, report DOWN on {@code /actuator/health}, and retry in
 * the background.
 *
 * <p>Bad credentials are the exception: they will never start working on their own, so the
 * retry loop stops and the health check says so.
 */
@Component
public class BridgeLifecycle {

  private static final Logger log = LoggerFactory.getLogger(BridgeLifecycle.class);

  private final DaikinHomeBridge bridge;
  private final BridgeStatus status;
  private final BridgeSettings settings;
  private final ScheduledExecutorService retries =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "daikin-bridge-start");
            thread.setDaemon(true);
            return thread;
          });

  public BridgeLifecycle(DaikinHomeBridge bridge, BridgeStatus status) {
    this.bridge = bridge;
    this.status = status;
    this.settings = bridge.settings();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void startBridge() {
    attemptStart();
  }

  private void attemptStart() {
    if (bridge.isRunning() || status.isPermanentlyFailed()) {
      return;
    }

    try {
      bridge.start();

      if (bridge.isRunning()) {
        status.clear();
      } else {
        // Started cleanly but found no thermostats. Retry: the account may simply not
        // have finished provisioning yet.
        status.recoverable("no thermostats returned by the Daikin account");
        scheduleRetry();
      }
    } catch (DaikinAuthException e) {
      status.permanent(e.getMessage());
      log.error("not retrying: {}", e.getMessage());
    } catch (Exception e) {
      status.recoverable(e.getMessage());
      log.warn(
          "could not start the HomeKit bridge, retrying in {}s: {}",
          settings.pollInterval().toSeconds(),
          e.getMessage());
      scheduleRetry();
    }
  }

  private void scheduleRetry() {
    retries.schedule(this::attemptStart, settings.pollInterval().toSeconds(), TimeUnit.SECONDS);
  }

  @PreDestroy
  public void shutdown() {
    retries.shutdownNow();
    bridge.stop();
  }
}
