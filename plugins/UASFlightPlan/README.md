ATAK Plugin — UAS Flight Plan

**Download UAS Flight Plan 0.2** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/uas-flight-plan/releases/download/v0.2/ATAK-Plugin-UASFlightPlan-0.2--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/uas-flight-plan/releases/download/v0.2/ATAK-Plugin-UASFlightPlan-0.2--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/uas-flight-plan/releases/download/v0.2/ATAK-Plugin-UASFlightPlan-0.2--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/uas-flight-plan/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/uas-flight-plan/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

A ceiling planner for the UAS pilot on a wildland fire: pick where you will
launch, draw the area the aircraft has to cover, and see from the map what
ceiling the terrain needs and what a given ceiling does to the mission,
before the radio call to Air Attack.

Capabilities:

  - Launch point: tap the map or use your position. The pane reads the
    ground there in feet MSL (0 AGL) from the elevation ATAK already holds,
    converted from the engine's ellipsoid height to mean sea level.
  - Area: a circle around the launch point (0.5 to 3 mi), or a polygon drawn
    with ATAK's own shape tool.
  - Ceiling, two ways. "Need a ceiling": the highest terrain in the area plus
    the height you fly above it (100 to 400 ft, Part 107's limit), rounded
    up, as the number to ask Air Attack for. "Given a ceiling": type the
    ceiling Air Attack assigned and see what it does to the area.
  - Islands: ground too high to work over at your height above the terrain,
    painted red inside the area, with a white edge marking what was checked.
  - FAA obstacles: towers, wire spans, turbines and stacks from the FAA
    Digital Obstacle File inside the area, drawn as masts standing at their
    height in 3D, red when their top is above the ceiling, listed nearest
    you first with distance and bearing, with a details page for each.
  - The number for the controller: the ceiling as height above the launch
    point, since a UAS reports altitude relative to takeoff.
  - The plan survives a plugin reload and an ATAK restart, with the obstacle
    list kept on the phone for when there is no network.

Everything is plugin-owned and stays on this phone: no CoT is generated and
nothing is sent to other users.

_________________________________________________________________
STATUS

Version 0.2, for feedback from UAS pilots in the field. Published for
ATAK-CIV 5.6, 5.7 and 5.8, with the user manual reachable from ATAK's Tool
Preferences.

Exercised on hardware: Samsung Galaxy XCover Pro (ATAK-CIV 5.8.0.3), with
DTED2 elevation loaded for southern California. Terrain readings checked
against an independent 30 m elevation source. Restart with no network and a
plugin reinstall both bring the plan back.

Not in this version: line of sight from the controller to the aircraft, a
clearance request card to send, and the user manual inside the plugin.

Prepared for tak.gov third-party submission.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/uas-flight-plan/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only, to one host: the FAA's public Digital
  Obstacle File feature service at services6.arcgis.com. It is queried once
  per plan, when a launch point or area is set, for the obstacles inside the
  area; the last answer is kept on the phone and reused when the host cannot
  be reached.

  No inbound ports. No listening sockets. No traffic to or from the TAK
  server, and no CoT is generated or consumed. The terrain comes from the
  elevation data already loaded in ATAK (DTED), with no download.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.6, 5.7 or 5.8.
  Elevation data loaded in ATAK for the operating area, DTED2 (30 m posts)
  or finer. Without it the plugin says so and paints nothing: a ridge
  between coarser posts would not be in the data.

_________________________________________________________________
EQUIPMENT SUPPORTED

  Any Android device supported by ATAK. No additional or external hardware,
  no sensors, no peripherals. The aircraft itself is not connected.

_________________________________________________________________
COMPILATION

  Standard ATAK plugin build. Set sdk.path in local.properties to an unpacked
  ATAK CIV SDK, then:

      ./gradlew assembleCivDebug
      ./gradlew assembleCivRelease

  ext.ATAK_VERSION in app/build.gradle selects the ATAK release to target.

_________________________________________________________________
DEVELOPER NOTES

  Altitudes are MSL throughout and say so. ATAK's elevation engine works in
  height above the ellipsoid (its altitude reference has no MSL member);
  every reading is converted with the EGM96 geoid offset at the launch
  point, which is about 34 m in southern California. A pilot reading an
  ellipsoid height as MSL would be off by that much.

  The islands overlay is a raster drawn the way the SDK's helloworld heat
  map does it: one texture pinned to the corners of the area's box,
  sampled once on a worker from a single bulk ElevationManager call, and
  recolored without resampling when the ceiling changes. Nothing is
  computed on the GL thread.

  Elevation coverage is checked before anything is painted. getElevation
  returns a number from whatever it can find, including DTED0 at a kilometer
  per post, so the metadata is probed first and anything coarser than DTED2
  is refused with a message naming what was found.

  The FAA obstacles are a read-only feature layer, not markers: each is a
  vertical line from the ground to its top and an icon at the top, both with
  a relative altitude mode so they stand up when the map is tilted. Labels
  are composed into icons (a pill above the symbol), because the label
  engine trims feature labels and cannot be told not to. One obstacle is
  three features, so the hit test is deduplicated per obstacle.

  The FAA service is queried with a form POST: an ArcGIS query carrying a
  geometry is longer than its REST gateway accepts on a GET.

  Every dialog is built on the MapView's context. A plugin resource id
  handed to a dialog on that context resolves in ATAK's own resources, so
  dialogs are given Strings, never ids.

  The plugin's launch marker is its own map item, never persisted by ATAK
  and never sent; the drawn area is one of the user's ATAK drawings, which
  ATAK brings back at every start and the plugin adopts.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

UAS Flight Plan is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify UAS Flight Plan and pass it
on, and the people you pass it to are owed the complete corresponding source of
your version under the same license. Nobody can take this, close it, and sell it
back to the emergency-services community.

**If you only install and use UAS Flight Plan, this obligation never touches
you.** Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers UAS Flight Plan's own code. It does not change the
license of the TAK Software, which stays under the TAK Software License
Agreement, and it does not cover the parts of this repository scaffolded from
the TAK-SDK plugin template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
