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
= Where: states

Open the plugin from the ATAK toolbar, or from Tools if it is not on the bar.

The first control is *states*. Pick one or pick a dozen; the list is every state
and territory the service covers, by name. There is no "all", on purpose: a
phone showing every alert in the country is showing nothing.

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

Severity is also the colour on the map and the bar down the side of each row in
the list, so it reads before the words do:

- Extreme - red
- Severe - orange
- Moderate - yellow
- Minor - blue
- Unknown - grey
]

#tak-slide[
= The list, and what it tells you

Under the controls is every alert that got through the filter, worst first, then
whichever ends soonest.

Each row is the alert, the areas it covers, its severity and how long is left.
An alert whose office gave no end time says so rather than showing an invented
one.

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
- "Updated 2 minutes ago - 3 alerts with no map area" - those alerts are in the
  list but their areas could not be drawn.

*Check now* asks immediately instead of waiting for the interval.
]

#tak-slide[
= Updates and notifications

*Every N min* is how often the plugin asks, from one minute to thirty. Five is
the default. The check runs whether or not the pane is open and whether or not
the screen is on; closing the pane does not stop it.

*Notify* is off to begin with. Switched on, it announces new alerts at the
severities you pick, and Extreme and Severe is what it offers first. It never
announces an alert that was already active when the filter changed, so widening
a filter does not set off a dozen notifications for weather that was there all
along.

A plugin that chimes for a Winter Weather Advisory in the next county gets
switched off within a day, which is why this one starts quiet.
]

#tak-slide[
= On the map

Alert areas are drawn as polygons this plugin owns. The edge carries the
severity colour and the fill is deliberately faint: alert areas overlap
constantly, and three solid fills stacked on a basemap hide the ground you are
looking at.

The overlay is in ATAK's *Overlay Manager* as "IPAWS Alerts", with one entry per
severity. So the whole thing can be left running and its quieter half switched
off without opening this plugin at all.

Areas come from the alert itself when it carries one. Most do not - they name
the forecast zones they cover instead - so those are fetched and kept on the
device. They fill in as they arrive rather than making you wait for a blank map,
and once fetched they are held for a long time, so the same zone is not
downloaded twice.
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

Marine areas are not yet selectable as places in their own right. Marine alerts
still reach you when they touch a county you picked, and the Marine category is
there to switch them off.
]
