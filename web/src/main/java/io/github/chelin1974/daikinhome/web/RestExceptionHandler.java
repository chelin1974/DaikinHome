package io.github.chelin1974.daikinhome.web;

import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinApiException;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinAuthException;
import io.github.chelin1974.daikinhome.daikin.DaikinExceptions.DaikinNetworkException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns failures into honest status codes.
 *
 * <p>The distinction that matters here is whose fault it is. A rejected setpoint is the
 * caller's problem (4xx); Daikin's cloud being unreachable or unhappy is not (5xx), and
 * returning 400 for it would send clients chasing their own request.
 */
@RestControllerAdvice
public class RestExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

  @ExceptionHandler(NoSuchElementException.class)
  public ProblemDetail notFound(NoSuchElementException e) {
    return problem(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail badRequest(IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  /** Our credentials, not the caller's — so this is a 502, not a 401. */
  @ExceptionHandler(DaikinAuthException.class)
  public ProblemDetail daikinAuth(DaikinAuthException e) {
    log.error("Daikin rejected the bridge's credentials: {}", e.getMessage());
    return problem(
        HttpStatus.BAD_GATEWAY,
        "the Daikin cloud rejected this bridge's credentials; check the API key, account "
            + "email and integrator token");
  }

  @ExceptionHandler(DaikinNetworkException.class)
  public ProblemDetail daikinUnreachable(DaikinNetworkException e) {
    return problem(HttpStatus.GATEWAY_TIMEOUT, e.getMessage());
  }

  @ExceptionHandler(DaikinApiException.class)
  public ProblemDetail daikinRejected(DaikinApiException e) {
    // A 4xx from Daikin usually means the payload was wrong, which given the clamping in
    // Mapping means our request was wrong, not the caller's.
    HttpStatus status =
        e.status() == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
    return problem(status, e.getMessage());
  }

  private static ProblemDetail problem(HttpStatus status, String detail) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setDetail(detail);
    return problem;
  }
}
