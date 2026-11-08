package io.github.chelin1974.daikinhome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.github.chelin1974.daikinhome.BridgeSettings.SettingsException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the bridge's configuration file.
 *
 * <p>One file serves every way of running the bridge: {@link #load(Path)} turns it into
 * {@link BridgeSettings} for the CLI, and {@link #readAsProperties(Path)} turns it into
 * {@code daikin.*} properties for the Spring Boot service, which is also how the container
 * gets its settings. Keeping both readings here means a new setting is added in one place.
 *
 * <p>Example {@code daikin.config.json} for an account on the SkyportHome app:
 *
 * <pre>{@code
 * {
 *   "backend": "skyport",
 *   "email": "you@example.com",
 *   "password": "..."
 * }
 * }</pre>
 *
 * <p>And for the Open API:
 *
 * <pre>{@code
 * {
 *   "apiKey": "...",
 *   "email": "you@example.com",
 *   "integratorToken": "...",
 *   "pollIntervalSeconds": 180,
 *   "showOutdoorTemperatureSensor": true
 * }
 * }</pre>
 */
public final class JsonConfig {

  private JsonConfig() {}

  /** Raw JSON shape, before defaults, clamping and validation. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class Raw {
    public String backend;
    public String password;
    public String bindAddress;
    public String apiKey;
    public String email;
    public String integratorToken;
    public String baseUrl;
    public Integer pollIntervalSeconds;
    public Integer requestTimeoutSeconds;
    public Boolean showIndoorHumidity;
    public Boolean showOutdoorTemperatureSensor;
    public String bridgeName;
    public Integer port;
    public String storagePath;
    public String prometheusUrl;
    public String otlpEndpoint;
    public Integer metricsIntervalSeconds;
  }

  public static BridgeSettings load(Path file) {
    return toSettings(read(file), file.toAbsolutePath().getParent());
  }

  /** Exposed for tests; {@code storageDirectory} is where the state file lands. */
  static BridgeSettings toSettings(Raw raw, Path storageDirectory) {
    return BridgeSettings.builder()
        .backend(raw.backend)
        .password(raw.password)
        .bindAddress(raw.bindAddress)
        .apiKey(raw.apiKey)
        .email(raw.email)
        .integratorToken(raw.integratorToken)
        .baseUrl(raw.baseUrl)
        .pollIntervalSeconds(raw.pollIntervalSeconds)
        .requestTimeoutSeconds(raw.requestTimeoutSeconds)
        .showIndoorHumidity(raw.showIndoorHumidity)
        .showOutdoorTemperatureSensor(raw.showOutdoorTemperatureSensor)
        .bridgeName(raw.bridgeName)
        .port(raw.port)
        .storagePath(raw.storagePath)
        .prometheusUrl(raw.prometheusUrl)
        .otlpEndpoint(raw.otlpEndpoint)
        .metricsIntervalSeconds(raw.metricsIntervalSeconds)
        .storageDirectory(storageDirectory)
        .build();
  }

  /** The raw poll interval, so the caller can warn that it was clamped. */
  public static Integer requestedPollIntervalSeconds(Path file) {
    try {
      return new ObjectMapper().readValue(Files.readString(file), Raw.class).pollIntervalSeconds;
    } catch (IOException | JacksonException e) {
      return null;
    }
  }

  /**
   * Reads the same file as {@link #load(Path)}, but as {@code daikin.*} configuration
   * properties rather than a finished {@link BridgeSettings}.
   *
   * <p>This is what lets the Spring Boot flavour read the file the CLI already uses, so one
   * {@code daikin.config.json} serves every way of running the bridge. The caller puts the
   * returned map on Spring's property source list; validation, defaults and clamping still
   * happen once, in {@link BridgeSettings}, exactly as they do for the CLI.
   *
   * <p>Keys absent from the file are absent from the map rather than present and null, so a
   * value from {@code application.yml} is left to stand instead of being overwritten with
   * nothing. {@code storagePath} is relative to the file's own directory, matching
   * {@link #load(Path)}; everything else is passed through as written.
   *
   * @throws SettingsException if the file cannot be read or is not valid JSON
   */
  public static Map<String, Object> readAsProperties(Path file) {
    Raw raw = read(file);
    Path directory = file.toAbsolutePath().getParent();

    Map<String, Object> properties = new LinkedHashMap<>();
    put(properties, "backend", raw.backend);
    put(properties, "password", raw.password);
    put(properties, "bind-address", raw.bindAddress);
    put(properties, "api-key", raw.apiKey);
    put(properties, "email", raw.email);
    put(properties, "integrator-token", raw.integratorToken);
    put(properties, "base-url", raw.baseUrl);
    put(properties, "poll-interval-seconds", raw.pollIntervalSeconds);
    put(properties, "request-timeout-seconds", raw.requestTimeoutSeconds);
    put(properties, "show-indoor-humidity", raw.showIndoorHumidity);
    put(properties, "show-outdoor-temperature-sensor", raw.showOutdoorTemperatureSensor);
    put(properties, "bridge-name", raw.bridgeName);
    // The file calls it "port"; the service has an HTTP port too, so it spells this one out.
    put(properties, "homekit-port", raw.port);
    put(properties, "prometheus-url", raw.prometheusUrl);
    put(properties, "otlp-endpoint", raw.otlpEndpoint);
    put(properties, "metrics-interval-seconds", raw.metricsIntervalSeconds);

    if (raw.storagePath != null && !raw.storagePath.isBlank()) {
      Path storage = Path.of(raw.storagePath.strip());
      put(
          properties,
          "storage-path",
          storage.isAbsolute() || directory == null
              ? storage.toString()
              : directory.resolve(storage).toString());
    }

    return properties;
  }

  private static void put(Map<String, Object> properties, String name, Object value) {
    if (value == null || (value instanceof String text && text.isBlank())) {
      return;
    }
    properties.put("daikin." + name, value instanceof String text ? text.strip() : value);
  }

  private static Raw read(Path file) {
    if (!Files.isReadable(file)) {
      throw new SettingsException(
          "cannot read the configuration file "
              + file.toAbsolutePath()
              + ". Copy daikin.config.example.json and fill in your Daikin credentials.");
    }
    try {
      return new ObjectMapper().readValue(Files.readString(file), Raw.class);
    } catch (IOException | JacksonException e) {
      throw new SettingsException(
          "could not parse " + file.toAbsolutePath() + ": " + e.getMessage(), e);
    }
  }
}
