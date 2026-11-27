#!/usr/bin/env bash
# ci-load-smoke: does this plugin APK actually LOAD in ATAK? (ops #692)
#
# A green build says nothing about that. A plugin that builds, passes its tests
# and installs can still be refused by ATAK (wrong ATAK target, proguard removed
# a class, signature, plugin-api mismatch), and nothing in the build shows it.
# This installs the APK on the tak-dev emulator, answers ATAK's "Load plugin?"
# prompt, and reads the plugin registry's log lines to judge the result.
#
#   scripts/ci-load-smoke.sh <apk> [--screenshot FILE] [--keep]
#   scripts/ci-load-smoke.sh --catalog <Plugin> [--target 5.8.0] [--screenshot FILE] [--keep]
#   scripts/ci-load-smoke.sh --judge <logfile> <package>      # log verdict only, no emulator
#
# <apk>      a locally built APK (ci-plugin-check.sh <Plugin> --release prints its
#            path as APK=...). It loads on the SDK's dev ATAK, which is what the
#            tak-dev emulator has.
# --catalog  the Market path: read the live catalog row for the plugin, download
#            the exact file the Market would, check it against the catalog's
#            size, SHA-256, version code and package, then load it. Catalog APKs
#            are signed by tak.gov and load ONLY on official ATAK (CLAUDE.md,
#            "Release builds differ from debug builds"). The emulator has dev
#            ATAK, so the load step exits 3 ("not run") and says why. The file
#            checks still run and print their own result.
# --keep     leave the plugin installed afterwards (default: uninstall it).
#
# Exit codes: 0 loaded cleanly; 1 ATAK refused it or crashed; 2 usage error;
# 3 the load test could not run (no emulator, wrong ATAK, no prompt). A 3 is not
# a pass: the release gate needs a 0.
#
# Ground truth for the log lines (ATAK 5.8.0.5 dev build, tak-dev emulator,
# Thu Nov 12, 2026): a clean load logs
#   AtakPluginRegistry: Loaded plugin: <package>, with extension count: N
# and a refusal logs, at warning level or worse,
#   AtakPluginRegistry: No class <Impl> found in plugin <package> ...
#   AtakPluginRegistry: java.lang.ClassNotFoundException: Didn't find class ...
# with no "Loaded plugin" line.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EMU="${TAK_DEV_EMU:-$HOME/tak-agent/workers/bin/tak-dev-emu}"
ATAK_PKG="com.atakmap.app.civ"

usage() { sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

# judge <logfile> <package>: the verdict from the registry log lines.
# The log is `logcat -d -v threadtime` output, cleared just before the install.
# Prints one line, returns 0 (loaded), 1 (refused or crashed), 3 (no verdict).
judge() {
  local log="$1" pkg="$2" bad
  # A warning, error or fatal line from the plugin registry, or a crash of ATAK
  # itself, means the load went wrong whatever else was logged.
  bad="$(awk -v pkg="$pkg" '
    $5 ~ /^[WEF]$/ && $6 ~ /^AtakPluginRegistry:?$/ { print; next }
    $0 ~ /FATAL EXCEPTION/ { print; next }
    $5 ~ /^[EF]$/ && $6 ~ /^AndroidRuntime:?$/ { print; next }
  ' "$log" | head -3)"
  if [ -n "$bad" ]; then
    echo "REFUSED: ATAK logged a problem loading $pkg:"
    printf '  %s\n' "$bad" | cut -c1-220
    return 1
  fi
  if grep -qE "AtakPluginRegistry: Loaded plugin: ${pkg//./\\.}," "$log"; then
    echo "LOADED: ATAK loaded $pkg ($(grep -cE "addPluginIcon: ${pkg//./\\.}" "$log") toolbar icon line(s))"
    return 0
  fi
  echo "NO VERDICT: no 'Loaded plugin: $pkg' line and no error line either"
  return 3
}

if [ "${1:-}" = "--judge" ]; then
  [ $# -eq 3 ] && [ -f "$2" ] || usage
  judge "$2" "$3"; exit $?
fi

APK=""; CATALOG=""; TARGET=""; SHOT=""; KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --catalog)    CATALOG="${2:-}"; shift 2 || usage ;;
    --target)     TARGET="${2:-}"; shift 2 || usage ;;
    --screenshot) SHOT="${2:-}"; shift 2 || usage ;;
    --keep)       KEEP=1; shift ;;
    -*)           usage ;;
    *)            [ -z "$APK" ] || usage; APK="$1"; shift ;;
  esac
done
{ [ -n "$APK" ] && [ -z "$CATALOG" ]; } || { [ -z "$APK" ] && [ -n "$CATALOG" ]; } || usage
case "$CATALOG" in *[!A-Za-z0-9]*) echo "ci-load-smoke: plugin names are letters and digits only" >&2; exit 2 ;; esac

