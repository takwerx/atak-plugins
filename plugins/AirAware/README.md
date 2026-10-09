ATAK Plugin — AirAware

**Download AirAware 0.5** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/air-aware/releases/download/v0.5/ATAK-Plugin-AirAware-0.5--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/air-aware/releases/download/v0.5/ATAK-Plugin-AirAware-0.5--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/air-aware/releases/download/v0.5/ATAK-Plugin-AirAware-0.5--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/air-aware/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/air-aware/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

AirAware answers one question for a crew working under or in the air: what is
in the column of air I am in, and what am I allowed to do here. It is built for
wildfire aviation and UAS crews and draws everything on the operator's own
device, with no TAK Server and no Data Sync mission in the path.

Seven layers, each switched on its own from the front page:

  TFR                 FAA Temporary Flight Restrictions, drawn as 3D volumes
                      from the published floor to the published ceiling, red
                      when in effect and amber when scheduled.

  NOTAMs              What is going on in the air right now that no chart
                      shows: drone operations, parachute drops, towers with
                      their lights out, cranes, closed runways. Read every
                      three minutes from the FAA NOTAM Management Service by
                      a takwerx relay and published as tiles; the phone never
                      holds an FAA credential. Colored by kind, with the FAA
                      text as issued on the details page.

  Airspace            Class A through G, one shelf per published step, so the
                      upside-down wedding cake over a Class B airport is drawn
                      as the twelve shelves the FAA publishes rather than one
                      ring. Sectional chart colors: B and D blue, C and E
                      magenta, with Class E taking its color from how low it
                      comes down.

  Special Use         Prohibited, Restricted, Warning and Alert areas and
                      Military Operations Areas, with whether each is
                      continuous or activated by NOTAM.

  UAS ceilings        The FAA UAS Facility Map: how high a drone may fly
                      without an authorization, as a grid colored green
                      through red. The pinned status line says it in words for
                      wherever the operator is standing.

  Obstacles           The FAA Digital Obstacle File — towers, wire spans,
                      transmission towers, turbines and stacks — standing at
                      their published height with the height on a label.

  METARs              Observations as flight-category chips in the
                      aviation convention: green VFR, blue MVFR, red IFR,
                      magenta LIFR, as published and never derived.

Beside the layers there is a UAS flight planner: a launch point and a circle or
a drawn area, terrain above the ceiling painted as islands, obstacle headroom,
and the ceiling worked out two ways — given one, see what reaches it; or need
one, and be told the number to ask for. It links out to where a LAANC
authorization is filed and to Flight Service for a NOTAM, with the plan's own
numbers filled in.

Everything downloaded survives a restart with no network. Switching a layer off
hides it and never deletes it.

_________________________________________________________________
STATUS

Version 0.4, for ATAK-CIV 5.6.0, 5.7.0 and 5.8.0. A new icon, and a geofence
made from a restriction is now drawn where it alerts: from the ground up to the
published ceiling. Before, the 3D shape stood on sea level, so over high ground
it was drawn inside the hill even though the alert itself was right.

Version 0.3 was the first release.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/air-aware/issues

_________________________________________________________________
PORTS REQUIRED

Outbound HTTPS (TCP 443) only. AirAware opens no listening port, accepts no
inbound connection, and sends nothing to a TAK Server.

  tfr.faa.gov               FAA Temporary Flight Restriction list and the
                            per-restriction XNOTAM detail documents.

  aviationweather.gov       METAR observations, by bounding box.

  mapdepot.takwerx.org      Airspace, obstacle and UAS facility map tiles,
                            published by TAKWERX from the FAA's own public
                            data. These are read as static files; no credential
                            is held or sent, and nothing about the device or
                            its position is transmitted.

The plugin is a reader. It sends no telemetry, no position, and no identifying
information to any host. Requests are plain GETs for public aeronautical data,
and every fetch is refused unless the scheme is HTTPS.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android device running ATAK-CIV 5.8.0, and network access for the first
download of each area. After that the plugin works offline on what it holds.

_________________________________________________________________
EQUIPMENT SUPPORTED

No external hardware. The flight planner uses the device's own position and
ATAK's elevation data; DTED2 coverage is needed for the terrain and ceiling
calculations, and the plugin says so plainly where it is missing rather than
computing from coarser data.

_________________________________________________________________
COMPILATION

A standalone Gradle project built against the ATAK-CIV SDK, as scaffolded from
the SDK's plugintemplate sample.

  ./gradlew assembleCivDebug
  ./gradlew assembleCivRelease

local.properties must carry sdk.path pointing at the ATAK-CIV SDK matching
ext.ATAK_VERSION in app/build.gradle. template.local.properties shows the
shape.

_________________________________________________________________
DEVELOPER NOTES

Aeronautical data is advisory. AirAware makes no warranty of accuracy or
timeliness; always check current charts and NOTAMs. The plugin says in words
where its picture is incomplete — when a layer is gated by zoom, when a list is
trimmed, when nothing has been downloaded for an area, and when it is showing
what the device saved rather than something current.

Airspace, obstacle and UAS facility map data is the FAA's, redistributed as
tiles under its published terms. Acknowledgement of the FAA does not imply FAA
endorsement, and the FAA seal and initials are not used.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

AirAware is free software under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**, with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
it may be combined with and distributed alongside ATAK.

Files derived from the ATAK-CIV SDK's plugintemplate remain under the TAK
Product Center's own license; LICENSE-EXCEPTION.md lists them.

**If you only install and use AirAware, this obligation never touches you.**
The AGPL's source-offer requirement applies to anyone who modifies it and
conveys it, or runs a modified version as a network service.

**Scope.** The AGPL covers AirAware's own code. It does not change the license
of ATAK, of the TAK SDK, or of the FAA data the plugin displays.

Contributions: see CONTRIBUTING.md and CLA.md.
