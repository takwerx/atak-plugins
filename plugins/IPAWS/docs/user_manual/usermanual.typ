#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "IPAWS Alerts",
   plugin-version: "0.1",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

IPAWS Alerts puts public emergency alerts on the ATAK map, with the filter
chosen on this device.

A takwerx server can already publish one alert picture to everyone on it. This
is the other half of that: an engine, a division or a strike team sets its own
states, its own counties and its own alert types, on the phone, in the field,
whether or not there is a server in the path.

Everything it draws is local to this device. Nothing is published, nothing
reaches another phone, and turning it on cannot put anything on anybody else's
map.

The alerts come from the National Weather Service. That is NWS's own products -
red flag warnings, fire weather watches, floods, winter storms - plus the
non-weather messages NWS relays, which is where Evacuation Immediate, Civil
Emergency Message, Local Area Emergency and Child Abduction Emergency come from.
]

#tak-slide[
= The pane

Open the plugin from the ATAK toolbar, or from Tools if it is not on the bar.

The pane is a column of rows. Each row names a setting and says what it is set
to - "Zoom gate: 5 mi or closer", "Distance: Everything", "Where: 3 states" - so
the whole setup reads at a glance without opening anything.

Tap a row that has an arrow, or the arrow itself, to open its controls, and
again to close them. Notify is the one exception: tapping it switches
notifications on or off, and its arrow opens the settings. Everything starts
closed, and each section stays open or closed the way you left it.

From the top: *All ON / All OFF*, Zoom gate, Distance, Map key, Where, Types,
Severity, how often to check, and Notify. The alerts themselves are listed
underneath.
]

#tak-slide[
= All ON / All OFF, and the zoom gate

*All OFF* takes every alert off the map at once. The list, the checks and the
notifications keep running, so *All ON* puts the current picture straight back.
The top line says "map off" while it is off, so an empty map never reads as no
weather.

The *zoom gate* keeps the map clean when zoomed out. Open it and either tap
*Use this zoom* to take the zoom the map is at now, or pick what the scale bar
should read: 0.25, 1, 5, 15 or 50 miles or closer, or Always.

Zoomed out past the gate, nothing is drawn and the top line says "zoom in to see
alerts on the map". Zoom in and they come back.
]

#tak-slide[
= Distance

*Distance* narrows the list and the map to what is near.

- The slider all the way left is *Everything* - no limit.
- Anywhere else it is *Within N miles* of the point below it, 1 to 50.
- *Measuring from* switches between *My Location* and *Map Center*. My Location
  follows the phone; Map Center follows the map as you pan.
- *Use this extent* sets the radius to cover what is on the screen.
- *Presets* has Everything, What is in view, and 2, 5, 10, 25 and 50 miles.

An alert counts if any part of its area is within the distance - a statewide
advisory you are standing in is zero miles away. With no GPS fix, My Location
measures from the map center and says so.
]

#tak-slide[
= Where: states

Open *Where*. *Regions* ticks whole groups at once - Pacific Southwest, Great
Plains, Gulf waters - and *states* picks them one by one. Regions include the
marine areas, so offshore waters can be picked as places of their own.

A region shows as ticked only when every state in it is selected, and unticking
it removes just its states, so the two lists can never disagree.

A brand new install picks the state the phone is in, once, so there is something
on the map before anything is touched. It asks only while nothing is selected,
so it can never move a filter that has been set - drive across a state line and
the filter stays where it was put.

With no position fix it selects nothing and says "Choose states", rather than
guessing a state and filling the map with somebody else's weather.
]

#tak-slide[
= Where: counties inside a state

The second control narrows a state to particular counties.

Tap *counties*, pick which of your states to narrow, then tick the counties. A
state with no counties ticked means the whole state. A state with counties
ticked means only those. Narrowing California to three counties does not switch
Nevada off.

A county is a *touch*, not a filing. An alert shows if its area reaches the
county, whether or not the Weather Service filed it under that county. That
matters in two places:

- A *marine* alert names no county at all. If it reaches the water off your
  county, you get it.
- A forecast zone that laps over a county line is filed however NWS chose to
  file it. If it reaches your ground, you get it.

Deselecting a state drops its counties with it, so a state added back later
comes back whole rather than silently narrowed to a choice made weeks ago.
]

#tak-slide[
= Types: categories first

The 111 alert types NWS publishes are grouped into thirteen categories: Fire,
Civil and Emergency, Tornado and Thunderstorm, Flood, Marine, Tropical, Winter
and Cold, Wind and Dust, Heat, Air Quality, Fog, Geologic and Tsunami, and
Other and Outlooks.

All of them are on to begin with. Switch *Marine* off and its twenty types go
with it. That is the control to reach for first: most crews want a category
gone, not a list of type names.

If NWS invents a type after this build, it lands in Other and Outlooks and is
*shown*. A new hazard appearing unasked is the right way to be wrong; an alert
that silently never appears is not.
]

#tak-slide[
= Types: getting specific

The second types control is for when a whole category is too much.

