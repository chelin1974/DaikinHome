package io.github.chelin1974.daikinhome;

import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.EquipmentStatus;
import io.github.hapjava.characteristics.impl.thermostat.CurrentHeatingCoolingStateEnum;
import io.github.hapjava.characteristics.impl.thermostat.TargetHeatingCoolingStateEnum;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translation between Daikin's device model and HomeKit's thermostat model.
 *
 * <p>Every method here is pure — no network, no state, no HAP server — which is what makes
 * the awkward parts (setpoint separation, missing fields, emergency heat) straightforward
 * to test.
 */
public final class Mapping {

  /**
   * Fallback setpoint envelope, in Celsius, for thermostats that do not report their own
   * limits. 10–32.2 °C is 50–90 °F, the Daikin One range.
   */
  public static final double DEFAULT_SETPOINT_MINIMUM = 10.0;

  public static final double DEFAULT_SETPOINT_MAXIMUM = 32.2;

  /** 2 °F, the smallest heat/cool separation the One family accepts. */
  public static final double DEFAULT_SETPOINT_DELTA = 1.1;

  private Mapping() {}

  /** The setpoint envelope a thermostat will accept. */
  public record SetpointLimits(double minimum, double maximum, double delta) {}

  /** Which setpoint the user just moved, and so must not be moved back. */
  public enum Anchor {
    HEAT,
    COOL,
    NONE
  }

  /**
   * A pending change. Null fields mean "leave this alone".
   *
   * @param mode a {@link DaikinMode} value, or null
   * @param heatSetpoint degrees Celsius, or null
   * @param coolSetpoint degrees Celsius, or null
   */
  public record Desired(Integer mode, Double heatSetpoint, Double coolSetpoint) {

    public static final Desired NOTHING = new Desired(null, null, null);

    public static Desired ofMode(int mode) {
      return new Desired(mode, null, null);
    }

    public static Desired ofHeat(double heatSetpoint) {
      return new Desired(null, heatSetpoint, null);
    }

    public static Desired ofCool(double coolSetpoint) {
      return new Desired(null, null, coolSetpoint);
    }

    public boolean isEmpty() {
      return mode == null && heatSetpoint == null && coolSetpoint == null;
    }

    /** Later values win, so a second write to the same field replaces the first. */
    public Desired merge(Desired later) {
      return new Desired(
          later.mode != null ? later.mode : mode,
          later.heatSetpoint != null ? later.heatSetpoint : heatSetpoint,
          later.coolSetpoint != null ? later.coolSetpoint : coolSetpoint);
    }
  }

  /** A complete {@code msp} payload. */
  public record MspUpdate(int mode, double heatSetpoint, double coolSetpoint) {

    /** JSON body for {@code PUT /v1/devices/{id}/msp}. */
    public Map<String, Object> toMap() {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("mode", mode);
      body.put("heatSetpoint", heatSetpoint);
      body.put("coolSetpoint", coolSetpoint);
      return body;
    }
  }

  public static SetpointLimits getSetpointLimits(DaikinDevice device) {
    double minimum = orElse(device.setpointMinimum, DEFAULT_SETPOINT_MINIMUM);
    double maximum = orElse(device.setpointMaximum, DEFAULT_SETPOINT_MAXIMUM);
    double delta = Math.max(0, orElse(device.setpointDelta, DEFAULT_SETPOINT_DELTA));

    // A thermostat reporting a nonsense envelope should not brick the accessory.
    if (maximum <= minimum) {
      return new SetpointLimits(DEFAULT_SETPOINT_MINIMUM, DEFAULT_SETPOINT_MAXIMUM, delta);
    }
    return new SetpointLimits(minimum, maximum, delta);
  }

