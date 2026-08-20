#!/usr/bin/env python3
"""
Pack fetched BLM NDJSON into the on-device store.

Step 2 of the pipeline in PLAN-PLSS-v0.1.md section 5.3, producing the format
described in 5.2: one SQLite file per state, with an R-tree so the renderer can
ask for "everything in this bbox" and never load a state whole.

Geometry is stored quantized and delta-encoded rather than as WKT. At 1e-6 degrees
(~0.11 m, matching the precision we asked BLM for) successive vertices of a survey
boundary are close together, so zigzag varint deltas cost 2-4 bytes per ordinate
instead of the ~20 that text would.

Blob layout, all varints:
    zigzag(origin_lon_q), zigzag(origin_lat_q),
    ring_count, then per ring:
        vertex_count, then per vertex:
            zigzag(delta_lon_q), zigzag(delta_lat_q)
    deltas are against the previous vertex; the first of each ring is against the
    feature origin, which keeps the leading values small too. The origin is in the
    blob rather than read back off the R-tree so that geometry decodes without the
    index -- the two are separate concerns and the store is easier to reason about.

The national files are split in a single pass rather than re-read once per
state: the section NDJSON is ~1.4 GB, and thirty passes over it would dominate
the whole build.

Usage:
    ./pack_plss.py --sections ca_sections.ndjson --townships ca_townships.ndjson \
                   --out plss_CA.sqlite
    ./pack_plss.py --sections us_sections.ndjson --townships us_townships.ndjson \
                   --split-dir packs/
"""

import argparse
import json
import os
import sqlite3
import sys

# 1e-6 degrees. Matches the geometryPrecision requested in fetch_blm.py, so this
# quantization discards nothing the pull did not already discard.
SCALE = 1000000


def zigzag(n):
    return (n << 1) ^ (n >> 63)


def put_varint(buf, n):
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            buf.append(b | 0x80)
        else:
            buf.append(b)
            return


def encode_rings(rings):
    """
    Returns (blob, minx, miny, maxx, maxy) with the bbox in degrees.

    Degenerate rings (fewer than 3 vertices) are dropped -- they cannot draw and
    would otherwise cost an empty entry in the index.
    """
    usable = [r for r in rings if len(r) >= 3]
    if not usable:
        return None, None, None, None, None

    qrings = []
    for ring in usable:
        qrings.append([(int(round(x * SCALE)), int(round(y * SCALE)))
                       for x, y in ring])

    qminx = min(p[0] for r in qrings for p in r)
    qminy = min(p[1] for r in qrings for p in r)
    qmaxx = max(p[0] for r in qrings for p in r)
    qmaxy = max(p[1] for r in qrings for p in r)

    buf = bytearray()
    put_varint(buf, zigzag(qminx))
    put_varint(buf, zigzag(qminy))
    put_varint(buf, len(qrings))
    for ring in qrings:
        put_varint(buf, len(ring))
        # first vertex is relative to the feature origin, the rest to their
        # predecessor
        px, py = qminx, qminy
        for x, y in ring:
            put_varint(buf, zigzag(x - px))
            put_varint(buf, zigzag(y - py))
            px, py = x, y

    return (bytes(buf), qminx / SCALE, qminy / SCALE,
            qmaxx / SCALE, qmaxy / SCALE)


# BLM spells a few principal meridians more than one way -- "Mount Diablo",
# "Mt Diablo Meridian" and "Mount Diablo Meridian" are all the same meridian, and
# so are "Willamette" and "Willamette Meridian". Left alone they appear as
# separate entries in the plugin's meridian picker, and choosing the wrong one
# searches a handful of townships instead of thousands. Nationally this affects
# 6 townships out of 85,983, which is exactly the kind of thing that stays hidden
# until someone's lookup silently fails.
MERIDIAN_ALIASES = {
    "Mount Diablo": "Mount Diablo Meridian",
    "Mt Diablo Meridian": "Mount Diablo Meridian",
    "Willamette": "Willamette Meridian",
}


def meridian_of(rec):
    m = rec.get("PRINMER")
    if not m:
        return m
    m = m.strip()
    return MERIDIAN_ALIASES.get(m, m)


