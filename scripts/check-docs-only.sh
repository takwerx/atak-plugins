#!/bin/bash
# check-docs-only: this branch may be published to a plugin's public repo
# WITHOUT a release, because nothing on it can change the APK.
#
# Publishing a file and releasing software were the same act until 2026-09-14,
# because the only path to a plugin's public repo was a subtree push inside
# /ship, and /ship refuses a version that is already signed. So a LICENSE file
# two contributors were waiting on -- takwerx/comms#1 and #2, both offering
# work, neither able to send a patch against a repo with no license -- could
# only be published by inventing a version: a new tak.gov submission, three
# fresh signed APKs and a Market catalog refresh, to ship a text file. The
# same wall stands in front of a wrong download link and a typo in a guide.
#
# The rule that makes a docs ship safe is mechanical: a docs ship CANNOT change
# what is inside the APK. Everything the Android build reads is denied here --
# app/, the gradle files, the wrapper. What is left is the plugin's published
# surface: its README, its guide and screenshots, its license and contributing
# files, and the tooling beside it that never enters the build. Change one byte
# under app/ and this fails and it is a release, which is the honest answer.
#
#   scripts/check-docs-only.sh <Plugin>             # against main
#   scripts/check-docs-only.sh <Plugin> --base <r>  # against another ref
#
# Exit 1 when the branch would change the APK or touches another plugin, 2 on
# a usage error.
set -u
PLUGIN=""
BASE="main"
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="${2:-}"; shift 2 || { echo "usage: $0 <Plugin> [--base <ref>]" >&2; exit 2; } ;;
    -*) echo "usage: $0 <Plugin> [--base <ref>]" >&2; exit 2 ;;
    *) [ -z "$PLUGIN" ] || { echo "usage: $0 <Plugin> [--base <ref>]" >&2; exit 2; }; PLUGIN="$1"; shift ;;
  esac
done
[ -n "$PLUGIN" ] || { echo "usage: $0 <Plugin> [--base <ref>]" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
[ -d "plugins/$PLUGIN" ] || { echo "check-docs-only: no such plugin: plugins/$PLUGIN" >&2; exit 2; }
git rev-parse --verify -q "$BASE" >/dev/null || { echo "check-docs-only: no such ref: $BASE" >&2; exit 2; }

BRANCH="$(git branch --show-current)"
[ -n "$BRANCH" ] || { echo "check-docs-only: detached HEAD in $ROOT" >&2; exit 2; }

CHANGED="$(git diff --name-only "$BASE...HEAD")"
if [ -z "$CHANGED" ]; then
  echo "check-docs-only: FAIL ($PLUGIN): nothing to publish, $BRANCH has no changes over $BASE" >&2
  exit 1
fi

fail=0

# A release-sized diff lists fifty files and buries the point. Ten and a count
# is what a person needs to see which rule was hit.
show() {
  local n; n="$(echo "$1" | wc -l | tr -d ' ')"
  echo "$1" | head -10 | sed 's/^/        /'
  [ "$n" -gt 10 ] && echo "        ... and $((n - 10)) more"
  return 0
}

# Anything the Android build reads. One byte here and the APK is not the one
# already signed and in the Market, so it is a release and needs a version.
APK_PATHS='^plugins/'"$PLUGIN"'/(app/|gradle/|gradlew|gradlew\.bat|build\.gradle|settings\.gradle|gradle\.properties|template\.local\.properties)'
inapk="$(echo "$CHANGED" | grep -E "$APK_PATHS" || true)"
if [ -n "$inapk" ]; then
  echo "  FAIL: these change what is inside the APK, so this is a release, not a docs publish:"
  show "$inapk"
  fail=1
fi

# Another plugin's directory, or a shared file. Shared files go to main on a
# tooling ship and never ride a plugin's publish (CLAUDE.md, "Working in
# parallel"); another plugin is another session's worktree.
outside="$(echo "$CHANGED" | grep -vE "^plugins/$PLUGIN/" || true)"
if [ -n "$outside" ]; then
  echo "  FAIL: these are outside plugins/$PLUGIN; shared files are a tooling ship, another plugin is its own:"
  show "$outside"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "check-docs-only: FAIL ($PLUGIN, $BRANCH over $BASE): not publishable without a release" >&2
  exit 1
fi

echo "  ok    $(echo "$CHANGED" | wc -l | tr -d ' ') file(s) change, none of them inside the APK:"
show "$(echo "$CHANGED" | sed "s|^plugins/$PLUGIN/||")"
echo "check-docs-only: PASS ($PLUGIN, $BRANCH over $BASE): publishable to the plugin's public repo with no release"
