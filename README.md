# DaikinHome

Puts a Daikin One thermostat into Apple Home.

Daikin has not announced native HomeKit or Matter support for the One family, and
its documentation still points at Daikin's own app and cloud. But Daikin does
publish a [Cloud Open API](https://daikinone.com/openapi/) covering the **One+**,
**One Touch** and **One Lite** thermostats, with read *and* write access. Both
implementations here sit on that API.

No hardware changes, no replacing the thermostat, and the Daikin app keeps
working exactly as before.

## Two implementations — pick one

They do the same job. Choose by what you want to run and what you can maintain.

### [`java/`](java/README.md) — standalone Java bridge

```
Daikin One  →  Daikin Open API  →  daikin-home.jar  →  Apple Home / Siri
```

One runnable jar. Speaks the HomeKit Accessory Protocol directly via
[HAP-Java](https://github.com/hap-java/HAP-Java), the library behind openHAB's HomeKit
add-on. Needs Java 21 and a machine that stays on. No Node.js, no Homebridge.

**Start here if you'd rather read and change Java than TypeScript.**

### Homebridge plugin (this directory) — TypeScript

```
Daikin One  →  Daikin Open API  →  Homebridge  →  Apple Home / Siri
```

A conventional Homebridge plugin, with a config form in the Homebridge UI. Worth it if you
already run Homebridge, or want to sit alongside other plugins. On Homebridge 2.x it can
also be exposed over Matter.

It has two extras the Java bridge doesn't: an outdoor *humidity* sensor, and experimental
schedule/fan switches.

The rest of this file documents the Homebridge plugin. For the Java bridge, see
**[java/README.md](java/README.md)**.

---

## What shows up in Home

| Home app | Backed by |
| --- | --- |
| Thermostat tile — Off / Heat / Cool / Auto | `mode` |
| Current temperature | `tempIndoor` |
| Target temperature, and heat/cool thresholds in Auto | `heatSetpoint` / `coolSetpoint` |
| Heating / Cooling / Idle status | `equipmentStatus` |
| Indoor humidity | `humIndoor` |
| Outdoor temperature sensor *(optional)* | `tempOutdoor` |
| Outdoor humidity sensor *(optional, off by default)* | `humOutdoor` |
| Schedule switch *(optional, experimental)* | `scheduleEnabled` |
| Fan circulate switch *(optional, experimental)* | `fanCirculate` |

Everything works with Siri and with Home automations. On Homebridge 2.x you can
also expose the bridge over Matter, so the thermostat reaches Home without the
HomeKit-only pairing path.

## Getting your Daikin credentials

You need three things: an **API key**, the **email address** on your Daikin One
account, and an **integrator token**. All three come from the Daikin One Home app
(the same app is branded Amana or Goodman on those systems, and Daikin's own docs
call it the "SkyportHome" app — the menus are identical).

**1. Request the integrator token.** In the app: **Daikin One Care → home
integration → get integration token**. You have to accept the Integration Token
Terms and re-enter your account password. The token is tied to the account that
*owns* the thermostat — if someone else in the household owns it, the token has to
be generated on their account.

**2. Enable the developer menu**, which is where the API key lives:

- **iOS** — open the system **Settings** app, scroll to the Daikin One app, and
  switch on **developer menu**.
- **Android** — in the app, go to **Daikin One Care → home integration** and tap
  the page description **five times**.

**3. Get the API key.** Back in the app: **Daikin One Care → home integration →
developer**. Accept the Daikin B2B Terms and the Open API License Terms, and the
API key is shown there.

Keep both the API key and the integrator token secret — together they grant full
control of your HVAC system.

### Check them before touching Homebridge

The plugin ships a probe tool that authenticates, lists your thermostats and
dumps the raw JSON each one returns:

```bash
npx daikin-one-probe --api-key YOUR_KEY --email you@example.com --token YOUR_TOKEN
```

It also reads `DAIKIN_API_KEY` / `DAIKIN_EMAIL` / `DAIKIN_INTEGRATOR_TOKEN` from
the environment, or `--config path/to/config.json` (either a bare credentials file
or a Homebridge `config.json` containing a `DaikinOne` platform block).

That JSON dump is worth keeping. Daikin's payload varies by model and firmware,
and it is the fastest way to tell "the plugin has a bug" apart from "your
thermostat doesn't report that field".

## Install

```bash
npm install -g homebridge-daikin-one
```

Then add the platform in Homebridge (the Homebridge UI renders a form for this):

```json
{
  "platforms": [
    {
      "platform": "DaikinOne",
      "name": "Daikin One",
      "apiKey": "YOUR_API_KEY",
      "email": "you@example.com",
      "integratorToken": "YOUR_INTEGRATOR_TOKEN"
    }
  ]
}
```

Restart Homebridge. Every thermostat on the account is discovered automatically —
there is nothing to list by hand.

### Options

| Setting | Default | Notes |
| --- | --- | --- |
| `apiKey`, `email`, `integratorToken` | — | Required. |
| `pollIntervalSeconds` | `180` | Values below 180 are raised back to 180 (see below). |
| `showIndoorHumidity` | `true` | Adds humidity to the thermostat tile. |
| `showOutdoorTemperatureSensor` | `true` | Separate sensor accessory. |
| `showOutdoorHumiditySensor` | `false` | Separate sensor accessory. |
| `experimentalScheduleSwitch` | `false` | Switch for `scheduleEnabled`. |
| `experimentalFanSwitch` | `false` | Switch for `fanCirculate`. |
| `requestTimeoutSeconds` | `20` | Per-request timeout. |
| `baseUrl` | Daikin's endpoint | Override only if Daikin moves the API. |
| `debug` | `false` | Logs API activity at info level. |

## How it behaves

**Polling is slow, on purpose.** Daikin documents a floor of one request per
thermostat every three minutes and rate limits integrations that exceed it. The
plugin enforces that floor regardless of what the config asks for. The practical
consequence: a change made *at the wall unit* can take up to one polling interval
to appear in the Home app.

**Changes from Home are immediate.** They are sent right away and the Home app
shows the new value instantly rather than snapping back — the plugin serves an
optimistic view until the cloud confirms (Daikin needs ~15 seconds), then re-reads
the thermostat. If the write is rejected, the log says why and the tile reverts to
the thermostat's real state.

**Writes are batched.** Changing the mode and then the temperature is one API
call, not two — HomeKit fires several characteristic writes for one gesture.

**Setpoints are kept legal.** The thermostat requires the heat setpoint to sit
below the cool setpoint by at least its `setpointDelta`, with both inside
`setpointMinimum`/`setpointMaximum`. Drag one threshold into the other in Home and
the plugin moves the *other* one out of the way rather than sending a request the
thermostat would reject.

**Emergency heat is preserved.** HomeKit has no emergency-heat mode, so a system
in em-heat reads as *Heat*. Re-selecting Heat in the Home app will not silently
downgrade it; picking any other mode leaves em-heat as you'd expect.

## Known gaps

- **Emergency heat cannot be turned on** from Home — only preserved. HomeKit has
  no matching mode.
- **Fan-only shows as Off.** When `equipmentStatus` is fan-only, HomeKit's current
  state has nowhere to put it. Overcool-to-dehumidify does show as Cooling,
  because the compressor genuinely is cooling.
- **The schedule and fan switches are experimental.** They write `scheduleEnabled`
  and `fanCirculate` alongside the setpoints. That matches the documented field
  names, but it is not verified against every firmware, which is why both default
  to off. If a switch snaps back, the Homebridge log will have the API's
  rejection — please open an issue with it.
- **No air quality, zoning, or humidifier control.** The One+ reports more than
  this plugin surfaces. Run the probe tool; if your thermostat reports something
  useful, it's a small addition.

## Troubleshooting

**"Daikin rejected the credentials"** — the API key, email or integrator token is
wrong, or the token was generated on an account that doesn't own the thermostat.
Verify with `npx daikin-one-probe` first.

**No thermostats found** — same cause, almost always the account-ownership one.

**"Daikin API rate limited this integration"** — something is polling too fast.
Check whether another integration is using the same API key; the limit applies to
the key, not to this plugin.

**A value reads 0 or is missing** — run the probe tool and look for the field in
the raw JSON. If Daikin doesn't report it for your model, the plugin can't invent
it.

## Development

```bash
npm install
npm run build     # compile to dist/
npm test          # unit + end-to-end tests
npm run lint      # type-check only
```

The test suite includes an end-to-end test that runs the real platform against a
local HTTP server standing in for the Daikin cloud, with real HAP service objects
— so accessory wiring, write batching and setpoint clamping are all covered
without touching your thermostat.

## License

MIT
