#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Atmosphere",
   plugin-version: "0.9",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#let cap(body) = text(size: 9pt, fill: luma(90), body)

#tak-slide[
= Overview

Atmosphere is the weather on the ATAK map for fire and hazmat crews: the
forecast at a point, the fire weather planning forecast for a zone, the
readings from the stations, gauges and buoys around you, and the pictures the
weather services publish - radar, satellite, wind,
rain, smoke, air quality, lightning, outlooks, the Santa Ana wildfire threat,
power shutoffs, flooding, the sea and snow - each one a layer you switch on
when you need it.

#image("1.png", width: 90%)

Open it from the ATAK toolbar, or from Tools if it is not on the bar.

Everything it shows is read by this device from public services and stays on
this device. Nothing is published to a server or to anyone else's map. The
forecast asks the National Weather Service for the point you are reading; every
map layer asks you once, naming its server, before it talks to the network.
Warnings,
watches and advisories are not here on purpose: they are the IPAWS Alerts
plugin's job, and the two are meant to run side by side. The one exception is
fire: a fire weather zone under a Red Flag Warning or a Fire Weather Watch is
shaded, and the zones page lists them.
]

#tak-slide[
= The first time

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("2.png", width: 100%)
  #cap[A new install: the forecast from NWS, in US units.]
][
  There is nothing to set up. Atmosphere reads the forecast from the National
  Weather Service (*NWS*) as soon as it opens, for the point you are reading,
  rounded to about 100 m.

  Outside the US, open *Forecast settings* and tap *Forecast from Open-Meteo*.
]
]

#tak-slide[
= The pane

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("3.png", width: 100%)
][
  The pane opens at half width. The top row is the same on every page:

  - *My position*, *Map center* and *Pick a point* choose the point every
    reading is for. The one in use is green. Map center follows the map: the
    forecast and the fire weather zone are read again when the map stops.
  - The star is *Favorites*: places kept by name.
  - *Units* switches every number between US, metric and aviation units.
  - *Full size* makes the pane full width and back.
  - The arrows step through the pages; the name between them opens a list of
    all eight.

  Back closes the pane, or narrows a full-size one first.
]
]

#tak-slide[
= Full size, and the pages

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("4.png", width: 100%)
  #cap[At full size every button in the top row has its name under it.]
][
  #image("5.png", width: 100%)
  #cap[Tap the page name for the list.]
]

#v(4pt)
There are eight pages: *Forecast*, the readout for a point; *Layers*, every
map layer with its switch and settings; *Spot Weather Forecast*, the open spot
forecast requests; *Fire Weather Zones*, the planning forecast for a zone;
*SAWTI*, the Santa Ana Wildfire Threat Index;
*Weather Stations*, *River Gauges* and *Buoys*, the ones around you as lists. Swipe, use the
arrows, or pick from the list.
]

#tak-slide[
= Forecast: now

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("6.png", width: 100%)
][
  #image("9.png", width: 100%)
]

#v(4pt)
The first page is the forecast for one point: your position, the map center,
a point you tap on the map, or a favorite. The top line names the point and
how old the reading is. *Now* is the numbers a briefing asks for, one glance
each: temperature, humidity, the 20-foot wind and where it is from, gusts,
mixing height, transport wind, wet bulb globe temperature and the chance of
rain. Under them, the sky cover, then sunrise, sunset and the moon for the
point. An element the forecast office does not issue says so. *Refresh* reads
the forecast again.
]

#tak-slide[
= Forecast: the hours ahead

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("7.png", width: 100%)
  #cap[Next hours: one value at a time, picked with the buttons under it.]
][
  #image("8.png", width: 100%)
  #cap[Show hourly table opens every value, hour by hour.]
]

#v(4pt)
*Next hours* draws the next day and a half as a line with the sky above it and
sunrise and sunset marked. The buttons under it pick which value the line
shows. *Show hourly table* opens the whole table; *Hide hourly table* folds it
away again, and it stays the way you left it.
]

