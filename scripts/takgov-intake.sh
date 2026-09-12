#!/bin/bash
# Take in what tak.gov sends back, file it, and read the scans before anyone publishes.
#
# tak.gov returns one zip per build: the signed APK and AAB, a Fortify static
# analysis, an OWASP Dependency-Check report, an SBOM, and the build log. Until
# 2026-09-12 that arrived as a pile of identically named files that had to be
# sorted by hand, and the scans were read only if somebody remembered to. The
# operator: "you need some sort of skill or something or rule to like check all
# this shit to make sure we dont have any issues that we want to correct before
# we publish."
#
# So: drop every return zip in $ATAK_DIST/inbox and run this. It reads the plugin,
# the version and the ATAK target off the APK name inside, files everything where
# it belongs, and then checks the things that decide whether a build is fit to
# publish. It is a gate, not a report: a real finding exits non-zero.
#
#   ./scripts/takgov-intake.sh              # everything in the inbox
#   ./scripts/takgov-intake.sh <zip>...     # named zips
#   ./scripts/takgov-intake.sh --keep       # leave the zips in the inbox
#
# What it files:
#   $ATAK_DIST/signed/<apk>, <aab>
#   $ATAK_DIST/scans/<Plugin>/<version>/<target>/{build.log,fortify_*,dependency-check-report.html,sbom/,...}
#
# What it checks, and why each one is here:
#   signer          a build signed by anything but the TAK Product Center is not
#                   the artifact we asked for
#   versionCode     MAJOR*10000+MINOR*100+PATCH, or no MDM can push it as an
#                   update -- the same rule submission-zip.sh enforces going out
#   version match   the APK inside says the version its name claims
#   package         the same package id as the last signed release, or it installs
#                   alongside the old one instead of over it
#   certificate     the same signing certificate as the last signed release, for
#                   the same reason
#   Fortify         "Rendering N results": any N above zero is a real finding in
#                   our own source and stops the gate
#   Dependency-Check  every CVE is listed with the artifact it attached to. These
#                   are frequently false positives -- the scanner opens an Android
#                   asset renamed to .jar and fuzzy-matches it against a library
#                   -- so the SBOM is checked for the named product before the
#                   gate fails on it
set -o pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=/dev/null
[ -f "$HERE/env.sh" ] && . "$HERE/env.sh" >/dev/null 2>&1
DIST="${ATAK_DIST:-$HOME/atak-dist}"
INBOX="$DIST/inbox"
KEEP=0
ZIPS=()
for a in "$@"; do
    case "$a" in
        --keep) KEEP=1 ;;
        -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) ZIPS+=("$a") ;;
    esac
