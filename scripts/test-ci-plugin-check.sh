#!/usr/bin/env bash
# test-ci-plugin-check: proves the --release mode of ci-plugin-check.sh catches
# what the debug check lets through (ops #692).
#
#   scripts/test-ci-plugin-check.sh
#
# It adds a broken rule to SignalDF's proguard file. Proguard and R8 run only on
# a release build, so the debug check must still PASS and the release check must
# FAIL. Then it removes the rule and the release check must PASS again. The file
# is restored whatever happens. Takes about a minute (Gradle, through `heavy`).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULES="$ROOT/plugins/SignalDF/app/proguard-gradle.txt"
CHECK="$ROOT/scripts/ci-plugin-check.sh"
BACKUP="$(mktemp)"
cp "$RULES" "$BACKUP"
trap 'cp "$BACKUP" "$RULES"; rm -f "$BACKUP"' EXIT

fail=0
expect() { # expect <pass|fail> <label> <command...>
  local want="$1" label="$2"; shift 2
  if "$@" >/tmp/ci-plugin-check-test.log 2>&1; then got=pass; else got=fail; fi
  if [ "$got" = "$want" ]; then echo "ok   $label ($got)"; else
    echo "FAIL $label: wanted $want, got $got"; tail -15 /tmp/ci-plugin-check-test.log; fail=1; fi
}

printf '\n-thisIsNotAProguardOption\n' >> "$RULES"
expect pass "debug check ignores a broken proguard rule" bash "$CHECK" SignalDF
expect fail "release check catches the broken proguard rule" bash "$CHECK" SignalDF --release
cp "$BACKUP" "$RULES"
expect pass "release check passes once the rule is removed" bash "$CHECK" SignalDF --release
expect fail "unknown flag is refused" bash "$CHECK" SignalDF --nonsense
exit "$fail"
