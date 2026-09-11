#!/usr/bin/env python3
"""
Build the Comms catalog: catalog.json, the radio sites on the map and the nets on them.

The plugin reads this from https://mapdepot.takwerx.org/comms/catalog.json and carries
a copy in its APK (app/src/main/assets/catalog.json) as the fallback. Adding a site or
a net is a row in a source below, never a plugin release.

Two tables, joined by net designator:

  nets      one row per net that is on at least one site: the
            designator ("CDF C5"), its name and its agency. A net exists here
            only because a site carries it.
  sites     where: name, coordinates, county, ground elevation, manager.
  channels  one net on one site: designator, site, callsign, the tone used AT THAT
            SITE, notes, source, as-of date. A channel never restates a frequency.

Where comes from GIS -- the Cal OES FIRENET and CESRS relay services and the USFS
communications-site layer -- and from the operator's own rows in sites.csv. What is
on a site comes from the FIRENET and CESRS attributes and from sites.csv.

Frequencies are the mobile's view throughout, the way a radio is programmed: rx is
what you listen on (the repeater's output), tx is what you transmit on. The Cal OES
services name them from the repeater's side and are swapped on the way in.

Usage:
    ./build_comms_catalog.py --out ../app/src/main/assets/catalog.json
    ./build_comms_catalog.py --dry-run                 # fetch, parse, report only
    ./build_comms_catalog.py --out catalog.json --upload   # and rclone to R2

Network fetches are cached under
~/.cache/takwerx-comms/ ; --refresh throws the cache away.
"""

import argparse
import csv
import datetime
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import urllib.parse

REMOTE = "r2:mapdepot/comms"
UA = "Comms-catalog/0.1 (takwerx)"
CACHE = os.path.expanduser("~/.cache/takwerx-comms")
HERE = os.path.dirname(os.path.abspath(__file__))
FORMAT = 1


CALOES = "https://services.arcgis.com/BLN4oKB0N1YSgvY8/arcgis/rest/services"
FIRENET_RELAYS = CALOES + "/FIRENET_Mobile_Relays/FeatureServer/0"
FIRENET_STATE = CALOES + "/FIRENET_Mobile_Relay_State/FeatureServer/0"
CESRS = CALOES + "/CESRS_Relay_Stations/FeatureServer/0"
USFS_SITES = "https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_SpecialUsesCommunicationsSites_01/MapServer/0"
EPQS = "https://epqs.nationalmap.gov/v1/json"
TIGER_COUNTY = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/State_County/MapServer/1"

# The California standard 32 tones (MACS 441-1, "California Standard 32 Tones / 32 NACs").
# CAL FIRE, FIRESCOPE, BLM, BIA and USFS in California all number them this way, and
# the call plans say "Tone 8" as often as "103.5".
TONES = {
    1: 110.9, 2: 123.0, 3: 131.8, 4: 136.5, 5: 146.2, 6: 156.7, 7: 167.9, 8: 103.5,
    9: 100.0, 10: 107.2, 11: 114.8, 12: 127.3, 13: 141.3, 14: 151.4, 15: 162.2, 16: 192.8,
    17: 67.0, 18: 71.9, 19: 74.4, 20: 77.0, 21: 79.7, 22: 82.5, 23: 85.4, 24: 88.5,
    25: 91.5, 26: 94.8, 27: 97.4, 28: 118.8, 29: 173.8, 30: 179.9, 31: 186.2, 32: 203.5,
}
TONE_NUMBER = {v: k for k, v in TONES.items()}

# Which agency a net designator belongs to, from its prefix. The site filter in the
# plugin counts a site under every agency whose net it carries, so this is what
# "CAL FIRE (61)" means.
NET_AGENCY = [
    (re.compile(r"^CDF\b|^CDFA"), "CAL FIRE"),
    # CAL FIRE unit local nets, by unit designator. LAC, ORC, VNC, XSD and the like
    # are county departments and stay "Local".
    (re.compile(r"^(AEU|BDU|BEU|BTU|CZU|FKU|HUU|LMU|LNU|MEU|MMU|MRN|NEU|RRU|SBC|SCU|SHU|SKU|SLU|TCU|TGU|TUU)\b"), "CAL FIRE"),
    (re.compile(r"^NIFC\b|^AG-|^AIRGUARD|^IA "), "NIFC"),
    (re.compile(r"^OES\b|^CESRS"), "Cal OES"),
    (re.compile(r"^VFIRE|^VTAC|^VCALL|^CALCORD|^VMED|^UTAC|^UCALL|^8TAC|^8CALL|^NIFOG"), "Interop"),
]

S = lambda **k: k  # noqa: E731


# ---- fetching ----------------------------------------------------------------------

def cached(key, fetch, refresh=False, binary=False):
    """Run fetch() once and keep its result under the cache directory."""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, hashlib.sha1(key.encode()).hexdigest()[:16] + ("" if binary else ".txt"))
    if not refresh and os.path.isfile(path):
        with open(path, "rb" if binary else "r") as f:
            return f.read()
    data = fetch()
    with open(path, "wb" if binary else "w") as f:
        f.write(data)
    return data


def get(url, timeout=120):
    # curl rather than urllib: Homebrew's Python ships without a certificate store,
    # and every host here is HTTPS.
    r = subprocess.run(["curl", "-fsSL", "--max-time", str(timeout), "-A", UA, url], capture_output=True)
    if r.returncode != 0:
        raise RuntimeError("curl %s: exit %d %s" % (url, r.returncode, r.stderr.decode("utf-8", "replace").strip()))
    return r.stdout


def getj(url, refresh=False):
    return json.loads(cached(url, lambda: get(url).decode("utf-8"), refresh))


def arcgis_all(layer, where="1=1", fields="*", refresh=False):
    """Every feature of a layer, WGS84, paging past the server's record cap."""
    out, offset = [], 0
    while True:
        q = urllib.parse.urlencode(dict(where=where, outFields=fields, f="json", returnGeometry="true",
                                        outSR="4326", resultOffset=offset, resultRecordCount=1000))
        d = getj(layer + "/query?" + q, refresh)
        if "error" in d:
            raise RuntimeError("%s: %s" % (layer, d["error"]))
        feats = d.get("features", [])
        out.extend(feats)
        if not d.get("exceededTransferLimit") or not feats:
            return out
        offset += len(feats)


