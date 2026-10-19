package io.github.chelin1974.daikinhome.daikin;

import java.util.List;
import java.util.Map;

/**
 * What the bridge needs from a Daikin cloud backend.
 *
 * <p>Two implementations exist, because Daikin runs two clouds for the same thermostats:
 *
 * <ul>
 *   <li>{@link DaikinOneClient} — the documented Open API ({@code integrator-api}), using an
 *       API key and integrator token.
 *   <li>{@link SkyportCloudClient} — the cloud behind the SkyportHome app
 *       ({@code api.skyportcloud.com}), using the account email and password. Accounts moved
 *       to SkyportHome in 2026 can only log in here.
 * </ul>
 *
 * <p>Both speak the Open API's vocabulary to the rest of the bridge: {@link DaikinDevice} in
 * Celsius with Open API mode and equipment-status codes, and the same {@code msp} update map.
 * Translation is the backend's job, so the HomeKit side never knows which cloud it is on.
 *
 * <p>All methods block. Implementations must be thread-safe.
 */
public interface DaikinCloud {

  /** Every thermostat on the account. */
  List<DaikinDeviceSummary> getDevices();

  /** Full state for one thermostat. */
  DaikinDevice getDevice(String deviceId);

  /**
   * Sets mode and both setpoints.
   *
   * @param update {@code mode} (Open API mode code), {@code heatSetpoint} and
   *     {@code coolSetpoint} (degrees Celsius), as built by
   *     {@link io.github.chelin1974.daikinhome.Mapping.MspUpdate#toMap()}
   */
  void updateDevice(String deviceId, Map<String, Object> update);

  /** Drops any cached session; the next request logs in again. */
  void invalidateToken();

  /** The endpoint this backend talks to, for log lines. */
  String baseUrl();
}