Tap it, pick a category you have kept, and tick the types you want. A category
with nothing ticked means the whole category, the same way a state with no
counties means the whole state.

So "everything, but out of Winter and Cold only Blizzard Warning" is two taps
and one tick, and everything else carries on as it was.
]

#tak-slide[
= Severity

CAP severity, as the issuing office set it: Extreme, Severe, Moderate, Minor,
Unknown.

A new install shows all of them. Toggle off what you do not want - the usual
move is to drop Minor and Unknown once the map gets busy.

Severity sorts the list worst first and decides what notifies. It is not the
color. It does not track warning, watch and advisory the way people read them:
every Watch is Severe while most Warnings are only Moderate, so coloring by it
made a Watch look worse than a Warning.
]

#tak-slide[
= Colors and the map key

Each alert type is drawn in the National Weather Service's own color for it, the
same table every public weather map uses: Tornado Warning red, Severe
Thunderstorm Warning orange, Flash Flood Warning dark red, Red Flag Warning deep
pink, Small Craft Advisory pale purple. There are 111 of them.

Open *Map key* for the colors of the alert types on the map right now - only
those, in the Weather Service's order of priority. The key is drawn from the
same table as the map, so the two cannot disagree. The bar beside each type in
the list is the same color again.

A few types share a color in the Weather Service's table - Small Craft Advisory
and Hazardous Seas Warning, for one. The key names them.
]

#tak-slide[
= The list, and what it tells you

Under the controls is every alert that got through the filter, worst first, then
whichever ends soonest.

Alerts of one type are grouped: "Red Flag Warning 3" is one row, and tapping it
opens the three. A type with only one alert opens it directly. Each alert shows
the areas it covers, its severity and how long is left; an alert whose office
gave no end time says so rather than showing an invented one.

Tap a row for the whole thing: the headline, what is happening, and what to do,
in the issuing office's own words. *Zoom to* frames that alert's area on the
map, and the details stay open beside it - you can read the alert and look at
the ground at the same time.
]

#tak-slide[
= The line at the top

The top line of the pane says how old the picture is, and it is worth reading,
because a map that has not been updated for twenty minutes looks exactly like
one that has.

- "Updated 4 minutes ago" - the picture is current.
- "Last updated 12 minutes ago - timed out" - the network failed. What is on the
  map is the last good set and is still the best information available. It is
  never blanked because a check failed.
- "Updated 2 minutes ago - 1 alert shown at the state's center" - its area could
  not be found, so it is a dot in the middle of its state or stretch of water.
- "map off" - All OFF is on. "zoom in to see alerts on the map" - zoomed out past
  the zoom gate.
]

#tak-slide[
= Checking for alerts

*Every N min* is how often the plugin asks, from one minute to thirty. Five is
the default. The check runs whether or not the pane is open and whether or not
the screen is on; closing the pane does not stop it. *Check now* asks
immediately instead of waiting.
]

#tak-slide[
= Notifications

*Notify* is off to begin with. Tap it to switch it on, and the arrow beside it
opens its settings:

- *Severity* - which severities notify. Extreme and Severe to begin with.
- *Where* - *In my states* (everything in the states and counties picked under
  Where, whatever the map is showing), *Within 2 to 50 miles of me*, or *Same as
  the map*.
- *Updates* - *New alerts only*, or *New and updated*. The Weather Service
  reissues an alert whenever it changes; without this each change would notify
  again.
- *Send a test notification* - shows exactly what one looks and sounds like.

A notification pops up with a sound, names the worst of the new alerts, and
opens that alert when tapped. It never announces weather that was already there
when a filter changed. Android lists it under ATAK as "IPAWS Alerts", where its
sound can be changed.
]

#tak-slide[
= On the map

Alert areas are drawn as polygons this plugin owns, named in the middle. The
edge carries the color and the fill is deliberately faint: alert areas overlap
constantly, and three solid fills stacked on a basemap hide the ground you are
looking at.

Tap an area for its menu, and *Details* opens the alert in the pane. Where areas
overlap, ATAK first asks which alert you meant, one line each.

The overlay is in ATAK's *Overlay Manager* as "IPAWS Alerts", with one entry per
severity. So the whole thing can be left running and its quieter half switched
off without opening this plugin at all.

Areas come from the alert itself when it carries one. Most do not - they name
the forecast zones they cover instead - so those are fetched and kept on the
device. They fill in as they arrive rather than making you wait for a blank map,
and once fetched they are held for a long time, so the same zone is not
downloaded twice. An alert whose areas cannot be found at all is drawn as a dot
in the middle of its state, and the top line counts it.
]

#tak-slide[
= What it does not do

It does not publish anything. There is no path by which this plugin puts an
alert on another phone.

It is not the FEMA aggregator. Real IPAWS-OPEN carries state and local
originators - a county sheriff's evacuation order that never reaches the Weather
Service - and it needs a signed agreement with FEMA. What this shows is
everything NWS issues or relays, which is what the takwerx server already
publishes under the same name.
]
