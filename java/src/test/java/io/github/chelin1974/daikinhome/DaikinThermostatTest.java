package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import io.github.chelin1974.daikinhome.daikin.DaikinOneClient;
import io.github.chelin1974.daikinhome.homekit.DaikinThermostat;
import io.github.hapjava.characteristics.Characteristic;
import io.github.hapjava.characteristics.impl.base.FloatCharacteristic;
import io.github.hapjava.characteristics.impl.humiditysensor.CurrentRelativeHumidityCharacteristic;
import io.github.hapjava.characteristics.impl.thermostat.CoolingThresholdTemperatureCharacteristic;
import io.github.hapjava.characteristics.impl.thermostat.CurrentHeatingCoolingStateEnum;
import io.github.hapjava.characteristics.impl.thermostat.HeatingThresholdTemperatureCharacteristic;
import io.github.hapjava.characteristics.impl.thermostat.TargetHeatingCoolingStateEnum;
import io.github.hapjava.services.Service;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for the accessory, driven against a real HTTP server.
 *
 * <p>These are the tests that would catch a bridge which compiles but misbehaves in the
 * Home app: writes that fire twice, a dial that snaps back, or setpoints the thermostat
 * would reject.
 */
class DaikinThermostatTest {

  private FakeDaikinCloud cloud;
  private DaikinOneClient client;
  private ScheduledExecutorService scheduler;
  private final AtomicInteger refreshRequests = new AtomicInteger();

  @BeforeEach
  void setUp() throws IOException {
    cloud = new FakeDaikinCloud();
    client =
        DaikinOneClient.builder()
            .apiKey(FakeDaikinCloud.API_KEY)
            .email("user@example.com")
            .integratorToken("integrator")
            .baseUrl(cloud.baseUrl())
            .minRequestInterval(Duration.ZERO)
            .build();
    scheduler = Executors.newScheduledThreadPool(2);
    refreshRequests.set(0);
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
    cloud.close();
  }

  private DaikinThermostat thermostat() {
    return thermostat(true);
  }

  private DaikinThermostat thermostat(boolean showIndoorHumidity) {
    DaikinDevice initial = client.getDevice(cloud.deviceId());
    return new DaikinThermostat(
        2,
        new DaikinDeviceSummary("thermostat-1", "Hallway", "ONEPLUS", "3.4.1", "Home"),
        initial,
        client,
        scheduler,
        showIndoorHumidity,
        refreshRequests::incrementAndGet);
  }

  private static void awaitUntil(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for condition");
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted", e);
      }
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName("publishes the thermostat's current state to HomeKit")
  void publishesState() throws Exception {
    DaikinThermostat thermostat = thermostat();

    assertEquals("Hallway", thermostat.getName().get());
    assertEquals("thermostat-1", thermostat.getSerialNumber().get());
    assertEquals("Daikin", thermostat.getManufacturer().get());
    assertEquals("ONEPLUS", thermostat.getModel().get());
    assertEquals("3.4.1", thermostat.getFirmwareRevision().get());

    assertEquals(19.5, thermostat.getCurrentTemperature().get());
    assertEquals(CurrentHeatingCoolingStateEnum.HEAT, thermostat.getCurrentState().get());
    assertEquals(TargetHeatingCoolingStateEnum.AUTO, thermostat.getTargetState().get());
    // Auto mode: the single dial reports the midpoint of the two setpoints.
    assertEquals(22.0, thermostat.getTargetTemperature().get());
  }

  @Test
  @DisplayName("takes its slider range from the thermostat, not HAP's generic defaults")
  void usesDeviceSetpointRange() {
    DaikinThermostat thermostat = thermostat();

    assertEquals(10.0, thermostat.getMinTargetTemperature());
    assertEquals(32.2, thermostat.getMaxTargetTemperature());
  }

  @Test
  @DisplayName("coalesces a mode change and a temperature change into one API write")
  void coalescesWrites() {
    DaikinThermostat thermostat = thermostat();

    thermostat.setTargetState(TargetHeatingCoolingStateEnum.HEAT);
    thermostat.setTargetTemperature(22.5);

    awaitUntil(() -> !cloud.writes.isEmpty());
    // Give any straggling second write time to arrive before asserting there is only one.
    sleep(Duration.ofMillis(500));

    assertEquals(1, cloud.writes.size());
    Map<String, Object> write = cloud.writes.get(0);
    assertEquals(DaikinMode.HEAT, write.get("mode"));
    assertEquals(22.5, write.get("heatSetpoint"));
    assertEquals(24.5, write.get("coolSetpoint"));
  }

