ATAK Plugin - IPAWS Alerts


_________________________________________________________________
PURPOSE AND CAPABILITIES

Public emergency alerts on the ATAK map, with the filter chosen on the device
rather than once for a whole server.

A TAK server can already publish one alert picture to everyone on it. This is
the other half of that: an engine, a division or a strike team sets its own
states, counties and alert types on the phone, in the field, whether or not
there is a server in the path.

  - States and territories, any number of them.
  - Counties inside a state, where a county is a TOUCH and not a filing: an
    alert shows if its area reaches the county, whether or not the National
    Weather Service filed it under that county. A marine zone names no county at
    all, and a forecast zone that laps a county line is filed however NWS chose.
  - Alert types by category - Fire, Civil and Emergency, Marine, Winter and
    Cold, and nine more - all on to begin with, so an operator who does not want
    marine switches off one control rather than untick twenty type names. Each
    category can then be narrowed to particular types.
  - CAP severity, defaulting to Extreme and Severe on a fresh install.
  - A list, worst first, with how long each alert has left, and tap-to-details
    carrying the issuing office's headline, what is happening and what to do.
  - Notifications, off by default, at the severities the operator picks.

Everything it draws is local to the device. Nothing is published, and there is
no path by which this plugin puts an alert on another phone.

The alerts come from the National Weather Service: NWS's own products plus the
non-weather messages NWS relays, which is where Evacuation Immediate, Civil
Emergency Message, Local Area Emergency and Child Abduction Emergency come from.
It is not the FEMA IPAWS-OPEN aggregator, which carries state and local
originators and requires a signed agreement with FEMA.

_________________________________________________________________
STATUS

0.1, in development. Not yet released.

_________________________________________________________________
POINT OF CONTACTS

TAKWERX. Issues and questions through the plugin's GitHub repository.

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

A position fix is used once, on a fresh install, to preselect the state the
device is in. Without one the plugin selects nothing and says so.

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
altitude sink under terrain on zoom.

Most alerts carry no geometry of their own - measured at 312 of 337 - and name
the forecast zones they cover instead. Those zones are fetched one at a time
(the bulk zone endpoint answers 200 with null geometry, silently) using the URL
the alert supplied verbatim, so the zone type is never guessed, and they are
cached on the device. A poll that fails never blanks the overlay; the last good
set stays and the status line gives its age.

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
