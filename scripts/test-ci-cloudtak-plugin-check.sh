#!/usr/bin/env bash
# test-ci-cloudtak-plugin-check: the verdicts of ci-cloudtak-plugin-check.py, with no box (ops #692).
#
#   scripts/test-ci-cloudtak-plugin-check.sh
#
# The served-app fixture "broken" is what a fresh 10.3.12 box really served after a Marketplace install ended
# `complete` (ops #687, Thu Nov 12, 2026): CloudTAK's plugin glob found only the built-in example. The job
# fixture with the terminal error is the text TAK Portal's job really failed with on 10.3.12.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
C="$ROOT/scripts/ci-cloudtak-plugin-check.py"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0
expect() { # expect <exit code> <label> <must appear in output> -- args...
  local want="$1" label="$2" text="$3"; shift 4
  python3 -I "$C" "$@" >"$T/out" 2>&1; got=$?
  if [ "$got" = "$want" ] && grep -qF -- "$text" "$T/out"; then echo "ok   $label (exit $got)"
  else echo "FAIL $label: wanted exit $want with '$text', got $got"; cat "$T/out"; fail=1; fi
}
echo '{"jobs":[{"kind":"install","pluginId":"print","status":"complete"}]}' > "$T/complete.json"
echo '{"jobs":[{"kind":"install","pluginId":"print","status":"failed","error":"Unable to request a pseudo-terminal"}]}' > "$T/pty.json"
echo '{"jobs":[{"kind":"install","pluginId":"print","status":"running"}]}' > "$T/running.json"
echo '{"jobs":[{"kind":"install","pluginId":"search-containment","status":"complete"}]}' > "$T/other.json"
echo '{"jobs":[{"kind":"install","pluginId":"print","status":"complete","error":"rebuild failed"}]}' > "$T/err.json"
echo 'not json' > "$T/bad.json"
expect 0 "a complete job is a pass"                          "PASS" -- --judge-job "$T/complete.json" install print
expect 1 "a terminal error names the key line"               "must allow pty" -- --judge-job "$T/pty.json" install print
expect 1 "a job still running is not a pass"                 "ended running" -- --judge-job "$T/running.json" install print
expect 1 "another plugin's job is no answer for this one"    "no install job for print" -- --judge-job "$T/other.json" install print
expect 1 "complete with an error text is a failure"          "rebuild failed" -- --judge-job "$T/err.json" install print
expect 1 "unreadable status is a failure, not a pass"        "no readable job status" -- --judge-job "$T/bad.json" install print
printf '%s\n' 'var x=Object.assign({"../plugins/example.ts":E});' > "$T/broken.js"
printf '%s\n' 'var x=Object.assign({"../plugins/example.ts":E,"../plugins/print/index.ts":P,"../plugins/search-containment/index.ts":S});' > "$T/good.js"
: > "$T/empty.js"
expect 1 "the Nov 12 app with no plugins is a failure"       "does not load: print" -- --judge-bundle "$T/broken.js" print
expect 0 "an app with the plugin and the others is a pass"   "PASS" -- --judge-bundle "$T/good.js" print search-containment
expect 1 "a rebuild that drops the OTHER plugin fails"       "does not load: tak-dispatcher" -- --judge-bundle "$T/good.js" print tak-dispatcher
expect 1 "a folder-only name is not enough (substring)"      "does not load: pri" -- --judge-bundle "$T/good.js" pri
expect 1 "a map page that did not load is never a pass"      "did not load (no scripts" -- --judge-bundle "$T/empty.js" print
expect 2 "no arguments is a usage error"                     "ci-cloudtak-plugin-check" --
exit "$fail"
