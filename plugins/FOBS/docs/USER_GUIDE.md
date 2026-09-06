# FOBS for ATAK — User Guide

**Version 0.2 · takwerx**

**Download FOBS 0.2** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/fobs/releases/download/v0.2/ATAK-Plugin-FOBS-0.2--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/fobs/releases/download/v0.2/ATAK-Plugin-FOBS-0.2--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/fobs/releases/download/v0.2/ATAK-Plugin-FOBS-0.2--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/fobs/releases

FOBS (Field Observation Survey) captures a perimeter or an area by walking it,
tracing it, or pulling it out of a track log, and turns it into an ordinary ATAK
shape with area and perimeter. Everything it makes is a normal ATAK drawing.

---

## Before you start

- Published builds exist for ATAK-CIV 5.6, 5.7 and 5.8. Pick the download that
  matches your ATAK; a build for another version will not load.
- Sideload the APK, then open ATAK's Plugins manager and load FOBS. A boot-print
  icon appears on the toolbar.
- Nothing needs to be created first. Tap the icon and pick a tile.

## 1. The pane

Tap the boot print. A pane opens beside the map with six tiles and a Default
Style box. The map stays live.

_(screenshot: the pane)_

**Default Style** sets the color, line style and thickness every new track
starts with. Color opens ATAK's own palette.

## 2. Start a GPS track

Name it (the default is your callsign and the next track number), then it
records where you go. The toolbar shows Pause and End, and a small count of
fixes the filter rejected if there were any.

- **Pause** when you leave the line to look at something. Nothing is recorded
  until Resume; the gap is drawn straight.
- **End** asks: Make polygon, Single line, or Cancel to keep recording.

_(screenshot: recording, with the toolbar)_

If a TAK Server is connected you are also asked whether to send the track live
to a Data Sync feed. See section 8.

## 3. Draw a track

Two ways: **Drop points**, tapping along a road or ridge you can see, with
Undo, or **Freehand**, which opens ATAK's telestration tool in your color and
turns the scribble into a track when you tap Done. Tapping the green ring on a
dropped-point track's first point closes it into an area on the spot.

_(screenshot: drop points with the green ring)_

## 4. Select element

Tap a shape already on the map and it becomes a track: a telestration (one
track per stroke, or joined in order), a freeform line or polygon, a route, a
rectangle or a circle. Closed shapes become areas.

## 5. Import a track

Opens ATAK's Track History. Browse, search the server, and turn on the track
you want. Then tap that track on the map: its radial menu has a boot print
that imports it as a FOBS track, cleaned to the same standard as a live walk.

_(screenshot: the radial with the boot print on a Track History line)_

## 6. Split track

Long press any line or closed shape exactly where you want it broken, holding
still for half a second. A tiny gap comes out where you pressed.

- A **line** becomes two lines. They show orange and blue with a small pane:
  Delete orange, Delete blue, or Keep both.
- A **closed shape** opens into one line, starting and ending at the gap.

The result opens in ATAK's vertex editor so you can adjust it right away. The
scissors on any line's radial menu start Split locked to that line.

_(screenshot: a split with orange and blue halves)_

## 7. Join tracks

**Tap ends:** every track grows a marker at each end. Tap an end on one line,
then an end on another; those ends are connected and the two lines become one.
Tap both ends of the same line to close it into an area. Undo puts the last two
back.

**Lasso:** ATAK's lasso opens. Circle two or more tracks and they are chained
nearest end to nearest end and closed into an area. If the largest gap it would
bridge is long, it asks first.

_(screenshot: join by tapping ends)_

## 8. Live to a Data Sync feed

After naming a GPS track, with a TAK Server connected: **Just local**, **Choose
feed**, or **Send to** the feed you used last. The feed list is the server's
own. While you walk, the track is re-sent every 15 seconds, so subscribers see
it grow. Make polygon at End and the same feed item becomes the area.

Split, join and lasso keep the feed membership on their result and take the
pieces they consumed out of the feed. Deleting a track with the radial does not
touch the feed. Password-protected feeds are listed but not supported in this
version.

## 9. Reading the numbers

Area and perimeter are ATAK's own, in the units set in ATAK's preferences. Tap
a shape and open its details to see them. A toast shows them when a polygon is
made.

## Notes

- GPS is what it is. The filter removes fixes that cannot be real and leaves
  the rest; it never smooths. The track you see is the walk you took, to the
  accuracy of the phone.
- Tracks drawn or imported carry no accuracy information and are not filtered
  for speed.
