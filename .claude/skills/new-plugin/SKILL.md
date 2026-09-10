---
name: new-plugin
description: The ONLY way a new takwerx ATAK plugin is started. Invoke on the FIRST message that proposes one ("I want to build a plugin that...", "new plugin idea", "can we make a plugin for X") — before any design discussion. Makes the worktree, scaffolds the plugin inside it, opens the PLAN, and hands the session over. Not for work on a plugin that already exists.
---

# /new-plugin — a plugin gets its folder before it gets a design

A new plugin's worktree is made **first**, and the plugin is scaffolded inside
it, so it never touches the main checkout or another plugin's worktree
(CLAUDE.md, "Working in parallel" and "Creating a plugin").

This exists because the rule kept losing to momentum. When the operator
describes an idea, the interesting part is the idea, and the session keeps
talking in whatever directory it is already in — Comms and Feature Layer both
got their folder late, and the operator has said twice that the first reply to
a plugin idea should name the worktree step and take it. `.claude/hooks/`
`new-plugin-guard.sh` blocks the scaffold in the main checkout, so there is no
version of this that works by remembering.

**Run this at the top of the conversation, not after the design is settled.**
The design conversation belongs in the new worktree, where its notes, its PLAN
and its commits are already in the right place.

## Step 1 — Is this actually a new plugin?

Yes: the operator proposes a capability no plugin under `plugins/` has, in any
words ("a plugin that...", "could ATAK show...", "I want something for...").

No, and stop here: a change to an existing plugin, a question about one, or a
capability that belongs in one that already exists. Say which plugin it lands
in and work there. Adding a screen to Cam Depot is not a new plugin.

If the capability is close to an existing plugin, ask before making anything —
a plugin is a public repo, a Market entry and a support surface forever, and
merging into an existing one is often the better answer.

## Step 2 — The name, decided with the operator

The name is the directory, `rootProject.name`, the proguard descriptor, the
public repo, the Market entry and **the APK name tak.gov produces**. Renaming
later is disruptive. Propose one and get a yes; do not pick silently.

- **Letters and digits only.** No dash, no underscore, no space. A dash reaches
  `-repackageclasses atakplugin.${rootProject.name}` as an invalid Java package
  and the *release* build fails — debug builds pass, so it bites at submission.
- Capitals are fine and are how it reads: `CamDepot`, `FeatureLayer`, `FOBS`.
- The Display Name is separate and may have spaces: `"Cam Depot"`.
- The public repo is kebab-case of the display name: `takwerx/feature-layer`.

Say all three back before running anything: `<Name>`, `"<Display Name>"`,
`takwerx/<repo-name>`.

## Step 3 — Make the worktree and scaffold, in that order

Run from anywhere; `worktree.sh` reads `main` for what exists, so a name with
no plugin on `main` is a new plugin and a misspelt existing one is refused.

```bash
~/GitHub/atak-plugins/scripts/worktree.sh new <Name> <name>-v0.1
cd ~/GitHub/atak-plugins-<name>
./scripts/new-plugin.sh <Name> "<Display Name>"
git rm -r --quiet plugins/<Name>/docs/user_manual        # see below
git add plugins/<Name> && git commit -m "<Name> 0.1: scaffold from the SDK template"
```

`worktree.sh new` also writes what git does not carry: the `dist` symlink, a
`local.properties` per plugin pointing at the SDK it targets, and the link that
gives the new directory the main checkout's auto-memory (a worktree otherwise
starts knowing none of it).

**`git rm -r docs/user_manual` is not optional.** The SDK template ships a
placeholder manual titled "Plugin Template 0.1" and `new-plugin.sh` copies it
with everything else. Left in place, tak.gov compiles the template's manual
into the plugin. FOBS caught it one zip from submission; Weather still carries
it. Remove it now, and the plugin gets its own when it has one.

## Step 4 — Open the PLAN in the notes repo

Plan-first applies to a new plugin without exception. Create
`../atak-plugins-notes/docs/PLAN-<Name>-v0.1.md` with the sections a PLAN
carries here, and fill what is already known from the operator's description:

- What it does, and for whom in the field
- Data sources, and whether anything will actually serve them (CLAUDE.md's
  catalog rule: reading a catalog is not the same as asking a server)
- **Background and interruptions** — answer every line of
  `../atak-plugins-notes/docs/CHECKLIST-background-behavior.md` that applies.
  "Not applicable" is an answer; silence is not. Anything that must outlive a
  tap lives in a component, never inside a `Tool`.
- ATAK targets to build for, and the UI surface (pane, toolbar item, radial)
- Open questions for the operator

Add it by name (`git add <file>`, never `git add -A` in the notes repo).

## Step 5 — Hand the session over, and stop

Tell the operator, in these terms:

> `<Name>` is scaffolded in `~/GitHub/atak-plugins-<name>` on branch
> `<name>-v0.1`, and the PLAN is at `../atak-plugins-notes/docs/PLAN-<Name>-v0.1.md`.
> Open that folder in a new window (the workspace file has both repos) and we
> design it there — this session stays out of it.

Then **stop working on the plugin in this session.** Continuing here is the
failure this skill exists to prevent: two sessions on one checkout, and a
plugin's history starting in the wrong place. Answer questions, but every edit
happens in the new worktree.

## Before its first `/ship`

Not this session's job, but say it once so it is not discovered at ship time:

- `takwerx/<repo-name>` must exist and match the plugin page standard
  (`takwerx/plss-grid` is the reference — CLAUDE.md, "One public repo per plugin")
- `refresh_depot.py` in the notes repo must list the repo, or the Market never
  shows the plugin at all (HARD RULE 2)
- The icons are two files, not one: `ic_launcher.png` on a dark tile,
  `ic_toolbar.png` a bare white glyph, edge to edge at 256
