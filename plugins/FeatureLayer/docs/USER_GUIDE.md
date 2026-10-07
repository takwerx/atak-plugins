# Feature Layer — User Guide

**Download Feature Layer 0.12** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/feature-layer/releases/download/v0.12/ATAK-Plugin-FeatureLayer-0.12--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/feature-layer/releases/download/v0.12/ATAK-Plugin-FeatureLayer-0.12--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/feature-layer/releases/download/v0.12/ATAK-Plugin-FeatureLayer-0.12--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/feature-layer/releases

## Before you start

Builds are published for ATAK-CIV 5.6, 5.7 and 5.8. Install the one that matches
your ATAK version, then enable it in ATAK's Plugins manager. NIFC incidents need
your own NIFC ArcGIS login; the other built-in sources are public.

Feature Layer puts live ArcGIS feature layers on the ATAK map the way a GIS
analyst sees them: every point, line and area stays a feature you can tap for its
attributes, drawn with the symbols its community uses. Layers you add stay on the
phone, on or off, draw from the cache the moment ATAK starts, and refresh on their
own when there is signal. With no network they still come back after a restart.

## Opening it

![The Feature Layer icon in the toolbar](screenshots/1_toolbar.png)

Open it from the ATAK toolbar: the globe with the layer stack. Tapping it again
closes the pane.

The pane opens on one row of buttons and your layers under it: **Add Layer**,
**Find** and **All ON / All OFF**. **Add Layer** opens a page of sources; **Back**,
or adding a layer, returns to the list. **Find** searches every loaded layer at
once, whatever source it came from.

## Pick a source

![Pick a source](screenshots/2_pick_a_source.png)

- **NIFC** is the live National Incident Feature Service. It needs your own NIFC
  ArcGIS login.
- **SARCOP Training** is NAPSG's public search-and-rescue sandbox.
- **New Fire Starts** is new wildfires and prescribed fires across the country,
  the last 24 hours, public.
- **Ongoing Fires** is every fire not yet contained, from the day after it was
  found, public.
- **Fire History** is where fires have burned since 1900, public.
- **CA Air Intel** is statewide California fire perimeters, public.
- **NIFS Archive (demo)** is last year's Dragon Bravo fire, public, for trying the
  symbology without a login.
- **Add your own org…** takes any ArcGIS Online organization or ArcGIS Enterprise
  portal.

## Signing in

![The main pane with NIFC picked](screenshots/3_main_pane_signed_in.png)

In **Add Layer**, with NIFC picked, tap **Sign in**. The organization's own login page opens inside
the pane, with multi-factor sign-in if the org uses it. The plugin never sees your
password.

![The NIFC login page](screenshots/4_signin_page.png)

A sign-in lasts two weeks and survives restarts and updates. The button then reads
**Sign out** with your account name. Your organization's token is only ever sent
to that organization's own servers.

![Sign out](screenshots/5_sign_out_button.png)

## Finding a fire

![Which fire?](screenshots/6_fire_picker.png)

Type part of a fire name and tap **Find fire**, or leave the box blank for the
latest fires. Pick one from the list and it loads: event points, event lines, the
perimeter lines and polygons, IR points and polygons, accountable property and
label points.

SARCOP Training works the same way with **Find incident**. CA Air Intel and New
Fire Starts need no search: **Add perimeters**, **Add new starts**.

## Layers

![Two layer rows](screenshots/7_layer_rows.png)

Each layer is one line: its name and **ON** or **OFF**. Tap it to turn the layer
on or off. The arrow at the right opens its controls: a line saying how many
features it holds, when it last refreshed and the refresh interval, then
**Features**, **Go to**, **Auto** (how often it refreshes), **Refresh** and
**Remove**. The arrow works while a layer is off, and each row stays open or
closed the way you left it.

**No network.** Everything a layer has downloaded stays on the phone whether the
layer is on or off, and comes back after ATAK or the phone restarts. With no signal
the row says *no network, showing what this phone saved*. What you see is what was
last downloaded: a layer that loads what is in view has the area you last looked at.

