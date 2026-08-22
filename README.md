# atak-plugins

takwerx ATAK (Android Team Awareness Kit) plugins. One directory per plugin under
`plugins/`, each a standalone Gradle project built against the ATAK CIV SDK.

**Looking for a plugin to install?** Each plugin is published from its own
repository — README, user guide, downloads and issues all in one place:

| Plugin | What it does | Public repo (downloads, guide, issues) |
|---|---|---|
| PLSS Grid | Township, range and section overlay from BLM survey data | https://github.com/takwerx/plss-grid |

This repository is the development workspace: shared tooling, build rules, and
every plugin side by side. Each plugin directory is pushed out to its public repo
with `git subtree` on release.

Engineering notes, release plans and handoffs live in the private sibling repo
[`takwerx/atak-plugins-notes`](https://github.com/takwerx/atak-plugins-notes).

## Prerequisites

| Component | Version | Location on the dev Mac |
|---|---|---|
| JDK | 17 | `/opt/homebrew/opt/openjdk@17` |
| Android SDK | platform 36, build-tools 36.0.0, platform-tools | `/opt/homebrew/share/android-commandlinetools` |
| Android Studio | current | `/Applications/Android Studio.app` |
| ATAK CIV SDK | 5.6.0.8 | `~/atak-sdk/ATAK-CIV-5.6.0.8` |

The **ATAK SDK is not in this repo and never will be** — its license forbids
redistribution. Download it from [tak.gov](https://tak.gov) and unpack it to
`~/atak-sdk/ATAK-CIV-<version>/`. Each plugin points at it via `sdk.path` in a
gitignored `local.properties`.

```bash
source scripts/env.sh    # exports JAVA_HOME / ANDROID_HOME / ATAK_SDK for this shell
```

## Create a plugin

```bash
./scripts/new-plugin.sh unittracker "Unit Tracker"
```

Name must be **lowercase alphanumeric** (no dashes) — the release proguard step turns it
into a Java package name.

## Build and sideload

```bash
cd plugins/<name>
./gradlew assembleCivDebug
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-*.apk
```

The device needs the matching ATAK build first (`~/atak-sdk/<version>/atak.apk`), then
enable the plugin from ATAK's Plugins manager. A plugin whose `plugin-api` version does
not match the running ATAK will not load.

Release build (what actually gets published — proguard changes behavior, so test it):

```bash
./gradlew assembleCivRelease
```

## Repo layout

```
plugins/<name>/           standalone Gradle project (app/, gradle/, docs/, gradlew)
scripts/new-plugin.sh     scaffold a new plugin from the SDK template
scripts/env.sh            toolchain environment for a shell
CLAUDE.md                 development rules — read before changing build files
```

## Distribution

Plugins publish through TAK's third-party plugin pipeline. Keep each plugin's
`README.md` headings (including **PORTS REQUIRED**, used for ATO review) and its
`docs/user_manual/*.typ` source current — the pipeline builds the manual and expects the
release-signed APK with the SDK's standard `ATAK-Plugin-…` naming.
