# LCES for ATAK — User Guide

**Version 0.2 · takwerx**

**Download LCES 0.2** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/lces/releases/download/v0.2/ATAK-Plugin-LCES-0.2--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/lces/releases/download/v0.2/ATAK-Plugin-LCES-0.2--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/lces/releases/download/v0.2/ATAK-Plugin-LCES-0.2--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/lces/releases

Lookouts, Communications, Escape Routes, Safety Zones. LCES puts the wildland
fire Incident Response Pocket Guide (IRPG) one tap away in ATAK, and puts the
lessons other crews learned the hard way on the map, where they happened.

---

## Before you start

- **Match the plugin to your ATAK version.** Builds are tied to the ATAK
  release they were built against. Published builds: **ATAK-CIV 5.6, 5.7 and
  5.8**.
- **It works with no signal.** The whole IRPG and the whole lessons list are
  inside the plugin. With a network, it checks once a day for a newer lessons
  list.
- **Nothing is sent from your device.** LCES creates no markers for anyone else
  and does not talk to your TAK server.

Load it from ATAK's Plugins manager (TAK Package Mgmt) like any other plugin,
then open it from the toolbar with the LCES icon:

![The LCES icon in the ATAK toolbar](screenshots/00_toolbar.png)

## The main screen

<img src="screenshots/01_main_screen.png" width="420" alt="The LCES main screen">

- **Lessons Learned ON / OFF** puts the lessons on the map or takes them off.
- **Settings:** text size, which lessons to show, and the map key.
- **Search** covers the IRPG and the lessons at once.
- **FROM THE IRPG:** the pages read every shift, one tap away.
- **Lessons near the map center:** the closest lessons first, with the distance.
  **Details** opens one.

The line at the top says how many lessons the list and the map are showing.

## The IRPG

<img src="screenshots/02_irpg_10s18s.png" width="420" alt="10 Standard Orders and 18 Watch Outs">

The tiles under **FROM THE IRPG** open the pages read every shift: the **10s &
18s**, **LCES**, the **Briefing** checklist and the **Common Denominators** of
fire behavior on tragedy fires. The buttons at the bottom of a page go to the
page before and the page after, as turning a page in the book.

<img src="screenshots/03_irpg_contents.png" width="420" alt="IRPG contents">

**IRPG** opens the contents in the book's own sections and colors. **Continue**
takes you back to the last page you read. The arrows button at the top right
makes the pane wide, for tables and diagrams.

<img src="screenshots/04_page_table.png" width="420" alt="An IRPG page with a table">

Tables and diagrams are the book's own. For larger text, set **Text size:
Large** in Settings.

## Search

<img src="screenshots/05_search.png" width="420" alt="Search results">

Type in the search box on the main screen. The list shows the IRPG pages that
match first, then the lessons, and the map shows only the lessons found.
**Clear** puts everything back.

<img src="screenshots/06_search_page.png" width="420" alt="A page opened from a search">

An IRPG result opens with the words you searched for marked in yellow.

## Lessons on the map

![Lessons across the country](screenshots/07_map_us.png)

Every lesson is a dot where it happened, in the color of its type. Zoomed out,
ATAK shows some of the names; zoom in to see them all.

![Zoomed in, with names](screenshots/08_map_labels.png)

Tap a dot to open its page.

## A lesson's page

<img src="screenshots/09_incident.png" width="420" alt="A lesson's page">

The type, date, place, distance and toll; **Show on the map**; **WHAT
HAPPENED** and **FROM THE REPORT** in the Lessons Learned Center's own words;
and the details. **Open the full report** opens the Center's report in your
browser, and needs a network:

![The full report on lessons.wildfire.gov](screenshots/09b_full_report.png)

## Settings

<img src="screenshots/10_settings.png" width="420" alt="Settings">

- **Text size:** Normal or Large, for every IRPG page.
- **Zoom gate:** hide the lessons when the map is zoomed out past a distance
  you pick.
- **Area:** everything, or only lessons within a distance of your location or
  the map center.
- **Types**, **Category** and the **Map key**, below.

<img src="screenshots/11_types.png" width="420" alt="Types">

Five types, each in its color, with how many there are. Tap one to turn it ON
or OFF. The list and the map always show the same lessons.

<img src="screenshots/12_category.png" width="420" alt="Category">

The Lessons Learned Center's own categories. **None**, then one category, shows
only that kind of lesson.

<img src="screenshots/13_map_key.png" width="420" alt="Map key">

The type is the most serious thing that happened: a fatality first, then a
burnover, an entrapment, an injury, and otherwise a near miss.

## What is not shown

<img src="screenshots/14_status_line.png" width="520" alt="The status line">

When a setting hides lessons, the line at the top of the main screen says so.
The list shows the nearest 100; the map shows every lesson the settings allow.

## Opening the manual on the device

<img src="screenshots/15_tool_prefs.png" width="520" alt="Tool Preferences">

ATAK's **Settings**, then **Tool Preferences**, **Specific Tool Preferences**,
**LCES**, and **Plugin Documentation**.

## Sources

- **IRPG:** NWCG Incident Response Pocket Guide, PMS 461, January 2025. A
  reference, not a substitute for the IRPG or your training.
- **Lessons Learned:** the Wildland Fire Lessons Learned Center's incident
  database, checked every night and updated when it changes.

Report a problem or ask for a change: https://github.com/takwerx/lces/issues
