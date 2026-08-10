import type {
  API,
  Characteristic,
  DynamicPlatformPlugin,
  Logger,
  PlatformAccessory,
  PlatformConfig,
  Service,
} from 'homebridge';

import { DaikinOneClient } from './api/client';
import { DaikinAuthError } from './api/errors';
import type { DaikinDeviceSummary } from './api/types';
import {
  ConfigError,
  pollIntervalWasClamped,
  resolveConfig,
  type DaikinOneConfig,
  type ResolvedConfig,
} from './config';
import {
  MIN_POLL_INTERVAL_SECONDS,
  PLATFORM_NAME,
  PLUGIN_NAME,
} from './settings';
import { ThermostatAccessory } from './thermostatAccessory';

/**
 * Homebridge dynamic platform for Daikin One thermostats.
 *
 * Discovers every thermostat on the account once Homebridge has finished
 * launching, then polls each one on a shared timer. Polling is deliberately
 * slow — Daikin's documented floor is one request per thermostat every three
 * minutes — so accessory reads are served from cache.
 */
export class DaikinOnePlatform implements DynamicPlatformPlugin {
  readonly Service: typeof Service;
  readonly Characteristic: typeof Characteristic;

  readonly settings: ResolvedConfig;
  readonly client: DaikinOneClient;

  /** Accessories restored from Homebridge's cache, before discovery runs. */
  private readonly cachedAccessories = new Map<string, PlatformAccessory>();
  private readonly thermostats = new Map<string, ThermostatAccessory>();

  private pollTimer?: NodeJS.Timeout;
  private shuttingDown = false;
  /** Set when the config is unusable; the platform then does nothing. */
  private readonly disabledReason?: string;

  constructor(
    readonly log: Logger,
    config: PlatformConfig,
    readonly api: API,
  ) {
    this.Service = api.hap.Service;
    this.Characteristic = api.hap.Characteristic;

    let settings: ResolvedConfig | undefined;
    try {
      settings = resolveConfig(config as DaikinOneConfig);
    } catch (error) {
      if (error instanceof ConfigError) {
        this.disabledReason = error.message;
      } else {
        throw error;
      }
    }

    if (!settings) {
      this.log.error(`Daikin One is not configured: ${this.disabledReason}`);
      // Keep the object valid so Homebridge does not crash; discovery is
      // skipped and cached accessories are left untouched.
      this.settings = resolveConfigFallback();
      this.client = null as unknown as DaikinOneClient;
      return;
    }

    this.settings = settings;

    if (pollIntervalWasClamped(config as DaikinOneConfig)) {
      this.log.warn(
        `pollIntervalSeconds was raised to ${MIN_POLL_INTERVAL_SECONDS}s: Daikin rate limits ` +
          'integrations that poll a thermostat more than once every three minutes.',
      );
    }

    this.client = new DaikinOneClient({
      apiKey: this.settings.apiKey,
      email: this.settings.email,
      integratorToken: this.settings.integratorToken,
      baseUrl: this.settings.baseUrl,
      timeoutMs: this.settings.requestTimeoutMs,
      log: {
        debug: (message, ...params) =>
          this.settings.debug
            ? this.log.info(`[api] ${message}`, ...params)
            : this.log.debug(`[api] ${message}`, ...params),
        info: (message, ...params) => this.log.info(`[api] ${message}`, ...params),
        warn: (message, ...params) => this.log.warn(`[api] ${message}`, ...params),
        error: (message, ...params) => this.log.error(`[api] ${message}`, ...params),
      },
    });

    this.api.on('didFinishLaunching', () => {
      void this.discoverDevices();
    });

    this.api.on('shutdown', () => this.shutdown());
  }

  /** Homebridge replays every accessory it cached for this plugin at startup. */
  configureAccessory(accessory: PlatformAccessory): void {
    this.log.debug(`restoring cached accessory: ${accessory.displayName}`);
    this.cachedAccessories.set(accessory.UUID, accessory);
  }

