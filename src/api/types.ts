/**
 * Types for the Daikin One Cloud Open API (integrator-api.daikinskyport.com).
 *
 * The Open API covers the Daikin One+, One Touch and One Lite thermostats (and
 * the Amana / Goodman rebadges of the same hardware). Daikin publishes the
 * reference at https://daikinone.com/openapi/ — the shapes below follow that
 * documentation, but every field on `DaikinDevice` is declared optional because
 * the exact payload varies by thermostat model and firmware. Nothing in this
 * plugin should assume a field is present; use the accessors in `mapping.ts`.
 *
 * Run `npx daikin-one-probe` to dump what *your* thermostat actually returns.
 */

/** POST /v1/token request body. */
export interface TokenRequest {
  email: string;
  integratorToken: string;
}

/** POST /v1/token response body. */
export interface TokenResponse {
  accessToken: string;
  /** Lifetime of `accessToken` in seconds. */
  accessTokenExpiresIn?: number;
  refreshToken?: string;
  tokenType?: string;
}

/** A thermostat as it appears in the GET /v1/devices listing. */
export interface DaikinDeviceSummary {
  /** Thermostat ID used for every other endpoint. */
  id: string;
  name: string;
  model?: string;
  firmwareVersion?: string;
  /** Location name, filled in by the client when the API groups by location. */
  locationName?: string;
}

/** A location entry from GET /v1/devices. */
export interface DaikinLocation {
  locationName?: string;
  name?: string;
  devices?: DaikinDeviceSummary[];
}

/**
 * GET /v1/devices/{id} response.
 *
 * Temperatures are degrees Celsius, humidity is relative percent.
 */
export interface DaikinDevice {
  id?: string;
  name?: string;
  model?: string;
  firmwareVersion?: string;

  /** What the equipment is doing right now. See `EquipmentStatus`. */
  equipmentStatus?: number;
  /** Requested system mode. See `DaikinMode`. */
  mode?: number;
  /**
   * Which modes the thermostat allows, as configured during installation.
   * See `ModeLimit`. Semantics are not fully documented; treated as advisory.
   */
  modeLimit?: number;
  /** Whether the system supports emergency (auxiliary) heat. */
  modeEmHeatAvailable?: boolean;

  /** Fan mode. See `FanMode`. */
  fan?: number;
  /** Fan circulate mode. See `FanCirculate`. */
  fanCirculate?: number;
  fanCirculateSpeed?: number;

  heatSetpoint?: number;
  coolSetpoint?: number;
  /** Minimum gap the thermostat enforces between heat and cool setpoints. */
  setpointDelta?: number;
  setpointMinimum?: number;
  setpointMaximum?: number;

  tempIndoor?: number;
  humIndoor?: number;
  tempOutdoor?: number;
  humOutdoor?: number;

  /** True when the thermostat is following its programmed schedule. */
  scheduleEnabled?: boolean;
  geofencingEnabled?: boolean;

  /** Unknown/new fields are preserved so the probe tool can show them. */
  [key: string]: unknown;
}

/**
 * PUT /v1/devices/{id}/msp request body.
 *
 * The documented examples always send mode together with both setpoints, so
 * the client sends the full triple on every write rather than a partial patch.
 */
export interface MspUpdate {
  mode: number;
  heatSetpoint: number;
  coolSetpoint: number;
}

/**
 * An `msp` body plus any additional documented fields (`scheduleEnabled`,
 * `fanCirculate`, …) a caller wants to set in the same round trip.
 */
export type MspPayload = MspUpdate & Record<string, unknown>;

/** `mode` / `DaikinDevice.mode` values. */
export const DaikinMode = {
  OFF: 0,
  HEAT: 1,
  COOL: 2,
  AUTO: 3,
  EMERGENCY_HEAT: 4,
} as const;
export type DaikinModeValue = (typeof DaikinMode)[keyof typeof DaikinMode];

/** `equipmentStatus` values. */
export const EquipmentStatus = {
  COOL: 1,
  OVERCOOL_DEHUMIDIFY: 2,
  HEAT: 3,
  FAN: 4,
  IDLE: 5,
} as const;

/** `fan` values. */
export const FanMode = {
  AUTO: 0,
  ON: 1,
} as const;

/** `fanCirculate` values. */
export const FanCirculate = {
  OFF: 0,
  ALWAYS_ON: 1,
  ON_SCHEDULE: 2,
} as const;
