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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: bridges Daikin One thermostats into Apple Home.
 *
 * <pre>
 *   java -jar daikin-home.jar --config daikin.config.json
 *   java -jar daikin-home.jar --config daikin.config.json --probe
 * </pre>
 *
 * <p>Runs until interrupted. Something has to keep it alive — a systemd unit, a Docker
 * container, or launchd — and it has to be on the same network as the Home hub.
 */
public final class DaikinHomeBridge {

  private static final Logger log = LoggerFactory.getLogger(DaikinHomeBridge.class);

  /** HomeKit reserves accessory ID 1 for the bridge itself. */
  private static final int FIRST_ACCESSORY_ID = 2;

  private final Config config;
  private final DaikinOneClient client;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "daikin-home");
        thread.setDaemon(true);
        return thread;
      });

  private final Map<String, DaikinThermostat> thermostats = new LinkedHashMap<>();
  private final Map<String, OutdoorTemperatureSensor> outdoorSensors = new LinkedHashMap<>();

  private HomekitServer server;
  private HomekitRoot bridge;
  private volatile boolean shuttingDown;

  public DaikinHomeBridge(Config config) {
    this.config = config;
    this.client =
        DaikinOneClient.builder()
            .apiKey(config.apiKey())
            .email(config.email())
            .integratorToken(config.integratorToken())
            .baseUrl(config.baseUrl())
            .timeout(config.requestTimeout())
            .build();
  }

  public static void main(String[] args) {
    Map<String, String> options = parseArgs(args);

    if (options.containsKey("help") || options.containsKey("h")) {
      System.out.println(
          """
          usage: java -jar daikin-home.jar [options]

            --config <path>   configuration file (default: daikin.config.json)
            --probe           authenticate, list thermostats, dump raw JSON, and exit
            --help            show this message
          """);
      return;
    }

    Path configPath = Path.of(options.getOrDefault("config", "daikin.config.json"));

    try {
      Config config = Config.load(configPath);

      if (options.containsKey("probe")) {
        System.exit(ProbeTool.run(config));
      }

      DaikinHomeBridge bridge = new DaikinHomeBridge(config);
      Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop, "daikin-home-shutdown"));
      bridge.start();
    } catch (Config.ConfigException e) {
      log.error("configuration problem: {}", e.getMessage());
      System.exit(1);
    } catch (DaikinAuthException e) {
      log.error("{}", e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      log.error("failed to start: {}", e.getMessage(), e);
      System.exit(1);
    }
  }

  /** Discovers thermostats, publishes them to HomeKit, and starts polling. */
  public void start() throws Exception {
    List<DaikinDeviceSummary> devices = client.getDevices();

    if (devices.isEmpty()) {
      log.warn(
          "the Daikin account has no thermostats the Open API can see. Confirm the "
              + "integrator token was generated on the account that owns the thermostat.");
      return;
    }

    FileAuthInfo authInfo = FileAuthInfo.loadOrCreate(config.storagePath());
    server = new HomekitServer(config.port());
    bridge =
        server.createBridge(
            authInfo,
            config.bridgeName(),
            HomekitAccessoryCategories.BRIDGES,
            "Daikin",
            "Daikin One Bridge",
            "daikin-home-bridge",
            "1.0.0",
            "1.0.0");

    int nextId = FIRST_ACCESSORY_ID;

    for (DaikinDeviceSummary summary : devices) {
      // Read the thermostat before publishing it. HomeKit fixes a characteristic's
      // permitted range when the service is built, so the setpoint limits have to be
      // known first — otherwise the sliders are stuck on HAP's generic defaults.
      DaikinDevice initial = readInitialState(summary);

      DaikinThermostat thermostat =
          new DaikinThermostat(
              nextId++,
              summary,
              initial,
              client,
              scheduler,
              config.showIndoorHumidity(),
              () -> refreshDevice(summary.id()));

      thermostats.put(summary.id(), thermostat);
      bridge.addAccessory(thermostat);

      if (config.showOutdoorTemperatureSensor()) {
        OutdoorTemperatureSensor sensor =
            new OutdoorTemperatureSensor(
                nextId++,
                summary.name() + " Outdoor",
                summary.id() + "-outdoor",
                initial);
        outdoorSensors.put(summary.id(), sensor);
        bridge.addAccessory(sensor);
      }

      log.info(
          "added thermostat \"{}\"{}",
          summary.name(),
          summary.locationName() == null ? "" : " at " + summary.locationName());
    }

    bridge.start();

    log.info("");
    log.info("  +------------------------------------------+");
    log.info("  |  Add the bridge in the Home app using     |");
    log.info("  |  setup code:  {}                 |", authInfo.getPin());
    log.info("  +------------------------------------------+");
    log.info("");

    scheduler.scheduleWithFixedDelay(
        this::refreshAll,
        config.pollInterval().toSeconds(),
        config.pollInterval().toSeconds(),
        TimeUnit.SECONDS);

    log.info(
        "polling {} thermostat(s) every {}s",
        thermostats.size(),
        config.pollInterval().toSeconds());

    // Keep the JVM alive; the scheduler threads are daemons.
    Thread.currentThread().join();
  }

  public void stop() {
    shuttingDown = true;
    thermostats.values().forEach(DaikinThermostat::dispose);
    scheduler.shutdownNow();
    if (bridge != null) {
      bridge.stop();
    }
    if (server != null) {
      server.stop();
    }
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

  /** Re-reads a single thermostat, used after a write settles. */
  private void refreshDevice(String deviceId) {
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

  private void refreshAll() {
    // Sequential on purpose: the client paces requests anyway, and a burst of parallel
    // reads is exactly what Daikin rate limits.
    for (String deviceId : new ArrayList<>(thermostats.keySet())) {
      if (shuttingDown) {
        return;
      }
      refreshDevice(deviceId);
    }
  }

  /** Parses {@code --key value} and {@code --flag} into a map. */
  static Map<String, String> parseArgs(String[] args) {
    Map<String, String> options = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        continue;
      }
      String key = args[i].substring(2);
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
        options.put(key, args[++i]);
      } else {
        options.put(key, "true");
      }
    }
    return options;
  }
}
