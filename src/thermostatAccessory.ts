import type {
  Characteristic,
  CharacteristicValue,
  PlatformAccessory,
  Service,
  WithUUID,
} from 'homebridge';

import type { DaikinDevice, DaikinDeviceSummary, MspPayload } from './api/types';
import { FanCirculate } from './api/types';
import type { DaikinOnePlatform } from './platform';
import {
  applyDesired,
  buildMspUpdate,
  clamp,
  getSetpointLimits,
  round1,
  toCurrentHeatingCoolingState,
  toTargetHeatingCoolingState,
  toTargetTemperature,
  toDaikinMode,
  type DesiredState,
  type SetpointAnchor,
} from './mapping';
import { WRITE_DEBOUNCE_MS, WRITE_SETTLE_MS } from './settings';

/** Extra `msp` fields the optional switches drive. */
interface ExtraWrites {
  scheduleEnabled?: boolean;
  fanCirculate?: number;
}

/**
 * One Daikin thermostat, exposed to HomeKit.
 *
 * Reads are served from the last poll (plus an overlay of any write that has
 * not landed yet), so the Home app never blocks on the network. Writes are
 * debounced and coalesced into a single `msp` call.
 */
export class ThermostatAccessory {
  private readonly thermostat: Service;
  private readonly outdoorTemperature?: Service;
  private readonly outdoorHumidity?: Service;
  private readonly scheduleSwitch?: Service;
  private readonly fanSwitch?: Service;

  private state: DaikinDevice;
  private displayUnits: number;

  /** Values written from HomeKit that the cloud has not confirmed yet. */
  private pendingDesired: DesiredState = {};
  private pendingExtras: ExtraWrites = {};
  private anchor: SetpointAnchor = 'none';
  private optimisticUntil = 0;

  private flushTimer?: NodeJS.Timeout;
  private settleTimer?: NodeJS.Timeout;
  private writeInFlight: Promise<void> = Promise.resolve();
  private disposed = false;
  private propsInitialised = false;

  constructor(
    private readonly platform: DaikinOnePlatform,
    private readonly accessory: PlatformAccessory,
    private readonly summary: DaikinDeviceSummary,
  ) {
    const { Service, Characteristic } = this.platform;
    const cached = accessory.context.device as DaikinDevice | undefined;
    this.state = cached ?? {};
    this.displayUnits =
      typeof accessory.context.displayUnits === 'number'
        ? accessory.context.displayUnits
        : Characteristic.TemperatureDisplayUnits.CELSIUS;

    (
      accessory.getService(Service.AccessoryInformation) ??
      accessory.addService(Service.AccessoryInformation)
    )
      .setCharacteristic(Characteristic.Manufacturer, 'Daikin')
      .setCharacteristic(Characteristic.Model, summary.model ?? 'Daikin One')
      .setCharacteristic(Characteristic.SerialNumber, summary.id)
      .setCharacteristic(
        Characteristic.FirmwareRevision,
        summary.firmwareVersion ?? '0.0.0',
      );

    this.thermostat =
      accessory.getService(Service.Thermostat) ??
      accessory.addService(Service.Thermostat, summary.name);
    this.thermostat.setCharacteristic(Characteristic.Name, summary.name);

    this.thermostat
      .getCharacteristic(Characteristic.CurrentHeatingCoolingState)
      .onGet(() => toCurrentHeatingCoolingState(this.view()));

    this.thermostat
      .getCharacteristic(Characteristic.TargetHeatingCoolingState)
      .onGet(() => toTargetHeatingCoolingState(this.view()))
      .onSet((value) => this.setTargetHeatingCoolingState(value));

    this.thermostat
      .getCharacteristic(Characteristic.CurrentTemperature)
      .onGet(() => this.currentTemperature());

    this.thermostat
      .getCharacteristic(Characteristic.TargetTemperature)
      .onGet(() => this.boundedTarget(toTargetTemperature(this.view())))
      .onSet((value) => this.setTargetTemperature(value));

    this.thermostat
      .getCharacteristic(Characteristic.HeatingThresholdTemperature)
      .onGet(() => this.boundedTarget(this.view().heatSetpoint))
      .onSet((value) => this.setThreshold('heat', value));

    this.thermostat
      .getCharacteristic(Characteristic.CoolingThresholdTemperature)
      .onGet(() => this.boundedTarget(this.view().coolSetpoint))
      .onSet((value) => this.setThreshold('cool', value));

    this.thermostat
      .getCharacteristic(Characteristic.TemperatureDisplayUnits)
      .onGet(() => this.displayUnits)
      .onSet((value) => {
        // Purely a display preference; the API is Celsius either way.
        this.displayUnits = value as number;
        this.accessory.context.displayUnits = this.displayUnits;
      });

    if (this.platform.settings.showIndoorHumidity) {
      this.thermostat
        .getCharacteristic(Characteristic.CurrentRelativeHumidity)
        .onGet(() => clamp(Math.round(numberOr(this.state.humIndoor, 0)), 0, 100));
    }

    this.outdoorTemperature = this.optionalService(
      this.platform.settings.showOutdoorTemperatureSensor,
      Service.TemperatureSensor,
      `${summary.name} Outdoor`,
      'outdoor-temperature',
    );
    this.outdoorTemperature
      ?.getCharacteristic(Characteristic.CurrentTemperature)
      .onGet(() => clamp(numberOr(this.state.tempOutdoor, 0), -270, 100));

    this.outdoorHumidity = this.optionalService(
      this.platform.settings.showOutdoorHumiditySensor,
      Service.HumiditySensor,
      `${summary.name} Outdoor Humidity`,
      'outdoor-humidity',
    );
    this.outdoorHumidity
      ?.getCharacteristic(Characteristic.CurrentRelativeHumidity)
      .onGet(() => clamp(Math.round(numberOr(this.state.humOutdoor, 0)), 0, 100));

    this.scheduleSwitch = this.optionalService(
      this.platform.settings.experimentalScheduleSwitch,
      Service.Switch,
      `${summary.name} Schedule`,
      'schedule',
    );
    this.scheduleSwitch
      ?.getCharacteristic(Characteristic.On)
      .onGet(() => this.pendingExtras.scheduleEnabled ?? this.state.scheduleEnabled === true)
      .onSet((value) => this.queueExtra({ scheduleEnabled: value === true }));

    this.fanSwitch = this.optionalService(
      this.platform.settings.experimentalFanSwitch,
      Service.Switch,
      `${summary.name} Fan Circulate`,
      'fan-circulate',
    );
    this.fanSwitch
      ?.getCharacteristic(Characteristic.On)
      .onGet(() => {
        const value = this.pendingExtras.fanCirculate ?? this.state.fanCirculate;
        return value !== undefined && value !== FanCirculate.OFF;
      })
      .onSet((value) =>
        this.queueExtra({
          fanCirculate: value === true ? FanCirculate.ALWAYS_ON : FanCirculate.OFF,
        }),
      );

    if (cached) {
      this.applyState(cached);
    }
  }

