#!/bin/bash
# sync-public-repo: the plugin's public repo must hold nothing the monorepo
# does not, or /ship's subtree push to it is refused as non-fast-forward.
#
# A plugin reaches takwerx/<repo> by `git subtree split` + push, so anything
# merged THERE (a pull request from the Claude worker or a contributor, the
# worker workflow itself, the Market's community list) is not in the split,
# and the push is refused partway through a ship. Until 2026-10-31 every ship
# hit this and merged the public main into a throwaway export branch by hand,
# and the next ship hit it again: all 16 public repos carried commits the
# monorepo did not (the worker workflow, and the Market's community list).
#
#   scripts/sync-public-repo.sh <Plugin>            # PASS, or what is missing
#   scripts/sync-public-repo.sh <Plugin> --merge    # bring it in, on this branch
#
# --merge, on a clean tree, adds two commits and touches nothing else:
#   1. the merged files: plugins/<Plugin> becomes the three-way merge of this
#      branch's split and the public main (git merge-tree, no work-tree merge)
#   2. a recorded split (the commit `git subtree split --rejoin` writes, with
#      its git-subtree-* trailers) naming a split commit whose parents are the
#      old split AND the public main
# The second is what makes the push a fast-forward: a merge alone is not
# enough, because the plain split /ship runs drops any parent it was never
# told is subtree history. `split --onto <public> --rejoin` does the same once,
# then dies ("cache ... already exists") on every later sync, because the
# earlier recorded split is inside the public history; writing the commit
# directly has no such limit.
#
# A merged-in claude-worker.yml is replaced by the monorepo's own, which reads
# its paths from TAK_WORKER_DIR instead of a /Users path. On a conflict nothing
# is written and the files are named.
#
# The repo is read from the README's "All releases:" line; --repo overrides,
# --url replaces the GitHub URL (a test copy). Exit 1 when the public repo
# holds commits this branch lacks (or --merge could not bring them in), 2 on
# bad usage.
set -u
USAGE="usage: $0 <Plugin> [--merge] [--repo <name>] [--url <git url>]"
PLUGIN="" MERGE=0 REPO="" URL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --merge) MERGE=1 ;;
    --repo) REPO="${2:-}"; shift ;;
    --url) URL="${2:-}"; shift ;;
    -*) echo "$USAGE" >&2; exit 2 ;;
    *) PLUGIN="$1" ;;
  esac
  shift
