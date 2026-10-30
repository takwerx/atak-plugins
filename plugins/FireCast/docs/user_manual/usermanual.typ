#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Fire Cast",
   plugin-version: "0.1",
   platform: "ATAK",
   platform-version: "5.7.0",
)

#tak-slide[
= Overview

#toolbox.side-by-side(columns: (1.5fr, 9fr))[
  #image("plugin_icon.png", width: 85%)
][
Fire Cast puts satellite heat detections on the ATAK map and runs a fire spread
model on the device, from a point you tap or a perimeter you draw. It also shows
other agencies' published fire forecasts beside your own runs.
]

#v(6pt)

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("01.png", width: 62%)
][
  Everything starts on this one pane: the detections in view, a summary of the
  fire activity on screen, the Fire model, the forecast layers and the feeds.

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
= The fire model

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("03.png", width: 96%)
][
  Tap *Fire model*, then *Place start* and tap the map where the fire starts.
  Drag the marker to make it exact.

  - *Conditions:* as observed, at the gust, a dry day, or dry with 20 mph wind.
  - *Station:* automatic, or pick a weather station by name.
  - *Draw perimeter:* start from the fire's current edge.
  - *Started:* how long ago it started, up to three hours.
  - *Horizon:* one or three hours ahead.

  Then tap *Run model*.
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
  - The *table* gives each time step: acres, buildings and people reached.
]
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
of the forecast; Fire Cast shows the middle run unless you pick the smaller or
larger end.
]

#tak-slide[
= Offline

Everything Fire Cast has fetched or run stays on the device. After a restart of
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
