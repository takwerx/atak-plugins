#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "MAST",
   plugin-version: "0.1",
   platform: "ATAK",
   platform-version: "5.8.0",
)

// Straight quotes: tak.gov's typst prints a ? for some non-ASCII glyphs.
#set smartquote(enabled: false)

#tak-slide[
= Overview

MAST - Mission Adaptable Support Tools - is the front door to the tools a
mission needs.

Pick the mission and MAST shows two pages for it: the ATAK tools that job
uses, and the plugins that job uses. One tap opens any of them. Nobody hunts
through the whole tool menu in the middle of an incident.

MAST remembers the last mission. Tap MAST on the toolbar from inside any
plugin and it opens on that mission's page again.

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("1.png", height: 7.5cm)
][
  The first time, MAST opens on the mission list. Tap a mission to open its
  pages.

  To change mission later, tap the *Mission* button at the top of the pane.
  The mission in use shows in cyan on that button.
]
]

#tak-slide[
= The two pages of a mission

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("2.png", height: 10.5cm)
][
  *Plugins* shows the plugins for the mission. Tap a tile and that plugin
  opens, the same as its own toolbar button.

  A plugin that is not on this device shows as a faded *Not installed* tile.
  Tap it and MAST offers to open the TAKwerx Market, where you can get it.

  The page scrolls when it holds more tiles than the pane shows.
]
]

#tak-slide[
= ATAK tools

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("3.png", height: 10.5cm)
][
  *ATAK Tools* shows ATAK's own tools for the mission. A tile does exactly
  what the same item in ATAK's tool menu does, and MAST closes so the map is
  yours.

  MAST does not put Maps, Drawing or Overlay Manager on these pages: most
  people keep those on the toolbar already. The pages carry the tools that are
  harder to find.

  The two buttons under *Mission* switch between the pages. MAST remembers
  which one you used last.
]
]

#tak-slide[
= Arrange a page

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("5.png", height: 10.5cm)
][
  Press and hold a tile. The page goes into edit mode: the tiles move a little
  and a green *Done* button takes the place of the page switch.

  *Drag* a tile onto another tile and the two change places. Nothing else
  moves. Drop it on an empty place and it goes to the end.

  Tap *Done*, or press Back, to finish. The page is saved as you go.
]
]

#tak-slide[
= Replace, remove, reset

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("6.png", height: 4.6cm)
][
  In edit mode, tap a tile to open its menu.

  - *Replace* puts a different tool or plugin in that place.
  - *Remove* takes the tile off the page.
  - *Reset Page* puts back the tiles MAST ships for this mission.
]

#v(8pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("9.png", height: 3.2cm)
][
  Reset asks first, because your changes to that page are lost. It changes
  only the page you are on.
]
]

#tak-slide[
= Add a tile

Every page ends with one or more *Choose Tool* or *Choose Plugin* tiles. Tap one
to add a tile at the end of the page.

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("8.png", height: 7cm)
][
  #image("7.png", height: 7cm)
]

#v(6pt)
*Choose an ATAK Tool* lists every tool in this ATAK's tool menu. *Choose a
Plugin* lists every plugin loaded in ATAK that has a toolbar button. What the
page already has is left out.
]

#tak-slide[
= When an MDM manages MAST

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("10.png", height: 7.5cm)
][
  An agency can set MAST's pages from its MDM, so every device shows the same
  tools in the same places.

  When the MDM locks the pages, they cannot be changed on the device: there is
  no edit mode and no *Choose* tiles. The tiles still open their tools and
  plugins as usual.

  With no MDM, the pages are yours to arrange.
]

#v(8pt)
For the administrator: MAST declares its settings in the APK, so an MDM that
reads plugin settings (for example Atlas, Plugin Pref Config) lists them. There
is one setting for the lock, one for the mission, one for the page shown, and
one for each mission page. A page is a comma list: tool names such as
`rangeandbearing, routes, redx`, or plugin package names.
]

#tak-slide[
= Worth knowing

- *Nothing leaves the device.* MAST has no network, no account and no server.

- *Your pages survive a restart.* They are kept in ATAK's own settings, so they
  come back after ATAK or the device restarts.

- *MAST only opens things.* Each plugin keeps its own settings and its own map
  layers; MAST does not change them.

- *A tool this ATAK does not have is left off the page*, so a page set on a
  newer ATAK still works on an older one.

#v(8pt)
#image("11.png", width: 80%)

This manual is reached from ATAK's *Settings* > *Tool Preferences* >
*Specific Tool Preferences* > *MAST*.
]
