package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.daikinhome.BridgeSettings.SettingsException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeSettingsTest {

  private static BridgeSettings.Builder credentials() {
    return BridgeSettings.builder()
        .apiKey("key")
        .email("user@example.com")
        .integratorToken("token");
  }

  @Test
  @DisplayName("names every missing credential in one message")
  void reportsAllMissingCredentials() {
    SettingsException error =
        assertThrows(SettingsException.class, () -> BridgeSettings.builder().build());

    assertTrue(error.getMessage().contains("apiKey"), error.getMessage());
    assertTrue(error.getMessage().contains("email"), error.getMessage());
    assertTrue(error.getMessage().contains("integratorToken"), error.getMessage());
  }

  @Test
  @DisplayName("treats blank strings as missing")
  void blankIsMissing() {
    assertThrows(SettingsException.class, () -> credentials().apiKey("   ").build());
  }

  @Test
  @DisplayName("trims credentials, since app copy-paste picks up whitespace")
  void trimsCredentials() {
    assertEquals("key", credentials().apiKey("  key  ").build().apiKey());
  }

  @Test
  @DisplayName("defaults the poll interval to Daikin's documented floor")
  void defaultsPollInterval() {
    assertEquals(BridgeSettings.MIN_POLL_INTERVAL, credentials().build().pollInterval());
  }

  @Test
  @DisplayName("raises a too-fast poll interval back to the floor")
  void clampsPollInterval() {
    assertEquals(
        BridgeSettings.MIN_POLL_INTERVAL,
        credentials().pollIntervalSeconds(30).build().pollInterval());
    assertTrue(BridgeSettings.pollIntervalWasClamped(30));
  }

  @Test
  @DisplayName("keeps a slower poll interval")
  void keepsSlowerPollInterval() {
    assertEquals(
        Duration.ofSeconds(600), credentials().pollIntervalSeconds(600).build().pollInterval());
    assertFalse(BridgeSettings.pollIntervalWasClamped(600));
    assertFalse(BridgeSettings.pollIntervalWasClamped(null));
  }

  @Test
  @DisplayName("enforces a floor on the request timeout")
  void clampsTimeout() {
    assertEquals(
        BridgeSettings.MIN_REQUEST_TIMEOUT,
        credentials().requestTimeoutSeconds(1).build().requestTimeout());
  }

  @Test
  @DisplayName("defaults the optional accessories on")
  void defaultsAccessories() {
    BridgeSettings settings = credentials().build();

    assertTrue(settings.showIndoorHumidity());
    assertTrue(settings.showOutdoorTemperatureSensor());
    assertEquals(BridgeSettings.DEFAULT_BRIDGE_NAME, settings.bridgeName());
    assertEquals(BridgeSettings.DEFAULT_PORT, settings.port());
  }

  @Test
  @DisplayName("lets the opt-out flags turn accessories off")
  void honoursOptOut() {
    BridgeSettings settings =
        credentials().showIndoorHumidity(false).showOutdoorTemperatureSensor(false).build();

    assertFalse(settings.showIndoorHumidity());
    assertFalse(settings.showOutdoorTemperatureSensor());
  }

  @Test
  @DisplayName("leaves the base URL unset so the client uses its own default")
  void baseUrlIsOptional() {
    assertNull(credentials().build().baseUrl());
    assertEquals("http://example", credentials().baseUrl("http://example").build().baseUrl());
  }

  @Test
  @DisplayName("puts the state file in the storage directory by default")
  void storagePathDefaultsToDirectory(@TempDir Path dir) {
    assertEquals(
        dir.resolve(BridgeSettings.DEFAULT_STATE_FILE),
        credentials().storageDirectory(dir).build().storagePath());
  }

  @Test
  @DisplayName("an explicit storage path wins over the directory")
  void explicitStoragePathWins(@TempDir Path dir) {
    assertEquals(
        Path.of("/var/lib/daikin/state.json"),
        credentials()
            .storageDirectory(dir)
            .storagePath("/var/lib/daikin/state.json")
            .build()
            .storagePath());
  }

  // ------------------------------------------------------------------ backends

  private static BridgeSettings.Builder skyport() {
    return BridgeSettings.builder().backend("skyport").email("user@example.com").password("pw");
  }

  @Test
  @DisplayName("defaults to the Open API, so existing configs keep working")
  void defaultsToOpenApi() {
    assertEquals(BridgeSettings.Backend.OPEN_API, credentials().build().backend());
  }

  @Test
  @DisplayName("skyport needs only email and password")
  void skyportCredentials() {
    BridgeSettings settings = skyport().build();

    assertEquals(BridgeSettings.Backend.SKYPORT, settings.backend());
    assertEquals("pw", settings.password());
    assertNull(settings.apiKey());
    assertNull(settings.integratorToken());
  }

  @Test
  @DisplayName("a password without an API key selects skyport")
  void infersSkyport() {
    BridgeSettings settings =
        BridgeSettings.builder().email("user@example.com").password("pw").build();
    assertEquals(BridgeSettings.Backend.SKYPORT, settings.backend());
  }

  @Test
  @DisplayName("an explicit backend wins over inference")
  void explicitBackendWins() {
    assertThrows(
        SettingsException.class,
        () -> credentials().backend("skyport").build(),
        "skyport without a password is an error even with Open API credentials present");
    assertEquals(
        BridgeSettings.Backend.OPEN_API, credentials().password("pw").backend("openapi").build().backend());
  }

  @Test
  @DisplayName("accepts the obvious spellings of each backend")
  void parsesBackendNames() {
    for (String name : new String[] {"skyport", "SkyportHome", "SKYPORT", "skyport-cloud"}) {
      assertEquals(BridgeSettings.Backend.SKYPORT, BridgeSettings.Backend.parse(name), name);
    }
    for (String name : new String[] {"openapi", "open-api", "OPEN_API", "integrator"}) {
      assertEquals(BridgeSettings.Backend.OPEN_API, BridgeSettings.Backend.parse(name), name);
    }
  }

  @Test
  @DisplayName("an unknown backend is named in the error, with the valid choices")
  void rejectsUnknownBackend() {
    SettingsException error =
        assertThrows(SettingsException.class, () -> skyport().backend("homebridge").build());
    assertTrue(error.getMessage().contains("homebridge"), error.getMessage());
    assertTrue(error.getMessage().contains("skyport"), error.getMessage());
  }

  @Test
  @DisplayName("skyport names its own missing settings, not the Open API's")
  void skyportMissingSettings() {
    SettingsException error =
        assertThrows(SettingsException.class, () -> BridgeSettings.builder().backend("skyport").build());

    assertTrue(error.getMessage().contains("email"), error.getMessage());
    assertTrue(error.getMessage().contains("password"), error.getMessage());
    assertFalse(error.getMessage().contains("integratorToken"), error.getMessage());
  }

  @Test
  @DisplayName("the password is kept exactly as typed")
  void passwordNotTrimmed() {
    assertEquals(" pw ", skyport().password(" pw ").build().password());
  }

  @Test
  @DisplayName("toString never includes a credential")
  void toStringRedacts() {
    String text = skyport().password("hunter2").build().toString();
    assertFalse(text.contains("hunter2"), text);

    String openApi = credentials().apiKey("secret-key").integratorToken("secret-token").build().toString();
    assertFalse(openApi.contains("secret-key"), openApi);
    assertFalse(openApi.contains("secret-token"), openApi);
  }

  @Test
  @DisplayName("metrics go to Prometheus' OTLP endpoint, or an explicit one, every 60 s by default")
  void metricsEndpoint() {
    assertNull(credentials().build().otlpEndpoint());
    BridgeSettings prometheus = credentials().prometheusUrl("http://localhost:9090/").build();
    assertEquals("http://localhost:9090/api/v1/otlp/v1/metrics", prometheus.otlpEndpoint().toString());
    assertEquals(Duration.ofSeconds(60), prometheus.metricsInterval());
    BridgeSettings collector = credentials()
        .prometheusUrl("http://localhost:9090").otlpEndpoint("http://collector:4318/v1/metrics")
        .metricsIntervalSeconds(3).build();
    assertEquals("http://collector:4318/v1/metrics", collector.otlpEndpoint().toString());
    assertEquals(Duration.ofSeconds(10), collector.metricsInterval(), "raised to the minimum");
    assertThrows(SettingsException.class, () -> credentials().prometheusUrl("localhost:9090").build());
  }
}
