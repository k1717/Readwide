# F-Droid submission notes for Readwide 1.0.5

This document records the project-side preparation for an F-Droid Data merge request.

## App identity

- App name: Readwide
- Android application ID: `com.readwide.manager`
- Version name: `1.0.5`
- Version code: `10005`
- First-party license: Apache-2.0
- Source repository: `https://github.com/k1717/Readwide`

Since 1.0.4 the Android application ID is `com.readwide.manager`, and 1.0.5 is an in-place update over 1.0.4 when signed with the same key. Earlier TextView Reader/Readwide builds used a different application ID and install as a separate app; users transfer data with the in-app JSON backup export/import.

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
v1.0.5
```

The draft metadata uses `commit: v1.0.5`. Confirm that this tag points to the audited source tree before copying `fdroid/metadata/com.readwide.manager.yml` into fdroiddata.

## F-Droid-facing baseline

The default build is intended to be reviewed as a local-first FOSS app:

- First-party source is Apache-2.0.
- The default manifest does not request `INTERNET`.
- No ads, analytics, telemetry SDK, Firebase, Google Play Services dependency, account system, cloud sync, developer-operated upload backend, or app-network update checker is included.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- Broad file access is requested because the app is a local reader and file browser that works with user-selected folders, documents, images, and archives.
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
unset TEXTVIEW_KEYSTORE_PATH TEXTVIEW_KEYSTORE_PASSWORD TEXTVIEW_KEY_ALIAS TEXTVIEW_KEY_PASSWORD
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

These provide title, short description, full description, and versionCode `10005` changelog text.

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
- `docs/LICENSE_REPORT_READWIDE_1_0_5.md`
- `docs/SBOM_READWIDE_1_0_5.spdx.json`
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md`
- `fdroid/metadata/com.readwide.manager.yml`

## Remaining submitter tasks

- Confirm a clean network-enabled Gradle build from the tagged source.
- Confirm the draft metadata points to the immutable `v1.0.5` tag or to an equivalent exact commit hash requested by the reviewer.
- Confirm no optional local jars are present in `app/libs`.
- Confirm native dependency notices for `libarchive-android` / libarchive and `zstd-jni` / Zstandard are included with binary release materials if APK assets are published outside F-Droid.
