ATAK Plugin — Signal DF

**Download Signal DF 0.3** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/signal-df/releases/download/v0.3/ATAK-Plugin-SignalDF-0.3--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/signal-df/releases/download/v0.3/ATAK-Plugin-SignalDF-0.3--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/signal-df/releases/download/v0.3/ATAK-Plugin-SignalDF-0.3--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/signal-df/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/signal-df/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

Signal DF puts RF direction-finding bearings from a KrakenSDR onto the ATAK map.

The operator has a KrakenSDR — five coherent RTL-SDR receivers and a Raspberry
Pi running the open-source `krakensdr_doa` software — on a vehicle or at a fixed
site. It produces a direction of arrival several times a second. Today that
lives in a web page on the Pi, or is relayed into TAK by a script on a laptop.
This plugin reads it directly and draws it where the crew is already looking.

Intended for search and rescue on a beacon or a handheld, interference hunting
(a stuck mic, a keyed repeater, a transmitter desensing a fire channel), and
locating an unknown transmitter on a tactical channel.

Version 0.2 does the whole hunt. It connects to the radio and draws a bearing
per active VFO with its provenance stated; it collects bearings while the
vehicle drives, crosses them into a position with a 95% error ellipse, draws
every bearing it collected so a reflection is visible as the one line missing
the crowd, and puts a waypoint where driving next will tighten the answer
fastest. Bearings can be transmitted live to a TAK Server Data Sync feed, with
a stale time, so a team sees them and nothing is left stored.

It finds the radio whichever output mode it was left in, falling back between
the interfaces by itself. It states in words where the array heading came from,
takes that heading from ATAK's own GPS track when the array is vehicle-mounted,
and draws nothing at all on the map when nothing knows which way the array
points. It sizes the antenna array for a frequency, naming the hole on
KrakenRF's printed arms, the position on the 3D-printed template and the plain
measurement, and says how long to extend each whip. It shows the age of the
newest bearing, greys the line when the feed goes stale, and distinguishes a
radio that has stopped answering from one that is answering but not producing.

It also opens the radio's own web interface inside ATAK, so tuning, gain,
squelch and calibration are reachable without leaving the map application.

Nothing leaves the device. Bearings are drawn locally and are not published to a
TAK server or to other users.

Planned: the power lobe from the DoA spectrum, a weighted maximum-likelihood fix
with an error ellipse and advice on the collection geometry, and bearings shared
between several operators so one crew's receivers all contribute to one fix.


_________________________________________________________________
STATUS

Version 0.3 — beta, for field use and feedback.

Runs on ATAK-CIV 5.6, 5.7 and 5.8.

Verified against a KrakenSDR running `krakensdr_doa` 1.8.1: the plugin finds an
available feed by itself, falls back when the preferred one is absent, reads
live direction-of-arrival data, and reports staleness correctly. The finding
loop — collect while driving, cross the bearings, draw the fix and its error
ellipse — is verified end to end against a simulated radio driving a known
route past a known transmitter, and by 60 unit tests over the arithmetic.
ATAK's Bloodhound navigates to the fix.

0.3 replaces the single "drive here" waypoint with a band: an arc drawn round
the estimate that asks the operator to swing round it and close on it at the
same time, since angle width and range are the two quantities the published
work names. Its thresholds and wording come from FM 24-18, which defines a fix
as three or more bearings, and from Haskell and Ballard (2007), who put best
accuracy at 90-100 degrees of angle width; they live in one class so the map,
the pane and the notifications cannot disagree. Driven against a simulated
vehicle that turns, rather than one that only goes straight, which is the
only way guidance of this kind can be exercised at all.

Known limitation, stated plainly, and it governs everything else here: the
absolute bearing convention has not been validated against a transmitter at a
measured bearing. The two interfaces the radio exposes disagree by a mirror,
and which one corresponds to a compass bearing is taken from the radio's own
source and from agreement with an independent implementation, not from a
controlled measurement. Every fix, ellipse and shared bearing carries that
caveat in its remarks. Directions are useful for hunting and should not be
treated as surveyed until that validation is done.

