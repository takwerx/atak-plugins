#!/usr/bin/env bash
# test-ci-static-check: ci-static-check.sh must pass a healthy plugin and fail each
# thing it promises to catch (ops #692). Uses PLSS as the healthy tree, copied to a
# scratch folder so nothing in the repo changes. No SDK, no network, about 10 seconds.
#
#   scripts/test-ci-static-check.sh
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$ROOT/scripts/ci-static-check.sh"
export PUBLISH_SCRUB="$ROOT/scripts/publish-scrub.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0

fresh() { rm -rf "$T/p"; mkdir -p "$T/p"; git -C "$ROOT" archive HEAD plugins/PLSS | tar -x -C "$T/p" --strip-components=2; }
expect() { # expect <pass|fail> <label> [text the failure must contain]
  if bash "$CHECK" "$T/p" >"$T/out" 2>&1; then got=pass; else got=fail; fi
  if [ "$got" = fail ] && [ -n "${3:-}" ] && ! grep -qF -- "$3" "$T/out"; then got="fail, but not for '$3'"; fi
  if [ "$got" = "$1" ]; then echo "ok   $2 ($got)"; else echo "FAIL $2: wanted $1, got $got"; cat "$T/out"; fail=1; fi
}

fresh;                                                                        expect pass "an unchanged plugin passes"
fresh; sed -i.bak 's/enable = false/enable = true/' "$T/p/app/build.gradle";  expect fail "storeArchive turned on" "storeArchive"
fresh; sed -i.bak '/defaultConfig.versionCode/d' "$T/p/app/build.gradle";     expect fail "versionCode no longer PLUGIN_VERSION_CODE" "PLUGIN_VERSION_CODE"
fresh; sed -i.bak 's/ext.PLUGIN_VERSION = "[^"]*"/ext.PLUGIN_VERSION = "v-one"/' "$T/p/app/build.gradle"; expect fail "PLUGIN_VERSION malformed" "PLUGIN_VERSION 'v-one'"
fresh; sed -i.bak '/useLegacyPackaging/d' "$T/p/app/build.gradle";            expect fail "legacy native-library packaging removed" "useLegacyPackaging"
fresh; sed -i.bak 's/com.atakmap.app.component/com.atakmap.app.other/g' "$T/p/app/src/main/AndroidManifest.xml"; expect fail "ATAK discovery activity removed" "com.atakmap.app.component"
fresh; sed -i.bak '/^-keep.*plss/d' "$T/p/app/proguard-gradle.txt";           expect fail "the plugin's own proguard keep rule removed" "no -keep rule"
fresh; : > "$T/p/app/libs-main.jar"; mkdir -p "$T/p/libs"; : > "$T/p/libs/main.jar"; expect fail "an SDK jar added" "SDK binaries"
fresh; printf 'sdk.dir=%s/jane/Library/Android/sdk\n' "/Users" > "$T/p/docs/notes.txt"; expect fail "a machine-local path added (publish scrub)" "publish scrub failed"
exit "$fail"
