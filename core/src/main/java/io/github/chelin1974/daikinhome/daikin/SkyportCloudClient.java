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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for the cloud behind the SkyportHome app ({@code api.skyportcloud.com}).
 *
 * <p>In 2026 Daikin renamed the Daikin One Home app to SkyportHome and began moving accounts,
 * one at a time, to a new backend. A moved account can no longer log in at
 * {@code api.daikinskyport.com} — it gets {@code 400 NotAuthorizedException} even with the
 * right password — so this client talks to the new host with the same email and password
 * the app uses.
 *
 * <p>This API is not published. Endpoints and payloads come from the SkyportHome web client,
 * as mapped in <a href="https://github.com/apetrycki/daikinskyport/issues/156">
 * apetrycki/daikinskyport#156</a>; {@link SkyportPayload} translates them into the Open API
 * model the rest of the bridge uses.
 *
 * <p>Pacing, retries and rate-limit handling follow {@link DaikinOneClient}: a floor between
 * requests, exponential backoff on 5xx and network failures, a pause on 429, and no retries
 * at all on rejected credentials, so a wrong password cannot lock the account.
 *
 * <p>The password is held in memory only and never logged.
 *
 * <p>This class is thread-safe.
 */
public final class SkyportCloudClient implements DaikinCloud {

