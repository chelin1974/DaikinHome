package io.github.chelin1974.daikinhome.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chelin1974.daikinhome.BridgeSettings;
import io.github.chelin1974.daikinhome.DaikinClients;
import io.github.chelin1974.daikinhome.daikin.DaikinCloud;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connectivity and discovery check for whichever Daikin cloud the settings select.
 *
 * <p>Run this before debugging anything in the bridge: it authenticates, lists the
 * thermostats on the account, and dumps the raw JSON each one returns.
 *
 * <p>That JSON is the ground truth for field names. Daikin's payload varies by model and
 * firmware and this project reads it defensively, so it is the fastest way to tell "the
 * bridge has a bug" apart from "your thermostat doesn't report that field".
 *
 * <pre>
 *   java -jar daikin-home.jar --config daikin.config.json --probe
 * </pre>
 */
public final class ProbeTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProbeTool() {}

  /** Returns a process exit code. */
  public static int run(BridgeSettings settings) {
    DaikinCloud client = DaikinClients.create(settings);

    System.err.println(
        "Authenticating as "
            + settings.email()
            + " against "
            + client.baseUrl()
            + " ("
            + settings.backend()
            + " backend)...");

    List<DaikinDeviceSummary> devices;
    try {
      devices = client.getDevices();
    } catch (DaikinAuthException e) {
      System.err.println();
      System.err.println("Authentication failed: " + e.getMessage());
      return 1;
    } catch (RuntimeException e) {
      System.err.println();
      System.err.println(e.getMessage());
      return 1;
    }

    if (devices.isEmpty()) {
      System.err.println();
      System.err.println("No thermostats returned. " + DaikinClients.noDevicesHint(settings));
      return 1;
    }

    System.err.println();
    System.err.println("Found " + devices.size() + " thermostat(s):");
    for (DaikinDeviceSummary device : devices) {
      System.err.println(
          "  - "
              + device.name()
              + " ("
              + device.id()
              + ")"
              + (device.model() == null ? "" : " - " + device.model())
              + (device.locationName() == null ? "" : " @ " + device.locationName()));
    }

    Map<String, Object> dump = new LinkedHashMap<>();
    for (DaikinDeviceSummary device : devices) {
      System.err.println();
      System.err.println("Reading " + device.name() + "...");
      try {
        dump.put(device.id(), client.getDevice(device.id()));
      } catch (RuntimeException e) {
        System.err.println("  failed: " + e.getMessage());
        return 1;
      }
    }

    System.err.println();
    System.err.println("Raw device state:");
    try {
      System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(dump));
    } catch (Exception e) {
      System.err.println("could not format the device state: " + e.getMessage());
      return 1;
    }

    return 0;
  }
}
