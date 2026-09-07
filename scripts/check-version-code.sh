#!/bin/bash
# check-version-code: a plugin release must be a real Android upgrade, or the
# fleet's MDM (Watchtower) will not push it over the build already installed.
# Cam Depot 1.2 was refused as an update to 1.1 on 2026-09-06 because every
# tak.gov-signed build carried versionCode 1 (no .git at tak.gov, so the SDK's
# git-derived code fell back); 1.3 exists only to carry the fix. Android and
# the MDM compare the integer versionCode; versionName is display only.
#
# What "an update" needs, checked here:
#   - app/build.gradle derives versionCode from PLUGIN_VERSION
#     (MAJOR*10000 + MINOR*100 + PATCH), never from git
#   - the version is above every release already signed (dist/signed/ keeps
#     every tak.gov-signed APK), so a resubmission is a NEW version, not the
#     same one again. --target narrows "already signed" to one ATAK target: a
#     target that failed at tak.gov may be resubmitted under the same version
#     while no signed APK exists for it
#   - --signed: this version's signed APKs are all present, one per target the
#     README links (a missing target strands every device on that ATAK), each
#     carries that code, the same package name and the same signing
#     certificate as the last signed release
#   - --live: no release on the plugin's public repo is this version or newer
#
#   scripts/check-version-code.sh <Plugin>                    # tree + dist/signed, no network
#   scripts/check-version-code.sh <Plugin> --target 5.7.0     # what submission-zip.sh runs
#   scripts/check-version-code.sh <Plugin> --signed [--live]  # ship pre-flight and the release hook
#
# Exit 1 on any finding, 2 on usage.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN="${1:-}"; [ $# -gt 0 ] && shift
SIGNED=0; LIVE=0; TARGET=""
while [ $# -gt 0 ]; do
  case "$1" in
    --signed) SIGNED=1 ;;
    --live)   LIVE=1 ;;
    --target) TARGET="${2:-}"; shift ;;
    *) echo "usage: $0 <Plugin> [--target <atak>] [--signed] [--live]" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$PLUGIN" ] || { echo "usage: $0 <Plugin> [--target <atak>] [--signed] [--live]" >&2; exit 2; }
DIR="$ROOT/plugins/$PLUGIN"
GRADLE="$DIR/app/build.gradle"
[ -f "$GRADLE" ] || { echo "check-version-code: no plugin at plugins/$PLUGIN" >&2; exit 2; }

VER="$(sed -nE 's/^[[:space:]]*ext\.PLUGIN_VERSION[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$GRADLE" | head -1)"
[ -n "$VER" ] || { echo "check-version-code: PLUGIN_VERSION not found in plugins/$PLUGIN/app/build.gradle" >&2; exit 2; }

# "1.3" -> 10300, "1.3.1" -> 10301; anything but digits -> empty, status 1.
# The same arithmetic as PLUGIN_VERSION_CODE in app/build.gradle.
code_of() {
  printf '%s' "$1" | awk -F. '{
    if ($1 !~ /^[0-9]+$/ || ($2 != "" && $2 !~ /^[0-9]+$/) || ($3 != "" && $3 !~ /^[0-9]+$/) || NF > 3) exit 1
    printf "%d", $1*10000 + $2*100 + $3 }'
}

EXPECT="$(code_of "$VER")" || EXPECT=""
if [ -z "$EXPECT" ] || [ "$EXPECT" -le 1 ]; then
  echo "check-version-code: FAIL ($PLUGIN): PLUGIN_VERSION '$VER' is not MAJOR.MINOR[.PATCH] digits" >&2; exit 1
fi

fail=0
finding() { echo "  FAIL: $*"; fail=1; }

# 1. The tree derives the code from PLUGIN_VERSION. submission-zip.sh proves the
#    built APK agrees; this catches a plugin that never got the change.
grep -qE '^[[:space:]]*defaultConfig\.versionCode[[:space:]]*=[[:space:]]*PLUGIN_VERSION_CODE\b' "$GRADLE" \
  || finding "app/build.gradle does not set defaultConfig.versionCode = PLUGIN_VERSION_CODE (the SDK's getVersionCode() is 1 at tak.gov)"
