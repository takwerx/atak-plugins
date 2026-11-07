ATAK Plugin — LCES

**Download LCES 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/lces/releases/download/v0.1/ATAK-Plugin-LCES-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/lces/releases/download/v0.1/ATAK-Plugin-LCES-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/lces/releases/download/v0.1/ATAK-Plugin-LCES-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/lces/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/lces/blob/main/docs/USER_GUIDE.md)


_________________________________________________________________
PURPOSE AND CAPABILITIES

Lookouts, Communications, Escape Routes, Safety Zones: the wildland fire
Incident Response Pocket Guide one tap away in ATAK, and the lessons other
crews learned the hard way, on the map where they happened.

The IRPG:

  - The whole NWCG Incident Response Pocket Guide (PMS 461, January 2025) in
    the pane, every page, with its tables and diagrams, in the book's own
    section colors.
  - Tiles for the pages read every shift: the 10 Standard Firefighting Orders
    and 18 Watch Out Situations, LCES, the Briefing Checklist, the Common
    Denominators of Fire Behavior on Tragedy Fires, and the book's contents.
  - One search box for the whole book and the lessons below; a search opens
    the page with the words marked.
  - Larger text for reading in a moving vehicle.

Lessons Learned:

  - Burnovers, entrapments, fatalities, near misses and injuries from the
    Wildland Fire Lessons Learned Center's incident database, about 2,270 of
    them, as a layer on the ATAK map. Tap one for what happened, a quote from
    the report, the fire behavior, and a link to the full report.
  - A list under the tiles of the incidents nearest you, nearest first.
  - Filters by distance, type and category (aviation, chainsaw, medical and
    others), with counts, and a zoom setting so a zoomed-out map stays readable.
    The list and the map always show the same incidents.
  - Works with no signal. The plugin carries the whole list; with a network it
    checks once a day for a newer one.

Nothing is sent from the device. The plugin creates no CoT and talks to no TAK
server.

_________________________________________________________________
STATUS

Version 0.1, first release. Tested on a Samsung Galaxy S10 with ATAK-CIV
5.8.0.3, release build.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/lces/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only.

  Once a day, when the device has a network, the plugin reads a small JSON
  manifest from mapdepot.takwerx.org and, only if it lists a newer lessons
  catalog, downloads it (about 600 KB). The download is checked against the
  SHA-256 digest in the manifest and against the catalog it replaces before it
  is used.

  "Open the full report" on an incident hands an https link to the device's
  browser; the plugin itself does not fetch it.

  No inbound ports. No listening sockets. No traffic to or from the TAK server,
  and no CoT is generated or consumed. With no network the plugin works in
  full from the copy it carries.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.6, 5.7 or 5.8.

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

  tools/ holds the off-device pipeline (Python 3): irpg_build.py turns the IRPG
  PDF into the pages under app/src/main/assets/irpg/, lessons_build.py builds
  the lessons catalog from the Lessons Learned Center's public service, and
  lessons_verify.py checks it. These are build-time tools and are not part of
  the plugin.

_________________________________________________________________
DEVELOPER NOTES

  Every word of the IRPG comes out of the NWCG PDF through poppler; nothing is
  typed in by hand, so a new edition is a rerun of irpg_build.py and a read of
  the diff. NWCG publications are public domain.

  The lessons layer is an ATAK feature layer, not markers: about 2,270 points
  draw without slowing the map, and a filter hides sets in the feature store
  rather than deleting them, so the layer comes back after a restart with no
  network.

  A newer catalog is used only if it is newer than the one the APK carries and
  holds at least half as many incidents as the one it replaces, so a broken
  build on the server cannot empty the map.

  Dialogs are built with ATAK's Activity context, never the plugin's, and
  there is no Spinner anywhere: a Spinner's popup built from the plugin
  context crashes ATAK.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

LCES is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify LCES and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use LCES, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers LCES's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
