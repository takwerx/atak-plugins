#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "PLSS Grid",
   plugin-version: "0.5",
   platform: "ATAK",
   platform-version: "5.7.0",
)

#tak-slide[
= Overview

#toolbox.side-by-side(columns: (1.5fr, 9fr))[
  #image("plugin_icon.png", width: 85%)
][
PLSS Grid draws the Public Land Survey System over the map: townships, ranges
and sections, labelled the way they are called on the radio. ATAK ships MGRS
and USNG grids and nothing for PLSS, which is what wildland fire and
public-land operations actually use for division assignments, drop points,
contingency lines and legal descriptions.
]

#v(6pt)

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("01.png", width: 94%)
][
  The survey is held on the device, not streamed. You download the states you
  work in once, and the grid then draws with no network at all -- in airplane
  mode, in a canyon, on a hotspot you would rather not spend.

  #v(6pt)

  Everything is on this one panel: the data, the search, the overlay and the
  two colours.
]
]

#tak-slide[
= The PLSS in one minute

Most of the United States west of Ohio was surveyed into a rectangular grid.

#v(6pt)

- A *principal meridian* and base line anchor each survey region -- the Mount
  Diablo Meridian, San Bernardino Meridian, Boise Meridian and about three
  dozen others.
- *Townships* are six-mile squares, counted north or south of the base line
  (*T9N* is the ninth township north) and east or west of the meridian
  (*R28W* is the 28th range west). A township is named by both: *T9N-R28W*.
- Each township is cut into 36 *sections* of one square mile, numbered 1 to 36
  starting at the north-east corner and snaking back and forth.

#v(6pt)

So a PLSS address reads like a radio call: "San Bernardino Meridian, Township
16 South, Range 11 East, Section 21."

#v(6pt)

*The township and range numbers restart at every principal meridian.* T1N-R1W
exists under most of them, hundreds of miles apart, which is why the meridian
is part of the address and why the plugin always asks for it.
]

#tak-slide[
= Before you start

- *Match the plugin to your ATAK version.* Builds are tied to the ATAK release
  they were built against; 5.6, 5.7 and 5.8 builds are published. A mismatched
  build will not load, and the failure does not look like a version problem.
- *The plugin ships with no map data.* On first use you download the states you
  need. Packs run from about 6 MB to 124 MB, and a typical western operating
  area of three or four states is under 100 MB -- worth doing on Wi-Fi.
- *No other network calls.* Outbound HTTPS on port 443 only, and only while a
  download is running. Never to the TAK server, and no CoT is generated.

#v(6pt)

Load it from ATAK's Plugins manager (TAK Package Mgmt) like any other plugin,
then open it from the toolbar.
]

#tak-slide[
= Map data

The plugin arrives empty, so the first thing to do is fetch the states you
work in. *Manage map data* lists every state the survey covers, with the size
of each pack and whether you already hold it.

#v(6pt)

Downloads resume rather than restart after a dropped connection, and each pack
is checked against a digest before it is installed -- a truncated pack would
otherwise draw a grid that is quietly wrong.

#v(6pt)

Installed states can be removed individually from the same dialog to free
storage. The source date of the survey is shown at the top, so you can tell how
current the data is.

#v(6pt)

*Data source:* BLM National PLSS CadNSDI, the authoritative survey record.
USGS products derive from it.
]

#tak-slide[
= Map data

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("02.png", width: 96%)

  Installed states carry Delete, the rest carry Download. The BLM source date
  is at the top.
][
  #image("03.png", width: 96%)

  A download in progress. Each pack says how many sections it holds.
]
]

#tak-slide[
= Showing the grid

The overlay starts hidden every time ATAK launches, the same as ATAK's own
grids. Turn it on from the plugin's pane or from the Overlay Manager.

#v(6pt)

*Townships draw first, sections as you zoom in.* Each has its own zoom
threshold, so a view that shows nothing usually means you are zoomed too far
out rather than that something is broken. Zoom in and the townships appear,
then the sections inside them.

#v(6pt)

Township and section colours are yours to set, and they persist across
restarts. Pick something that reads against the base map you actually use --
a colour chosen over imagery can vanish over a topo sheet.
]

#tak-slide[
= Townships, then sections

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("05.jpg", width: 96%)

  Zoomed out: townships only, each labelled with its township and range.
][
  #image("06.jpg", width: 96%)

  Zoomed in: the 36 sections inside one township, numbered from the north-east
  corner and snaking back and forth.
]
]

#tak-slide[
= Colours

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("07.png", width: 96%)
][
  Tap either swatch on the panel to change it. Townships and sections are set
  separately, and both persist across restarts.

  #v(6pt)

  Custom opens a full picker if none of the presets reads well against the base
  map you are using.
]
]

#tak-slide[
= Finding a township

You can search for a township directly rather than hunting for it on the map.
Give the meridian, the township and the range, and the map goes there.

#v(6pt)

The meridian is not optional and not a formality: T1N-R1W exists under most of
the three dozen principal meridians, hundreds of miles apart. Without it the
address is ambiguous.

#v(6pt)

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("08.png", width: 96%)

  The address from the example, entered.
][
  #image("09.jpg", width: 96%)

  And where it lands. Section 21 is in the middle of the frame.
]
]

#tak-slide[
= What you will see along state lines

BLM publishes the survey *one state at a time, clipped at the state boundary*.
A township straddling a state line therefore exists in two files, each with its
own outline. The plugin handles it, and it is worth knowing what you are
looking at.

#v(6pt)

- *Each township and section is named once.* With both states installed the
  plugin recognises the two halves as one feature and centres a single name
  over the whole of it. Without the neighbouring state you simply see nothing
  on the far side of the line.
- *A line runs along the state boundary.* That is where BLM cut the data, not a
  survey line. The narrow cells beside it are the two halves of genuine
  sections, not extra ones -- each carries a single number.
- *Irregular cells at lakes and rivers are real.* Sections meeting a large lake
  or a navigable river stop at the surveyed meander line of the shore.
- *Gaps are real too.* The rectangular survey went around Spanish and Mexican
  land grants, so there is no PLSS beneath much of coastal and southern
  California. Sparse or irregular sections in land-grant country are the data,
  not a fault.
]

#tak-slide[
= A state line, in one picture

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("10.jpg", width: 96%)
][
  The California--Nevada line at Crystal Bay, with both states installed.

  #v(6pt)

  The straight line running north is where BLM cut the data, not a survey line.
  The sections either side of it are numbered normally.

  #v(6pt)

  The ragged orange edge along the water is the surveyed meander line of the
  shore -- the actual survey, not an artefact.
]
]

#tak-slide[
= Good to know

- The overlay starts hidden at every launch; your colours persist.
- If nothing draws, zoom in. Sections and townships each have their own
  threshold.
- After updating the plugin ATAK unloads it. Reload it from the Plugins
  manager and turn the overlay back on.
- Several states can be installed side by side, and removed one at a time.
- Once a state is downloaded the plugin needs no network at all.

#v(6pt)

*Questions and problems:* https://github.com/takwerx/plss-grid/issues
]
