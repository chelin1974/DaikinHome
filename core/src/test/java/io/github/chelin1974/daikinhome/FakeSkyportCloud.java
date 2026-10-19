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
 * A real HTTP server standing in for {@code api.skyportcloud.com}.
 *
 * <p>Mirrors the quirks the client has to cope with: {@code AccessToken} with a capital A,
 * JSON served as {@code text/plain}, {@code _id} rather than {@code id}, raw VUT readings,
 * and 401 with a readable message for a wrong password.
 */
final class FakeSkyportCloud implements AutoCloseable {

  static final String EMAIL = "user@example.com";
  static final String PASSWORD = "correct horse";
  static final String DEVICE_ID = "0a1b2c3d-0000-4000-8000-000000000001";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpServer server;
  private final String baseUrl;

  /** Raw live payload, VUT units. Starts as the published sample from a DW6.93 thermostat. */
  final Map<String, Object> live = new LinkedHashMap<>();

  final List<Map<String, Object>> commands = new CopyOnWriteArrayList<>();
  final AtomicInteger logins = new AtomicInteger();
  final AtomicInteger liveReads = new AtomicInteger();
  final List<String> loginBodies = new CopyOnWriteArrayList<>();

  /** When > 0, the next N live reads fail with a 500 before succeeding. */
  final AtomicInteger failNextLiveReads = new AtomicInteger();

  /** Session this cloud currently accepts; changing it simulates expiry. */
  volatile String validToken = "session-1";

  /** Token handed out by the next login. */
  volatile String nextToken = "session-1";

  /** Respond to login with 200 but no AccessToken, as a 2FA challenge might. */
  volatile boolean loginChallenges;

  FakeSkyportCloud() throws IOException {
    live.put("action", "init");
    live.put("status", "connected");
    live.put("deviceId", DEVICE_ID);
    live.put("spaceTemp", 127);
    live.put("coolSP", 122);
    live.put("heatSP", 105);
    live.put("spMin", 117);
    live.put("spMax", 133);
    live.put("mode", 2);
    live.put("eqStatus", 2);
    live.put("fanMode", 0);
    live.put("fanStatus", 1);
    live.put("humidity", 0);
    live.put("wiredTemp", 255);
    live.put("fwVer", "DW6.93");

    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  String baseUrl() {
    return baseUrl;
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);

    if (method.equals("POST") && path.equals("/users/auth/login")) {
      logins.incrementAndGet();
      loginBodies.add(body);
      @SuppressWarnings("unchecked")
      Map<String, Object> credentials = MAPPER.readValue(body, Map.class);
      if (!EMAIL.equals(credentials.get("email")) || !PASSWORD.equals(credentials.get("password"))) {
        respond(exchange, 401, Map.of("message", "Incorrect e-mail or password."));
        return;
      }
      if (loginChallenges) {
        respond(exchange, 200, Map.of("ChallengeName", "SOFTWARE_TOKEN_MFA", "Session", "x"));
        return;
      }
      validToken = nextToken;
      respond(
          exchange,
          200,
          Map.of(
              "AccessToken", validToken,
              "RefreshToken", "refresh",
              "ExpiresIn", 3600,
              "TokenType", "Bearer"));
      return;
    }

    if (!("Bearer " + validToken).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      respond(exchange, 401, Map.of("message", "Unauthorized"));
      return;
    }

    if (method.equals("GET") && path.equals("/devices")) {
      respond(
          exchange,
          200,
          List.of(
              Map.of(
                  "_id", DEVICE_ID,
                  "name", "",
                  "fwVer", "DW6.93",
                  "locationID", "loc-1",
                  "locationName", "Home",
                  "writable", true,
                  "external", false)));
      return;
    }

    if (method.equals("GET") && path.equals("/devices/" + DEVICE_ID + "/live")) {
      if (failNextLiveReads.get() > 0) {
        failNextLiveReads.decrementAndGet();
        respond(exchange, 500, Map.of("message", "boom"));
        return;
      }
      liveReads.incrementAndGet();
      respond(exchange, 200, live);
      return;
    }

    if (method.equals("POST") && path.equals("/cmd/" + DEVICE_ID + "/msp")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = MAPPER.readValue(body, Map.class);
      commands.add(parsed);
      live.putAll(parsed);
      respond(exchange, 200, Map.of("message", "ok"));
      return;
    }

    respond(exchange, 404, Map.of("message", "no route for " + method + " " + path));
  }

  private void respond(HttpExchange exchange, int status, Object payload) throws IOException {
    byte[] bytes = MAPPER.writeValueAsBytes(payload);
    // The real cloud serves JSON as text/plain; strict clients choke on it.
    exchange.getResponseHeaders().add("Content-Type", "text/plain");
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
