#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "Cam Depot",
   plugin-version: "1.7",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

Cam Depot puts public traffic and wildfire cameras on the ATAK map. Roughly
43,000 of them, across 55 states and provinces, from state departments of
transportation, the FAA, and the wildfire lookout networks.

You pick an area, filter down to what you want, and bring it in. Tap a camera
for its latest picture; where the agency publishes a stream, play it as live
video inside ATAK.

Nothing here needs an account or a key. The cameras are public; Cam Depot finds
them, keeps them current, and puts them where you are looking.

#v(6pt)
#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("1.png", width: 100%)
][
  Open it from the ATAK toolbar. The icon sits with the other tools; tapping it
  again closes the panel, so it is one tap on and one tap off when you want the
  map to yourself.

  The first thing it does is load the catalog, then the cameras for the state it
  is showing. A line at the top of the panel says so while it works.
]
]

#tak-slide[
= The panel

The panel is the control. The map shows exactly what the panel selects, never
the whole catalog.

#v(6pt)
#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("30.png", width: 100%)
][
  The main screen is what you use in the field.

  *Cameras ON / OFF* is the map's switch. *Settings* opens everything that is
  set once and left. *Favorites* pins the cameras you starred.

  *Suggest Cameras* finds the cameras that cover a spot. See _Suggest Cameras_.

  *Search* finds a camera by name, in every state at once. *On screen only*
  narrows the list to the map view. *Turn off N bearings* puts every bearing
  line away.

  The status line says how many cameras are up and matching, and whether the
  map is drawing everything.
]
]

#tak-slide[
= Settings

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("31.png", width: 100%)
][
  #image("31b.png", width: 100%)
][
  Every row reads its own value while it is closed, so the page is a summary of
  how the panel is set. Tap a row to open it.

  *Sync Cameras*, last, re-reads the catalog and reloads the state without
  restarting ATAK. Use it when a camera you expect is missing, or when video
  that worked yesterday does not connect today.
]
]

#tak-slide[
== Where

#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("32.png", width: 100%)
][
  *State* picks the state or province, 54 of them, and *Provider* narrows to
  one agency within it. *County* takes as many counties as the job covers.

  #v(6pt)
  #toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
    #image("3.png", width: 100%)
  ][
    #image("4.png", width: 100%)
  ][
    #image("5.png", width: 100%)
  ]

  #v(4pt)
  Each agency says how many cameras it has *before* you pick it. Counties come
  from Census boundaries, so they work on every camera in the United States.
]
]

#tak-slide[
== Show, Distance

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("33.png", width: 100%)
][
  #image("34.png", width: 100%)
][
  *Video*, *Still* and *Fire* each carry their own count. Tick more to show
  more; none ticked means everything.

  #v(6pt)
  The distance slider runs to 50 in ATAK's units. *Measuring from* rotates
  between your own position and the map center. *Use this extent* takes the
  radius from what is on screen. *Presets* names the one in force.
]
]

#tak-slide[
== Suggest reach, Zoom gate

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("35.png", width: 100%)
][
  #image("36.png", width: 100%)
][
  *Suggest reach* is how far out a camera is trusted to see a point. 10 miles
  to start with; the picker offers 2, 5, 10, 20 and 40 miles, or the bearing
  line's length.

  #v(6pt)
  *Zoom gate*: cameras draw only when you are zoomed in past a threshold you
  choose. *Use this zoom* makes the current view the threshold; *Presets*
  offers a few starting points in plain terms.
]
]

#tak-slide[
= Suggest Cameras

You have a point on the map and a tower with five cameras on it. Which one is
looking that way? Suggest Cameras answers it in one tap instead of turning five
bearings on and off.

#v(6pt)
#toolbox.side-by-side(columns: (4fr, 8fr))[
  #image("37.png", width: 100%)
][
  Tap *Suggest Cameras*, then tap the point on the map. A cyan marker lands
  where you tapped, and the list narrows to the cameras that cover it.

  *Clear Suggestion* takes the marker off the map and gives the full list
  back. The point, the marker and the bearing lines all come back after
  restarting ATAK.

  #v(6pt)
  #image("38.png", width: 100%)
]
]

#tak-slide[
== Three groups, nearest first

