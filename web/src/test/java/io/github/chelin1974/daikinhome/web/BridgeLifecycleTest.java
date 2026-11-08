package io.github.chelin1974.daikinhome.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.DaikinHomeBridge;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeLifecycleTest {

  private static BridgeSettings settings(Path dir) {
    return BridgeSettings.builder()
        .apiKey("key")
        .email("user@example.com")
        .integratorToken("token")
        // Port 9 is the discard port: nothing answers, so start() fails rather than
        // hanging on a real network call.
        .baseUrl("http://127.0.0.1:9")
        .storagePath(dir.resolve("state.json").toString())
        .build();
  }

  @Test
  @DisplayName("starting the bridge does not block the ApplicationReadyEvent thread")
  void startBridgeReturnsImmediately(@TempDir Path dir) throws Exception {
    DaikinHomeBridge bridge = new DaikinHomeBridge(settings(dir));
    BridgeStatus status = new BridgeStatus();
    BridgeLifecycle lifecycle = new BridgeLifecycle(bridge, status);

    long startedAt = System.nanoTime();
    lifecycle.startBridge();
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

    try {
      // Run inline, this takes as long as the Daikin client's connect-and-retry cycle —
      // seconds — and Spring would report the application OUT_OF_SERVICE for all of it,
      // which keeps a Kubernetes pod out of its Service while Tomcat is already serving.
      assertThat(elapsedMillis)
          .as("startBridge must hand off to a background thread, not block the caller")
          .isLessThan(500);

      // And the work really does happen, just not on this thread.
      for (int i = 0; i < 100 && status.lastError() == null; i++) {
        TimeUnit.MILLISECONDS.sleep(100);
      }
      assertThat(status.lastError())
          .as("the background attempt should have run and recorded its failure")
          .isNotNull();
      assertThat(bridge.isRunning()).isFalse();
    } finally {
      lifecycle.shutdown();
    }
  }
}
