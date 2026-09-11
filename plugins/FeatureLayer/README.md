ATAK Plugin — Feature Layer

**Download Feature Layer 0.5** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/feature-layer/releases/download/v0.5/ATAK-Plugin-FeatureLayer-0.5--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/feature-layer/releases/download/v0.5/ATAK-Plugin-FeatureLayer-0.5--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/feature-layer/releases/download/v0.5/ATAK-Plugin-FeatureLayer-0.5--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/feature-layer/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/feature-layer/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

Live ArcGIS feature layers on the ATAK map, the way a GIS analyst sees them in
ArcGIS: every point, line and area stays a feature you can tap for its
attributes, drawn with the symbology its community uses.

Built-in sources:

  - NIFC: the National Incident Feature Service, live. Sign in with your own
    NIFC ArcGIS login (MFA supported), find a fire, and its Event Points, Event
    Lines, Perimeter Lines, Event Polygons, IR Points and Polygons, Accountable
    Property and Label Points load with the NWCG PMS 936 symbology: the standard
    point icons, lettered hand/road/mixed/plow lines, dozer X chains, one-sided
    ticks on the uncontained edge, Repair Status halos on request.
  - SARCOP Training: NAPSG's Search and Rescue Common Operating Platform
    sandbox, public, with NAPSG's own US&R symbology for waypoints, worksites,
    tracklogs, segments and divisions.
  - CA Air Intel: statewide California fire perimeters from FIRIS, CAL FIRE
    intel flights, USFS, NIFC and WFIGS, public. Each source toggles on its own,
    a time window picks how far back to look, and only the latest perimeter per
    fire per source is drawn.
  - Add your own org: any ArcGIS Online organization. Sign in, search its
    content, add a layer; it draws with the service's own renderer.

Every layer: ON/OFF, per-type ON/OFF and fill, labels, zoom gate, automatic
refresh with a per-layer interval, cached on the phone so it survives restarts
and loss of signal. Search inside a layer by name, number or type, sorted by
distance from you or from the map center, newest or oldest. Tap a feature for
its attributes, a bloodhound, a range and bearing line, or a marker.

_________________________________________________________________
STATUS

0.5, correct multi-part areas: an Esri service sends every ring of a feature in
one list and only the winding order separates them, so a fire perimeter of
several separate burn islands drew as one island full of holes - no fill, and
the shape fell apart on zoom-in. A CA Air Intel flight is also labeled by its
mission now, and a feature type's fill setting survives a refresh (0.1 could not
sign in, its OAuth client ID was empty; 0.2 lost cached features on the first
toggle after a restart; 0.3 searched all of ArcGIS Online instead of your own
organization for Find layer; 0.4 was the first release for feedback, with the
illustrated guide). Tested on ATAK-CIV 5.8 with live NIFS incidents, the SARCOP
training sandbox and CA Air Intel. SARCOP Live is not wired yet.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, TAKwerx. Bug reports and requests:
https://github.com/takwerx/feature-layer/issues

_________________________________________________________________
PORTS REQUIRED

Outbound HTTPS (TCP 443) only; nothing inbound.

  - The organization's ArcGIS portal (for example nifc.maps.arcgis.com, or the
    org you add) for OAuth 2.0 sign-in and content search.
  - ArcGIS Online hosted feature services on services*.arcgis.com: NIFC
    (services3.arcgis.com), NAPSG SARCOP (services.arcgis.com), CA Air Intel
    (services1.arcgis.com), and whatever services your own org hosts.

Sign-in is OAuth 2.0 authorization code with PKCE in an in-app browser; the
plugin never sees a password. An organization's token is sent only to that
organization's own servers, never in a URL.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android device running ATAK-CIV 5.6, 5.7 or 5.8 with a network connection
to load or refresh layers. Cached layers draw without one.

_________________________________________________________________
EQUIPMENT SUPPORTED

Any device ATAK-CIV supports. Exercised on a Samsung Galaxy XCover Pro.

_________________________________________________________________
COMPILATION

Standard ATAK plugin build against the ATAK-CIV SDK:

    cp template.local.properties local.properties   # then set sdk.path and arcgis.clientId
    ./gradlew assembleCivRelease

arcgis.clientId is the OAuth client ID of an ArcGIS application registration
(a public-client identifier, not a secret); without it the sign-in button says
so and the public sources still work.

_________________________________________________________________
DEVELOPER NOTES

Features are written into an ATAK feature store (FeatureDataStore2 /
FeatureLayer3) on the SD card under atak/tools/featurelayer, one SQLite store
per layer, and rendered by ATAK's own feature renderer, so the map stays vector
and every feature stays tappable. NWCG marks that ATAK's strokes cannot draw
(letters, X chains, ticks, chevrons, zigzags) are generated as ground-sized
geometry inside the line's own feature. Symbol images and colors come from the
NWCG PMS 936 symbology pages and NAPSG's published US&R symbol library. Area
names sit at the centroid by pairing the polygon with its center point in one
feature under an empty label style.