  /**
   * What the equipment is doing right now.
   *
   * <p>HomeKit has no "fan only" or "dehumidifying" current state. Fan-only reads as OFF
   * (nothing is being heated or cooled); overcool-to-dehumidify reads as COOL, because the
   * compressor is in fact cooling.
   */
  public static CurrentHeatingCoolingStateEnum toCurrentState(DaikinDevice device) {
    if (device.equipmentStatus != null) {
      int equipmentStatus = device.equipmentStatus;
      switch (equipmentStatus) {
        case EquipmentStatus.COOL:
        case EquipmentStatus.OVERCOOL_DEHUMIDIFY:
          return CurrentHeatingCoolingStateEnum.COOL;
        case EquipmentStatus.HEAT:
          return CurrentHeatingCoolingStateEnum.HEAT;
        case EquipmentStatus.FAN:
        case EquipmentStatus.IDLE:
          return CurrentHeatingCoolingStateEnum.OFF;
        default:
          break;
      }
    }

    // No usable equipmentStatus: fall back to the requested mode, so the tile is not stuck
    // on "Off" while the system is clearly running.
    if (device.mode != null) {
      int mode = device.mode;
      switch (mode) {
        case DaikinMode.HEAT:
        case DaikinMode.EMERGENCY_HEAT:
          return CurrentHeatingCoolingStateEnum.HEAT;
        case DaikinMode.COOL:
          return CurrentHeatingCoolingStateEnum.COOL;
        default:
          break;
      }
    }

    return CurrentHeatingCoolingStateEnum.OFF;
  }

  /**
   * The requested mode.
   *
   * <p>Emergency heat has no HomeKit equivalent and is reported as HEAT. The bridge never
   * <em>sets</em> emergency heat, so a system in em-heat keeps that mode until it is
   * changed at the thermostat or the user picks a different mode in Home.
   */
  public static TargetHeatingCoolingStateEnum toTargetState(DaikinDevice device) {
    if (device.mode == null) {
      return TargetHeatingCoolingStateEnum.OFF;
    }
    int mode = device.mode;
    return switch (mode) {
      case DaikinMode.HEAT, DaikinMode.EMERGENCY_HEAT -> TargetHeatingCoolingStateEnum.HEAT;
      case DaikinMode.COOL -> TargetHeatingCoolingStateEnum.COOL;
      case DaikinMode.AUTO -> TargetHeatingCoolingStateEnum.AUTO;
      default -> TargetHeatingCoolingStateEnum.OFF;
    };
  }

  /** HomeKit target state to Daikin mode. */
  public static int toDaikinMode(TargetHeatingCoolingStateEnum state, DaikinDevice device) {
    return switch (state) {
      case HEAT ->
          // Preserve emergency heat: if the system is already in em-heat and the user
          // re-selects Heat in the Home app, do not silently downgrade it.
          device != null && device.mode != null && device.mode == DaikinMode.EMERGENCY_HEAT
              ? DaikinMode.EMERGENCY_HEAT
              : DaikinMode.HEAT;
      case COOL -> DaikinMode.COOL;
      case AUTO -> DaikinMode.AUTO;
      case OFF -> DaikinMode.OFF;
    };
  }

  /**
   * The single target temperature HomeKit shows outside of Auto mode.
   *
   * <p>In Auto, HomeKit drives the heating and cooling threshold characteristics instead;
   * {@code TargetTemperature} is still required, so report the midpoint.
   */
  public static double toTargetTemperature(DaikinDevice device) {
    Double heat = device.heatSetpoint;
    Double cool = device.coolSetpoint;

    switch (toTargetState(device)) {
      case HEAT:
        return firstPresent(DEFAULT_SETPOINT_MINIMUM, heat, cool, device.tempIndoor);
      case COOL:
        return firstPresent(DEFAULT_SETPOINT_MAXIMUM, cool, heat, device.tempIndoor);
      default:
        if (heat != null && cool != null) {
          return round1((heat + cool) / 2);
        }
        return firstPresent(21.0, heat, cool, device.tempIndoor);
    }
  }

