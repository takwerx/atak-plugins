#!/bin/bash
# git-guard: PreToolUse hook enforcing the CLAUDE.md release hard-stops for the
# PUBLIC repo takwerx/atak-plugins. Blocks merge-into-main, git tag creation,
# pushes to main/tags, and gh release create — unless /ship has created a fresh
# authorization sentinel (.claude/.ship-authorized, valid 30 minutes).
# Ported from infra-TAK; same semantics, different repo.
GG_INPUT="$(cat)" GG_ROOT="${CLAUDE_PROJECT_DIR:-$PWD}" exec python3 <<'PYEOF'
import json, sys, os, re, time, subprocess

root = os.environ.get("GG_ROOT") or os.getcwd()
try:
    data = json.loads(os.environ.get("GG_INPUT") or "{}")
except Exception:
    sys.exit(0)
cmd = (data.get("tool_input") or {}).get("command", "") or ""
if "git" not in cmd and "gh " not in cmd:
    sys.exit(0)

# Scope to the PUBLIC repo. The private notes repo also has a branch named
# main and its pushes are routine (CLAUDE.md says to push them). Fail-closed:
# if the target repo cannot be determined, treat it as the public repo.
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
        url = subprocess.run(
            ["git", "-C", _target_dir(), "remote", "get-url", "origin"],
            capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        return True  # fail closed
    if not url:
        return True  # fail closed
    return bool(re.search(r'takwerx/atak-plugins(\.git)?/?$', url))

if not _is_public_repo():
    sys.exit(0)

# Sentinel created by /ship after the operator answers the ship prompt; valid 30 min.
sentinel = os.path.join(root, ".claude", ".ship-authorized")
try:
    if time.time() - os.path.getmtime(sentinel) < 1800:
        sys.exit(0)
except OSError:
    pass

def block(what):
    sys.stderr.write(
        f"BLOCKED by git-guard: {what}. This is a release hard-stop (CLAUDE.md): "
        "merge-to-main, tagging, pushing main/tags, and gh release create require "
        "explicit operator authorization via the /ship skill, which presents the "
        "ship prompt and unlocks these operations for 30 minutes. Do NOT retry or "
        "work around this; if the operator wants to ship, invoke /ship.\n")
    sys.exit(2)

if re.search(r'\bgh\s+release\s+create\b', cmd):
    block("gh release create")

if re.search(r'\bgit\b[^|;&]*\bpush\b', cmd):
    if re.search(r'--tags\b', cmd):
        block("git push --tags")
    if re.search(r'\bpush\b[^|;&]*[\s:+]main\b', cmd):
        block("git push to main")
    if re.search(r'\bpush\b[^|;&]*\s\+?[A-Za-z]*-?v\d+\.\d+', cmd):
        block("git push of a version tag")

m = re.search(r'\bgit\s+(?:[-\w=.]+\s+)*tag\b\s*(.*)', cmd)
if m:
    rest = m.group(1).strip()
    # bare `git tag` / -l / --list / -n / --contains etc. are read-only
    if rest and not re.match(r'^(-l\b|--list\b|-n\d*\b|--contains\b|--points-at\b|--sort)', rest):
        block("git tag creation/deletion")

if re.search(r'\bgit\b[^|;&]*\bmerge\b', cmd) and not re.search(r'--abort|--continue|--quit', cmd):
    branch = ""
    try:
        branch = subprocess.run(
            ["git", "-C", _target_dir(), "branch", "--show-current"],
            capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        pass
    if branch == "main" or re.search(r'checkout\s+(-B\s+|-q\s+)*main\b|switch\s+main\b', cmd):
        block("git merge while on main")

sys.exit(0)
PYEOF
