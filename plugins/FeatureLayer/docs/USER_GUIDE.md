# Feature Layer — User Guide

**Download Feature Layer 0.5** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/feature-layer/releases/download/v0.5/ATAK-Plugin-FeatureLayer-0.5--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/feature-layer/releases/download/v0.5/ATAK-Plugin-FeatureLayer-0.5--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/feature-layer/releases/download/v0.5/ATAK-Plugin-FeatureLayer-0.5--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/feature-layer/releases

## Before you start

Builds are published for ATAK-CIV 5.6, 5.7 and 5.8. Install the one that matches
your ATAK version, then enable it in ATAK's Plugins manager. NIFC incidents need
your own NIFC ArcGIS login; the other built-in sources are public.

Feature Layer puts live ArcGIS feature layers on the ATAK map the way a GIS
analyst sees them: every point, line and area stays a feature you can tap for its
attributes, drawn with the symbols its community uses. Layers you add stay on the
phone, draw from the cache the moment ATAK starts, and refresh on their own when
there is signal.

## Opening it

![The Feature Layer icon in the toolbar](screenshots/1_toolbar.png)

Open it from the ATAK toolbar: the globe with the layer stack. Tapping it again
closes the pane.

## Pick a source

![Pick a source](screenshots/2_pick_a_source.png)

- **NIFC** is the live National Incident Feature Service. It needs your own NIFC
  ArcGIS login.
- **SARCOP Training** is NAPSG's public search-and-rescue sandbox.
- **CA Air Intel** is statewide California fire perimeters, public.
- **NIFS Archive (demo)** is last year's Dragon Bravo fire, public, for trying the
  symbology without a login.
- **Add your own org…** takes any ArcGIS Online organization or ArcGIS Enterprise
  portal.

## Signing in

![The main pane with NIFC picked](screenshots/3_main_pane_signed_in.png)

With NIFC picked, tap **Sign in**. The organization's own login page opens inside
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

SARCOP Training works the same way with **Find incident**. CA Air Intel needs no
search: **Add perimeters**.

## Layers

![Two layer rows](screenshots/7_layer_rows.png)

Each layer has a row: **ON/OFF**, **Features**, **Go to**, then **Auto** (how often
it refreshes), **Refresh** and **Remove**. The line under the name says how many
features it holds, when it last refreshed, and the refresh interval.

**All ON / All OFF** beside the heading works every layer at once. The row says
**Loading…** while a layer fetches. **Go to** frames the whole incident.

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

**Feature** on the main pane, or **Find** inside a layer's Features, opens a search
inside that layer. With nothing typed, **Find** lists every kind of thing the
layer holds with a count. Tap one to list its features.

![Search results](screenshots/16_search_results.png)

Type a name, a number or a type for a free search across names and every
attribute. Each row shows what it is, when it was collected, and how far away, in
your ATAK units, with **tap to go there**. **From: Me** or **From: Map center**
says where distances are measured from.

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
