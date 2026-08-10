package io.github.chelin1974.daikinhome.homekit;

import io.github.chelin1974.daikinhome.Config;
import io.github.chelin1974.daikinhome.Mapping;
import io.github.chelin1974.daikinhome.Mapping.Anchor;
import io.github.chelin1974.daikinhome.Mapping.Desired;
import io.github.chelin1974.daikinhome.Mapping.MspUpdate;
import io.github.chelin1974.daikinhome.Mapping.SetpointLimits;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import io.github.chelin1974.daikinhome.daikin.DaikinOneClient;
import io.github.hapjava.accessories.ThermostatAccessory;
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback;
import io.github.hapjava.characteristics.impl.humiditysensor.CurrentRelativeHumidityCharacteristic;
import io.github.hapjava.characteristics.impl.thermostat.CoolingThresholdTemperatureCharacteristic;
import io.github.hapjava.characteristics.impl.thermostat.CurrentHeatingCoolingStateEnum;
import io.github.hapjava.characteristics.impl.thermostat.HeatingThresholdTemperatureCharacteristic;
import io.github.hapjava.characteristics.impl.thermostat.TargetHeatingCoolingStateEnum;
import io.github.hapjava.characteristics.impl.thermostat.TemperatureDisplayUnitEnum;
import io.github.hapjava.services.Service;
import io.github.hapjava.services.impl.ThermostatService;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Daikin thermostat, exposed to HomeKit.
 *
 * <p>Reads are served from the last poll — HomeKit characteristic reads must return
 * immediately and must never block on the network. Writes are debounced and coalesced into
 * a single {@code msp} call, and are shown optimistically until the Daikin cloud catches
 * up, so the Home app does not snap the dial back mid-gesture.
 */
public final class DaikinThermostat implements ThermostatAccessory {

  private static final Logger log = LoggerFactory.getLogger(DaikinThermostat.class);

  private final int id;
  private final DaikinDeviceSummary summary;
  private final DaikinOneClient client;
  private final ScheduledExecutorService scheduler;
  private final boolean showIndoorHumidity;

  /** Asks the bridge to re-read this thermostat once a write has settled. */
  private final Runnable requestRefresh;

  private volatile DaikinDevice state;
  private volatile TemperatureDisplayUnitEnum displayUnit = TemperatureDisplayUnitEnum.CELSIUS;

  /** Guards every field below it. */
  private final Object writeLock = new Object();

  private Desired pending = Desired.NOTHING;
  private Anchor anchor = Anchor.NONE;
  private Instant optimisticUntil = Instant.EPOCH;
  private ScheduledFuture<?> flushTask;
  private ScheduledFuture<?> settleTask;

  private final AtomicReference<HomekitCharacteristicChangeCallback> onCurrentTemperature =
      new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onTargetTemperature =
      new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onCurrentState =
      new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onTargetState =
      new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onDisplayUnit =
      new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onHeatingThreshold =
      new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onCoolingThreshold =
      new AtomicReference<>();
  private final AtomicReference<HomekitCharacteristicChangeCallback> onHumidity =
      new AtomicReference<>();

  public DaikinThermostat(
      int id,
      DaikinDeviceSummary summary,
      DaikinDevice initialState,
      DaikinOneClient client,
      ScheduledExecutorService scheduler,
      boolean showIndoorHumidity,
      Runnable requestRefresh) {
    this.id = id;
    this.summary = summary;
    this.state = initialState;
    this.client = client;
    this.scheduler = scheduler;
    this.showIndoorHumidity = showIndoorHumidity;
    this.requestRefresh = requestRefresh;
  }

  public String deviceId() {
    return summary.id();
  }

  public String displayName() {
    return summary.name();
  }

  /** Called by the bridge after every successful poll. */
  public void applyState(DaikinDevice device) {
    this.state = device;
    notifyAll(
        onCurrentTemperature,
        onTargetTemperature,
        onCurrentState,
        onTargetState,
        onHeatingThreshold,
        onCoolingThreshold,
        onHumidity);
  }

  /** Cancels timers so the JVM can shut down promptly. */
  public void dispose() {
    synchronized (writeLock) {
      cancel(flushTask);
      cancel(settleTask);
      flushTask = null;
      settleTask = null;
    }
  }

  // ------------------------------------------------------------- accessory metadata

  @Override
  public int getId() {
    return id;
  }

  @Override
  public CompletableFuture<String> getName() {
    return CompletableFuture.completedFuture(summary.name());
  }

  @Override
  public void identify() {
    log.info("[{}] identify requested from the Home app", displayName());
  }

  @Override
  public CompletableFuture<String> getSerialNumber() {
    return CompletableFuture.completedFuture(summary.id());
  }

  @Override
  public CompletableFuture<String> getModel() {
    return CompletableFuture.completedFuture(
        summary.model() == null ? "Daikin One" : summary.model());
  }

  @Override
  public CompletableFuture<String> getManufacturer() {
    return CompletableFuture.completedFuture("Daikin");
  }

