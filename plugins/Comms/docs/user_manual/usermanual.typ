
#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Comms",
   plugin-version: "0.4",
   platform: "ATAK",
   platform-version: "5.8.0",
)


#tak-slide[
  = Overview
#toolbox.side-by-side(columns: (.75fr, 9fr))[
#image("plugin_icon.png", width: 70%)
][
 Comms puts mountaintop radio sites on the ATAK map, nearest first, with the nets
 that live on each one and the tone that opens each repeater. It answers "what is
 the closest Command 5 repeater, what mountain is it on and what tone does it use"
 from a side pane, and works out which of those sites a handheld where you are
 standing is likely to reach.
]

= Finding a site or a net
The pane opens from the Comms toolbar button. Type in the search box: a site
name ("Santiago"), a net name or designator ("Command 5", "CDF C5", "cdf 5"), a
tone ("103.5" or "tone 8") or a callsign. The list shows the
nearest sites first. When the search names a net, each row shows that net's line
at that site, so the nearest one is readable without opening anything. A net with
no fixed site, such as an incident portable repeater, is shown as its own card.

*Where* picks the state, whether distances are measured from you or from the map
center, and a radius. *Show* is one checkbox per agency, with the number of sites
it would show. *Draw on the map* sets the zoom at which sites are drawn, quoted
as what the scale bar reads.

= Site details
Tap a row, or Details on a site's radial menu, for county, elevation, distance
and bearing, and every net on the site with the tone that opens that repeater,
as Hz and as the California tone number, plus callsign and notes.

= Which repeaters can I reach from here
*From me ON* works out, for every site within *Range*, whether a handheld where
you are standing is likely to open it. It walks the terrain between you and the
antenna over the elevation data on the device (Map Depot's DTED packs provide
it), scores the worst ridge in the way for the loss a signal takes bending over
it, and weighs that against what a handheld into a mountaintop repeater can
afford. *Height* says how high your antenna is, from handheld to tower.

Sites come back *likely*, *marginal* or *unlikely*. A likely site lights its
symbol green on the map and heads the list; the rest follow under their own
headings. *Only likely* puts everything else away, map and list together, when
you just want the ones worth trying. With a search running, the top row is the
nearest matching site you are likely to reach. It follows you as you move, and
the status line counts the sites off while it works.

#block(inset: (left: 6pt), stroke: (left: 2pt + gray))[
*This is a guide, not a prediction.* It is bare earth: it knows nothing about
trees, buildings, vehicles or weather, it assumes one ridge rather than a range
of them, and it assumes a handheld rather than the radio in your hand. A site it
calls unlikely is still worth a try, and a site it calls likely can still fail.
Confirm on the radio.
]

Nothing here draws a coverage picture on the map, and that is deliberate. Drawn
from a repeater, a viewshed reads as coverage and is not one. Drawn from you, the
one ATAK provides changed its answer about the same five-mile mountain depending
on how far out you had asked it to reach, because it samples on a fixed grid
however large an area it covers. The answer above is worked out per site and does
not move with a display setting.

= The catalog
Sites and nets come from a catalog refreshed from the depot host once a day and
on *Sync catalog*. The status line says which copy is shown and how old it is.
The plugin works offline from its built-in copy.
]
