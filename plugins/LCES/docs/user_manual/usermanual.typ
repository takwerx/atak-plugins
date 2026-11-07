#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "LCES",
   plugin-version: "0.1",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

#toolbox.side-by-side(columns: (1.5fr, 9fr))[
  #image("plugin_icon.png", width: 85%)
][
Lookouts, Communications, Escape Routes, Safety Zones. LCES puts the wildland
fire Incident Response Pocket Guide (IRPG) one tap away in ATAK, and puts the
lessons other crews learned the hard way on the map, where they happened.
]

#v(6pt)

- *The IRPG:* every page of the NWCG Incident Response Pocket Guide (PMS 461,
  January 2025), with its tables and diagrams, in the book's own section colors.
- *Lessons Learned:* more than 2,300 burnovers, entrapments, fatalities, near
  misses and injuries from the Wildland Fire Lessons Learned Center, on the map
  and in a list nearest first.
- *One search box* for both.
]

#tak-slide[
= Before you start

- *Match the plugin to your ATAK version.* Builds are tied to the ATAK release
  they were built against; 5.6, 5.7 and 5.8 builds are published.
- *It works with no signal.* The whole IRPG and the whole lessons list are
  inside the plugin. With a network, it checks once a day for a newer lessons
  list.
- *Nothing is sent from your device.* LCES creates no markers for anyone else
  and does not talk to your TAK server.

#v(6pt)

Load it from ATAK's Plugins manager (TAK Package Mgmt) like any other plugin,
then open it from the toolbar with the LCES icon.
]

// SHOT 01 (main screen) goes here once it is taken: see SHOTLIST-LCES-manual.md.

#tak-slide[
= The IRPG tiles

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("02.png", width: 96%)
][
  The tiles under *FROM THE IRPG* open the pages read every shift:

  - *10s & 18s:* the 10 Standard Firefighting Orders and 18 Watch Out
    Situations.
  - *LCES*, the *Briefing* Checklist, and the *Common Denominators* of fire
    behavior on tragedy fires.
  - *IRPG:* the book's contents.

  #v(6pt)

  The buttons at the bottom go to the page before and the page after, as
  turning a page in the book.
]
]

#tak-slide[
= The whole book

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("03.png", width: 96%)
][
  *IRPG* opens the contents, in the book's own sections and colors. Tap a
  section to open its list of pages.

  #v(6pt)

  *Continue* takes you back to the last page you read.

  #v(6pt)

  The arrows button at the top right makes the pane wide, for tables and
  diagrams. Tap it again, or Back, for half width.
]
]

#tak-slide[
= Tables and diagrams

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("04.png", width: 96%)
][
  Tables and diagrams are the book's own, so the numbers are exactly what
  the IRPG prints.

  #v(6pt)

  For larger text, open *Settings* from the main screen and set
  *Text size: Large*.
]
]

#tak-slide[
= Search

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("05.png", width: 96%)
][
  Type in the search box on the main screen. The list shows the IRPG pages
  that match first, then the lessons.

  #v(6pt)

  The line at the top says how many of each matched.

  #v(6pt)

  A search also filters the map to the lessons it found. *Clear* puts
  everything back.
]
]

#tak-slide[
= A page from a search

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("06.png", width: 96%)
][
  Tap an IRPG result and the page opens with the words you searched for
  marked in yellow.

  #v(6pt)

  Back returns to the results.
]
]

// SHOTS 07 and 08 (lessons on the map, signed build) go here.

// SHOT 09 (an incident's page) goes here.

#tak-slide[
= Settings

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("10.png", width: 96%)
][
  *Settings* on the main screen.

  - *Text size:* Normal or Large, for every IRPG page.
  - *Zoom gate:* hide the lessons when the map is zoomed out past a
    distance you pick, so a wide view stays readable.
  - *Area:* everything, or only lessons within a distance of your location
    or the map center.
  - *Types* and *Category:* see the next pages.
  - *Map key:* what each color means.
]
]

#tak-slide[
= Types

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("11.png", width: 96%)
][
  Five types, each in its color on the map, with how many there are. Tap
  one to turn it ON or OFF.

  #v(6pt)

  The list on the main screen and the map always show the same lessons.
]
]

#tak-slide[
= Category

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("12.png", width: 96%)
][
  The Lessons Learned Center's own categories: medical, aviation, chainsaw,
  vehicle accident and the rest, with how many of each.

  #v(6pt)

  *None*, then one category, shows only that kind of lesson: two taps.
]
]

#tak-slide[
= Map key

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("13.png", width: 96%)
][
  The color of each type on the map.

  #v(6pt)

  The type is the most serious thing that happened: a fatality first, then a
  burnover, an entrapment, an injury, and otherwise a near miss.
]
]

// SHOT 14 (status line naming what is hidden) goes here.

#tak-slide[
= Opening this guide

#image("15.png", width: 92%)

ATAK's *Settings*, then *Tool Preferences*, *Specific Tool Preferences*,
*LCES*, and *Plugin Documentation*.
]

#tak-slide[
= Sources

- *IRPG:* NWCG Incident Response Pocket Guide, PMS 461, January 2025, every
  word and diagram taken from the published book. A reference, not a
  substitute for the IRPG or your training.
- *Lessons Learned:* the Wildland Fire Lessons Learned Center's incident
  database. *Open the full report* on an incident opens the Center's own
  report in your browser and needs a network.
- *The list on the server* is checked against the Center's database every
  night and updated when it changes. Your device picks up a new copy within a
  day when it has a network, and keeps the copy it has when it does not.

#v(6pt)

Report a problem or ask for a change: github.com/takwerx/lces/issues
]
