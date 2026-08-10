# Daikin One HomeKit bridge (Java)

Puts a Daikin One thermostat into Apple Home. No Homebridge, no Home Assistant, no
Node.js — one runnable jar that appears to iOS as a HomeKit bridge.

```
Daikin One  →  Daikin Cloud Open API  →  this bridge  →  Apple Home / Siri
```

It speaks the HomeKit Accessory Protocol directly, via
[HAP-Java](https://github.com/hap-java/HAP-Java) — the same library behind
[openHAB's HomeKit add-on](https://www.openhab.org/addons/bindings/homekit/).

## What shows up in Home

| Home app | Backed by |
| --- | --- |
| Thermostat tile — Off / Heat / Cool / Auto | `mode` |
| Current temperature | `tempIndoor` |
| Target temperature, and heat/cool thresholds in Auto | `heatSetpoint` / `coolSetpoint` |
| Heating / Cooling / Idle status | `equipmentStatus` |
| Indoor humidity | `humIndoor` |
| Outdoor temperature sensor *(optional)* | `tempOutdoor` |

Everything works with Siri and with Home automations.

## Requirements

- **Java 21 or newer.**
- **A machine that stays on**, on the same LAN as your Home hub — a Raspberry Pi, a NAS,
  a spare Mac. HomeKit finds the bridge over mDNS, so it has to be on the same network
  segment as your Apple TV or HomePod. Don't put it behind Docker's default bridge network
  unless you use `--network host`.
- Remote access (away from home) needs a Home Hub, exactly as with any HomeKit accessory.

## Getting your Daikin credentials

You need three things: an **API key**, the **email address** on your Daikin One account,
and an **integrator token**. All three come from the Daikin One Home app (the same app is
branded Amana or Goodman on those systems, and Daikin's own docs call it the "SkyportHome"
app — the menus are identical).

**1. Request the integrator token.** In the app: **Daikin One Care → home integration →
get integration token**. You have to accept the Integration Token Terms and re-enter your
account password. The token is tied to the account that *owns* the thermostat — if someone
else in the household owns it, the token has to be generated on their account.

**2. Enable the developer menu**, which is where the API key lives:

- **iOS** — open the system **Settings** app, scroll to the Daikin One app, and switch on
  **developer menu**.
- **Android** — in the app, go to **Daikin One Care → home integration** and tap the page
  description **five times**.

**3. Get the API key.** Back in the app: **Daikin One Care → home integration →
developer**. Accept the Daikin B2B Terms and the Open API License Terms, and the API key is
shown there.

Keep both the API key and the integrator token secret — together they grant full control of
your HVAC system.

## Build and run

```bash
mvn package                      # produces target/daikin-home.jar
cp daikin.config.example.json daikin.config.json
$EDITOR daikin.config.json       # paste in your three credentials
```

Check the credentials before going anywhere near HomeKit:

```bash
java -jar target/daikin-home.jar --config daikin.config.json --probe
```

That authenticates, lists your thermostats, and dumps the raw JSON each one returns. Keep
that dump — Daikin's payload varies by model and firmware, and it's the fastest way to tell
"the bridge has a bug" apart from "your thermostat doesn't report that field".

Then start the bridge:

```bash
java -jar target/daikin-home.jar --config daikin.config.json
```

It prints a setup code at startup:

```
  +------------------------------------------+
  |  Add the bridge in the Home app using     |
  |  setup code:  379-96-555                 |
  +------------------------------------------+
```

In the Home app: **Add Accessory → More options… → pick the bridge → enter that code**. The
thermostat and its outdoor sensor arrive together.

To keep it running, `daikin-home.service` is a ready-made systemd unit — copy it to
`/etc/systemd/system/`, adjust the paths, and `systemctl enable --now daikin-home`.

### Configuration

| Setting | Default | Notes |
| --- | --- | --- |
| `apiKey`, `email`, `integratorToken` | — | Required. |
| `pollIntervalSeconds` | `180` | Values below 180 are raised back to 180 (see below). |
| `showIndoorHumidity` | `true` | Adds humidity to the thermostat tile. |
| `showOutdoorTemperatureSensor` | `true` | Separate sensor accessory. |
| `requestTimeoutSeconds` | `20` | Per-request timeout. |
| `bridgeName` | `Daikin One` | Shown while pairing. |
| `port` | `51826` | HomeKit listen port. |
| `storagePath` | next to the config file | Pairing state — see below. |
| `baseUrl` | Daikin's endpoint | Override only if Daikin moves the API. |

### The state file

`daikin-home.state.json` holds the bridge's HomeKit identity and every paired controller's
key. **Back it up, and don't delete it.** Losing it means iOS finds a stranger where its
bridge used to be, and every accessory goes "No Response" until you remove and re-add the
bridge. It also contains the setup code in plain text, so it's written owner-only — treat
it like a password file.

## How it behaves

**Polling is slow, on purpose.** Daikin documents a floor of one request per thermostat
every three minutes and rate limits integrations that exceed it. The bridge enforces that
floor regardless of what the config asks for. The practical consequence: a change made *at
the wall unit* can take up to one polling interval to appear in the Home app.

**Changes from Home are immediate.** They are sent right away and the Home app shows the
new value instantly rather than snapping back — the bridge serves an optimistic view until
the cloud confirms (Daikin needs ~15 seconds), then re-reads the thermostat. If the write is
rejected, the log says why and the tile reverts to the thermostat's real state.

**Writes are batched.** Changing the mode and then the temperature is one API call, not two
— HomeKit fires several characteristic writes for one gesture.

**Setpoints are kept legal.** The thermostat requires the heat setpoint to sit below the
cool setpoint by at least its `setpointDelta`, with both inside
`setpointMinimum`/`setpointMaximum`. Drag one threshold into the other in Home and the
bridge moves the *other* one out of the way rather than sending a request the thermostat
would reject.

**Emergency heat is preserved.** HomeKit has no emergency-heat mode, so a system in em-heat
reads as *Heat*. Re-selecting Heat in the Home app will not silently downgrade it; picking
any other mode leaves em-heat as you'd expect.

## Known gaps

- **Emergency heat cannot be turned on** from Home — only preserved. HomeKit has no
  matching mode.
- **Fan-only shows as Off.** When `equipmentStatus` is fan-only, HomeKit's current state has
  nowhere to put it. Overcool-to-dehumidify does show as Cooling, because the compressor
  genuinely is cooling.
- **No schedule or fan control.** The Daikin API exposes `scheduleEnabled` and
  `fanCirculate`, but the write behaviour is not verified, so this bridge does not touch
  them.
- **No air quality, zoning, or humidifier control.** The One+ reports more than this bridge
  surfaces. Run `--probe`; if your thermostat reports something useful, it's a small
  addition.
- **HAP-Java's last release on Maven Central is 2.0.7 (March 2023).** It is stable and in
  production use via openHAB, but it is not moving quickly. Worth knowing before you build
  on it.

## Troubleshooting

**"Daikin API rejected the credentials"** — the API key, email or integrator token is wrong,
or the token was generated on an account that doesn't own the thermostat. Verify with
`--probe` first.

**No thermostats found** — same cause, almost always the account-ownership one.

**The bridge doesn't appear in the Home app** — mDNS isn't reaching your phone. Check the
bridge is on the same network segment (not a guest VLAN), and if it's in Docker, that it's
using host networking.

**"Daikin API rate limited this integration"** — something is polling too fast. Check
whether another integration is using the same API key; the limit applies to the key, not to
this bridge.

**Accessories show "No Response" after a restart** — the state file was lost or a different
one is being used. Check `storagePath`.

## Development

```bash
mvn test        # 70 unit and behavioural tests
mvn package     # runnable jar at target/daikin-home.jar
```

Layout:

| Path | What it does |
| --- | --- |
| `daikin/DaikinOneClient` | HTTP client: tokens, retries, pacing, listing normalisation |
| `Mapping` | Pure Daikin ↔ HomeKit translation. No network, no state — start here |
| `homekit/DaikinThermostat` | The HomeKit accessory: reads, debounced writes, subscriptions |
| `homekit/FileAuthInfo` | Pairing state on disk |
| `DaikinHomeBridge` | Startup, discovery, polling |

The tests run against a real local HTTP server standing in for the Daikin cloud
(`FakeDaikinCloud`), so header handling, status codes, URL encoding and JSON parsing are
genuinely exercised rather than mocked.
