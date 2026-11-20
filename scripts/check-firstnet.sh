#!/usr/bin/env bash
# Does every server a plugin calls work from FirstNet?
#
#   ./scripts/check-firstnet.sh <Plugin>             # gate, no device needed
#   ./scripts/check-firstnet.sh --probe <Plugin>     # test its hosts from a FirstNet device
#   ./scripts/check-firstnet.sh --probe --all        # every plugin's hosts
#   ./scripts/check-firstnet.sh --list <Plugin|--all> # print the hosts it finds, nothing else
#
# Why (2026-11-17/18): FirstNet LTE gives the device an IPv6 address, and Android
# tries IPv6 first. The Census Bureau's firewall answers FirstNet IPv6 with a
# "Request Rejected" page (HTTP 200, HTML), and EPA's geodata takes no IPv6
# connection at all; both answer over IPv4. Sit Rep showed "no census data" in
# Arlington VA on a crew's Note 20. From the Studio every one of those hosts works,
# so no desk test can find this.
#
# --probe runs curl ON THE DEVICE (adb; honor ADB_SERVER_SOCKET / ANDROID_SERIAL),
# each host over -6 and -4, and records the result in the notes repo,
# ../atak-plugins-notes/firstnet-hosts.txt:   host|ok|bad-rejected|bad-silent  date
# The device must be on FirstNet (its SIM, or a FirstNet phone's hotspot: the
# hotspot egress is rejected the same way).
#
# The gate (no device) FAILS when the plugin calls:
#   - a host with no probe result in the last 120 days, or
#   - a host recorded bad while the plugin's source carries no IPv4 fallback.
# The fallback is Sit Rep's net/Http.java (copy forward): the "Request Rejected"
# page retried over IPv4, and ipv6Dead() for a silent IPv6 address.
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NOTES="${NOTES_DIR:-$REPO_ROOT/../atak-plugins-notes}"
[ -d "$NOTES" ] || NOTES="$HOME/GitHub/atak-plugins-notes"
DB="$NOTES/firstnet-hosts.txt"
MAX_AGE_DAYS=120

PROBE=0; ALL=0; LIST=0; NAME=""
for a in "$@"; do
    case "$a" in
        --probe) PROBE=1 ;;
        --all) ALL=1 ;;
        --list) LIST=1 ;;
        -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
        *) NAME="$a" ;;
    esac
done
[ -n "$NAME" ] || [ "$ALL" = 1 ] || { echo "usage: $0 [--probe] <Plugin> | --probe --all" >&2; exit 2; }

# The hosts a plugin's shipped code and assets name. Two sources:
#  1. an "https://host" literal anywhere under app/src/main (same extraction as
#     the 2026-11-18 fleet probe);
#  2. a Java/Kotlin constant that holds a bare host name and is joined to the
#     scheme in code, e.g. HOST = "nomads.ncep.noaa.gov" with "https://" + HOST
#     (ops #991: Atmosphere's NomadsWind.HOST was never probed, because no
#     "https://nomads..." text exists). The constant's name has "host" or "domain"
#     in it, any case; preference keys ("signaldf.host"), file names ("hosts.json") and
#     the .local names of a device on the same network are not internet hosts and are skipped.
# Schema and doc links are not fetched.
hosts_of() {
    {
        grep -rhoE 'https://[A-Za-z0-9.-]+\.[a-z]{2,}' "$1/app/src/main" 2>/dev/null | sed 's#https://##'
        grep -rhoiE --include='*.java' --include='*.kt' \
            '[A-Za-z_]*(host|domain)[A-Za-z_]*[[:space:]]*(:[[:space:]]*String[[:space:]]*)?=[[:space:]]*"[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}"' \
            "$1/app/src/main" 2>/dev/null \
            | grep -viE '(^|[^A-Za-z])(pref|key|action|extra|tag|file|path|dir)[A-Za-z_]*[[:space:]]*(:|=)' \
            | sed -E 's/.*"([^"]+)"$/\1/' \
            | grep -viE '\.(json|xml|txt|csv|png|jpe?g|html?|js|css|zip|apk|db|gz|kml|kmz|geojson|properties|java|kt)$'
    } | tr 'A-Z' 'a-z' | sort -u \
        | grep -vE '\.local$|(^|\.)(w3\.org|schemas\.android\.com|android\.com|doi\.org|play\.google\.com)$|^github\.com$|^www\.apache\.org$|^opensource\.org$' || true
}

if [ "$ALL" = 1 ]; then
    DIRS="$(ls -d "$REPO_ROOT"/plugins/*/)"
else
    DIRS="$REPO_ROOT/plugins/$NAME/"
    [ -d "$DIRS" ] || { echo "error: no plugin at $DIRS" >&2; exit 2; }
