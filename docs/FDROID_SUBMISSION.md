# F-Droid submission notes for Readwide 1.0.17

This document records the project-side preparation for an F-Droid Data merge request.

## App identity

- App name: Readwide
- Android application ID: `com.readwide.manager`
- Version name: `1.0.17`
- Version code: `10017`
- First-party license: Apache-2.0
- Source repository: `https://github.com/k1717/Readwide`

The Android application ID is `com.readwide.manager` (since 1.0.4). The F-Droid build is compiled from source and signed with F-Droid's key, so it does not install over a self-installed GitHub-release APK (signed with the project's own key) or an older `com.textview.reader` build — install the F-Droid build fresh and transfer data with the in-app JSON backup export/import.

## Draft metadata

A draft metadata file is included at:

```text
fdroid/metadata/com.readwide.manager.yml
```

For submission, copy it into the F-Droid Data repository as:

```text
metadata/com.readwide.manager.yml
```

Create and push the immutable release tag before opening the merge request:

```text
v1.0.17
```

The checked-in metadata file is only a historical mirror through `1.0.13`; do not copy it over current fdroiddata. After `v1.0.17` is pushed, start from current upstream metadata, add only the `1.0.17` block, set its `commit` to the full 40-character hash that the final tag points to, and then update `CurrentVersion: 1.0.17` / `CurrentVersionCode: 10017`. A commented template may be kept locally as guidance but must not be activated with a guessed or abbreviated hash. Because `UpdateCheckMode: Tags` and `AutoUpdateMode: Version` are set, F-Droid can also detect the tag and propose the build entry automatically.

## F-Droid-facing baseline

The default build is intended to be reviewed as a local-first FOSS app:

- First-party source is Apache-2.0.
- The default manifest does not request `INTERNET`.
- No ads, analytics, telemetry SDK, Firebase, Google Play Services dependency, account system, cloud sync, developer-operated upload backend, or app-network update checker is included.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- Broad file access is requested because the app is a local reader and file browser that works with user-selected folders, documents, images, and archives.
- `MANAGE_EXTERNAL_STORAGE` is requested for general local file browsing on Android versions where scoped-storage permissions alone cannot implement it. It is used only for local browse/open/copy/move/delete/extract/compress of user-selected files, and is not paired with `INTERNET` or any developer-operated upload path.
- The persisted `ACTION_OPEN_DOCUMENT_TREE` browser needs no additional permission and works without broad storage access. It is read-oriented; raw-path-only mutation and recursive-search operations remain visibly confined to the fully authorized raw browser.
- The main activity exports an `ACTION_VIEW` intent filter (with the `BROWSABLE` category and document/image MIME types, no `http`/`https` scheme) so files can be opened in Readwide from a browser, messenger, file manager, or document provider. A file opened this way is copied into an app-private `opened_files` cache with display-name sanitization, a canonical-path containment check, a 2 GB per-file copy limit, and cache pruning before and after the copy; provider `query`/`getType` exceptions are caught. No network access is involved.
- Default builds do not bundle Junrar or RARLAB UnRAR-license code.
- HWP/HWPX support uses Apache-2.0 Java libraries (`hwplib`, `hwpxlib`) and is text-first/read-only.
- Release signing is conditional; if private signing environment variables are absent, `assembleRelease` should produce an unsigned release artifact suitable for source-builder workflows.

## Gradle wrapper verification

The source keeps the standard Gradle 9.4.1 wrapper files. This source tree aligns the wrapper JAR with the configured distribution and pins the distribution checksum:

```text
gradle-wrapper.jar SHA-256: 55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
gradle-9.4.1-bin.zip SHA-256: 2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
```

Both values are the official Gradle 9.4.1 checksums. F-Droid also verifies wrapper JARs against known-good official hashes and builds with trusted tooling, so the metadata does not need an `rm` rule for the wrapper. The release maintainer should still verify these values before tagging.

## Build command

The metadata builds from the `app` module with the normal Gradle release build:

```yaml
subdir: app
gradle:
  - yes
```

Manual local check for the no-private-keystore path:

```bash
unset READWIDE_KEYSTORE_PATH READWIDE_KEYSTORE_PASSWORD READWIDE_KEY_ALIAS READWIDE_KEY_PASSWORD TEXTVIEW_KEYSTORE_PATH TEXTVIEW_KEYSTORE_PASSWORD TEXTVIEW_KEY_ALIAS TEXTVIEW_KEY_PASSWORD
./gradlew clean assembleRelease
```

## Fastlane metadata

Source metadata is included under:

```text
fastlane/metadata/android/
```

Current locales:

- `en-US`
- `ko-KR`

These provide title, short description, full description, and versionCode `10017` changelog text.

## Conservative support wording for review

Use conservative wording in the merge request:

- RAR/CBR support is limited and not complete.
- Encrypted RAR, broad split RAR, SFX, VM-filtered RAR, and full RAR compatibility are not claimed.
- HWP/HWPX support is text-first reading only; no Hancom layout parity, editing, writing, cloud/server conversion, or password/encrypted HWP support is claimed.
- Legacy `.doc` files have basic read-only rendering through a self-contained pure-Java parser; layout fidelity is limited compared with OOXML `.docx`.

## Files reviewers should inspect

- `README.md`
- `PRIVACY.md`
- `THIRD_PARTY_NOTICES.md`
- `docs/FOSS_STATUS.md`
- `docs/LICENSE_REPORT_READWIDE_1_0_17.md`
- `docs/SBOM_READWIDE_1_0_17.spdx.json`
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md`
- `fdroid/metadata/com.readwide.manager.yml`

## Native dependency provenance (for the merge request)

The Android runtime native backend is built entirely from source checked into the release tree. No prebuilt libarchive `.aar` or `.so` is used.

- `third_party/libarchive-android` — Android wrapper source at commit `3a592be028c7be41847f667570bd343c0010bd9d` (Apache-2.0), advanced by Readwide to official libarchive commit `27cbc7827172698143e440801fc0ba39ccb4f1f5` (3.8.9), with the exact bzip2, XZ Utils, LZ4, Zstandard, and Mbed TLS inputs recorded in `UPSTREAM.md`. Gradle, NDK 29.0.14206865, and CMake 3.22.1 compile it for `armeabi-v7a` and `arm64-v8a`.

Readwide packages `app/src/main/assets/open_source_licenses/libarchive_android_and_codecs.txt`, derived from those pinned source revisions, so the APK retains the applicable copyright, redistribution, and warranty-disclaimer terms. The corresponding source licenses also remain beside their components in `third_party/libarchive-android`.

`com.github.luben:zstd-jni:1.5.7-9` remains only under `testImplementation` so plain-JVM archive fixtures can decode Zstandard. Its desktop native resources are not part of the Android release APK and are not required by the F-Droid release assembly path.

All APK-bundled native components are permissive/FOSS-compatible, and no copyleft option is selected: Zstandard is used under its BSD option and Mbed TLS under Apache-2.0. For the 1.0.17 fdroiddata build block, declare `ndk: 29.0.14206865` and `buildjni: no`; Gradle/CMake performs the JNI build from the checked-in source.

## Remaining submitter tasks

- Confirm a clean network-enabled Gradle build from the tagged source.
- Confirm the submitted build's `commit` field is the full 40-character hash of the final `v1.0.17` release commit. Start from current upstream metadata and add only the version actually submitted.
- Confirm no optional local jars are present in `app/libs`.
- Confirm the built APK contains `assets/open_source_licenses/libarchive_android_and_codecs.txt`. Keep the `zstd-jni` notice with source/test materials; it is not shipped in the APK.
