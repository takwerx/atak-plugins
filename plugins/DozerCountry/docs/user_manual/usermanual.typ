#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Dozer Country",
   plugin-version: "0.8",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

Dozer Country answers one question from the map: can we put this machine in
there.

Draw an area and the ground inside it is shaded against the slope limits that
machine is held to, so the call can be made from the map rather than from memory
of the ground. It is for the person picking where a line goes before the iron
gets there.

The limits are the per-machine table in NWCG S-236 Heavy Equipment Boss, Unit 2.
A dozer is the most capable thing in that table - 45 percent across, 55 uphill,
75 downhill - and everything else is held tighter.

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
= Pick the machine first

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("2.png", width: 100%)
][
  Nothing is painted until the machine is known, and *Draw area* stays greyed out
  until it is.

  That is deliberate. S-236 holds a feller buncher to 30 percent sidehill where a
  dozer gets 45, and a tractor plow to less again. A tool that quietly assumed a
  dozer would hand the most permissive map in the book to whoever never touched
  the control.
]
]

#tak-slide[
= What are you working with

#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("3.png", width: 100%)
][
  Eight machines, all of them things that go cross-country: dozer, pumper cat,
  dozer/track skidder, excavator, feller buncher, rubber-tired skidder or skid
  steer, masticator or harvester, and tractor plow.

  #text(size: 0.9em)[
  *A grader and a forwarder are deliberately absent.* Both work from a road, and a
  road is the one thing this overlay cannot see - see _What it cannot do_.

  Changing machine repaints ground you have already drawn. You do not have to
  draw it again.
  ]
]
]

#tak-slide[
= Drawing an area

#image("4.jpg", width: 100%)

#v(4pt)
Press *Draw area* and ATAK's own shape tool takes over, with its prompt, its
rubber band and its Undo and End Shape buttons. The panel says the same thing on
its own side, and *Draw area* becomes *Cancel* while the tool is up.
]

#tak-slide[
= Closing the shape

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("5.jpg", width: 100%)
][
  *Tap the first marker to close the shape.*

  Pressing End Shape instead gives a line rather than an area. Dozer Country will
  say so and ask you to close it, because there is no inside to a line and
  nothing to shade.

  Any shape works. The color stops at the ring you drew, not at a box around it.
]
]

#tak-slide[
= The same ground, a dozer

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("6.jpg", height: 320pt)
][
  A dozer takes nearly all of this country. The shaded ground is the exception,
  and that is the whole idea: bare map means a machine can work it whichever way
  it sits.
]
]

#tak-slide[
= The same ground, a skid steer

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("7.jpg", height: 320pt)
][
  Same area, same zoom, machine switched. A rubber-tired skidder is held to 22
  percent sidehill instead of 45, and most of what a dozer could work is now out.

  This pair is the reason the machine is asked for first.
]
]

#tak-slide[
= What the colors mean

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("8.png", width: 100%)
][
  The classes for the machine you picked, in the words of the standard, with the
  hollow swatch for the band that is deliberately not drawn.

  Every band names its own number, so you can tell ground that is merely over the
  cross-slope limit from ground that is over everything.
]
]

#tak-slide[
= What the panel tells you

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("9.png", width: 100%)
][
  The cell size actually used. Each cell is classed by the *worst slope within one
  chain* (66 ft), not by its own value, so an isolated flat spot inside a steep
  face is never called workable.

  A large area gives coarse cells; the panel says so and says to draw a smaller
  one for a finer read.

  *Overlay* hides and shows the shading without recomputing. *Clear* removes the
  area and its outline.
]
]

#tak-slide[
= The same ground in 3D

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("10.jpg", height: 320pt)
][
  Tilting the map is where the overlay earns its keep: the drainages come up
  unshaded, the sidehills yellow and orange, the noses of the ridges dark red.
  That is the shape of the problem rather than a list of percentages.
]
]

#tak-slide[
= Turning the shading off

#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("11.jpg", width: 100%)
][
  *Overlay* hides the shading without recomputing it, and the outline stays behind
  so you can still see which ground was worked out.

  Deleting the area itself - from its own radial menu, or from Overlay Manager -
  takes the shading with it.
]
]

#tak-slide[
= When it refuses

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("12.png", width: 100%)
][
  Dozer Country will not guess. It needs DTED2 - 30 metre posts - or better over
  every part of the area, and if it has not got it, it declines and tells you what
  it found and what to load.

  That refusal is what makes the unshaded ground trustworthy: bare map inside an
  area always means "under every limit", never "no data".

  Elevation loads through ATAK's own elevation manager, or with the Map Depot
  plugin.
]
]

#tak-slide[
= What it cannot do

#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("13.png", width: 90%)
][
  *It cannot see a road.* DTED2 is 30 metre posts and a road bench is a few metres
  wide, so a graded road cut across a steep face paints the same red as the face.
  Read this for open ground, never for road work. That is also why a grader and a
  forwarder are not in the machine list at all.

  *It does not know which way your line runs*, so it reads every cell as the worst
  case - the sidehill limit.

  *It does not know soil, rock, moisture or fuel*, and it is a guideline rather
  than a clearance. Type and size of machine, weather, ground conditions and
  operator all move these numbers, and the guide says so in the same paragraph
  the limits come from.

  *Water is left unclassed.* It is found by looking for dead-flat ground, so water
  that ATAK's elevation does not render flat can be missed.
]
]

#tak-slide[
= Worth knowing

- *Nothing leaves the device.* No network, no account, no server. Slope is
  computed from the elevation already on the phone.

- *The standard is a readable file*, not numbers buried in code:
  `assets/dozer_data.json` inside the plugin carries every machine, its limits,
  the wording of each band and the source they came from - so the numbers a color
  is claiming can be audited by the person whose ground it is.

- *Where a range is published, the overlay paints the conservative end*, and says
  so in the panel. The excavator's sidehill is given as 35-50 percent and it
  paints 35.

- *The masticator has no published sidehill at all.* S-236 gives it none, so it
  carries the most conservative sidehill in the guide, 12 percent, and the panel
  says where that came from.

- *S-236 replaced S-232 Dozer Boss and S-233 Tractor Plow Boss*, in its own
  words. Older material under those numbers carries the same dozer figures.

#v(8pt)
This manual is reached from ATAK's *Settings* > *Tool Preferences* > *Dozer
Country*.
]
