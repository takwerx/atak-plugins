#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "FireCast",
   plugin-version: "0.2",
   platform: "ATAK",
   platform-version: "5.7.0",
)

#tak-slide[
= Overview

#toolbox.side-by-side(columns: (1.5fr, 9fr))[
  #image("plugin_icon.png", width: 85%)
][
FireCast puts satellite heat detections on the ATAK map and runs a fire spread
model on the device, from a point you tap or a perimeter you draw. It also shows
other agencies' published fire forecasts beside your own runs.
]

#v(6pt)

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("01.png", width: 62%)
][
  Everything starts on this one pane: the detections in view, Where is it
  burning?, Run a Model, and folded rows for Models, Feeds, Filters and the
  map key. "In view" is the map you can see, not the part behind the pane.

  #v(6pt)

  Switches read ON in green or OFF in red.
]
]

#tak-slide[
= Before you start

- *Match the plugin to your ATAK version.* Builds are tied to the ATAK release
  they were built against; 5.6, 5.7 and 5.8 builds are published.
- *It needs a network to fetch and to run the model.* Outbound HTTPS on port
  443 only, to public agency services. Nothing is sent to a TAK server and no
  CoT is generated.
- *What it fetched stays on the device.* Runs, the last detections and the
  forecasts come back after a restart with no signal, with their age shown.

#v(6pt)

Load it from ATAK's Plugins manager (TAK Package Mgmt) like any other plugin,
then open it from the toolbar.
]

#tak-slide[
= Heat detections

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("02.png", width: 96%)
][
  - *VIIRS:* three satellites, a few passes a day. Drawn as the square the
    sensor saw, about 375 m across.
  - *MODIS:* 1 km at the center of the swath, over 4 km at the edge, and the
    drawn box grows with it.
  - *GOES:* a scan every few minutes; the footprint is worked out from the
    viewing angle.
  - *RADR:* burned areas from the last fourteen days, real outlines of where
    fire has been.
]
]

#tak-slide[
= Detections on the map

#image("16.jpg", width: 92%)

Three VIIRS detections, each the 375 m square the sensor saw, inside one GOES
detection, worked out from the satellite's viewing angle. The feeds list gives
each feed's count in view.
]

#tak-slide[
= Reading a detection

Detections are colored by age: red within the hour, through amber, to gray past
a day, fading with it. *Filters* sets the time window, the confidence and
whether to hide known industrial heat sources. *Map key* explains the colors.

#v(6pt)

Tap a detection on the map or in the list for its detail panel: how big the
footprint is and how that was worked out, when the satellite saw it, and the
radiative power.

#v(6pt)

*What this does not mean* is on every panel. A heat detection is a hot pixel. It
does not say the fire is at the center of the box, that an empty map is empty
ground, or that a hot pixel is a wildfire and not a flare or a solar farm.
]

#tak-slide[
= A detection up close

#image("17.jpg", width: 88%)

How big the footprint is and how that is known, which satellite and when.
]

#tak-slide[
= NASA Fire Event Perimeters

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("19.jpg", width: 90%)
][
  A feed like the others: NASA's Fire Event Data Suite draws an outline around
  each fire's VIIRS detections every 12 hours, so a fire nobody has mapped has
  a perimeter. Magenta, brighter while active.

  #v(4pt)

  375 m steps, up to a day old, and not the incident's mapped perimeter. It
  always looks back three days.
]
]

#tak-slide[
= A NASA perimeter up close

#image("20.jpg", width: 88%)

Its acres, when NASA drew it, and what it is not. *Run a Model from this
perimeter* starts a run from that outline.
]

#tak-slide[
= Where is it burning?

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("21.png", width: 58%)
][
  One list: GOES fires burning now, then NASA perimeters with no GOES fire in
  them, then fires seen in one scan only (often burn piles).

  #v(4pt)

  Pick the order and how far back above the list. Tap one and the map goes
  there; the list stays open.
]
]

#tak-slide[
= Run a Model

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("22.png", width: 96%)
][
  Tap *Run a Model*, then *Start from*:

  - *A point:* tap the map. Drag the marker to make it exact.
  - *NASA satellite perimeter:* pick a fire in the map view.
  - *A perimeter you draw* with ATAK's shape tool.
  - *A shape on the map:* tap one, drawn, sent, or a Feature Layer perimeter.

  Then the settings:

  - *Conditions:* as observed, at the gust, a dry day, or dry with 20 mph wind.
  - *Weather:* a station, your own observation, or a Kestrel station.
  - *Ignition:* when it started; the rings run from then.
  - *Horizon:* one or three hours ahead.

  Then *Run model*.
]
]

#tak-slide[
= Your own weather and the ignition

*Weather* takes what you measured at the fire: wind, direction, gust,
temperature, humidity and the 1 h fuel moisture. An eye-level wind (a hand-held
meter) is taken as the wind at the flames. *From a Kestrel station* lists the
stations the Kestrel Wx plugin shares, ready to check and use. An observation
is used while under six hours old.

#v(6pt)

*Ignition* is when the fire started: minutes ago, a clock time, or minutes
before dispatch. A run from a point then begins at the ignition, and one of
its rings is *now*.
]

#tak-slide[
= Start from a NASA perimeter

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("23.png", width: 96%)
][
  The fires in the map view with a NASA outline from the last three days, with
  county, acres, how long burning and how old the outline is. It follows the
  map; nothing is drawn until you tap one. The run starts from every piece of
  it, everything inside already burning.
]
]

