package io.github.chelin1974.daikinhome.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.chelin1974.daikinhome.ThermostatGateway;
import io.github.chelin1974.daikinhome.daikin.DaikinDevice.DaikinMode;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinApiException;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinNetworkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ThermostatController.class, BridgeController.class})
@Import({RestExceptionHandler.class, ThermostatControllerTest.Fixtures.class})
class ThermostatControllerTest {

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
  }

  @Autowired MockMvc mvc;
  @Autowired ThermostatGateway gateway;

  private FakeGateway fake;

  @BeforeEach
  void setUp() {
    fake = (FakeGateway) gateway;
    fake.reset();
  }

  @Test
  @DisplayName("lists thermostats with modes as readable strings")
  void listsThermostats() throws Exception {
    mvc.perform(get("/api/thermostats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].id").value("thermostat-1"))
        .andExpect(jsonPath("$[0].name").value("Hallway"))
        .andExpect(jsonPath("$[0].location").value("Home"))
        .andExpect(jsonPath("$[0].mode").value("auto"))
        .andExpect(jsonPath("$[0].currentState").value("heat"))
        .andExpect(jsonPath("$[0].targetState").value("auto"))
        .andExpect(jsonPath("$[0].indoorTemperature").value(19.5))
        .andExpect(jsonPath("$[0].outdoorTemperature").value(7.2))
        // Auto mode: the reported target is the midpoint of the two setpoints.
        .andExpect(jsonPath("$[0].targetTemperature").value(22.0));
  }

  @Test
  @DisplayName("omits readings the thermostat does not report")
  void omitsAbsentReadings() throws Exception {
    fake.state("thermostat-1").humOutdoor = null;

    mvc.perform(get("/api/thermostats/thermostat-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outdoorHumidity").doesNotExist())
        .andExpect(jsonPath("$.indoorHumidity").value(41.0));
  }

  @Test
  @DisplayName("404s for an unknown thermostat")
  void unknownThermostat() throws Exception {
    mvc.perform(get("/api/thermostats/nope"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("no thermostat with id nope"));
  }

  @Test
  @DisplayName("changes the mode")
  void changesMode() throws Exception {
    mvc.perform(
            put("/api/thermostats/thermostat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"heat\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("heat"));

    assertThat(fake.writes).hasSize(1);
    assertThat(fake.writes.get(0).mode()).isEqualTo(DaikinMode.HEAT);
  }

  @Test
  @DisplayName("clamps setpoints and reports what was actually applied")
  void clampsSetpoints() throws Exception {
    // Ask for a heat setpoint that collides with the cool setpoint; the cool one must move.
    mvc.perform(
            put("/api/thermostats/thermostat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"heatSetpoint\":25.0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.heatSetpoint").value(25.0))
        .andExpect(jsonPath("$.coolSetpoint").value(27.0));
  }

  @Test
  @DisplayName("clamps a setpoint outside the thermostat's range")
  void clampsOutOfRange() throws Exception {
    mvc.perform(
            put("/api/thermostats/thermostat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"coolSetpoint\":99.0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coolSetpoint").value(32.2));
  }

  @Test
  @DisplayName("rejects an unknown mode with the valid options")
  void rejectsUnknownMode() throws Exception {
    mvc.perform(
            put("/api/thermostats/thermostat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"warmish\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail")
                .value(org.hamcrest.Matchers.containsString("off, heat, cool, auto")));
  }

  @Test
  @DisplayName("rejects an empty update")
  void rejectsEmptyUpdate() throws Exception {
    mvc.perform(
            put("/api/thermostats/thermostat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    assertThat(fake.writes).isEmpty();
  }

  @Test
  @DisplayName("forces a refresh")
  void forcesRefresh() throws Exception {
    mvc.perform(post("/api/thermostats/thermostat-1/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("thermostat-1"));

    assertThat(fake.refreshes).containsExactly("thermostat-1");
  }

  @Test
  @DisplayName("does not spend an API call refreshing an unknown thermostat")
  void refreshUnknown() throws Exception {
    mvc.perform(post("/api/thermostats/nope/refresh")).andExpect(status().isNotFound());
    assertThat(fake.refreshes).isEmpty();
  }

  @Test
  @DisplayName("reports our own bad credentials as 502, not 401")
  void mapsAuthFailure() throws Exception {
    fake.failNextWrite = () -> new DaikinAuthException("Daikin API rejected the credentials");

    mvc.perform(
            put("/api/thermostats/thermostat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"cool\"}"))
        .andExpect(status().isBadGateway());
  }

  @Test
  @DisplayName("reports an unreachable cloud as 504")
  void mapsNetworkFailure() throws Exception {
    fake.failNextWrite =
        () -> new DaikinNetworkException("could not reach the Daikin API", new RuntimeException());

    mvc.perform(
            put("/api/thermostats/thermostat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"cool\"}"))
        .andExpect(status().isGatewayTimeout());
  }

  @Test
  @DisplayName("passes a Daikin rate limit through as 429")
  void mapsRateLimit() throws Exception {
    fake.failNextWrite = () -> new DaikinApiException("rate limited", 429, "slow down");

    mvc.perform(
            put("/api/thermostats/thermostat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"cool\"}"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("shows the setup code until the bridge is paired, then hides it")
  void bridgeStatus() throws Exception {
    mvc.perform(get("/api/bridge"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.running").value(true))
        .andExpect(jsonPath("$.paired").value(false))
        .andExpect(jsonPath("$.setupCode").value("379-96-555"))
        .andExpect(jsonPath("$.thermostatCount").value(1));

    fake.paired = true;

    mvc.perform(get("/api/bridge"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paired").value(true))
        .andExpect(jsonPath("$.setupCode").doesNotExist());
  }
}
