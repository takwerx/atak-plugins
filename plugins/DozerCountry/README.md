ATAK Plugin — Dozer Country

**Download Dozer Country 0.2** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/dozer-country/releases/download/v0.2/ATAK-Plugin-DozerCountry-0.2--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/dozer-country/releases/download/v0.2/ATAK-Plugin-DozerCountry-0.2--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/dozer-country/releases/download/v0.2/ATAK-Plugin-DozerCountry-0.2--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/dozer-country/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/dozer-country/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

Where a dozer can work, drawn on the ATAK map. Draw an area and the ground inside
it is shaded by the slope limits a dozer is held to, so a Division Supervisor,
dozer boss or plans chief can answer "can we put a cat in there" from the map
rather than from memory of the ground.

The bands are the refusal points in USFS/NWCG S-232 Dozer Boss, Appendix D:
dozers should not be operated across slopes (sidehill) over 45 percent, uphill
over 55 percent, or downhill over 75 percent.

  - 0-45%     within all three limits
  - 46-55%    over the sidehill limit; up and down only, not across
  - 56-75%    over the uphill limit; downhill only, at the last limit
  - over 75%  over every limit in the guide

Capabilities:

  - Draw an area of interest with ATAK's own polygon tool and get the slope
    inside it classed against the standard. The color stops at the ring you
    drew, not at a box around it.
  - Exceptions only: ground inside every limit is left unshaded so the basemap
    shows through, because an overlay should not spend the screen saying "fine".
  - Each cell is classed by the WORST slope within one chain (66 ft), not by its
    own value, so an isolated flat spot inside a steep face is not called
    workable.
  - Slope is computed from the elevation already on the device. The plugin
    downloads nothing and reaches no network at all.
  - It refuses rather than guesses. Without DTED2 (30 m posts) or better over
    every part of the area it declines, and says what it found and what to load.
  - Water is left unclassed rather than painted as workable ground.
  - The standard ships as a readable JSON asset, not as constants, so the numbers
    a color is claiming can be audited by the person whose ground it is.
  - An on-screen legend using ATAK's own gradient widget, and a panel that states
    the cell size, the window actually used, and anything it could not class.

Limitations, stated on screen as well as here: this is slope steepness only, read
as worst case. It does not know the direction a line will run, nor soil, rock,
moisture or fuel. The 45% boundary is the cross-slope limit, so unshaded ground
is under every limit whichever way the machine sits; ground shown at 46-55% may
still be workable straight up and down. It is a guideline from the guide, not a
clearance.

_________________________________________________________________
STATUS

Version 0.1, first release. Built and tested against ATAK-CIV 5.6, 5.7 and 5.8.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/dozer-country/issues

_________________________________________________________________
PORTS REQUIRED

None. The plugin makes no network connections of any kind. It reads the elevation
already present on the device through ATAK's own elevation API and writes nothing
outside its own APK assets. It publishes no CoT and contacts no server.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android device running ATAK-CIV 5.6, 5.7 or 5.8, with DTED Level 2 (30 m) or
better elevation loaded for the ground of interest. Elevation can be loaded
through ATAK's own elevation manager or with the Map Depot plugin.

Without DTED2 the plugin does not draw; it says so and names what to load.

_________________________________________________________________
EQUIPMENT SUPPORTED

Any ATAK-capable Android device. No external hardware, no sensors, no radios.

Tested on a Samsung Galaxy XCover Pro running ATAK-CIV 5.8.0.3.

_________________________________________________________________
COMPILATION

Standard ATAK plugin build. From the plugin directory:

    ./gradlew assembleCivRelease

Requires the ATAK-CIV SDK; set sdk.path in local.properties to the unpacked SDK
matching ext.ATAK_VERSION in app/build.gradle. template.local.properties shows
the shape.

Unit tests cover the slope arithmetic and need no device:

    ./gradlew testCivDebugUnitTest

_________________________________________________________________
DEVELOPER NOTES

The overlay is a plain ARGB raster on an AbstractLayer with four corner points,
drawn by a GLAbstractLayer as one GLTexture, following the SDK's own helloworld
SimpleHeatMapLayer sample. ATAK's HeatMapOverlay and ElevationHeatmapLayer cannot
be used for this: both color by HSV across an elevation range with no hook for a
classifier, and GLHeatMap's constructor and parameter object are package-private.

Elevation comes from one bulk ElevationManager.getElevation(Iterator, double[],
QueryParameters, Hints) call on a worker thread, using MODEL_TERRAIN so the
values are bare earth rather than canopy. Nothing is recomputed on map movement,
so no slope arithmetic can reach the GL thread.

Data adequacy is checked before anything is drawn, by asking ATAK what source it
would actually use: getElevationMetadata returns a GeoPointMetaData whose
altitude source is one of ATAK's own constants, probed on a grid inside the drawn
ring. DTED2, DTED3, SRTM1 and LIDAR are accepted; DTED0 and DTED1 are not, since
a 45% boundary decided from 100 m or 1000 m posts is a number with no ground
under it.

The overlay is owned by the plugin for its lifetime rather than by a Tool or by
the panel, because ATAK ends the active tool whenever another starts, a dropdown
opens, or Back is pressed.

_________________________________________________________________
LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

DozerCountry is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify DozerCountry and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use Dozer Country, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers Dozer Country's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
