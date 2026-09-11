#!/usr/bin/env python3
"""Build the plugin's icons from the NWCG GeoOps repeater symbol.

The map marker is the standard's own 60 px "Repeater" point symbol (PMS 936,
tools/nwcg_repeater.png, from nwcg.gov), used verbatim so a repeater reads the same
here as in Feature Layer and on every GeoOps map. A site with line of sight from
the operator gets the same symbol on a green ring (ic_marker_seen.png), the way
Feature Layer marks repair status, because tinting a navy symbol green makes mud.

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


def outline_diamond(px):
    """
    The operator's own icon, 2026-09-11: the diamond as an outline on black, with
    the standard's white dot and arcs inside it.

    <p>The NWCG symbol is a solid navy diamond, which is right on a map full of
    GeoOps symbology and heavy as an app icon -- at launcher size it reads as a
    blue blob. Drawn as a stroke the shape is still the repeater symbol and the
    marks inside it are what the eye lands on.
    """
    s = px * SS
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = s / 2
    m = 0.10 * s
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

    # The seen variant: the symbol on a green ring, as Feature Layer rings status.
    ring = 7
    size = src.size[0] + 2 * ring
    seen = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    big = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    d.ellipse([2 * SS, 2 * SS, size * SS - 2 * SS, size * SS - 2 * SS], outline=SEEN, width=4 * SS)
    seen.alpha_composite(big.resize((size, size), Image.LANCZOS))
    seen.alpha_composite(src, (ring, ring))
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
    g = outline_diamond(220)
    tile.alpha_composite(g, ((SIZE - 220) // 2, (SIZE - 220) // 2))
    tile.save(os.path.join(OUT, "ic_launcher.png"))
    tile.save(os.path.join(HERE, "..", "docs", "user_manual", "plugin_icon.png"))
    print("wrote ic_marker.png (%dx%d), ic_marker_seen.png (%dx%d), ic_toolbar.png, ic_launcher.png"
          % (src.size[0], src.size[1], size, size))


if __name__ == "__main__":
    main()
