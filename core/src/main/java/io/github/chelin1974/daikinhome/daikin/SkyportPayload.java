package io.github.chelin1974.daikinhome.daikin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.EquipmentStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Translation between the SkyportHome cloud's raw payloads and the Open API model the rest of
 * the bridge uses.
 *
 * <p>Pure functions only — no network, no state — so the awkward parts (VUT units, fault
 * sentinels, the different equipment-status codes) are straightforward to test.
 *
 * <h2>What the Skyport cloud looks like</h2>
 *
 * <p>{@code GET /devices/{_id}/live} returns the thermostat's raw register view, not the
 * friendly Open API shape. Temperatures are in "VUT" units, where
 * {@code °C = 0.5 × raw − 40}; 254 and 255 mean a shorted or open sensor. Field names and
 * codes are taken from the SkyportHome web client and from a migrated account, as
 * documented in <a href="https://github.com/apetrycki/daikinskyport/issues/156">
 * apetrycki/daikinskyport#156</a>. None of it is published by Daikin, so it is read
 * defensively: anything missing or out of range becomes {@code null} and the bridge falls
 * back to its defaults.
 */
public final class SkyportPayload {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Raw reading for a sensor that is not connected. */
  public static final int SENSOR_OPEN = 255;

  /** Raw reading for a shorted sensor. */
  public static final int SENSOR_SHORTED = 254;

  /** Smallest heat/cool gap the Skyport web client enforces, in VUT units (= 1 °C). */
  public static final int SETPOINT_DELTA_VUT = 2;

  /**
   * Where the outdoor temperature might live.
   *
   * <p>The one published live payload is truncated and does not show an outdoor field, so
   * these are educated guesses. The first numeric, non-sentinel one wins. Run
   * {@code --probe} and check the raw {@code skyport} block if this comes back empty.
   */
  static final List<String> OUTDOOR_TEMPERATURE_FIELDS =
      List.of("outdoorTemp", "tempOutdoor", "outdoorTemperature", "outTemp", "oaTemp");

  /** Skyport {@code eqStatus} values. */
  public static final class SkyportEquipmentStatus {
    public static final int IDLE = 0;
    public static final int HEATING = 1;
    public static final int COOLING = 2;
    public static final int WAITING = 3;
    public static final int ERROR = 4;
    public static final int FREE_COOLING = 5;

    private SkyportEquipmentStatus() {}
  }

  private SkyportPayload() {}

  // ------------------------------------------------------------------ units

  /** VUT to Celsius, or null for a missing, sentinel or nonsensical reading. */
  public static Double vutToCelsius(JsonNode raw) {
    if (raw == null || !raw.isNumber()) {
      return null;
    }
    double value = raw.asDouble();
    if (!Double.isFinite(value)
        || value < 0
        || value == SENSOR_OPEN
        || value == SENSOR_SHORTED
        || value > 250) {
      return null;
    }
    return 0.5 * value - 40.0;
  }

  /**
   * Celsius to VUT, rounded to the nearest half degree the thermostat can represent.
   *
   * <p>Rounds half up, so 22.25 °C becomes 124.5 → 125, never a value Daikin would reject.
   */
  public static int celsiusToVut(double celsius) {
    return (int) Math.round(2.0 * (celsius + 40.0));
  }

  // ------------------------------------------------------------------ listing

  /**
   * {@code GET /devices} to summaries.
   *
   * <p>Each entry carries {@code _id}; {@code name} can be an empty string, in which case the
   * location name is the best label available.
   */
  public static List<DaikinDeviceSummary> toSummaries(JsonNode payload) {
    if (payload == null || !payload.isArray()) {
      throw new DaikinExceptions.DaikinResponseException(
          "GET /devices returned an unexpected shape: " + truncate(String.valueOf(payload)));
    }

    List<DaikinDeviceSummary> devices = new ArrayList<>();
    for (JsonNode node : payload) {
      if (node == null || !node.isObject()) {
        continue;
      }
      Optional<String> id = text(node, "_id").or(() -> text(node, "id"));
      if (id.isEmpty()) {
        continue;
      }
      String location = text(node, "locationName").orElse(null);
      String name = text(node, "name").or(() -> Optional.ofNullable(location)).orElse(id.get());
      devices.add(
          new DaikinDeviceSummary(
              id.get(), name, text(node, "model").orElse(null), text(node, "fwVer").orElse(null), location));
    }
    return devices;
  }

  // ------------------------------------------------------------------- state

