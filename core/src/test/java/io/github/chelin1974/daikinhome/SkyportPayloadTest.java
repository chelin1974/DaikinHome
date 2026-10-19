package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.EquipmentStatus;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinResponseException;
import io.github.chelin1974.daikinhome.daikin.SkyportPayload;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkyportPayloadTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DaikinDeviceSummary SUMMARY =
      new DaikinDeviceSummary("dev-1", "Upstairs", null, null, "Home");

  private static JsonNode json(String text) throws Exception {
    return MAPPER.readTree(text);
  }

  private static DaikinDevice decode(String live) throws Exception {
    return SkyportPayload.toDevice(SUMMARY, json(live));
  }

  @Test
  @DisplayName("VUT converts to Celsius at 0.5 x raw - 40")
  void vutToCelsius() throws Exception {
    assertEquals(23.5, SkyportPayload.vutToCelsius(json("127")), 1e-9);
    assertEquals(0.0, SkyportPayload.vutToCelsius(json("80")), 1e-9);
    assertEquals(-40.0, SkyportPayload.vutToCelsius(json("0")), 1e-9);
  }

  @Test
  @DisplayName("open and shorted sensor sentinels are not temperatures")
  void sentinels() throws Exception {
    assertNull(SkyportPayload.vutToCelsius(json("255")));
    assertNull(SkyportPayload.vutToCelsius(json("254")));
    assertNull(SkyportPayload.vutToCelsius(json("-3")));
    assertNull(SkyportPayload.vutToCelsius(json("\"127\"")));
    assertNull(SkyportPayload.vutToCelsius(null));
  }

  @Test
  @DisplayName("Celsius converts back to the nearest half degree")
  void celsiusToVut() {
    assertEquals(120, SkyportPayload.celsiusToVut(20.0));
    assertEquals(125, SkyportPayload.celsiusToVut(22.3)); // 124.6
    assertEquals(124, SkyportPayload.celsiusToVut(22.2)); // 124.4
    // 72 °F, the usual Home app value, survives the round trip within half a degree.
    double seventyTwoF = (72 - 32) / 1.8;
    int vut = SkyportPayload.celsiusToVut(seventyTwoF);
    assertEquals(72, Math.round((0.5 * vut - 40) * 1.8 + 32));
  }

  @Test
  @DisplayName("equipment status maps onto the Open API codes the HomeKit mapping expects")
  void equipmentStatus() throws Exception {
    assertEquals(EquipmentStatus.HEAT, decode("{\"eqStatus\":1}").equipmentStatus);
    assertEquals(EquipmentStatus.COOL, decode("{\"eqStatus\":2}").equipmentStatus);
    assertEquals(EquipmentStatus.COOL, decode("{\"eqStatus\":5}").equipmentStatus);
    assertEquals(EquipmentStatus.IDLE, decode("{\"eqStatus\":0,\"fanStatus\":0}").equipmentStatus);
    assertEquals(EquipmentStatus.IDLE, decode("{\"eqStatus\":3}").equipmentStatus);
    assertEquals(EquipmentStatus.FAN, decode("{\"eqStatus\":0,\"fanStatus\":1}").equipmentStatus);
    assertNull(decode("{\"eqStatus\":4}").equipmentStatus, "error falls back to the mode");
    assertNull(decode("{\"eqStatus\":42}").equipmentStatus);
    assertNull(decode("{}").equipmentStatus);
  }

  @Test
  @DisplayName("mode codes pass through unchanged")
  void modes() throws Exception {
    assertEquals(DaikinMode.OFF, decode("{\"mode\":0}").mode);
    assertEquals(DaikinMode.HEAT, decode("{\"mode\":1}").mode);
    assertEquals(DaikinMode.COOL, decode("{\"mode\":2}").mode);
    assertEquals(DaikinMode.AUTO, decode("{\"mode\":3}").mode);
    assertNull(decode("{}").mode);
  }

  @Test
  @DisplayName("humidity of 0 means no sensor; real readings pass through")
  void humidity() throws Exception {
    assertNull(decode("{\"humidity\":0}").humIndoor);
    assertNull(decode("{\"humidity\":250}").humIndoor);
    assertEquals(44.0, decode("{\"humidity\":44}").humIndoor, 1e-9);
  }

  @Test
  @DisplayName("finds an outdoor temperature under any of the candidate names")
  void outdoorTemperature() throws Exception {
    assertEquals(10.0, decode("{\"outdoorTemp\":100}").tempOutdoor, 1e-9);
    assertEquals(10.0, decode("{\"tempOutdoor\":100}").tempOutdoor, 1e-9);
    assertNull(decode("{\"outdoorTemp\":255}").tempOutdoor);
    assertNull(decode("{}").tempOutdoor);
  }

  @Test
  @DisplayName("a payload missing everything still decodes, so the bridge can use its defaults")
  void sparsePayload() throws Exception {
    DaikinDevice device = decode("{}");
    assertEquals("dev-1", device.id);
    assertEquals("Upstairs", device.name);
    assertNull(device.tempIndoor);
    assertNull(device.heatSetpoint);
    assertNull(device.setpointMinimum, "Mapping's defaults apply");
  }

  @Test
  @DisplayName("a non-object live payload is a response error")
  void rejectsNonObject() {
    assertThrows(DaikinResponseException.class, () -> decode("[]"));
  }

  @Test
  @DisplayName("the msp command carries mode and VUT setpoints")
  void mspCommand() {
    Map<String, Object> command =
        SkyportPayload.toMspCommand(new Mapping.MspUpdate(DaikinMode.AUTO, 20.0, 24.0).toMap());
    assertEquals(Map.of("mode", 3, "heatSP", 120, "coolSP", 128), command);
  }

  @Test
  @DisplayName("half-degree rounding cannot close the heat/cool gap")
  void mspCommandKeepsSeparation() {
    // 1.1 °C apart in Celsius, but 21.24 rounds to 122 and 21.6 to 123: only 1 VUT apart.
    Map<String, Object> command =
        SkyportPayload.toMspCommand(new Mapping.MspUpdate(DaikinMode.AUTO, 21.24, 21.6).toMap());
    assertEquals(122, command.get("heatSP"));
    assertEquals(124, command.get("coolSP"));
  }

  @Test
  @DisplayName("emergency heat is sent as heat, which the msp command is known to accept")
  void mspCommandEmergencyHeat() {
    Map<String, Object> command =
        SkyportPayload.toMspCommand(
            new Mapping.MspUpdate(DaikinMode.EMERGENCY_HEAT, 20.0, 24.0).toMap());
    assertEquals(DaikinMode.HEAT, command.get("mode"));
  }

  @Test
  @DisplayName("an incomplete msp update is rejected rather than half-sent")
  void mspCommandIncomplete() {
    assertThrows(
        IllegalArgumentException.class, () -> SkyportPayload.toMspCommand(Map.of("mode", 1)));
  }

  @Test
  @DisplayName("the listing skips entries without an id and labels nameless ones")
  void summaries() throws Exception {
    List<DaikinDeviceSummary> devices =
        SkyportPayload.toSummaries(
            json(
                "[{\"_id\":\"a\",\"name\":\"Den\",\"locationName\":\"Home\"},"
                    + "{\"_id\":\"b\",\"name\":\"\",\"locationName\":\"Cabin\"},"
                    + "{\"_id\":\"c\"},"
                    + "{\"name\":\"no id\"}, null, 7]"));

    assertEquals(3, devices.size());
    assertEquals("Den", devices.get(0).name());
    assertEquals("Cabin", devices.get(1).name());
    assertEquals("c", devices.get(2).name());
  }

  @Test
  @DisplayName("a listing that is not an array is a response error")
  void summariesRejectNonArray() {
    assertThrows(DaikinResponseException.class, () -> SkyportPayload.toSummaries(json("{}")));
  }
}
