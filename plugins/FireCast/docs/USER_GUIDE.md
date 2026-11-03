# FireCast for ATAK — User Guide

**Version 0.3 · takwerx**

**Download FireCast 0.3** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/firecast/releases/download/v0.3/ATAK-Plugin-FireCast-0.3--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/firecast/releases/download/v0.3/ATAK-Plugin-FireCast-0.3--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/firecast/releases/download/v0.3/ATAK-Plugin-FireCast-0.3--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/firecast/releases

**Before you start:** the builds listed above are the ATAK-CIV versions with a published FireCast build. Install the one that matches the ATAK on your device.

FireCast puts satellite heat detections on the ATAK map and lets you run a fire spread model on the device, from a point you tap or a perimeter you draw. It also shows other agencies' published fire forecasts beside your own runs.

---

## 1. The pane

Tap the FireCast icon in the toolbar. The pane opens on the right.

![The main pane](screenshots/1_main_pane.png)

From the top:

- **Detections in view**, and how long ago they were fetched.
- **Where is it burning?** The fires in the area on screen, in one list (section 2).
- **Run a Model** opens the model page (section 3).
- **Models**, folded: **FIRIS Model Runs** and **PyreCast Forecasts**, other people's spread runs, one switch each (section 4).
- **Feeds**, folded: the heat detection feeds and NASA's fire perimeters, each with its own switch.
- **Filters** and **Map key**, folded.

"In view" is always the part of the map you can see, never the part behind the pane.

A switch reads `<Name> ON` in green or `<Name> OFF` in red.

## 2. Heat detections

![The feeds](screenshots/2_feeds_list.png)

The feeds:

- **VIIRS** — three satellites, a few passes a day. Each detection is drawn as the square the sensor actually saw, about 375 m across.
- **MODIS** — Terra and Aqua. A MODIS pixel is 1 km at the center of the swath and grows past 4 km at the edge, so the drawn box grows with it.
- **GOES** — a scan every few minutes. These have no footprint of their own, so it is worked out from the satellite's viewing angle.
- **RADR** — burned areas mapped from satellite passes over the last fourteen days. These are real outlines of where fire has been, not where it is.
- **NASA Fire Event Perimeters** — an outline of every fire VIIRS sees in the lower 48, drawn by NASA's Fire Event Data Suite (FEDS) around the VIIRS detections every 12 hours, day pass and night pass. Drawn in magenta, brighter while the fire is active. It gives a fire nobody has mapped yet a perimeter, and a newer one between the incident's own updates. Its edges are 375 m steps, it can be a day old, and it is not the incident's mapped perimeter. It always looks back three days, whatever **Filters** says, because NASA's outlines arrive late.

![VIIRS and GOES detections on the map](screenshots/18_detections_on_map.png)

