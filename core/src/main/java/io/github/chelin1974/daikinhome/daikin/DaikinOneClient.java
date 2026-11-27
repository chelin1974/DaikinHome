package io.github.chelin1974.daikinhome.daikin;

import static io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinApiException;
import static io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import static io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinException;
import static io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinNetworkException;
import static io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinResponseException;

import io.github.chelin1974.daikinhome.daikin.api.DefaultApi;
import io.github.chelin1974.daikinhome.daikin.invoker.ApiClient;
import io.github.chelin1974.daikinhome.daikin.invoker.ApiException;
import io.github.chelin1974.daikinhome.daikin.model.Device;
import io.github.chelin1974.daikinhome.daikin.model.DeviceSummary;
import io.github.chelin1974.daikinhome.daikin.model.DeviceUpdate;
import io.github.chelin1974.daikinhome.daikin.model.Location;
import io.github.chelin1974.daikinhome.daikin.model.TokenRequest;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for the Daikin One Cloud Open API.
 *
 * <p>The HTTP and JSON work is generated from {@code
 * core/src/main/resources/openapi/daikin-one.yaml} at build time; see {@code core/pom.xml}.
 * What is left here is everything the specification cannot express: token acquisition and
 * renewal, a floor between requests, retrying the failures worth retrying, and translating
 * the generated {@link ApiException} into this package's exceptions.
 *
 * <p>All methods block. Callers run them on a background thread — never on a HomeKit
 * characteristic read, which must return immediately.
 *
 * <p>This class is thread-safe.
 */
public final class DaikinOneClient implements DaikinCloud {

  private static final Logger log = LoggerFactory.getLogger(DaikinOneClient.class);

  public static final String DEFAULT_BASE_URL = "https://integrator-api.daikinskyport.com";

  /** Re-authenticate this long before the token actually expires. */
  private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofSeconds(60);

  /** Used when the token response omits {@code accessTokenExpiresIn}. */
  private static final Duration FALLBACK_TOKEN_TTL = Duration.ofMinutes(10);

  /** The keys {@link #updateDevice} knows how to send; anything else is a mistake, not a hint. */
  private static final Set<String> UPDATE_KEYS = Set.of("mode", "heatSetpoint", "coolSetpoint");

  private final String apiKey;
  private final String email;
  private final String integratorToken;
  private final String baseUrl;
  private final Duration minRequestInterval;
  private final int maxAttempts;
  private final Supplier<Instant> clock;
  private final DefaultApi api;

  /** Guards the token fields and the request pacing fields below. */
  private final Object lock = new Object();

  private String accessToken;
  private Instant accessTokenExpiresAt = Instant.EPOCH;
  private Instant lastRequestStartedAt = Instant.EPOCH;
  private Instant pausedUntil = Instant.EPOCH;