# ---- sites ------------------------------------------------------------------------------

def tone_value(s):
    """
    '103.5' -> 103.5; '0.0' -> None (carrier squelch); 'OST' and NACs stay strings.

    A call plan names a tone by its California standard number as often as by its
    frequency, so 'T8', 'tone 8' and a bare '8' resolve through the table. There is
    no ambiguity: every CTCSS tone is 67.0 Hz or higher and carries a decimal, and
    every tone number is 1 to 32.
    """
    s = (s or "").strip()
    if s in ("", "0.0", "0", "CSQ", "None", "none", "N/A"):
        return None
    m = re.fullmatch(r"(?:[Tt](?:one)?[ -]?)?(\d{1,2})", s)
    if m and 1 <= int(m.group(1)) <= 32:
        return TONES[int(m.group(1))]
    try:
        v = float(s)
        return v if v > 0 else None
    except ValueError:
        return s


def net_agency(designator):
    for rx, agency in NET_AGENCY:
        if rx.search(designator):
            return agency
    return "Local"


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def norm_name(s):
    """'MT. LOWE' and 'Mount Lowe' meet here."""
    k = re.sub(r"\(.*?\)", "", s.upper())
    k = re.sub(r"[^A-Z0-9 ]", " ", k)
    k = re.sub(r"\bMT\b|\bMTN\b|\bMOUNTAIN\b", "MOUNT", k)
    k = re.sub(r"\bPK\b", "PEAK", k)
    k = re.sub(r"\bSAINT\b", "ST", k)
    return re.sub(r"\s+", " ", k).strip()


# The words a call plan drops when it is short of room on a map sheet. "MOUNT" is
# the spelling after norm_name, which folds MT, MTN and MOUNTAIN into it -- a
# word listed here as "MOUNTAIN" would never match anything. Not to be confused
# with GENERICS below, which is the title-case list the names-database lookup uses.
DROPPED = ("MOUNT", "PEAK", "RIDGE", "HILL", "BUTTE", "SUMMIT", "POINT", "LOOKOUT")

# Two records, two different names for one repeater: one names the pass a site sits
# on, the other what is built there. No amount of loosening the letters joins a pair
# like that, so each one is written down by hand after it has been checked as one
# place -- same mountain, same tone on the same net.
#
# The pairs are data, not code, and they live with the rows they belong to and
# outside this repo for the same reason the rows do: "<name> = <name>", one per
# line, in comms-site-aliases.txt beside the first --rows file, or wherever
# --same-site points. With no such file every site is filed under its own name.
SAME_SITE = {}


def load_same_site(path):
    if not path or not os.path.exists(path):
        return
    for line in open(path):
        line = line.split("#")[0].strip()
        printed, _, filed = line.partition("=")
        if line and filed.strip():
            SAME_SITE[norm_name(printed)] = filed.strip()
    if SAME_SITE:
        print("site aliases: %d pairs of names that are one site" % len(SAME_SITE))


def alias(name):
    """The name this catalog files a site under, normalized."""
    k = norm_name(name)
    return norm_name(SAME_SITE[k]) if k in SAME_SITE else k


def title_case(s):
    small = {"of", "the", "and", "at", "de", "la", "del"}
    words = []
    for w in s.lower().split():
        words.append(w if w in small and words else w.capitalize())
    out = " ".join(words)
    out = re.sub(r"\bMt\b\.?", "Mount", out)
    out = re.sub(r"'([A-Z])", lambda m: "'" + m.group(1).lower(), out)
    return out


def haversine(lat1, lon1, lat2, lon2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1, math.sqrt(a)))


class Sites:
    """Sites keyed by position: two sources naming the same mountaintop become one site."""

    # Two records this close are one site whatever they are called; with the same
    # normalized name they merge from further, because the USFS layer rounds some
    # coordinates (Santiago Peak is 3 km off the Cal OES point).
    NEAR_M = 500
    SAME_NAME_M = 6000

    def __init__(self):
        self.sites = []
        self.named = {}          # source -> the site names it lists, normalized

    def lists(self, source, names):
        """Record what one source calls its sites, for the guard in find()."""
        self.named.setdefault(source, set()).update(alias(n) for n in names)

    def add(self, name, lat, lon, st, county="", elev_m=None, ant_m=None, manager="", source="", priority=9):
        key = norm_name(name)
        for s in self.sites:
            d = haversine(lat, lon, s["lat"], s["lon"])
            # One source listing two names is listing two sites, however close they
            # sit. One of them has two names 13 m apart carrying the same three
            # channels on a different tone, which is proof they are not the same
            # repeater.
            if source and source in s["sources"] and norm_name(s["name"]) != key:
                continue
            if d <= self.NEAR_M or (d <= self.SAME_NAME_M and norm_name(s["name"]) == key):
                if priority < s["_prio"]:
                    s["name"], s["lat"], s["lon"], s["_prio"] = name, lat, lon, priority
                if county and not s["county"]:
                    s["county"] = county
                if elev_m is not None and s["elev_m"] is None:
                    s["elev_m"] = elev_m
                if ant_m is not None and s["ant_m"] is None:
                    s["ant_m"] = ant_m
                if manager and manager not in s["managers"]:
                    s["managers"].append(manager)
                if source and source not in s["sources"]:
                    s["sources"].append(source)
                return s
        s = S(name=name, lat=round(lat, 5), lon=round(lon, 5), st=st, county=county, elev_m=elev_m, ant_m=ant_m,
              managers=[manager] if manager else [], sources=[source] if source else [], _prio=priority)
        self.sites.append(s)
        return s

    def find(self, name, st, bounds=None, source=""):
        """
        A site already on the map by this name.

        <p>{@code bounds} is the forest or park the row belongs to. Without it a name
        match reaches across the whole state and lands on the wrong mountain:
        Stanislaus and San Bernardino both have a Strawberry Peak, and the Stanislaus
        row attached itself to the San Bernardino site 400 km away. A candidate
        outside the row's own boundary is a different place with the same name.

        <p>Call plans abbreviate, and they do not agree with each other: the CAL FIRE
        command sheets say "Bloomer" and "Hatchet" where the Cal OES layer says
        Bloomer Mountain and Hatchet Mountain, "Pierce" where it says Mount Pierce,
        "Antelope Peak" where it says Antelope Mountain, and "Coldsprings" where it
        says Cold Spring. So the name is tried four ways, loosest last, and each way
        has to land on exactly one site -- because "Red Mountain" is two different
        mountains in this catalog and guessing between them would put a repeater in
        the wrong county.

        <p>The one thing no amount of loosening reaches is a site two records call
        by different names -- one by the pass, the other by what is built on it.
        Those are in {@code SAME_SITE}, one line each, and there are very few.

        <p>Loosening this far needs the guard the merger already has, or it undoes
        it: one source listing two names is listing two sites. One of them gives
        Six Rivers a Horse Mountain and a Horse Ridge on different tones, and with
        the generic gone both read "HORSE", so Horse Ridge landed on Horse Mountain
        and the same source appeared to contradict itself. {@code others} is what
        that source's own list calls something else, and the two readings that strip
        a generic may not reach one of those, however well the letters fit.

        <p>And the last two readings are only safe with somewhere to stand. Taking
        every generic off leaves words like PINE and BLACK that half the mountains
        in the state answer to, so with no boundary at all they put the command
        sheet's Pine Hill on Pine Mountain above Los Angeles and BLM's Blacks on
        Black Peak in Arizona. A row inside a boundary may be read that loosely; a
        row with none gets a leading Mount or a trailing generic and no more.
        """
        def ok(s):
            return s["st"] == st and (bounds is None or (
                bounds[0] <= s["lat"] <= bounds[2] and bounds[1] <= s["lon"] <= bounds[3]))

        key = alias(name)
        others = self.named.get(source, set()) - {key}
        here = [(s, alias(s["name"])) for s in self.sites if ok(s)]
        reads = [lambda k: k, variants]
        if bounds is not None:
            reads += [base_name, squash_name]

        # Loosest last, and each reading has to land on exactly one site. The order
        # matters: Red Mountain and Red Hill are both "RED" once the generic is gone,
        # so the reading that tells them apart gets its answer first.
        for n, read in enumerate(reads):
            want = read(key)
            # The guard belongs on the readings that can swap one generic for
            # another, which is where two real places meet. It does not belong on
            # the first two: an exact match is exact, and variants only ever adds or
            # drops a generic, so it reaches Delilah Lookout from Delilah and never
            # reaches Horse Ridge from Horse Mountain.
            hit = [s for s, k in here if match(read(k), want) and (n < 2 or k not in others)]
            if len(hit) == 1:
                return hit[0]
        return None


