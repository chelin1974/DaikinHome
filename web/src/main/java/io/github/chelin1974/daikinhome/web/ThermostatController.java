package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.ThermostatGateway;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read and control thermostats over HTTP, alongside HomeKit. */
@RestController
@RequestMapping(path = "/api/thermostats", produces = MediaType.APPLICATION_JSON_VALUE)
public class ThermostatController {

  private final ThermostatGateway gateway;
  private final PasscodeGuard guard;

  public ThermostatController(ThermostatGateway gateway, PasscodeGuard guard) {
    this.gateway = gateway;
    this.guard = guard;
  }

  /** Every thermostat, with its last polled state. */
  @GetMapping
  public List<ThermostatDto> list() {
    return gateway.devices().stream()
        .map(summary -> ThermostatDto.of(summary, gateway.state(summary.id())))
        .toList();
  }

  /**
   * One thermostat.
   *
   * <p>Served from the last poll, which by design can be up to the polling interval old —
   * Daikin rate limits anything faster. Use {@code POST /refresh} to force a read.
   */
  @GetMapping("/{id}")
  public ThermostatDto get(@PathVariable String id) {
    return ThermostatDto.of(summary(id), gateway.state(id));
  }

  /**
   * Changes mode and/or setpoints.
   *
   * <p>Sent synchronously, so the response reflects what the thermostat actually accepted —
   * which may not be what was asked for, since setpoints are clamped into the thermostat's
   * own range and kept apart by its minimum separation.
   *
   * <p>Needs {@code X-Passcode} when {@code dashboardPasscode} is configured; see
   * {@link PasscodeGuard}, which also explains why it is not required when it is not.
   */
  @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Object> update(
      @PathVariable String id,
      @RequestBody ThermostatUpdateRequest request,
      @RequestHeader(name = "X-Passcode", required = false) String passcode,
      HttpServletRequest http) {

    Optional<ResponseEntity<Object>> refused = guard.check(passcode, http.getRemoteAddr());
    if (refused.isPresent()) {
      return refused.get();
    }

    if (request == null || request.isEmpty()) {
      throw new IllegalArgumentException(
          "supply at least one of: mode, heatSetpoint, coolSetpoint");
    }

    gateway.apply(id, request.toDesired(), request.anchor());
    return ResponseEntity.ok(ThermostatDto.of(summary(id), gateway.state(id)));
  }

  /**
   * Forces a read from the Daikin cloud rather than waiting for the next poll.
   *
   * <p>Guarded like {@link #update}, and for a second reason: it spends a call against
   * Daikin's rate-limited API, so an unauthenticated one is a way to get the integration
   * throttled from outside.
   */
  @PostMapping("/{id}/refresh")
  public ResponseEntity<Object> refresh(
      @PathVariable String id,
      @RequestHeader(name = "X-Passcode", required = false) String passcode,
      HttpServletRequest http) {

    Optional<ResponseEntity<Object>> refused = guard.check(passcode, http.getRemoteAddr());
    if (refused.isPresent()) {
      return refused.get();
    }

    summary(id); // 404 before spending an API call
    gateway.refresh(id);
    return ResponseEntity.ok(ThermostatDto.of(summary(id), gateway.state(id)));
  }

  private DaikinDeviceSummary summary(String id) {
    return gateway
        .device(id)
        .orElseThrow(() -> new NoSuchElementException("no thermostat with id " + id));
  }
}
