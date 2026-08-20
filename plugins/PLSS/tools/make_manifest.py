#!/usr/bin/env python3
"""
Build the manifest the plugin reads to find and verify data packs.

The packs cannot ride inside the APK -- a tak.gov submission is a source zip of
a few hundred KB (PLAN-PLSS-v0.1.md section 4.1) -- so the plugin fetches them
once and works offline afterwards. This file is the contract between the two.

Served from a static host: GitHub Releases on a public repo for the fielded
build, or any local HTTP server while developing. Pack URLs are relative to the
manifest's own location, so moving the whole set between hosts needs no edits.

Usage:
    ./make_manifest.py --dir packs/ --source-date 2026-08-19 --out packs/manifest.json
"""

import argparse
import hashlib
import json
import os
import sqlite3
import sys

# The 30 PLSS states, by the postal code that prefixes PLSSID.
STATE_NAMES = {
    "AL": "Alabama", "AK": "Alaska", "AZ": "Arizona", "AR": "Arkansas",
    "CA": "California", "CO": "Colorado", "FL": "Florida", "ID": "Idaho",
    "IL": "Illinois", "IN": "Indiana", "IA": "Iowa", "KS": "Kansas",
    "LA": "Louisiana", "MI": "Michigan", "MN": "Minnesota",
    "MS": "Mississippi", "MO": "Missouri", "MT": "Montana",
    "NE": "Nebraska", "NV": "Nevada", "NM": "New Mexico",
    "ND": "North Dakota", "OH": "Ohio", "OK": "Oklahoma", "OR": "Oregon",
    "SD": "South Dakota", "UT": "Utah", "WA": "Washington",
    "WI": "Wisconsin", "WY": "Wyoming", "US": "United States (all)",
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def describe(path):
    """Feature counts come from the pack itself, so they cannot drift."""
    conn = sqlite3.connect(path)
    try:
        meta = dict(conn.execute("SELECT key, value FROM meta"))
    finally:
        conn.close()

    name = os.path.basename(path)
    state = name[len("plss_"):-len(".sqlite")] if name.startswith("plss_") else ""

    return {
        "state": state,
        "name": STATE_NAMES.get(state, state),
        "url": name,
        "bytes": os.path.getsize(path),
        "sha256": sha256(path),
        "townships": int(meta.get("townships", 0)),
        "sections": int(meta.get("sections", 0)),
        "schema": int(meta.get("schema", 1)),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dir", required=True, help="directory of plss_*.sqlite")
    ap.add_argument("--source-date", required=True,
                    help="BLM source date, YYYY-MM-DD, for the update check")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    packs = []
    for name in sorted(os.listdir(args.dir)):
        if name.startswith("plss_") and name.endswith(".sqlite"):
            path = os.path.join(args.dir, name)
            print("hashing %s (%.1f MB)" % (name, os.path.getsize(path) / 1048576))
            packs.append(describe(path))

    if not packs:
        print("no packs found in %s" % args.dir, file=sys.stderr)
        return 1

    manifest = {
        "schema": 1,
        "source": "BLM CadNSDI PLSS",
        "sourceUrl": "https://gis.blm.gov/arcgis/rest/services/Cadastral/"
                     "BLM_Natl_PLSS_CadNSDI/MapServer",
        "sourceDate": args.source_date,
        "packs": packs,
    }

    with open(args.out, "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")

    total = sum(p["bytes"] for p in packs)
    print("wrote %s: %d packs, %.1f MB total"
          % (args.out, len(packs), total / 1048576))
    return 0


if __name__ == "__main__":
    sys.exit(main())