def match(a, b):
    """Two readings of a name agree: sets overlap, strings are equal."""
    if isinstance(a, set):
        return bool(a & b)
    return a == b


def variants(k):
    """The name with a leading Mount, or a trailing generic, added."""
    out = {k}
    out.add(k[6:] if k.startswith("MOUNT ") else "MOUNT " + k)
    for g in DROPPED:
        out.add(k + " " + g)
    return out


def base_name(k):
    """The name with a leading Mount and every trailing generic taken off."""
    if k.startswith("MOUNT "):
        k = k[6:]
    w = k.split()
    while len(w) > 1 and w[-1] in DROPPED:
        w.pop()
    return " ".join(w)


def squash_name(k):
    """The bare name with spaces and a plural ignored: Coldsprings is Cold Spring."""
    return base_name(k).replace(" ", "").rstrip("S")


def elevation_m(lat, lon, refresh=False):
    q = urllib.parse.urlencode(dict(x=lon, y=lat, units="Meters", wkid=4326, includeDate="false"))
    try:
        d = getj(EPQS + "?" + q, refresh)
        v = float(d["value"])
        return round(v) if -500 < v < 9000 else None
    except Exception as e:  # noqa: BLE001
        print("  elevation lookup failed for %.4f,%.4f: %s" % (lat, lon, e), file=sys.stderr)
        return None


GNIS = "https://carto.nationalmap.gov/arcgis/rest/services/geonames/MapServer"
# Landforms (summits, ridges, buttes), then cultural points (towers, locales).
GNIS_LAYERS = (5, 9)
# A repeater is on one of its own forest's mountains, so a candidate this far from
# the rest of that forest's sites is a different mountain with the same name --
# California has a dozen Black Mountains. Los Padres runs 400 km end to end, so the
# cap is generous and anything landing past REVIEW_KM from the anchor is reported.
GNIS_MAX_KM = 250
GNIS_REVIEW_KM = 120
# A repeater sits inside the forest or park it serves, or just outside it on a peak
# that looks into it. The boundary is the real constraint; the anchor is only a
# tiebreak, because a centroid put Yosemite's Mount Hoffmann in Fresno County.
AREA_PAD_KM = 25
# With no boundary to check against (BLM districts, CAL FIRE units) the anchor is all
# there is, so it has to be held tightly.
NO_AREA_MAX_KM = 90

USFS_BOUNDARY = "https://apps.fs.usda.gov/arcx/rest/services/EDW/EDW_ForestSystemBoundaries_01/MapServer/0"
NPS_BOUNDARY = ("https://services1.arcgis.com/fBc8EJBxQRMcHlei/arcgis/rest/services/"
                "NPS_Land_Resources_Division_Boundary_and_Tract_Data_Service/FeatureServer/2")


