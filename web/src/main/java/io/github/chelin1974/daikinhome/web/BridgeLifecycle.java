package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.DaikinHomeBridge;
import io.github.chelin1974.daikinhome.Failures;
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

  /**
   * Begins bringing the bridge up, without waiting for it.
   *
   * <p>Handed to the executor rather than run inline. {@code bridge.start()} reads every
   * thermostat before publishing it and then brings up a HomeKit server and an mDNS
   * responder — seconds of work on a good day, and it can block much longer if the Daikin
   * cloud is slow or JmDNS cannot reach the network. This method is called from the
   * {@link ApplicationReadyEvent} chain, and Spring does not mark the application
   * ACCEPTING_TRAFFIC until every listener has returned, so running it inline leaves
   * {@code /actuator/health/readiness} reporting OUT_OF_SERVICE while Tomcat is already
   * answering. Under Kubernetes that holds the pod out of its Service for the whole
   * window — and a hung start would hold it out for good, hiding the very endpoint that
   * would explain why.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void startBridge() {
    retries.execute(this::attemptStart);
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
      // The whole cause chain, not just the outermost message: the wrapper names the
      // operation ("could not save HomeKit pairing state to /data/...") and only the cause
      // says whether that was a full disk or an unwritable volume.
      String reason = Failures.describe(e);
      status.recoverable(reason);
      log.warn(
          "could not start the HomeKit bridge, retrying in {}s: {}",
          settings.pollInterval().toSeconds(),
          reason);
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