  private static final Logger log = LoggerFactory.getLogger(SkyportCloudClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static final String DEFAULT_BASE_URL = "https://api.skyportcloud.com";

  /** Log in again this long before the session actually expires. */
  private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(60);

  /** Used when the login response omits {@code ExpiresIn}. Observed value is 3600. */
  private static final Duration FALLBACK_TOKEN_TTL = Duration.ofMinutes(30);

  private final String email;
  private final String password;
  private final String baseUrl;
  private final Duration timeout;
  private final Duration minRequestInterval;
  private final int maxAttempts;
  private final Supplier<Instant> clock;
  private final HttpClient http;

  /**
   * Names and locations from the last listing.
   *
   * <p>The live endpoint carries no name, so the summary is what labels a device read.
   */
  private final Map<String, DaikinDeviceSummary> summaries = new ConcurrentHashMap<>();

  /** Guards the session fields and the pacing fields below. */
  private final Object lock = new Object();

  private String accessToken;
  private Instant accessTokenExpiresAt = Instant.EPOCH;
  private Instant lastRequestStartedAt = Instant.EPOCH;
  private Instant pausedUntil = Instant.EPOCH;

  private SkyportCloudClient(Builder builder) {
    if (isBlank(builder.email) || isBlank(builder.password)) {
      throw new DaikinAuthException("email and password are both required for the SkyportHome cloud");
    }
    this.email = builder.email.trim();
    // Not trimmed: a password may legitimately start or end with a space.
    this.password = builder.password;
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

  @Override
  public List<DaikinDeviceSummary> getDevices() {
    List<DaikinDeviceSummary> devices = SkyportPayload.toSummaries(request("GET", "/devices", null));
    devices.forEach(device -> summaries.put(device.id(), device));
    return devices;
  }

  @Override
  public DaikinDevice getDevice(String deviceId) {
    DaikinDeviceSummary summary =
        summaries.getOrDefault(deviceId, new DaikinDeviceSummary(deviceId, deviceId, null, null, null));
    JsonNode live = request("GET", "/devices/" + encode(deviceId) + "/live", null);
    return SkyportPayload.toDevice(summary, live);
  }

  @Override
  public void updateDevice(String deviceId, Map<String, Object> update) {
    Map<String, Object> command = SkyportPayload.toMspCommand(update);
    log.debug("POST /cmd/{}/msp {}", deviceId, command);
    request("POST", "/cmd/" + encode(deviceId) + "/msp", command);
  }

  @Override
  public void invalidateToken() {
    synchronized (lock) {
      accessToken = null;
      accessTokenExpiresAt = Instant.EPOCH;
    }
  }

  @Override
  public String baseUrl() {
    return baseUrl;
  }

  // ---------------------------------------------------------------- requests

  private JsonNode request(String method, String path, Object body) {
    RuntimeException lastError = null;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return dispatch(method, path, body, accessToken(), attempt);
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

    throw lastError == null ? new IllegalStateException("request loop ended without a result") : lastError;
  }

  private void backoff(String method, String path, int attempt, RuntimeException cause) {
    long millis = (long) Math.pow(2, attempt - 1) * 1_000L;
    log.debug(
        "{} {} failed (attempt {}/{}), retrying in {}ms: {}",
        method, path, attempt, maxAttempts, millis, cause.getMessage());
    sleep(millis);
  }

  /**
   * Sends one request.
   *
   * @param bearer the session token, or null for the login call itself
   */
  private JsonNode dispatch(String method, String path, Object body, String bearer, int attempt) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Accept", "application/json");

    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
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
          method + " " + path + " could not reach the SkyportHome cloud: " + describe(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DaikinNetworkException(method + " " + path + " was interrupted", e);
    }

    int status = response.statusCode();
    String text = response.body() == null ? "" : response.body();

    // The session can end between our expiry estimate and the request. Log in again once
    // and let the retry loop take another pass.
    if (status == 401 && bearer != null && attempt == 1) {
      log.debug("session rejected, logging in again");
      invalidateToken();
      throw new DaikinApiException("session rejected", 503, text);
    }

    if (status == 429) {
      Duration retryAfter =
          DaikinOneClient.parseRetryAfter(response.headers().firstValue("retry-after").orElse(null), now())
              .orElse(Duration.ofSeconds(60));
      pauseFor(retryAfter);
      log.warn("SkyportHome cloud rate limited this bridge; pausing requests for {}s", retryAfter.toSeconds());
    }

    if (status == 401 || status == 403) {
      throw new DaikinAuthException(
          "the SkyportHome cloud rejected the login for "
              + email
              + " (HTTP "
              + status
              + messageSuffix(text)
              + "). Check the email and password you use in the SkyportHome app.");
    }

    if (status < 200 || status >= 300) {
      throw new DaikinApiException(
          method + " " + path + " failed with HTTP " + status + ": " + SkyportPayload.truncate(text),
          status,
          text);
    }

    if (text.isBlank()) {
      return null;
    }

    // Served as text/plain, so parse whatever came back rather than trusting the header.
    try {
      return MAPPER.readTree(text);
    } catch (Exception e) {
      throw new DaikinResponseException(
          method + " " + path + " returned invalid JSON: " + SkyportPayload.truncate(text), e);
    }
  }

  // ----------------------------------------------------------------- session

  private String accessToken() {
    synchronized (lock) {
      if (accessToken != null && now().isBefore(accessTokenExpiresAt)) {
        return accessToken;
      }
    }
    return login();
  }

  /**
   * Logs in with email and password.
   *
   * <p>A refresh endpoint exists, but it needs a still-valid bearer token, so it buys
   * nothing over simply logging in again when the session runs out.
   */
  private synchronized String login() {
    synchronized (lock) {
      if (accessToken != null && now().isBefore(accessTokenExpiresAt)) {
        return accessToken;
      }
    }

    log.debug("logging in to the SkyportHome cloud as {}", email);

    JsonNode session;
    try {
      // Attempt 2 so the session-rejected branch cannot fire for the login itself.
      session = dispatch("POST", "/users/auth/login", Map.of("email", email, "password", password), null, 2);
    } catch (DaikinApiException e) {
      // The old host answers bad credentials with 400 NotAuthorizedException; treat any 400
      // from the login the same way, so a wrong password is never retried.
      if (e.status() == 400) {
        throw new DaikinAuthException(
            "the SkyportHome cloud rejected the login for "
                + email
                + " (HTTP 400"
                + messageSuffix(e.body())
                + "). Check the email and password you use in the SkyportHome app.");
      }
      throw e;
    }

    String issued = session == null ? null : session.path("AccessToken").asText(null);
    if (isBlank(issued)) {
      // Older responses used camelCase; accept either.
      issued = session == null ? null : session.path("accessToken").asText(null);
    }
    if (isBlank(issued)) {
      throw new DaikinAuthException(
          "the SkyportHome login succeeded but returned no access token. If two-factor"
              + " authentication is on for this account, this bridge cannot complete it yet.");
    }

    long ttlSeconds = session.path("ExpiresIn").asLong(session.path("expiresIn").asLong(0));
    Duration ttl = ttlSeconds > 0 ? Duration.ofSeconds(ttlSeconds) : FALLBACK_TOKEN_TTL;
    Duration effective = ttl.minus(TOKEN_EXPIRY_MARGIN);
    if (effective.compareTo(Duration.ofSeconds(30)) < 0) {
      effective = Duration.ofSeconds(30);
    }

    synchronized (lock) {
      accessToken = issued;
      accessTokenExpiresAt = now().plus(effective);
    }

    log.debug("logged in, session valid for {}s", ttl.toSeconds());
    return issued;
  }

  // ------------------------------------------------------------------ pacing

  private void awaitSlot() {
    while (true) {
      long waitMillis;
      synchronized (lock) {
        Instant now = now();
        long untilPause = Duration.between(now, pausedUntil).toMillis();
        long untilFloor = Duration.between(now, lastRequestStartedAt.plus(minRequestInterval)).toMillis();
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

  // ----------------------------------------------------------------- helpers

  /** ": message" from a JSON error body, or nothing. Never echoes the request. */
  private static String messageSuffix(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      String message = MAPPER.readTree(body).path("message").asText("");
      return message.isBlank() ? "" : ": " + SkyportPayload.truncate(message);
    } catch (Exception e) {
      return "";
    }
  }

  private Instant now() {
    return clock.get();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DaikinNetworkException("interrupted while waiting to call the SkyportHome cloud", e);
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
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

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
    private String email;
    private String password;
    private String baseUrl = DEFAULT_BASE_URL;
    private Duration timeout = Duration.ofSeconds(20);
    private Duration minRequestInterval = Duration.ofSeconds(1);
    private int maxAttempts = 3;
    private Supplier<Instant> clock = Instant::now;

    public Builder email(String value) {
      this.email = value;
      return this;
    }

    public Builder password(String value) {
      this.password = value;
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

    /** Overridable clock, so session-expiry behaviour can be tested without waiting. */
    public Builder clock(Supplier<Instant> value) {
      this.clock = value;
      return this;
    }

    public SkyportCloudClient build() {
      return new SkyportCloudClient(this);
    }
  }
}
