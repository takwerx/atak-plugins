#!/usr/bin/env python3
"""Build the Lessons Learned catalog LCES draws on the map.

What it is
----------
One row per incident in the Wildland Fire Lessons Learned Center's Spatial
Incident Review Database (the "Histomap"): fatalities, injuries, entrapments,
burnovers and near misses, each with a point, a date, the LLC's own summary and
a link to the full report on lessons.wildfire.gov. The phone never asks the
LLC's server; it reads this catalog, which ships in the APK and is refreshed
from our depot.

Where it comes from
-------------------
The public, keyless view of the database, owned by NIFC's ArcGIS org:

    https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/
        HistomapPublicView/FeatureServer/0

Points in WGS84, 1,000 rows per page. A US federal work; the item carries the
Forest Service's standard geospatial disclaimer ("the data are dynamic and may
change"). Text is copied as the LLC wrote it, never rewritten.

The type
--------
The source classifies by cause (``SymbologyName``: Medical, Hit By,
Entrapment/Burnover, Aviation ...). LCES draws five kinds, decided here so the
phone and the catalog agree, in this order, the first that fits:

    fatality    FatalityCount > 0
    burnover    Entrapment/Burnover, and people sheltered: a fire shelter, a
                vehicle or in place (EntrapmentType names Shelter_*)
    entrapment  Entrapment/Burnover otherwise (escape route compromised, safety
                zone used)
    injury      InjuryCount > 0, or the source marks an injury or illness
    near miss   everything else

The source's own category travels with each row as ``category``, so the
details page can still say "Entrapment/Burnover" on a fatality.

Output
------
    app/src/main/assets/lessons/lessons.json   the snapshot bundled in the APK
                                               (one incident per line, so a
                                               refresh reads as a diff)
    --out DIR:  lessons.json (the depot manifest: counts, sha256) and
                lessons.json.gz (the catalog), for r2:mapdepot/lces/lessons/
                and https://mapdepot.takwerx.org/lces/lessons/

Usage
-----
    ./lessons_build.py                         # refresh the bundled snapshot
    ./lessons_build.py --out ~/atak-dist/lces-lessons
    ./lessons_build.py --out ~/atak-dist/lces-lessons --publish

Then run ./lessons_verify.py, which asks one byte of every report link;
with --write-dead it records the ones the server says are gone in
lessons_dead.txt, and the next build leaves those links out.
"""

import argparse
import datetime
import gzip
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

SOURCE = ("https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/"
          "HistomapPublicView/FeatureServer/0")
SOURCE_NAME = "Wildland Fire Lessons Learned Center, Spatial Incident Review Database"
PAGE = 1000
UA = "takwerx-lces-catalog/1 (+https://github.com/takwerx)"

FIELDS = [
    "globalid", "LLIncidentName", "IncidentName", "incidentdate", "incidenttime",
    "State", "SymbologyName", "EntrapmentType", "FatalityCount", "InjuryCount",
    "InjuryPresence", "FireBehavior", "ActivityUnderway", "Snipit1", "Snipit2",
    "Link1",
]

TYPES = ["fatality", "burnover", "entrapment", "injury", "near miss"]

HERE = os.path.dirname(os.path.abspath(__file__))
ASSET = os.path.join(HERE, "..", "app", "src", "main", "assets", "lessons", "lessons.json")
DEAD = os.path.join(HERE, "lessons_dead.txt")


def dead_links():
    if not os.path.isfile(DEAD):
        return set()
    with open(DEAD) as fh:
        return {l.strip() for l in fh if l.strip() and not l.startswith("#")}


def get_json(url, tries=4):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                d = json.load(r)
            if "error" in d:
                raise RuntimeError("server error: %s" % d["error"])
            return d
        except Exception as e:  # noqa: BLE001 -- retried, then raised
            last = e
            time.sleep(2 * (i + 1))
    raise last


def query(params):
    return get_json(SOURCE + "/query?" + urllib.parse.urlencode(params))


def fetch_all():
    count = query({"where": "1=1", "returnCountOnly": "true", "f": "json"})["count"]
    rows = []
    offset = 0
    while offset < count:
        d = query({
            "where": "1=1", "outFields": ",".join(FIELDS), "outSR": "4326",
            "orderByFields": "objectid", "resultOffset": offset,
            "resultRecordCount": PAGE, "returnGeometry": "true", "f": "json",
        })
        feats = d.get("features", [])
        if not feats:
            break
        rows.extend(feats)
        offset += len(feats)
        print("  %d of %d" % (len(rows), count), file=sys.stderr)
    if len(rows) != count:
        raise RuntimeError("asked for %d rows, got %d" % (count, len(rows)))
    layer = get_json(SOURCE + "?f=json")
    edited = (layer.get("editingInfo") or {}).get("dataLastEditDate")
    return rows, edited


