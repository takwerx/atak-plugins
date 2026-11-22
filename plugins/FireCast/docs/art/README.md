# Icon art

Drop icon art for Fire Cast in this folder. Any format, any size, no preparation
needed — it gets turned into the two files the plugin actually ships.

Nothing here reaches the APK or the tak.gov submission: the submission zip carries
`docs/user_manual/` and nothing else under `docs/`, and the zip gate checks that.

## Why the plugin needs two icons and not one

- `res/drawable/ic_toolbar.png` — a bare white alpha mask, glyph edge to edge at
  256. ATAK draws the toolbar button and the Tools list entry on **dark**, and it
  draws every icon in the same square, so a glyph with padding reads smaller than
  its neighbors.
- `res/drawable/ic_launcher.png` — the same glyph at about 196 of 256 on a rounded
  `#121212` tile. This is `android:icon`, which Android shows on **light**
  backgrounds: the app list, Settings, and the file browser a user opens the manual
  through. A bare white glyph is invisible there.

## What works best

A **square** composition, drawn edge to edge, with strokes heavy enough to survive
being scaled into ATAK's 40 px toolbar slot. A tall or wide glyph leaves empty sides
that no scaling can fix. White on solid black is ideal but not required — the
conversion keys the background out by luminance, which preserves antialiasing that a
hard threshold would leave jagged.

The current icon is generated, not supplied: a flame inside a detection footprint's
corner brackets, from `DrawFireCastIcon.java` in the notes repo. Supplied art
replaces it.

## How the current icon was made

`firecast_icon.png` is the art as supplied and is the source of record — it is not
edited. `firecast_icon_trimmed.png` is what actually goes into the plugin: the same
art with two of its five contour rings and its smallest signal arc removed, because
ATAK draws every toolbar icon in the same small square and five nested rings became
a tangle at 40 px. Regenerate both plugin icons with
`tools/make-firecast-icon.sh` in the notes repo.
