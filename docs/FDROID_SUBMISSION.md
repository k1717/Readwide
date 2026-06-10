# F-Droid submission notes for Readwide 1.0.1

This document records the project-side preparation needed before opening an
F-Droid Data merge request for Readwide 1.0.1.

## App identity

- App name: Readwide
- Android applicationId: `com.textview.reader`
- Version name: `1.0.1`
- Version code: `10001`
- License: Apache-2.0 for first-party source
- Source repository: `https://github.com/k1717/Readwide`

The applicationId intentionally remains `com.textview.reader` for update
compatibility with the previous TextView Reader 2.2.6 line when the APK is
signed with the same key. Mention this in the F-Droid merge request so it is
not mistaken for an incomplete rebrand.

## Draft fdroiddata metadata

A draft metadata file is included at:

```text
fdroid/metadata/com.textview.reader.yml
```

Before submitting to `fdroiddata`, copy it to:

```text
metadata/com.textview.reader.yml
```

Then replace:

```text
REPLACE_WITH_FINAL_GIT_COMMIT_HASH_FOR_V1_0_1
```

with the final immutable Git commit hash that corresponds to the published
`v1.0.1` tag. F-Droid metadata should point at the exact commit used for the
release, not a moving branch.

## Gradle wrapper jar handling

The GitHub source keeps the normal Gradle wrapper files for local developers.
The F-Droid metadata draft removes the wrapper jar before build scanning:

```yaml
rm:
  - gradle/wrapper/gradle-wrapper.jar
```

This keeps the public repository convenient for Android Studio / local Gradle
users while letting F-Droid build with its own trusted Gradle environment.

## Executable permissions

The source ZIP should preserve executable permission for Linux/macOS tooling:

```bash
chmod +x gradlew scripts/clean_removed_sources.sh
git update-index --chmod=+x gradlew
git update-index --chmod=+x scripts/clean_removed_sources.sh
git update-index --chmod=+x scripts/generate_rar_fixture_reports.sh
git update-index --chmod=+x scripts/generate_archive_fixture_reports.sh
```

If the source is uploaded through a web UI or extracted from a ZIP that loses
POSIX metadata, rerun the commands before tagging the release.

## Fastlane / localized metadata

The repository includes source-embedded Fastlane metadata under:

```text
fastlane/metadata/android/
```

Current locales:

- `en-US`
- `ko-KR`

These files provide title, short description, full description, and the
versionCode `10001` changelog.

## Privacy and anti-feature review points

- Missing bookmark files are kept as local bookmark records and shown with an in-app missing-file label/dialog. No network lookup or upload is used for rebinding; the app uses local file identity data only when the user opens matching local files.

Expected project-side statements for the F-Droid merge request:

- Default builds do not request `INTERNET`.
- No ads, analytics, account login, Firebase, Google Play Services, or
  app-network update check is included.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- Broad file access is requested because the app is a local reader/file browser
  that works with user-selected folders, documents, images, and archives.
- Junrar / UnRAR-license fallback code is not bundled in the default build.
- The only source-tree jar intentionally present for GitHub users is the Gradle
  wrapper jar, and the F-Droid metadata draft removes it before build scanning.

## Local verification checklist

Run from a clean checkout after publishing the tag:

```bash
./gradlew clean testDebugUnitTest assembleRelease
```

Also test the F-Droid-style no-private-keystore path:

```bash
unset TEXTVIEW_KEYSTORE_PATH TEXTVIEW_KEYSTORE_PASSWORD TEXTVIEW_KEY_ALIAS TEXTVIEW_KEY_PASSWORD
./gradlew clean assembleRelease
```

Expected behavior: the release task should not require a local developer
keystore when the signing environment variables are absent.

## Known boundary to keep conservative

Do not advertise complete RAR support. The default build attempts common RAR
read/extract paths through libarchive-android plus first-party metadata/stored
entry handling, but split/multi-volume RAR, encrypted RAR, broad solid RAR,
PPMd, VM-filtered, broad SFX, and RAR5 compressed/solid/encrypted-header
variants are not guaranteed.


## ALZ/EGG extraction memory notes

Readwide 1.0.1 streams supported ALZ Store/Deflate/BZip2 payloads and EGG Store/Deflate/BZip2/LZMA blocks to output where possible, with CRC verification. The EGG AZO path remains block-buffered because the current xunazo-derived decoder is block-based. This does not add a new dependency or non-free decoder.

## Archive preview cache privacy note

Archive image/document preview creates temporary app-private cache files so Android viewers can open archive entries as files. Password-protected archive previews use a separate `archive_preview_sensitive` cache root with shorter/smaller pruning limits. These files are generated cache data, not bundled assets or network transfers.

## Support-boundary UI note

The app now surfaces family-specific archive support boundaries for RAR, ZIPX, 7z, ALZ, and EGG and separates bad-password, unsupported-feature, and corrupt/incomplete archive failures. This is UI clarification only; it does not broaden RAR or proprietary archive compatibility claims.