_ws = re.compile(r"\s+")
_tag = re.compile(r"<[^>]+>")


def text(v):
    """The LLC's words, with stray markup and runs of white space taken out."""
    if v is None:
        return ""
    s = _tag.sub(" ", str(v))
    s = s.replace(" ", " ")
    return _ws.sub(" ", s).strip()


def words(tag):
    """Shelter_in_Vehicle -> Shelter in vehicle."""
    t = tag.strip().replace("_", " ")
    return t[:1].upper() + t[1:].lower() if t else ""


def split_tags(v):
    """A comma list of tags as words; the form's "not applicable" and "unknown" say nothing."""
    out = []
    for t in (v or "").split(","):
        w = words(t)
        if w and w not in out and w.lower() not in ("not applicable", "unknown"):
            out.append(w)
    return out


def kind(a):
    if (a.get("FatalityCount") or 0) > 0:
        return "fatality"
    if a.get("SymbologyName") == "Entrapment/Burnover":
        if "Shelter_" in (a.get("EntrapmentType") or ""):
            return "burnover"
        return "entrapment"
    if (a.get("InjuryCount") or 0) > 0 or a.get("InjuryPresence"):
        return "injury"
    return "near miss"


def report_link(v):
    """Only a web address goes in; anything else is dropped, not repaired."""
    v = (v or "").strip()
    p = urllib.parse.urlsplit(v)
    if p.scheme not in ("http", "https") or not p.netloc:
        return ""
    return v


def hhmm(v):
    if v is None:
        return ""
    try:
        n = int(v)
    except (TypeError, ValueError):
        return ""
    if not (0 <= n <= 2359) or n % 100 > 59:
        return ""
    return "%04d" % n


def to_row(f):
    a = f.get("attributes") or {}
    g = f.get("geometry") or {}
    x, y = g.get("x"), g.get("y")
    if x is None or y is None:
        return None, "no point"
    if not (-180 <= x <= 180 and -90 <= y <= 90) or (abs(x) < 1e-6 and abs(y) < 1e-6):
        return None, "point out of range"
    ms = a.get("incidentdate")
    if ms is None:
        return None, "no date"
    # The LLC enters a calendar date; ArcGIS stores it as a UTC instant around the
    # middle of that day, so the UTC date is the date the report gives.
    date = datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).date()
    gid = (a.get("globalid") or "").strip("{}").lower()
    if not gid:
        return None, "no id"
    summary = text(a.get("Snipit2"))
    quote = text(a.get("Snipit1"))
    if not summary:
        summary, quote = quote, ""
    if quote == summary:
        quote = ""
    row = {
        "id": gid,
        "name": text(a.get("LLIncidentName")) or text(a.get("IncidentName")) or "Unnamed incident",
        "fire": text(a.get("IncidentName")),
        "date": date.isoformat(),
        "time": hhmm(a.get("incidenttime")),
        "state": text(a.get("State")),
        "type": kind(a),
        "category": text(a.get("SymbologyName")),
        "fatalities": int(a.get("FatalityCount") or 0),
        "injuries": int(a.get("InjuryCount") or 0),
        "entrapment": split_tags(a.get("EntrapmentType")),
        "behavior": split_tags(a.get("FireBehavior")),
        "activity": text(a.get("ActivityUnderway")),
        "summary": summary,
        "quote": quote,
        "lat": round(y, 5),
        "lon": round(x, 5),
        "report": report_link(a.get("Link1")),
    }
    return row, None


def catalog_bytes(rows, edited_ms):
    built = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    edited = ""
    if edited_ms:
        edited = datetime.datetime.fromtimestamp(
            edited_ms / 1000, datetime.timezone.utc).date().isoformat()
    head = {
        "version": 1,
        "built": built.isoformat().replace("+00:00", "Z"),
        "source": SOURCE_NAME,
        "sourceUrl": SOURCE,
        "sourceEdited": edited,
        "count": len(rows),
    }
    # One incident per line: a refresh of the bundled snapshot is a readable diff.
    lines = [json.dumps(head, ensure_ascii=False)[:-1] + ',"incidents":[']
    for i, r in enumerate(rows):
        lines.append(json.dumps(r, ensure_ascii=False, separators=(",", ":"))
                     + ("," if i < len(rows) - 1 else ""))
    lines.append("]}")
    return ("\n".join(lines) + "\n").encode("utf-8"), head


