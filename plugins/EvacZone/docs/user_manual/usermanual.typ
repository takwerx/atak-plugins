#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Evac Zone",
   plugin-version: "0.2",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

Evac Zone puts evacuation zones on the ATAK map, from the agencies that publish
them: state emergency management, CAL FIRE, and the counties and cities that run
their own zone maps. Every zone is drawn in one color language whatever its
agency calls the status, so an Order in one county looks like an Order in the
next.

You pick a state, then the counties you work, and turn on the feeds you want.
Live feeds refresh on their own. Tap a zone for everything the agency publishes
about it, or find it by name in the list and go to it.

Nothing here needs an account or a key. The zones are public; Evac Zone finds
them, keeps them current, and says where they came from.

#v(6pt)
#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("1.png", width: 100%)
][
  Open it from the ATAK toolbar. The badge sits with the other tools; tapping it
  again closes the pane, so it is one tap on and one tap off when you want the
  map to yourself.

  The first thing it does is read the catalog, the list of every feed it knows.
  The line at the top of the pane says which catalog it has and when it was
  built.
]
]

#tak-slide[
= The pane

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("2.png", width: 100%)
][
  *State* picks the state or province. *Refresh* fetches every feed that is on,
  now.

  The status line counts what is on and drawn, and names the catalog.

  The legend totals the zones that are on, in their colors: Order, Warning,
  Advisory, Shelter in Place, Lifted. It is the same vocabulary the map uses.
]

#v(6pt)
Everything below scrolls: the statewide feeds, the counties, the visibility
tools, and the zone list. The State button stays put.
]

#tak-slide[
== Pick a state

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("3.png", width: 100%)
][
  Each entry says how many feeds it has and how many are on, before you pick
  it. Nothing in this pane makes you guess what a choice will do.

  The pane opens on the state under the map, or the one you used last.
]
]

#tak-slide[
= Statewide feeds

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("4.png", width: 100%)
][
  A feed that covers the whole state is always listed. The row says who
  publishes it, how often it refreshes, and how many zones it holds.

  *ON* loads it and draws it. From then on the row shows the count, how long ago
  it refreshed, and anything that is not being shown.

  *Go to* frames everything the feed fetched. *Refresh* fetches now.
]

#v(6pt)
For California, Cal OES carries every county's orders and warnings and refreshes
every five minutes. CAL FIRE's combined layer adds the reason, the population, and
zones that were lifted. Oregon OEM carries the levels: 1 Be Ready, 2 Be Set,
3 Go Now. British Columbia carries its orders and alerts.
]

#tak-slide[
= Counties

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("5.png", width: 100%)

  Every county of the state, from the Census, whether or not anyone publishes a
  layer for it. Beside each: zones in the feeds that are on, and feeds of its own.
][
  #image("6.png", width: 100%)

  Tick one or many. The button says which are picked.
][
  #image("7.png", width: 100%)

  Under it, each county's share of every statewide feed, with the counts, then
  the county's own feeds if it has any.
]

#v(6pt)
The county under the map is picked for you the first time you open a state.
A county with a feed that is on is always listed, so nothing drawn on the map
can be missing from the pane.
]

#tak-slide[
= Reading the map

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("8.png", width: 100%)
][
  One color language, whatever the county calls it:

  #text(fill: rgb("#D9534F"))[*Red*] Evacuation Order, Level 3 Go Now, Mandatory

  #text(fill: rgb("#E5C447"))[*Yellow*] Evacuation Warning, Level 2 Be Set, Voluntary, Alert

  #text(fill: rgb("#6A95CB"))[*Blue*] Advisory, Level 1 Be Ready

  #text(fill: rgb("#BF6ADC"))[*Purple*] Shelter in Place

  #text(fill: rgb("#90D260"))[*Green*] Lifted, Cancelled, All Clear, Repopulation

  *Faint* Normal, no evacuation. *White* a status the plugin does not know.
]
]

#tak-slide[
== Every zone carries its name

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("9.png", width: 100%)
][
  The zone's id is drawn at its center, the short way the agencies say it:
  RVC-1912, not the full US-CA-XRI-RVC-1912. The full id is in the details.

  These colors are the ones Genasys Protect uses on the public site, so a zone
  looks the same to you and to a resident reading their phone.
]
]

#tak-slide[
= A zone's details

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("10.png", width: 100%)
][
  Tap a zone on the map, or a row in the zone list. The details show the status
  and the id, which feed it came from, and every attribute the agency publishes:
  county, reason, population where they give it, when they last edited it.

  Where a feed carries the zone's public Genasys Protect page, its address is
  among the attributes.

  *Back* closes the details. From the list it puts the pane back where you were.
]
]

