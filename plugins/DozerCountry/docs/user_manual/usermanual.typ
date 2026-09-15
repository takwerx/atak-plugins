#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Dozer Country",
   plugin-version: "0.2",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

Dozer Country answers one question from the map: can we put a cat in there.

Draw an area and the ground inside it is shaded against the slope limits a dozer
is held to, so the call can be made from the map rather than from memory of the
ground. It is for the person picking where a line goes before the machine gets
there.

The limits are the refusal points in the Dozer Boss guide, S-232 Appendix D:
dozers should not be operated across slopes over 45 percent, uphill over 55
percent, or downhill over 75 percent.

Ground inside every limit is left unshaded on purpose, so the map shows through.
Colour means something needs looking at.

#v(6pt)
#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("1.png", width: 100%)
][
  Open it from the ATAK toolbar. Tapping the badge again closes the panel, so it
  is one tap on and one tap off when you want the map to yourself.
]
]

#tak-slide[
= Drawing an area

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("2.png", width: 100%)
][
  Press *Draw area*. Nothing is computed until you have drawn one, and the panel
  says so.
]

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("3.png", width: 100%)
][
  ATAK's own shape tool takes over, with its prompt and its Undo and End Shape
  buttons. Tap to place each corner.
]

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("4.png", width: 100%)
][
  *Tap the first marker to close the shape.* Pressing End Shape gives a line
  rather than an area, and Dozer Country will say so and ask you to close it.
]
]

#tak-slide[
= Reading the overlay

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("5.png", width: 100%)
][
  The orange outline is the area you drew, and the colour stops exactly there.

  Unshaded ground is under every limit — a machine can work it whichever way it
  sits. That is most of a good piece of country, and leaving it bare is what lets
  you still read the terrain underneath.
]

#v(6pt)
#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("6.png", width: 100%)
][
  #text(size: 0.9em)[
  *46-55%* — over the 45 percent sidehill limit. Up and down only, not across.

  *56-75%* — over the 55 percent uphill limit as well. Downhill only, and that is
  the last limit in the guide.

  *Over 75%* — over every limit. The guide has nothing to say past here.
  ]
]
]

#tak-slide[
= What the panel tells you

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("7.png", width: 100%)
][
  The cell size and the window actually used. Each cell is classed by the *worst
  slope within one chain*, not by its own value, so an isolated flat spot inside
  a steep face is never called workable.

  *Overlay* hides and shows the shading without recomputing. *Clear* removes the
  area and its outline.
]

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("8.png", width: 100%)
][
  The classes, in the words of the standard. The hollow swatch is the band that
  is deliberately not drawn.

  The amber line is the part worth reading twice: this is *slope steepness only*,
  read as worst case. It does not know which way your line will run, nor soil,
  rock, moisture or fuel.
]
]

#tak-slide[
= When it refuses

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("9.png", width: 100%)
][
  Dozer Country will not guess. It needs DTED2 — 30 metre posts — or better over
  every part of the area, and if it has not got it, it declines and tells you
  what it found and what to load.

  That refusal is what makes the unshaded ground trustworthy: bare map inside an
  area always means "under every limit", and never "no data".

  Elevation loads through ATAK's own elevation manager, or with the Map Depot
  plugin.
]
]

#tak-slide[
= Worth knowing

- *It is a guideline, not a clearance.* Type and size of machine, weather,
  ground conditions and operator all move these numbers, and the guide says so in
  the same paragraph the limits come from.

- *The 45 percent line is the cross-slope limit*, used as the worst case because
  the overlay cannot know which way a line will run. Ground shown at 46-55% may
  be perfectly workable straight up and down.

- *Water is left unclassed* rather than painted as workable ground.

- *Nothing leaves the device.* No network, no account, no server. Slope is
  computed from the elevation already on the phone.

- *The standard is a readable file*, not numbers buried in code:
  `assets/dozer_data.json` inside the plugin carries the bands, the limits and
  the source they came from, so the numbers a colour is claiming can be checked
  by the person whose ground it is.

#v(10pt)
This manual is reached from ATAK's *Settings → Tool Preferences → Dozer Country*.
]