  private DaikinOneClient(final Builder builder) {
    if (isBlank(builder.apiKey) || isBlank(builder.email) || isBlank(builder.integratorToken)) {
      throw new DaikinAuthException("apiKey, email and integratorToken are all required");
    }
    this.apiKey = builder.apiKey.trim();
    this.email = builder.email.trim();
    this.integratorToken = builder.integratorToken.trim();
    this.baseUrl = builder.baseUrl.replaceAll("/+$", "");
    this.minRequestInterval = builder.minRequestInterval;
    this.maxAttempts = Math.max(1, builder.maxAttempts);
    this.clock = builder.clock;

    final var client =
        new ApiClient(
                HttpClient.newBuilder().connectTimeout(builder.timeout),
                ApiClient.createDefaultObjectMapper(),
                this.baseUrl)
            .setConnectTimeout(builder.timeout)
            .setReadTimeout(builder.timeout);
    this.api = new DefaultApi(client);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Lists every thermostat on the account, flattening Daikin's location grouping. */
  @Override
  public List<DaikinDeviceSummary> getDevices() {
    return flatten(call("GET /v1/devices", AUTHENTICATED, () -> api.listDevices(authHeaders())));
  }

  /** Full state for one thermostat. */
  @Override
  public DaikinDevice getDevice(final String deviceId) {
    final var what = "GET /v1/devices/" + ApiClient.urlEncode(String.valueOf(deviceId));
    final var device = call(what, AUTHENTICATED, () -> api.getDevice(deviceId, authHeaders()));
    if (device == null) {
      throw new DaikinResponseException("device " + deviceId + " returned an empty payload");
    }
    return toDaikinDevice(device);
  }

  /**
   * Updates mode and setpoints.
   *
   * <p>Daikin's examples always send mode together with both setpoints, and the thermostat
   * validates them as a set (heat must sit below cool by at least {@code setpointDelta}),
   * so callers pass a complete payload rather than a partial patch.
   */
  @Override
  public void updateDevice(final String deviceId, final Map<String, Object> update) {
    final var body = toDeviceUpdate(update);
    final var what = "PUT /v1/devices/" + ApiClient.urlEncode(String.valueOf(deviceId)) + "/msp";
    call(
        what,
        AUTHENTICATED,
        () -> {
          api.updateDevice(deviceId, body, authHeaders());
          return null;
        });
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

  private static final boolean AUTHENTICATED = false;
  private static final boolean ANONYMOUS = true;

  /** One call to the generated API, as {@link #call} invokes it. */
  @FunctionalInterface
  private interface Operation<T> {
    T invoke() throws ApiException;
  }

  /**
   * Runs one generated API call with this client's retry budget, pacing and exception
   * translation around it.
   *
   * @param what the method and path, for log lines and messages
   * @param anonymous {@link #ANONYMOUS} for the token call itself, which carries no bearer
   *     token and so must not trigger a re-authentication when it is rejected
   */
  private <T> T call(final String what, final boolean anonymous, final Operation<T> operation) {
    DaikinException lastError = null;

    for (var attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        if (!anonymous) {
          // Before the pacing slot, not inside the operation: authenticating is itself a
          // paced request, and the floor belongs between the token call and this one rather
          // than only in front of the pair.
          accessToken();
        }
        awaitSlot();
        return operation.invoke();
      } catch (final ApiException e) {
        final var failure = translate(what, anonymous, attempt, e);
        final var retryable =
            failure instanceof DaikinNetworkException
                || (failure instanceof DaikinApiException http && http.isRetryable());
        if (!retryable || attempt == maxAttempts) {
          throw failure;
        }
        lastError = failure;
        backoff(what, attempt, failure);
      }
    }

    // Unreachable: the loop returns, or throws on its last attempt.
    throw lastError == null
        ? new IllegalStateException("request loop ended without a result")
        : lastError;
  }

  /**
   * Turns a generated {@link ApiException} into this package's exceptions.
   *
   * <p>Also the place where a rate limit is noted and a stale token is dropped, because the
   * response headers and the attempt number are both only in scope here.
   */
  private DaikinException translate(
      final String what, final boolean anonymous, final int attempt, final ApiException error) {

    final var status = error.getCode();

    // Status 0 means the call never got an HTTP response: the generated client reports a
    // transport failure and an unreadable body the same way, and only the cause separates them.
    if (status == 0) {
      final var cause = error.getCause();
      if (cause instanceof IOException || cause instanceof InterruptedException) {
        return new DaikinNetworkException(
            what + " could not reach the Daikin API: " + describe(error), error);
      }
      return new DaikinResponseException(
          what + " returned a reply this client could not read: " + describe(error), error);
    }

    final var body = error.getResponseBody() == null ? "" : error.getResponseBody();

    // A token can be revoked between our expiry estimate and the actual request.
    // Re-authenticate once and let the retry loop take another pass.
    if (status == 401 && !anonymous && attempt == 1) {
      log.debug("access token rejected, re-authenticating");
      invalidateToken();
      return new DaikinApiException("access token rejected", 503, body);
    }

    if (status == 429) {
      final var retryAfter =
          parseRetryAfter(header(error, "retry-after"), now()).orElse(Duration.ofSeconds(60));
      pauseFor(retryAfter);
      log.warn(
          "Daikin API rate limited this integration; pausing requests for {}s",
          retryAfter.toSeconds());
    }

    if (status == 401 || status == 403) {
      return new DaikinAuthException(
          "Daikin API rejected the credentials for "
              + what
              + " (HTTP "
              + status
              + "). Check the API key, account email and integrator token in the Daikin One app.");
    }

    return new DaikinApiException(
        what + " failed with HTTP " + status + ": " + truncate(body), status, body);
  }

  private static String header(final ApiException error, final String name) {
    final var headers = error.getResponseHeaders();
    return headers == null ? null : headers.firstValue(name).orElse(null);
  }

  private void backoff(final String what, final int attempt, final RuntimeException cause) {
    final var millis = (long) Math.pow(2, attempt - 1) * 1_000L;
    log.debug(
        "{} failed (attempt {}/{}), retrying in {}ms: {}",
        what,
        attempt,
        maxAttempts,
        millis,
        cause.getMessage());
    sleep(millis);
  }

  /** Every authenticated call carries the API key and the bearer token. */
  private Map<String, String> authHeaders() {
    return Map.of("x-api-key", apiKey, "Authorization", "Bearer " + accessToken());
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

    final var request = new TokenRequest().email(email).integratorToken(integratorToken);

    // The one call that carries the API key but no bearer token - asking for a token must not
    // require one.
    final var token =
        call(
            "POST /v1/token",
            ANONYMOUS,
            () -> api.issueToken(request, Map.of("x-api-key", apiKey)));

    final var issued = token == null ? null : token.getAccessToken();
    if (isBlank(issued)) {
      throw new DaikinAuthException("the Daikin token endpoint did not return an accessToken");
    }

    final var reported = token.getAccessTokenExpiresIn();
    final var ttl =
        Duration.ofSeconds(
            reported != null && reported > 0 ? reported : FALLBACK_TOKEN_TTL.toSeconds());

    var effective = ttl.minus(TOKEN_EXPIRY_MARGIN);
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
      final long waitMillis;
      synchronized (lock) {
        final var now = now();
        final var untilPause = ceilMillis(Duration.between(now, pausedUntil));
        final var untilFloor =
            ceilMillis(Duration.between(now, lastRequestStartedAt.plus(minRequestInterval)));
        waitMillis = Math.max(untilPause, untilFloor);
        if (waitMillis <= 0) {
          lastRequestStartedAt = now;
          return;
        }
      }
      sleep(waitMillis);
    }
  }

