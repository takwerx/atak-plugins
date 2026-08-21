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
#   - .takdev/, app/libs/, docs/, build output, local.properties and keystores must NOT
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
if [ "$SIZE_BYTES" -lt 2000000 ]; then
    echo "  PASS  size is $SIZE_HUMAN (expected KB, not MB)"
else
    echo "  FAIL  size is $SIZE_HUMAN — something large is in the zip"
    FAIL=1
fi

check "every path is under $NAME/"        "$(zipinfo -1 "$OUT" | grep -v "^$NAME/" || true)"
check "no app/libs/ or .takdev/"          "$(zipinfo -1 "$OUT" | grep -E "(libs/|takdev)" || true)"
check "no docs/ or build output"          "$(zipinfo -1 "$OUT" | grep -E "(^$NAME/docs/|/build/|^$NAME/\.gradle/)" || true)"
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
