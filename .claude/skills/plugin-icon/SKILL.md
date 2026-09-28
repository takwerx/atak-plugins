---
name: plugin-icon
description: >
  Make or regenerate a takwerx ATAK plugin's two icons from supplied artwork.
  Invoke whenever a plugin needs an icon, an icon looks wrong on a device (too
  small in the toolbar, invisible in the file manager, clipped under the
  launcher mask, mushy at a glance), or artwork arrives for one. Also when
  scaffolding a new plugin, because the SDK template ships one placeholder glyph
  used for both files and it is wrong for both. Not for map marker icons or
  feature-layer symbology.
---

# Plugin icons

Every takwerx ATAK plugin ships **two** PNGs, and they are not the same picture
scaled twice.

| File | What it is | Where Android and ATAK draw it |
|---|---|---|
| `ic_toolbar.png` | a bare white glyph, **alpha only**, edge to edge at 256 | ATAK's toolbar, the Tools list, `ToolsPreferenceFragment.register` — always on **dark** |
| `ic_launcher.png` | the same glyph on a solid `#121212` rounded tile, glyph 180 wide | `android:icon` — the app list, Settings, and the My Files browser a user reaches the manual through, always on **light** |

The SDK template ships one white-on-transparent glyph and points both at it.
That glyph is invisible in the file manager and reads like a missing image. Keep
them separate, always.

## Do not hand-roll this

The conversion lives in `../atak-plugins-notes/tools/MakeIcon.java` and every
plugin drives it from its own `tools/make-<plugin>-icon.sh`, so an icon is
reproducible rather than something somebody did once in an image editor and
cannot redo. Read `MakeIcon.java`'s header before changing any argument — it
records why each default is what it is.

`make-ipaws-icon.sh`, `make-dozercountry-icon.sh`, `make-mast-icon.sh`,
`make-firecast-icon.sh` and `make-signaldf-icon.sh` are the worked examples.
Copy the closest one.

## The procedure

1. **Put the supplied art in `plugins/<Name>/docs/icon-source/`, unmodified.**
   It is the operator's and it is the thing every later regeneration derives
   from. Nothing edits it in place.

2. **Write `tools/make-<plugin>-icon.sh` in the notes repo.** Its header
   comment is where the reasoning goes: which cleanup pass the art needed and
   why, what dilation was chosen and what it was protecting, and which element
   is the thinnest so the next person knows what to check.

3. **Pick the cleanup pass, or none.** This is the one judgment call:

   | Source looks like | Use | Why |
   |---|---|---|
   | clean white glyph on solid black, no alpha | **nothing** | that is exactly what `MakeIcon`'s luminance keying wants; a threshold pass would eat the antialiasing on curves |
   | right shapes, blizzard of semi-transparent speckle | `CleanMask` | hard threshold; the speckle averages into gray mush at 40 px |
   | haze, a not-quite-black background, wrong polarity | `PrepArt` | remaps haze rather than thresholding it |
   | drawing carried in the **alpha** channel, opaque ink on near-transparent paper | `AlphaArt` | luminance keying sees nothing to key |

   `Despeck` keeps only the largest connected component. It is wrong for any
   icon made of several separate shapes, which is most of them.

4. **Run `MakeIcon` with the new-work arguments:**

   ```
   java -cp "$TMP" MakeIcon <source> <ic_toolbar> <ic_launcher> <check40> <dilate> 256 180
   ```

   - **`256`** — the toolbar glyph's longer side fills the whole square.
     `MakeIcon` defaults to 236 for backward compatibility with icons made
     before 2026-09-13. ATAK draws every toolbar icon in the same square, so a
     padded glyph reads smaller than its neighbors. Pass 256 for new work.
   - **`180`** — the launcher glyph's width inside the tile. The default of 204
     is too big: launchers **mask** a legacy bitmap icon, and both Samsung's
     squircle and a plain circle cut inside the tile's own rounded corners. At
     204 the bounding-box corners sit at radius ~133 on a circle of radius 128
     and clip. 180 puts them at ~118. Pass 180 for new work.
   - **`dilate`** — 0 for a bold, simple glyph. Raise it when fine detail drops
     below a pixel at 40 px. 2 holds thin strokes without merging neighbors; 4
     starts fusing adjacent shapes. Go up one step at a time and look.

5. **Look at `check40.png`. Every time.** It is the 40 px render, which is the
   size that actually matters, and it is the only thing that will tell you the
   detail has turned to mush. An icon that is beautiful at 256 and unreadable at
   40 is a failed icon.

6. **Check the launcher composited on white**, not in a dark image editor where
   a white glyph looks fine right up until a user sees it in My Files.

7. **Build after any `res/` change, before the commit.** A resource that does
   not compile reaches tak.gov otherwise.

## On a device

**Resources do not reload when the plugin does.** Installing a new APK reloads
the code but the old icon keeps being drawn — so an icon that looks unchanged on
the phone is usually not a broken icon, it is ATAK still holding the old
resources. Quit ATAK fully and reopen it, then check. A reinstall also unloads
the plugin; re-enable it in TAK Package Management or nothing appears at all.

## Artwork is ours to produce

Never ask the operator for icons, art or docs. Produce them and ask for a
verdict. If they supply artwork, use theirs — it is theirs, it lives in
`docs/icon-source/`, and the job is to make it survive 40 px, not to replace it.
If it genuinely cannot survive 40 px, say so plainly with the `check40` render
in hand and offer a simplification, rather than shipping something that reads as
a smudge on a vehicle mount.
