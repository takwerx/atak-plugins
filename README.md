ATAK Plugin — Atmosphere

**Download Atmosphere 0.16** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/atmosphere/releases/download/v0.16/ATAK-Plugin-Atmosphere-0.16--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/atmosphere/releases/download/v0.16/ATAK-Plugin-Atmosphere-0.16--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/atmosphere/releases/download/v0.16/ATAK-Plugin-Atmosphere-0.16--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/atmosphere/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/atmosphere/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

The weather on the ATAK map for fire and hazmat crews: the forecast at a
point, the readings from the weather stations, river gauges and buoys around
you, and the pictures the National Weather Service publishes, each one a map
layer switched on when it is needed. Everything is read by the device from
public government services and stays on the device; nothing is published.

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
    the criteria behind a tap, favorites, and a list with counted filters. In
    California, the utilities' stations too (SCE, SDG&E, PG&E, HPWREN, from
    Cal OES), drawn apart and saying their wind reads higher than a RAWS.
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
  - Fire weather zones: the NWS zones on the map with their numbers, shaded
    under a Red Flag Warning or a Fire Weather Watch; the office's Fire
    Weather Planning Forecast for a zone as written, found by the point being
    read, by zone number or name, with starred zones.
  - SAWTI, the Forest Service's Santa Ana Wildfire Threat Index, on the map by
    level for four days and as a page with the site's gauges, the forecaster's
    words and the recommended actions; NOAA's 15-minute lightning strike
    density; California's Public Safety Power Shutoffs from Cal OES, SCE and
    SDG&E (counties warned, circuits watched, where the power is shut off,
    customers out and when it is expected back), saying what it does not
    cover.
  - Fire danger: every Predictive Service Area in the lower 48 shaded by its
    Energy Release Component percentile, observed or forecast, from the
    Forest Service's national service; tap an area's name for its ERC and
    Burning Index and its GACC's own ERC chart, full screen to pinch and zoom.
    Each fire weather zone gives the ERC of the area it is in.
  - Fuel moisture samples: every sampling site of the National Fuel Moisture
    Database in the state under the map, the newest sample's moisture in
    the pill and colored critical to low on the live fuel or timelag class's
    own breaks, every fuel sampled there
    behind a tap; live fuels, dead fuels or both, 30 days to a year back.
  - The wind as a heat map: the speed the streaks are drawn from as a colored
    field under them, in the same bands as the key, with one switch.
  - Storms after the fact: every storm report the NWS offices logged in the
    last three days (tornado, wind, hail, flood, rain, snow, landslide), and
    the tornado paths survey teams have walked in the last year, each line in
    its EF rating's color with the survey behind a tap.
  - Debris flow: the USGS post-fire assessments, every fire of the last three
    years marked and, zoomed in, each stream basin filled Low, Moderate or
    High for a 15-minute burst of rain, beside the river gauges and the rain.
  - Earthquakes: the USGS feed worldwide, circles sized by magnitude and
    colored by age the way USGS draws them, the shaking contours of the
    largest, and the USGS record behind a tap.
  - Storm Reports as a page: the reports and the tornado paths in one list,
    nationwide, near the pane's point, on the map or by state, newest or
    closest first, any mix of types; a row flies to it and opens it.
  - Rain that fell, for the time picked: the last hour to 30 days, since
    October 1 or January 1, one day, or the hours before a date and time (the
    rain before a fire started). The NWS rainfall picture in the service's own
    colors with a transparency slider; the measured gauges around the map
    (USGS, airports, NWS observers, county flood control, RAWS) with their
    totals in the same colors, a gauge that missed part of the time or reads
    far off its neighbors left off; and the forecast office's own rainfall
    report as a table, most rain first or closest.
  - The forecaster's synopsis on the Forecast page, from the Area Forecast
    Discussion for the office covering the point, the other sections a tap
    away.
  - Spot forecasts: every open request the National Weather Service has, with
    the office's forecast, newest or closest first and by type, and help
    preparing your own request.
  - A tap on anything Atmosphere draws opens its page or its details, with no
    radial menu.
  - The forecast asks the weather service picked in Forecast settings (NWS by
    default) for the point being read. Every map layer asks the operator once,
    by name, before it touches the network; the dialog names the server and
    says what is sent. Coordinates that leave the device are rounded to about
    100 m. A failed refresh keeps the last good reading and says how old it is.
  - Warnings, watches and advisories are deliberately absent, except Red Flag
    Warnings and Fire Weather Watches on the fire weather zones: the companion
    IPAWS Alerts plugin draws them, on the same map.

_________________________________________________________________
STATUS

