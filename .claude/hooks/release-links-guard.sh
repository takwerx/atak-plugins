#!/bin/bash
# release-links-guard: PreToolUse hook for Bash. Before a plugin's tree is
# subtree-pushed to its public repo's main, or a GitHub Release is created
# there, run scripts/check-download-links.sh for that plugin and block on
# failure. Map Depot 1.6 went out with every README download link aimed at a
# release that did not exist; git-guard only scopes to the monorepo, so the
# subtree push that published those links was never checked.
#
# The plugin is found by its README's "All releases:" line, which names the
# public repo. A push to a repo no README claims is not a plugin release and
# is left alone.
RL_INPUT="$(cat)" RL_ROOT="${CLAUDE_PROJECT_DIR:-$PWD}" exec python3 <<'PYEOF'
import glob, json, os, re, subprocess, sys

root = os.environ.get("RL_ROOT") or os.getcwd()
try:
    data = json.loads(os.environ.get("RL_INPUT") or "{}")
except Exception:
    sys.exit(0)
cmd = (data.get("tool_input") or {}).get("command", "") or ""

repo = None
m = re.search(r'\bgit\b[^|;&]*\bpush\b[^|;&]*github\.com[/:]takwerx/([A-Za-z0-9._-]+?)(?:\.git)?\s+\S*:(?:refs/heads/)?main\b', cmd)
if m:
    repo = m.group(1)
m2 = re.search(r'\bgh\s+release\s+create\b[^|;&]*--repo\s+takwerx/([A-Za-z0-9._-]+)', cmd)
if m2:
    repo = m2.group(1)
if not repo or repo == "atak-plugins":
    sys.exit(0)

plugin = None
for readme in glob.glob(os.path.join(root, "plugins", "*", "README.md")):
    try:
        text = open(readme, encoding="utf-8", errors="replace").read()
    except OSError:
        continue
    if re.search(r'^All releases: https://github\.com/takwerx/' + re.escape(repo) + r'/releases\s*$', text, re.M):
        plugin = os.path.basename(os.path.dirname(readme))
        break
if not plugin:
    sys.exit(0)

check = os.path.join(root, "scripts", "check-download-links.sh")
if not os.path.exists(check):
    sys.stderr.write("release-links-guard: scripts/check-download-links.sh missing; refusing to publish a plugin release without it.\n")
    sys.exit(2)
r = subprocess.run(["bash", check, plugin], capture_output=True, text=True, timeout=60)
if r.returncode != 0:
    sys.stderr.write("BLOCKED by release-links-guard: the download links at the top of plugins/"
                     + plugin + "/README.md and docs/USER_GUIDE.md do not match PLUGIN_VERSION. "
                     "Users would get 404s. Fix the download block and commit it before this push; "
                     "do not work around this.\n\n" + r.stdout + r.stderr)
    sys.exit(2)
sys.exit(0)
PYEOF
