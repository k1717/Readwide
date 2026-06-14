# Readwide

Readwide is a local-first Android reader and file browser for TXT, Markdown, PDF, EPUB, Word-family documents, images, comic archives, and common archive workflows.

Readwide is the public successor to TextView Reader. The Android `applicationId` remains `com.textview.reader` so compatible installs can be updated when signed with the same key.

![Latest release](https://img.shields.io/github/v/release/k1717/Readwide?label=latest)
![Total downloads](https://img.shields.io/github/downloads/k1717/Readwide/total?label=downloads)

- Current public version: **1.0.3**
- Android metadata: `versionCode 10003`, `versionName "1.0.3"`
- License for first-party source: **Apache License 2.0**
- Source repository: `https://github.com/k1717/Readwide`
- Release page: `https://github.com/k1717/Readwide/releases`

## Local-first privacy baseline

The default build is designed for local files.

- No `INTERNET` permission in the default manifest.
- No ads, analytics, account system, Firebase, Google Play Services dependency, telemetry, cloud sync, or in-app network update checker.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- Broad storage access is requested because the app is a local reader and file browser for user-selected folders, documents, images, and archives.
- Opening or sharing a file with another app uses Android's user-triggered intent / `FileProvider` flow; Readwide does not upload the file itself.

See `PRIVACY.md` for the full local-data and cache policy.

## Main features

- TXT reader with exact page/bookmark continuity for large files.
- Markdown reader through a themed WebView visual page model; TXT remains on the exact source-page model.
- PDF reader with single-page and vertical-continuous modes, bookmark restore, slider/page controls, and inertial pan behavior while zoomed.
- EPUB reader through the document WebView path, including reflow/fixed-layout handling boundaries and reader-theme integration.
- Word-family document filter:
  - OOXML Word: `.docx`, `.docm`, `.dotx`, `.dotm`
  - HWP/HWPX: `.hwp`, `.hwpx`
  - Legacy `.doc` is recognized under the Word filter but is not rendered yet.
- HWP/HWPX text-first reading via Apache-2.0 dogfoot libraries (`hwplib`, `hwpxlib`). This is not Hancom-compatible layout rendering.
- Image viewer with archive-backed image sequences, saved positions, optional touch page zones, adaptive image fit, and left-to-right/right-to-left flow mode.
- Archive browser and extraction/creation workflows for supported ZIP, 7z, TAR-family, RAR/CBR, ALZ, and EGG paths, with conservative support boundaries.
- File browser operations: recent files/folders, search/filtering, bookmarks, folder shortcuts, multi-select, copy/move/delete, archive extraction/compression queues, and progress UI.
- Reader themes, custom colors, toolbar/icon ordering, and display-rule support.

## Format support summary

This table is a release-summary view. See the archive support matrix and the format-specific docs for precise boundaries.

| Family | Current public scope |
| --- | --- |
| TXT | Main exact-page reader path, including large-file partitioned reading and legacy bookmark fallback. |
| Markdown | WebView-rendered visual-page model; bookmarks restore from source/content anchors where available. |
| PDF | Native Android PDF reader path; no PDF editing. |
| EPUB | WebView document reader path; exact publisher layout parity is not guaranteed. |
| OOXML Word | Document WebView text/layout path for covered `.docx/.docm/.dotx/.dotm` content. |
| HWP/HWPX | Text-first read-only extraction through `hwplib` / `hwpxlib`; no Hancom layout parity, editing, writing, or password/encrypted HWP support. |
| Legacy DOC | Recognized and grouped under the Word filter; rendering is still unsupported. |
| ZIP/CBZ | Zip4j-primary listing/extraction, covered password and split cases, with Commons Compress fallback for selected non-encrypted methods. |
| 7z/CB7 | Apache Commons Compress 7z path, password forwarding for covered variants, and standard `.001/.002/...` raw split chains. |
| TAR family / single compressor streams | Commons Compress primary path for covered tar/compressor combinations and single streams. |
| RAR/CBR | Limited extraction/read support. libarchive-android is the primary compressed-RAR backend; first-party Java handles covered stored entries, scoped RAR3/RAR4 PPMd cases, RAR5 v5.0 compressed/solid cases, and fixture-verified RAR5 AES visible-header multi-volume cases. No broad encrypted, split, SFX, VM-filtered, or complete RAR support claim. |
| ALZ/EGG | First-party read/extraction paths for covered Store/Deflate/BZip2/LZMA/AZO cases with CRC checks. Encrypted/split/solid EGG remains unsupported. |

## FOSS / F-Droid preparation

Readwide 1.0.3 is prepared as a FOSS-friendly source package, but the final repository submission still needs the usual source-builder checks.

- First-party code is Apache-2.0.
- The default build does not bundle Junrar or RARLAB UnRAR-license code.
- HWP/HWPX support uses Apache-2.0 Java libraries.
- `THIRD_PARTY_NOTICES.md`, `docs/FOSS_STATUS.md`, license reports, and SBOM drafts are included where available.
- Draft F-Droid metadata is in `fdroid/metadata/com.textview.reader.yml` and must be copied to `fdroiddata/metadata/com.textview.reader.yml` with the final immutable release commit hash.

F-Droid-facing notes are in `docs/FDROID_SUBMISSION.md`.

## Build

Requirements:

- JDK 17
- Android SDK / Android Gradle Plugin repositories available through Google Maven and Maven Central

Common local commands:

```bash
./gradlew clean testDebugUnitTest assembleRelease
```

On Windows:

```powershell
.\gradlew.bat clean testDebugUnitTest assembleRelease
```

Release signing is conditional. If the `TEXTVIEW_*` signing environment values are absent, `assembleRelease` builds an unsigned release artifact for source-builder environments instead of requiring a private keystore. See `RELEASE_BUILD.md` for the release build and verification checklist.

## Release documents

- `CHANGELOG.md` — public changelog.
- `PATCHNOTES.md` — detailed public release notes.
- `GITHUB_UPLOAD_NOTES.md` — GitHub upload checklist.
- `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_3.md` — copy-ready GitHub release notes.
- `docs/FDROID_SUBMISSION.md` — F-Droid submission notes.
- `docs/FOSS_STATUS.md` — FOSS boundary and caveats.
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md` — archive compatibility wording source.
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md` — HWP/HWPX scope and license notes.
- `docs/LICENSE_REPORT_READWIDE_1_0_2.md` and `docs/SBOM_READWIDE_1_0_2.spdx.json` — direct-dependency license/SBOM drafts.
