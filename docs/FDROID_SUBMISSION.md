# F-Droid submission notes for Readwide 1.0.6

This document records the project-side preparation for an F-Droid Data merge request.

## App identity

- App name: Readwide
- Android application ID: `com.readwide.manager`
- Version name: `1.0.6`
- Version code: `10006`
- First-party license: Apache-2.0
- Source repository: `https://github.com/k1717/Readwide`

The Android application ID is `com.readwide.manager` (since 1.0.4). This is the first F-Droid submission; the F-Droid build is compiled from source and signed with F-Droid's key, so it does not install over a self-installed GitHub-release APK (signed with the project's own key) or an older `com.textview.reader` build — install the F-Droid build fresh and transfer data with the in-app JSON backup export/import.

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
v1.0.6
```

The draft metadata uses `commit: v1.0.6`. Confirm that this tag points to the audited source tree before copying `fdroid/metadata/com.readwide.manager.yml` into fdroiddata.

## F-Droid-facing baseline

The default build is intended to be reviewed as a local-first FOSS app:

- First-party source is Apache-2.0.
- The default manifest does not request `INTERNET`.
- No ads, analytics, telemetry SDK, Firebase, Google Play Services dependency, account system, cloud sync, developer-operated upload backend, or app-network update checker is included.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- Broad file access is requested because the app is a local reader and file browser that works with user-selected folders, documents, images, and archives.
- `MANAGE_EXTERNAL_STORAGE` is requested for general local file browsing on Android versions where scoped-storage permissions alone cannot implement it. It is used only for local browse/open/copy/move/delete/extract/compress of user-selected files, and is not paired with `INTERNET` or any developer-operated upload path.
- The main activity exports an `ACTION_VIEW` intent filter (with the `BROWSABLE` category and document/image MIME types, no `http`/`https` scheme) so files can be opened in Readwide from a browser, messenger, file manager, or document provider. A file opened this way is copied into an app-private `opened_files` cache with display-name sanitization, a canonical-path containment check, a 2 GB per-file copy limit, and cache pruning before and after the copy; provider `query`/`getType` exceptions are caught. No network access is involved.
- Default builds do not bundle Junrar or RARLAB UnRAR-license code.
- HWP/HWPX support uses Apache-2.0 Java libraries (`hwplib`, `hwpxlib`) and is text-first/read-only.
- Release signing is conditional; if private signing environment variables are absent, `assembleRelease` should produce an unsigned release artifact suitable for source-builder workflows.

## Gradle wrapper jar handling

The GitHub source keeps normal Gradle wrapper files for developer convenience. The draft F-Droid metadata removes the wrapper jar before build scanning:

```yaml
rm:
  - gradle/wrapper/gradle-wrapper.jar
```

## Build command

The draft metadata uses the normal Gradle release build:

```yaml
gradle:
  - 'yes'
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

These provide title, short description, full description, and versionCode `10006` changelog text.

## Conservative support wording for review

Use conservative wording in the merge request:

- RAR/CBR support is limited and not complete.
- Encrypted RAR, broad split RAR, SFX, VM-filtered RAR, and full RAR compatibility are not claimed.
- HWP/HWPX support is text-first reading only; no Hancom layout parity, editing, writing, cloud/server conversion, or password/encrypted HWP support is claimed.
- Legacy `.doc` files are recognized under the Word filter but are not rendered yet.

## Files reviewers should inspect

- `README.md`
- `PRIVACY.md`
- `THIRD_PARTY_NOTICES.md`
- `docs/FOSS_STATUS.md`
- `docs/LICENSE_REPORT_READWIDE_1_0_6.md`
- `docs/SBOM_READWIDE_1_0_6.spdx.json`
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md`
- `fdroid/metadata/com.readwide.manager.yml`

## Native dependency provenance (for the merge request)

Two Maven dependencies ship prebuilt native code embedded in their published artifacts. Both are unmodified upstream FOSS releases resolved from Maven Central by exact, pinned coordinates; no native source is vendored into this repository.

- `me.zhanghai.android.libarchive:library:1.1.6` — Android wrapper, Apache-2.0 (© Google LLC / Hai Zhang), source at https://github.com/zhanghai/libarchive-android. The published `.aar` embeds an NDK-built native libarchive (BSD-2-Clause) compiled with libz (Zlib), libbz2 (BSD-style), liblzma/xz (public-domain / 0BSD), liblz4 (BSD-2-Clause), libzstd (BSD-3-Clause OR GPLv2, used under BSD-3-Clause), and Mbed TLS / libmbedcrypto (Apache-2.0). Used for RAR and backend-dependent archive decode paths.
- `com.github.luben:zstd-jni:1.5.7-9` — JNI wrapper, BSD-2-Clause (© Luben Karavelov), source at https://github.com/luben/zstd-jni. The published `.jar` embeds the native Zstandard library (dual-licensed BSD-3-Clause OR GPLv2, used here under BSD-3-Clause, © Meta). Used for the Zstandard codec path under Commons Compress.

All bundled native components are permissive/FOSS-compatible, and no copyleft obligation is triggered because the dual-licensed pieces are taken under their BSD option. If the reviewer requires a fully source-built native chain, both upstreams publish buildable sources at the URLs above, and F-Droid's build server can resolve the same pinned artifacts from Maven Central.

## Remaining submitter tasks

- Confirm a clean network-enabled Gradle build from the tagged source.
- Confirm the draft metadata points to the immutable `v1.0.6` tag or to an equivalent exact commit hash requested by the reviewer.
- Confirm no optional local jars are present in `app/libs`.
- Confirm native dependency notices for `libarchive-android` / libarchive and `zstd-jni` / Zstandard are included with binary release materials if APK assets are published outside F-Droid.
