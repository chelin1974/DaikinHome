package io.github.chelin1974.daikinhome.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.BridgeSettings.SettingsException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads {@link BridgeSettings} from a JSON file.
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

  public static BridgeSettings load(Path file) {
    if (!Files.isReadable(file)) {
      throw new SettingsException(
          "cannot read the configuration file "
              + file.toAbsolutePath()
              + ". Copy daikin.config.example.json and fill in your Daikin credentials.");
    }

    Raw raw;
    try {
      raw = new ObjectMapper().readValue(Files.readString(file), Raw.class);
    } catch (IOException e) {
      throw new SettingsException(
          "could not parse " + file.toAbsolutePath() + ": " + e.getMessage(), e);
    }

    return toSettings(raw, file.toAbsolutePath().getParent());
  }

  /** Exposed for tests; {@code storageDirectory} is where the state file lands. */
  static BridgeSettings toSettings(Raw raw, Path storageDirectory) {
    return BridgeSettings.builder()
        .backend(raw.backend)
        .password(raw.password)
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
        .storageDirectory(storageDirectory)
        .build();
  }

  /** The raw poll interval, so the caller can warn that it was clamped. */
  static Integer requestedPollIntervalSeconds(Path file) {
    try {
      return new ObjectMapper().readValue(Files.readString(file), Raw.class).pollIntervalSeconds;
    } catch (IOException e) {
      return null;
    }
  }
}
