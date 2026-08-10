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
}
