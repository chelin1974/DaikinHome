package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.BridgeSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code daikin.*} block of {@code application.yml}.
 *
 * <p>Spring binds these from YAML, environment variables ({@code DAIKIN_API_KEY}),
 * command-line arguments or anything else on the property source list — which is the main
 * reason to run the service flavour in a container.
 *
 * <p>Validation and clamping are deliberately not done here. They live in
 * {@link BridgeSettings}, so the CLI and this service reject the same mistakes with the same
 * message.
 */
@ConfigurationProperties(prefix = "daikin")
public class DaikinProperties {

  /**
   * {@code skyport} (email and password, for accounts on the SkyportHome app) or
   * {@code openapi} (API key and integrator token). Inferred when absent.
   */
  private String backend;

  /** SkyportHome app password. Used by the {@code skyport} backend only. */
  private String password;

  /** LAN address to advertise HomeKit on. Blank picks one automatically. */
  private String bindAddress;

  /** Integrator API key, from the Daikin One app's developer menu. */
  private String apiKey;

  /** Email address of the Daikin One account. */
  private String email;

  /** Integrator token, generated in the Daikin One app. */
  private String integratorToken;

  /** Override only if Daikin moves the Open API endpoint. */
  private String baseUrl;

  /** Seconds between polls. Values below 180 are raised back to 180. */
  private Integer pollIntervalSeconds;

  /** Per-request timeout in seconds. */
  private Integer requestTimeoutSeconds;

  /** Show indoor humidity on the HomeKit thermostat tile. */
  private Boolean showIndoorHumidity;

  /** Publish a separate outdoor temperature sensor accessory. */
  private Boolean showOutdoorTemperatureSensor;

  /** Name shown while pairing in the Home app. */
  private String bridgeName;

  /**
   * Port the HomeKit accessory server listens on.
   *
   * <p>Not the HTTP port — that is {@code server.port}. Naming it {@code homekit-port} keeps
   * the two from being confused in a config file.
   */
  private Integer homekitPort;

  /** Where HomeKit pairing state is stored. Back this file up. */
  private String storagePath;

  /** Prometheus 3 base URL; readings go to its OTLP receiver. Blank sends none. */
  private String prometheusUrl;

  /** Full OTLP/HTTP metrics URL, for a collector. Wins over prometheusUrl. */
  private String otlpEndpoint;

  /** Seconds between metric pushes. */
  private Integer metricsIntervalSeconds;

  /** Applies defaults, clamping and validation. */
  public BridgeSettings toBridgeSettings() {
    return BridgeSettings.builder()
        .backend(backend)
        .password(password)
        .bindAddress(bindAddress)
        .apiKey(apiKey)
        .email(email)
        .integratorToken(integratorToken)
        .baseUrl(baseUrl)
        .pollIntervalSeconds(pollIntervalSeconds)
        .requestTimeoutSeconds(requestTimeoutSeconds)
        .showIndoorHumidity(showIndoorHumidity)
        .showOutdoorTemperatureSensor(showOutdoorTemperatureSensor)
        .bridgeName(bridgeName)
        .port(homekitPort)
        .storagePath(storagePath)
        .prometheusUrl(prometheusUrl)
        .otlpEndpoint(otlpEndpoint)
        .metricsIntervalSeconds(metricsIntervalSeconds)
        .build();
  }

  public String getBackend() {
    return backend;
  }

  public void setBackend(String backend) {
    this.backend = backend;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getBindAddress() {
    return bindAddress;
  }

  public void setBindAddress(String bindAddress) {
    this.bindAddress = bindAddress;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getIntegratorToken() {
    return integratorToken;
  }

  public void setIntegratorToken(String integratorToken) {
    this.integratorToken = integratorToken;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public Integer getPollIntervalSeconds() {
    return pollIntervalSeconds;
  }

  public void setPollIntervalSeconds(Integer pollIntervalSeconds) {
    this.pollIntervalSeconds = pollIntervalSeconds;
  }

  public Integer getRequestTimeoutSeconds() {
    return requestTimeoutSeconds;
  }

  public void setRequestTimeoutSeconds(Integer requestTimeoutSeconds) {
    this.requestTimeoutSeconds = requestTimeoutSeconds;
  }

  public Boolean getShowIndoorHumidity() {
    return showIndoorHumidity;
  }

  public void setShowIndoorHumidity(Boolean showIndoorHumidity) {
    this.showIndoorHumidity = showIndoorHumidity;
  }

  public Boolean getShowOutdoorTemperatureSensor() {
    return showOutdoorTemperatureSensor;
  }

  public void setShowOutdoorTemperatureSensor(Boolean showOutdoorTemperatureSensor) {
    this.showOutdoorTemperatureSensor = showOutdoorTemperatureSensor;
  }

  public String getBridgeName() {
    return bridgeName;
  }

  public void setBridgeName(String bridgeName) {
    this.bridgeName = bridgeName;
  }

  public Integer getHomekitPort() {
    return homekitPort;
  }

  public void setHomekitPort(Integer homekitPort) {
    this.homekitPort = homekitPort;
  }

  public String getStoragePath() {
    return storagePath;
  }

  public void setStoragePath(String storagePath) {
    this.storagePath = storagePath;
  }

  public String getPrometheusUrl() {
    return prometheusUrl;
  }

  public void setPrometheusUrl(String prometheusUrl) {
    this.prometheusUrl = prometheusUrl;
  }

  public String getOtlpEndpoint() {
    return otlpEndpoint;
  }

  public void setOtlpEndpoint(String otlpEndpoint) {
    this.otlpEndpoint = otlpEndpoint;
  }

  public Integer getMetricsIntervalSeconds() {
    return metricsIntervalSeconds;
  }

  public void setMetricsIntervalSeconds(Integer metricsIntervalSeconds) {
    this.metricsIntervalSeconds = metricsIntervalSeconds;
  }
}
