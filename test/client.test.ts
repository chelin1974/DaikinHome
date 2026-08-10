import { describe, expect, it } from 'vitest';

import {
  DaikinOneClient,
  normaliseDeviceListing,
  parseRetryAfter,
} from '../src/api/client';
import {
  DaikinApiError,
  DaikinAuthError,
  DaikinNetworkError,
  DaikinResponseError,
} from '../src/api/errors';

interface StubCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

function stubFetch(responses: Array<{ status: number; body: unknown; headers?: Record<string, string> }>) {
  const calls: StubCall[] = [];
  let index = 0;

  const impl = (async (url: string, init: RequestInit) => {
    calls.push({
      url,
      method: init.method as string,
      headers: init.headers as Record<string, string>,
      body: init.body ? JSON.parse(init.body as string) : undefined,
    });

    const response = responses[Math.min(index, responses.length - 1)];
    index++;

    return {
      status: response!.status,
      text: async () =>
        typeof response!.body === 'string'
          ? response!.body
          : JSON.stringify(response!.body),
      headers: {
        get: (name: string) => response!.headers?.[name.toLowerCase()] ?? null,
      },
    };
  }) as unknown as typeof fetch;

  return { impl, calls };
}

const token = { status: 200, body: { accessToken: 'tok-1', accessTokenExpiresIn: 3600 } };

function makeClient(
  fetchImpl: typeof fetch,
  overrides: Partial<ConstructorParameters<typeof DaikinOneClient>[0]> = {},
) {
  return new DaikinOneClient({
    apiKey: 'key',
    email: 'user@example.com',
    integratorToken: 'integrator',
    fetchImpl,
    minRequestIntervalMs: 0,
    ...overrides,
  });
}

describe('authentication', () => {
  it('exchanges the integrator token before the first API call', async () => {
    const { impl, calls } = stubFetch([token, { status: 200, body: [] }]);
    await makeClient(impl).getDevices();

    expect(calls[0]!.url).toBe('https://integrator-api.daikinskyport.com/v1/token');
    expect(calls[0]!.method).toBe('POST');
    expect(calls[0]!.body).toEqual({
      email: 'user@example.com',
      integratorToken: 'integrator',
    });
    expect(calls[0]!.headers['x-api-key']).toBe('key');
    expect(calls[0]!.headers.Authorization).toBeUndefined();

    expect(calls[1]!.headers.Authorization).toBe('Bearer tok-1');
    expect(calls[1]!.headers['x-api-key']).toBe('key');
  });

  it('reuses a valid token across calls', async () => {
    const { impl, calls } = stubFetch([token, { status: 200, body: [] }]);
    const client = makeClient(impl);

    await client.getDevices();
    await client.getDevices();

    expect(calls.filter((call) => call.url.endsWith('/v1/token'))).toHaveLength(1);
  });

  it('re-authenticates once the token expires', async () => {
    let now = 0;
    const { impl, calls } = stubFetch([
      token,
      { status: 200, body: [] },
      { status: 200, body: { accessToken: 'tok-2', accessTokenExpiresIn: 3600 } },
      { status: 200, body: [] },
    ]);
    const client = makeClient(impl, { now: () => now });

    await client.getDevices();
    now = 3_600_000;
    await client.getDevices();

    expect(calls.filter((call) => call.url.endsWith('/v1/token'))).toHaveLength(2);
    expect(calls.at(-1)!.headers.Authorization).toBe('Bearer tok-2');
  });

  it('collapses concurrent callers onto a single token request', async () => {
    const { impl, calls } = stubFetch([token, { status: 200, body: [] }]);
    const client = makeClient(impl);

    await Promise.all([client.getDevices(), client.getDevices(), client.getDevices()]);

    expect(calls.filter((call) => call.url.endsWith('/v1/token'))).toHaveLength(1);
  });

  it('re-authenticates and retries when a token is rejected mid-flight', async () => {
    const { impl, calls } = stubFetch([
      token,
      { status: 401, body: { message: 'expired' } },
      { status: 200, body: { accessToken: 'tok-2', accessTokenExpiresIn: 3600 } },
      { status: 200, body: [{ id: 'abc', name: 'Hall' }] },
    ]);

    const devices = await makeClient(impl).getDevices();

    expect(devices).toEqual([
      { id: 'abc', name: 'Hall', model: undefined, firmwareVersion: undefined, locationName: undefined },
    ]);
    expect(calls.at(-1)!.headers.Authorization).toBe('Bearer tok-2');
  });

  it('surfaces bad credentials as an auth error without retrying', async () => {
    const { impl, calls } = stubFetch([{ status: 403, body: { message: 'forbidden' } }]);

    await expect(makeClient(impl).getDevices()).rejects.toBeInstanceOf(DaikinAuthError);
    expect(calls).toHaveLength(1);
  });

  it('rejects a token response with no accessToken', async () => {
    const { impl } = stubFetch([{ status: 200, body: { tokenType: 'Bearer' } }]);
    await expect(makeClient(impl).getDevices()).rejects.toBeInstanceOf(DaikinAuthError);
  });

  it('refuses to construct without credentials', () => {
    expect(() =>
      new DaikinOneClient({ apiKey: '', email: '', integratorToken: '' }),
    ).toThrow(DaikinAuthError);
  });
});

