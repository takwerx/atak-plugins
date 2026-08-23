#!/usr/bin/env bash
# Scaffold a new ATAK plugin from the SDK's plugintemplate sample.
#
#   ./scripts/new-plugin.sh <PluginName> "Display Name"
#
# <PluginName> becomes the directory name, rootProject.name, the proguard repackage
# descriptor, AND the APK name that tak.gov produces from a source submission. Pick it
# deliberately — it is the public identity of the plugin.
#
# Letters and digits only: no dashes or underscores. The release build writes
# "-repackageclasses atakplugin.${rootProject.name}" into proguard, and a dash there is
# an invalid Java package name — debug builds pass, so it only fails at release time.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./env.sh
source "$REPO_ROOT/scripts/env.sh"

NAME="${1:-}"
DISPLAY="${2:-}"

if [ -z "$NAME" ] || [ -z "$DISPLAY" ]; then
    echo "usage: $0 <PluginName> \"Display Name\"" >&2
    echo "example: $0 UnitTracker \"Unit Tracker\"" >&2
    exit 1
fi

if ! [[ "$NAME" =~ ^[A-Za-z][A-Za-z0-9]*$ ]]; then
    echo "error: name must be letters and digits only (no dashes, underscores or spaces)" >&2
    echo "       got: $NAME" >&2
    exit 1
fi

TEMPLATE="$ATAK_SDK/samples/plugintemplate"
DEST="$REPO_ROOT/plugins/$NAME"

[ -d "$TEMPLATE" ] || { echo "error: template not found at $TEMPLATE" >&2; exit 1; }
[ -e "$DEST" ] && { echo "error: $DEST already exists" >&2; exit 1; }

# Java package segments are lowercase by convention; the class keeps the given casing.
PKG="$(printf '%s' "$NAME" | tr '[:upper:]' '[:lower:]')"
CLASS="$(printf '%s' "${NAME:0:1}" | tr '[:lower:]' '[:upper:]')${NAME:1}"

echo "==> copying $TEMPLATE -> $DEST"
mkdir -p "$REPO_ROOT/plugins"
cp -R "$TEMPLATE" "$DEST"
rm -rf "$DEST/build" "$DEST/app/build" "$DEST/.gradle" "$DEST/.idea" "$DEST/.takdev"
find "$DEST" -name '.DS_Store' -delete

echo "==> renaming package com.atakmap.android.plugintemplate -> com.atakmap.android.$PKG"
for variant in main gov test androidTest; do
    OLD_PKG_DIR="$DEST/app/src/$variant/java/com/atakmap/android/plugintemplate"
    if [ -d "$OLD_PKG_DIR" ]; then
        mv "$OLD_PKG_DIR" "$DEST/app/src/$variant/java/com/atakmap/android/$PKG"
    fi
done

echo "==> rewriting sources"
# Text files only — never touch binaries (icons, fonts, the gradle wrapper jar).
find "$DEST" -type f \
    \( -name '*.java' -o -name '*.kt' -o -name '*.xml' -o -name '*.gradle' \
       -o -name '*.properties' -o -name '*.pro' -o -name '*.txt' -o -name '*.md' \) \
    -not -path '*/gradle/wrapper/*' -print0 |
while IFS= read -r -d '' f; do
    sed -i '' \
        -e "s/com\.atakmap\.android\.plugintemplate/com.atakmap.android.$PKG/g" \
        -e "s/plugintemplate/$PKG/g" \
        -e "s/PluginTemplate/$CLASS/g" \
        "$f"
done

if [ -f "$DEST/app/src/main/java/com/atakmap/android/$PKG/plugin/PluginTemplate.java" ]; then
    mv "$DEST/app/src/main/java/com/atakmap/android/$PKG/plugin/PluginTemplate.java" \
       "$DEST/app/src/main/java/com/atakmap/android/$PKG/plugin/$CLASS.java"
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

# rootProject.name drives archivesBaseName, the proguard repackage target, and the APK
# name tak.gov builds from a source submission. It must match the directory name.
if ! grep -q '^rootProject.name' "$DEST/settings.gradle"; then
    printf "\nrootProject.name = '%s'\n" "$NAME" >> "$DEST/settings.gradle"
fi

# tak.gov requirement: the proguard User Section must keep THIS plugin's package. A
# template or copied-project keep rule protects nothing and the release build silently
# obfuscates classes the plugin loader needs.
cat >> "$DEST/app/proguard-gradle.txt" <<EOF

# Keep this plugin's own classes. tak.gov requires a plugin-specific rule here —
# a leftover rule from a copied project protects nothing.
-keep class com.atakmap.android.$PKG.** { *; }
EOF

# Each plugin directory is pushed out to its own public repo with git subtree, so it has
# to be safe as a standalone repo root on its own — SDK binaries, signing material and
# local.properties must be ignored there, not only by the monorepo root .gitignore.
echo "==> writing .gitignore"
cat > "$DEST/.gitignore" <<'GITIGNORE_EOF'
# ATAK SDK artifacts — never commit (SDK license forbids redistribution)
main.jar
atak.apk
atak-javadoc.jar
atak-gradle-takdev.jar
android_keystore

# Signing material
*.jks
*.keystore
*.p12
keystore.properties

# Machine-local paths and credentials
local.properties

# Unpacked by the takdev Gradle plugin at build time
.takdev/

# Build output
.gradle/
build/
app/libs/
captures/
.externalNativeBuild/
.cxx/
*.apk
*.aab

# IDE
.idea/
*.iml
*.ipr
*.iws
.DS_Store

# Submission zips
dist/
*.zip
GITIGNORE_EOF

echo "==> writing local.properties (gitignored — machine-local paths)"
cat > "$DEST/local.properties" <<EOF
# Machine-local. Gitignored: never commit real paths or credentials.
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
echo "  entry class: com.atakmap.android.$PKG.plugin.$CLASS"
echo "  APK name on submission: ATAK-Plugin-$NAME-<ver>-<sha>-<atakver>"
echo
echo "next:"
echo "  cd plugins/$NAME && ./gradlew assembleCivDebug"
echo "  adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-*.apk"
