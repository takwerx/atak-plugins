#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "UAS Flight Plan",
   plugin-version: "0.2",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

UAS Flight Plan answers two questions a UAS pilot has standing at the turnout
before the radio call to Air Attack: *what ceiling does this mission area
need*, and *what does the ceiling I was given do to it*.

It reads the terrain ATAK already carries, paints the ground you cannot work
over as red islands, and puts the FAA's charted towers and wires on the map at
their height. Every altitude in the plugin is feet above mean sea level and
says so.

#v(6pt)
#toolbox.side-by-side(columns: (8fr, 4fr))[
  #image("1.png", width: 100%)
][
  Open it from the ATAK toolbar. The pane opens at half width with three
  buttons: *Islands ON/OFF*, *Settings* and *Launch point*.
]
]

#tak-slide[
= Before you start

- *Elevation data.* The plugin reads the elevation loaded in ATAK. It needs
  DTED2 (30 m posts) or finer for the area you work in; the Map Depot plugin can
  download it. With coarser data the plugin says so and paints nothing, because
  a ridge between kilometer posts is simply not in the file.

- *Network.* The FAA obstacles are fetched once per plan over the internet. The
  last answer is kept on the phone, so a plan comes back without signal.
  Everything else works offline.

- *ATAK versions.* A plugin built for another ATAK version will not load. Pick
  the download that matches your ATAK-CIV version.

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("2.png", height: 180pt)
][
  The line at the top is the status line. It always says what the plugin is
  doing and what it is not showing.
]
]

#tak-slide[
= The launch point

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("3.png", width: 100%)
][
  Tap *Launch point*. The popup offers:

  - *My position*: where the phone is.
  - *Tap the map*: ATAK's prompt bar asks for one tap.
  - *Draw the area to cover*: the next page.
  - *Clear the area* and *Clear everything*.

  A quadcopter marker goes on the map with a *Launch* pill above it, and the
  pane reads the ground there: *Ground here: 3,570 ft MSL (0 AGL)*. That is the
  terrain under the marker from ATAK's elevation data, not the phone's GPS
  altitude.
]
]

#tak-slide[
= The circle

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("4.png", height: 300pt)
][
  Around the launch point the plugin paints a circle, 1 mi across by default
  (Settings, Area). Blue is ground you can work over. A white edge marks the
  limit of what was checked.
]
]

#tak-slide[
= Drawing the area to cover

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("5.png", height: 300pt)
][
  A circle around where you stand is rarely the mission. Tap *Launch point*,
  then *Draw the area to cover*. ATAK's drawing toolbar comes up with the
  polygon tool: tap each corner, then tap the first corner to close the shape.

  The shape replaces the circle. If no launch point is placed yet, the plugin
  asks for it the moment the shape closes: *My position* or *Tap the map*.

  Starting a new area removes the old one at once. The shape is one of your own
  ATAK drawings, so it comes back when ATAK restarts and the plugin picks it up.
]
]

#tak-slide[
= Need a ceiling

Under *Ground here* is the *Ceiling* row with two buttons. Pick the one that is
your situation; the chosen one is green.

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("6.png", height: 300pt)
][
  You are planning and have to ask Air Attack for a ceiling. The block reads the
  highest ground in the area, adds the height you fly above the terrain
  (Settings, Height above terrain), and rounds up to the next 100 ft. Here
  5,687 ft of ground plus 400 ft becomes *Ask Air Attack for 6,100 ft MSL*.

  The last line is the same number above your launch point, which is what the
  controller wants.
]
]

#tak-slide[
= Given a ceiling

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("7.png", height: 300pt)
][
  Air Attack assigned you a ceiling and you have to live with it. Tap *Given a
  ceiling* and type it. The block reads what that ceiling does to your area:
  the highest ground you can work over at your height above terrain, and how
  much of the area is too high.

  The mission area has to be blue, or the ceiling is wrong for the mission.
]
]

