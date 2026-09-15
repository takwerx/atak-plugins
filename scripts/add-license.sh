#!/bin/bash
# add-license: give a plugin its LICENSE, LICENSE-EXCEPTION, CONTRIBUTING and CLA.
#
# Every plugin directory is subtree-pushed to its own public repo, and until
# 2026-09-14 not one of them carried a license. Two people offered work on
# takwerx/comms -- an Idaho row file and Pennsylvania AuxComm data -- and
# neither could open a pull request, because there was nothing to contribute
# under. fabriazza asked the same question on takwerx/map-depot and it went
# unanswered for a week. A repository with no license is not "permissive by
# default"; it is all rights reserved, and a careful contributor reads it that
# way and walks.
#
# AGPL-3.0-or-later, matching infra-TAK, so a plugin cannot be taken closed and
# sold back to the agencies it was written for. Two things an ATAK plugin needs
# that infra-TAK does not, both in LICENSE-EXCEPTION.md: an additional
# permission under AGPL section 7 for the TAK Software, because a plugin's
# classes load into ATAK's process rather than sitting at arm's length; and a
# provenance list, because new-plugin.sh copies the SDK's plugintemplate
# wholesale and the SDK license grants the right to derive new works, not to
# sublicense the SDK.
#
#   scripts/add-license.sh <Plugin>              # writes only what is missing
#   scripts/add-license.sh <Plugin> --force      # overwrite existing files too
#   scripts/add-license.sh --all                 # every plugin under plugins/
#
# Idempotent by default: a file that already exists is left alone, so a plugin
# that has grown its own CONTRIBUTING.md (Comms documents its catalog row-file
# format there) keeps it. Exit 1 on a plugin it cannot read.
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
TPL="$ROOT/scripts/templates/license"
[ -d "$TPL" ] || { echo "add-license: missing templates: $TPL" >&2; exit 2; }
cd "$ROOT" || exit 2

one() {
  local name="$1" dir="plugins/$1" display pkg wrote=0
  [ -d "$dir" ] || { echo "add-license: no such plugin: $dir" >&2; return 1; }

  # LICENSE-EXCEPTION.md lists, by name, the files that came from the SDK
  # template, and a provenance statement that names files which do not exist is
  # worse than none. A half-scaffolded directory (Weather, 2026-09-14: a README
  # and app/build.gradle, no wrapper, no settings.gradle) gets a license when it
  # gets a plugin.
  if [ ! -f "$dir/settings.gradle" ] || [ ! -f "$dir/gradlew" ]; then
    echo "add-license: $name is not a scaffolded plugin (no settings.gradle/gradlew); license it once it is" >&2
    return 1
  fi

  # The display name is the README's title line; it is what users are shown and
  # what the license text should call the plugin ("Map Depot", not "MapDepot").
  display="$(sed -n '1s/^ATAK Plugin[^A-Za-z0-9]*//p' "$dir/README.md" 2>/dev/null)"
  [ -n "$display" ] || display="$name"

  # The java package segment, for the provenance list's file paths.
  pkg="$(basename "$(ls -d "$dir"/app/src/main/java/com/atakmap/android/*/ 2>/dev/null | head -1)" 2>/dev/null)"
  [ -n "$pkg" ] && [ "$pkg" != "*" ] || pkg="$(echo "$name" | tr '[:upper:]' '[:lower:]')"

  # The provenance list must name files that exist. Class names do not follow
  # the plugin name closely enough to guess: PLSS's fragment is
  # PlssPreferenceFragment, FOBS's is FobsPreferenceFragment, and TakwerxMarket
  # has none at all. So read them off disk.
  local derived pdir DERIVED_FILE
  pdir="$dir/app/src/main/java/com/atakmap/android/$pkg/plugin"
  derived=""
  if [ -d "$pdir" ]; then
    for f in "$pdir"/*.java; do
      [ -e "$f" ] || continue
      case "$(basename "$f")" in
        PluginNativeLoader.java) continue ;;   # listed above, unchanged
      esac
      derived="$derived- \`app/src/main/java/com/atakmap/android/$pkg/plugin/$(basename "$f")\`
"
    done
  fi
  [ -n "$derived" ] || derived="- (none: this plugin keeps no template-derived class)
"
  DERIVED_FILE="$(mktemp)"
  printf '%s' "$derived" > "$DERIVED_FILE"
  trap 'rm -f "$DERIVED_FILE"' RETURN

  put() { # <template> <destination>
    if [ -e "$dir/$2" ] && [ "$FORCE" -ne 1 ]; then
      echo "  keep  $dir/$2 (already present)"
      return 0
    fi
    awk -v d="$display" -v k="$pkg" -v df="$DERIVED_FILE" '
      { gsub(/__DISPLAY__/, d); gsub(/__PKG__/, k)
        if ($0 == "__DERIVED_CLASSES__") { while ((getline line < df) > 0) print line; close(df); next }
        print }' "$TPL/$1" > "$dir/$2"
    echo "  write $dir/$2"
    wrote=1
  }

  # The AGPL text is verbatim and carries no placeholder; a substitution pass
  # over it would corrupt a license document that says it may not be changed.
  if [ -e "$dir/LICENSE" ] && [ "$FORCE" -ne 1 ]; then
    echo "  keep  $dir/LICENSE (already present)"
  else
    cp "$TPL/LICENSE" "$dir/LICENSE"
    echo "  write $dir/LICENSE"
    wrote=1
  fi
  put LICENSE-EXCEPTION.md LICENSE-EXCEPTION.md
  put CONTRIBUTING.md CONTRIBUTING.md
  put CLA.md CLA.md

  # The README's own LICENSE section, appended once. The plugin page standard
  # (CLAUDE.md) fixes the order of everything above it, so this goes last and
  # nothing is inserted between the existing headings.
  if [ ! -f "$dir/README.md" ]; then
    # Appending to a file that is not there writes a README whose entire content
    # is a license section, which is not a plugin page. The page standard
    # (CLAUDE.md) owns that file; this only adds a section to one that exists.
    echo "  skip  $dir/README.md (no README yet; the page standard writes it)"
  elif grep -q '^LICENSE$' "$dir/README.md" 2>/dev/null; then
    echo "  keep  $dir/README.md (already has a LICENSE section)"
  else
    sed -e "s/__DISPLAY__/$display/g" "$TPL/README-license-section.md" >> "$dir/README.md"
    echo "  write $dir/README.md (LICENSE section appended)"
    wrote=1
  fi

  [ "$wrote" -eq 1 ] && echo "add-license: $name ($display) licensed AGPL-3.0-or-later" \
                     || echo "add-license: $name already complete"
  return 0
}

rc=0
if [ "$ALL" -eq 1 ]; then
  for d in plugins/*/; do
    n="$(basename "$d")"
    [ -f "$d/app/build.gradle" ] || continue
    echo "== $n"
    one "$n" || rc=1
  done
else
  [ -n "$PLUGIN" ] || { echo "usage: $0 <Plugin>|--all [--force]" >&2; exit 2; }
  one "$PLUGIN" || rc=1
fi
exit "$rc"
