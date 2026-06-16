# GitHub upload notes for Readwide 1.0.4

Use this checklist before publishing the public GitHub source and release assets.

## Identity

- App name: Readwide
- Android `applicationId`: `com.readwide.manager`
- `versionName`: `1.0.4`
- `versionCode`: `10004`
- First-party license: Apache-2.0
- Repository: `https://github.com/k1717/Readwide`

As of 1.0.4 the `applicationId` is `com.readwide.manager`. Earlier TextView Reader/Readwide builds used a different application ID, so 1.0.4 installs as a separate app rather than an in-place update. Existing users transfer data with the in-app JSON backup export/import.

## Files expected in the source release

Keep these files in the public source package:

- `LICENSE`
- `NOTICE`
- `README.md`
- `CHANGELOG.md`
- `PRIVACY.md`
- `THIRD_PARTY_NOTICES.md`
- `RELEASE_BUILD.md`
- `CONTRIBUTING.md`
- `docs/FOSS_STATUS.md`
- `docs/FDROID_SUBMISSION.md`
- `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_4.md`
- `docs/LICENSE_REPORT_READWIDE_1_0_4.md`
- `docs/SBOM_READWIDE_1_0_4.spdx.json`
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

```bash
./gradlew clean testDebugUnitTest assembleRelease
```

Check the default source package:

```bash
grep -RIn "C:\\Users\|/Users/\|/home/.*Downloads\|TEXTVIEW_KEYSTORE_PASSWORD\|BEGIN PRIVATE KEY" . \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle

find . -type f \( -name "*.jks" -o -name "*.keystore" -o -name "*.p12" -o -name "*.apk" -o -name "*.aab" \)
```

Check release APK basics if attaching a binary:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

aapt dump xmltree app/build/outputs/apk/release/app-release.apk AndroidManifest.xml \
  | grep -E "debuggable|usesCleartextTraffic|INTERNET|allowBackup"
```

Expected public baseline:

- `debuggable` false / absent for release.
- `INTERNET` absent in the default manifest.
- `usesCleartextTraffic="false"`.
- `allowBackup="false"`.

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

1. Tag the exact release commit, e.g. `v1.0.4`.
2. Confirm `fdroid/metadata/com.readwide.manager.yml` points to the immutable `v1.0.4` release tag, or replace it with the exact commit hash if the F-Droid reviewer requests a hash.
3. Confirm release builds work without private signing environment variables.
4. Confirm no optional local jars are present under `app/libs`.
5. Keep broad-storage and no-network privacy rationale in the merge request.
