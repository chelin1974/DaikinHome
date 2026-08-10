package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.daikinhome.Config.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

  private static Config.Raw credentials() {
    Config.Raw raw = new Config.Raw();
    raw.apiKey = "key";
    raw.email = "user@example.com";
    raw.integratorToken = "token";
    return raw;
  }

  @Test
  @DisplayName("names every missing credential in one message")
  void reportsAllMissingCredentials() {
    ConfigException error =
        assertThrows(ConfigException.class, () -> Config.fromRaw(new Config.Raw(), null));

    assertTrue(error.getMessage().contains("apiKey"), error.getMessage());
    assertTrue(error.getMessage().contains("email"), error.getMessage());
    assertTrue(error.getMessage().contains("integratorToken"), error.getMessage());
  }

  @Test
  @DisplayName("treats blank strings as missing")
  void blankIsMissing() {
    Config.Raw raw = credentials();
    raw.apiKey = "   ";
    assertThrows(ConfigException.class, () -> Config.fromRaw(raw, null));
  }

  @Test
  @DisplayName("trims credentials, since app copy-paste picks up whitespace")
  void trimsCredentials() {
    Config.Raw raw = credentials();
    raw.apiKey = "  key  ";
    assertEquals("key", Config.fromRaw(raw, null).apiKey());
  }

  @Test
  @DisplayName("defaults the poll interval to Daikin's documented floor")
  void defaultsPollInterval() {
    assertEquals(Config.MIN_POLL_INTERVAL, Config.fromRaw(credentials(), null).pollInterval());
  }

  @Test
  @DisplayName("raises a too-fast poll interval back to the floor")
  void clampsPollInterval() {
    Config.Raw raw = credentials();
    raw.pollIntervalSeconds = 30;

    assertEquals(Config.MIN_POLL_INTERVAL, Config.fromRaw(raw, null).pollInterval());
    assertTrue(Config.pollIntervalWasClamped(30));
  }

  @Test
  @DisplayName("keeps a slower poll interval")
  void keepsSlowerPollInterval() {
    Config.Raw raw = credentials();
    raw.pollIntervalSeconds = 600;

    assertEquals(Duration.ofSeconds(600), Config.fromRaw(raw, null).pollInterval());
    assertFalse(Config.pollIntervalWasClamped(600));
  }

  @Test
  @DisplayName("enforces a floor on the request timeout")
  void clampsTimeout() {
    Config.Raw raw = credentials();
    raw.requestTimeoutSeconds = 1;

    assertEquals(Duration.ofSeconds(5), Config.fromRaw(raw, null).requestTimeout());
  }

  @Test
  @DisplayName("defaults the optional accessories on")
  void defaultsAccessories() {
    Config config = Config.fromRaw(credentials(), null);

    assertTrue(config.showIndoorHumidity());
    assertTrue(config.showOutdoorTemperatureSensor());
    assertEquals("Daikin One", config.bridgeName());
    assertEquals(51826, config.port());
  }

  @Test
  @DisplayName("lets the opt-out flags turn accessories off")
  void honoursOptOut() {
    Config.Raw raw = credentials();
    raw.showIndoorHumidity = false;
    raw.showOutdoorTemperatureSensor = false;

    Config config = Config.fromRaw(raw, null);
    assertFalse(config.showIndoorHumidity());
    assertFalse(config.showOutdoorTemperatureSensor());
  }

  @Test
  @DisplayName("puts the pairing state next to the config file by default")
  void storagePathDefaultsBesideConfig(@TempDir Path dir) {
    Config config = Config.fromRaw(credentials(), dir);
    assertEquals(dir.resolve("daikin-home.state.json"), config.storagePath());
  }

  @Test
  @DisplayName("loads from a JSON file")
  void loadsFromFile(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("daikin.config.json");
    Files.writeString(
        file,
        """
        {
          "apiKey": "key",
          "email": "user@example.com",
          "integratorToken": "token",
          "pollIntervalSeconds": 300,
          "showOutdoorTemperatureSensor": false,
          "somethingUnknown": true
        }
        """);

    Config config = Config.load(file);

    assertEquals("key", config.apiKey());
    assertEquals(Duration.ofSeconds(300), config.pollInterval());
    assertFalse(config.showOutdoorTemperatureSensor());
  }

  @Test
  @DisplayName("explains itself when the file is missing")
  void reportsMissingFile(@TempDir Path dir) {
    ConfigException error =
        assertThrows(ConfigException.class, () -> Config.load(dir.resolve("nope.json")));
    assertTrue(error.getMessage().contains("cannot read"), error.getMessage());
  }
}
