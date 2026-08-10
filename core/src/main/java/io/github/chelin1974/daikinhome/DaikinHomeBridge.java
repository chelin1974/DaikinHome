package io.github.chelin1974.daikinhome;

import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import io.github.chelin1974.daikinhome.daikin.DaikinOneClient;
import io.github.chelin1974.daikinhome.homekit.DaikinThermostat;
import io.github.chelin1974.daikinhome.homekit.FileAuthInfo;
import io.github.chelin1974.daikinhome.homekit.OutdoorTemperatureSensor;
import io.github.hapjava.server.HomekitAccessoryCategories;
import io.github.hapjava.server.impl.HomekitRoot;
import io.github.hapjava.server.impl.HomekitServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers Daikin thermostats, publishes them to HomeKit, and keeps them refreshed.
 *
 * <p>Embeddable on purpose: {@link #start()} returns as soon as the bridge is up rather than
 * blocking, so a CLI can park on {@link #awaitTermination()} while a Spring Boot service
 * simply keeps it as a bean. The accessors below let a front end read and change thermostat
 * state through the same code path HomeKit uses.
 */
public final class DaikinHomeBridge implements ThermostatGateway, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DaikinHomeBridge.class);

  /** HomeKit reserves accessory ID 1 for the bridge itself. */
  private static final int FIRST_ACCESSORY_ID = 2;

  private final BridgeSettings settings;
  private final DaikinOneClient client;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "daikin-home");
            thread.setDaemon(true);
            return thread;
          });

  private final Map<String, DaikinDeviceSummary> summaries = new LinkedHashMap<>();
  private final Map<String, DaikinThermostat> thermostats = new LinkedHashMap<>();
  private final Map<String, OutdoorTemperatureSensor> outdoorSensors = new LinkedHashMap<>();
  private final CountDownLatch terminated = new CountDownLatch(1);

  private HomekitServer server;
  private HomekitRoot bridge;
  private FileAuthInfo authInfo;
  private volatile boolean running;
  private volatile boolean shuttingDown;

  public DaikinHomeBridge(BridgeSettings settings) {
    this(settings, defaultClient(settings));
  }

  /** Injects a client, so tests can point the bridge at a local server. */
  public DaikinHomeBridge(BridgeSettings settings, DaikinOneClient client) {
    this.settings = settings;
    this.client = client;
  }

  private static DaikinOneClient defaultClient(BridgeSettings settings) {
    return DaikinOneClient.builder()
        .apiKey(settings.apiKey())
        .email(settings.email())
        .integratorToken(settings.integratorToken())
        .baseUrl(settings.baseUrl())
        .timeout(settings.requestTimeout())
        .build();
  }

  /**
   * Discovers thermostats, publishes them to HomeKit, and starts polling.
   *
   * <p>Returns once the bridge is listening. Throws if the credentials are rejected — that
   * is not something to retry past.
   */
  public synchronized void start() throws IOException {
    if (running) {
      return;
    }

    List<DaikinDeviceSummary> devices = client.getDevices();

    if (devices.isEmpty()) {
      log.warn(
          "the Daikin account has no thermostats the Open API can see. Confirm the "
              + "integrator token was generated on the account that owns the thermostat.");
      return;
    }

    authInfo = FileAuthInfo.loadOrCreate(settings.storagePath());
    server = new HomekitServer(settings.port());
    bridge =
        server.createBridge(
            authInfo,
            settings.bridgeName(),
            HomekitAccessoryCategories.BRIDGES,
            "Daikin",
            "Daikin One Bridge",
            "daikin-home-bridge",
            "1.0.0",
            "1.0.0");

    int nextId = FIRST_ACCESSORY_ID;

    for (DaikinDeviceSummary summary : devices) {
      // Read the thermostat before publishing it. HomeKit fixes a characteristic's
      // permitted range when the service is built, so the setpoint limits have to be known
      // first, or the sliders are stuck on HAP's generic defaults.
      DaikinDevice initial = readInitialState(summary);

      DaikinThermostat thermostat =
          new DaikinThermostat(
              nextId++,
              summary,
              initial,
              client,
              scheduler,
              settings.showIndoorHumidity(),
              () -> refresh(summary.id()));

      summaries.put(summary.id(), summary);
      thermostats.put(summary.id(), thermostat);
      bridge.addAccessory(thermostat);

      if (settings.showOutdoorTemperatureSensor()) {
        OutdoorTemperatureSensor sensor =
            new OutdoorTemperatureSensor(
                nextId++, summary.name() + " Outdoor", summary.id() + "-outdoor", initial);
        outdoorSensors.put(summary.id(), sensor);
        bridge.addAccessory(sensor);
      }

      log.info(
          "added thermostat \"{}\"{}",
          summary.name(),
          summary.locationName() == null ? "" : " at " + summary.locationName());
    }

    bridge.start();
    running = true;

    if (!isPaired()) {
      log.info("");
      log.info("  +------------------------------------------+");
      log.info("  |  Add the bridge in the Home app using     |");
      log.info("  |  setup code:  {}                 |", authInfo.getPin());
      log.info("  +------------------------------------------+");
      log.info("");
    }

    scheduler.scheduleWithFixedDelay(
        this::refreshAll,
        settings.pollInterval().toSeconds(),
        settings.pollInterval().toSeconds(),
        TimeUnit.SECONDS);

    log.info(
        "polling {} thermostat(s) every {}s",
        thermostats.size(),
        settings.pollInterval().toSeconds());
  }

  /** Blocks until {@link #stop()} is called. */
  public void awaitTermination() throws InterruptedException {
    terminated.await();
  }

  public synchronized void stop() {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    running = false;

    thermostats.values().forEach(DaikinThermostat::dispose);
    scheduler.shutdownNow();
    if (bridge != null) {
      bridge.stop();
    }
    if (server != null) {
      server.stop();
    }
    terminated.countDown();
  }

  @Override
  public void close() {
    stop();
  }

  // ------------------------------------------------------------------ state

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public BridgeSettings settings() {
    return settings;
  }

  public DaikinOneClient client() {
    return client;
  }

  /** True once at least one controller has paired with the bridge. */
  @Override
  public boolean isPaired() {
    return authInfo != null && authInfo.hasUser();
  }

  /**
   * The HomeKit setup code, present only while the bridge is unpaired.
   *
   * <p>Withheld after pairing so it does not leak from an API response or a status page
   * once it no longer needs to be shown.
   */
  @Override
  public Optional<String> setupCode() {
    return authInfo == null || authInfo.hasUser()
        ? Optional.empty()
        : Optional.of(authInfo.getPin());
  }

  /** Every thermostat the bridge published, in discovery order. */
  @Override
  public List<DaikinDeviceSummary> devices() {
    return List.copyOf(summaries.values());
  }

  @Override
  public Optional<DaikinDeviceSummary> device(String deviceId) {
    return Optional.ofNullable(summaries.get(deviceId));
  }

  /** Last known state of one thermostat. Served from cache; never hits the network. */
  @Override
  public DaikinDevice state(String deviceId) {
    return thermostat(deviceId).state();
  }

  /**
   * Applies a change immediately and returns what was actually sent.
   *
   * <p>Goes through the same accessory the Home app drives, so HomeKit sees the new value
   * at once and the follow-up refresh is scheduled as usual.
   */
  @Override
  public Mapping.MspUpdate apply(String deviceId, Mapping.Desired desired, Mapping.Anchor anchor) {
    return thermostat(deviceId).applyNow(desired, anchor);
  }

  /** Re-reads one thermostat from the Daikin cloud and republishes it to HomeKit. */
  @Override
  public void refresh(String deviceId) {
    if (shuttingDown) {
      return;
    }

    DaikinThermostat thermostat = thermostats.get(deviceId);
    if (thermostat == null) {
      return;
    }

    try {
      DaikinDevice device = client.getDevice(deviceId);
      thermostat.applyState(device);
      OutdoorTemperatureSensor sensor = outdoorSensors.get(deviceId);
      if (sensor != null) {
        sensor.applyState(device);
      }
    } catch (DaikinAuthException e) {
      log.error("Daikin rejected the credentials: {}", e.getMessage());
    } catch (RuntimeException e) {
      log.warn("could not refresh \"{}\": {}", thermostat.displayName(), e.getMessage());
    }
  }

  public void refreshAll() {
    // Sequential on purpose: the client paces requests anyway, and a burst of parallel
    // reads is exactly what Daikin rate limits.
    for (String deviceId : new ArrayList<>(thermostats.keySet())) {
      if (shuttingDown) {
        return;
      }
      refresh(deviceId);
    }
  }

  private DaikinThermostat thermostat(String deviceId) {
    DaikinThermostat thermostat = thermostats.get(deviceId);
    if (thermostat == null) {
      throw new NoSuchElementException("no thermostat with id " + deviceId);
    }
    return thermostat;
  }

  /**
   * Reads a thermostat at startup.
   *
   * <p>A thermostat that cannot be read right now is still published, using defaults, so a
   * cloud hiccup at boot does not leave the accessory missing from Home entirely.
   */
  private DaikinDevice readInitialState(DaikinDeviceSummary summary) {
    try {
      return client.getDevice(summary.id());
    } catch (DaikinAuthException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn(
          "could not read \"{}\" at startup, publishing it with default ranges: {}",
          summary.name(),
          e.getMessage());
      DaikinDevice placeholder = new DaikinDevice();
      placeholder.id = summary.id();
      placeholder.name = summary.name();
      return placeholder;
    }
  }
}
