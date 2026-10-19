package io.github.chelin1974.daikinhome.daikin;

import static io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinApiException;
import static io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import static io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinNetworkException;
import static io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for the Daikin One Cloud Open API.
 *
 * <p>Handles token acquisition and renewal, keeps a floor between requests, retries the
 * failures worth retrying, and flattens the device listing into a plain list.
 *
 * <p>All methods block. Callers run them on a background thread — never on a HomeKit
 * characteristic read, which must return immediately.
 *
 * <p>This class is thread-safe.
 */
public final class DaikinOneClient implements DaikinCloud {

  private static final Logger log = LoggerFactory.getLogger(DaikinOneClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static final String DEFAULT_BASE_URL = "https://integrator-api.daikinskyport.com";

  /** Re-authenticate this long before the token actually expires. */
  private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(60);

  /** Used when the token response omits {@code accessTokenExpiresIn}. */
  private static final Duration FALLBACK_TOKEN_TTL = Duration.ofMinutes(10);

  private final String apiKey;
  private final String email;
  private final String integratorToken;
  private final String baseUrl;
  private final Duration timeout;
  private final Duration minRequestInterval;
  private final int maxAttempts;
  private final Supplier<Instant> clock;
  private final HttpClient http;

  /** Guards the token fields and the request pacing fields below. */
  private final Object lock = new Object();

  private String accessToken;
  private Instant accessTokenExpiresAt = Instant.EPOCH;
  private Instant lastRequestStartedAt = Instant.EPOCH;
  private Instant pausedUntil = Instant.EPOCH;

  private DaikinOneClient(Builder builder) {
    if (isBlank(builder.apiKey) || isBlank(builder.email) || isBlank(builder.integratorToken)) {
      throw new DaikinAuthException("apiKey, email and integratorToken are all required");
    }
    this.apiKey = builder.apiKey.trim();
    this.email = builder.email.trim();
    this.integratorToken = builder.integratorToken.trim();
    this.baseUrl = builder.baseUrl.replaceAll("/+$", "");
    this.timeout = builder.timeout;
    this.minRequestInterval = builder.minRequestInterval;
    this.maxAttempts = Math.max(1, builder.maxAttempts);
    this.clock = builder.clock;
    this.http = HttpClient.newBuilder().connectTimeout(builder.timeout).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Lists every thermostat on the account.
   *
   * <p>The API groups thermostats by location; some accounts return a flat array instead,
   * so both shapes are accepted.
   */
  @Override
  public List<DaikinDeviceSummary> getDevices() {
    return normaliseDeviceListing(requestJson("GET", "/v1/devices", null, false));
  }

  /** Full state for one thermostat. */
  @Override
  public DaikinDevice getDevice(String deviceId) {
    JsonNode payload =
        requestJson("GET", "/v1/devices/" + encode(deviceId), null, false);
    if (payload == null || !payload.isObject()) {
      throw new DaikinResponseException("device " + deviceId + " returned a non-object payload");
    }
    try {
      return MAPPER.treeToValue(payload, DaikinDevice.class);
    } catch (Exception e) {
      throw new DaikinResponseException("could not parse the state of device " + deviceId, e);
    }
  }

  /**
   * Updates mode and setpoints.
   *
   * <p>Daikin's examples always send mode together with both setpoints, and the thermostat
   * validates them as a set (heat must sit below cool by at least {@code setpointDelta}),
   * so callers pass a complete payload rather than a partial patch.
   */
  @Override
  public void updateDevice(String deviceId, Map<String, Object> update) {
    requestJson("PUT", "/v1/devices/" + encode(deviceId) + "/msp", update, false);
  }

  /** Drops the cached token; the next request re-authenticates. */
  @Override
  public void invalidateToken() {
    synchronized (lock) {
      accessToken = null;
      accessTokenExpiresAt = Instant.EPOCH;
    }
  }

  // ---------------------------------------------------------------- requests

  private JsonNode requestJson(String method, String path, Object body, boolean anonymous) {
    RuntimeException lastError = null;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return dispatch(method, path, body, anonymous, attempt);
      } catch (DaikinAuthException e) {
        throw e;
      } catch (DaikinApiException e) {
        lastError = e;
        if (!e.isRetryable() || attempt == maxAttempts) {
          throw e;
        }
        backoff(method, path, attempt, e);
      } catch (DaikinNetworkException e) {
        lastError = e;
        if (attempt == maxAttempts) {
          throw e;
        }
        backoff(method, path, attempt, e);
      }
    }

    // Unreachable: the loop always returns or leaves lastError set.
    throw lastError == null
        ? new IllegalStateException("request loop ended without a result")
        : lastError;
  }

  private void backoff(String method, String path, int attempt, RuntimeException cause) {
    long millis = (long) Math.pow(2, attempt - 1) * 1_000L;
    log.debug(
        "{} {} failed (attempt {}/{}), retrying in {}ms: {}",
        method,
        path,
        attempt,
        maxAttempts,
        millis,
        cause.getMessage());
    sleep(millis);
  }

