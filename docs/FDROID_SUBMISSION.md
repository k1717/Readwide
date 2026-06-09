# F-Droid submission notes for Readwide 1.0.0

This document records the project-side preparation needed before opening an
F-Droid Data merge request for Readwide 1.0.0.

## App identity

- App name: Readwide
- Android applicationId: `com.textview.reader`
- Version name: `1.0.0`
- Version code: `10000`
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
REPLACE_WITH_FINAL_GIT_COMMIT_HASH_FOR_V1_0_0
```

with the final immutable Git commit hash that corresponds to the published
`v1.0.0` tag. F-Droid metadata should point at the exact commit used for the
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
versionCode `10000` changelog.

## Privacy and anti-feature review points

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
