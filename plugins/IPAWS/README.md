ATAK Plugin - IPAWS Alerts


_________________________________________________________________
PURPOSE AND CAPABILITIES

Public emergency alerts on the ATAK map, with the filter chosen on the device
rather than once for a whole server.

A TAK server can already publish one alert picture to everyone on it. This is
the other half of that: an engine, a division or a strike team sets its own
states, counties and alert types on the phone, in the field, whether or not
there is a server in the path.

  - States, territories and offshore waters, any number of them, picked one by
    one or a region at a time.
  - Counties inside a state, where a county is a TOUCH and not a filing: an
    alert shows if its area reaches the county, whether or not the National
    Weather Service filed it under that county. A marine zone names no county at
    all, and a forecast zone that laps a county line is filed however NWS chose.
  - Alert types by category - Fire, Civil and Emergency, Marine, Winter and
    Cold, and nine more - all on to begin with, so an operator who does not want
    marine switches off one control rather than untick twenty type names. Each
    category can then be narrowed to particular types.
  - CAP severity as a filter, all of them on a fresh install.
  - Each alert type drawn in the National Weather Service's own color for it,
    the table every public weather map uses, with a map key listing only the
    types on the map.
  - Map controls: an Alerts ON / OFF switch that shows its state, a zoom gate ("draw when the
    scale bar reads 5 mi or closer"), and a distance limit - within N miles of
    My Location or of the map center, or what is in view.
  - A list grouped by alert type, worst first, with how long each alert has
    left, and tap-to-details carrying the issuing office's headline, what is
    happening and what to do. Tapping an area on the map opens the same page.
  - Notifications, off by default: at chosen severities, in the operator's
    states or within N miles of the device, new alerts only or updates too, on
    their own high-importance Android channel. Tapping one opens that alert.

Everything it draws is local to the device. Nothing is published, and there is
no path by which this plugin puts an alert on another phone.

The alerts come from the National Weather Service: NWS's own products plus the
non-weather messages NWS relays, which is where Evacuation Immediate, Civil
Emergency Message, Local Area Emergency and Child Abduction Emergency come from.
It is not the FEMA IPAWS-OPEN aggregator, which carries state and local
originators and requires a signed agreement with FEMA.

_________________________________________________________________
STATUS

0.2, in development. Not yet released.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/ipaws-alerts/issues

_________________________________________________________________
PORTS REQUIRED

Outbound HTTPS (TCP 443) to api.weather.gov only.

The plugin refuses any request that is not HTTPS, does not follow redirects, and
will not fetch a URL from any other host - including URLs that arrive inside a
response body, which is how zone geometry is referenced. No inbound ports, no
listeners, and no traffic to a TAK server.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android device running ATAK-CIV with a network path to api.weather.gov.

_________________________________________________________________
EQUIPMENT SUPPORTED

Any ATAK-CIV device. No external hardware, no radios, no sensors.

A position fix is used on a fresh install to preselect the state the device is
in, and, only when the operator chooses it, to measure "within N miles of My
Location" for the map or for notifications. It never leaves the device. Without
a fix the plugin says so: it selects nothing on a fresh install, and measures
from the map center instead of My Location.

_________________________________________________________________
COMPILATION

Standard ATAK plugin build. Set sdk.path in local.properties to an unpacked
ATAK SDK matching ext.ATAK_VERSION in app/build.gradle, then:

    ./gradlew assembleCivDebug
    ./gradlew assembleCivRelease

_________________________________________________________________
DEVELOPER NOTES

Alert areas are ATAK features in a store this plugin owns, registered with
addOverlay so they appear in the Overlay Manager under "IPAWS Alerts" with one
entry per severity. Every feature is ClampToGround, or polygons carrying any
altitude sink under terrain on zoom, and every area is flattened to a single
level of geometry collection before it is stored: FeatureSetDatabase2 writes a
collection nested inside another as a point at 0,0, which lost 15% of areas.

The colors are data/EventColors.java, generated from weather.gov/help-map by
the verifier, which also fails the release gate when a live event type has no
color or a color has drifted from what NWS publishes.

The zoom gate is applied by the plugin on settled map moves, not as the feature
sets' resolution range: ATAK's renderer tests that range against its own draw
resolution, rounded to a tile level, and hid alerts at zooms the status line
called close enough. The ON / OFF switch and the gate share one visibility path.

Most alerts carry no geometry of their own - measured at 312 of 337 - and name
the forecast zones they cover instead. Those zones are fetched one at a time
(the bulk zone endpoint answers 200 with null geometry, silently) using the URL
the alert supplied verbatim, so the zone type is never guessed, and they are
cached on the device, each zone's extent also kept in memory so a distance-
scoped rebuild rejects far alerts without opening their files. An alert none of
whose zones will resolve is drawn at its state's center, from the same table the
infra-TAK Node-RED flow uses. A poll that fails never blanks the overlay; the
last good set stays and the status line gives its age.

Counties are filtered on the device rather than asked of the server. Alerts are
issued against forecast zones, not counties, but every alert carries the
counties it covers in geocode.SAME, so a county toggle re-filters the set
already in hand instead of costing a request. Where SAME cannot settle it, the
alert's assembled area is intersected with the county polygon.

The poll lives in a component that lasts the plugin's life, never in a Tool:
ATAK ends the active tool whenever another starts or a dropdown opens.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

IPAWS is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify IPAWS and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use IPAWS, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers IPAWS's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
