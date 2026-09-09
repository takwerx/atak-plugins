#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Evac Zone",
   plugin-version: "0.3",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

Evac Zone puts evacuation zones on the ATAK map, from the agencies that publish
them. Every zone is drawn in one color language whatever its agency calls the
status, so an Order in one county looks like an Order in the next, and each zone
carries its name.

You pick a state and turn it on. Narrow to the counties you work if you want.
Tap a zone for everything the agency publishes about it, or find it by name in
the list and go to it.

Nothing here needs an account or a key. The zones are public; Evac Zone finds
them, keeps them current, and says where they came from.

#v(6pt)
#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("1.jpg", width: 100%)
][
  Open it from the ATAK toolbar. The ZONE badge sits with the other tools;
  tapping it again closes the pane, so it is one tap on and one tap off when
  you want the map to yourself.
]
]

#tak-slide[
= The pane

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("2.png", width: 100%)

  #v(6pt)
  #image("4.png", width: 100%)
][
  The *state* button names the state that is up. *Refresh* fetches now.

  *ON / OFF* is the map's switch. OFF takes every zone off the map and
  remembers what you had on; ON puts it back. While it is off the pane says so,
  in words.

  Under the buttons, the counts of what is on the map: Order, Warning,
  Advisory, Lifted, in the colors the map uses. They follow whatever you narrow
  to below.
]

#v(6pt)
Everything below scrolls: the state's feed, the counties, the visibility
tools, and the zone list. The top row stays put.
]

#tak-slide[
== Pick a state

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("3.png", width: 100%)
][
  Just the states. Pick one and the pane shows that state's feed and its
  counties.

  Built and tested in California. Oregon and British Columbia carry their
  agencies' live orders and alerts. Florida carries hurricane zones for two
  counties, lettered A to E, with no live status; the county calls zones by
  letter.

  A state that is not here is one whose data has not been found yet. Point us
  at it and it goes in.
]
]

#tak-slide[
= The state's feed

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("5.png", width: 100%)
][
  One row per state. The row says who publishes it and how often it refreshes.

  *ON* loads it and draws it. From then on the row shows how many zones it
  holds and how long ago it refreshed. *Refresh* fetches now; a live feed also
  refreshes on its own.
]

#v(6pt)
California is Cal OES's orders and warnings, refreshed every five minutes, with
CAL FIRE's reason and population, its lifted zones, and the county feeds folded
in, so one zone is drawn once. Oregon is OEM's levels: 1 Be Ready, 2 Be Set,
3 Go Now. British Columbia is its orders and alerts.
]

#tak-slide[
= Counties

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("6.png", width: 100%)

  *All counties* is the whole state. Tap it to narrow.
][
  #image("7.png", width: 100%)

  Every county of the state is listed. The number beside a county is how many
  zones the feed has there right now; no number means nothing is happening
  there.
][
  #image("8.png", width: 100%)

  Tick one or many and press OK. Tick *All counties* to clear it.
]
]

#tak-slide[
== Narrowed to a county

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("9.png", width: 100%)
][
  The button names what is picked. The map, the counts and the zone list all
  narrow to those counties, so the pane and the map say the same thing.

  Every start is *All counties*. A pick lasts for the session and does not
  follow you into the next one.
]
]

#tak-slide[
= Reading the map

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("10.jpg", width: 100%)
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
  #image("11.jpg", width: 100%)
][
  The zone's id is drawn at its center, the short way the agencies say it:
  FHL-G026, not the full US-CA-XMY-FHL-G026. The full id is in the details.

  These colors are the ones Genasys Protect uses on the public site, so a zone
  looks the same to you and to a resident reading their phone.

  The zone is one thing on the map: tap the name or the area and you get the
  zone.
]
]

#tak-slide[
= A zone's details

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("12.png", width: 100%)
][
  Tap a zone on the map, or *Details* on a row in the zone list. You get the
  status and the id, the state and the agency it came from, and every attribute
  the agency publishes: county, reason, population where they give it, when
  they last edited it.

  *Go there* frames the zone on the map and keeps the details open.

  *Back* closes them. From the list it puts the pane back where you were.
]
]

#tak-slide[
= Distance from a point

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("13.png", width: 100%)
][
  Limit what is drawn to a radius around the map center, or around you. The
  slider is in your own ATAK units, and the label reads the distance and the
  point it is measured from.

  *Use this extent* takes what you are looking at as the radius.

  All the way left is off: the whole state.
]

