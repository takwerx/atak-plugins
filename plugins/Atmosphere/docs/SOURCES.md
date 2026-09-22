# Atmosphere source definitions

A weather source is a JSON file. The plugin reads it, and from then on that provider is
in the source list — no rebuild, no release, no code.

This exists because the provider an operation trusts changes faster than a plugin ships.
An agency switches from one model to another, a provider changes a URL, a unit needs a
key you have and we don't. All of those should be a file you edit on the device.

## Where files go

| Location | Meaning |
|---|---|
| `assets/wx_sources/*.json` inside the APK | the definitions we ship |
| `/sdcard/atak/Atmosphere/sources/*.json` | yours |

Both are read alphabetically, bundled first. **A file with the same `sourceId` as an
earlier one replaces it** — that is how you override a bundled source without touching
the plugin.

A file that fails validation is never loaded silently. The plugin shows what was wrong,
naming the file and the field.

## Enabling is separate from loading

Loading a definition does nothing on the network. A source only makes requests once it is
enabled in the **Sources** dialog, which lists the hosts that definition will contact.
Bundled sources ship disabled.

Coordinates are rounded before they are sent — three decimals (~110 m) by default, set
under **Position sent**.

## Top level

| Field | Required | Meaning |
|---|---|---|
| `schemaVersion` | yes | `1`. A file with any other value is refused with the version this build reads. |
| `sourceId` | yes | Lowercase letters, digits and hyphens. The override key. |
| `displayName` | yes | What appears in the source list. |
| `description` | no | One or two lines shown to the operator. |
| `attribution` | no, but expected | Shown whenever the source is used. Most free providers require it. |
| `termsUrl` | no | Where the provider's terms live. |
| `requestUrl` | yes¹ | The data request. HTTPS only. |
| `resolveUrl` | no | A first request that returns the data URL. See *Two-step providers*. |
| `resolvePath` | with `resolveUrl` | Where the data URL is in the resolve response. |
| `headers` | no | Extra request headers. `User-Agent` here is ignored — the plugin sets it. |
| `layout` | no | `columns` (default) or `records`. |
| `recordsPath` | with `records` | Path to the array of period objects. |
| `timePath` | no | Time of each series step. Absolute for `columns`, a field name for `records`. |
| `currentTimePath` | no | Time of the "current" block, if the provider has one. |
| `parameters` | yes | At least one variable. |

¹ Not required when `resolveUrl` is set: a two-step provider hands back its own data URL.

### Placeholders in `requestUrl` and `resolveUrl`

| Placeholder | Becomes |
|---|---|
| `{lat}` `{lon}` | the rounded query position |
| `{group:NAME}` | the selected parameter keys whose `requestGroups` include `NAME`, comma-joined |
| `{apiKey}` | reserved; key storage is not in this version |

Any other `{placeholder}` is a validation error — otherwise it would reach the provider
verbatim and come back as a 400 that looks like a network fault.

## Parameters

| Field | Required | Meaning |
|---|---|---|
| `key` | yes | The provider's own name for the variable; also what `{group:…}` sends. |
| `label` | yes | What the operator reads. |
| `quantity` | no | `temperature`, `speed`, `length`, `precipitation`, `pressure`, `angle`, `percent`. Omit for a plain number. |
| `unit` | no | The unit the provider returns, e.g. `celsius`, `mph`, `hPa`, `wmoUnit:km_h-1`. Omitted means already canonical. |
| `unitPath` | no | Path to a unit carried *in the response*, per value. Overrides `unit`. |
| `requestGroups` | no | Which `{group:…}` lists this key belongs in, e.g. `["current","hourly"]`. |
| `currentPath` | one of these two | Absolute path to the current value. |
| `seriesPath` | one of these two | Path in the series — absolute for `columns`, relative to each record for `records`. |
| `parse` | no | `number` (default), `leadingNumber` (`"10 mph"`, and the low end of `"10 to 15 mph"`), `compass` (`"NW"` → 315). |
| `defaultOn` | no | Selected the first time this source is used. |

Values are converted to canonical units on arrival — Celsius, m/s, metres, mm, hPa,
degrees — and converted again only for display. Switching metric/imperial/aviation never
refetches and never loses precision. A JSON `null` reads as *no value*, not as zero.

## The two layouts

**`columns`** — one array per variable, plus an array of times. Open-Meteo:

```json
{ "hourly": { "time": ["…T14:00", "…T15:00"], "temperature_2m": [21.5, 22.0] } }
```

`timePath` is `hourly.time`; a parameter's `seriesPath` is `hourly.temperature_2m`.

**`records`** — an array of period objects. NWS:

```json
{ "properties": { "periods": [
  { "startTime": "…T08:00:00-06:00", "temperature": 68, "temperatureUnit": "F" } ] } }
```

`recordsPath` is `properties.periods`, `timePath` is `startTime` (relative), and a
parameter's `seriesPath` is `temperature` (also relative).

Paths are dotted, with array indices: `properties.periods[0].temperature`. A path that
does not resolve yields "no reading" — never a crash.

## Two-step providers

Some providers make you ask where to ask. NWS turns a point into a gridpoint forecast URL:

```json
"resolveUrl":  "https://api.weather.gov/points/{lat},{lon}",
"resolvePath": "properties.forecastHourly"
```

The resolved URL is cached for a week; the forecast itself for fifteen minutes.

## A complete example

Hourly temperature and wind from Open-Meteo's ECMWF model, overriding nothing:

```json
{
  "schemaVersion": 1,
  "sourceId": "open-meteo-ecmwf",
  "displayName": "Open-Meteo (ECMWF)",
  "attribution": "Weather data by Open-Meteo.com (CC BY 4.0)",
  "requestUrl": "https://api.open-meteo.com/v1/ecmwf?latitude={lat}&longitude={lon}&hourly={group:hourly}&timezone=UTC&wind_speed_unit=ms",
  "layout": "columns",
  "timePath": "hourly.time",
  "parameters": [
    {
      "key": "temperature_2m",
      "label": "Temperature",
      "quantity": "temperature",
      "unit": "celsius",
      "requestGroups": ["hourly"],
      "seriesPath": "hourly.temperature_2m",
      "defaultOn": true
    },
    {
      "key": "wind_speed_10m",
      "label": "Wind",
      "quantity": "speed",
      "unit": "m/s",
      "requestGroups": ["hourly"],
      "seriesPath": "hourly.wind_speed_10m",
      "defaultOn": true
    }
  ]
}
```

Save it as `/sdcard/atak/Atmosphere/sources/open-meteo-ecmwf.json`, reopen the plugin, enable
it in **Sources**, and it is in the list.

## Validation rules worth knowing

- `http://` is refused outright. A plaintext forecast request leaks your position to
  anyone on the path, and no free provider is worth that.
- A parameter with neither `currentPath` nor `seriesPath` is refused: it could never
  produce a reading.
- A source declaring `requiresApiKey` loads but cannot be used — key storage is not in
  this version, and pretending otherwise would fail at request time instead.
- Files over 512 KB are ignored. A source definition is a few kilobytes.
