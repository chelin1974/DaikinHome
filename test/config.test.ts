import { describe, expect, it } from 'vitest';

import { ConfigError, pollIntervalWasClamped, resolveConfig } from '../src/config';
import { MIN_POLL_INTERVAL_SECONDS } from '../src/settings';

const credentials = {
  platform: 'DaikinOne',
  apiKey: 'key',
  email: 'user@example.com',
  integratorToken: 'token',
};

describe('resolveConfig', () => {
  it('names every missing credential in one message', () => {
    expect(() => resolveConfig({ platform: 'DaikinOne' })).toThrow(ConfigError);
    expect(() => resolveConfig({ platform: 'DaikinOne' })).toThrow(
      /apiKey, email, integratorToken/,
    );
  });

  it('treats blank strings as missing', () => {
    expect(() => resolveConfig({ ...credentials, apiKey: '   ' })).toThrow(ConfigError);
  });

  it('trims credentials, since app copy-paste picks up whitespace', () => {
    expect(resolveConfig({ ...credentials, apiKey: '  key  ' }).apiKey).toBe('key');
  });

  it('defaults the poll interval to Daikin’s documented floor', () => {
    expect(resolveConfig(credentials).pollIntervalSeconds).toBe(MIN_POLL_INTERVAL_SECONDS);
  });

  it('raises a too-fast poll interval back to the floor', () => {
    const config = { ...credentials, pollIntervalSeconds: 30 };
    expect(resolveConfig(config).pollIntervalSeconds).toBe(MIN_POLL_INTERVAL_SECONDS);
    expect(pollIntervalWasClamped(config)).toBe(true);
  });

  it('keeps a slower poll interval', () => {
    const config = { ...credentials, pollIntervalSeconds: 600 };
    expect(resolveConfig(config).pollIntervalSeconds).toBe(600);
    expect(pollIntervalWasClamped(config)).toBe(false);
  });

  it('accepts numeric settings that arrived as strings from the UI', () => {
    expect(
      resolveConfig({ ...credentials, pollIntervalSeconds: '900' }).pollIntervalSeconds,
    ).toBe(900);
  });

  it('defaults the optional accessories the way the schema advertises', () => {
    const resolved = resolveConfig(credentials);
    expect(resolved.showIndoorHumidity).toBe(true);
    expect(resolved.showOutdoorTemperatureSensor).toBe(true);
    expect(resolved.showOutdoorHumiditySensor).toBe(false);
    expect(resolved.experimentalScheduleSwitch).toBe(false);
    expect(resolved.experimentalFanSwitch).toBe(false);
  });

  it('lets the opt-out flags turn services off', () => {
    const resolved = resolveConfig({
      ...credentials,
      showIndoorHumidity: false,
      showOutdoorTemperatureSensor: false,
    });
    expect(resolved.showIndoorHumidity).toBe(false);
    expect(resolved.showOutdoorTemperatureSensor).toBe(false);
  });

  it('enforces a floor on the request timeout', () => {
    expect(resolveConfig({ ...credentials, requestTimeoutSeconds: 1 }).requestTimeoutMs).toBe(
      5_000,
    );
  });
});
