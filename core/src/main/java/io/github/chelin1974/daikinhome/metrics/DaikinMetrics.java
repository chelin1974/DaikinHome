package io.github.chelin1974.daikinhome.metrics;

import io.github.chelin1974.daikinhome.ThermostatGateway;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.EquipmentStatus;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the thermostats' readings into an OTLP metrics request (the JSON encoding of
 * {@code ExportMetricsServiceRequest}).
 *
 * <p>Pure: no network and no clock of its own. Names follow OpenTelemetry's dotted style;
 * Prometheus' OTLP receiver turns {@code daikin.indoor.temperature} with unit {@code Cel} into
 * {@code daikin_indoor_temperature_celsius}, and attribute {@code thermostat.id} into label
 * {@code thermostat_id}. Readings a thermostat doesn't report are left out rather than sent as 0.
 *
 * <table>
 *   <caption>Metrics, each with attributes thermostat, thermostat.id and, when known, location</caption>
 *   <tr><th>Name</th><th>Unit</th><th>Value</th></tr>
 *   <tr><td>daikin.indoor.temperature</td><td>Cel</td><td>indoor temperature</td></tr>
 *   <tr><td>daikin.indoor.humidity</td><td>%</td><td>indoor relative humidity</td></tr>
 *   <tr><td>daikin.outdoor.temperature</td><td>Cel</td><td>outdoor temperature</td></tr>
 *   <tr><td>daikin.outdoor.humidity</td><td>%</td><td>outdoor relative humidity</td></tr>
 *   <tr><td>daikin.heat.setpoint</td><td>Cel</td><td>heat setpoint</td></tr>
 *   <tr><td>daikin.cool.setpoint</td><td>Cel</td><td>cool setpoint</td></tr>
 *   <tr><td>daikin.equipment.active</td><td>none</td><td>1 for what the equipment is doing, 0 for the
 *       rest; attribute activity: heating, cooling, dehumidifying, fan, idle</td></tr>
 *   <tr><td>daikin.mode.active</td><td>none</td><td>1 for the chosen mode, 0 for the rest;
 *       attribute mode: off, heat, cool, auto, emergency_heat</td></tr>
 * </table>
 *
 * <p>The two "active" metrics send every state, so a series exists for each and summing one over
 * time gives how long the system spent in it.
 */
public final class DaikinMetrics {

  /** One reading. */
  public record Point(String name, String unit, String description, double value, Map<String, String> attributes) {}

  static final Map<Integer, String> ACTIVITIES = new LinkedHashMap<>();
  static final Map<Integer, String> MODES = new LinkedHashMap<>();

  static {
    ACTIVITIES.put(EquipmentStatus.HEAT, "heating");
    ACTIVITIES.put(EquipmentStatus.COOL, "cooling");
    ACTIVITIES.put(EquipmentStatus.OVERCOOL_DEHUMIDIFY, "dehumidifying");
    ACTIVITIES.put(EquipmentStatus.FAN, "fan");
    ACTIVITIES.put(EquipmentStatus.IDLE, "idle");
    MODES.put(DaikinMode.OFF, "off");
    MODES.put(DaikinMode.HEAT, "heat");
    MODES.put(DaikinMode.COOL, "cool");
    MODES.put(DaikinMode.AUTO, "auto");
    MODES.put(DaikinMode.EMERGENCY_HEAT, "emergency_heat");
  }

  private DaikinMetrics() {}

  /** Every thermostat's readings. A thermostat whose state can't be read is skipped. */
  public static List<Point> collect(ThermostatGateway gateway) {
    List<Point> points = new ArrayList<>();
    if (!gateway.isRunning()) {
      return points;
    }
    for (DaikinDeviceSummary summary : gateway.devices()) {
      DaikinDevice d;
      try {
        d = gateway.state(summary.id());
      } catch (RuntimeException e) {
        continue;
      }
      if (d == null) {
        continue;
      }
      points.addAll(points(summary, d));
    }
    return points;
  }