#tak-slide[
= Islands

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("8.png", height: 300pt)
][
  The map paints the ground above the ceiling minus your height above terrain
  as *red islands*: flying over them at that height would put the aircraft
  through the ceiling.

  The status line says how much of the area they cover and names the highest
  ground. *Islands ON/OFF* hides the paint without forgetting the plan.
]
]

#tak-slide[
= Obstacles

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("9.png", height: 280pt)
][
  Inside the area the plugin draws the FAA's charted obstacles: towers,
  transmission towers, wire spans, wind turbines, stacks and tanks. Each stands
  on the map as a mast at its real height, with its name in a pill above a
  tower symbol. Tilt the map into 3D and they stand up.

  - *Orange*: the top is under the ceiling.
  - *Red*: the top is at or above the ceiling.

  *Not every wire or tower is charted.* The FAA file holds the obstacles that
  affect charting; distribution lines are never in it.
]
]

#tak-slide[
= The obstacle list

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("10.png", width: 100%)
][
  The list under the ceiling block shows the same obstacles nearest you first,
  with the height above ground, the top in MSL, lit or unlit, verified or not,
  and the distance and compass bearing from where you stand.

  Tap a row to pan to it, or *Details* for everything the FAA records about it.
  Tapping an obstacle on the map opens the same page.
]
]

#tak-slide[
= An obstacle's details

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("11.png", width: 100%)
][
  The FAA's record for one obstacle: type, height above ground, top in MSL,
  lighting, marking, whether the FAA has verified it, and the date of the
  record. *Zoom to* puts it in the middle of the map; *Back* returns to the
  list.
]
]

#tak-slide[
= Settings

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("12.png", width: 100%)
][
  Everything set once and left. Each row reads its value; tap it to change it.

  - *Obstacles ON/OFF*: the obstacle layer on the map.
  - *Taller than*: obstacles shorter than this are hidden. 50 ft by default,
    because the FAA file lists 20 ft solar arrays on ballfields.
  - *Types*: which kinds show, with the count of each in the area.
  - *Height above terrain*: how high above the ground you fly. Both ceiling
    modes use it.
  - *Area*: the circle size when no area is drawn.
  - *Map key*: what the colors mean.
]
]

#tak-slide[
= Types and the map key

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("13.png", height: 250pt)
][
  #image("14.png", height: 250pt)
]

#v(6pt)
Transmission towers, wire spans, towers, turbines, stacks and tanks are on by
default; buildings, poles and solar panels are off. Every tile shows the count
in the area before you tap it, so a filter says what it will cost.
]

#tak-slide[
= When there is no terrain data

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("15.png", height: 300pt)
][
  UAS Flight Plan will not guess. It needs DTED2 (30 m posts) or better over
  every part of the area, and if it has not got it, it declines, names what it
  found and says what to load.

  That refusal is what makes the blue trustworthy: blue inside an area always
  means "under the ceiling", never "no data".
]
]

#tak-slide[
= When there is no network

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("16.png", width: 100%)
][
  The FAA obstacles for an area are kept on the phone after the first fetch.
  Without signal the status line says the phone's saved answer is in use, and
  the plan is otherwise complete.

  The plan survives a plugin reload and an ATAK restart, with or without
  network: the launch point, the area, the mode, the ceiling, the height and
  the filters. Only your own *Clear everything* forgets it.
]
]

#tak-slide[
= Worth knowing

- *The obstacle data is the FAA Digital Obstacle File*, fetched from the FAA's
  public service for the area of the plan. It is a charting file: it holds what
  affects aviation charts, and it is not a survey of every wire on the ground.

- *Elevation is ATAK's own.* The plugin converts the engine's ellipsoid height
  to MSL with the EGM96 geoid, which is what an altimeter and an aviation chart
  mean by MSL.

- *Islands are a comparison, not a clearance.* They mark ground that cannot be
  worked at your height above terrain under the ceiling; they do not know about
  wind, airspace or other aircraft.

- *Nothing is shared.* The plan lives on this phone and is never sent to the
  TAK server or to other users.

#v(8pt)
This manual is reached from ATAK's *Settings* > *Tool Preferences* > *UAS
Flight Plan*.
]
