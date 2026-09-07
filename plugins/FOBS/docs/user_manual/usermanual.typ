#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "FOBS",
   plugin-version: "0.4",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

FOBS, Field Observation Survey, captures a perimeter or an area and turns it
into an ordinary ATAK drawing shape with area and perimeter. Walk it with the
phone in hand, trace it on the map, pick up a shape or a KML already there, or
pull it out of ATAK's Track History. Split what came out wrong, join the pieces
into one line, close the line into an area.

Everything it makes is a normal ATAK drawing: it shows in the details panel,
shares over the mesh, edits with ATAK's vertex editor and deletes like any other
shape. Nothing has to be created first. Tap the icon and pick a tile.

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("1.jpg", width: 100%)
  #v(4pt)
  The boot print on ATAK's toolbar opens FOBS.
][
  Optionally, a walked track goes live to a Data Sync feed on a connected TAK
  Server, and grows on every subscriber's map as it is walked.
]
]

#tak-slide[
= The pane

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("2.jpg", width: 100%)
][
  Six tiles in a pane beside the map. The map stays live, so you can zoom to
  where you are working before you start.

  *Start a GPS track*, *Draw a track*, *Select element*, *Import a track*,
  *Split track*, *Join tracks*. Each one is described on its own page.

  Tap the boot print again to close the pane.
]
]

#tak-slide[
== Default Style

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("2b.jpg", width: 100%)
][
  Below the tiles, the style every new track starts with: *Color*, *Line*
  (solid, dashed, dotted) and *Thickness* (thin, medium, thick).

  Color opens ATAK's own palette. A track keeps the style it was made with; a
  change here applies to the next one.
]
#v(4pt)
#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("3.jpg", width: 100%)
][
  #image("4.jpg", width: 100%)
][
  #image("4b.jpg", width: 100%)
]
]

#tak-slide[
= Start a GPS track

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("5.jpg", width: 100%)
  #v(4pt)
  Name it. The default is your callsign and the next track number.
][
  #image("6.jpg", width: 100%)
  #v(4pt)
  With a TAK Server connected, FOBS asks whether to send the track live to a
  Data Sync feed. *Just local* keeps it on the device. See _Live to a feed_.
]
#v(6pt)
Then walk. Every fix that passes the filter is added to the line: reported
accuracy better than 25 m, no impossible jumps, at least 2 m from the last one.
Standing still adds nothing.
]

#tak-slide[
== Recording

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("7.jpg", width: 100%)
][
  The bar shows the feed the track is going to, then *Pause* and *End*. If the
  filter has refused fixes for poor accuracy or an impossible jump, a count of
  them appears there too. Standing still is not counted.

  #image("7b.jpg", width: 100%)

  The line grows behind you. It is saved every fifteen seconds and re-sent to
  the feed on every save.
]
]

#tak-slide[
== Pause, and End

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("8.jpg", width: 100%)
  #v(4pt)
  *Pause* when you leave the line to look at something. Nothing is recorded
  until *Resume*, and the gap is drawn straight.
][
  #image("9.jpg", width: 100%)
  #v(4pt)
  *End* shows the count and length and asks: *Make polygon*, *Single line*, or
  *Cancel* to keep recording.
]
]

#tak-slide[
== The result

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("10.jpg", width: 100%)
][
  *Make polygon* closes the line back to its start and fills it. Area and
  perimeter are shown in ATAK's own units, the ones set in ATAK's preferences,
  and again any time in the shape's details.

  *Single line* keeps it open, to be joined to other legs later.

  A polygon is the same map item as the line it came from, so a subscriber
  watching the feed sees the line become an area.
]
]

#tak-slide[
= Draw a track

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("11.jpg", width: 100%)
  #v(4pt)
  Two ways to draw: *Drop points* or *Freehand*.
][
  #image("12.jpg", width: 100%)
  #v(4pt)
  *Drop points*: tap along a road, a ridge, the edge of a field. *Undo* takes
  the last point back. The first point wears a green ring; tapping it closes
  the line into an area on the spot.
]
]

#tak-slide[
== Drop points, then End

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("12b.jpg", width: 100%)
  #v(4pt)
  *End* asks the same question a walk does: line or polygon.
][
  #image("12c.jpg", width: 100%)
  #v(4pt)
  Fifteen points around a field, made into an area.
]
]

#tak-slide[
== Freehand

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("13.jpg", width: 100%)
][
  *Freehand* opens ATAK's own telestration tool in your color. Draw the stroke
  with a finger; the map does not move while the tool is active.

  Tap *End* on the telestration bar and the stroke becomes a FOBS track, with
  the same line-or-polygon question.
]
]

#tak-slide[
= Select element

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("14.jpg", width: 100%)
][
  Turns a shape already on the map into a FOBS track: a line or polygon from
  an imported KML, a freeform line or polygon, a route, a rectangle, a circle,
  a telestration.

  Tap the tile, then tap the shape. Name it. A closed shape becomes an area
  at once; an open line asks line or polygon.
]
]

#tak-slide[
== Before and after

#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("14c.jpg", width: 100%)
  #v(4pt)
  A KML boundary on the map, in blue.
][
  #image("14d.jpg", width: 100%)
  #v(4pt)
  The same boundary as a FOBS area, in the default style. The KML is untouched
  underneath; both show when you tap the spot.
]
]

#tak-slide[
= Import a track

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("16.jpg", width: 100%)
][
  Import opens ATAK's own *Track History*, which knows every track this device
  has logged and, with a server connected, every user's tracks on the server.

  It opens on the current active track. The list icon at the top shows all of
  them; the search icon asks the server.
]
]

