#!/bin/bash
# check-download-links: the download block at the top of a plugin's README and
# user guide must name the version in PLUGIN_VERSION and link to that version's
# release assets. Map Depot 1.6 shipped with every link aimed at a v1.5 release
# that was never created, and users got 404s until one of them told us.
#
#   scripts/check-download-links.sh <Plugin>          # text check, no network
#   scripts/check-download-links.sh <Plugin> --live   # also HEAD each link: 200
#
# The text check is for pre-flight, before the release exists. --live is for
# after `gh release create`, to prove the links resolve. Exit 1 on any finding.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN="${1:-}"; LIVE=0; [ "${2:-}" = "--live" ] && LIVE=1
[ -n "$PLUGIN" ] || { echo "usage: $0 <Plugin> [--live]" >&2; exit 2; }
DIR="$ROOT/plugins/$PLUGIN"
[ -f "$DIR/app/build.gradle" ] || { echo "check-download-links: no plugin at plugins/$PLUGIN" >&2; exit 2; }

VER="$(sed -nE 's/^[[:space:]]*ext\.PLUGIN_VERSION[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$DIR/app/build.gradle" | head -1)"
[ -n "$VER" ] || { echo "check-download-links: PLUGIN_VERSION not found in plugins/$PLUGIN/app/build.gradle" >&2; exit 2; }

fail=0
finding() { echo "  FAIL: $*"; fail=1; }

for f in README.md docs/USER_GUIDE.md; do
  p="$DIR/$f"
  [ -f "$p" ] || { finding "$f is missing"; continue; }
  # the bold download line names the version being shipped
  if ! grep -qE "^\*\*Download .* $VER\*\*" "$p"; then
    got="$(grep -oE '^\*\*Download [^*]*\*\*' "$p" | head -1)"
    finding "$f: download line does not say $VER (found: ${got:-none})"
  fi
  # the guide's version header, where present
  if [ "$f" = docs/USER_GUIDE.md ] && grep -qE '^\*\*Version [0-9.]+ ' "$p" \
     && ! grep -qE "^\*\*Version $VER " "$p"; then
    finding "$f: version header is not $VER ($(grep -oE '^\*\*Version [0-9.]+' "$p" | head -1))"
  fi
  # every release-asset link is this version's tag and this version's APK name
  links="$(grep -oE 'https://github\.com/takwerx/[A-Za-z0-9._-]+/releases/download/[^ )]+' "$p" | sort -u)"
  [ -n "$links" ] || finding "$f: no release download links"
  for u in $links; do
    case "$u" in
      */releases/download/v"$VER"/ATAK-Plugin-"$PLUGIN"-"$VER"--*-civ-release.apk) ;;
      *) finding "$f: link is not a v$VER asset: $u" ;;
    esac
  done
done

# when this version's signed APKs are staged, each one must be linked from the
# README (dist/signed keeps every past release, so match the version)
if ls "$ROOT"/dist/signed/ATAK-Plugin-"$PLUGIN"-"$VER"--*.apk >/dev/null 2>&1; then
  for a in "$ROOT"/dist/signed/ATAK-Plugin-"$PLUGIN"-"$VER"--*.apk; do
    b="$(basename "$a")"
    grep -q "/$b" "$DIR/README.md" || finding "dist/signed/$b is not linked from README.md"
  done
fi

if [ "$LIVE" = 1 ]; then
  for u in $(grep -ohE 'https://github\.com/takwerx/[A-Za-z0-9._-]+/releases/download/[^ )]+' "$DIR/README.md" "$DIR/docs/USER_GUIDE.md" 2>/dev/null | sort -u); do
    code="$(curl -sIL -o /dev/null -w '%{http_code}' --max-time 20 "$u")"
    if [ "$code" = 200 ]; then echo "  200  $u"; else finding "$code  $u"; fi
  done
fi

if [ "$fail" = 0 ]; then
  suffix=""; [ "$LIVE" = 1 ] && suffix=", all resolve"
  echo "check-download-links: PASS ($PLUGIN $VER: README and guide link the v$VER assets$suffix)"
  exit 0
fi
echo "check-download-links: FAIL ($PLUGIN $VER): fix the download block at the top of README.md and docs/USER_GUIDE.md before the subtree push" >&2
exit 1
