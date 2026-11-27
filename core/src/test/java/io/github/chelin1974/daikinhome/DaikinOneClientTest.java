package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinApiException;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinNetworkException;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinResponseException;
import io.github.chelin1974.daikinhome.daikin.DaikinOneClient;
import io.github.chelin1974.daikinhome.daikin.model.DeviceSummary;
import io.github.chelin1974.daikinhome.daikin.model.Location;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DaikinOneClientTest {

  @Test
  @DisplayName("trims names, since Daikin returns some with trailing spaces")
  void trimsNames() {
    List<DaikinDeviceSummary> devices =
        DaikinOneClient.flatten(
            List.of(location(null, summary("1", "East Hallway "), summary("2", "  "))));
    assertEquals("East Hallway", devices.get(0).name());
    assertEquals("2", devices.get(1).name());
  }

  /** The generated listing models, as Daikin returns them. */
  private static Location location(String name, DeviceSummary... devices) {
    return new Location().locationName(name).devices(List.of(devices));
  }

  private static DeviceSummary summary(String id, String name) {
    return new DeviceSummary().id(id).name(name);
  }

  private FakeDaikinCloud cloud;

  @BeforeEach
  void startCloud() throws IOException {
    cloud = new FakeDaikinCloud();
  }

  @AfterEach
  void stopCloud() {
    cloud.close();
  }

  private DaikinOneClient client() {
    return clientBuilder().build();
  }

  private DaikinOneClient.Builder clientBuilder() {
    return DaikinOneClient.builder()
        .apiKey(FakeDaikinCloud.API_KEY)
        .email("user@example.com")
        .integratorToken("integrator")
        .baseUrl(cloud.baseUrl())
        .minRequestInterval(Duration.ZERO);
  }

  @Test
  @DisplayName("exchanges the integrator token before the first API call")
  void authenticatesFirst() {
    List<DaikinDeviceSummary> devices = client().getDevices();

    assertEquals(1, cloud.tokenRequests.get());
    assertEquals(1, devices.size());
    assertEquals("Hallway", devices.get(0).name());
    assertEquals("Home", devices.get(0).locationName());
  }

  @Test
  @DisplayName("reuses a valid token across calls")
  void reusesToken() {
    DaikinOneClient client = client();
    client.getDevices();
    client.getDevices();

    assertEquals(1, cloud.tokenRequests.get());
  }

  @Test
  @DisplayName("re-authenticates once the token expires")
  void reauthenticatesOnExpiry() {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-10T12:00:00Z"));
    DaikinOneClient client = clientBuilder().clock(now::get).build();

    client.getDevices();
    now.set(now.get().plus(Duration.ofHours(2)));
    client.getDevices();

    assertEquals(2, cloud.tokenRequests.get());
  }

  @Test
  @DisplayName("collapses concurrent callers onto a single token request")
  void collapsesConcurrentAuth() throws Exception {
    DaikinOneClient client = client();

    List<Thread> threads =
        List.of(
            new Thread(client::getDevices),
            new Thread(client::getDevices),
            new Thread(client::getDevices));
    threads.forEach(Thread::start);
    for (Thread thread : threads) {
      thread.join(10_000);
    }

    assertEquals(1, cloud.tokenRequests.get());
  }

  @Test
  @DisplayName("re-authenticates and retries when a token is rejected mid-flight")
  void recoversFromRejectedToken() {
    DaikinOneClient client = client();
    client.getDevices();
    assertEquals(1, cloud.tokenRequests.get());

    // The cloud rotates its token. The client still holds the old one and believes it is
    // valid, so the next call comes back 401 and has to recover on its own.
    cloud.rotateToken("access-2");
    DaikinDevice device = client.getDevice(cloud.deviceId());

    assertNotNull(device);
    assertEquals("thermostat-1", device.id);
    assertEquals(2, cloud.tokenRequests.get(), "expected the client to re-authenticate");
  }

  @Test
  @DisplayName("surfaces bad credentials without retrying")
  void badCredentialsAreNotRetried() {
    DaikinOneClient client =
        DaikinOneClient.builder()
            .apiKey("wrong-key")
            .email("user@example.com")
            .integratorToken("integrator")
            .baseUrl(cloud.baseUrl())
            .minRequestInterval(Duration.ZERO)
            .build();

    assertThrows(DaikinAuthException.class, client::getDevices);
    assertEquals(0, cloud.tokenRequests.get());
  }

  @Test
  @DisplayName("refuses to build without credentials")
  void requiresCredentials() {
    assertThrows(
        DaikinAuthException.class,
        () -> DaikinOneClient.builder().apiKey("").email("").integratorToken("").build());
  }

  @Test
  @DisplayName("parses the full device payload")
  void parsesDevice() {
    DaikinDevice device = client().getDevice(cloud.deviceId());

    assertEquals(3, device.mode);
    assertEquals(20.0, device.heatSetpoint);
    assertEquals(24.0, device.coolSetpoint);
    assertEquals(19.5, device.tempIndoor);
    assertEquals(7.2, device.tempOutdoor);
    assertEquals(Boolean.TRUE, device.scheduleEnabled);
  }

  /**
   * The generated model only names the fields the spec names, so without the spec's
   * {@code additionalProperties} this would quietly drop everything else - and {@code --probe}
   * exists precisely to show the fields a newer firmware has started reporting.
   */
  @Test
  @DisplayName("keeps fields the spec does not name, so the probe tool can still show them")
  void keepsUnknownFields() {
    cloud.device.put("fanCirculateSpeedHigh", 3);

    DaikinDevice device = client().getDevice(cloud.deviceId());

    assertEquals(3, device.getAdditional().get("fanCirculateSpeedHigh"));
  }

  @Test
  @DisplayName("refuses an msp key it cannot send, rather than dropping it")
  void refusesUnknownMspKeys() {
    DaikinResponseException error =
        assertThrows(
            DaikinResponseException.class,
            () -> client().updateDevice(cloud.deviceId(), Map.of("fan", 1)));

    assertTrue(error.getMessage().contains("fan"), error.getMessage());
    assertEquals(0, cloud.writes.size());
  }

  @Test
  @DisplayName("PUTs the full msp payload")
  void writesMsp() {
    client().updateDevice(cloud.deviceId(), Map.of("mode", 1, "heatSetpoint", 20.0, "coolSetpoint", 24.0));

    assertEquals(1, cloud.writes.size());
    assertEquals(1, cloud.writes.get(0).get("mode"));
    assertEquals(20.0, cloud.writes.get(0).get("heatSetpoint"));
    assertEquals(24.0, cloud.writes.get(0).get("coolSetpoint"));
  }

  @Test
  @DisplayName("retries a 500 and succeeds")
  void retriesServerErrors() {
    cloud.failNextDeviceReads.set(1);

    DaikinDevice device = client().getDevice(cloud.deviceId());

    assertEquals("thermostat-1", device.id);
    assertEquals(1, cloud.deviceReads.get());
  }

  @Test
  @DisplayName("gives up after the attempt budget")
  void givesUpEventually() {
    cloud.failNextDeviceReads.set(10);
    DaikinOneClient client = clientBuilder().maxAttempts(2).build();

    assertThrows(DaikinApiException.class, () -> client.getDevice(cloud.deviceId()));
  }

  @Test
  @DisplayName("does not retry a 400")
  void doesNotRetryClientErrors() {
    cloud.mspStatus = 400;

    DaikinApiException error =
        assertThrows(
            DaikinApiException.class,
            () ->
                client()
                    .updateDevice(
                        cloud.deviceId(),
                        Map.of("mode", 1, "heatSetpoint", 30.0, "coolSetpoint", 20.0)));

    assertEquals(400, error.status());
    assertEquals(0, cloud.writes.size());
  }

  @Test
  @DisplayName("honours Retry-After on a 429")
  void honoursRetryAfter() {
    cloud.rateLimitNextReadWithRetryAfter = "1";

    DaikinDevice device = client().getDevice(cloud.deviceId());

    assertEquals("thermostat-1", device.id);
  }

  @Test
  @DisplayName("wraps a connection failure")
  void wrapsNetworkFailures() {
    DaikinOneClient client =
        DaikinOneClient.builder()
            .apiKey(FakeDaikinCloud.API_KEY)
            .email("user@example.com")
            .integratorToken("integrator")
            // Port 9 is the discard port: reliably refuses or blackholes.
            .baseUrl("http://127.0.0.1:9")
            .timeout(Duration.ofSeconds(5))
            .maxAttempts(1)
            .minRequestInterval(Duration.ZERO)
            .build();

    assertThrows(DaikinNetworkException.class, client::getDevices);
  }

  @Test
  @DisplayName("URL-encodes device ids")
  void encodesDeviceIds() {
    // A space must become %20, not '+', or the path is wrong.
    DaikinApiException error =
        assertThrows(DaikinApiException.class, () -> client().getDevice("a/b c"));

    assertEquals(404, error.status());
    assertTrue(error.getMessage().contains("a%2Fb%20c"), error.getMessage());
  }

  @Test
  @DisplayName("flattens the documented location-grouped listing")
  void normalisesLocationGroupedListing() {
    List<DaikinDeviceSummary> devices =
        DaikinOneClient.flatten(
            List.of(
                location(
                    "Home",
                    new DeviceSummary()
                        .id("1")
                        .name("Upstairs")
                        .model("ONEPLUS")
                        .firmwareVersion("3.1"),
                    summary("2", "Downstairs")),
                location("Cabin", summary("3", "Cabin"))));

    assertEquals(3, devices.size());
    assertEquals(
        new DaikinDeviceSummary("1", "Upstairs", "ONEPLUS", "3.1", "Home"), devices.get(0));
    assertEquals("Home", devices.get(1).locationName());
    assertEquals("Cabin", devices.get(2).locationName());
  }

  @Test
  @DisplayName("falls back to the id when a thermostat has no name, and skips entries with no id")
  void normalisesEdgeCases() {
    assertEquals("1", DaikinOneClient.flatten(List.of(location(null, summary("1", null)))).get(0).name());

    // A location with no devices, and a device with no id, are skipped rather than rejected:
    // one unusable entry should not cost the account its other thermostats.
    assertEquals(
        0,
        DaikinOneClient.flatten(
                List.of(new Location().locationName("Home"), location(null, summary(null, "nameless"))))
            .size());
  }

  @Test
  @DisplayName("rejects a listing with no body at all")
  void rejectsMissingListing() {
    assertThrows(DaikinResponseException.class, () -> DaikinOneClient.flatten(null));
  }

  @Test
  @DisplayName("reads Retry-After as delta-seconds and as an HTTP date")
  void parsesRetryAfter() {
    Instant now = Instant.parse("2026-08-10T12:00:00Z");

    assertEquals(
        Duration.ofSeconds(30), DaikinOneClient.parseRetryAfter("30", now).orElseThrow());
    assertEquals(
        Duration.ofSeconds(30),
        DaikinOneClient.parseRetryAfter("Mon, 10 Aug 2026 12:00:30 GMT", now).orElseThrow());
    // A date in the past must never produce a negative delay.
    assertEquals(
        Duration.ZERO,
        DaikinOneClient.parseRetryAfter("Mon, 10 Aug 2026 11:59:00 GMT", now).orElseThrow());
    assertTrue(DaikinOneClient.parseRetryAfter("later", now).isEmpty());
    assertTrue(DaikinOneClient.parseRetryAfter(null, now).isEmpty());
  }

  @Test
  @DisplayName("keeps a floor between consecutive requests")
  void pacesRequests() {
    DaikinOneClient client = clientBuilder().minRequestInterval(Duration.ofMillis(300)).build();

    long start = System.nanoTime();
    client.getDevices(); // token + listing = 2 requests
    client.getDevices(); // 1 more
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    // Three paced requests means at least two gaps of 300ms.
    assertTrue(elapsedMillis >= 600, "expected pacing to take at least 600ms, took " + elapsedMillis);
  }
}
