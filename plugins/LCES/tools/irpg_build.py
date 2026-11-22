#!/usr/bin/env python3
"""Turn the IRPG PDF (NWCG PMS 461) into the pages the plugin shows.

    python3 tools/irpg_build.py <pms461.pdf> [--dump <dir>]

Writes app/src/main/assets/irpg/pages.json and the picture crops under
app/src/main/assets/irpg/fig/. Every word comes out of the PDF through poppler:
`pdftohtml -xml` gives each run of text with its position and weight, and
`pdftoppm` cuts the diagrams and the tables that do not survive as text out of
the page itself. Nothing is typed in by hand, so a new edition is a rerun, then
a read of the diff and of every page against the PDF. `--dump` writes one plain
text file per page for that read.

A topic is one entry of the book's own table of contents (plus the two titled
pages the contents leaves out, see EXTRA), and runs from its printed page to the
page before the next entry. Three pairs of topics share a page and are split at
the second one's title.

Blocks, in reading order:
  {"t": "h",   "x": text}                  heading (the book's bold lines, 13 pt and up)
  {"t": "h2",  "x": text}                  smaller bold heading
  {"t": "p",   "x": text, "lvl": n}        paragraph; lvl > 0 is an indented line
  {"t": "li" | "chk" | "sub" | "dash",     a bullet, a checkbox, a hollow bullet,
        "x": text, "lvl": n}               a dash; lvl is the nesting depth
  {"t": "num", "n": "1", "x": text, "lvl": n}
  {"t": "tbl", "hdr": [..] | null, "rows": [[..], ..]}
  {"t": "fig", "src": "fig/<name>.png", "w": px, "h": px}
  {"t": "toc", "x": text, "pg": n, "id": page id}   (the contents page only)

Text carries <b> for the book's bold runs and <a href="pg:N"> for its "see page
N" references; & < > are escaped. The plugin renders it with Html.fromHtml.
"""
import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

EDITION = "January 2025"
TRACE = None   # --trace <pdf page>: print each line's parse decision
# Printed page N is PDF page N + 16 in the January 2025 edition.
PDF_OFFSET = 16
# pdftohtml's units are points x 1.5; a 300 dpi crop is that x 300/72.
PX = 300.0 / 72.0 / 1.5
FOOTER_TOP = 505
PAGE_W = 351

# Titled pages the book's own contents leaves out, each its own topic.
EXTRA = [
    ("Firing Operations", 10, "Operational Engagement"),
    ("Aerial Delivered Retardant/Water Drop Zone Considerations", 73, "Aviation"),
]

# The pages in the covers group, by PDF page: (title, where it is cited).
COVERS = [
    (2, "Sizeup Report", "front cover (inside)"),
    (139, "Briefing Checklist", "back cover (inside)"),
    # One page, as the back cover prints them, with both headings kept.
    (140, "10s & 18s", "back cover (outside)"),
    (3, "About the IRPG", "title page"),        # titled by us: the page has none
    (138, "Publication Notice", "122"),         # titled by us
]
UNTITLED = {"About the IRPG", "Publication Notice",
            "10s & 18s"}

# Ids the pane's tiles and the plugin's code rely on; everything else is a slug.
IDS = {
    "10s & 18s": "orders",
    "LCES": "lces",
    "Briefing Checklist": "briefing",
    "Common Denominators of Fire Behavior on Tragedy Fires": "denominators",
    "Sizeup Report": "sizeup",
    "Medical Incident Report": "mir",
}

NOTES_PAGES = {32, 48, 68, 76, 98, 121, 122, 125}

