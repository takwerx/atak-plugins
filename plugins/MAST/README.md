ATAK Plugin — MAST

**Download MAST 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/mast/releases/download/v0.1/ATAK-Plugin-MAST-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/mast/releases/download/v0.1/ATAK-Plugin-MAST-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/mast/releases/download/v0.1/ATAK-Plugin-MAST-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/mast/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/mast/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

MAST, Mission Adaptable Support Tools, is the front door to the tools a mission
needs. Pick the mission (Wildfire, Tactical Response, Hazmat, Swift Water /
Flood, Search and Rescue) and MAST shows two pages for it: the ATAK tools that
job uses and the plugins that job uses. One tap opens any of them, so nobody
hunts through the whole tool menu in the middle of an incident.

MAST remembers the last mission, so its toolbar button from inside any plugin
lands back on that mission's page.

Every page can be arranged like ATAK's own toolbar: press and hold a tile, drag
it onto another to swap them, tap to replace or remove, add from a chooser that
lists ATAK's tools or the loaded plugins, or reset the page to the set MAST
ships.

An agency can set the pages from its MDM. MAST declares its settings in the
APK, so an MDM that reads plugin settings (Atlas, Plugin Pref Config) lists
them: a lock, the mission, the page shown, and one comma list per page. With
the lock set, the pages cannot be changed on the device. With no MDM, the user
arranges them.

_________________________________________________________________
STATUS

Version 0.1, first release. Built for ATAK-CIV 5.6, 5.7 and 5.8; tested on 5.8.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/mast/issues

_________________________________________________________________
PORTS REQUIRED

None. The plugin makes no network connections of any kind. It opens ATAK's own
tools and other loaded plugins through ATAK's local broadcasts, publishes no
CoT and contacts no server.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android device running ATAK-CIV 5.6, 5.7 or 5.8. The plugins a mission page
lists are installed separately; a missing one shows as a faded "Not installed"
tile that points at the TAKwerx Market.

_________________________________________________________________
EQUIPMENT SUPPORTED

Any ATAK-capable Android device. No external hardware, no sensors, no radios.

Tested on a Samsung Galaxy S10 running ATAK-CIV 5.8.0.3.

_________________________________________________________________
COMPILATION

Standard ATAK plugin build. From the plugin directory:

    ./gradlew assembleCivRelease

Requires the ATAK-CIV SDK; set sdk.path in local.properties to the unpacked SDK
matching ext.ATAK_VERSION in app/build.gradle. template.local.properties shows
the shape.

_________________________________________________________________
DEVELOPER NOTES

The missions and their base pages are data, in app/src/main/assets/missions.json.

A plugin tile opens its plugin by sending the local broadcast
"<package>.ACTION", which ATAK registers for every plugin toolbar item, so no
plugin needs to expose anything for MAST. An ATAK tool tile reads ATAK's own
action bar item (assets/actionbar/items/<name>.xml in the ATAK APK) at run time
and sends the same broadcast and extras that ATAK's menu item sends.

Pages are stored in ATAK's default preferences as comma lists under
mast_page_<mission>_<tools|plugins>; every page is written at first start, so
an exported ATAK .pref carries the whole layout. res/xml/mast_mdm.xml declares
them for MDM discovery and is deliberately not shown in ATAK's Tool
Preferences, so a user cannot turn the lock (mast_locked) off.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

MAST is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify MAST and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use MAST, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers MAST's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