#tak-slide[
= Forecast: the days ahead

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("10.png", width: 100%)
][
  *Next days* folds the hours into days, the numbers a shift is planned on:
  the high and the low, the lowest humidity, the strongest wind and gust, and
  the highest chance of rain.

  The service it came from is credited under the table.
]
]

#tak-slide[
= Forecast settings and favorites

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("11.png", width: 100%)
  #cap[Forecast settings.]
][
  #image("14.png", width: 100%)
  #cap[Favorites.]
]

#v(4pt)
*Forecast settings* holds everything about the forecast: where it comes from,
*NWS (United States)* or *Open-Meteo* for anywhere else, picked with one tap,
and *What to show* in the Now tiles and the table. *Favorites* keeps places by
name: *Add
this place* saves the point being read, and picking one later reads the
forecast there.
]

#tak-slide[
= Layers

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("15.png", width: 100%)
][
  The *Layers* page lists every map layer under a heading for what it is
  about: *Wind*, *Fire*, *Rain and rivers*, *Ocean* and *Snow*, with *Spot
  Weather Forecast* and *Weather Stations* at the top. Each row is a switch that shows
  its state, green ON or red OFF, with the layer's icon beside it. *All on*
  and *All off* switch every allowed layer at once.
]

#v(4pt)
A layer that is ON grows an arrow at the end of its row: tap it for the
layer's settings, its map key, and a status line that says what is on the map
and what is not. The first time a layer is switched on it asks once, naming
the server and what is sent: the area of the map, never your position.

Tap anything Atmosphere draws and it opens straight away: a zone, spot
request, gauge or buoy opens its page, anything else its details. There is no
radial menu on Atmosphere's items; bloodhound and the rest are ATAK's own tools.
]

#tak-slide[
= In ATAK's Overlay Manager

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("66.png", width: 100%)
][
  #image("67.png", width: 100%)
][
  Every Atmosphere layer that draws on the map is a row inside one
  *Atmosphere* row in ATAK's *Overlay Manager*, each with its own icon.

  The eye beside a row hides that layer, or all of them at once, without
  opening the plugin. A layer that is off shows 0 items.
]
]

#tak-slide[
= Radar

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("16.jpg", width: 100%)
][
  #image("17.png", width: 100%)
][
  *Radar* draws the radar mosaics of the National Weather Service over the
  United States, Alaska, Hawaii, Puerto Rico and Guam, and Environment
  Canada's over Canada. It picks the right one by itself as the map moves.

  The time strip under it scrubs back through the last two to three hours of
  frames. *Live* follows the newest.
]
]

#tak-slide[
= Radar around the world

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("18.jpg", width: 100%)
][
  #image("19.png", width: 100%)
][
  Everywhere else - Mexico past the border radars, Central America, the
  Caribbean beyond Puerto Rico, Europe, wherever a country publishes its
  radar - the picture is RainViewer's composite of the world's public
  weather radars. Frames are ten minutes apart, and the credit at the bottom
  of the Layers page says so while it is drawn.

  Far out at sea no radar reaches: that is what Satellite and Rain are for.
]
]

#tak-slide[
= Satellite

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("20.jpg", width: 100%)
][
  #image("21.png", width: 100%)
][
  *Satellite* draws the newest GOES picture, about ten minutes old.
  *Infrared* shows the cloud tops day and night, brightest where they are
  highest and coldest. *Visible* is the daylight picture and goes dark at
  night.

  It reaches where no radar does - the open Pacific and Atlantic - which is
  where a hurricane at sea is read from.
]
]

#tak-slide[
= Wind

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("22.jpg", width: 100%)
][
  #image("23.png", width: 100%)
][
  *Wind* draws the forecast wind as moving streaks colored by speed, with the
  key under the time strip in mph, kt or km/h.

  The line under it reads the wind *Here*, at the pane's point: speed and
  where it comes from. Outside the area being drawn it says so.

  The height buttons further down draw the wind from 10 m up to the jet
  stream.
]
]

