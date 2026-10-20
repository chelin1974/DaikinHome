# DaikinHome

Puts a Daikin One thermostat into Apple Home.

Daikin has not announced native HomeKit or Matter support for the One family, and its
documentation still points at Daikin's own app and cloud. But Daikin does publish a
[Cloud Open API](https://daikinone.com/openapi/) covering the **One+**, **One Touch** and
**One Lite** thermostats, with read *and* write access. This project sits on that API and
speaks the HomeKit Accessory Protocol directly, via
[HAP-Java](https://github.com/hap-java/HAP-Java) — the library behind
[openHAB's HomeKit add-on](https://www.openhab.org/addons/bindings/homekit/).

```
Daikin One  →  Daikin Cloud Open API  →  this bridge  →  Apple Home / Siri
```

No Homebridge, no Home Assistant, no Node.js. No hardware changes, no replacing the
thermostat, and the Daikin app keeps working exactly as before.

## Which backend: Open API or SkyportHome

In 2026 Daikin renamed the Daikin One Home app to **SkyportHome** and started moving accounts,
one at a time, to a new cloud (`api.skyportcloud.com`). A moved account can no longer log in
to the old endpoints: with the right password it gets `400 {"message":"NotAuthorizedException"}`,
which looks exactly like a wrong password. Homebridge's `homebridge-daikin-oneplus` fails this
way on moved accounts.

The bridge supports both clouds. Pick with `"backend"` in the config:

| `backend` | Logs in with | Use it when |
| --- | --- | --- |
| `skyport` | the email and password you use in the SkyportHome app | your account is on SkyportHome |
| `openapi` *(default)* | API key + email + integrator token | the documented Open API works for your account |

**Not sure which you're on?** Run this (the leading space keeps it out of your shell history):

```bash
 curl -s -X POST https://api.skyportcloud.com/users/auth/login \
   -H "Content-Type: application/json" \
   -d '{"email":"you@example.com","password":"YOUR_PASSWORD"}'
```

A reply containing `AccessToken` means your account is on SkyportHome: use `skyport`.
`Incorrect e-mail or password.` means a typo; anything else, try `openapi`.

The `skyport` backend uses an API that Daikin has not published. Its endpoints and payload
format come from the SkyportHome web client, as mapped in
[apetrycki/daikinskyport#156](https://github.com/apetrycki/daikinskyport/issues/156). It can
change without notice. See [SkyportHome backend](#skyporthome-backend) for what it does and
doesn't cover.

## Two ways to run it

Same bridge, same behaviour. Pick by what you need around it.

| | **CLI** (`cli/`) | **Spring Boot service** (`web/`) |
| --- | --- | --- |
| Jar | `cli/target/daikin-home.jar` (~9 MB) | `web/target/daikin-home-web.jar` (~32 MB) |
| Config | a JSON file | `application.yml`, env vars, CLI args |
| HomeKit | yes | yes |
| REST API | — | yes |
| Health checks / metrics | — | `/actuator/health` |
| Use it when | you just want the thermostat in Home | you want it reachable from your own automations or monitoring |

Both build from the same `core/` module, so a fix in the Daikin client or the HomeKit
mapping applies to both.

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
- **A machine that stays on**, on the same LAN as your Home hub — a Raspberry Pi, a NAS, a
  spare Mac. HomeKit finds the bridge over mDNS, so it has to be on the same network segment
  as your Apple TV or HomePod. Don't put it behind Docker's default bridge network unless
  you use `--network host`.
- Remote access (away from home) needs a Home Hub, exactly as with any HomeKit accessory.

## Getting your Daikin credentials

**`skyport` backend:** nothing to fetch. Use the email and password you sign in to the
SkyportHome app with. Start from `daikin.config.skyport.example.json`.

**`openapi` backend:** you need three things: an **API key**, the **email address** on your Daikin One account, and
an **integrator token**. All three come from the Daikin One Home app (the same app is branded
Amana or Goodman on those systems, and Daikin's own docs call it the "SkyportHome" app — the
menus are identical).

**1. Request the integrator token.** In the app: **Daikin One Care → home integration → get
integration token**. You have to accept the Integration Token Terms and re-enter your account
password. The token is tied to the account that *owns* the thermostat — if someone else in
the household owns it, the token has to be generated on their account.

**2. Enable the developer menu**, which is where the API key lives:

- **iOS** — open the system **Settings** app, scroll to the Daikin One app, and switch on
  **developer menu**.
- **Android** — in the app, go to **Daikin One Care → home integration** and tap the page
  description **five times**.

**3. Get the API key.** Back in the app: **Daikin One Care → home integration → developer**.
Accept the Daikin B2B Terms and the Open API License Terms, and the API key is shown there.

Keep both the API key and the integrator token secret — together they grant full control of
your HVAC system.

## Build

```bash
mvn package
```

Produces both jars. Check your credentials before going anywhere near HomeKit:

```bash
cp daikin.config.example.json daikin.config.json
$EDITOR daikin.config.json
java -jar cli/target/daikin-home.jar --config daikin.config.json --probe
```

That authenticates, lists your thermostats, and dumps the raw JSON each one returns. Keep
that dump — Daikin's payload varies by model and firmware, and it's the fastest way to tell
"the bridge has a bug" apart from "your thermostat doesn't report that field".

## Run: CLI

```bash
java -jar cli/target/daikin-home.jar --config daikin.config.json
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

`daikin-home.service` is a ready-made systemd unit — copy it to `/etc/systemd/system/`,
adjust the paths, and `systemctl enable --now daikin-home`.

### Run at login on a Mac (launchd)

To keep the bridge running in the background, starting at login and restarting if it crashes,
install it as a launchd agent. Stop any copy running in a terminal first: two can't share the
HomeKit port.

**1. Create the agent.** From the repo folder, after `mvn package`, paste this whole block.
It records where your Java is and writes the agent definition:

```bash
JAVA="$(which java)"
DIR="$(pwd)"
cat > ~/Library/LaunchAgents/io.github.chelin1974.daikinhome.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>io.github.chelin1974.daikinhome</string>
  <key>ProgramArguments</key>
  <array>
    <string>$JAVA</string>
    <string>-jar</string>
    <string>$DIR/cli/target/daikin-home.jar</string>
    <string>--config</string>
    <string>$DIR/daikin.config.json</string>
  </array>
  <key>WorkingDirectory</key><string>$DIR</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$HOME/Library/Logs/daikin-home.log</string>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/daikin-home.log</string>
</dict>
</plist>
EOF
```

**2. Start it.**

```bash
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/io.github.chelin1974.daikinhome.plist
```

**3. Pair, if you haven't yet.** The setup code is in the log:

```bash
tail -f ~/Library/Logs/daikin-home.log
```

Press Ctrl-C to stop watching; the agent keeps running.

| To | Run |
| --- | --- |
| Restart (after a config change or `mvn package`) | `launchctl kickstart -k gui/$(id -u)/io.github.chelin1974.daikinhome` |
| Stop and remove | `launchctl bootout gui/$(id -u)/io.github.chelin1974.daikinhome` |
| Check it's running | `launchctl print gui/$(id -u)/io.github.chelin1974.daikinhome \| grep state` |
| Read the log | `tail -50 ~/Library/Logs/daikin-home.log` |

The agent runs only while you're logged in and the Mac is awake. A sleeping laptop, lid
closed included, takes the accessories to "No Response" until it wakes. For an always-on
setup, use a Mac mini or a Linux box with the systemd unit above.

### CLI configuration

| Setting | Default | Notes |
| --- | --- | --- |
| `backend` | `openapi` | `skyport` or `openapi`. A `password` with no `apiKey` selects `skyport`. |
| `email`, `password` | — | Required for `skyport`. The SkyportHome app login. |
| `apiKey`, `email`, `integratorToken` | — | Required for `openapi`. |
| `pollIntervalSeconds` | `180` | Values below 180 are raised back to 180 (see below). |
| `showIndoorHumidity` | `true` | Adds humidity to the thermostat tile. |
| `showOutdoorTemperatureSensor` | `true` | Separate sensor accessory. |
| `requestTimeoutSeconds` | `20` | Per-request timeout. |
| `bridgeName` | `Daikin One` | Shown while pairing. |
| `port` | `51826` | HomeKit listen port. |
| `bindAddress` | chosen automatically | LAN IP to advertise HomeKit on, e.g. `192.168.1.20`. Set it if the Home app can't find the bridge. |
| `storagePath` | next to the config file | Pairing state — see below. |
| `baseUrl` | Daikin's endpoint | Override only if Daikin moves the API. |

## Run: Spring Boot service

```bash
# SkyportHome account
DAIKIN_BACKEND=skyport DAIKIN_EMAIL=you@example.com DAIKIN_PASSWORD=... \
  java -jar web/target/daikin-home-web.jar

# Open API
DAIKIN_API_KEY=... DAIKIN_EMAIL=you@example.com DAIKIN_INTEGRATOR_TOKEN=... \
  java -jar web/target/daikin-home-web.jar
```

Everything the CLI does, plus an HTTP API on port 8080. Configuration comes from
`application.yml`, environment variables, or command-line arguments — Spring's usual
precedence — under the `daikin.*` prefix:

```yaml
daikin:
  backend: skyport             # or openapi
  email: you@example.com
  password: ...                # skyport
  api-key: ...                 # openapi
  integrator-token: ...        # openapi
  poll-interval-seconds: 180
  bridge-name: Daikin One
  homekit-port: 51826          # not server.port — that's the HTTP port
  storage-path: daikin-home.state.json
server:
  port: 8080
```

`daikin-home-web.service` is the matching systemd unit.

### REST API

| Method | Path | Does |
| --- | --- | --- |
| `GET` | `/api/thermostats` | Every thermostat with its last polled state |
| `GET` | `/api/thermostats/{id}` | One thermostat |
| `PUT` | `/api/thermostats/{id}` | Change mode and/or setpoints |
| `POST` | `/api/thermostats/{id}/refresh` | Force a read instead of waiting for the next poll |
| `GET` | `/api/bridge` | HomeKit status: running, paired, setup code |
| `GET` | `/actuator/health` | Health, including the bridge |

Modes are strings, not Daikin's integers: `off`, `heat`, `cool`, `auto`, `emergencyHeat`.

```bash
curl -X PUT localhost:8080/api/thermostats/{id} \
  -H 'Content-Type: application/json' \
  -d '{"mode":"heat","heatSetpoint":21.5}'
```

Every field of the body is optional. The response is the thermostat's state **after** the
change, which may not be what you asked for — setpoints are clamped into the thermostat's own
range and kept apart by its minimum separation. Ask for a cool setpoint of 99 and you will
get back 32.2.

Failures are [RFC 7807 problem details](https://www.rfc-editor.org/rfc/rfc7807) with honest
status codes: `400` for a bad request, `404` for an unknown thermostat, `502` when the Daikin
cloud rejects *us* (including bad credentials — that is not the caller's fault, so it is not
a 401), `504` when the cloud is unreachable, and `429` passed through when Daikin rate limits
the integration.

The service starts even when Daikin is unreachable. It comes up, reports `DOWN` on
`/actuator/health` with the reason, and retries in the background — a service you cannot
reach because its dependency is down is a service you cannot diagnose. Bad credentials are
the exception: they will never fix themselves, so the retry loop stops and health says
`retrying: false`.

## The state file

`daikin-home.state.json` holds the bridge's HomeKit identity and every paired controller's
key. **Back it up, and don't delete it.** Losing it means iOS finds a stranger where its
bridge used to be, and every accessory goes "No Response" until you remove and re-add the
bridge. It also contains the setup code in plain text, so it's written owner-only — treat it
like a password file.

## How it behaves

**Polling is slow, on purpose.** Daikin documents a floor of one request per thermostat every
three minutes and rate limits integrations that exceed it. The bridge enforces that floor
regardless of what the config asks for. The practical consequence: a change made *at the wall
unit* can take up to one polling interval to appear in the Home app. `POST /refresh` is the
escape hatch.

**Changes from Home are immediate.** They are sent right away and the Home app shows the new
value instantly rather than snapping back — the bridge serves an optimistic view until the
cloud confirms (Daikin needs ~15 seconds), then re-reads the thermostat. If the write is
rejected, the log says why and the tile reverts to the thermostat's real state.

**Writes are batched.** Changing the mode and then the temperature is one API call, not two —
HomeKit fires several characteristic writes for one gesture.

**Setpoints are kept legal.** The thermostat requires the heat setpoint to sit below the cool
setpoint by at least its `setpointDelta`, with both inside
`setpointMinimum`/`setpointMaximum`. Drag one threshold into the other in Home and the bridge
moves the *other* one out of the way rather than sending a request the thermostat would
reject.

**Emergency heat is preserved.** HomeKit has no emergency-heat mode, so a system in em-heat
reads as *Heat*. Re-selecting Heat in the Home app will not silently downgrade it; picking any
other mode leaves em-heat as you'd expect.

## SkyportHome backend

What it does: logs in with your app email and password (logging in again when the hour-long
session ends), lists thermostats, reads each one from `GET /devices/{id}/live`, and writes
mode and setpoints with `POST /cmd/{id}/msp`. The same pacing, retry and rate-limit handling
as the Open API client applies, and a rejected password is never retried, so a typo cannot
lock the account.

How it translates:

- **Temperatures** arrive in Daikin's raw "VUT" units, °C = 0.5 × raw − 40, so the thermostat
  works in half degrees. Raw 254 and 255 mean a shorted or disconnected sensor and are ignored.
- **Equipment status** maps idle and waiting to *Idle*, heating to *Heating*, and cooling and
  free cooling to *Cooling*. An error code falls back to the selected mode.
- **Humidity** of 0 is treated as "no sensor", since real indoor air is never 0 %.
- **Setpoint limits** use the One family's 50–90 °F range, with a 1 °C minimum gap between
  heat and cool. If rounding to half degrees would close that gap, the cool setpoint moves up.

Gaps specific to this backend:

- **Outdoor temperature:** the field name in the live payload isn't confirmed yet. The bridge
  checks the likely names; if none is present, it skips the outdoor sensor rather than showing
  0°. If your system has one, run `--probe` and look in the `skyport` block for it.
- **Emergency heat** is sent as plain heat, because the command's em-heat value is unknown.
- **Two-factor authentication** isn't supported. If it's on for the account, login fails with
  a message saying so.
- **Your password sits in the config file in plain text.** Keep `daikin.config.json`
  owner-only (`chmod 600`); it's already in `.gitignore`.

## Known gaps

- **Emergency heat cannot be turned on** from Home — only preserved. HomeKit has no matching
  mode. The REST API accepts `emergencyHeat` if you want it.
- **Fan-only shows as Off.** When `equipmentStatus` is fan-only, HomeKit's current state has
  nowhere to put it. Overcool-to-dehumidify does show as Cooling, because the compressor
  genuinely is cooling.
- **No schedule or fan control.** The Daikin API exposes `scheduleEnabled` and `fanCirculate`,
  but the write behaviour is not verified, so this bridge does not touch them.
- **No air quality, zoning, or humidifier control.** The One+ reports more than this bridge
  surfaces. Run `--probe`; if your thermostat reports something useful, it's a small addition.
- **The REST API has no authentication.** It is meant for a trusted LAN. Do not expose port
  8080 to the internet — anyone who reaches it controls your heating.
- **HAP-Java's last release on Maven Central is 2.0.7 (March 2023).** It is stable and in
  production use via openHAB, but it is not moving quickly. Worth knowing before you build on
  it.

## Troubleshooting

**`400 {"message":"NotAuthorizedException"}` with the right password** — your account has
moved to SkyportHome. Switch to `"backend": "skyport"`.

**"the SkyportHome cloud rejected the login"** — wrong email or password for the `skyport`
backend. Sign out and back in to the SkyportHome app to confirm them.

**Running next to Homebridge on the same machine** — both default to HomeKit port 51826.
Give this bridge another `port` (the SkyportHome example config uses 51827).

**"Daikin API rejected the credentials"** — the API key, email or integrator token is wrong,
or the token was generated on an account that doesn't own the thermostat. Verify with
`--probe` first.

**No thermostats found** — same cause, almost always the account-ownership one.

**The bridge doesn't appear in the Home app** — check the `advertised at` line in the log
first. If it shows `127.0.0.1` or an address that isn't on your home network, set
`bindAddress` to the machine's LAN IP (`ipconfig getifaddr en0` on a Mac). Otherwise mDNS
isn't reaching your phone. Check the
bridge is on the same network segment (not a guest VLAN), and if it's in Docker, that it's
using host networking.

**"Daikin API rate limited this integration"** — something is polling too fast. Check whether
another integration is using the same API key; the limit applies to the key, not to this
bridge.

**Accessories show "No Response" after a restart** — the state file was lost or a different
one is being used. Check `storagePath`.

## Development

```bash
mvn test        # runs the tests in all three modules
mvn package     # both runnable jars
```

| Module | Contains |
| --- | --- |
| `core` | The Daikin client, the HomeKit mapping, the bridge. No Spring, no CLI. |
| `cli` | JSON config, `main`, the probe tool. |
| `web` | Spring Boot: REST controllers, config properties, health indicator. |

| Path | What it does |
| --- | --- |
| `core/…/Mapping.java` | Pure Daikin ↔ HomeKit translation. No network, no state — **start here** |
| `core/…/daikin/DaikinCloud` | The interface both backends implement |
| `core/…/daikin/DaikinOneClient` | Open API client: tokens, retries, pacing, listing normalisation |
| `core/…/daikin/SkyportCloudClient` | SkyportHome client: email/password session, same pacing and retries |
| `core/…/daikin/SkyportPayload` | Pure Skyport ↔ Open API translation (VUT units, status codes) |
| `core/…/homekit/DaikinThermostat` | The HomeKit accessory: reads, debounced writes, subscriptions |
| `core/…/homekit/FileAuthInfo` | Pairing state on disk |
| `core/…/DaikinHomeBridge` | Startup, discovery, polling. Implements `ThermostatGateway` |

The core tests run against a real local HTTP server standing in for the Daikin cloud
(`FakeDaikinCloud`, and `FakeSkyportCloud` for the SkyportHome backend), so header handling, status codes, URL encoding and JSON parsing are
genuinely exercised rather than mocked. The web tests drive the controllers through MockMvc
against an in-memory `ThermostatGateway`, which still runs the real clamping logic — so they
test behaviour, not a stub that agrees with everything.

## License

MIT
