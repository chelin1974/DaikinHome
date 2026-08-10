#!/usr/bin/env node
/**
 * Connectivity and discovery check for the Daikin One Open API.
 *
 * Run it before (or instead of) debugging inside Homebridge: it authenticates,
 * lists the thermostats on the account and dumps the raw JSON each one returns.
 * That JSON is the ground truth for field names — Daikin's payload varies by
 * model and firmware, and this plugin reads it defensively — so if a value is
 * missing in the Home app, start here.
 *
 *   npx daikin-one-probe --api-key KEY --email you@example.com --token TOKEN
 *   DAIKIN_API_KEY=… DAIKIN_EMAIL=… DAIKIN_INTEGRATOR_TOKEN=… npx daikin-one-probe
 *   npx daikin-one-probe --config ./daikin.config.json
 */
import { readFileSync } from 'node:fs';

import { DaikinOneClient } from '../api/client';
import { DaikinAuthError } from '../api/errors';

interface Credentials {
  apiKey: string;
  email: string;
  integratorToken: string;
  baseUrl?: string;
}

function parseArgs(argv: string[]): Record<string, string | boolean> {
  const args: Record<string, string | boolean> = {};
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (!token?.startsWith('--')) {
      continue;
    }
    const key = token.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith('--')) {
      args[key] = next;
      i++;
    } else {
      args[key] = true;
    }
  }
  return args;
}

function loadCredentials(args: Record<string, string | boolean>): Credentials {
  let fromFile: Partial<Credentials> = {};

  if (typeof args.config === 'string') {
    const raw = JSON.parse(readFileSync(args.config, 'utf8')) as Record<string, unknown>;
    // Accept either a bare credentials file or a Homebridge config.json with a
    // DaikinOne platform block in it.
    const platforms = Array.isArray(raw.platforms) ? raw.platforms : [];
    const platform = platforms.find(
      (entry): entry is Record<string, unknown> =>
        typeof entry === 'object' && entry !== null && (entry as Record<string, unknown>).platform === 'DaikinOne',
    );
    fromFile = (platform ?? raw) as Partial<Credentials>;
  }

  const apiKey = str(args['api-key']) ?? process.env.DAIKIN_API_KEY ?? fromFile.apiKey;
  const email = str(args.email) ?? process.env.DAIKIN_EMAIL ?? fromFile.email;
  const integratorToken =
    str(args.token) ?? process.env.DAIKIN_INTEGRATOR_TOKEN ?? fromFile.integratorToken;
  const baseUrl = str(args['base-url']) ?? process.env.DAIKIN_BASE_URL ?? fromFile.baseUrl;

  const missing = [
    ['--api-key / DAIKIN_API_KEY', apiKey],
    ['--email / DAIKIN_EMAIL', email],
    ['--token / DAIKIN_INTEGRATOR_TOKEN', integratorToken],
  ].filter(([, value]) => !value);

  if (missing.length > 0) {
    throw new Error(
      `missing credentials: ${missing.map(([label]) => label).join(', ')}\n` +
        'In the Daikin One app: "Daikin One Care → home integration → get ' +
        'integration token" for the token, and the developer menu under the ' +
        'same section for the API key. See the README for the full walkthrough.',
    );
  }

  return {
    apiKey: apiKey as string,
    email: email as string,
    integratorToken: integratorToken as string,
    baseUrl,
  };
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

async function main(): Promise<number> {
  const args = parseArgs(process.argv.slice(2));

  if (args.help || args.h) {
    process.stdout.write(
      'usage: daikin-one-probe [--api-key KEY] [--email EMAIL] [--token TOKEN]\n' +
        '                        [--config path/to/config.json] [--base-url URL]\n',
    );
    return 0;
  }

  const credentials = loadCredentials(args);
  const client = new DaikinOneClient({
    ...credentials,
    log: {
      debug: (message) => process.stderr.write(`  · ${message}\n`),
      info: (message) => process.stderr.write(`  · ${message}\n`),
      warn: (message) => process.stderr.write(`  ! ${message}\n`),
      error: (message) => process.stderr.write(`  ✗ ${message}\n`),
    },
  });

  process.stderr.write(`Authenticating as ${credentials.email}…\n`);
  const devices = await client.getDevices();

  if (devices.length === 0) {
    process.stderr.write(
      '\nNo thermostats returned. The integrator token must belong to the ' +
        'account that owns the thermostat.\n',
    );
    return 1;
  }

  process.stderr.write(`\nFound ${devices.length} thermostat(s):\n`);
  for (const device of devices) {
    process.stderr.write(
      `  • ${device.name} (${device.id})` +
        `${device.model ? ` — ${device.model}` : ''}` +
        `${device.locationName ? ` @ ${device.locationName}` : ''}\n`,
    );
  }

  const dump: Record<string, unknown> = {};
  for (const device of devices) {
    process.stderr.write(`\nReading ${device.name}…\n`);
    dump[device.id] = await client.getDevice(device.id);
  }

  process.stderr.write('\nRaw device state:\n');
  process.stdout.write(`${JSON.stringify(dump, null, 2)}\n`);
  return 0;
}

main()
  .then((code) => {
    process.exitCode = code;
  })
  .catch((error: unknown) => {
    if (error instanceof DaikinAuthError) {
      process.stderr.write(`\nAuthentication failed: ${error.message}\n`);
    } else {
      process.stderr.write(
        `\n${error instanceof Error ? error.message : String(error)}\n`,
      );
    }
    process.exitCode = 1;
  });