  private JsonNode dispatch(
      String method, String path, Object body, boolean anonymous, int attempt) {

    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("x-api-key", apiKey)
            .header("Accept", "application/json");

    if (!anonymous) {
      request.header("Authorization", "Bearer " + accessToken());
    }

    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json");
      request.method(method, HttpRequest.BodyPublishers.ofString(writeJson(body)));
    }

    awaitSlot();
    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new DaikinNetworkException(
          method + " " + path + " could not reach the Daikin API: " + describe(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DaikinNetworkException(method + " " + path + " was interrupted", e);
    }

    int status = response.statusCode();
    String text = response.body() == null ? "" : response.body();

    // A token can be revoked between our expiry estimate and the actual request.
    // Re-authenticate once and let the retry loop take another pass.
    if (status == 401 && !anonymous && attempt == 1) {
      log.debug("access token rejected, re-authenticating");
      invalidateToken();
      throw new DaikinApiException("access token rejected", 503, text);
    }

    if (status == 429) {
      Duration retryAfter =
          parseRetryAfter(response.headers().firstValue("retry-after").orElse(null), now())
              .orElse(Duration.ofSeconds(60));
      pauseFor(retryAfter);
      log.warn(
          "Daikin API rate limited this integration; pausing requests for {}s",
          retryAfter.toSeconds());
    }

    if (status == 401 || status == 403) {
      throw new DaikinAuthException(
          "Daikin API rejected the credentials for "
              + method
              + " "
              + path
              + " (HTTP "
              + status
              + "). Check the API key, account email and integrator token in the Daikin One app.");
    }

    if (status < 200 || status >= 300) {
      throw new DaikinApiException(
          method + " " + path + " failed with HTTP " + status + ": " + truncate(text),
          status,
          text);
    }

    if (text.isEmpty()) {
      return null;
    }

    try {
      return MAPPER.readTree(text);
    } catch (Exception e) {
      throw new DaikinResponseException(
          method + " " + path + " returned invalid JSON: " + truncate(text), e);
    }
  }

  // ------------------------------------------------------------------ tokens

  private String accessToken() {
    synchronized (lock) {
      if (accessToken != null && now().isBefore(accessTokenExpiresAt)) {
        return accessToken;
      }
    }
    return authenticate();
  }

  /**
   * Exchanges the integrator token for an access token.
   *
   * <p>Synchronized on the client so concurrent callers share one token request rather
   * than each opening their own.
   */
  private synchronized String authenticate() {
    synchronized (lock) {
      // Another thread may have refreshed while we waited for the monitor.
      if (accessToken != null && now().isBefore(accessTokenExpiresAt)) {
        return accessToken;
      }
    }

    log.debug("requesting a Daikin access token");

    Map<String, Object> body =
        Map.of("email", email, "integratorToken", integratorToken);

    // Attempt 2 so the 401-retry branch above cannot fire for the token call itself.
    JsonNode token = dispatch("POST", "/v1/token", body, true, 2);

    String issued = token == null ? null : token.path("accessToken").asText(null);
    if (isBlank(issued)) {
      throw new DaikinAuthException("the Daikin token endpoint did not return an accessToken");
    }

    long ttlSeconds =
        token.hasNonNull("accessTokenExpiresIn") && token.get("accessTokenExpiresIn").asLong() > 0
            ? token.get("accessTokenExpiresIn").asLong()
            : FALLBACK_TOKEN_TTL.toSeconds();

    Duration ttl = Duration.ofSeconds(ttlSeconds);
    Duration effective = ttl.minus(TOKEN_EXPIRY_MARGIN);
    if (effective.compareTo(Duration.ofSeconds(30)) < 0) {
      effective = Duration.ofSeconds(30);
    }

    synchronized (lock) {
      accessToken = issued;
      accessTokenExpiresAt = now().plus(effective);
    }

    log.debug("access token acquired, valid for {}s", ttl.toSeconds());
    return issued;
  }

  // ------------------------------------------------------------------ pacing

  /**
   * Holds requests until the floor has elapsed and any rate-limit pause has expired.
   *
   * <p>HomeKit refreshes hit every accessory in the same tick; without this they go out in
   * parallel and trip the API's own rate limiting.
   */
  private void awaitSlot() {
    while (true) {
      long waitMillis;
      synchronized (lock) {
        Instant now = now();
        long untilPause = Duration.between(now, pausedUntil).toMillis();
        long untilFloor =
            Duration.between(now, lastRequestStartedAt.plus(minRequestInterval)).toMillis();
        waitMillis = Math.max(untilPause, untilFloor);
        if (waitMillis <= 0) {
          lastRequestStartedAt = now;
          return;
        }
      }
      sleep(waitMillis);
    }
  }

  private void pauseFor(Duration duration) {
    synchronized (lock) {
      Instant candidate = now().plus(duration);
      if (candidate.isAfter(pausedUntil)) {
        pausedUntil = candidate;
      }
    }
  }

  // ------------------------------------------------------------------ helpers

