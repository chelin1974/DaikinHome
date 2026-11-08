package io.github.chelin1974.daikinhome.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.chelin1974.daikinhome.BridgeSettings.SettingsException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The reader that lets the service take its settings from {@code daikin.config.json}.
 *
 * <p>What matters here is precedence. The file has to beat the defaults in
 * {@code application.yml}, or setting anything in it would have no effect; and environment
 * variables have to beat the file, or the container could not force
 * {@code DAIKIN_STORAGE_PATH} to the mounted volume.
 */
class JsonConfigEnvironmentPostProcessorTest {

  @TempDir Path directory;

  private final JsonConfigEnvironmentPostProcessor processor =
      new JsonConfigEnvironmentPostProcessor();

  /** A stand-in for application.yml: the lowest-precedence source, as Spring places it. */
  private StandardEnvironment environmentWithDefaults(Map<String, Object> systemEnvironment) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addLast(
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systemEnvironment));
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "applicationConfig",
                Map.of("daikin.email", "", "daikin.bridge-name", "Daikin One")));
    return environment;
  }

  private Path write(String json) throws IOException {
    Path file = directory.resolve("daikin.config.json");
    Files.writeString(file, json);
    return file;
  }

  @Test
  void readsTheFileNamedByDaikinConfigFile() throws IOException {
    Path file = write("{\"email\": \"you@example.com\", \"apiKey\": \"key-1\"}");
    StandardEnvironment environment =
        environmentWithDefaults(Map.of("DAIKIN_CONFIG_FILE", file.toString()));

    processor.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("daikin.email")).isEqualTo("you@example.com");
    assertThat(environment.getProperty("daikin.api-key")).isEqualTo("key-1");
  }

  /** Without this, nothing in the file would have any effect. */
  @Test
  void theFileBeatsTheDefaultsInApplicationYml() throws IOException {
    Path file = write("{\"email\": \"you@example.com\", \"bridgeName\": \"Upstairs\"}");
    StandardEnvironment environment =
        environmentWithDefaults(Map.of("DAIKIN_CONFIG_FILE", file.toString()));

    processor.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("daikin.bridge-name")).isEqualTo("Upstairs");
    assertThat(environment.getProperty("daikin.email")).isEqualTo("you@example.com");
  }

  /** The container sets DAIKIN_STORAGE_PATH to the mounted volume; it has to win. */
  @Test
  void environmentVariablesBeatTheFile() throws IOException {
    Path file =
        write(
            """
            {
              "email": "file@example.com",
              "storagePath": "/home/you/daikin-home.state.json"
            }
            """);
    StandardEnvironment environment =
        environmentWithDefaults(
            Map.of(
                "DAIKIN_CONFIG_FILE", file.toString(),
                "DAIKIN_STORAGE_PATH", "/data/daikin-home.state.json",
                "DAIKIN_EMAIL", "env@example.com"));

    processor.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("daikin.storage-path"))
        .isEqualTo("/data/daikin-home.state.json");
    assertThat(environment.getProperty("daikin.email")).isEqualTo("env@example.com");
  }

  /**
   * Environment variables on their own stay a complete way to configure the service — which
   * is what the Kubernetes manifests rely on.
   */
  @Test
  void doesNothingWhenThereIsNoFileAndNoneWasAskedFor() {
    StandardEnvironment environment = environmentWithDefaults(Map.of());

    processor.postProcessEnvironment(environment, null);

    assertThat(environment.getPropertySources().contains("daikinConfigJson")).isFalse();
  }

  /** A file named explicitly and then not found can only be a mistake. */
  @Test
  void failsWhenAnExplicitlyNamedFileIsMissing() {
    StandardEnvironment environment =
        environmentWithDefaults(
            Map.of("DAIKIN_CONFIG_FILE", directory.resolve("absent.json").toString()));

    assertThatThrownBy(() -> processor.postProcessEnvironment(environment, null))
        .isInstanceOf(SettingsException.class)
        .hasMessageContaining("cannot read the configuration file");
  }

  @Test
  void failsOnMalformedJson() throws IOException {
    Path file = write("{\"email\": ");
    StandardEnvironment environment =
        environmentWithDefaults(Map.of("DAIKIN_CONFIG_FILE", file.toString()));

    assertThatThrownBy(() -> processor.postProcessEnvironment(environment, null))
        .isInstanceOf(SettingsException.class)
        .hasMessageContaining("could not parse");
  }

  /** Spring may post-process the same environment more than once. */
  @Test
  void isSafeToRunTwice() throws IOException {
    Path file = write("{\"email\": \"you@example.com\"}");
    StandardEnvironment environment =
        environmentWithDefaults(Map.of("DAIKIN_CONFIG_FILE", file.toString()));

    processor.postProcessEnvironment(environment, null);
    processor.postProcessEnvironment(environment, null);

    assertThat(environment.getProperty("daikin.email")).isEqualTo("you@example.com");
  }
}
