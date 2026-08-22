#!/usr/bin/env python3
"""
Page the BLM CadNSDI PLSS MapServer into newline-delimited JSON.

Step 1 of the pipeline in PLAN-PLSS-v0.1.md section 5.3. This only *fetches*;
normalizing, quantizing and edge-deduplication happen downstream so that a slow
network pull never has to be repeated while the packing format is still moving.

Layer 1 is PLSS Township, layer 2 is PLSS Section (First Division). Layer 3
(Intersected, 27M features) is explicitly out of scope for v0.1.

Paging is keyset-based on OBJECTID rather than resultOffset: ArcGIS deep offsets
get slow and can skip or repeat rows, and a keyset resumes exactly after an
interruption.

Usage:
    ./fetch_blm.py --state CA  --layer 2 --out ca_sections.ndjson
    ./fetch_blm.py --state CA  --layer 1 --out ca_townships.ndjson
    ./fetch_blm.py --state ALL --layer 2 --out us_sections.ndjson
"""

import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

BASE = ("https://gis.blm.gov/arcgis/rest/services/Cadastral/"
        "BLM_Natl_PLSS_CadNSDI/MapServer")

# Only the attributes the plugin actually uses. Everything else is weight we
# would carry through the whole pipeline for nothing.
FIELDS = {
    1: ["PLSSID", "TWNSHPLAB", "STATEABBR", "PRINMER", "PRINMERCD",
        "TWNSHPNO", "TWNSHPDIR", "RANGENO", "RANGEDIR"],
    2: ["PLSSID", "FRSTDIVID", "FRSTDIVNO", "FRSTDIVLAB"],
}

PAGE = 2000          # the service's maxRecordCount; asking for more is ignored
RETRIES = 5
BACKOFF = 3.0        # seconds, multiplied by attempt number


def state_where(layer, state):
    """
    The township layer carries STATEABBR; the section layer does not, so it is
    filtered on the state prefix of PLSSID instead (see the plan, section 4).

    ALL takes the whole service -- the national build, which is the shipping
    scope; a state is a checkpoint, not the product (plan, decision 1).
    """
    if state == "ALL":
        return "1=1"

    if layer == 1:
        return "STATEABBR = '%s'" % state
    return "PLSSID LIKE '%s%%'" % state


def query(layer, params):
    url = "%s/%d/query?%s" % (BASE, layer, urllib.parse.urlencode(params))

    last = None
    for attempt in range(1, RETRIES + 1):
        try:
            with urllib.request.urlopen(url, timeout=300) as r:
                body = json.load(r)
        except Exception as e:                       # network, timeout, bad JSON
            last = e
        else:
            if "error" in body:
                last = RuntimeError(body["error"])
            else:
                return body

        if attempt < RETRIES:
            sleep = BACKOFF * attempt
            print("  retry %d/%d after %.0fs (%s)" % (attempt, RETRIES, sleep, last),
                  file=sys.stderr)
            time.sleep(sleep)

    raise RuntimeError("giving up after %d attempts: %s" % (RETRIES, last))


def count(layer, where):
    return query(layer, {
        "where": where, "returnCountOnly": "true", "f": "json",
    })["count"]


def fetch(layer, state, out_path):
    where = state_where(layer, state)

    expected = count(layer, where)
    print("layer %d, %s: %d features expected" % (layer, state, expected))

    # Resume: pick up after the highest OBJECTID already written.
    last_oid = 0
    written = 0
    if os.path.exists(out_path):
        with open(out_path) as f:
            for line in f:
                if line.strip():
                    written += 1
                    last_oid = max(last_oid, json.loads(line)["OBJECTID"])
        if written:
            print("resuming: %d already written, last OBJECTID %d"
                  % (written, last_oid))

    fields = ",".join(["OBJECTID"] + FIELDS[layer])
    started = time.time()

    with open(out_path, "a") as out:
        while True:
            body = query(layer, {
                "where": "(%s) AND OBJECTID > %d" % (where, last_oid),
                "outFields": fields,
                "orderByFields": "OBJECTID",
                "returnGeometry": "true",
                "outSR": "4326",
                # ~0.1 m. The pack quantizes harder than this; the margin keeps
                # the decision downstream rather than baking it into the pull.
                "geometryPrecision": "6",
                "resultRecordCount": str(PAGE),
                "f": "json",
            })

            features = body.get("features", [])
            if not features:
                break

            for feat in features:
                rec = dict(feat["attributes"])
                rec["rings"] = feat.get("geometry", {}).get("rings", [])
                out.write(json.dumps(rec, separators=(",", ":")) + "\n")
                last_oid = max(last_oid, rec["OBJECTID"])

            written += len(features)
            out.flush()

            rate = written / max(time.time() - started, 1e-6)
            print("  %d/%d (%.1f%%)  %.0f feat/s" %
                  (written, expected, 100.0 * written / expected, rate))

    print("done: %d written, %d expected" % (written, expected))
    if written != expected:
        print("MISMATCH: %+d" % (written - expected), file=sys.stderr)
        return 1

    print("count matches the service")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--state", required=True,
                    help="two-letter state (e.g. CA), or ALL for the whole US")
    ap.add_argument("--layer", type=int, choices=(1, 2), required=True,
                    help="1 = township, 2 = section")
    ap.add_argument("--out", required=True, help="output .ndjson path")
    args = ap.parse_args()

    return fetch(args.layer, args.state.upper(), args.out)


if __name__ == "__main__":
    sys.exit(main())
