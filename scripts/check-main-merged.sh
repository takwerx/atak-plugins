#!/bin/bash
# check-main-merged: the current branch must contain every commit on main.
#
# main only moves through /ship, and every shared release rule (scripts/,
# .claude/, CLAUDE.md, the build-file invariants in each plugin's
# app/build.gradle) lands there on its own tooling ship. A plugin branch that
# has not merged main since then builds and ships WITHOUT those rules, and
# nothing else says so: FOBS 0.5's zips were built on 2026-09-06 without the
# versionCode gate that camdepot-v1.3 had already added, and 0.4 had shipped
# with versionCode 1. With one worktree per plugin this recurs for every rule
# unless something refuses.
#
#   scripts/check-main-merged.sh     # PASS, or the missing commits and the fix
#
# submission-zip.sh runs it before zipping and /ship runs it in pre-flight.
# The fix is always the same: `git merge main` on the branch. Exit 1 when
# behind, 2 when not on a branch or there is no main.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
BRANCH="$(git branch --show-current)"
[ -n "$BRANCH" ] || { echo "check-main-merged: detached HEAD in $ROOT" >&2; exit 2; }
git rev-parse --verify -q main >/dev/null || { echo "check-main-merged: no local main branch" >&2; exit 2; }

# origin/main ahead of main means main was pushed from somewhere this Mac has
# not pulled: the main checkout must catch up first, or /ship merges onto a
# stale main. Best effort; being offline is not a finding.
if git fetch -q origin main 2>/dev/null; then
  ahead="$(git rev-list --count main..origin/main 2>/dev/null || echo 0)"
  if [ "${ahead:-0}" -gt 0 ]; then
    echo "  FAIL: origin/main is $ahead commit(s) ahead of local main. In the main checkout: git pull --ff-only origin main"
    echo "check-main-merged: FAIL (local main is stale)" >&2
    exit 1
  fi
else
  echo "  note  could not fetch origin (offline?); comparing against local main only"
fi

if [ "$BRANCH" = main ]; then
  echo "check-main-merged: PASS (on main)"; exit 0
fi
missing="$(git rev-list --count HEAD..main)"
if [ "$missing" = 0 ]; then
  echo "check-main-merged: PASS ($BRANCH contains main $(git rev-parse --short main))"; exit 0
fi
echo "  FAIL: $BRANCH is missing $missing commit(s) that are on main:"
git log --oneline HEAD..main | sed 's/^/        /'
shared="$(git log --oneline HEAD..main -- scripts .claude CLAUDE.md README.md .gitignore 'plugins/*/app/build.gradle' | wc -l | tr -d ' ')"
if [ "$shared" -gt 0 ]; then
  echo "  FAIL: $shared of them change shared rules (scripts/, .claude/, CLAUDE.md, build-file invariants); this branch builds without them"
fi
echo "check-main-merged: FAIL ($BRANCH is behind main): git merge main on the branch, then rerun" >&2
exit 1
