# GitHub upload notes for Readwide 1.0.17

Use this checklist before publishing the public GitHub source and release assets.

## Identity

- App name: Readwide
- Android `applicationId`: `com.readwide.manager`
- `versionName`: `1.0.17`
- `versionCode`: `10017`
- First-party license: Apache-2.0
- Repository: `https://github.com/k1717/Readwide`

The `applicationId` has been `com.readwide.manager` since 1.0.4. 1.0.17 keeps the `readwide` release signing key introduced in 1.0.6, so it installs in place over 1.0.16 through 1.0.6. Updating from 1.0.4/1.0.5 (which used the previous key) still requires uninstalling the old version, installing 1.0.17, and transferring data with the in-app JSON backup export/import, because of the 1.0.6 signing-key change. Older `com.textview.reader` builds also install as a separate app and migrate the same way.

## Files expected in the source release

Keep these files in the public source package:

- `LICENSE`
- `NOTICE`
- `README.md`
- `CHANGELOG.md`
- `PRIVACY.md`
- `THIRD_PARTY_NOTICES.md`
- `app/src/main/assets/open_source_licenses/libarchive_android_and_codecs.txt`
- `RELEASE_BUILD.md`
- `CONTRIBUTING.md`
- `docs/FOSS_STATUS.md`
- `docs/FDROID_SUBMISSION.md`
- `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_17.md` (and the retained per-version notes back through 1.0.2)
- `docs/LICENSE_REPORT_READWIDE_1_0_17.md`
- `docs/SBOM_READWIDE_1_0_17.spdx.json`
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md`
- `docs/TXT_SEARCH_USAGE.md`
- `fdroid/metadata/com.readwide.manager.yml`
- `fastlane/metadata/android/en-US/*`
- `fastlane/metadata/android/ko-KR/*`

## Do not upload

Do not commit or attach private release materials:

- release keystores, `.jks`, `.keystore`, `.p12`
- signing passwords or `secrets.properties`
- local `local.properties`
- private sample documents or personal test fixtures
- build outputs unless they are intentional release APK/AAB assets
- IDE/user folders such as `.idea/`, `.gradle/`, `captures/`, logs, hprof files

## Pre-upload checks

The source ZIP must use `/` entry separators and preserve POSIX file modes. In particular, `gradlew` and shell scripts should be stored as executable (`0755`); source and documentation files should be regular non-executable files (`0644`). Do not create the public source ZIP with a Windows-only path format. Use `scripts/create_source_zip.py` so generated `build/` trees are excluded without dropping libarchive's checked-in `third_party/libarchive-android/library/src/main/jni/external/libarchive/build/cmake/` source modules.

```bash
python3 scripts/create_source_zip.py Readwide-1.0.17-github-source-full.zip
```

```bash
./gradlew clean testDebugUnitTest assembleDebug lintDebug
./gradlew clean assembleRelease
```

Check the default source package:

```bash
grep -RIn "C:\\Users\|/Users/\|/home/.*Downloads\|BEGIN PRIVATE KEY" . \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle

find . -type f \( -name "*.jks" -o -name "*.keystore" -o -name "*.p12" -o -name "*.apk" -o -name "*.aab" \)
```

Select the built release artifact. An unsigned source-builder build normally uses `app-release-unsigned.apk`; a locally signed public asset normally uses `app-release.apk`:

```bash
APK=app/build/outputs/apk/release/app-release.apk
test -f "$APK" || APK=app/build/outputs/apk/release/app-release-unsigned.apk
test -f "$APK"

aapt dump xmltree "$APK" AndroidManifest.xml \
  | grep -E "debuggable|usesCleartextTraffic|INTERNET|allowBackup"

unzip -p "$APK" assets/open_source_licenses/libarchive_android_and_codecs.txt \
  | grep -E "libarchive|bzip2|XZ Utils|LZ4|Zstandard|zlib|Mbed TLS"
```

If attaching the signed GitHub APK, verify that signed file separately:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Expected public baseline:

- `debuggable` false / absent for release.
- `INTERNET` absent in the default manifest.
- `usesCleartextTraffic="false"`.
- `allowBackup="false"`.
- Native libarchive/codecs notices are present inside the APK.

Wrapper baseline for this source tree:

```text
gradle-wrapper.jar SHA-256: 55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
gradle-9.4.1-bin.zip SHA-256: 2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
```

The distribution checksum is pinned in `gradle/wrapper/gradle-wrapper.properties`.

## Public wording rules

Use:

- "local-first reader and file browser"
- "HWP/HWPX text-first read-only support"
- "limited/scoped/backend-dependent archive support"
- "RAR/CBR support remains limited"
- "shared reader search options for TXT and document viewers"

Avoid:

- "complete RAR support"
- "encrypted RAR supported"
- "full HWP support"
- "Hancom-compatible rendering"
- "legacy DOC supported" unless the renderer is actually implemented

## F-Droid handoff

Before opening an F-Droid Data merge request:

1. Tag the exact release commit as `v1.0.17` and push the tag.
2. Start from the current fdroiddata upstream metadata; the checked-in historical mirror stops at 1.0.13 and must not be copied over upstream. Add only the 1.0.17 build block, set its `commit` field to the full 40-character commit hash that the `v1.0.17` tag points to, then update `CurrentVersion` to `1.0.17` and `CurrentVersionCode` to `10017`.
3. Confirm release builds work without private signing environment variables.
4. Confirm no optional local jars are present under `app/libs`.
5. Keep broad-storage and no-network privacy rationale in the merge request.

## Verification boundary for this source handoff

Static source/package checks may be completed before handoff, but do not describe the source as build-, test-, or lint-verified until the release maintainer runs the commands above on the exact tagged tree. Publish the source ZIP SHA-256 next to the release asset after the final archive has been created.