0.16, for ATAK-CIV 5.6, 5.7 and 5.8 (0.15 was a 5.8-only build for the pictures). New: rain totals for
any time on both rain layers: the last 24 hours, 3, 7 or 30 days, since October 1
or January 1, one day, or the hours before a date and time, such as when a fire
started; Rain Gauges from the USGS, county flood control, RAWS and airport
gauges, no volunteer gauges, in Rain Totals' colors, with a gauge that missed
part of the time or disagrees with its neighbors left off; where the NWS has not
produced its 7-day analysis, the radar estimate fills it in; the SAWTI page and
map on one day, the page's zone outlined on the map while it is open; every page
with a map layer starts with that layer's switch; and filter counts that follow
the map. 0.14 brought a Storms group with Storm Reports
(the NWS Local Storm Reports of the last three days) and Tornado Paths (the
NWS Damage Assessment Toolkit's surveyed tracks of the last year, by EF
rating); Debris Flow (the USGS post-fire debris-flow assessments, fires marked
and basins by hazard) beside the river gauges; and a Ground group with
Earthquakes (the USGS feed, ShakeMap contours for the largest); a Storm
Reports page listing reports and paths by where and type; Rain Totals (the NWS
MRMS radar estimate and the RFC gauge-corrected analysis as a picture) and Rain
Gauges (USGS gauges and the forecast office's rainfall report, on the map and
as a list); the forecaster's synopsis on the Forecast page; Fuel Moisture
(the National Fuel Moisture Database's samples, by state); the wind as a heat
map under the streaks; the rainfall report's RAWS placed through NIFC as well
as NWS, with Go to on every placed row; and every section heading in the house
cyan. 0.12 fixed a
storm the National Hurricane Center still lists but no longer forecasts
(takwerx/atmosphere#5). 0.11 brought Fire Danger, the Energy Release
Component by Predictive Service Area with each GACC's chart; California's
utility weather stations; a Layers button in the top row; labels that stay
level when the map turns; the smoke forecast at every zoom; fire weather zones
outlined in purple; and Atmosphere's drawn icons kept in ATAK's private
storage rather than the shared card (takwerx/atmosphere#2). Developed on
Samsung Galaxy S10 and XCover Pro phones running the development ATAK-CIV
5.8.0.3; compiled clean against the 5.6.0.23 and 5.7.0.14 SDKs. The manual
and the user guide carry screenshots from the signed build.

Parsers for every service are covered by unit tests that run off-device
(./gradlew testCivDebugUnitTest).

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/atmosphere/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only: to the forecast service picked in Forecast
  settings (api.weather.gov by default, or api.open-meteo.com), and to the
  services of the map layers the operator has allowed by name. A plaintext
  http URL in a weather service definition is rejected when it is loaded, not
  at request time. With no map layer allowed, the forecast is the only call.

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
    services3.arcgis.com            the interagency weather station feed, the
                                    Energy Release Component, the RAWS a
                                    rainfall report names
    fsapps.nwcg.gov                 SAWTI (Forest Service)
    gacc.nifc.gov                   Fire Danger charts (the GACCs)
    www.nfmdb.org                   fuel moisture samples (NFMD)
    services.dat.noaa.gov           tornado paths (NWS Damage Assessment Toolkit)
    earthquake.usgs.gov             earthquakes, debris flow basins (USGS)
    waterservices.usgs.gov          USGS rain gauges
    data.rcc-acis.org               airports and NWS observers' rain over a run
                                    of days (NOAA Regional Climate Centers)
    mesonet.agron.iastate.edu       county flood control, RAWS and agency
                                    gauges, airports' hourly rain, past radar
                                    rain pictures (Iowa Environmental Mesonet)
    geocoding.geo.census.gov        address search for a spot request, only
                                    when the operator types an address
    mapdepot.takwerx.org            Public Safety Power Shutoffs: Cal OES's
                                    warned counties and PG&E's areas, SCE's
                                    and SDG&E's circuits, relayed by takwerx
                                    as one file

  An operator-supplied weather service definition can name a different host;
  the dialog that enables it lists the hosts it will contact.

  No inbound ports. No listening sockets. No traffic to or from the TAK
  server; the plugin neither generates nor consumes CoT. Requests carry a
  User-Agent naming the plugin and its version, which the public services
  require. No callsign, device identifier or TAK server detail is sent. The
  device's position leaves it only for the forecast the operator asks for,
  rounded, and for the station, gauge and buoy layers, which measure from the
  same point (the device's position when the pane is set to My position),
  rounded the same way; every other layer sends the map's area.

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
