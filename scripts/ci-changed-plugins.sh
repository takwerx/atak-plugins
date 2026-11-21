#!/usr/bin/env bash
# ci-changed-plugins: which plugins a pull request touches, as a JSON array for
# the pr-checks.yml build matrix.
#
#   scripts/ci-changed-plugins.sh <base-ref>     # e.g. origin/main
#
# A plugin is touched when the diff against the merge base has a file under
# plugins/<Name>/. A change to the build tooling itself (scripts/ci-*.sh,
# env.sh, local-properties.sh, the workflow) touches no plugin but could break
# every build, so it runs one canary plugin that has real unit tests.
set -euo pipefail
BASE="${1:-}"
[ $# -eq 1 ] && [ -n "$BASE" ] || { echo "usage: $0 <base-ref>" >&2; exit 2; }
CANARY="SignalDF"

FILES="$(git diff --name-only "$BASE"...HEAD)"
# Letters and digits only: the name goes into a matrix and onto a command line.
PLUGINS="$(printf '%s\n' "$FILES" | sed -n 's#^plugins/\([A-Za-z0-9]*\)/.*#\1#p' | sort -u)"
# A plugin deleted by the PR has nothing to build.
PLUGINS="$(for p in $PLUGINS; do [ -f "plugins/$p/app/build.gradle" ] && echo "$p"; done || true)"

if [ -z "$PLUGINS" ] && printf '%s\n' "$FILES" \
     | grep -qE '^(scripts/(ci-[a-z-]+|env|local-properties)\.sh|\.github/workflows/pr-checks\.yml)$'; then
  PLUGINS="$CANARY"
fi

printf '%s\n' "$PLUGINS" | python3 -I -c 'import json,sys; print(json.dumps([l for l in sys.stdin.read().split() if l]))'