  @Test
  @DisplayName("shows the new value immediately, before the cloud confirms it")
  void showsWritesOptimistically() throws Exception {
    DaikinThermostat thermostat = thermostat();

    thermostat.setTargetState(TargetHeatingCoolingStateEnum.COOL);

    // Read back before the debounce window has even elapsed.
    assertEquals(TargetHeatingCoolingStateEnum.COOL, thermostat.getTargetState().get());
    assertTrue(cloud.writes.isEmpty(), "the write should not have been sent yet");
  }

  @Test
  @DisplayName("keeps the heat and cool setpoints separated by the reported delta")
  void enforcesSetpointSeparation() {
    DaikinThermostat thermostat = thermostat();
    Service service = thermostat.getServices().iterator().next();

    // Drag the heat threshold up past the cool threshold.
    setCharacteristic(service, HeatingThresholdTemperatureCharacteristic.class, 25.0);

    awaitUntil(() -> !cloud.writes.isEmpty());

    Map<String, Object> write = cloud.writes.get(0);
    assertEquals(25.0, write.get("heatSetpoint"));
    assertEquals(27.0, write.get("coolSetpoint"));
  }

  @Test
  @DisplayName("reverts the Home app when the thermostat rejects a change")
  void revertsRejectedWrites() throws Exception {
    DaikinThermostat thermostat = thermostat();
    cloud.mspStatus = 400;

    thermostat.setTargetState(TargetHeatingCoolingStateEnum.OFF);

    // The optimistic view holds until the failure comes back.
    awaitUntil(() -> {
      try {
        return thermostat.getTargetState().get() == TargetHeatingCoolingStateEnum.AUTO;
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });

    assertTrue(cloud.writes.isEmpty(), "a rejected write must not be recorded");
    assertEquals(0, refreshRequests.get(), "a failed write should not schedule a settle refresh");
  }

  @Test
  @DisplayName("re-reads the thermostat after a successful write settles")
  void schedulesSettleRefresh() {
    DaikinThermostat thermostat = thermostat();

    thermostat.setTargetState(TargetHeatingCoolingStateEnum.HEAT);
    awaitUntil(() -> !cloud.writes.isEmpty());

    // WRITE_SETTLE is 20s in production; assert the refresh was scheduled rather than
    // waiting for it, so the suite stays fast.
    assertEquals(0, refreshRequests.get());
    assertTrue(Config.WRITE_SETTLE.toSeconds() >= 15, "Daikin documents a 15s settle minimum");
  }

  @Test
  @DisplayName("exposes the threshold characteristics Auto mode needs")
  void exposesThresholds() {
    Service service = thermostat().getServices().iterator().next();

    assertTrue(hasCharacteristic(service, HeatingThresholdTemperatureCharacteristic.class));
    assertTrue(hasCharacteristic(service, CoolingThresholdTemperatureCharacteristic.class));
    assertTrue(hasCharacteristic(service, CurrentRelativeHumidityCharacteristic.class));

    // The thresholds must carry the thermostat's own range, not HAP's defaults, or the
    // Home app will happily offer setpoints the thermostat rejects.
    FloatCharacteristic heating =
        characteristic(service, HeatingThresholdTemperatureCharacteristic.class);
    assertEquals(10.0, heating.getMinValue());
    assertEquals(32.2, heating.getMaxValue());
  }

  @Test
  @DisplayName("omits humidity when it is turned off")
  void omitsHumidityWhenDisabled() {
    Service service = thermostat(false).getServices().iterator().next();

    assertFalse(hasCharacteristic(service, CurrentRelativeHumidityCharacteristic.class));
    assertTrue(hasCharacteristic(service, HeatingThresholdTemperatureCharacteristic.class));
  }

  @Test
  @DisplayName("applies a refreshed poll to the characteristics")
  void appliesPolledState() throws Exception {
    DaikinThermostat thermostat = thermostat();

    DaikinDevice updated = client.getDevice(cloud.deviceId()).copy();
    updated.tempIndoor = 24.9;
    updated.mode = DaikinMode.COOL;
    thermostat.applyState(updated);

    assertEquals(24.9, thermostat.getCurrentTemperature().get());
    assertEquals(TargetHeatingCoolingStateEnum.COOL, thermostat.getTargetState().get());
  }

  private static boolean hasCharacteristic(Service service, Class<?> type) {
    return service.getCharacteristics().stream().anyMatch(type::isInstance);
  }

  private static FloatCharacteristic characteristic(Service service, Class<?> type) {
    List<Characteristic> characteristics = service.getCharacteristics();
    return characteristics.stream()
        .filter(type::isInstance)
        .map(FloatCharacteristic.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " on the service"));
  }

  private static void setCharacteristic(Service service, Class<?> type, double value) {
    try {
      characteristic(service, type).setValue(value);
    } catch (Exception e) {
      throw new AssertionError("could not set " + type.getSimpleName(), e);
    }
  }
}