#tak-slide[
= Distance from a point

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("11.png", width: 100%)
][
  Limit what is drawn to a radius around you, or around the map center. The
  slider is in your own ATAK units.

  *Measuring from* says which point it is, and a tap switches it. Map Center
  follows the map as you pan.

  *Use this extent* takes what you are looking at as the radius.
]

#v(6pt)
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("12.png", width: 100%)
][
  *Presets* opens with the current one ticked. Off is the whole state.

  Every feed row says how many of its zones are inside the radius, so a quiet
  map is never a mystery.
]
]

#tak-slide[
= Draw on the map

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("13.png", width: 100%)
][
  Hold the zones until you are zoomed in past a threshold. The readout quotes
  ATAK's own scale bar, so the pane and the map agree, and it says *hidden* when
  the map is outside it.

  *Use this zoom* takes the scale you are looking at as the threshold. No number
  to interpret: you set it by example.
]

#v(6pt)
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("14.png", width: 100%)
][
  *Presets* are named for what they are for, from a city block to a region,
  with *Always draw them* at the end.

  *Go to* knows about the threshold: if framing a zone would land outside it,
  it zooms in past it, so Go to never takes you somewhere and shows you nothing.
]
]

#tak-slide[
= The zone list

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("15.png", width: 100%)
][
  Every zone that is on the map in this state. Type part of a zone id, a name or
  a county to narrow it; *Clear* is live while a search is in force.

  Zones are listed nearest first from the same point the radius measures from,
  with the distance on each row.

  Tap a row for the details. *Go to* frames the zone.
]

#v(6pt)
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("16.png", width: 100%)
][
  *Status* narrows the list to one status, and each entry carries its count, so
  the filter says what it costs before you use it.

  The list shows what is on the map, and only that. If the radius trimmed a
  zone away, it is not in the list either.
]
]

#tak-slide[
= When something is off

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("17.png", width: 100%)
][
  A feed that could not refresh keeps its last zones and says *STALE* with the
  reason. Nothing is taken off the map because a server did not answer. The next
  refresh, on the feed's own interval, tries again.
]

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("18.png", width: 100%)
][
  A live feed with 0 features between incidents is telling the truth. Los
  Angeles City and Ventura publish nothing until something is happening.
]

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("19.png", width: 100%)
][
  Some county feeds are zone boundaries only: every zone the county has drawn,
  most of them Normal, drawn faint. Their row says so, and says that live status
  is in the statewide feeds. Turn both on and the live zone sits over the
  boundaries.
]
]

#tak-slide[
= Where the zones come from

The catalog lists public ArcGIS services: state aggregations (Cal OES, CAL FIRE,
Oregon OEM, EmergencyInfoBC) and the counties and cities that publish their own
zone maps. It is read from takwerx's server when the plugin starts, so a feed can
be added or corrected without a new plugin, and the copy built into the plugin is
used when there is no connection. "Built-in catalog" in the status line means
that is what happened.

Every feed in the catalog was checked before it went in: that it answers, that
it names the fields it claims, that it is small enough for a phone, and that a
feed calling itself live has actually been edited this year. A county whose own
layer went stale is dropped, and its live status comes through the state.

Most counties in California and Oregon run their zones on Genasys Protect. Their
live status reaches the map through the state aggregations, which carry every
Genasys county. A county's full set of zones, the Normal ones included, is on
the map only where the county publishes it.

#v(6pt)
*What is not shown:* zones that no agency publishes; a Normal zone in a county
without a boundary feed; anything hidden by the zoom threshold or trimmed by the
radius, which the row and the status line say in words.
]

#tak-slide[
= This guide, on the device

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("20.png", width: 100%)
][
  Settings → Tool Preferences → Evac Zone → *User manual* opens this guide as a
  PDF on the phone. It is the copy that matches the plugin you have installed.
]
]

#tak-slide[
= What it needs

- ATAK-CIV 5.6, 5.7 or 5.8; a build for each is published.
- Internet access for the feeds that are on. HTTPS only, to takwerx's catalog
  and to the public ArcGIS services the catalog lists. A feed that has been
  fetched keeps drawing without a connection and says how old it is.
- Nothing is sent from the phone: no location, no callsign, no identifiers.
  Your position and the map center are used only to measure the radius and to
  pick a county for you.
- A few megabytes of storage per feed that is on.

#v(6pt)
Problems and requests: https://github.com/takwerx/evac-zone/issues
]
