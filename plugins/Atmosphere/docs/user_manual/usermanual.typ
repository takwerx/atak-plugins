#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Atmosphere",
   plugin-version: "0.1",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

Atmosphere is the weather on the ATAK map for fire and hazmat crews: the
forecast at a point, the readings from the stations, gauges and buoys around
you, and the pictures the National Weather Service publishes - radar, wind,
smoke, air quality, outlooks, flooding, snow and the sea - each one a layer
you switch on when you need it.

Open it from the ATAK toolbar, or from Tools if it is not on the bar.

Everything it shows is read by this device from public US government services,
and stays on this device. Nothing is published to a server or to anyone else's
map. No layer talks to the network until you allow it, once, by name.

Warnings, watches and advisories are not here on purpose: they are the IPAWS
Alerts plugin's job, and the two are meant to run side by side.
]

#tak-slide[
= The pane

The pane opens at half width; *Wide* (the arrows button) makes it full width and
back. It is six pages, side by side. Swipe, tap a dot, or press the arrow
button to move between them:

+ *Forecast* - the readout for a point.
+ *Layers* - every map layer, its switch and its settings.
+ *Spot forecasts* - the open spot forecast requests, and yours.
+ *Weather stations* - the stations around you, as a list.
+ *River gauges* - the gauges around you, as a list.
+ *Buoys* - the buoys and coastal stations around you, as a list.

The row of buttons at the top is the same on every page: *My position*, *Map
center* and *Pick a point* choose where the forecast is read; the star is
*Favorites*; the unit button switches wind between knots and miles per hour;
then *Wide*, *Next page*, *Refresh* and *Settings*.

Back closes the pane, or narrows a wide one first.
]

#tak-slide[
= Forecast

The first page is the readout for one point: your position, the map center, a
point you tap on the map, or a favorite. The top line names the point and how
old the reading is.

- *Now*: temperature, humidity, the 20-foot wind and where it is from, gusts,
  mixing height, transport wind, wet bulb globe temperature and the chance of
  rain - the numbers a briefing asks for, one glance each. An element the
  forecast office does not issue says so instead of showing a blank.
- *Next hours*: the next hours as a strip, one value at a time - pick which
  with the chips under it. *Show hourly table* opens the whole table.
- *Next days*: the days ahead, high and low, wind and rain.
- *Sun and moon*: sunrise, sunset and the moon for the point.

*Settings* on this page picks the *Weather service* the forecast comes from,
*What to show*, and *Position sent* - how coarsely your position is rounded
before it leaves the device.

*Favorites* keeps places by name. *Add this place* saves the point being read;
pick one later from the star.
]

#tak-slide[
= Layers

The second page lists every map layer under a heading for what it is about:
*Wind*, *Fire*, *Rain and rivers*, *Ocean* and *Snow*, with spot forecasts and
weather stations at the top. Each row is a switch that shows its state - green
ON, red OFF - with the layer's own icon beside it. *All on* and *All off* at
the top switch every allowed layer at once.

A layer that is ON grows an arrow at the end of its row. Tap it to open the
layer's settings: what it is drawing, how often, its map key, and a status
line that says what is on the map right now and, just as important, what is
not - "zoom in to see", "drawn for the middle of the map", "no storms right
now".

The first time a layer is switched on it asks once. The question names the
server it will talk to and says what is sent: the area of the map, never your
position, unless the layer says so. *Allow* remembers the answer.

Every layer that draws features is also a row of its own in ATAK's *Overlay
Manager* - "Buoys", "Streams running high", "River gauges" and the rest -
where it can be hidden without opening the plugin.
]

#tak-slide[
= Wind and fire

*Radar* draws the national radar mosaic and lets you scrub back through the
last frames.

*Wind* draws the modeled wind field as barbs with a time scrubber, and its
status line says the wind *Here*, at the pane's point: speed, direction and
gusts. Outside the drawn area it says so.

*Fire weather outlook* draws the areas where the Storm Prediction Center
expects elevated, critical or extreme fire weather, and where dry lightning
is expected, one day at a time: *Day 1*, *Day 2*, *Day 3* or *All days*.
Every colored area carries its own label, in the middle of it.

*Smoke* draws the forecast smoke, *Ground* level or the *Whole sky*, *Light*
or *Dense*, with the amount *Here*.

*Air quality* draws the EPA's air quality areas and the index *Here*.
]

#tak-slide[
= Rain and rivers

*Flash flood outlook* draws the Weather Prediction Center's excessive rainfall
areas for the next three days, marginal to high, one day at a time.

*River gauges* draws the gauges around you, colored by the National Weather
Service's flood categories, from no flooding through action, minor, moderate
and major. Tap one for its stage, flow, the flood stages and the forecast
hydrograph. The list page has the same gauges with filters.

*Flooded ground* draws where the Weather Service's river model puts water
over the banks, as a picture on the map: *Now*, or at the worst of the *Next 5
days*. The service calls this experimental, it covers only part of the
country, and it is a model's estimate, not a survey. What you can see wins.
Zoom in to a county-wide view or closer to see it.

*Streams running high* draws every stretch of stream the same model has over
its high-water mark, colored by how rare a flow that big is: a 1-in-2-year
flow through a 1-in-50-year flow or worse. Tap a line for the numbers: the
flow, the high-water mark and the 2, 5, 10, 25 and 50-year flows. It follows
the same *Now* / *Next 5 days* choice as the flooded ground.
]