  get deviceId(): string {
    return this.summary.id;
  }

  get displayName(): string {
    return this.summary.name;
  }

  /** Called by the platform after every successful poll. */
  applyState(device: DaikinDevice): void {
    this.state = device;
    this.accessory.context.device = device;

    this.initialiseTemperatureProps(device);

    const { Characteristic } = this.platform;
    const view = this.view();
    const limits = getSetpointLimits(view);

    this.thermostat.updateCharacteristic(
      Characteristic.CurrentHeatingCoolingState,
      toCurrentHeatingCoolingState(view),
    );
    this.thermostat.updateCharacteristic(
      Characteristic.TargetHeatingCoolingState,
      toTargetHeatingCoolingState(view),
    );
    this.thermostat.updateCharacteristic(
      Characteristic.CurrentTemperature,
      this.currentTemperature(),
    );
    this.thermostat.updateCharacteristic(
      Characteristic.TargetTemperature,
      this.boundedTarget(toTargetTemperature(view)),
    );
    this.thermostat.updateCharacteristic(
      Characteristic.HeatingThresholdTemperature,
      clamp(numberOr(view.heatSetpoint, limits.minimum), limits.minimum, limits.maximum),
    );
    this.thermostat.updateCharacteristic(
      Characteristic.CoolingThresholdTemperature,
      clamp(numberOr(view.coolSetpoint, limits.maximum), limits.minimum, limits.maximum),
    );

    if (this.platform.settings.showIndoorHumidity && device.humIndoor !== undefined) {
      this.thermostat.updateCharacteristic(
        Characteristic.CurrentRelativeHumidity,
        clamp(Math.round(numberOr(device.humIndoor, 0)), 0, 100),
      );
    }

    if (this.outdoorTemperature && device.tempOutdoor !== undefined) {
      this.outdoorTemperature.updateCharacteristic(
        Characteristic.CurrentTemperature,
        clamp(numberOr(device.tempOutdoor, 0), -270, 100),
      );
    }

    if (this.outdoorHumidity && device.humOutdoor !== undefined) {
      this.outdoorHumidity.updateCharacteristic(
        Characteristic.CurrentRelativeHumidity,
        clamp(Math.round(numberOr(device.humOutdoor, 0)), 0, 100),
      );
    }

    if (this.scheduleSwitch && device.scheduleEnabled !== undefined) {
      this.scheduleSwitch.updateCharacteristic(
        Characteristic.On,
        this.pendingExtras.scheduleEnabled ?? device.scheduleEnabled === true,
      );
    }

    if (this.fanSwitch && device.fanCirculate !== undefined) {
      const value = this.pendingExtras.fanCirculate ?? device.fanCirculate;
      this.fanSwitch.updateCharacteristic(
        Characteristic.On,
        value !== FanCirculate.OFF,
      );
    }
  }