grep -qE '^[[:space:]]*ext\.PLUGIN_VERSION_CODE[[:space:]]*=' "$GRADLE" \
  || finding "app/build.gradle does not define ext.PLUGIN_VERSION_CODE"

# 2. Above every release already signed. The version in the file name is the
#    truth for builds from before the fix: they all read versionCode 1.
# dist/ is gitignored and lives in the main checkout; a worktree has none. Fall
# back to the main worktree's, and say which one was read, so an empty result
# is never mistaken for "nothing signed yet".
SIGNED_DIR="${ATAK_SIGNED_DIR:-$ROOT/dist/signed}"
if [ ! -d "$SIGNED_DIR" ]; then
  MAIN_WT="$(git -C "$ROOT" worktree list --porcelain 2>/dev/null | sed -n '1s/^worktree //p')"
  [ -n "$MAIN_WT" ] && [ -d "$MAIN_WT/dist/signed" ] && SIGNED_DIR="$MAIN_WT/dist/signed"
fi
[ -d "$SIGNED_DIR" ] || finding "no signed-release folder at $SIGNED_DIR (set ATAK_SIGNED_DIR); cannot tell whether $VER is an update"
seen=0
prev_max=0; prev_ver=""; prev_apk=""
for a in "$SIGNED_DIR"/ATAK-Plugin-"$PLUGIN"-*--*-civ-release.apk; do
  [ -f "$a" ] || continue
  b="$(basename "$a")"
  v="$(printf '%s' "$b" | sed -nE "s/^ATAK-Plugin-$PLUGIN-([0-9.]+)--([0-9.]+)-civ-release\.apk$/\1/p")"
  t="$(printf '%s' "$b" | sed -nE "s/^ATAK-Plugin-$PLUGIN-([0-9.]+)--([0-9.]+)-civ-release\.apk$/\2/p")"
  [ -n "$v" ] || continue
  c="$(code_of "$v")" || c=""
  [ -n "$c" ] || continue
  seen=$((seen + 1))
  if [ "$v" = "$VER" ]; then
    if [ "$SIGNED" = 0 ] && { [ -z "$TARGET" ] || [ "$t" = "$TARGET" ]; }; then
      finding "$VER is already signed for ATAK $t (dist/signed/$b): a resubmission is a NEW version, bump PLUGIN_VERSION"
    fi
    continue
  fi
  if [ "$c" -ge "$EXPECT" ]; then
    finding "$VER (code $EXPECT) is not above the signed $v (code $c, dist/signed/$b): no MDM would see an update"
  fi
  if [ "$c" -gt "$prev_max" ]; then prev_max=$c; prev_ver=$v; prev_apk=$a; fi
done
if [ "$seen" = 0 ]; then
  echo "  note  no signed $PLUGIN release under $SIGNED_DIR: treating $VER as the first release"
else
  echo "  ok    $seen signed $PLUGIN APK(s) under $SIGNED_DIR, newest other version ${prev_ver:-none}"
fi

