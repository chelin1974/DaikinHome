/**
 * End-to-end test: the real platform, the real HAP service objects, and a real
 * HTTP server standing in for the Daikin cloud.
 *
 * This is the test that would catch a plugin that type-checks but never
 * actually appears in the Home app.
 */
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

import { Accessory, Characteristic, Service, uuid } from '@homebridge/hap-nodejs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { DaikinMode, EquipmentStatus, type DaikinDevice } from '../src/api/types';
import { DaikinOnePlatform } from '../src/platform';

class TestPlatformAccessory extends Accessory {
  context: Record<string, unknown> = {};

  constructor(displayName: string, accessoryUuid: string) {
    super(displayName, accessoryUuid);
  }
}

interface FakeCloud {
  server: Server;
  baseUrl: string;
  device: DaikinDevice;
  writes: Array<Record<string, unknown>>;
  tokenRequests: number;
}

async function startFakeCloud(): Promise<FakeCloud> {
  const state: FakeCloud = {
    server: undefined as unknown as Server,
    baseUrl: '',
    writes: [],
    tokenRequests: 0,
    device: {
      id: 'thermostat-1',
      name: 'Hallway',
      model: 'ONEPLUS',
      firmwareVersion: '3.4.1',
      mode: DaikinMode.AUTO,
      equipmentStatus: EquipmentStatus.HEAT,
      heatSetpoint: 20,
      coolSetpoint: 24,
      setpointMinimum: 10,
      setpointMaximum: 32.2,
      setpointDelta: 2,
      tempIndoor: 19.5,
      humIndoor: 41,
      tempOutdoor: 7.2,
      humOutdoor: 66,
      scheduleEnabled: true,
    },
  };

  const server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => chunks.push(chunk));
    req.on('end', () => {
      const url = req.url ?? '';
      const body = chunks.length > 0 ? JSON.parse(Buffer.concat(chunks).toString()) : undefined;

      const reply = (status: number, payload: unknown) => {
        res.writeHead(status, { 'content-type': 'application/json' });
        res.end(JSON.stringify(payload));
      };

      if (req.headers['x-api-key'] !== 'test-key') {
        return reply(403, { message: 'bad api key' });
      }

      if (req.method === 'POST' && url === '/v1/token') {
        state.tokenRequests++;
        return reply(200, { accessToken: 'access-1', accessTokenExpiresIn: 3600 });
      }

      if (req.headers.authorization !== 'Bearer access-1') {
        return reply(401, { message: 'bad token' });
      }

      if (req.method === 'GET' && url === '/v1/devices') {
        return reply(200, [
          {
            locationName: 'Home',
            devices: [
              {
                id: state.device.id,
                name: state.device.name,
                model: state.device.model,
                firmwareVersion: state.device.firmwareVersion,
              },
            ],
          },
        ]);
      }

      if (req.method === 'GET' && url === '/v1/devices/thermostat-1') {
        return reply(200, state.device);
      }

      if (req.method === 'PUT' && url === '/v1/devices/thermostat-1/msp') {
        state.writes.push(body);
        Object.assign(state.device, body);
        return reply(200, { message: 'ok' });
      }

      return reply(404, { message: `no route for ${req.method} ${url}` });
    });
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  state.server = server;
  state.baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  return state;
}

interface Harness {
  platform: DaikinOnePlatform;
  registered: TestPlatformAccessory[];
  unregistered: TestPlatformAccessory[];
  launch: () => Promise<void>;
  shutdown: () => void;
  logs: { level: string; message: string }[];
}

