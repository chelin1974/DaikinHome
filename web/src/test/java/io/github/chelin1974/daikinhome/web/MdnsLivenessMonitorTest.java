package io.github.chelin1974.daikinhome.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.DaikinHomeBridge;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

class MdnsLivenessMonitorTest {

  /** Records what was published instead of needing a running application context. */
  private static final class RecordingPublisher implements ApplicationEventPublisher {
    final List<Object> events = new ArrayList<>();

    @Override
    public void publishEvent(Object event) {
      events.add(event);
    }

    @Override
    public void publishEvent(ApplicationEvent event) {
      events.add(event);
    }
  }

  private static DaikinHomeBridge bridge(Path dir) {
    return new DaikinHomeBridge(
        BridgeSettings.builder()
            .apiKey("key")
            .email("user@example.com")
            .integratorToken("token")
            .baseUrl("http://127.0.0.1:9")
            .storagePath(dir.resolve("state.json").toString())
            .build());
  }

  @Test
  @DisplayName("publishes LivenessState.BROKEN so a liveness probe fails and restarts the pod")
  void publishesBrokenLiveness(@TempDir Path dir) {
    RecordingPublisher publisher = new RecordingPublisher();
    MdnsLivenessMonitor monitor = new MdnsLivenessMonitor(bridge(dir), publisher);

    monitor.markBroken("JmDNS gave up recovering its mDNS responder");

    assertThat(publisher.events).hasSize(1);
    assertThat(publisher.events.get(0)).isInstanceOf(AvailabilityChangeEvent.class);

    AvailabilityChangeEvent<?> event = (AvailabilityChangeEvent<?>) publisher.events.get(0);
    // Spring's LivenessStateHealthIndicator maps BROKEN to DOWN, which is what turns
    // /actuator/health/liveness into a 503 and gets the container restarted.
    assertThat(event.getState()).isEqualTo(LivenessState.BROKEN);
  }

  @Test
  @DisplayName("subscribes to the bridge so a real failure reaches it")
  void subscribesToTheBridge(@TempDir Path dir) {
    RecordingPublisher publisher = new RecordingPublisher();
    DaikinHomeBridge bridge = bridge(dir);
    MdnsLivenessMonitor monitor = new MdnsLivenessMonitor(bridge, publisher);

    monitor.subscribe();

    // The bridge reports no failure yet, so nothing should have been published merely by
    // wiring the listener up.
    assertThat(bridge.advertisementFailure()).isEmpty();
    assertThat(publisher.events).isEmpty();
  }
}
