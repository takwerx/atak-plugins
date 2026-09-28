ATAK Plugin — Atmosphere

**Download Atmosphere 0.2** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/atmosphere/releases/download/v0.2/ATAK-Plugin-Atmosphere-0.2--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/atmosphere/releases/download/v0.2/ATAK-Plugin-Atmosphere-0.2--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/atmosphere/releases/download/v0.2/ATAK-Plugin-Atmosphere-0.2--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/atmosphere/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/atmosphere/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

The weather on the ATAK map for fire and hazmat crews: the forecast at a
point, the readings from the weather stations, river gauges and buoys around
you, and the pictures the National Weather Service publishes, each one a map
layer switched on when it is needed. Everything is read by the device from
public US government services and stays on the device; nothing is published.

Capabilities:

  - A forecast readout for a point -- your position, the map center, a point
    tapped on the map, or a favorite -- with the fire weather elements a
    briefing asks for: temperature, humidity, the 20-foot wind and gusts,
    mixing height, transport wind, wet bulb globe temperature, chance of rain;
    the next hours as a strip and a table, the next days, sun and moon. The
    weather service it comes from is a JSON definition file the operator can
    replace in the field (docs/SOURCES.md); two ship, both key-free.
  - Weather stations around you, colored against the Red Flag criteria of
    their own fire weather zone (the California, Great Basin, Southwest and
    Northwest annual operating plans are transcribed), with every reading and
    the criteria behind a tap, favorites, and a list with counted filters.
  - River gauges colored by flood category, with stage, flow, flood stages and
    the observed and forecast hydrograph; buoys and coastal stations with
    their readings graded by sea state, the nearest tide and current
    predictions, and the coastal or offshore waters forecast for their zone.
  - Map layers: radar and modeled wind with a time scrubber; forecast smoke and
    the EPA's air quality; the SPC fire weather outlook and the WPC excessive
    rainfall outlook, one day at a time; modeled flooded ground now or for the
    next five days and the streams running high, with the flows behind a tap
    (both experimental services, and labeled so); the beach forecast by rip
    current risk; sea surface temperature; the National Hurricane Center's
    active storms; avalanche forecast zones by danger; mountain snow stations
    and the daily snow depth analysis. Every colored area carries its label;
    every layer says in words what it is not showing.
  - Spot forecasts: every open request the National Weather Service has, with
    the office's forecast, and help preparing your own request.
  - No layer or weather service touches the network until the operator allows
    it, once, by name; the dialog names the server and says what is sent.
    Coordinates that leave the device are rounded to a precision the operator
    sets. A failed refresh keeps the last good reading and says how old it is.
  - Warnings, watches and advisories are deliberately absent: the companion
    IPAWS Alerts plugin draws them, on the same map.

_________________________________________________________________
STATUS

0.2, first public release, for ATAK-CIV 5.6, 5.7 and 5.8. Developed on a
Samsung Galaxy XCover Pro running ATAK-CIV 5.8.0.3 and checked on official
ATAK-CIV 5.8 with the tak.gov-signed 0.1; compiled clean against the 5.6.0.23
and 5.7.0.14 SDKs. The manual and the user guide carry screenshots from the
signed build.

Parsers for every service are covered by unit tests that run off-device
(./gradlew testCivDebugUnitTest).

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/atmosphere/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only, and only to services the operator has
  allowed by name. A plaintext http URL in a weather service definition is
  rejected when it is loaded, not at request time. With nothing allowed the
  plugin makes no network calls at all.

  Hosts, by what allows them:

    api.weather.gov                 forecasts, marine forecast products,
                                    spot forecast texts (the weather service
                                    and several layers)
    api.open-meteo.com              the other bundled forecast service, if chosen
    mapservices.weather.noaa.gov    NWS map services: fire weather and
                                    excessive rainfall outlooks, beach forecast,
                                    spot forecast requests, fire weather zones,
                                    hurricane maps, snow depth
    opengeo.ncep.noaa.gov           radar mosaics (NWS)
    geo.weather.gc.ca               radar over Canada (Environment Canada)
    api.rainviewer.com              radar everywhere else, the frame list
    tilecache.rainviewer.com        and its tiles (RainViewer's composite)
    nomads.ncep.noaa.gov            model wind, smoke, wave and rain files
    nowcoast.noaa.gov               sea surface temperature
    maps.water.noaa.gov             flooded ground, streams running high
    api.water.noaa.gov              river gauges and their forecasts
    www.ndbc.noaa.gov               buoys and coastal stations
    api.tidesandcurrents.noaa.gov   tide and current predictions
    www.nhc.noaa.gov                hurricane advisories
    api.avalanche.org               avalanche forecast zones
    wcc.sc.egov.usda.gov            snow stations (NRCS)
    services.arcgis.com             EPA AirNow air quality areas
    services3.arcgis.com            the interagency weather station feed
    geocoding.geo.census.gov        address search for a spot request, only
                                    when the operator types an address

  An operator-supplied weather service definition can name a different host;
  the dialog that enables it lists the hosts it will contact.

  No inbound ports. No listening sockets. No traffic to or from the TAK
  server; the plugin neither generates nor consumes CoT. Requests carry a
  User-Agent naming the plugin and its version, which the public services
  require. No callsign, device identifier or TAK server detail is sent. The
  device's position leaves it only for the forecast the operator asks for,
  rounded, and for the station, gauge and buoy layers when set to "My
  position", rounded the same way; every other layer sends the map's area.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.6, 5.7 or 5.8. Network connectivity
  to fetch readings; fetched readings stay readable offline with their age
  shown.

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

  The user manual in docs/user_manual/ is compiled into the APK by
  gradle/typst.gradle when ATAK_CI=1 (or -PbuildManual locally).

_________________________________________________________________
DEVELOPER NOTES

  Weather service definitions are documented in docs/SOURCES.md, with a
  worked example. The bundled definitions in app/src/main/assets/wx_sources/
  are the reference implementations of the schema and are asserted clean by
  the test suite.

  Design rules this plugin holds to:

    - Other agencies' data is a read-only feature layer, never markers or
      drawings the operator could edit or send: every layer draws through one
      class (overlay/AtmosphereFeatures) on a worker thread, and a tap gives
      a record, not a shape to recolor.
    - gov.tak.api.* is preferred throughout. Calls into com.atakmap.android.*
      internals are isolated in compat/MapCompat.java where an equivalent
      exists.
    - No lambdas or method references in shipping code. The SDK documents
      them breaking under release proguard.
    - Every outbound request passes through net/EgressPolicy.java and
      net/Http.java, which own enablement, coordinate rounding and the
      User-Agent. There is no second path to the network.
    - Dialogs and toasts use the map view's context, never the plugin's.
    - Controls carry no model or service names; the record behind a tap says
      where a number came from and how sure it is.

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