# Per PDF page, what the generic parse cannot know. Keys:
#   fig:    [("gap", after, before|None[, rotate]) | ("lines", start, end[, extra lines]) |
#            ("page", rotate)]   each one's search starts after the one before;
#            rotate turns a landscape table upright
#   tbl:    [{"start", "end", "hdr": n lines | [names], "cols": [x..] | None}]
#   nojoin: [(start, end)]   every line in the range is its own block
#   rows:   [(start, end)]   same, but a line indented deeper than the one before
#                            it continues that one (a wrapped cell)
#   fix:    [(from, to)]     text replacements, after the parse
#   cols:   False            no column splitting on this page
#   prefix: [(text, prefix)] text drawn as a symbol the PDF has no character for
SPEC = {
    22: {"tbl": [{"start": "Flame", "end": "46 acres", "hdr": 2, "cols": [45, 115, 260]}]},
    45: {"tbl": [{"start": "Key Weather Variables", "end": "Cloud Cover",
                  "hdr": ["Key Weather Variables", "Watch Out", "Critical"],
                  "cols": [40, 190, 258]}],
         "fix": [("ᴼ", "°")]},
    54: {"fig": [("gap", "Stable Atmosphere:", "Limited vertical motion"),
                 ("gap", "Unstable Atmosphere:", "Gusty winds")]},
    57: {"fig": [("lines", "Critical Winds", "from snow and ice cover")]},
    58: {"fig": [("lines", "Upslope", "seasonally"),
                 ("lines", "Surface Wind at", "directions")]},
    59: {"fig": [("lines", "Terms", "blown from roofs")]},
    60: {"fig": [("gap", "Estimate PIG from temp", None, True)]},
    61: {"fig": [("page", True)]},
    62: {"fig": [("page", True)]},
    63: {"fig": [("page", True)]},
    64: {"fig": [("page", True)]},
    65: {"fig": [("lines", "RH", "spots, critical fire", 1)]},
    66: {"fig": [("lines", "Flame", "should be considered.")]},
    67: {"fig": [("gap", "average, or low for the time of year", None)]},
    71: {"cols": False, "nojoin": [("CHEMTREC", "3E COMPANY")]},
    72: {"fig": [("gap", "Will not burn", "SPECIFIC HAZARD")],
         "rows": [("HEALTH HAZARD", "POI – Poisonous")],
         # The radiation trefoil is drawn, not typed; the PDF has no text for it.
         "prefix": [("– Radioactive", "☢ ")]},
    75: {"fig": [("lines", "Structure is safe for Search", "Entrance is located")]},
    83: {"fig": [("page", False)]},
    84: {"fig": [("page", False)]},
    86: {"fig": [("page", False)]},
    88: {"tbl": [{"start": "Item", "end": "Cubee", "hdr": 1, "cols": [45, 255]}]},
    92: {"rows": [("ATGS =", "VLAT =")]},
    93: {"fig": [("lines", "Coverage", "Medium to heavy slash")]},
    103: {"fig": [("lines", "NWCG Dozer Typing Standard", "Weight (lbs)")]},
    104: {"cols": False,
          "nojoin": [("Forester", "1½”: 60 gpm"), ("10 gpm =", "30 gpm = 20"),
                     ("20 gpm = 1 psi", "* Numbers rounded"), ("¾", "1½\" hose")],
          },
    105: {"fig": [("lines", "Engine Type", "Personnel (min)"),
                  ("lines", "Water Tender Type", "Personnel (minimum)")]},
    108: {"fig": [("lines", "Possible Cause", "Clean or replace."),
                  ("lines", "Possible Cause", "warehouse.")]},
    109: {"fig": [("lines", "Possible Cause", "Wrong type spark plug."),
                  ("lines", "Possible Cause", "Air filter dirty.")]},
    110: {"fig": [("lines", "Possible Cause", "Wrong type spark plug."),
                  ("lines", "Possible Cause", "Muffler blocked")]},
    111: {"tbl": [{"start": "Acres", "end": "50 120", "hdr": 1, "cols": [50, 110, 195, 255]},
                  {"start": "Class Fire Size", "end": "5,000+ acres",
                   "hdr": ["Class", "Fire Size"], "cols": [95, 145]}]},
    120: {"tbl": [{"start": "Letter", "end": "Zebra",
                   "hdr": ["Letter", "Law Enforcement", "International"], "cols": [28, 70, 185]}]},
    132: {"fig": [("lines", "Color", "repositioning airway.")]},
    136: {"fig": [("lines", "RED / PRIORITY 1", "Lat. / Long. (WGS84)")]},
    137: {"fig": [("lines", "Geographic Name", "(Ex: EMT Smith)"),
                  ("lines", "Function", "TACTICAL")],
          "nojoin": [("Patient Assessment: See", "Treatment:"),
                     ("Evacuation Location", "Helispot / Extraction")]},
}

# Glyphs the PDF draws its markers with, by character (private-use ones are
# Wingdings), and the item kind each one starts.
MARKS = {"•": "li", "▪": "li", "": "li",
         "□": "chk", "☐": "chk", "": "chk", "": "chk",
         "○": "sub", "o": "sub",
         "–": "dash", "-": "dash", "−": "dash"}
# Marks that are the thing itself, drawn as the item's own glyph.
CUSTOM = {"\\": "li", "×": "li"}
# Characters that are text, not markers, once mapped.
GLYPH_TEXT = {"": "→", "": "←", "⁄": "/", "−": "–"}
NUM = re.compile(r"^(\d{1,2})\.\s+(\S.*)$")
PHONE = re.compile(r"\d-\d{3}-\d{4}\s*$")
PAGE_REF = re.compile(r"\b([Pp]ages?)\s+(\d{1,3})\b")


class Chunk:
    __slots__ = ("top", "left", "right", "bottom", "size", "bold", "fam", "text")

    def __init__(self, top, left, width, height, size, bold, fam, text):
        self.top, self.left = top, left
        self.right, self.bottom = left + width, top + height
        self.size, self.bold, self.fam, self.text = size, bold, fam, text


class Line:
    def __init__(self, chunks, join="yes"):
        self.join = join   # "yes", "no" (own block), "rows" (own block unless indented deeper)
        self.chunks = sorted(chunks, key=lambda c: c.left)
        self.top = min(c.top for c in chunks)
        self.bottom = max(c.bottom for c in chunks)
        # The text's own bottom: a marker glyph's box runs deeper than its text.
        text = [c for c in chunks if not is_glyph(c)] or chunks
        self.tbottom = max(c.bottom for c in text)
        self.left = self.chunks[0].left
        self.right = max(c.right for c in chunks)

    def plain(self):
        return norm(join_chunks(self.chunks, plain=True))


