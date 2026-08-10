import {
  DaikinApiError,
  DaikinAuthError,
  DaikinNetworkError,
  DaikinResponseError,
} from './errors';
import { RequestQueue, sleep } from './requestQueue';
import type {
  DaikinDevice,
  DaikinDeviceSummary,
  DaikinLocation,
  MspPayload,
  TokenResponse,
} from './types';

export const DEFAULT_BASE_URL = 'https://integrator-api.daikinskyport.com';

/** Minimal logger surface, so the client does not depend on Homebridge. */
export interface ClientLogger {
  debug(message: string, ...params: unknown[]): void;
  info(message: string, ...params: unknown[]): void;
  warn(message: string, ...params: unknown[]): void;
  error(message: string, ...params: unknown[]): void;
}

export interface DaikinOneClientOptions {
  /** Integrator API key, from the Daikin One app. */
  apiKey: string;
  /** Email address of the Daikin One account. */
  email: string;
  /** Integrator token, generated in the Daikin One app. */
  integratorToken: string;
  baseUrl?: string;
  log?: ClientLogger;
  /** Per-request timeout. */
  timeoutMs?: number;
  /** Floor between consecutive requests. */
  minRequestIntervalMs?: number;
  /** Attempts for retryable failures, including the first. */
  maxAttempts?: number;
  /** Injected for tests. */
  fetchImpl?: typeof fetch;
  /** Injected for tests. */
  now?: () => number;
}

const noopLogger: ClientLogger = {
  debug: () => undefined,
  info: () => undefined,
  warn: () => undefined,
  error: () => undefined,
};

/** Re-authenticate this long before the token actually expires. */
const TOKEN_EXPIRY_MARGIN_MS = 60_000;
/** Used when the token response omits `accessTokenExpiresIn`. */
const FALLBACK_TOKEN_TTL_MS = 10 * 60_000;

interface RequestOptions {
  method: 'GET' | 'PUT' | 'POST';
  path: string;
  body?: unknown;
  /** Skips the Authorization header — used by the token endpoint itself. */
  anonymous?: boolean;
}

/**
 * Client for the Daikin One Cloud Open API.
 *
 * Handles token acquisition and renewal, serialises requests, retries the
 * failures worth retrying, and normalises the device listing into a flat array.
 */
export class DaikinOneClient {
  private readonly baseUrl: string;
  private readonly log: ClientLogger;
  private readonly timeoutMs: number;
  private readonly maxAttempts: number;
  private readonly fetchImpl: typeof fetch;
  private readonly now: () => number;
  private readonly queue: RequestQueue;

  private accessToken: string | null = null;
  private accessTokenExpiresAt = 0;
  /** In-flight authentication, so concurrent callers share one token request. */
  private authInFlight: Promise<string> | null = null;

  constructor(private readonly options: DaikinOneClientOptions) {
    if (!options.apiKey || !options.email || !options.integratorToken) {
      throw new DaikinAuthError(
        'apiKey, email and integratorToken are all required',
        0,
        '',
      );
    }
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '');
    this.log = options.log ?? noopLogger;
    this.timeoutMs = options.timeoutMs ?? 20_000;
    this.maxAttempts = Math.max(1, options.maxAttempts ?? 3);
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch;
    this.now = options.now ?? Date.now;
    this.queue = new RequestQueue(options.minRequestIntervalMs ?? 1_000);

