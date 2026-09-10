ATAK Plugin — Comms

**Download Comms 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/comms/releases/download/v0.1/ATAK-Plugin-Comms-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/comms/releases/download/v0.1/ATAK-Plugin-Comms-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/comms/releases/download/v0.1/ATAK-Plugin-Comms-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/comms/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/comms/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

Mountaintop radio sites on the ATAK map, nearest first, with the nets that live on
each one: name, frequencies, tone and callsign. Answers "what is the closest
Command 5 repeater, what mountain is it on, and what tone does it use" from a
side pane, and draws ATAK's own viewshed from the site's antenna.

Capabilities:

  - Radio sites from the Cal OES FIRENET and CESRS relay layers, the USFS
    communications-site layer, and the operator's own rows, with county and
    ground elevation.
  - Every channel of the FIRESCOPE statewide plan (MACS 441-1): designator,
    name, RX/TX as programmed in a mobile, tones as Hz and as California tone
    numbers ("103.5 (Tone 8)").
  - Search by site name, net name or designator, frequency, tone or callsign.
    When the search names a net, each row shows that net's line at that site,
    so the nearest one is readable from the list.
  - Nearest first, measured from the device or from the map center, within a
    chosen radius, filtered by agency with counts.
  - Site details: county, elevation, distance and bearing, and every net on it.
  - Viewshed from a site's antenna through ATAK's own viewshed layer, and a
    viewshed from the operator with the sites inside it: each site in range gets
    a line-of-sight check over the device's elevation data, turns green on the
    map when it can be seen, and lists first. Line of sight, not radio coverage,
    and labeled as such.
  - Catalog refreshed from the depot host once a day and on demand, with the
    built-in copy and the last download as fallbacks. Adding a site or a net is
    a catalog change, not a plugin release.

_________________________________________________________________
STATUS

In progress. 0.1 is the first build: California sites and the statewide plan.
More states follow as their site data is added; the catalog format already
allows it.

_________________________________________________________________
POINT OF CONTACTS

takwerx. Bug reports and requests: https://github.com/takwerx/comms/issues

_________________________________________________________________
PORTS REQUIRED

Outbound HTTPS (TCP 443) to mapdepot.takwerx.org for the catalog. Nothing
inbound. The plugin works offline from its built-in catalog.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android device running ATAK-CIV 5.6, 5.7 or 5.8. Elevation data on the
device (DTED, for example from Map Depot) for the viewshed.

_________________________________________________________________
EQUIPMENT SUPPORTED

Tested on Samsung Galaxy S21+, Note 20, S22 Ultra and XCover Pro.

_________________________________________________________________
COMPILATION

Standard ATAK plugin build. Point `sdk.path` in `local.properties` at the
ATAK-CIV SDK matching `ATAK_VERSION` in `app/build.gradle`, then:

    ./gradlew assembleCivRelease

The catalog is built by `tools/build_comms_catalog.py` (needs pdftotext) and
committed to `app/src/main/assets/catalog.json`.

_________________________________________________________________
DEVELOPER NOTES

Frequencies are the mobile's view throughout: rx is what you listen on, tx is
what you transmit on. The Cal OES layers name them from the repeater's side and
are swapped on the way in. A channel row never restates a frequency; when a
source disagrees with the plan the row carries the source's pair and says so.

The viewshed is ATAK's ViewShedReceiver, handed the antenna height as an AGL
altitude; ATAK's own viewshed tool does the same. Every viewshed the plugin
draws is dismissed when the plugin stops.
