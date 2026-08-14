# Public release build checklist

This file is the practical build and verification checklist for Readwide 1.0.17.

## Version metadata

```text
applicationId com.readwide.manager
versionCode 10017
versionName 1.0.17
```

The application ID has been `com.readwide.manager` since 1.0.4. 1.0.6 switched to a new release signing key (the `readwide` alias); 1.0.17 keeps that key, so it installs in place over 1.0.16, 1.0.15, 1.0.14, 1.0.13, 1.0.12, 1.0.11, 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6. Updating from 1.0.4/1.0.5 (signed with the previous `textview`-alias key) still requires uninstalling the old version, installing 1.0.17, and migrating data with the in-app JSON backup export/import. Earlier builds using `com.textview.reader` install as a separate app because the applicationId differs.

## Keystore policy

Do not commit release signing files or passwords. Release signing reads from environment variables first and then Gradle properties (the legacy `TEXTVIEW_*` names are still accepted):

```text
READWIDE_KEYSTORE_PATH=/secure/path/release.keystore
READWIDE_KEYSTORE_PASSWORD=...
READWIDE_KEY_ALIAS=readwide
READWIDE_KEY_PASSWORD=...
```

If these four values are absent, `assembleRelease` is expected to build an unsigned release artifact instead of failing. This supports F-Droid-style source-builder workflows. Local GitHub APK releases should still be signed with the developer release key outside the source tree.

## Build commands

Linux/macOS:

```bash
./gradlew clean testDebugUnitTest assembleDebug lintDebug
./gradlew clean assembleRelease
```

Windows:

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug lintDebug
.\gradlew.bat clean assembleRelease
```

Optional source-builder check without private signing values:

```bash
unset READWIDE_KEYSTORE_PATH READWIDE_KEYSTORE_PASSWORD READWIDE_KEY_ALIAS READWIDE_KEY_PASSWORD TEXTVIEW_KEYSTORE_PATH TEXTVIEW_KEYSTORE_PASSWORD TEXTVIEW_KEY_ALIAS TEXTVIEW_KEY_PASSWORD
./gradlew clean assembleRelease
```

## Release notice files

Keep these files with source and binary release materials:

- `LICENSE`
- `NOTICE`
- `THIRD_PARTY_NOTICES.md`
- `PRIVACY.md`
- `docs/FOSS_STATUS.md`
- `docs/LICENSE_REPORT_READWIDE_1_0_17.md`
- `docs/SBOM_READWIDE_1_0_17.spdx.json`

The source-controlled native notice file at `app/src/main/assets/open_source_licenses/libarchive_android_and_codecs.txt` must also remain in the APK.

## APK verification

```bash
APK=app/build/outputs/apk/release/app-release.apk
test -f "$APK" || APK=app/build/outputs/apk/release/app-release-unsigned.apk
test -f "$APK"

aapt dump xmltree "$APK" AndroidManifest.xml \
  | grep -E "debuggable|usesCleartextTraffic|INTERNET|allowBackup"

strings "$APK" \
  | grep -E "^/(home|Users|builds)/" | sort -u

sha256sum "$APK"
unzip -p "$APK" assets/open_source_licenses/libarchive_android_and_codecs.txt \
  | grep -E "libarchive|bzip2|XZ Utils|LZ4|Zstandard|zlib|Mbed TLS"
```

For a signed GitHub asset, additionally run `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`. Do not expect signature verification to pass for the intentionally unsigned source-builder artifact.

Expected default release baseline:

- release APK is not debuggable;
- `INTERNET` is absent;
- `usesCleartextTraffic="false"`;
- `allowBackup="false"`;
- no local user path strings are embedded;
- no keystore/private signing material is embedded.
- the native libarchive/codecs notice asset is present and readable.

## Source tree checks before tagging

```bash
find . -type f \( -name "*.jks" -o -name "*.keystore" -o -name "*.p12" -o -name "*.apk" -o -name "*.aab" \)

grep -RIn "C:\\Users\|/Users/\|/home/.*Downloads\|BEGIN PRIVATE KEY" . \
  --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=build

grep -RInE "import com\\.github\\.junrar|com\\.github\\.junrar:" app/src app/build.gradle || true
```

Expected result:

- no private key or keystore files in source;
- no accidental personal path strings in release docs/source;
- no Junrar dependency or fallback source in the default build.

## Source ZIP portability

The public source ZIP must use `/` path separators and preserve POSIX modes. Store `gradlew` and `*.sh` as executable (`0755`) and ordinary source/document files as `0644`. After creating the archive, run a full CRC read and compare its normalized file list with the filtered source tree; exclude `.gradle/`, every `build/` directory, IDE state, local properties, signing material, and compiled artifacts.

Gradle 9.4.1 integrity baseline:

```text
gradle-wrapper.jar SHA-256: 55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
gradle-9.4.1-bin.zip SHA-256: 2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
```

The distribution hash is pinned with `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`.

## F-Droid handoff

Before opening an F-Droid Data merge request:

1. Publish a final Git tag, e.g. `v1.0.17`.
2. Start from current fdroiddata upstream. The checked-in historical mirror stops at 1.0.13 and must not replace upstream metadata. Add only the version actually submitted and pin it to the final tag's full 40-character commit hash.
3. Keep the verified official Gradle 9.4.1 wrapper; F-Droid checks known wrapper hashes, so no `rm` rule is needed.
4. Confirm a no-private-keystore `assembleRelease` build works.
5. Keep RAR and HWP/HWPX support wording conservative.

## Manual smoke QA

Run representative files through these paths before publishing:

- TXT large-file open, page tap, slider, search options, end-of-file search reveal, bookmark save/restore, and legacy bookmark fallback.
- Markdown WebView open, visual page navigation, bookmarks, document search, and table/code rendering.
- EPUB reflow and fixed-layout samples, including cover/title page, image-heavy pages, and document search highlight/reveal.
- OOXML Word document open, bookmark paths, and document search highlight/reveal.
- HWP/HWPX text-first open, encrypted-HWP failure, archive-internal HWP/HWPX open, and document search highlight/reveal.
- PDF single-page and vertical-continuous modes, zoom/pan, bookmarks, slider, and toolbar hidden/visible state in portrait/landscape. Verify that Android status/navigation bars remain, app chrome leaves no blank reserve, and no PDF rerender flash occurs.
- Browser and Recent list/tile switching from the fixed upper-right overflow, including thumbnails off/on, rotation, position retention, search paths, selection, and long-hold actions.
- ZIP/CBZ, 7z/CB7, RAR/CBR, TAR-family, ALZ, and EGG fixture coverage according to `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`.
- Android 3-button navigation and gesture navigation safe areas across TXT, WebView documents, PDF, and image viewer.
- Copy/move/delete/extract/compress queues with pause/resume/cancel/background behavior.

Optional external archive fixture tests can be run by setting:

```bash
TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR=/path/to/archive-fixtures
./gradlew testDebugUnitTest
```

This source handoff does not claim that these Gradle, unit-test, lint, APK, or device checks have run against the exact tree that will be tagged. Record their results only after running them on that tree.
