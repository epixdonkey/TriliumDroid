# TriliumDroid — custom fork

Unofficial Android port of [Trilium Notes](https://github.com/TriliumNext/Trilium),
based on [FliegendeWurst/TriliumDroid](https://github.com/FliegendeWurst/TriliumDroid).

This fork adds personal quality-of-life features on top of upstream and tracks
compatibility with current TriliumNext servers. See **[CHANGELOG.md](./CHANGELOG.md)**
for the complete list of changes.

> **Fork highlights**
> - Unlocked calendar: every day is selectable, empty days are created on demand
>   with proper `#yearNote` / `#monthNote` / `#dateNote` labels
> - Share-to-journal: text shared from other apps lands as a child of today's
>   calendar note instead of the inbox
> - Standalone quick-note home screen widget, with instant editor opening and
>   clean discard of empty notes
> - Biometric app lock (fingerprint / device credential)
> - Customizable accent colors and gradient theme engine
> - Material Symbols icon pack for notes (in addition to boxicons)
> - Synchronization with TriliumNext v0.107.0 (sync version 41, database version 242)
> - Hardened sync: pull chunks are applied in single SQLite transactions, automatic
>   retries on dropped connections, self-healing consistency pass after every sync

## Building from source

Any machine with Docker works — no local Android SDK required:

```bash
mkdir -p ../.android-build-cache/gradle ../.android-build-cache/sdk
docker run --rm \
  -v "$PWD:/workspace" \
  -v "$PWD/../.android-build-cache/gradle:/root/.gradle" \
  -v "$PWD/../.android-build-cache/sdk:/opt/android-sdk" \
  -w /workspace eclipse-temurin:21-jdk bash -c '
set -e
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
if [ ! -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  apt-get update && apt-get install -y unzip curl
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -sSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdtools.zip
  unzip -q /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm /tmp/cmdtools.zip
fi
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
if [ ! -f /tmp/gradle-9.4.1/bin/gradle ]; then
  curl -sSL https://services.gradle.org/distributions/gradle-9.4.1-bin.zip -o /tmp/gradle.zip
  unzip -q /tmp/gradle.zip -d /tmp && rm /tmp/gradle.zip
fi
/tmp/gradle-9.4.1/bin/gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
'
```

The debug APK installs alongside the original app (package id suffix `.debug`)
and appears in `app/build/outputs/apk/debug/`.

Incorporating upstream changes:

```bash
git fetch upstream
git rebase upstream/master
```

---

# Trilium Notes for Android ![test workflow](https://github.com/FliegendeWurst/TriliumDroid/actions/workflows/test.yaml/badge.svg) <a href="https://hosted.weblate.org/engage/triliumdroid/"><img src="https://hosted.weblate.org/widget/triliumdroid/app/svg-badge.svg" alt="translation status" /></a> <a href="https://matrix.to/#/#triliumdroid:matrix.org" title="link to Matrix channel"><img src="https://img.shields.io/matrix/triliumdroid:matrix.org?server_fqdn=matrix.org&label=matrix" /></a>

<img align="right" width="200" src="./fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" />

Unofficial port of [Trilium Notes](https://github.com/TriliumNext/Trilium) to Android.

[<img src="https://github.com/user-attachments/assets/38acb15c-dbe2-4bc1-9f8b-1539654d3641" width="170">](https://apt.izzysoft.de/fdroid/index/apk/eu.fliegendewurst.triliumdroid)

## Features

- Synchronisation with sync server / desktop app
- Notes can be arranged into arbitrarily deep tree, where single notes can be placed into multiple places in the tree
- WYSIWYG note editor based on [CKEditor](https://github.com/ckeditor/ckeditor5)
- Fast and easy navigation between notes
- Automatic note versioning
- Note attributes and relations for organization, querying and advanced scripting
- Note encryption with per-note granularity
- Canvas notes powered by [Excalidraw](https://excalidraw.com/)
- Geo map notes powered by Leaflet using OSM data
- Note map view (context-based / all notes)
- Scripting API to automate tasks or send notifications
- In-app user guide based on TriliumNext documentation

More features are planned, see the [issue tracker](https://github.com/FliegendeWurst/TriliumDroid/issues?q=sort%3Aupdated-desc%20is%3Aissue%20is%3Aopen%20label%3Aenhancement) for a full list.

### Android integration

- Note content can be shared to other apps
- Received shared content is saved as new note
- Translated UI: English, German, Turkish, Chinese (Simplified Han script)
- Light/Dark Theme following system preference

### Requirements

- Trilium sync server running version 0.97+
- Android 7.0+

For using the app without a sync server, follow [this issue](https://github.com/FliegendeWurst/TriliumDroid/issues/75).  
If you're still using Android 6.0 or older, see [this issue](https://github.com/FliegendeWurst/TriliumDroid/issues/72).  
For other ways to use Trilium on mobile, see https://github.com/TriliumNext/Trilium/issues/4962.

## Screenshots

| Note tree  | Free-form diagrams | Full-screen navigation | Note icon selection | Jump-to-note dialog |
| ------------- | ------------- | --- | --- | --- |
| ![note tree](./app/test/screenshots/InitialSyncTest_test_010_initialSync_1.png) | ![note tree](./app/test/screenshots/InitialSyncTest_test_011_canvas_1.png) | ![navigation](./app/test/screenshots/InitialSyncTest_test_030_noteNavigation_1.png) | ![icons](./app/test/screenshots/InitialSyncTest_test_038_noteIcon_1.png) | ![jump](./app/test/screenshots/InitialSyncTest_test_011_jumpToNote_2.png) |
| collapsible sub-trees | [Excalidraw](https://excalidraw.com/) | | [boxicons](https://boxicons.com/) | | |

## Installation

If you already have [F-Droid](https://f-droid.org/) installed, you can add [IzzyOnDroid's F-Droid repository](https://apt.izzysoft.de/fdroid/) in F-Droid's settings. Then install the app like any other.

Or if you prefer to install the app directly:

1. Download the APK from [IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/eu.fliegendewurst.triliumdroid).
2. Install the APK. You need to enable installing apps from unknown sources.
3. Open the app and configure your sync server (hostname, port, password).
4. Go back, wait until sync is finished.

Several parts of the user interface are configurable, check the app settings for more details.
To edit notes, use the edit button in the toolbar.

## Contribute

Please report bugs and missing features, either using [Github issues](https://github.com/FliegendeWurst/TriliumDroid/issues) or on Matrix: [#triliumdroid:matrix.org](https://matrix.to/#/#triliumdroid:matrix.org).

Translations are done via [Weblate](https://hosted.weblate.org/projects/triliumdroid/app/).

If you're any good at Android app development using Kotlin, feel free to contribute code :)

### Testing

The app is tested using both unit tests and emulator tests ([details](./app/test/release-testing.md)).

## License

Copyright © 2023 Arne Keller

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.

Parts of this program are derived from zadam's [Trilium Notes](https://github.com/zadam/trilium/), the AGPL-3.0 applies.  
Parts of this program are derived from Elian Doran's [TriliumNext Notes](https://github.com/TriliumNext/Notes), the AGPL-3.0 applies.  
The MIT License applies to the [boxicons](https://boxicons.com/) included in the project (see `boxicons_LICENSE.txt`).  
The GPL-3.0 license applies to the styles derived from [Simple-Commons](https://github.com/SimpleMobileTools/Simple-Commons).  
The MIT license applies to the bundled [Excalidraw](https://github.com/excalidraw/excalidraw), other free licenses apply to other bundled web libraries (see notices in `app/src/main/assets/web.zip`).  
The GPL-2.0 (or later) license applies to the included [CKEditor](https://github.com/ckeditor/ckeditor5) (`app/src/main/assets/ckeditor.js`).  
For other included libraries, their respective license applies.  
