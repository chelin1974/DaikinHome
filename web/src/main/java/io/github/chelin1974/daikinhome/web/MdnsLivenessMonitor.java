package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.DaikinHomeBridge;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Turns a dead HomeKit advertisement into a failed liveness probe.
 *
 * <p>Without this the liveness probe is decorative. The bridge's mDNS responder can die —
 * a network change is enough — and from then on nothing in Apple Home can reach the
 * bridge, while the JVM, Tomcat and the REST API all carry on perfectly healthy. Both
 * {@code /actuator/health/liveness} and a Kubernetes probe pointed at it would keep
 * answering 200 forever.
 *
 * <p>Publishing {@link LivenessState#BROKEN} is Spring's own mechanism for "this process
 * is beyond helping itself": {@code /actuator/health/liveness} turns DOWN, a Kubernetes
 * liveness probe fails, and the pod is restarted. Restarting is the correct remedy here,
 * because JmDNS does not recover on its own.
 *
 * <p>Readiness is deliberately left alone. The REST API still works and is the only way to
 * read the reason, so pulling the pod out of its Service would hide the diagnosis.
 */
@Component
public class MdnsLivenessMonitor {

  private static final Logger log = LoggerFactory.getLogger(MdnsLivenessMonitor.class);

  private final DaikinHomeBridge bridge;
  private final ApplicationEventPublisher events;

  public MdnsLivenessMonitor(DaikinHomeBridge bridge, ApplicationEventPublisher events) {
    this.bridge = bridge;
    this.events = events;
  }

  @PostConstruct
  void subscribe() {
    bridge.onAdvertisementFailure(this::markBroken);
  }

  /** Visible for tests. */
  void markBroken(String reason) {
    log.error(
        "marking this instance's liveness BROKEN so it gets restarted: {}", reason);
    AvailabilityChangeEvent.publish(events, this, LivenessState.BROKEN);
  }
}
