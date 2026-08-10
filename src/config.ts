import type { PlatformConfig } from 'homebridge';
import { MIN_POLL_INTERVAL_SECONDS } from './settings';

export interface DaikinOneConfig extends PlatformConfig {
  apiKey?: string;
  email?: string;
  integratorToken?: string;
  baseUrl?: string;
  pollIntervalSeconds?: number;
  requestTimeoutSeconds?: number;
  showIndoorHumidity?: boolean;
  showOutdoorTemperatureSensor?: boolean;
  showOutdoorHumiditySensor?: boolean;
  experimentalScheduleSwitch?: boolean;
  experimentalFanSwitch?: boolean;
  debug?: boolean;
}

/** Config after defaults and clamping, with credentials proven present. */
export interface ResolvedConfig {
  apiKey: string;
  email: string;
  integratorToken: string;
  baseUrl?: string;
  pollIntervalSeconds: number;
  requestTimeoutMs: number;
  showIndoorHumidity: boolean;
  showOutdoorTemperatureSensor: boolean;
  showOutdoorHumiditySensor: boolean;
  experimentalScheduleSwitch: boolean;
  experimentalFanSwitch: boolean;
  debug: boolean;
}

export class ConfigError extends Error {}

/**
 * Validates and normalises the platform config.
 *
 * Throws `ConfigError` with an actionable message when credentials are missing
 * — the platform reports that once and then stays idle rather than retrying a
 * request that cannot possibly succeed.
 */
export function resolveConfig(config: DaikinOneConfig): ResolvedConfig {
  const missing = (['apiKey', 'email', 'integratorToken'] as const).filter(
    (key) => !isNonEmptyString(config[key]),
  );

  if (missing.length > 0) {
    throw new ConfigError(
      `missing required setting${missing.length > 1 ? 's' : ''}: ${missing.join(', ')}. ` +
        'In the Daikin One app, request an integrator token under "Daikin One Care → ' +
        'home integration" and read the API key from the developer menu in the same ' +
        'section, then add them to the plugin configuration.',
    );
  }

  const requested = toNumber(config.pollIntervalSeconds, MIN_POLL_INTERVAL_SECONDS);
  const pollIntervalSeconds = Math.max(MIN_POLL_INTERVAL_SECONDS, requested);

  return {
    apiKey: (config.apiKey as string).trim(),
    email: (config.email as string).trim(),
    integratorToken: (config.integratorToken as string).trim(),
    baseUrl: isNonEmptyString(config.baseUrl) ? config.baseUrl.trim() : undefined,
    pollIntervalSeconds,
    requestTimeoutMs: Math.max(5, toNumber(config.requestTimeoutSeconds, 20)) * 1_000,
    showIndoorHumidity: config.showIndoorHumidity !== false,
    showOutdoorTemperatureSensor: config.showOutdoorTemperatureSensor !== false,
    showOutdoorHumiditySensor: config.showOutdoorHumiditySensor === true,
    experimentalScheduleSwitch: config.experimentalScheduleSwitch === true,
    experimentalFanSwitch: config.experimentalFanSwitch === true,
    debug: config.debug === true,
  };
}

/** True when the user asked for a faster poll than Daikin permits. */
export function pollIntervalWasClamped(config: DaikinOneConfig): boolean {
  const requested = toNumber(config.pollIntervalSeconds, MIN_POLL_INTERVAL_SECONDS);
  return requested < MIN_POLL_INTERVAL_SECONDS;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function toNumber(value: unknown, fallback: number): number {
  const parsed = typeof value === 'string' ? Number(value) : value;
  return typeof parsed === 'number' && Number.isFinite(parsed) ? parsed : fallback;
}