#tak-slide[
= Rain

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("32.jpg", width: 100%)
][
  #image("33.png", width: 100%)
][
  *Rain* draws how hard the forecast model expects rain to fall, light to
  violent, hour by hour for five days, everywhere on the map including far
  out to sea, with the rate *Here* at the pane's point.

  It is a forecast, not radar. Radar is what is falling now where a radar
  can see it; Rain is what the model expects where nothing can.
]
]

#tak-slide[
= Fire Weather Outlook

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("24.jpg", width: 100%)
][
  #image("25.png", width: 100%)
][
  *Fire Weather Outlook* draws where the Storm Prediction Center expects
  elevated, critical or extreme fire weather, and dry thunderstorms, one day
  at a time: *Day 1*, *Day 2*, *Day 3* or *All days*. Every area carries its
  label in the middle of it.

  From Day 3 the center gives a chance instead of a category: a 40% or 70%
  chance of critical fire weather, a 10% or 40% chance of dry thunderstorms.
]
]

#tak-slide[
= Fire Weather Zones

#toolbox.side-by-side(columns: (6fr, 3fr, 3fr))[
  #image("71.png", width: 100%)
][
  #image("72.png", width: 100%)
][
  #image("73.png", width: 100%)
]

#v(4pt)
*Fire Weather Zones* draws the National Weather Service's fire weather zones in
the map view: orange outlines, each with its zone number, a starred zone in
yellow, a zone under a Red Flag Warning filled pink and one under a Fire Weather
Watch beige. Tap a zone for its forecast. The two zoom settings under the
layer's arrow decide when the zones and their numbers draw; a view wider than a
few states says to zoom in.
]

#tak-slide[
= A zone's fire weather forecast

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("68.png", width: 100%)
][
  #image("69.png", width: 100%)
][
  #image("70.png", width: 100%)
]

#v(4pt)
The *Fire Weather Zones* page shows the zone the point you are reading is in,
the zones under a Red Flag Warning or a Fire Weather Watch anywhere, a search
by zone number (CAZ548, CA548 or 548) or by name, and your starred zones. Tap
one for the office's Fire Weather Planning Forecast for that zone, as the
office wrote it: western offices write it by period, eastern ones as a table;
slide sideways for a wide line. The star keeps the zone on the page and yellow
on the map. *Show the office discussion* opens the office's discussion for the
day under it. A forecast more than a day old says so.
]

#tak-slide[
= SAWTI

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("80.png", width: 100%)
][
  #image("81.png", width: 100%)
]

#v(4pt)
*SAWTI* draws the US Forest Service's Santa Ana Wildfire Threat Index for its
four Southern California zones: LA-Ventura, Orange-Inland Empire, San Diego and
Santa Barbara, where it rates Sundowner winds. It rates how hard a fire would be
to fight if one started during an offshore wind: No Rating, Marginal, Moderate,
High or Extreme. A rated zone is filled in its level's color, with its label in
the same color; a No Rating zone is a cyan outline. The date buttons pick one of
the four days the Forest Service shows. Tap a zone to open its page.
]

#tak-slide[
= The SAWTI page

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("76.png", width: 100%)
  #cap[The zone, the four days and the day's level.]
][
  #image("77.png", width: 100%)
  #cap[The gauges, the forecaster's words and what to do.]
]

#v(4pt)
The *SAWTI* page is the Forest Service site's forecast page. It opens on the
zone the point you are reading is in, and the zone button picks another. Each
day button shows that day's level. Under the level are the site's three gauges,
*Threat Level*, *Wind Strength* (weak to strong) and *Fuel Moisture* (moist to
dry), then the forecaster's *Event Description* and *Recommended Actions*, with
the county links the site gives for the zone.
]

#tak-slide[
= SAWTI: all zones

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("78.png", width: 100%)
][
  #image("79.png", width: 100%)
]

