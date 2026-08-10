package io.github.chelin1974.daikinhome.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.chelin1974.daikinhome.Mapping;
import io.github.chelin1974.daikinhome.Mapping.SetpointLimits;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;

/**
 * A thermostat as the REST API presents it.
 *
 * <p>Modes are strings rather than Daikin's integers: an API you have to consult a table to
 * read is an API you will misuse. Absent readings are omitted rather than sent as null, so
 * a client can tell "not reported by this model" from "reported as zero".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThermostatDto(
    String id,
    String name,
    String model,
    String firmwareVersion,
    String location,
    String mode,
    String currentState,
    String targetState,
    Double indoorTemperature,
    Double indoorHumidity,
    Double outdoorTemperature,
    Double outdoorHumidity,
    Double heatSetpoint,
    Double coolSetpoint,
    Double targetTemperature,
    Double setpointMinimum,
    Double setpointMaximum,
    Double setpointDelta,
    Boolean scheduleEnabled) {

  public static ThermostatDto of(DaikinDeviceSummary summary, DaikinDevice device) {
    SetpointLimits limits = Mapping.getSetpointLimits(device);

    return new ThermostatDto(
        summary.id(),
        summary.name(),
        summary.model(),
        summary.firmwareVersion(),
        summary.locationName(),
        device.mode == null ? null : Mapping.describeMode(device.mode),
        Mapping.toCurrentState(device).name().toLowerCase(),
        Mapping.toTargetState(device).name().toLowerCase(),
        device.tempIndoor,
        device.humIndoor,
        device.tempOutdoor,
        device.humOutdoor,
        device.heatSetpoint,
        device.coolSetpoint,
        Mapping.toTargetTemperature(device),
        limits.minimum(),
        limits.maximum(),
        limits.delta(),
        device.scheduleEnabled);
  }
}