def norm(s):
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"(\d) (st|nd|rd|th)\b", r"\1\2", s)   # a superscript "90 th"
    return s.replace("º", "°").replace("° F", "°F")       # the book's degree signs


def esc(s):
    return html.escape(s, quote=False)


# ---------------------------------------------------------------- the PDF

def read_pdf(pdf):
    """Every page's visible text runs, with position, size and weight."""
    xml = subprocess.run(["pdftohtml", "-xml", "-i", pdf, "-stdout"],
                         check=True, capture_output=True, text=True).stdout
    fonts = {}
    pages = {}
    for num, body in re.findall(r'<page number="(\d+)"[^>]*>(.*?)</page>', xml, re.S):
        for fid, size, fam, color in re.findall(
                r'<fontspec id="(\d+)" size="(\d+)" family="([^"]*)" color="(#\w+)"', body):
            fonts[fid] = (int(size), fam.split("+")[-1], color)
        chunks = []
        for top, left, w, h, fid, inner in re.findall(
                r'<text top="(\d+)" left="(\d+)" width="(\d+)" height="(\d+)" '
                r'font="(\d+)">(.*?)</text>', body, re.S):
            size, fam, color = fonts[fid]
            text = html.unescape(re.sub(r"<[^>]+>", "", inner))
            text = "".join(GLYPH_TEXT.get(ch, ch) for ch in text)
            if size < 6 or color == "#ffffff" or not text.strip():
                continue   # the hidden section banner, and spacing runs
            if int(top) >= FOOTER_TOP:
                continue   # "IRPG – Aviation (Blue)   63 of 122"
            chunks.append(Chunk(int(top), int(left), int(w), int(h), size,
                                "<b>" in inner, fam, text))
        pages[int(num)] = chunks
    for n, spec in SPEC.items():
        for text, prefix in spec.get("prefix", []):
            for c in pages.get(n, []):
                if c.text.strip() == text:
                    c.text = prefix + c.text
                    c.left -= 11
    return pages


def is_glyph(c):
    t = c.text.strip()
    return len(t) == 1 and (t in MARKS or t in CUSTOM) and c.right - c.left < 20


def lines_of(chunks):
    """Group runs into lines. Text runs on one line share a top within a few
    units (a superscript sits 6 below); a marker glyph joins the line whose
    bottom edge is nearest its own."""
    out = []
    for c in sorted((c for c in chunks if not is_glyph(c)), key=lambda c: (c.top, c.left)):
        if out and abs(c.top - out[-1][0].top) <= 7:
            out[-1].append(c)
        else:
            out.append([c])
    for g in sorted((c for c in chunks if is_glyph(c)), key=lambda c: (c.top, c.left)):
        # Its text starts just to the right of it, on a line it overlaps; of two
        # such lines (a glyph box is taller than its text) the greater overlap.
        def overlap(l):
            return min(max(c.bottom for c in l), g.bottom) - max(min(c.top for c in l), g.top)
        cands = [l for l in out if overlap(l) > 0
                 and any(g.right - 1 <= c.left <= g.right + 16 for c in l)]
        if cands:
            max(cands, key=overlap).append(g)
        else:
            near = min(out, key=lambda l: abs(max(c.bottom for c in l) - g.bottom), default=None)
            if near is not None and abs(max(c.bottom for c in near) - g.bottom) <= 8:
                near.append(g)
            else:
                out.append([g])
    out.sort(key=lambda l: min(c.top for c in l))
    return [Line(l) for l in out]


def join_chunks(chunks, plain=False):
    """One line's runs as text; a run that starts where the last ended is the
    same word ("Br" "i" "efing"), and a bold run becomes <b>...</b>."""
    s = ""
    last = None
    for c in chunks:
        t = c.text.strip()
        if not t:
            continue
        if last is not None and (c.text.startswith(" ") or last.text.endswith(" ")
                                 or c.left - last.right >= 2):
            s += " "
        s += t if plain else (("<b>%s</b>" % esc(t)) if c.bold else esc(t))
        last = c
    return s


def join_text(a, b):
    """A wrapped line onto the text before it: "well-" + "being" is one word."""
    plain = re.sub(r"<[^>]+>", "", a)
    if re.search(r"[A-Za-z]-$", plain) or (
            re.search(r"[A-Za-z]/$", plain) and "://" not in plain.split()[-1]):
        return a + b
    return a + " " + b


def marker_of(line):
    """(kind, number, text chunks) when the line starts with a bullet, a checkbox,
    a dash or "1."; else (None, None, chunks)."""
    first = line.chunks[0]
    t = first.text.strip()
    m = re.match(r"^([•▪□☐○])\s+(\S.*)$", t)
    if m:
        rest = list(line.chunks)
        rest[0] = Chunk(first.top, first.left + 8, first.right - first.left - 8,
                        first.bottom - first.top, first.size, first.bold, first.fam, m.group(2))
        return MARKS[m.group(1)], None, rest
    if t in CUSTOM and len(line.chunks) > 1:
        return CUSTOM[t], t, line.chunks[1:]
    if t in MARKS and (t != "o" or "Courier" in first.fam) \
            and (t != "-" or "Times" not in first.fam or len(line.chunks) > 1):
        return MARKS[t], None, line.chunks[1:]
    m = NUM.match(norm(join_chunks(line.chunks, plain=True)))
    if m:
        # The number is the start of the first run; cut it off there.
        rest = list(line.chunks)
        rest[0] = Chunk(first.top, first.left, first.right - first.left,
                        first.bottom - first.top, first.size, first.bold, first.fam,
                        re.sub(r"^\s*\d{1,2}\.\s*", "", first.text))
        if not rest[0].text.strip():
            rest = rest[1:]
        return "num", m.group(1), rest
    return None, None, line.chunks


