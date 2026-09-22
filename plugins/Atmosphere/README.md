ATAK Plugin — Atmosphere

Weather readout for ATAK whose data sources are configuration files, not compiled code,
and which keeps answering after the network drops.

_________________________________________________________________
PURPOSE AND CAPABILITIES

Forecast conditions for a point on the map — the map center or the operator's own
position — from a provider the operator chooses, shown in the units the operator
chooses, with the age of the reading on the face of it.

Capabilities:

  - Sources are JSON definition files. Each one describes a provider's URL, the
    variables it offers, where each value sits in the response and what unit it
    arrives in. Adding or replacing a provider is a file, not a plugin release.
  - Operator-supplied definitions are read from /sdcard/atak/Atmosphere/sources/ and
    override a bundled source of the same id, so a provider's URL change can be
    fixed in the field.
  - Two response layouts are supported, which covers the public APIs met so far:
    parallel arrays per variable (Open-Meteo) and an array of period objects
    (National Weather Service). Two-step providers that return their own data URL
    are supported through a resolve step.
  - Bundled with two sources, both key-free: Open-Meteo (global) and the US
    National Weather Service (CONUS and territories). Both ship DISABLED.
  - No source can make a network request until the operator enables it, and the
    dialog that enables it names the hosts it will contact.
  - Coordinates sent to a provider are rounded first, to a precision the operator
    sets (default three decimals, about 110 m). Nothing else about the device is
    sent.
  - Responses are cached on the device. A failed refresh falls back to the last
    good reading and says how old it is, rather than showing nothing.
  - Metric, imperial or aviation units, switched at any time. Values are stored
    canonically, so switching re-renders and never refetches.

_________________________________________________________________
STATUS

Version 0.1, in development. Not yet fielded and not yet submitted.

Source parsing, unit conversion, response mapping for both layouts and time
handling are covered by unit tests that run off-device (./gradlew
testCivDebugUnitTest). The release build has been produced and verified to keep
the plugin's own classes under proguard. On-device verification against ATAK-CIV
5.7 is outstanding, as is the radar overlay planned for a later version.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/atmosphere/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only, and only to providers the operator has
  explicitly enabled. A source definition that specifies a plaintext http URL is
  rejected when it is loaded, not at request time.

  With the bundled definitions and both sources enabled, the hosts contacted are:

    api.open-meteo.com    forecast request
    api.weather.gov       point lookup, then the gridpoint forecast URL it returns

  An operator-supplied definition can name a different host. The Sources dialog
  lists the hosts each definition will contact before it is enabled.

  No inbound ports. No listening sockets. No traffic to or from the TAK server;
  this version neither generates nor consumes CoT. With no source enabled the
  plugin makes no network calls at all.

  Requests carry a User-Agent identifying the plugin and its version, which
  several public providers require, and the rounded query coordinates. No
  callsign, device identifier, or TAK server detail is sent.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.7. Network connectivity to fetch a
  forecast; cached forecasts are readable offline.

_________________________________________________________________
EQUIPMENT SUPPORTED

  Any Android device supported by ATAK. No additional or external hardware, no
  sensors, no peripherals.

_________________________________________________________________
COMPILATION

  Standard ATAK plugin build. The ATAK CIV SDK is not included in this
  repository; set sdk.path in local.properties to an unpacked SDK (see
  template.local.properties).

    ./gradlew assembleCivDebug        debug APK, signed with the SDK dev key
    ./gradlew assembleCivRelease      release APK, minified
    ./gradlew testCivDebugUnitTest    unit tests, no device needed

_________________________________________________________________
DEVELOPER NOTES

  Source definitions are documented in docs/SOURCES.md, with a worked example.
  The bundled definitions in app/src/main/assets/wx_sources/ are the reference
  implementations of the schema and are asserted clean by the test suite.

  Design rules this plugin holds to:

    - gov.tak.api.* is preferred throughout. The only calls into
      com.atakmap.android.* internals are isolated in compat/MapCompat.java, so
      an ATAK upgrade has one file to check.
    - No lambdas or method references in shipping code. The SDK documents them
      breaking under release proguard.
    - Every outbound request passes through net/EgressPolicy.java, which owns
      source enablement, coordinate rounding and the User-Agent. There is no
      second path to the network.
    - Preferences are stored against ATAK's context, not the plugin context; a
      plugin context has no writable shared_prefs directory.
    - No annotation processors and no database. A cached forecast is a small JSON
      file.

  Attribution for prior art is in NOTICE.md.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

Atmosphere is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify Atmosphere and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use Atmosphere, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers Atmosphere's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
