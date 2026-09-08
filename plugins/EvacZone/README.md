ATAK Plugin — Evac Zone

**Download Evac Zone 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/evac-zone/releases/download/v0.1/ATAK-Plugin-EvacZone-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/evac-zone/releases/download/v0.1/ATAK-Plugin-EvacZone-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/evac-zone/releases/download/v0.1/ATAK-Plugin-EvacZone-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/evac-zone/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/evac-zone/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

Evacuation zones on the ATAK map, from the services that publish them: state
emergency management agencies, CAL FIRE, and the counties and cities that run
their own zone maps. Browsed by state, then by county, the way Cam Depot
browses cameras, and drawn in one color language whatever the source calls a
status: Order red, Warning yellow, Advisory blue, Shelter in Place purple,
Lifted green, Normal faint.

Capabilities:

  - A catalog of evacuation services, read from a hosted file so sources can
    be added, corrected or retired without a plugin release. Every entry is
    probed by the catalog builder before it is published.
  - Pick a state; its statewide feeds are always listed. Pick one or many
    counties; each shows its share of the statewide feeds ("Monterey in Active
    Evacuation Zones: Order 8, Warning 4") and the county's own sources.
  - Every county of a state is in the picker, from the Census, whether or not
    anyone publishes a layer for it, with a box to frame it.
  - Zones are ATAK feature layers: tap one for its full attributes, and the
    zone's name is drawn at its center. Live sources refresh on their own
    interval and survive an ATAK restart from their cached store.
  - Cam Depot's visibility tools: a radius from the operator or the map
    center, in the operator's own units, and a zoom threshold quoted in
    scale-bar terms.
  - A zone list: search by id, name or county, narrow by status with counts,
    nearest first with distances, Go to and details per zone.
  - Each row says what it costs before it is used (feature counts, lag notes)
    and what is not shown (outside the radius, hidden by the zoom threshold).

A step-by-step user guide lives at docs/USER_GUIDE.md in the repository
(excluded from the source submission zip).

_________________________________________________________________
STATUS

Version 0.1. Verified on ATAK-CIV 5.8.0.3 (Samsung Galaxy XCover Pro), release
build with proguard. Compiles against the 5.6, 5.7 and 5.8 SDKs.

Eighteen sources at release: California and Oregon statewide, British
Columbia, nine California counties and cities, three Oregon county layers, two
Florida counties. More states and counties arrive through the catalog.

Prepared for tak.gov third-party submission.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/evac-zone/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only. Read-only GET requests, no credentials, no
  cookies, a static User-Agent. Nothing about the device or the operator is
  sent: no location, no callsign, no identifiers.

  Hosts:

  - mapdepot.takwerx.org, for the catalog (one small JSON file, read at
    plugin start). If it is unreachable the copy built into the APK is used.
  - The public ArcGIS FeatureServers the catalog lists, only for sources the
    operator turns on: services.arcgis.com and services1 through services9
    .arcgis.com (state and county ArcGIS Online organizations), and
    gis.napacounty.gov. Each is polled on its own interval, five minutes for
    live sources, daily for static zone boundaries. Plaintext (http) catalog
    entries are refused.

  No inbound ports. No listening sockets. No traffic to or from the TAK
  server, and no CoT is generated or consumed. With no network the plugin
  draws what it last fetched and says how old it is.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.6, 5.7 or 5.8, with internet access
  for the sources that are on. Storage is a few megabytes per source.

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

  The catalog is built off-device by a Python 3 script (standard library
  only) that probes every source and refuses one that does not answer, lacks
  the fields it names, is too large for a phone, or has not been edited in
  half a year while claiming to be live. Its output is app/src/main/assets/
  catalog.json and the hosted copy the plugin reads first.

_________________________________________________________________
DEVELOPER NOTES

  Sources are ArcGIS FeatureServer layers, fetched as Esri JSON in WGS84 with
  server-side generalization (maxAllowableOffset), and written into a
  file-backed FeatureSetDatabase2 drawn by a FeatureLayer3 on the vector
  overlays stack. A refresh writes the new set, then drops the old one, so
  the map never goes blank between fetches. The layer object is never
  recreated: ATAK keeps the labels of layers that are thrown away.

  A zone is one feature whose geometry is the polygon plus its center point in
  a collection, with a label style carrying no text: the point child draws the
  feature's name, the polygon child (which labels only from a style's own
  text) stays silent. A separate label feature was tried first and ATAK's tap
  chooser listed it beside its zone.

  Status is normalized from the field the catalog names (StatusColors.java):
  counties do not agree on words, and Cal OES ships a simple renderer that
  draws every status the same. Sources without a status field keep the
  service's own renderer, translated from drawingInfo; points always do.

  The zoom threshold is the feature set's own resolution limit, changed in
  place. The radius rewrites the store from the memory copy when the point
  has moved a tenth of the radius, coalesced off the GL-thread map-moved
  callback (never touch a view or a map item there).

  Genasys Protect is not a source. Its public WFS answers anonymously but its
  terms forbid automated use; live status for Genasys counties comes through
  the state aggregations (Cal OES, CAL FIRE, Oregon OEM), which carry every
  Genasys county.
