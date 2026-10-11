#!/usr/bin/python3
"""Derive the plugin's two icons from the source artwork.

The artwork in docs/icon-source/cursorwerx.png is the record: a white glyph on solid
black, 1254x1254 RGB with no alpha, as supplied on 2026-09-26. This script is how it
becomes the two PNGs the plugin ships. Re-run it after replacing the source; do not
hand-edit the outputs.

What it does, in the order the notes repo's HOWTO-plugin-icon-from-generated-art.md
gives:

**Key out the black.** There is no alpha channel, so brightness becomes alpha: white
ink opaque, black transparent, the grey edge pixels partial, which keeps the curves
smooth. Then the same remap the other plugins use, so faint haze goes and near-solid
ink is driven to solid while real edge antialiasing survives.

**Trim.** Crop to the ink so the generator's padding does not shrink the glyph.

**Two files.** ic_toolbar.png: the glyph spanning the full 256 with no margin, because
ATAK scales every icon into the same slot and any padding makes this one smaller than
its neighbours. ic_launcher.png: the glyph at about 196 px centred on a rounded #121212
tile, because Android shows the launcher icon on light backgrounds where a bare white
glyph is invisible.

    ./make_icon.py
"""

import os
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("needs Pillow:  python3 -m pip install --user pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGIN = os.path.dirname(HERE)
SRC = os.path.join(PLUGIN, "docs", "icon-source", "cursorwerx.png")
DRAWABLE = os.path.join(PLUGIN, "app", "src", "main", "res", "drawable")
TOOLBAR = os.path.join(DRAWABLE, "ic_toolbar.png")
LAUNCHER = os.path.join(DRAWABLE, "ic_launcher.png")

SIZE = 256
# Below FLOOR is haze and is discarded; at or above SOLID is ink and is driven to
# opaque. Between the two is real edge antialiasing and is rescaled across the range.
FLOOR = 64
SOLID = 190
TILE = (0x12, 0x12, 0x12, 255)
TILE_RADIUS = 48
LAUNCHER_GLYPH = 196


def glyph():
    im = Image.open(SRC).convert("RGBA")
    if Image.open(SRC).mode in ("RGBA", "LA"):
        alpha = im.split()[3]
    else:
        alpha = im.convert("L")  # brightness is the ink
    alpha = alpha.point(lambda v: 0 if v < FLOOR else
                        (255 if v >= SOLID else
                         int(255 * (v - FLOOR) / float(SOLID - FLOOR))))
    box = alpha.getbbox()
    if box:
        alpha = alpha.crop(box)
    white = Image.new("RGBA", alpha.size, (255, 255, 255, 255))
    white.putalpha(alpha)
    return white


def fit(im, span):
    w, h = im.size
    scale = span / float(max(w, h))
    return im.resize((max(1, int(round(w * scale))), max(1, int(round(h * scale)))),
                     Image.LANCZOS)


def report(path, out):
    hist = out.split()[3].histogram()
    ink = sum(hist[1:]) or 1
    print("wrote %s  %dx%d" % (path, out.size[0], out.size[1]))
    print("  %.0f%% of ink fully opaque   %.0f%% of canvas is haze"
          % (100.0 * hist[255] / ink, 100.0 * sum(hist[1:64]) / sum(hist)))


def main():
    if not os.path.exists(SRC):
        sys.exit("no source artwork at " + SRC)
    g = glyph()

    # Toolbar: full span, no margin.
    t = fit(g, SIZE)
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.alpha_composite(t, ((SIZE - t.size[0]) // 2, (SIZE - t.size[1]) // 2))
    out.save(TOOLBAR)
    report(TOOLBAR, out)

    # Launcher: the glyph on a rounded dark tile.
    tile = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle((0, 0, SIZE - 1, SIZE - 1), TILE_RADIUS, fill=TILE)
    l = fit(g, LAUNCHER_GLYPH)
    tile.alpha_composite(l, ((SIZE - l.size[0]) // 2, (SIZE - l.size[1]) // 2))
    tile.save(LAUNCHER)
    report(LAUNCHER, tile)

    # Stroke weight, the way HOWTO-plugin-icon.md measures it: median run of ink.
    a = out.split()[3]
    w, h = a.size
    runs = []
    for y in range(0, h, 3):
        run = 0
        for x in range(w):
            if a.getpixel((x, y)) > 140:
                run += 1
            elif run:
                runs.append(run)
                run = 0
    runs = [r for r in runs if r <= w * 0.30]
    if runs:
        runs.sort()
        print("  stroke: %d px on a 256 canvas (ATAK's own are 14-26)" % runs[len(runs) // 2])


if __name__ == "__main__":
    main()
