#!/usr/bin/env bash
# ci-static-check: what can be checked about a plugin with NO ATAK SDK and no
# network (ops #692). The SDK is not ours to publish, so a plugin's public repo
# cannot build on GitHub's runners, which leaves a pull request there (a reporter
# or a worker) merged on a look. This reads the files that decide whether the
# plugin builds and loads, and the files that must never be public:
#
#   scripts/ci-static-check.sh [<plugin dir>]       # default: the current directory
#
# In the monorepo the plugin dir is plugins/<Name>; in a plugin's public repo it
# is the repo root. It checks:
#   1. app/build.gradle keeps the load-bearing settings from CLAUDE.md "Build-file
#      invariants": PLUGIN_VERSION and ATAK_VERSION well formed, versionCode from
#      PLUGIN_VERSION_CODE, storeArchive off, legacy native-lib packaging, the
#      namespace;
#   2. AndroidManifest.xml keeps the com.atakmap.app.component activity (without
#      it ATAK cannot see the plugin) and the plugin-api meta-data;
#   3. proguard-gradle.txt has a keep rule for this plugin's own classes (tak.gov
#      requires one, and a release build without it loses the plugin's classes);
#   4. no SDK binary or signing material is in the tree (HARD RULE 1);
#   5. the publish scrub passes on the tree, when scripts/publish-scrub.sh is
#      beside this script (set PUBLISH_SCRUB=/path to point at a copy).
# It never builds anything, so it cannot say the plugin compiles; the release
# check (ci-plugin-check.sh --release) and the load smoke do that.
#
# Exit 0 PASS, 1 findings, 2 usage error.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIR="${1:-.}"
[ $# -le 1 ] && [ -f "$DIR/app/build.gradle" ] || { echo "usage: $0 [<plugin dir>]   (needs <dir>/app/build.gradle)" >&2; exit 2; }
DIR="$(cd "$DIR" && pwd)"
G="$DIR/app/build.gradle"; M="$DIR/app/src/main/AndroidManifest.xml"; P="$DIR/app/proguard-gradle.txt"
fail=0
finding() { echo "  FAIL: $*"; fail=1; }

# 1. build.gradle
ver="$(sed -nE 's/^[[:space:]]*ext\.PLUGIN_VERSION[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p' "$G" | head -1)"
atak="$(sed -nE "s/^[[:space:]]*ext\.ATAK_VERSION[[:space:]]*=[[:space:]]*['\"]([^'\"]*)['\"].*/\1/p" "$G" | head -1)"
ns="$(sed -nE "s/^[[:space:]]*namespace[[:space:]]+'([^']+)'.*/\1/p" "$G" | head -1)"
printf '%s' "$ver"  | grep -qE '^[0-9]+\.[0-9]+(\.[0-9]+)?$' || finding "PLUGIN_VERSION '$ver' is not like 1.2 or 1.2.3"
printf '%s' "$atak" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'    || finding "ATAK_VERSION '$atak' is not like 5.8.0"
printf '%s' "$ns"   | grep -qE '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$' || finding "namespace '$ns' is missing or not a package name"
grep -qE 'defaultConfig\.versionCode[[:space:]]*=[[:space:]]*PLUGIN_VERSION_CODE' "$G" \
  || finding "versionCode is not PLUGIN_VERSION_CODE (the SDK's git-derived code is 1 at tak.gov; Watchtower cannot push an update over it)"
awk '/storeArchive[[:space:]]*\{/ {f=1} f && /enable[[:space:]]*=[[:space:]]*false/ {ok=1} END {exit !ok}' "$G" \
  || finding "bundle { storeArchive { enable = false } } is missing (TAK's inner signing check fails without it)"
grep -qE 'useLegacyPackaging[[:space:]]*(=[[:space:]]*)?true' "$G" || finding "jniLibs.useLegacyPackaging true is missing (native libraries fail to load)"

# 2. manifest
if [ -f "$M" ]; then
  grep -q 'android:name="com.atakmap.app.component"' "$M" || finding "AndroidManifest.xml has no com.atakmap.app.component activity: ATAK will not see the plugin"
  grep -q 'android:name="plugin-api"' "$M"                 || finding "AndroidManifest.xml has no plugin-api meta-data: ATAK will not load the plugin"
else
  finding "app/src/main/AndroidManifest.xml is missing"
fi

# 3. proguard: the plugin's own classes, by its package root (namespace without the .plugin tail)
if [ -f "$P" ]; then
  root="${ns%.plugin}"
  grep -vE '^[[:space:]]*#' "$P" | grep -E '^-keep' | grep -qF "$root" \
    || finding "proguard-gradle.txt has no -keep rule for $root (a release build can strip the plugin's own classes)"
else
  finding "app/proguard-gradle.txt is missing"
fi

# 4. SDK artifacts and signing material (the same list as the sdk-guard job in pr-checks.yml)
bad="$(cd "$DIR" && find . -path ./.git -prune -o -type f \( -name main.jar -o -name atak.apk -o -name atak-javadoc.jar -o -name atak-gradle-takdev.jar \
        -o -name android_keystore -o -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name keystore.properties -o -name local.properties \) -print | sed 's#^\./##')"
if [ -n "$bad" ]; then
  # A gitignored local.properties on a dev machine is fine; judge only what git would publish.
  if git -C "$DIR" rev-parse --git-dir >/dev/null 2>&1; then
    tracked="$(cd "$DIR" && printf '%s\n' "$bad" | while IFS= read -r f; do git ls-files --error-unmatch -- "$f" >/dev/null 2>&1 && echo "$f"; done)"
    bad="$tracked"
  fi
  [ -z "$bad" ] || finding "SDK binaries or signing material in the tree (the SDK license forbids publishing them): $(echo "$bad" | tr '\n' ' ')"
fi

# 5. publish scrub
SCRUB="${PUBLISH_SCRUB:-$HERE/publish-scrub.sh}"
if [ -f "$SCRUB" ]; then
  # Scrub what git would publish (tracked plus new, not ignored), never build output or a
  # dev machine's local.properties. Outside a git tree the whole directory is scrubbed.
  TARGET="$DIR"; TMP=""
  if git -C "$DIR" rev-parse --git-dir >/dev/null 2>&1; then
    TMP="$(mktemp -d)"; TARGET="$TMP"
    (cd "$DIR" && git ls-files -z -co --exclude-standard | tar --null -T - -cf - 2>/dev/null) | tar -xf - -C "$TMP" 2>/dev/null
  fi
  out="$(bash "$SCRUB" "$TARGET" 2>&1)" || { finding "publish scrub failed:"; echo "$out" | tail -15 | sed 's/^/        /'; }
  [ -z "$TMP" ] || rm -rf "$TMP"
else
  echo "  note: publish-scrub.sh not found ($SCRUB); the scrub was not run"
fi

if [ "$fail" = 0 ]; then
  echo "ci-static-check: PASS ($(basename "$DIR") ${ver:-?} for ATAK ${atak:-?})"
  exit 0
fi
echo "ci-static-check: FAIL ($(basename "$DIR"))" >&2
exit 1
