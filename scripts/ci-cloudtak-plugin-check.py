#!/usr/bin/env python3
"""ci-cloudtak-plugin-check: does a CloudTAK plugin really work after a TAK Portal Marketplace install? (ops #692)

A folder in ~/CloudTAK/app/plugins/ is not proof. On Nov 12, 2026 a Marketplace install ended `complete` with the
files in place while CloudTAK's served app held no plugin at all (ops #687: the portal's SSH key had no terminal;
then CloudTAK's rebuild dropped app/plugins/* because the Docker ignore file was missing). Customers saw nothing.
This does what a customer does and judges the outcome:

  1. TAK Portal (127.0.0.1:3000 on the box) lists the plugin.
  2. Stage the install, press Deploy (the Marketplace page's own two calls), wait for the job. It must end `complete`.
  3. The plugin's folder is in ~/CloudTAK, and the Portal lists it installed with no error.
  4. CloudTAK's SERVED app holds the plugin: the map page's scripts contain `plugins/<id>/index`, the key CloudTAK's
     plugin loader keeps. Every OTHER plugin the Portal lists as installed must still be there (a rebuild that drops
     plugins is the same bug).
  5. Optional, on the Studio: a headless browser opens CloudTAK signed in and screenshots the plugin's menu entry
     (ci-cloudtak-plugin-look.py), so a person can see it loaded.

  scripts/ci-cloudtak-plugin-check.py --via 'ssh test12' --plugin dispatcher --map-url https://map.example.org
        [--reinstall] [--label 'TAK Dispatcher'] [--screenshot FILE] [--max-min 45] [--cloudtak-dir DIR]
  scripts/ci-cloudtak-plugin-check.py --judge-job STATUS.json KIND ID        # verdict on a saved job list, no box
  scripts/ci-cloudtak-plugin-check.py --judge-bundle APP.js ID [ID ...]      # verdict on a saved served app, no box

--via       the command that runs ONE shell command string on the box as a user that can run docker and read the
            console user's home (root, or the console user): `ssh test12`, `fresh-install/box fi-2026...`,
            `ssh -i KEY ubuntu@IP sudo bash -c`. The box must run TAK Portal and CloudTAK.
--plugin    the Marketplace plugin id (GET /api/cloudtak-marketplace/plugins lists them; e.g. print,
            search-containment, dispatcher).
--map-url   CloudTAK's public address, fetched FROM THE BOX (`curl -k`), so a box behind NAT works.
--reinstall Uninstall first, then install (another CloudTAK rebuild, about 20 to 40 min). Without it, a plugin that is
            already installed is not installed again: only the app check runs, and the report says so.
--label     the menu text in CloudTAK, for the screenshot step (needs --screenshot and the playwright venv in
            $CT_PLAYWRIGHT_PY, default ~/tak-agent/work/ops/692-venv/bin/python).

Exit: 0 PASS; 1 FAIL (the line says what a customer would see); 2 usage; 3 could not run (box unreachable, plugin not in
the Marketplace). Only 0 is a pass. A box test that says "complete" and nothing more is not a pass.
"""
import argparse, json, os, re, shlex, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
PORTAL = "http://127.0.0.1:3000"
HDR = "-H 'x-authentik-username: akadmin' -H 'x-authentik-groups: authentik Admins'"
RESULTS = []


def line(status, what, detail=""):
    RESULTS.append(status)
    print(f"{status} {what}: {detail}".rstrip(), flush=True)
    return status == "PASS"


# ── Verdicts: pure functions, tested offline (scripts/test-ci-cloudtak-plugin-check.sh) ──────────────────────────
def judge_job(status_json, kind, plugin_id):
    """(ok, why) for the newest `kind` job of `plugin_id` in the Portal's /status answer."""
    try:
        d = json.loads(status_json)
    except ValueError as e:
        return False, "TAK Portal gave no readable job status: %s" % str(e)[:120]
    jobs = [j for j in d.get("jobs", []) if j.get("kind") == kind and j.get("pluginId") == plugin_id]
    if not jobs:
        return False, "TAK Portal has no %s job for %s" % (kind, plugin_id)
    j = jobs[-1]
    if j.get("status") == "complete" and not j.get("error"):
        return True, "%s job for %s complete" % (kind, plugin_id)
    why = "%s job for %s ended %s" % (kind, plugin_id, j.get("status") or "with no status")
    if j.get("error"):
        why += ": %s" % str(j["error"])[:200]
    if "pseudo-terminal" in json.dumps(j).lower():
        why += (" [TAK Portal could not open a terminal over SSH to the host: the portal key line in authorized_keys"
                " must allow pty (`restrict,pty,from=...`), ops #644, #687]")
    return False, why


