# F-Droid submission notes for Readwide 1.0.18

This document records project-side preparation only. It is not a submitted 1.0.18
metadata file or evidence of a successful current build. See
[release handoff gates](RELEASE_READINESS_1_0_18.md).

## Source review — 2026-09-11

No obvious inclusion-policy blocker was found in this static source review.
This is a submission-readiness assessment, not F-Droid approval, a passing
`fdroid scanner` result, or proof that version 1.0.18 builds on its servers.
The maintainer's earlier successful build predates the latest timeout-field and
translation changes; the exact final snapshot still needs a release build.

Read-only checks of the [public app listing](https://f-droid.org/packages/com.readwide.manager/)
and [live upstream metadata](https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/com.readwide.manager.yml)
showed 1.0.17 / 10017, using commit
`1adcb9471235496307f20928e4ba94b8a74a7900`. The upstream file had no 1.0.18
build entry at the time of review. Do not replace it with the older local mirror.

The declared repositories are Google Maven, Maven Central and the Gradle plugin
portal. The only checked-in JAR is the official Gradle wrapper; no APK, AAR, native
shared-library prebuilt, optional app JAR or signing key was found in the source
tree. Both native modules compile checked-in C/CMake source. App version,
permissions, source license notices and EN/KR store metadata agree with the
current source. This does not inspect resolved transitive dependencies or an APK.

The [inclusion policy](https://f-droid.org/en/docs/Inclusion_Policy/) permits
FLOSS source and dependencies from accepted sources; LGPL code is not inherently
excluded. Preserve the ZIPX LGPL corresponding source/build scripts and packaged
notices. The [metadata license field](https://f-droid.org/en/docs/Build_Metadata_Reference/#License)
describes the distributable app, not merely first-party Java: disclose the separate
LGPL-2.1-or-later ZIPX library when maintainers review that field. Do not describe
the whole native stack as Apache-only or silently relicense the first-party app.

## App identity

- App name: Readwide
- Android application ID: `com.readwide.manager`
- Version name: `1.0.18`
- Version code: `10018`
- First-party license: Apache-2.0
- Source repository: `https://github.com/k1717/Readwide`

The Android application ID is `com.readwide.manager` (since 1.0.4). The F-Droid build is compiled from source and signed with F-Droid's key, so it does not install over a self-installed GitHub-release APK (signed with the project's own key) or an older `com.textview.reader` build — install the F-Droid build fresh and transfer data with the in-app JSON backup export/import.

## Historical metadata mirror

The historical local mirror is included at:

```text
fdroid/metadata/com.readwide.manager.yml
```

Do not copy this historical file over current fdroiddata. The eventual upstream
target is `metadata/com.readwide.manager.yml`, but its current contents must be
preserved and updated from upstream when a submission is actually prepared.

Create and push the immutable release tag before opening the merge request:

```text
v1.0.18
```

The checked-in metadata file is only a historical mirror through `1.0.13`; do not copy it over current fdroiddata. After `v1.0.18` is pushed, start from current upstream metadata, add only the `1.0.18` block, set its `commit` to the full 40-character hash that the final tag points to, and then update `CurrentVersion: 1.0.18` / `CurrentVersionCode: 10018`. A commented template may be kept locally as guidance but must not be activated with a guessed or abbreviated hash. Because `UpdateCheckMode: Tags` and `AutoUpdateMode: Version` are set, F-Droid can also detect the tag and propose the build entry automatically.

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

The 1.0.18 build block should use the `app` module's normal Gradle release
build and explicitly select the native toolchain. These are build-block fields,
not a complete submission file; add the final version and immutable commit:

```yaml
subdir: app
gradle:
  - yes
ndk: 29.0.14206865
buildjni: no
```

Both native modules and the app pin that NDK, and both CMake builds require
3.22.1. Ensure SDK Platform 35, CMake 3.22.1 and the selected NDK are available
in the F-Droid build environment. Do not assume copying the older version's
Gradle-only block provisions everything. The
[metadata reference](https://f-droid.org/en/docs/Build_Metadata_Reference/#Builds)
documents NDK selection and leaving JNI compilation to Gradle with `buildjni: no`.

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

These provide title, short description, full description, and versionCode `10018` changelog text.

## Conservative support wording for review

Use conservative wording in the merge request:

- RAR/CBR support is limited and not complete.
- Describe encrypted, split, SFX and VM-filtered RAR by their specific implemented paths, not as universally supported or universally absent. The new mixed fallback is plain/single-volume only; custom VM and unsupported scheduling remain excluded. See `CURRENT_SOURCE_STATUS_1_0_18.md`.
- HWP/HWPX support is text-first reading only; no Hancom layout parity, editing, writing, cloud/server conversion, or password/encrypted HWP support is claimed.
- Legacy `.doc` files have basic read-only rendering through a self-contained pure-Java parser; layout fidelity is limited compared with OOXML `.docx`.

## Files reviewers should inspect

- `README.md`
- `PRIVACY.md`
- `THIRD_PARTY_NOTICES.md`
- `docs/FOSS_STATUS.md`
- `docs/LICENSE_REPORT_READWIDE_1_0_18.md` (current source-declared report)
- `docs/SBOM_READWIDE_1_0_18.spdx.json` (current source-declared report)
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md`
- `fdroid/metadata/com.readwide.manager.yml`

## Native dependency provenance (for the merge request)

The Android runtime native backend is built entirely from source checked into the release tree. No prebuilt libarchive `.aar` or `.so` is used.

- `third_party/libarchive-android` — Android wrapper source at commit `3a592be028c7be41847f667570bd343c0010bd9d` (Apache-2.0), advanced by Readwide to official libarchive commit `27cbc7827172698143e440801fc0ba39ccb4f1f5` (3.8.9), with the exact bzip2, XZ Utils, LZ4, Zstandard, and Mbed TLS inputs recorded in `UPSTREAM.md`. Gradle, NDK 29.0.14206865, and CMake 3.22.1 compile it for `armeabi-v7a` and `arm64-v8a`.

Readwide packages `app/src/main/assets/open_source_licenses/libarchive_android_and_codecs.txt`, derived from those pinned source revisions, so the APK retains the applicable copyright, redistribution, and warranty-disclaimer terms. The corresponding source licenses also remain beside their components in `third_party/libarchive-android`.

`com.github.luben:zstd-jni:1.5.7-9` remains only under `testImplementation` so plain-JVM archive fixtures can decode Zstandard. Its desktop native resources are not part of the Android release APK and are not required by the F-Droid release assembly path.

The APK also includes the source-built `project(':zipxCodecsAndroid')` module in `third_party/zipx-codecs-android`. Its `readwide-zipx-codecs` shared library includes the XADMaster WinZip JPEG decoder and is documented as LGPL-2.1-or-later; WavPack itself is BSD-3-Clause. Keep its corresponding source, build scripts, component licenses and packaged notices with the release, as described in `THIRD_PARTY_NOTICES.md` and the module's `UPSTREAM.md`. The native stack is therefore not exclusively permissively licensed. Zstandard's BSD option and Mbed TLS's Apache-2.0 option apply to those components, not to the ZIPX library as a whole.

For the 1.0.18 fdroiddata build block, declare `ndk: 29.0.14206865` and `buildjni: no`; Gradle/CMake performs both JNI builds from the checked-in source. This provenance correction does not claim that the LGPL component is incompatible with F-Droid or certify the complete distribution's license compliance.

## Remaining submitter tasks

- Confirm a clean network-enabled Gradle build from the tagged source.
- Confirm the submitted build's `commit` field is the full 40-character hash of the final `v1.0.18` release commit. Start from current upstream metadata and add only the version actually submitted.
- Confirm no optional local jars are present in `app/libs`.
- Confirm the built APK contains `assets/open_source_licenses/libarchive_android_and_codecs.txt`, `xadmaster_winzip_jpeg_lgpl_2_1.txt`, and `wavpack_bsd_3_clause.txt`. Keep the `zstd-jni` notice with source/test materials; it is not shipped in the APK.
