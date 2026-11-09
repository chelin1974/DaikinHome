package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.BridgeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Says what the service is actually configured with, once, at startup.
 *
 * <p>Written after a container answered a 403 from Daikin and the logs could not tell whether
 * it had even read the right configuration file. The two questions you want answered before
 * suspecting your credentials are "which file did it read?" and "which credentials did it find
 * in it?", and neither was visible: settings arrive as Spring properties from a file, the
 * environment and {@code application.yml} at once, so there was nothing to point at.
 *
 * <p>Deliberately prints no secret. The API key, integrator token and password are reported as
 * present or absent and by length, which is enough to catch the things that actually go wrong -
 * an empty value, a truncated paste, a stray quote counted into the length - without putting a
 * credential in a log file that gets pasted into a chat.
 *
 * <p>The SpanHome and CellGateHome bridges log the same thing; they read a config file directly,
 * so they could say it from the start.
 */
@Component
public class StartupSummary {

  private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

  private final Environment environment;
  private final DaikinProperties properties;

  public StartupSummary(Environment environment, DaikinProperties properties) {
    this.environment = environment;
    this.properties = properties;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void describeConfiguration() {
    String file = environment.getProperty(JsonConfigEnvironmentPostProcessor.CONFIG_FILE_USED);
    if (file == null || file.isBlank()) {
      log.info(
          "no configuration file was read; settings come from DAIKIN_* environment variables "
              + "and application.yml only");
    } else {
      log.info("configuration read from {}", file);
    }

    BridgeSettings settings;
    try {
      settings = properties.toBridgeSettings();
    } catch (RuntimeException e) {
      // Validation failures are reported by whoever asked for the settings; this is only a
      // summary and must never be the thing that stops the service coming up.
      log.info("settings are not usable yet: {}", e.getMessage());
      return;
    }

    log.info(
        "backend {}: email {}, {}",
        settings.backend(),
        describe(settings.email()),
        credentialsFor(settings));
    log.info("writing to {}", settings.storagePath().toAbsolutePath().getParent());
    log.info("  pairing state: {}", settings.storagePath().toAbsolutePath());
  }

  private static String credentialsFor(BridgeSettings settings) {
    return switch (settings.backend()) {
      case SKYPORT -> "password " + redact(settings.password());
      case OPEN_API ->
          "apiKey " + redact(settings.apiKey()) + ", integratorToken "
              + redact(settings.integratorToken());
    };
  }

  /** Present or absent, and how long - never the value. */
  private static String redact(String secret) {
    return secret == null || secret.isBlank() ? "not set" : "set (" + secret.length() + " chars)";
  }

  private static String describe(String value) {
    return value == null || value.isBlank() ? "not set" : value;
  }
}
