#!/usr/bin/env bash
# Check which ATAK SDK the build scripts pick (ops #802, the move to 5.8.0.10).
# Builds a throwaway ~/atak-sdk with empty ATAK-CIV-<version> folders and a fake
# plugin, so it needs no real SDK. Fails if:
#   - scripts/env.sh does not default to ATAK-CIV-5.8.0.10 when it is unpacked
#   - scripts/env.sh does not keep an ATAK_SDK the caller already set
#   - scripts/local-properties.sh picks 5.8.0.5 over 5.8.0.10 (plain sort would)
#
#   scripts/check-sdk-pick.sh
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FAIL=0
ok()  { echo "  PASS  $*"; }
bad() { echo "  FAIL  $*"; FAIL=1; }

mkdir -p "$TMP/home/atak-sdk" "$TMP/plugin/app" "$TMP/android"
for v in 5.6.0.8 5.6.0.23 5.7.0.14 5.8.0.3 5.8.0.5 5.8.0.10; do
    mkdir -p "$TMP/home/atak-sdk/ATAK-CIV-$v"
done
printf "ext.ATAK_VERSION = '5.8.0'\n" > "$TMP/plugin/app/build.gradle"

# env.sh default
got="$(env -i HOME="$TMP/home" PATH="$PATH" ANDROID_HOME="$TMP/android" bash -c "source '$REPO_ROOT/scripts/env.sh' 2>/dev/null; echo \"\$ATAK_SDK\"")"
[ "$got" = "$TMP/home/atak-sdk/ATAK-CIV-5.8.0.10" ] \
    && ok "env.sh defaults to 5.8.0.10 when it is unpacked" \
    || bad "env.sh default is '$got', want ATAK-CIV-5.8.0.10"

# env.sh keeps a caller's choice
got="$(env -i HOME="$TMP/home" PATH="$PATH" ANDROID_HOME="$TMP/android" ATAK_SDK="$TMP/home/atak-sdk/ATAK-CIV-5.7.0.14" bash -c "source '$REPO_ROOT/scripts/env.sh' 2>/dev/null; echo \"\$ATAK_SDK\"")"
[ "$got" = "$TMP/home/atak-sdk/ATAK-CIV-5.7.0.14" ] \
    && ok "env.sh keeps an ATAK_SDK set by the caller" \
    || bad "env.sh changed a caller's ATAK_SDK to '$got'"

# env.sh fallback when 5.8.0.10 is not there
rmdir "$TMP/home/atak-sdk/ATAK-CIV-5.8.0.10"
got="$(env -i HOME="$TMP/home" PATH="$PATH" ANDROID_HOME="$TMP/android" bash -c "source '$REPO_ROOT/scripts/env.sh' 2>/dev/null; echo \"\$ATAK_SDK\"")"
[ "$got" = "$TMP/home/atak-sdk/ATAK-CIV-5.6.0.8" ] \
    && ok "env.sh falls back to 5.6.0.8 while 5.8.0.10 is missing" \
    || bad "env.sh fallback is '$got', want ATAK-CIV-5.6.0.8"
mkdir -p "$TMP/home/atak-sdk/ATAK-CIV-5.8.0.10"

# local-properties.sh picks the newest 5.8.0.x by version, not by text
env -i HOME="$TMP/home" PATH="$PATH" ANDROID_HOME="$TMP/android" \
    bash "$REPO_ROOT/scripts/local-properties.sh" "$TMP/plugin" >/dev/null 2>&1
got="$(sed -n 's/^sdk\.path=//p' "$TMP/plugin/local.properties" 2>/dev/null)"
[ "$got" = "$TMP/home/atak-sdk/ATAK-CIV-5.8.0.10" ] \
    && ok "local-properties.sh picks 5.8.0.10 over 5.8.0.5 for ATAK_VERSION 5.8.0" \
    || bad "local-properties.sh picked '$got', want ATAK-CIV-5.8.0.10"

exit "$FAIL"
