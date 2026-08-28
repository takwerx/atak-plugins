# CLAUDE.md — atak-plugins

Guidance for Claude Code working in this repo. Rules below are hard requirements.

## What this is

`takwerx/atak-plugins` is the single source of truth for takwerx ATAK (Android Team
Awareness Kit) plugin development. One directory per plugin under `plugins/`, each a
**standalone Gradle project** derived from the ATAK SDK's `plugintemplate` sample.

Engineering notes, PLANs and HANDOFFs live in the **private sibling repo**
`takwerx/atak-plugins-notes` (`../atak-plugins-notes/` on the dev Mac — open
`~/GitHub/atak-plugins.code-workspace` to get both in one window). Same public/private
split as `infra-TAK` ↔ `infra-TAK-notes`.

ATAK plugins are a **completely separate subsystem** from CloudTAK plugins
(`infra-TAK/cloudtak-plugins/`, Vue/TypeScript) and from TAK Server plugins
(`.jar`/`.yaml`, `/api/takserver/plugins/*`). Do not mix guidance between them.

## One public repo per plugin; this monorepo is where they are built

Users get a plugin from **its own public repository** — `takwerx/plss-grid` for
PLSS Grid, `takwerx/<plugin-name>` for each future plugin — whose front page is
the plugin: download links at the top of the README, the user guide with
screenshots under `docs/`, its own Releases (`vX.Y`, tak.gov-signed APKs
attached) and its own Issues. GitHub Releases and Issues are per repository, so
sharing them across plugins in one repo was confusing to hand out.

`takwerx/atak-plugins` (this repo) is the **development workspace**: shared
scripts, these rules, the scrub/ship gates, all plugins side by side under
`plugins/`. A plugin reaches its public repo by subtree push, history preserved:

```bash
git subtree split --prefix=plugins/<Name> -b <name>-export
git push https://github.com/takwerx/<plugin-repo>.git <name>-export:refs/heads/main
```

### The plugin page standard — `plss-grid` is the reference

Every plugin's public repo looks the same. `takwerx/plss-grid` is the reference
implementation; copy it rather than inventing a layout. A new plugin repo is not
finished until all of this matches:

**README.md** — in this exact order, and nothing between them:
1. Title line `ATAK Plugin — <Display Name>`
2. `**Download <Display Name> <ver>** (pick the one matching your ATAK-CIV
   version, sideload, then load it in ATAK's Plugins manager):` then one bullet
   per ATAK target, ascending, then `All releases: <releases URL>`
3. `**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**`
   with the absolute URL beneath it
4. The SDK template headings, unchanged and in template order: PURPOSE AND
   CAPABILITIES, STATUS, POINT OF CONTACTS, PORTS REQUIRED, EQUIPMENT REQUIRED,
   EQUIPMENT SUPPORTED, COMPILATION, DEVELOPER NOTES

Check it mechanically before shipping — this compares a plugin against the
reference and should print only the plugin's own name:

```bash
skel() { sed -E 's/[A-Za-z0-9._-]+\.apk/APK/g; s/[0-9]+\.[0-9]+(\.[0-9]+)?/N/g; s#https://[^ )]*#URL#g' "$1" \
  | awk '/^[A-Z][A-Z ]+$/ || /^\*\*/ || /^- \*\*/ {print}'; }
diff <(skel plugins/PLSS/README.md) <(skel plugins/<Name>/README.md)
```

**GitHub repo metadata** — set these with `gh repo edit`; they are what the repo
list and search show, and they are invisible from inside the README:
- description: `ATAK Plugin: <Display Name> — <what it does>. Downloads, guide and issues here.`
- website: the user guide's absolute URL
- topics: `atak`, `atak-plugin`, `tak`, plus one or two domain topics
- Issues enabled

**docs/USER_GUIDE.md** — same download block as the README at the top, and a
"Before you start" note listing which ATAK versions have published builds.

A plugin's *support surface* is its own repo: its Releases carry the
tak.gov-signed APKs, its Issues take the bug reports. Anything that is not a
plugin but lives beside them (e.g. `plss-data`, which hosts the packs PLSS Grid
downloads at runtime) says so in the first words of its description, so the repo
list does not read as if it were something to install.

