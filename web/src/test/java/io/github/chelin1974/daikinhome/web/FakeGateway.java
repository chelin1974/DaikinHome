package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.Mapping;
import io.github.chelin1974.daikinhome.Mapping.Anchor;
import io.github.chelin1974.daikinhome.Mapping.Desired;
import io.github.chelin1974.daikinhome.Mapping.MspUpdate;
import io.github.chelin1974.daikinhome.ThermostatGateway;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.EquipmentStatus;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * An in-memory {@link ThermostatGateway}, so the REST layer can be tested without starting
 * a HomeKit server or advertising over mDNS.
 *
 * <p>It still runs the real {@link Mapping} clamping on every write, so the tests exercise
 * the behaviour that actually matters rather than a stub that agrees with everything.
 */
class FakeGateway implements ThermostatGateway {

  private final Map<String, DaikinDeviceSummary> summaries = new LinkedHashMap<>();
  private final Map<String, DaikinDevice> devices = new LinkedHashMap<>();

  final List<MspUpdate> writes = new ArrayList<>();
  final List<String> refreshes = new ArrayList<>();

  boolean running;
  boolean paired;
  String setupCode;

  /** Set to make the next write blow up, for exception-mapping tests. */
  Supplier<RuntimeException> failNextWrite;

  FakeGateway() {
    reset();
  }

  /**
   * Restores the starting state.
   *
   * <p>Spring caches the application context, so this bean is one singleton shared by every
   * test method in the class. Without an explicit reset the tests pass or fail depending on
   * the order they happen to run in.
   */
  void reset() {
    summaries.clear();
    devices.clear();
    writes.clear();
    refreshes.clear();
    running = true;
    paired = false;
    setupCode = "379-96-555";
    failNextWrite = null;

    DaikinDeviceSummary summary =
        new DaikinDeviceSummary("thermostat-1", "Hallway", "ONEPLUS", "3.4.1", "Home");

    DaikinDevice device = new DaikinDevice();
    device.id = "thermostat-1";
    device.name = "Hallway";
    device.mode = DaikinMode.AUTO;
    device.equipmentStatus = EquipmentStatus.HEAT;
    device.heatSetpoint = 20.0;
    device.coolSetpoint = 24.0;
    device.setpointMinimum = 10.0;
    device.setpointMaximum = 32.2;
    device.setpointDelta = 2.0;
    device.tempIndoor = 19.5;
    device.humIndoor = 41.0;
    device.tempOutdoor = 7.2;
    device.scheduleEnabled = true;

    summaries.put(summary.id(), summary);
    devices.put(summary.id(), device);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isPaired() {
    return paired;
  }

  @Override
  public Optional<String> setupCode() {
    return paired ? Optional.empty() : Optional.ofNullable(setupCode);
  }

  @Override
  public BridgeSettings settings() {
    return BridgeSettings.builder()
        .apiKey("key")
        .email("user@example.com")
        .integratorToken("token")
        .build();
  }

  @Override
  public List<DaikinDeviceSummary> devices() {
    return List.copyOf(summaries.values());
  }

  @Override
  public Optional<DaikinDeviceSummary> device(String deviceId) {
    return Optional.ofNullable(summaries.get(deviceId));
  }

  @Override
  public DaikinDevice state(String deviceId) {
    DaikinDevice device = devices.get(deviceId);
    if (device == null) {
      throw new NoSuchElementException("no thermostat with id " + deviceId);
    }
    return device;
  }

  @Override
  public MspUpdate apply(String deviceId, Desired desired, Anchor anchor) {
    DaikinDevice current = state(deviceId);

    if (failNextWrite != null) {
      RuntimeException error = failNextWrite.get();
      failNextWrite = null;
      throw error;
    }

    MspUpdate update = Mapping.buildMspUpdate(current, desired, anchor);
    writes.add(update);

    DaikinDevice applied = current.copy();
    applied.mode = update.mode();
    applied.heatSetpoint = update.heatSetpoint();
    applied.coolSetpoint = update.coolSetpoint();
    devices.put(deviceId, applied);

    return update;
  }

  @Override
  public void refresh(String deviceId) {
    state(deviceId);
    refreshes.add(deviceId);
  }
}