# aapt reads the package and version code out of an APK.
AAPT="$(ls -d /opt/homebrew/share/android-commandlinetools/build-tools/*/aapt 2>/dev/null | sort -V | tail -1)"
[ -x "$AAPT" ] || { echo "ci-load-smoke: aapt not found (Android build-tools)" >&2; exit 3; }
badging() { "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)'.*/\1 \2/p" | head -1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------- Market path: fetch and verify what the catalog serves ----------
if [ -n "$CATALOG" ]; then
  DIR="$ROOT/plugins/$CATALOG"
  [ -f "$DIR/app/build.gradle" ] || { echo "ci-load-smoke: no plugin at plugins/$CATALOG" >&2; exit 2; }
  PKG="$(sed -nE "s/^[[:space:]]*namespace[[:space:]]+'([^']+)'.*/\1/p" "$DIR/app/build.gradle" | head -1)"
  [ -n "$TARGET" ] || TARGET="$(sed -n "s/.*ext\.ATAK_VERSION *= *['\"]\([^'\"]*\)['\"].*/\1/p" "$DIR/app/build.gradle" | head -1)"
  [ -n "$PKG" ] && [ -n "$TARGET" ] || { echo "ci-load-smoke: cannot read namespace or ATAK_VERSION from $CATALOG" >&2; exit 2; }
  ROW="$(curl -sfL -H 'Cache-Control: no-cache' --max-time 30 "https://mapdepot.takwerx.org/depot/$TARGET.CIV/product.inf" \
        | grep -E "^Android,plugin,$PKG," | head -1)"
  [ -n "$ROW" ] || { echo "CATALOG FAIL: $PKG is not in the $TARGET catalog"; exit 1; }
  # row: Android,plugin,pkg,name,version,versionCode,url,icon,desc,sha256,minsdk,plugin-api,size
  C_VERSION="$(echo "$ROW" | cut -d, -f5)"; C_CODE="$(echo "$ROW" | cut -d, -f6)"
  C_URL="$(echo "$ROW" | cut -d, -f7)"; C_SHA="$(echo "$ROW" | cut -d, -f10)"
  C_API="$(echo "$ROW" | cut -d, -f12)"; C_SIZE="$(echo "$ROW" | cut -d, -f13)"
  case "$C_URL" in https://github.com/takwerx/*) ;; *) echo "CATALOG FAIL: $C_URL is not a takwerx release link"; exit 1 ;; esac
  APK="$WORK/catalog.apk"
  curl -sfL --max-time 300 -o "$APK" "$C_URL" || { echo "CATALOG FAIL: $C_URL does not download (the Market would show an install error)"; exit 1; }
  GOT_SHA="$(shasum -a 256 "$APK" | cut -d' ' -f1)"; GOT_SIZE="$(wc -c < "$APK" | tr -d ' ')"
  [ "$GOT_SHA" = "$C_SHA" ]   || { echo "CATALOG FAIL: SHA-256 of the download is $GOT_SHA, the catalog says $C_SHA"; exit 1; }
  [ "$GOT_SIZE" = "$C_SIZE" ] || { echo "CATALOG FAIL: size $GOT_SIZE, the catalog says $C_SIZE"; exit 1; }
  read -r A_PKG A_CODE <<<"$(badging "$APK")"
  [ "$A_PKG" = "$PKG" ]       || { echo "CATALOG FAIL: the file is package '$A_PKG', the catalog row is $PKG"; exit 1; }
  [ "$A_CODE" = "$C_CODE" ]   || { echo "CATALOG FAIL: the file has version code $A_CODE, the catalog says $C_CODE"; exit 1; }
  echo "CATALOG OK: $CATALOG $C_VERSION for ATAK $TARGET ($C_API): file, size, SHA-256, package and version code all match"
  CATALOG_APK=1
else
  CATALOG_APK=0
fi

# ---------- load test on the emulator ----------
[ -f "$APK" ] || { echo "ci-load-smoke: no such file: $APK" >&2; exit 2; }
read -r PKG CODE <<<"$(badging "$APK")"
[ -n "$PKG" ] || { echo "ci-load-smoke: $APK is not an APK aapt can read" >&2; exit 2; }

emu() { bash "$EMU" "$@"; }
[ -f "$EMU" ] || { echo "NOT RUN: $EMU not found"; exit 3; }
case "$(emu status 2>&1)" in *running*) ;; *) echo "NOT RUN: the tak-dev emulator is not running (tak-dev-emu up)"; exit 3 ;; esac
ATAK_INFO="$(emu shell "dumpsys package $ATAK_PKG" 2>/dev/null | tr -d '\r')"
ATAK_VER="$(printf '%s\n' "$ATAK_INFO" | sed -n 's/^ *versionName=\([0-9.]*\).*/\1/p' | head -1)"
[ -n "$ATAK_VER" ] || { echo "NOT RUN: ATAK is not installed on the emulator (tak-dev-emu install-atak)"; exit 3; }
if printf '%s\n' "$ATAK_INFO" | grep -q 'pkgFlags=.*DEBUGGABLE'; then ATAK_KIND=dev; else ATAK_KIND=official; fi

