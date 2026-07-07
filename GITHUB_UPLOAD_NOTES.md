# GitHub upload notes for Readwide 1.0.13

Use this checklist before publishing the public GitHub source and release assets.

## Identity

- App name: Readwide
- Android `applicationId`: `com.readwide.manager`
- `versionName`: `1.0.13`
- `versionCode`: `10013`
- First-party license: Apache-2.0
- Repository: `https://github.com/k1717/Readwide`

The `applicationId` has been `com.readwide.manager` since 1.0.4. 1.0.13 keeps the `readwide` release signing key introduced in 1.0.6, so it installs in place over 1.0.12, 1.0.11, 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6. Updating from 1.0.4/1.0.5 (which used the previous key) still requires uninstalling the old version, installing 1.0.13, and transferring data with the in-app JSON backup export/import, because of the 1.0.6 signing-key change. Older `com.textview.reader` builds also install as a separate app and migrate the same way.

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
- `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_13.md` (and the retained per-version notes back through 1.0.2)
- `docs/LICENSE_REPORT_READWIDE_1_0_13.md`
- `docs/SBOM_READWIDE_1_0_13.spdx.json`
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
grep -RIn "C:\\Users\|/Users/\|/home/.*Downloads\|TEXTVIEW_KEYSTORE_PASSWORD\|READWIDE_KEYSTORE_PASSWORD\|BEGIN PRIVATE KEY" . \
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

1. Tag the exact release commit as `v1.0.13` and push the tag.
2. Start from the current fdroiddata upstream metadata. It already contains Readwide builds through 1.0.12; add only the 1.0.13 build block, set its `commit` field to the full 40-character commit hash that the `v1.0.13` tag points to, then update `CurrentVersion` to `1.0.13` and `CurrentVersionCode` to `10013`. This app's F-Droid maintainer requires a full commit hash rather than the tag name.
3. Confirm release builds work without private signing environment variables.
4. Confirm no optional local jars are present under `app/libs`.
5. Keep broad-storage and no-network privacy rationale in the merge request.