  @Override
  public CompletableFuture<String> getFirmwareRevision() {
    return CompletableFuture.completedFuture(
        summary.firmwareVersion() == null ? "0.0.0" : summary.firmwareVersion());
  }

  // -------------------------------------------------------------------- reads

  @Override
  public CompletableFuture<Double> getCurrentTemperature() {
    DaikinDevice device = state;
    double indoor = device.tempIndoor == null ? 0.0 : device.tempIndoor;
    return CompletableFuture.completedFuture(
        Mapping.clamp(indoor, getMinCurrentTemperature(), getMaxCurrentTemperature()));
  }

  @Override
  public CompletableFuture<Double> getTargetTemperature() {
    return CompletableFuture.completedFuture(bounded(Mapping.toTargetTemperature(view())));
  }

  @Override
  public CompletableFuture<CurrentHeatingCoolingStateEnum> getCurrentState() {
    return CompletableFuture.completedFuture(Mapping.toCurrentState(view()));
  }

  @Override
  public CompletableFuture<TargetHeatingCoolingStateEnum> getTargetState() {
    return CompletableFuture.completedFuture(Mapping.toTargetState(view()));
  }

  @Override
  public CompletableFuture<TemperatureDisplayUnitEnum> getTemperatureDisplayUnit() {
    return CompletableFuture.completedFuture(displayUnit);
  }

  private CompletableFuture<Double> getHeatingThreshold() {
    return CompletableFuture.completedFuture(bounded(view().heatSetpoint));
  }

  private CompletableFuture<Double> getCoolingThreshold() {
    return CompletableFuture.completedFuture(bounded(view().coolSetpoint));
  }

  private CompletableFuture<Double> getIndoorHumidity() {
    Double humidity = state.humIndoor;
    return CompletableFuture.completedFuture(
        humidity == null ? 0.0 : Mapping.clamp(Math.round(humidity), 0, 100));
  }

  // ------------------------------------------------------------------- writes

  @Override
  public void setTargetState(TargetHeatingCoolingStateEnum value) {
    int mode = Mapping.toDaikinMode(value, state);
    log.info("[{}] mode -> {}", displayName(), Mapping.describeMode(mode));
    queue(Desired.ofMode(mode), Anchor.NONE);
  }

  @Override
  public void setTargetTemperature(Double value) {
    double target = Mapping.round1(value);

    // Outside Auto, the single HomeKit dial drives whichever setpoint the current mode
    // uses. In Auto, Home uses the threshold characteristics, so a write here means the
    // user is dragging the midpoint — shift both.
    switch (Mapping.toTargetState(view())) {
      case HEAT -> queue(Desired.ofHeat(target), Anchor.HEAT);
      case COOL -> queue(Desired.ofCool(target), Anchor.COOL);
      default -> {
        double half = Mapping.getSetpointLimits(view()).delta() / 2;
        queue(
            new Desired(null, Mapping.round1(target - half), Mapping.round1(target + half)),
            Anchor.NONE);
      }
    }
  }

  @Override
  public void setTemperatureDisplayUnit(TemperatureDisplayUnitEnum value) {
    // Purely a display preference; the Daikin API is Celsius either way.
    this.displayUnit = value;
    fire(onDisplayUnit);
  }

  private void setHeatingThreshold(Double value) {
    queue(Desired.ofHeat(Mapping.round1(value)), Anchor.HEAT);
  }

  private void setCoolingThreshold(Double value) {
    queue(Desired.ofCool(Mapping.round1(value)), Anchor.COOL);
  }

  /** Merges a change into the pending write and re-arms the flush timer. */
  private void queue(Desired desired, Anchor newAnchor) {
    synchronized (writeLock) {
      pending = pending.merge(desired);
      if (newAnchor != Anchor.NONE) {
        anchor = newAnchor;
      }
      // Keep serving the optimistic view until the write lands and settles.
      optimisticUntil =
          Instant.now().plus(Config.WRITE_DEBOUNCE).plus(Config.WRITE_SETTLE);

      cancel(flushTask);
      flushTask =
          scheduler.schedule(
              this::flush, Config.WRITE_DEBOUNCE.toMillis(), TimeUnit.MILLISECONDS);
    }

    // The dial should move the moment the user lets go, not after the API round trip.
    notifyAll(onTargetState, onTargetTemperature, onHeatingThreshold, onCoolingThreshold);
  }

