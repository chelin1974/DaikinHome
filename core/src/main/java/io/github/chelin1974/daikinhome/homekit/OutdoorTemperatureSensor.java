package io.github.chelin1974.daikinhome.homekit;

import io.github.chelin1974.daikinhome.Mapping;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.hapjava.accessories.TemperatureSensorAccessory;
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Outdoor temperature, as a separate HomeKit sensor accessory.
 *
 * <p>Daikin sources this from the cloud for your location, not from a sensor on the
 * equipment, so treat it as a weather reading rather than a measurement.
 */
public final class OutdoorTemperatureSensor implements TemperatureSensorAccessory {

  private final int id;
  private final String name;
  private final String serialNumber;

  private volatile DaikinDevice state;

  private final AtomicReference<HomekitCharacteristicChangeCallback> onCurrentTemperature =
      new AtomicReference<>();

  public OutdoorTemperatureSensor(
      int id, String name, String serialNumber, DaikinDevice initialState) {
    this.id = id;
    this.name = name;
    this.serialNumber = serialNumber;
    this.state = initialState;
  }

  public void applyState(DaikinDevice device) {
    this.state = device;
    HomekitCharacteristicChangeCallback callback = onCurrentTemperature.get();
    if (callback != null) {
      callback.changed();
    }
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public CompletableFuture<String> getName() {
    return CompletableFuture.completedFuture(name);
  }

  @Override
  public void identify() {
    // Nothing to flash on a cloud-sourced reading.
  }

  @Override
  public CompletableFuture<String> getSerialNumber() {
    return CompletableFuture.completedFuture(serialNumber);
  }

  @Override
  public CompletableFuture<String> getModel() {
    return CompletableFuture.completedFuture("Daikin One outdoor temperature");
  }

  @Override
  public CompletableFuture<String> getManufacturer() {
    return CompletableFuture.completedFuture("Daikin");
  }

  @Override
  public CompletableFuture<String> getFirmwareRevision() {
    return CompletableFuture.completedFuture("1.0.0");
  }

  @Override
  public CompletableFuture<Double> getCurrentTemperature() {
    Double outdoor = state.tempOutdoor;
    return CompletableFuture.completedFuture(
        Mapping.clamp(outdoor == null ? 0.0 : outdoor, getMinCurrentTemperature(), getMaxCurrentTemperature()));
  }

  @Override
  public double getMinCurrentTemperature() {
    return -50;
  }

  @Override
  public double getMaxCurrentTemperature() {
    return 100;
  }

  @Override
  public double getMinStepCurrentTemperature() {
    return 0.1;
  }

  @Override
  public void subscribeCurrentTemperature(HomekitCharacteristicChangeCallback callback) {
    onCurrentTemperature.set(callback);
  }

  @Override
  public void unsubscribeCurrentTemperature() {
    onCurrentTemperature.set(null);
  }
}