done
[ ${#ZIPS[@]} -eq 0 ] && { shopt -s nullglob; ZIPS=("$INBOX"/*.zip); shopt -u nullglob; }
[ ${#ZIPS[@]} -eq 0 ] && { echo "nothing to take in: $INBOX is empty"; exit 0; }

command -v unzip >/dev/null || { echo "error: unzip not on PATH" >&2; exit 1; }
BT="$(ls -d "${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"/build-tools/* 2>/dev/null | sort -V | tail -1)"
AAPT="$BT/aapt"
APKSIGNER="$BT/apksigner"
[ -x "$AAPT" ] || { echo "error: aapt not found under $BT" >&2; exit 1; }

FAIL=0
NOTE=0
ok()   { echo "  PASS  $*"; }
bad()  { echo "  FAIL  $*"; FAIL=1; }
note() { echo "  ..    $*"; NOTE=1; }

# The signing certificate of the newest signed release of this plugin other than
# the one in hand, so a change of key is caught the moment it happens.
prev_cert() {
    local plugin="$1" skip="$2" newest="" f
    for f in $(ls -t "$DIST"/signed/ATAK-Plugin-"$plugin"-*.apk 2>/dev/null); do
        [ "$(basename "$f")" = "$skip" ] && continue
        newest="$f"; break
    done
    [ -n "$newest" ] || return 1
    echo "$newest"
}

cert_of() { "$APKSIGNER" verify --print-certs "$1" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: \(.*\)/\1/p' | head -1; }
pkg_of()  { "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/package: name='\([^']*\)'.*/\1/p" | head -1; }

for Z in "${ZIPS[@]}"; do
    [ -f "$Z" ] || { echo "no such zip: $Z" >&2; FAIL=1; continue; }
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    unzip -q "$Z" -d "$TMP" || { echo "could not unzip $Z" >&2; FAIL=1; rm -rf "$TMP"; continue; }

    APK="$(ls "$TMP"/ATAK-Plugin-*.apk 2>/dev/null | head -1)"
    [ -n "$APK" ] || { echo "no ATAK-Plugin-*.apk inside $(basename "$Z") -- is this a tak.gov return zip?" >&2; FAIL=1; rm -rf "$TMP"; continue; }
    BASE="$(basename "$APK")"
    PLUGIN="$(echo "$BASE" | sed -E 's/^ATAK-Plugin-([A-Za-z0-9]+)-.*/\1/')"
    VERSION="$(echo "$BASE" | sed -E 's/^ATAK-Plugin-[A-Za-z0-9]+-([0-9][0-9.]*)--.*/\1/')"
    TARGET="$(echo "$BASE" | sed -E 's/.*--([0-9][0-9.]*)-civ-release\.apk$/\1/')"
    echo
    echo "==> $PLUGIN $VERSION for ATAK $TARGET   ($(basename "$Z"))"

    DEST="$DIST/scans/$PLUGIN/$VERSION/$TARGET"
    mkdir -p "$DEST" "$DIST/signed"
    for f in build.log civRelease-app-mapping.txt dependency-check-report.html \
             fortify_analyze.log fortify_analyze_FortifySupport.log fortify_scan.txt \
             fortify_scan_FortifySupport.txt fortify_scan_results.pdf scan_results.fpr; do
        [ -f "$TMP/$f" ] && cp "$TMP/$f" "$DEST/"
    done
    [ -d "$TMP/sbom" ] && { mkdir -p "$DEST/sbom"; cp "$TMP"/sbom/* "$DEST/sbom/" 2>/dev/null; }
    cp "$APK" "$DIST/signed/"
    for b in "$TMP"/ATAK-Plugin-*.aab; do [ -f "$b" ] && cp "$b" "$DIST/signed/"; done
    ok "filed: signed/$BASE and scans/$PLUGIN/$VERSION/$TARGET/"

    # ---- the APK is what it claims to be ------------------------------------
    SIGNER="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n 's/.*certificate DN: CN=\([^,]*\).*/\1/p' | head -1)"
    case "$SIGNER" in
        *"TAK Product Center"*) ok "signed by $SIGNER" ;;
        "") bad "no signature could be read from the APK" ;;
        *)  bad "signed by '$SIGNER', not the TAK Product Center" ;;
    esac

    BADGING="$("$AAPT" dump badging "$APK" 2>/dev/null | head -1)"
    GOT_CODE="$(echo "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
    GOT_NAME="$(echo "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
    WANT_CODE="$(printf '%s' "$VERSION" | awk -F. '{printf "%d", $1*10000 + $2*100 + $3}')"
    if [ "$GOT_CODE" = "$WANT_CODE" ] && [ "$GOT_CODE" -gt 1 ] 2>/dev/null; then
        ok "versionCode $GOT_CODE, derived from $VERSION as an MDM needs"
    else
        bad "versionCode $GOT_CODE, expected $WANT_CODE from version $VERSION -- no MDM can push this as an update"
    fi
    case "$GOT_NAME" in
        "$VERSION"*) ok "versionName '$GOT_NAME' agrees with the file name" ;;
        *) bad "versionName '$GOT_NAME' does not start with $VERSION" ;;
    esac

    PKG="$(pkg_of "$APK")"
    PREV="$(prev_cert "$PLUGIN" "$BASE")"
    if [ -n "$PREV" ]; then
        if [ "$(pkg_of "$PREV")" = "$PKG" ]; then
            ok "package $PKG, same as $(basename "$PREV")"
        else
            bad "package $PKG differs from $(basename "$PREV") ($(pkg_of "$PREV")) -- it would install alongside, not over"
        fi
        if [ "$(cert_of "$PREV")" = "$(cert_of "$APK")" ]; then
            ok "same signing certificate as the last signed release"
        else
            bad "signing certificate CHANGED since $(basename "$PREV") -- Android will refuse the update"
        fi
    else
        note "no earlier signed release to compare package and certificate against"
    fi

    # ---- Fortify: our own source ---------------------------------------------
    if [ -f "$DEST/fortify_scan.txt" ]; then
        N="$(sed -n 's/.*Rendering \([0-9]*\) results.*/\1/p' "$DEST/fortify_scan.txt" | tail -1)"
        if [ -z "$N" ]; then
            bad "Fortify did not report a result count -- read $DEST/fortify_scan.txt by hand"
        elif [ "$N" -eq 0 ]; then
            ok "Fortify: 0 findings"
        else
            bad "Fortify: $N finding(s) in our own source -- open $DEST/fortify_scan_results.pdf"
        fi
    else
        note "no Fortify report in this zip"
    fi

    # ---- Dependency-Check: libraries, and its habit of guessing --------------
    DC="$DEST/dependency-check-report.html"
    if [ -f "$DC" ]; then
        CVES="$(grep -oE 'CVE-[0-9]{4}-[0-9]+' "$DC" | sort -u)"
        if [ -z "$CVES" ]; then
            ok "Dependency-Check: no CVEs"
        else
            for CVE in $CVES; do
                # The product the scanner thinks is vulnerable, and whether the
                # SBOM has ever heard of it. A CVE against something we do not
                # ship is the scanner matching an Android asset to a library.
                PROD="$(python3 - "$DC" "$CVE" <<'PY' 2>/dev/null
import re, sys, html
# The product a CVE is actually against is the one under "Vulnerable Software &
# Versions" after the identifier, not whichever CPE the scanner guessed at last
# while listing candidates for the file.
s = open(sys.argv[1], encoding='utf-8', errors='replace').read()
i = s.find(sys.argv[2])
after = html.unescape(re.sub(r'<[^>]+>', ' ', s[i:i + 9000]))
m = re.search(r'Vulnerable Software[^:]*:\s*cpe:2\.3:a:[a-z0-9_\-]+:([a-z0-9_\-]+):', after)
if not m:
    m = re.search(r'cpe:2\.3:a:[a-z0-9_\-]+:([a-z0-9_\-]+):', after)
print(m.group(1) if m else '')
PY
)"
                SB="$DEST/sbom/bom.json"
                if [ -n "$PROD" ] && [ -f "$SB" ] && ! grep -qi "\"$PROD\"" "$SB"; then
                    note "$CVE claims '$PROD', which the SBOM does not list -- a misidentified artifact, not a dependency"
                elif [ -n "$PROD" ]; then
                    bad "$CVE against '$PROD', which IS in the SBOM -- this one is real, update it"
                else
                    bad "$CVE reported and its product could not be read -- open $DC"
                fi
            done
        fi
    else
        note "no Dependency-Check report in this zip"
    fi

    [ "$KEEP" -eq 1 ] || { mkdir -p "$INBOX/done"; mv "$Z" "$INBOX/done/"; }
    rm -rf "$TMP"
    trap - EXIT
done

echo
if [ "$FAIL" -eq 0 ]; then
    [ "$NOTE" -eq 1 ] && echo "takgov-intake: PASS with notes above -- read them before publishing"
    [ "$NOTE" -eq 0 ] && echo "takgov-intake: PASS"
    exit 0
fi
echo "takgov-intake: FAIL -- do not publish until the findings above are answered"
exit 1
