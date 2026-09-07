#!/usr/bin/env bash
# Write a plugin's gitignored local.properties: the machine-local paths to the
# Android SDK and the ATAK SDK. new-plugin.sh writes one for a new plugin and
# scripts/worktree.sh writes one for every plugin in a new worktree, because a
# worktree starts with no gitignored file at all and a plugin without this one
# does not build.
#
#   scripts/local-properties.sh <plugin dir> [<ATAK SDK dir>]
#
# The SDK defaults to the newest installed one matching the plugin's
# ext.ATAK_VERSION (under ~/atak-sdk), else $ATAK_SDK from env.sh. An existing
# file is kept: a session may have retargeted it on purpose.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./env.sh
source "$REPO_ROOT/scripts/env.sh"

DIR="${1:-}"; SDK="${2:-}"
[ -n "$DIR" ] && [ -f "$DIR/app/build.gradle" ] || { echo "usage: $0 <plugin dir> [<ATAK SDK dir>]" >&2; exit 2; }
OUT="$DIR/local.properties"
if [ -e "$OUT" ]; then
    echo "    keeping existing $OUT"; exit 0
fi
if [ -z "$SDK" ]; then
    WANT="$(sed -n "s/.*ext\.ATAK_VERSION *= *['\"]\([^'\"]*\)['\"].*/\1/p" "$DIR/app/build.gradle" | head -1)"
    [ -n "$WANT" ] && SDK="$(ls -d "$HOME"/atak-sdk/ATAK-CIV-"$WANT"* 2>/dev/null | sort -V | tail -1)" || true
    [ -n "$SDK" ] || SDK="$ATAK_SDK"
fi
[ -d "$SDK" ] || { echo "error: no ATAK SDK at $SDK" >&2; exit 1; }
cat > "$OUT" <<PROPS
# Machine-local. Gitignored: never commit real paths or credentials.
# Offline build against the SDK unpacked outside the repo.
sdk.dir=$ANDROID_HOME
sdk.path=$SDK
takdev.plugin=$SDK/atak-gradle-takdev.jar

# Artifactory build (alternative to offline) — uncomment and fill in if a tak.gov
# Artifactory account exists. Setting takrepo.url switches takdev out of offline mode.
#takrepo.url=https://artifacts.tak.gov/artifactory/maven
#takrepo.user=
#takrepo.password=
PROPS
echo "    wrote $OUT (sdk.path=$SDK)"