  /** One thermostat's readings. Pure, for tests. */
  static List<Point> points(DaikinDeviceSummary summary, DaikinDevice d) {
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("thermostat", summary.name() == null ? summary.id() : summary.name().trim());
    attrs.put("thermostat.id", summary.id());
    if (summary.locationName() != null && !summary.locationName().isBlank()) {
      attrs.put("location", summary.locationName().trim());
    }
    List<Point> points = new ArrayList<>();
    add(points, "daikin.indoor.temperature", "Cel", "Indoor temperature", d.tempIndoor, attrs);
    add(points, "daikin.indoor.humidity", "%", "Indoor relative humidity", d.humIndoor, attrs);
    add(points, "daikin.outdoor.temperature", "Cel", "Outdoor temperature", d.tempOutdoor, attrs);
    add(points, "daikin.outdoor.humidity", "%", "Outdoor relative humidity", d.humOutdoor, attrs);
    add(points, "daikin.heat.setpoint", "Cel", "Heat setpoint", d.heatSetpoint, attrs);
    add(points, "daikin.cool.setpoint", "Cel", "Cool setpoint", d.coolSetpoint, attrs);
    oneHot(points, "daikin.equipment.active", "activity", "What the equipment is doing: 1 for the current activity",
        ACTIVITIES, d.equipmentStatus, attrs);
    oneHot(points, "daikin.mode.active", "mode", "The thermostat's mode: 1 for the current mode",
        MODES, d.mode, attrs);
    return points;
  }

  private static void add(List<Point> points, String name, String unit, String description, Double value,
      Map<String, String> attrs) {
    if (value != null && Double.isFinite(value)) {
      points.add(new Point(name, unit, description, value, attrs));
    }
  }

  private static void oneHot(List<Point> points, String name, String key, String description,
      Map<Integer, String> states, Integer current, Map<String, String> attrs) {
    if (current == null || !states.containsKey(current)) {
      return;
    }
    states.forEach((code, label) -> {
      Map<String, String> a = new LinkedHashMap<>(attrs);
      a.put(key, label);
      points.add(new Point(name, "", description, code.equals(current) ? 1 : 0, a));
    });
  }

  /** The OTLP JSON request. Points with the same name become one gauge with several data points. */
  public static Map<String, Object> request(List<Point> points, long nowNanos, Map<String, String> resource, String version) {
    Map<String, Map<String, Object>> metrics = new LinkedHashMap<>();
    for (Point point : points) {
      Map<String, Object> metric = metrics.computeIfAbsent(point.name(), name -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("unit", point.unit());
        m.put("description", point.description());
        Map<String, Object> gauge = new LinkedHashMap<>();
        gauge.put("dataPoints", new ArrayList<>());
        m.put("gauge", gauge);
        return m;
      });
      @SuppressWarnings("unchecked")
      List<Object> dataPoints = (List<Object>) ((Map<String, Object>) metric.get("gauge")).get("dataPoints");
      Map<String, Object> dp = new LinkedHashMap<>();
      dp.put("attributes", attributes(point.attributes()));
      // OTLP JSON carries 64-bit integers as strings.
      dp.put("timeUnixNano", Long.toString(nowNanos));
      dp.put("asDouble", point.value());
      dataPoints.add(dp);
    }
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("scope", Map.of("name", "io.github.chelin1974.daikinhome", "version", version));
    scope.put("metrics", new ArrayList<>(metrics.values()));
    Map<String, Object> resourceMetrics = new LinkedHashMap<>();
    resourceMetrics.put("resource", Map.of("attributes", attributes(resource)));
    resourceMetrics.put("scopeMetrics", List.of(scope));
    return Map.of("resourceMetrics", List.of(resourceMetrics));
  }

  private static List<Object> attributes(Map<String, String> values) {
    List<Object> out = new ArrayList<>();
    values.forEach((key, value) -> out.add(Map.of("key", key, "value", Map.of("stringValue", value))));
    return out;
  }
}
