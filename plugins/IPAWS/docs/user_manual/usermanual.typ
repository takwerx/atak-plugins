#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "IPAWS Alerts",
   plugin-version: "0.4",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

IPAWS Alerts puts public emergency alerts on the ATAK map, with the filter
chosen on this device. An engine, a division or a strike team sets its own
states, counties and alert types, on the phone, in the field, whether or not
there is a server in the path.

#image("1.png", width: 80%)

Open it from the ATAK toolbar, or from Tools if it is not on the bar.

Everything it draws is local to this device. Nothing is published, nothing
reaches another phone. The alerts come from the National Weather Service: its
own products - red flag warnings, floods, winter storms - plus the non-weather
messages it relays, such as Evacuation Immediate and Civil Emergency Message.
]

#tak-slide[
= The main screen

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("2.jpg", width: 100%)
  Alerts OFF: the list keeps running, the map is clear.
][
  #image("3.jpg", width: 100%)
  Alerts ON: the same alerts drawn on the map.
]

#v(4pt)
The main screen is three buttons and the alert list. *Alerts ON / OFF* switches
the alerts on the map, *Settings* opens everything else, and *Notify ON / OFF*
switches notifications. Both switches show what is: green ON, red OFF. With the
map off the top line says "map off", so an empty map never reads as no weather.
]

#tak-slide[
= Settings

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("4.png", width: 100%)
][
  *Settings* replaces the list with a page of rows: zoom gate, distance, map
  key, where, types, severity, how often to check, and notifications. *Back*
  returns to the list.

  Each row names a setting and says what it is set to - "Zoom gate: Always",
  "Where: 7 states" - so the whole setup reads at a glance without opening
  anything.

  Tap a row, or the arrow at its end, to open its controls. Each stays open or
  closed the way you left it.
]
]

#tak-slide[
= The zoom gate

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("5.png", width: 100%)
][
  #image("6.png", width: 100%)
][
  The *zoom gate* keeps the map clean when zoomed out. Alerts draw only while
  ATAK's scale bar reads the chosen distance or less.

  *Use this zoom* takes whatever the scale bar reads right now. The other button
  picks from a list: 0.25, 1, 5, 15 or 50 miles or closer, or Always, which is
  the default.
]
]

#tak-slide[
== Zoomed out past the gate

#image("7.jpg", width: 66%)

With the gate at 5 miles and the scale bar reading 126, nothing draws, and the
top line says "zoom in to see alerts on the map". Zoom in and they come back.
]

#tak-slide[
= Distance

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("8.png", width: 100%)
][
  *Distance* narrows the list and the map to what is near.

  - The slider all the way left is *Everything* - no limit.
  - Anywhere else it is *Within N miles*, 1 to 50.
  - *Measuring from* switches between *My Location* and *Map Center*.
  - *Use this extent* covers what is on the screen.
  - *Presets*: Everything, What is in view, 2 to 50 miles.

  An alert counts if any part of its area is within the distance. With no GPS
  fix, My Location measures from the map center and says so.
]
]

#tak-slide[
= Where

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("9.png", width: 100%)
  Regions, states and counties.
][
  #image("10.png", width: 100%)
  Regions tick whole groups, offshore waters included.
][
  #image("11.png", width: 100%)
  Counties narrow a state. *Whole state* undoes it.
]

#v(4pt)
A brand new install picks the state the phone is in, as soon as it has a fix and
a connection. It never moves a filter that has been set: drive across a state
line and the filter stays put.
]

#tak-slide[
== A county is a touch, not a filing

A state with no counties ticked means the whole state; with counties ticked,
only those. Narrowing California does not switch Nevada off.

An alert shows if its area *reaches* a picked county, whether or not the
Weather Service filed it under that county:

- A *marine* alert names no county at all. If it reaches the water off your
  county, you get it.
- A forecast zone that laps over a county line is filed however the Weather
  Service chose. If it reaches your ground, you get it.

Deselecting a state drops its counties with it, so a state added back later
comes back whole.
]

