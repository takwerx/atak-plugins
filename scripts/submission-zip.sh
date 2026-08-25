#!/usr/bin/env bash
# Build a tak.gov source-submission zip for a plugin, then verify it.
#
#   ./scripts/submission-zip.sh <PluginName> [--no-build]
#
# tak.gov builds the plugin FROM SOURCE — you submit a zip of the source tree, not an
# APK. Rules encoded here (per TAK_GOV_SUBMISSION_INSTRUCTIONS in the notes repo):
#   - every path inside the zip must sit under a single "<PluginName>/" root
#   - the zip must be created from the PARENT of the project directory
#   - gradle/wrapper/gradle-wrapper.jar MUST be included (tak.gov runs ./gradlew)
#   - .takdev/, app/libs/, build output, local.properties and keystores must NOT
#   - docs/user_manual/ IS included when present; tak.gov builds the PDF from it
#   - the root folder name becomes the APK name
#
# By default the zip is extracted to a temp dir and built, because a zip that does not
# build from a clean extract will not build on tak.gov either.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./env.sh
source "$REPO_ROOT/scripts/env.sh"

NAME="${1:-}"
DO_BUILD=1
[ "${2:-}" = "--no-build" ] && DO_BUILD=0

[ -n "$NAME" ] || { echo "usage: $0 <PluginName> [--no-build]" >&2; exit 1; }

PARENT="$REPO_ROOT/plugins"
PROJECT="$PARENT/$NAME"
DIST="$REPO_ROOT/dist"

[ -d "$PROJECT" ] || { echo "error: no plugin at $PROJECT" >&2; exit 1; }

# Naming convention: <Name>-<PLUGIN_VERSION>-<ATAK_VERSION>.zip, both read from
# the tree being zipped, so the filename always says what is inside. One zip per
# ATAK target: retarget ext.ATAK_VERSION in app/build.gradle (and sdk.path in
# local.properties for the verify build), re-run this script, restore.
PLUGIN_VERSION=$(sed -n "s/.*ext.PLUGIN_VERSION *= *[\"']\([^\"']*\)[\"'].*/\1/p" "$PROJECT/app/build.gradle" | head -1)
ATAK_VERSION=$(sed -n "s/.*ext.ATAK_VERSION *= *[\"']\([^\"']*\)[\"'].*/\1/p" "$PROJECT/app/build.gradle" | head -1)
[ -n "$PLUGIN_VERSION" ] && [ -n "$ATAK_VERSION" ] || {
    echo "error: could not read PLUGIN_VERSION/ATAK_VERSION from $PROJECT/app/build.gradle" >&2; exit 1; }
OUT="$DIST/$NAME-$PLUGIN_VERSION-$ATAK_VERSION.zip"

mkdir -p "$DIST"
rm -f "$OUT"

# zip aborts on a path that does not exist, so only pass the ones that do.
CANDIDATES=(
    "$NAME/app/build.gradle"
    "$NAME/app/proguard-gradle.txt"
    "$NAME/app/proguard-gradle-repackage.txt"
    "$NAME/app/src/test/"
    "$NAME/app/src/main/AndroidManifest.xml"
    "$NAME/app/src/main/assets/"
    "$NAME/app/src/main/res/"
    "$NAME/app/src/main/java/"
    "$NAME/app/src/gov/"
    "$NAME/template.local.properties"
    "$NAME/README.md"
    "$NAME/docs/user_manual/"
    "$NAME/gradle/"
    "$NAME/gradlew"
    "$NAME/gradlew.bat"
    "$NAME/build.gradle"
    "$NAME/gradle.properties"
    "$NAME/settings.gradle"
)

INCLUDE=()
for path in "${CANDIDATES[@]}"; do
    if [ -e "$PARENT/$path" ]; then
        INCLUDE+=("$path")
    else
        echo "    (skipping absent $path)"
    fi
done

echo "==> zipping from $PARENT"
( cd "$PARENT" && zip -rq "$OUT" "${INCLUDE[@]}" -x "*.DS_Store" "*/.git/*" )

# --- point of contact ---------------------------------------------------------
# tak.gov wants a contact address in the README. The public repo must never carry
# one: publish-scrub blocks email addresses, and this same README is subtree-pushed
# to the plugin's public GitHub repo verbatim. Those two requirements are only
# compatible if the address never enters the tracked tree at all.
#
# So it lives in the private notes repo and is injected into the README *inside the
# zip*, after the tracked tree has been zipped. The committed README keeps the
# public contact line (name, org, issue tracker), which is correct on its own
# rather than being a template with a hole in it.
POC_FILE="${POC_FILE:-$REPO_ROOT/../atak-plugins-notes/submission-poc.txt}"
if [ -f "$POC_FILE" ]; then
    # A file of comments only is the normal state, and grep matching nothing exits 1 —
    # which under `set -e` would abort the whole script after the zip had been written.
    POC_LINE="$(grep -v '^[[:space:]]*#' "$POC_FILE" 2>/dev/null | sed '/^[[:space:]]*$/d' | head -1 || true)"
fi
if [ -n "${POC_LINE:-}" ]; then
    POC_TMP="$(mktemp -d)"
    ( cd "$POC_TMP" && unzip -q "$OUT" "$NAME/README.md" )
    # Insert directly beneath the existing contact block, keeping the heading intact.
    awk -v poc="$POC_LINE" '
        /^POINT OF CONTACTS$/ { inpoc = 1 }
        inpoc && /^https:\/\// { print; print poc; inpoc = 0; next }
        { print }
    ' "$POC_TMP/$NAME/README.md" > "$POC_TMP/README.new"
    if ! grep -qF "$POC_LINE" "$POC_TMP/README.new"; then
        echo "error: could not place the point of contact in README.md" >&2
        rm -rf "$POC_TMP"; exit 1
    fi
    mv "$POC_TMP/README.new" "$POC_TMP/$NAME/README.md"
    ( cd "$POC_TMP" && zip -q "$OUT" "$NAME/README.md" )
    rm -rf "$POC_TMP"
    echo "==> point of contact injected into the zip's README (not the tracked one)"