#tak-slide[
= Ocean

*Buoys* draws the buoys and coastal stations as diamonds: yellow when the
reading is recent, red when the station has been silent for more than eight
hours. The reading under each one is colored by the sea state it means, using
the same bands the marine forecasts use: small craft, gale, storm, hurricane.
Tap a buoy for its readings, the nearest tide and current predictions, and the
coastal or offshore waters forecast for the water it sits in.

*Beach forecast* draws the surf zone forecast along the coast in view: each
beach area colored by rip current risk, low, moderate or high, with surf,
water temperature and the rest behind a tap.

*Sea temperature* draws the sea surface temperature as a picture, with its
scale in the pane.

*Hurricanes* draws the National Hurricane Center's active storms: track, cone
and wind fields, with each storm's advisory behind a tap. With no storm it says
"No storms right now".
]

#tak-slide[
= Snow

*Avalanche* draws the avalanche centers' forecast zones, filled by danger
rating in the national danger scale colors, with the travel advice and the
center's forecast link behind a tap. A zone that is out of season is not
drawn; one that is in season but not yet rated is drawn as an outline.

*Snow stations* draws the mountain snow stations around the map: snow depth,
water equivalent and temperature, colored by depth.

*Snow depth* draws the daily snow analysis as a picture, in the same depth
classes, lower 48 only.
]

#tak-slide[
= Weather stations

The station layer draws the weather stations around you - your position or
the map center, out to the distance you pick - with a barb for the wind and
the readings beside it once you are zoomed in far enough. A station is colored
by how close its humidity and wind are to the Red Flag criteria for its own
fire weather zone.

The *Weather stations* page lists the same stations, nearest first, with the
filters at the top: what each station reports, and what its reading means.
Every filter says how many stations it will show before you tap it.

Tap a station, on the map or in the list, for the whole record: every
reading, the zone and its Red Flag criteria, and the station's own page. The
star on a row keeps the station as a favorite; a favorite stays on the map
and in the list however far away you go.

Two settings decide when things draw: *Draw stations at this zoom or closer*
and *Draw their readings at this zoom or closer*. *Use this zoom* takes what
the scale bar reads now; *Always* never hides them.
]

#tak-slide[
= River gauges and buoys as lists

The *River gauges* and *Buoys* pages work the way the station page does:
nearest first from your position or the map center, filters with counts at
the top, a star for favorites, *Go to* to frame one on the map, and the whole
record when you tap a row.

A gauge's record carries the stage and flow now, the flood stages, and the
hydrograph - observed and forecast - for the last day to the last month.

A buoy's record carries its readings, the sea state they mean and the criteria
behind that, the nearest tide station's highs and lows for today and tomorrow,
the nearest current station's ebb and flood, and the coastal waters forecast
for its zone, or the offshore forecast for a buoy beyond the coastal zones.
]

#tak-slide[
= Spot forecasts

The *Spot forecasts* layer draws every spot forecast request the National
Weather Service has open, with the forecast the office wrote for each. The
page lists them - *All*, *Near me*, *On map*, by *State* or by *Region* - and
a search box finds one by incident name. Tap a request, on the map or in the
list, and its forecast opens.

*Request a spot forecast* prepares yours: pick the point - *My position*,
*Map center*, *Pick on map*, a *Favorite*, or an *Address* - and the plugin
copies it in the form the Weather Service's web form takes. The form itself
cannot be filled in from here: open it, hold its USNG box, paste, and press
Plot USNG.

An address you type is looked up by the US Census Bureau first, and only then
by the address finder chosen in ATAK's settings. You see what came back before
it is used.
]

#tak-slide[
= What the status lines say

Every layer says what it is doing in its own line, in words you can act on:

- "Getting the ..." - a request is out.
- "Zoom in to see ..." - the view is too wide for this layer to draw
  honestly, so it draws nothing rather than a picture of the middle.
- "Drawn for the middle of the map" - the picture is smaller than the view.
- "The 2,000 biggest streams running high here; zoom in for the smaller
  ones" - the answer was cut, and the small ones are what is missing.
- "No ... here" - the answer came back empty. That is an answer.
- A server error is said as one, and the layer asks again on the next map
  move; the last good picture stays up.

The forecast page's top line says how old the reading is, and keeps the last
reading when the network drops.
]

#tak-slide[
= What leaves the device

Nothing until you allow a layer or a weather service. Then:

- A *forecast* sends the point it is for, rounded as coarsely as *Position
  sent* says, to the weather service you picked.
- A *map layer* sends the area of the map it is drawing, never your position,
  except the station, gauge and buoy layers when set to *My position*, which
  send that position rounded.
- Every request carries the plugin's name and version, which public services
  require. No callsign, device identifier or TAK server detail is ever sent.

All of it is outbound HTTPS to US government services - the National Weather
Service and its centers, NOAA, the EPA, the Forest Service, the USGS and the
avalanche centers - and to the one weather service you pick for the forecast.
]

#tak-slide[
= This manual

This manual opens from ATAK's *Settings*, under *Tool Preferences*, *Specific
Tool Preferences*, *Atmosphere*.

Atmosphere is a data view. It publishes nothing, it records nothing, and it
draws no warnings: the IPAWS Alerts plugin draws those, on the same map, and
the two are meant to run together.

Feedback and bug reports: the issue tracker of the plugin's own repository,
linked from its README.
]