**All ON / All OFF** beside the heading works every layer at once. A row says
**Loading…** while its layer fetches. **Go to** frames the whole incident; layers
spread across a state or the country (DART, FireGuard, New Fire Starts, CA Air
Intel) have no Go to.

## On the map

![An incident at incident scale](screenshots/8_incident_map.png)

NIFC layers draw with the NWCG PMS 936 symbology: the standard point icons, the
fire perimeter in red or black, dozer lines as chains of X's, hand lines with H's
in the line, roads with R's, planned lines in magenta.

![The uncontained edge and a dozer line](screenshots/9_edge_and_dozer.png)

The uncontained fire edge carries its ticks on the burned side, the way the
standard draws it.

![A completed road as line](screenshots/10_road_letters.png)

Letters and X's are drawn on the ground, so they grow as you zoom in and stay part
of the line when you tap it.

## Features

![The Features list](screenshots/11_features.png)

**Features** lists what a layer holds, each with its own **ON/OFF**. Areas also
have a fill button. At the top: **Zoom gate**, **Labels**, and for NIFC layers
**Repair status halos**. Everything here is remembered per layer, across restarts
and updates.

![The fill button](screenshots/12_fill_button.png)

The fill button cycles **Fill 25%**, **Fill 50%** and **Outline**. It changes the
map at once, no refetch.

![Repair status halos on the map](screenshots/13_repair_halos.png)

**Repair status halos** is off by default, like NIFC's own incident map. On, a
line's repair status shows as a colored band under it and a point's as a disc
behind its symbol, in the standard's colors.

## Zoom gate

![The zoom gate picker](screenshots/14_zoom_gate_picker.png)

A layer can hide itself when the map is zoomed out, so a statewide view is not
covered in symbols. **Use this zoom** takes the scale the map is at right now as
the limit. The button beside it names the current limit as a scale-bar reading and
opens a picker: a quarter mile up to fifty, or **Always**. Zoomed out past the
limit nothing in the layer draws; zoom in and it all comes back.

## Search

![Blank Find lists the types](screenshots/15_find_types.png)

**Find** on the main screen searches every loaded layer at once; **Find** inside a
layer's Features searches only that layer. With nothing typed, **Find** lists every kind of thing the
layer holds with a count. Tap one to list its features.

![Search results](screenshots/16_search_results.png)

Type a name, a number or a type for a free search across names and every
attribute. Features named for what you typed come first; under them, features that
only mention it in another field, each saying which field and what it says. Each row shows what it is, when it was collected, and how far away, in
your ATAK units, with **tap to go there**. **From: Me** or **From: Map center**
says where distances are measured from, and the list follows it: pan the map on
**Map center**, or walk on **Me**, and the distances keep up.

![Sort by](screenshots/17_sort_picker.png)

Sort by **Nearest**, **Newest**, **Oldest** or **Name**. **Clear** empties the box
and brings the list of types back. The pane remembers your sort and measuring
point.

## Tap a feature

![Select Item](screenshots/18_select_item.png)

Tap on the map and ATAK lists what is under your finger, each with its symbol.
Where lines overlap you pick the one you meant.

![The radial menu on a point](screenshots/19_radial_menu.png)

The radial menu offers **Details**, **Bloodhound**, a **range and bearing** line,
**polar coordinates** and **Marker here**. Bloodhound plants a marker to follow
and removes it when the bloodhound stops; Marker here leaves one.

![Details](screenshots/20_details.png)

**Details** shows every attribute the service carries, with **Back** pinned at
the top.

## DART

![DART and FireGuard under the search box](screenshots/30_nifc_row.png)

DART is NIFC's live position feed: the vehicles of the federal wildland fire
fleet (USFS and the DOI agencies, by AVL) and people sharing a last known
location from Field Maps, a Garmin inReach or WFTAK. Once you are signed in to
NIFC, **DART** and **FireGuard** sit under the search box.

![Add DART](screenshots/31_add_dart.png)

**DART** asks which half you want. Personnel and vehicles are two layers, each
turned off or removed on its own. Both are 24-hour views that refresh every
minute, and both sit at the top of the layer list whatever else is loaded.

![The Vehicles row](screenshots/07b_layer_row_dart.png)

A DART row's controls also carry a scope: what the layer fetches.

