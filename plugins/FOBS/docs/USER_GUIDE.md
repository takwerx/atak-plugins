# FOBS for ATAK — User Guide

**Version 0.5 · takwerx**

**Download FOBS 0.5** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/fobs/releases/download/v0.5/ATAK-Plugin-FOBS-0.5--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/fobs/releases/download/v0.5/ATAK-Plugin-FOBS-0.5--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/fobs/releases/download/v0.5/ATAK-Plugin-FOBS-0.5--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/fobs/releases

FOBS (Field Observation Survey) captures a perimeter or an area and turns it
into an ordinary ATAK drawing shape with area and perimeter. Walk it with the
phone in hand, trace it on the map, pick up a shape or a KML already there, or
pull it out of ATAK's Track History. Split what came out wrong, join the pieces
into one line, close the line into an area. Everything it makes is a normal
ATAK drawing.

---

## Before you start

- Published builds exist for ATAK-CIV 5.6, 5.7 and 5.8. Pick the download that
  matches your ATAK; a build for another version will not load.
- Sideload the APK, then open ATAK's Plugins manager and load FOBS. A boot-print
  icon appears on the toolbar.
- Nothing needs to be created first. Tap the icon and pick a tile.

![FOBS in the ATAK toolbar](screenshots/1_plugin_in_toolbar.png)

## 1. The pane

Tap the boot print. A pane opens beside the map with six tiles and a Default
Style box. The map stays live, so zoom to where you are working first. Tap the
boot print again to close it.

![The pane](screenshots/2_the_pane.png)

**Default Style** sets the color, line style and thickness every new track
starts with. Color opens ATAK's own palette. A track keeps the style it was made
with; a change here applies to the next one.

![Default Style](screenshots/2b_default_style.png)

## 2. Start a GPS track

Name it. The default is your callsign and the next track number.

![Name this track](screenshots/5_name_this_track.png)

With a TAK Server connected, FOBS asks whether to send the track live to a Data
Sync feed. **Just local** keeps it on the device. See section 8.

![Send this track live to a feed?](screenshots/6_feed_prompt.png)

Then walk. Every fix that passes the filter is added to the line: reported
accuracy better than 25 m, no impossible jumps, at least 2 m from the last one.
Standing still adds nothing. The bar shows the feed the track is going to, then
Pause and End, and a count of bad fixes if there were any: fixes refused for
poor accuracy or an impossible jump. Standing still is not counted.

![Recording](screenshots/7_recording.png)

- **Pause** when you leave the line to look at something. Nothing is recorded
  until Resume, and the gap is drawn straight.
- **End** shows the count and length and asks: Make polygon, Single line, or
  Cancel to keep recording.

![Paused](screenshots/8_paused.png)

![Track finished](screenshots/9_track_finished.png)

**Make polygon** closes the line back to its start and fills it. Area and
perimeter are shown in ATAK's own units, the ones set in ATAK's preferences, and
again any time in the shape's details. **Single line** keeps it open, to be
joined to other legs later.

![The polygon with its area](screenshots/10_polygon_area.png)

## 3. Draw a track

Two ways: **Drop points** or **Freehand**.

![Draw a track](screenshots/11_draw_how.png)

**Drop points**: tap along a road, a ridge, the edge of a field. Undo takes the
last point back. The first point wears a green ring; tapping it closes the line
into an area on the spot. End asks the same question a walk does.

![Dropping points](screenshots/12_drop_points.png)

![Fifteen points made into an area](screenshots/12c_drawn_polygon.png)

**Freehand** opens ATAK's own telestration tool in your color. Draw the stroke
with a finger; the map does not move while the tool is active. Tap End on the
telestration bar and the stroke becomes a FOBS track.

![Freehand](screenshots/13_freehand.png)

## 4. Select element

Turns a shape already on the map into a FOBS track: a line or polygon from an
imported KML, a freeform line or polygon, a route, a rectangle, a circle, a
telestration. Tap the tile, then tap the shape, then name it. A closed shape
becomes an area at once; an open line asks line or polygon.

![Tap the element to use as a track](screenshots/14_select_element.png)

![A KML boundary, in blue](screenshots/14c_kml_before.png)

![The same boundary as a FOBS area; both show when you tap the spot](screenshots/14d_selected_area.png)

## 5. Import a track

Import opens ATAK's own Track History, which knows every track this device has
logged and, with a server connected, every user's tracks on the server. It opens
on the current active track; the list icon shows all of them, the search icon
asks the server.