function createHarness(cloud: FakeCloud, overrides: Record<string, unknown> = {}): Harness {
  const registered: TestPlatformAccessory[] = [];
  const unregistered: TestPlatformAccessory[] = [];
  const logs: { level: string; message: string }[] = [];
  const handlers = new Map<string, () => void>();

  const log = {
    info: (message: string) => logs.push({ level: 'info', message }),
    warn: (message: string) => logs.push({ level: 'warn', message }),
    error: (message: string) => logs.push({ level: 'error', message }),
    debug: (message: string) => logs.push({ level: 'debug', message }),
    log: () => undefined,
    success: (message: string) => logs.push({ level: 'info', message }),
    prefix: 'DaikinOne',
  };

  const api = {
    hap: { Service, Characteristic, uuid },
    platformAccessory: TestPlatformAccessory,
    on: (event: string, handler: () => void) => {
      handlers.set(event, handler);
    },
    registerPlatformAccessories: (_p: string, _n: string, accessories: TestPlatformAccessory[]) => {
      registered.push(...accessories);
    },
    unregisterPlatformAccessories: (_p: string, _n: string, accessories: TestPlatformAccessory[]) => {
      unregistered.push(...accessories);
    },
    updatePlatformAccessories: () => undefined,
  };

  const platform = new DaikinOnePlatform(
    log as never,
    {
      platform: 'DaikinOne',
      name: 'Daikin One',
      apiKey: 'test-key',
      email: 'user@example.com',
      integratorToken: 'integrator-token',
      baseUrl: cloud.baseUrl,
      ...overrides,
    },
    api as never,
  );

  return {
    platform,
    registered,
    unregistered,
    logs,
    launch: async () => {
      handlers.get('didFinishLaunching')?.();
      await waitFor(() => registered.length > 0 || logs.some((l) => l.level === 'error'));
      // Let the initial refresh land.
      await waitFor(
        () =>
          registered[0]?.getService(Service.Thermostat)?.getCharacteristic(
            Characteristic.CurrentTemperature,
          ).value !== 0,
      );
    },
    shutdown: () => handlers.get('shutdown')?.(),
  };
}

async function waitFor(predicate: () => boolean, timeoutMs = 5_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() > deadline) {
      throw new Error('timed out waiting for condition');
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
}

let cloud: FakeCloud;
let harness: Harness | undefined;

beforeEach(async () => {
  cloud = await startFakeCloud();
});

afterEach(async () => {
  harness?.shutdown();
  harness = undefined;
  await new Promise<void>((resolve) => cloud.server.close(() => resolve()));
});

