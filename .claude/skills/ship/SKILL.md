---
name: ship
description: The ONLY path to main, a tag, or a GitHub Release in takwerx/atak-plugins. Invoke when the operator gives an unambiguous ship instruction ("send it to main", "merge to main", "tag it", "release PLSS 0.4"). Runs pre-flight, presents the mandatory ship prompt, and unlocks the git-guard hook only after an explicit yes.
---

# /ship — atak-plugins release procedure

`.claude/hooks/git-guard.sh` blocks merge-to-main, `git tag`, pushes of
main/tags, and `gh release create` unless this skill's sentinel exists. General
approval given before the ship prompt ("go for it", "send it") does not count —
the answer to THIS prompt is the authorization.

Ship units are per plugin: a ship is `<Plugin> <version>` (e.g. `PLSS 0.3`).
Each plugin has its OWN public repo (`takwerx/plss-grid`, `takwerx/<name>`) —
that is where its tag (`v<version>`), GitHub Release (tak.gov-signed APKs
attached), README, guide and issues live. This monorepo carries no plugin
release tags. The tak.gov TPC submission is the upstream release; the GitHub
Release on the plugin repo is how users download.

## Step 0 — Pre-flight (read-only, before asking anything)

Repo: this repo (`$CLAUDE_PROJECT_DIR`). Branch being shipped: the
current feature branch (e.g. `plss-overlay-v0.1`).

1. `git fetch origin` and confirm the branch tip is pushed (`git status -sb`).
   Record `git log -1 --format='%h %s'`.
2. Version: `grep PLUGIN_VERSION plugins/<Plugin>/app/build.gradle` — this is the
   version being shipped; it must already be bumped in-branch, and `README.md`
   STATUS must say the same number.
3. Target: `grep ATAK_VERSION plugins/<Plugin>/app/build.gradle` must be the
   shipping target (`5.7.0`), `local.properties` on the same SDK, tree clean.
4. **Publish scrub (MANDATORY):** `./scripts/publish-scrub.sh` → PASS. A FAIL
   stops the ship; fix or move content to the notes repo.
5. **Device verification:** find the evidence in this session or the latest
   `../atak-plugins-notes/docs/HANDOFF-*.md` — what was run on hardware, on
   which ATAK version, and the result. If the code being shipped was never run
   on a device, STOP and say so. Release builds differ from debug (CLAUDE.md) —
   note whether civRelease, or a tak.gov-signed build, was the thing tested.
6. **Security scan:** CLAUDE.md requires a `/security-review`-class scan before
   a plugin is fielded or published, and again on any vendored-code bump. Cite
   the scan (date, commit, result) or run it now.
7. **Submission artifacts:** `ls dist/<Plugin>-<ver>-*.zip` — one per ATAK target
   the fleet runs, named by `submission-zip.sh`, all from the candidate commit.
   If they predate the candidate, regenerate before shipping.
8. **Open-issue review (MANDATORY):** `gh issue list --repo takwerx/atak-plugins
   --state open --json number,title,updatedAt,comments`. For each open issue read
   the latest comment; surface before the prompt any that touches what ships,
   any "fixed" issue the reporter says still fails, any fresh bug with no fix.
   The operator decides whether it blocks — but must see the list.
9. **Candidate-range commit scan (MANDATORY):**
   ```bash
   git log --oneline origin/main..HEAD | grep -iE "test:|revert|\bWIP\b|debug|temporar|do not ship|hack"
   ```
   Any hit → show each commit (SHA + subject) and get per-commit acknowledgment
   ("still wanted / must be reverted") before the prompt. Zero hits → say
   "commit scan clean".
10. **Download links (MANDATORY):** `./scripts/check-download-links.sh <Plugin>`
    → PASS. The download block at the top of `README.md` and
    `docs/USER_GUIDE.md` must name `PLUGIN_VERSION` and link the `v<version>`
    assets, and when `dist/signed/` holds this version's APKs each one must be
    linked. A FAIL stops the ship: fix the block, commit it on the branch,
    re-run. Map Depot 1.6 shipped with every link aimed at a v1.5 release that
    was never created, and the 404s were found by a user.
    `.claude/hooks/release-links-guard.sh` also blocks the subtree push and
    the `gh release create` mechanically, so a ship that skips this step still
    cannot publish stale links.

## Step 1 — The ship prompt (HARD STOP)

Present exactly this via AskUserQuestion and wait:

> Ready to ship **<Plugin> <version>** to `main`:
> - branch tip: `<sha>` (`<subject>`), N commits over main
> - device verification: `<one line: device, ATAK version, what was run, result>`
> - publish scrub: PASS · security scan: `<date/commit>` · zips: `<names>`
> - open issues: `<count surfaced / none>` · commit scan: `<clean / acknowledged>`
> - download links: `check-download-links PASS (<Plugin> <version>)`
> - this will: merge the branch into `main` (merge commit), push main, subtree-push
>   `plugins/<Name>` to `takwerx/<plugin-repo>` main, tag `v<version>` there and
>   create its GitHub Release with the signed APKs
>
> **Ship it?**

Options: "Ship it" / "Abort". Anything other than an explicit yes → stop entirely.

## Step 2 — Unlock the guard

Only after the explicit yes:

```bash
touch "$CLAUDE_PROJECT_DIR"/.claude/.ship-authorized
```

Expires after 30 minutes. Never create it outside this skill.

## Step 3 — Execute (all of it)

1. **Merge:** `git checkout main && git pull --ff-only origin main &&
   git merge --no-ff <branch> -m "Merge <branch>: <Plugin> <version>\n\n<product summary>"`.
   Histories here are not diverged; a real merge commit is wanted — it is the
   release marker. Verify `git diff <branch> main --stat` is empty.
2. **Push main:** `git push origin main`.
3. **Subtree push to the plugin's public repo (history preserved):**
   ```bash
   git subtree split --prefix=plugins/<Name> -b <name>-export
   git push https://github.com/takwerx/<plugin-repo>.git <name>-export:refs/heads/main
   ```
4. **Tag + GitHub Release ON THE PLUGIN REPO:** `git push https://github.com/takwerx/<plugin-repo>.git <name>-export:refs/tags/v<version>` (or tag there), then `gh release create v<version> --repo takwerx/<plugin-repo> --title "<Plugin> <version>" --latest --notes-file … dist/signed/*.apk`. Body is product-only: what it does, what changed, a table of which APK is for which ATAK version, link to the guide. No device names, serials, test locations, or engineering detail. Never an SDK artifact. The download links at the top of the plugin README/guide were verified against this version in pre-flight step 10; after the release exists, prove they resolve: `./scripts/check-download-links.sh <Plugin> --live` → every link 200. A 404 here means the release tag or an asset name does not match the README; fix the release, not the check.
5. **Private notes:** write/update the HANDOFF or a `RELEASE-<Plugin>-v<version>.md`
   in `../atak-plugins-notes/docs/` (what shipped, commit, verification
   evidence, signed-APK digests, residuals). Commit + push the notes repo.
6. **Return to the branch:** `git checkout <branch> && git merge --ff-only main`
   so branch == main, push the branch.

## Step 4 — Re-lock and report

```bash
rm -f "$CLAUDE_PROJECT_DIR"/.claude/.ship-authorized
```

Report: main SHA, tag, release URL if any, what remains manual (TPC upload of
the zips, device check of the tak.gov-signed build when it arrives).

## If anything fails mid-sequence

Stop, report exactly which step failed with output, and leave the sentinel in
place only if the operator wants to continue immediately — otherwise remove it.
Never improvise recovery pushes to main without telling the operator first.