Above, three VIIRS detections (the small squares, each the 375 m the sensor saw) inside one GOES detection (the large box, worked out from the satellite's viewing angle) on the same fire. The feeds list beside them gives each feed's count in view.

Detections are colored by age, red within the hour, through amber, to gray after a day, and fade with it. **Filters** sets the time window (three hours to three days), the confidence, and whether to hide known industrial heat sources such as flares and solar farms. **Map key** explains the colors.

![A detection's detail panel](screenshots/19_detection_detail.png)

Tap a detection on the map or in the list for its detail panel: how big the footprint is and how that was worked out, when the satellite saw it, the radiative power, and a section called **What this does not mean**. A heat detection is a hot pixel. It does not say the fire is at the center of the box, that an empty map is empty ground, or that a hot pixel is a wildfire rather than a flare.

![NASA perimeters on the map](screenshots/21_nasa_perimeters_on_map.png)

![A NASA perimeter's panel](screenshots/22_nasa_perimeter_detail.png)

A NASA perimeter's panel gives its acres, when NASA drew it, and what it is not. **Run a Model from this perimeter** opens the model page started from that outline.

![Run a Model from this perimeter](screenshots/22b_run_from_perimeter_button.png)

### Where is it burning?

![Where is it burning?](screenshots/27_where_is_it_burning.png)

**Where is it burning?** is one list of the fires in the area:

- GOES fires burning now, with their state, how long they have burned, how many detections and the peak heat. A fire that sits inside a NASA perimeter says so on a second line, with the perimeter's acres and age.
- NASA perimeters with no GOES fire in them, each with its county and state, acres, how long it has burned and how old the outline is.
- GOES fires seen in only one scan, last; those are often burn piles.

Above the list, the order (**Strongest**, **Newest**, **Nearest** or **Biggest first**) and how far back (**Last 3 hours** to **3 days**, the same setting as **Filters**). **Area** and **Where** narrow it. Tap a fire and the map goes there and draws it; a magenta mark shows the spot at once while its detections load. The list stays open for the next one.

## 3. Run a Model

Tap **Run a Model**.

![The model page](screenshots/5_model_page.png)

The top line is always there: **Back**, **Run model**, **Rerun** (the run you have selected again, from its own start, with the conditions set now) and **Clear all**. Under it, once there is a run, **Predicted Fire Size** and **Send run**. Below that the page folds into three boxes, each with a summary line when it is closed: **Start and conditions**, **Runs on the map** and **Timeline Slider**. Tap a box's bar to open or close it; the page remembers which are open.

### Start a run

1. Open **Start and conditions** and tap **Start from**. Pick one:
   - **A point**: tap the map where the fire is. A marker labeled *Fire start* is placed. Drag it, or use Fine Adjust on its menu, to make it exact.
   - **A perimeter you draw**: trace the fire's edge with ATAK's shape tool.
   - **A shape already on the map**: tap it. A shape you drew earlier, one a contact sent, an imported KML, or a perimeter from another layer, such as a FIRIS perimeter in Feature Layer. Every piece of it starts burning. A line is refused, in orange, and the start is cleared so nothing runs by mistake; the outline has to be an area.

   The button then says what the run starts from. To run from a NASA satellite perimeter, tap the perimeter on the map and use **Run from perimeter** on its detail (see section 2).

![Start the Run From](screenshots/25_how_the_fire_starts.png)

2. Set what you know:
   - **Conditions:** *As observed* at the weather station, *As observed but at the gust*, *Dry day*, or *Dry and 20 mph wind*. The last two are for planning what a bad day would do.
   - **Weather:** where the wind, temperature and humidity come from:
     - *A weather station*: automatic picks a nearby station by distance and height, so a station on a ridge does not stand in for a valley; or pick one by name. The list is ready as soon as the start is placed, before any run: each station with its distance, height above or below the start, wind, humidity and the time of its reading. *HRRR wind* and *NWS forecast for this spot* are choices too. **OK** keeps what is ticked.
     - *My observation*: type in what you measured at the fire: wind speed, the direction it comes from (degrees, or N, SW and so on), gust, temperature, humidity, and the 1 h fuel moisture if you have it. Tick *eye level* for a hand-held meter; the model then takes it as the wind at the flames. Otherwise it is a 20 ft wind.
     - *From a Kestrel station on the map*: the stations the Kestrel Wx plugin shares to the team, with their reading and age. Pick one and the form opens filled in, to check or change before using it.

     Your observation is used while it is under six hours old and held through the run. The page and the shared run say whose reading it was and when. *Stop using my observation* goes back to the stations.
   - **Ignition:** when the fire started: not known, now, minutes ago, a clock time, or minutes before dispatch (enter the dispatch time, then how long before). For a point start the run begins at the ignition, so each ring is where the fire should be at its time since, one of them marked *now*, and on by the horizon. With a perimeter it is the fire's age, which calibrates the model's speed.
   - **Horizon:** one hour or three hours ahead.
3. Tap **Run model**. It takes a few seconds.

![The start placed on the map](screenshots/6_start_placed.png)

### What goes into the model

Everything below is fetched for the run and runs on the device. No run is sent anywhere.

**The ground.** From LANDFIRE (USGS and the Forest Service), in 30 m cells, for a square 4 km each way around the start (10 km for a three-hour run):

- the fuel model, one of the 40 Scott and Burgan fuel models (grass, grass-shrub, shrub, timber understory, timber litter, slash);
- the slope and which way the slope faces;
- the canopy: tree cover, tree height, the height of the lowest branches, and how dense the crowns are;
- the vegetation type, which is how the model tells houses, roads and parks apart inside LANDFIRE's single urban class.

The fire moves over this in 15 m steps (30 m for three hours).

**The wind.** Three sources, the best one there is:

- a weather station near the fire, picked by distance *and* height, so a station on a ridge does not stand in for a valley: a RAWS fire weather station, a National Weather Service station, or in California a utility station (SCE, SDG&E, PG&E or HPWREN, through Cal OES), often the closest in the valleys. A utility station averages its wind over a minute or two rather than ten, so it reads a little higher for the same air, and its wind is taken as a 20 ft wind; both err toward more fire. Only a station that reported in the last three hours is picked. **Weather** on the model page picks one by name;
- for the hours ahead, the National Weather Service hourly forecast at the start;
- where it is available, the HRRR 3 km wind forecast from NOAA, which varies across the ground and hour by hour.

**How dry the fuel is.**

- Dead fuel (the 1 h, 10 h and 100 h sizes, twigs to logs): the Forest Service's fire danger figures for the nearest station, with the 1 h worked out from that station's humidity and temperature. With no station, the WFAS national grid.
- Live fuel (brush, grass, conifer needles): the nearest field sample in the National Fuel Moisture Database from the last 45 days, or the seasonal figure when nobody has sampled nearby.

**How it burns.** The Forest Service Fire Lab's Behave library, the same engine as BehavePlus, compiled into the plugin: Rothermel's surface fire spread, Scott and Reinhardt's crown fire (whether the fire stays on the ground, torches single trees or runs through the crowns), and Albini's ember distances.

**How it spreads.** Cell by cell over the real ground, each cell at its own fuel, slope, canopy and wind. Embers carry it across a road or a block narrower than they fly. Roads slow it; commercial blocks, water, bare ground and farmed ground stop it. Houses burn house to house at the rate measured in nine North American conflagrations (Moran, Seielstad and Pietruszka, 2025).

**What it reaches.** Buildings and the people in them from FEMA's national building inventory (USA Structures). A building counts when the fire reaches the ground beside it. People are FEMA's upper estimate for each building, shown as "up to", because its middle estimate is zero for most second homes.

**From a perimeter.** A perimeter is drawn by hand or taken from NASA's satellite outline (FEDS, from openveda.cloud), every piece of it. A perimeter wider than the model's usual square gets a bigger square, up to about 25 miles across, with coarser cells. With a perimeter and a start time, the model first runs from the middle of the perimeter for the time the fire has burned and compares that with what it actually covered. It then scales its rates to match, at most three times faster or slower, and says so on the page.

**Predicted Fire Size.** The run is repeated 30 times with the wind speed, wind direction and fuel moisture nudged the way forecasts miss, and ranked by how much burned (below).

**What is not in it.** Firefighting. Wind the fire makes itself. Wind bent by a canyon finer than the forecast grid. A fuel break narrower than 15 m. How the fuel looks today if it has changed since LANDFIRE mapped it.

### Reading a result

![A run's result](screenshots/7_run_result.png)

- **Timeline Slider** opens on the run. Its line says where the fire is by a time, with its area, how far it has gone and how many buildings.
- The **time slider** moves the rings on the map between the start and the end of the run. The edge at the slider's time is drawn in cyan.
- The **table** lists each time step with the area in acres, buildings and people reached (up to).

![A run on the map](screenshots/8_run_on_map.png)

![The slider on a run](screenshots/17_run_slider.png)

Drag the slider and the cyan edge moves to that time, with the line above it saying where the fire is by then.

On the map each time step is a ring, red for the first, then orange, then yellow, with the burned ground filled in.

A run can be small. A litter fire under trees in a light wind may cover a fraction of an acre in an hour, and the page says so: *A slow fire: the fuel here carries fire at ...*. A run from a perimeter on a wet night may not grow past its edge at all, and the page says why: *No spread past the perimeter: the fine dead fuel is 28% water*. Set **Conditions** to *Dry day* or *Dry and 20 mph wind* to see what a bad day would do from the same start.

![A run from a NASA perimeter](screenshots/23_run_from_nasa_perimeter.png)

![The slider on a run from a NASA perimeter](screenshots/24_slider_nasa_run.png)


### Predicted Fire Size

A fire does not spread exactly as forecast. The wind misses, the fuel is drier or wetter than measured. FireCast repeats each run 30 times with the wind speed, wind direction and fuel moisture nudged the way a forecast misses, ranks the 30 by how much burned, and offers five of them.

![The Predicted Fire Size picker](screenshots/9_size_picker.png)

Tap **Predicted Fire Size** to pick **Smallest**, **Smaller**, **Median**, **Larger** or **Largest**. It starts on Median. Everything on the page and the map becomes that one run: the rings, the slider, the acres and the buildings. The five names and the percentile wording match PyreCast's.

| Smallest | Largest |
|---|---|
| ![Smallest](screenshots/10a_size_smallest.png) | ![Largest](screenshots/10b_size_largest.png) |

The same start, the same map: the smallest of the 30 runs and the largest. In a light wind they are close; the stronger the wind, the further apart they come.

### The detail pills

Under the results, the rest is folded away so the page does not become one long scroll. A closed pill shows its first line. Tap a pill to open it, tap again to close it.

![Where it goes, open](screenshots/11_where_it_goes_open.png)

- **Where it goes:** each time step in words, with embers, crown fire and the buildings reached.
- **About Predicted Fire Size:** what the picker is.
- **Burn behavior:** flame length and fireline intensity, with what a hand crew can hold, and what the run does not mean.

![What went in, open](screenshots/12_what_went_in_open.png)

- **What went in:** the fuel, slope and canopy, the wind and where it came from, and the moisture. Check this first when a run looks wrong: a station far from the fire is the usual reason.

**Rerun** on the top line runs the selected run again from its own start with fresh weather, and **Send run** shares it as one file through ATAK's Send dialog.

### Runs on the map

Each run is listed with its time, fuel, wind and area; the one the page shows has the arrow and is in cyan. Tap a run to show it. **ON** and **OFF** show or hide it on the map. **Delete** removes it. Runs are kept on the device.

**Show: In view** lists only the runs in the part of the map beside the pane, and says how many of all of them that is; tap it for **Show: All runs**. **Opacity** fades every run on the map, from 10% to 100%, so the ground under the rings can be read.

## 4. Other forecasts

![The PyreCast switch](screenshots/14_pyrecast_switch.png)

Under **Models** on the pane: **FIRIS Model Runs** shows the spread runs that California's fire analysts publish for ATAK. **PyreCast Forecasts** shows the ELMFIRE forecasts PyreCast (CloudFire, Inc.) publishes for large fires across the country, shown with their permission, with their logo and a link to pyrecast.com. Each is a switch. Long press a layer's switch for its list, and pick a fire to put its time slider on it. Tap the run's name above its slider and the map goes to it.

A FIRIS run also counts what it reaches, from the same FEMA building inventory as FireCast's own runs: *6 structures (6 homes), up to 30 people*, by the slider's hour, and schools, hospitals and the like by name. The count is made once and kept on the device.

![The PyreCast list](screenshots/14b_pyrecast_list.png)

![A PyreCast forecast on the map](screenshots/15_pyrecast_on_map.png)

Each forecast runs out to about two weeks, drawn from yellow for the first hours to blue for the last.

![The slider on a PyreCast forecast](screenshots/16_slider.png)

Drag the slider to any time in the forecast. The edge at that time is drawn in cyan, and the pane says where the fire is by then: *By Nov 2 11:04, in 3 days: 1,236 acres.*

PyreCast ranks its own runs by how much burned at the end of the forecast. FireCast shows the middle run unless you pick the smaller or larger end, and shows one run at a time.

## 5. Offline

Everything FireCast has fetched or run stays on the device. After a restart of ATAK, or with no signal, your runs, the last detections, NASA's perimeters, the FIRIS runs with their structure counts and the PyreCast forecasts come back, with their age on the pane. Switching a layer off only hides it. A failed fetch never empties what you already have, and a new run with no signal says what it could not reach, for example "Could not read the fuel and slope here", and leaves your other runs alone.

## 6. What to keep in mind

A spread model gives a direction and an order of magnitude. It is not a plan to base a perimeter on. Fuel and slope are the real ground, but the wind is one reading or a coarse forecast and cannot know a canyon, there is no suppression in it, and house-to-house spread is an average over past fires, not this street. Run it again as the wind changes.

## Questions and bugs

Open an issue at https://github.com/takwerx/firecast/issues.
