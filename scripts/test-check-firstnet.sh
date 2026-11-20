#!/usr/bin/env bash
# Test for scripts/check-firstnet.sh (ops #991).
#
#   ./scripts/test-check-firstnet.sh                 # test the script in this repo
#   ./scripts/test-check-firstnet.sh /path/to.sh     # test another copy (to show it fail on the old one)
#
# The bug: the gate found only "https://host" text. Atmosphere builds nomads.ncep.noaa.gov
# from a Java constant (HOST = "..." and "https://" + HOST), so the gate said PASS for a host
# nobody had probed from FirstNet. This builds a scratch repo with fake plugins and checks that
# a host held in a constant is found, gated, and not confused with a preference key or a
# .local device name. Needs no device and no network.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${1:-$HERE/check-firstnet.sh}"
[ -f "$SCRIPT" ] || { echo "error: no script at $SCRIPT" >&2; exit 2; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/scripts" "$T/notes"
cp "$SCRIPT" "$T/scripts/check-firstnet.sh"
SRC() { echo "$T/plugins/$1/app/src/main/java/x"; }
for p in Lit Const Kt Skip; do mkdir -p "$(SRC $p)"; done

# Literal only (the case the old script handled).
cat > "$(SRC Lit)/A.java" <<'EOF'
class A { static final String U = "https://lit.example.gov/data.json"; }
EOF
# Host held in a constant (the Atmosphere case).
cat > "$(SRC Const)/NomadsWind.java" <<'EOF'
class NomadsWind {
    public static final String HOST = "nomads.example.gov";
    private static final String FILTER = "https://" + HOST + "/cgi-bin/";
}
EOF
# Kotlin constant, camel case and a name that only ends in Host.
cat > "$(SRC Kt)/B.kt" <<'EOF'
object B {
    const val API_HOST: String = "kt-api.example.org"
    val tileHost = "tiles.example.org"
}
EOF
# Not internet hosts: a preference key, a same-network device, a file name.
cat > "$(SRC Skip)/C.java" <<'EOF'
class C {
    private static final String PREF_HOST = "skip.host";
    public static final String DEFAULT_HOST = "box.local";
    static final String HOSTS_FILE = "hosts.json";
}
EOF

TODAY="$(date +%Y-%m-%d)"
printf 'lit.example.gov|ok|%s\n' "$TODAY" > "$T/notes/firstnet-hosts.txt"
run() { NOTES_DIR="$T/notes" bash "$T/scripts/check-firstnet.sh" "$@" 2>&1; }

PASS=0; FAILN=0
ok()  { PASS=$((PASS + 1)); echo "  ok    $1"; }
bad() { FAILN=$((FAILN + 1)); echo "  FAIL  $1"; }
expect_has() { # name, text, needle
    case "$2" in *"$3"*) ok "$1" ;; *) bad "$1 (wanted '$3' in: $(echo "$2" | tr '\n' ' '))" ;; esac
}
expect_lacks() {
    case "$2" in *"$3"*) bad "$1 (did not want '$3' in: $(echo "$2" | tr '\n' ' '))" ;; *) ok "$1" ;; esac
}

# 1. Gate: a host found only in a constant, never probed, must FAIL.
out="$(run Const)"; rc=$?
[ "$rc" = 1 ] && ok "Const: gate exits 1" || bad "Const: gate exits 1 (got $rc: $out)"
expect_has "Const: names the constant host" "$out" "nomads.example.gov: never probed"

# 2. Once probed ok, the same plugin passes.
printf 'nomads.example.gov|ok|%s\n' "$TODAY" >> "$T/notes/firstnet-hosts.txt"
out="$(run Const)"; rc=$?
[ "$rc" = 0 ] && ok "Const: passes once probed" || bad "Const: passes once probed (got $rc: $out)"

# 3. A constant host recorded bad, plugin without fallback: FAIL.
printf 'nomads.example.gov|bad-rejected|%s\n' "$TODAY" >> "$T/notes/firstnet-hosts.txt"
out="$(run Const)"; rc=$?
[ "$rc" = 1 ] && ok "Const: bad host with no fallback fails" || bad "Const: bad host with no fallback fails (got $rc)"
expect_has "Const: says no IPv4 fallback" "$out" "no IPv4 fallback"

# 4. Kotlin constants, both spellings, are found; the literal plugin still works.
out="$(run --list Kt)"
expect_has "Kt: const val API_HOST: String" "$out" "kt-api.example.org"
expect_has "Kt: val tileHost" "$out" "tiles.example.org"
out="$(run --list Lit)"
expect_has "Lit: https literal still found" "$out" "lit.example.gov"

# 5. Preference keys, .local names and file names are not hosts.
out="$(run --list Skip)"
expect_lacks "Skip: preference key" "$out" "skip.host"
expect_lacks "Skip: .local device" "$out" "box.local"
expect_lacks "Skip: file name" "$out" "hosts.json"
out="$(run Skip)"
expect_has "Skip: gate passes with no hosts" "$out" "PASS"

# 6. --all lists the constant hosts too.
out="$(run --list --all)"
expect_has "--all: includes the constant host" "$out" "tiles.example.org"

echo "test-check-firstnet: $PASS passed, $FAILN failed"
[ "$FAILN" = 0 ]