  /**
   * How long to sleep for a remaining duration, rounded up.
   *
   * <p>{@link Duration#toMillis()} truncates towards zero, which makes a floor that is not
   * quite a floor: with 299.7ms left it sleeps 299, comes round again, truncates the
   * remaining 0.7ms to zero and goes early. One millisecond under per gap is harmless to a
   * thermostat and fatal to a test that asserts the floor held, and the point of the floor
   * is to stay the right side of Daikin's rate limiter, so round up instead.
   *
   * <p>A duration already past comes back as zero, so the caller stops waiting.
   */
  private static long ceilMillis(final Duration remaining) {
    final var nanos = remaining.toNanos();
    return nanos <= 0 ? 0L : (nanos + 999_999L) / 1_000_000L;
  }

  private void pauseFor(final Duration duration) {
    synchronized (lock) {
      final var candidate = now().plus(duration);
      if (candidate.isAfter(pausedUntil)) {
        pausedUntil = candidate;
      }
    }
  }

  // --------------------------------------------------------------- the models

  /**
   * Flattens the listing into a plain list, carrying each location's name onto its
   * thermostats.
   *
   * <p>A location with no devices, and an entry with no id, are skipped rather than
   * rejected: one unusable entry should not cost the account its other thermostats.
   */
  public static List<DaikinDeviceSummary> flatten(final List<Location> locations) {
    if (locations == null) {
      throw new DaikinResponseException("GET /v1/devices returned no body");
    }

    final var devices = new ArrayList<DaikinDeviceSummary>();
    for (final var location : locations) {
      if (location == null || location.getDevices() == null) {
        continue;
      }
      for (final var device : location.getDevices()) {
        summarise(device, location.getLocationName()).ifPresent(devices::add);
      }
    }
    return devices;
  }

  private static Optional<DaikinDeviceSummary> summarise(
      final DeviceSummary device, final String locationName) {

    if (device == null || isBlank(device.getId())) {
      return Optional.empty();
    }

    // Daikin can return names with stray whitespace ("East Hallway "). HomeKit wants names
    // to start and end with a letter or number, so trim before they reach an accessory.
    final var id = device.getId();
    final var reported = device.getName();
    final var name = isBlank(reported) ? id : reported.trim();

    return Optional.of(
        new DaikinDeviceSummary(
            id, name, device.getModel(), device.getFirmwareVersion(), locationName));
  }

