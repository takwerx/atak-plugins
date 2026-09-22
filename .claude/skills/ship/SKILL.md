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

There are two other ship units, both at the end of this file:

- **`tooling`**: shared files (`scripts/`, `.claude/`, `CLAUDE.md`, the root
  `README.md`, `.gitignore`, a rule applied to every plugin's
  `app/build.gradle`) going to `main` from `atak-plugins-tooling`, with nothing
  published anywhere. A plugin branch never carries those (CLAUDE.md, "Working
  in parallel").
- **`docs`**: a plugin's published files — README, guide, screenshots, LICENSE,
  CONTRIBUTING — reaching its public repo with no tag, no GitHub Release and no
  catalog. For a change that cannot alter the APK, so there is nothing to
  version.

Every ship merges in the **main checkout**, `~/GitHub/atak-plugins`, which is
on `main` and nothing else; the branch being shipped lives in its own
worktree. `git -C ~/GitHub/atak-plugins …` below is that checkout.

## Step 0 — Pre-flight (read-only, before asking anything)

Repo: this repo (`$CLAUDE_PROJECT_DIR`, the plugin's worktree). Branch being
shipped: the current branch there (e.g. `camdepot-v1.3`). Merge target: the
main checkout; `git -C ~/GitHub/atak-plugins branch --show-current` must print
`main`, and if it does not, stop and say so before anything else.

1. `git fetch origin` and confirm the branch tip is pushed (`git status -sb`).
   Record `git log -1 --format='%h %s'`. Then `./scripts/check-main-merged.sh`
   → PASS: the branch contains every commit on main, so it carries every
   shared rule and the merge below is clean. A FAIL means main landed since
   this session opened: `./scripts/check-main-merged.sh --merge` brings it in
   on a clean tree (the session-start hook does the same), and only a
   conflict needs a hand (FOBS 0.5's zips were built without the versionCode
   gate that already existed on another branch).
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
7. **Submission artifacts:** `ls "$ATAK_DIST"/<Plugin>-<ver>-*.zip` (`~/atak-dist`,
   which `dist/` in every checkout links to) — one per ATAK target the fleet
   runs, named by `submission-zip.sh`, all from the candidate commit. If they
   predate the candidate, regenerate before shipping.
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
    assets, and when `~/atak-dist/signed/` holds this version's APKs each one must be
    linked. A FAIL stops the ship: fix the block, commit it on the branch,
    re-run. Map Depot 1.6 shipped with every link aimed at a v1.5 release that
    was never created, and the 404s were found by a user.
    `.claude/hooks/release-links-guard.sh` also blocks the subtree push and
    the `gh release create` mechanically, so a ship that skips this step still
    cannot publish stale links.
11. **Version code (MANDATORY):** `./scripts/check-version-code.sh <Plugin>
    --signed --live` → PASS. The fleet gets plugins pushed by Watchtower MDM,
    which keys updates on Android's integer `versionCode`; every signed
    release before Cam Depot 1.3 carried 1 and could not be pushed over the
    one before it. The check proves the tree derives the code from
    `PLUGIN_VERSION`, the version is above every signed release and every
    live GitHub Release, and this version's signed APKs are all present (one
    per target the README links), carry that code, and keep the package name
    and signing certificate of the last release. A FAIL stops the ship; the
    fix is a version bump and a resubmission, never an edit to the check.
    `release-links-guard.sh` runs the same check (without `--live`) before
    the subtree push and the `gh release create`.

## Step 1 — The ship prompt (HARD STOP)

Present exactly this via AskUserQuestion and wait:

> Ready to ship **<Plugin> <version>** to `main`:
> - branch tip: `<sha>` (`<subject>`), N commits over main
> - device verification: `<one line: device, ATAK version, what was run, result>`
> - publish scrub: PASS · security scan: `<date/commit>` · zips: `<names>`
> - open issues: `<count surfaced / none>` · commit scan: `<clean / acknowledged>`
> - download links: `check-download-links PASS (<Plugin> <version>)`
> - version code: `check-version-code PASS (<Plugin> <version> -> <code>)`, signed APKs for every target
> - this will: merge the branch into `main` (merge commit), push main, subtree-push
>   `plugins/<Name>` to `takwerx/<plugin-repo>` main, tag `v<version>` there,
>   create its GitHub Release with the signed APKs, and refresh the depot
>   catalog so the TAKwerx Market offers <version>
>
> **Ship it?**

Options: "Ship it" / "Abort". Anything other than an explicit yes → stop entirely.

## Step 2 — Unlock the guard

Only after the explicit yes:

```bash
echo "<Plugin>" > "$CLAUDE_PROJECT_DIR"/.claude/.ship-authorized
```

The file names the plugin directory being shipped (`MapDepot`); the ship-close
guard reads it. Expires after 30 minutes. Never create it outside this skill.

## Step 3 — Execute (all of it)

1. **Merge, in the main checkout:** `git -C ~/GitHub/atak-plugins pull --ff-only origin main &&
   git -C ~/GitHub/atak-plugins merge --no-ff <branch> -m "Merge <branch>: <Plugin> <version>\n\n<product summary>"`.
   The main checkout is on `main` (pre-flight checked); never `git checkout main`
   in a worktree. The branch contains main (pre-flight step 1), so this is
   clean; a real merge commit is wanted — it is the release marker. Verify
   `git diff <branch> main --stat` is empty.
2. **Push main:** `git -C ~/GitHub/atak-plugins push origin main`.
3. **Subtree push to the plugin's public repo (history preserved), from the main checkout:**
   ```bash
   git -C ~/GitHub/atak-plugins subtree split --prefix=plugins/<Name> -b <name>-export
   git -C ~/GitHub/atak-plugins push https://github.com/takwerx/<plugin-repo>.git <name>-export:refs/heads/main
   ```
4. **Tag + GitHub Release ON THE PLUGIN REPO:** `git -C ~/GitHub/atak-plugins push https://github.com/takwerx/<plugin-repo>.git <name>-export:refs/tags/v<version>` (or tag there), then `gh release create v<version> --repo takwerx/<plugin-repo> --title "<Plugin> <version>" --latest --notes-file … "$ATAK_DIST"/signed/ATAK-Plugin-<Plugin>-<version>--*.apk` (this version's three, never `signed/*.apk`, which is every plugin ever signed). Body is product-only: what it does, what changed, a table of which APK is for which ATAK version, link to the guide. No device names, serials, test locations, or engineering detail. Never an SDK artifact. The download links at the top of the plugin README/guide were verified against this version in pre-flight step 10; after the release exists, prove they resolve: `./scripts/check-download-links.sh <Plugin> --live` → every link 200. A 404 here means the release tag or an asset name does not match the README; fix the release, not the check.
5. **Depot catalog — the TAKwerx Market installs whatever this says is newest:**
   `scripts/check-depot-catalog.sh <Name> --refresh`. It rebuilds the catalog
   from the GitHub Release just created (`../atak-plugins-notes/tools/publish_depot.sh`),
   uploads it to R2 and checks that every ATAK target now offers `<version>`.
   Must print `PASS`. Map Depot 1.6 skipped this and the Market installed 1.4
   the next day; `.claude/hooks/ship-close-guard.sh` blocks Step 4 until it
   passes. A plugin's first release also needs its repo added to
   `DEFAULT_REPOS` and `LOCAL_PLUGIN_DIR` in `refresh_depot.py`.
6. **Private notes:** write/update the HANDOFF or a `RELEASE-<Plugin>-v<version>.md`
   in `../atak-plugins-notes/docs/` (what shipped, commit, verification
   evidence, signed-APK digests, residuals). Commit + push the notes repo.
7. **Bring the branch up to main:** in the plugin's worktree, `git merge --ff-only main`
   so branch == main, push the branch. The main checkout stays on `main`.

## Step 4 — Re-lock and report

```bash
rm -f "$CLAUDE_PROJECT_DIR"/.claude/.ship-authorized
```

The guard refuses the `rm` while `check-depot-catalog.sh <Name>` fails; fix the
catalog (step 5), do not work around it.

Report: main SHA, tag, release URL if any, the depot catalog line
(`<Plugin> <version> offered on: 5.6.0 5.7.0 5.8.0`), what remains manual (TPC
upload of the zips, device check of the tak.gov-signed build when it arrives).

## If anything fails mid-sequence

Stop, report exactly which step failed with output, and leave the sentinel in
place only if the operator wants to continue immediately — otherwise remove it.
Never improvise recovery pushes to main without telling the operator first.

## Ship unit `tooling` — shared files to `main`, nothing published

For a branch in `atak-plugins-tooling` that changes only shared files. Same
guard, same sentinel, shorter list, because nothing leaves this machine but
the push of `main`.

Pre-flight:
1. `git status -sb` clean; `./scripts/check-main-merged.sh` → PASS (`--merge`
   brings main in if it is not).
2. The branch changes only shared paths:
   `git diff --stat main...HEAD -- . ':!scripts' ':!.claude' ':!CLAUDE.md' ':!README.md' ':!.gitignore'`
   is empty, or lists only one rule applied to every plugin's `app/build.gradle`
   (say which rule). A plugin's own code on a tooling branch is a plugin ship,
   not this.
3. **Publish scrub (MANDATORY):** `./scripts/publish-scrub.sh` → PASS.
4. **Commit scan** as in step 9 above: clean, or each hit acknowledged.
5. Main checkout on `main`: `git -C ~/GitHub/atak-plugins branch --show-current`.

Prompt, via AskUserQuestion:

> Ready to ship **tooling** (`<branch>`, N commits over main) to `main`:
> - what changes: `<one line per concern: scripts, hooks, CLAUDE.md …>`
> - publish scrub: PASS · commit scan: `<clean / acknowledged>` · contains main: PASS
> - this will: merge the branch into `main` and push main. No plugin repo, no
>   tag, no release, no catalog. Every plugin worktree picks the new main up by
>   itself when its session next opens or builds a zip; only a conflict is
>   handed to a person.
>
> **Ship it?**

After "Ship it": `echo tooling > "$CLAUDE_PROJECT_DIR"/.claude/.ship-authorized`,
then steps 1, 2 and 7 of the execute list (merge in the main checkout, push
main, fast-forward the branch), a line in the day's HANDOFF naming the merge
commit, and the re-lock `rm`. Report the main SHA and which worktrees are now
behind main (`./scripts/worktree.sh list`).

## Ship unit `docs` — a plugin's published files, no release

For a plugin branch whose changes **cannot alter the APK**: README, `docs/`
(guide, screenshots, user manual source), `LICENSE`, `LICENSE-EXCEPTION.md`,
`CONTRIBUTING.md`, `CLA.md`, and the `tools/` beside the plugin that never
enters the build. It publishes the plugin tree to its public repo and stops:
no tag, no GitHub Release, no depot catalog, no version bump, no tak.gov
submission.

Until 2026-09-14 publishing a file and releasing software were one act, because
the only route to a plugin's public repo was the subtree push inside a plugin
ship — and a plugin ship refuses a version that is already signed
(`check-version-code.sh`). So a LICENSE file that two contributors were waiting
on (takwerx/comms#1 and #2, both offering work against a repo with no license)
could only be published by inventing a version: a new submission, three fresh
signed APKs and a Market refresh, to ship a text file. A wrong download link and
a typo in a guide hit the same wall.

`scripts/check-docs-only.sh` is what keeps this honest. It refuses any change
under `app/`, `gradle/`, `gradlew`, `build.gradle`, `settings.gradle`,
`gradle.properties` or `template.local.properties`, and any change outside this
plugin's directory. One byte inside the APK and it is a release — which is the
correct answer, not an obstacle.

Pre-flight:
1. `git status -sb` clean; `./scripts/check-main-merged.sh` → PASS (`--merge`
   brings main in if it is not).
2. **`./scripts/check-docs-only.sh <Plugin>` → PASS.** This is the unit's whole
   justification; a FAIL means ship the plugin properly with a version.
3. **Publish scrub (MANDATORY):** `./scripts/publish-scrub.sh` → PASS. The tree
   is about to become a public repo's `main`.
4. `./scripts/check-download-links.sh <Plugin>` → PASS and
   `./scripts/check-version-code.sh <Plugin> --signed` → PASS. Both already run
   in `release-links-guard.sh` on the push; running them here means the ship
   prompt is honest rather than discovering it mid-sequence. They pass at the
   already-shipped version, because neither the version nor the APKs moved.
5. **Commit scan** as in step 9 above: clean, or each hit acknowledged.
6. Main checkout on `main`: `git -C ~/GitHub/atak-plugins branch --show-current`.

Prompt, via AskUserQuestion:

> Ready to publish **<Plugin> docs** (`<branch>`, N commits over main) to
> `takwerx/<plugin-repo>`:
> - what changes: `<one line: the files, e.g. "LICENSE, CONTRIBUTING, CLA and a
>   README license section">`
> - docs-only: `check-docs-only PASS (nothing inside the APK)`
> - publish scrub: PASS · commit scan: `<clean / acknowledged>` · contains main: PASS
> - this will: merge the branch into `main`, push main, and subtree-push
>   `plugins/<Name>` to `takwerx/<plugin-repo>` main. **No tag, no release, no
>   catalog, no version bump** — <Plugin> stays at <version> and the signed APKs
>   already published are untouched.
>
> **Publish it?**

Options: "Publish it" / "Abort". Anything other than an explicit yes → stop.

After "Publish it": `echo "<Plugin> docs" > "$CLAUDE_PROJECT_DIR"/.claude/.ship-authorized`,
then execute-list steps 1, 2, 3 and 7 only — merge in the main checkout, push
main, subtree push, fast-forward the branch. **Skip steps 4, 5 and 6** (tag,
release, catalog, release notes); there is no release to note. Then the re-lock
`rm`.

The sentinel's first word is the plugin, so `ship-close-guard.sh` checks the
depot catalog on the way out as it does for a release. That is right: the
catalog should already offer this version, and a FAIL here means it drifted and
wants fixing regardless of what was just published.

Report the main SHA, the public repo's new head, and one line stating that no
release was created and the plugin remains at its current version.
