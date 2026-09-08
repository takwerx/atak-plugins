#!/usr/bin/env bash
# One git worktree per plugin, so several sessions can build plugins at the
# same time without sharing a checkout. The layout and the rules are in
# CLAUDE.md, "Working in parallel".
#
#   scripts/worktree.sh new <Plugin|tooling> <branch>    # ~/GitHub/atak-plugins-<plugin> on <branch>
#   scripts/worktree.sh list                             # every worktree: branch, distance from main, state
#   scripts/worktree.sh setup [<Plugin|path>]            # (re)apply the per-worktree pieces below
#   scripts/worktree.sh remove <Plugin|path> [--unmerged]
#
# A worktree shares the repository but starts with no gitignored file, so
# `new` also writes what a checkout needs before it can build or ship:
#   - dist -> $ATAK_DIST: zips and signed APKs are in one place from every checkout
#   - plugins/*/local.properties, each pointing at the SDK that plugin targets
#   - Claude Code's auto-memory for the new directory linked to the main
#     checkout's. Memory is keyed by directory, and a session opened in a
#     worktree would otherwise start with none of it.
#
# <branch> is created from main when it does not exist yet. Plugin names are
# the directory names under plugins/ (CamDepot, FOBS); `tooling` is the
# worktree for shared files (scripts/, .claude/, CLAUDE.md). A name with no
# plugin on main yet is a NEW plugin: its worktree is made here first, then
# new-plugin.sh scaffolds the plugin inside it (CLAUDE.md, "Creating a plugin").
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./env.sh
source "$REPO_ROOT/scripts/env.sh"

MAIN_WT="$(git -C "$REPO_ROOT" worktree list --porcelain | sed -n '1s/^worktree //p')"
PARENT="$(dirname "$MAIN_WT")"
BASE="$(basename "$MAIN_WT")"

usage() { sed -n '2,/^set -euo/p' "$0" | grep '^#' | sed 's/^# \{0,1\}//' >&2; exit 2; }

path_of() { # <Plugin|tooling|path> -> absolute worktree path
    case "$1" in
        */*|.) ( cd "$1" 2>/dev/null && pwd ) || printf '%s\n' "$1" ;;
        *)     printf '%s/%s-%s\n' "$PARENT" "$BASE" "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" ;;
    esac
}

is_plugin_wt() { # <path>: named for a plugin under plugins/ (atak-plugins-<plugin>)?
    # On main, or only in this worktree: a new plugin before its first ship.
    local suffix="${1##*/$BASE-}" d
    for d in "$MAIN_WT"/plugins/*/ "$1"/plugins/*/; do
        [ "$(basename "$d" | tr '[:upper:]' '[:lower:]')" = "$suffix" ] && return 0
    done
    return 1
}

memory_link() { # <worktree path>: ~/.claude/projects/<slug>/memory -> the main checkout's
    local slug main_slug proj
    slug="$(printf '%s' "$1" | tr '/' '-')"
    main_slug="$(printf '%s' "$MAIN_WT" | tr '/' '-')"
    [ "$slug" != "$main_slug" ] || return 0
    if [ ! -d "$HOME/.claude/projects/$main_slug/memory" ]; then
        echo "    no auto-memory at ~/.claude/projects/$main_slug/memory; nothing to link"; return 0
    fi
    proj="$HOME/.claude/projects/$slug"
    mkdir -p "$proj"
    if [ -L "$proj/memory" ]; then
        echo "    memory already linked -> $(readlink "$proj/memory")"
    elif [ -d "$proj/memory" ] && [ -n "$(ls -A "$proj/memory")" ]; then
        echo "    WARN ~/.claude/projects/$slug/memory has files of its own; not replacing it with a link"
    else
        rmdir "$proj/memory" 2>/dev/null || true
        ln -s "../$main_slug/memory" "$proj/memory"
        echo "    memory -> ~/.claude/projects/$main_slug/memory"
    fi
}