Not yet built: the grid heatmap, which is what handles two transmitters on one
frequency and heavy multipath. The bearing trace shows the same information to
the eye in the meantime.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/signal-df/issues


_________________________________________________________________
PORTS REQUIRED

All traffic is outbound from the EUD to the operator's own KrakenSDR receiver on
the local network. The plugin opens no listening ports, requires no inbound
access, and contacts no internet host.

| Direction | Protocol | Port | Purpose |
|---|---|---|---|
| Outbound to the receiver | TCP / HTTP | 8081 | Reads the direction-of-arrival status files the radio publishes (`doa.xml`, `DOA_value.html`) |
| Outbound to the receiver | TCP / HTTP | 8080 | Opens the radio's own configuration web interface, on operator request only |

Traffic is plaintext HTTP because that is the only service the KrakenSDR
offers. Those status files are unauthenticated on the radio itself; no
credentials, position data or mission data are transmitted to it. The host
address is entered by the operator and is validated to be a host name or IP
address only, so no path, query or alternate scheme can be introduced through
it.

The plugin requests no Android permissions of its own.


_________________________________________________________________
EQUIPMENT REQUIRED

- A KrakenSDR five-channel coherent receiver with its own power supply.
- A Raspberry Pi 4 or 5 running the KrakenRF `krakensdr_doa` image.
- Five identical antennas on identical lengths of coaxial cable, in the array
  geometry the radio is configured for.
- An Android EUD running ATAK-CIV, on the same network as the receiver.


_________________________________________________________________
EQUIPMENT SUPPORTED

- KrakenSDR, `krakensdr_doa` 1.8.1. Earlier versions are expected to work; the
  plugin reads the interfaces that software has published across releases and
  falls back between them.
- Uniform circular and uniform linear antenna arrays, as configured on the
  radio.
- Receiver reached over its own WiFi access point, over a shared network, or
  over a wired connection to the vehicle's router.


_________________________________________________________________
COMPILATION

Standard ATAK plugin build. Requires the ATAK-CIV SDK; set `sdk.path` in
`local.properties` to the SDK matching `ext.ATAK_VERSION` in
`app/build.gradle`.

    ./gradlew assembleCivDebug
    ./gradlew assembleCivRelease

Unit tests cover the wire-format parsing and the bearing arithmetic and run
without a device:

    ./gradlew testCivDebugUnitTest


_________________________________________________________________
DEVELOPER NOTES

The radio exposes more than one interface and they do not agree with each other.
The same direction of arrival, in the same frame, is written as `theta` on one
and as `360 - theta` on another, and the scaling of frequency, confidence and
power differs as well. Each feed adapter therefore owns its own convention and
its own scaling; a single shared parser would be wrong about half the time, and
a mirrored bearing renders as a confident line pointing the wrong way rather
than as anything that looks like a fault.

The radio does not fold its heading into the bearing on any interface. It
reports heading separately, and reports zero both when the array faces true
north and when it has no heading at all. The plugin therefore treats the array
heading as a value plus its provenance, never a bare number, and says which
claim it is making.

A receiver that is powered but not acquiring still serves a well-formed status
file, frozen at one value. The plugin treats an unchanged response as the same
frame served again rather than a new measurement, so a stopped receiver cannot
present as a live one.

Where the operator's device is joined to the receiver's own network, that
network has no internet, and joining it can make it the device's default route.
The plugin binds its own requests to that network explicitly and leaves the
default route alone, so the EUD keeps its normal connectivity for everything
else.

Signal DF is not a KrakenRF product and carries none of their branding. It
speaks the interfaces their software publishes. KrakenRF's `krakensdr_doa` is
licensed GPL-3.0; no code from it is included here.


LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

Signal DF is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify Signal DF and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use Signal DF, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers Signal DF's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

The KrakenSDR and `krakensdr_doa` are by KrakenRF. Signal DF is not a KrakenRF
product and carries none of their branding -- it speaks to the radio over the
interfaces their software already publishes.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
