package io.github.chelin1974.daikinhome;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server standing in for the Daikin cloud.
 *
 * <p>Tests run against this rather than a mocked HTTP client, so header handling, status
 * codes, URL encoding and JSON parsing are all genuinely exercised.
 */
final class FakeDaikinCloud implements AutoCloseable {

  static final String API_KEY = "test-key";
  static final String ACCESS_TOKEN = "access-1";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpServer server;
  private final String baseUrl;

  final Map<String, Object> device = new LinkedHashMap<>();
  final List<Map<String, Object>> writes = new CopyOnWriteArrayList<>();
  final AtomicInteger tokenRequests = new AtomicInteger();
  final AtomicInteger deviceReads = new AtomicInteger();

  /** Status to answer PUT /msp with; 200 means accept. */
  volatile int mspStatus = 200;

  /** When > 0, the next N device reads fail with a 500 before succeeding. */
  final AtomicInteger failNextDeviceReads = new AtomicInteger();

  /** Optional Retry-After header value for a one-shot 429 on the next device read. */
  volatile String rateLimitNextReadWithRetryAfter;

  /**
   * The token this cloud currently considers valid.
   *
   * <p>Changing it simulates a token being revoked or rotated behind the client's back —
   * previously issued tokens then start coming back 401.
   */
  volatile String validToken = ACCESS_TOKEN;

  /** Invalidates any token the client already holds. */
  void rotateToken(String next) {
    validToken = next;
  }

  FakeDaikinCloud() throws IOException {
    device.put("id", "thermostat-1");
    device.put("name", "Hallway");
    device.put("model", "ONEPLUS");
    device.put("firmwareVersion", "3.4.1");
    device.put("mode", 3);
    device.put("equipmentStatus", 3);
    device.put("heatSetpoint", 20.0);
    device.put("coolSetpoint", 24.0);
    device.put("setpointMinimum", 10.0);
    device.put("setpointMaximum", 32.2);
    device.put("setpointDelta", 2.0);
    device.put("tempIndoor", 19.5);
    device.put("humIndoor", 41.0);
    device.put("tempOutdoor", 7.2);
    device.put("scheduleEnabled", true);

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  String baseUrl() {
    return baseUrl;
  }

  String deviceId() {
    return (String) device.get("id");
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);

    if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
      respond(exchange, 403, Map.of("message", "bad api key"), null);
      return;
    }

    if (method.equals("POST") && path.equals("/v1/token")) {
      tokenRequests.incrementAndGet();
      respond(
          exchange,
          200,
          Map.of("accessToken", validToken, "accessTokenExpiresIn", 3600),
          null);
      return;
    }

    if (!("Bearer " + validToken)
        .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      respond(exchange, 401, Map.of("message", "bad token"), null);
      return;
    }

    if (method.equals("GET") && path.equals("/v1/devices")) {
      respond(
          exchange,
          200,
          List.of(
              Map.of(
                  "locationName",
                  "Home",
                  "devices",
                  List.of(
                      Map.of(
                          "id", device.get("id"),
                          "name", device.get("name"),
                          "model", device.get("model"),
                          "firmwareVersion", device.get("firmwareVersion"))))),
          null);
      return;
    }

    if (method.equals("GET") && path.equals("/v1/devices/" + deviceId())) {
      String retryAfter = rateLimitNextReadWithRetryAfter;
      if (retryAfter != null) {
        rateLimitNextReadWithRetryAfter = null;
        respond(exchange, 429, Map.of("message", "slow down"), retryAfter);
        return;
      }
      if (failNextDeviceReads.get() > 0) {
        failNextDeviceReads.decrementAndGet();
        respond(exchange, 500, Map.of("message", "boom"), null);
        return;
      }
      deviceReads.incrementAndGet();
      respond(exchange, 200, device, null);
      return;
    }

    if (method.equals("PUT") && path.equals("/v1/devices/" + deviceId() + "/msp")) {
      if (mspStatus != 200) {
        respond(exchange, mspStatus, Map.of("message", "setpoints out of range"), null);
        return;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = MAPPER.readValue(body, Map.class);
      writes.add(parsed);
      device.putAll(parsed);
      respond(exchange, 200, Map.of("message", "ok"), null);
      return;
    }

    respond(exchange, 404, Map.of("message", "no route for " + method + " " + path), null);
  }

  private void respond(HttpExchange exchange, int status, Object payload, String retryAfter)
      throws IOException {
    byte[] bytes = MAPPER.writeValueAsBytes(payload);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    if (retryAfter != null) {
      exchange.getResponseHeaders().add("Retry-After", retryAfter);
    }
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    try (in) {
      return in.readAllBytes();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
