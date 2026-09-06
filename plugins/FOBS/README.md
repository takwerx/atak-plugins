ATAK Plugin — FOBS

**Download FOBS 0.3** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/fobs/releases/download/v0.3/ATAK-Plugin-FOBS-0.3--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/fobs/releases/download/v0.3/ATAK-Plugin-FOBS-0.3--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/fobs/releases/download/v0.3/ATAK-Plugin-FOBS-0.3--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/fobs/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/fobs/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

FOBS (Field Observation Survey) captures a perimeter or an area in ATAK by
walking it, tracing it, or pulling it out of a track log, and turns the result
into an ordinary ATAK drawing shape with area and perimeter. Built for wildland
fire and search operations where the line on the map has to be the line on the
ground: a fireline walked with a phone in hand, a road you can see and do not
need to walk, a leg a teammate already drove.

Everything FOBS makes is a normal ATAK drawing. It shows area and perimeter in
ATAK's own details panel, shares over the mesh, edits with ATAK's vertex editor
and deletes like any other shape. Nothing has to be created first; there is no
survey object, no project, no server step unless you ask for one.

Capabilities, six tiles in a side pane:

  - Start a GPS track: record where you walk or drive, with Pause and Resume.
    Fixes are filtered live for reported accuracy, impossible speed and
    standing-still jitter, and the count of rejected fixes is shown while
    recording. At End: keep it as a line, or make it a polygon.
  - Draw a track: drop points by tapping, or draw freehand with ATAK's own
    telestration tool; the scribble becomes a track when you tap Done.
  - Select element: turn a shape already on the map into a track -- a line
    or polygon from an imported KML, a telestration (one track per stroke, or
    joined in order), a freeform line or polygon, a route, a rectangle, a
    circle, or a Track History line.
  - Import a track: opens ATAK's Track History. Browse, search the server,
    turn a track on, and its radial menu gets a FOBS button that imports it,
    with the same accuracy filtering as a live walk.
  - Split track: long press any line or closed shape exactly where you want
    it broken. A line becomes two lines with a hairline gap, with Undo; a
    polygon opens into one line. The result opens in ATAK's vertex editor.
  - Join tracks: tap an end on one line and an end on another and they become
    one line; tap both ends of the same line to close it into an area. Or
    lasso several lines and they are chained nearest end to nearest end and
    closed into an area. Any open line on the map can be joined, not only a
    FOBS track.

Optionally, a GPS track can be sent live to a Data Sync feed on a connected TAK
Server. The track grows on every subscriber's map as it is walked, and when it
is made into a polygon the same feed item becomes the area.

A step-by-step user guide with screenshots lives at docs/USER_GUIDE.md in the
repository, with the images under docs/screenshots/ (both excluded from the
source submission zip).

_________________________________________________________________
STATUS

Version 0.3: the 0.2 tools with the user manual, Join taking any line, and
fixes found on the signed 0.2 (Split cut where ATAK said the finger was,
Undo split, the End button while paused, a clearer message for an import
that filters down to nothing).

Verified on ATAK-CIV 5.8.0.3 (development build, Samsung Galaxy XCover Pro)
and, as tak.gov-signed 0.2, on official ATAK-CIV 5.6.0.18, 5.7.0.14 and
5.8.0.4 (Samsung Galaxy S21+, Note 20, S22 Ultra). Feed delivery confirmed
against a TAK Server 5.7 and watched on a second device running Data Sync.

Submitted to the tak.gov third-party pipeline.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/fobs/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  No inbound ports. No listening sockets. No traffic to any host other than
  TAK Servers the operator has already configured in ATAK.

  Without a connected TAK Server the plugin makes no network calls at all and
  works with the device in airplane mode.

  With a connected TAK Server, and only when the operator chooses "Send to
  feed" for a track, the plugin uses that server's existing connections:

    - The streaming CoT connection ATAK already holds (typically TCP 8089,
      TLS), through ATAK's own send path, to publish the track to the chosen
      Data Sync mission.
    - The server's REST API (typically TCP 8443, HTTPS, client certificate),
      through ATAK's own HTTP client with ATAK's stored credentials, for:
      listing missions, subscribing this device to the chosen mission, adding
      the track's UID to the mission's contents, and removing it when FOBS
      itself splits or joins the track away.

  Importing a track from the server's track history uses the same REST API
  through ATAK's own Track History code. Password-protected missions are
  listed but not written to in this version.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.6, 5.7 or 5.8, with a GPS receiver
  for live tracks. No storage beyond the plugin itself.

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

  The build declares useLibrary 'org.apache.http.legacy' because ATAK's HTTP
  client hands back Apache request and response objects; the classes are
  present in ATAK's process at run time and are needed only to compile.

_________________________________________________________________
DEVELOPER NOTES

  Tracks and areas are plain com.atakmap.android.drawing.mapItems.DrawingShape
  objects in ATAK's drawing group, tagged through a small <fobs .../> CoT
  detail (kind, source, altitude source, fix counts, feed name) carried by a
  CotDetailHandler. Nothing is a subclass: ATAK rebuilds shapes from its CoT
  store on restart as plain DrawingShape, so an instanceof check would stop
  working the first time the app was reopened.

  Every FOBS shape is clamped to ground. Points carry GPS or interpolated
  altitudes, and a shape drawn at those altitudes sinks under higher terrain
  and disappears as the operator zooms in.

  The GPS filter is conservative on purpose. It drops a fix for reported
  horizontal error above 25 m, implied speed above 45 m/s (a multipath jump,
  not a drive) or movement under 2 m (standing still). It does not smooth, and
  it does not thin a track under 2,000 points; a fixed Douglas-Peucker pass
  turned gentle curves into right angles against the breadcrumb log and was
  removed. Imported tracks skip the speed test, because a beacon's report
  times are the server's and sparse.

  Long-press handling follows ATAK's own touch controller: an item long press
  is only reported when exactly one item is under the finger, so Split
  registers a DeconflictionListener that narrows stacked hits, picks the
  nearest line within a finger's width (longest on a tie) rather than trusting
  the hit item, and takes the finger position from the event's screen point
  inverted through the map. A shape's recorded click point can be stale by
  hundreds of kilometers.

  Radial-menu buttons added through a MapMenuFactory must copy an existing
  button's orientation radius, width and background; the ring normalizes the
  slice angles itself but not those.

  Publishing to a Data Sync mission from a plugin: the CoT goes through
  CommsMapComponent.sendCoTToServersByMission with the chosen server's
  connect string as the key (null means every connected server). Data Sync's
  REST sequence must be followed: subscribe the device, then add the UID to
  the mission's contents. Measured on TAK Server 5.7: adding a UID the server
  has no CoT for is HTTP 500 with an empty message; adding from an
  unsubscribed creator is HTTP 200 and records nothing; a raw
  <marti><dest mission=.../></marti> on a streamed CoT is stored but not
  filed into the mission; and CotDispatcher.dispatchToConnectString cannot
  reach an SSL streaming server at all.

  A map item's removal is never turned into a mission DELETE. ATAK honors a
  peer's forced-delete CoT by removing the item locally, so doing that would
  let any peer delete mission content under this user's identity. Only FOBS's
  own split, join and lasso take a consumed track out of its feed.

  Plugin resources are resolved through the plugin context; anything that
  opens a window is built with ATAK's Activity context, and there are no
  Spinners.

  The workflow follows the Fire Area Survey plugin's leg-collection and
  connect-legs tools, rebuilt without its survey and feed-management layer.
