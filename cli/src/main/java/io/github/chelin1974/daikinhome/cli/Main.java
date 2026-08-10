package io.github.chelin1974.daikinhome.cli;

import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.BridgeSettings.SettingsException;
import io.github.chelin1974.daikinhome.DaikinHomeBridge;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the plain (non-Spring) bridge.
 *
 * <pre>
 *   java -jar daikin-home.jar --config daikin.config.json
 *   java -jar daikin-home.jar --config daikin.config.json --probe
 * </pre>
 *
 * <p>Runs until interrupted. Something has to keep it alive — a systemd unit, a Docker
 * container, or launchd — and it has to be on the same network as the Home hub.
 */
public final class Main {

  private static final Logger log = LoggerFactory.getLogger(Main.class);

  private Main() {}

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
      BridgeSettings settings = JsonConfig.load(configPath);

      if (options.containsKey("probe")) {
        System.exit(ProbeTool.run(settings));
      }

      if (BridgeSettings.pollIntervalWasClamped(
          JsonConfig.requestedPollIntervalSeconds(configPath))) {
        log.warn(
            "pollIntervalSeconds was raised to {}s: Daikin rate limits integrations that poll"
                + " a thermostat more than once every three minutes.",
            BridgeSettings.MIN_POLL_INTERVAL.toSeconds());
      }

      DaikinHomeBridge bridge = new DaikinHomeBridge(settings);
      Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop, "daikin-home-shutdown"));

      bridge.start();
      bridge.awaitTermination();
    } catch (SettingsException e) {
      log.error("configuration problem: {}", e.getMessage());
      System.exit(1);
    } catch (DaikinAuthException e) {
      log.error("{}", e.getMessage());
      System.exit(1);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.error("failed to start: {}", e.getMessage(), e);
      System.exit(1);
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
