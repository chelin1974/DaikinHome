package io.github.chelin1974.daikinhome.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Thermostat Board page.
 *
 * <p>Until now this service answered {@code /} with a 404: it had a REST API and no page,
 * unlike the SpanHome and CellGateHome bridges, so the only way to read it was curl. The page
 * is one file with no build step and no dependencies, and it drives the API that was already
 * here - {@code /api/bridge} and {@code /api/thermostats} - rather than adding routes of its
 * own.
 *
 * <p>It lives in this module's resources rather than in core, because core has no resources
 * directory and no second flavour to share it with: the CLI jar serves no HTTP.
 */
@RestController
public class DashboardController {

  private static final String PAGE = "/dashboard/index.html";

  @GetMapping(path = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<byte[]> page() throws IOException {
    try (InputStream in = DashboardController.class.getResourceAsStream(PAGE)) {
      if (in == null) {
        return ResponseEntity.internalServerError()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                "{\"error\":\"The dashboard page is missing from the jar.\"}"
                    .getBytes(StandardCharsets.UTF_8));
      }
      return ResponseEntity.ok()
          .contentType(MediaType.TEXT_HTML)
          // The page reads live readings over fetch; a cached shell would keep serving an
          // old build of the markup after an upgrade.
          .cacheControl(CacheControl.noStore())
          .body(in.readAllBytes());
    }
  }
}