![Track Details](screenshots/16_track_history.png)

![Track Search](screenshots/16b_track_search.png)

Tick **View** on a track and it is drawn on the map. Hide Temp hides short
tracks; untick it, or tick Show All, to see a short one. Close Track History
once the track is showing.

![The track list](screenshots/16c_track_list.png)

![A track ticked and drawn](screenshots/16e_track_list_show_all.png)

Tap the track's line on the map. Its radial menu has a boot print: that is the
import. Name it; line or polygon. The fixes are cleaned to the same standard as
a live walk.

![The radial with the boot print](screenshots/17_radial_boot_print.png)

![The imported track, Track History off](screenshots/17d_imported_track.png)

## 6. Split track

Split takes any line or closed shape on the map. Long press the line exactly
where you want it broken, holding still for half a second. A tiny gap comes out
where you pressed. The scissors on any line's radial menu start Split locked to
that line.

![The Split prompt](screenshots/18_split_prompt.png)

- A **line** becomes two lines. They show orange and blue, with rings at the
  gap and a pane beside the map: Delete orange, Delete blue, Keep both, or Undo
  split, which puts the original back.
- A **closed shape** opens into one line, starting and ending at the gap.

![A line split at the pressed vertex](screenshots/19_split_result.png)

The result opens in ATAK's vertex editor so the point that was wrong can be
moved right away. The map stays where you were when you pressed.

![The vertex editor after Keep both](screenshots/19b_vertex_editor.png)

![A polygon opened at the gap](screenshots/20_ring_opened.png)

## 7. Join tracks

Two ways: **Tap ends** or **Lasso**. Both take any open line on the map.

![Join tracks](screenshots/22_join_how.png)

**Tap ends:** every open line grows a marker at each end. Tap an end on one
line; it turns green. Then tap an end on another. Those ends are connected and
the two lines become one, in the first line's name and style. Undo puts the last
two back. Tap both ends of the same line and it closes into an area.

![End markers, one picked](screenshots/23_tap_ends.png)

![Joined](screenshots/24_joined.png)

![Closed into an area](screenshots/25_closed_into_area.png)

**Lasso:** ATAK's lasso opens. Drag a loop around the lines to join, and pick
Shapes when ATAK asks what to take from inside it. The lines are chained nearest
end to nearest end, starting from the longest, and closed into an area. If the
largest gap it would bridge is long, it asks first.

![The lasso drawn](screenshots/26b_lasso_drawn.png)

![ATAK's Select Items](screenshots/26c_lasso_select_items.png)

![Large gap](screenshots/28_large_gap.png)

![Three legs, one area](screenshots/27_lasso_area.png)

## 8. Live to a Data Sync feed

After naming a GPS track, with a TAK Server connected: **Just local**, **Choose
feed**, or, after the first use, **Send to** the feed you used last. The feed
list is the server's own. The first save puts the track into the feed; every
save after that, every fifteen seconds while you walk, updates it, so
subscribers see it grow. Make polygon at End and the same feed item becomes the
area.

![The feed list](screenshots/6b_feed_list.png)

![Sent to feed](screenshots/7c_sent_to_feed.png)

![What a subscriber sees while you walk](screenshots/30_subscriber_growing.png)

![And when you make the polygon](screenshots/30b_subscriber_polygon.png)

Split, join and lasso keep the feed membership on their result and take the
pieces they consumed out of the feed. Deleting a track with the radial menu does
not touch the feed. Password-protected feeds are listed but not supported in
this version. If Data Sync is also running on the recording device it may ask
once whether to publish the change; the answer is a Data Sync setting.

## 9. The manual on the device

ATAK's Settings → Tool Preferences lists FOBS with the other tools. Plugin
Documentation opens the same guide as a PDF, on the device, with no network.

![FOBS in Tool Preferences](screenshots/31_tool_preferences.png)

![Plugin Documentation](screenshots/31b_plugin_documentation.png)

![The manual open on the device](screenshots/31c_manual_on_device.png)

## Notes

- GPS is what it is. The filter removes fixes that cannot be real and leaves
  the rest; it never smooths. The track you see is the walk you took, to the
  accuracy of the phone.
- Tracks drawn or imported carry no accuracy information and are not filtered
  for speed.
- Units follow ATAK's preferences. Distances, areas and perimeters are shown
  the way the rest of ATAK shows them.
