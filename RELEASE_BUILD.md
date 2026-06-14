# Public release build checklist

This file is the practical build and verification checklist for Readwide 1.0.3.

## Version metadata

```text
applicationId com.textview.reader
versionCode 10003
versionName 1.0.3
```

The application ID remains `com.textview.reader` for update compatibility with earlier compatible builds when signed with the same key.

## Keystore policy

Do not commit release signing files or passwords. Release signing reads from environment variables first and then Gradle properties:

```text
TEXTVIEW_KEYSTORE_PATH=/secure/path/release.keystore
TEXTVIEW_KEYSTORE_PASSWORD=...
TEXTVIEW_KEY_ALIAS=textview
TEXTVIEW_KEY_PASSWORD=...
```

If these four values are absent, `assembleRelease` is expected to build an unsigned release artifact instead of failing. This supports F-Droid-style source-builder workflows. Local GitHub APK releases should still be signed with the developer release key outside the source tree.

## Build commands

Linux/macOS:

```bash
./gradlew clean testDebugUnitTest assembleRelease
```

Windows:

```powershell
.\gradlew.bat clean testDebugUnitTest assembleRelease
```

Optional source-builder check without private signing values:

```bash
unset TEXTVIEW_KEYSTORE_PATH TEXTVIEW_KEYSTORE_PASSWORD TEXTVIEW_KEY_ALIAS TEXTVIEW_KEY_PASSWORD
./gradlew clean assembleRelease
```

## APK verification

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

aapt dump xmltree app/build/outputs/apk/release/app-release.apk AndroidManifest.xml \
  | grep -E "debuggable|usesCleartextTraffic|INTERNET|allowBackup"

strings app/build/outputs/apk/release/app-release.apk \
  | grep -E "^/(home|Users|builds)/" | sort -u

sha256sum app/build/outputs/apk/release/app-release.apk
```

Expected default release baseline:

- release APK is not debuggable;
- `INTERNET` is absent;
- `usesCleartextTraffic="false"`;
- `allowBackup="false"`;
- no local user path strings are embedded;
- no keystore/private signing material is embedded.

## Source tree checks before tagging

```bash
find . -type f \( -name "*.jks" -o -name "*.keystore" -o -name "*.p12" -o -name "*.apk" -o -name "*.aab" \)

grep -RIn "C:\\Users\|/Users/\|/home/.*Downloads\|BEGIN PRIVATE KEY\|TEXTVIEW_KEYSTORE_PASSWORD" . \
  --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=build

grep -RIn "com.github.junrar\|junrar\|RarJunrarFallback" app docs README.md CHANGELOG.md THIRD_PARTY_NOTICES.md || true
```

Expected result:

- no private key or keystore files in source;
- no accidental personal path strings in release docs/source;
- no Junrar dependency or fallback source in the default build.

## F-Droid handoff

Before opening an F-Droid Data merge request:

1. Publish a final Git tag, e.g. `v1.0.3`.
2. Replace the commit placeholder in `fdroid/metadata/com.textview.reader.yml` with the immutable tag commit.
3. Confirm `gradle/wrapper/gradle-wrapper.jar` is removed by the F-Droid metadata `rm` rule.
4. Confirm a no-private-keystore `assembleRelease` build works.
5. Keep RAR and HWP/HWPX support wording conservative.

## Manual smoke QA

Run representative files through these paths before publishing:

- TXT large-file open, page tap, slider, search, bookmark save/restore, and legacy bookmark fallback.
- Markdown WebView open, visual page navigation, bookmarks, and table/code rendering.
- EPUB reflow and fixed-layout samples, including cover/title page and image-heavy pages.
- OOXML Word document open and bookmark/search paths.
- HWP/HWPX text-first open, encrypted-HWP failure, and archive-internal HWP/HWPX open.
- PDF single-page and vertical-continuous modes, zoom/pan, bookmarks, slider, and toolbar hidden/visible state.
- ZIP/CBZ, 7z/CB7, RAR/CBR, TAR-family, ALZ, and EGG fixture coverage according to `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`.
- Android 3-button navigation and gesture navigation safe areas across TXT, WebView documents, PDF, and image viewer.
- Copy/move/delete/extract/compress queues with pause/resume/cancel/background behavior.

Optional external archive fixture tests can be run by setting:

```bash
TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR=/path/to/archive-fixtures
./gradlew testDebugUnitTest
```
