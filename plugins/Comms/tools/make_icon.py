#!/usr/bin/env python3
"""Draw the plugin's two icons: a radio mast, edge to edge.

ic_toolbar.png is the bare white glyph for ATAK's dark toolbar, Tool Preferences
row and the site markers; its longer side spans the full 256 px with no margin,
because ATAK draws every toolbar icon in the same square and a glyph with padding
reads smaller than its neighbors. ic_launcher.png is the same glyph at about 196 px
on a #121212 rounded tile, for android:icon, which Android shows on light
backgrounds (the app list, Settings, the file browser).

    /usr/bin/python3 tools/make_icon.py      # the system Python has Pillow
"""

import math
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable")
SIZE = 256
SS = 4  # supersample


def glyph(px):
    """The mast and its waves, white on transparent, a square composition."""
    s = px * SS
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = lambda f: max(1, int(f * s))  # noqa: E731
    cx = s / 2
    tip = 0.30 * s                 # where the antenna ends and the waves radiate from
    top, base = tip + 0.10 * s, 0.98 * s
    half_top, half_base = 0.04 * s, 0.23 * s
    lw = w(0.042)

    def half_at(y):
        t = (y - top) / (base - top)
        return half_top + (half_base - half_top) * max(0.0, min(1.0, t))

    # Legs.
    d.line([(cx - half_top, top), (cx - half_base, base)], fill="white", width=lw)
    d.line([(cx + half_top, top), (cx + half_base, base)], fill="white", width=lw)
    # Rungs, and an X of bracing between each pair.
    rungs = [top + (base - top) * f for f in (0.0, 0.22, 0.46, 0.72, 1.0)]
    for y in rungs:
        d.line([(cx - half_at(y), y), (cx + half_at(y), y)], fill="white", width=w(0.03))
    for ya, yb in zip(rungs, rungs[1:]):
        d.line([(cx - half_at(ya), ya), (cx + half_at(yb), yb)], fill="white", width=w(0.03))
        d.line([(cx + half_at(ya), ya), (cx - half_at(yb), yb)], fill="white", width=w(0.03))
    # The antenna.
    d.line([(cx, tip), (cx, top)], fill="white", width=lw)
    d.ellipse([cx - w(0.035), tip - w(0.035), cx + w(0.035), tip + w(0.035)], fill="white")
    # Waves either side of the tip. PIL angles run clockwise from 3 o'clock.
    for rad in (0.16, 0.30, 0.46):
        r = rad * s
        box = [cx - r, tip - r, cx + r, tip + r]
        d.arc(box, 152, 208, fill="white", width=w(0.04))
        d.arc(box, 332, 388, fill="white", width=w(0.04))
    return im.resize((px, px), Image.LANCZOS)


def main():
    g = glyph(SIZE)
    # Trim to the ink and stretch the longer side to the full square.
    box = g.getbbox()
    g = g.crop(box)
    scale = SIZE / max(g.size)
    g = g.resize((max(1, int(g.size[0] * scale)), max(1, int(g.size[1] * scale))), Image.LANCZOS)
    toolbar = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    toolbar.alpha_composite(g, ((SIZE - g.size[0]) // 2, (SIZE - g.size[1]) // 2))
    toolbar.save(os.path.join(OUT, "ic_toolbar.png"))

    tile = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=48, fill=(0x12, 0x12, 0x12, 255))
    inner = 196
    scale = inner / max(g.size)
    small = g.resize((max(1, int(g.size[0] * scale)), max(1, int(g.size[1] * scale))), Image.LANCZOS)
    tile.alpha_composite(small, ((SIZE - small.size[0]) // 2, (SIZE - small.size[1]) // 2))
    tile.save(os.path.join(OUT, "ic_launcher.png"))

    # The map marker: ATAK draws marker icons at their pixel size, and a 256 px glyph
    # covers a county. 48 px is the size of ATAK's own point icons.
    marker = glyph(48 * 2).resize((48, 48), Image.LANCZOS)
    marker.save(os.path.join(OUT, "ic_marker.png"))
    print("wrote ic_toolbar.png (glyph %dx%d of %d), ic_launcher.png and ic_marker.png" % (g.size[0], g.size[1], SIZE))


if __name__ == "__main__":
    main()
