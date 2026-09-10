# Comms — user guide

**Download Comms 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/comms/releases/download/v0.1/ATAK-Plugin-Comms-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/comms/releases/download/v0.1/ATAK-Plugin-Comms-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/comms/releases/download/v0.1/ATAK-Plugin-Comms-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/comms/releases

**Version 0.1** — first build. Screenshots follow once a signed build exists.

## Before you start

Published builds exist for ATAK-CIV 5.6, 5.7 and 5.8. The viewshed needs
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
- **Viewshed** turns every viewshed off at once and sets the range for the next
  one.

## Viewshed

Viewshed ON in a site's details draws ATAK's viewshed from that site's antenna.
It is line of sight from the antenna, not radio coverage: terrain that can see
the antenna is painted, terrain that cannot is not. It needs elevation data on
the device. Viewsheds are removed when the plugin is unloaded.