#tak-slide[
= Types and severity

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("12.png", width: 100%)
][
  #image("13.png", width: 100%)
][
  The 111 alert types are grouped into thirteen categories. Switch *Marine* off
  and its twenty types go with it; the second types button narrows a kept
  category to particular types. A type the Weather Service adds later is shown.

  Severity sorts the list worst first and decides what notifies. It is not the
  color: every Watch is Severe while most Warnings are only Moderate.
]
]

#tak-slide[
= Colors and the map key

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("14.jpg", width: 100%)
][
  Each alert type is drawn in the National Weather Service's own color for it,
  the table every public weather map uses.

  *Map key* lists the colors of the types on the map right now, and only those,
  so it changes with the filter and the weather. It is drawn from the same
  table as the map, so the two cannot disagree.
]
]

#tak-slide[
= The list

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("15.jpg", width: 100%)
][
  Alerts are grouped by type, worst first: "Storm Warning 17" is one row, and
  tapping it opens the seventeen. A type with one alert opens it directly.

  Each alert shows its areas, its severity and how long is left.
]
]

#tak-slide[
== An alert's details

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("16.jpg", width: 100%)
][
  Tap an alert for the whole thing: severity, urgency, areas, times, and the
  issuing office's headline, what is happening and what to do.

  *Zoom to* frames its area on the map, with the details still open beside it.
]
]

#tak-slide[
= The line at the top

The top line of the pane says how old the picture is. It is worth reading: a map
that has not been updated for twenty minutes looks exactly like one that has.

- "Updated 4 minutes ago" - the picture is current.
- "Last updated 12 minutes ago - timed out" - the network failed. The map keeps
  the last good set; it is never blanked because a check failed.
- "N alerts still being drawn" - areas are still arriving.
- "1 alert shown at the state's center" - its area could not be found, so it is
  a dot in the middle of its state or stretch of water.
- "map off", or "zoom in to see alerts on the map".
- "No states picked" - choose them in Settings, Where. On a first start it says
  "Finding the state you are in..." and keeps trying until it can.

*Every N min* sets how often it checks, one to thirty minutes, five by default.
It keeps checking with the pane closed. *Check now* asks immediately.
]

#tak-slide[
= Notifications

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("17.png", width: 100%)
][
  *Notify* on the main screen switches notifications on. Their settings are the
  last row of *Settings*:

  - *Severity*: Extreme and Severe to begin with.
  - *Where*: *In my states*, *Within 2 to 50 miles of me*, or *Same as the map*.
  - *Updates*: *New alerts only*, or *New and updated*.
  - *Send a test notification*.
]
]

#tak-slide[
== What a notification looks like

#image("18.jpg", width: 80%)

It pops up with a sound and names the worst of the new alerts. Tap it to open
that alert. It never announces weather that was already there when a filter
changed. Android lists it under ATAK as "IPAWS Alerts", where its sound can be
changed.
]

#tak-slide[
= On the map

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("19.jpg", width: 100%)
  Where areas overlap, ATAK asks which alert you meant, one line each.
][
  #image("20.jpg", width: 100%)
  Tap an area for its menu. *Details* opens the alert in the pane.
]

#v(4pt)
Areas have a colored edge and a faint fill, because alerts overlap constantly and
solid fills would hide the ground. Most alerts name forecast zones instead of
carrying an area; those are fetched once and kept on the device.
]

#tak-slide[
== The Overlay Manager

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("21.png", width: 100%)
][
  #image("22.png", width: 100%)
][
  The overlay is in ATAK's *Overlay Manager* as "IPAWS Alerts", with one entry
  per severity. The quieter half can be switched off there without opening the
  plugin.
]
]

#tak-slide[
= This manual, and what it does not do

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("23.png", width: 100%)
  #v(4pt)
  #image("24.png", width: 100%)
][
  This manual opens from ATAK's Settings, under Tool Preferences, Specific Tool
  Preferences, IPAWS Alerts.

  IPAWS Alerts publishes nothing: no path puts an alert on another phone.

  It is not the FEMA aggregator. IPAWS-OPEN also carries state and local
  originators and needs an agreement with FEMA. This shows everything the
  Weather Service issues or relays.
]
]
