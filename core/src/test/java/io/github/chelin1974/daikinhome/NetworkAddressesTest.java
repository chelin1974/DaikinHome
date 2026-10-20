package io.github.chelin1974.daikinhome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chelin1974.daikinhome.NetworkAddresses.Candidate;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NetworkAddressesTest {

  private static Candidate c(String name, String ip) throws Exception {
    return new Candidate(name, InetAddress.getByName(ip));
  }

  @Test
  @DisplayName("prefers en0 over VPN, AirDrop and bridge interfaces")
  void prefersPrimary() throws Exception {
    Candidate chosen =
        NetworkAddresses.choose(
                List.of(
                    c("utun3", "10.8.0.2"),
                    c("bridge100", "192.168.2.1"),
                    c("en5", "192.168.1.40"),
                    c("en0", "192.168.1.20")))
            .orElseThrow();
    assertEquals("en0", chosen.interfaceName());
  }

  @Test
  @DisplayName("ignores loopback and public addresses")
  void ignoresUnreachable() throws Exception {
    assertTrue(
        NetworkAddresses.choose(List.of(c("lo0", "127.0.0.1"), c("en0", "8.8.8.8"))).isEmpty());
  }

  @Test
  @DisplayName("an explicit bindAddress wins")
  void explicitWins() throws Exception {
    assertEquals("192.168.1.99", NetworkAddresses.resolve("192.168.1.99").getHostAddress());
  }

  @Test
  @DisplayName("bindAddress flows through the settings")
  void settings() {
    BridgeSettings settings =
        BridgeSettings.builder()
            .apiKey("k").email("e@example.com").integratorToken("t")
            .bindAddress(" 192.168.1.20 ")
            .build();
    assertEquals("192.168.1.20", settings.bindAddress());
  }
}