def is_bold_line(line):
    chars = sum(len(c.text.strip()) for c in line.chunks)
    bold = sum(len(c.text.strip()) for c in line.chunks if c.bold)
    return chars > 0 and bold >= 0.8 * chars


def find_line(lines, needle, start=0):
    n = norm(needle)
    for i in range(start, len(lines)):
        if n in lines[i].plain():
            return i
    raise KeyError("no line containing %r" % needle)


# ---------------------------------------------------------------- columns

def side_split(lines):
    """Regions of two-column text (the indicator lists, the checkbox pairs). Yields
    (kind, lines) with kind "one" or "two"; a "two" region is a list of
    (left_lines, right_lines)."""
    out = []
    i = 0
    while i < len(lines):
        gs = gaps(lines[i])
        if not gs:
            out.append(("one", [lines[i]]))
            i += 1
            continue
        left_mark = lines[i].chunks[0].left
        j = i + 1
        while j < len(lines):
            ln = lines[j]
            keep = {g for g in gs if not any(c.left < g < c.right for c in ln.chunks)}
            if not keep:
                break
            both = any(c.right <= min(keep) for c in ln.chunks) and \
                any(c.left >= max(keep) for c in ln.chunks)
            if not both:
                # A single-side line stays only as a continuation or an item at
                # the region's own nesting; a heading or a shallower item ends it.
                if is_bold_line(ln) and ln.left < left_mark + 4:
                    break
                kind = marker_of(ln)[0]
                if kind and ln.chunks[0].left < left_mark - 4:
                    break
                if not kind and ln.left < left_mark + 4 and \
                        ln.right - ln.left > PAGE_W * 0.3:
                    break
            gs = keep
            j += 1
        g = sorted(gs)[len(gs) // 2]
        if j == i + 1:
            l = [c for c in lines[i].chunks if c.right <= g]
            r = [c for c in lines[i].chunks if c.left >= g]
            items = ("li", "chk", "sub")
            if not (l and r and marker_of(Line(l))[0] in items and marker_of(Line(r))[0] in items):
                out.append(("one", [lines[i]]))
                i = j
                continue
        pairs = []
        for ln in lines[i:j]:
            l = [c for c in ln.chunks if c.right <= g]
            r = [c for c in ln.chunks if c.left >= g]
            pairs.append((Line(l, ln.join) if l else None, Line(r, ln.join) if r else None))
        out.append(("two", pairs))
        i = j
    return out


def gaps(line):
    """x positions, if any, that split the line in two with text on both sides.
    The book's columns sit as little as 4 units apart (the complexity tables)."""
    out = set()
    text = [c for c in line.chunks if not is_glyph(c)]
    for g in range(130, 250):
        if any(c.left < g < c.right for c in line.chunks):
            continue
        if any(c.right <= g - 2 for c in text) and any(c.left >= g + 2 for c in text):
            out.add(g)
    return out


# ---------------------------------------------------------------- blocks

class Parser:
    def __init__(self, pdf_page, lines, spec, title_bottom=0):
        self.pdf_page = pdf_page
        self.spec = spec
        self.lines = lines
        self.title_bottom = title_bottom
        self.claimed_to = -1
        self.blocks = []
        self.warnings = []
        self.figs = []
        # The white space between consecutive lines inside a paragraph (often
        # negative: the boxes overlap), as the lower quartile over the page. A
        # paragraph break adds a few units to it.
        ordered = sorted(lines, key=lambda l: l.top)
        d = sorted(b.top - a.tbottom for a, b in zip(ordered, ordered[1:]) if b.top - a.top > 2)
        self.lead = d[len(d) // 4] if d else -2
        for key in ("nojoin", "rows"):
            for a, b in spec.get(key, []):
                s = find_line(lines, a)
                e = find_line(lines, b, s)
                for l in lines[s:e + 1]:
                    l.join = "no" if key == "nojoin" else "rows"

    def run(self):
        lines = self.lines
        used = [False] * len(lines)
        # Tables and figures first: they claim their lines.
        claims = []
        for t in self.spec.get("tbl", []):
            s = find_line(lines, t["start"])
            e = find_line(lines, t["end"], s)
            claims.append((s, e, self.table(lines[s:e + 1], t)))
        for f in self.spec.get("fig", []):
            claims.append(self.figure(lines, f))
        for s, e, block in claims:
            for k in range(s, e + 1):
                used[k] = True
        claims.sort(key=lambda c: c[0])
        # Then the flow, with each claim dropped in where its lines were.
        pos = 0
        for s, e, block in claims:
            self.flow([(k, lines[k]) for k in range(pos, s) if not used[k]])
            self.blocks.append(block)
            pos = e + 1
        self.flow([(k, lines[k]) for k in range(pos, len(lines)) if not used[k]])
        for blk in self.blocks:
            # A bold line is a heading; a bold sentence or two is emphasis.
            plain = re.sub(r"<[^>]+>", "", blk.get("x", ""))
            if blk["t"] in ("h", "h2") and (len(plain) > 60 or plain.endswith(".")):
                blk["t"] = "p"
                blk["lvl"] = 0
        for a, b in self.spec.get("fix", []):
            hit = False
            for blk in self.blocks:
                if "x" in blk and a in blk["x"]:
                    blk["x"] = blk["x"].replace(a, b)
                    hit = True
                for row in blk.get("rows", []):
                    for i, cell in enumerate(row):
                        if a in cell:
                            row[i] = cell.replace(a, b)
                            hit = True
            if not hit:
                self.warnings.append("fix %r matched nothing" % a)
        return self.blocks

    def flow(self, numbered):
        if not numbered:
            return
        lines = [l for _, l in numbered]
        if self.spec.get("cols", True):
            regions = side_split(lines)
        else:
            regions = [("one", [l]) for l in lines]
        # Single-column lines between two-column regions are one stream, so a
        # paragraph's gap and a wrapped line's indent are measured against
        # their neighbours and not against themselves.
        main = [l for k, part in regions if k == "one" for l in part] + \
            [l for k, part in regions if k == "two" for l, _ in part if l]
        margin = min(l.left for l in main) if main else 27
        ranks = []
        for x in sorted({l.chunks[0].left for l in main if marker_of(l)[0]}):
            if not ranks or x - ranks[-1] > 4:
                ranks.append(x)
        base = (ranks, margin)
        run = []
        for kind, part in regions + [("end", None)]:
            if kind == "one":
                run.extend(part)
                continue
            if run:
                self.stream(run, base=base, shift=False)
                run = []
            if kind == "two":
                left = [l for l, _ in part if l]
                right = [r for _, r in part if r]
                self.stream(left, base=base, shift=False)
                # A right-hand item sits at the depth of the left item on its row.
                rows = {}
                for l, r in part:
                    if l and r and marker_of(l)[0] and marker_of(r)[0]:
                        lvl = next((i for i, x in enumerate(ranks)
                                    if abs(l.chunks[0].left - x) <= 4), 0)
                        rows.setdefault(r.chunks[0].left, lvl)
                self.stream(right, base=base, lvl_map=rows)

    def stream(self, lines, base, shift=True, lvl_map=None):
        """Lines of one column into blocks. The page's marker ranking gives the
        nesting; a right-hand column uses it shifted to its own edge."""
        if not lines:
            return
        ranks, margin = base
        if shift:
            margin = min(l.left for l in lines)
            d = margin - base[1]
            ranks = [r + d for r in ranks]
            for x in sorted({l.chunks[0].left for l in lines if marker_of(l)[0]}):
                if all(abs(x - r) > 4 for r in ranks):
                    ranks.append(x)
            ranks.sort()
        prev_line = None
        for ln in lines:
            kind, n, rest = marker_of(ln)
            text = norm(join_chunks(rest))
            last = self.blocks[-1] if self.blocks else None
            close = prev_line is None or ln.top - prev_line.tbottom <= self.lead + 2.5
            indented = ln.left > margin + 4
            bold = is_bold_line(ln)
            # "AWARENESS – Stress Injuries can be caused by:" starts a paragraph
            # with a bold run; the line before it did not end in one.
            runin = ln.chunks and ln.chunks[0].bold and not bold and prev_line is not None \
                and not prev_line.chunks[-1].bold and not (indented)
            cont = False
            if not kind and not runin and last \
                    and last["t"] in ("li", "chk", "sub", "dash", "num", "p", "h2", "h") \
                    and ln.join != "no":
                prev_text = prev_line.plain() if prev_line else ""
                mid_sentence = prev_text and prev_text[-1] not in ".!?:" \
                    and not PHONE.search(prev_text)
                if ln.join == "rows":
                    cont = prev_line is not None and ln.left > prev_line.left + 4
                elif bold:
                    # A bold sentence wraps ("Since calculations assume ... safety" /
                    # "zones downwind"), or a sentence ends in a bold run. A wrapped
                    # line reads on in lower case or ends the sentence; a second
                    # heading ("Indicators") does neither.
                    plain = re.sub(r"<[^>]+>", "", text)
                    reads_on = plain[:1].islower() or plain.endswith(".")
                    cont = close and mid_sentence and prev_line is not None and reads_on and (
                        (is_bold_line(prev_line) and (prev_line.right >= 285
                                                      or last["t"] in ("h", "h2")))
                        or (last["t"] in ("p", "num", "li") and prev_line.chunks[-1].bold))
                    if cont and last["t"] in ("h", "h2"):
                        last["t"] = "p"
                elif last["t"] in ("h", "h2"):
                    # A bold lead ("Energy Release Component (ERC-NFDRS) and Buildup")
                    # with its sentence wrapped under it.
                    cont = close and mid_sentence and ln.left >= last["_x"] - 2 \
                        and len(re.sub(r"<[^>]+>", "", last["x"])) >= 25
                    if cont:
                        last["t"] = "p"
                elif last["t"] == "p":
                    cont = close and not PHONE.search(prev_text) and (
                        (not indented and last.get("lvl", 0) == 0) or ln.left > last["_x"] + 4)
                elif ln.left > self.mark_x(last, ranks, margin) + 3:
                    cont = True
                elif last["t"] == "num" and prev_line is not None and prev_line.right >= 250 \
                        and abs(ln.left - last["_x"]) <= 4 and close:
                    cont = True   # "1. Assess ..." wraps back to the margin
            if TRACE == self.pdf_page:
                print("  t%3d x%3d %s%s%s%s %r" % (ln.top, ln.left, kind or "-",
                      " B" if bold else "", " R" if runin else "", " cont" if cont else "",
                      re.sub(r"<[^>]+>", "", text)[:50]), file=sys.stderr)
            if cont:
                last["x"] = join_text(last["x"], text)
            elif kind:
                x = ln.chunks[0].left
                lvl = next((i for i, r in enumerate(ranks) if abs(x - r) <= 4), 0)
                if lvl_map:
                    lvl = next((v for k, v in lvl_map.items() if abs(k - x) <= 4), lvl)
                b = {"t": kind, "x": text, "lvl": lvl, "_x": rest[0].left if rest else ln.left}
                if kind in CUSTOM.values() and n in CUSTOM:
                    b["m"] = n
                elif n:
                    b["n"] = n
                self.blocks.append(b)
            elif bold and ln.join == "yes":
                size = max(c.size for c in ln.chunks)
                kind = "h" if size >= 13 else "h2"
                if len(re.sub(r"<[^>]+>", "", text)) > 60:
                    kind = "p"   # a bold sentence, not a heading
                self.blocks.append({"t": kind, "x": text, "lvl": 0, "_x": ln.left})
            else:
                lvl = 0
                if indented:
                    lvl = 1 + sum(1 for r in ranks if r < ln.left - 4)
                self.blocks.append({"t": "p", "x": text, "lvl": lvl, "_x": ln.left})
            prev_line = ln

    @staticmethod
    def mark_x(block, ranks, margin):
        lvl = block.get("lvl", 0)
        return ranks[lvl] if lvl < len(ranks) else margin

    def table(self, lines, spec):
        hdr = spec.get("hdr", 1)
        cols = spec.get("cols")
        if cols is None:
            lefts = sorted(c.left for l in lines for c in l.chunks)
            cols = []
            for x in lefts:
                if not cols or x - cols[-1] > 12:
                    cols.append(x)
            self.warnings.append("table at %r: columns guessed at %s" % (spec["start"], cols))
        if isinstance(hdr, list):
            names, data = hdr, lines[1:]
        else:
            names = [""] * len(cols) if hdr else None
            for l in lines[:hdr]:
                for c in l.chunks:
                    k = self.col_of(c, cols)
                    names[k] = norm(names[k] + " " + c.text)
            data = lines[hdr:]
        rows = []
        for l in data:
            cells = [[] for _ in cols]
            for c in l.chunks:
                cells[self.col_of(c, cols)].append(c)
            rows.append([norm(join_chunks(cs)) for cs in cells])
        return {"t": "tbl", "hdr": names, "rows": rows}

    @staticmethod
    def col_of(chunk, cols):
        k = 0
        for i, x in enumerate(cols):
            if chunk.left >= x - 6:
                k = i
        return k

    def figure(self, lines, spec):
        kind = spec[0]
        after = self.claimed_to + 1
        if kind == "gap":
            s = find_line(lines, spec[1], after)
            e = find_line(lines, spec[2], s) if spec[2] else len(lines)
            self.claimed_to = e - 1
            y0 = lines[s].bottom + 2
            y1 = lines[e].top - 2 if spec[2] else FOOTER_TOP
            box = (18, y0, PAGE_W - 18, y1)
            return (s + 1, e - 1, self.crop(box, len(spec) > 3 and spec[3]))
        if kind == "lines":
            s = find_line(lines, spec[1], after)
            e = find_line(lines, spec[2], s) + (spec[3] if len(spec) > 3 else 0)
            self.claimed_to = e
            y0 = min(l.top for l in lines[s:e + 1]) - 4
            y1 = max(l.bottom for l in lines[s:e + 1]) + 4
            return (s, e, self.crop((18, y0, PAGE_W - 18, y1), False))
        if kind == "page":
            box = (14, self.title_bottom + 4, PAGE_W - 14, FOOTER_TOP)
            return (0, len(lines) - 1, self.crop(box, spec[1]))
        raise ValueError(spec)

    def crop(self, box, rotate):
        name = "p%03d_%d" % (self.pdf_page, len(self.figs) + 1)
        self.figs.append((name, box, rotate))
        return {"t": "fig", "src": "fig/%s.png" % name}


def render_figs(pdf, figs, out_dir):
    """Cut each box out of its page at 300 dpi, grey, and record its size."""
    os.makedirs(out_dir, exist_ok=True)
    sizes = {}
    for pdf_page, name, (x0, y0, x1, y1), rotate in figs:
        px = [int(round(v * PX)) for v in (x0, y0, x1 - x0, y1 - y0)]
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["pdftoppm", "-r", "300", "-gray", "-png",
                            "-f", str(pdf_page), "-l", str(pdf_page),
                            "-x", str(px[0]), "-y", str(px[1]), "-W", str(px[2]),
                            "-H", str(px[3]), pdf, os.path.join(tmp, "c")], check=True)
            src = next(os.path.join(tmp, f) for f in os.listdir(tmp))
            dst = os.path.join(out_dir, name + ".png")
            if rotate:
                subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src,
                                "-vf", "transpose=2", dst], check=True)
            else:
                shutil.copy(src, dst)
        sizes[name] = png_size(dst)
    return sizes


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


