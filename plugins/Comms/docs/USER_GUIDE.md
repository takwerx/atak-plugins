# Comms — user guide

**Download Comms 0.4** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/comms/releases/download/v0.4/ATAK-Plugin-Comms-0.4--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/comms/releases/download/v0.4/ATAK-Plugin-Comms-0.4--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/comms/releases/download/v0.4/ATAK-Plugin-Comms-0.4--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/comms/releases

**Version 0.3** — screenshots follow once a signed build exists.

## Before you start

Published builds exist for ATAK-CIV 5.6, 5.7 and 5.8. The reach check needs
elevation data on the device; Map Depot's DTED packs provide it.

## What it does

Radio sites on the map, nearest first. Tap a site for the nets on it and the tone
that opens each repeater. Search for a net ("Command 5", "CDF C5" or "cdf 5"), a
site ("Santiago") or a tone ("tone 8") and the list shows the nearest site
carrying it, with that net's line under the site name.

## The pane

- **Find** matches site names, net names and designators, tones (Hz or
  California tone number) and callsigns, and is loose about how a net is typed.
  Clear is live while a search is filtering the list.
- **Where** picks the state, whether distances are measured from you or from the
  map center, and a radius.
- **Show** is one checkbox per agency, with the number of sites it would show.
- **Draw on the map** sets the zoom at which sites are drawn, quoted as what the
  scale bar reads.
- **Reach from here** turns the check on, and sets how high your antenna is and
  how far out to look. **Only likely** hides everything the check does not like.

## Which repeaters can I reach from here

**From me ON** works out, for every site within **Range**, whether a handheld
where you are standing is likely to open it. It walks the terrain between you and
the antenna over the elevation data on the device, scores the worst ridge in the
way for the loss a signal takes bending over it, and weighs that against what a
handheld into a mountaintop repeater can afford. **Height** says how high your
antenna is, from handheld to tower; you can also measure from the map center
instead of from yourself.

Sites come back **likely**, **marginal** or **unlikely**. A likely site lights
its symbol green on the map and heads the list, with the others under their own
headings and anything past the range listed as not checked. **Only likely** puts
everything else away, map and list together, when you just want the ones worth
trying. With a search running, say "Command 1", the top row is the nearest
Command 1 you are likely to reach. It follows you as you move, and the status
line counts the sites off while it works.

> **This is a guide, not a prediction.** It is bare earth: it knows nothing about
> trees, buildings, vehicles or weather, it assumes one ridge rather than a range
> of them, and it assumes a handheld rather than the radio in your hand. A site it
> calls unlikely is still worth a try, and a site it calls likely can still fail.
> Confirm on the radio.

There is no coverage picture drawn on the map, and that is deliberate. Drawn from
a repeater, a viewshed reads as coverage and is not one. Drawn from you, ATAK's
own viewshed changed its answer about the same five-mile mountain depending on how
far out it had been asked to reach, because it samples on a fixed grid however
large an area it covers. The answer above is worked out per site and does not move
with a display setting.
