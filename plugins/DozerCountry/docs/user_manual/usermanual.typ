#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Dozer Country",
   plugin-version: "0.6",
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

The limits are the dozer slope limits in NWCG S-236 Heavy Equipment Boss,
Student Workbook, Unit 2 - "Maximum slope: 75% downhill, 55% uphill, 45%
sidehill" - the same three numbers the legacy S-232 Dozer Boss carried in
Appendix D. S-236 tables every machine separately and this paints the dozer row
only:
dozers should not be operated across slopes over 45 percent, uphill over 55
percent, or downhill over 75 percent.

Ground inside every limit is left unshaded on purpose, so the map shows through.
Color means something needs looking at.

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

  *Clear* takes the area and its outline back off the map when you are done with
  it.
]
]

#tak-slide[
= ATAK's own shape tool

Dozer Country does not invent a drawing tool. ATAK's own takes over, with the
prompt, the rubber band, the vertex handles and the Undo and End Shape buttons an
ATAK user already knows. The panel says the same thing on its own side, and
*Draw area* becomes *Cancel* while the tool is up.

#v(6pt)
#image("3.png", width: 100%)
]

#tak-slide[
= Closing the shape

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("4.jpg", width: 100%)
][
  *Tap the first marker to close the shape.*

  Pressing End Shape instead gives a line rather than an area. Dozer Country will
  say so and ask you to close it, because there is no inside to a line and
  nothing to shade.
]
]

#tak-slide[
= Reading the overlay

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("5.jpg", height: 290pt)
][
  The orange outline is the area you drew, and the color stops exactly there.

  Unshaded ground is under every limit - a machine can work it whichever way it
  sits. That is most of a good piece of country, and leaving it bare is what lets
  you still read the terrain underneath.

  #v(4pt)
  #image("11.png", width: 62%)

  #text(size: 0.85em)[The key sits bottom left, and lists only the bands that are
  actually painted.]
]
]

#tak-slide[
= What the colors mean

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("8.png", width: 100%)
][
  The classes, in the words of the standard. The hollow swatch is the band that
  is deliberately not drawn.

  The amber paragraph is worth reading twice: this is *slope steepness only*,
  read as worst case. It does not know which way your line will run, nor soil,
  rock, moisture or fuel.
]
]

#tak-slide[
= The same ground in 3D

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("6.jpg", height: 290pt)
][
  Tilting the map is where the overlay earns its keep. The drainages come up
  unshaded, the sidehills yellow and orange, and the noses of the ridges dark
  red - which is the shape of the problem, not a list of percentages.

  Nothing extra is needed. The shading is an ordinary map layer and follows
  ATAK's own terrain.
]
]

#tak-slide[
= What the panel tells you

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("7.png", width: 100%)
][
  The cell size actually used, and the working window. Each cell is classed by
  the *worst slope within one chain*, not by its own value, so an isolated flat
  spot inside a steep face is never called workable.

  A large area gives coarse cells. The panel says the size it managed and says to
  draw a smaller area for a finer read.
]
]

#tak-slide[
= Turning the shading off

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("10.jpg", width: 100%)
][
  *Overlay* hides and shows the shading without recomputing it, and the outline
  stays behind so you can still see which ground was worked out.
]
]

#tak-slide[
= When it refuses

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("9.png", width: 100%)
][
  Dozer Country will not guess. It needs DTED2 - 30 metre posts - or better over
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

- *Water is left unclassed* rather than painted as workable ground. It is found
  by looking for dead-flat ground, so water that ATAK's elevation does not render
  flat can be missed.

- *Nothing leaves the device.* No network, no account, no server. Slope is
  computed from the elevation already on the phone.

- *The standard is a readable file*, not numbers buried in code:
  `assets/dozer_data.json` inside the plugin carries the bands, the limits and
  the source they came from, so the numbers a color is claiming can be checked by
  the person whose ground it is.

#v(10pt)
This manual is reached from ATAK's *Settings* > *Tool Preferences* > *Dozer Country*.
]
