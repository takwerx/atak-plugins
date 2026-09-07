#!/bin/bash
# ship-close-guard: PreToolUse hook for Bash. /ship closes by removing
# .claude/.ship-authorized; the sentinel names the plugin being shipped. Block
# that removal while scripts/check-depot-catalog.sh fails for it, so a ship
# cannot be reported done with the depot catalog still offering the previous
# version. Map Depot 1.6 shipped 2026-09-04 that way; the Market installed 1.4
# on the operator's phone the next day.
SC_INPUT="$(cat)" SC_ROOT="${CLAUDE_PROJECT_DIR:-$PWD}" exec python3 <<'PYEOF'
import json, os, re, subprocess, sys

root = os.environ.get("SC_ROOT") or os.getcwd()
try:
    data = json.loads(os.environ.get("SC_INPUT") or "{}")
except Exception:
    sys.exit(0)
cmd = (data.get("tool_input") or {}).get("command", "") or ""
if not re.search(r'\brm\b[^|;&]*\.ship-authorized\b', cmd):
    sys.exit(0)

sentinel = os.path.join(root, ".claude", ".ship-authorized")
try:
    plugin = open(sentinel, encoding="utf-8").read().strip().split()[0]
except (OSError, IndexError):
    sys.exit(0)          # no sentinel, or an old-style empty one: nothing to check
if not re.fullmatch(r'[A-Za-z0-9]+', plugin):
    sys.exit(0)
if not os.path.isfile(os.path.join(root, "plugins", plugin, "app", "build.gradle")):
    sys.exit(0)

check = os.path.join(root, "scripts", "check-depot-catalog.sh")
out = subprocess.run(["bash", check, plugin], capture_output=True, text=True)
if out.returncode == 0:
    sys.exit(0)
sys.stderr.write(
    "BLOCKED by ship-close-guard: the depot catalog does not offer %s at the version "
    "being shipped, so the TAKwerx Market would install the previous release.\n%s%s"
    "Run: scripts/check-depot-catalog.sh %s --refresh, then re-lock.\n"
    % (plugin, out.stdout, out.stderr, plugin))
sys.exit(2)
PYEOF