  /** Marks the accessory unreachable in Home while the cloud is unavailable. */
  reportFault(error: unknown): void {
    this.platform.log.debug(
      `[${this.displayName}] refresh failed: ${error instanceof Error ? error.message : String(error)}`,
    );
  }

  /** Cancels timers so Homebridge can shut down promptly. */
  dispose(): void {
    this.disposed = true;
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
    }
    if (this.settleTimer) {
      clearTimeout(this.settleTimer);
    }
  }

  /**
   * The state HomeKit should see: the last poll, with any un-landed write
   * layered on top until the cloud has had time to catch up.
   */
  private view(): DaikinDevice {
    if (Date.now() >= this.optimisticUntil) {
      return this.state;
    }
    return applyDesired(this.state, this.pendingDesired);
  }

  private currentTemperature(): number {
    // -270 is HAP's floor; a thermostat that has not reported yet reads 0 in
    // the Home app rather than throwing.
    return clamp(numberOr(this.state.tempIndoor, 0), -270, 100);
  }

  private boundedTarget(value: unknown): number {
    const limits = getSetpointLimits(this.view());
    return round1(clamp(numberOr(value, limits.minimum), limits.minimum, limits.maximum));
  }

  /**
   * Narrows the HomeKit sliders to what the thermostat actually accepts.
   *
   * Done once, on the first poll that reports limits: HAP re-validates the
   * current value against new props, so churning these on every poll risks
   * spurious warnings.
   */
  private initialiseTemperatureProps(device: DaikinDevice): void {
    if (this.propsInitialised) {
      return;
    }
    if (device.setpointMinimum === undefined && device.setpointMaximum === undefined) {
      return;
    }

    const { Characteristic } = this.platform;
    const limits = getSetpointLimits(device);
    const props = {
      minValue: limits.minimum,
      maxValue: limits.maximum,
      minStep: 0.1,
    };

    const scaled: [WithUUID<new () => Characteristic>, number][] = [
      [Characteristic.TargetTemperature, toTargetTemperature(device)],
      [
        Characteristic.HeatingThresholdTemperature,
        numberOr(device.heatSetpoint, limits.minimum),
      ],
      [
        Characteristic.CoolingThresholdTemperature,
        numberOr(device.coolSetpoint, limits.maximum),
      ],
    ];

    for (const [type, seed] of scaled) {
      const characteristic = this.thermostat.getCharacteristic(type);

      // HAP validates the *stored* value against the new props and logs a
      // warning if it no longer fits. HeatingThresholdTemperature in
      // particular starts at 0, which is below every Daikin minimum. Seed a
      // real value first, inside both the old and the new range, so tightening
      // the props is silent.
      const current = characteristic.props;
      const low = Math.max(numberOr(current.minValue, limits.minimum), limits.minimum);
      const high = Math.min(numberOr(current.maxValue, limits.maximum), limits.maximum);
      characteristic.updateValue(round1(clamp(seed, low, high)));

      characteristic.setProps(props);
    }

    this.propsInitialised = true;
    this.platform.log.debug(
      `[${this.displayName}] setpoint range ${limits.minimum}–${limits.maximum} °C, minimum separation ${limits.delta} °C`,
    );
  }

  private setTargetHeatingCoolingState(value: CharacteristicValue): void {
    const mode = toDaikinMode(value as number, this.state);
    this.platform.log.info(
      `[${this.displayName}] mode → ${describeMode(mode)}`,
    );
    this.queue({ mode }, 'none');
  }

  private setTargetTemperature(value: CharacteristicValue): void {
    const target = round1(value as number);
    const state = toTargetHeatingCoolingState(this.view());
    const { TargetHeatingCoolingState } = this.platform.Characteristic;

    // Outside Auto, the single HomeKit dial drives whichever setpoint the
    // current mode uses. In Auto, Home uses the threshold characteristics, so a
    // write here means the user is dragging the midpoint — shift both.
    if (state === TargetHeatingCoolingState.HEAT) {
      this.queue({ heatSetpoint: target }, 'heat');
    } else if (state === TargetHeatingCoolingState.COOL) {
      this.queue({ coolSetpoint: target }, 'cool');
    } else {
      const limits = getSetpointLimits(this.view());
      const half = limits.delta / 2;
      this.queue(
        { heatSetpoint: round1(target - half), coolSetpoint: round1(target + half) },
        'none',
      );
    }
  }

  private setThreshold(which: 'heat' | 'cool', value: CharacteristicValue): void {
    const target = round1(value as number);
    this.queue(
      which === 'heat' ? { heatSetpoint: target } : { coolSetpoint: target },
      which,
    );
  }

  private queueExtra(extras: ExtraWrites): void {
    this.pendingExtras = { ...this.pendingExtras, ...extras };
    this.queue({}, 'none');
  }

  /** Merges a change into the pending write and (re)arms the flush timer. */
  private queue(desired: DesiredState, anchor: SetpointAnchor): void {
    if (this.disposed) {
      return;
    }

    this.pendingDesired = { ...this.pendingDesired, ...desired };
    if (anchor !== 'none') {
      this.anchor = anchor;
    }
    // Keep serving the optimistic view until the write lands and settles.
    this.optimisticUntil = Date.now() + WRITE_DEBOUNCE_MS + WRITE_SETTLE_MS;

    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
    }
    this.flushTimer = setTimeout(() => {
      this.flushTimer = undefined;
      void this.flush();
    }, WRITE_DEBOUNCE_MS);
    this.flushTimer.unref?.();
  }

  /** Serialised so two rapid flushes cannot race each other to the cloud. */
  private flush(): Promise<void> {
    this.writeInFlight = this.writeInFlight.then(() => this.sendPending());
    return this.writeInFlight;
  }

  private async sendPending(): Promise<void> {
    const desired = this.pendingDesired;
    const extras = this.pendingExtras;
    const anchor = this.anchor;

    if (this.disposed || (isEmpty(desired) && isEmpty(extras))) {
      return;
    }

    this.pendingDesired = {};
    this.pendingExtras = {};
    this.anchor = 'none';

    const update = buildMspUpdate(this.state, desired, anchor);
    const payload: MspPayload = { ...update, ...stripUndefined(extras) };

    try {
      await this.platform.client.updateDevice(this.deviceId, payload);
      this.platform.log.info(
        `[${this.displayName}] set mode ${describeMode(update.mode)}, heat ${update.heatSetpoint} °C, cool ${update.coolSetpoint} °C`,
      );

      // Believe our own write until the poll after the cloud has settled.
      this.state = { ...this.state, ...update, ...stripUndefined(extras) };
      this.accessory.context.device = this.state;
      this.optimisticUntil = 0;
      this.scheduleSettleRefresh();
    } catch (error) {
      this.optimisticUntil = 0;
      this.platform.log.error(
        `[${this.displayName}] failed to apply the change: ${error instanceof Error ? error.message : String(error)}`,
      );
      // Snap HomeKit back to the thermostat's real state.
      this.applyState(this.state);
    }
  }

  /** Re-reads the thermostat once the cloud has had time to apply the change. */
  private scheduleSettleRefresh(): void {
    if (this.settleTimer) {
      clearTimeout(this.settleTimer);
    }
    this.settleTimer = setTimeout(() => {
      this.settleTimer = undefined;
      void this.platform.refreshDevice(this.deviceId);
    }, WRITE_SETTLE_MS);
    this.settleTimer.unref?.();
  }

  private optionalService(
    enabled: boolean,
    type: WithUUID<typeof Service>,
    name: string,
    subtype: string,
  ): Service | undefined {
    const existing = this.accessory.getServiceById(type, subtype);

    if (!enabled) {
      // The user turned the option off: drop the service so the extra tile
      // disappears from the Home app instead of freezing at its last value.
      if (existing) {
        this.accessory.removeService(existing);
      }
      return undefined;
    }

    const service = existing ?? this.accessory.addService(type, name, subtype);
    service.setCharacteristic(this.platform.Characteristic.Name, name);
    return service;
  }
}

function describeMode(mode: number): string {
  return (
    (
      {
        0: 'off',
        1: 'heat',
        2: 'cool',
        3: 'auto',
        4: 'emergency heat',
      } as Record<number, string>
    )[mode] ?? `unknown (${mode})`
  );
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function isEmpty(value: object): boolean {
  return Object.keys(value).length === 0;
}

function stripUndefined<T extends object>(value: T): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(value).filter(([, v]) => v !== undefined),
  );
}
