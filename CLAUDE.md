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
- `.takdev/`, `app/libs/` (the ~30 MB SDK `main.jar`), `docs/`, build output,
  `local.properties` and any keystore **must not** be included. tak.gov resolves the SDK
  itself. The zip is ~100–300 KB; megabytes means something is wrong.
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

`README.md` keeps the SDK's template headings — PURPOSE AND CAPABILITIES, STATUS, POINT OF
CONTACTS, **PORTS REQUIRED** (used for ATO/security review), EQUIPMENT REQUIRED/SUPPORTED,
COMPILATION, DEVELOPER NOTES — and it *is* part of the zip. `docs/` is not: keep
`docs/user_manual/*.typ` current for our own use, but it is excluded from the submission
(`gradle/typst.gradle` only builds the PDF when `ATAK_CI=1`, and degrades to a warning
when `docs/` is absent).

## Process rules inherited from infra-TAK

- Plan-first for anything beyond a hot fix — PLAN doc in `../atak-plugins-notes/docs/`.
- Write a HANDOFF in `../atak-plugins-notes/docs/HANDOFF-<YYYY-MM-DD>.md` at the end of any
  chat longer than ~10 turns.
- Security scan (`/module-scan`-class review) before a plugin is fielded or published, and
  again on any version bump of vendored third-party code.
- Never push to `main` / tag / release without explicit operator authorization.