fi
HOSTS="$(for d in $DIRS; do hosts_of "$d"; done | sort -u)"
if [ "$LIST" = 1 ]; then echo "$HOSTS"; exit 0; fi
[ -n "$HOSTS" ] || { echo "check-firstnet: PASS (${NAME:-all}: calls no https host)"; exit 0; }

if [ "$PROBE" = 1 ]; then
    command -v adb >/dev/null || { echo "error: adb not found" >&2; exit 2; }
    EGRESS="$(adb shell 'curl -6 -s -m 10 https://api64.ipify.org' 2>/dev/null | tr -d '\r')"
    case "$EGRESS" in
        *:*) echo "==> device IPv6 egress $EGRESS (FirstNet/AT&T is 2600:38x:...)" ;;
        *) echo "error: the device has no IPv6 egress; put it on FirstNet LTE or a FirstNet hotspot" >&2; exit 2 ;;
    esac
    case "$EGRESS" in 2600:38*) ;; *) echo "  WARN  egress is not in AT&T's 2600:380::/27 block; is this FirstNet?" ;; esac
    TODAY="$(date +%Y-%m-%d)"
    touch "$DB"
    BAD=0
    for h in $HOSTS; do
        # On the device: v6 status, curl exit, rejected page?, then v4.
        r="$(adb shell "c6=\$(curl -6 -s -m 20 -o /data/local/tmp/fn6 -w '%{http_code}' https://$h/); e6=\$?; \
            j=0; grep -q 'Request Rejected' /data/local/tmp/fn6 2>/dev/null && j=1; \
            c4=\$(curl -4 -s -m 20 -o /dev/null -w '%{http_code}' https://$h/); e4=\$?; \
            rm -f /data/local/tmp/fn6; echo \"\$c6 \$e6 \$j \$c4 \$e4\"" 2>/dev/null | tr -d '\r')"
        set -- $r
        c6="${1:-000}" e6="${2:-99}" j="${3:-0}" c4="${4:-000}" e4="${5:-99}"
        if [ "$e6" = 6 ]; then
            verdict=ok; why="no IPv6 address"
        elif [ "$j" = 1 ] && [ "$e4" = 0 ]; then
            verdict=bad-rejected; why="IPv6 'Request Rejected', IPv4 $c4"
        elif [ "$e6" != 0 ] && [ "$e4" = 0 ]; then
            verdict=bad-silent; why="IPv6 curl exit $e6, IPv4 $c4"
        else
            verdict=ok; why="IPv6 $c6, IPv4 $c4"
        fi
        [ "$verdict" = ok ] && echo "  ok    $h ($why)" || { echo "  BAD   $h ($why)"; BAD=$((BAD + 1)); }
        grep -v "^$h|" "$DB" > "$DB.tmp" || true
        echo "$h|$verdict|$TODAY" >> "$DB.tmp"
        sort "$DB.tmp" > "$DB"; rm -f "$DB.tmp"
    done
    echo "==> recorded in $DB ($BAD bad). Commit it in the notes repo by name."
    [ "$ALL" = 1 ] && exit 0
fi

# The gate.
FALLBACK=0
for d in $DIRS; do
    if grep -rqs "Request Rejected" "$d/app/src/main/java" && grep -rqs "ipv6Dead" "$d/app/src/main/java"; then
        FALLBACK=1
    fi
done
NOW="$(date +%s)"
FAIL=0
for h in $HOSTS; do
    line="$(grep "^$h|" "$DB" 2>/dev/null | tail -1)"
    if [ -z "$line" ]; then
        echo "  FAIL  $h: never probed from FirstNet (run --probe on a FirstNet device)"; FAIL=1; continue
    fi
    verdict="$(echo "$line" | cut -d'|' -f2)"; day="$(echo "$line" | cut -d'|' -f3)"
    then_s="$(date -j -f %Y-%m-%d "$day" +%s 2>/dev/null || date -d "$day" +%s 2>/dev/null || echo 0)"
    age=$(( (NOW - then_s) / 86400 ))
    if [ "$age" -gt "$MAX_AGE_DAYS" ]; then
        echo "  FAIL  $h: probed $day, older than $MAX_AGE_DAYS days (run --probe again)"; FAIL=1; continue
    fi
    if [ "$verdict" != ok ]; then
        if [ "$FALLBACK" = 1 ]; then
            echo "  ok    $h: $verdict on FirstNet, handled by the IPv4 fallback"
        else
            echo "  FAIL  $h: $verdict on FirstNet and the plugin has no IPv4 fallback (copy Sit Rep's net/Http.java)"; FAIL=1
        fi
    fi
done
if [ "$FAIL" = 0 ]; then
    echo "check-firstnet: PASS (${NAME:-all}: $(echo "$HOSTS" | wc -l | tr -d ' ') hosts, all work on FirstNet or are handled)"
    exit 0
fi
echo "check-firstnet: FAIL (${NAME:-all})"
exit 1