Each `plugins/<Name>/` carries its own `.gitignore` so it is safe as a standalone
repo root. Per-plugin tags/releases live in the plugin repo (`v0.3`); this repo
does not carry plugin release tags. The root README is the index of plugins →
public repos. `/ship` covers the subtree push and the per-plugin release.

## Plugin UI standard — look like ATAK, not like a plugin

Every takwerx plugin uses the same controls, so a user moving between them is not
learning a new dialect each time. `CamDepot` is the reference implementation.

**Use ATAK's own button drawables.** `new-plugin.sh` already copies them out of the
SDK template — `btn_gray` is a selector over `new_dark_button_bg` /
`_selected` / `_disabled`: black-to-`#383838` gradient, `#585858` border, green
border when pressed, flat grey when disabled. Do not invent a button background.

Add this to `res/values/styles.xml` verbatim and use it on **every** button. ATAK's
own `darkButton` sets vertical padding only, which lets a short label render wider
than its own background:

```xml
<style name="TakwerxButton" parent="@style/darkButton">
    <item name="android:paddingLeft">12dp</item>
    <item name="android:paddingRight">12dp</item>
    <item name="android:paddingTop">6dp</item>
    <item name="android:paddingBottom">8dp</item>
    <item name="android:minHeight">44dp</item>
    <item name="android:minWidth">0dp</item>
    <item name="android:textSize">15sp</item>
    <item name="android:singleLine">true</item>
    <item name="android:ellipsize">end</item>
</style>
```

`style="@style/TakwerxButton"` on the widget — no per-button `textSize`, or it
fights the style. 44dp minimum height is a touch target for a gloved hand on a
vehicle mount, not a cosmetic choice.

**Never use a Spinner.** Its dropdown is a `Dialog` built from the context that
inflated the view; on the plugin context that is `BadTokenException` and **ATAK
dies**. Use a `TakwerxButton` showing the current value, opening an
`AlertDialog.Builder(mapView.getContext()).setSingleChoiceItems(...)`. Same rule for
every dialog and toast: **MapView context, never plugin context**.

**A ListView cannot live inside a ScrollView.** If a panel's controls are taller
than the pane, put them in the list's `addHeaderView()` so the whole panel is one
scroller — and offset click positions by `getHeaderViewsCount()`.

**Label sections, and put counts on filters.** Small caps headings (10sp,
`textAllCaps`, `alpha=0.6`) over each group. A filter states what it will cost
before it is used — `Video (1,013)`, not `Video` — otherwise the only way to learn
what a control does is to toggle it and watch a total move.

**Distances follow ATAK, never a hardcoded unit.** Read `rab_rng_units_pref` and
format through `SpanUtilities.formatType(type, metres, Span.METER)`. Note that
`Span.ENGLISH = 0` and `METRIC = 1` — assuming the obvious ordering gets it exactly
backwards. For a fixed list of values pin the large unit instead, or 800 m appears
as "2624 ft" beside entries in miles.

**Say what is not being shown.** If a view is truncated or gated, the panel says so
in words the operator can act on — "map shows nearest 300, zoom in" — because a
silently trimmed map reads as the whole picture.

## The SDK lives outside this repo

- Path: `~/atak-sdk/ATAK-CIV-<version>/` (currently `ATAK-CIV-5.6.0.8`).
- Contains `main.jar` (the ATAK API you compile against), `atak-gradle-takdev.jar`
  (the Gradle plugin), `android_keystore` (shared dev signing key), `atak.apk`
  (the matching ATAK build to sideload), `ATAK_Plugin_Development_Guide.pdf`, and
  `samples/` — 28 worked examples. **Read the sample before inventing an approach:**
  `plugintemplate` (the skeleton), `helloworld` (broad API tour), plus targeted ones —
  `cotinjector`, `importexportexample`, `videooverlay`, `radialmenudemo`, `hellojni`,
  `sensortester`, `customtiles`, `selfmarkerdata`, `munition-consumer`.

**HARD RULE 1 — never commit SDK artifacts.** The CIV SDK license forbids copying,
publishing, or distributing the SDK. `main.jar`, `atak.apk`, `atak-gradle-takdev.jar`,
`atak-javadoc.jar` and `android_keystore` are gitignored and must stay that way, in
public *and* private repos. Each plugin reaches the SDK through `sdk.path` in its
gitignored `local.properties`.