  private async discoverDevices(): Promise<void> {
    if (this.disabledReason) {
      return;
    }

    let devices: DaikinDeviceSummary[];
    try {
      devices = await this.client.getDevices();
    } catch (error) {
      if (error instanceof DaikinAuthError) {
        this.log.error(
          `Daikin rejected the credentials, so no thermostats were added: ${error.message}`,
        );
        return;
      }
      this.log.error(
        `could not list Daikin thermostats: ${describe(error)}. Retrying in ${this.settings.pollIntervalSeconds}s.`,
      );
      // Transient failures (cloud outage, no WAN at boot) should not leave the
      // platform permanently empty.
      this.scheduleRetry();
      return;
    }

    if (devices.length === 0) {
      this.log.warn(
        'the Daikin account has no thermostats the Open API can see. Confirm the ' +
          'integrator token was generated on the account that owns the thermostat.',
      );
    }

    const activeUuids = new Set<string>();

    for (const device of devices) {
      const uuid = this.api.hap.uuid.generate(`${PLUGIN_NAME}:${device.id}`);
      activeUuids.add(uuid);

      const existing = this.cachedAccessories.get(uuid);
      if (existing) {
        existing.displayName = device.name;
        existing.context.summary = device;
        this.thermostats.set(
          device.id,
          new ThermostatAccessory(this, existing, device),
        );
        this.api.updatePlatformAccessories([existing]);
        this.log.info(`restored thermostat "${device.name}"`);
        continue;
      }

      const accessory = new this.api.platformAccessory(device.name, uuid);
      accessory.context.summary = device;
      this.thermostats.set(
        device.id,
        new ThermostatAccessory(this, accessory, device),
      );
      this.api.registerPlatformAccessories(PLUGIN_NAME, PLATFORM_NAME, [accessory]);
      this.log.info(
        `added thermostat "${device.name}"${device.locationName ? ` at ${device.locationName}` : ''}`,
      );
    }

    const stale = [...this.cachedAccessories.entries()]
      .filter(([uuid]) => !activeUuids.has(uuid))
      .map(([, accessory]) => accessory);

    if (stale.length > 0) {
      this.log.info(
        `removing ${stale.length} thermostat(s) no longer on the Daikin account`,
      );
      this.api.unregisterPlatformAccessories(PLUGIN_NAME, PLATFORM_NAME, stale);
    }

    await this.refreshAll();
    this.startPolling();
  }

  /** Re-reads a single thermostat, used after a write settles. */
  async refreshDevice(deviceId: string): Promise<void> {
    const thermostat = this.thermostats.get(deviceId);
    if (!thermostat || this.shuttingDown) {
      return;
    }

    try {
      const device = await this.client.getDevice(deviceId);
      thermostat.applyState(device);
    } catch (error) {
      if (error instanceof DaikinAuthError) {
        this.log.error(`Daikin rejected the credentials: ${error.message}`);
      } else {
        this.log.warn(
          `could not refresh "${thermostat.displayName}": ${describe(error)}`,
        );
      }
      thermostat.reportFault(error);
    }
  }

  private async refreshAll(): Promise<void> {
    // Sequential on purpose: the client serialises requests anyway, and a
    // burst of parallel reads is exactly what Daikin rate limits.
    for (const deviceId of this.thermostats.keys()) {
      if (this.shuttingDown) {
        return;
      }
      await this.refreshDevice(deviceId);
    }
  }

  private startPolling(): void {
    if (this.pollTimer || this.shuttingDown) {
      return;
    }
    this.pollTimer = setInterval(() => {
      void this.refreshAll();
    }, this.settings.pollIntervalSeconds * 1_000);
    this.pollTimer.unref?.();

    this.log.info(
      `polling ${this.thermostats.size} thermostat(s) every ${this.settings.pollIntervalSeconds}s`,
    );
  }

  private scheduleRetry(): void {
    const timer = setTimeout(() => {
      void this.discoverDevices();
    }, this.settings.pollIntervalSeconds * 1_000);
    timer.unref?.();
  }

  private shutdown(): void {
    this.shuttingDown = true;
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = undefined;
    }
    for (const thermostat of this.thermostats.values()) {
      thermostat.dispose();
    }
  }
}

/** Placeholder settings for a platform that failed config validation. */
function resolveConfigFallback(): ResolvedConfig {
  return {
    apiKey: '',
    email: '',
    integratorToken: '',
    pollIntervalSeconds: MIN_POLL_INTERVAL_SECONDS,
    requestTimeoutMs: 20_000,
    showIndoorHumidity: true,
    showOutdoorTemperatureSensor: true,
    showOutdoorHumiditySensor: false,
    experimentalScheduleSwitch: false,
    experimentalFanSwitch: false,
    debug: false,
  };
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
