#!/usr/bin/env bash
# Source this to get the ATAK plugin build environment in the current shell:
#   source scripts/env.sh
#
# Everything here is machine-local. Nothing in this file is a secret.

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# The ATAK CIV SDK lives outside every repo — its license forbids redistribution.
# Point this at whichever SDK version you are targeting.
export ATAK_SDK="${ATAK_SDK:-$HOME/atak-sdk/ATAK-CIV-5.6.0.8}"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

if [ ! -d "$ATAK_SDK" ]; then
    echo "warning: ATAK_SDK not found at $ATAK_SDK — download the SDK from tak.gov" >&2
fi
