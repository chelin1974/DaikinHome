/** Base class for every error this plugin raises against the Daikin API. */
export class DaikinError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = new.target.name;
  }
}

/** A non-2xx HTTP response from the Daikin API. */
export class DaikinApiError extends DaikinError {
  constructor(
    message: string,
    readonly status: number,
    readonly body: string,
    readonly method: string,
    readonly path: string,
  ) {
    super(message);
  }

  /** Retrying an identical request may succeed for these. */
  get isRetryable(): boolean {
    return this.status === 429 || this.status === 408 || this.status >= 500;
  }
}

/**
 * The API rejected our credentials.
 *
 * Never retried automatically: with a bad API key or a revoked integrator
 * token, retrying just burns quota and can get the key throttled.
 */
export class DaikinAuthError extends DaikinError {
  constructor(
    message: string,
    readonly status: number,
    readonly body: string,
  ) {
    super(message);
  }
}

/** The request did not complete (network failure, DNS, timeout). */
export class DaikinNetworkError extends DaikinError {}

/** The response was not the shape we expected. */
export class DaikinResponseError extends DaikinError {}
