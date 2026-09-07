#!/bin/bash
# check-depot-catalog: the catalog the TAKwerx Market reads must offer this
# plugin at PLUGIN_VERSION on every ATAK target the README links a build for.
# The Market installs whatever the catalog says is newest, and nothing updates
# the catalog on its own: Map Depot 1.6 shipped 2026-09-04 with the catalog
# still on 1.4, and the Market installed 1.4 the next day.
#
#   scripts/check-depot-catalog.sh <Plugin>            # check the live catalog
#   scripts/check-depot-catalog.sh <Plugin> --refresh  # publish the catalog from
#                                                      # the GitHub releases first
#                                                      # (private notes repo), then check
#
# Exit 1 on any finding. The ship-close guard runs the check form before /ship
# may re-lock, so a ship cannot be reported done with a stale catalog.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN="${1:-}"; REFRESH=0; [ "${2:-}" = "--refresh" ] && REFRESH=1
[ -n "$PLUGIN" ] || { echo "usage: $0 <Plugin> [--refresh]" >&2; exit 2; }
DIR="$ROOT/plugins/$PLUGIN"
[ -f "$DIR/app/build.gradle" ] || { echo "check-depot-catalog: no plugin at plugins/$PLUGIN" >&2; exit 2; }
BASE="https://mapdepot.takwerx.org/depot"

VER="$(sed -nE 's/^[[:space:]]*ext\.PLUGIN_VERSION[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$DIR/app/build.gradle" | head -1)"
[ -n "$VER" ] || { echo "check-depot-catalog: PLUGIN_VERSION not found in plugins/$PLUGIN/app/build.gradle" >&2; exit 2; }
PKG="$(sed -nE "s/^[[:space:]]*namespace[[:space:]]+'([^']+)'.*/\1/p" "$DIR/app/build.gradle" | head -1)"
[ -n "$PKG" ] || { echo "check-depot-catalog: namespace not found in plugins/$PLUGIN/app/build.gradle" >&2; exit 2; }

# The ATAK targets this release was built for: the README's download bullets
# (already required to name the v$VER assets by check-download-links).
TARGETS="$(grep -oE "releases/download/v$VER/ATAK-Plugin-$PLUGIN-$VER--[0-9.]+-civ-release\.apk" "$DIR/README.md" 2>/dev/null \
  | sed -E 's/.*--([0-9.]+)-civ.*/\1/' | sort -u)"
[ -n "$TARGETS" ] || TARGETS="5.6.0 5.7.0 5.8.0"

if [ "$REFRESH" = 1 ]; then
  PUB="$ROOT/../atak-plugins-notes/tools/publish_depot.sh"
  [ -x "$PUB" ] || { echo "check-depot-catalog: $PUB not found; the refresh needs the private notes repo beside this one" >&2; exit 2; }
  "$PUB" || { echo "check-depot-catalog: FAIL: publish_depot.sh did not publish (see its output)" >&2; exit 1; }
fi

fail=0
finding() { echo "  FAIL: $*"; fail=1; }
seen=0
for t in $TARGETS; do
  cat="$BASE/$t.CIV/product.inf"
  body="$(curl -sfL -H 'Cache-Control: no-cache' --max-time 30 "$cat")" \
    || { finding "$cat: cannot be read"; continue; }
  row="$(printf '%s\n' "$body" | grep -E "^Android,plugin,$PKG," | head -1)"
  if [ -z "$row" ]; then
    finding "$t: $PKG is not in the catalog at all"
    continue
  fi
  seen=1
  got="$(printf '%s' "$row" | cut -d, -f5)"
  apk="$(printf '%s' "$row" | cut -d, -f7)"
  case "$got" in
    "$VER "*) ;;
    *) finding "$t: catalog offers $got, not $VER" ;;
  esac
  case "$apk" in
    */releases/download/v"$VER"/ATAK-Plugin-"$PLUGIN"-"$VER"--"$t"-civ-release.apk) echo "  ok   $t: $got" ;;
    *) finding "$t: catalog links $apk" ;;
  esac
done

if [ "$fail" = 0 ]; then
  echo "check-depot-catalog: PASS ($PLUGIN $VER offered on: $(echo $TARGETS))"
  exit 0
fi
if [ "$seen" = 0 ]; then
  echo "check-depot-catalog: FAIL ($PLUGIN): the depot does not list $PKG. Add the public repo to DEFAULT_REPOS and LOCAL_PLUGIN_DIR in ../atak-plugins-notes/tools/refresh_depot.py, then re-run with --refresh" >&2
else
  echo "check-depot-catalog: FAIL ($PLUGIN $VER): the Market would install the wrong version. Run: scripts/check-depot-catalog.sh $PLUGIN --refresh" >&2
fi
exit 1