    if (typeof this.fetchImpl !== 'function') {
      throw new DaikinNetworkError(
        'global fetch is unavailable; Node 18 or newer is required',
      );
    }
  }

  /**
   * Lists every thermostat on the account.
   *
   * The API groups thermostats by location. Some accounts/firmwares return a
   * flat device array instead, so both shapes are accepted.
   */
  async getDevices(): Promise<DaikinDeviceSummary[]> {
    const payload = await this.request<unknown>({
      method: 'GET',
      path: '/v1/devices',
    });
    return normaliseDeviceListing(payload);
  }

  /** Full state for one thermostat. */
  async getDevice(deviceId: string): Promise<DaikinDevice> {
    const payload = await this.request<DaikinDevice>({
      method: 'GET',
      path: `/v1/devices/${encodeURIComponent(deviceId)}`,
    });
    if (!payload || typeof payload !== 'object') {
      throw new DaikinResponseError(
        `device ${deviceId} returned a non-object payload`,
      );
    }
    return payload;
  }

  /**
   * Updates mode and setpoints.
   *
   * Daikin's examples always send all three fields together and the thermostat
   * validates them as a set (heat must sit below cool by at least
   * `setpointDelta`), so callers pass a complete triple.
   */
  async updateDevice(deviceId: string, update: MspPayload): Promise<void> {
    await this.request<unknown>({
      method: 'PUT',
      path: `/v1/devices/${encodeURIComponent(deviceId)}/msp`,
      body: update,
    });
  }

  /** Drops the cached token; the next request re-authenticates. */
  invalidateToken(): void {
    this.accessToken = null;
    this.accessTokenExpiresAt = 0;
  }

  private async request<T>(options: RequestOptions): Promise<T> {
    let lastError: unknown;

    for (let attempt = 1; attempt <= this.maxAttempts; attempt++) {
      try {
        return await this.queue.run(() => this.dispatch<T>(options, attempt));
      } catch (error) {
        lastError = error;

        if (error instanceof DaikinAuthError) {
          throw error;
        }

        const retryable =
          (error instanceof DaikinApiError && error.isRetryable) ||
          error instanceof DaikinNetworkError;

        if (!retryable || attempt === this.maxAttempts) {
          throw error;
        }

        const backoff = 2 ** (attempt - 1) * 1_000;
        this.log.debug(
          `${options.method} ${options.path} failed (attempt ${attempt}/${this.maxAttempts}), retrying in ${backoff}ms: ${describe(error)}`,
        );
        await sleep(backoff);
      }
    }

    throw lastError;
  }

  private async dispatch<T>(
    options: RequestOptions,
    attempt: number,
  ): Promise<T> {
    const headers: Record<string, string> = {
      'x-api-key': this.options.apiKey,
      Accept: 'application/json',
    };

    if (!options.anonymous) {
      headers.Authorization = `Bearer ${await this.getAccessToken()}`;
    }
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }

    const response = await this.fetchJson(options, headers);

    // A token can be revoked between our expiry estimate and the actual
    // request. Re-authenticate once and let the retry loop take another pass.
    if (response.status === 401 && !options.anonymous && attempt === 1) {
      this.log.debug('access token rejected, re-authenticating');
      this.invalidateToken();
      throw new DaikinApiError(
        'access token rejected',
        // Reported as 503 so the retry loop treats it as retryable; a genuine
        // auth failure surfaces from the token endpoint as DaikinAuthError.
        503,
        response.text,
        options.method,
        options.path,
      );
    }

    if (response.status === 429) {
      const retryAfterMs = parseRetryAfter(response.retryAfter) ?? 60_000;
      this.queue.pauseFor(retryAfterMs);
      this.log.warn(
        `Daikin API rate limited this integration; pausing requests for ${Math.round(retryAfterMs / 1000)}s`,
      );
    }

    if (response.status === 401 || response.status === 403) {
      throw new DaikinAuthError(
        `Daikin API rejected the credentials for ${options.method} ${options.path} (HTTP ${response.status}). ` +
          'Check the API key, account email and integrator token in the Daikin One app.',
        response.status,
        response.text,
      );
    }

    if (response.status < 200 || response.status >= 300) {
      throw new DaikinApiError(
        `${options.method} ${options.path} failed with HTTP ${response.status}: ${truncate(response.text)}`,
        response.status,
        response.text,
        options.method,
        options.path,
      );
    }

    if (response.text.length === 0) {
      return undefined as T;
    }

    try {
      return JSON.parse(response.text) as T;
    } catch (error) {
      throw new DaikinResponseError(
        `${options.method} ${options.path} returned invalid JSON: ${truncate(response.text)}`,
        { cause: error },
      );
    }
  }

  private async fetchJson(
    options: RequestOptions,
    headers: Record<string, string>,
  ): Promise<{ status: number; text: string; retryAfter: string | null }> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    timer.unref?.();

    try {
      const response = await this.fetchImpl(`${this.baseUrl}${options.path}`, {
        method: options.method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: controller.signal,
      });

      return {
        status: response.status,
        text: await response.text(),
        retryAfter: response.headers?.get?.('retry-after') ?? null,
      };
    } catch (error) {
      const reason =
        (error as Error)?.name === 'AbortError'
          ? `timed out after ${this.timeoutMs}ms`
          : describe(error);
      throw new DaikinNetworkError(
        `${options.method} ${options.path} could not reach the Daikin API: ${reason}`,
        { cause: error },
      );
    } finally {
      clearTimeout(timer);
    }
  }

  private async getAccessToken(): Promise<string> {
    if (this.accessToken && this.now() < this.accessTokenExpiresAt) {
      return this.accessToken;
    }
    // Collapse concurrent callers onto a single token request.
    this.authInFlight ??= this.authenticate().finally(() => {
      this.authInFlight = null;
    });
    return this.authInFlight;
  }

  private async authenticate(): Promise<string> {
    this.log.debug('requesting a Daikin access token');

    const token = await this.dispatch<TokenResponse>(
      {
        method: 'POST',
        path: '/v1/token',
        anonymous: true,
        body: {
          email: this.options.email,
          integratorToken: this.options.integratorToken,
        },
      },
      // Attempt 2 so the 401-retry branch cannot fire for the token call.
      2,
    );

    if (!token?.accessToken) {
      throw new DaikinAuthError(
        'the Daikin token endpoint did not return an accessToken',
        200,
        JSON.stringify(token ?? null),
      );
    }

    const ttlMs =
      typeof token.accessTokenExpiresIn === 'number' &&
      token.accessTokenExpiresIn > 0
        ? token.accessTokenExpiresIn * 1_000
        : FALLBACK_TOKEN_TTL_MS;

    this.accessToken = token.accessToken;
    this.accessTokenExpiresAt =
      this.now() + Math.max(ttlMs - TOKEN_EXPIRY_MARGIN_MS, 30_000);

    this.log.debug(
      `access token acquired, valid for ${Math.round(ttlMs / 1000)}s`,
    );
    return token.accessToken;
  }
}

