package io.github.chelin1974.daikinhome.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import io.github.chelin1974.daikinhome.metrics.DaikinMetrics.Point;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DaikinMetricsTest {

  private static final DaikinDeviceSummary HALL =
      new DaikinDeviceSummary("dev-1", " East Hallway ", "ONEPLUS", "3.2", "Home");

  private static DaikinDevice device() {
    DaikinDevice d = new DaikinDevice();
    d.tempIndoor = 21.5;
    d.humIndoor = 44.0;
    d.tempOutdoor = 12.0;
    d.heatSetpoint = 20.0;
    d.coolSetpoint = 25.5;
    d.mode = DaikinDevice.DaikinMode.AUTO;
    d.equipmentStatus = DaikinDevice.EquipmentStatus.HEAT;
    return d;
  }

  @Test
  @DisplayName("temperatures, humidity and setpoints are sent with thermostat attributes; missing ones are left out")
  void readings() {
    List<Point> points = DaikinMetrics.points(HALL, device());
    Map<String, Double> byName = points.stream()
        .filter(p -> !p.name().endsWith(".active"))
        .collect(Collectors.toMap(Point::name, Point::value));
    assertEquals(Map.of(
        "daikin.indoor.temperature", 21.5,
        "daikin.indoor.humidity", 44.0,
        "daikin.outdoor.temperature", 12.0,
        "daikin.heat.setpoint", 20.0,
        "daikin.cool.setpoint", 25.5), byName, "no outdoor humidity: the thermostat didn't report it");
    Point indoor = points.get(0);
    assertEquals("Cel", indoor.unit());
    assertEquals(Map.of("thermostat", "East Hallway", "thermostat.id", "dev-1", "location", "Home"), indoor.attributes());
  }

  @Test
  @DisplayName("equipment activity and mode are one-hot: every state is sent, 1 for the current one")
  void oneHot() {
    List<Point> points = DaikinMetrics.points(HALL, device());
    Map<String, Double> activity = points.stream().filter(p -> p.name().equals("daikin.equipment.active"))
        .collect(Collectors.toMap(p -> p.attributes().get("activity"), Point::value));
    assertEquals(Map.of("heating", 1.0, "cooling", 0.0, "dehumidifying", 0.0, "fan", 0.0, "idle", 0.0), activity);
    Map<String, Double> mode = points.stream().filter(p -> p.name().equals("daikin.mode.active"))
        .collect(Collectors.toMap(p -> p.attributes().get("mode"), Point::value));
    assertEquals(1.0, mode.get("auto"));
    assertEquals(4L, mode.values().stream().filter(v -> v == 0).count());

    DaikinDevice unknown = device();
    unknown.equipmentStatus = 99;
    assertTrue(DaikinMetrics.points(HALL, unknown).stream().noneMatch(p -> p.name().equals("daikin.equipment.active")),
        "an activity code we don't know is left out rather than guessed");
  }

  @Test
  @DisplayName("the OTLP request groups points by metric into gauges")
  @SuppressWarnings("unchecked")
  void request() {
    Map<String, Object> request = DaikinMetrics.request(
        DaikinMetrics.points(HALL, device()), 1_700_000_000_000_000_000L, Map.of("service.name", "daikin-home"), "1.0.0");
    Map<String, Object> rm = ((List<Map<String, Object>>) request.get("resourceMetrics")).get(0);
    Map<String, Object> scope = ((List<Map<String, Object>>) rm.get("scopeMetrics")).get(0);
    List<Map<String, Object>> metrics = (List<Map<String, Object>>) scope.get("metrics");
    Map<String, Object> active = metrics.stream().filter(m -> m.get("name").equals("daikin.equipment.active")).findFirst().orElseThrow();
    List<Map<String, Object>> dps = (List<Map<String, Object>>) ((Map<String, Object>) active.get("gauge")).get("dataPoints");
    assertEquals(5, dps.size());
    assertEquals("1700000000000000000", dps.get(0).get("timeUnixNano"));
    assertEquals(7, metrics.size(), "five readings, activity and mode");
  }
}
