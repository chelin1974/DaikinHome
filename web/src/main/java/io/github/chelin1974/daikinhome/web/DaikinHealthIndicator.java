package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.ThermostatGateway;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports the HomeKit bridge on {@code /actuator/health}.
 *
 * <p>Being unpaired is not unhealthy — a freshly installed bridge is waiting for someone to
 * scan its setup code, which is a normal state, not a fault.
 */
@Component("daikinBridge")
public class DaikinHealthIndicator implements HealthIndicator {

  private final ThermostatGateway gateway;
  private final BridgeStatus status;

  public DaikinHealthIndicator(ThermostatGateway gateway, BridgeStatus status) {
    this.gateway = gateway;
    this.status = status;
  }

  @Override
  public Health health() {
    // A dead mDNS advertisement is as fatal as a bridge that never started: the thermostat
    // is unreachable from Apple Home either way. It is reported separately because the two
    // need different remedies - one is "wait for Daikin", the other is "restart me".
    String advertisementFailure = gateway.advertisementFailure().orElse(null);
    boolean healthy = gateway.isRunning() && advertisementFailure == null;

    Health.Builder health = healthy ? Health.up() : Health.down();

    health
        .withDetail("running", gateway.isRunning())
        .withDetail("paired", gateway.isPaired())
        .withDetail("thermostats", gateway.devices().size())
        .withDetail("homekitPort", gateway.settings().port())
        .withDetail("discoverable", advertisementFailure == null);

    if (advertisementFailure != null) {
      health.withDetail("advertisementError", advertisementFailure);
    }

    if (status.lastError() != null) {
      health.withDetail("error", status.lastError());
    }
    if (status.isPermanentlyFailed()) {
      // Distinguishes "still trying" from "will never work without a config change".
      health.withDetail("retrying", false);
    }

    return health.build();
  }
}
