# Atmosphere for ATAK — User Guide

**Version 0.7 · takwerx**

**Download Atmosphere 0.7** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/atmosphere/releases/download/v0.7/ATAK-Plugin-Atmosphere-0.7--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/atmosphere/releases/download/v0.7/ATAK-Plugin-Atmosphere-0.7--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/atmosphere/releases/download/v0.7/ATAK-Plugin-Atmosphere-0.7--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/atmosphere/releases

Atmosphere is the weather on the ATAK map for fire and hazmat crews: the
forecast at a point, the readings from the weather stations, river gauges and
buoys around you, and the pictures the weather services publish — radar,
satellite, wind, rain, smoke, air quality, lightning, fire and flood outlooks,
the Santa Ana wildfire threat, power shutoffs, flooding, waves, the sea and
snow — each one a layer you switch on when you need it.

<img src="screenshots/1_toolbar.png" width="640">

Everything it shows is read by your device from public services and stays on
your device. Nothing is published to a server or to anyone else's map. The
forecast asks the National Weather Service for the point you are reading; every
map layer asks you once, naming its server, before it talks to the network.

Warnings, watches and advisories are not here on purpose: they are the
[IPAWS Alerts](https://github.com/takwerx/ipaws-alerts) plugin's job, and the
two are meant to run side by side. The one exception is fire: a fire weather
zone under a Red Flag Warning or a Fire Weather Watch is shaded, and the
Fire Weather Zones page lists them.

---

## 1. Before you start

- Published builds exist for **ATAK-CIV 5.6, 5.7 and 5.8**. Install the one that
  matches your ATAK exactly; a build for another version will not load.
- The phone needs a network path to the weather services; each map layer names
  its server when it first asks to be allowed. A reading that has been fetched stays
  readable when the network drops, with its age on it.
- The forecast needs nothing set up: it reads from the National Weather Service
  (NWS) as soon as Atmosphere opens, in US units. Outside the US, open
  **Forecast settings** and tap **Forecast from Open-Meteo**.

<img src="screenshots/2_first_run.png" width="420">

## 2. The pane

Open Atmosphere from the ATAK toolbar, or from Tools if it is not on the bar.
The pane opens at half width. The top row is the same on every page:

- **My position**, **Map center** and **Pick a point** choose the point every
  reading is for; the one in use is green. Map center follows the map: the
  forecast and the fire weather zone are read again when the map stops.
- The star is **Favorites**: places kept by name.
- **Units** switches every number between US, metric and aviation units.
- **Full size** makes the pane full width and back; at full size every button
  has its name under it.
- The arrows step through the pages; the page name between them opens a list of
  all eight.

<img src="screenshots/3_pane_half.png" width="420"> <img src="screenshots/5_page_list.png" width="420">

The eight pages are **Forecast**, the readout for a point; **Layers**, every map
layer with its switch and settings; **Spot Weather Forecast**, the open spot
forecast requests; **Fire Weather Zones**, the planning forecast for a zone;
**SAWTI**, the Santa Ana Wildfire Threat Index; and **Weather Stations**, **River Gauges** and **Buoys**, the ones around you as
lists. Back
closes the pane, or narrows a full-size one first.

<img src="screenshots/4_pane_wide.png" width="860">

## 3. The forecast

The first page is the readout for one point: your position, the map center, a
point you tap on the map, or a favorite. The top line names the point and how
old the reading is.

- **Now**: temperature, humidity, the 20-foot wind and where it is from, gusts,
  mixing height, transport wind, wet bulb globe temperature and the chance of
  rain; under them the sky cover, sunrise, sunset and the moon. An element the
  forecast office does not issue says so.
- **Next hours**: the next day and a half as a line, one value at a time; the
  buttons under it pick which. **Show hourly table** opens the whole table.
- **Next days**: each day's high and low, lowest humidity, strongest wind and
  gust, and highest chance of rain.

<img src="screenshots/6_now.png" width="860">

<img src="screenshots/7_next_hours.png" width="420"> <img src="screenshots/10_next_days.png" width="420">

**Refresh** at the top of this page reads the forecast again. **Forecast
settings** beside it picks where the forecast comes from, **NWS (United
States)** or **Open-Meteo** for anywhere else, with one tap, and **What to
show** picks the readings. The point is sent rounded to about 100 m.
**Favorites** keeps places by name.

<img src="screenshots/11_forecast_settings.png" width="420"> <img src="screenshots/14_favorites.png" width="420">

## 4. The layers

The second page lists every layer under a heading for what it is about: **Spot
Weather Forecast** and **Weather Stations** at the top, then **Wind**, **Fire**, **Rain and
rivers**, **Ocean** and **Snow**. Each row is a switch that shows its state —
green ON, red OFF — with the layer's icon beside it. **All on** and **All off**
switch every allowed layer at once.

A layer that is ON grows an arrow at the end of its row; tap it for the layer's
settings, its map key, and a status line that says what is on the map and what
is not — "zoom in to see", "drawn for the middle of the map", "no storms right
now".

The first time a layer is switched on it asks once, naming the server it will
talk to and what is sent: the area of the map, never your position, unless the
layer says so. **Allow** remembers the answer.

Tap anything Atmosphere draws and it opens straight away: a fire weather zone,
spot request, gauge or buoy opens its page, anything else its details. There is
no radial menu on Atmosphere's items; bloodhound and the rest are ATAK's own
tools.

Every layer that draws on the map is a row inside one **Atmosphere** row in
ATAK's Overlay Manager, each with its own icon, where it can be hidden without
opening the plugin.

<img src="screenshots/15_layers_top.png" width="420"> <img src="screenshots/67_overlay_manager_inside.png" width="420">

| Group | Layer | What it draws |
|---|---|---|
| | Spot Weather Forecast | Every open spot forecast request, with the office's forecast behind a tap |
| | Weather Stations | The stations around you, wind barb and readings, colored against the Red Flag criteria for their zone |
| Wind | Radar | The NWS radar mosaics over the US, Environment Canada's over Canada, and RainViewer's composite of the world's public radars everywhere else, whichever the map is over, with a time scrubber |
| Wind | Satellite | The newest GOES picture, infrared (day and night) or visible; reaches the open ocean where no radar does |
| Wind | Rain | The model's rain rate, light to violent, hour by hour for five days with the rate "Here"; a forecast, not radar |
| Wind | Wind | The forecast wind as moving streaks colored by speed, with a time scrubber, heights to the jet stream, and the wind "Here" |
| Fire | Fire Weather Outlook | Elevated, critical and extreme areas and dry thunderstorms, Day 1, 2, 3 or all; from Day 3 as a chance of critical |
| Fire | Fire Weather Zones | The NWS fire weather zones with their numbers, shaded under a Red Flag Warning or a Fire Weather Watch; the zone's planning forecast behind a tap |
| Fire | SAWTI | The Forest Service's Santa Ana Wildfire Threat Index for four Southern California zones, colored by level for each of four days; the SAWTI page behind a tap |
| Fire | Lightning | NOAA's lightning strike density, the newest 15 minutes, in five bands from under 1 to over 150 strikes in a 5-mile square |
| Fire | PSPS | California's Public Safety Power Shutoffs from Cal OES: counties warned, power off, power back on |
| Fire | Smoke | Forecast smoke at the ground or through the whole sky, with the amount "Here" |
| Fire | Air Quality | The EPA's air quality areas and the index "Here" |
| Rain and rivers | Flash Flood Outlook | Excessive rainfall areas for three days, marginal to high |
| Rain and rivers | River Gauges | Gauges colored by flood category; stage, flow and hydrograph behind a tap |
| Rain and rivers | Flooded Ground | Where the river model puts water over the banks, now or at the worst of the next 5 days (experimental) |
| Rain and rivers | Streams Running High | Stream stretches over their high-water mark, colored by how rare the flow is; the numbers behind a tap |
| Ocean | Buoys | Buoys and coastal stations, readings colored by sea state; tides, currents and the marine forecast behind a tap |
| Ocean | Waves | The wave forecast: seas colored by state, the swell as moving crests, five days on the time strip, with the seas "Here" |
| Ocean | Beach Forecast | Beach areas colored by rip current risk |
| Ocean | Sea Temperature | Sea surface temperature as a picture |
| Ocean | Hurricanes | Active storms: track, cone, wind fields, advisory |
| Snow | Avalanche | Forecast zones by danger rating, travel advice behind a tap |
| Snow | Snow Stations | Mountain snow stations: depth, water equivalent, temperature |
| Snow | Snow Depth | The daily snow analysis as a picture |

## 5. What the layers look like

<table>
<tr><td><img src="screenshots/16_radar_us.jpg" width="420"><br>Radar over the Northeast</td>
<td><img src="screenshots/18_radar_world.jpg" width="420"><br>Radar over Mexico and Central America, from RainViewer</td></tr>
<tr><td><img src="screenshots/20_satellite.jpg" width="420"><br>Satellite, infrared: a hurricane's eye at sea</td>
<td><img src="screenshots/22_wind.jpg" width="420"><br>Wind around the same hurricane</td></tr>
<tr><td><img src="screenshots/32_rain.jpg" width="420"><br>Rain, the model's forecast, where no radar reaches</td>
<td><img src="screenshots/24_firewx.jpg" width="420"><br>Fire weather outlook, Day 2 Elevated</td></tr>
<tr><td><img src="screenshots/26_smoke.jpg" width="420"><br>Smoke at the ground</td>
<td><img src="screenshots/30_flash_flood.jpg" width="420"><br>Flash flood outlook, Day 2</td></tr>
<tr><td><img src="screenshots/36_flooded_ground.jpg" width="420"><br>Flooded ground along a river at major flood</td>
<td><img src="screenshots/39_streams.jpg" width="420"><br>Streams running high on the same river</td></tr>
<tr><td><img src="screenshots/42_waves.jpg" width="420"><br>Waves: sea state and swell</td>
<td><img src="screenshots/52_hurricane.jpg" width="420"><br>Hurricanes: track and cone</td></tr>
<tr><td><img src="screenshots/48_beach.jpg" width="420"><br>Beach forecast: rip current risk</td>
<td><img src="screenshots/54_stations.jpg" width="420"><br>Weather stations with wind and humidity</td></tr>
</table>

## 6. Weather stations, river gauges and buoys as lists

The **Weather Stations**, **River Gauges** and **Buoys** pages list what their layers draw,
nearest first from your position or the map center, out to the distance you
pick. The filters at the top say what each will show, with a count, before you
tap. A star keeps one as a favorite, and a favorite stays on the map and in the
list however far away you go. **Go to** frames one on the map; tapping a row
opens the whole record.

<img src="screenshots/55_station_list.png" width="420"> <img src="screenshots/56_station_record.png" width="420">

- A station's record: every reading, its fire weather zone and the Red Flag
  criteria for it, and the station's own page.
- A gauge's record: stage and flow now, the flood stages, and the observed and
  forecast hydrograph for the last day to the last month.
- A buoy's record: its readings and the sea state they mean, the nearest tide
  station's highs and lows, the nearest current station's ebb and flood, and
  the coastal or offshore waters forecast for the water it sits in.

<img src="screenshots/35_gauge_record.png" width="860">

<img src="screenshots/46_buoy_record.png" width="420"> <img src="screenshots/47_marine_forecast.png" width="420">

Two settings per layer decide when things draw: at this zoom or closer for the
markers, and for their readings. **Use this zoom** takes what the scale bar
reads now; **Always** never hides them.

## 7. Fire weather zones

**Fire Weather Zones** draws the National Weather Service's fire weather zones
in the map view: orange outlines, each with its zone number, a starred zone in
yellow, a zone under a Red Flag Warning filled pink and one under a Fire Weather
Watch beige. Tap a zone for its forecast. Two zoom settings under the layer's
arrow decide when the zones and their numbers draw; a view wider than a few
states says to zoom in.

<img src="screenshots/71_zones_map.png" width="860">

<img src="screenshots/72_zones_layer.png" width="420"> <img src="screenshots/73_zones_key.png" width="420">

The **Fire Weather Zones** page shows the zone the point you are reading is in,
**Red Flag Warning** and **Fire Weather Watch** with how many zones are under
each anywhere in the country, a search by zone number (CAZ548, CA548 or 548)
or by name, and your starred zones. Tap one for the office's Fire Weather
Planning Forecast for that zone, as the office wrote it: western offices write
it by period, eastern ones as a table; slide sideways for a wide line. The star
keeps the zone on the page and yellow on the map. **Show the office
discussion** opens the office's discussion for the day under it, and a
forecast more than a day old says so.

<img src="screenshots/68_zones_find.png" width="280"> <img src="screenshots/69_zone_forecast.png" width="280"> <img src="screenshots/70_zone_forecast_body.png" width="280">

## 8. SAWTI, Lightning and PSPS

**SAWTI** draws the US Forest Service's Santa Ana Wildfire Threat Index for its
four Southern California zones: LA-Ventura, Orange-Inland Empire, San Diego and
Santa Barbara, where it rates Sundowner winds. It rates how hard a fire would be
to fight if one started during an offshore wind: No Rating, Marginal, Moderate,
High or Extreme. A rated zone is filled in its level's color, with its label in
the same color; a No Rating zone is a cyan outline. The date buttons pick one of
the four days the Forest Service shows. Tap a zone to open its page.

<img src="screenshots/80_sawti_map.png" width="560"> <img src="screenshots/81_sawti_layer_block.png" width="300">

The **SAWTI** page is the Forest Service site's forecast page. It opens on the
zone the point you are reading is in, and the zone button picks another. Each
day button shows that day's level. Under the level are the site's three gauges,
**Threat Level**, **Wind Strength** (weak to strong) and **Fuel Moisture**
(moist to dry), then the forecaster's **Event Description** and **Recommended
Actions**, with the county links the site gives for the zone. **All Zones**
puts every zone and day side by side; tap a square for that zone and day. The
issue time is under it, and an issue more than a day old says so. When the wind
and fuel numbers cannot be had, the gauges say *Not available* and the page asks
again the next time you look.

<img src="screenshots/76_sawti_page_top.png" width="420"> <img src="screenshots/77_sawti_page_middle.png" width="420">

<img src="screenshots/78_sawti_page_table.png" width="420"> <img src="screenshots/79_sawti_zone_picker.png" width="420">

**Lightning** draws NOAA's lightning strike density: how many strikes the
ground networks counted in each 5-mile square in 15 minutes, from yellow (under
1) to deep purple (over 150). It counts ground strikes and some cloud flashes.
A new frame comes every 15 minutes and arrives 10 to 25 minutes after the
strikes; the line under the switch says which 15 minutes and how old. It shows
which storms are making lightning and whether they are building. It is not a
reason to call it safe to be outside.

<img src="screenshots/83_lightning_map.png" width="360"> <img src="screenshots/82_lightning_layer_block.png" width="420">

**PSPS** draws California's Public Safety Power Shutoffs as Cal OES reports
them from PG&E, SCE and SDG&E: a county a utility has warned may have a shutoff
in amber, where the power is off in red, off because a line feeding it was cut
in orange, and back on in green. Tap one for its details. The line under the
switch says what is warned and off and when Cal OES last updated; if Cal OES
goes quiet for 45 minutes it says the status is unknown rather than none. It
does not cover PacifiCorp, Liberty, Bear Valley or any other state: ask the
utility.

<img src="screenshots/85_psps_map.png" width="420"> <img src="screenshots/84_psps_layer_block.png" width="420">

<img src="screenshots/86_psps_details.png" width="420">

## 9. Spot Weather Forecast

The layer draws every spot forecast request the National Weather Service has
open, with the forecast the office wrote for each. The page lists them — All,
Near me, On map, by State or by Region — **Newest** or **Closest** first, and a
search box finds one by incident name. Tap a request for its forecast.

<img src="screenshots/58_spots.jpg" width="420"> <img src="screenshots/59_spot_list.png" width="420">

**All Types** picks one kind of request, with how many there are of each; the
kind picked applies to the map as well as the list. Under the layer's arrow the
key shows each kind's icon and what the three colors mean, and **Last 3 days
only** keeps both to requests asked for or answered in the last three days.

<img src="screenshots/74_spot_types.png" width="420"> <img src="screenshots/75_spot_key.png" width="420">

<img src="screenshots/60_spot_forecast.png" width="420">

**Request a spot forecast** prepares yours: pick the point — My position, Map
center, Pick on map, a Favorite, or an Address — and the plugin copies it in the
form the Weather Service's web form takes. Open the form, hold its USNG box,
paste, and press Plot USNG. An address is looked up by the US Census Bureau
first; you see what came back before it is used.

<img src="screenshots/61_spot_request.png" width="420"> <img src="screenshots/64_address_match.png" width="420">

## 10. The user manual

The manual is inside the plugin. Open ATAK's **Settings**, then **Tool
Preferences**, **Specific Tool Preferences**, **Atmosphere**, and tap
**Atmosphere user manual**.

## 11. What it does not do

- It draws no warnings, watches or advisories, except Red Flag Warnings and
  Fire Weather Watches on its fire weather zones. IPAWS Alerts does the rest.
- Its lightning is a 15-minute density map that arrives 10 to 25 minutes
  late, not single strikes as they happen, and not a lightning safety trigger.
- Its power shutoffs are California's three big utilities only, as Cal OES
  reports them; anywhere else, ask the utility.
- It publishes nothing: no path puts anything on another phone or a server.
- It records nothing about you: no callsign, device identifier or TAK server
  detail is ever sent. Your position leaves the device only for the forecast
  when it is set to My position, rounded to about 100 m, and for the station,
  gauge and buoy lists when they are set to My position. The Fire Weather Zones
  page finds your zone by sending the half-degree square the point is in, about
  30 miles across, never the point.
