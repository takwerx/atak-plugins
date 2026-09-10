#!/bin/bash
# new-plugin-guard: PreToolUse hook keeping plugin work out of the MAIN checkout.
#
# ~/GitHub/atak-plugins is nobody's workspace: it stays on main so /ship can
# merge there and refresh_depot.py can read there. Every plugin is edited in
# its own worktree, ~/GitHub/atak-plugins-<plugin> (CLAUDE.md, "Working in
# parallel"). The rule was prose, and prose lost: a session that starts talking
# about a plugin in the main checkout keeps editing there, which is exactly what
# breaks two plugins being built at once.
#
# Blocks, in the main checkout only:
#   - Write/Edit to anything under plugins/
#   - scripts/new-plugin.sh (a new plugin is scaffolded inside its worktree)
# Every other checkout is untouched, and `worktree.sh new` is never blocked, so
# there is always a way forward and never a reason to work around this.
NPG_INPUT="$(cat)" NPG_ROOT="${CLAUDE_PROJECT_DIR:-$PWD}" exec python3 <<'PYEOF'
import json, os, re, sys, subprocess

root = os.environ.get("NPG_ROOT") or os.getcwd()
try:
    data = json.loads(os.environ.get("NPG_INPUT") or "{}")
except Exception:
    sys.exit(0)

tool = data.get("tool_name") or ""
ti = data.get("tool_input") or {}
cwd = data.get("cwd") or root


def main_checkout():
    """Absolute path of the repository's MAIN worktree, or None if not this repo."""
    for d in (cwd, root):
        try:
            out = subprocess.run(["git", "-C", d, "worktree", "list", "--porcelain"],
                                 capture_output=True, text=True, timeout=5).stdout
        except Exception:
            continue
        m = re.match(r"worktree (.+)", out)
        if not m:
            continue
        wt = os.path.realpath(m.group(1).strip())
        # Only this repo. The notes repo and everything else are none of our business.
        try:
            url = subprocess.run(["git", "-C", d, "remote", "get-url", "origin"],
                                 capture_output=True, text=True, timeout=5).stdout.strip()
        except Exception:
            url = ""
        if re.search(r"takwerx/atak-plugins(\.git)?/?$", url):
            return wt
    return None


MAIN = main_checkout()
if not MAIN:
    sys.exit(0)


def block(what, fix):
    sys.stderr.write(
        "BLOCKED by new-plugin-guard: %s in the MAIN checkout (%s).\n"
        "The main checkout stays on main and is nobody's workspace; every plugin is "
        "edited in its own worktree (CLAUDE.md, \"Working in parallel\"). %s\n"
        "Do NOT work around this by writing the file another way.\n" % (what, MAIN, fix))
    sys.exit(2)


def expand(p):
    p = p.strip("'\"")
    for var, val in (("CLAUDE_PROJECT_DIR", root), ("HOME", os.path.expanduser("~")),
                     ("PWD", cwd)):
        p = p.replace("${%s}" % var, val).replace("$%s" % var, val)
    return os.path.realpath(os.path.join(cwd, os.path.expanduser(p)))


plugins_dir = os.path.join(MAIN, "plugins") + os.sep

if tool in ("Write", "Edit", "NotebookEdit"):
    path = ti.get("file_path") or ti.get("notebook_path") or ""
    if path and expand(path).startswith(plugins_dir):
        plugin = expand(path)[len(plugins_dir):].split(os.sep)[0]
        block("editing plugins/%s" % plugin,
              "Work in ~/GitHub/atak-plugins-%s (scripts/worktree.sh list shows it; "
              "scripts/worktree.sh new %s %s-v<next> makes it)."
              % (plugin.lower(), plugin, plugin.lower()))
    sys.exit(0)

if tool != "Bash":
    sys.exit(0)

cmd = ti.get("command", "") or ""

# Heredoc bodies are data, not commands: a commit message or a PLAN quoting a
# path must not trip this. Same treatment as git-guard.
def strip_heredocs(text):
    out, lines, i = [], text.split("\n"), 0
    while i < len(lines):
        out.append(lines[i])
        m = re.search(r"""<<-?\s*['\"]?([A-Za-z_][A-Za-z0-9_]*)['\"]?""", lines[i])
        i += 1
        if not m:
            continue
        end = m.group(1)
        while i < len(lines) and lines[i].strip() != end:
            i += 1
        if i < len(lines):
            out.append(lines[i])
            i += 1
    return "\n".join(out)

body = strip_heredocs(cmd)

# Which directory would this command run in?
target = cwd
m = re.search(r"(?:^|&&|;)\s*cd\s+([^\s;&|]+)", cmd)
if m:
    target = expand(m.group(1))
in_main = os.path.realpath(target) == MAIN

# A shell edit under the main checkout's plugins/. The Write/Edit matcher above
# never sees these: in bypass-permissions mode files are edited with sed, tee
# and heredocs, which is most of the editing this session does.
if in_main and re.search(r"(?<![\w/-])plugins/[A-Za-z0-9]+/", body):
    if re.search(r"\bsed\s+(-[^\s]*\s+)*-[^\s]*i|\btee\b|\bcp\b|\bmv\b|\brm\b|"
                 r"\btouch\b|\bmkdir\b|\bgit\s+apply\b|\bpatch\b|>\s*[^\s|&]*plugins/", body):
        block("editing under plugins/",
              "Work in that plugin's worktree, ~/GitHub/atak-plugins-<plugin> "
              "(scripts/worktree.sh list shows them).")

if "new-plugin.sh" not in body:
    sys.exit(0)

if in_main:
    block("scaffolding a new plugin",
          "A new plugin gets its worktree FIRST: "
          "~/GitHub/atak-plugins/scripts/worktree.sh new <Name> <name>-v0.1, then "
          "cd ~/GitHub/atak-plugins-<name> and scaffold there. Invoke the /new-plugin "
          "skill, which does this in order.")
sys.exit(0)
PYEOF
