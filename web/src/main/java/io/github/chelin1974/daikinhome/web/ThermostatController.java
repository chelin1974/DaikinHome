package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.ThermostatGateway;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read and control thermostats over HTTP, alongside HomeKit. */
@RestController
@RequestMapping(path = "/api/thermostats", produces = MediaType.APPLICATION_JSON_VALUE)
public class ThermostatController {

  private final ThermostatGateway gateway;

  public ThermostatController(ThermostatGateway gateway) {
    this.gateway = gateway;
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
   */
  @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ThermostatDto update(
      @PathVariable String id, @RequestBody ThermostatUpdateRequest request) {

    if (request == null || request.isEmpty()) {
      throw new IllegalArgumentException(
          "supply at least one of: mode, heatSetpoint, coolSetpoint");
    }

    gateway.apply(id, request.toDesired(), request.anchor());
    return ThermostatDto.of(summary(id), gateway.state(id));
  }

  /** Forces a read from the Daikin cloud rather than waiting for the next poll. */
  @PostMapping("/{id}/refresh")
  public ThermostatDto refresh(@PathVariable String id) {
    summary(id); // 404 before spending an API call
    gateway.refresh(id);
    return ThermostatDto.of(summary(id), gateway.state(id));
  }

  private DaikinDeviceSummary summary(String id) {
    return gateway
        .device(id)
        .orElseThrow(() -> new NoSuchElementException("no thermostat with id " + id));
  }
}