  /**
   * {@code GET /devices/{_id}/live} to the Open API model.
   *
   * <p>The whole raw payload is kept under {@code additional.skyport}, so {@code --probe}
   * shows exactly what the thermostat sent alongside what the bridge made of it.
   */
  public static DaikinDevice toDevice(DaikinDeviceSummary summary, JsonNode live) {
    if (live == null || !live.isObject()) {
      throw new DaikinExceptions.DaikinResponseException(
          "live data for " + summary.id() + " was not an object: " + truncate(String.valueOf(live)));
    }

    DaikinDevice device = new DaikinDevice();
    device.id = summary.id();
    device.name = summary.name();
    device.model = summary.model();
    device.firmwareVersion = text(live, "fwVer").orElse(summary.firmwareVersion());

    device.mode = toOpenApiMode(live.get("mode"));
    device.equipmentStatus = toOpenApiEquipmentStatus(live.get("eqStatus"), live.get("fanStatus"));

    device.heatSetpoint = vutToCelsius(live.get("heatSP"));
    device.coolSetpoint = vutToCelsius(live.get("coolSP"));
    device.setpointDelta = 0.5 * SETPOINT_DELTA_VUT;
    // setpointMinimum/Maximum are left unset on purpose. The payload's spMin/spMax do not
    // behave like an envelope (a live heat setpoint sat below spMin), and the web client's
    // 35–99 °F limits are wider than HomeKit's thermostat range. Mapping's 50–90 °F
    // defaults are the One family's own range.

    device.tempIndoor = vutToCelsius(live.get("spaceTemp"));
    device.humIndoor = toHumidity(live.get("humidity"));

    for (String field : OUTDOOR_TEMPERATURE_FIELDS) {
      Double outdoor = vutToCelsius(live.get(field));
      if (outdoor != null) {
        device.tempOutdoor = outdoor;
        break;
      }
    }

    device.setAdditional("skyport", MAPPER.convertValue(live, Map.class));
    return device;
  }

  /**
   * Mode codes are the same in both clouds (0 off, 1 heat, 2 cool, 3 auto). Anything else is
   * passed through, so an unexpected value shows up in logs rather than being silently
   * rewritten.
   */
  static Integer toOpenApiMode(JsonNode raw) {
    return raw != null && raw.canConvertToInt() ? raw.asInt() : null;
  }

  /**
   * Skyport's equipment status to the Open API's.
   *
   * <p>Waiting reads as idle. Free cooling (an economiser bringing in outside air) reads as
   * cooling, because the room is being cooled. An error, or a value we do not know, becomes
   * null so {@link io.github.chelin1974.daikinhome.Mapping#toCurrentState} falls back to the
   * requested mode instead of showing a wrong state.
   */
  static Integer toOpenApiEquipmentStatus(JsonNode raw, JsonNode fanStatus) {
    if (raw == null || !raw.canConvertToInt()) {
      return null;
    }
    boolean fanRunning = fanStatus != null && fanStatus.canConvertToInt() && fanStatus.asInt() != 0;
    return switch (raw.asInt()) {
      case SkyportEquipmentStatus.HEATING -> EquipmentStatus.HEAT;
      case SkyportEquipmentStatus.COOLING, SkyportEquipmentStatus.FREE_COOLING ->
          EquipmentStatus.COOL;
      case SkyportEquipmentStatus.IDLE, SkyportEquipmentStatus.WAITING ->
          fanRunning ? EquipmentStatus.FAN : EquipmentStatus.IDLE;
      default -> null;
    };
  }

  /**
   * Relative humidity, or null.
   *
   * <p>A thermostat without a humidity sensor reports 0. Real indoor air is never 0 %, so 0
   * is treated as "not reported" rather than shown in the Home app.
   */
  static Double toHumidity(JsonNode raw) {
    if (raw == null || !raw.isNumber()) {
      return null;
    }
    double value = raw.asDouble();
    return value > 0 && value <= 100 ? value : null;
  }

  // ------------------------------------------------------------------ writes

  /**
   * An Open API {@code msp} map to the Skyport body for {@code POST /cmd/{id}/msp}.
   *
   * <p>The caller has already clamped and separated the setpoints in Celsius. Converting to
   * VUT rounds to half degrees, which can close the gap, so the separation is re-applied in
   * VUT: the cool setpoint moves up rather than sending a pair the thermostat would refuse.
   */
  public static Map<String, Object> toMspCommand(Map<String, Object> update) {
    Object mode = update.get("mode");
    Object heat = update.get("heatSetpoint");
    Object cool = update.get("coolSetpoint");
    if (!(mode instanceof Number) || !(heat instanceof Number) || !(cool instanceof Number)) {
      throw new IllegalArgumentException(
          "an msp update needs mode, heatSetpoint and coolSetpoint, got " + update);
    }

    int heatVut = celsiusToVut(((Number) heat).doubleValue());
    int coolVut = celsiusToVut(((Number) cool).doubleValue());
    if (coolVut - heatVut < SETPOINT_DELTA_VUT) {
      coolVut = heatVut + SETPOINT_DELTA_VUT;
    }

    int daikinMode = ((Number) mode).intValue();
    if (daikinMode == DaikinMode.EMERGENCY_HEAT) {
      // The Skyport msp command has no documented emergency-heat mode. Sending 4 risks a
      // rejected write or an undefined state on the thermostat; plain heat is the safe
      // reading of what the user asked for.
      daikinMode = DaikinMode.HEAT;
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("mode", daikinMode);
    body.put("heatSP", heatVut);
    body.put("coolSP", coolVut);
    return body;
  }

  // ----------------------------------------------------------------- helpers

  private static Optional<String> text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() && !value.asText().isBlank()
        ? Optional.of(value.asText().trim())
        : Optional.empty();
  }

  static String truncate(String value) {
    return value.length() > 300 ? value.substring(0, 300) + "..." : value;
  }
}
