#!/usr/bin/env bash
# ci-plugin-check: what a pull request must pass for one plugin -- the civ debug
# build and the plugin's unit tests. .github/workflows/pr-checks.yml runs this on
# the Studio, and you can run the very same thing by hand:
#
#   scripts/ci-plugin-check.sh <Plugin>
#
# It builds against the ATAK SDK under ~/atak-sdk (local-properties.sh picks the
# newest one for the plugin's ATAK_VERSION). The SDK license forbids shipping it,
# which is why this runs on a self-hosted runner and never on GitHub's.
#
# Gradle goes through `heavy` when the Studio has it, so builds queue behind
# whatever else is running instead of fighting it for memory.
#
# Exit 1 when the build or a test fails, 2 on a usage error.
set -euo pipefail
PLUGIN="${1:-}"
[ $# -eq 1 ] && [ -n "$PLUGIN" ] || { echo "usage: $0 <Plugin>" >&2; exit 2; }
case "$PLUGIN" in *[!A-Za-z0-9]*) echo "ci-plugin-check: plugin names are letters and digits only" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/plugins/$PLUGIN"
[ -f "$DIR/app/build.gradle" ] || { echo "ci-plugin-check: no plugin at plugins/$PLUGIN" >&2; exit 2; }

# shellcheck source=./env.sh
source "$ROOT/scripts/env.sh"
"$ROOT/scripts/local-properties.sh" "$DIR"

HEAVY=""
[ -x "$HOME/tak-agent/workers/bin/heavy" ] && HEAVY="$HOME/tak-agent/workers/bin/heavy"

cd "$DIR"
# A Gradle daemon left running holds a gigabyte or two after the job ends.
trap './gradlew --stop >/dev/null 2>&1 || true' EXIT

# The SDK template ships an ExampleTest and no junit, so on a plugin that never
# added the dependency testCivDebugUnitTest cannot even compile. Such a plugin
# gets the build alone -- unless it has real tests, which would then never run.
TASKS="assembleCivDebug testCivDebugUnitTest"
if ! grep -qE "^[[:space:]]*testImplementation[[:space:]]+['\"]junit:junit" app/build.gradle; then
  REAL="$(find app/src/test -name '*.java' -o -name '*.kt' 2>/dev/null | grep -v '/ExampleTest\.java$' || true)"
  if [ -n "$REAL" ]; then
    echo "ci-plugin-check: $PLUGIN has tests but no junit dependency, so they never run:" >&2
    echo "$REAL" >&2
    echo "  add  testImplementation 'junit:junit:4.13.2'  to plugins/$PLUGIN/app/build.gradle" >&2
    exit 1
  fi
  echo "notice: $PLUGIN has no unit tests (template ExampleTest only, no junit); building only"
  TASKS="assembleCivDebug"
fi

echo "== $PLUGIN: $TASKS"
# shellcheck disable=SC2086
$HEAVY ./gradlew --console=plain $TASKS

ls app/build/outputs/apk/civ/debug/ATAK-Plugin-*.apk >/dev/null 2>&1 \
  || { echo "ci-plugin-check: $PLUGIN built but no ATAK-Plugin-*.apk came out" >&2; exit 1; }
echo "== $PLUGIN: ok"