#tak-slide[
== Find the track

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("16c.jpg", width: 100%)
  #v(4pt)
  The track list. Tick *View* on a track and it is drawn on the map. *Hide
  Temp* hides short tracks; untick it, or tick *Show All*, to see a short one.
][
  #image("16e.jpg", width: 100%)
  #v(4pt)
  A track ticked and drawn. Close Track History with the X once it is showing.
]
]

#tak-slide[
== Bring it in

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("17.jpg", width: 100%)
  #v(4pt)
  Tap the track's line on the map. Its radial menu has a boot print: that is
  the import. Name it; line or polygon.
][
  #image("17d.jpg", width: 100%)
  #v(4pt)
  The imported FOBS track in red, with the Track History line turned off. The
  fixes were cleaned to the same standard as a live walk.
]
]

#tak-slide[
= Split track

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("18.jpg", width: 100%)
][
  Split takes any line or closed shape on the map, not only FOBS tracks.

  *Long press* the line exactly where you want it broken, holding still for
  half a second. A tiny gap comes out where you pressed.

  The scissors on any line's radial menu start Split locked to that line.
]
]

#tak-slide[
== What a split leaves

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("19.jpg", width: 100%)
][
  A *line* becomes two lines. They show *orange* and *blue*, with rings at the
  gap and a pane beside the map: *Delete orange*, *Delete blue*, *Keep both*,
  or *Undo split*, which puts the original line back.

  Use it to cut a drive off the front of a walked track, or to drop the leg
  where the GPS wandered.
]
]

#tak-slide[
== Then the editor

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("19b.jpg", width: 100%)
  #v(4pt)
  The result opens in ATAK's vertex editor, so the point that was wrong can be
  moved right away. The map stays where you were when you pressed.
][
  #image("20.jpg", width: 100%)
  #v(4pt)
  A *closed shape* opens into one line, starting and ending at the gap. The
  polygon is gone; the line can be split again or joined.
]
]

#tak-slide[
= Join tracks

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("22.jpg", width: 100%)
  #v(4pt)
  Two ways to join: *Tap ends* or *Lasso*. Both take any open line on the map.
][
  #image("23.jpg", width: 100%)
  #v(4pt)
  *Tap ends*: every open line grows a marker at each end. Tap an end on one
  line; it turns green. Then tap an end on another.
]
]

#tak-slide[
== Joined, and closed

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("24.jpg", width: 100%)
  #v(4pt)
  Those two ends are connected and the two lines become one, in the first
  line's name and style. *Undo* puts the last two back.
][
  #image("25.jpg", width: 100%)
  #v(4pt)
  Tap both ends of the same line and it closes into an area.
]
]

#tak-slide[
== Lasso

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("26b.jpg", width: 100%)
  #v(4pt)
  *Lasso* opens ATAK's lasso. Drag a loop around the lines to join.
][
  #image("26c.jpg", width: 100%)
  #v(4pt)
  ATAK asks what to take from inside the loop. *Shapes* is the one you want.
]
]

#tak-slide[
== Chained into an area

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("28.jpg", width: 100%)
  #v(4pt)
  The lines are chained nearest end to nearest end, starting from the longest,
  and closed. If the largest gap that would be bridged is long, it asks first.
][
  #image("27.jpg", width: 100%)
  #v(4pt)
  Three legs, walked by three people, become one area.
]
]

#tak-slide[
= Live to a feed

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("6b.jpg", width: 100%)
  #v(4pt)
  *Choose feed* lists the server's Data Sync feeds. After the first use a third
  button offers the last feed by name. Password-protected feeds are listed but
  not supported in this version.
][
  #image("7c.jpg", width: 100%)
  #v(4pt)
  The first save puts the track into the feed; every save after that updates
  it. Make polygon at End and the same feed item becomes the area.
]
]

#tak-slide[
== What a subscriber sees

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("30.jpg", width: 100%)
  #v(4pt)
  On another device subscribed to the feed, the line grows as it is walked.
  The Data Sync badge counts the saves.
][
  #image("30b.jpg", width: 100%)
  #v(4pt)
  Make polygon, and the area arrives.
]
#v(4pt)
Split, join and lasso keep the feed membership on their result and take the
pieces they consumed out of the feed. Deleting a track with the radial menu
does not touch the feed. If Data Sync is also on the recording device it may ask
once whether to publish the change; the answer is a Data Sync setting.
]

#tak-slide[
= This guide, on the device

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("31.jpg", width: 100%)

  #v(4pt)
  ATAK's *Settings* #sym.arrow.r *Tool Preferences* lists FOBS with the other
  tools.
][
  #image("31b.jpg", width: 100%)

  #v(4pt)
  *Plugin Documentation* opens this guide, so it is on the device with you
  and needs no network.
]
]

#tak-slide[
= What it needs

- *GPS* for a live track. The filter drops fixes reported worse than 25 m,
  faster than 45 m/s, or under 2 m from the last. It never smooths: the line is
  the walk you took, to the accuracy of the phone.

- *Nothing else* without a server. No account, no key, no network; it works
  in airplane mode.

- *A TAK Server*, only when you choose a feed. It uses the connections ATAK
  already holds to that server: the streaming CoT connection to send the
  track, and the REST API to list feeds, subscribe, and add or remove the
  track. Importing from the server's track history goes through ATAK's own
  Track History.

- *Units* follow ATAK's preferences. Distances, areas and perimeters are shown
  the way the rest of ATAK shows them.
]
