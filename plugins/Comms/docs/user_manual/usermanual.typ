#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Comms",
   plugin-version: "0.5",
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
#toolbox.side-by-side(columns: (5fr, 4fr))[
The pane opens from the Comms toolbar button. Type in the search box: a site name
("Santiago"), a net name or designator ("Command 5", "CDF C5", "cdf 5"), a tone
("103.5" or "tone 8") or a callsign.

Searching a net gives every mountain that carries it, nearest first, with that
net's tone at each one. That is the question this exists for: you know you need
Command 1, and you want the nearest hill that will give it to you.

A net with no mountain at all -- an incident portable, carried to the fire and set
up there -- says so rather than showing an empty list.

*Where* picks the state, whether distances are measured from you or from the map
center, and a radius. *Show* is one checkbox per agency, carrying the number of
sites it would add.
][
#image("1.png", width: 100%)
]

= Site details
#toolbox.side-by-side(columns: (5fr, 4fr))[
Tap a row, or Details on a site's radial menu, for county, elevation, distance and
bearing, and every net on the mountain with the tone that opens that repeater.

One tone per net, and it is the one you transmit to key that repeater. A call plan
prints two, but the other belongs to the net and is identical at every site on it,
so it can never tell you which mountain to use. Where this catalog does not hold a
tone the row says which kind of nothing it is -- a digital trunked channel has no
CTCSS tone at all, and a plan can withhold one -- rather than leaving you to guess.

A mountain often carries several agencies, each on its own tone.
][
#image("2.png", width: 100%)
#v(6pt)
#image("3.png", width: 100%)
]

= On the map
#toolbox.side-by-side(columns: (4fr, 5fr))[
Sites draw as the NWCG repeater symbol, named, once you are zoomed in far enough
to make sense of them. *Draw on the map* sets that zoom and quotes it as what the
scale bar reads, so you can match it to how you work rather than to a number.

Tapping a site opens ATAK's radial menu on it, with Details, a pairing line and
polar.
][
#image("4.jpg", width: 100%)
]

= Which repeaters can I reach from here
#toolbox.side-by-side(columns: (5fr, 4fr))[
*From me ON* works out, for every site within *Range*, whether a handheld where
you are standing is likely to open it. It walks the terrain between you and the
antenna over the elevation data on the device (Map Depot's DTED packs provide it),
scores the worst ridge in the way for the loss a signal takes bending over it, and
weighs that against what a handheld into a mountaintop repeater can afford.

*Height* says how high your own antenna is, from a handheld at head height to a
tower. *Range* decides only how far to look, never what the answer is.
][
#image("5.png", width: 100%)
#v(6pt)
#image("8.png", width: 100%)
]

= Likely, marginal, unlikely
#toolbox.side-by-side(columns: (4fr, 5fr))[
Sites you are likely to reach light their symbol green on the map and sort to the
top of the list under *Likely from here*, with *Marginal* and *Unlikely* below and
anything past the range listed as not checked. A group only appears when something
falls into it.

*Only likely* puts the rest away, map and list together, for when you just want
the ones worth trying; the status line says how many it is holding back. The
answer follows you as you move, and the status line counts the sites off while it
works.

#block(inset: (left: 6pt), stroke: (left: 2pt + gray))[
*This is a guide, not a prediction.* It is bare earth: it knows nothing about
trees, buildings, vehicles or weather, it assumes one ridge rather than a range of
them, and it assumes a handheld rather than the radio in your hand. A site it
calls unlikely is still worth a try, and a site it calls likely can still fail.
Confirm on the radio.
]
][
#image("6.jpg", width: 100%)
#v(6pt)
#image("7.png", width: 100%)
]

= Why there is no coverage picture
Nothing here paints coverage on the map, and that is deliberate.

Drawn from a repeater, a viewshed reads as a coverage map and is not one: it is
bare-earth line of sight, pessimistic wherever a signal would bend into a valley
and silent about whether your handheld can make the uplink back.

Drawn from you, ATAK's own viewshed has a subtler problem. It samples on a fixed
grid however large an area you ask it to cover, so its cells run from about 30 m at
a five-mile range to about 320 m at fifty -- wide enough to smooth a blocking ridge
away entirely. The same mountain was painted visible at one range setting and
hidden at another. A picture that changes its answer with a display setting is
worse than no picture.

The reach check is worked out per site, at the same resolution whatever the range.

= The catalog
Sites and nets come from a catalog refreshed from the depot host once a day and on
*Sync catalog*. The status line says which copy is shown and how old it is. The
plugin works offline from its built-in copy, and adding a site or a net is a
catalog change rather than a new plugin release.

= If something looks wrong
*Nothing is drawing on the map.* The pane says why, in amber, at the front of the
status line: either the map toggle at the top is off, or you are zoomed out past
the draw limit.

*Every site says the reach is unknown.* There is no elevation data for where you
are. Map Depot's DTED packs provide it.

*The plugin will not load.* Check the build matches your ATAK version; 5.6, 5.7
and 5.8 have separate downloads.
]