# 3. --signed: this version's signed APKs, one per target, right code, same
#    package and same certificate as the last signed release.
BT="$(ls -d "${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
AAPT="${BT:-/nonexistent}/aapt"
KEYTOOL="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}/bin/keytool"
pkg_of()  { "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p"; }
code_in() { "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p"; }
cert_of() { "$KEYTOOL" -printcert -jarfile "$1" 2>/dev/null | sed -n 's/^[[:space:]]*SHA256: //p' | head -1; }

if [ "$SIGNED" = 1 ]; then
  [ -x "$AAPT" ] || finding "no aapt under ${BT:-\$ANDROID_HOME/build-tools}; cannot read the signed APKs"
  [ -x "$KEYTOOL" ] || finding "no keytool at $KEYTOOL; cannot compare signing certificates"
  targets="$(grep -oE "/releases/download/v$VER/ATAK-Plugin-$PLUGIN-$VER--[0-9.]+-civ-release\.apk" "$DIR/README.md" 2>/dev/null \
             | sed -E 's/.*--([0-9.]+)-civ.*/\1/' | sort -u)"
  [ -n "$targets" ] || finding "README.md links no v$VER assets; nothing says which targets this release ships"
  prev_pkg=""; prev_cert=""
  if [ -n "$prev_apk" ] && [ -x "$AAPT" ]; then
    prev_pkg="$(pkg_of "$prev_apk")"
    [ -x "$KEYTOOL" ] && prev_cert="$(cert_of "$prev_apk")"
  fi
  certs=""
  for t in $targets; do
    a="$SIGNED_DIR/ATAK-Plugin-$PLUGIN-$VER--$t-civ-release.apk"
    if [ ! -f "$a" ]; then
      finding "no signed APK for ATAK $t (dist/signed/$(basename "$a")): every target ships, or devices on that ATAK can never be updated"
      continue
    fi
    [ -x "$AAPT" ] || continue
    before=$fail
    c="$(code_in "$a")"
    [ "$c" = "$EXPECT" ] || finding "$(basename "$a") carries versionCode '${c:-?}', expected $EXPECT"
    p="$(pkg_of "$a")"
    if [ -n "$prev_pkg" ] && [ "$p" != "$prev_pkg" ]; then
      finding "$(basename "$a") is package $p but the last signed release ($prev_ver) was $prev_pkg: a different app, not an update"
    fi
    s=""
    if [ -x "$KEYTOOL" ]; then
      s="$(cert_of "$a")"
      [ -n "$s" ] || finding "$(basename "$a"): could not read the signing certificate"
      if [ -n "$prev_cert" ] && [ -n "$s" ] && [ "$s" != "$prev_cert" ]; then
        finding "$(basename "$a") is signed by a different certificate than $prev_ver: Android refuses the update"
      fi
      case "$certs" in *"$s"*) ;; *) certs="$certs $s" ;; esac
    fi
    [ "$fail" = "$before" ] && echo "  ok    $(basename "$a"): versionCode $c, $p"
  done
  [ "$(printf '%s' "$certs" | wc -w)" -le 1 ] || finding "this version's APKs are not all signed by one certificate"
  if [ -n "$prev_ver" ] && [ "$fail" = 0 ]; then
    echo "  ok    an update over the last signed release, $prev_ver: same package, same signing certificate"
  fi
fi

# 4. --live: nothing on the public repo is this version or newer.
if [ "$LIVE" = 1 ]; then
  repo="$(sed -nE 's#^All releases: https://github\.com/takwerx/([A-Za-z0-9._-]+)/releases[[:space:]]*$#\1#p' "$DIR/README.md" 2>/dev/null | head -1)"
  if [ -z "$repo" ]; then
    finding "README.md has no 'All releases:' line naming the public repo"
  elif ! command -v gh >/dev/null 2>&1; then
    finding "gh is not installed; cannot compare against the live releases"
  else
    if tags="$(gh api "repos/takwerx/$repo/releases?per_page=100" --jq '.[].tag_name' 2>&1)"; then
      [ -n "$tags" ] || echo "  note  takwerx/$repo has no releases yet (first release)"
      for tg in $tags; do
        tc="$(code_of "${tg#v}")" || tc=""
        [ -n "$tc" ] || continue
        [ "$tc" -lt "$EXPECT" ] || finding "takwerx/$repo already has release $tg (code $tc); $VER (code $EXPECT) is not newer"
      done
      [ "$fail" = 0 ] && echo "  ok    takwerx/$repo: every existing release is older than $VER"
    else
      finding "gh api repos/takwerx/$repo/releases failed: $tags"
    fi
  fi
fi

if [ "$fail" = 0 ]; then
  echo "check-version-code: PASS ($PLUGIN $VER -> versionCode $EXPECT, an update over every signed release)"
  exit 0
fi
echo "check-version-code: FAIL ($PLUGIN $VER): an MDM could not push this as an update; fix the findings above, do not work around them" >&2
exit 1
