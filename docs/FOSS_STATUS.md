# FOSS status for Readwide 1.0.13

This is the project-level FOSS status note for the default Readwide 1.0.13 source package and default release build. It is not legal advice; it records the current release position and the checks a reviewer should make.

## Current assessment

The default Readwide 1.0.13 source package is intended to remain FOSS-friendly:

- First-party source is licensed under Apache License 2.0.
- Source code needed for the default build is included in the repository/source package.
- No proprietary app EULA is added by the project.
- The default build does not bundle Junrar or RARLAB UnRAR-license code.
- The default manifest does not request `INTERNET`.
- The app contains no ads, analytics, telemetry SDK, Firebase, Google Play Services dependency, account system, cloud sync, developer-operated upload backend, or in-app network update checker.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- Private signing files, build outputs, and optional proprietary binary dependencies are not part of the default source package.

## Runtime dependency summary

| Component | Use | License position recorded for this release |
| --- | --- | --- |
| Readwide first-party source | App code | Apache-2.0 |
| AndroidX / Material Components | Android UI/runtime support | Apache-2.0 |
| JUniversalChardet | Text encoding detection | MPL-1.1 option used by this project |
| Apache Commons Compress | TAR/7z/stream archive support and ZIP method fallback | Apache-2.0 |
| libarchive-android | RAR/backend archive support through Android libarchive bindings | Android library artifact under Apache-2.0; bundled native libarchive under permissive BSD-style notices |
| XZ for Java | XZ/LZMA support | 0BSD |
| zstd-jni | Zstandard codec used by Commons Compress | BSD-family licensing path recorded in `THIRD_PARTY_NOTICES.md` |
| Zip4j | ZIP/CBZ listing/extraction/encryption/split support | Apache-2.0 |
| hwplib | HWP 5.x read/text extraction backend | Apache-2.0 |
| hwpxlib | HWPX read/text extraction backend | Apache-2.0 |
| PdfBox-Android | PDF text extraction for in-document find (rendering stays on the platform PdfRenderer) | Apache-2.0 |
| xunazo-derived AZO port | EGG AZO extraction | zlib license notice retained in source |

See `docs/LICENSE_REPORT_READWIDE_1_0_13.md`, `docs/SBOM_READWIDE_1_0_13.spdx.json`, and `THIRD_PARTY_NOTICES.md` for detail.

## RAR / CBR boundary

Readwide is extraction/read-only for RAR/CBR. It does not create RAR archives and does not implement password recovery.

Default RAR handling is deliberately conservative:

1. libarchive-android is the primary backend for common compressed RAR read/extract attempts.
2. First-party Java handles covered metadata, safe paths, stored entries, stored split paths, and selected validation/cleanup paths.
3. Scoped first-party decode-only fallbacks exist for eligible RAR3/RAR4 PPMd solid sets, covered RAR5 v5.0 compressed/solid runs, and fixture-tested RAR5 AES visible-header multi-volume chains, with CRC/password-check safeguards.
4. Broad encrypted RAR, broad split/multi-volume RAR, SFX, VM-filtered RAR3/RAR4, broad RAR5-era variants, and complete RAR compatibility are not claimed.

No Junrar or RARLAB UnRAR-license source code is bundled in the default build.

## HWP / HWPX boundary

HWP/HWPX support is read-only and text-first:

- `.hwp` uses `kr.dogfoot:hwplib:1.1.10`.
- `.hwpx` uses `kr.dogfoot:hwpxlib:1.0.9`.
- Both are recorded as Apache-2.0 dependencies.
- Readwide does not bundle Hancom proprietary SDKs, LibreOffice, a server conversion service, or non-FOSS HWP code.
- Hancom-compatible layout rendering, editing/writing, original page-count parity, and password/encrypted HWP support are not claimed.

## Permission and privacy boundary

The app is a local file browser/reader and requests broad storage access for that purpose. Broad storage access is not a FOSS license issue by itself, but it is a privacy/review-sensitive Android permission and must stay documented in `PRIVACY.md`, the F-Droid metadata, and release notes.

The `FileProvider` configuration includes broad external storage sharing support so user-triggered open-with/share actions can grant temporary read access to selected files outside app-private storage. The provider is not exported and grants access through Android intent URI grants; this behavior still needs to remain documented because static scanners may flag broad `external-path` use.

## Optional local jars

The default source package does not require optional local jars under `app/libs`. If a developer adds local jars in a private fork, that is a separate custom build and must be re-audited before calling the resulting APK FOSS.

## Release signing boundary

Release signing is conditional. If `READWIDE_KEYSTORE_PATH`, `READWIDE_KEYSTORE_PASSWORD`, `READWIDE_KEY_ALIAS`, and `READWIDE_KEY_PASSWORD` (or the legacy `TEXTVIEW_*` names) are absent, `assembleRelease` should build an unsigned release artifact instead of requiring a developer keystore. Local GitHub APK releases should be signed outside the source tree.

## GitHub vs F-Droid source handling

The GitHub source package keeps the standard Gradle wrapper. F-Droid's build verifies `gradle/wrapper/gradle-wrapper.jar` against the known-good hashes of official Gradle releases and builds with its own trusted Gradle, so the metadata does not remove it.

## Binary release notice checklist

When distributing APK/AAB files, keep these alongside the binary release assets:

- `LICENSE`
- `NOTICE`
- `THIRD_PARTY_NOTICES.md`
- `PRIVACY.md`
- `docs/FOSS_STATUS.md`
- `docs/LICENSE_REPORT_READWIDE_1_0_13.md`
- `docs/SBOM_READWIDE_1_0_13.spdx.json`

The Android packaging block excludes duplicate dependency `META-INF/LICENSE*` / `META-INF/NOTICE*` resources to avoid resource merge conflicts. That does not remove the obligation to provide project-level and third-party notices with source and binary release materials.

## Caveats

- `docs/LICENSE_REPORT_READWIDE_1_0_13.md` and `docs/SBOM_READWIDE_1_0_13.spdx.json` are direct-dependency/source-declared drafts, not a fully resolved transitive Gradle SBOM.
- A strict repository submission should regenerate a resolved dependency report/SBOM from a clean, network-enabled build environment.
- Native Maven dependencies such as libarchive-android and zstd-jni may require additional explanation or source-build handling for strict source-only repositories.
- Archive compatibility claims must stay conservative and align with `README.md`, current release notes, the RAR/7z revalidation note, and the historical support-label terminology in `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`.