done
[ -n "$PLUGIN" ] || { echo "$USAGE" >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
PREFIX="plugins/$PLUGIN"
[ -d "$PREFIX" ] || { echo "sync-public-repo: no $PREFIX" >&2; exit 2; }
if [ -z "$REPO" ]; then
  REPO="$(sed -nE 's#^All releases: https://github\.com/takwerx/([A-Za-z0-9._-]+)/releases[[:space:]]*$#\1#p' "$PREFIX/README.md" 2>/dev/null | head -1)"
fi
[ -n "$REPO" ] || { echo "sync-public-repo: no \"All releases:\" line in $PREFIX/README.md; pass --repo" >&2; exit 2; }
[ -n "$URL" ] || URL="https://github.com/takwerx/$REPO.git"
REF="refs/public/$REPO"

if ! git fetch -q "$URL" "+main:$REF" 2>/dev/null; then
  if git ls-remote --exit-code "$URL" >/dev/null 2>&1; then
    echo "sync-public-repo: PASS (takwerx/$REPO has no main yet; the first push creates it)"
    exit 0
  fi
  echo "sync-public-repo: cannot reach $URL" >&2
  exit 1
fi
PUB="$(git rev-parse "$REF")"

SPLIT="$(git subtree split --prefix="$PREFIX" HEAD 2>/dev/null)" \
  || { echo "sync-public-repo: subtree split of $PREFIX failed" >&2; exit 1; }
if git merge-base --is-ancestor "$PUB" "$SPLIT"; then
  echo "sync-public-repo: PASS (takwerx/$REPO main ${PUB:0:8} is in the split; the push is a fast-forward)"
  exit 0
fi

echo "takwerx/$REPO main holds commits $PREFIX does not:"
git log --oneline "$PUB" --not "$SPLIT" | sed 's/^/  /'
echo "files that differ from the split:"
git diff --name-status "$SPLIT" "$PUB" | sed 's/^/  /'
if [ "$MERGE" = 0 ]; then
  echo "sync-public-repo: FAIL. Bring them in on this branch: $0 $PLUGIN --merge"
  exit 1
fi

[ -n "$(git branch --show-current)" ] || { echo "sync-public-repo: detached HEAD" >&2; exit 1; }
if [ -n "$(git status --porcelain --untracked-files=no)" ] || [ -e "$(git rev-parse --git-path MERGE_HEAD)" ]; then
  echo "sync-public-repo: the tree is not clean; commit first" >&2
  exit 1
fi
BASE="$(git merge-base "$SPLIT" "$PUB")" || {
  echo "sync-public-repo: FAIL, takwerx/$REPO shares no history with the split of $PREFIX (was it pushed from somewhere else?)" >&2
  exit 1
}

MERGED="$(git merge-tree --write-tree --name-only --merge-base="$BASE" "$SPLIT" "$PUB")"
if [ $? -ne 0 ]; then
  echo "sync-public-repo: FAIL, the public changes conflict with $PREFIX in:"
  printf '%s\n' "$MERGED" | sed -n '2,/^$/p' | sed '/^$/d; s/^/  /'
  echo "Nothing was written. Merge by hand (git subtree merge --prefix=$PREFIX $REF),"
  echo "resolve and commit, then run this again with --merge: it records the split."
  exit 1
fi
MERGED="$(printf '%s\n' "$MERGED" | head -1)"

# The monorepo tree with $PREFIX replaced by the merge, built in a scratch index.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export GIT_INDEX_FILE="$TMP/index"
git read-tree HEAD
git rm -r -q --cached "$PREFIX"
git read-tree --prefix="$PREFIX/" "$MERGED"
WORKER="$PREFIX/.github/workflows/claude-worker.yml"
FIXED_WORKER=0
if git ls-files --error-unmatch "$WORKER" >/dev/null 2>&1 \
   && [ "$(git rev-parse ":$WORKER")" != "$(git rev-parse HEAD:.github/workflows/claude-worker.yml)" ]; then
  git update-index --cacheinfo "100644,$(git rev-parse HEAD:.github/workflows/claude-worker.yml),$WORKER"
  FIXED_WORKER=1
fi
TREE="$(git write-tree)"
unset GIT_INDEX_FILE
SUBTREE="$(git rev-parse "$TREE:$PREFIX")"

MSG="Merge takwerx/$REPO main into $PREFIX

What was merged on the public repo and not here, brought in so the next
subtree push is a fast-forward (scripts/sync-public-repo.sh)."
NEWSPLIT="$(git commit-tree "$SUBTREE" -p "$SPLIT" -p "$PUB" -m "$MSG")"
MERGECOMMIT="$(git commit-tree "$TREE" -p HEAD -m "$MSG")"
REJOIN="$(git commit-tree "$TREE" -p "$MERGECOMMIT" -p "$NEWSPLIT" -m "Split '$PREFIX/' into commit '$NEWSPLIT'

Records the split whose parents are the old split and takwerx/$REPO main,
so the plain subtree split /ship runs builds on it and the push is a
fast-forward (scripts/sync-public-repo.sh).

git-subtree-dir: $PREFIX
git-subtree-mainline: $MERGECOMMIT
git-subtree-split: $NEWSPLIT")"

ORIG="$(git rev-parse HEAD)"
git reset -q --keep "$REJOIN" || { echo "sync-public-repo: could not move the branch" >&2; exit 1; }
[ "$FIXED_WORKER" = 1 ] && echo "  $WORKER is the monorepo's copy (TAK_WORKER_DIR, no /Users path)"

CHECK="$(git subtree split --prefix="$PREFIX" HEAD 2>/dev/null)"
if [ "$CHECK" != "$NEWSPLIT" ] || ! git merge-base --is-ancestor "$PUB" "$CHECK"; then
  echo "sync-public-repo: FAIL, the split is ${CHECK:0:8}, not ${NEWSPLIT:0:8}; branch put back at ${ORIG:0:8}" >&2
  git reset -q --keep "$ORIG"
  exit 1
fi
echo "sync-public-repo: PASS (merged; the push of ${NEWSPLIT:0:8} over ${PUB:0:8} is a fast-forward)"
echo "Run ./scripts/publish-scrub.sh: what was merged in is now in the tracked tree."
