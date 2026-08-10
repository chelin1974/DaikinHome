package io.github.chelin1974.daikinhome;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridge configuration, loaded from a JSON file.
 *
 * <p>Example {@code daikin.config.json}:
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
public record Config(
    String apiKey,
    String email,
    String integratorToken,
    String baseUrl,
    Duration pollInterval,
    Duration requestTimeout,
    boolean showIndoorHumidity,
    boolean showOutdoorTemperatureSensor,
    String bridgeName,
    int port,
    Path storagePath) {

  /**
   * Daikin documents a polling floor of one request per thermostat every three minutes.
   * Polling faster gets the integration rate limited, so the bridge refuses to go below
   * this no matter what the file says.
   */
  public static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(180);

  /**
   * How long a change takes to appear in the cloud's own view of the thermostat. Daikin
   * documents a 15 second minimum; the extra margin avoids reading back a stale value and
   * bouncing the Home app UI.
   */
  public static final Duration WRITE_SETTLE = Duration.ofSeconds(20);

  /**
   * How long to batch characteristic writes before sending them. Changing mode and
   * temperature in the Home app produces several writes within a few hundred
   * milliseconds; they collapse into one API call.
   */
  public static final Duration WRITE_DEBOUNCE = Duration.ofMillis(750);

  /** Thrown when the configuration file is missing or unusable. */
  public static class ConfigException extends RuntimeException {
    public ConfigException(String message) {
      super(message);
    }

    public ConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Raw JSON shape, before defaults and clamping are applied. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  static final class Raw {
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
  }

  public static Config load(Path file) {
    if (!Files.isReadable(file)) {
      throw new ConfigException(
          "cannot read the configuration file "
              + file.toAbsolutePath()
              + ". Copy daikin.config.example.json and fill in your Daikin credentials.");
    }

    Raw raw;
    try {
      raw = new ObjectMapper().readValue(Files.readString(file), Raw.class);
    } catch (IOException e) {
      throw new ConfigException("could not parse " + file.toAbsolutePath() + ": " + e.getMessage(), e);
    }

    return fromRaw(raw, file.toAbsolutePath().getParent());
  }

  /** Applies defaults, clamps the poll interval, and checks the credentials are present. */
  static Config fromRaw(Raw raw, Path baseDirectory) {
    List<String> missing = new ArrayList<>();
    if (isBlank(raw.apiKey)) {
      missing.add("apiKey");
    }
    if (isBlank(raw.email)) {
      missing.add("email");
    }
    if (isBlank(raw.integratorToken)) {
      missing.add("integratorToken");
    }

    if (!missing.isEmpty()) {
      throw new ConfigException(
          "missing required setting"
              + (missing.size() > 1 ? "s" : "")
              + ": "
              + String.join(", ", missing)
              + ". In the Daikin One app, request an integrator token under \"Daikin One Care"
              + " -> home integration\" and read the API key from the developer menu in the"
              + " same section.");
    }

    Duration requested =
        Duration.ofSeconds(raw.pollIntervalSeconds == null ? 180 : raw.pollIntervalSeconds);
    Duration pollInterval =
        requested.compareTo(MIN_POLL_INTERVAL) < 0 ? MIN_POLL_INTERVAL : requested;

    int timeoutSeconds = raw.requestTimeoutSeconds == null ? 20 : raw.requestTimeoutSeconds;

    Path storage =
        raw.storagePath != null && !raw.storagePath.isBlank()
            ? Path.of(raw.storagePath)
            : (baseDirectory == null ? Path.of("daikin-home.state.json")
                : baseDirectory.resolve("daikin-home.state.json"));

    return new Config(
        raw.apiKey.trim(),
        raw.email.trim(),
        raw.integratorToken.trim(),
        isBlank(raw.baseUrl) ? null : raw.baseUrl.trim(),
        pollInterval,
        Duration.ofSeconds(Math.max(5, timeoutSeconds)),
        raw.showIndoorHumidity == null || raw.showIndoorHumidity,
        raw.showOutdoorTemperatureSensor == null || raw.showOutdoorTemperatureSensor,
        isBlank(raw.bridgeName) ? "Daikin One" : raw.bridgeName.trim(),
        raw.port == null ? 51826 : raw.port,
        storage);
  }

  /** True when the file asked for a faster poll than Daikin permits. */
  public static boolean pollIntervalWasClamped(Integer requestedSeconds) {
    return requestedSeconds != null
        && Duration.ofSeconds(requestedSeconds).compareTo(MIN_POLL_INTERVAL) < 0;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