# ---------------------------------------------------------------- topics

TOC_LINE = re.compile(r"^(.*?)\s*\.{2,}\s*(\S.*)$")
SECTION = re.compile(r"^(.*?)\s*\((\w+) pages\)$")


def contents(pages):
    """The book's table of contents, PDF pages 4 to 7: topics in order, with the
    section each sits in and that section's paper color."""
    out, pending, section, color = [], None, "Operational Leadership", "white"
    for n in range(4, 8):
        for ln in lines_of(pages[n]):
            s = ln.plain()
            if s == "Table of Contents":
                continue
            m = TOC_LINE.match(s)
            if not m:
                pending = s if pending is None else pending + " " + s
                continue
            name, pg = m.group(1).strip(), m.group(2).strip()
            if pending:
                name, pending = pending + " " + name, None
            sm = SECTION.match(name)
            if sm:
                section, color = sm.group(1).title(), sm.group(2)
                continue
            if "cover" in pg:
                continue   # the covers group is built from COVERS
            out.append({"title": name, "pg": pg, "section": section, "color": color})
    for title, pg, section in EXTRA:
        color = next(t["color"] for t in out if t["section"] == section)
        k = next(i for i, t in enumerate(out)
                 if t["pg"].isdigit() and int(t["pg"]) > pg)
        out.insert(k, {"title": title, "pg": str(pg), "section": section, "color": color})
    return out