def township_label(rec):
    """
    "T19S-R25E", the form surveyors and fire ops actually say, built from the
    parts rather than from TWNSHPLAB ("19S 25E") so the leading zeros in
    TWNSHPNO/RANGENO ("019", "025") come off cleanly.
    """
    tn, td = rec.get("TWNSHPNO"), rec.get("TWNSHPDIR")
    rn, rd = rec.get("RANGENO"), rec.get("RANGEDIR")

    if tn and td and rn and rd:
        return "T%s%s-R%s%s" % (tn.lstrip("0") or "0", td,
                                rn.lstrip("0") or "0", rd)

    # some townships carry no range (unsurveyed, protracted); fall back
    return rec.get("TWNSHPLAB") or ""


SCHEMA = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous = OFF;

CREATE TABLE IF NOT EXISTS township (
    id       INTEGER PRIMARY KEY,
    plssid   TEXT NOT NULL,
    label    TEXT,
    meridian TEXT,
    geom     BLOB NOT NULL
);

CREATE TABLE IF NOT EXISTS section (
    id       INTEGER PRIMARY KEY,
    plssid   TEXT NOT NULL,
    divid    TEXT,
    divno    TEXT,
    label    TEXT,
    geom     BLOB NOT NULL
);

CREATE VIRTUAL TABLE IF NOT EXISTS township_idx
    USING rtree(id, minx, maxx, miny, maxy);
CREATE VIRTUAL TABLE IF NOT EXISTS section_idx
    USING rtree(id, minx, maxx, miny, maxy);

CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
"""


def load(conn, path, kind):
    """Stream one NDJSON file into its table and R-tree. Returns rows written."""
    if kind == "township":
        ins = ("INSERT INTO township (id, plssid, label, meridian, geom) "
               "VALUES (?,?,?,?,?)")
        idx = "INSERT INTO township_idx (id, minx, maxx, miny, maxy) VALUES (?,?,?,?,?)"
    else:
        ins = ("INSERT INTO section (id, plssid, divid, divno, label, geom) "
               "VALUES (?,?,?,?,?,?)")
        idx = "INSERT INTO section_idx (id, minx, maxx, miny, maxy) VALUES (?,?,?,?,?)"

    rows = skipped = geom_bytes = 0
    batch_f, batch_i = [], []

    with open(path) as f:
        for line in f:
            if not line.strip():
                continue

            rec = json.loads(line)
            blob, minx, miny, maxx, maxy = encode_rings(rec.get("rings", []))
            if blob is None:
                skipped += 1
                continue

            oid = rec["OBJECTID"]
            if kind == "township":
                batch_f.append((oid, rec.get("PLSSID"), township_label(rec),
                                meridian_of(rec), blob))
            else:
                batch_f.append((oid, rec.get("PLSSID"), rec.get("FRSTDIVID"),
                                rec.get("FRSTDIVNO"), rec.get("FRSTDIVLAB"),
                                blob))
            batch_i.append((oid, minx, maxx, miny, maxy))

            rows += 1
            geom_bytes += len(blob)

            if len(batch_f) >= 10000:
                conn.executemany(ins, batch_f)
                conn.executemany(idx, batch_i)
                batch_f, batch_i = [], []
                print("  %s: %d" % (kind, rows))

    if batch_f:
        conn.executemany(ins, batch_f)
        conn.executemany(idx, batch_i)

    conn.commit()

    if skipped:
        print("  %s: skipped %d degenerate" % (kind, skipped), file=sys.stderr)
    print("  %s: %d rows, %.1f bytes/feature geometry"
          % (kind, rows, geom_bytes / max(rows, 1)))
    return rows


def state_of(rec):
    """
    The two-letter state that prefixes PLSSID, which is how the section layer is
    attributed -- it carries no STATEABBR of its own (see the plan, section 4).
    """
    plssid = rec.get("PLSSID") or ""
    return plssid[:2].upper() if len(plssid) >= 2 else ""


def split(sections, townships, out_dir):
    """
    One pass over each input, routing every feature into its state's pack.

    All the connections stay open at once; there are 30 PLSS states, so the
    handles cost nothing next to re-reading the inputs.
    """
    os.makedirs(out_dir, exist_ok=True)

    conns = {}
    counts = {}

    def conn_for(state):
        if state not in conns:
            path = os.path.join(out_dir, "plss_%s.sqlite" % state)
            if os.path.exists(path):
                os.remove(path)
            c = sqlite3.connect(path)
            c.executescript(SCHEMA)
            conns[state] = c
            counts[state] = {"township": 0, "section": 0}
        return conns[state]

    for kind, path in (("township", townships), ("section", sections)):
        if not path:
            continue

        print("splitting %s" % path)
        seen = 0
        with open(path) as f:
            for line in f:
                if not line.strip():
                    continue

                rec = json.loads(line)
                state = state_of(rec)
                if not state:
                    continue

                blob, minx, miny, maxx, maxy = encode_rings(rec.get("rings", []))
                if blob is None:
                    continue

                c = conn_for(state)
                oid = rec["OBJECTID"]

                if kind == "township":
                    c.execute("INSERT INTO township (id, plssid, label,"
                              " meridian, geom) VALUES (?,?,?,?,?)",
                              (oid, rec.get("PLSSID"), township_label(rec),
                               meridian_of(rec), blob))
                    c.execute("INSERT INTO township_idx (id, minx, maxx, miny,"
                              " maxy) VALUES (?,?,?,?,?)",
                              (oid, minx, maxx, miny, maxy))
                else:
                    c.execute("INSERT INTO section (id, plssid, divid, divno,"
                              " label, geom) VALUES (?,?,?,?,?,?)",
                              (oid, rec.get("PLSSID"), rec.get("FRSTDIVID"),
                               rec.get("FRSTDIVNO"), rec.get("FRSTDIVLAB"),
                               blob))
                    c.execute("INSERT INTO section_idx (id, minx, maxx, miny,"
                              " maxy) VALUES (?,?,?,?,?)",
                              (oid, minx, maxx, miny, maxy))

                counts[state][kind] += 1
                seen += 1
                if seen % 200000 == 0:
                    print("  %s: %d" % (kind, seen))
                    for c2 in conns.values():
                        c2.commit()

        for c2 in conns.values():
            c2.commit()

    total = 0
    for state in sorted(conns):
        c = conns[state]
        c.executemany("INSERT OR REPLACE INTO meta (key, value) VALUES (?,?)", [
            ("schema", "1"),
            ("state", state),
            ("scale", str(SCALE)),
            ("townships", str(counts[state]["township"])),
            ("sections", str(counts[state]["section"])),
        ])
        c.commit()
        c.execute("VACUUM")
        c.close()

        size = os.path.getsize(os.path.join(out_dir, "plss_%s.sqlite" % state))
        total += size
        print("  %-3s %6d twp %8d sec  %7.1f MB"
              % (state, counts[state]["township"], counts[state]["section"],
                 size / 1048576.0))

    print("\n%d state packs, %.1f MB total" % (len(conns), total / 1048576.0))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--sections")
    ap.add_argument("--townships")
    ap.add_argument("--state", default="")
    ap.add_argument("--out")
    ap.add_argument("--split-dir",
                    help="write one pack per state into this directory")
    args = ap.parse_args()

    if args.split_dir:
        return split(args.sections, args.townships, args.split_dir)

    if not args.out:
        ap.error("either --out or --split-dir is required")

    if os.path.exists(args.out):
        os.remove(args.out)

    conn = sqlite3.connect(args.out)
    conn.executescript(SCHEMA)

    counts = {}
    if args.townships:
        counts["township"] = load(conn, args.townships, "township")
    if args.sections:
        counts["section"] = load(conn, args.sections, "section")

    conn.executemany("INSERT OR REPLACE INTO meta (key, value) VALUES (?,?)", [
        ("schema", "1"),
        ("state", args.state),
        ("scale", str(SCALE)),
        ("townships", str(counts.get("township", 0))),
        ("sections", str(counts.get("section", 0))),
    ])
    conn.commit()
    conn.execute("VACUUM")
    conn.close()

    size = os.path.getsize(args.out)
    print("wrote %s: %.1f MB" % (args.out, size / 1048576.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
