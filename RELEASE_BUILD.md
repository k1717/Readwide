# Public release build checklist

This source package is prepared for public GitHub distribution of Readwide 1.0.0, formerly TextView Reader.
The app version metadata remains:

```text
versionCode 10000
versionName "1.0.0"
```

## Keystore policy

Do not commit release signing files or passwords. The release build reads signing values from environment variables first and then from `~/.gradle/gradle.properties`:

```text
TEXTVIEW_KEYSTORE_PATH=/secure/path/release.keystore
TEXTVIEW_KEYSTORE_PASSWORD=...
TEXTVIEW_KEY_ALIAS=textview
TEXTVIEW_KEY_PASSWORD=...
```

Create the release keystore once and back it up securely:

```bash
keytool -genkeypair \
  -alias textview \
  -keyalg RSA -keysize 4096 \
  -validity 36500 \
  -keystore release.keystore \
  -dname "CN=your-alias, OU=Apps, O=your-project-name, L=City, ST=State, C=Country"
```

The distinguished name is visible in the APK certificate. Use values you are comfortable publishing.
Losing this keystore prevents normal updates to the same installed app package.

## Build commands

On Windows, the root helper script keeps signing passwords out of the source tree:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\build-release.ps1
```

By default it looks for:

```text
%USERPROFILE%\AndroidKeys\textview-release.jks
```

and uses the release key alias:

```text
textview
```

To use a different keystore path without editing the script:

```powershell
.\build-release.ps1 -KeystorePath "D:\secure\textview-release.jks" -KeyAlias "textview"
```

If the key password differs from the store password, set the four `TEXTVIEW_*` environment variables manually before invoking Gradle.
You can also let the helper prompt for a separate key password:

```powershell
.\build-release.ps1 -SeparateKeyPassword
```

For F-Droid or other reproducible source-build environments, release signing is intentionally conditional. If the four `TEXTVIEW_*` signing values are absent, `assembleRelease` builds an unsigned release artifact instead of failing on a missing local keystore. Local public release APKs should still be built with `build-release.ps1` or equivalent signing environment variables.

```bash
./gradlew clean
./gradlew testDebugUnitTest
./gradlew compileReleaseJavaWithJavac
./gradlew assembleRelease
```

The signed APK is expected at:

```text
app/build/outputs/apk/release/app-release.apk
```

## Release verification

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

aapt dump xmltree app/build/outputs/apk/release/app-release.apk AndroidManifest.xml \
  | grep -E "debuggable|usesCleartextTraffic|INTERNET"

strings app/build/outputs/apk/release/app-release.apk \
  | grep -E "^/(home|Users|builds)/" | sort -u

sha256sum app/build/outputs/apk/release/app-release.apk
```

Expected results:

- certificate is your release certificate, not `CN=Android Debug, O=Android, C=US`;
- `debuggable` is absent or false;
- `usesCleartextTraffic` is false;
- no `INTERNET` permission is present;
- no build-machine path appears in the APK strings output;
- the APK SHA-256 can be copied into the GitHub Release notes.

## Public-source notes

- Upload the contents of the extracted project folder, not the outer extracted folder itself.
- Before tagging from git, preserve executable bits with:

```bash
git update-index --chmod=+x gradlew
git update-index --chmod=+x scripts/clean_removed_sources.sh
```

- `*.keystore`, `*.jks`, `local.properties`, and build outputs are ignored by git.
- R8/minify and resource shrinking are enabled for release builds.
- 2.2.6 includes post-2.2.2 refactoring passes: browse-folder state/cache code is centralized in `MainBrowseStateController`, and archive browser/list/image-sequence plus archive create/extract planning code is split into focused helper classes.
- Apache Commons Compress notice now includes the ZIP fallback path in addition to 7z/TAR/single-stream archive handling; no new Maven dependency was added for this fallback.
- JUniversalChardet remains active for TXT encoding detection.
- TXT TTS uses Android platform TTS APIs and does not add a bundled third-party voice engine.
- Junrar is no longer bundled. Do not add RAR creation/compression paths or UnRAR-licensed fallback code to the default build.
- ALZ/EGG readers are first-party extraction code. Synthetic ALZ Store/Deflate fixtures verify parsing, output bytes, CRC rejection, and corrupt-output cleanup. EGG supports Store/Deflate/BZip2/AZO/LZMA in code, but real ESTsoft-created ALZ/EGG fixtures remain required before claiming broad compatibility. Encrypted, split, solid, and unverified legacy variants must fail cleanly.
- No Junrar/UnRAR-license fallback is bundled in the source package. The normal release uses the bundled libarchive-android AAR for limited RAR3/RAR4 normal non-encrypted compressed fallback and should not require manual NDK/CMake setup.
- Network cleartext is disabled, and WebView resource interception explicitly blocks non-local requests.
- Short feedback toasts are centralized in `ShortToast` with a roughly 700ms display window; longer user-facing warnings remain explicit long toasts where needed.

