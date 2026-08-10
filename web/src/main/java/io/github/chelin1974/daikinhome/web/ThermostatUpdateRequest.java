package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.Mapping.Anchor;
import io.github.chelin1974.daikinhome.Mapping.Desired;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import java.util.Locale;

/**
 * A requested change. Every field is optional; omitted ones are left alone.
 *
 * <pre>{@code
 * { "mode": "heat", "heatSetpoint": 21.5 }
 * }</pre>
 */
public record ThermostatUpdateRequest(String mode, Double heatSetpoint, Double coolSetpoint) {

  public boolean isEmpty() {
    return mode == null && heatSetpoint == null && coolSetpoint == null;
  }

  /**
   * Which setpoint the caller moved, so the clamping logic knows which one to protect.
   *
   * <p>When both are given, neither is anchored — they were sent as a pair and the caller
   * meant them together.
   */
  public Anchor anchor() {
    if (heatSetpoint != null && coolSetpoint == null) {
      return Anchor.HEAT;
    }
    if (coolSetpoint != null && heatSetpoint == null) {
      return Anchor.COOL;
    }
    return Anchor.NONE;
  }

  public Desired toDesired() {
    return new Desired(mode == null ? null : parseMode(mode), heatSetpoint, coolSetpoint);
  }

  /**
   * Accepts the mode names the API documents, and nothing else.
   *
   * @throws IllegalArgumentException with the valid options, so a client can correct itself
   */
  static int parseMode(String value) {
    return switch (value.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "")) {
      case "off" -> DaikinMode.OFF;
      case "heat" -> DaikinMode.HEAT;
      case "cool" -> DaikinMode.COOL;
      case "auto" -> DaikinMode.AUTO;
      case "emergencyheat", "emheat" -> DaikinMode.EMERGENCY_HEAT;
      default ->
          throw new IllegalArgumentException(
              "unknown mode \""
                  + value
                  + "\"; expected one of: off, heat, cool, auto, emergencyHeat");
    };
  }
}