  /**
   * Copies the generated model onto the bridge's own.
   *
   * <p>{@link DaikinDevice} stays hand-written because the rest of the bridge reads it, two
   * backends fill it, and it is the vocabulary {@link DaikinCloud} is defined in. Fields the
   * spec does not name are carried across too, so {@code --probe} still shows what a newer
   * firmware has started reporting.
   */
  private static DaikinDevice toDaikinDevice(final Device source) {
    final var device = new DaikinDevice();
    device.id = source.getId();
    device.name = source.getName();
    device.model = source.getModel();
    device.firmwareVersion = source.getFirmwareVersion();
    device.equipmentStatus = source.getEquipmentStatus();
    device.mode = source.getMode();
    device.modeLimit = source.getModeLimit();
    device.modeEmHeatAvailable = source.getModeEmHeatAvailable();
    device.fan = source.getFan();
    device.fanCirculate = source.getFanCirculate();
    device.fanCirculateSpeed = source.getFanCirculateSpeed();
    device.heatSetpoint = source.getHeatSetpoint();
    device.coolSetpoint = source.getCoolSetpoint();
    device.setpointDelta = source.getSetpointDelta();
    device.setpointMinimum = source.getSetpointMinimum();
    device.setpointMaximum = source.getSetpointMaximum();
    device.tempIndoor = source.getTempIndoor();
    device.humIndoor = source.getHumIndoor();
    device.tempOutdoor = source.getTempOutdoor();
    device.humOutdoor = source.getHumOutdoor();
    device.scheduleEnabled = source.getScheduleEnabled();
    device.geofencingEnabled = source.getGeofencingEnabled();

    final var extra = source.getAdditionalProperties();
    if (extra != null) {
      extra.forEach(device::setAdditional);
    }
    return device;
  }

  /**
   * Builds the {@code msp} body from the update map {@link DaikinCloud} is defined in.
   *
   * <p>An unknown key is rejected rather than ignored: silently dropping one would leave a
   * caller watching a write that reported success and changed nothing.
   */
  private static DeviceUpdate toDeviceUpdate(final Map<String, Object> update) {
    if (update == null) {
      throw new DaikinResponseException("an msp update needs a body");
    }

    final var unknown = new LinkedHashSet<>(update.keySet());
    unknown.removeAll(UPDATE_KEYS);
    if (!unknown.isEmpty()) {
      throw new DaikinResponseException(
          "the Daikin Open API client cannot send " + unknown + "; it sends " + UPDATE_KEYS);
    }

    return new DeviceUpdate()
        .mode(asInteger(update.get("mode")))
        .heatSetpoint(asDouble(update.get("heatSetpoint")))
        .coolSetpoint(asDouble(update.get("coolSetpoint")));
  }

  private static Integer asInteger(final Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private static Double asDouble(final Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }

  // ------------------------------------------------------------------ helpers

  /** Retry-After is either delta-seconds or an HTTP date. */
  public static Optional<Duration> parseRetryAfter(final String value, final Instant now) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }

    try {
      final var seconds = Long.parseLong(value.trim());
      return Optional.of(Duration.ofSeconds(Math.max(0, seconds)));
    } catch (final NumberFormatException ignored) {
      // Not delta-seconds; fall through to the HTTP-date form.
    }

    try {
      final var when = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value.trim()));
      final var delay = Duration.between(now, when);
      return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
    } catch (final RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private Instant now() {
    return clock.get();
  }

  private static void sleep(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DaikinNetworkException("interrupted while waiting to call the Daikin API", e);
    }
  }

  private static String truncate(final String value) {
    return value.length() > 300 ? value.substring(0, 300) + "..." : value;
  }

  private static boolean isBlank(final String value) {
    return value == null || value.isBlank();
  }

  /**
   * A usable description of a failure.
   *
   * <p>Several of the exceptions the HTTP client throws carry a null message — a connection
   * reset during shutdown, for one — and "could not reach the Daikin API: null" tells nobody
   * anything. Fall back to the exception type, and to its cause when that is also blank.
   */
  private static String describe(final Throwable error) {
    for (var current = error; current != null; current = current.getCause()) {
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

    public Builder apiKey(final String value) {
      this.apiKey = value;
      return this;
    }

    public Builder email(final String value) {
      this.email = value;
      return this;
    }

    public Builder integratorToken(final String value) {
      this.integratorToken = value;
      return this;
    }

    public Builder baseUrl(final String value) {
      if (value != null && !value.isBlank()) {
        this.baseUrl = value;
      }
      return this;
    }

    public Builder timeout(final Duration value) {
      this.timeout = value;
      return this;
    }

    public Builder minRequestInterval(final Duration value) {
      this.minRequestInterval = value;
      return this;
    }

    public Builder maxAttempts(final int value) {
      this.maxAttempts = value;
      return this;
    }

    /** Overridable clock, so token-expiry behaviour can be tested without waiting. */
    public Builder clock(final Supplier<Instant> value) {
      this.clock = value;
      return this;
    }

    public DaikinOneClient build() {
      return new DaikinOneClient(this);
    }
  }

  /** Convenience for callers that only need the credentials. */
  public static DaikinOneClient create(
      final String apiKey, final String email, final String integratorToken) {
    return builder().apiKey(apiKey).email(email).integratorToken(integratorToken).build();
  }

  /** Exposed so the bridge can log which endpoint it is talking to. */
  @Override
  public String baseUrl() {
    return baseUrl;
  }
}