def slug(title):
    s = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
    return IDS.get(title, s)


def roman(s):
    vals = {"i": 1, "v": 5, "x": 10}
    total = 0
    for a, b in zip(s, s[1:] + " "):
        v = vals[a]
        total += -v if b in vals and vals[b] > v else v
    return total


def pdf_page_of(pg):
    """PDF page of a printed page: arabic after the offset, roman in the front."""
    if pg.isdigit():
        return int(pg) + PDF_OFFSET
    return roman(pg) + 3    # v is PDF page 8


def title_match(text, title, partial=False):
    """The text reads as the title; with partial, as the start of it."""
    a = re.sub(r"[^a-z0-9]", "", text.lower())
    b = re.sub(r"[^a-z0-9]", "", title.lower())
    return a == b or (partial and len(a) >= 4 and b.startswith(a))


def strip_title(lines, title):
    """Drop the bold line(s) at the top that read the topic's title."""
    k = 0
    acc = ""
    best = None
    while k < len(lines) and lines[k].top < 100 and is_bold_line(lines[k]):
        acc = norm(acc + " " + lines[k].plain())
        k += 1
        if title_match(acc, title):
            best = k
            break
        if not title_match(acc, title, partial=True):
            break
    if best:
        return lines[best:], lines[best - 1].bottom
    return lines, None