### On the map

![Rigs with rings and callsigns](screenshots/33_rigs_rings_labels.png)

Every rig is drawn the way NIFC's own EGP viewer draws it, with its whole
callsign above the icon. The ring says how recently it reported: green inside
10 minutes, yellow inside 70, red older than that. A parked engine reports every
half hour or hour, so yellow is normal for one that is not moving.

![Forest Service green beside BLM lime](screenshots/35_usfs_and_blm.png)

The icon says what it is, engine, crew carrier, dozer, light vehicle, and the
color says whose: green for the Forest Service, lime yellow-green for the U.S.
Wildland Fire Service and BLM, grey for a rig with no agency in the feed.

![A DART pin, inReach handsets and a WFTAK badge](screenshots/36_personnel_three_kinds.png)

People come three ways. A yellow DART pin is a Field Maps user, the handset on a
light disc is a Garmin inReach, the badge on a light disc is a WFTAK user. An
inReach that has sent an S.O.S. gets a red S.O.S. label at every zoom.

### What it fetches

![Scope controls](screenshots/37_scope_controls.png)

**What is in view**, the default, fetches what the map shows and follows the
map: pan or zoom, and a second or two later the rigs there appear. **My
Location** and **Map Center** fetch a radius instead, set on the slider or with a
preset, and follow you or the map center.

![Zoom in to load](screenshots/43_view_ceiling_status.png)

A view wider than about 300 miles is not fetched; the status line says to zoom in
and the layer keeps what it has. A layer never draws more than 300 rigs at once;
when there are more, the row says so and asks you to zoom in.

![Zoomed out: discs only](screenshots/34_rigs_bare_zoomed_out.png)

Zoomed out, the callsigns come off and the discs stay, so a wide view stays
readable. Where that happens is the Label zoom.

## Label zoom

![Labels, Label zoom and the ring legend](screenshots/32b_vehicles_labelzoom_legend.png)

Every layer has a **Label zoom** under its **Labels** switch: names show from that
scale-bar reading and closer, symbols alone further out. Out of the box it is
about five miles for DART and FireGuard and about a mile for fires; change it per
layer. The **Reported** line under it is the ring legend.

![The Label zoom picker](screenshots/47_label_zoom_picker.png)

Tap the reading to pick a preset, or **Always** to label at every zoom. **Use
this zoom** takes the zoom the map is at right now, the same way the zoom gate
does.

## Find in DART

![The type catalog with counts in view](screenshots/38_find_type_catalog.png)

**Find** with nothing typed lists every kind of rig the layer has ever seen, with
how many are in view beside each. Pick one and the list follows the map: pan, and
it fills with that kind wherever you look.

![A picked type](screenshots/39_type_picked_list.png)

Each row shows the kind, how long ago it reported in the ring's color, and how
far away. **Go** pans to it; tap the row for its details. When the view holds
none of that kind, the line says so.

![A typed search asks the feed](screenshots/40_typed_search_feed.png)

Type a callsign or part of one and **Find** asks the feed itself, the whole
country, not just the view: "31 matches on the feed, anywhere". Rows are sorted
by distance from you or the map center.

![Details from the list](screenshots/41_row_details.png)

Tap a row for its details: every attribute the feed carries, with **Go there** at
the top and **Back** to the list. The same details come from the radial menu on
the map.

## FireGuard

![A FireGuard detection](screenshots/45_fireguard_dome_detection.png)

**FireGuard** adds NIFC's FireGuard detections: the areas the analysts draw around
a satellite heat detection, named by type and acreage, marked URGENT when they
flag it so. The fill is the detection's age the way EGP colors it: maroon in the
first half hour, red to 75 minutes, orange to two hours, yellow through the first
day, grey after four.

![The FireGuard row](screenshots/44_fireguard_row.png)

The layer refreshes every five minutes and sits under DART in the list. The time
window is 24 hours by default; widen it up to EGP's 14 days from the Features
panel.

![Find in FireGuard](screenshots/46a_fireguard_find_list.png)

