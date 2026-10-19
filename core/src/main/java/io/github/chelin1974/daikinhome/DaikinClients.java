package io.github.chelin1974.daikinhome;

import io.github.chelin1974.daikinhome.daikin.DaikinCloud;
import io.github.chelin1974.daikinhome.daikin.DaikinOneClient;
import io.github.chelin1974.daikinhome.daikin.SkyportCloudClient;

/** Builds the Daikin client the settings ask for. Shared by the bridge and the probe tool. */
public final class DaikinClients {

  private DaikinClients() {}

  public static DaikinCloud create(BridgeSettings settings) {
    return switch (settings.backend()) {
      case SKYPORT ->
          SkyportCloudClient.builder()
              .email(settings.email())
              .password(settings.password())
              .baseUrl(settings.baseUrl())
              .timeout(settings.requestTimeout())
              .build();
      case OPEN_API ->
          DaikinOneClient.builder()
              .apiKey(settings.apiKey())
              .email(settings.email())
              .integratorToken(settings.integratorToken())
              .baseUrl(settings.baseUrl())
              .timeout(settings.requestTimeout())
              .build();
    };
  }

  /** Where to look when an account shows no thermostats, by backend. */
  public static String noDevicesHint(BridgeSettings settings) {
    return switch (settings.backend()) {
      case SKYPORT ->
          "Confirm you can see the thermostats in the SkyportHome app with this login. If the"
              + " account has not been moved to SkyportHome yet, the thermostats are still on"
              + " the old cloud: use \"backend\": \"openapi\" with an API key and integrator"
              + " token instead.";
      case OPEN_API ->
          "Confirm the integrator token was generated on the account that owns the thermostat.";
    };
  }
}
