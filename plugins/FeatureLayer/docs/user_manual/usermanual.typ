#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Feature Layer",
   plugin-version: "0.3",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

Feature Layer puts live ArcGIS feature layers on the ATAK map the way a GIS
analyst sees them: every point, line and area stays a feature you can tap for
its attributes, drawn with the symbols its community uses.

It comes with the National Incident Feature Service (NIFC), NAPSG's SARCOP
training sandbox and California's statewide air-intel perimeters built in, and
takes any ArcGIS Online organization you sign in to.


  Open it from the ATAK toolbar: the globe with the layer stack. Tapping it
  again closes the pane.

  Layers you add stay on the phone. They draw from the cache the moment ATAK
  starts, and refresh on their own when there is signal.
]

#tak-slide[
= Pick a source


  *Pick a source* lists where layers come from.

  *NIFC* is the live National Incident Feature Service. It needs your own NIFC
  ArcGIS login.

  *SARCOP Training* is NAPSG's public search-and-rescue sandbox.

  *CA Air Intel* is statewide California fire perimeters, public.

  *NIFS Archive (demo)* is last year's Dragon Bravo fire, public, for trying the
  symbology without a login.

  *Add your own org…* takes any ArcGIS Online organization.
]

#tak-slide[
= Signing in


  With NIFC picked, tap *Sign in*. The organization's own login page opens
  inside the pane, with multi-factor sign-in if the org uses it. The plugin
  never sees your password.


  A sign-in lasts two weeks and survives restarts and updates. The button then
  reads *Sign out* with your account name.

  Your organization's token is only ever sent to that organization's own
  servers.
]

#tak-slide[
= Finding a fire


  Type part of a fire name and tap *Find fire*, or leave the box blank for the
  latest fires. Pick one from the list and it loads: event points, event lines,
  the perimeter lines and polygons, IR points and polygons, accountable property
  and label points.

  SARCOP Training works the same way with *Find incident*. CA Air Intel needs no
  search: *Add perimeters*.
]

#tak-slide[
= Layers


  Each layer has a row: *ON/OFF*, *Features*, *Go to*, then *Auto* (how often
  it refreshes), *Refresh* and *Remove*. The line under the name says how many
  features it holds, when it last refreshed, and the refresh interval.

  *All ON / All OFF* beside the heading works every layer at once. The row says
  *Loading…* while a layer fetches.

  *Go to* frames the whole incident.
]

#tak-slide[
= On the map


  NIFC layers draw with the NWCG PMS 936 symbology: the standard point icons,
  the fire perimeter in red or black, dozer lines as chains of X's, hand lines
  with H's in the line, roads with R's, planned lines in magenta.


  The uncontained fire edge carries its ticks on the burned side, the way the
  standard draws it.


  Letters and X's are drawn on the ground, so they grow as you zoom in and stay
  part of the line when you tap it.
]

#tak-slide[
= Features


  *Features* lists what a layer holds, each with its own *ON/OFF*. Areas also
  have a fill button.

  At the top: *Zoom gate*, *Labels*, and for NIFC layers *Repair status halos*.


  The fill button cycles *Fill 25%*, *Fill 50%* and *Outline*. It changes the
  map at once, no refetch.


  *Repair status halos* is off by default, like NIFC's own incident map. On, a
  line's repair status shows as a colored band under it and a point's as a disc
  behind its symbol, in the standard's colors.
]

#tak-slide[
= Zoom gate


  A layer can hide itself when the map is zoomed out, so a statewide view is not
  covered in symbols.

  *Use this zoom* takes the scale the map is at right now as the limit. The
  button beside it names the current limit as a scale-bar reading and opens a
  picker: a quarter mile up to fifty, or *Always*.

  Zoomed out past the limit nothing in the layer draws; zoom in and it all
  comes back.
]

#tak-slide[
= Search


  *Feature* on the main pane, or *Find* inside a layer's Features, opens a
  search inside that layer. With nothing typed, *Find* lists every kind of
  thing the layer holds with a count. Tap one to list its features.


  Type a name, a number or a type for a free search across names and every
  attribute. Each row shows what it is, when it was collected, and how far away,
  in your ATAK units, with *tap to go there*.

  *From: Me* or *From: Map center* says where distances are measured from.


  Sort by *Nearest*, *Newest*, *Oldest* or *Name*. *Clear* empties the box and
  brings the list of types back. The pane remembers your sort and measuring
  point.
]

#tak-slide[
= Tap a feature


  Tap on the map and ATAK lists what is under your finger, each with its
  symbol. Where lines overlap you pick the one you meant.


  The radial menu offers *Details*, *Bloodhound*, a *range and bearing* line,
  *polar coordinates* and *Marker here*. Bloodhound plants a marker to follow
  and removes it when the bloodhound stops; Marker here leaves one.


  *Details* shows every attribute the service carries, with *Back* pinned at
  the top.
]

#tak-slide[
= SARCOP


  SARCOP layers draw with NAPSG's own symbology: tracklogs in their mission-type
  colors, search segments and divisions outlined by status, the incident area as
  a dashed blue outline, waypoints and worksites with the US&R symbols.


  Its Features list has one row per SARCOP layer: Waypoints, Worksites,
  Logistics Points, Tracklog, Search Segments, Divisions, Branches and Incident
  Area.
]

#tak-slide[
= CA Air Intel


  One layer, statewide, refreshed every minute. Its Features list has a row per
  source with a line saying what it is: CAL FIRE intel flights, FIRIS, USFS,
  NIFC, WFIGS and EGP. Each toggles on its own.


  *Time window* picks how far back to look, six hours to thirty days or all.
  Only the latest perimeter per fire per source is drawn, so widening the window
  adds fires, not copies.


  Active perimeters draw in orange with a dark red edge, named at the center.
  Inactive ones are not fetched.
]

#tak-slide[
= Your own organization


  *Add your own org…* asks for your organization's ArcGIS address once, the one
  you sign in with. Any ArcGIS Online organization works.


  The picker then shows the organization by name. *Forget* removes one you
  added by mistake.


  *Find layer* searches the organization's content. Each result names its
  owner and the server it lives on; a layer from elsewhere is read without your
  organization's sign-in.
]

#tak-slide[
= This guide, on the device


  Settings, Tool Preferences, Feature Layer, *Plugin Documentation* opens this
  guide as a PDF.

= What it needs

An Android device running ATAK-CIV 5.6, 5.7 or 5.8. A network connection to
load or refresh layers; cached layers draw without one. A NIFC ArcGIS login for
NIFC incidents; the other built-in sources are public.
]
