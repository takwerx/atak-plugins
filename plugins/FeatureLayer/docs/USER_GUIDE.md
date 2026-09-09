# Feature Layer — User Guide

**Download Feature Layer 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/feature-layer/releases/download/v0.1/ATAK-Plugin-FeatureLayer-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/feature-layer/releases/download/v0.1/ATAK-Plugin-FeatureLayer-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/feature-layer/releases/download/v0.1/ATAK-Plugin-FeatureLayer-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/feature-layer/releases

## Before you start

Builds are published for ATAK-CIV 5.6, 5.7 and 5.8. Install the one that matches
your ATAK version, then enable it in ATAK's Plugins manager. Screenshots for this
guide come with the next release.

## Pick a source

Open the plugin from the toolbar and tap **Pick a source**.

- **NIFC** needs your own NIFC ArcGIS login. Tap Sign in, log in (MFA works), then
  type part of a fire name and tap Find fire, or leave the box blank for the latest
  fires. Pick one and it loads.
- **SARCOP Training** is public. Find incident lists the training incidents.
- **CA Air Intel** is public. Tap Add perimeters.
- **Add your own org…** asks for your organization's ArcGIS address once, then works
  like NIFC: sign in, search, add a layer. Forget removes an org you added.

## Layers

Each layer has ON/OFF, **Features**, Go to, Auto (refresh interval), Refresh and
Remove. All ON/OFF beside the heading works every layer at once.

**Features** lists what the layer holds, each with its own ON/OFF and, for areas, a
fill control. At the top: Labels, Repair status halos (NIFC layers, off by default
like NIFC's own map), a zoom gate (Use this zoom, or pick a scale), and for CA Air
Intel a time window and one row per agency.

## Find

Feature opens a search inside one layer. Blank Find lists every type with a count;
tap one to list its features, or type a name or number. Sort by nearest, newest,
oldest or name; measure from yourself or from the map center. Each row says "tap
to go there".

## On the map

Tap a feature and pick it from the list. Details shows every attribute with a Back
button. The radial menu offers Bloodhound, a range and bearing line, polar
coordinates and Marker here. A bloodhound's marker removes itself when the
bloodhound stops.
