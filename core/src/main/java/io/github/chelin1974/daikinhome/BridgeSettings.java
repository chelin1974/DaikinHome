package io.github.chelin1974.daikinhome;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the bridge needs to run, already validated and clamped.
 *
 * <p>Deliberately not tied to any config format. The CLI builds one of these from a JSON
 * file; the Spring Boot service builds one from {@code application.yml}, environment
 * variables or anything else Spring binds. Validation lives here so both routes reject the
 * same mistakes with the same message.
 *
 * <p>Which credentials are required depends on {@link #backend()}: the Open API needs an API
 * key, email and integrator token; the SkyportHome cloud needs the app's email and password.
 * Fields the chosen backend does not use are null.
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
    Path storagePath,
    Backend backend,
    String password,
    String bindAddress) {

  /** Which Daikin cloud the bridge talks to. */
  public enum Backend {
    /** The documented Open API: API key, email and integrator token. */
    OPEN_API,

    /**
     * The cloud behind the SkyportHome app: email and password. Needed for accounts Daikin
     * has moved to SkyportHome, which can no longer log in anywhere else.
     */
    SKYPORT;

    /**
     * Parses a config value. Accepts the enum names plus the obvious spellings
     * ({@code openapi}, {@code open-api}, {@code skyport}, {@code skyporthome}).
     */
    public static Backend parse(String value) {
      String normalised = value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
      return switch (normalised) {
        case "openapi", "integrator", "integratorapi" -> OPEN_API;
        case "skyport", "skyporthome", "skyportcloud" -> SKYPORT;
        default ->
            throw new SettingsException(
                "unknown backend \"" + value + "\". Use \"skyport\" (email and password, for"
                    + " accounts on the SkyportHome app) or \"openapi\" (API key and"
                    + " integrator token).");
      };
    }
  }

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

  /** Redacts credentials, so a settings object can be logged safely. */
  @Override
  public String toString() {
    return "BridgeSettings[backend="
        + backend
        + ", email="
        + email
        + ", baseUrl="
        + baseUrl
        + ", pollInterval="
        + pollInterval
        + ", bridgeName="
        + bridgeName
        + ", port="
        + port
        + ", bindAddress="
        + bindAddress
        + ", storagePath="
        + storagePath
        + "]";
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
    private String backend;
    private String password;
    private String bindAddress;

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

    /**
     * {@code skyport} or {@code openapi}. When absent, a password without an API key means
     * {@code skyport}; anything else means {@code openapi}.
     */
    public Builder backend(String value) {
      this.backend = value;
      return this;
    }

    /**
     * LAN address to advertise HomeKit on, e.g. {@code 192.168.1.20}. Null picks one
     * automatically; set it when the Home app cannot find the bridge.
     */
    public Builder bindAddress(String value) {
      this.bindAddress = value;
      return this;
    }

    /** SkyportHome app password. Used by the {@code skyport} backend only. */
    public Builder password(String value) {
      this.password = value;
      return this;
    }

    /** Where the state file lands when {@code storagePath} is not given. */
    public Builder storageDirectory(Path value) {
      this.storageDirectory = value;
      return this;
    }

    public BridgeSettings build() {
      Backend chosen;
      if (!isBlank(backend)) {
        chosen = Backend.parse(backend);
      } else if (isBlank(apiKey) && !isBlank(password)) {
        chosen = Backend.SKYPORT;
      } else {
        chosen = Backend.OPEN_API;
      }

      List<String> missing = new ArrayList<>();
      if (chosen == Backend.SKYPORT) {
        if (isBlank(email)) {
          missing.add("email");
        }
        // Not isBlank: a password of spaces is unusual but not invalid.
        if (password == null || password.isEmpty()) {
          missing.add("password");
        }
        if (!missing.isEmpty()) {
          throw new SettingsException(
              "missing required setting"
                  + (missing.size() > 1 ? "s" : "")
                  + " for the skyport backend: "
                  + String.join(", ", missing)
                  + ". Use the email and password you sign in to the SkyportHome app with.");
        }
      } else {
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
                  + " in the same section. If your account has moved to the SkyportHome app,"
                  + " set \"backend\": \"skyport\" with your email and password instead.");
        }
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

      boolean openApi = chosen == Backend.OPEN_API;
      return new BridgeSettings(
          openApi ? apiKey.trim() : null,
          email.trim(),
          openApi ? integratorToken.trim() : null,
          isBlank(baseUrl) ? null : baseUrl.trim(),
          poll,
          timeout,
          showIndoorHumidity == null || showIndoorHumidity,
          showOutdoorTemperatureSensor == null || showOutdoorTemperatureSensor,
          isBlank(bridgeName) ? DEFAULT_BRIDGE_NAME : bridgeName.trim(),
          port == null ? DEFAULT_PORT : port,
          storage,
          chosen,
          openApi ? null : password,
          isBlank(bindAddress) ? null : bindAddress.trim());
    }

    private static boolean isBlank(String value) {
      return value == null || value.isBlank();
    }
  }
}
