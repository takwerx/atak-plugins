#!/usr/bin/env bash
# Scaffold a new ATAK plugin from the SDK's plugintemplate sample.
#
#   ./scripts/new-plugin.sh <name> "Display Name"
#
# <name> must be lowercase alphanumeric with no dashes or underscores: the release
# build writes "-repackageclasses atakplugin.${rootProject.name}" into proguard, and a
# dash there is an invalid Java package name (debug builds pass, release builds fail).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./env.sh
source "$REPO_ROOT/scripts/env.sh"

NAME="${1:-}"
DISPLAY="${2:-}"

if [ -z "$NAME" ] || [ -z "$DISPLAY" ]; then
    echo "usage: $0 <name> \"Display Name\"" >&2
    echo "example: $0 unittracker \"Unit Tracker\"" >&2
    exit 1
fi

if ! [[ "$NAME" =~ ^[a-z][a-z0-9]*$ ]]; then
    echo "error: name must be lowercase letters and digits only (no dashes/underscores)" >&2
    echo "       got: $NAME" >&2
    exit 1
fi

TEMPLATE="$ATAK_SDK/samples/plugintemplate"
DEST="$REPO_ROOT/plugins/$NAME"

[ -d "$TEMPLATE" ] || { echo "error: template not found at $TEMPLATE" >&2; exit 1; }
[ -e "$DEST" ] && { echo "error: $DEST already exists" >&2; exit 1; }

# Class name: unittracker -> Unittracker. Renamed from the template's PluginTemplate.
CLASS="$(printf '%s' "${NAME:0:1}" | tr '[:lower:]' '[:upper:]')${NAME:1}"

echo "==> copying $TEMPLATE -> $DEST"
mkdir -p "$REPO_ROOT/plugins"
cp -R "$TEMPLATE" "$DEST"
rm -rf "$DEST/build" "$DEST/app/build" "$DEST/.gradle" "$DEST/.idea"
find "$DEST" -name '.DS_Store' -delete

echo "==> renaming package com.atakmap.android.plugintemplate -> com.atakmap.android.$NAME"
for variant in main gov test androidTest; do
    OLD_PKG_DIR="$DEST/app/src/$variant/java/com/atakmap/android/plugintemplate"
    if [ -d "$OLD_PKG_DIR" ]; then
        mv "$OLD_PKG_DIR" "$DEST/app/src/$variant/java/com/atakmap/android/$NAME"
    fi
done

echo "==> rewriting sources"
# Text files only — never touch the binaries (icons, fonts, jars, wrapper).
find "$DEST" -type f \
    \( -name '*.java' -o -name '*.kt' -o -name '*.xml' -o -name '*.gradle' \
       -o -name '*.properties' -o -name '*.pro' -o -name '*.txt' -o -name '*.md' \) \
    -not -path '*/gradle/wrapper/*' -print0 |
while IFS= read -r -d '' f; do
    sed -i '' \
        -e "s/com\.atakmap\.android\.plugintemplate/com.atakmap.android.$NAME/g" \
        -e "s/plugintemplate/$NAME/g" \
        -e "s/PluginTemplate/$CLASS/g" \
        "$f"
done

if [ -f "$DEST/app/src/main/java/com/atakmap/android/$NAME/plugin/PluginTemplate.java" ]; then
    mv "$DEST/app/src/main/java/com/atakmap/android/$NAME/plugin/PluginTemplate.java" \
       "$DEST/app/src/main/java/com/atakmap/android/$NAME/plugin/$CLASS.java"
fi

echo "==> setting display name and rootProject.name"
cat > "$DEST/app/src/main/res/values/strings.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- App -->
    <string name="app_name">$DISPLAY</string>
    <!-- description -->
    <string name="app_desc">$DISPLAY</string>
</resources>
EOF

# rootProject.name drives archivesBaseName and the proguard repackage target.
if ! grep -q '^rootProject.name' "$DEST/settings.gradle"; then
    printf "\nrootProject.name = '%s'\n" "$NAME" >> "$DEST/settings.gradle"
fi

echo "==> writing local.properties (gitignored — machine-local paths)"
cat > "$DEST/local.properties" <<EOF
# Machine-local. Gitignored: never commit paths or credentials.
# Offline build against the SDK unpacked outside the repo.
sdk.dir=$ANDROID_HOME
sdk.path=$ATAK_SDK
takdev.plugin=$ATAK_SDK/atak-gradle-takdev.jar

# Artifactory build (alternative to offline) — uncomment and fill in if a tak.gov
# Artifactory account exists. Setting takrepo.url switches takdev out of offline mode.
#takrepo.url=https://artifacts.tak.gov/artifactory/maven
#takrepo.user=
#takrepo.password=
EOF

sed -i '' "1s/.*/$DISPLAY/" "$DEST/README.md"

echo
echo "created plugins/$NAME"
echo "  entry class: com.atakmap.android.$NAME.plugin.$CLASS"
echo
echo "next:"
echo "  cd plugins/$NAME && ./gradlew assembleCivDebug"
echo "  adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-*.apk"
