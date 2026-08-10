/**
 * Translation between Daikin's device model and HomeKit's thermostat model.
 *
 * Deliberately free of Homebridge imports: the HAP characteristic values are
 * stable, documented integers, so mirroring them here keeps this logic unit
 * testable without booting HAP.
 */
import {
  DaikinMode,
  EquipmentStatus,
  type DaikinDevice,
  type MspUpdate,
} from './api/types';

/** HAP `CurrentHeatingCoolingState`. */
export const CurrentHeatingCoolingState = {
  OFF: 0,
  HEAT: 1,
  COOL: 2,
} as const;

/** HAP `TargetHeatingCoolingState`. */
export const TargetHeatingCoolingState = {
  OFF: 0,
  HEAT: 1,
  COOL: 2,
  AUTO: 3,
} as const;

/**
 * Fallback setpoint envelope, in Celsius, for thermostats that do not report
 * their own limits. 10–32.2 °C is 50–90 °F, the Daikin One range.
 */
export const DEFAULT_SETPOINT_MINIMUM = 10;
export const DEFAULT_SETPOINT_MAXIMUM = 32.2;
/** 2 °F, the smallest heat/cool separation the One family accepts. */
export const DEFAULT_SETPOINT_DELTA = 1.1;

export interface SetpointLimits {
  minimum: number;
  maximum: number;
  delta: number;
}

/** Which setpoint the user just moved, and so must not be moved back. */
export type SetpointAnchor = 'heat' | 'cool' | 'none';

export interface DesiredState {
  mode?: number;
  heatSetpoint?: number;
  coolSetpoint?: number;
}

export function getSetpointLimits(device: DaikinDevice): SetpointLimits {
  const minimum = finiteOr(device.setpointMinimum, DEFAULT_SETPOINT_MINIMUM);
  const maximum = finiteOr(device.setpointMaximum, DEFAULT_SETPOINT_MAXIMUM);
  const delta = Math.max(0, finiteOr(device.setpointDelta, DEFAULT_SETPOINT_DELTA));

  // A thermostat reporting a nonsense envelope should not brick the accessory.
  if (maximum <= minimum) {
    return {
      minimum: DEFAULT_SETPOINT_MINIMUM,
      maximum: DEFAULT_SETPOINT_MAXIMUM,
      delta,
    };
  }
  return { minimum, maximum, delta };
}

/**
 * What the equipment is doing right now.
 *
 * HomeKit has no "fan only" or "dehumidifying" current state. Fan-only reads as
 * OFF (nothing is being heated or cooled); overcool-to-dehumidify reads as COOL
 * because the compressor is in fact cooling.
 */
export function toCurrentHeatingCoolingState(device: DaikinDevice): number {
  switch (device.equipmentStatus) {
    case EquipmentStatus.COOL:
    case EquipmentStatus.OVERCOOL_DEHUMIDIFY:
      return CurrentHeatingCoolingState.COOL;
    case EquipmentStatus.HEAT:
      return CurrentHeatingCoolingState.HEAT;
    case EquipmentStatus.FAN:
    case EquipmentStatus.IDLE:
      return CurrentHeatingCoolingState.OFF;
    default:
      break;
  }

  // No usable equipmentStatus: fall back to the requested mode so the tile is
  // not stuck on "Off" while the system is clearly running.
  switch (device.mode) {
    case DaikinMode.HEAT:
    case DaikinMode.EMERGENCY_HEAT:
      return CurrentHeatingCoolingState.HEAT;
    case DaikinMode.COOL:
      return CurrentHeatingCoolingState.COOL;
    default:
      return CurrentHeatingCoolingState.OFF;
  }
}

/**
 * The requested mode.
 *
 * Emergency heat has no HomeKit equivalent and is reported as HEAT; the plugin
 * never *sets* emergency heat, so a system in em-heat keeps that mode until it
 * is changed at the thermostat or the user picks a different mode in Home.
 */
export function toTargetHeatingCoolingState(device: DaikinDevice): number {
  switch (device.mode) {
    case DaikinMode.HEAT:
    case DaikinMode.EMERGENCY_HEAT:
      return TargetHeatingCoolingState.HEAT;
    case DaikinMode.COOL:
      return TargetHeatingCoolingState.COOL;
    case DaikinMode.AUTO:
      return TargetHeatingCoolingState.AUTO;
    case DaikinMode.OFF:
    default:
      return TargetHeatingCoolingState.OFF;
  }
}