def write_atomic(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    with open(tmp, "wb") as fh:
        fh.write(body)
    os.replace(tmp, path)


def publish(out_dir, prefix):
    """Upload to R2. The catalog lands before the manifest that names its hash."""
    remote = "r2:mapdepot/%s" % prefix
    for name in ("lessons.json.gz", "lessons.json"):
        subprocess.run(["rclone", "copyto", "--checksum",
                        os.path.join(out_dir, name), remote + "/" + name], check=True)
    print("live at https://mapdepot.takwerx.org/%s/lessons.json" % prefix)


def _slug(url):
    return url.rstrip("/").rsplit("/", 1)[-1] if url else ""


def _km(a, b):
    dy = (a["lat"] - b["lat"]) * 111.0
    dx = (a["lon"] - b["lon"]) * 111.0 * math.cos(math.radians(a["lat"]))
    return math.hypot(dx, dy)


def same_incident(a, b):
    """The source carries some incidents twice: the same name and report, at the
    same spot or on the same day with the year mistyped (Buck Incident Fatality,
    2012 and "2022"). Separate incidents that share one report (three pile burn
    injuries a state apart) are not this, so a different place AND a different
    day keeps both."""
    if a["name"].strip().lower() != b["name"].strip().lower():
        return False
    close = _km(a, b) <= 5.0
    if a["date"] == b["date"] and close:
        return True
    sa = _slug(a["report"])
    year_typo = a["date"][5:] == b["date"][5:] and a["date"][:4] != b["date"][:4]
    return bool(sa) and sa == _slug(b["report"]) and (close or year_typo)


def _keep_score(r):
    """Which copy of a duplicate stays: the one whose link is the LLC's current
    site and names this incident in this year."""
    slug = _slug(r["report"])
    name = re.sub(r"[^a-z0-9]+", "-", r["name"].lower()).strip("-")
    return (r["report"].startswith("https://lessons.wildfire.gov/"),
            bool(name) and slug.startswith(name[:20]),
            slug.endswith(r["date"][:4]),
            len(r.get("summary") or ""))


def drop_duplicates(rows):
    kept, gone = [], 0
    for r in rows:
        twin = next((k for k in kept if same_incident(k, r)), None)
        if twin is None:
            kept.append(r)
            continue
        gone += 1
        if _keep_score(r) > _keep_score(twin):
            kept[kept.index(twin)] = r
    return kept, gone


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", help="also write the depot manifest and gzipped catalog here")
    ap.add_argument("--asset", default=ASSET, help="the bundled snapshot (default: the APK asset)")
    ap.add_argument("--no-asset", action="store_true", help="do not touch the bundled snapshot")
    ap.add_argument("--prefix", default="lces/lessons", help="R2 prefix under the mapdepot bucket")
    ap.add_argument("--publish", action="store_true", help="upload --out to R2 when built")
    args = ap.parse_args()

    print("reading %s" % SOURCE, file=sys.stderr)
    feats, edited = fetch_all()
    rows, dropped, seen = [], {}, set()
    dead = dead_links()
    unlinked = 0
    for f in feats:
        r, why = to_row(f)
        if r is None:
            dropped[why] = dropped.get(why, 0) + 1
            continue
        if r["id"] in seen:
            dropped["duplicate id"] = dropped.get("duplicate id", 0) + 1
            continue
        seen.add(r["id"])
        if r["report"] in dead:
            r["report"] = ""
            unlinked += 1
        rows.append(r)
    rows.sort(key=lambda r: (r["date"], r["id"]))
    rows, twins = drop_duplicates(rows)
    rows.sort(key=lambda r: (r["date"], r["id"]))
    if twins:
        dropped["same incident twice"] = twins
    body, head = catalog_bytes(rows, edited)

    by_type = {t: 0 for t in TYPES}
    for r in rows:
        by_type[r["type"]] += 1
    print("%d incidents, %s to %s; %s" % (
        len(rows), rows[0]["date"], rows[-1]["date"],
        ", ".join("%s %d" % (t, by_type[t]) for t in TYPES)))
    print("no report link: %d (%d of them dead links left out); no summary: %d; dropped: %s" % (
        sum(1 for r in rows if not r["report"]), unlinked, sum(1 for r in rows if not r["summary"]),
        json.dumps(dropped) if dropped else "none"))
    print("source last edited %s; %.2f MB" % (head["sourceEdited"] or "?", len(body) / 1e6))

    if not args.no_asset:
        write_atomic(os.path.abspath(args.asset), body)
        print("wrote %s" % os.path.relpath(os.path.abspath(args.asset)))

    if args.out:
        out = os.path.expanduser(args.out)
        gz = gzip.compress(body, mtime=0)
        manifest = dict(head)
        manifest["files"] = {"lessons.json.gz": {
            "sha256": hashlib.sha256(gz).hexdigest(), "bytes": len(gz), "count": len(rows)}}
        write_atomic(os.path.join(out, "lessons.json.gz"), gz)
        write_atomic(os.path.join(out, "lessons.json"),
                     (json.dumps(manifest, separators=(",", ":")) + "\n").encode())
        print("wrote %s (%.0f KB gzipped) and its manifest" % (out, len(gz) / 1024))
        if args.publish:
            publish(out, args.prefix)


if __name__ == "__main__":
    main()
