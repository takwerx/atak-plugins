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
#   scripts/check-main-merged.sh            # PASS, or the missing commits
#   scripts/check-main-merged.sh --merge    # bring main in, when that is safe
#
# Nobody is meant to type `git merge main` (operator, 2026-09-07: "I can't
# just say I want to work on this?"). The SessionStart hook
# .claude/hooks/update-from-main.sh runs --merge when a session opens in a
# worktree, and submission-zip.sh runs it before zipping. --merge touches only
# a clean tree with no merge or rebase in progress, and on a conflict aborts
# and names the files, so a branch is never left half-merged; a conflict is
# the one case left for a person. /ship runs the plain check in pre-flight.
# Exit 1 when behind (or --merge could not bring main in), 2 when not on a
# branch or there is no main.
set -u
MERGE=0
for a in "$@"; do
  case "$a" in
    --merge) MERGE=1 ;;
    *) echo "usage: $0 [--merge]" >&2; exit 2 ;;
  esac
done
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

if [ "$MERGE" = 1 ]; then
  if [ -e "$(git rev-parse --git-path MERGE_HEAD)" ] || [ -d "$(git rev-parse --git-path rebase-merge)" ] || [ -d "$(git rev-parse --git-path rebase-apply)" ]; then
    echo "  FAIL: $BRANCH is $missing commit(s) behind main and a merge or rebase is in progress in $ROOT; finish or abort it, then rerun"
    echo "check-main-merged: FAIL ($BRANCH is behind main, merge in progress)" >&2
    exit 1
  fi
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "  FAIL: $BRANCH is $missing commit(s) behind main and has uncommitted changes, so main was not merged in:"
    git status --short --untracked-files=no | sed 's/^/        /'
    echo "check-main-merged: FAIL ($BRANCH is behind main): commit, then rerun" >&2
    exit 1
  fi
  msg="$(mktemp)"; err="$(mktemp)"
  trap 'rm -f "$msg" "$err"' EXIT
  {
    echo "Merge main into $BRANCH"
    echo
    echo "Brought in by check-main-merged --merge:"
    git log --oneline HEAD..main | sed 's/^/  /'
  } > "$msg"
  before="$(git rev-parse --short HEAD)"
  if git merge --no-edit -F "$msg" main >/dev/null 2>"$err"; then
    echo "check-main-merged: PASS (merged main $(git rev-parse --short main) into $BRANCH: $before -> $(git rev-parse --short HEAD), $missing commit(s))"
    sed -n '4,$p' "$msg" | sed 's/^  /        /'
    exit 0
  fi
  conflicts="$(git diff --name-only --diff-filter=U 2>/dev/null)"
  git merge --abort >/dev/null 2>&1
  if [ -n "$conflicts" ]; then
    echo "  FAIL: main does not merge cleanly into $BRANCH; conflicts in:"
    echo "$conflicts" | sed 's/^/        /'
    echo "  The branch is as it was. By hand: git merge main, resolve the files above, git commit."
    echo "check-main-merged: FAIL ($BRANCH is behind main, conflict)" >&2
  else
    echo "  FAIL: git merge main did not go through in $ROOT:"
    sed 's/^/        /' "$err"
    echo "check-main-merged: FAIL ($BRANCH is behind main, merge refused)" >&2
  fi
  exit 1
fi

echo "  FAIL: $BRANCH is missing $missing commit(s) that are on main:"
git log --oneline HEAD..main | sed 's/^/        /'
shared="$(git log --oneline HEAD..main -- scripts .claude CLAUDE.md README.md .gitignore 'plugins/*/app/build.gradle' | wc -l | tr -d ' ')"
if [ "$shared" -gt 0 ]; then
  echo "  FAIL: $shared of them change shared rules (scripts/, .claude/, CLAUDE.md, build-file invariants); this branch builds without them"
fi
echo "check-main-merged: FAIL ($BRANCH is behind main): scripts/check-main-merged.sh --merge brings it in (a session does this when it opens)" >&2
exit 1
