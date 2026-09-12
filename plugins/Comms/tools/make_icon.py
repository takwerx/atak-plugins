#!/usr/bin/env python3
"""Build the plugin's icons from the NWCG GeoOps repeater symbol.

The map marker is the standard's own 60 px "Repeater" point symbol (PMS 936,
tools/nwcg_repeater.png, from nwcg.gov), used verbatim so a repeater reads the same
here as in Feature Layer and on every GeoOps map. A site with line of sight from
the operator gets the same symbol with its white dot and arcs lit green
(ic_marker_seen.png), so the marker changes state without changing size.

The toolbar glyph and the launcher tile are the same diamond redrawn at 256 px
(the 60 px PNG would blur) with a thin white edge, so it stands out on ATAK's dark
toolbar and on the #121212 tile Android shows on light backgrounds.

    /usr/bin/python3 tools/make_icon.py      # the system Python has Pillow
"""

import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "res", "drawable")
SRC = os.path.join(HERE, "nwcg_repeater.png")
SIZE = 256
SS = 4
NAVY = (0, 0, 128, 255)
MARKER = 48
SEEN = (0x3D, 0xDC, 0x61, 255)


def outline_diamond(px, margin=0.0):
    """
    The operator's own icon, 2026-09-11: the diamond as an outline on black, with
    the standard's white dot and arcs inside it.

    <p>The NWCG symbol is a solid navy diamond, which is right on a map full of
    GeoOps symbology and heavy as an app icon -- at launcher size it reads as a
    blue blob. Drawn as a stroke the shape is still the repeater symbol and the
    marks inside it are what the eye lands on.

    <p>{@code margin} is the empty border as a fraction of the square, and the
    default of none is deliberate: ATAK draws every toolbar icon in the same
    square, so a glyph with padding reads smaller than its neighbours. The
    toolbar copy spans the full 256 edge to edge, the way PLSS's grid does, and
    the launcher glyph is sized instead by the tile it sits on.
    """
    s = px * SS
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = s / 2
    m = margin * s
    w = 0.055 * s
    # Two polygons rather than a stroked line: a stroke closes with a joint at the
    # top vertex and leaves a nick there. Filling the outer diamond and clearing an
    # inner one mitres every corner exactly.
    outer = [(c, m), (s - m, c), (c, s - m), (m, c)]
    k = w * 1.4142                       # inward offset along both axes for a 90 deg corner
    inner = [(c, m + k), (s - m - k, c), (c, s - m - k), (m + k, c)]
    d.polygon(outer, fill=(255, 255, 255, 255))
    d.polygon(inner, fill=(0, 0, 0, 0))
    # The dot and arcs keep the standard's proportions, measured off its 60 px
    # original and scaled to the space inside the stroke.
    r = 5 / 60 * s * 0.92
    d.ellipse([c - r, c - r, c + r, c + r], fill=(255, 255, 255, 255))
    ra, aw = 14.5 / 60 * s * 0.92, 6 / 60 * s * 0.92
    box = [c - ra, c - ra, c + ra, c + ra]
    d.arc(box, 140, 220, fill=(255, 255, 255, 255), width=int(aw))
    d.arc(box, 320, 400, fill=(255, 255, 255, 255), width=int(aw))
    return im.resize((px, px), Image.LANCZOS)


def diamond(px, edge=True):
    """The NWCG repeater symbol, measured off the 60 px original: navy diamond,
    white dot of radius 5/60, two white arcs of radius 14.5/60 and stroke 6/60
    spanning about 40 degrees either side of the horizontal."""
    s = px * SS
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = s / 2
    m = 0.02 * s
    pts = [(c, m), (s - m, c), (c, s - m), (m, c)]
    if edge:
        d.polygon(pts, fill=(255, 255, 255, 255))
        e = 0.035 * s
        pts_in = [(c, m + e * 1.4), (s - m - e * 1.4, c), (c, s - m - e * 1.4), (m + e * 1.4, c)]
        d.polygon(pts_in, fill=NAVY)
    else:
        d.polygon(pts, fill=NAVY)
    r = 5 / 60 * s
    d.ellipse([c - r, c - r, c + r, c + r], fill=(255, 255, 255, 255))
    ra, w = 14.5 / 60 * s, 6 / 60 * s
    box = [c - ra, c - ra, c + ra, c + ra]
    d.arc(box, 140, 220, fill=(255, 255, 255, 255), width=int(w))
    d.arc(box, 320, 400, fill=(255, 255, 255, 255), width=int(w))
    return im.resize((px, px), Image.LANCZOS)


def main():
    # The standard's file is 60 px, which Feature Layer draws as is; on a phone that
    # sits a hair larger than ATAK's own point markers, so sites draw at 48 px.
    src = Image.open(SRC).convert("RGBA").resize((MARKER, MARKER), Image.LANCZOS)
    src.save(os.path.join(OUT, "ic_marker.png"))

    # The seen variant: the same symbol with its marks lit green, no ring. The
    # operator's own call, 2026-09-11 -- "instead of a halo its the white part green
    # on the icon lets you know this is possibly a good repeater site". A ring is a
    # second object to read at marker size; a symbol that lights up is one. Only the
    # white dot and arcs move, so the diamond stays the NWCG symbol and the footprint
    # is identical whether a site is seen or not.
    seen = Image.new("RGBA", src.size, (0, 0, 0, 0))
    sp, dp = src.load(), seen.load()
    for y in range(src.size[1]):
        for x in range(src.size[0]):
            r, g, b, a = sp[x, y]
            if a == 0:
                continue
            # The source is navy and white with antialiasing between; red rises from
            # 0 in the diamond to 255 in the marks, so it measures how white a pixel
            # is. Carry each one the same distance towards the green.
            t = r / 255.0
            dp[x, y] = (round(NAVY[0] + (SEEN[0] - NAVY[0]) * t),
                        round(NAVY[1] + (SEEN[1] - NAVY[1]) * t),
                        round(NAVY[2] + (SEEN[2] - NAVY[2]) * t), a)
    size = seen.size[0]
    seen.save(os.path.join(OUT, "ic_marker_seen.png"))

    # ATAK draws plugin toolbar icons as white masks: only the alpha survives, so
    # the navy diamond came out a solid white lozenge in the Tools list. The
    # toolbar copy is therefore shaped by alpha alone: a diamond with the dot and
    # arcs punched through, which the mask renders as the symbol in white.
    # ATAK draws plugin toolbar icons as white masks: only the alpha survives. The
    # outline glyph is already white marks on nothing, so it is its own mask -- no
    # punching holes in a filled body, which is what the navy diamond needed.
    outline_diamond(SIZE).save(os.path.join(OUT, "ic_toolbar.png"))

    tile = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=48, fill=(0, 0, 0, 255))
    # 196 of 256 on the tile, the size every takwerx launcher glyph uses.
    g = outline_diamond(196)
    tile.alpha_composite(g, ((SIZE - 196) // 2, (SIZE - 196) // 2))
    tile.save(os.path.join(OUT, "ic_launcher.png"))
    tile.save(os.path.join(HERE, "..", "docs", "user_manual", "plugin_icon.png"))
    print("wrote ic_marker.png (%dx%d), ic_marker_seen.png (%dx%d), ic_toolbar.png, ic_launcher.png"
          % (src.size[0], src.size[1], size, size))


if __name__ == "__main__":
    main()
