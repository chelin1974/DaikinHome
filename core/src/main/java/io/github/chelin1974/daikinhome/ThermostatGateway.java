package io.github.chelin1974.daikinhome;

import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * What a front end needs from the bridge: read thermostats, change them, check on it.
 *
 * <p>{@link DaikinHomeBridge} is the real implementation. The interface exists so a REST
 * layer can be built and tested without starting a HomeKit server and advertising itself
 * over mDNS.
 */
public interface ThermostatGateway {

  /** True once the bridge is up and publishing accessories. */
  boolean isRunning();

  /** True once at least one controller has paired with the bridge. */
  boolean isPaired();

  /** The HomeKit setup code, present only while the bridge is unpaired. */
  Optional<String> setupCode();

  BridgeSettings settings();

  /** Every thermostat the bridge published, in discovery order. */
  List<DaikinDeviceSummary> devices();

  Optional<DaikinDeviceSummary> device(String deviceId);

  /**
   * Last known state of one thermostat, served from cache.
   *
   * @throws NoSuchElementException if no such thermostat was discovered
   */
  DaikinDevice state(String deviceId);

  /**
   * Applies a change immediately and returns what was actually sent, after clamping.
   *
   * @throws NoSuchElementException if no such thermostat was discovered
   */
  Mapping.MspUpdate apply(String deviceId, Mapping.Desired desired, Mapping.Anchor anchor);

  /** Re-reads one thermostat from the Daikin cloud. */
  void refresh(String deviceId);
}
