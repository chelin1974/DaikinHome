package io.github.chelin1974.daikinhome;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the bridge needs to run, already validated and clamped.
 *
 * <p>Deliberately not tied to any config format. The CLI builds one of these from a JSON
 * file; the Spring Boot service builds one from {@code application.yml}, environment
 * variables or anything else Spring binds. Validation lives here so both routes reject the
 * same mistakes with the same message.
 */
public record BridgeSettings(
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
   * this no matter what the configuration says.
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
   * temperature in the Home app produces several writes within a few hundred milliseconds;
   * they collapse into one API call.
   */
  public static final Duration WRITE_DEBOUNCE = Duration.ofMillis(750);

  public static final Duration MIN_REQUEST_TIMEOUT = Duration.ofSeconds(5);
  public static final String DEFAULT_BRIDGE_NAME = "Daikin One";
  public static final int DEFAULT_PORT = 51826;
  public static final String DEFAULT_STATE_FILE = "daikin-home.state.json";

  /** Thrown when the supplied settings cannot produce a working bridge. */
  public static class SettingsException extends RuntimeException {
    public SettingsException(String message) {
      super(message);
    }

    public SettingsException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /** True when the caller asked for a faster poll than Daikin permits. */
  public static boolean pollIntervalWasClamped(Integer requestedSeconds) {
    return requestedSeconds != null
        && Duration.ofSeconds(requestedSeconds).compareTo(MIN_POLL_INTERVAL) < 0;
  }

  /**
   * Collects raw values and applies defaults, clamping and validation on {@link #build()}.
   *
   * <p>Null means "not supplied" for every setter, so a config source can pass through
   * whatever it has without special-casing absent values.
   */
  public static final class Builder {
    private String apiKey;
    private String email;
    private String integratorToken;
    private String baseUrl;
    private Integer pollIntervalSeconds;
    private Integer requestTimeoutSeconds;
    private Boolean showIndoorHumidity;
    private Boolean showOutdoorTemperatureSensor;
    private String bridgeName;
    private Integer port;
    private String storagePath;
    private Path storageDirectory;

    public Builder apiKey(String value) {
      this.apiKey = value;
      return this;
    }

    public Builder email(String value) {
      this.email = value;
      return this;
    }

    public Builder integratorToken(String value) {
      this.integratorToken = value;
      return this;
    }

    public Builder baseUrl(String value) {
      this.baseUrl = value;
      return this;
    }

    public Builder pollIntervalSeconds(Integer value) {
      this.pollIntervalSeconds = value;
      return this;
    }

    public Builder requestTimeoutSeconds(Integer value) {
      this.requestTimeoutSeconds = value;
      return this;
    }

    public Builder showIndoorHumidity(Boolean value) {
      this.showIndoorHumidity = value;
      return this;
    }

    public Builder showOutdoorTemperatureSensor(Boolean value) {
      this.showOutdoorTemperatureSensor = value;
      return this;
    }

    public Builder bridgeName(String value) {
      this.bridgeName = value;
      return this;
    }

    public Builder port(Integer value) {
      this.port = value;
      return this;
    }

    public Builder storagePath(String value) {
      this.storagePath = value;
      return this;
    }

    /** Where the state file lands when {@code storagePath} is not given. */
    public Builder storageDirectory(Path value) {
      this.storageDirectory = value;
      return this;
    }

    public BridgeSettings build() {
      List<String> missing = new ArrayList<>();
      if (isBlank(apiKey)) {
        missing.add("apiKey");
      }
      if (isBlank(email)) {
        missing.add("email");
      }
      if (isBlank(integratorToken)) {
        missing.add("integratorToken");
      }

      if (!missing.isEmpty()) {
        throw new SettingsException(
            "missing required setting"
                + (missing.size() > 1 ? "s" : "")
                + ": "
                + String.join(", ", missing)
                + ". In the Daikin One app, request an integrator token under \"Daikin One"
                + " Care -> home integration\" and read the API key from the developer menu"
                + " in the same section.");
      }

      Duration requested =
          Duration.ofSeconds(pollIntervalSeconds == null ? 180 : pollIntervalSeconds);
      Duration poll = requested.compareTo(MIN_POLL_INTERVAL) < 0 ? MIN_POLL_INTERVAL : requested;

      Duration timeout =
          Duration.ofSeconds(requestTimeoutSeconds == null ? 20 : requestTimeoutSeconds);
      if (timeout.compareTo(MIN_REQUEST_TIMEOUT) < 0) {
        timeout = MIN_REQUEST_TIMEOUT;
      }

      Path storage;
      if (!isBlank(storagePath)) {
        storage = Path.of(storagePath.trim());
      } else if (storageDirectory != null) {
        storage = storageDirectory.resolve(DEFAULT_STATE_FILE);
      } else {
        storage = Path.of(DEFAULT_STATE_FILE);
      }

      return new BridgeSettings(
          apiKey.trim(),
          email.trim(),
          integratorToken.trim(),
          isBlank(baseUrl) ? null : baseUrl.trim(),
          poll,
          timeout,
          showIndoorHumidity == null || showIndoorHumidity,
          showOutdoorTemperatureSensor == null || showOutdoorTemperatureSensor,
          isBlank(bridgeName) ? DEFAULT_BRIDGE_NAME : bridgeName.trim(),
          port == null ? DEFAULT_PORT : port,
          storage);
    }

    private static boolean isBlank(String value) {
      return value == null || value.isBlank();
    }
  }
}
