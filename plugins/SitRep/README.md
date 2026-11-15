ATAK Plugin — Sit Rep

**Download Sit Rep 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/sit-rep/releases/download/v0.1/ATAK-Plugin-SitRep-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/sit-rep/releases/download/v0.1/ATAK-Plugin-SitRep-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/sit-rep/releases/download/v0.1/ATAK-Plugin-SitRep-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/sit-rep/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/sit-rep/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

A one-shot situation report for any area, for any incident type. Draw an area,
use an ATAK shape, tap a county or city, or type a place name; Sit Rep asks the
public agency sources for that area and writes a read-only report, laid out by
the FEMA community lifelines. Reports are saved on the device and can be
refreshed later.

It answers the questions a supervisor, IC or EOC lead asks on arrival, without
switching on layers in five other plugins:

  - What is happening now: weather and daylight, NWS and civil alerts (and the
    national terrorism advisory when one is in force), public 911 call feeds
    where an agency publishes one, wildfires, evacuation orders and warnings,
    road incidents, airspace classes and TFRs, cameras, disaster declarations.
  - Who and what is here: jurisdiction, people and vulnerability (Census, CDC
    SVI), FEMA hazard ratings, flood zones, structures by occupancy, terrain.
  - Places that matter: hospitals with the nearest ER and trauma ladder,
    urgent care, schools, nursing and care homes, dialysis, shelters, fire and
    police stations, airports and heliports, power plants and battery storage,
    nuclear plants with their 10 and 50 mile planning zones, dams, bridges,
    railroad crossings, rail and transmission lines, chemical facilities.
    Each category can be put on the map; a tap on a place opens a radial
    (Details, Bloodhound, Range and Bearing, polar).
  - Radio sites with known nets nearby, counted from the Comms catalog.
  - History: past wildfire perimeters, firefighter fatalities and
    entrapments with their reviews.

Every line is a fact an agency published; Sit Rep adds no rating of its own.
Where a source does not cover a place, the report says "Not published here"
rather than showing nothing. Each section ends by naming the takwerx plugin
that shows the same data in full (Atmosphere, AirAware, Feature Layer, Comms
and others), with an Open or Get button.

_________________________________________________________________
STATUS

Version 0.1: first release.

Tested on hardware: Samsung Galaxy S10 (ATAK-CIV 5.8), release build, with
report areas in California (Corona, Murrieta), Idaho (Bayview) and Colorado
(Pagosa Springs). Release builds compile against ATAK-CIV 5.6, 5.7 and 5.8.

Prepared for tak.gov third-party submission.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/sit-rep/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only, when the operator makes or refreshes a
  report, and every 5 minutes while 911 calls are switched on for a report.
  The plugin refuses plain HTTP and does not follow redirects.

  Public agency services:
    api.weather.gov, mapservices.weather.noaa.gov (NWS)
    tigerweb.geo.census.gov (Census)
    onemap.cdc.gov (CDC/ATSDR SVI)
    www.fema.gov, gis.fema.gov, hazards.fema.gov (FEMA)
    carto.nationalmap.gov, elevation.nationalmap.gov (USGS)
    geodata.epa.gov (EPA)
    geospatial.sec.usace.army.mil (USACE dams)
    tfr.faa.gov (FAA TFRs)
    www.dhs.gov (NTAS advisory)
    services.arcgis.com, services2.arcgis.com, services3.arcgis.com,
    services5.arcgis.com (ArcGIS Online layers of NIFC, FEMA, DOT, CDPH,
    CEC and others)

  takwerx hosting:
    mapdepot.takwerx.org (catalogs and packs: hospitals, places, care homes,
    power, airspace tiles, camera and evacuation catalogs, Comms catalog)
    cams.takwerx.org (road incident relay)

  Agency feeds named in the hosted catalogs: public 911 call feeds and
  evacuation zone services of individual cities and counties, each HTTPS.
  A feed that moves is a catalog edit, not a plugin change.

  No inbound ports. No listening sockets. No CoT is generated or sent, and
  nothing is shared to a TAK server. Saved reports work with no network.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.6, 5.7 or 5.8.
  A network connection to make or refresh a report. Saved reports are kept on
  the device (a few hundred KB each) and open with no network.

_________________________________________________________________
EQUIPMENT SUPPORTED

  Any Android device supported by ATAK. No additional or external hardware, no
  sensors, no peripherals.

_________________________________________________________________
COMPILATION

  Standard ATAK plugin build. Set sdk.path in local.properties to an unpacked
  ATAK CIV SDK, then:

      ./gradlew assembleCivDebug
      ./gradlew assembleCivRelease

  ext.ATAK_VERSION in app/build.gradle selects the ATAK release to target.

_________________________________________________________________
DEVELOPER NOTES

  One class per source under section/: an agency that moves a service is a
  change to one file. Each section is fetched on its own worker and fails
  alone; a dead source says "Not available" and the rest of the report stands.

  Every ArcGIS query is a POST with the area polygon, never a GET: a polygon
  in a GET past about 2,000 characters answers 404, and GETs are cached by the
  CDN for an hour. The where clause goes first in the body; the USGS firewall
  answers 403 to a POST whose where clause comes later.

  Places on the map are a feature store (FeatureLayer3), not map items, and
  every write is one transaction under the store's bulk modify lock. Inserted
  one by one, each feature was its own commit to shared storage and 2,000
  points took minutes.

  Reports are JSON files under atak/tools/sitrep/reports/, written to a temp
  file and renamed, and read back with a depth and size limit, since shared
  storage can hold anything.

  Dialogs use the MapView context, never the plugin context, and there is no
  Spinner: both open windows from the plugin context and take ATAK down.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

Sit Rep is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify Sit Rep and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use Sit Rep, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers Sit Rep's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
