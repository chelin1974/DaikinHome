package io.github.chelin1974.daikinhome.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * The Daikin One bridge as a long-running service.
 *
 * <p>Same HomeKit bridge as the CLI, with a REST API and actuator health checks bolted on.
 * Useful when you want the thermostat reachable from your own automations, or want a health
 * endpoint something else can watch.
 *
 * <pre>
 *   java -jar daikin-home-web.jar
 *   DAIKIN_API_KEY=... DAIKIN_EMAIL=... DAIKIN_INTEGRATOR_TOKEN=... java -jar daikin-home-web.jar
 * </pre>
 */
@SpringBootApplication
@EnableConfigurationProperties(DaikinProperties.class)
public class DaikinWebApplication {

  public static void main(String[] args) {
    SpringApplication.run(DaikinWebApplication.class, args);
  }
}
