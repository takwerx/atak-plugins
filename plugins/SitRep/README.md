ATAK Plugin — Sit Rep

**Download Sit Rep 0.9** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/sit-rep/releases/download/v0.9/ATAK-Plugin-SitRep-0.9--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/sit-rep/releases/download/v0.9/ATAK-Plugin-SitRep-0.9--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/sit-rep/releases/download/v0.9/ATAK-Plugin-SitRep-0.9--5.8.0-civ-release.apk

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

Version 0.9: Share as PDF. The Share button on a report makes a read-only
PDF of it, a snapshot of one area at one time, and sends it as an ATAK data
package through ATAK's own send window (contacts, teams, TAK servers, Data
Sync, Export). It is made on the device with no network. It never carries
shelter contact details or anything marked CUI. Shelter rows no longer say
"Pets: null" when the shelter list leaves that field blank.

Version 0.8: All ON / OFF replaces the Areas switch. It turns every
report's Map switch on or off at once, outline and places together. When
only some reports are on, it shows how many (All: 2 of 5), and a tap turns
them all on. Before, Areas OFF hid every outline while each report still
read Map ON.

Version 0.7: Evacuations shows Genasys Protect evacuation zones, in every
state where an agency issues them through Genasys. In California and Oregon
it also shows the Cal OES, CAL FIRE, Oregon and county zones that Genasys
does not have, and a zone in two feeds counts once.

Version 0.6: place search finds more of what people type. Abbreviations
("Ft. Lauderdale", "St. Louis", "Mt. Vernon"), nicknames ("NYC", "Philly"),
a kind word ("Orange County", "City of Miami"), names without their accents
("Espanola") and part of a name ("Vegas") now find the place.

Version 0.5: cooling and warming centers. Where a city publishes its list,
Places shows them within Nearby, with type, hours, address and phone. Chicago
is the first city. The flood section tries once more when FEMA's server drops
the connection.

Version 0.4: Sit Rep works on FirstNet. Some agency servers block the IPv6
address that FirstNet gives a device. The Census Bureau answers it with a
"Request Rejected" page, and EPA never answers it. Sit Rep now retries those
requests over IPv4, so census data and power plants load on FirstNet.
0.3 went to tak.gov with the Census fix only and was not published.

Version 0.2: first public release. 0.1 went to tak.gov once for ATAK 5.8 as
the build the manual's map pictures were taken on, and was not published.
0.2 makes the Search and Rename boxes readable in landscape on phones with a
taller keyboard (Galaxy S22), and finds a city's boundary through a typo in
Search.

Tested on hardware: Samsung Galaxy S10 (ATAK-CIV 5.8, release build) and
Samsung Galaxy S22 Ultra (official ATAK-CIV 5.8, tak.gov-signed 0.1),
Samsung Galaxy S9+ (official ATAK-CIV 5.6, 5.7 and 5.8, tak.gov-signed 0.4,
on FirstNet), with
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
