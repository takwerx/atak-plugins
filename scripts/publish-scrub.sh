#!/usr/bin/env bash
# publish-scrub: the last look before anything leaves this machine for a public
# place — a push to the public repo, a tak.gov submission zip, a published guide
# or artifact. Greps for what must never be published: personal identifiers,
# credentials, machine-local paths, device identifiers, SDK binaries and
# signing material. Exit 0 = PASS, exit 1 = FAIL with a findings list.
#
#   scripts/publish-scrub.sh                 # tracked + untracked (non-ignored) files in this repo
#   scripts/publish-scrub.sh <dir>           # every text file under a directory (e.g. an extracted zip)
#   scripts/publish-scrub.sh --file <path>   # one file (e.g. an artifact HTML before publishing)
#
# Sensitive literals that must not appear anywhere — the operator's own
# addresses, device serials, anything specific — cannot live in a public
# denylist, so they are read from the PRIVATE notes repo if present:
#   ../atak-plugins-notes/publish-scrub.denylist   (one literal per line, # comments)
#
# Allowlist (false positives that are genuinely public and fine):
#   - the SDK's shared dev keystore password/alias (tnttnt / wintec_mapping): in
#     every SDK download, documented in CLAUDE.md as not a secret
#   - Co-Authored-By noreply@anthropic.com, example.com, schema/xmlns URLs
#   - 0.0.0.0 / 127.0.0.1 / localhost
#
# What it cannot do: read a screenshot. Pictures must be eyeballed for callsigns,
# coordinates, names, faces, plates, server addresses before they are committed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DENYLIST="$REPO_ROOT/../atak-plugins-notes/publish-scrub.denylist"

MODE="repo"; TARGET=""
case "${1:-}" in
    "") ;;
    --file) MODE="file"; TARGET="${2:-}"; [ -n "$TARGET" ] || { echo "usage: $0 --file <path>" >&2; exit 2; } ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) MODE="dir"; TARGET="$1" ;;
esac

# ---- file list ------------------------------------------------------------
list_files() {
    case "$MODE" in
        # tracked AND untracked-but-not-ignored: what a push publishes, plus what
        # the next commit would add. (A new file checked before `git add` once
        # slipped through on tracked-only.)
        repo) git -C "$REPO_ROOT" ls-files -z --cached --others --exclude-standard | tr '\0' '\n' | sed "s|^|$REPO_ROOT/|" ;;
        dir)  find "$TARGET" -type f -not -path '*/.git/*' ;;
        file) echo "$TARGET" ;;
    esac
}

is_text() {
    # skip images, archives, jars, fonts, databases — grep -I handles most, this is cheaper
    case "$1" in
        *.png|*.jpg|*.jpeg|*.gif|*.webp|*.svg|*.ico|*.zip|*.jar|*.apk|*.aab|*.sqlite|*.db|*.ttf|*.otf|*.woff|*.woff2|*.pdf|*.so) return 1 ;;
    esac
    return 0
}

FAIL=0
finding() { # category, line
    [ $FAIL -eq 0 ] && echo "FINDINGS:"
    FAIL=1
    printf '  [%s] %s\n' "$1" "$2"
}

# ---- 1. forbidden files (by name/extension) --------------------------------
while IFS= read -r f; do
    base="$(basename "$f")"
    rel="${f#$REPO_ROOT/}"
    case "$base" in
        main.jar|atak.apk|atak-javadoc.jar|atak-gradle-takdev.jar|android_keystore|local.properties|keystore.properties)
            finding "forbidden-file" "$rel" ;;
        *.jks|*.keystore|*.p12|*.pem|*.key|*.apk|*.aab|*.sqlite)
            finding "forbidden-file" "$rel" ;;
    esac
    case "$rel" in
        *.takdev/*|*/app/libs/*|*/build/*) finding "forbidden-path" "$rel" ;;
    esac
done < <(list_files)

# ---- 2. content patterns ----------------------------------------------------
# Each pattern is an extended regex; ALLOW is applied to matching lines.
ALLOW='noreply@anthropic\.com|example\.com|schemas\.android\.com|w3\.org|0\.0\.0\.0|127\.0\.0\.1|localhost|tnttnt|wintec_mapping|takrepo\.(user|password)|takrepoUser|takrepoPassword|storePassword|keyPassword'

scan() { # category, regex
    local cat="$1" re="$2"
    while IFS= read -r f; do
        is_text "$f" || continue
        [ -f "$f" ] || continue
        grep -nHIE "$re" "$f" 2>/dev/null | grep -vE "$ALLOW" | while IFS= read -r line; do
            finding "$cat" "${line#$REPO_ROOT/}"
        done
    done < <(list_files)
}

# subshell-safe: collect findings through a temp file since scan() runs in pipes
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
finding() { printf '  [%s] %s\n' "$1" "$2" >> "$TMP"; }

scan "email"        '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
scan "phone"        '(^|[^0-9A-Za-z])\(?[0-9]{3}\)?[-. ][0-9]{3}[-. ][0-9]{4}([^0-9]|$)'
scan "home-path"    '(/Users/[A-Za-z]|/home/[a-z][a-z0-9_-]*/|C:\\Users\\)'
# IPv4 that looks like a real address rather than a 4-part version: private
# ranges, or any octet >= 100. (ATAK versions like 5.8.0.3 do not trip this.)
scan "ip-address"   '(^|[^0-9.])(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|([0-9]{1,3}\.){3}(1[0-9]{2}|2[0-5][0-9])|(1[0-9]{2}|2[0-5][0-9])(\.[0-9]{1,3}){3})([^0-9.]|$)'
scan "secret"       '(password|passwd|secret|api[_-]?key|access[_-]?key|auth[_-]?token|bearer)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{4,}["'"'"']'
scan "secret"       'BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY'
scan "secret"       '(AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{20,}|xox[bpa]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9]{32,})'
scan "takrepo-cred" '^[[:space:]]*takrepo\.(user|password)[[:space:]]*=[[:space:]]*[^[:space:]#]'

# ---- 3. private denylist (literals from the notes repo) ---------------------
if [ -f "$DENYLIST" ]; then
    while IFS= read -r lit; do
        lit="${lit%%#*}"; lit="$(echo "$lit" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
        [ -n "$lit" ] || continue
        while IFS= read -r f; do
            is_text "$f" || continue
            [ -f "$f" ] || continue
            grep -nHIF -- "$lit" "$f" 2>/dev/null | while IFS= read -r line; do
                finding "denylist" "${line#$REPO_ROOT/}"
            done
        done < <(list_files)
    done < "$DENYLIST"
else
    echo "  note: no private denylist at $DENYLIST (operator literals not checked)"
fi

# ---- report -----------------------------------------------------------------
if [ -s "$TMP" ]; then
    echo "publish-scrub: FAIL ($MODE${TARGET:+ $TARGET})"
    echo "FINDINGS:"
    sort -u "$TMP"
    echo
    echo "Fix or move to the private notes repo, then re-run. Nothing ships with findings."
    exit 1
fi
echo "publish-scrub: PASS ($MODE${TARGET:+ $TARGET}) — no PII, credentials, machine paths, device ids or forbidden files"
exit 0
