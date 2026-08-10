package io.github.chelin1974.daikinhome.daikin;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * State of one thermostat, as returned by {@code GET /v1/devices/{id}}.
 *
 * <p>Every field is a boxed type and may be {@code null}. Daikin's payload varies by
 * thermostat model and firmware, so nothing here is guaranteed to be present — read
 * values through {@link io.github.chelin1974.daikinhome.Mapping}, which supplies
 * sensible fallbacks, rather than dereferencing fields directly.
 *
 * <p>Temperatures are degrees Celsius; humidity is relative percent.
 *
 * <p>Run the probe tool ({@code --probe}) to see exactly what your thermostat returns.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
// Absent fields are omitted when this is written back out, so the probe tool shows what
// the thermostat actually reports rather than a wall of nulls.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DaikinDevice {

  public String id;
  public String name;
  public String model;
  public String firmwareVersion;

  /** What the equipment is doing right now. See {@link EquipmentStatus}. */
  public Integer equipmentStatus;

  /** Requested system mode. See {@link DaikinMode}. */
  public Integer mode;

  /** Which modes the installer allowed. Semantics undocumented; advisory only. */
  public Integer modeLimit;

  public Boolean modeEmHeatAvailable;

  public Integer fan;
  public Integer fanCirculate;
  public Integer fanCirculateSpeed;

  public Double heatSetpoint;
  public Double coolSetpoint;

  /** Minimum gap the thermostat enforces between the heat and cool setpoints. */
  public Double setpointDelta;

  public Double setpointMinimum;
  public Double setpointMaximum;

  public Double tempIndoor;
  public Double humIndoor;
  public Double tempOutdoor;
  public Double humOutdoor;

  public Boolean scheduleEnabled;
  public Boolean geofencingEnabled;

  /**
   * Anything Daikin returns that this class does not name.
   *
   * <p>Kept so the probe tool can show fields a newer firmware has added without this
   * class needing to know about them first.
   */
  private final Map<String, Object> additional = new LinkedHashMap<>();

  @JsonAnySetter
  public void setAdditional(String key, Object value) {
    additional.put(key, value);
  }

  @JsonAnyGetter
  public Map<String, Object> getAdditional() {
    return additional;
  }

  /** Shallow copy, used to layer a pending write over the last polled state. */
  public DaikinDevice copy() {
    DaikinDevice copy = new DaikinDevice();
    copy.id = id;
    copy.name = name;
    copy.model = model;
    copy.firmwareVersion = firmwareVersion;
    copy.equipmentStatus = equipmentStatus;
    copy.mode = mode;
    copy.modeLimit = modeLimit;
    copy.modeEmHeatAvailable = modeEmHeatAvailable;
    copy.fan = fan;
    copy.fanCirculate = fanCirculate;
    copy.fanCirculateSpeed = fanCirculateSpeed;
    copy.heatSetpoint = heatSetpoint;
    copy.coolSetpoint = coolSetpoint;
    copy.setpointDelta = setpointDelta;
    copy.setpointMinimum = setpointMinimum;
    copy.setpointMaximum = setpointMaximum;
    copy.tempIndoor = tempIndoor;
    copy.humIndoor = humIndoor;
    copy.tempOutdoor = tempOutdoor;
    copy.humOutdoor = humOutdoor;
    copy.scheduleEnabled = scheduleEnabled;
    copy.geofencingEnabled = geofencingEnabled;
    copy.additional.putAll(additional);
    return copy;
  }

  /** {@code mode} values. */
  public static final class DaikinMode {
    public static final int OFF = 0;
    public static final int HEAT = 1;
    public static final int COOL = 2;
    public static final int AUTO = 3;
    public static final int EMERGENCY_HEAT = 4;

    private DaikinMode() {}
  }

  /** {@code equipmentStatus} values. */
  public static final class EquipmentStatus {
    public static final int COOL = 1;
    public static final int OVERCOOL_DEHUMIDIFY = 2;
    public static final int HEAT = 3;
    public static final int FAN = 4;
    public static final int IDLE = 5;

    private EquipmentStatus() {}
  }

  /** {@code fanCirculate} values. */
  public static final class FanCirculate {
    public static final int OFF = 0;
    public static final int ALWAYS_ON = 1;
    public static final int ON_SCHEDULE = 2;

    private FanCirculate() {}
  }
}
