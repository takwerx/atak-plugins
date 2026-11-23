#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Sit Rep",
   plugin-version: "0.6",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

#toolbox.side-by-side(columns: (1.5fr, 9fr))[
  #image("plugin_icon.png", width: 85%)
][
Sit Rep makes a situation report for any area you choose. It asks the public
agency sources for that area and writes a read-only report that you can save,
refresh and open again with no network.
]

#v(6pt)

- *What is happening now:* weather, alerts, 911 calls where an agency
  publishes them, wildfires, evacuations, road events, airspace, cameras,
  disaster declarations.
- *Who and what is here:* jurisdiction, people, hazard ratings, flood zones,
  structures, terrain, radio sites.
- *Places that matter:* hospitals, schools, care homes, shelters, stations,
  power, dams and more, each one a switch to put them on the map.
- *History:* past wildfires, firefighter fatalities and entrapments.
]

#tak-slide[
= Before you start

- *Match the plugin to your ATAK version.* This release is published for
  ATAK 5.6, 5.7 and 5.8.
- *A network connection makes the report.* A saved report opens with no
  network. Live sections (alerts, fires, weather) show their age.
- *Facts, not judgments.* Every line is what an agency published. Where a
  source does not cover a place, the report says "Not published here".
- *The other takwerx plugins are the detail.* Each section ends with an Open
  or Get button for the plugin that shows the same data in full.

#v(6pt)

Load Sit Rep from ATAK's Plugins manager, then open it from the toolbar with
the SITREP icon (a clipboard, binoculars and a radio):

#v(4pt)

#image("07.jpg", width: 100%)
]

#tak-slide[
= The main screen

#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("01.png", width: 100%)
][
- *New area* makes a report.
- *Settings* holds the Nearby distance, the zoom at which places show, and
  the map key.
- *Areas ON / OFF* shows or hides the outlines of saved reports on the map.
- The arrows button makes the pane wide or half width.
- *Reports* lists every saved report, newest first, with its main numbers.
  *Map ON / OFF* on a row shows or hides that report on the map.
- Tap a report to read it.
]
]

#tak-slide[
= Make a report

#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("02.png", width: 100%)
][
Tap *New area* and choose how to give the area:

- *Draw an area:* tap each corner on the map, then tap the first point to
  close it.
- *Use a shape:* tap a polygon, circle or rectangle already on the map.
- *Search:* type a city, town, community or county and pick it from the
  list.
- *Tap on map:* tap a point and choose the county or city there.

The report fills in section by section. The status line counts the sections
still loading.
]
]

#tak-slide[
= Search and Tap on map

#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("09.png", width: 78%)
][
*Search* lists the places that start with what you typed, nearest to the
map first. A small typo still finds the place. A town with no Census
boundary is a circle around its point ("town, no boundary").

#v(6pt)

#image("10.png", width: 80%)

*Tap on map* offers the city and the county at the point you tapped.
]
]

#tak-slide[
= A new report

#image("08.jpg", width: 74%)

The area on the map, and the report filling in: "Making sit rep: 15 of 17
sections."
]

#tak-slide[
= Read a report

#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("03.png", width: 100%)
][
The buttons across the top:

- *Back* to the list. *Go to* puts the area in view.
- *Refresh* runs the live sections again for the same area.
- *Delete* removes the report. *Rename* (the pencil) changes its name.
- *Settings* (the gear). *Wide or half width* (the arrows).

Each section is one row. Its head is the short answer. Tap the row to open
it.
]
]

#tak-slide[
= The area on the map

#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("11.jpg", width: 72%)
][
*Go to* puts the report's area in view, its outline drawn on the map.

Tap the outline to open that report.

*Areas ON / OFF* on the main screen shows or hides every saved area;
*Map ON / OFF* on a report's row does it for that one.
]
]

#tak-slide[
= Open a section

#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("04.png", width: 100%)
][
An open section shows its detail. Groups inside it (here "Next 24 hours")
open the same way.

- Live sections show their age, for example "(8 min ago)".
- A source that did not answer says "Not available". Tap *Refresh* to try
  it again. The rest of the report stays.
- "Not published here" means no agency publishes this for the place. It is
  not the same as "none".
- A line with a web page opens it in the device's browser.
]
]

#tak-slide[
= Places on the map

#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("05.png", width: 100%)
][
In the *Places* section, each category is a switch with its count:
*OFF* in red, *ON* in green. Tap the name to put that category on the map.
Tap the arrow to list the places, nearest first.

Tap a place on the map for its radial: *Details* (opens it in the report),
*Bloodhound*, *Range and Bearing* and *polar coordinates*.

Places show only at the zoom you set in Settings, so the map stays readable.
The status line says when places are hidden.
]
]

#tak-slide[
= Places on the map

#image("12.jpg", width: 74%)

Hospitals and Schools ON in the Corona report, at street zoom.
]

#tak-slide[
= The radial and Details

#toolbox.side-by-side(columns: (1fr, 1.6fr))[
  #image("13.jpg", width: 78%)
][
#image("14.jpg", width: 100%)

Tap a place for its radial: *Details*, *Bloodhound*, *Range and Bearing*
and *polar coordinates*. *Details* opens the report at that place, its row
marked: here the nearest emergency room.
]
]

#tak-slide[
= Nuclear plants

#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("15.jpg", width: 72%)
][
A nuclear plant within 50 mi of the area is listed under Places. On the map
it carries its two NRC planning zones:

- *10 mile plume zone* (red): after a release, shelter or evacuation may be
  ordered here.
- *50 mile ingestion zone* (amber): food and water may be controlled here.

Zoom out to see the 50 mile ring.
]
]

#tak-slide[
= Cooling and warming centers

Where a city publishes its list of cooling or warming centers, Places shows
them within Nearby: *Cooling centers* and *Warming centers*, each a switch.
Chicago is the first city.

Each center shows its type, its hours, the address and the phone number.
The list is the city's sites, not a statement that a site is open now.
Call ahead before you send people.
]

#tak-slide[
= 911 calls

#image("16.jpg", width: 74%)

Where a city or county publishes its 911 calls, the section lists the last 2
hours inside the area, by agency. *Calls on map* puts them on the map and
fetches them again every 5 minutes. Medical calls are shown to the block in
the text.
]

#tak-slide[
= Settings

#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("06.png", width: 100%)
][
- *Nearby:* how far around the area Sit Rep looks for hospitals, shelters,
  fires, history and other things outside the area itself.
- *Places show at:* the scale bar distance at which places appear on the
  map. Pick a value, *Always*, or *Use this zoom* for the zoom you have now.
- *Map key:* what each map symbol means.

#image("17.png", width: 45%)

This manual is in ATAK under *Settings, Tool Preferences, Sit Rep, Plugin
Documentation*.
]
]

#tak-slide[
= Where this manual lives

#image("18.png", width: 100%)

ATAK *Settings > Tool Preferences > Sit Rep > Plugin Documentation*.
]