  /**
   * Builds a complete {@code msp} payload from the current device state plus whatever the
   * user just changed.
   *
   * <p>The thermostat validates mode and both setpoints as a set, so this always emits all
   * three, clamped into the reported envelope and separated by at least the reported
   * {@code setpointDelta}. {@code anchor} names the setpoint the user just moved — the
   * other one gives way when the pair would otherwise violate the delta.
   */
  public static MspUpdate buildMspUpdate(DaikinDevice device, Desired desired, Anchor anchor) {
    SetpointLimits limits = getSetpointLimits(device);
    int mode =
        desired.mode() != null
            ? desired.mode()
            : (device.mode != null ? device.mode : DaikinMode.OFF);

    double heat = firstPresent(limits.minimum(), desired.heatSetpoint(), device.heatSetpoint);
    double cool = firstPresent(limits.maximum(), desired.coolSetpoint(), device.coolSetpoint);

    heat = clamp(heat, limits.minimum(), limits.maximum());
    cool = clamp(cool, limits.minimum(), limits.maximum());

    if (cool - heat < limits.delta()) {
      Anchor effective = anchor;
      if (effective == Anchor.NONE) {
        // Nothing was explicitly moved: keep the setpoint that matters for the mode we
        // are heading into.
        effective = mode == DaikinMode.COOL ? Anchor.COOL : Anchor.HEAT;
      }

      if (effective == Anchor.HEAT) {
        cool = heat + limits.delta();
        if (cool > limits.maximum()) {
          cool = limits.maximum();
          heat = cool - limits.delta();
        }
      } else {
        heat = cool - limits.delta();
        if (heat < limits.minimum()) {
          heat = limits.minimum();
          cool = heat + limits.delta();
        }
      }

      heat = clamp(heat, limits.minimum(), limits.maximum());
      cool = clamp(cool, limits.minimum(), limits.maximum());
    }

    return new MspUpdate(mode, round1(heat), round1(cool));
  }

  /**
   * Applies a pending write on top of the last polled state.
   *
   * <p>HomeKit re-reads a characteristic immediately after writing it. The Daikin cloud
   * needs about 15 seconds before a change is visible, so without this overlay the Home
   * app snaps the dial back to the old value.
   */
  public static DaikinDevice applyDesired(DaikinDevice device, Desired desired) {
    DaikinDevice merged = device.copy();
    if (desired.mode() != null) {
      merged.mode = desired.mode();
    }
    if (desired.heatSetpoint() != null) {
      merged.heatSetpoint = desired.heatSetpoint();
    }
    if (desired.coolSetpoint() != null) {
      merged.coolSetpoint = desired.coolSetpoint();
    }
    return merged;
  }

  /** Human-readable mode, for log lines. */
  public static String describeMode(int mode) {
    return switch (mode) {
      case DaikinMode.OFF -> "off";
      case DaikinMode.HEAT -> "heat";
      case DaikinMode.COOL -> "cool";
      case DaikinMode.AUTO -> "auto";
      case DaikinMode.EMERGENCY_HEAT -> "emergency heat";
      default -> "unknown (" + mode + ")";
    };
  }

  /** HomeKit rejects out-of-range values outright, so clamp before reporting. */
  public static double clamp(double value, double min, double max) {
    if (!Double.isFinite(value)) {
      return min;
    }
    return Math.min(Math.max(value, min), max);
  }

  public static double round1(double value) {
    return Math.round(value * 10.0) / 10.0;
  }

  private static double orElse(Double value, double fallback) {
    return value != null && Double.isFinite(value) ? value : fallback;
  }

  /** First candidate that is actually present and finite, else {@code fallback}. */
  private static double firstPresent(double fallback, Double... candidates) {
    for (Double candidate : candidates) {
      if (candidate != null && Double.isFinite(candidate)) {
        return candidate;
      }
    }
    return fallback;
  }
}