/** HomeKit target state to Daikin mode. */
export function toDaikinMode(targetState: number, device?: DaikinDevice): number {
  switch (targetState) {
    case TargetHeatingCoolingState.HEAT:
      // Preserve emergency heat: if the system is already in em-heat and the
      // user re-selects Heat in the Home app, do not silently downgrade it.
      return device?.mode === DaikinMode.EMERGENCY_HEAT
        ? DaikinMode.EMERGENCY_HEAT
        : DaikinMode.HEAT;
    case TargetHeatingCoolingState.COOL:
      return DaikinMode.COOL;
    case TargetHeatingCoolingState.AUTO:
      return DaikinMode.AUTO;
    case TargetHeatingCoolingState.OFF:
    default:
      return DaikinMode.OFF;
  }
}

/**
 * The single target temperature HomeKit shows outside of Auto mode.
 *
 * In Auto, HomeKit drives the heating/cooling threshold characteristics
 * instead; `TargetTemperature` is still required, so report the midpoint.
 */
export function toTargetTemperature(device: DaikinDevice): number {
  const heat = finiteOr(device.heatSetpoint, NaN);
  const cool = finiteOr(device.coolSetpoint, NaN);

  switch (toTargetHeatingCoolingState(device)) {
    case TargetHeatingCoolingState.HEAT:
      return firstFinite(heat, cool, device.tempIndoor, DEFAULT_SETPOINT_MINIMUM);
    case TargetHeatingCoolingState.COOL:
      return firstFinite(cool, heat, device.tempIndoor, DEFAULT_SETPOINT_MAXIMUM);
    default:
      if (Number.isFinite(heat) && Number.isFinite(cool)) {
        return round1((heat + cool) / 2);
      }
      return firstFinite(heat, cool, device.tempIndoor, 21);
  }
}

/**
 * Builds a complete `msp` payload from the current device state plus whatever
 * the user just changed.
 *
 * The thermostat validates mode and both setpoints as a set, so this always
 * emits all three, clamped into the reported envelope and separated by at least
 * `setpointDelta`. `anchor` names the setpoint the user just moved — the other
 * one gives way when the pair would otherwise violate the delta.
 */
export function buildMspUpdate(
  device: DaikinDevice,
  desired: DesiredState,
  anchor: SetpointAnchor = 'none',
): MspUpdate {
  const limits = getSetpointLimits(device);
  const mode = desired.mode ?? finiteOr(device.mode, DaikinMode.OFF);

  let heat = firstFinite(
    desired.heatSetpoint,
    device.heatSetpoint,
    limits.minimum,
  );
  let cool = firstFinite(
    desired.coolSetpoint,
    device.coolSetpoint,
    limits.maximum,
  );

  heat = clamp(heat, limits.minimum, limits.maximum);
  cool = clamp(cool, limits.minimum, limits.maximum);

  if (cool - heat < limits.delta) {
    const effectiveAnchor =
      anchor === 'none'
        ? // Nothing was explicitly moved: keep the setpoint that matters for
          // the mode we are heading into.
          mode === DaikinMode.COOL
          ? 'cool'
          : 'heat'
        : anchor;

    if (effectiveAnchor === 'heat') {
      cool = heat + limits.delta;
      if (cool > limits.maximum) {
        cool = limits.maximum;
        heat = cool - limits.delta;
      }
    } else {
      heat = cool - limits.delta;
      if (heat < limits.minimum) {
        heat = limits.minimum;
        cool = heat + limits.delta;
      }
    }

    heat = clamp(heat, limits.minimum, limits.maximum);
    cool = clamp(cool, limits.minimum, limits.maximum);
  }

  return {
    mode,
    heatSetpoint: round1(heat),
    coolSetpoint: round1(cool),
  };
}

/**
 * Applies a pending write on top of the last polled state.
 *
 * HomeKit re-reads a characteristic immediately after writing it. The Daikin
 * cloud needs ~15 s before a change is visible, so without this overlay the
 * Home app snaps the dial back to the old value.
 */
export function applyDesired(
  device: DaikinDevice,
  desired: DesiredState,
): DaikinDevice {
  const merged: DaikinDevice = { ...device };
  if (desired.mode !== undefined) {
    merged.mode = desired.mode;
  }
  if (desired.heatSetpoint !== undefined) {
    merged.heatSetpoint = desired.heatSetpoint;
  }
  if (desired.coolSetpoint !== undefined) {
    merged.coolSetpoint = desired.coolSetpoint;
  }
  return merged;
}

/** HomeKit rejects out-of-range values outright, so clamp before reporting. */
export function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) {
    return min;
  }
  return Math.min(Math.max(value, min), max);
}

export function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

function finiteOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function firstFinite(...values: unknown[]): number {
  for (const value of values) {
    if (typeof value === 'number' && Number.isFinite(value)) {
      return value;
    }
  }
  return 0;
}
