#!/usr/bin/env bash
# Hammers the shipped JNI bridge (app/src/main/cpp/firecast_jni.cpp, unchanged)
# with address and undefined-behavior sanitizers and a 10 s hang watchdog:
#   real  - every fuel model with real-world inputs must compute, and every input
#           at its limit at once must finish
#   fuzz  - garbage (NaN, infinity, 1e308, negatives, bad fuel codes) must be
#           refused or answered, never crash or hang
# Run before any submission that touches the bridge or bumps behave.
set -euo pipefail
cd "$(dirname "$0")"
C=../../app/src/main/cpp
OUT=$(mktemp -d)
for t in real fuzz; do
    clang++ -std=c++14 -O1 -g -fsanitize=address,undefined -fno-sanitize-recover=undefined \
        -fexceptions -frtti -Iinc -I$C/behave $t.cpp $C/firecast_jni.cpp $C/behave/*.cpp -o "$OUT/$t"
done
"$OUT/real"
"$OUT/fuzz" "${1:-1000000}" 2>&1 | tail -1
