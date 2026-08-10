package io.github.chelin1974.daikinhome.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.ThermostatGateway;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Status of the HomeKit side: is it up, is it paired, what is the setup code. */
@RestController
@RequestMapping(path = "/api/bridge", produces = MediaType.APPLICATION_JSON_VALUE)
public class BridgeController {

  private final ThermostatGateway gateway;
  private final BridgeStatus status;

  public BridgeController(ThermostatGateway gateway, BridgeStatus status) {
    this.gateway = gateway;
    this.status = status;
  }

  /**
   * @param setupCode present only while unpaired — withheld afterwards so it does not keep
   *     appearing in responses once it is no longer needed
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record BridgeStatusDto(
      boolean running,
      boolean paired,
      String setupCode,
      String bridgeName,
      int homekitPort,
      long pollIntervalSeconds,
      int thermostatCount,
      String error) {}

  @GetMapping
  public BridgeStatusDto status() {
    BridgeSettings settings = gateway.settings();

    return new BridgeStatusDto(
        gateway.isRunning(),
        gateway.isPaired(),
        gateway.setupCode().orElse(null),
        settings.bridgeName(),
        settings.port(),
        settings.pollInterval().toSeconds(),
        gateway.devices().size(),
        status.lastError());
  }
}
