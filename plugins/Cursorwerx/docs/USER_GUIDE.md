# Cursorwerx for ATAK — User Guide

**Version 0.1 · takwerx**

**Download Cursorwerx 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/cursorwerx/releases/download/v0.1/ATAK-Plugin-Cursorwerx-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/cursorwerx/releases/download/v0.1/ATAK-Plugin-Cursorwerx-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/cursorwerx/releases/download/v0.1/ATAK-Plugin-Cursorwerx-0.1--5.8.0-civ-release.apk

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
- There is nothing to configure. Once the plugin is loaded it is working. The
  plugin's own pane, opened from its toolbar icon, only explains what it does.
- If you run ATAK on the TAKwerx ATAK Terminal, the Market inside ATAK installs
  and updates Cursorwerx for you.

## 1. On the map

- **Wheel zooms at the cursor**, one gentle step per click. A trackpad's
  two-finger scroll is paced by how far your fingers travel, so it does not fly
  through the zoom range.
- **Click and drag pans the map** 1:1, the way dragging a finger does.
- **A click selects**, as a tap does. Hold the button for the radial menu.
- Anything ATAK lets you drag with a finger, such as the end of a
  range-and-bearing line or a shape's vertex, **still drags** with the mouse.

## 2. In panes and menus

- **The wheel scrolls the pane or list under the cursor**, and never zooms the
  map behind it. That includes the toolbar overflow, Overlay Manager, the
  settings pages and every plugin pane.
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

## 4. Nothing to undo

Unload the plugin in ATAK's Plugins manager and ATAK is exactly as it was.
Cursorwerx keeps no settings and writes nothing to disk.

## Getting help

Bugs and requests: https://github.com/takwerx/cursorwerx/issues