setup() { # <worktree path>
    local wt="$1" g
    [ -d "$wt/.git" ] || [ -f "$wt/.git" ] || { echo "error: $wt is not a checkout" >&2; exit 1; }
    echo "==> setup $wt"
    mkdir -p "$ATAK_DIST/signed"
    if [ -L "$wt/dist" ]; then
        echo "    dist -> $(readlink "$wt/dist")"
    elif [ -e "$wt/dist" ]; then
        echo "    WARN $wt/dist is a real directory: move its contents into $ATAK_DIST, delete it, rerun setup"
    else
        ln -s "$ATAK_DIST" "$wt/dist"
        echo "    dist -> $ATAK_DIST"
    fi
    for g in "$wt"/plugins/*/app/build.gradle; do
        [ -f "$g" ] || continue
        "$REPO_ROOT/scripts/local-properties.sh" "$(dirname "$(dirname "$g")")"
    done
    memory_link "$wt"
}

cmd_new() {
    local name="${1:-}" branch="${2:-}" wt
    [ -n "$name" ] && [ -n "$branch" ] || usage
    case "$name" in */*) echo "error: give a plugin name (CamDepot, FOBS) or 'tooling', not a path" >&2; exit 2 ;; esac
    # Compare against the directory names main actually has, never by probing
    # the path: the Mac's filesystem is case-insensitive, so plugins/Camdepot
    # "exists" there while git and Linux know only CamDepot.
    local new_plugin=0 d found=0
    if [ "$name" != tooling ]; then
        for d in "$MAIN_WT"/plugins/*/; do
            d="$(basename "$d")"
            [ -f "$MAIN_WT/plugins/$d/app/build.gradle" ] || continue
            if [ "$d" = "$name" ]; then found=1; break; fi
            if [ "$(printf '%s' "$d" | tr '[:upper:]' '[:lower:]')" = "$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')" ]; then
                echo "error: no plugin at plugins/$name on main; did you mean $d? (names are case-sensitive)" >&2; exit 2
            fi
        done
        if [ "$found" = 0 ]; then
            # Not on main: a brand-new plugin, worktree before scaffold.
            case "$name" in *[!A-Za-z0-9]*)
                echo "error: a plugin name is letters and digits only, no dashes or underscores (new-plugin.sh)" >&2; exit 2 ;;
            esac
            new_plugin=1
            echo "==> no plugins/$name on main yet: a NEW plugin. Worktree first, then new-plugin.sh inside it."
        fi
    fi
    wt="$(path_of "$name")"
    if [ -e "$wt" ]; then
        echo "error: $wt exists. One worktree per plugin: work there, or 'remove' it first." >&2; exit 1
    fi
    if [ "$(git -C "$MAIN_WT" branch --show-current)" != main ]; then
        echo "WARN the main checkout $MAIN_WT is on '$(git -C "$MAIN_WT" branch --show-current)', not main (CLAUDE.md: it stays on main)"
    fi
    if git -C "$REPO_ROOT" rev-parse --verify -q "refs/heads/$branch" >/dev/null; then
        echo "==> worktree $wt on existing branch $branch"
        git -C "$REPO_ROOT" worktree add "$wt" "$branch"
    else
        echo "==> worktree $wt on new branch $branch from main"
        git -C "$REPO_ROOT" worktree add "$wt" -b "$branch" main
    fi
    setup "$wt"
    echo
    echo "$wt  [$branch]"
    if [ "$new_plugin" = 1 ]; then
        echo "Next, in that worktree:"
        echo "  ./scripts/new-plugin.sh $name \"Display Name\"   # then commit plugins/$name"
    fi
    echo "Open the session there. It merges main into the branch by itself when it opens"
    echo "and before every zip (scripts/check-main-merged.sh --merge); only a conflict needs a hand."
}

cmd_list() {
    local wt br ahead behind dirty state
    printf '%-44s %-24s %s\n' "worktree" "branch" "state"
    git -C "$REPO_ROOT" worktree list --porcelain | sed -n 's/^worktree //p' | while read -r wt; do
        br="$(git -C "$wt" branch --show-current 2>/dev/null || true)"
        [ -n "$br" ] || br="(detached)"
        ahead="$(git -C "$wt" rev-list --count main..HEAD 2>/dev/null || echo '?')"
        behind="$(git -C "$wt" rev-list --count HEAD..main 2>/dev/null || echo '?')"
        dirty="$(git -C "$wt" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
        state=""
        [ "$dirty" = 0 ] || state="$state uncommitted:$dirty"
        [ "$ahead" = 0 ] || state="$state ahead:$ahead"
        [ "$behind" = 0 ] || state="$state behind-main:$behind"
        [ -L "$wt/dist" ] || state="$state no-dist-link"
        if [ "$wt" = "$MAIN_WT" ]; then
            state="$state main-checkout"
            [ "$br" = main ] || state="$state NOT-ON-MAIN"
        elif [ "$ahead" = 0 ] && [ "$dirty" = 0 ]; then
            # a plugin's worktree lives as long as the plugin; a topic worktree
            # (tooling-*, an old task name) is done once it equals main
            if is_plugin_wt "$wt"; then
                [ "$behind" = 0 ] && state="$state up-to-date"
            else
                state="$state merged,removable"
            fi
        fi
        printf '%-44s %-24s %s\n' "${wt/#$HOME/~}" "$br" "${state# }"
    done
}

cmd_remove() {
    local wt br dirty ahead unmerged=0
    [ -n "${1:-}" ] || usage
    wt="$(path_of "$1")"
    [ "${2:-}" = "--unmerged" ] && unmerged=1
    if [ ! -d "$wt" ] || ! git -C "$REPO_ROOT" worktree list --porcelain | grep -x "worktree $wt" >/dev/null; then
        echo "error: $wt is not a worktree of this repository" >&2; exit 1
    fi
    [ "$wt" != "$MAIN_WT" ] || { echo "error: $wt is the main checkout" >&2; exit 1; }
    br="$(git -C "$wt" branch --show-current)"
    dirty="$(git -C "$wt" status --porcelain | wc -l | tr -d ' ')"
    ahead="$(git -C "$wt" rev-list --count main..HEAD)"
    if [ "$dirty" != 0 ]; then
        echo "error: $wt has $dirty uncommitted change(s); commit or discard them first:" >&2
        git -C "$wt" status --short | head -10 >&2; exit 1
    fi
    if [ "$ahead" != 0 ] && [ "$unmerged" = 0 ]; then
        echo "error: branch $br has $ahead commit(s) not on main. Ship it, or pass --unmerged to remove the" >&2
        echo "       checkout anyway (the branch and its commits stay; only the directory goes)." >&2; exit 1
    fi
    git -C "$REPO_ROOT" worktree remove "$wt"
    echo "removed $wt; branch $br kept (git branch -d $br once it is merged)"
}

case "${1:-}" in
    new)    shift; cmd_new "$@" ;;
    list)   cmd_list ;;
    setup)  shift; setup "$(path_of "${1:-$REPO_ROOT}")" ;;
    remove) shift; cmd_remove "$@" ;;
    *)      usage ;;
esac
