# Readwide

Readwide is a local-first Android reader and file browser for TXT, Markdown, PDF, EPUB, Word-family documents, HWP/HWPX, images, comic archives, and common archive workflows.

[![Latest release](https://img.shields.io/github/v/release/k1717/Readwide?label=latest)](https://github.com/k1717/Readwide/releases)
[![Downloads](https://img.shields.io/github/downloads/k1717/Readwide/total?label=downloads)](https://github.com/k1717/Readwide/releases)

Readwide is the public successor to TextView Reader. The Android `applicationId` has been `com.readwide.manager` since 1.0.4, and 1.0.14 keeps the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.13, 1.0.12, 1.0.11, 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6. Updating from 1.0.4/1.0.5 (which used the previous key) still requires uninstalling the old version, installing 1.0.14, then restoring bookmarks, reading positions, themes, and settings through the in-app JSON backup export/import, because of the 1.0.6 signing-key change. Builds with the older `com.textview.reader` application ID likewise install as a separate app and migrate the same way.

- Current public version: **1.0.14**
- Android metadata: `versionCode 10014`, `versionName "1.0.14"`
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
- Files opened *into* Readwide from another app (browser, messenger, file manager, document provider) via `ACTION_VIEW`/`BROWSABLE` are copied into an app-private cache with filename sanitization, a canonical-path containment check, a 2 GB per-file copy limit, and cache pruning; JSON backup import is capped at 256 MB.
- Imported reader fonts are copied into app-private storage and are not part of the JSON backup; the backup can record the selected font name but not the font file itself, so an imported font must be re-imported after a reinstall or distribution-channel switch.

See `PRIVACY.md` for the full local-data and cache policy.

## Main features

- TXT reader with exact page/bookmark continuity for large files and shared find-in-page options; sequential reading of very large files is O(N) (each line decoded once), so page turns and scrolling stay fast from the first page to the last.
- Markdown reader through a themed WebView visual page model; TXT remains on the exact source-page model.
- TXT-style find-in-page options for TXT, Markdown, EPUB, HWP/HWPX, and Word-family document viewers: case-sensitive, whole-word, regex, nth-match jump, and current/total match status where supported.
- PDF reader with single-page and vertical-continuous modes, bookmark restore, slider/page controls, inertial pan behavior while zoomed, and in-document text find for digital (text-based) PDFs.
- EPUB reader through the document WebView path, including reflow/fixed-layout handling boundaries and reader-theme integration.
- Landscape two-page viewing for EPUB and PDF: in landscape orientation, EPUB pages and PDF single-page mode automatically show two pages side by side, on phones and tablets alike; portrait stays single-page, page controls move one spread at a time (with a `3-4 / 20` style page indicator), and PDF vertical continuous mode is unchanged.
- Word-family document filter:
  - OOXML Word: `.docx`, `.docm`, `.dotx`, `.dotm`
  - HWP/HWPX: `.hwp`, `.hwpx`
  - Legacy `.doc` (Word 97-2003) opens through a self-contained pure-Java reader.
- Read-aloud (text-to-speech) across the readers and viewers - plain-text/Markdown, the document viewer (EPUB, Word-family, HWP/HWPX, and Markdown), and text-based PDF: language and voice selection, speed and pitch, adjustable phrase length and pause reduction for neural voices, pause/resume, a sleep timer, a playback notification with media controls, and continuous reading that follows along as it goes (turning the page across boundaries in paginated viewers, and scrolling to follow in Markdown). A read-aloud button sits next to the bookmark button in each viewer's toolbar, and "continue reading aloud" from the main screen resumes at the saved spot. It uses the Android `TextToSpeech` API, so any installed engine works, including neural engines exposed as system TTS. Scanned/image-only PDFs report that they have no selectable text instead of playing silence.
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
| Markdown | WebView-rendered visual-page model; bookmarks/search restore from source/content anchors where available. |
| PDF | Native Android PDF reader path with in-document text find for digital PDFs; no OCR for scanned/image-only PDFs, and no PDF editing. |
| EPUB | WebView document reader path with TXT-style search dialog; exact publisher layout parity is not guaranteed. |
| OOXML Word | Document WebView text/layout path for covered `.docx/.docm/.dotx/.dotm` content, including shared document search. |
| HWP/HWPX | Text-first read-only extraction through `hwplib` / `hwpxlib`, including shared document search; no Hancom layout parity, editing, writing, or password/encrypted HWP support. |
| Legacy DOC | Read-only rendering through a self-contained pure-Java parser (paragraph text with alignment and indents); layout fidelity is limited compared to `.docx`. |
| ZIP/CBZ | Zip4j-primary listing/extraction, covered password and split cases, with Commons Compress fallback for selected non-encrypted methods. |
| 7z/CB7 | Apache Commons Compress 7z path, password forwarding for covered variants, and standard `.001/.002/...` raw split chains. |
| TAR family / single compressor streams | Commons Compress primary path for covered tar/compressor combinations and single streams. |
| RAR/CBR | Limited extraction/read support. libarchive-android is the primary compressed-RAR backend; first-party Java handles covered stored entries, scoped RAR3/RAR4 PPMd cases, RAR5 v5.0 compressed/solid cases, and fixture-verified RAR5 AES visible-header multi-volume cases. No broad encrypted, split, SFX, VM-filtered, or complete RAR support claim. |
| ALZ/EGG | First-party read/extraction paths for covered Store/Deflate/BZip2/LZMA/AZO cases with CRC checks, split volumes (EGG `.volN.egg`, ALZ `.a00`...), and ZipCrypto-encrypted entries. AES/LEA-encrypted and solid EGG remain unsupported. |

## Quick filter buttons

The file list has quick-filter chips. Each matches by file-name extension (case-insensitive); folders are always shown regardless of the active filter.

| Filter | Matches |
| --- | --- |
| All | Every file (no extension filter). |
| General | Text-like and source/config files **except** plain `.txt`/`.text` and `.svg`: `.log .md .markdown .csv .tsv .ini .cfg .conf .properties .prop .json .jsonl .xml .html .htm .xhtml .css .scss .sass .yaml .yml .toml .sql .srt .vtt .rtf .tex .bib`, common source code (`.java .kt .kts .gradle .groovy .js .mjs .cjs .tsx .jsx .vue .svelte .py .pyw .rb .go .rs .swift .c .cc .cpp .cxx .h .hh .hpp .m .mm .cs .php .pl .pm .r .lua .dart .scala .sc .sh .bash .zsh .fish .bat .cmd .ps1 .psm1`), dotfiles (`.gitignore .gitattributes .editorconfig .env`), and `.manifest .mf .plist`. Extensionless files named `readme`, `license`/`licence`, `copying`, `notice`, `authors`, `contributors`, `changelog`, `changes`, `makefile`, `dockerfile`, `gemfile`, `rakefile`, `podfile`, `procfile` are also matched. |
| TXT | `.txt .text` |
| Archive | `.zip .zipx .cbz .rar .cbr .alz .egg .7z .cb7 .tar .cbt .tar.gz .tgz .tar.bz2 .tbz2 .tbz .tar.xz .txz .tar.lzma .tlz .tar.z .taz .tar.zst .tzst .tar.lz4 .gz .bz2 .xz .lzma .z .zst .lz4`, plus split-volume parts (RAR `.partN.rar` / old-style `.rNN`, 7z `.7z.NNN`, EGG volumes, ALZ `.aNN` parts, and first numeric `.001` split parts). |
| PDF | `.pdf` |
| EPUB | `.epub` |
| Word | `.doc .docx .docm .dotx .dotm` and HWP `.hwp .hwpx` (grouped together under the Word filter). |
| Image | `.jpg .jpeg .jfif .png .webp .gif .bmp .wbmp .dng .heic .heif .avif` |

The same image extension set is also what the image viewer opens (including images inside archives). Video files (`.mp4 .mkv .webm .avi .mov` and similar) get a video icon in listings but have no dedicated quick-filter chip.

## FOSS / F-Droid preparation

Readwide 1.0.14 is prepared as a FOSS-friendly source package, but the final repository submission still needs the usual source-builder checks.

- First-party code is Apache-2.0.
- The default build does not bundle Junrar or RARLAB UnRAR-license code.
- HWP/HWPX support uses Apache-2.0 Java libraries.
- `THIRD_PARTY_NOTICES.md`, `docs/FOSS_STATUS.md`, license reports, and SBOM drafts are included where available.
- Draft F-Droid metadata is in `fdroid/metadata/com.readwide.manager.yml` and must be copied to `fdroiddata/metadata/com.readwide.manager.yml` with the final immutable release commit hash.

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

Release signing is conditional. If the `READWIDE_*` (or legacy `TEXTVIEW_*`) signing environment values are absent, `assembleRelease` builds an unsigned release artifact for source-builder environments instead of requiring a private keystore. See `RELEASE_BUILD.md` for the release build and verification checklist.

## Release documents

- `CHANGELOG.md` — public changelog.
- `PATCHNOTES.md` — detailed public release notes.
- `GITHUB_UPLOAD_NOTES.md` — GitHub upload checklist.
- `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_14.md` — copy-ready GitHub release notes (per-version notes back through 1.0.2 are retained alongside).
- `docs/FDROID_SUBMISSION.md` — F-Droid submission notes.
- `docs/TXT_SEARCH_USAGE.md` — reader find-in-page options for TXT and WebView document readers (case sensitive, whole word, regular expression).
- `docs/FOSS_STATUS.md` — FOSS boundary and caveats.
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md` — historical archive compatibility baseline and support-label glossary; use this README and current release notes for 1.0.14 support claims.
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md` — HWP/HWPX scope and license notes; its legacy `.doc` remarks are historical because `.doc` gained a basic read-only path in 1.0.11.
- `docs/LICENSE_REPORT_READWIDE_1_0_14.md` and `docs/SBOM_READWIDE_1_0_14.spdx.json` — direct-dependency license/SBOM drafts.
