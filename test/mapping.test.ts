import { describe, expect, it } from 'vitest';

import { DaikinMode, EquipmentStatus } from '../src/api/types';
import {
  CurrentHeatingCoolingState,
  TargetHeatingCoolingState,
  applyDesired,
  buildMspUpdate,
  getSetpointLimits,
  toCurrentHeatingCoolingState,
  toDaikinMode,
  toTargetHeatingCoolingState,
  toTargetTemperature,
} from '../src/mapping';

const base = {
  mode: DaikinMode.AUTO,
  heatSetpoint: 20,
  coolSetpoint: 24,
  setpointMinimum: 10,
  setpointMaximum: 32.2,
  setpointDelta: 2,
  tempIndoor: 21.5,
};

describe('toCurrentHeatingCoolingState', () => {
  it('maps equipment status to what the system is doing', () => {
    expect(toCurrentHeatingCoolingState({ equipmentStatus: EquipmentStatus.HEAT })).toBe(
      CurrentHeatingCoolingState.HEAT,
    );
    expect(toCurrentHeatingCoolingState({ equipmentStatus: EquipmentStatus.COOL })).toBe(
      CurrentHeatingCoolingState.COOL,
    );
    expect(toCurrentHeatingCoolingState({ equipmentStatus: EquipmentStatus.IDLE })).toBe(
      CurrentHeatingCoolingState.OFF,
    );
  });

  it('treats overcool-to-dehumidify as cooling, because the compressor is running', () => {
    expect(
      toCurrentHeatingCoolingState({
        equipmentStatus: EquipmentStatus.OVERCOOL_DEHUMIDIFY,
      }),
    ).toBe(CurrentHeatingCoolingState.COOL);
  });

  it('reports fan-only as off, since HomeKit has no fan-only current state', () => {
    expect(toCurrentHeatingCoolingState({ equipmentStatus: EquipmentStatus.FAN })).toBe(
      CurrentHeatingCoolingState.OFF,
    );
  });

  it('falls back to the requested mode when equipmentStatus is absent', () => {
    expect(toCurrentHeatingCoolingState({ mode: DaikinMode.COOL })).toBe(
      CurrentHeatingCoolingState.COOL,
    );
    expect(toCurrentHeatingCoolingState({})).toBe(CurrentHeatingCoolingState.OFF);
  });
});

describe('mode translation', () => {
  it('round-trips the four modes HomeKit knows', () => {
    for (const [hk, daikin] of [
      [TargetHeatingCoolingState.OFF, DaikinMode.OFF],
      [TargetHeatingCoolingState.HEAT, DaikinMode.HEAT],
      [TargetHeatingCoolingState.COOL, DaikinMode.COOL],
      [TargetHeatingCoolingState.AUTO, DaikinMode.AUTO],
    ] as const) {
      expect(toDaikinMode(hk)).toBe(daikin);
      expect(toTargetHeatingCoolingState({ mode: daikin })).toBe(hk);
    }
  });

  it('shows emergency heat as Heat', () => {
    expect(
      toTargetHeatingCoolingState({ mode: DaikinMode.EMERGENCY_HEAT }),
    ).toBe(TargetHeatingCoolingState.HEAT);
  });

  it('does not downgrade emergency heat when Heat is re-selected', () => {
    expect(
      toDaikinMode(TargetHeatingCoolingState.HEAT, { mode: DaikinMode.EMERGENCY_HEAT }),
    ).toBe(DaikinMode.EMERGENCY_HEAT);
  });

  it('leaves emergency heat when a different mode is selected', () => {
    expect(
      toDaikinMode(TargetHeatingCoolingState.COOL, { mode: DaikinMode.EMERGENCY_HEAT }),
    ).toBe(DaikinMode.COOL);
  });
});

describe('toTargetTemperature', () => {
  it('uses the setpoint that matches the mode', () => {
    expect(toTargetTemperature({ ...base, mode: DaikinMode.HEAT })).toBe(20);
    expect(toTargetTemperature({ ...base, mode: DaikinMode.COOL })).toBe(24);
  });

  it('reports the midpoint in auto and off, where HomeKit uses thresholds', () => {
    expect(toTargetTemperature({ ...base, mode: DaikinMode.AUTO })).toBe(22);
    expect(toTargetTemperature({ ...base, mode: DaikinMode.OFF })).toBe(22);
  });

  it('falls back to indoor temperature when no setpoints are reported', () => {
    expect(toTargetTemperature({ mode: DaikinMode.HEAT, tempIndoor: 19.4 })).toBe(19.4);
  });
});

