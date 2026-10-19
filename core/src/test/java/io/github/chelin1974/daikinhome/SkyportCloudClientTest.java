package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.daikinhome.daikin.DaikinDevice;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.EquipmentStatus;
import io.github.chelin1974.daikinhome.daikin.DaikinDeviceSummary;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import io.github.chelin1974.daikinhome.daikin.SkyportCloudClient;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkyportCloudClientTest {

  private FakeSkyportCloud cloud;

  @BeforeEach
  void startCloud() throws IOException {
    cloud = new FakeSkyportCloud();
  }

  @AfterEach
  void stopCloud() {
    cloud.close();
  }

  private SkyportCloudClient.Builder clientBuilder() {
    return SkyportCloudClient.builder()
        .email(FakeSkyportCloud.EMAIL)
        .password(FakeSkyportCloud.PASSWORD)
        .baseUrl(cloud.baseUrl())
        .minRequestInterval(Duration.ZERO);
  }

  private SkyportCloudClient client() {
    return clientBuilder().build();
  }

  @Test
  @DisplayName("logs in with email and password, then lists thermostats by _id")
  void listsDevices() {
    List<DaikinDeviceSummary> devices = client().getDevices();

    assertEquals(1, cloud.logins.get());
    assertEquals(1, devices.size());
    assertEquals(FakeSkyportCloud.DEVICE_ID, devices.get(0).id());
    assertEquals("DW6.93", devices.get(0).firmwareVersion());
    assertEquals("Home", devices.get(0).locationName());
  }

  @Test
  @DisplayName("an empty thermostat name falls back to the location name")
  void nameFallsBackToLocation() {
    assertEquals("Home", client().getDevices().get(0).name());
  }

  @Test
  @DisplayName("decodes the published DW6.93 sample into Celsius and Open API codes")
  void decodesLiveData() {
    SkyportCloudClient client = client();
    client.getDevices();
    DaikinDevice device = client.getDevice(FakeSkyportCloud.DEVICE_ID);

    assertEquals("Home", device.name);
    assertEquals(23.5, device.tempIndoor, 1e-9); // 127 VUT, ~74 °F
    assertEquals(21.0, device.coolSetpoint, 1e-9); // 122 VUT, ~70 °F
    assertEquals(12.5, device.heatSetpoint, 1e-9); // 105 VUT, ~54 °F
    assertEquals(DaikinMode.COOL, device.mode);
    assertEquals(EquipmentStatus.COOL, device.equipmentStatus);
    assertEquals(1.0, device.setpointDelta, 1e-9);
    assertNull(device.humIndoor, "0 % humidity means no sensor, not dry air");
    assertNull(device.tempOutdoor);
    assertEquals("DW6.93", device.firmwareVersion);
    assertTrue(device.getAdditional().containsKey("skyport"), "raw payload kept for --probe");
  }

  @Test
  @DisplayName("reads a thermostat it has not listed yet, labelled by its id")
  void readsUnlistedDevice() {
    DaikinDevice device = client().getDevice(FakeSkyportCloud.DEVICE_ID);
    assertEquals(FakeSkyportCloud.DEVICE_ID, device.name);
  }

  @Test
  @DisplayName("sends setpoints as VUT to /cmd/{id}/msp")
  void writesSetpoints() {
    client()
        .updateDevice(
            FakeSkyportCloud.DEVICE_ID,
            new Mapping.MspUpdate(DaikinMode.HEAT, 20.0, 24.0).toMap());

    assertEquals(1, cloud.commands.size());
    Map<String, Object> command = cloud.commands.get(0);
    assertEquals(DaikinMode.HEAT, ((Number) command.get("mode")).intValue());
    assertEquals(120, ((Number) command.get("heatSP")).intValue());
    assertEquals(128, ((Number) command.get("coolSP")).intValue());
  }

  @Test
  @DisplayName("reuses the session across calls")
  void reusesSession() {
    SkyportCloudClient client = client();
    client.getDevices();
    client.getDevice(FakeSkyportCloud.DEVICE_ID);
    client.getDevice(FakeSkyportCloud.DEVICE_ID);

    assertEquals(1, cloud.logins.get());
  }

  @Test
  @DisplayName("logs in again once the session expires")
  void logsInAgainOnExpiry() {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T12:00:00Z"));
    SkyportCloudClient client = clientBuilder().clock(now::get).build();

    client.getDevices();
    now.set(now.get().plus(Duration.ofMinutes(59)));
    client.getDevices();

    assertEquals(2, cloud.logins.get());
  }

  @Test
  @DisplayName("logs in again when the cloud ends the session early")
  void logsInAgainOn401() {
    SkyportCloudClient client = client();
    client.getDevices();

    cloud.validToken = "revoked";
    cloud.nextToken = "session-2";

    assertEquals(1, client.getDevices().size());
    assertEquals(2, cloud.logins.get());
  }

  @Test
  @DisplayName("a wrong password fails fast, once, without echoing the password")
  void wrongPassword() {
    SkyportCloudClient client = clientBuilder().password("wrong").build();

    DaikinAuthException error = assertThrows(DaikinAuthException.class, client::getDevices);

    assertEquals(1, cloud.logins.get(), "credentials are never retried");
    assertTrue(error.getMessage().contains("Incorrect e-mail or password."), error.getMessage());
    assertFalse(error.getMessage().contains("wrong"), "password must not appear in the message");
  }

  @Test
  @DisplayName("a login that returns no token explains two-factor rather than failing obscurely")
  void loginChallenge() {
    cloud.loginChallenges = true;

    DaikinAuthException error = assertThrows(DaikinAuthException.class, () -> client().getDevices());

    assertTrue(error.getMessage().contains("two-factor"), error.getMessage());
  }

  @Test
  @DisplayName("retries a server error on a live read")
  void retriesServerError() {
    cloud.failNextLiveReads.set(1);
    SkyportCloudClient client = clientBuilder().maxAttempts(2).build();

    DaikinDevice device = client.getDevice(FakeSkyportCloud.DEVICE_ID);

    assertEquals(23.5, device.tempIndoor, 1e-9);
    assertEquals(1, cloud.liveReads.get());
  }

  @Test
  @DisplayName("requires both email and password")
  void requiresCredentials() {
    assertThrows(
        DaikinAuthException.class,
        () -> SkyportCloudClient.builder().email(FakeSkyportCloud.EMAIL).build());
    assertThrows(
        DaikinAuthException.class,
        () -> SkyportCloudClient.builder().password(FakeSkyportCloud.PASSWORD).build());
  }

  @Test
  @DisplayName("sends the password exactly as given, untrimmed")
  void passwordNotTrimmed() {
    SkyportCloudClient client = clientBuilder().password(" " + FakeSkyportCloud.PASSWORD).build();

    assertThrows(DaikinAuthException.class, client::getDevices);
    assertTrue(cloud.loginBodies.get(0).contains("\" correct horse\""), cloud.loginBodies.get(0));
  }
}