The line is *SDK binaries vs. derivative work*: the same license grants the right "to use
the TAK-SDK and to derive new works or applications based on the TAK-SDK", so plugin code
scaffolded from `samples/plugintemplate` is ours to commit and publish. The SDK's own
distributable artifacts are not.

Consequence: **GitHub Actions cannot build these plugins today.** CI would need the SDK,
which we may not ship. The supported CI path is `takrepo.url/user/password`
(artifacts.tak.gov Artifactory) as repo secrets — wire that up if/when a tak.gov
Artifactory account exists. Do not "solve" CI by committing the SDK.

## Toolchain (already installed on the dev Mac)

- JDK 17 — `/opt/homebrew/opt/openjdk@17` (`JAVA_HOME`)
- Android SDK — `/opt/homebrew/share/android-commandlinetools` (`ANDROID_HOME`),
  with `platforms;android-36`, `build-tools;36.0.0`, `platform-tools` (adb)
- Android Studio — `/Applications/Android Studio.app`
- Gradle comes from each project's wrapper (`./gradlew`, currently 8.14.3)

`source scripts/env.sh` exports the above if a shell lacks them.

## Remote dev: operator away with the phone

When the operator is not at the Studio, the test phone is usually plugged into
their MacBook and reached over NetBird: the MacBook runs the adb server, the
Studio's adb points at it via `ADB_SERVER_SOCKET`. **Read
`../atak-plugins-notes/docs/DEVICE-SETUP-remote.md` before touching adb in that
situation** — it has the exact commands, the address, and the failure table.
Shell state does not persist between Bash calls: export `ADB_SERVER_SOCKET`
in every adb-touching command. With it set, only the MacBook's devices are
visible (the Studio's emulator disappears); unset it for emulator work.

## Creating a plugin

```bash
./scripts/new-plugin.sh <name> "Display Name"    # name: lowercase letters/digits only
```

Copies `plugintemplate` out of the SDK, renames the package/class/descriptor, sets
`rootProject.name`, and writes a gitignored `local.properties`.

**Plugin names are letters and digits only — no dashes, no underscores.** Capitals are
fine (`UnitTracker`). The release build emits `-repackageclasses atakplugin.${rootProject.name}`
into proguard; a dash there is an invalid Java package and the release build fails (debug
builds pass, so this only bites at submission time).

The name is the directory, `rootProject.name`, the proguard descriptor, **and the APK name
tak.gov produces from a source submission**. It is the plugin's public identity — pick it
deliberately, renaming later is disruptive.

## Build / install cycle

```bash
cd plugins/<name>
./gradlew assembleCivDebug                       # civ = the CIV flavor; also mil/gov/xyz
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-*.apk
```

ATAK must already be installed on the device and be the **same version** the plugin
targets. Load the plugin from ATAK's Plugins manager. `~/atak-sdk/<version>/atak.apk`
is the matching ATAK build.

In Android Studio, set Build → Select Build Variants explicitly — it defaults to the
alphabetically-first flavor, which is not `civ` once other flavors exist.

## Build-file invariants — do not "clean these up"

The template's `app/build.gradle` carries load-bearing config. Removing any of it
produces a plugin that builds fine and then refuses to load:

- `bundle { storeArchive { enable = false } }` — required or TAK's inner signing check fails.
- `packagingOptions { jniLibs.useLegacyPackaging true }` — required for native libraries.
- The `com.atakmap.app.component` activity in `AndroidManifest.xml` — ATAK ≥4.6.0.2 uses
  it for plugin discovery. Delete it and the plugin is invisible.
- `<meta-data android:name="plugin-api" android:value="${atakApiVersion}"/>` — resolves to
  `com.atakmap.app@<ATAK_VERSION>.<FLAVOR>`. This must match the target ATAK build; a
  mismatch means ATAK will not load the plugin.
- `archivesBaseName` / `getVersionCode()` / `getVersionName()` logic — the SDK explicitly
  asks developers not to change these, and the third-party publication pipeline expects
  the resulting `ATAK-Plugin-<name>-<ver>-<gitsha>-<atakver>.apk` naming.

## Release builds differ from debug builds — test the release APK

`release` enables minify + proguard. Known breakages that do not appear in debug:

- **Lambdas break under release proguard** (documented in the SDK README). Prefer anonymous
  classes in code paths that ship.
- Reflection, `Class.forName`, and resource-by-name lookups need proguard keep rules
  (`app/proguard-gradle.txt`, plus the SDK's `proguard-release-keep.txt` as reference).

Never submit or field a plugin that has only been run as `civDebug`.

**Test the release build BEFORE submitting, not after.** Waiting for tak.gov to sign
an APK to find out whether the plugin works turns every bug into a full round trip —
Map Depot burned 0.1 through 0.4 in a day that way, and 0.4's signed APKs were thrown
away because the manual could not be opened. You do not need tak.gov to test any of
this: `assembleCivRelease` runs the same minify and proguard locally, signed with the
SDK's shared dev keystore.

```bash
cd plugins/<Name>
./gradlew assembleCivRelease -PbuildManual        # same proguard, manual included
adb install -r app/build/outputs/apk/civ/release/ATAK-Plugin-*.apk
```

`-PbuildManual` compiles `docs/user_manual/` with a local typst, so the manual is in
the APK and can be **opened on the device** — the only way to catch a manual that has
no preferences entry to reach it. The signed build differs only in who signed it, so
what passes here passes there. The tak.gov-signed APK still gets a load check when it
arrives, but it should not be where bugs are first discovered.

The local PDF lands in `app/src/main/assets/usermanual.pdf`, which is gitignored and
excluded from the submission zip — tak.gov builds its own.

**A tak.gov-signed APK cannot load on the SDK's development ATAK, and a locally
built one cannot load on official ATAK.** They are mirror images and neither is
broken. Official ATAK is obfuscated, so `gov.tak.api.plugin.IServiceController` is
`gov.tak.api.plugin.a` there; tak.gov builds plugins to match it. The SDK's
`atak.apk` is a dev build with the real names, so a signed plugin fails on it with
`ClassNotFoundException: Didn't find class "gov.tak.api.plugin.a"` and
`failed to load extension`. That reads exactly like a broken release and is not one.

Consequence for testing, and it is not optional:

- **Locally built APKs** (debug or `assembleCivRelease`) — test on a device running
  the SDK's `atak.apk`. That is where proguard breakage shows up.
- **tak.gov-signed APKs** — test ONLY on a device running **official** ATAK from
  tak.gov or the Play Store. A dev-build device can never validate one.

Check which a device has before drawing conclusions: if `versionName` matches the
SDK's `atak.apk` exactly, including the build hash in brackets, it is the dev build.

```bash
aapt2 dump badging "$ATAK_SDK/atak.apk" | grep versionName    # e.g. 5.8.0.3 (4f67063)
adb shell dumpsys package com.atakmap.app.civ | grep versionName
```

**If the plugin downloads from a catalog, verify the catalog against the servers
before shipping.** Reading the catalog is not the same as asking whether anything
will serve it, and the interesting failures are silent: `data.fs.usda.gov` answers
`204 No Content` — no body, no error — for a map it does not hold, and ATAK's own
downloader would report that as a generic failure. Map Depot 0.5 shipped with all 173
whole-forest maps unreachable because the plugin asked for the wrong `seriesType`, and
that was found by a user rather than by us.

```bash
../atak-plugins-notes/tools/verify_packages.py     # 832 packages, ~2 min, non-zero on failure
```

It builds each URL the way the plugin does and asks for one byte, so it is cheap
enough to run before every submission and after every catalog regeneration. Keep it
mirroring the plugin's URL construction — a verifier that builds URLs differently
proves nothing about what users get.

## ATAK version targeting — the silent-failure trap

Officially built plugins are **version-matched to the ATAK release they were built
against**. ATAK's own obfuscation mapping changes between releases, so a plugin built for
5.6 can fail on official 5.7 while working fine against dev/SDK builds — the failure does
not look like a version problem.

- `ext.ATAK_VERSION` in `app/build.gradle` sets the target.
- Request a build artifact for **every** ATAK version the fleet runs. One APK is not
  enough once customers are on mixed versions.
- Prefer the stable `gov.tak.api.*` classes over `com.atakmap.android.*` internals wherever
  an equivalent exists — the stable API is not obfuscated and survives version changes.
  This is a design rule, not a preference: it decides whether a plugin survives an ATAK
  upgrade.

## Signing

- **Debug/dev:** the SDK's shared `android_keystore` (`tnttnt` / `wintec_mapping`). It is
  public and in every SDK download — it is not a secret and must never sign a public release.
- **Officially published plugins are built and signed by tak.gov** from the source zip, so
  no takwerx key is involved in that path.
- A takwerx release keystore is only needed for APKs we distribute **ourselves** (direct
  sideload, MDM push). If we go that way: Android requires every update to be signed by the
  same key as the installed version, so generate it once, back it up, and custody it
  outside the repo. Record custody in the notes repo, never the key.

## Publication — tak.gov builds from SOURCE, not from our APK

The submission is a **zip of the source tree**; tak.gov runs `./gradlew` itself and
produces the APK. Do not submit a built APK.

```bash
./scripts/submission-zip.sh <PluginName>      # builds dist/<PluginName>.zip and verifies it
```

The script encodes the pipeline's requirements and checks them, because each one is a
rejected or silently-broken submission:

- Every path lives under a single `<PluginName>/` root, zipped from the **parent** dir.
- `gradle/wrapper/gradle-wrapper.jar` **must** be included — without it tak.gov's
  `./gradlew` cannot bootstrap (`Could not find or load main class …GradleWrapperMain`).
- `.takdev/`, `app/libs/` (the ~30 MB SDK `main.jar`), build output,
  `local.properties` and any keystore **must not** be included. tak.gov resolves the SDK
  itself. Without a manual the zip is ~100–300 KB; megabytes means something is wrong.
- `docs/user_manual/` **is** included when it exists, and is the one exception to the
  docs rule. `gradle/typst.gradle` compiles it to `app/src/main/assets/usermanual.pdf`
  when `ATAK_CI=1`, which is how the manual reaches the plugin — so tak.gov needs the
  source, not a built PDF. It carries its own fonts and title art, which lifts the zip
  to a few MB; the size gate moves with it. Everything else under `docs/` stays out.
  Verify the manual against tak.gov's own typst version (0.13.1, pinned in
  `typst.gradle`), not whatever is installed locally — the clean-extract build does not
  run typst, because it builds without `ATAK_CI`.

**A manual in `assets/` is unreachable.** Building the PDF is half the job: ATAK
surfaces a plugin's documentation through its **Tool Preferences** entry, so a plugin
without one ships the manual inside the APK with no way for anyone to open it. This
shipped once, undetected, because the PDF *was* in the APK. Three pieces are required
(`samples/dsmmanager` is the reference):

- `res/xml/preferences.xml` with a `com.atakmap.android.gui.PanPreference` keyed `manual`
- a fragment extending `com.atakmap.android.preference.PluginPreferenceFragment` whose
  click handler calls `PdfHelper.extractAndShow(pluginContext, getActivity(),
  "usermanual.pdf", <extract path>, true)`
- `ToolsPreferenceFragment.register(new ToolPreference(title, summary, key, icon,
  fragment))` on plugin start, and `unregister(key)` on stop

Check it on a device by opening Settings → Tool Preferences, not by unzipping the APK.
- `template.local.properties` (placeholders only) is included; the real `local.properties`
  never is.
- `proguard-gradle-repackage.txt` must carry a plugin-specific descriptor
  (`-repackageclasses atakplugin.<PluginName>`), and the proguard **User Section** must
  keep this plugin's own package (`-keep class com.atakmap.android.<pkg>.** { *; }`).
  A keep rule inherited from a copied project protects nothing and the release build
  obfuscates classes the plugin loader needs. `new-plugin.sh` writes both correctly.
- `assembleCivRelease` must be a defined target, and the `com.atakmap.app.component`
  discovery activity must be in the manifest.

The script's last check extracts the zip to a clean directory and builds it — a zip that
does not build from a clean extract will not build on tak.gov.

### Point of contact — recorded once, injected automatically

tak.gov requires a contact address in the submission README. The public repo must
never carry one (publish-scrub blocks email addresses, and the same README is
subtree-pushed to the plugin's public repo verbatim). Both hold only because the
address never enters the tracked tree at all:

- It lives in the **private notes repo**, one line in
  `../atak-plugins-notes/submission-poc.txt` — the address itself is recorded
  there and deliberately does not appear anywhere in this repo.
- `submission-zip.sh` reads it and rewrites the README **inside the zip**, after
  the tracked tree has been zipped, then passes it to the scrub as `POC_ALLOW` so
  the zip's own scrub does not fail on the address it was just told to add.
- `POC_ALLOW` allows that one literal, escaped, in that one run. Any other address,
  and every other scrub category, still fails. Never widen it, and never set it by
  hand to get a scrub to pass.

Do not type the address into a README, a commit, or any file in this repo. If it
ever needs to change, change that one line in the notes repo — nothing else.

`README.md` keeps the SDK's template headings — PURPOSE AND CAPABILITIES, STATUS, POINT OF
CONTACTS, **PORTS REQUIRED** (used for ATO/security review), EQUIPMENT REQUIRED/SUPPORTED,
COMPILATION, DEVELOPER NOTES — and it *is* part of the zip. So is
`docs/user_manual/`, which tak.gov compiles into the plugin (see the point above);
the rest of `docs/` is not. `gradle/typst.gradle` only builds the PDF when
`ATAK_CI=1`, and degrades to a warning when the manual is absent, so a plugin
without one still builds.

## Publish scrub (hard stop) — nothing leaves this machine unscanned

This repo is public, and so is every branch of it. Before **any** of these —
`git push` to `takwerx/atak-plugins`, a tak.gov submission zip, a published guide
or artifact, a GitHub release — run the scrub and get a PASS:

```bash
./scripts/publish-scrub.sh                 # tracked tree (what a push publishes)
./scripts/publish-scrub.sh <dir>           # an extracted zip or a docs tree
./scripts/publish-scrub.sh --file <path>   # one file, e.g. an artifact HTML
```

It fails on: personal identifiers (email addresses, phone numbers), machine-local
paths (`/Users/...`, `/home/...`), real-looking IP addresses, credentials and
keys (password/token/api-key literals, private-key blocks, AWS/GitHub/Slack
tokens, `takrepo.user/password` values), SDK binaries and signing material
(`main.jar`, `atak.apk`, `android_keystore`, `*.jks/*.keystore/*.p12`,
`local.properties`), build output and data packs, and every literal in the
**private** denylist `../atak-plugins-notes/publish-scrub.denylist` (the
operator's own addresses, device serials — things that cannot be listed in a
public file). Allowlisted on purpose: the SDK's shared dev keystore password,
`noreply@anthropic.com`, schema URLs, loopback addresses.

`.claude/hooks/publish-guard.sh` (wired in `.claude/settings.json`) runs the scrub
automatically before any `git push`, `gh release`, or `submission-zip.sh` aimed
at the public repo and **blocks on findings**. Do not work around a block; fix the
content or move it to the private notes repo. `submission-zip.sh` also scrubs the
extracted zip. Artifact publishes are tool calls the hook cannot see — run
`--file` on the HTML yourself first.

Policy the scrub enforces, in words:

- **No personal contact details in public.** Point of contact is name + org +
  the repository issue tracker. The tak.gov submission README additionally needs a
  real address; that is handled automatically and must never be done by hand — see
  **Point of contact** under Publication.
- **Screenshots are reviewed by eye** before commit: callsigns, coordinates,
  names, faces, plates, server addresses. The scrub cannot read pictures.
- **Sensitive engineering detail lives in the private notes repo**, never here —
  device serials, test locations, credentials custody, hashes of signed builds.
- The notes repo is where a denylist entry is added the moment something
  sensitive shows up anywhere; the scrub then holds the line mechanically.

## Process rules inherited from infra-TAK

- Plan-first for anything beyond a hot fix — PLAN doc in `../atak-plugins-notes/docs/`.
- Write a HANDOFF in `../atak-plugins-notes/docs/HANDOFF-<YYYY-MM-DD>.md` at the end of any
  chat longer than ~10 turns.
- Security scan (`/module-scan`-class review) before a plugin is fielded or published, and
  again on any version bump of vendored third-party code.
- **`/ship` is the ONLY path to `main`, a tag, or a GitHub Release.** The
  PreToolUse hook `.claude/hooks/git-guard.sh` mechanically blocks merge-to-main,
  `git tag`, pushes of main/tags, and `gh release create`; `/ship` runs pre-flight
  (publish scrub, device-verification evidence, security scan, zips, open issues,
  commit scan), presents the ship prompt, and unlocks the guard for 30 minutes
  after an explicit "Ship it". Never create `.claude/.ship-authorized` outside
  `/ship`. General approval given before the prompt does not count.
