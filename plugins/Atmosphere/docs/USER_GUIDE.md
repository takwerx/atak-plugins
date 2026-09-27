# Atmosphere for ATAK — User Guide

**Version 0.1 · takwerx**

**Download Atmosphere 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/atmosphere/releases/download/v0.1/ATAK-Plugin-Atmosphere-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/atmosphere/releases/download/v0.1/ATAK-Plugin-Atmosphere-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/atmosphere/releases/download/v0.1/ATAK-Plugin-Atmosphere-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/atmosphere/releases

Atmosphere is the weather on the ATAK map for fire and hazmat crews: the
forecast at a point, the readings from the weather stations, river gauges and
buoys around you, and the pictures the National Weather Service publishes —
radar, wind, smoke, air quality, fire and flood outlooks, flooding, snow and
the sea — each one a layer you switch on when you need it.

Everything it shows is read by your device from public US government services
and stays on your device. Nothing is published to a server or to anyone else's
map. No layer talks to the network until you allow it, once, by name.

Warnings, watches and advisories are not here on purpose: they are the
[IPAWS Alerts](https://github.com/takwerx/ipaws-alerts) plugin's job, and the
two are meant to run side by side.

---

## 1. Before you start

- Published builds exist for **ATAK-CIV 5.6, 5.7 and 5.8**. Install the one that
  matches your ATAK exactly; a build for another version will not load.
- The phone needs a network path to the US government weather services; each
  layer names its server when it first asks to be allowed. A reading that has
  been fetched stays readable when the network drops, with its age on it.
- Screenshots for this guide are being made from the first signed build and
  will be added here.

## 2. The pane

Open Atmosphere from the ATAK toolbar, or from Tools if it is not on the bar.
The pane opens at half width; **Wide** makes it full width and back. Back closes
it, or narrows a wide one first.

It is six pages, side by side. Swipe, tap a dot, or press the arrow button:

1. **Forecast** — the readout for a point.
2. **Layers** — every map layer, its switch and its settings.
3. **Spot forecasts** — the open spot forecast requests, and yours.
4. **Weather stations** — the stations around you, as a list.
5. **River gauges** — the gauges around you, as a list.
6. **Buoys** — the buoys and coastal stations around you, as a list.

The row of buttons at the top is the same on every page: **My position**,
**Map center** and **Pick a point** choose where the forecast is read; the star
is **Favorites**; the unit button switches wind between knots and miles per
hour; then **Wide**, **Next page**, **Refresh** and **Settings**.

## 3. The forecast

The first page is the readout for one point: your position, the map center, a
point you tap on the map, or a favorite. The top line names the point and how
old the reading is.

- **Now**: temperature, humidity, the 20-foot wind and where it is from, gusts,
  mixing height, transport wind, wet bulb globe temperature and the chance of
  rain. An element the forecast office does not issue says so.
- **Next hours**: a strip of the next hours, one value at a time; the chips
  under it pick which. **Show hourly table** opens the whole table.
- **Next days**: the days ahead.
- **Sun and moon** for the point.

**Settings** on this page picks the weather service the forecast comes from,
what to show, and how coarsely your position is rounded before it leaves the
device. **Favorites** keeps places by name.

## 4. The layers

The second page lists every layer under a heading for what it is about:
spot forecasts and weather stations at the top, then **Wind**, **Fire**,
**Rain and rivers**, **Ocean** and **Snow**. Each row is a switch that shows its
state — green ON, red OFF — with the layer's icon beside it. **All on** and
**All off** switch every allowed layer at once.

A layer that is ON grows an arrow at the end of its row; tap it for the layer's
settings, its map key, and a status line that says what is on the map and what
is not — "zoom in to see", "drawn for the middle of the map", "no storms right
now".

The first time a layer is switched on it asks once, naming the server it will
talk to and what is sent: the area of the map, never your position, unless the
layer says so. **Allow** remembers the answer. Every layer is also in ATAK's
Overlay Manager under Atmosphere.

| Group | Layer | What it draws |
|---|---|---|
| | Spot forecasts | Every open spot forecast request, with the office's forecast behind a tap |
| | Weather stations | The stations around you, wind barb and readings, colored against the Red Flag criteria for their zone |
| Wind | Radar | The national radar mosaic, with a time scrubber |
| Wind | Wind | The modeled wind field, with a time scrubber and the wind "Here" |
| Fire | Fire weather outlook | Elevated, critical and extreme areas and dry lightning, Day 1, 2, 3 or all |
| Fire | Smoke | Forecast smoke, ground or whole sky, light or dense |
| Fire | Air quality | The EPA's air quality areas and the index "Here" |
| Rain and rivers | Flash flood outlook | Excessive rainfall areas for three days, marginal to high |
| Rain and rivers | River gauges | Gauges colored by flood category; stage, flow and hydrograph behind a tap |
| Rain and rivers | Flooded ground | Where the river model puts water over the banks, now or at the worst of the next 5 days (experimental) |
| Rain and rivers | Streams running high | Stream stretches over their high-water mark, colored by how rare the flow is; the numbers behind a tap |
| Ocean | Buoys | Buoys and coastal stations, readings colored by sea state; tides, currents and the marine forecast behind a tap |
| Ocean | Beach forecast | Beach areas colored by rip current risk |
| Ocean | Sea temperature | Sea surface temperature as a picture |
| Ocean | Hurricanes | Active storms: track, cone, wind fields, advisory |
| Snow | Avalanche | Forecast zones by danger rating, travel advice behind a tap |
| Snow | Snow stations | Mountain snow stations: depth, water equivalent, temperature |
| Snow | Snow depth | The daily snow analysis as a picture |

## 5. Stations, gauges and buoys as lists

The **Weather stations**, **River gauges** and **Buoys** pages list what their
layers draw, nearest first from your position or the map center, out to the
distance you pick. The filters at the top say what each will show, with a count,
before you tap. A star keeps a station as a favorite, and a favorite stays on
the map and in the list however far away you go. **Go to** frames one on the
map; tapping a row opens the whole record.

- A station's record: every reading, its fire weather zone and the Red Flag
  criteria for it, and the station's own page.
- A gauge's record: stage and flow now, the flood stages, and the observed and
  forecast hydrograph for the last day to the last month.
- A buoy's record: its readings and the sea state they mean, the nearest tide
  station's highs and lows, the nearest current station's ebb and flood, and
  the coastal or offshore waters forecast for the water it sits in.

Two settings per layer decide when things draw: at this zoom or closer for the
markers, and for their readings. **Use this zoom** takes what the scale bar
reads now; **Always** never hides them.

## 6. Spot forecasts

The layer draws every spot forecast request the National Weather Service has
open, with the forecast the office wrote for each. The page lists them — All,
Near me, On map, by State or by Region — and a search box finds one by incident
name. Tap a request for its forecast.

**Request a spot forecast** prepares yours: pick the point — My position, Map
center, Pick on map, a Favorite, or an Address — and the plugin copies it in the
form the Weather Service's web form takes. Open the form, hold its USNG box,
paste, and press Plot USNG. An address is looked up by the US Census Bureau
first; you see what came back before it is used.

## 7. The user manual

The manual is inside the plugin. Open ATAK's **Settings**, then **Tool
Preferences**, **Specific Tool Preferences**, **Atmosphere**, and tap
**Atmosphere user manual**.

## 8. What it does not do

- It draws no warnings, watches or advisories. IPAWS Alerts does.
- It publishes nothing: no path puts anything on another phone or a server.
- It records nothing about you: no callsign, device identifier or TAK server
  detail is ever sent. Your position leaves the device only for the forecast
  you ask for, rounded as coarsely as you set, and for the station, gauge and
  buoy lists when they are set to My position.