#v(6pt)
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("14.png", width: 100%)
][
  *Preset* opens with the current one ticked.

  Which point it measures from is the *Nearest to* choice down by the zone
  list: the map center follows the map as you pan; My Location travels with
  you.
]
]

#tak-slide[
= Draw on the map

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("15.png", width: 100%)
][
  Hold the zones until you are zoomed in past a threshold. The readout quotes
  ATAK's own scale bar, so the pane and the map agree, and it says *hidden* when
  the map is outside it.

  *Use this zoom* takes the scale you are looking at as the threshold. No number
  to interpret: you set it by example.
]

#v(6pt)
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("16.png", width: 100%)
][
  *Presets* are named for what they are for, from a neighborhood to a region,
  with *Always draw them* at the end.

  Going to a zone knows about the threshold: if framing it would land outside,
  the map zooms in past it, so a tap never takes you somewhere and shows you
  nothing.
]
]

#tak-slide[
= The zone list

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("17.png", width: 100%)
][
  Every zone on the map for this state. Type part of an id, a name or a county
  to narrow it; *Clear* empties it. The keyboard covers the rows while you
  type; press the phone's Back key once and they show.

  *Status* narrows the list to one status. *Nearest to* is the point the
  distances are measured from. *On screen only* keeps the list to the current
  map view.
]
]

#tak-slide[
== Reading a row

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("18.png", width: 100%)
][
  Rows are nearest first, with the distance on each: the zone's id, then its
  status and county in the status color.

  Tap the row and the map goes there. *Details* opens the zone's details.

  The list shows what is on the map, and only that. If the counties or the
  radius trimmed a zone away, it is not in the list either.
]
]

#tak-slide[
== Status, and which point

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("19.png", width: 100%)

  *Status* offers each status with its count, so the filter says what it costs
  before you use it.
][
  #image("20.png", width: 100%)

  *Nearest to* is the map center or you, and the chosen one is green. *On
  screen only* follows the map as you pan.
]
]

#tak-slide[
= When something is off

*"Map is off. Press ON to draw the zones."* The big switch is off. Press ON.

*"Zoom in to see the zones."* You are zoomed out past your threshold. Zoom in,
or set the threshold to *Always draw them*.

*A row reading STALE* kept its last zones after a refresh that failed, and says
why. Nothing is taken off the map because a server did not answer; the next
refresh tries again.

*A feed that shows 0 zones* between incidents is telling the truth. Most feeds
publish nothing until something is happening.

*A quiet map with counts in the pane* is usually a county pick or a radius left
on from earlier. Both say so on their buttons; the counties reset every start.
]

#tak-slide[
= Where the zones come from

The catalog lists public ArcGIS services: state emergency management (Cal OES,
Oregon OEM, EmergencyInfoBC), CAL FIRE, and the counties and cities that publish
their own zone maps. It is read from takwerx's server when the plugin starts, so
a feed can be added or corrected without a new plugin, and the copy built into
the plugin is used when there is no connection.

One state, one feed. Where several agencies publish the same zones, the state
agency is the authority for status and the others add what they know: the
reason, the population, a zone the state has not drawn. A zone appears once.

Every feed was checked before it went in: that it answers, that it names the
fields it claims, that it is small enough for a phone, and that a feed calling
itself live has actually been edited this year. A county whose own layer went
stale is dropped, and its live status comes through the state.

#v(6pt)
*What is not shown:* zones that no agency publishes; anything hidden by the zoom
threshold, or trimmed by the radius or the county pick, which the pane says in
words.
]

#tak-slide[
= What it needs

- ATAK-CIV 5.6, 5.7 or 5.8; a build for each is published.
- Internet access to fetch zones. HTTPS only, to takwerx's catalog and to the
  public ArcGIS services the catalog lists. A state that has been fetched keeps
  drawing without a connection and says how old it is.
- Nothing is sent from the phone: no location, no callsign, no identifiers.
  Your position and the map center are used only to measure distances.
- A few megabytes of storage per state that is on.

#v(6pt)
// The Tool Preferences shot (shot list #20) goes beside this once a build
// carries the manual; 0.3 has no preferences entry to photograph.
*This guide, on the device.* Settings → Tool Preferences → Evac Zone →
*User manual* opens this guide as a PDF on the phone. It is the copy that
matches the plugin you have installed.

#v(6pt)
Problems, and states to add: https://github.com/takwerx/evac-zone/issues
]
