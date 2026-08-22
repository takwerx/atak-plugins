#!/bin/bash
# publish-guard: PreToolUse hook for Bash. Before anything that publishes from the
# PUBLIC repo (takwerx/atak-plugins) — git push, gh release, submission-zip — it
# runs scripts/publish-scrub.sh over the tracked tree and blocks on findings.
# The private notes repo is exempt (its pushes are routine). Fail-closed: if the
# target repo cannot be determined, the scrub runs anyway.
PG_INPUT="$(cat)" PG_ROOT="${CLAUDE_PROJECT_DIR:-$PWD}" exec python3 <<'PYEOF'
import json, os, re, subprocess, sys

root = os.environ.get("PG_ROOT") or os.getcwd()
try:
    data = json.loads(os.environ.get("PG_INPUT") or "{}")
except Exception:
    sys.exit(0)
cmd = (data.get("tool_input") or {}).get("command", "") or ""

publishing = (
    re.search(r'\bgit\b[^|;&]*\bpush\b', cmd)
    or re.search(r'\bgh\s+release\s+create\b', cmd)
    or re.search(r'submission-zip\.sh', cmd)
)
if not publishing:
    sys.exit(0)

def _target_dir():
    d = data.get("cwd") or root
    m = re.search(r'(?:^|&&|;)\s*cd\s+([^\s;&|]+)', cmd)
    if m:
        d = os.path.expanduser(m.group(1).strip('\'"'))
    m = re.search(r'\bgit\s+(?:[-\w=.]+\s+)*-C\s+([^\s;&|]+)', cmd)
    if m:
        d = os.path.expanduser(m.group(1).strip('\'"'))
    return d

def _is_public_repo():
    m = re.search(r'\bpush\b[^|;&]*\s(\S*(github\.com|git@)\S*)', cmd)
    if m:
        return bool(re.search(r'takwerx/atak-plugins(\.git)?/?$', m.group(1)))
    try:
        url = subprocess.run(["git", "-C", _target_dir(), "remote", "get-url", "origin"],
                             capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        return True  # fail closed
    if not url:
        return True
    return bool(re.search(r'takwerx/atak-plugins(\.git)?/?$', url))

if not _is_public_repo():
    sys.exit(0)

scrub = os.path.join(root, "scripts", "publish-scrub.sh")
if not os.path.exists(scrub):
    sys.stderr.write("publish-guard: scripts/publish-scrub.sh missing — refusing to publish without the scrub.\n")
    sys.exit(2)
r = subprocess.run(["bash", scrub], capture_output=True, text=True, timeout=120)
if r.returncode != 0:
    sys.stderr.write("BLOCKED by publish-guard: the publish scrub found material that must not be "
                     "published from the public repo. Fix it or move it to the private notes repo; "
                     "do not work around this.\n\n" + r.stdout + r.stderr)
    sys.exit(2)
sys.exit(0)
PYEOF