/**
 * Flattens GET /v1/devices into a plain device array.
 *
 * Accepts the documented location-grouped shape, a `{ locations: [...] }`
 * wrapper, and a bare device array.
 */
export function normaliseDeviceListing(payload: unknown): DaikinDeviceSummary[] {
  const entries = Array.isArray(payload)
    ? payload
    : isRecord(payload) && Array.isArray(payload.locations)
      ? payload.locations
      : isRecord(payload) && Array.isArray(payload.devices)
        ? [payload]
        : null;

  if (!entries) {
    throw new DaikinResponseError(
      `GET /v1/devices returned an unexpected shape: ${truncate(JSON.stringify(payload))}`,
    );
  }

  const devices: DaikinDeviceSummary[] = [];

  for (const entry of entries) {
    if (!isRecord(entry)) {
      continue;
    }

    // A location wrapping a list of thermostats.
    if (Array.isArray(entry.devices)) {
      const location = entry as DaikinLocation;
      const locationName =
        typeof location.locationName === 'string'
          ? location.locationName
          : typeof location.name === 'string'
            ? location.name
            : undefined;

      for (const device of location.devices ?? []) {
        const summary = toSummary(device, locationName);
        if (summary) {
          devices.push(summary);
        }
      }
      continue;
    }

    // A bare thermostat.
    const summary = toSummary(entry, undefined);
    if (summary) {
      devices.push(summary);
    }
  }

  return devices;
}

function toSummary(
  value: unknown,
  locationName: string | undefined,
): DaikinDeviceSummary | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = value.id ?? value.deviceId ?? value.thermostatId;
  if (typeof id !== 'string' || id.length === 0) {
    return null;
  }

  return {
    id,
    name: typeof value.name === 'string' && value.name.length > 0 ? value.name : id,
    model: typeof value.model === 'string' ? value.model : undefined,
    firmwareVersion:
      typeof value.firmwareVersion === 'string' ? value.firmwareVersion : undefined,
    locationName,
  };
}

/** Retry-After is either delta-seconds or an HTTP date. */
export function parseRetryAfter(
  value: string | null,
  now: number = Date.now(),
): number | null {
  if (!value) {
    return null;
  }
  const seconds = Number(value);
  if (Number.isFinite(seconds)) {
    return Math.max(0, seconds * 1_000);
  }
  const date = Date.parse(value);
  return Number.isNaN(date) ? null : Math.max(0, date - now);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function truncate(value: string, max = 300): string {
  return value.length > max ? `${value.slice(0, max)}…` : value;
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
