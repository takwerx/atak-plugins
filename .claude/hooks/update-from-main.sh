#!/bin/bash
# update-from-main: SessionStart hook. A session opening in a worktree gets
# main merged into its branch, so "I want to work on this" is the whole
# procedure and nobody remembers `git merge main` (operator, 2026-09-07:
# "I can't just say hey I want to work on this?"). Every plugin worktree fell
# behind main by one commit at every tooling ship and every plugin ship, and
# check-main-merged.sh only refused; now it acts.
#
# scripts/check-main-merged.sh --merge does the work, and only on a clean tree
# with no merge in progress; this hook only decides what to say. Silent when
# there is nothing to do, one line when main came in, the check's own words
# when it could not (uncommitted changes, a conflict, a stale main), so the
# session tells the operator before building on an old main. Always exits 0:
# a failure here must not stop a session from opening.
ROOT="${CLAUDE_PROJECT_DIR:-$PWD}"
CHECK="$ROOT/scripts/check-main-merged.sh"
[ -f "$CHECK" ] || exit 0
git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || exit 0
out="$(bash "$CHECK" --merge 2>&1)"; rc=$?
case "$rc:$out" in
  0:*"PASS (on main)"*|0:*"contains main"*) exit 0 ;;
esac
if [ "$rc" = 0 ]; then
  echo "update-from-main: main was merged into this worktree's branch when the session opened. Tell the operator in one line."
  echo "$out"
else
  echo "update-from-main: this worktree's branch is behind main and main could NOT be merged in automatically. Tell the operator before building anything; do not work around it."
  echo "$out"
fi
exit 0