  /**
   * Flattens {@code GET /v1/devices} into a plain list.
   *
   * <p>Accepts the documented location-grouped shape, a {@code {"locations": [...]}}
   * wrapper, and a bare device array.
   */
  public static List<DaikinDeviceSummary> normaliseDeviceListing(JsonNode payload) {
    List<JsonNode> entries = new ArrayList<>();

    if (payload != null && payload.isArray()) {
      payload.forEach(entries::add);
    } else if (payload != null && payload.path("locations").isArray()) {
      payload.get("locations").forEach(entries::add);
    } else if (payload != null && payload.path("devices").isArray()) {
      entries.add(payload);
    } else {
      throw new DaikinResponseException(
          "GET /v1/devices returned an unexpected shape: " + truncate(String.valueOf(payload)));
    }

    List<DaikinDeviceSummary> devices = new ArrayList<>();

    for (JsonNode entry : entries) {
      if (entry == null || !entry.isObject()) {
        continue;
      }

      // A location wrapping a list of thermostats.
      if (entry.path("devices").isArray()) {
        String locationName =
            firstText(entry.path("locationName"), entry.path("name")).orElse(null);
        for (JsonNode device : entry.get("devices")) {
          toSummary(device, locationName).ifPresent(devices::add);
        }
        continue;
      }

      // A bare thermostat.
      toSummary(entry, null).ifPresent(devices::add);
    }

    return devices;
  }

  private static Optional<DaikinDeviceSummary> toSummary(JsonNode node, String locationName) {
    if (node == null || !node.isObject()) {
      return Optional.empty();
    }

    Optional<String> id =
        firstText(node.path("id"), node.path("deviceId"), node.path("thermostatId"));
    if (id.isEmpty()) {
      return Optional.empty();
    }

    String name = firstText(node.path("name")).orElse(id.get());
    return Optional.of(
        new DaikinDeviceSummary(
            id.get(),
            name,
            node.path("model").isTextual() ? node.get("model").asText() : null,
            node.path("firmwareVersion").isTextual() ? node.get("firmwareVersion").asText() : null,
            locationName));
  }

  /** Retry-After is either delta-seconds or an HTTP date. */
  public static Optional<Duration> parseRetryAfter(String value, Instant now) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }

    try {
      long seconds = Long.parseLong(value.trim());
      return Optional.of(Duration.ofSeconds(Math.max(0, seconds)));
    } catch (NumberFormatException ignored) {
      // Not delta-seconds; fall through to the HTTP-date form.
    }

    try {
      Instant when = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value.trim()));
      Duration delay = Duration.between(now, when);
      return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private static Optional<String> firstText(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node != null && node.isTextual() && !node.asText().isEmpty()) {
        return Optional.of(node.asText());
      }
    }
    return Optional.empty();
  }

  private Instant now() {
    return clock.get();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DaikinNetworkException("interrupted while waiting to call the Daikin API", e);
    }
  }

  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new DaikinResponseException("could not serialise the request body", e);
    }
  }

  private static String encode(String value) {
    // URLEncoder is form encoding: it turns a space into '+', which is wrong in a path.
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String truncate(String value) {
    return value.length() > 300 ? value.substring(0, 300) + "..." : value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * A usable description of a failure.
   *
   * <p>Several of the exceptions the HTTP client throws carry a null message — a connection
   * reset during shutdown, for one — and "could not reach the Daikin API: null" tells nobody
   * anything. Fall back to the exception type, and to its cause when that is also blank.
   */
  private static String describe(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        return current == error
            ? current.getMessage()
            : error.getClass().getSimpleName() + ": " + current.getMessage();
      }
    }
    return error.getClass().getSimpleName();
  }

  /** Builder, so the tunables stay optional and named at the call site. */
  public static final class Builder {
    private String apiKey;
    private String email;
    private String integratorToken;
    private String baseUrl = DEFAULT_BASE_URL;
    private Duration timeout = Duration.ofSeconds(20);
    private Duration minRequestInterval = Duration.ofSeconds(1);
    private int maxAttempts = 3;
    private Supplier<Instant> clock = Instant::now;

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
      if (value != null && !value.isBlank()) {
        this.baseUrl = value;
      }
      return this;
    }

    public Builder timeout(Duration value) {
      this.timeout = value;
      return this;
    }

    public Builder minRequestInterval(Duration value) {
      this.minRequestInterval = value;
      return this;
    }

    public Builder maxAttempts(int value) {
      this.maxAttempts = value;
      return this;
    }

    /** Overridable clock, so token-expiry behaviour can be tested without waiting. */
    public Builder clock(Supplier<Instant> value) {
      this.clock = value;
      return this;
    }

    public DaikinOneClient build() {
      return new DaikinOneClient(this);
    }
  }

  /** Convenience for callers that only need the credentials. */
  public static DaikinOneClient create(String apiKey, String email, String integratorToken) {
    return builder().apiKey(apiKey).email(email).integratorToken(integratorToken).build();
  }

  /** Exposed so the bridge can log which endpoint it is talking to. */
  @Override
  public String baseUrl() {
    return baseUrl;
  }
}
