package io.github.chelin1974.daikinhome.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class DaikinHealthIndicatorTest {

  @Test
  @DisplayName("is UP when the bridge is running")
  void upWhenRunning() {
    FakeGateway gateway = new FakeGateway();
    Health health = new DaikinHealthIndicator(gateway, new BridgeStatus()).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("running", true).containsEntry("thermostats", 1);
  }

  @Test
  @DisplayName("stays UP while unpaired, because waiting to be paired is not a fault")
  void unpairedIsStillHealthy() {
    FakeGateway gateway = new FakeGateway();
    gateway.paired = false;

    Health health = new DaikinHealthIndicator(gateway, new BridgeStatus()).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("paired", false);
  }

  @Test
  @DisplayName("is DOWN when the mDNS advertisement has died, even though the bridge runs")
  void deadAdvertisementIsUnhealthy() {
    FakeGateway gateway = new FakeGateway();
    gateway.running = true;
    gateway.paired = true;
    gateway.advertisementFailure = "JmDNS gave up recovering its mDNS responder";

    Health health = new DaikinHealthIndicator(gateway, new BridgeStatus()).health();

    // The whole point of the change: running and healthy are not the same thing. A bridge
    // whose responder has died serves HTTP perfectly and is invisible to Apple Home.
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsEntry("running", true)
        .containsEntry("discoverable", false)
        .containsEntry("advertisementError", "JmDNS gave up recovering its mDNS responder");
  }

  @Test
  @DisplayName("reports discoverable while the advertisement is alive")
  void liveAdvertisementIsReported() {
    Health health = new DaikinHealthIndicator(new FakeGateway(), new BridgeStatus()).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("discoverable", true);
    assertThat(health.getDetails()).doesNotContainKey("advertisementError");
  }

  @Test
  @DisplayName("is DOWN with the reason when the bridge never started")
  void downWithReason() {
    FakeGateway gateway = new FakeGateway();
    gateway.running = false;

    BridgeStatus status = new BridgeStatus();
    status.recoverable("could not reach the Daikin API");

    Health health = new DaikinHealthIndicator(gateway, status).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("error", "could not reach the Daikin API");
    // Still retrying, so no "retrying: false" marker.
    assertThat(health.getDetails()).doesNotContainKey("retrying");
  }

  @Test
  @DisplayName("marks a permanent failure as not retrying, so it is distinguishable")
  void permanentFailureIsMarked() {
    FakeGateway gateway = new FakeGateway();
    gateway.running = false;

    BridgeStatus status = new BridgeStatus();
    status.permanent("Daikin rejected the credentials");

    Health health = new DaikinHealthIndicator(gateway, status).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("retrying", false);
  }
}
