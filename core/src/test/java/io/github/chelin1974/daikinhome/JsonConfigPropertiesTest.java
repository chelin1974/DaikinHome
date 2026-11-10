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

    // data-directory is always emitted - see readAsProperties - so compare the rest.
    Map<String, Object> withoutDataDirectory = new java.util.LinkedHashMap<>(properties);
    withoutDataDirectory.remove("daikin.data-directory");
    assertEquals(Map.of("daikin.email", "you@example.com"), withoutDataDirectory);
  }

  /** The example files carry their own commentary under keys Jackson should ignore. */
  @Test
  void ignoresUnknownKeys() throws IOException {
    Map<String, Object> properties =
        JsonConfig.readAsProperties(
            write("{\"_comment\": \"copy me\", \"email\": \"you@example.com\"}"));

    Map<String, Object> withoutDataDirectory = new java.util.LinkedHashMap<>(properties);
    withoutDataDirectory.remove("daikin.data-directory");
    assertEquals(Map.of("daikin.email", "you@example.com"), withoutDataDirectory);
  }

  @Test
  void trimsSurroundingWhitespace() throws IOException {
    Map<String, Object> properties =
        JsonConfig.readAsProperties(write("{\"email\": \"  you@example.com  \"}"));

    assertEquals("you@example.com", properties.get("daikin.email"));
  }

  /**
   * Same rule as {@link JsonConfig#load(Path)}: relative to the file, not the process.
   *
   * <p>Asserted on where the file ends up rather than on the property, because the property is
   * now left relative on purpose and resolved once, against the data directory, in
   * BridgeSettings - so that a relative storagePath cannot bypass the data directory.
   */
  @Test
  void resolvesRelativeStoragePathAgainstTheFilesDirectory() throws IOException {
    Path file = write("{\"storagePath\": \"state/daikin.json\"}");

    Map<String, Object> properties = JsonConfig.readAsProperties(file);

    assertEquals("state/daikin.json", properties.get("daikin.storage-path"));
    assertEquals(directory.toString(), properties.get("daikin.data-directory"));

    // End to end: the two together put the file beside the config, as the CLI does.
    assertEquals(
        directory.resolve("state/daikin.json"),
        BridgeSettings.builder()
            .email("a@b.com")
            .password("x")
            .dataDirectory((String) properties.get("daikin.data-directory"))
            .storagePath((String) properties.get("daikin.storage-path"))
            .build()
            .storagePath());
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

  /**
   * The one setting that is a property but not a {@link BridgeSettings} field: it guards HTTP
   * routes, and the CLI flavour serves no HTTP. Without the field on {@code Raw},
   * {@code ignoreUnknown} would drop it and it would work from an environment variable but
   * not from the configuration file.
   */
  @Test
  void carriesTheDashboardPasscodeWithoutPuttingItInSettings() throws Exception {
    Path file = Files.createTempDirectory("daikin-passcode").resolve("daikin.config.json");
    Files.writeString(
        file,
        """
        {
          "apiKey": "key",
          "email": "you@example.com",
          "integratorToken": "token",
          "dashboardPasscode": "letmein"
        }
        """);

    assertEquals("letmein", JsonConfig.readAsProperties(file).get("daikin.dashboard-passcode"));
  }

  /** Absent, not present and null, so application.yml's own value is left to stand. */
  @Test
  void omitsTheDashboardPasscodeWhenTheFileHasNone() throws Exception {
    Path file = Files.createTempDirectory("daikin-no-passcode").resolve("daikin.config.json");
    Files.writeString(
        file,
        """
        {
          "apiKey": "key",
          "email": "you@example.com",
          "integratorToken": "token"
        }
        """);

    assertFalse(JsonConfig.readAsProperties(file).containsKey("daikin.dashboard-passcode"));
  }
}
