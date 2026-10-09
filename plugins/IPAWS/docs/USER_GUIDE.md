# IPAWS Alerts for ATAK — User Guide

**Version 0.5 · takwerx**

**Download IPAWS Alerts 0.5** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/ipaws-alerts/releases/download/v0.5/ATAK-Plugin-IPAWS-0.5--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/ipaws-alerts/releases/download/v0.5/ATAK-Plugin-IPAWS-0.5--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/ipaws-alerts/releases/download/v0.5/ATAK-Plugin-IPAWS-0.5--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/ipaws-alerts/releases

IPAWS Alerts puts public emergency alerts on the ATAK map, with the filter set
on your own device: your states, your counties, the alert types you care about,
how close they have to be. The alerts come from the National Weather Service —
its own warnings, watches and advisories, plus the non-weather messages it
relays, such as Evacuation Immediate and Civil Emergency Message.

Everything it draws stays on your device. Nothing is published to a server or to
anyone else's map.

---

## 1. Before you start

- Published builds exist for **ATAK-CIV 5.6, 5.7 and 5.8**. Install the one that
  matches your ATAK exactly; a build for another version will not load.
- The phone needs a network path to `api.weather.gov`. Nothing else is contacted.
- A fresh install selects the state your phone is in as soon as it has a
  position fix and a connection, and keeps trying until it does. You can always
  pick states yourself in Settings, Where.

## 2. The main screen

### Open the plugin

The IPAWS icon sits on the ATAK toolbar with your other tools. Tap it to open
the pane; tap it again to close it.

![The IPAWS icon on the ATAK toolbar](screenshots/01_toolbar.png)

### Three buttons and the list

The main screen is one row of three buttons, with the alert list right under it:

- **Alerts ON / OFF** — whether alerts are drawn on the map.
- **Settings** — everything else.
- **Notify ON / OFF** — whether new alerts notify you.

Both switches show the current state: green ON, red OFF.

![Alerts OFF: the list keeps running, the map is clear](screenshots/02_alerts_off.png)

Turn alerts on and the same alerts are drawn on the map, each area in its own
color and named in the middle.

![Alerts ON over Hawaii](screenshots/03_alerts_on.png)

## 3. Settings

**Settings** replaces the list with a page of rows. Each row names a setting and
says what it is set to, so the setup reads at a glance. Tap a row, or the arrow
at its end, to open it. **Back** returns to the list.

![The Settings page](screenshots/04_settings.png)

### Zoom gate

The zoom gate keeps the map clean when you zoom out: alerts draw only while
ATAK's scale bar reads the chosen distance or less. **Use this zoom** takes the
scale bar's current reading; the other button picks from a list.

![Zoom gate opened](screenshots/05_zoom_gate.png)

![Draw when the scale bar reads](screenshots/06_gate_picker.png)

Zoomed out past the gate, the map is empty and the top line says **"zoom in to
see alerts on the map"**.

![Zoomed out past a 5 mi gate](screenshots/07_gate_hidden.png)

### Distance

Distance narrows the list and the map to what is near. The slider all the way
left is **Everything**; anywhere else it is **Within N miles** of your location
or the map center. **Presets** also offers **What is in view**.

![Distance](screenshots/08_distance.png)

### Where

**Regions** ticks whole groups of states at once, offshore waters included;
**states** picks them one at a time; **counties** narrows a state to particular
counties.

![Where opened](screenshots/09_where.png)

![Regions](screenshots/10_regions.png)

![Counties inside California](screenshots/11_counties.png)

A county is a *touch*, not a filing: an alert shows if its area reaches a county
you picked, even a marine alert that names no county at all.

### Types and severity

The 111 alert types are grouped into thirteen categories. Switch off a whole
category — Marine, say — or narrow a kept one to particular types.

![Categories](screenshots/12_categories.png)

Severity is a filter, and it sorts the list worst first. It is not the color on
the map.

![Severity](screenshots/13_severity.png)

### Colors and the map key

Each alert type is drawn in the National Weather Service's own color for it —
the same colors every public weather map uses. **Map key** lists the colors of
the types on the map right now, and only those, so it changes as the filter and
the weather change.

![The map key beside the map](screenshots/14_map_key.png)

### Notifications

Notification settings are the last row of Settings: which **severities** notify,
**where** (in your states, within 2 to 50 miles of you, or the same as the map),
and whether **updates** to alerts already out notify again.

![Notification settings](screenshots/17_notifications.png)

**Send a test notification** shows exactly what one looks and sounds like. Tap a
notification to open the alert it is about.

![A test notification](screenshots/18_notification.png)

## 4. The alert list

Alerts are grouped by type, worst first. Tap a type to open its alerts; each
shows its areas, severity and time left.

![Storm Warning opened to its alerts](screenshots/15_group_open.png)

Tap an alert for its details: severity, urgency, areas, times and the issuing
office's own words. **Zoom to** frames it on the map.

![An alert's details](screenshots/16_details.png)

## 5. On the map

Where areas overlap, ATAK asks which alert you meant, one line each.

![Select Item over two overlapping alerts](screenshots/19_chooser.png)

Tap an area and its details open in the pane, the same page the list opens.

The overlay is in ATAK's **Overlay Manager** as "IPAWS Alerts", with one entry
per severity, so you can switch the quieter ones off without opening the plugin.

![IPAWS Alerts in the Overlay Manager](screenshots/21_overlay_manager.png)

![Expanded to its severities](screenshots/22_overlay_expanded.png)

## 6. The user manual

A full manual is built into the plugin. Open ATAK's **Settings → Tool
Preferences → Specific Tool Preferences → IPAWS Alerts**.

![IPAWS Alerts in Specific Tool Preferences](screenshots/23_tool_prefs.png)

![The user manual row](screenshots/24_manual_row.png)

## 7. The line at the top

The top line of the pane says how old the picture is — worth reading, because a
stale map looks exactly like a current one.

- **Updated N minutes ago** — current.
- **Last updated … – timed out** — the network failed; the map keeps the last
  good set.
- **N alerts still being drawn** — areas are still arriving.
- **shown at the state's center** — an alert whose area could not be found is a
  dot in the middle of its state.
- **map off**, or **zoom in to see alerts on the map**.

## 8. What it does not do

It does not publish anything; no path puts an alert on another phone. It is not
the FEMA IPAWS-OPEN aggregator, which also carries state and local originators
and needs an agreement with FEMA. It shows everything the National Weather
Service issues or relays.

Questions and problems: https://github.com/takwerx/ipaws-alerts/issues