describe('requests', () => {
  it('PUTs the full msp payload', async () => {
    const { impl, calls } = stubFetch([token, { status: 200, body: '' }]);

    await makeClient(impl).updateDevice('dev-1', {
      mode: 1,
      heatSetpoint: 20,
      coolSetpoint: 24,
    });

    expect(calls[1]!.url).toBe(
      'https://integrator-api.daikinskyport.com/v1/devices/dev-1/msp',
    );
    expect(calls[1]!.method).toBe('PUT');
    expect(calls[1]!.body).toEqual({ mode: 1, heatSetpoint: 20, coolSetpoint: 24 });
    expect(calls[1]!.headers['Content-Type']).toBe('application/json');
  });

  it('URL-encodes device ids', async () => {
    const { impl, calls } = stubFetch([token, { status: 200, body: {} }]);
    await makeClient(impl).getDevice('a/b c');
    expect(calls[1]!.url).toContain('/v1/devices/a%2Fb%20c');
  });

  it('retries a 500 and succeeds', async () => {
    const { impl } = stubFetch([
      token,
      { status: 500, body: 'boom' },
      { status: 200, body: { id: 'dev-1', tempIndoor: 21 } },
    ]);

    await expect(makeClient(impl).getDevice('dev-1')).resolves.toMatchObject({
      tempIndoor: 21,
    });
  });

  it('gives up after the attempt budget', async () => {
    const { impl } = stubFetch([token, { status: 500, body: 'boom' }]);

    await expect(
      makeClient(impl, { maxAttempts: 2 }).getDevice('dev-1'),
    ).rejects.toBeInstanceOf(DaikinApiError);
  });

  it('does not retry a 400', async () => {
    const { impl, calls } = stubFetch([token, { status: 400, body: 'bad setpoints' }]);

    await expect(
      makeClient(impl).updateDevice('dev-1', {
        mode: 1,
        heatSetpoint: 30,
        coolSetpoint: 20,
      }),
    ).rejects.toThrow(/HTTP 400/);
    expect(calls).toHaveLength(2);
  });

  it('wraps network failures', async () => {
    const impl = (async () => {
      throw new Error('ECONNREFUSED');
    }) as unknown as typeof fetch;

    await expect(
      makeClient(impl, { maxAttempts: 1 }).getDevices(),
    ).rejects.toBeInstanceOf(DaikinNetworkError);
  });

  it('reports invalid JSON rather than throwing a parse error', async () => {
    const { impl } = stubFetch([token, { status: 200, body: '<html>oops</html>' }]);
    await expect(makeClient(impl).getDevice('dev-1')).rejects.toBeInstanceOf(
      DaikinResponseError,
    );
  });

  it('honours Retry-After on a 429', async () => {
    const { impl } = stubFetch([
      token,
      { status: 429, body: 'slow down', headers: { 'retry-after': '1' } },
      { status: 200, body: { id: 'dev-1' } },
    ]);

    await expect(makeClient(impl).getDevice('dev-1')).resolves.toMatchObject({
      id: 'dev-1',
    });
  });
});

describe('normaliseDeviceListing', () => {
  it('flattens the documented location-grouped shape', () => {
    expect(
      normaliseDeviceListing([
        {
          locationName: 'Home',
          devices: [
            { id: '1', name: 'Upstairs', model: 'ONEPLUS', firmwareVersion: '3.1' },
            { id: '2', name: 'Downstairs' },
          ],
        },
        { locationName: 'Cabin', devices: [{ id: '3', name: 'Cabin' }] },
      ]),
    ).toEqual([
      {
        id: '1',
        name: 'Upstairs',
        model: 'ONEPLUS',
        firmwareVersion: '3.1',
        locationName: 'Home',
      },
      {
        id: '2',
        name: 'Downstairs',
        model: undefined,
        firmwareVersion: undefined,
        locationName: 'Home',
      },
      {
        id: '3',
        name: 'Cabin',
        model: undefined,
        firmwareVersion: undefined,
        locationName: 'Cabin',
      },
    ]);
  });

  it('accepts a bare device array', () => {
    expect(normaliseDeviceListing([{ id: '1', name: 'Hall' }])).toHaveLength(1);
  });

  it('accepts a locations wrapper object', () => {
    expect(
      normaliseDeviceListing({ locations: [{ name: 'Home', devices: [{ id: '1', name: 'Hall' }] }] }),
    ).toEqual([
      {
        id: '1',
        name: 'Hall',
        model: undefined,
        firmwareVersion: undefined,
        locationName: 'Home',
      },
    ]);
  });

  it('falls back to the id when a thermostat has no name', () => {
    expect(normaliseDeviceListing([{ id: '1' }])[0]!.name).toBe('1');
  });

  it('skips entries without a usable id', () => {
    expect(normaliseDeviceListing([{ name: 'nameless' }, null, 7])).toEqual([]);
  });

  it('rejects a shape it cannot interpret', () => {
    expect(() => normaliseDeviceListing('nope')).toThrow(DaikinResponseError);
  });
});

describe('parseRetryAfter', () => {
  it('reads delta-seconds', () => {
    expect(parseRetryAfter('30')).toBe(30_000);
  });

  it('reads an HTTP date', () => {
    const now = Date.parse('2026-08-10T12:00:00Z');
    expect(parseRetryAfter('Mon, 10 Aug 2026 12:00:30 GMT', now)).toBe(30_000);
  });

  it('never returns a negative delay', () => {
    const now = Date.parse('2026-08-10T12:00:00Z');
    expect(parseRetryAfter('Mon, 10 Aug 2026 11:59:00 GMT', now)).toBe(0);
  });

  it('ignores garbage', () => {
    expect(parseRetryAfter('later')).toBeNull();
    expect(parseRetryAfter(null)).toBeNull();
  });
});