## F-Droid source-build notes

The repository includes `docs/FDROID_SUBMISSION.md`, `fdroid/metadata/com.textview.reader.yml`, and `fastlane/metadata/android/` as a starting point for an F-Droid Data merge request. Replace the placeholder commit in the draft metadata with the final immutable Git commit hash for the published `v1.0.0` tag. The metadata draft removes `gradle/wrapper/gradle-wrapper.jar` before F-Droid scanning/building, while the GitHub source keeps the wrapper for local developer convenience.

## Pre-upload source check

Before uploading through the GitHub web UI, confirm the extracted source tree does not include:

```text
.gradle/
.idea/
build/
app/build/
app/src/androidTest/assets/large_txt_real_fixture.txt
local.properties
*.apk
*.aab
*.apks
*.jks
*.keystore
*.pem
*.p12
.env
.env.*
secrets.properties
google-services.json
captures/
*.hprof
*.log
```

## Readwide 1.0.0 focused QA

Verify queued ZIP creation destination: queue a ZIP creation, navigate to another writable folder, run the pending create action, and confirm the ZIP is created in that current destination folder.

Verify the 2.2.5 multi-select delete progress path: select multiple files/folders, start Delete, pause the progress window, send it to Background, and confirm that the toolbar progress button is immediately visible and reopens the same paused operation without needing to enter another folder. Also confirm the same visibility is restored after leaving and returning to the app.


Verify drawer gestures before publishing: from the main screen, right-swipe should open the drawer from the intended broad main area; with the drawer open or partially open, a real left-swipe on the outside area should close it; a light outside tap should not be treated as a swipe-close; manual drags should settle fully open or fully closed instead of sticking half-open.

Verify the drawer bottom **Open File / Bookmarks / Settings** buttons: each action should start immediately, and the drawer should close in the background instead of delaying the action until after the close animation.

Before publishing this 2.2.5 source, verify image preview/detail policy on a large ZIP/CBZ: the selected image should open quickly, preview decode should remain bounded near the 12MP preview policy, zoom should request higher detail up to the 48MP detail policy, and paging should lazy-extract or prefetch adjacent images without viewer crashes.


Also verify Settings > Button / icon order for the main filter strip and TXT / EPUB-Word / PDF viewer controls, including reset-to-default and Settings return/apply behavior.

Verify large-folder browsing and search on a folder with thousands of files: progressive folder entries should appear before the final sorted list, sort changes should reorder the visible list without an app-not-responding pause, and both the main list and recent list right-edge drag scrollers should track the loaded item count.

Verify RAR/CBR behavior with explicit fixture categories: RAR5 and RAR4/RAR3 method-0 stored entries should list and extract; common normal non-encrypted RAR4/RAR3 compressed cases should attempt extraction through the bundled libarchive-android backend. Solid fixture passes are fixture-specific and must not be advertised as broad solid support. Encrypted compressed entries, compressed split volumes, and SFX wrappers should either pass a specific fixture or fail cleanly. RAR5 compressed/solid/encrypted extraction is outside the default FOSS build unless a separately audited decoder path is added. RAR creation/compression must remain unsupported.

Verify ALZ/EGG handling with small fixtures: `.alz` and `.egg` should appear under the archive filter and derive clean extraction-folder names. ALZ Store/Deflate synthetic fixtures should extract with CRC checks and corrupt-output cleanup; add real ESTsoft ALZ plus ALZ BZip2 Android fixtures when available. EGG Store/Deflate/BZip2/AZO/LZMA entries should extract with per-block CRC checks, but keep real ESTsoft EGG fixtures in release QA. Unsupported ALZ/EGG variants such as encrypted EGG, split/solid EGG, or malformed containers should fail cleanly without partial output.

Verify archive-engine routing with ZIP and 7z fixtures: production ZIP/CBZ should still list and extract through Zip4j, encrypted ZIP should require and accept the correct password, standard split ZIP should extract, non-encrypted Deflate64/BZip2/XZ/ZSTD ZIP fixtures should extract through the Apache Commons Compress fallback when Zip4j rejects the method, AES+XZ should fail cleanly as unsupported, and R8 should pass with the bundled zstd-jni dependency, and standard `.7z.001` / `.7z.002` plus `.cb7.001` / `.cb7.002` chains should list/extract through the concatenated seekable channel.

Verify RAR volume selection with `.part1.rar`/`.part2.rar` and `.rar`/`.r00` fixtures: selecting a later volume should resolve to the first volume for listing, while entries that actually span volumes should fail cleanly. Verify encrypted RAR headers request a password and encrypted payloads do not loop password prompts after a failed password-backed attempt.

Verify CBZ, CBR, CB7, and CBT comic opening from the main file list: if a saved archive image entry exists, the image reader should open directly and Back should return to the previous main/browser screen, not the archive internals.

