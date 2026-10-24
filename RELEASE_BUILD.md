# Public release build checklist

This file is the practical build and verification checklist for Readwide 1.0.21.

## Version metadata

```text
applicationId com.readwide.manager
versionCode 10021
versionName 1.0.21
```

The application ID has been `com.readwide.manager` since 1.0.4. 1.0.6 switched to a new release signing key (the `readwide` alias); 1.0.21 keeps that key, so it installs in place over 1.0.20 through 1.0.6. Updating from 1.0.4/1.0.5 (signed with the previous `textview`-alias key) still requires uninstalling the old version, installing 1.0.21, and migrating data with the in-app JSON backup export/import. Earlier builds using `com.textview.reader` install as a separate app because the applicationId differs.

## Keystore policy

Do not commit release signing files or passwords. Release signing reads from environment variables first and then Gradle properties (the legacy `TEXTVIEW_*` names are still accepted):

```text
READWIDE_KEYSTORE_PATH=/secure/path/release.keystore
READWIDE_KEYSTORE_PASSWORD=...
READWIDE_KEY_ALIAS=readwide
READWIDE_KEY_PASSWORD=...
```

If these four values are absent, `assembleRelease` is expected to build an unsigned release artifact instead of failing. This supports F-Droid-style source-builder workflows. Local GitHub APK releases should still be signed with the project release key outside the source tree. Supply all four values for signing; partial configuration is not a signed-build setup.

## Build commands

Linux/macOS:

```bash
./gradlew clean assembleRelease
```

Windows:

```powershell
.\gradlew.bat clean assembleRelease
```

These commands assemble the release variant; they do not run regression tests or
install the app. Optional JVM tests and lint are separate tasks:

```bash
./gradlew testDebugUnitTest lintDebug
```

On Windows, use `.\gradlew.bat testDebugUnitTest lintDebug`. Compiling test code
alone is not test execution; device/instrumentation checks are separate again.

Optional source-builder check without private signing values (also ensure no
signing values are set in project or global Gradle properties):

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
- `docs/LICENSE_REPORT_READWIDE_1_0_21.md`
- `docs/SBOM_READWIDE_1_0_21.spdx.json`

The 1.0.21 reports retain the dependency pins declared by the 1.0.20 source. They cover direct dependencies and bundled source; a resolved transitive report is still needed for submission.

All source-controlled notice assets in `app/src/main/assets/open_source_licenses/`
must remain in the APK, not only the libarchive/ZIPX notices. The inventory and
component obligations are recorded in [third-party notices](THIRD_PARTY_NOTICES.md).

## APK verification

Publish the verified signed APK as `Readwide_1.0.21.apk`. Local build output names may differ.

```bash
APK=app/build/outputs/apk/release/app-release.apk
test -f "$APK" || APK=app/build/outputs/apk/release/app-release-unsigned.apk
test -f "$APK"

aapt dump xmltree "$APK" AndroidManifest.xml \
  | grep -E "debuggable|usesCleartextTraffic|INTERNET|allowBackup"

unzip -l "$APK" 'assets/open_source_licenses/*'
```

For a signed GitHub asset, additionally run `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`. Do not expect signature verification to pass for the intentionally unsigned source-builder artifact.

Expected default release baseline:

- release APK is not debuggable;
- `INTERNET` is absent;
- `usesCleartextTraffic="false"`;
- `allowBackup="false"`;
- no accidental machine-local path strings are embedded;
- no keystore/private signing material is embedded.
- all required notice assets are present and readable.

Compare the APK notice inventory with the source asset directory and inspect
their contents. A filename listing alone does not validate license text. Likewise,
running `strings` on the compressed APK cannot establish that DEX/native entries
are free of private paths; inspect the relevant extracted entries when auditing
binary contents. Manifest inspection and signature verification serve different purposes.

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

The public source ZIP must use `/` path separators and preserve POSIX modes. Store
`gradlew` and `*.sh` as executable (`0755`) and ordinary source/document files as
`0644`. Use a fresh, timestamped destination outside the source tree; never
overwrite an earlier package. From the project root:

```bash
python3 scripts/create_source_zip.py <new-output.zip> --no-digest
```

On Windows, use `python` or `py -3` in place of `python3`. This route validates
the filtered inventory, CRC and executable modes without generating a package
digest. It excludes generated `.gradle/` and `build/` trees, IDE state, local
properties, signing material and compiled artifacts, while retaining libarchive's
checked-in `external/libarchive/build/cmake/` source modules. The older PowerShell
packager generates a digest and is not the documented no-digest route.

Keep the official Gradle wrapper and its pinned distribution configuration in
`gradle/wrapper/gradle-wrapper.properties`. Packaging does not replace or disable
the build tooling's own dependency/distribution verification.

## F-Droid handoff

Before opening an F-Droid Data merge request:

1. Publish a final Git tag, e.g. `v1.0.21`.
2. Start from current fdroiddata upstream. The checked-in historical mirror stops at 1.0.19 and must not replace upstream metadata. Add only the version actually submitted and pin it to the final tag's full 40-character commit hash.
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
- ZIP/CBZ, 7z/CB7, RAR/CBR, TAR-family, CAB, LHA, ALZ and EGG examples according to [current archive support notes](docs/ARCHIVE_BOUNDARY_SUPPORT_NOTES.md), including the inherited profile limits. The 1.0.2 matrix is historical, not the current acceptance checklist.
- Android 3-button navigation and gesture navigation safe areas across TXT, WebView documents, PDF, and image viewer.
- Copy/move/delete/extract/compress queues with pause/resume/cancel/background behavior.

Optional external archive fixture tests can be run by setting:

```bash
export TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR=/path/to/archive-fixtures
./gradlew testDebugUnitTest
```

On PowerShell, set `$env:TEXTVIEW_EXTERNAL_ARCHIVE_FIXTURE_DIR` before starting
`.\gradlew.bat testDebugUnitTest`. A shell-local variable is not inherited by
Gradle; missing fixture configuration makes these optional tests skip.

The [release readiness record](docs/RELEASE_READINESS_1_0_21.md) distinguishes
completed checks from outstanding verification. Verify source ZIP inventory, CRC
and executable modes separately from APK identity/signature and runtime checks;
none substitutes for the others.
