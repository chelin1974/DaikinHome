package io.github.chelin1974.daikinhome.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers only devices on the home network.
 *
 * <p>This service changes things: {@code PUT /api/thermostats/{id}} sets the mode and the
 * setpoints, and {@code POST /api/thermostats/{id}/refresh} spends a call against Daikin's
 * rate-limited API. It binds the host's network because HomeKit needs that, so every one of
 * those routes is reachable from anywhere on the LAN - and would be reachable from the
 * internet if a router ever forwarded port 8080 by mistake. This refuses anything that is not
 * a private, link-local or loopback address, so a misconfigured router is not enough on its
 * own to let a stranger turn the heat on.
 *
 * <p>The SpanHome and CellGateHome bridges have had this since their dashboards existed. This
 * one did not, because its web module started as a REST API with no page in front of it - the
 * routes were no less exposed for it.
 *
 * <h2>Why the check lives here rather than in core</h2>
 *
 * SpanHome keeps the same predicate in {@code DashboardServer}, shared with the flavour that
 * serves the page from the bridge's own HTTP server, so the two cannot disagree about what
 * counts as local. DaikinHome has no such second consumer: its CLI jar serves no HTTP at all.
 * {@code NetworkAddresses} would be the tempting home for it in core, but that file is kept
 * byte-identical across all three bridges and its digest is pinned in each of their
 * {@code mirrored-sources.sha256} - so a method added for this one bridge would have to be
 * ported to the other two to keep their drift tests passing, which is a poor reason to touch
 * them.
 *
 * <h2>What is exempt</h2>
 *
 * {@code /actuator/**} is answered before this check. Those are a supervisor's endpoints, and
 * a probe that got a 403 would fail forever and restart the container in a loop - worse than
 * disclosing a status word. Kubernetes probes arrive from the node, whose address is usually
 * private and would pass anyway, but a cluster on CGNAT space (100.64/10) would not, and that
 * trap is not worth leaving in place.
 */
@Component
@Order(LocalNetworkFilter.ORDER)
public class LocalNetworkFilter extends OncePerRequestFilter {

  /** Ahead of the controllers, behind nothing that matters. */
  static final int ORDER = 0;

  private static final Logger log = LoggerFactory.getLogger(LocalNetworkFilter.class);

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/actuator");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    if (isLocal(request.getRemoteAddr())) {
      chain.doFilter(request, response);
      return;
    }

    log.warn(
        "refused {} {} from {}",
        request.getMethod(),
        request.getRequestURI(),
        request.getRemoteAddr());
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response
        .getWriter()
        .write("{\"error\":\"This bridge only answers devices on the home network.\"}");
  }

  /** Package-private so the predicate can be tested without a request. */
  static boolean isLocal(String remoteAddress) {
    if (remoteAddress == null || remoteAddress.isBlank()) {
      return false;
    }
    try {
      // getByName on a literal address does not resolve anything, so this cannot block.
      return isPrivate(InetAddress.getByName(remoteAddress));
    } catch (UnknownHostException e) {
      return false;
    }
  }

  /** Named apart from {@link #isLocal(String)} so {@code isLocal(null)} is not ambiguous. */
  private static boolean isPrivate(InetAddress address) {
    if (address == null) {
      return false;
    }
    if (address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()) {
      return true;
    }
    if (address instanceof Inet6Address) {
      byte first = address.getAddress()[0];
      return (first & 0xfe) == 0xfc; // fc00::/7, IPv6's private range
    }
    return false;
  }
}
