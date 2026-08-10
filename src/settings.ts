/** Must match the `platform` value users put in Homebridge's config.json. */
export const PLATFORM_NAME = 'DaikinOne';

/** Must match the `name` field in package.json. */
export const PLUGIN_NAME = 'homebridge-daikin-one';

/**
 * Daikin documents a polling floor of one request per thermostat every three
 * minutes. Polling faster gets the integration rate limited, so the plugin
 * refuses to go below this no matter what the config says.
 */
export const MIN_POLL_INTERVAL_SECONDS = 180;

/**
 * How long a change takes to appear in the cloud's own view of the thermostat.
 * Daikin documents a 15 second minimum; the extra margin avoids reading back a
 * stale value and bouncing the Home app UI.
 */
export const WRITE_SETTLE_MS = 20_000;

/**
 * How long to batch characteristic writes before sending them.
 *
 * Changing mode and temperature in the Home app produces several writes within
 * a few hundred milliseconds; they collapse into one API call.
 */
export const WRITE_DEBOUNCE_MS = 750;
