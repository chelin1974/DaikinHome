package io.github.chelin1974.daikinhome.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.BridgeSettings.SettingsException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;

class DaikinPropertiesTest {

  private static DaikinProperties bind(Map<String, Object> values) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(values);
    return new Binder(source).bind("daikin", DaikinProperties.class).orElseGet(DaikinProperties::new);
  }

  @Test
  @DisplayName("binds kebab-case keys the way Spring config files are written")
  void bindsKebabCase() {
    DaikinProperties properties =
        bind(
            Map.of(
                "daikin.api-key", "key",
                "daikin.email", "user@example.com",
                "daikin.integrator-token", "token",
                "daikin.poll-interval-seconds", "600",
                "daikin.homekit-port", "51830",
                "daikin.show-outdoor-temperature-sensor", "false"));

    BridgeSettings settings = properties.toBridgeSettings();

    assertThat(settings.apiKey()).isEqualTo("key");
    assertThat(settings.pollInterval()).isEqualTo(Duration.ofSeconds(600));
    assertThat(settings.port()).isEqualTo(51830);
    assertThat(settings.showOutdoorTemperatureSensor()).isFalse();
  }

  @Test
  @DisplayName("applies the same clamping the CLI gets")
  void clampsPollInterval() {
    DaikinProperties properties =
        bind(
            Map.of(
                "daikin.api-key", "key",
                "daikin.email", "user@example.com",
                "daikin.integrator-token", "token",
                "daikin.poll-interval-seconds", "5"));

    assertThat(properties.toBridgeSettings().pollInterval())
        .isEqualTo(BridgeSettings.MIN_POLL_INTERVAL);
  }

  @Test
  @DisplayName("fails with the same message as the CLI when credentials are missing")
  void rejectsMissingCredentials() {
    assertThatThrownBy(() -> bind(Map.of("daikin.email", "user@example.com")).toBridgeSettings())
        .isInstanceOf(SettingsException.class)
        .hasMessageContaining("apiKey")
        .hasMessageContaining("integratorToken");
  }

  @Test
  @DisplayName("defaults the state file to the working directory")
  void defaultsStoragePath() {
    DaikinProperties properties =
        bind(
            Map.of(
                "daikin.api-key", "key",
                "daikin.email", "user@example.com",
                "daikin.integrator-token", "token"));

    assertThat(properties.toBridgeSettings().storagePath())
        .isEqualTo(Path.of(BridgeSettings.DEFAULT_STATE_FILE));
  }
}