#v(4pt)
*All Zones* puts every zone and day side by side in the level colors; tap a
square for that zone and day. The issue time is under it: the Forest Service
posts a new index every morning and again when a forecaster updates it, and an
issue more than a day old says so. When the wind and fuel numbers cannot be had,
the gauges say *Not available* and the page asks again the next time you look.
]

#tak-slide[
= Lightning

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("83.png", width: 100%)
][
  #image("82.png", width: 100%)
][
  *Lightning* draws NOAA's lightning strike density: how many strikes the
  ground networks counted in each 5-mile square in 15 minutes, from yellow
  (under 1) to deep purple (over 150). It counts ground strikes and some cloud
  flashes.

  A new frame comes every 15 minutes and arrives 10 to 25 minutes after the
  strikes; the line under the switch says which 15 minutes and how old. It
  shows which storms are making lightning and whether they are building. It is
  not a reason to call it safe to be outside.
]
]

#tak-slide[
= Power shutoffs (PSPS)

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("85.png", width: 100%)
  #image("86.png", width: 100%)
][
  #image("84.png", width: 100%)
][
  *PSPS* draws California's Public Safety Power Shutoffs as Cal OES reports
  them from PG&E, SCE and SDG&E: a county a utility has warned may have a
  shutoff in amber, where the power is off in red, off because a line feeding
  it was cut in orange, and back on in green. Tap one for its details.

  The line under the switch says what is warned and off and when Cal OES last
  updated; if Cal OES goes quiet for 45 minutes it says the status is unknown
  rather than none. It does not cover PacifiCorp, Liberty, Bear Valley or any
  other state: ask the utility.
]
]

#tak-slide[
= Smoke

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("26.jpg", width: 100%)
][
  #image("27.png", width: 100%)
][
  *Smoke* draws the forecast smoke hour by hour, at *Ground* level or through
  the *Whole sky*, in the air quality colors, with the amount *Here* in
  micrograms per cubic meter and what that means for breathing.
]
]

#tak-slide[
= Air Quality

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("28.jpg", width: 100%)
][
  #image("29.png", width: 100%)
][
  *Air Quality* draws the EPA's air quality areas in the index colors, Good to
  Hazardous, with the index *Here*. The line above the key says when it was
  measured, and says so when the EPA has published nothing newer.
]
]

#tak-slide[
= Flash Flood Outlook

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("30.jpg", width: 100%)
][
  #image("31.png", width: 100%)
][
  *Flash Flood Outlook* draws the Weather Prediction Center's excessive
  rainfall outlook for the next three days: the chance that rain will run
  off faster than the ground and streams can take it, marginal, slight,
  moderate or high. One day at a time, or all three.
]
]

#tak-slide[
= River Gauges

#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("34.jpg", width: 100%)
][
  #image("35.png", width: 100%)
]

#v(4pt)
*River Gauges* draws the gauges around you, colored by the flood category they
are in, no flooding through action, minor, moderate and major. Tap one for its
record: the stage and flow now, the flood stages, and the hydrograph, observed
and forecast, for the last day up to the last month. The *River Gauges* page lists
the same gauges.
]

#tak-slide[
= Flooded Ground

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("36.jpg", width: 100%)
][
  #image("37.png", width: 100%)
][
  *Flooded Ground* draws where the Weather Service's river model puts water
  over the banks: *Now*, or at the worst of the *Next 5 days*.

  The service calls it experimental. It is a model's estimate, not a survey,
  and it covers only part of the country. What you can see wins. Zoom in to a
  county-wide view or closer to see it.
]
]

#tak-slide[
= Streams Running High

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("39.jpg", width: 100%)
][
  #image("40.png", width: 100%)
][
  #image("41.png", width: 100%)
]

