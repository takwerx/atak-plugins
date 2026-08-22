PLSS Grid


_________________________________________________________________
PURPOSE AND CAPABILITIES

Public Land Survey System (PLSS) overlay for ATAK. Draws surveyed township and
section boundaries with their labels, the way the built-in Grid Lines overlay
draws a computed graticule, and answers "what section is this" for any point on
the map.

PLSS is how ground is referenced in the western United States for wildland fire
and public-land operations -- division assignments, drop points, contingency
lines. ATAK ships MGRS and USNG grid lines but nothing for PLSS.

Capabilities:

  - Township and section boundaries as a rendered GL layer, not map items, so
    the section layer (2.77 million polygons nationally) stays usable.
  - Two zoom tiers: townships with their T/R label, then sections with their
    numbers, each appearing only when large enough to carry its label.
  - Toggled from Overlay Manager alongside Grid Lines, and from the plugin pane.
  - Color selection per tier: township lines and labels together, section
    lines and numbers together. The label backdrop flips automatically between
    dark and light so any chosen color stays readable.
  - Go-to-township lookup by principal meridian, township and range.
  - Works fully offline once data is installed.

Source data is the BLM National PLSS CadNSDI, which is the authoritative survey
record. USGS products derive from it.

A step-by-step user guide with screenshots lives at docs/USER_GUIDE.md in the
repository, with the images under docs/screenshots/ (both excluded from the
source submission zip).

_________________________________________________________________
STATUS

Release candidate. Version 0.3.

Rendering, data pipeline and data management are working and have been
exercised on hardware: Samsung Galaxy S21+ (Android 15, ATAK-CIV 5.7) and
Samsung Galaxy XCover Pro (ATAK-CIV 5.8.0.3). Labels are placed and sized by
ATAK's own label engine and verified through pan, zoom, tier handover and
rotation. California, Idaho and Nevada validated against BLM feature counts.

Prepared for tak.gov third-party submission.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/atak-plugins (issues)

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only, and only when the operator explicitly requests
  a data download.

  The plugin ships with no map data. On first use the operator opens the data
  manager, which fetches a small JSON manifest over HTTPS and then downloads the
  state packs the operator selects. Downloads are resumable and each pack is
  verified against a SHA-256 digest published in the manifest before it is
  installed.

  No inbound ports. No listening sockets. No traffic to or from the TAK server,
  and no CoT is generated or consumed. The plugin does not contact the BLM
  service at runtime -- BLM is queried offline by the tools in tools/ when the
  data packs are built.

  After the packs are installed the plugin makes no network calls at all and
  functions with the device in airplane mode.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.7.
  Storage for the data packs the operator installs. Packs are per state and
  range from about 6 MB to 124 MB; a typical western operating area of three or
  four states is under 100 MB.

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

  tools/ holds the off-device data pipeline (Python 3, standard library only):
  fetch_blm.py pages the BLM MapServer, pack_plss.py builds the per-state
  SQLite packs, and make_manifest.py writes the manifest the plugin reads.
  These are build-time tools and are not part of the plugin.

_________________________________________________________________
DEVELOPER NOTES

  Data cannot ship inside the APK. The national dataset is about 550 MB across
  30 states, which is incompatible with a source submission. The plugin
  therefore ships bare and downloads packs on request.

  The packed store is SQLite with an R-tree spatial index and geometry stored
  quantized and delta-varint encoded rather than as text. Note that Android's
  bundled SQLite is built without the R*Tree module: the index is only visible
  through ATAK's own SQLite, reached via com.atakmap.database.Databases and
  DatabaseIface. That interface also provides typed parameter binding, which
  matters because android.database.sqlite passes every argument as text and
  compares wrongly against the index's REAL columns.

  Labels are not drawn by the plugin at all. Every label is registered with
  ATAK's own label engine (GLMapView.getLabelManager()), which owns placement,
  screen-size text, decluttering and rotation -- the same engine that places
  every marker callsign, so labels agree with the surface-drawn grid by
  construction. Text placed by hand from either render pass does not: the
  passes do not share a projection, and the error grows with distance from
  the screen center. Labels must be released back to the engine on every
  clear and hidden with the layer, or they keep drawing with nothing able to
  reach them.

  The label engine does not render every color faithfully. 0xFFFFA500 text
  measured (255,231,0) on screen against (255,166,0) for identically
  colored lines, regardless of bold or priority; ATAK's own palette colors
  measured exact. Ship palette colors for labels, and measure any hard-coded
  label color on the device against its line.

  Plugin resources must be resolved through the plugin context, while anything
  that opens a window -- dialogs, popups, list choosers -- must be built with
  ATAK's Activity context. A Spinner cannot satisfy both, since it inflates with
  one context and opens its popup with the same one, so pickers here are buttons
  that open list dialogs.

  Zoom thresholds are measured on a device from GLMapView.State.drawMapResolution
  rather than derived from the scale bar, which changes length to suit round
  numbers and does not convert to a fixed meters-per-pixel.

  PLSS coverage is not universal. The rectangular survey went around Spanish and
  Mexican land grants, so there is no PLSS beneath much of coastal and southern
  California. Gaps in those areas are correct, not missing data.

  BLM spells some principal meridians more than one way in its own data. The
  packer canonicalizes them, otherwise the meridian picker offers several
  near-identical entries and a lookup against the wrong one silently fails.
