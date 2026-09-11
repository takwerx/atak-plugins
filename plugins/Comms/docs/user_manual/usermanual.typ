
#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Comms",
   plugin-version: "0.1",
   platform: "ATAK",
   platform-version: "5.8.0",
)


#tak-slide[
  = Overview
#toolbox.side-by-side(columns: (.75fr, 9fr))[
#image("plugin_icon.png", width: 70%)
][
 Comms puts mountaintop radio sites on the ATAK map, nearest first, with the nets
 that live on each one: name, frequencies, tone and callsign. It answers "what is
 the closest Command 5 repeater, what mountain is it on and what tone does it use"
 from a side pane, and draws ATAK's own viewshed from where you are standing.
]

= Finding a site or a net
The pane opens from the Comms toolbar button. Type in the search box: a site
name ("Santiago"), a net name or designator ("Command 5", "CDF C5"), a frequency
("151.3175"), a tone ("103.5" or "tone 8") or a callsign. The list shows the
nearest sites first. When the search names a net, each row shows that net's line
at that site, so the nearest one is readable without opening anything. A net with
no fixed site, such as an incident portable repeater, is shown as its own card.

*Where* picks the state, whether distances are measured from you or from the map
center, and a radius. *Show* is one checkbox per agency, with the number of sites
it would show. *Draw on the map* sets the zoom at which sites are drawn, quoted
as what the scale bar reads.

= Site details
Tap a row, or Details on a site's radial menu, for county, elevation, distance
and bearing, and every net on the site with its frequencies, tones as Hz and as
California tone numbers, callsign and notes.

= Which repeaters can I reach from here
*From me ON* draws ATAK's viewshed from your own position, at the height set
under *Height*, out to the chosen *Range*, and checks every site in that range
for line of sight to its antenna over the elevation data on the device (Map
Depot's DTED packs provide it). Sites you can see turn green on the map and list
first; the rest list under "No line of sight". With a search running, the top
row is the nearest matching site you can see. It follows you as you move. The
viewshed is removed when the plugin is unloaded.

It is line of sight, not radio coverage. Nothing here draws coverage from a
repeater: from that end such a picture reads as a coverage map and is not one,
and the honest answer to "can I work this site" is the check above, made from
where you are actually standing.

= The catalog
Sites and nets come from a catalog refreshed from the depot host once a day and
on *Sync catalog*. The status line says which copy is shown and how old it is.
The plugin works offline from its built-in copy.
]