#v(4pt)
*Streams Running High* draws every stretch of stream the same model has over
its high-water mark, colored by how rare a flow that big is, a 1-in-2-year
flow up to 1-in-50 or worse. Tap a line for the flow, the high-water mark and
the 2 to 50-year flows. It follows the same *Now* / *Next 5 days* choice as
the flooded ground.
]

#tak-slide[
= Waves

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("42.jpg", width: 100%)
][
  #image("43.png", width: 100%)

  *Waves* draws the wave forecast: the height of the seas as color in the
  states mariners use, smooth to phenomenal, and the swell as crests moving
  the way it runs, faster when the swell is longer. The line reads the seas
  *Here*: height and state, where they come from and how often, and the swell
  under them. A model's forecast, not a buoy's reading.
]
]

#tak-slide[
= Buoys

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("44.jpg", width: 100%)
][
  #image("45.png", width: 100%)
][
  *Buoys* draws the buoys and coastal stations as diamonds: yellow when the
  reading is recent, red when the station has been silent for more than
  eight hours. The *Buoys* page lists them nearest first, with filters that
  say how many each will show: wind, seas, rough water, favorites.
]
]

#tak-slide[
= A buoy's record

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("46.png", width: 100%)
][
  #image("47.png", width: 100%)
][
  Tap a buoy, on the map or in the list, for its readings, the sea state they
  mean, the nearest tide station's highs and lows for today and tomorrow, and
  the nearest current.

  At the end of the record is the coastal waters forecast for the water it
  sits in, or the offshore forecast for a buoy beyond the coastal zones.
]
]

#tak-slide[
= Beach Forecast and Sea Temperature

#toolbox.side-by-side(columns: (3fr, 3fr, 3fr, 3fr))[
  #image("48.jpg", width: 100%)
][
  #image("49.png", width: 100%)
][
  #image("50.jpg", width: 100%)
][
  #image("51.png", width: 100%)
]

#v(4pt)
*Beach Forecast* draws the surf zone forecast along the coast in view: each
beach area colored by rip current risk, low, moderate or high, with surf and
water temperature behind a tap. *Sea Temperature* draws the sea surface
temperature as a picture, with its scale in both units.
]

#tak-slide[
= Hurricanes

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("52.jpg", width: 100%)
][
  #image("53.png", width: 100%)
][
  *Hurricanes* draws the National Hurricane Center's active storms: the
  forecast track with its times, the cone, and the wind fields. The block
  lists every storm with its strength; *Go to* frames it, and the arrow opens
  its advisory and map choices. With no storm it says "No storms right now".
]
]

#tak-slide[
= Snow

*Avalanche Zones* draws the avalanche centers' forecast zones, filled by
danger rating in the national danger scale colors, with the travel advice and
the center's forecast link behind a tap. A zone out of season is not drawn;
one in season but not yet rated is drawn as an outline.

*Snow Stations* draws the mountain snow stations around the map: snow depth,
water equivalent and temperature, colored by depth.

*Snow Depth* draws the daily snow analysis as a picture, in the same depth
classes, lower 48 only.
]

#tak-slide[
= Weather Stations

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("54.jpg", width: 100%)
][
  #image("55.png", width: 100%)
][
  The station layer draws the weather stations around you with a barb for the
  wind and the readings beside it: wind, gusts and humidity. A station is
  colored by how close it is to the Red Flag criteria for its own fire weather
  zone. The *Weather Stations* page lists them nearest first, with filters that say
  how many each will show.
]
]

#tak-slide[
= A station, and when stations draw

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("56.png", width: 100%)
][
  #image("57.png", width: 100%)
][
  Tap a station for the whole record: every reading, its fire weather zone and
  that zone's Red Flag criteria. The star keeps it as a favorite.

  *Draw on the map* picks which stations draw. The two zoom settings decide
  when the stations and their readings appear: *Use this zoom* takes what the
  scale bar reads now, *Always* never hides them.
]
]

#tak-slide[
= Spot Weather Forecast

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("58.jpg", width: 100%)
][
  #image("59.png", width: 100%)
][
  #image("60.png", width: 100%)
]

