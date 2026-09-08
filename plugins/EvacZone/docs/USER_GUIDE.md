# Evac Zone for ATAK — User Guide

**Version 0.1 · takwerx**

**Download Evac Zone 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/evac-zone/releases/download/v0.1/ATAK-Plugin-EvacZone-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/evac-zone/releases/download/v0.1/ATAK-Plugin-EvacZone-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/evac-zone/releases/download/v0.1/ATAK-Plugin-EvacZone-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/evac-zone/releases

Evac Zone draws evacuation zones on the ATAK map from the agencies that publish
them, colored by what their status means, and lets you find a zone by name and
go to it.

---

## Before you start

- Published builds exist for ATAK-CIV 5.6, 5.7 and 5.8. Install the one that
  matches your ATAK.
- The plugin needs internet access to fetch zones. Once a source has been
  fetched it keeps drawing without a connection and tells you how old it is.
- Nothing is sent from your phone: no location, no callsign.
- Screenshots for this guide will be added with the next release.

## 1. Open the pane

Tap the Evac Zone badge on the toolbar. The pane opens on the state under the
map, or the one you used last.

## 2. Pick a state

The **State** button at the top opens the list of states and provinces the
catalog covers, with how many sources each has and how many are on.

## 3. Statewide sources

Under **Statewide**, every feed that covers the whole state is listed with who
publishes it, how often it refreshes, and how many zones it holds. **ON** loads
it and draws it; the row then shows the count and how long ago it refreshed.
**Go to** frames everything it fetched. **Refresh** fetches now.

For California, Cal OES carries every county's orders and warnings and CAL FIRE
adds the reason, population and lifted zones. Oregon OEM carries the levels
(1 Be Ready, 2 Be Set, 3 Go Now).

## 4. Counties

The **Counties** button lists every county of the state. Beside each name:
how many zones the statewide feeds that are on have there, and how many
sources of its own it has. Tick one or many. Under the button, each picked
county shows its share of every statewide feed with the status counts, then
the county's own sources if it has any. The county under the map is picked for
you the first time you open a state.

Some county sources are zone boundaries only: every zone the county has
drawn, most of them Normal. Their row says so, and says that live status is in
the statewide feeds.

## 5. Reading the map

One color language, whatever a county calls it:

| Color | Meaning |
|---|---|
| Red | Evacuation Order, Level 3 Go Now, Mandatory |
| Yellow | Evacuation Warning, Level 2 Be Set, Voluntary, Alert |
| Blue | Advisory, Level 1 Be Ready |
| Purple | Shelter in Place |
| Green | Lifted, Cancelled, All Clear, Repopulation |
| Faint | Normal, no evacuation |
| White | A status the plugin does not know |

The legend under the state button totals the zones that are on. Each zone's
name is drawn at its center. Tap a zone for every attribute the agency
publishes, including the public Genasys Protect page where one exists.

## 6. Distance from a point, and draw on the map

**Distance from a point** limits what is drawn to a radius around you or
around the map center, in your ATAK units. Tap **Measuring from** to switch.
**Use this extent** takes the current view as the radius. Rows say how many of
their zones are inside.

**Draw on the map** holds the zones until you are zoomed in past a threshold.
**Use this zoom** takes the scale you are looking at; **Presets** are quoted as
scale-bar readings. The readout says when the map is outside it.

## 7. The zone list

At the bottom, **Zones on the map** lists every zone that is drawn in this
state: search by zone id, name or county; narrow by status with the counts
beside each; nearest first from the same point the radius uses, with the
distance. Tap a row for its details, **Go to** to frame it.

## 8. What is not shown

- Only zones the agencies publish. Where a county has no public layer of its
  own, only its active zones (from the statewide feed) are on the map;
  Normal zones for that county are not available.
- A row marked **STALE** kept its last fetch after a failed refresh; the
  reason is on the row.
- A layer hidden by the zoom threshold or trimmed by the radius says so on
  its row and in the status line.

## 9. If something looks wrong

- "built-in catalog" in the status line means the hosted catalog could not be
  reached; the copy inside the plugin is being used.
- A feed that is on but shows 0 zones between incidents is telling the truth;
  Los Angeles City and Ventura are empty until something is happening.
- Report a problem at https://github.com/takwerx/evac-zone/issues