#toolbox.side-by-side(columns: (4fr, 4fr, 4fr))[
  #image("40.png", width: 100%)
][
  #image("40b.png", width: 100%)
][
  *Suggested* are cameras whose reported aim covers the point: inside their lens
and within 30 degrees of its center. Each row says how far the camera is from
the point and how far off center the point sits, so you can judge the edge
cases yourself.

*Could be turned to it* are pan-tilt-zoom cameras within reach that are aimed
somewhere else; someone at the agency can swing them. *No bearing reported* are
cameras within reach that never say where they look, most highway cameras among
them.

  The map keeps every camera; only the list narrows. A camera's aim is as of
  the last catalog refresh. Tap a row to see its picture; that is the final
  word on what it sees.
]
]

#tak-slide[
== Bearings in one tap, and a point you can move

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("41.png", width: 100%)

  #v(4pt)
  *N Bearings OFF* turns on the bearing line of every suggested camera at
  once and reads *N Bearings ON* in green. Tap it again to take them down.
  Lines you turned on by hand on other cameras are left alone.
][
  #image("42.png", width: 100%)

  #v(4pt)
  Press the marker and drag it. Where it lands, the counts recompute, and if
  the bearings were on they move with it: off the cameras that dropped out, on
  to the ones that came in.
]
]

#tak-slide[
= Find a camera by name

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("7.png", width: 76%)
][
  Typing in the search box searches *every state at once*, not only the one in
  the dropdown. Here "pitts" finds a wildfire lookout in California and a
  highway camera in Kansas together, 1,300 miles apart.

  Camera names are the agency's own -- "I-40/75 \@ West Hills", "Keller
  Peak 1" -- so a road number, a landmark or a peak name will usually find it.

  *Clear* sits beside the box and lights up only while something is in it, so a
  live Clear button is the panel telling you a search is still filtering the
  list.
]
]

#tak-slide[
== Reading a row

Each row is one camera. The colored dot is what kind it is -- orange for a
wildfire lookout, blue for a department of transportation or FAA camera -- and
the legend above the list says so.

Under the name: the state, the agency, the county, and how far away it is.
A small #sym.triangle.filled.r *live* marks a camera that streams.

*Go to* moves the map to that camera and zooms in close enough to see the road
it is watching. Tapping the row itself opens the camera.

Below the list the panel says how many cameras matched, how many are drawn on
the map, and, if the map is not showing all of them, why. A view that is trimmed
says so rather than looking complete.
]

#tak-slide[
= Favorites

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("12.png", width: 100%)
][
  The star at the end of each row marks a camera as a favorite. Favorites are
  kept on the device and survive restarting ATAK.

  The same star appears in the camera's own pane, so you can mark one while you
  are looking at it.
]
]

#tak-slide[
== Favorites First

Tapping *Favorites* turns the star gold and pins them in their own section,
with the whole state still below. Favorites are not tied to a state: a lookout
in California and a highway camera in Ontario sit in the same list.

#v(6pt)
*While Favorites First is on, a favorite ignores every filter.* State, county,
radius and the Show boxes all pass it by, and it stays on the map whatever the
panel says -- which is the point: a camera you starred does not vanish because of
a setting you left on an hour ago. The status line says so. Turn Favorites First
off and they become ordinary cameras again, filtered like everything else.
]

#tak-slide[
= Opening a camera

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("13.png", width: 100%)
][
  Tapping a camera, in the list or on the map, opens it in a side pane so the
  map stays live behind it.

  The pane names the camera, then says the agency, whether it is online, whether
  it steers, and the county -- then shows the latest picture. *Refresh* fetches
  a new frame; while one is on its way the pane says
  "Fetching the latest picture#sym.dots.h" rather than sitting blank, because a
  slow agency and a broken one look identical otherwise.

  Pictures are current to within about a minute on most networks. Some agencies
  publish on their own cycle: British Columbia refreshes its whole province
  every few minutes, so a picture there can be a few minutes old even when
  everything is working.

  A few cameras publish live video and no picture at all -- all of Maryland
  works this way. Those say so, and point you at the video button.
]
]

#tak-slide[
= Live video

Where the agency publishes a stream, *Live video* plays it in ATAK's own player.

Not every camera has one. A camera that only publishes pictures says
"Video (still only)" rather than offering a button that cannot work. Of the
43,000 cameras in the catalog, about 16,500 stream.

Streams are checked before they are published, so a camera that offers video was
serving video when the catalog was built. Agencies do have outages; when one
does, its cameras keep their video button rather than silently losing it, and
they come back when the agency does.