  private void flush() {
    Desired desired;
    Anchor effectiveAnchor;

    synchronized (writeLock) {
      if (pending.isEmpty()) {
        return;
      }
      desired = pending;
      effectiveAnchor = anchor;
      pending = Desired.NOTHING;
      anchor = Anchor.NONE;
    }

    DaikinDevice current = state;
    MspUpdate update = Mapping.buildMspUpdate(current, desired, effectiveAnchor);

    try {
      client.updateDevice(deviceId(), update.toMap());
      log.info(
          "[{}] set mode {}, heat {} C, cool {} C",
          displayName(),
          Mapping.describeMode(update.mode()),
          update.heatSetpoint(),
          update.coolSetpoint());

      // Believe our own write until the poll after the cloud has settled.
      DaikinDevice applied = current.copy();
      applied.mode = update.mode();
      applied.heatSetpoint = update.heatSetpoint();
      applied.coolSetpoint = update.coolSetpoint();

      synchronized (writeLock) {
        optimisticUntil = Instant.EPOCH;
        cancel(settleTask);
        settleTask =
            scheduler.schedule(
                requestRefresh, Config.WRITE_SETTLE.toMillis(), TimeUnit.MILLISECONDS);
      }

      applyState(applied);
    } catch (RuntimeException e) {
      synchronized (writeLock) {
        optimisticUntil = Instant.EPOCH;
      }
      log.error("[{}] failed to apply the change: {}", displayName(), e.getMessage());
      // Snap HomeKit back to the thermostat's real state.
      applyState(current);
    }
  }

  // ------------------------------------------------------------------- ranges

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
  public double getMinTargetTemperature() {
    return Mapping.getSetpointLimits(state).minimum();
  }

  @Override
  public double getMaxTargetTemperature() {
    return Mapping.getSetpointLimits(state).maximum();
  }

  @Override
  public double getMinStepTargetTemperature() {
    return 0.1;
  }

  // ------------------------------------------------------------- subscriptions

  @Override
  public void subscribeCurrentTemperature(HomekitCharacteristicChangeCallback callback) {
    onCurrentTemperature.set(callback);
  }

  @Override
  public void subscribeTargetTemperature(HomekitCharacteristicChangeCallback callback) {
    onTargetTemperature.set(callback);
  }

  @Override
  public void subscribeCurrentState(HomekitCharacteristicChangeCallback callback) {
    onCurrentState.set(callback);
  }

  @Override
  public void subscribeTargetState(HomekitCharacteristicChangeCallback callback) {
    onTargetState.set(callback);
  }

  @Override
  public void subscribeTemperatureDisplayUnit(HomekitCharacteristicChangeCallback callback) {
    onDisplayUnit.set(callback);
  }

  @Override
  public void unsubscribeCurrentTemperature() {
    onCurrentTemperature.set(null);
  }

  @Override
  public void unsubscribeTargetTemperature() {
    onTargetTemperature.set(null);
  }

  @Override
  public void unsubscribeCurrentState() {
    onCurrentState.set(null);
  }

  @Override
  public void unsubscribeTargetState() {
    onTargetState.set(null);
  }

  @Override
  public void unsubscribeTemperatureDisplayUnit() {
    onDisplayUnit.set(null);
  }

  // ----------------------------------------------------------------- services

  /**
   * The thermostat service, plus the optional characteristics Home needs.
   *
   * <p>Auto mode in the Home app drives the heating and cooling thresholds rather than the
   * single target temperature, so without these the Auto dial has nothing to move.
   */
  @Override
  public Collection<Service> getServices() {
    SetpointLimits limits = Mapping.getSetpointLimits(state);
    ThermostatService service = new ThermostatService(this);

    service.addOptionalCharacteristic(
        new HeatingThresholdTemperatureCharacteristic(
            limits.minimum(),
            limits.maximum(),
            0.1,
            this::getHeatingThreshold,
            this::setHeatingThreshold,
            onHeatingThreshold::set,
            () -> onHeatingThreshold.set(null)));

    service.addOptionalCharacteristic(
        new CoolingThresholdTemperatureCharacteristic(
            limits.minimum(),
            limits.maximum(),
            0.1,
            this::getCoolingThreshold,
            this::setCoolingThreshold,
            onCoolingThreshold::set,
            () -> onCoolingThreshold.set(null)));

    if (showIndoorHumidity) {
      service.addOptionalCharacteristic(
          new CurrentRelativeHumidityCharacteristic(
              this::getIndoorHumidity, onHumidity::set, () -> onHumidity.set(null)));
    }

    return List.of(service);
  }

  // ------------------------------------------------------------------ helpers

  /**
   * The state HomeKit should see: the last poll, with any un-landed write layered on top
   * until the cloud has had time to catch up.
   */
  private DaikinDevice view() {
    synchronized (writeLock) {
      if (Instant.now().isBefore(optimisticUntil)) {
        return Mapping.applyDesired(state, pending);
      }
    }
    return state;
  }

  private double bounded(Double value) {
    SetpointLimits limits = Mapping.getSetpointLimits(view());
    double resolved = value == null ? limits.minimum() : value;
    return Mapping.round1(Mapping.clamp(resolved, limits.minimum(), limits.maximum()));
  }

  @SafeVarargs
  private static void notifyAll(AtomicReference<HomekitCharacteristicChangeCallback>... targets) {
    for (AtomicReference<HomekitCharacteristicChangeCallback> target : targets) {
      fire(target);
    }
  }

  private static void fire(AtomicReference<HomekitCharacteristicChangeCallback> target) {
    HomekitCharacteristicChangeCallback callback = target.get();
    if (callback != null) {
      callback.changed();
    }
  }

  private static void cancel(ScheduledFuture<?> task) {
    if (task != null) {
      task.cancel(false);
    }
  }
}