#tak-slide[
= The start on the map

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("08.jpg", width: 80%)
][
  The *Fire start* marker sits where you tapped. Drag it, or use Fine Adjust on
  its menu, to put it exactly where the fire is.

  #v(6pt)

  Then set the conditions and tap *Run model*.
]
]

#tak-slide[
= What the model uses

The fuel, slope and canopy are the real ground from LANDFIRE, cell by cell. Wind
comes from the nearest weather station and the National Weather Service
forecast. How dry the fuels are comes from the national fuel moisture data.

#v(6pt)

The fire spreads over that ground, carries embers, is slowed by roads and
stopped by water and bare ground, and counts the buildings and people it
reaches from FEMA's building inventory.

#v(6pt)

*Check what went in first.* When a run looks wrong, a weather station far from
the fire is the usual reason.
]

#tak-slide[
= Reading a result

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("04.png", width: 96%)
][
  - The top line says where the fire is by a time: area, how far it has gone,
    how many buildings.
  - The *time slider* moves the rings on the map. The edge at the slider's
    time is drawn in cyan.
  - The *table* gives each time step: acres, buildings and people reached
    (FEMA's upper estimate, "up to").
]
]

#tak-slide[
= A run on the map

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("09.jpg", width: 80%)
][
  Each time step is a ring, red for the first, then orange, then yellow, with
  the burned ground filled in.

  #v(6pt)

  A run can be small: a litter fire under trees in a light wind may cover a
  fraction of an acre in an hour, and the page says so. Set *Conditions* to
  *Dry and 20 mph wind* to see what a bad day would do.
]
]

#tak-slide[
= A run from a NASA perimeter

#image("24.jpg", width: 72%)

The rings grow out from the outline. On a wet night a run may not grow at all,
and the page says why; *Dry day* shows what it would do once it dries.
]

#tak-slide[
= The slider on a run

#image("15.jpg", width: 92%)

Drag the slider and the cyan edge moves to that time. The line above it says
where the fire is by then: the acres, how far it has gone and the buildings.
]

#tak-slide[
= Predicted Fire Size

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("05.png", width: 96%)
][
  A fire does not spread exactly as forecast. Each run is repeated 30 times
  with the wind speed, the wind direction and the fuel moisture nudged the way
  a forecast misses, and ranked by what burned.

  #v(4pt)

  Pick *Smallest*, *Smaller*, *Median*, *Larger* or *Largest*. It starts on
  Median. The rings, the slider, the acres and the buildings all become that
  one run.
]
]

#tak-slide[
= Smallest and Largest

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("10.jpg", width: 80%)
  Smallest
][
  #image("11.jpg", width: 80%)
  Largest
]

The same start and the same map. In a light wind the two are close; the stronger
the wind, the further apart they come.
]

#tak-slide[
= The detail pills

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("06.png", width: 96%)
][
  Under the results the rest is folded away. A closed pill shows its first
  line; tap it to open it and tap again to close it.

  - *Where it goes:* each time step in words, embers, crown fire, buildings.
  - *About Predicted Fire Size.*
  - *Burn behavior:* flame length and what a hand crew can hold.
]
]

#tak-slide[
= What went in

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("07.png", width: 96%)
][
  The fuel, slope and canopy, the wind and the station it came from, and the
  moisture.

  #v(4pt)

  *Rerun from this start* runs it again with fresh weather. *Send this run*
  shares it as one file through ATAK's Send dialog.
]
]

#tak-slide[
= Other forecasts

*FIRIS Model Runs* shows the spread runs that California's fire analysts
publish for ATAK. *PyreCast Forecasts* shows the ELMFIRE forecasts PyreCast
publishes for large fires across the country, shown with their permission, with
their logo and a link to pyrecast.com.

#v(6pt)

Each is a switch in the pane. Long press a switch for its list, and pick a fire
to put its time slider on it. PyreCast ranks its runs by what burned at the end
of the forecast; FireCast shows the middle run unless you pick the smaller or
larger end.
]

#tak-slide[
= A PyreCast forecast

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("12.jpg", width: 72%)
][
  #image("13.png", width: 96%)

  Long press the switch for the list and pick a fire. Each forecast runs out to
  about two weeks, drawn from yellow for the first hours to blue for the last.
]
]

#tak-slide[
= The slider

#image("14.jpg", width: 92%)

Drag the slider to any time. The edge at that time is drawn in cyan, and the pane
says where the fire is by then and how big.
]

#tak-slide[
= Offline

Everything FireCast has fetched or run stays on the device. After a restart of
ATAK, or with no signal, your runs, the last detections and the PyreCast
forecasts come back with their age on the pane.

#v(6pt)

Switching a layer off only hides it. A failed fetch never empties what you
already have, and a new run with no signal says what it could not reach and
leaves your other runs alone.
]

#tak-slide[
= What to keep in mind

A spread model gives a direction and an order of magnitude. It is not a plan to
base a perimeter on.

#v(6pt)

- Fuel and slope are the real ground, but the wind is one reading or a coarse
  forecast and cannot know a canyon.
- There is no suppression in it.
- House-to-house spread is an average over past fires, not this street.

#v(6pt)

Run it again as the wind changes.
]
