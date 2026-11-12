ATAK Plugin — FireCast

**Download FireCast 0.5** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/firecast/releases/download/v0.5/ATAK-Plugin-FireCast-0.5--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/firecast/releases/download/v0.5/ATAK-Plugin-FireCast-0.5--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/firecast/releases/download/v0.5/ATAK-Plugin-FireCast-0.5--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/firecast/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/firecast/blob/main/docs/USER_GUIDE.md)


_________________________________________________________________
PURPOSE AND CAPABILITIES

Satellite heat detections on the ATAK map, drawn at the ground each one actually
covers.

Every public feed draws a fire detection as a pin, and almost none of them mean
what a pin means. A VIIRS detection means "something inside this 375 m square was
hot at 23:38 Zulu". A GOES detection seen at 58 degrees off nadir means
"something inside this 4 km by 2 km patch was hot", and that is the same dot on
every other map. An operator deciding whether to drive to it needs the
difference, so FireCast draws the footprint the record itself describes and puts
the rest in words on a detail panel.

Feeds, all public NIFC services and none of them needing a key or a sign-in:

  - VIIRS: Suomi-NPP, NOAA-20 and NOAA-21, a few passes a day. The footprint is
    the sensor's own Scan and Track for that pixel, near 375 m across the swath.
  - MODIS: Terra and Aqua. Same fields, but a MODIS pixel is 1 km at nadir and
    grows past 4 km at the edge of the swath, so the drawn box grows with it.
  - GOES: GOES-18 and GOES-19, a scan every few minutes. The feed carries no
    footprint, so it is computed from the record's satellite view angle and the
    geostationary geometry; the long axis points at the satellite, because that
    is the direction the tilt stretches the pixel.
  - RADR: burn areas mapped from Landsat and Sentinel passes over the last
    fourteen days, as real polygons - where fire has been, not where it is.
  - NASA Fire Event Perimeters: NASA's Fire Event Data Suite (FEDS) draws an
    outline around every fire's VIIRS detections in the lower 48 every 12 hours,
    so a fire nobody has mapped has a perimeter. Open data on NASA VEDA, no key.
    An algorithm's 375 m outline, never presented as the incident's own.

On top of that: NIFC's static industrial heat source mask as a filter, with the
masked ground drawable so you can see what is being flagged; age as color, red
within the hour through amber to gray past a day, and fading with it; a time
window from three hours to three days; a confidence filter; and a list of what is
in the map view, newest first, that opens the same detail panel as tapping the
map. Where is it burning? lists the GOES fires and NASA perimeters in one list,
each with its county and state, in the order the operator picks.

Beside the detections, two layers of other people's fire spread runs, each a switch
in the pane with a list behind a long press and a time slider on the run you pick:

  - FIRIS Model Runs: the spread runs California's fire analysts publish for ATAK.
  - PyreCast Forecasts: the ELMFIRE forecasts PyreCast (CloudFire, Inc.) publishes
    for large fires across the country, shown with their permission, with their
    logo and a link to pyrecast.com beside them. PyreCast ranks its runs by the
    area burned at the end of the forecast; the plugin shows the middle run
    unless you pick the smaller or larger end. Forecasts are kept on the device
    and come back with no signal.

The fire model runs on the device. Start from a point, a perimeter you draw, any
shape already on the map (a FIRIS perimeter in Feature Layer, a shape a contact
sent), or any NASA perimeter, every piece of it. Set when it ignited if you know,
and use your own weather observation or a Kestrel Wx station's if you have one.
Runs stay on the device and come back after a restart with no signal. Nothing
about a run is sent anywhere unless the operator sends it.

What goes into the model, all fetched for the run from public sources:

  - Ground: LANDFIRE (USGS and the Forest Service) in 30 m cells, for a square
    4 km each way around the start (10 km for a three-hour run, up to 20 km
    each way for a large perimeter): the fuel model (one of the 40 Scott and Burgan
    models), slope and aspect, the tree canopy (cover, height, base height, bulk
    density), and the vegetation type, which tells houses, roads and parks apart
    inside LANDFIRE's single urban class. The fire moves in 15 m steps (30 m for
    three hours, 60 m for the largest squares).
  - Wind: the nearest weather station that reported in the last three hours,
    picked by distance and height (RAWS, National Weather Service, and in
    California the SCE, SDG&E, PG&E and HPWREN utility stations); the National
    Weather Service hourly forecast for the hours ahead; and, where available,
    NOAA's HRRR 3 km wind forecast, which varies across the ground and hour by
    hour. Or the operator's own observation or a Kestrel station.
  - Fuel moisture: dead fuel (1 h, 10 h, 100 h) from the Forest Service's fire
    danger figures for the nearest station, else the WFAS national grid; live
    fuel from the nearest National Fuel Moisture Database sample of the last 45
    days, else the seasonal figure.
  - Behavior: the Forest Service Fire Lab's public domain Behave library (the
    engine in BehavePlus), compiled into the plugin through the NDK: Rothermel
    surface spread, Scott and Reinhardt crown fire, Albini ember distances.
  - Spread: cell by cell over the real ground, each cell at its own fuel, slope,
    canopy and wind. Embers carry it across a road or block narrower than they
    fly; roads slow it; commercial blocks, water, bare and farmed ground stop it.
    Houses burn house to house at the rate measured in nine North American
    conflagrations (Moran, Seielstad and Pietruszka, 2025).
  - From a perimeter with a known ignition time, the model first replays the
    fire from ignition to when the perimeter was mapped, compares that with the
    area it really covered, and scales its rates to match, at most three times
    faster or slower. The page says when it did, and when it did not.
  - Predicted Fire Size: each run is repeated 30 times with the wind speed, wind
    direction and fuel moisture nudged the way forecasts miss, ranked by area
    burned. The operator picks Smallest to Largest; the rings, slider, acres,
    buildings and people all follow that pick.
  - Not in it: firefighting, wind the fire makes itself, wind bent by terrain
    finer than the forecast grid, fuel breaks narrower than 15 m, and fuel that
    has changed since LANDFIRE mapped it.

