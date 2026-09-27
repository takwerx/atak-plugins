ATAK Plugin — Cursorwerx

**Download Cursorwerx 0.1** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/cursorwerx/releases/download/v0.1/ATAK-Plugin-Cursorwerx-0.1--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/cursorwerx/releases/download/v0.1/ATAK-Plugin-Cursorwerx-0.1--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/cursorwerx/releases/download/v0.1/ATAK-Plugin-Cursorwerx-0.1--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/cursorwerx/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/cursorwerx/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

ATAK expects a finger. Cursorwerx makes it behave for a mouse or a trackpad:
on a desktop (the TAKwerx ATAK Terminal, Android Studio's emulator, any
Android-in-a-window), on Samsung DeX, or on a Chromebook. It is working the
moment it loads; there is nothing to set up.

On the map:

  - The scroll wheel zooms in and out at the cursor, one gentle step per click,
    paced so a trackpad does not fly through the zoom range.
  - Click and drag pans the map 1:1. A click selects, as a tap does; a long
    press opens the radial menu.
  - Things ATAK lets you drag, such as a range-and-bearing end or a shape
    vertex, still drag.

In panes and menus:

  - The wheel scrolls the pane or list under the cursor, and never zooms the
    map behind it.
  - A Back button on the left edge, and the Escape key, are Back.
  - Clicks land in text fields, so you can type; on an emulator whose pointer
    reaches Android as a tablet (mouse and stylus sources at once), presses
    are re-issued the way a finger would deliver them, which is what an
    EditText or a WebView needs to take focus. Esri and other web sign-in pages
    inside plugins work.
  - The emulator's second, guest-drawn pointer is hidden so there is one
    cursor on screen.

On a phone or tablet used with a finger, Cursorwerx does nothing at all.

_________________________________________________________________
STATUS

Version 0.1. Verified on ATAK-CIV 5.8.0.3 and 5.8.0.5 in the TAKwerx ATAK
Terminal (Google's Android Emulator, Android 14, on Apple Silicon Macs): mouse
and trackpad, wheel zoom and pane scrolling, click and drag, text entry in
ATAK's own preferences and in plugin panes, web sign-in inside a plugin pane.
The release build (civRelease) is the build tested.

Prepared for tak.gov third-party submission.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/cursorwerx/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  None. The plugin makes no network calls, opens no sockets, and neither
  generates nor consumes CoT. It only handles input events inside ATAK's
  process.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device or Android environment supported by ATAK-CIV 5.6, 5.7 or 5.8,
  with a mouse or trackpad: a desktop emulator, Samsung DeX, or a Chromebook.

_________________________________________________________________
EQUIPMENT SUPPORTED

  Any mouse, trackpad or trackball Android sees as a pointer. No additional or
  external hardware beyond that, no sensors, no peripherals.

_________________________________________________________________
COMPILATION

  Standard ATAK plugin build. Set sdk.path in local.properties to an unpacked
  ATAK CIV SDK, then:

      ./gradlew assembleCivDebug
      ./gradlew assembleCivRelease

  ext.ATAK_VERSION in app/build.gradle selects the ATAK release to target.

  tools/make_icon.py renders the toolbar and launcher icons from
  docs/icon-source/; it is a build-time tool and not part of the plugin.

_________________________________________________________________
DEVELOPER NOTES

  The plugin wraps the ATAK activity's Window.Callback so it sees every input
  event before ATAK does (ClickRepair), and installs a touch and generic-motion
  router on the map view (PointerRouter, PanControl). Wheel events over a solid
  view are handed to that view's nearest scrollable ancestor and vetoed for the
  map; over the map they become paced zoom steps at the cursor. Mouse presses
  on the map are replayed as touch so ATAK's own gesture handling pans and
  selects; presses that ATAK would treat as a drag of a map item are left to
  ATAK. Everything is restored on unload.

  Debug builds log each routed event under the tag Cursorwerx; release builds
  log nothing.

LICENSE

Copyright (C) 2026 Andreas Johansson (TAKWERX).

Cursorwerx is free software, licensed under the
**[GNU Affero General Public License v3.0 or later](LICENSE)**
(AGPL-3.0-or-later), with an
**[additional permission for the TAK Software](LICENSE-EXCEPTION.md)** so that
this plugin may be built against the TAK SDK, loaded into ATAK and distributed
without the AGPL reaching into ATAK itself.

You may run it, study it, modify it, and share it -- for any purpose, commercial
or not, with no fee and no per-seat license. What the AGPL adds over a permissive
license is a guarantee that it **stays** free: modify Cursorwerx and pass it on,
and the people you pass it to are owed the complete corresponding source of your
version under the same license. Nobody can take this, close it, and sell it back
to the emergency-services community.

**If you only install and use Cursorwerx, this obligation never touches you.**
Running it, in any agency, on any number of devices, triggers nothing.

**Scope.** The AGPL covers Cursorwerx's own code. It does not change the license
of the TAK Software, which stays under the TAK Software License Agreement, and it
does not cover the parts of this repository scaffolded from the TAK-SDK plugin
template -- those are listed under Provenance in
[LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). No SDK binary is distributed here.

Contributions are welcome -- see [CONTRIBUTING.md](CONTRIBUTING.md) for the
contribution terms and the [Contributor License Agreement](CLA.md).
