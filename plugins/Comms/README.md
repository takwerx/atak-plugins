ATAK Plugin — Comms

**Download Comms 0.7** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/comms/releases/download/v0.7/ATAK-Plugin-Comms-0.7--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/comms/releases/download/v0.7/ATAK-Plugin-Comms-0.7--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/comms/releases/download/v0.7/ATAK-Plugin-Comms-0.7--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/comms/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/comms/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

Mountaintop radio sites on the ATAK map, nearest first, with the nets that live on
each one and the tone that opens each repeater. Answers "what is the closest
Command 5 repeater, what mountain is it on, and what tone does it use" from a
side pane, and works out which of those sites a handheld where you are standing
is likely to reach.

Capabilities:

  - Radio sites from the Cal OES FIRENET and CESRS relay layers, the USFS
    communications-site layer, and the operator's own rows, with county and
    ground elevation.
  - The nets on each site, with the tone that opens that repeater, as Hz and as
    the California tone number ("Tone 8 (103.5)"). One tone per site: the one a
    radio transmits to key that mountain.
  - Search by site name, net name or designator, tone or callsign, and loosely:
    "cdf command 5", "CDF C5" and "cdf 5" all find the same net. When the search
    names a net, each row shows that net's line at that site, so the nearest one
    is readable from the list.
  - Nearest first, measured from the device or from the map center, within a
    chosen radius, filtered by agency with counts.
  - Site details: county, elevation, distance and bearing, and every net on it.
  - Which sites a handheld where you are standing is likely to reach: the
    terrain between is walked over the device's elevation data, the worst
    obstruction is scored for diffraction loss and weighed against a handheld's
    link budget, and each site comes back likely, marginal or unlikely. A likely
    site lights up green on the map and heads the list. A guide, not a
    prediction, and labeled as such. There is deliberately no viewshed anywhere:
    drawn from a repeater it reads as a coverage map and is not one, and drawn
    from the operator its answer moved with the range setting.
  - Catalog refreshed from the depot host once a day and on demand, with the
    built-in copy and the last download as fallbacks. Adding a site or a net is
    a catalog change, not a plugin release.

_________________________________________________________________
STATUS

0.7 is the first public release: 387 California sites, 212 nets and 1,024
channels, each site carrying at least one net and the tone that opens it, with
the reach check that says which of them a handheld where you are standing is
likely to open. More states follow as their site data is added; the catalog
format already allows it, and adding a site is a catalog change rather than a
plugin release.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/comms/issues

_________________________________________________________________
PORTS REQUIRED

Outbound HTTPS (TCP 443) to mapdepot.takwerx.org for the catalog. Nothing
inbound. The plugin works offline from its built-in catalog.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android device running ATAK-CIV 5.6, 5.7 or 5.8. Elevation data on the
device (DTED, for example from Map Depot) for the line-of-sight check.

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

A site shows one tone: the one a radio transmits to open that repeater. A call
plan prints two, but the other belongs to the net and is the same at every site
on it, so it can never tell an operator which mountain to use. Where a tone is
not held, the row says which kind of nothing it is -- a digital trunked channel
has no CTCSS tone at all, a plan can withhold one -- rather than implying the
site sets it. A site is never operator-selectable; a repeater has one fixed tone.

There is no viewshed. ATAK's ViewShedReceiver samples on a 501 x 501 grid
whatever the radius, so a cell runs from about 32 m at five miles to about 320 m
at fifty, and the same mountain changed from visible to hidden with the range
setting alone. Reach answers the question instead, entirely on the device: the
terrain profile is walked over the elevation data, lowered by the earth's bulge
at a 4/3 radius, and the worst obstruction is scored with the Fresnel-Kirchhoff
parameter and turned into a loss by the ITU-R P.526 single knife-edge
approximation. That plus free-space loss is weighed against what a 5 W handheld
into a mountaintop repeater can afford, and the site comes back likely, marginal
or unlikely. It is a guide: bare earth, one obstruction, a nominal VHF
wavelength and a nominal handheld.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

Comms is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify Comms and pass it on, and
the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use Comms, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers Comms's own code. It does not change the license of
the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

The catalog's data comes from public records published by Cal OES, the USDA
Forest Service and the USGS, each named in `catalog.json` and on the plugin's
About screen.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms, the row-file format for adding sites and nets, and the
[Contributor License Agreement](CLA.md).