Buildings and people reached, from FEMA's national building inventory (USA
Structures, built by Oak Ridge National Laboratory for FEMA), which lists every
structure with its location, occupancy class and a population estimate:

  - Buildings: every structure in the inventory whose location the fire reaches
    by that time. A building's own cell does not burn, so a building counts when
    the fire reaches any model cell touching it.
  - Homes: the buildings among them whose FEMA occupancy class is Residential.
  - People, shown as "up to": the sum of FEMA's upper estimate (the top of its
    95% range) for each building reached, its median where there is no upper.
    The upper figure is used because the median is zero for most second homes
    and cabins, which read as nobody there beside a count of homes. It is how
    many people could be inside, not a count of who is home at that hour.
  - Named, not just counted: schools, hospitals, nursing and care homes, fire
    and police stations, hotels, churches, community centers and utility sites
    the fire reaches, with the time it reaches each.
  - If the inventory cannot be reached, the page says the buildings were not
    counted; it never shows zero for a count it does not have. Past 8,000
    buildings the page says the count is a floor.
  - FIRIS model runs are counted against the same inventory and in the same
    words; there a building counts when it is inside that hour's FIRIS
    perimeter.

The detail panel is the point of the plugin. It says how big the footprint is and
how that was arrived at, when the satellite saw it and how long it has been
burning where the feed tracks that, the radiative power and brightness
temperature, what the feed's own confidence and classification mean - and a
section headed "What this does not mean", listing the things a heat detection
does not say: that the fire is at the center of the box, that an empty map is
empty ground, that a hot pixel is a wildfire rather than a flare or a solar farm.

_________________________________________________________________
STATUS

0.6, built and signed by tak.gov for ATAK-CIV 5.6, 5.7 and 5.8. 0.6 names the
weather the model uses on the Conditions choices (your own observation, a
Kestrel, or a station). Heat detections, a fire spread model that runs on the device, and other
agencies' published forecasts, all on the ATAK map. The model gives a direction
and an order of magnitude, not a perimeter to plan on; the user guide says what
it leaves out.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, TAKwerx.

Bug reports and questions:
https://github.com/takwerx/firecast/issues

_________________________________________________________________
PORTS REQUIRED

Outbound HTTPS (TCP 443) only; nothing inbound.

  - services3.arcgis.com, services2.arcgis.com - NIFC's and FEMA's public ArcGIS
    organizations: the heat detection feeds, weather stations and the building
    inventory.
  - fire.data.nesdis.noaa.gov - NOAA's GOES fire detections.
  - edcintl.cr.usgs.gov - LANDFIRE fuel, slope and canopy.
  - api.weather.gov - National Weather Service observations and gridded forecast.
  - nomads.ncep.noaa.gov - NOAA's HRRR wind forecast.
  - fems.fs2c.usda.gov, www.wfas.net - Forest Service fuel moisture.
  - data.pyrecast.org - PyreCast forecasts (www.pyrecast.com for the link in the pane).
  - atak.s3.us-west-1.amazonaws.com - FIRIS model runs.
  - openveda.cloud - NASA's satellite fire perimeters (FEDS), for a model run's start.
  - geocoding.geo.census.gov - county and state of each fire in that list (Census Bureau).

No sign-in, no token, no credential of any kind is sent or stored. The device's
position is sent only as part of a request to the services above.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android device running ATAK-CIV 5.6, 5.7 or 5.8, with a network connection to
fetch detections and run the model. The last set fetched is kept on the device and redraws without
one, with its age on the pane.

_________________________________________________________________
EQUIPMENT SUPPORTED

Any device ATAK-CIV supports.

_________________________________________________________________
COMPILATION

Standard ATAK plugin build against the ATAK-CIV SDK:

    cp template.local.properties local.properties   # then set sdk.path
    ./gradlew assembleCivRelease

_________________________________________________________________
DEVELOPER NOTES

Detections are written into an ATAK feature store (FeatureDataStore2 /
FeatureLayer3) under atak/tools/firecast and rendered by ATAK's own feature
renderer, so every footprint stays a tappable vector feature. A refresh writes
the new feature sets before dropping the old ones, so the map never blanks, and
the store is file-backed, so a failed refresh leaves the last good picture up and
says how old it is.

Each feed is a DetectionSource producing one HeatDetection type. That is
deliberate: the differences between sensors belong in how a footprint is arrived
at, not in a second code path through the map, the list and the panel, so a new
constellation - Earth Fire Alliance's FireSat, when a public endpoint exists - is
one new class rather than a rewrite.

The numeric fields on the VIIRS and MODIS services are strings with their units
baked in and sometimes a leading space ("0.4 km", " 329.76 K", "7.39 MW"), which
Double.parseDouble throws on, and their Latitude and Longitude columns hold each
other's values - so positions always come from the feature geometry and every
value goes through Fields, which returns null rather than throwing. The feed also
carries a record dated 1789, which is its spelling of a null date.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

FireCast is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify FireCast and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use FireCast, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers FireCast's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md) -- or the Forest Service's `behave`
fire behavior library under `app/src/main/cpp/behave/`, which is public domain.
No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