def judge_job_running(status_json, kind, plugin_id):
    """True while the newest matching job has not finished (or does not exist yet)."""
    try:
        d = json.loads(status_json)
    except ValueError:
        return True
    jobs = [j for j in d.get("jobs", []) if j.get("kind") == kind and j.get("pluginId") == plugin_id]
    return (not jobs) or (jobs[-1].get("status") or "") in ("", "queued", "running", "staged")


def judge_bundle(app_js, ids):
    """(ok, why): every id's `plugins/<id>/index` key is in the served app. Empty app = the map page did not load."""
    if not app_js.strip():
        return False, "CloudTAK's map page did not load (no scripts to read)"
    missing = [i for i in ids if ("plugins/%s/index" % i) not in app_js]
    if missing:
        return False, ("CloudTAK's served app does not load: %s (no plugins/<id>/index in the map page's scripts; "
                       "the Marketplace job said complete, a customer sees no plugin)" % ", ".join(missing))
    return True, "CloudTAK's served app loads " + ", ".join(ids)


# ── The box ──────────────────────────────────────────────────────────────────────────────────────────────────────
class Box:
    def __init__(self, via):
        self.via = shlex.split(via)

    def run(self, cmd, timeout=300):
        try:
            r = subprocess.run(self.via + [cmd], capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
        except subprocess.TimeoutExpired:
            return 124, "", "timed out"
        return r.returncode, r.stdout, r.stderr

    def portal(self, method, path, body=None):
        """(http status, body). Sent from the box to TAK Portal on loopback with the admin headers Caddy/Authentik add."""
        data = ""
        if body is not None:
            data = "-H 'Content-Type: application/json' -d %s" % shlex.quote(json.dumps(body))
        rc, out, err = self.run("curl -s -m 120 -X %s %s %s -w '\\n%%{http_code}' %s%s" % (method, HDR, data, PORTAL, path))
        if rc != 0 or "\n" not in out:
            return 0, (err or out)[:200]
        b, _, code = out.rpartition("\n")
        return (int(code) if code.strip().isdigit() else 0), b


def find_cloudtak_dir(box, given):
    if given:
        return given
    rc, out, _ = box.run("ls -d /home/*/CloudTAK /root/CloudTAK /opt/CloudTAK 2>/dev/null | head -1")
    return out.strip()


def served_app(box, map_url):
    """All JS the map page links, joined, fetched on the box. '' when the page did not load."""
    base = map_url.rstrip("/")
    cmd = ("html=$(curl -sk -m 60 %s/) && [ -n \"$html\" ] || exit 2; "
           "for u in $(printf '%%s' \"$html\" | grep -oE '(src|href)=\"/[^\"]+\\.js\"' | sed -E 's/^(src|href)=\"//; s/\"$//' | sort -u); do "
           "curl -sk -m 60 %s$u; echo; done") % (shlex.quote(base), shlex.quote(base))
    rc, out, _ = box.run(cmd, timeout=400)
    return out if rc == 0 else ""


def wait_job(box, kind, pid, max_min):
    last = ""
    for _ in range(max_min * 6):
        st, body = box.portal("GET", "/api/cloudtak-marketplace/status")
        if st == 200:
            last = body
            if not judge_job_running(body, kind, pid):
                return last
        time.sleep(10)
    return None


def run_job(box, kind, pid, max_min, dest=""):
    body = {"kind": kind, "pluginId": pid}
    if kind == "uninstall":
        body["dest"] = dest or None
    st, b = box.portal("POST", "/api/cloudtak-marketplace/jobs", body)
    if not 200 <= st < 300:
        return False, "TAK Portal would not stage the %s of %s: HTTP %s %s" % (kind, pid, st, b[:160])
    st, b = box.portal("POST", "/api/cloudtak-marketplace/jobs/deploy", {})
    if not 200 <= st < 300:
        return False, "Marketplace Deploy refused (%s %s): HTTP %s %s" % (kind, pid, st, b[:160])
    t0 = time.time()
    last = wait_job(box, kind, pid, max_min)
    if last is None:
        return False, "%s job for %s not finished after %d min" % (kind, pid, max_min)
    ok, why = judge_job(last, kind, pid)
    return ok, "%s (%d min)" % (why, (time.time() - t0) // 60)


def main():
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--via"); ap.add_argument("--plugin"); ap.add_argument("--map-url"); ap.add_argument("--reinstall", action="store_true")
    ap.add_argument("--label"); ap.add_argument("--screenshot"); ap.add_argument("--max-min", type=int, default=45)
    ap.add_argument("--cloudtak-dir"); ap.add_argument("--judge-job", nargs=3, metavar=("STATUS", "KIND", "ID"))
    ap.add_argument("--judge-bundle", nargs="+", metavar="APP_ID"); ap.add_argument("--mint-email", default="webadmin")
    ap.add_argument("-h", "--help", action="store_true")
    a = ap.parse_args()
    if a.help:
        print(__doc__); return 0
    if a.judge_job:
        ok, why = judge_job(open(a.judge_job[0]).read(), a.judge_job[1], a.judge_job[2]); print(("PASS " if ok else "FAIL ") + why)
        return 0 if ok else 1
    if a.judge_bundle:
        ok, why = judge_bundle(open(a.judge_bundle[0]).read(), a.judge_bundle[1:]); print(("PASS " if ok else "FAIL ") + why)
        return 0 if ok else 1
    if not (a.via and a.plugin and a.map_url):
        print(__doc__, file=sys.stderr); return 2
    pid = a.plugin
    box = Box(a.via)

    st, body = box.portal("GET", "/api/cloudtak-marketplace/plugins")
    if st != 200:
        line("FAIL", "TAK Portal", "Marketplace list: HTTP %s %s (box unreachable, or TAK Portal down)" % (st, body[:120])); return 3
    plugins = json.loads(body).get("plugins", [])
    row = next((p for p in plugins if p.get("id") == pid), None)
    if not row:
        line("FAIL", "marketplace", "%s is not in TAK Portal's Marketplace list (ids: %s)" % (pid, ", ".join(p.get("id", "?") for p in plugins))); return 3
    line("PASS", "marketplace", "%s is listed (installed: %s)" % (pid, bool(row.get("installed"))))

    ct = find_cloudtak_dir(box, a.cloudtak_dir)
    if not ct:
        line("FAIL", "cloudtak", "no ~/CloudTAK folder found on the box (use --cloudtak-dir)"); return 3
    installed_before = bool(row.get("installed"))

    if installed_before and a.reinstall:
        ok, why = run_job(box, "uninstall", pid, a.max_min, row.get("dest") or "")
        if not line("PASS" if ok else "FAIL", "uninstall", why):
            return 1
        installed_before = False
    if not installed_before:
        ok, why = run_job(box, "install", pid, a.max_min)
        if not line("PASS" if ok else "FAIL", "install", why):
            return 1
    else:
        line("PASS", "install", "%s was already installed, no job run (use --reinstall for a fresh install)" % pid)

    rc, out, _ = box.run("ls -d %s/app/plugins/%s %s/api/web/plugins/%s 2>/dev/null | head -1" % (ct, pid, ct, pid))
    if not line("PASS" if out.strip() else "FAIL", "files", ("folder %s" % out.strip()) if out.strip() else "the job ended complete but %s has no folder in %s" % (pid, ct)):
        return 1
    st, body = box.portal("GET", "/api/cloudtak-marketplace/plugins")
    rows = json.loads(body).get("plugins", []) if st == 200 else []
    mine = next((p for p in rows if p.get("id") == pid), {})
    if not line("PASS" if mine.get("installed") and not mine.get("error") else "FAIL", "portal", "Marketplace lists %s installed%s" % (pid, "" if mine.get("installed") else " NO: it does not") + ((", error: %s" % mine.get("error")) if mine.get("error") else "")):
        return 1

    ids = [pid] + sorted(p["id"] for p in rows if p.get("installed") and p.get("id") != pid)
    ok, why = judge_bundle(served_app(box, a.map_url), ids)
    if not line("PASS" if ok else "FAIL", "served app", why):
        return 1

    if a.screenshot:
        py = os.environ.get("CT_PLAYWRIGHT_PY", os.path.expanduser("~/tak-agent/work/ops/692-venv/bin/python"))
        js = ("const j=require('jsonwebtoken');process.stdout.write(j.sign({access:'admin',email:process.argv[1]},process.env.SigningSecret,{expiresIn:'30m'}))")
        rc, tok, _ = box.run("docker exec cloudtak-api-1 node -e %s %s" % (shlex.quote(js), shlex.quote(a.mint_email)))
        if rc != 0 or not tok.strip():
            line("FAIL", "browser", "could not sign a CloudTAK token inside cloudtak-api-1 for %s" % a.mint_email); return 1
        tf = a.screenshot + ".token"
        fd = os.open(tf, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(tok.strip())
        try:
            r = subprocess.run([py, os.path.join(HERE, "ci-cloudtak-plugin-look.py"), "--map-url", a.map_url, "--token-file", tf,
                                "--label", a.label or pid, "--screenshot", a.screenshot], capture_output=True, text=True, timeout=300)
        finally:
            os.remove(tf)
        sys.stdout.write(r.stdout)
        line("PASS" if r.returncode == 0 else "FAIL", "browser", "plugin menu entry seen in CloudTAK, screenshot %s" % a.screenshot if r.returncode == 0 else (r.stderr or r.stdout).strip()[-300:])
        if r.returncode != 0:
            return 1
    print("PLUGIN-CHECK PASS (%s)" % pid)
    return 0


if __name__ == "__main__":
    sys.exit(main())
