package io.github.chelin1974.daikinhome.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.chelin1974.daikinhome.ThermostatGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The Thermostat Board page, the gate in front of it, and the passcode on the routes that
 * change a thermostat.
 *
 * <p>The passcode is configured here, unlike {@link ThermostatControllerTest}, which covers
 * the default of none - the two cases behave differently on purpose and both are worth
 * pinning down.
 */
@WebMvcTest(
    controllers = {
      DashboardController.class,
      BridgeController.class,
      ThermostatController.class
    })
@Import({LocalNetworkFilter.class, RestExceptionHandler.class, DashboardRoutesTest.Fixtures.class})
class DashboardRoutesTest {

  @TestConfiguration
  static class Fixtures {

    @Bean
    ThermostatGateway thermostatGateway() {
      return new FakeGateway();
    }

    @Bean
    BridgeStatus bridgeStatus() {
      return new BridgeStatus();
    }

    @Bean
    PasscodeGuard passcodeGuard() {
      DaikinProperties properties = new DaikinProperties();
      properties.setDashboardPasscode("letmein");
      return new PasscodeGuard(properties);
    }
  }

  @Autowired MockMvc mvc;

  // ----------------------------------------------------------------- the page

  @Test
  @DisplayName("the dashboard page is served from the classpath")
  void page() throws Exception {
    mvc.perform(from(get("/"), "192.168.7.20"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Thermostat Board")));
  }

  /** It is one file in the jar, so a missing resource would be a 500 at runtime, not a 404. */
  @Test
  @DisplayName("the page resource is actually packaged")
  void pageIsPackaged() {
    assertThat(DashboardController.class.getResourceAsStream("/dashboard/index.html")).isNotNull();
  }

  // -------------------------------------------------------------- the local gate

  /**
   * The whole reason this filter exists: these routes change a thermostat and spend Daikin
   * API calls, and the service binds the host's network because HomeKit needs it to.
   */
  @Test
  @DisplayName("a request from outside the home network is refused")
  void refusesTheInternet() throws Exception {
    mvc.perform(from(get("/api/thermostats"), "203.0.113.7"))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.error").value("This bridge only answers devices on the home network."));
  }

  @Test
  @DisplayName("a write from outside the home network is refused before the passcode is read")
  void refusesTheInternetOnWrites() throws Exception {
    mvc.perform(
            from(put("/api/thermostats/thermostat-1"), "203.0.113.7")
                .header("X-Passcode", "letmein")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"heat\"}"))
        .andExpect(status().isForbidden());
  }

  /**
   * Answered before that check, on purpose: a liveness probe that got a 403 would fail
   * forever and restart the container in a loop, which is worse than disclosing a status
   * word.
   */
  @Test
  @DisplayName("actuator answers even from outside the home network")
  void actuatorIsExemptFromTheLocalCheck() throws Exception {
    assertThat(LocalNetworkFilter.isLocal("203.0.113.7")).isFalse();
    assertThat(LocalNetworkFilter.isLocal("192.168.7.20")).isTrue();
    assertThat(LocalNetworkFilter.isLocal("10.1.2.3")).isTrue();
    assertThat(LocalNetworkFilter.isLocal("172.17.0.2")).isTrue();
    assertThat(LocalNetworkFilter.isLocal("127.0.0.1")).isTrue();
    assertThat(LocalNetworkFilter.isLocal("::1")).isTrue();
    assertThat(LocalNetworkFilter.isLocal("fd00::1")).isTrue();
    assertThat(LocalNetworkFilter.isLocal("2001:db8::1")).isFalse();
    assertThat(LocalNetworkFilter.isLocal(null)).isFalse();
    assertThat(LocalNetworkFilter.isLocal("")).isFalse();
    // CGNAT, which a managed cluster can sit on - not local, hence the actuator exemption.
    assertThat(LocalNetworkFilter.isLocal("100.64.0.1")).isFalse();
  }

  // ------------------------------------------------------------- what the page reads

  @Test
  @DisplayName("the bridge status tells the page a passcode is required")
  void bridgeStatusAdvertisesThePasscode() throws Exception {
    mvc.perform(from(get("/api/bridge"), "192.168.7.20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passcodeRequired").value(true));
  }

  // ------------------------------------------------------------------- the passcode

  @Test
  @DisplayName("changing a thermostat without the passcode is refused")
  void writeWithoutPasscode() throws Exception {
    mvc.perform(
            from(put("/api/thermostats/thermostat-1"), "192.168.7.21")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"heat\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Wrong passcode."));
  }

  @Test
  @DisplayName("refresh needs the passcode too, since it spends a Daikin API call")
  void refreshWithoutPasscode() throws Exception {
    mvc.perform(from(post("/api/thermostats/thermostat-1/refresh"), "192.168.7.22"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("reading never needs the passcode")
  void readsAreOpenOnTheLan() throws Exception {
    mvc.perform(from(get("/api/thermostats"), "192.168.7.23")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("the right passcode gets through")
  void writeWithTheRightPasscode() throws Exception {
    mvc.perform(
            from(put("/api/thermostats/thermostat-1"), "192.168.7.24")
                .header("X-Passcode", "letmein")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"heat\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("thermostat-1"));
  }

  @Test
  @DisplayName("an empty change is still a 400 once the passcode is accepted")
  void writeWithNothingToDo() throws Exception {
    mvc.perform(
            from(put("/api/thermostats/thermostat-1"), "192.168.7.25")
                .header("X-Passcode", "letmein")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  /** Five wrong tries lock the address out, so the passcode cannot be guessed at LAN speed. */
  @Test
  @DisplayName("five wrong passcodes lock the address out, beating even a correct one")
  void locksOut() throws Exception {
    for (int attempt = 0; attempt < PasscodeGuard.MAX_FAILURES; attempt++) {
      mvc.perform(
              from(put("/api/thermostats/thermostat-1"), "192.168.7.26")
                  .header("X-Passcode", "guess")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"mode\":\"heat\"}"))
          .andExpect(status().isUnauthorized());
    }

    mvc.perform(
            from(put("/api/thermostats/thermostat-1"), "192.168.7.26")
                .header("X-Passcode", "letmein")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"heat\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(
            jsonPath("$.error").value("Too many wrong passcodes. Try again in a few minutes."));
  }

  /** The lockout is per address, or one bad client could lock out the whole house. */
  @Test
  @DisplayName("a lockout does not spill onto another address")
  void lockoutIsPerAddress() throws Exception {
    for (int attempt = 0; attempt < PasscodeGuard.MAX_FAILURES; attempt++) {
      mvc.perform(
              from(put("/api/thermostats/thermostat-1"), "192.168.7.27")
                  .header("X-Passcode", "guess")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"mode\":\"heat\"}"))
          .andExpect(status().isUnauthorized());
    }

    mvc.perform(
            from(put("/api/thermostats/thermostat-1"), "192.168.7.28")
                .header("X-Passcode", "letmein")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"heat\"}"))
        .andExpect(status().isOk());
  }

  /** Every request needs an address the local-network filter accepts. */
  private static <T extends MockHttpServletRequestBuilder> T from(T builder, String address) {
    builder.with(
        request -> {
          request.setRemoteAddr(address);
          return request;
        });
    return builder;
  }
}
