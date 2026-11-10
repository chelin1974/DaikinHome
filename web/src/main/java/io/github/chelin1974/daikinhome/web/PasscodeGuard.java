package io.github.chelin1974.daikinhome.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * The household passcode in front of the routes that change a thermostat.
 *
 * <p>Carried over from SpanHome's relay guard, with the same three layers: the request must
 * come from the home network ({@link LocalNetworkFilter}), it must carry the passcode in
 * {@code X-Passcode}, compared in constant time, and five wrong passcodes from one address
 * lock that address out for a while so the passcode cannot be guessed at LAN speed.
 *
 * <h2>Where this deliberately differs from SpanHome</h2>
 *
 * With no passcode configured, SpanHome refuses to switch a circuit at all. This bridge
 * instead lets the write through, because its {@code PUT /api/thermostats/{id}} shipped
 * before any of this and has no passcode to send: turning it off by default would break
 * every script already calling it, to protect a route that {@link LocalNetworkFilter} has
 * just restricted to the LAN. So the passcode is opt-in here - set {@code dashboardPasscode}
 * and it is enforced; leave it blank and the LAN is the only gate.
 *
 * <p>That is a real difference in posture, not an oversight: anyone on your home network can
 * change your thermostat until you set one. The page says so, and so does the README.
 */
@Component
public class PasscodeGuard {

  private static final Logger log = LoggerFactory.getLogger(PasscodeGuard.class);

  static final int MAX_FAILURES = 5;
  static final Duration LOCKOUT = Duration.ofMinutes(5);

  private final byte[] passcode;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  private static final class Failures {
    int count;
    Instant lockedUntil = Instant.EPOCH;
  }

  public PasscodeGuard(DaikinProperties properties) {
    String configured = properties.getDashboardPasscode();
    this.passcode =
        configured == null || configured.isBlank()
            ? null
            : configured.getBytes(StandardCharsets.UTF_8);
  }

  /** True when a passcode is configured, which is what the page shows as "Live". */
  public boolean enabled() {
    return passcode != null;
  }

  /**
   * Checks a request.
   *
   * @param given the {@code X-Passcode} header, or null
   * @param who the caller's address, which the lockout is counted against
   * @return empty to let the request through, or the response to send instead
   */
  public Optional<ResponseEntity<Object>> check(String given, String who) {
    if (passcode == null) {
      return Optional.empty(); // opt-in; see the class javadoc
    }

    Failures record = failures.computeIfAbsent(who == null ? "" : who, key -> new Failures());
    synchronized (record) {
      if (Instant.now().isBefore(record.lockedUntil)) {
        return Optional.of(
            ResponseEntity.status(429)
                .<Object>body(
                    Map.of("error", "Too many wrong passcodes. Try again in a few minutes.")));
      }
      if (given == null
          || !MessageDigest.isEqual(passcode, given.getBytes(StandardCharsets.UTF_8))) {
        record.count++;
        if (record.count >= MAX_FAILURES) {
          record.lockedUntil = Instant.now().plus(LOCKOUT);
          record.count = 0;
          log.warn(
              "{} wrong passcodes from {}; locked out for {} minutes",
              MAX_FAILURES,
              who,
              LOCKOUT.toMinutes());
        }
        return Optional.of(
            ResponseEntity.status(401).<Object>body(Map.of("error", "Wrong passcode.")));
      }
      record.count = 0;
    }
    return Optional.empty();
  }
}
