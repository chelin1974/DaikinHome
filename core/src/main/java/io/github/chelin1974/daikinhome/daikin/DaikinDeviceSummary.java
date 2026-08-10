package io.github.chelin1974.daikinhome.daikin;

/**
 * A thermostat as it appears in the {@code GET /v1/devices} listing.
 *
 * @param id thermostat ID, used for every other endpoint
 * @param name display name, falling back to the ID when Daikin returns none
 * @param model may be null
 * @param firmwareVersion may be null
 * @param locationName filled in when the API groups thermostats by location
 */
public record DaikinDeviceSummary(
    String id, String name, String model, String firmwareVersion, String locationName) {}
