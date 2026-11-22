#!/usr/bin/env python3
"""Ask one byte of every report link in the Lessons Learned catalog.

A details page offers "Open the full report", and a link that 404s in the field
is worse than none. This reads the catalog the way the plugin does (the bundled
snapshot by default, or a depot catalog), asks each report URL for its first
byte with a ranged GET, and exits non-zero when any fails.

It follows redirects the way a phone's browser would, and reports where a link
ends up when that is a different host, because a report that has moved off
lessons.wildfire.gov is worth knowing about even when it still answers.

Polite on purpose: four requests at a time, a pause between them, a plain
User-Agent. About 2,300 links take five to ten minutes.

Usage
-----
    ./lessons_verify.py                          # the bundled snapshot
    ./lessons_verify.py --catalog lessons.json.gz
    ./lessons_verify.py --depot                  # the depot's manifest and catalog too
    ./lessons_verify.py --limit 50               # a quick look
    ./lessons_verify.py --write-dead             # record the 404s for the builder

--write-dead writes lessons_dead.txt beside this script: one link per line that
the server says does not exist (404 or 410). lessons_build.py leaves those links
out, so a details page never offers a report that is not there. Timeouts and
other failures are not written: a server that did not answer has not said no.
"""

import argparse
import concurrent.futures
import gzip
import hashlib
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSET = os.path.join(HERE, "..", "app", "src", "main", "assets", "lessons", "lessons.json")
DEPOT = "https://mapdepot.takwerx.org/lces/lessons/"
UA = "takwerx-lces-verify/1 (+https://github.com/takwerx)"


def load(path):
    with open(path, "rb") as fh:
        body = fh.read()
    if body[:2] == b"\x1f\x8b":
        body = gzip.decompress(body)
    return json.loads(body.decode("utf-8"))


def one_byte(url, timeout=30):
    """(ok, status, final url, note)."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-0"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            got = r.read(1)
            code = r.status
            final = r.geturl()
        if code not in (200, 206):
            return False, code, final, "status"
        if not got:
            # data.fs.usda.gov taught this one: 204 or an empty 200 is not a report.
            return False, code, final, "empty body"
        return True, code, final, ""
    except urllib.error.HTTPError as e:
        return False, e.code, url, "http error"
    except Exception as e:  # noqa: BLE001 -- every failure is a finding
        return False, 0, url, type(e).__name__ + ": " + str(e)[:80]


def check_depot():
    print("depot: %s" % DEPOT)
    req = urllib.request.Request(DEPOT + "lessons.json", headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            manifest = json.load(r)
    except Exception as e:  # noqa: BLE001
        print("  FAIL manifest: %s" % e)
        return False
    entry = (manifest.get("files") or {}).get("lessons.json.gz") or {}
    req = urllib.request.Request(DEPOT + "lessons.json.gz", headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            gz = r.read()
    except Exception as e:  # noqa: BLE001
        print("  FAIL catalog: %s" % e)
        return False
    sha = hashlib.sha256(gz).hexdigest()
    ok = sha == entry.get("sha256")
    count = len(json.loads(gzip.decompress(gz))["incidents"])
    print("  %s manifest names %s, catalog is %s; %d incidents (manifest %s), built %s"
          % ("OK" if ok and count == entry.get("count") else "FAIL",
             entry.get("sha256", "?")[:12], sha[:12], count, entry.get("count"),
             manifest.get("built")))
    return ok and count == entry.get("count")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--catalog", default=ASSET, help="catalog to read (json or json.gz)")
    ap.add_argument("--depot", action="store_true", help="also check the depot copy")
    ap.add_argument("--limit", type=int, default=0, help="check only the first N links")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--write-dead", action="store_true",
                    help="write the links the server says are gone to lessons_dead.txt")
    args = ap.parse_args()

    good = True
    if args.depot:
        good = check_depot() and good

    rows = load(args.catalog)["incidents"]
    links = [(r["id"], r["name"], r["report"]) for r in rows if r.get("report")]
    missing = [r for r in rows if not r.get("report")]
    if args.limit:
        links = links[:args.limit]
    print("%d report links to check (%d incidents carry none)" % (len(links), len(missing)))

    lock = threading.Lock()
    done = [0]
    failures, moved = [], []

    def run(item):
        gid, name, url = item
        ok, code, final, note = one_byte(url)
        time.sleep(0.25)
        with lock:
            done[0] += 1
            if done[0] % 200 == 0:
                print("  %d of %d" % (done[0], len(links)), file=sys.stderr)
            if not ok:
                failures.append((code, note, name, url))
            elif urllib.parse.urlsplit(final).netloc != urllib.parse.urlsplit(url).netloc:
                moved.append((name, url, final))

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as ex:
        list(ex.map(run, links))

    for name, url, final in sorted(moved):
        print("  moved  %s\n         %s -> %s" % (name, url, final))
    for code, note, name, url in sorted(failures, key=lambda f: (f[0], f[2])):
        print("  FAIL %s %s  %s\n         %s" % (code or "---", note, name, url))
    if args.write_dead and not args.limit:
        dead = sorted(url for code, _, _, url in failures if code in (404, 410))
        with open(os.path.join(HERE, "lessons_dead.txt"), "w") as fh:
            fh.write("# Report links the LLC's server says do not exist (404/410), from\n"
                     "# lessons_verify.py --write-dead. lessons_build.py leaves them out.\n")
            for url in dead:
                fh.write(url + "\n")
        print("wrote %d dead links to lessons_dead.txt" % len(dead))
    print("%d of %d links answered; %d failed; %d answered from another host"
          % (len(links) - len(failures), len(links), len(failures), len(moved)))
    if failures:
        good = False
    sys.exit(0 if good else 1)


if __name__ == "__main__":
    main()