# The plugin must target the ATAK on the emulator, or the refusal says nothing about the plugin.
API="$("$AAPT" dump xmltree "$APK" AndroidManifest.xml 2>/dev/null | grep -A2 'plugin-api' | sed -n 's/.*"\(com\.atakmap\.app@[^"]*\)".*/\1/p' | head -1)"
API_VER="${API#com.atakmap.app@}"; API_VER="${API_VER%.CIV}"
case "$ATAK_VER" in "$API_VER"*) ;; *) echo "NOT RUN: the plugin targets ATAK $API_VER but the emulator has ATAK $ATAK_VER"; exit 3 ;; esac

if [ "$CATALOG_APK" = 1 ] && [ "$ATAK_KIND" = dev ]; then
  echo "NOT RUN: catalog APKs are signed by tak.gov and load only on official ATAK; the emulator has the dev build ($ATAK_VER)."
  echo "  The file checks above passed. The load check of a signed build is still the phone with official ATAK."
  exit 3
fi
if [ "$CATALOG_APK" = 0 ] && [ "$ATAK_KIND" = official ]; then
  echo "NOT RUN: a locally built APK cannot load on official ATAK (it is signed with the SDK dev key)"; exit 3
fi

# ATAK must be in front, showing the map. A first-run dialog would hide the prompt.
emu shell "am start -n $ATAK_PKG/com.atakmap.app.ATAKActivity" >/dev/null 2>&1
sleep 4
PID_BEFORE="$(emu shell "pidof $ATAK_PKG" 2>/dev/null | tr -d '\r')"
[ -n "$PID_BEFORE" ] || { echo "NOT RUN: ATAK did not start"; exit 3; }

emu shell "pm uninstall $PKG" >/dev/null 2>&1      # a fresh install, so the prompt always appears
emu shell "logcat -c" >/dev/null 2>&1
out="$(emu install "$APK" 2>&1)" || { echo "NOT RUN: adb install failed: $(echo "$out" | tail -1)"; exit 3; }

# Find the "Load plugin" prompt and press its OK. The tap position comes from the
# screen's own layout dump, not from fixed coordinates.
python_ok='
import re,sys
x=sys.stdin.read()
if "Would you like to load this installed plugin" not in x: sys.exit(1)
m=re.search(r"text=\"OK\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",x)
if not m: sys.exit(1)
a,b,c,d=map(int,m.groups()); print((a+c)//2,(b+d)//2)'
tap=""
for _ in $(seq 1 20); do
  tap="$(emu shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" 2>/dev/null | python3 -I -c "$python_ok" 2>/dev/null)" && break
  tap=""; sleep 2
done
if [ -n "$tap" ]; then
  # shellcheck disable=SC2086
  emu tap $tap >/dev/null
else
  echo "note: no 'Load plugin' prompt appeared; judging by the log alone"
fi

LOG="$WORK/registry.log"
for _ in $(seq 1 15); do
  emu shell "logcat -d -v threadtime -s AtakPluginRegistry:V PluginMapComponent:V AndroidRuntime:V" 2>/dev/null | tr -d '\r' > "$LOG"
  grep -qE "Loaded plugin: ${PKG//./\\.}," "$LOG" && break
  grep -qE '^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [WEF] AtakPluginRegistry' "$LOG" && break
  sleep 2
done
[ -z "$SHOT" ] || emu screenshot "$SHOT" >/dev/null

verdict="$(judge "$LOG" "$PKG")"; rc=$?
echo "$verdict"
if [ "$rc" = 0 ]; then
  sleep 5
  PID_AFTER="$(emu shell "pidof $ATAK_PKG" 2>/dev/null | tr -d '\r')"
  if [ "$PID_AFTER" != "$PID_BEFORE" ]; then
    echo "REFUSED: ATAK restarted or died after loading $PKG (pid $PID_BEFORE -> ${PID_AFTER:-none})"; rc=1
  fi
fi
INSTALLED="$(emu shell "dumpsys package $PKG" 2>/dev/null | sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -1)"
[ -z "$INSTALLED" ] || [ "$INSTALLED" = "$CODE" ] || { echo "REFUSED: installed version code $INSTALLED is not the APK's $CODE"; rc=1; }
[ -z "${SAVE_LOG:-}" ] || cp "$LOG" "$SAVE_LOG"   # SAVE_LOG=file keeps the judged log (used to make test fixtures)
[ "$KEEP" = 1 ] || emu shell "pm uninstall $PKG" >/dev/null 2>&1
[ "$rc" != 0 ] && [ "$rc" != 1 ] && rc=3
if [ "$rc" = 0 ]; then echo "ci-load-smoke: PASS ($PKG $CODE loads on ATAK $ATAK_VER $ATAK_KIND)"; else echo "ci-load-smoke: FAIL ($PKG)"; fi
exit "$rc"
