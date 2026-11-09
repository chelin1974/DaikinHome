package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every file the bridge writes lands in the data directory.
 *
 * <p>This bridge writes only the pairing state today, so the stakes look lower than for the
 * sibling bridges - but losing that file un-pairs every controller, and the setting is the same
 * one in all three so that "where does it write?" has a single answer. A second written file
 * later needs no new knob, and this test covers it the day it appears.
 *
 * <p>Both routes are checked, because they used to disagree: the CLI builds settings from the
 * JSON file directly, while the service is handed properties and has no notion of "the config
 * file's directory".
 */
class DataDirectoryTest {

  @TempDir Path directory;

  private Path write(String json) throws IOException {
    Path file = directory.resolve("daikin.config.json");
    Files.writeString(file, json);
    return file;
  }

  private static BridgeSettings fromProperties(Map<String, Object> properties) {
    return BridgeSettings.builder()
        .email("a@b.com")
        .password("x")
        .dataDirectory((String) properties.get("daikin.data-directory"))
        .storagePath((String) properties.get("daikin.storage-path"))
        .build();
  }

  @Test
  @DisplayName("with no dataDirectory, the state file sits beside the config file")
  void defaultsToTheConfigDirectory() throws IOException {
    Path file = write("{\"email\":\"a@b.com\",\"password\":\"x\"}");

    assertEquals(
        directory.resolve(BridgeSettings.DEFAULT_STATE_FILE), JsonConfig.load(file).storagePath());
    assertEquals(
        directory.resolve(BridgeSettings.DEFAULT_STATE_FILE),
        fromProperties(JsonConfig.readAsProperties(file)).storagePath());
  }

  /** The container case: one setting puts the state on the mounted volume. */
  @Test
  @DisplayName("an absolute dataDirectory is taken as given, by both routes")
  void absoluteDataDirectory() throws IOException {
    Path file =
        write("{\"email\":\"a@b.com\",\"password\":\"x\",\"dataDirectory\":\"/data\"}");

    Path expected = Path.of("/data", BridgeSettings.DEFAULT_STATE_FILE);
    assertEquals(expected, JsonConfig.load(file).storagePath());
    assertEquals(expected, fromProperties(JsonConfig.readAsProperties(file)).storagePath());
  }

  @Test
  @DisplayName("a relative dataDirectory is resolved against the config file, by both routes")
  void relativeDataDirectory() throws IOException {
    Path file = write("{\"email\":\"a@b.com\",\"password\":\"x\",\"dataDirectory\":\"data\"}");

    Path expected = directory.resolve("data").resolve(BridgeSettings.DEFAULT_STATE_FILE);
    assertEquals(expected, JsonConfig.load(file).storagePath());
    assertEquals(expected, fromProperties(JsonConfig.readAsProperties(file)).storagePath());
  }

  /**
   * The case that used to escape the data directory. A relative storagePath was pre-resolved
   * against the config file by the properties reading, so it bypassed dataDirectory entirely.
   */
  @Test
  @DisplayName("a relative storagePath stays inside the data directory, by both routes")
  void relativeStoragePathStaysInsideTheDataDirectory() throws IOException {
    Path file =
        write(
            "{\"email\":\"a@b.com\",\"password\":\"x\",\"dataDirectory\":\"/data\","
                + "\"storagePath\":\"state.json\"}");

    assertEquals(Path.of("/data/state.json"), JsonConfig.load(file).storagePath());
    assertEquals(
        Path.of("/data/state.json"),
        fromProperties(JsonConfig.readAsProperties(file)).storagePath());
  }

  /** An absolute storagePath still wins, for a deliberately separate backed-up location. */
  @Test
  @DisplayName("an absolute storagePath wins over the data directory")
  void absoluteStoragePathWins() throws IOException {
    Path file =
        write(
            "{\"email\":\"a@b.com\",\"password\":\"x\",\"dataDirectory\":\"/data\","
                + "\"storagePath\":\"/elsewhere/state.json\"}");

    assertEquals(Path.of("/elsewhere/state.json"), JsonConfig.load(file).storagePath());
    assertEquals(
        Path.of("/elsewhere/state.json"),
        fromProperties(JsonConfig.readAsProperties(file)).storagePath());
  }
}