#v(6pt)
#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("15.jpg", width: 100%)
][
  ATAK's own player, with its snapshot and record controls. The overlay across
  the picture is the agency's, not ours.

  Closing the video returns you to the map. The toolbar icon reopens the panel.
]
]

#tak-slide[
= Two kinds of camera on the map

The map itself says which cameras stream and which only take pictures, so you
can tell before you open one.

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("21.jpg", width: 92%)

  *Streams.* ATAK's own camcorder.
][
  #image("22.jpg", width: 92%)

  *Stills only.* A plain camera.
]

#v(6pt)
The list says the same thing a second way: a streaming camera carries a small
#sym.triangle.filled.r *live* under its name, and its pane offers
*#sym.triangle.filled.r Live video* where a stills camera offers
*Video (still only)*.
]

#tak-slide[
= Bearings

Cameras that report which way they are pointing can draw a bearing line on the
map, labeled with its azimuth. *Show bearing* turns it on, and the marker turns
orange while it is on. On a steerable camera the line follows the camera as it
moves, updating every few seconds.

#v(6pt)
#image("16.jpg", width: 74%)

#v(4pt)
The pane and the map give the same number: *Bearing 355°T* beside the camera's
name, `355°T` on the line itself.

#v(6pt)
A bearing stays on when you pan away from its camera and comes back with it, so
looking at something else does not put it away.

#v(4pt)
#image("16b.png", width: 74%)

#v(4pt)
*Turn off N bearings*, beside _On screen only_ on the main screen, puts every
line away at once. It carries the count and is greyed out when there are none.
Suggest Cameras has its own switch for the suggested cameras' lines.
]

#tak-slide[
== What the line is, and is not

The line is the *direction* the camera points. It is not how wide it sees.

A camera's pane reports both -- *Bearing 355°T   Field of View 62.8°* -- but only
the bearing is drawn. A field of view is a wedge, and sixteen hundred overlapping
wedges is a map you cannot read, so Cam Depot draws the direction and leaves the
width as a number. Suggest Cameras is where the width is used.

#v(6pt)
Opening a stills camera that reports a bearing shows its line automatically for
as long as the picture is open, then puts it away again. That answers "what am I
looking at" at the moment you are asking it.

A camera that publishes no bearing says "No bearing reported" rather than
drawing a line that would be a guess.

#v(6pt)
One thing worth knowing: on the wildfire networks the bearing is ahead of the
picture. The agency reports where a camera is pointing immediately and publishes
its image twenty to thirty seconds later. The line is the truth; the picture is
the recent past.
]

#tak-slide[
= Drawing on the map

#toolbox.side-by-side(columns: (7fr, 5fr))[
  #image("17.jpg", width: 100%)
][
  Past the zoom gate, the cameras draw. If more match than can be drawn
  legibly, the status line says how many were left off and to zoom in. It
  will not quietly show you part of the picture.

  *Cameras OFF* takes every camera off the map and changes nothing else: the
  filters, the list and any bearings you asked for stay as they are, so ON
  puts back exactly what was there. The switch is remembered across restarts.
]
]

#tak-slide[
= Where the cameras come from

Cam Depot does not talk to twenty agencies from your device. One service
collects from them, checks what it collected, and publishes a single catalog
that every device reads. That keeps a tablet off the hook for a six-megabyte
download, and keeps one polite poller in front of each agency instead of
hundreds.

Camera pictures and video are fetched straight from the agency by your device.
They do not travel through anything of ours.

The catalog covers the wildfire lookout networks, the FAA weather cameras, and
the departments of transportation of most states and several Canadian
provinces. Coverage grows as agencies are added.
]

#tak-slide[
= This guide, on the device

#toolbox.side-by-side(columns: (6fr, 6fr))[
  #image("19.png", width: 100%)

  #v(4pt)
  ATAK's *Settings* #sym.arrow.r *Tool Preferences* lists Cam Depot with the
  other tools.
][
  #image("19a.png", width: 100%)

  #v(4pt)
  *Plugin Documentation* opens this guide, so it is on the device with you and
  needs no network.
]
]

#tak-slide[
= What it needs

- *Network* -- outbound HTTPS on port 443 for the catalog, and whatever port the
  agency serves its own pictures and video on. No inbound ports, and no TAK
  server involvement.

- *Nothing else* -- no account, no key, no configuration. It does not read or
  transmit position, callsign, or any other ATAK data. Favorites and your filter
  settings are kept on the device.
]
