#!/usr/bin/env bash
# test-ci-load-smoke: the log judge of ci-load-smoke.sh, with no emulator (ops #692).
#
#   scripts/test-ci-load-smoke.sh
#
# scripts/test-data/load-clean.log and load-refused.log are real logcat output
# from the tak-dev emulator (ATAK 5.8.0.5 dev build, Thu Nov 12, 2026): the first
# is SignalDF's release build loading, the second is a tak.gov-signed PLSS build
# refused by dev ATAK. The judge must tell them apart, and must never call a log
# with no load line a pass.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
J="$ROOT/scripts/ci-load-smoke.sh"
D="$ROOT/scripts/test-data"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0
expect() { # expect <exit code> <label> <log> <package>
  bash "$J" --judge "$3" "$4" >"$T/out" 2>&1; got=$?
  if [ "$got" = "$1" ]; then echo "ok   $2 (exit $got)"; else echo "FAIL $2: wanted $1, got $got"; cat "$T/out"; fail=1; fi
}
SDF=com.atakmap.android.signaldf.plugin; PLSS=com.atakmap.android.plss.plugin
expect 0 "a clean load is a pass"                         "$D/load-clean.log"   $SDF
expect 1 "ClassNotFoundException is a refusal"            "$D/load-refused.log" $PLSS
expect 3 "the clean log says nothing about another plugin" "$D/load-clean.log"  $PLSS
: > "$T/empty.log"
expect 3 "an empty log is no verdict, never a pass"       "$T/empty.log" $SDF
printf '11-12 07:39:48.031  4496  4496 D AtakPluginRegistry: Loaded plugin: %s, with extension count: 2\n11-12 07:39:49.000  4496  4496 E AndroidRuntime: FATAL EXCEPTION: main\n' $SDF > "$T/crash.log"
expect 1 "a load followed by a crash is a refusal"        "$T/crash.log" $SDF
printf '11-12 07:39:48.031  4496  4496 W AtakPluginRegistry: Plugin %s is incompatible with this ATAK\n' $SDF > "$T/warn.log"
expect 1 "any registry warning is a refusal"              "$T/warn.log" $SDF
exit "$fail"
