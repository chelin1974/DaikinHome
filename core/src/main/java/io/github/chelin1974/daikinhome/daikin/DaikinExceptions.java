package io.github.chelin1974.daikinhome.daikin;

/** Exceptions raised against the Daikin One Cloud Open API. */
public final class DaikinExceptions {

  private DaikinExceptions() {}

  /** Base class for every Daikin API failure. */
  public static class DaikinException extends RuntimeException {
    public DaikinException(String message) {
      super(message);
    }

    public DaikinException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A non-2xx HTTP response. */
  public static class DaikinApiException extends DaikinException {
    private final int status;
    private final String body;

    public DaikinApiException(String message, int status, String body) {
      super(message);
      this.status = status;
      this.body = body;
    }

    public int status() {
      return status;
    }

    public String body() {
      return body;
    }

    /** Retrying an identical request may succeed for these. */
    public boolean isRetryable() {
      return status == 429 || status == 408 || status >= 500;
    }
  }

  /**
   * The API rejected our credentials.
   *
   * <p>Never retried automatically: with a bad API key or a revoked integrator token,
   * retrying only burns quota and risks getting the key throttled.
   */
  public static class DaikinAuthException extends DaikinException {
    public DaikinAuthException(String message) {
      super(message);
    }
  }

  /** The request never completed (network failure, DNS, timeout). */
  public static class DaikinNetworkException extends DaikinException {
    public DaikinNetworkException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The response was not the shape we expected. */
  public static class DaikinResponseException extends DaikinException {
    public DaikinResponseException(String message) {
      super(message);
    }

    public DaikinResponseException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