def split_at_title(blocks, title):
    for i, b in enumerate(blocks):
        if b["t"] in ("h", "h2") and title_match(re.sub(r"<[^>]+>", "", b["x"]), title):
            return blocks[:i], blocks[i + 1:]
    return blocks, None


def build_topic(pages, topic, pdf_pages, figs, warnings):
    blocks = []
    for n in pdf_pages:
        if n in NOTES_PAGES or n not in pages:
            continue
        lines = lines_of(pages[n])
        title_bottom = 0
        if n == pdf_pages[0] and topic["title"] not in UNTITLED:
            lines, title_bottom = strip_title(lines, topic["title"])
            if title_bottom is None:
                title_bottom = 0
                warnings.append("p%d: title %r not found at the top" % (n, topic["title"]))
        p = Parser(n, lines, SPEC.get(n, {}), title_bottom)
        page_blocks = p.run()
        for name, box, rotate in p.figs:
            figs.append((n, name, box, rotate))
        warnings.extend("p%d: %s" % (n, w) for w in p.warnings)
        blocks.extend(page_blocks)
    return blocks


def link_pages(blocks, by_pg):
    for b in blocks:
        if "x" not in b:
            continue

        def repl(m):
            pg = m.group(2)
            if pg in by_pg:
                return '<a href="pg:%s">%s %s</a>' % (pg, m.group(1), pg)
            return m.group(0)
        b["x"] = PAGE_REF.sub(repl, b["x"])