elif [ -f "$POC_FILE" ]; then
    echo "==> $POC_FILE holds no address — zip README carries the public contact line only"
else
    echo "==> no $POC_FILE — zip README carries the public contact line only"
fi

echo
echo "==> verifying $OUT"
FAIL=0
check() { # description, offending-lines
    if [ -z "$2" ]; then
        echo "  PASS  $1"
    else
        echo "  FAIL  $1"
        printf '        %s\n' $2
        FAIL=1
    fi
}

SIZE_BYTES=$(wc -c < "$OUT" | tr -d ' ')
SIZE_HUMAN=$(du -h "$OUT" | cut -f1 | tr -d ' ')
# Without a manual a submission is a few hundred KB, and anything larger means the SDK
# jar or build output crept in. A manual legitimately carries its own fonts, title art
# and screenshots, so the ceiling moves -- but it is still a ceiling, and still well
# under the ~30 MB that main.jar alone would add.
if [ -d "$PROJECT/docs/user_manual" ]; then
    SIZE_LIMIT=12000000
    SIZE_NOTE="with the user manual"
else
    SIZE_LIMIT=2000000
    SIZE_NOTE="expected KB, not MB"
fi
if [ "$SIZE_BYTES" -lt "$SIZE_LIMIT" ]; then
    echo "  PASS  size is $SIZE_HUMAN ($SIZE_NOTE)"
else
    echo "  FAIL  size is $SIZE_HUMAN — something large is in the zip"
    FAIL=1
fi

check "every path is under $NAME/"        "$(zipinfo -1 "$OUT" | grep -v "^$NAME/" || true)"
check "no app/libs/ or .takdev/"          "$(zipinfo -1 "$OUT" | grep -E "(libs/|takdev)" || true)"
# docs/user_manual is the one part of docs/ that belongs in the zip: gradle/typst.gradle
# builds the PDF from it when ATAK_CI=1, which is how the manual reaches the plugin's
# assets. Everything else under docs/ -- screenshots for the public repo, icon sources,
# the built PDF itself -- stays out; tak.gov builds the PDF rather than being handed one.
check "no docs/ beyond user_manual"       "$(zipinfo -1 "$OUT" | grep -E "^$NAME/docs/" | grep -v "^$NAME/docs/user_manual/" || true)"
check "no build output"                   "$(zipinfo -1 "$OUT" | grep -E "(/build/|^$NAME/\.gradle/)" || true)"
check "only gradle-wrapper.jar as binary" "$(zipinfo -1 "$OUT" | grep -E '\.(jar|aar)$' | grep -v 'gradle-wrapper\.jar' || true)"
check "no real local.properties/keystore" "$(zipinfo -1 "$OUT" | grep -E "local\.properties|keystore" | grep -v 'template\.local\.properties' || true)"

# Capture rather than `grep -q`: under `set -o pipefail`, grep -q exits early, zipinfo
# takes SIGPIPE, and the pipeline reports failure even when the match was found.
WRAPPER_JAR="$(zipinfo -1 "$OUT" | grep 'gradle/wrapper/gradle-wrapper.jar' || true)"
if [ -n "$WRAPPER_JAR" ]; then
    echo "  PASS  gradle-wrapper.jar is included"
else
    echo "  FAIL  gradle-wrapper.jar is MISSING — tak.gov's ./gradlew cannot bootstrap"
    FAIL=1
fi

if grep -q "atakplugin\.$NAME" "$PROJECT/app/proguard-gradle-repackage.txt" 2>/dev/null; then
    echo "  PASS  proguard repackage descriptor is atakplugin.$NAME"
else
    echo "  FAIL  proguard-gradle-repackage.txt is not plugin-specific"
    FAIL=1
fi

if [ "$DO_BUILD" = 1 ]; then
    echo
    echo "==> publish scrub of the zip contents (PII, credentials, forbidden files)"
SCRUB_DIR="$(mktemp -d)"
( cd "$SCRUB_DIR" && unzip -q "$OUT" )
if POC_ALLOW="${POC_LINE:-}" "$REPO_ROOT/scripts/publish-scrub.sh" "$SCRUB_DIR"; then
    echo "  PASS  publish scrub"
else
    echo "  FAIL  publish scrub — see findings above"; FAIL=1
fi
rm -rf "$SCRUB_DIR"

echo "==> clean-extract build test (a zip that fails here fails on tak.gov)"
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    unzip -q "$OUT" -d "$TMP"
    cat > "$TMP/$NAME/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
sdk.path=$ATAK_SDK
takdev.plugin=$ATAK_SDK/atak-gradle-takdev.jar
EOF
    if ( cd "$TMP/$NAME" && ./gradlew assembleCivDebug -q >/dev/null 2>&1 ); then
        echo "  PASS  extracted zip builds assembleCivDebug"
    else
        echo "  FAIL  extracted zip does NOT build — rerun by hand for the error:"
        echo "        unzip $OUT -d /tmp/x && cd /tmp/x/$NAME && ./gradlew assembleCivDebug"
        FAIL=1
    fi
fi

echo
if [ "$FAIL" = 0 ]; then
    echo "$OUT ($SIZE_HUMAN) — ready to submit"
else
    echo "$OUT — FIX THE FAILURES ABOVE BEFORE SUBMITTING"
    exit 1
fi
