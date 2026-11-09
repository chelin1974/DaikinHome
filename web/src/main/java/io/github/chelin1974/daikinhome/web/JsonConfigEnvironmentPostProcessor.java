package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.JsonConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Lets the service read the same {@code daikin.config.json} the CLI reads.
 *
 * <p>Without this, the two flavours of the same bridge took their credentials from different
 * places: the CLI from the config file, the service only from {@code DAIKIN_*} environment
 * variables. Running it under Docker therefore meant writing the same secrets out a second
 * time, in a second format, in a {@code .env} file beside a config file that already had
 * them.
 *
 * <h2>Where the file is looked for</h2>
 *
 * {@code daikin.config-file} (so {@code DAIKIN_CONFIG_FILE} as an environment variable, or
 * {@code --daikin.config-file=} on the command line), falling back to
 * {@code daikin.config.json} in the working directory. A missing file at the default location
 * is not an error — environment variables alone remain a perfectly good way to configure the
 * service, and that is what the Kubernetes manifests use. A file named explicitly and then
 * not found *is* an error, because it can only be a mistake.
 *
 * <h2>Precedence</h2>
 *
 * The file is inserted immediately after the environment-variable property source, so:
 *
 * <ol>
 *   <li>command-line arguments win over everything;
 *   <li>then {@code DAIKIN_*} environment variables — which is what the container relies on
 *       to force {@code DAIKIN_STORAGE_PATH} to the mounted volume, whatever the file says;
 *   <li>then this file;
 *   <li>then the defaults in {@code application.yml}.
 * </ol>
 *
 * That ordering is the point: the file supplies the settings, and the deployment can still
 * override any one of them without the file having to be rewritten per environment.
 */
public class JsonConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Where the file is looked for when {@code daikin.config-file} is not set. */
  static final String DEFAULT_CONFIG_FILE = "daikin.config.json";

  static final String CONFIG_FILE_PROPERTY = "daikin.config-file";

  static final String PROPERTY_SOURCE_NAME = "daikinConfigJson";

  /** Set to the file that was read, for {@code StartupSummary} to report. */
  static final String CONFIG_FILE_USED = "daikin.config-file-used";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    addPropertySource(environment);
  }

  private void addPropertySource(ConfigurableEnvironment environment) {
    // Re-entrant: Spring Boot may post-process the environment more than once (a bootstrap
    // context, a refresh), and the file should be read once.
    if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
      return;
    }

    String configured = environment.getProperty(CONFIG_FILE_PROPERTY);
    boolean explicit = configured != null && !configured.isBlank();
    Path file = Path.of(explicit ? configured.strip() : DEFAULT_CONFIG_FILE);

    if (!explicit && !Files.isReadable(file)) {
      // Nothing to read, and nothing was asked for: environment variables are enough.
      return;
    }

    Map<String, Object> properties = new java.util.LinkedHashMap<>(JsonConfig.readAsProperties(file));
    if (properties.isEmpty()) {
      return;
    }

    // Records which file was actually read, so startup can say so. An EnvironmentPostProcessor
    // runs before logging is configured, so it cannot log this itself - and without it there is
    // no way to tell from the logs whether the service picked up the file you meant, which is
    // the first thing you want to know when it rejects your credentials.
    properties.put(CONFIG_FILE_USED, file.toAbsolutePath().toString());

    // Just below the environment variables, so DAIKIN_* still wins, and above
    // application.yml, so the file beats the built-in defaults.
    MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, properties);
    if (environment
        .getPropertySources()
        .contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
      environment
          .getPropertySources()
          .addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
    } else {
      environment.getPropertySources().addFirst(source);
    }
  }

  @Override
  public int getOrder() {
    // After config data (application.yml) has been added, so addAfter() positions this
    // source relative to a complete list.
    return ConfigDataEnvironmentPostProcessor.ORDER + 10;
  }
}
