# Comms — user guide

**Download Comms 0.2** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/comms/releases/download/v0.2/ATAK-Plugin-Comms-0.2--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/comms/releases/download/v0.2/ATAK-Plugin-Comms-0.2--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/comms/releases/download/v0.2/ATAK-Plugin-Comms-0.2--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/comms/releases

**Version 0.2** — screenshots follow once a signed build exists.

## Before you start

Published builds exist for ATAK-CIV 5.6, 5.7 and 5.8. The line-of-sight check needs
elevation data on the device; Map Depot's DTED packs provide it.

## What it does

Radio sites on the map, nearest first. Tap a site for the nets on it, their
frequencies, tones and callsigns. Search for a net ("Command 5"), a frequency
("151.3175") or a tone ("tone 8") and the list shows the nearest site carrying
it, with that net's line under the site name.

## The pane

- **Find** matches site names, net names and designators, frequencies, tones
  (Hz or California tone number) and callsigns. Clear is live while a search is
  filtering the list.
- **Where** picks the state, whether distances are measured from you or from the
  map center, and a radius.
- **Show** is one checkbox per agency, with the number of sites it would show.
- **Draw on the map** sets the zoom at which sites are drawn, quoted as what the
  scale bar reads.
- **Viewshed from you** draws the viewshed from your own position and sets the
  height you are at and how far to reach.

## Which repeaters can I reach from here

**From me ON** in the pane's Viewshed section draws the viewshed from your own
position (or from the map center when you have chosen that), at the height you
set under **Height**, out to the Range you picked. Every site within
that range is then checked for line of sight to its antenna over the elevation
data on the device: sites you can see turn green on the map and list first
under "Line of sight from you", the rest under "No line of sight", and sites
beyond the range are listed as not checked. With a search running, say
"Command 1", the top row is the nearest Command 1 site you can see. It follows
you as you move. Optical line of sight is a good first answer on VHF and not the
last one: a ridge can be worked over, and a clear path at the edge of the range
can still be weak.
