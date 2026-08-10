package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.DaikinHomeBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the bridge as a bean.
 *
 * <p>Constructed but not started here — {@link BridgeLifecycle} starts it once the web
 * application is listening, so a Daikin outage cannot stop the service from coming up.
 */
@Configuration
public class BridgeConfiguration {

  @Bean
  public DaikinHomeBridge daikinHomeBridge(DaikinProperties properties) {
    return new DaikinHomeBridge(properties.toBridgeSettings());
  }
}