#v(4pt)
The *Spot Weather Forecast* layer draws every open spot forecast request: W
for a wildfire, P for a prescribed fire, S for search and rescue. The *Spot
Weather Forecast* page lists them - all, near you, on the map, by state or
region - *Newest* or *Closest* first, with a search by incident name. Tap one
for the forecast the office wrote.
]

#tak-slide[
= Spot types and the map key

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("74.png", width: 100%)
][
  #image("75.png", width: 100%)
]

#v(4pt)
*All Types* on the page picks one kind of request, with how many there are of
each; the kind picked applies to the map as well as the list. Under the
layer's arrow the key shows each kind's icon and what the three colors mean.
*Last 3 days only* keeps both to requests asked for or answered in the last
three days.
]

#tak-slide[
= Requesting a spot forecast

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("61.png", width: 100%)
][
  #image("62.png", width: 100%)
]

#v(4pt)
*Request a spot forecast* prepares yours. Pick the point - *My position*,
*Map center*, *Pick on map*, a *Favorite* or an *Address* - and copy it. The
Weather Service's form cannot be filled in from here: *Open the form*, hold its
USNG box, paste, and press *Plot USNG*.
]

#tak-slide[
= By address

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("63.png", width: 100%)
][
  #image("64.png", width: 100%)
][
  #image("65.png", width: 100%)
]

#v(4pt)
An address is looked up by the US Census Bureau first, and only then by the
address finder chosen in ATAK's settings. You see what came back before it is
used: *Use this point* makes it the point to copy, and the form takes it as a
USNG string.
]

#tak-slide[
= What the status lines say

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("38.png", width: 100%)
][
  Every layer says what it is doing, in words you can act on:

  - "Getting the ..." - a request is out.
  - "Zoom in to see ..." - the view is too wide for this layer to draw
    honestly, so it draws nothing rather than a picture of the middle.
  - "Drawn for the middle of the map" - the picture is smaller than the view.
  - "The 800 biggest streams running high here; zoom in for the smaller ones"
    - the answer was cut, and the small ones are what is missing.
  - "No ... here" - the answer came back empty. That is an answer.
  - A server error is said as one; the last good picture stays up and the
    layer asks again on the next map move.
]
]

#tak-slide[
= What leaves the device

Only what you ask for:

- A *forecast* sends the point it is for, rounded to about 100 m, to the
  service picked in *Forecast settings*: NWS unless you pick Open-Meteo.
- A *map layer*, once you have allowed it, sends the area of the map it is drawing, never your position,
  except the station, gauge and buoy layers when set to *My position*, which
  send that position rounded.
- The *Fire Weather Zones* page finds your zone by sending the half-degree
  square the point is in, about 30 miles across, never the point. The Red Flag
  Warning and Fire Weather Watch list is one request for the whole country.
- *SAWTI* and *PSPS* send nothing about where you are: each asks for its whole
  set, and the SAWTI page finds your zone on this device.
- An *address* you type goes to the US Census Bureau, and to ATAK's own
  address finder if the Census has no answer.
- Every request carries the plugin's name and version, which public services
  require. No callsign, device identifier or TAK server detail is ever sent.

All of it is outbound HTTPS: to the National Weather Service and its centers,
NOAA, the EPA, the USGS, the Forest Service, Cal OES, Environment Canada,
RainViewer and the avalanche centers, and to the one weather service you pick.
]

#tak-slide[
= This manual

This manual opens from ATAK's *Settings*, under *Tool Preferences*, *Specific
Tool Preferences*, *Atmosphere*.

Atmosphere is a data view. It publishes nothing, it records nothing, and it
draws no warnings except the Red Flag Warnings and Fire Weather Watches on its
fire weather zones: the IPAWS Alerts plugin draws the rest, on the same map,
and the two are meant to run together.

Feedback and bug reports: the issue tracker of the plugin's own repository,
linked from its README.
]
