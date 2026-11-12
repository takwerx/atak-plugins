#!/bin/bash
# add-community-health: give a plugin the files that make a public issue useful
# before anyone has replied to it.
#
# Every plugin directory is subtree-pushed to its own public repo, and until
# 2026-10-26 those repos took bug reports as free text. The first reply to
# almost every one was "which plugin version, which ATAK, which device?", a
# round trip a reporter in the field answers a day later. This writes, into
# plugins/<Plugin>/:
#
#   .github/ISSUE_TEMPLATE/bug.yml      version, ATAK, device, expected vs actual, logs
#   .github/ISSUE_TEMPLATE/feature.yml  what are you trying to do
#   .github/ISSUE_TEMPLATE/config.yml   points security reports at private reporting
#   SECURITY.md                         report privately; latest release gets fixes
#
# The text lives once, in scripts/templates/community-health/; change it there
# and re-run with --force to roll the change out to every plugin. The display
# name and the public repo name are read off the plugin's README (title line
# and the "All releases:" link), the same way add-license.sh reads the title.
#
# Private vulnerability reporting must be switched on for the public repo (the
# tune-up script does it); config.yml and SECURITY.md both point at it.
#
#   scripts/add-community-health.sh <Plugin>           # writes only what is missing
#   scripts/add-community-health.sh <Plugin> --force   # overwrite existing files too
#   scripts/add-community-health.sh --all              # every plugin under plugins/
#
# Idempotent by default: a file that already exists is left alone, so a plugin
# that has grown its own form keeps it. Exit 1 on a plugin it cannot read.
set -u
FORCE=0
ALL=0
PLUGIN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --force) FORCE=1; shift ;;
    --all) ALL=1; shift ;;
    -*) echo "usage: $0 <Plugin>|--all [--force]" >&2; exit 2 ;;
    *) [ -z "$PLUGIN" ] || { echo "usage: $0 <Plugin>|--all [--force]" >&2; exit 2; }; PLUGIN="$1"; shift ;;
  esac
done
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TPL="$ROOT/scripts/templates/community-health"
[ -d "$TPL" ] || { echo "add-community-health: missing templates: $TPL" >&2; exit 2; }
cd "$ROOT" || exit 2

one() {
  local name="$1" dir="plugins/$1" display repo wrote=0 src dst
  [ -d "$dir" ] || { echo "add-community-health: no such plugin: $dir" >&2; return 1; }

  display="$(sed -n '1s/^ATAK Plugin[^A-Za-z0-9]*//p' "$dir/README.md" 2>/dev/null)"
  repo="$(grep -o -m1 'github\.com/takwerx/[A-Za-z0-9._-]*/releases' "$dir/README.md" 2>/dev/null | head -1 | cut -d/ -f3)"
  if [ -z "$display" ] || [ -z "$repo" ]; then
    echo "add-community-health: $name: README needs the title line and the 'All releases:' link to name the public repo" >&2
    return 1
  fi

  for pair in bug.yml:.github/ISSUE_TEMPLATE/bug.yml \
              feature.yml:.github/ISSUE_TEMPLATE/feature.yml \
              config.yml:.github/ISSUE_TEMPLATE/config.yml \
              SECURITY.md:SECURITY.md; do
    src="$TPL/${pair%%:*}"
    dst="$dir/${pair#*:}"
    if [ -e "$dst" ] && [ "$FORCE" -ne 1 ]; then
      echo "  keep   $dst (exists)"
      continue
    fi
    mkdir -p "$(dirname "$dst")"
    sed -e "s|@@DISPLAY@@|$display|g" -e "s|@@REPO@@|$repo|g" "$src" > "$dst"
    echo "  write  $dst"
    wrote=$((wrote + 1))
  done
  echo "add-community-health: $name ($display, takwerx/$repo): $wrote file(s) written"
}

rc=0
if [ "$ALL" -eq 1 ]; then
  for d in plugins/*/; do
    one "$(basename "$d")" || rc=1
  done
else
  [ -n "$PLUGIN" ] || { echo "usage: $0 <Plugin>|--all [--force]" >&2; exit 2; }
  one "$PLUGIN" || rc=1
fi
exit $rc