![A detection's details](screenshots/46_fireguard_details.png)

Tap a detection, or a row in **Find**, for its details: type, acres, county,
jurisdiction, dispatch center, the weather at detection, and when it was created
and last edited.

## Tap a rig

![Rigs stacked at one spot](screenshots/49_chooser_rigs.png)

Rigs parked together stack on the map. Tap the stack and ATAK lists one row per
rig, with its callsign and position, so you pick the one you meant. The radial
menu on a rig offers the same **Details**, **Bloodhound** and **range and
bearing** as any feature.

## SARCOP

![Which SARCOP incident?](screenshots/21a_sarcop_incident_picker.png)

**SARCOP Training** with **Find incident** lists NAPSG's training incidents; blank
lists them all. Pick one and it loads.

![A SARCOP training incident](screenshots/21_sarcop_map.png)

SARCOP layers draw with NAPSG's own symbology: tracklogs in their mission-type
colors, search segments and divisions outlined by status, the incident area as a
dashed outline, waypoints and worksites with the US&R symbols.

![The SARCOP Features list](screenshots/22_sarcop_features.png)

Its Features list has one row per SARCOP layer: Branches, Divisions, Incident
Area, Logistics Points, Search Segments, Tracklog, Waypoints and Worksites.

## CA Air Intel

![Add perimeters](screenshots/23a_ca_air_intel_add.png)

One layer, statewide, public. **Add perimeters** puts it in the list; the button
then goes away until the layer is removed.

![The CA Air Intel row](screenshots/23b_ca_air_intel_row.png)

It refreshes every minute, so a perimeter posted by an aircraft is on the map
within a minute or two of reaching ArcGIS.

![The CA Air Intel Features list](screenshots/23_ca_air_intel_features.png)

Its Features list has a row per source with a line saying what it is: CAL FIRE
intel flights, FIRIS, USFS, NIFC, WFIGS, EGP and the FIRIS WFIGS combo. Each
toggles on its own. Only the latest perimeter per fire per source is drawn, so a
fire flown by USFS, then CAL FIRE, then FIRIS shows one perimeter per source, not
every pass.

![Time window](screenshots/24_time_window.png)

**Time window** picks how far back to look, the last 24 hours up to thirty days or
all time. Widening the window adds fires, not copies.

![Perimeters named at the center](screenshots/25_perimeter_labels.png)

Active perimeters draw in orange with a dark red edge, named at the center.
Inactive ones are not fetched.

## New Fire Starts

New wildfires and prescribed fires across the country, from the moment a dispatch
center enters them: NIFC's own "New Starts" view. A start stays until it is
contained, controlled or out, or for 24 hours after it was found. Public, no
login. **Add new starts** puts it in the list.

Each start is a marker the size of a DART one: a red flame for a wildfire, a green
**RX** for a prescribed fire. A flame drawn in outline is a wildfire reported with
no size and nothing added since. The arrow on the layer's row opens a map key with
all three. Names and acres show from five miles in.

Its Features list has three types, each **ON/OFF**: **Wildfire**, **Wildfire, No
Size Yet** and **Prescribed Fire**. Turn off Prescribed Fire to see only
wildfires. Wildfire, No Size Yet holds starts that came in from a dispatch center
and were never updated: some centers enter every fire call this way, with a
dispatch number for a name, and never touch it again. Turn it off to see only the
fires someone has sized.

A fire with only a number for a name, such as LAC-357251, leaves the map an hour
after it was found unless someone has named it by then. Some dispatch centers file
every brush-fire call this way and never come back to it; LA County's filed most of
the 62 such fires listed one October evening. The row's status line says how many
were left out.

**Where** is **Everywhere** at first: every start in the country is loaded, so
**Find** reaches a fire wherever the map is. Slide it to load only the starts within
a distance of My Location or the map center. **Time window** picks the last hour, 3, 6, 12 or 24 hours. The
**Zoom gate** is off at first, so starts draw at every zoom. The layer refreshes
every 5 minutes, as often as NIFC's view does.

**Find** in its Features lists the starts by type, nearest first, from you or
from the map center.

## Ongoing Fires

Every wildfire and prescribed fire NIFC lists as not yet contained, controlled or
out: about 400 across the country in early October. It starts where New Fire
Starts ends, a day after a fire was found, so with both on each fire shows once:
today's starts in New Fire Starts, everything older here. Public, no login. **Add
ongoing fires** puts it in the list.

The markers, the three types and **Where** work as in New Fire Starts. Labels add
how much is contained: "DOME · 6,760 ac · 40%". A fire stays listed until someone
declares it contained, so a fire at 100% can still be on the map. NIFC drops a fire
under 10 acres after 3 days with no update, under 100 acres after 8, and a larger
one after 14.

Tap a fire on the map, from either layer, and its details open straight away, with
no radial menu.

**CAL FIRE** sits beside **Go there** in a fire's details when CAL FIRE runs or
posts the fire; it opens CAL FIRE's incident page, with the engines, crews and
dozers assigned. CAL FIRE's own acres, containment, location and update time are
the first lines of the details, and a fire CAL FIRE has closed says "final".

**InciWeb** sits beside **Go there** in a fire's details when the fire has an
InciWeb page, most often a Forest Service or other federal fire; it opens the page
in the phone's browser. The plugin learns which fires have a page from InciWeb's
own feed, read every 30 minutes.

## Fire History

Where fires have burned, from 1900 to today, from NIFC's interagency fire
perimeter history: about 140,000 burns. It is drawn the way the Enterprise
Geospatial Portal (EGP) draws it. Burns from the last ten years are colored by how
long ago they burned: purple under six months, blue six months to a year, green one
to two years, yellow two to three, orange three to ten. Earlier burns are one type
per decade: the 2010s rust, the 2000s dark brown, the 1990s tan, the 1980s teal, and
1979 and earlier grey. (EGP greys every decade; a 20-year-old burn scar still
matters, so here the decades back to 1980 keep a color.) **Add fire history** puts it in the list.

Each band and each decade is its own type in the Features list, each ON/OFF, so
the 1990s can be shown alone, or only the last three years. The arrow on its row
opens a map key with the colors. A burn over 20 acres is labeled with its name and
year, "Carr Fire (2018)", from 2 miles on the scale bar in (Label zoom changes it); tap one and its details open, with its acres, agency and
the rest. Where
several agencies mapped one fire, it shows once.

It loads what is in view, up to about 155 miles across, with the shapes simplified
to the zoom; zoom in and they load again in finer detail.

**Find** on the main screen searches all of Fire History by name, not only what is
in view. Add a year to pick one fire out of many with the same name: "Ranch 2007".
**Go** takes the map to it, close enough to read its name.

### My Fires

Pick out the burns that matter to the incident you are working, then show only
those:

1. Open a burn's details: tap it on the map, or find it with **Find**.
2. Tap **Add to My Fires**. The burn gets a white edge, and every other fire stays
   on the map so you can pick the next.
3. Add the rest the same way. **Remove from My Fires**, in a burn's details, takes
   one off.
4. Tap **Only My Fires OFF (3)**, under Add in the details or under the arrow on
   the row. It turns green, ON, and Fire History shows only your fires, wherever
   you pan and whichever decades are ticked. The row reads "Fire History: My Fires
   (3)". Tap it again to see every fire; your list is kept.

**Clear My Fires**, under the arrow on the row, empties the list when the incident
is over. The map key ends with a **My Fires** line for the white edge. The list is
kept when ATAK restarts.

## Your own organization

![Add your own org](screenshots/26_add_org.png)

**Add your own org…** asks for your organization's ArcGIS address once, the one
you sign in with. Any ArcGIS Online organization or ArcGIS Enterprise portal works.

![The organization's sign-in page](screenshots/27a_org_signin.png)

The organization's own sign-in page opens, with its own login choices.

![The picker shows the organization by name](screenshots/27_org_in_picker.png)

The picker then shows the organization by name, never by address.

![Sign in and Forget](screenshots/27b_org_pane.png)

**Forget** removes an organization you added by mistake. **Find layer** searches
the organization's own feature services by name, blank for all of them; pick one
and it loads with the service's own symbology.

## This guide, on the device

![Tool Preferences](screenshots/29_tool_preferences.png)

Settings, Tool Preferences, Feature Layer, **Plugin Documentation** opens this
guide as a PDF.