def area_bounds(area, refresh=False):
    """
    The bounding box of a named forest or park, padded, as (south, west, north, east).

    {@code area} is "USFS:<forest name>" or "NPS:<four-letter unit code>". Web
    Mercator comes back from both services; only the box is wanted, so the corners
    are converted rather than the geometry.
    """
    kind, _, name = area.partition(":")
    pad_km = AREA_PAD_KM
    if kind == "CO":
        # A command sheet names no forest, only the county its dot sits in. That is
        # boundary enough, and a tight one: 5 km of slack for a site on the line and
        # for a dot printed a little off, not the 25 km a forest gets.
        pad_km = 5
        url = TIGER_COUNTY + "/query?" + urllib.parse.urlencode(dict(
            where="NAME='%s County' AND STATE='06'" % name.replace("'", "''"),
            returnExtentOnly="true", f="json"))
    elif kind == "USFS":
        url = USFS_BOUNDARY + "/query?" + urllib.parse.urlencode(dict(
            where="forestname='%s'" % name.replace("'", "''"), returnExtentOnly="true", f="json"))
    elif kind == "NPS":
        # A park may be administered as one unit and mapped as two: Sequoia and
        # Kings Canyon is SEQU plus KICA, so a comma-separated list is a union.
        codes = ",".join("'%s'" % c.strip() for c in name.split(",") if c.strip())
        url = NPS_BOUNDARY + "/query?" + urllib.parse.urlencode(dict(
            where="UNIT_CODE IN (%s)" % codes, returnExtentOnly="true", f="json"))
    else:
        return None
    try:
        e = getj(url, refresh).get("extent")
        if not e:
            return None
        wkid = (e.get("spatialReference") or {}).get("latestWkid") or 4326

        def unproject(x, y):
            if wkid == 4326:
                return y, x
            lon = x / 20037508.34 * 180
            lat = math.degrees(2 * math.atan(math.exp(y / 20037508.34 * math.pi)) - math.pi / 2)
            return lat, lon

        s_lat, w_lon = unproject(e["xmin"], e["ymin"])
        n_lat, e_lon = unproject(e["xmax"], e["ymax"])
        pad = pad_km / 111.0
        return (s_lat - pad, w_lon - pad / max(0.2, math.cos(math.radians(s_lat))),
                n_lat + pad, e_lon + pad / max(0.2, math.cos(math.radians(n_lat))))
    except Exception as ex:  # noqa: BLE001
        print("  boundary lookup failed for %s: %s" % (area, ex), file=sys.stderr)
        return None
GENERICS = ("", " Mountain", " Peak", " Ridge", " Hill", " Butte", " Summit", " Point",
            " Lookout", " Mount")


def gnis(name, state, anchor, refresh=False, bounds=None, max_km=None):
    """
    The nearest USGS-named landform to {@code anchor} whose name starts with
    {@code name}. The guide abbreviates ("Bully Choop" for Bully Choop Mountain), so
    the query is a prefix and the exact name wins ties.

    <p>{@code bounds} is the forest or park the site belongs to: a candidate outside
    it is a different place with the same name, whatever its distance.

    <p>With no anchor the name has to be unambiguous: exactly one place in the state
    answers to it, or nothing is returned. That is what bootstraps a forest whose
    sites are all absent from the GIS layers -- one unambiguous summit anchors the
    rest.

    @return (lat, lon, official name, county, km from the anchor) or None
    """
    base = re.sub(r"\s+", " ", name).strip()
    base = re.sub(r"\b(Lookout|Benchmark|LKO|BM)\b\.?$", "", base).strip()
    if not base:
        return None
    like = base.replace("'", "''") + "%"
    best = None
    for layer in GNIS_LAYERS:
        q = urllib.parse.urlencode(dict(
            where="state_alpha='%s' AND gaz_name LIKE '%s'" % (state, like),
            outFields="gaz_name,gaz_featureclass,county_name", f="json",
            returnGeometry="true", outSR="4326", resultRecordCount=200))
        try:
            d = getj(GNIS + "/%d/query?" % layer + q, refresh)
        except Exception as e:  # noqa: BLE001
            print("  names lookup failed for %r: %s" % (name, e), file=sys.stderr)
            continue
        for f in d.get("features", []):
            g = f.get("geometry") or {}
            pts = g.get("points") or ([[g["x"], g["y"]]] if "x" in g else [])
            a = f["attributes"]
            got = (a.get("gaz_name") or "").strip()
            # A prefix match is only the same place when the extra words are a
            # generic: "Black Mountain" yes, "Blackwood Canyon" no.
            tail = got[len(base):].strip()
            if got.lower() != base.lower() and (" " + tail) not in GENERICS:
                continue
            for x, y in pts:
                # The boundary is the first thing asked, with or without an anchor.
                # It used to be asked only when there was one, so "unambiguous" meant
                # unambiguous in the whole state even for a row that had named its
                # county -- and a county is exactly the thing that makes a name like
                # Seigler or Sonoma Mountain unambiguous.
                if bounds is not None and not (bounds[0] <= y <= bounds[2]
                                               and bounds[1] <= x <= bounds[3]):
                    continue
                if anchor is None:
                    # Unambiguous or nothing: two places by this name inside the
                    # boundary and there is no way to tell which one is meant.
                    here = (round(y, 3), round(x, 3))
                    if best is not None and best[5] != here:
                        return None
                    best = ((0, 0), y, x, got, (a.get("county_name") or "").strip(), here)
                    continue
                d_km = haversine(anchor[0], anchor[1], y, x) / 1000.0
                if d_km > (max_km or GNIS_MAX_KM):
                    continue
                score = (0 if got.lower() == base.lower() else 1, d_km)
                if best is None or score < best[0]:
                    best = (score, y, x, got, (a.get("county_name") or "").strip(), None)
    if best is None:
        return None
    return best[1], best[2], best[3], best[4], best[0][1]


def county_of(lat, lon, refresh=False):
    q = urllib.parse.urlencode(dict(geometry="%f,%f" % (lon, lat), geometryType="esriGeometryPoint", inSR=4326,
                                    spatialRel="esriSpatialRelIntersects", outFields="NAME,STATE",
                                    returnGeometry="false", f="json"))
    try:
        # "/query?", not "?": without it this asks the layer to describe itself, which
        # answers 200 with no features, so every county came back empty and silent.
        d = getj(TIGER_COUNTY + "/query?" + q, refresh)
        f = d.get("features") or []
        if not f:
            return "", ""
        a = f[0]["attributes"]
        name = re.sub(r"\s+County$", "", a.get("NAME", ""))
        return name, a.get("STATE", "")
    except Exception as e:  # noqa: BLE001
        print("  county lookup failed for %.4f,%.4f: %s" % (lat, lon, e), file=sys.stderr)
        return "", ""


STATE_FIPS = {"06": "CA", "32": "NV", "41": "OR", "04": "AZ"}


