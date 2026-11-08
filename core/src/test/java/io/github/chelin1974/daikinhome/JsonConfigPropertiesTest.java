package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.daikinhome.BridgeSettings.SettingsException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link JsonConfig#readAsProperties(Path)} — the reading that lets the Spring Boot flavour
 * take its settings from the same file the CLI uses.
 */
class JsonConfigPropertiesTest {

  @TempDir Path directory;

  private Path write(String json) throws IOException {
    Path file = directory.resolve("daikin.config.json");
    Files.writeString(file, json);
    return file;
  }

  @Test
  void translatesCamelCaseToSpringPropertyNames() throws IOException {
    Map<String, Object> properties =
        JsonConfig.readAsProperties(
            write(
                """
                {
                  "apiKey": "key-1",
                  "email": "you@example.com",
                  "integratorToken": "token-1",
                  "pollIntervalSeconds": 240,
                  "showOutdoorTemperatureSensor": true,
                  "bridgeName": "Upstairs"
                }
                """));

    assertEquals("key-1", properties.get("daikin.api-key"));
    assertEquals("you@example.com", properties.get("daikin.email"));
    assertEquals("token-1", properties.get("daikin.integrator-token"));
    assertEquals(240, properties.get("daikin.poll-interval-seconds"));
    assertEquals(Boolean.TRUE, properties.get("daikin.show-outdoor-temperature-sensor"));
    assertEquals("Upstairs", properties.get("daikin.bridge-name"));
  }

  /** The file says "port"; the service has an HTTP port too, so this one is spelled out. */
  @Test
  void renamesPortToHomekitPort() throws IOException {
    Map<String, Object> properties = JsonConfig.readAsProperties(write("{\"port\": 51827}"));

    assertEquals(51827, properties.get("daikin.homekit-port"));
    assertFalse(properties.containsKey("daikin.port"));
  }

  @Test
  void readsTheSkyportShape() throws IOException {
    Map<String, Object> properties =
        JsonConfig.readAsProperties(
            write(
                """
                {
                  "backend": "skyport",
                  "email": "you@example.com",
                  "password": "secret"
                }
                """));

    assertEquals("skyport", properties.get("daikin.backend"));
    assertEquals("secret", properties.get("daikin.password"));
  }

  /**
   * An absent or blank setting must be absent from the map, not present and empty: the map
   * outranks application.yml, so an empty value here would wipe out a default rather than
   * leave it standing.
   */
  @Test
  void leavesOutAbsentAndBlankValues() throws IOException {
    Map<String, Object> properties =
        JsonConfig.readAsProperties(
            write(
                """
                {
                  "email": "you@example.com",
                  "prometheusUrl": "",
                  "bridgeName": "   "
                }
                """));

    assertEquals(Map.of("daikin.email", "you@example.com"), properties);
  }

  /** The example files carry their own commentary under keys Jackson should ignore. */
  @Test
  void ignoresUnknownKeys() throws IOException {
    Map<String, Object> properties =
        JsonConfig.readAsProperties(
            write("{\"_comment\": \"copy me\", \"email\": \"you@example.com\"}"));

    assertEquals(Map.of("daikin.email", "you@example.com"), properties);
  }

  @Test
  void trimsSurroundingWhitespace() throws IOException {
    Map<String, Object> properties =
        JsonConfig.readAsProperties(write("{\"email\": \"  you@example.com  \"}"));

    assertEquals("you@example.com", properties.get("daikin.email"));
  }

  /** Same rule as {@link JsonConfig#load(Path)}: relative to the file, not the process. */
  @Test
  void resolvesRelativeStoragePathAgainstTheFilesDirectory() throws IOException {
    Path file = write("{\"storagePath\": \"state/daikin.json\"}");

    Map<String, Object> properties = JsonConfig.readAsProperties(file);

    assertEquals(
        directory.resolve("state/daikin.json").toString(), properties.get("daikin.storage-path"));
  }

  @Test
  void keepsAnAbsoluteStoragePath() throws IOException {
    Map<String, Object> properties =
        JsonConfig.readAsProperties(write("{\"storagePath\": \"/data/daikin-home.state.json\"}"));

    assertEquals("/data/daikin-home.state.json", properties.get("daikin.storage-path"));
  }

  @Test
  void rejectsAFileThatIsNotThere() {
    SettingsException thrown =
        assertThrows(
            SettingsException.class,
            () -> JsonConfig.readAsProperties(directory.resolve("absent.json")));

    assertTrue(thrown.getMessage().contains("cannot read the configuration file"));
  }

  @Test
  void rejectsMalformedJsonWithTheFileName() throws IOException {
    Path file = write("{\"email\": ");

    SettingsException thrown =
        assertThrows(SettingsException.class, () -> JsonConfig.readAsProperties(file));

    assertTrue(thrown.getMessage().contains("could not parse"));
    assertTrue(thrown.getMessage().contains(file.toAbsolutePath().toString()));
  }

  /**
   * The two readings of the file must agree. A setting that reaches the CLI but not the
   * service, or the other way round, is the bug this whole class exists to prevent.
   */
  @Test
  void agreesWithTheSettingsReadingOnEverySharedField() throws IOException {
    Path file =
        write(
            """
            {
              "backend": "skyport",
              "email": "you@example.com",
              "password": "secret",
              "bindAddress": "192.168.7.10",
              "baseUrl": "https://example.invalid",
              "pollIntervalSeconds": 300,
              "requestTimeoutSeconds": 30,
              "showIndoorHumidity": false,
              "showOutdoorTemperatureSensor": false,
              "bridgeName": "Upstairs",
              "port": 51830,
              "prometheusUrl": "http://localhost:9090",
              "metricsIntervalSeconds": 120
            }
            """);

    Map<String, Object> properties = JsonConfig.readAsProperties(file);
    BridgeSettings settings = JsonConfig.load(file);

    assertEquals(BridgeSettings.Backend.SKYPORT, settings.backend());
    assertEquals("skyport", properties.get("daikin.backend"));

    assertEquals(settings.email(), properties.get("daikin.email"));
    assertEquals(settings.password(), properties.get("daikin.password"));
    assertEquals(settings.bindAddress(), properties.get("daikin.bind-address"));
    assertEquals(settings.baseUrl(), properties.get("daikin.base-url"));
    assertEquals(settings.bridgeName(), properties.get("daikin.bridge-name"));
    assertEquals(settings.port(), properties.get("daikin.homekit-port"));
    assertEquals(settings.showIndoorHumidity(), properties.get("daikin.show-indoor-humidity"));
    assertEquals(
        settings.showOutdoorTemperatureSensor(),
        properties.get("daikin.show-outdoor-temperature-sensor"));

    // The settings reading turns these into Durations; the property keeps the raw seconds,
    // and BridgeSettings does the same conversion for whatever Spring binds.
    assertEquals(
        settings.pollInterval().toSeconds(),
        ((Integer) properties.get("daikin.poll-interval-seconds")).longValue());
    assertEquals(
        settings.requestTimeout().toSeconds(),
        ((Integer) properties.get("daikin.request-timeout-seconds")).longValue());
    assertEquals(
        settings.metricsInterval().toSeconds(),
        ((Integer) properties.get("daikin.metrics-interval-seconds")).longValue());

    // Likewise prometheusUrl: the settings reading turns it into the OTLP endpoint it
    // implies, so the property carries the base URL the user wrote.
    assertEquals("http://localhost:9090", properties.get("daikin.prometheus-url"));
    assertTrue(settings.otlpEndpoint().toString().startsWith("http://localhost:9090"));
  }
}