Verify RAR5 encrypted stored-entry fixtures separately from compressed encrypted RARs: method-0 encrypted payloads should decrypt only with the correct password and then pass CRC validation, while encrypted headers, RAR4 encrypted files, compressed encrypted files, and split encrypted files should fail cleanly.

Verify stored split RAR fixtures separately: method-0 entries split across `.partN.rar` or `.rNN` sibling volumes should extract as one file; compressed or encrypted older split entries should route to libarchive-android when possible and otherwise fail cleanly. RAR5 split/multi-volume must not be documented as guaranteed.

Verify long copy/move and ZIP/TAR/7z extraction runs: the rounded progress window should update progress, pause/resume without UI blocking, cancel without leaving a completed destination, and continue after tapping Background.

Verify the 2.2.5/2.2.6 UI polish paths: color palette dialogs should open centered without a side-drop animation and update preview colors while dragging; image viewer launch from the main list or archive preview should fade/scale instead of hard-switching; TXT page labels should show `current / total` at page anchors and `current (line-in-page) / total` only for mid-page positions, including after bookmark restore.

Optional external archive fixture smoke tests can be run by placing the provided password ZIP, password 7z, password ZIPX, and `rar-test-files-master.zip` files in a local folder and setting:

```bash
TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR=/path/to/archive-fixtures
./gradlew testDebugUnitTest
```

The committed unit tests also include synthetic ALZ and EGG fixtures for Store/Deflate extraction and encrypted-entry failure classification, so the public test suite remains runnable without shipping private sample archives.

- Main file-list short-hold QA: hold a file/folder row briefly and confirm the regular action popup opens after about 200 ms, while the longer multi-select hold enters multi-select after about 800 ms.

## 2.2.5/2.2.6 viewer-return QA
- Open a file from a large folder, return from the viewer, and confirm the main folder list/scroll position is preserved without a full reload.
- Delete, move, add, or modify a file in the original folder while a viewer is open, then return and confirm the folder refreshes instead of preserving stale state. Include the TXT original-change output-file case because it creates a new file in the same folder.

## 2.2.5/2.2.6 browse-state QA

Before release, manually verify:

- Open a large folder, scroll to the middle, enter a subfolder, then press Back. If the original folder is unchanged and had fully loaded before navigation, the previous adapter list and RecyclerView position should restore without a full reload.
- Open folder A, scroll, enter folder B, scroll, return to A, then enter B again. Unchanged folders should restore list/scroll state instead of visibly reloading.
- Add, delete, rename, or modify a direct child in a cached folder, then return to it. The signature mismatch should force a fresh folder read.
- Change sort mode or hidden-file visibility, then return to a cached folder. The cache should be ignored so ordering and visibility stay correct.
- Open a cached large folder from a drawer shortcut or recent-folder row. The cached list should appear immediately; if the folder changed, it should refresh afterward.
- Open an uncached large folder from a drawer shortcut. The first visible list snapshot should appear quickly while the folder continues loading.
- Turn the screen off/on or press Home and return while browsing a folder. If the folder did not change, the visible list should remain without a rescan.
- Create/delete/rename/modify a file in the visible folder while away; returning should reload the folder and show the change.
- Switch a current-folder type filter such as General or Archive back to All; unchanged folders should restore the full list from cache.

## 2.2.6 FOSS release check

Before publishing the Readwide 1.0.0 source/APK as FOSS-friendly, verify:

- `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`, and `docs/FOSS_STATUS.md` are present.
- `app/libs` contains no optional decoder `.jar` files.
- `RarJunrarFallback.java`, `com.github.junrar`, and `junrar:` do not appear in compiled source/dependencies.
- The release APK contains only ARM Android native ABIs (`armeabi-v7a`, `arm64-v8a`) and no zstd-jni desktop resource binaries (`win/**`, `darwin/**`, `linux/**`, etc.).
- RAR support is described as limited extraction support, not complete RAR compatibility.

## 2.2.6 libarchive-android backend release check

The standard Readwide 1.0.0 release includes libarchive-android through the Maven dependency graph. Confirm that the AAR is resolved during Gradle sync, that `THIRD_PARTY_NOTICES.md` includes libarchive-android/libarchive, and that RAR3/RAR4 support is described as limited fallback support rather than complete RAR support.


## RAR backend release note

2.2.6 uses first-party stored-RAR handling plus the libarchive-android RAR3/RAR4 normal non-encrypted compressed fallback. Any solid-RAR pass is fixture-specific; do not broaden that into complete solid/encrypted/SFX/split/RAR5 support unless the exact path is tested on device and documented separately.


## Archive support matrix

Before publishing 2.2.6, check `docs/ARCHIVE_SUPPORT_MATRIX_2_2_6.md`. The default FOSS build should not be described as complete RAR support. Keep RAR3/RAR4 wording to common normal non-encrypted compressed fallback through libarchive-android, and keep RAR5 compressed/solid/encrypted outside default FOSS claims.