def build(args):
    today = datetime.date.today().isoformat()
    sources = []

    # ---- nets --------------------------------------------------------------------
    # Every net in this catalog arrives attached to a site. There is no separate
    # dictionary to start from.
    #
    # MACS 441-1 used to be that dictionary and it was the wrong shape for this
    # plugin. It is the whole California channel plan, so most of what it carries
    # has no repeater location and never will: of the 522 nets it produced, 152
    # were simplex -- car to car, there is no repeater to stand anywhere -- 2 were
    # incident portables, and 140 were repeater pairs whose mountain we do not
    # know. Only 210 were on a site.
    #
    # It also put the same net in twice. The plan writes "RRU 2" where the record
    # that knows the sites writes "RRU2 R", so a search for the plan's spelling
    # found an entry with nine sites hiding behind an identical one with none.
    # Eighteen pairs like that. And its table parse was bleeding the config column
    # into the name ("10 Simplex", "294 Base-Fixed-Mob"), so some rows were not
    # even data.
    #
    # The operator, 2026-09-11: "this is for fixed sites ok? everything needs a
    # site", and then "just get rid of the macs 441 as useful". A net is now
    # whatever a source that places sites says is on one.
    nets = []
    by_id = {}

    def net_key(s):
        """'OES-V1' in the Cal OES layers is 'OES V1' in the plan."""
        s = re.sub(r"\s+", " ", s.strip().upper())
        s = re.sub(r"^OES-V", "OES V", s)
        return s

    sites = Sites()
    channels = []

    # ---- Cal OES FIRENET: mobile relays, state and area ------------------------------
    for layer, title, prio in ((FIRENET_STATE, "Cal OES FIRENET Mobile Relay Sites (state)", 1),
                               (FIRENET_RELAYS, "Cal OES FIRENET Mobile Relays", 2)):
        feats = arcgis_all(layer, refresh=args.refresh)
        print("%s: %d" % (title, len(feats)))
        sources.append(S(id=slug(title), title=title, url=layer, as_of=today, kind="sites+channels"))
        for f in feats:
            a, g = f["attributes"], f["geometry"]
            name = (a.get("F_NAME") or "").strip()
            if not name:
                continue
            site = sites.add(name, g["y"], g["x"], "CA", manager="Cal OES", source=title, priority=prio)
            chan = a.get("CHANNEL") or a.get("F_NAME2") or ""
            if not chan.upper().startswith("OES"):
                chan = ""
            net = net_key(chan) if chan else ""
            dtmf = (a.get("DTMF_NUMB") or "").strip()
            if not dtmf and re.fullmatch(r"\d{3}", (a.get("F_NAME2") or "").strip()):
                dtmf = a["F_NAME2"].strip()
            tone = tone_value((a.get("CTCSS_FREQ") or "").strip())
            tone_num = (a.get("CTCSS_NUMB") or "").strip()
            notes = []
            if dtmf:
                notes.append("DTMF " + dtmf)
            if a.get("LAYER"):
                notes.append({"MR-State": "state mobile relay", "MR-Area": "area mobile relay"}.get(a["LAYER"], a["LAYER"]))
            # The layer names the net and gives its pair. There is no plan to check it
            # against any more, so the first source to name a net defines it and the
            # rest attach to it.
            override = {}
            if net and net not in by_id:
                rep_tx, rep_rx = (a.get("TX_FREQ") or "").strip(), (a.get("RX_FREQ") or "").strip()
                by_id[net] = S(id=net, name=net, agency="Cal OES", rx=rep_tx, tx=rep_rx, rx_tone=None,
                               tx_tone="OST", band="N", power="H", mode="A", config="Base-Fixed-Mobile",
                               usage="", remarks=title, portable=False)
                nets.append(by_id[net])
            if net:
                channels.append(S(site=site, net=net, callsign=(a.get("CALLSIGN") or "").strip(), tx_tone=tone,
                                  tx_tone_num=int(tone_num) if tone_num.isdigit() else None, rx_tone=None,
                                  rx_tone_num=None, notes=" · ".join(notes), source=title, as_of=today, **override))

    # ---- Cal OES CESRS: one statewide pair, a tone per mountaintop ---------------------
    feats = arcgis_all(CESRS, refresh=args.refresh)
    title = "Cal OES CESRS Relay Stations"
    print("%s: %d" % (title, len(feats)))
    sources.append(S(id="cesrs", title=title, url=CESRS, as_of=today, kind="sites+channels"))
    for f in feats:
        a, g = f["attributes"], f["geometry"]
        name = (a.get("MOUNTAIN") or "").strip()
        if not name:
            continue
        name = name.replace("Sugerloaf", "Sugarloaf").replace("Montain", "Mountain")
        county = (a.get("COUNTY") or "").strip().replace("Humbolt", "Humboldt")
        st = "NV" if "Nevada" in (a.get("NOTES") or "") else "CA"
        site = sites.add(name, g["y"], g["x"], st, county=county, manager="Cal OES", source=title, priority=3)
        relay = (a.get("RELAYSINGL") or "").strip()
        tone_num = int(relay) if relay.isdigit() and int(relay) in TONES else None
        notes = []
        route, access = (a.get("ROUTE") or "").strip(), (a.get("ACCESSCODE") or "").strip()
        if route:
            notes.append(route + " route")
        if access:
            notes.append("access " + access)
        pl_a, pl_b = (a.get("PL_A") or "").strip(), (a.get("PL_B") or "").strip()
        if pl_a and pl_a.lower() != "none":
            notes.append("codes " + pl_a + (" / " + pl_b if pl_b and pl_b.lower() != "none" else ""))
        if relay and tone_num is None:
            notes.append("relay " + relay)
        n = (a.get("NOTES") or "").strip()
        if n:
            notes.append(n)
        channels.append(S(site=site, net="CESRS", callsign=(a.get("CALL_SIGN") or "").strip(),
                          tx_tone=TONES.get(tone_num), tx_tone_num=tone_num, rx_tone=None, rx_tone_num=None,
                          notes=" · ".join(notes), source=title, as_of=today))

    # ---- USFS communications sites: where only ---------------------------------------
    title = "USFS Special Uses Communications Sites"
    usfs = arcgis_all(USFS_SITES, where="state='CA'",
                      fields="communications_site_name,national_forest,district,county,state,site_elevation,latitude,longitude,designation",
                      refresh=args.refresh)
    print("%s (CA): %d" % (title, len(usfs)))
    sources.append(S(id="usfs", title=title, url=USFS_SITES, as_of=today, kind="sites"))
    for f in usfs:
        a, g = f["attributes"], f.get("geometry") or {}
        name = (a.get("communications_site_name") or "").strip()
        lat, lon = a.get("latitude") or g.get("y"), a.get("longitude") or g.get("x")
        if not name or lat is None or lon is None:
            continue
        elev = a.get("site_elevation")
        forest = (a.get("national_forest") or "").strip()
        sites.add(title_case(name), float(lat), float(lon), "CA", county=(a.get("county") or "").strip(),
                  elev_m=round(float(elev) * 0.3048) if elev else None,
                  manager="USFS" + (" · " + forest + " NF" if forest else ""), source=title, priority=8)

    # ---- the operator's rows -------------------------------------------------------------
    # sites.csv beside this script, plus anything named with --rows. A row file may
    # live outside the repo: the Region 5 radio guide is marked CUI, so a catalog
    # built from it is never committed here and never published to the depot.
    #
    # Three passes, because a name can only be placed once something is on the map:
    # rows carrying coordinates first, then rows whose name is already in the GIS
    # layers, then the rest looked up in the USGS names database against the anchor
    # those two passes give each forest.
    row_files = [os.path.join(HERE, "sites.csv")] + list(args.rows or [])
    parsed = []
    for csv_path in row_files:
        if not os.path.isfile(csv_path):
            if csv_path != row_files[0]:
                raise SystemExit("no such row file: " + csv_path)
            continue
        label = os.path.basename(csv_path)
        with open(csv_path, newline="") as f:
            for r in csv.DictReader(l for l in f if not l.lstrip().startswith("#")):
                r = {k.strip(): (v or "").strip() for k, v in r.items() if k}
                if r.get("site"):
                    r["_label"] = label
                    r["_st"] = (r.get("state") or "CA").upper()
                    parsed.append(r)

    # What each source calls its sites, so a loose name match cannot merge two
    # places that source lists apart.
    for r in parsed:
        sites.lists(r.get("source") or r["_label"], [r["site"]])

    area_cache = {}

    def area_of(r):
        """The forest or park boundary a row names, fetched once."""
        area = (r.get("area") or "").strip()
        if not area:
            return None
        if area not in area_cache:
            area_cache[area] = area_bounds(area, args.refresh)
            if area_cache[area] is None:
                print("  no boundary for %s; falling back to the anchor" % area, file=sys.stderr)
        return area_cache[area]

    def anchor_key(r):
        """What a row belongs to: the net's first word, which is the forest or unit."""
        return (r["_st"], net_key(r.get("net", "")).split(" ")[0])

    placed = {}          # id(row) -> site
    anchor_pts = {}      # anchor_key -> [(lat, lon), ...]

    def remember(r, site):
        placed[id(r)] = site
        anchor_pts.setdefault(anchor_key(r), []).append((site["lat"], site["lon"]))

    for r in parsed:                                   # 1: explicit coordinates
        if r.get("lat") and r.get("lon"):
            remember(r, sites.add(r["site"], float(r["lat"]), float(r["lon"]), r["_st"],
                                  county=r.get("county", ""),
                                  elev_m=float(r["elev_m"]) if r.get("elev_m") else None,
                                  ant_m=float(r["ant_m"]) if r.get("ant_m") else None,
                                  manager=r.get("agency", ""), source=r["_label"], priority=0))

    for r in parsed:                                   # 2: a name the GIS layers know
        if id(r) in placed:
            continue
        site = sites.find(r["site"], r["_st"], area_of(r),
                          source=r.get("source") or r["_label"])
        if site is not None:
            remember(r, site)

    # 3a: a forest with nothing placed has no anchor. Bootstrap it on any of its own
    # sites whose name is unambiguous in the state.
    for key in {anchor_key(r) for r in parsed} - set(anchor_pts):
        for r in parsed:
            if id(r) in placed or anchor_key(r) != key:
                continue
            hit = gnis(r["site"], r["_st"], None, args.refresh,
                       bounds=area_of(r))
            if hit is None:
                continue
            lat, lon, official, county, _ = hit
            site = sites.add(official or r["site"], lat, lon, r["_st"], county=county,
                             manager=r.get("agency", ""), source="USGS geographic names", priority=5)
            remember(r, site)
            print("  anchored %s on %s, the one place in %s by that name"
                  % (key[1], official, r["_st"]))
            break

    unplaced = []
    named = 0
    far = []
    homeless = []
    for r in parsed:                                   # 3b: the USGS names database
        if id(r) in placed:
            continue
        bounds = area_of(r)
        if bounds is None:
            # No boundary to check a name against, and a centroid is not enough: the
            # CAL FIRE command nets are statewide, and anchoring on their spread put
            # Boucher Mountain in Calaveras County when the sheet has it in San
            # Diego. A row like this attaches to a site another source already
            # placed, or it is reported. A repeater in the wrong county is worse
            # than a repeater missing.
            #
            # It waits until this pass is done before it gives up, though: the names
            # database is about to put a hundred more mountains on the map, and the
            # command sheets name plenty of them. Asking before that ran is why
            # Command 1's Pine Hill reached for Pine Mountain above Los Angeles when
            # Eldorado's own Pine Hill was one pass away.
            homeless.append(r)
            continue
        if (r.get("area") or "").startswith("CO:"):
            # A county is the whole constraint. Ranking candidates by distance from
            # the rest of the net would be meaningless here -- a command net is the
            # length of the state -- so this asks for the one place in the county by
            # that name, and takes nothing if there are two.
            anchor = None
        else:
            pts = anchor_pts.get(anchor_key(r)) or []
            if not pts:
                unplaced.append((r["_label"], r["site"], r.get("net", ""),
                                 "no site of this forest is placed, so nothing to anchor on"))
                continue
            anchor = (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
        hit = gnis(r["site"], r["_st"], anchor, args.refresh, bounds=bounds)
        if hit is None:
            unplaced.append((r["_label"], r["site"], r.get("net", ""),
                             "the names database has no such place in %s"
                             % (r.get("area") or "range of the other sites")))
            continue
        lat, lon, official, county, d_km = hit
        site = sites.add(official or r["site"], lat, lon, r["_st"], county=county,
                         manager=r.get("agency", ""), source="USGS geographic names", priority=5)
        remember(r, site)
        named += 1
        if d_km > GNIS_REVIEW_KM and not bounds:
            far.append((r["site"], official, county, d_km, anchor_key(r)[1]))
    if named:
        print("USGS geographic names: placed %d rows the GIS layers did not have" % named)
        sources.append(S(id="gnis", title="USGS Geographic Names (GNIS)", url=GNIS, as_of=today,
                         kind="sites"))
    for r in homeless:                                 # 4: by name, with the map fuller
        site = sites.find(r["site"], r["_st"], None,
                          source=r.get("source") or r["_label"])
        # Looking the name up in the names database instead was tried and taken out.
        # The rule 3a bootstraps a forest on -- exactly one place in the state
        # answers to it -- is not safe with no boundary at all, because the site the
        # sheet means may simply not be in the database under the sheet's name, and
        # the one match offered is then somewhere else entirely. It put Command 1's
        # Soda Ridge in Plumas when the sheet has it in Siskiyou, Antelope Peak in
        # Alpine when the sheet has Siskiyou, and Boucher
        # Mountain in Calaveras when the sheet has San Diego -- five wrong out of
        # twenty. A county on the row is what these need, and the sheets print one.
        if site is not None:
            remember(r, site)
        else:
            unplaced.append((r["_label"], r["site"], r.get("net", ""),
                             "no site by this name is on the map, and the row names no "
                             "forest, park or county to look one up in"))

    if far:
        print("  %d landed more than %d km from the rest of their forest; check these by eye:"
              % (len(far), GNIS_REVIEW_KM), file=sys.stderr)
        for name, official, county, d_km, group in sorted(set(far), key=lambda t: -t[3]):
            print("    %-24s -> %s, %s County (%.0f km from %s)"
                  % (name, official, county, d_km, group), file=sys.stderr)

    n_rows = 0
    per_file = {}
    seen_channels = {}
    conflicts = []
    for r in parsed:                                   # every placed row becomes a channel
        site = placed.get(id(r))
        if site is None:
            continue
        if r.get("ant_m"):
            site["ant_m"] = float(r["ant_m"])
        if r.get("agency") and r["agency"] not in site["managers"]:
            site["managers"].append(r["agency"])
        net = net_key(r.get("net", ""))
        if not net:
            continue
        if net not in by_id:
            # A row file may carry no frequencies at all -- one source gives sites,
            # channels and tones and nothing else, by the operator's instruction. Such
            # a net exists for its name and its tone per site; the pane shows a tone on
            # a site either way.
            # The net's own tones, NOT this row's: a repeater net has one pair and a
            # different tone at every site, and the first site's tone is not the net's.
            by_id[net] = S(id=net, name=r.get("net_name") or r.get("net"),
                           agency=r.get("agency") or "Local", rx=r["rx"], tx=r["tx"],
                           rx_tone=tone_value(r.get("net_rx_tone")),
                           tx_tone=tone_value(r.get("net_tx_tone") or "OST"),
                           band="N", power="", mode="A", config="", usage="",
                           remarks=r.get("net_name", ""), portable=False)
            nets.append(by_id[net])
        rxt = tone_value(r.get("rx_tone"))
        txt_ = tone_value(r.get("tx_tone"))
        # One site carries one net once, however many sources describe it. The first
        # row file named wins; a second source agreeing is silent and a second source
        # disagreeing about the tone is reported, because that is a fact one of them
        # has wrong and a person has to settle.
        key = (id(site), net)
        if key in seen_channels:
            first = seen_channels[key]
            if isinstance(txt_, float) and isinstance(first["tx_tone"], float) \
                    and abs(txt_ - first["tx_tone"]) > 0.05:
                conflicts.append((site["name"], net, first["source"], first["tx_tone"],
                                  r.get("source") or r["_label"], txt_))
            continue
        ch = S(site=site, net=net, callsign=r.get("callsign", ""),
               tx_tone=txt_ if isinstance(txt_, float) else None,
               tx_tone_num=TONE_NUMBER.get(txt_) if isinstance(txt_, float) else None,
               rx_tone=rxt if isinstance(rxt, float) else None,
               rx_tone_num=TONE_NUMBER.get(rxt) if isinstance(rxt, float) else None,
               notes=r.get("notes", ""), source=r.get("source") or r["_label"],
               as_of=r.get("as_of") or today)
        seen_channels[key] = ch
        channels.append(ch)
        n_rows += 1
        per_file[r["_label"]] = per_file.get(r["_label"], 0) + 1
    for label, n in sorted(per_file.items()):
        print("%s: %d net rows" % (label, n))
        sources.append(S(id=slug(label), title="Operator rows (" + label + ")", url="",
                         as_of=today, kind="sites+channels"))
    if conflicts:
        print("  %d site/net pairs where the sources disagree about the tone:" % len(conflicts),
              file=sys.stderr)
        for site, net, s1, t1, s2, t2 in sorted(set(conflicts)):
            print("    %-22s %-12s %s says %.1f, %s says %.1f"
                  % (site, net, s1, t1, s2, t2), file=sys.stderr)
    if unplaced:
        print("  %d rows could not be placed:" % len(unplaced), file=sys.stderr)
        for label, name, net, why in sorted(set(unplaced)):
            print("    %s: %s (%s) -- %s" % (label, name, net, why), file=sys.stderr)

    # ---- fill in county and elevation ----------------------------------------------------
    for s in sites.sites:
        if not s["county"] or not s.get("st"):
            county, fips = county_of(s["lat"], s["lon"], args.refresh)
            if county and not s["county"]:
                s["county"] = county
            if fips in STATE_FIPS and s["st"] != STATE_FIPS[fips]:
                s["st"] = STATE_FIPS[fips]
        if s["elev_m"] is None:
            s["elev_m"] = elevation_m(s["lat"], s["lon"], args.refresh)

    # ---- ids, agencies, output --------------------------------------------------------------
    # One site carries one net once, whatever mix of sources described it: the GIS
    # layers add channels directly and the row files add more, and Santiago Peak was
    # listing OES V4 twice because of it. First wins; a real disagreement about the
    # tone is reported rather than quietly dropped.
    deduped, seen = [], {}
    for ch in channels:
        key = (id(ch["site"]), ch["net"])
        if key in seen:
            a, b = seen[key].get("tx_tone"), ch.get("tx_tone")
            if isinstance(a, float) and isinstance(b, float) and abs(a - b) > 0.05:
                conflicts.append((ch["site"]["name"], ch["net"], seen[key]["source"], a,
                                  ch["source"], b))
            continue
        seen[key] = ch
        deduped.append(ch)
    if len(deduped) != len(channels):
        print("  %d duplicate site/net rows folded together" % (len(channels) - len(deduped)))
    channels = deduped

    ids = set()
    for s in sites.sites:
        base = s["st"].lower() + "-" + slug(s["name"])
        sid, k = base, 2
        while sid in ids:
            sid, k = "%s-%d" % (base, k), k + 1
        ids.add(sid)
        s["id"] = sid
    for c in channels:
        c["site"] = c["site"]["id"]
    site_nets = {}
    for c in channels:
        site_nets.setdefault(c["site"], []).append(c["net"])
    out_sites = []
    dropped = 0
    for s in sites.sites:
        # A site with no channel is not a repeater site, it is a place name. The USFS
        # special-uses layer lists every communications site permitted on forest land
        # -- Modjeska, Pleasants Peak and 148 others -- and says nothing about what is
        # on them. It earns its place by giving a name a coordinate so the call plans
        # can be placed against it, and that is all: an operator asking which repeater
        # to use is not served by a list of mountains that might have one.
        if not site_nets.get(s["id"]):
            dropped += 1
            continue
        agencies = []
        for m in s["managers"]:
            a = m.split(" · ")[0]
            if a not in agencies:
                agencies.append(a)
        for n in site_nets.get(s["id"], []):
            a = by_id[n]["agency"] if n in by_id else "Local"
            if a not in agencies:
                agencies.append(a)
        out_sites.append(S(id=s["id"], name=s["name"], st=s["st"], county=s["county"], lat=s["lat"], lon=s["lon"],
                           elev_m=s["elev_m"], ant_m=s["ant_m"], managers=s["managers"], agencies=agencies,
                           sources=s["sources"]))
    # What a site is called on screen. A source hangs its own disambiguator on a
    # name -- "Sierra Peak (Corona)", "Black Mtn (RVC)" -- and it is a nearby town or
    # a unit code rather than where the mountain is; Sierra Peak is not in Corona. So
    # the bare name is the label, and where two sites share one, the county tells
    # them apart, because that is a fact about the mountain: Strawberry Peak
    # (San Bernardino) and Strawberry Peak (Tuolumne).
    def spell_out(n):
        """Pk and Mtn are the same words as Peak and Mountain, written shorter."""
        n = re.sub(r"\bMtn\.?\b", "Mountain", n)
        n = re.sub(r"\bPk\.?\b", "Peak", n)
        n = re.sub(r"\bMt\.?\b", "Mount", n)
        n = re.sub(r"\bLkt\.?\b|\bLO\b", "Lookout", n)
        return re.sub(r"\s+", " ", n).strip()

    bare = {}
    for s in out_sites:
        s["label"] = spell_out(re.sub(r"\s*\([^)]*\)$", "", s["name"]).strip() or s["name"])
        # Group on the normalized form: one source writes Strawberry Pk and another
        # Strawberry Peak, and they are one name for the purpose of telling two
        # mountains apart.
        bare.setdefault(norm_name(s["label"]), []).append(s)
    for _key, group in bare.items():
        if len(group) < 2:
            continue
        counties = [s["county"] for s in group]
        for s in group:
            label = s["label"]
            # The county only helps when it is known and not shared by the pair; if
            # two sites of one name sit in one county, the source's own words are all
            # there is to tell them apart.
            if s["county"] and counties.count(s["county"]) == 1:
                s["label"] = "%s (%s)" % (label, s["county"])
            elif s["name"] != label:
                s["label"] = s["name"]
    out_sites.sort(key=lambda s: (s["st"], s["label"].lower()))
    if dropped:
        print("  %d sites left out: a name and a coordinate, no channel on them" % dropped)
    # A net on no site does not belong in a catalog of sites: it cannot be gone to,
    # it cannot be checked for line of sight, and it has no tone that opens anything
    # here. It would only ever be a row that answers nothing.
    used = {c["net"] for c in channels}
    siteless = [n for n in nets if n["id"] not in used]
    if siteless:
        print("  %d nets dropped: on no site" % len(siteless))
    nets = [n for n in nets if n["id"] in used]
    catalog = S(format=FORMAT, generated=datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                sources=sources, tones={str(k): v for k, v in TONES.items()}, nets=nets, sites=out_sites,
                channels=channels)
    print("catalog: %d nets, %d sites, %d channels -- every net on a site, every site with a net"
          % (len(nets), len(out_sites), len(channels)))
    with_nets = len({c["site"] for c in channels})
    print("  sites carrying a net: %d; where-only: %d" % (with_nets, len(out_sites) - with_nets))
    for c in channels:
        if c["net"] not in by_id:
            raise SystemExit("channel on %s names net %r that does not exist" % (c["site"], c["net"]))
    for s in out_sites:
        if s["elev_m"] is None:
            print("  no elevation for %s" % s["name"], file=sys.stderr)
    return catalog


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", help="catalog.json to write")
    ap.add_argument("--upload", action="store_true", help="rclone the catalog to %s" % REMOTE)
    ap.add_argument("--dry-run", action="store_true", help="fetch and report, write nothing")
    ap.add_argument("--refresh", action="store_true", help="ignore the download cache")
    ap.add_argument("--rows", action="append", metavar="CSV",
                    help="another site/net row file, repeatable; may live outside the repo")
    ap.add_argument("--same-site", metavar="TXT", default="",
                    help="names that are one site, '<name> = <name>' per line (default: "
                         "comms-site-aliases.txt beside the first --rows file)")
    args = ap.parse_args()
    if not args.out and not args.dry_run:
        ap.error("--out or --dry-run")
    load_same_site(args.same_site or (
        os.path.join(os.path.dirname(os.path.abspath(args.rows[0])), "comms-site-aliases.txt")
        if args.rows else ""))
    catalog = build(args)
    if args.dry_run:
        return
    with open(args.out, "w") as f:
        json.dump(catalog, f, separators=(",", ":"), ensure_ascii=False)
        f.write("\n")
    print("wrote %s (%d KB)" % (args.out, os.path.getsize(args.out) // 1024))
    if args.upload:
        if shutil.which("rclone") is None:
            raise SystemExit("rclone not found")
        subprocess.run(["rclone", "copyto", args.out, REMOTE + "/catalog.json"], check=True)
        print("uploaded to %s/catalog.json" % REMOTE)


if __name__ == "__main__":
    main()
