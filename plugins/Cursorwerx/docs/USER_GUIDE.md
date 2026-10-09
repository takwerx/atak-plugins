# Cursorwerx for ATAK — User Guide

**Version 0.3 · takwerx**

**Download Cursorwerx 0.3** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/cursorwerx/releases/download/v0.3/ATAK-Plugin-Cursorwerx-0.3--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/cursorwerx/releases/download/v0.3/ATAK-Plugin-Cursorwerx-0.3--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/cursorwerx/releases/download/v0.3/ATAK-Plugin-Cursorwerx-0.3--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/cursorwerx/releases

Cursorwerx makes ATAK behave for a mouse or a trackpad. ATAK is built for a
finger; on a desktop, on Samsung DeX or on a Chromebook the wheel zooms the map
when you meant to scroll a list, clicks miss text fields, and there is no Back
button. Cursorwerx fixes those, and does nothing at all on a phone or tablet
used with a finger.

---

## Before you start

- Published builds exist for ATAK-CIV 5.6, 5.7 and 5.8. Install the one that
  matches your ATAK.
- There is nothing you have to configure. Once the plugin is loaded it is
  working. The plugin's own pane, opened from its toolbar icon, explains what it
  does and holds its one setting, Wheel zoom (section 4).
- If you run ATAK on the TAKwerx ATAK Terminal, the Market inside ATAK installs
  and updates Cursorwerx for you.

## 1. On the map

- **Wheel zooms at the cursor**, one step per click; how big a step is up to
  you (section 4). A trackpad's two-finger scroll is paced by how far your
  fingers travel, so it does not fly through the zoom range.
- **Click and drag pans the map** 1:1, the way dragging a finger does.
- **A click selects**, as a tap does. Hold the button for the radial menu.
- Anything ATAK lets you drag with a finger, such as the end of a
  range-and-bearing line or a shape's vertex, **still drags** with the mouse.

## 2. In panes and menus

- **The wheel scrolls the pane or list under the cursor**, and never zooms the
  map behind it. That includes the toolbar overflow, Overlay Manager, the
  settings pages and every plugin pane. On ATAK's main screen a trackpad
  swipe moves a list about a page, and a mouse click about a row.
- **Back:** the ‹ button on the left edge of the screen, or the Escape key on
  the keyboard, closes what a phone's Back button would close.
- **Text fields take the click.** Click into any field, in ATAK's own settings
  or in a plugin pane, and type. Web pages inside plugin panes, such as an
  Esri sign-in, take clicks too.

## 3. On an emulator

On Google's Android Emulator, and in the TAKwerx ATAK Terminal built on it, the
host pointer reaches Android as a tablet pen and the guest draws a second
pointer of its own. Cursorwerx delivers those presses the way a finger would,
and hides the guest's pointer, so there is one cursor on screen and every
control responds.

## 4. Wheel zoom: how far one click zooms

One click of the wheel zooms the map by a fixed step. On a trackpad or a
free-spinning wheel the default, Gentle, feels right; on a notched mouse wheel it
can take five clicks to double the scale. Pick the step that suits your mouse:

| Setting | One click zooms | Clicks to double the scale |
|---|---|---|
| Fine | 10% | about 7 |
| Gentle (default) | 15% | about 5 |
| Medium | 25% | about 3 |
| Fast | 40% | about 2 |
| Very fast | 60% | about 1.5 |

Two places set the same value, and a change applies at the next click:

- **The plugin's pane**: tap the Cursorwerx icon in the toolbar, then the
  **Wheel zoom** button under Settings.
- **ATAK's settings**: Settings, **Tool Preferences**, **Cursorwerx**, **Wheel
  zoom**.

## 5. Updating Cursorwerx

Each Cursorwerx release is built once for every ATAK version it supports (5.6,
5.7, 5.8). The build must match your ATAK.

- **With the TAKWERX Market** (the TAKwerx ATAK Terminal installs it for you):
  open the Market from ATAK's toolbar overflow. A newer Cursorwerx shows as an
  update; tap it. The Market chooses the build for your ATAK.
- **Without the Market:** download the APK for your ATAK from the links at the top
  of this guide, install it over the old one, and load it in ATAK's Plugins
  manager if ATAK asks. In the TAKwerx ATAK Terminal, `takwerx plugin FILE.apk`
  installs it, switches it on and restarts ATAK.
- **After updating ATAK itself** (say 5.7 to 5.8), the build for the old ATAK no
  longer loads. Open the Market and install Cursorwerx again; it offers the build
  for your new ATAK.

Your Wheel zoom choice lives in ATAK's own settings, so it survives every one of
these.

## 6. Nothing to undo

Unload the plugin in ATAK's Plugins manager and ATAK is exactly as it was.
Cursorwerx writes nothing to disk; its one setting is kept in ATAK's own
preferences, where it waits for the plugin to come back.

## Getting help

Bugs and requests: https://github.com/takwerx/cursorwerx/issues