describe('DaikinOnePlatform', () => {
  it('discovers the thermostat and publishes it as a HomeKit Thermostat', async () => {
    harness = createHarness(cloud);
    await harness.launch();

    expect(harness.registered).toHaveLength(1);
    const accessory = harness.registered[0]!;
    expect(accessory.displayName).toBe('Hallway');

    const information = accessory.getService(Service.AccessoryInformation)!;
    expect(information.getCharacteristic(Characteristic.Manufacturer).value).toBe('Daikin');
    expect(information.getCharacteristic(Characteristic.Model).value).toBe('ONEPLUS');
    expect(information.getCharacteristic(Characteristic.SerialNumber).value).toBe(
      'thermostat-1',
    );
    expect(information.getCharacteristic(Characteristic.FirmwareRevision).value).toBe(
      '3.4.1',
    );

    const thermostat = accessory.getService(Service.Thermostat)!;
    expect(thermostat.getCharacteristic(Characteristic.CurrentTemperature).value).toBe(19.5);
    expect(thermostat.getCharacteristic(Characteristic.CurrentRelativeHumidity).value).toBe(41);
    expect(thermostat.getCharacteristic(Characteristic.TargetHeatingCoolingState).value).toBe(3);
    expect(thermostat.getCharacteristic(Characteristic.CurrentHeatingCoolingState).value).toBe(1);
    expect(thermostat.getCharacteristic(Characteristic.HeatingThresholdTemperature).value).toBe(20);
    expect(thermostat.getCharacteristic(Characteristic.CoolingThresholdTemperature).value).toBe(24);
    // Auto mode: the single dial reports the midpoint of the two setpoints.
    expect(thermostat.getCharacteristic(Characteristic.TargetTemperature).value).toBe(22);
  });

  it('adds the outdoor temperature sensor by default and omits humidity', async () => {
    harness = createHarness(cloud);
    await harness.launch();

    const accessory = harness.registered[0]!;
    const outdoor = accessory.getServiceById(Service.TemperatureSensor, 'outdoor-temperature');
    // HAP quantises to the characteristic's 0.1 step, so compare approximately.
    expect(outdoor?.getCharacteristic(Characteristic.CurrentTemperature).value).toBeCloseTo(
      7.2,
      5,
    );
    expect(accessory.getServiceById(Service.HumiditySensor, 'outdoor-humidity')).toBeUndefined();
  });

  it('adds the outdoor humidity sensor when asked', async () => {
    harness = createHarness(cloud, { showOutdoorHumiditySensor: true });
    await harness.launch();

    const outdoor = harness.registered[0]!.getServiceById(
      Service.HumiditySensor,
      'outdoor-humidity',
    );
    expect(outdoor?.getCharacteristic(Characteristic.CurrentRelativeHumidity).value).toBe(66);
  });

  it('coalesces a mode change and a temperature change into one API write', async () => {
    harness = createHarness(cloud);
    await harness.launch();

    const thermostat = harness.registered[0]!.getService(Service.Thermostat)!;
    await thermostat.getCharacteristic(Characteristic.TargetHeatingCoolingState).handleSetRequest(1);
    await thermostat.getCharacteristic(Characteristic.TargetTemperature).handleSetRequest(22.5);

    await waitFor(() => cloud.writes.length > 0);
    // Give any straggling write time to arrive before asserting there is one.
    await new Promise((resolve) => setTimeout(resolve, 400));

    expect(cloud.writes).toEqual([
      { mode: DaikinMode.HEAT, heatSetpoint: 22.5, coolSetpoint: 24.5 },
    ]);
  });

  it('shows the new value immediately, before the cloud confirms it', async () => {
    harness = createHarness(cloud);
    await harness.launch();

    const thermostat = harness.registered[0]!.getService(Service.Thermostat)!;
    await thermostat.getCharacteristic(Characteristic.TargetHeatingCoolingState).handleSetRequest(2);

    // Read back before the debounce window has even elapsed.
    await expect(
      thermostat.getCharacteristic(Characteristic.TargetHeatingCoolingState).handleGetRequest(),
    ).resolves.toBe(2);
    expect(cloud.writes).toHaveLength(0);
  });

  it('keeps the heat and cool setpoints separated by the reported delta', async () => {
    harness = createHarness(cloud);
    await harness.launch();

    const thermostat = harness.registered[0]!.getService(Service.Thermostat)!;
    // Drag the heat threshold up past the cool threshold.
    await thermostat
      .getCharacteristic(Characteristic.HeatingThresholdTemperature)
      .handleSetRequest(25);

    await waitFor(() => cloud.writes.length > 0);
    expect(cloud.writes[0]).toEqual({
      mode: DaikinMode.AUTO,
      heatSetpoint: 25,
      coolSetpoint: 27,
    });
  });

  it('reverts the Home app when the thermostat rejects a change', async () => {
    harness = createHarness(cloud);
    await harness.launch();

    // Make every write fail from here on.
    cloud.server.closeAllConnections?.();
    const original = cloud.device.mode;
    cloud.device.mode = original;
    const failing = await startFailingCloud(cloud);

    const thermostat = harness.registered[0]!.getService(Service.Thermostat)!;
    await thermostat.getCharacteristic(Characteristic.TargetHeatingCoolingState).handleSetRequest(0);

    await waitFor(() => harness!.logs.some((l) => l.level === 'error'));
    expect(
      thermostat.getCharacteristic(Characteristic.TargetHeatingCoolingState).value,
    ).toBe(3);
    failing();
  });

  it('reports a credential failure once and registers nothing', async () => {
    harness = createHarness(cloud, { apiKey: 'wrong-key' });
    await harness.launch();

    expect(harness.registered).toHaveLength(0);
    expect(harness.logs.some((l) => l.level === 'error' && /credentials/.test(l.message))).toBe(
      true,
    );
  });

  it('refuses to start without credentials instead of crashing Homebridge', () => {
    const logs: { level: string; message: string }[] = [];
    expect(
      () =>
        new DaikinOnePlatform(
          {
            info: () => undefined,
            warn: () => undefined,
            error: (message: string) => logs.push({ level: 'error', message }),
            debug: () => undefined,
            log: () => undefined,
            success: () => undefined,
            prefix: 'DaikinOne',
          } as never,
          { platform: 'DaikinOne' },
          {
            hap: { Service, Characteristic, uuid },
            platformAccessory: TestPlatformAccessory,
            on: () => undefined,
          } as never,
        ),
    ).not.toThrow();

    expect(logs[0]!.message).toMatch(/apiKey, email, integratorToken/);
  });
});

/** Swaps the msp route for one that always fails; returns a restore function. */
async function startFailingCloud(cloud: FakeCloud): Promise<() => void> {
  const listeners = cloud.server.listeners('request');
  cloud.server.removeAllListeners('request');
  cloud.server.on('request', (req, res) => {
    if (req.method === 'PUT') {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ message: 'setpoints out of range' }));
      return;
    }
    (listeners[0] as (...args: unknown[]) => void)(req, res);
  });

  return () => {
    cloud.server.removeAllListeners('request');
    for (const listener of listeners) {
      cloud.server.on('request', listener as never);
    }
  };
}
