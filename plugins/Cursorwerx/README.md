ATAK Plugin — Cursorwerx

**Download Cursorwerx 0.3** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/cursorwerx/releases/download/v0.3/ATAK-Plugin-Cursorwerx-0.3--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/cursorwerx/releases/download/v0.3/ATAK-Plugin-Cursorwerx-0.3--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/cursorwerx/releases/download/v0.3/ATAK-Plugin-Cursorwerx-0.3--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/cursorwerx/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/cursorwerx/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

ATAK expects a finger. Cursorwerx makes it behave for a mouse or a trackpad:
on a desktop (the TAKwerx ATAK Terminal, Android Studio's emulator, any
Android-in-a-window), on Samsung DeX, or on a Chromebook. It is working the
moment it loads; there is nothing to set up, and one thing to adjust if you
want: how far each click of the wheel zooms.

On the map:

  - The scroll wheel zooms in and out at the cursor, one step per click, paced
    so a trackpad does not fly through the zoom range. The step is a setting,
    Wheel zoom, from Fine (10% a click) to Very fast (60% a click), in the
    plugin's pane and under Settings > Tool Preferences > Cursorwerx; the
    default is Gentle, 15% a click, as in 0.1.
  - Click and drag pans the map 1:1. A click selects, as a tap does; a long
    press opens the radial menu.
  - Things ATAK lets you drag, such as a range-and-bearing end or a shape
    vertex, still drag.

In panes and menus:

  - The wheel scrolls the pane or list under the cursor, and never zooms the
    map behind it. A trackpad swipe moves a list about a page, a mouse click
    about a row.
  - A Back button on the left edge, and the Escape key, are Back.
  - Clicks land in text fields, so you can type; on an emulator whose pointer
    reaches Android as a tablet (mouse and stylus sources at once), presses
    are re-issued the way a finger would deliver them, which is what an
    EditText or a WebView needs to take focus. Esri and other web sign-in pages
    inside plugins work.
  - The emulator's second, guest-drawn pointer is hidden so there is one
    cursor on screen.

On a phone or tablet used with a finger, Cursorwerx does nothing at all.

Settings. Cursorwerx has one, Wheel zoom: how far the map zooms for one click
of the wheel.

  - Fine: 10% a click, about 7 clicks to double the scale.
  - Gentle: 15% a click, about 5 clicks. The default, and 0.1's only speed;
    right for a trackpad or a free-spinning wheel.
  - Medium: 25% a click, about 3 clicks.
  - Fast: 40% a click, about 2 clicks. Good for a notched mouse wheel.
  - Very fast: 60% a click, about 1.5 clicks.

  Two places set the same value, and a change applies at the next click, no
  restart: the Wheel zoom button in the plugin's pane (tap the Cursorwerx icon
  in the toolbar), or ATAK's Settings > Tool Preferences > Specific Tool
  Preferences > Cursorwerx > Wheel zoom. The choice is kept in ATAK's own
  settings, so it survives restarts, plugin updates and ATAK updates.

Updates. Each release is built for each ATAK version (5.6, 5.7, 5.8); install
the one that matches your ATAK.

  - With the TAKWERX Market plugin, which the TAKwerx ATAK Terminal installs:
    open the Market from ATAK's toolbar overflow; when a newer Cursorwerx is out
    it shows as an update, one tap. The Market picks the build for your ATAK.
  - Without the Market: download the APK for your ATAK from the Releases page
    (the links at the top of this page), install it over the old one, and load
    it in ATAK's Plugins manager if ATAK asks. In the TAKwerx ATAK Terminal,
    `takwerx plugin FILE.apk` does all of that and restarts ATAK.
  - After ATAK itself moves to a new version (for example 5.7 to 5.8), the
    build for the old ATAK no longer loads. Open the Market and install
    Cursorwerx again: it offers the build for the new ATAK. Your Wheel zoom
    choice is kept.

_________________________________________________________________
STATUS

Version 0.3: a trackpad scrolls lists at a usable pace. On Android 15 in the
TAKwerx ATAK Terminal one two-finger swipe ran ATAK's Tools list from end to
end, so anything in the middle of it, Plugins and the plugins' own tools, only
flashed past. A swipe now moves a list about a page and a mouse click about a
row, and the map stays still while the cursor is over a list. Verified on
ATAK-CIV 5.8.0.3 in Google's Android Emulator 37.1.11 on Android 15, debug and
release builds, with wheel input through the emulator's own pointer device.
ATAK's Settings pages, a separate screen, still scroll at Android's own pace.

Version 0.2: the wheel zoom step is a setting (Fine to Very fast), in the
plugin's pane and under Tool Preferences. Verified on ATAK-CIV 5.8.0.3 in the
TAKwerx ATAK Terminal on a Mac (debug and release builds) and on official
ATAK-CIV 5.7 in the TAKwerx ATAK Terminal on Windows (the tak.gov-signed build).

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
