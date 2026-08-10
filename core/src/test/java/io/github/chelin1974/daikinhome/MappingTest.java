package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.daikinhome.Mapping.Anchor;
import io.github.chelin1974.daikinhome.Mapping.Desired;
import io.github.chelin1974.daikinhome.Mapping.MspUpdate;
import io.github.chelin1974.daikinhome.Mapping.SetpointLimits;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.EquipmentStatus;
import io.github.hapjava.characteristics.impl.thermostat.CurrentHeatingCoolingStateEnum;
import io.github.hapjava.characteristics.impl.thermostat.TargetHeatingCoolingStateEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MappingTest {

  /** An auto-mode thermostat holding 20–24 °C with a 2 °C separation. */
  private static DaikinDevice base() {
    DaikinDevice device = new DaikinDevice();
    device.mode = DaikinMode.AUTO;
    device.heatSetpoint = 20.0;
    device.coolSetpoint = 24.0;
    device.setpointMinimum = 10.0;
    device.setpointMaximum = 32.2;
    device.setpointDelta = 2.0;
    device.tempIndoor = 21.5;
    return device;
  }

  private static DaikinDevice withEquipmentStatus(int status) {
    DaikinDevice device = new DaikinDevice();
    device.equipmentStatus = status;
    return device;
  }

  @Nested
  @DisplayName("current heating/cooling state")
  class CurrentState {

    @Test
    void mapsEquipmentStatusToWhatTheSystemIsDoing() {
      assertEquals(
          CurrentHeatingCoolingStateEnum.HEAT,
          Mapping.toCurrentState(withEquipmentStatus(EquipmentStatus.HEAT)));
      assertEquals(
          CurrentHeatingCoolingStateEnum.COOL,
          Mapping.toCurrentState(withEquipmentStatus(EquipmentStatus.COOL)));
      assertEquals(
          CurrentHeatingCoolingStateEnum.OFF,
          Mapping.toCurrentState(withEquipmentStatus(EquipmentStatus.IDLE)));
    }

    @Test
    void treatsOvercoolDehumidifyAsCoolingBecauseTheCompressorIsRunning() {
      assertEquals(
          CurrentHeatingCoolingStateEnum.COOL,
          Mapping.toCurrentState(withEquipmentStatus(EquipmentStatus.OVERCOOL_DEHUMIDIFY)));
    }

    @Test
    void reportsFanOnlyAsOffBecauseHomeKitHasNoFanOnlyState() {
      assertEquals(
          CurrentHeatingCoolingStateEnum.OFF,
          Mapping.toCurrentState(withEquipmentStatus(EquipmentStatus.FAN)));
    }

    @Test
    void fallsBackToTheRequestedModeWhenEquipmentStatusIsAbsent() {
      DaikinDevice cooling = new DaikinDevice();
      cooling.mode = DaikinMode.COOL;
      assertEquals(CurrentHeatingCoolingStateEnum.COOL, Mapping.toCurrentState(cooling));
      assertEquals(CurrentHeatingCoolingStateEnum.OFF, Mapping.toCurrentState(new DaikinDevice()));
    }
  }

  @Nested
  @DisplayName("mode translation")
  class Modes {

    @Test
    void roundTripsTheFourModesHomeKitKnows() {
      int[][] pairs = {
        {TargetHeatingCoolingStateEnum.OFF.ordinal(), DaikinMode.OFF},
        {TargetHeatingCoolingStateEnum.HEAT.ordinal(), DaikinMode.HEAT},
        {TargetHeatingCoolingStateEnum.COOL.ordinal(), DaikinMode.COOL},
        {TargetHeatingCoolingStateEnum.AUTO.ordinal(), DaikinMode.AUTO},
      };

      for (int[] pair : pairs) {
        TargetHeatingCoolingStateEnum homekit = TargetHeatingCoolingStateEnum.values()[pair[0]];
        DaikinDevice device = new DaikinDevice();
        device.mode = pair[1];

        assertEquals(pair[1], Mapping.toDaikinMode(homekit, null));
        assertEquals(homekit, Mapping.toTargetState(device));
      }
    }

    @Test
    void showsEmergencyHeatAsHeat() {
      DaikinDevice device = new DaikinDevice();
      device.mode = DaikinMode.EMERGENCY_HEAT;
      assertEquals(TargetHeatingCoolingStateEnum.HEAT, Mapping.toTargetState(device));
    }

    @Test
    void doesNotDowngradeEmergencyHeatWhenHeatIsReselected() {
      DaikinDevice device = new DaikinDevice();
      device.mode = DaikinMode.EMERGENCY_HEAT;
      assertEquals(
          DaikinMode.EMERGENCY_HEAT,
          Mapping.toDaikinMode(TargetHeatingCoolingStateEnum.HEAT, device));
    }

    @Test
    void leavesEmergencyHeatWhenADifferentModeIsSelected() {
      DaikinDevice device = new DaikinDevice();
      device.mode = DaikinMode.EMERGENCY_HEAT;
      assertEquals(
          DaikinMode.COOL, Mapping.toDaikinMode(TargetHeatingCoolingStateEnum.COOL, device));
    }
  }

  @Nested
  @DisplayName("target temperature")
  class TargetTemperature {

    @Test
    void usesTheSetpointThatMatchesTheMode() {
      DaikinDevice heating = base();
      heating.mode = DaikinMode.HEAT;
      assertEquals(20.0, Mapping.toTargetTemperature(heating));

      DaikinDevice cooling = base();
      cooling.mode = DaikinMode.COOL;
      assertEquals(24.0, Mapping.toTargetTemperature(cooling));
    }

    @Test
    void reportsTheMidpointInAutoAndOffWhereHomeKitUsesThresholds() {
      assertEquals(22.0, Mapping.toTargetTemperature(base()));

      DaikinDevice off = base();
      off.mode = DaikinMode.OFF;
      assertEquals(22.0, Mapping.toTargetTemperature(off));
    }

    @Test
    void fallsBackToIndoorTemperatureWhenNoSetpointsAreReported() {
      DaikinDevice device = new DaikinDevice();
      device.mode = DaikinMode.HEAT;
      device.tempIndoor = 19.4;
      assertEquals(19.4, Mapping.toTargetTemperature(device));
    }
  }

  @Nested
  @DisplayName("setpoint limits")
  class Limits {

    @Test
    void prefersWhatTheThermostatReports() {
      assertEquals(new SetpointLimits(10.0, 32.2, 2.0), Mapping.getSetpointLimits(base()));
    }

    @Test
    void substitutesDefaultsForAnImpossibleEnvelope() {
      DaikinDevice device = new DaikinDevice();
      device.setpointMinimum = 30.0;
      device.setpointMaximum = 10.0;
      device.setpointDelta = 1.0;
      assertEquals(new SetpointLimits(10.0, 32.2, 1.0), Mapping.getSetpointLimits(device));
    }

    @Test
    void fallsBackToTheDaikinOneRangeWhenNothingIsReported() {
      SetpointLimits limits = Mapping.getSetpointLimits(new DaikinDevice());
      assertEquals(Mapping.DEFAULT_SETPOINT_MINIMUM, limits.minimum());
      assertEquals(Mapping.DEFAULT_SETPOINT_MAXIMUM, limits.maximum());
      assertEquals(Mapping.DEFAULT_SETPOINT_DELTA, limits.delta());
    }
  }

  @Nested
  @DisplayName("msp payload")
  class Msp {

    @Test
    void alwaysSendsModeWithBothSetpoints() {
      assertEquals(
          new MspUpdate(DaikinMode.HEAT, 20.0, 24.0),
          Mapping.buildMspUpdate(base(), Desired.ofMode(DaikinMode.HEAT), Anchor.NONE));
    }

    @Test
    void keepsTheCurrentModeWhenOnlyASetpointChanges() {
      assertEquals(
          DaikinMode.AUTO,
          Mapping.buildMspUpdate(base(), Desired.ofHeat(21.0), Anchor.HEAT).mode());
    }

    @Test
    void pushesTheCoolSetpointUpWhenHeatIsRaisedIntoIt() {
      assertEquals(
          new MspUpdate(DaikinMode.AUTO, 23.5, 25.5),
          Mapping.buildMspUpdate(base(), Desired.ofHeat(23.5), Anchor.HEAT));
    }

    @Test
    void pushesTheHeatSetpointDownWhenCoolIsLoweredIntoIt() {
      assertEquals(
          new MspUpdate(DaikinMode.AUTO, 18.5, 20.5),
          Mapping.buildMspUpdate(base(), Desired.ofCool(20.5), Anchor.COOL));
    }

    @Test
    void givesUpTheAnchorRatherThanExceedingTheMaximum() {
      assertEquals(
          new MspUpdate(DaikinMode.AUTO, 30.2, 32.2),
          Mapping.buildMspUpdate(base(), Desired.ofHeat(32.2), Anchor.HEAT));
    }

    @Test
    void givesUpTheAnchorRatherThanDroppingBelowTheMinimum() {
      assertEquals(
          new MspUpdate(DaikinMode.AUTO, 10.0, 12.0),
          Mapping.buildMspUpdate(base(), Desired.ofCool(10.0), Anchor.COOL));
    }

    @Test
    void clampsASetpointOutsideTheReportedEnvelope() {
      assertEquals(
          32.2, Mapping.buildMspUpdate(base(), Desired.ofCool(40.0), Anchor.COOL).coolSetpoint());
      assertEquals(
          10.0, Mapping.buildMspUpdate(base(), Desired.ofHeat(-5.0), Anchor.HEAT).heatSetpoint());
    }

    @Test
    void protectsTheCoolSetpointWhenHeadingIntoCoolModeWithNoAnchor() {
      DaikinDevice conflicting = base();
      conflicting.heatSetpoint = 24.0;
      conflicting.coolSetpoint = 24.0;

      assertEquals(
          new MspUpdate(DaikinMode.COOL, 22.0, 24.0),
          Mapping.buildMspUpdate(conflicting, Desired.ofMode(DaikinMode.COOL), Anchor.NONE));
    }

    @Test
    void roundsToTheOneDecimalPlaceTheApiAccepts() {
      assertEquals(
          20.1, Mapping.buildMspUpdate(base(), Desired.ofHeat(20.06), Anchor.HEAT).heatSetpoint());
    }

    @Test
    void worksFromAnEmptyDeviceUsingDefaults() {
      MspUpdate update =
          Mapping.buildMspUpdate(
              new DaikinDevice(), new Desired(DaikinMode.HEAT, 21.0, null), Anchor.HEAT);

      assertEquals(DaikinMode.HEAT, update.mode());
      assertEquals(21.0, update.heatSetpoint());
      assertTrue(
          update.coolSetpoint() >= update.heatSetpoint() + Mapping.DEFAULT_SETPOINT_DELTA,
          "cool setpoint should clear the heat setpoint by at least the default delta");
    }
  }

  @Nested
  @DisplayName("optimistic overlay")
  class Overlay {

    @Test
    void overlaysOnlyTheFieldsThatWereWritten() {
      DaikinDevice merged = Mapping.applyDesired(base(), Desired.ofHeat(22.0));
      assertEquals(22.0, merged.heatSetpoint);
      assertEquals(24.0, merged.coolSetpoint);
      assertEquals(DaikinMode.AUTO, merged.mode);
    }

    @Test
    void doesNotMutateThePolledState() {
      DaikinDevice original = base();
      Mapping.applyDesired(original, Desired.ofMode(DaikinMode.OFF));
      assertEquals(DaikinMode.AUTO, original.mode);
    }

    @Test
    void mergesLaterWritesOverEarlierOnes() {
      Desired merged = Desired.ofHeat(20.0).merge(Desired.ofHeat(22.0)).merge(Desired.ofCool(26.0));
      assertEquals(22.0, merged.heatSetpoint());
      assertEquals(26.0, merged.coolSetpoint());
    }
  }
}
