ATAK Plugin — IWI

**Download IWI 0.3** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.8:** https://github.com/takwerx/iwi/releases/download/v0.3/ATAK-Plugin-IWI-0.3--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/iwi/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/iwi/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

IWI ("Incident Within an Incident") reports and manages a medical emergency on
a wildland fire: a line injury, a vehicle accident, a burn. It follows the NWCG
Medical Incident Report in the Incident Response Pocket Guide (IRPG), in plain
civilian terms, in place of ATAK's military CASEVAC 9-line.

Capabilities:

  - Declare IWI at My Location or a point tapped on the map. The IWI is a real
    ATAK CASEVAC marker, so a device without the plugin sees a normal CASEVAC;
    the full report travels with it for devices that have IWI.
  - The Medical Incident Report as pick lists, not typing: the initial report
    (who you call, what happened, where), then follow-ups (patients, evacuation
    plan, communications, contingency, additional information). Each section
    turns green when it is complete.
  - One card per patient, from the triage counts (Red, Yellow, Green, Black),
    with the IRPG patient assessment, treatments, weight for short-haul or
    hoist, what the patient needs (trauma, burns, medical) and Transported.
  - A radio script built from the report, word for word, for the initial
    report and for each patient update.
  - Hospitals: nearest first, filtered by ER, trauma level and burn center,
    with the helipad and a flight-time estimate. At Declare the IWI marks the
    nearest ER, the nearest ER with a helipad and, as the patients need them,
    the nearest trauma center, Level I and burn center.
  - Sharing through a TAK Server Data Sync feed chosen at Declare: the IWI,
    its evacuation point and its hospitals. Clear removes them for everyone.
  - Everything the operator entered survives an ATAK restart with no network.

_________________________________________________________________
STATUS

Version 0.1, first submission, ATAK-CIV 5.8. Tested on ATAK-CIV 5.8.0.3
development builds (Samsung Galaxy S10) and with an official ATAK-CIV 5.8
device receiving the Data Sync feed.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, TAKWERX. Bug reports and requests:
https://github.com/takwerx/iwi/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) to mapdepot.takwerx.org, to download the hospital
  list (a gzipped JSON file, about 100 KB). It is downloaded once and then
  kept on the device; IWI works with it offline.

  The configured TAK Server, over ATAK's own connections only: CoT for the
  IWI markers, and the TAK Server Marti API (HTTPS, the server's API port,
  usually 8443) to list Data Sync feeds and add or remove the IWI's items.
  Only when the operator chooses a feed.

  No inbound ports. No listening sockets.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.8.
  A TAK Server with Data Sync (missions) to share an IWI. Without a server,
  IWI works on the device alone.

_________________________________________________________________
EQUIPMENT SUPPORTED

  Any Android device supported by ATAK. No additional or external hardware.

_________________________________________________________________
COMPILATION

  Standard ATAK plugin build. Set sdk.path in local.properties to an unpacked
  ATAK CIV SDK, then:

      ./gradlew assembleCivDebug
      ./gradlew assembleCivRelease

  ext.ATAK_VERSION in app/build.gradle selects the ATAK release to target.

_________________________________________________________________
DEVELOPER NOTES

  The IWI marker is ATAK's CASEVAC (type b-r-f-h-c with the _medevac_ detail)
  plus an _iwi_ detail carrying the report. ATAK keeps a detail it has no
  handler for and sends it on unchanged, so the report survives a device
  without IWI.

  Hospital markers use ATAK's built-in Responder Icons hospital icon, so every
  device draws them. On a device with IWI each one also gets a white-on-red
  label drawn by the plugin.

  The hospital list is built off the device from state trauma and burn center
  lists, hospital locations and FAA heliports. It is not part of the plugin.

_________________________________________________________________
LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

IWI is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify IWI and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use IWI, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers IWI's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
