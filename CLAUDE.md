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

**Plugin names are lowercase alphanumeric — no dashes, no underscores.** The template's
release build emits `-repackageclasses atakplugin.${rootProject.name}` into proguard;
a dash there is an invalid Java package and the release build fails (debug builds pass,
so this only bites at submission time).

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

## Signing

- **Debug/dev:** the SDK's shared `android_keystore` (`tnttnt` / `wintec_mapping`). It is
  public and in every SDK download — it is not a secret and must never sign a release.
- **Release:** a takwerx-owned keystore, held outside the repo and as a CI secret. Android
  requires every update to be signed by the **same** key as the installed version — losing
  the release key means users must uninstall/reinstall. Back it up before first release.

## Third-party publication (how these reach EUDs)

takwerx plugins publish through TAK's third-party plugin pipeline. Keep the submission
artifacts intact from day one rather than retrofitting them:

- `README.md` in each plugin keeps the SDK's template headings — PURPOSE AND CAPABILITIES,
  STATUS, POINT OF CONTACTS, **PORTS REQUIRED** (used for ATO/security review), EQUIPMENT
  REQUIRED/SUPPORTED, COMPILATION, DEVELOPER NOTES.
- `docs/user_manual/*.typ` is a typst source for the user manual PDF. `gradle/typst.gradle`
  compiles it into `assets/usermanual.pdf` **only when `ATAK_CI=1`**, so local builds skip
  it — keep the `.typ` current anyway; the pipeline builds it.
- Ship the **release-signed** APK, not a debug APK.

## Process rules inherited from infra-TAK

- Plan-first for anything beyond a hot fix — PLAN doc in `../atak-plugins-notes/docs/`.
- Write a HANDOFF in `../atak-plugins-notes/docs/HANDOFF-<YYYY-MM-DD>.md` at the end of any
  chat longer than ~10 turns.
- Security scan (`/module-scan`-class review) before a plugin is fielded or published, and
  again on any version bump of vendored third-party code.
- Never push to `main` / tag / release without explicit operator authorization.