describe('getSetpointLimits', () => {
  it('prefers what the thermostat reports', () => {
    expect(getSetpointLimits(base)).toEqual({
      minimum: 10,
      maximum: 32.2,
      delta: 2,
    });
  });

  it('substitutes defaults for an impossible envelope', () => {
    expect(
      getSetpointLimits({ setpointMinimum: 30, setpointMaximum: 10, setpointDelta: 1 }),
    ).toEqual({ minimum: 10, maximum: 32.2, delta: 1 });
  });
});

describe('buildMspUpdate', () => {
  it('always sends mode with both setpoints', () => {
    expect(buildMspUpdate(base, { mode: DaikinMode.HEAT })).toEqual({
      mode: DaikinMode.HEAT,
      heatSetpoint: 20,
      coolSetpoint: 24,
    });
  });

  it('keeps the current mode when only a setpoint changes', () => {
    expect(buildMspUpdate(base, { heatSetpoint: 21 }, 'heat').mode).toBe(DaikinMode.AUTO);
  });

  it('pushes the cool setpoint up when heat is raised into it', () => {
    expect(buildMspUpdate(base, { heatSetpoint: 23.5 }, 'heat')).toEqual({
      mode: DaikinMode.AUTO,
      heatSetpoint: 23.5,
      coolSetpoint: 25.5,
    });
  });

  it('pushes the heat setpoint down when cool is lowered into it', () => {
    expect(buildMspUpdate(base, { coolSetpoint: 20.5 }, 'cool')).toEqual({
      mode: DaikinMode.AUTO,
      heatSetpoint: 18.5,
      coolSetpoint: 20.5,
    });
  });

  it('gives up the anchor rather than exceeding the maximum', () => {
    expect(buildMspUpdate(base, { heatSetpoint: 32.2 }, 'heat')).toEqual({
      mode: DaikinMode.AUTO,
      heatSetpoint: 30.2,
      coolSetpoint: 32.2,
    });
  });

  it('gives up the anchor rather than dropping below the minimum', () => {
    expect(buildMspUpdate(base, { coolSetpoint: 10 }, 'cool')).toEqual({
      mode: DaikinMode.AUTO,
      heatSetpoint: 10,
      coolSetpoint: 12,
    });
  });

  it('clamps a setpoint outside the reported envelope', () => {
    expect(buildMspUpdate(base, { coolSetpoint: 40 }, 'cool').coolSetpoint).toBe(32.2);
    expect(buildMspUpdate(base, { heatSetpoint: -5 }, 'heat').heatSetpoint).toBe(10);
  });

  it('protects the cool setpoint when heading into cool mode with no anchor', () => {
    const conflicting = { ...base, heatSetpoint: 24, coolSetpoint: 24 };
    expect(buildMspUpdate(conflicting, { mode: DaikinMode.COOL })).toEqual({
      mode: DaikinMode.COOL,
      heatSetpoint: 22,
      coolSetpoint: 24,
    });
  });

  it('rounds to the one decimal place the API accepts', () => {
    const update = buildMspUpdate(base, { heatSetpoint: 20.06 }, 'heat');
    expect(update.heatSetpoint).toBe(20.1);
  });

  it('works from an empty device, using defaults', () => {
    const update = buildMspUpdate({}, { mode: DaikinMode.HEAT, heatSetpoint: 21 }, 'heat');
    expect(update.mode).toBe(DaikinMode.HEAT);
    expect(update.heatSetpoint).toBe(21);
    expect(update.coolSetpoint).toBeGreaterThanOrEqual(update.heatSetpoint + 1.1);
  });
});

describe('applyDesired', () => {
  it('overlays only the fields that were written', () => {
    expect(applyDesired(base, { heatSetpoint: 22 })).toMatchObject({
      heatSetpoint: 22,
      coolSetpoint: 24,
      mode: DaikinMode.AUTO,
    });
  });

  it('does not mutate the polled state', () => {
    const snapshot = { ...base };
    applyDesired(base, { mode: DaikinMode.OFF });
    expect(base).toEqual(snapshot);
  });
});