def main():
    args = sys.argv[1:]
    dump = None
    if "--dump" in args:
        k = args.index("--dump")
        dump = args[k + 1]
        del args[k:k + 2]
    if "--trace" in args:
        global TRACE
        k = args.index("--trace")
        TRACE = int(args[k + 1])
        del args[k:k + 2]
    if len(args) != 1:
        sys.exit(__doc__)
    pdf = args[0]
    with open(pdf, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    pages = read_pdf(pdf)
    here = os.path.dirname(os.path.abspath(__file__))
    assets = os.path.join(here, "..", "app", "src", "main", "assets", "irpg")

    topics = contents(pages)
    figs, warnings, out = [], [], []
    sections = []
    for t in topics:
        if not any(s["name"] == t["section"] for s in sections):
            sections.append({"name": t["section"], "color": t["color"]})
    sections.append({"name": "Covers", "color": "white"})

    # Printed page ranges: to the page before the next topic.
    for i, t in enumerate(topics):
        start = pdf_page_of(t["pg"])
        nxt = pdf_page_of(topics[i + 1]["pg"]) if i + 1 < len(topics) else 138
        t["pdf"] = list(range(start, nxt)) if nxt > start else [start]
        t["shared"] = nxt == start

    pending_split = None
    for i, t in enumerate(topics):
        page = {"id": slug(t["title"]), "title": t["title"], "section": t["section"],
                "color": t["color"], "pg": t["pg"], "pdf": t["pdf"]}
        if pending_split is not None:
            page["blocks"] = pending_split
            pending_split = None
        else:
            page["blocks"] = build_topic(pages, t, t["pdf"], figs, warnings)
            if t["shared"]:
                head, tail = split_at_title(page["blocks"], topics[i + 1]["title"])
                if tail is None:
                    warnings.append("p%d: %r not found to split at"
                                    % (t["pdf"][0], topics[i + 1]["title"]))
                    tail = []
                page["blocks"], pending_split = head, tail
        out.append(page)

    cover_blocks = {}
    for n, title, where in COVERS:
        if title in cover_blocks:
            continue
        cover_blocks[title] = build_topic(pages, {"title": title, "pg": where}, [n], figs, warnings)
    for n, title, where in COVERS:
        out.append({"id": slug(title), "title": title, "section": "Covers",
                    "color": "white", "pg": where, "pdf": [n],
                    "blocks": cover_blocks[title]})

    # Pictures, then the sizes the layout needs.
    sizes = render_figs(pdf, figs, os.path.join(assets, "fig"))
    by_pg = {}
    for p in out:
        for b in p["blocks"]:
            if b["t"] == "fig":
                b["w"], b["h"] = sizes[os.path.basename(b["src"])[:-4]]
            b.pop("_x", None)
        if p["pg"].isdigit():
            n0 = int(p["pg"])
            for n in range(n0, n0 + len(p["pdf"])):
                by_pg.setdefault(str(n), p["id"])
    for p in out:
        link_pages(p["blocks"], by_pg)

    # The contents page, from the topics themselves.
    toc = []
    for s in sections:
        toc.append({"t": "h", "x": s["name"], "c": s["color"]})
        for p in out:
            if p["section"] == s["name"]:
                toc.append({"t": "toc", "x": p["title"], "pg": p["pg"], "id": p["id"]})
    out.append({"id": "contents", "title": "IRPG Contents", "section": "Covers",
                "color": "white", "pg": "i", "pdf": [4, 5, 6, 7], "blocks": toc})

    doc = {"source": "NWCG Incident Response Pocket Guide (IRPG), PMS 461",
           "edition": EDITION, "sha256": sha, "sections": sections, "pages": out}
    os.makedirs(assets, exist_ok=True)
    with open(os.path.join(assets, "pages.json"), "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("%s: %d pages, %d pictures" % (os.path.join(assets, "pages.json"), len(out), len(figs)))
    for w in warnings:
        print("WARNING", w)
    if dump:
        write_dump(out, dump)


def write_dump(out, d):
    os.makedirs(d, exist_ok=True)
    for i, p in enumerate(out):
        with open(os.path.join(d, "%03d_%s.txt" % (i, p["id"])), "w") as f:
            f.write("# %s  [%s, page %s, pdf %s]\n\n" % (p["title"], p["section"], p["pg"], p["pdf"]))
            for b in p["blocks"]:
                t = b["t"]
                ind = "    " * b.get("lvl", 0)
                x = re.sub(r"<[^>]+>", "", b.get("x", ""))
                if t == "h":
                    f.write("\n## %s\n" % x)
                elif t == "h2":
                    f.write("\n### %s\n" % x)
                elif t == "p":
                    f.write("%s%s\n" % (ind, x))
                elif t == "num":
                    f.write("%s%s. %s\n" % (ind, b["n"], x))
                elif t in ("li", "chk", "sub", "dash"):
                    m = b.get("m") or {"li": "•", "chk": "□", "sub": "○", "dash": "–"}[t]
                    f.write("%s%s %s\n" % (ind, m, x))
                elif t == "tbl":
                    if b["hdr"]:
                        f.write("| " + " | ".join(b["hdr"]) + " |\n")
                    for r in b["rows"]:
                        f.write("| " + " | ".join(r) + " |\n")
                elif t == "fig":
                    f.write("[picture %s %dx%d]\n" % (b["src"], b["w"], b["h"]))
                elif t == "toc":
                    f.write("%s .... %s\n" % (x, b["pg"]))


if __name__ == "__main__":
    main()
