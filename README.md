# Readwide

Readwide is a local-first Android reader and file browser for TXT, Markdown, PDF, EPUB, Word-family documents, HWP/HWPX, images, comic archives, and common archive workflows.

[![Latest release](https://img.shields.io/github/v/release/k1717/Readwide?label=latest)](https://github.com/k1717/Readwide/releases)
[![Downloads](https://img.shields.io/github/downloads/k1717/Readwide/total?label=downloads)](https://github.com/k1717/Readwide/releases)

Readwide is the public successor to TextView Reader. The Android `applicationId` has been `com.readwide.manager` since 1.0.4, and 1.0.17 keeps the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.16, 1.0.15, 1.0.14, 1.0.13, 1.0.12, 1.0.11, 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6. Updating from 1.0.4/1.0.5 (which used the previous key) still requires uninstalling the old version, installing 1.0.17, then restoring bookmarks, reading positions, themes, and settings through the in-app JSON backup export/import, because of the 1.0.6 signing-key change. Builds with the older `com.textview.reader` application ID likewise install as a separate app and migrate the same way.

- Current source version: **1.0.17**
- Android metadata: `versionCode 10017`, `versionName "1.0.17"`
- License for first-party source: **Apache License 2.0**
- Source repository: `https://github.com/k1717/Readwide`
- Release page: `https://github.com/k1717/Readwide/releases`

## Local-first privacy baseline

The default build is designed for local files.

- No `INTERNET` permission in the default manifest.
- No ads, analytics, account system, Firebase, Google Play Services dependency, telemetry, cloud sync, or in-app network update checker.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- Broad storage access is requested for the full raw-path local file manager. When raw directory enumeration is unavailable, the same **Internal Storage** entry can instead use a persisted Storage Access Framework folder grant to browse and read a user-selected tree without broad/raw storage permission.
- Opening or sharing a file with another app uses Android's user-triggered intent / `FileProvider` flow; Readwide does not upload the file itself.
- Files opened *into* Readwide from another app (browser, messenger, file manager, document provider) via `ACTION_VIEW`/`BROWSABLE` are copied into an app-private cache with filename sanitization, a canonical-path containment check, a 2 GB per-file copy limit, and cache pruning; JSON backup import is capped at 256 MB.
- Imported reader fonts are copied into app-private storage and are not part of the JSON backup; the backup can record the selected font name but not the font file itself, so an imported font must be re-imported after a reinstall or distribution-channel switch.

See `PRIVACY.md` for the full local-data and cache policy.

## Main features

- TXT reader with exact page/bookmark continuity for large files and shared find-in-page options; sequential reading of very large files is O(N) (each line decoded once), so page turns and scrolling stay fast from the first page to the last.
- Markdown reader through a themed WebView visual page model; TXT remains on the exact source-page model.
- Source-safe TXT/Markdown annotations: select text to save a note or persistent highlight. Readwide keeps annotations in separate app-private data, never edits the original document, and includes them in JSON backup export/import.
- TXT-style find-in-page options for TXT, Markdown, EPUB, HWP/HWPX, and Word-family document viewers: case-sensitive, whole-word, regex, nth-match jump, and current/total match status where supported.
- PDF reader with single-page and vertical-continuous modes, bookmark restore, slider/page controls, inertial pan behavior while zoomed, and in-document text find for digital (text-based) PDFs.
- PDF controls can be hidden in portrait or landscape to release the complete Readwide title/bottom-toolbar frame. The current bitmap expands immediately without PDF rerendering; Android status and navigation bars remain visible and system-safe.
- EPUB reader through the document WebView path, including reflow/fixed-layout handling boundaries, direct image spine pages, legacy Japanese vertical-writing CSS compatibility, reader-theme integration, a dedicated global default-font setting shared with the in-book picker, scoped local scripted-spine/OPF-binding handling, point-CFI navigation, and basic foreground playback of OPF-linked SMIL text/audio cues. EPUB chrome overlays a stable WebView and releases the compact top strip; near-image-only fixed-layout pages bound incidental publisher overflow without applying that policy to text or mixed article pages.
- Landscape page viewing for EPUB and PDF: PDF single-page mode shows a two-page spread in landscape. EPUB uses a compact 12px-gutter spread for image-page books on every device and for ordinary EPUBs on Android large screens (`sw600dp` or wider); smaller phones keep one responsive text page. Portrait stays single-page, spread controls move one spread at a time (with a `3-4 / 20` style indicator), and PDF vertical continuous mode is unchanged.
- Word-family document filter:
  - OOXML Word: `.docx`, `.docm`, `.dotx`, `.dotm`
  - HWP/HWPX: `.hwp`, `.hwpx`
  - Legacy `.doc` (Word 97-2003) opens through a self-contained pure-Java reader.
- Read-aloud (text-to-speech) across the readers and viewers - plain-text/Markdown, the document viewer (EPUB, Word-family, HWP/HWPX, and Markdown), and text-based PDF: language and voice selection, speed and pitch, adjustable phrase length and pause reduction for neural voices, pause/resume, a sleep timer, a playback notification with media controls, and continuous reading that follows along as it goes (turning the page across boundaries in paginated viewers, and scrolling to follow in Markdown). On EPUB pages with publisher media overlays, the same button starts the declared local narration; long press opens Android TTS instead. A read-aloud button sits next to the bookmark button in each viewer's toolbar, and "continue reading aloud" from the main screen resumes at the saved spot. Android TTS works with installed system engines. Scanned/image-only PDFs report that they have no selectable text instead of playing silence.
- HWP/HWPX text-first reading via Apache-2.0 dogfoot libraries (`hwplib`, `hwpxlib`). This is not Hancom-compatible layout rendering.
- Image viewer with archive-backed image sequences, saved positions, direct original-byte archive-page export, optional touch page zones, adaptive image fit, and left-to-right/right-to-left flow mode. Its optional landscape spread pairs only two meaningfully portrait-shaped archive pages; square/wide and final unpaired pages remain single. Background memory pressure releases decoded image memory and reloads the same page on return; only a viewer left fully in the background for ten minutes closes back to the browser after saving its position.
- Archive browser and extraction/creation workflows for supported ZIP, 7z, TAR-family, RAR/CBR, ALZ, EGG, CAB, and LHA/LZH paths, with conservative support boundaries.
- File browser operations: recent files/folders, search/filtering, bookmarks, folder shortcuts, multi-select, copy/move/delete, archive extraction/compression queues, and progress UI. The fixed upper-right overflow beside the current location title offers a persistent compact-list or two-column tile display mode for both browser and Recent views. The drawer exposes one **Internal Storage** entry: it uses the normal raw-path browser when available and automatically routes through a persisted Android folder grant when an OEM cannot enumerate raw storage. The provider-backed compatibility path is deliberately read-oriented and supports navigation, sorting/filtering, and supported-file opening without pretending a content URI is a writable `File`. Optional cover thumbnails cover loose images, folder cover sources, ZIP/CBZ, RAR/CBR, 7z/CB7, ALZ, EGG, CAB, LHA/LZH, and TAR/CBT-family first images, PDF first pages, and raster EPUB covers in both normal and Recent lists. Generated previews are reused from bounded memory/disk caches; work is queue-bounded, transient failures can retry, changed folder covers are revalidated, and folder/archive sources fall through to later candidates when the first nominal image cannot be decoded. Folder, Recent, and multi-selection overflow menus size themselves for localized labels; the fixed top-right menu reports the active display, hidden-file, and thumbnail state.
- Reader themes, custom colors, toolbar/icon ordering, and display-rule support.

## Format support summary

This table is the current release-summary view. Use the 1.0.17 release notes and format-specific documents for current precise boundaries; `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md` is retained as a historical support-label baseline.

| Family | Current public scope |
| --- | --- |
| TXT | Main exact-page reader path, including large-file partitioned reading and legacy bookmark fallback. |
| Markdown | WebView-rendered visual-page model; bookmarks/search restore from source/content anchors where available. |
| PDF | Native Android PDF reader path with in-document text find for digital PDFs; no OCR for scanned/image-only PDFs, and no PDF editing. |
| EPUB | WebView document reader path with TXT-style search, reflow/fixed/image-spine handling, legacy Japanese vertical-writing aliases, scoped local scripts/bindings, point CFI, and basic OPF-linked foreground media-overlay narration; full browser/SMIL/CFI parity is not claimed. |
| OOXML Word | Document WebView text/layout path for covered `.docx/.docm/.dotx/.dotm` content, including shared document search. |
| HWP/HWPX | Text-first read-only extraction through `hwplib` / `hwpxlib`, including shared document search; no Hancom layout parity, editing, writing, or password/encrypted HWP support. |
| Legacy DOC | Read-only rendering through a self-contained pure-Java parser (paragraph text with alignment and indents); layout fidelity is limited compared to `.docx`. |
| ZIP/CBZ/ZIPX | Zip4j-primary listing/extraction for Store/Deflate, password, and covered split cases. Unencrypted extended methods route through Commons Compress/libarchive. WinZip-AES ZIPX supports Deflate64, BZip2, LZMA, and XZ on the authenticated Java supplement, PPMd and Zstandard through source-built libarchive 3.8.9, and JPEG/WavPack through the separate source-built FOSS native codec module. |
| 7z/CB7 | Apache Commons Compress for common methods, first-party PPMd/BCJ2 including covered AES/header-encrypted variants, libarchive forward/fallback routing for covered unencrypted special-coder cases, and standard `.001/.002/...` split chains. |
| TAR family / single compressor streams | Commons Compress for pure-Java covered combinations; Android Zstandard (`.tar.zst`/`.tzst`/`.zst`) routes through the bundled libarchive Zstd filter, including raw single-stream handling with the extraction-size cap. |
| RAR/CBR | Limited extraction/read support. libarchive-android is the primary compressed-RAR backend; first-party Java handles covered stored entries, scoped RAR3/RAR4 PPMd cases, RAR5-container algorithm v0 (RAR 5/6), bounded algorithm v1 (RAR 7), and fixture-verified RAR5 AES paths. The RAR7 path parses 80 distance codes and extended/non-power-of-two dictionary declarations up to 1 TiB without allocating that amount, but retains only 64 MiB of history and fails cleanly if a stream actually refers farther back. The classic-LZ fallback preserves/reuses the six standard RAR3 VM filter programs; custom VM bytecode remains unsupported. No complete RAR claim. |
| CAB / LHA / LZH | Read-only listing, image browsing, and extraction through the source-built libarchive backend. Archive creation, password handling, and broad multi-volume compatibility are not claimed. |
| ALZ/EGG | First-party read/extraction paths for covered ALZ Store/Deflate/BZip2 and EGG Store/Deflate/BZip2/AZO/LZMA cases, with CRC checks and split-volume support (EGG `.volN.egg`, ALZ `.a00`...). Covered ZipCrypto entries, WinZip-AES-128/256 non-solid EGG entries, and unencrypted solid EGG archives extract. LEA-encrypted EGG entries and encrypted solid EGG archives remain unsupported. |

## Quick filter buttons

The file list has quick-filter chips. Each matches by file-name extension (case-insensitive); folders are always shown regardless of the active filter.

| Filter | Matches |
| --- | --- |
| All | Every file (no extension filter). |
| General | Text-like and source/config files **except** plain `.txt`/`.text` and `.svg`: `.log .md .markdown .csv .tsv .ini .cfg .conf .properties .prop .json .jsonl .xml .html .htm .xhtml .css .scss .sass .yaml .yml .toml .sql .srt .vtt .rtf .tex .bib`, common source code (`.java .kt .kts .gradle .groovy .js .mjs .cjs .tsx .jsx .vue .svelte .py .pyw .rb .go .rs .swift .c .cc .cpp .cxx .h .hh .hpp .m .mm .cs .php .pl .pm .r .lua .dart .scala .sc .sh .bash .zsh .fish .bat .cmd .ps1 .psm1`), dotfiles (`.gitignore .gitattributes .editorconfig .env`), and `.manifest .mf .plist`. Extensionless files named `readme`, `license`/`licence`, `copying`, `notice`, `authors`, `contributors`, `changelog`, `changes`, `makefile`, `dockerfile`, `gemfile`, `rakefile`, `podfile`, `procfile` are also matched. |
| TXT | `.txt .text` |
| Archive | `.zip .zipx .cbz .rar .cbr .cab .lha .lzh .alz .egg .7z .cb7 .tar .cbt .tar.gz .tgz .tar.bz2 .tbz2 .tbz .tar.xz .txz .tar.lzma .tlz .tar.z .taz .tar.zst .tzst .tar.lz4 .gz .bz2 .xz .lzma .z .zst .lz4`, plus split-volume parts (RAR `.partN.rar` / old-style `.rNN`, 7z `.7z.NNN`, EGG volumes, ALZ `.aNN` parts, and first numeric `.001` split parts). |
| PDF | `.pdf` |
| EPUB | `.epub` |
| Word | `.doc .docx .docm .dotx .dotm` and HWP `.hwp .hwpx` (grouped together under the Word filter). |
| Image | `.jpg .jpeg .jfif .png .webp .gif .bmp .wbmp .dng .heic .heif .avif` |

The same image extension set is also what the image viewer opens (including images inside archives). Video files (`.mp4 .mkv .webm .avi .mov` and similar) get a video icon in listings but have no dedicated quick-filter chip.

## FOSS / F-Droid preparation

Readwide 1.0.17 is structured as a FOSS-friendly source release. For publication or repository submission, use the immutable tagged commit and run the clean source-builder checks below.

- First-party code is Apache-2.0.
- The default build does not bundle Junrar or RARLAB UnRAR-license code.
- HWP/HWPX support uses Apache-2.0 Java libraries.
- `THIRD_PARTY_NOTICES.md`, `docs/FOSS_STATUS.md`, license reports, and SBOM drafts are included where available.
- The checked-in F-Droid metadata file is a historical mirror through 1.0.13, not a submission-ready copy. Start from current upstream metadata and add 1.0.17 only after the final tag exists, pinning it to the immutable 40-character release commit hash.

F-Droid-facing notes are in `docs/FDROID_SUBMISSION.md`.

## Build

Requirements:

- JDK 17
- Android SDK / Android Gradle Plugin repositories available through Google Maven and Maven Central

Common local commands:

```bash
./gradlew clean testDebugUnitTest assembleDebug lintDebug
./gradlew clean assembleRelease
```

On Windows:

```powershell
.\gradlew.bat clean testDebugUnitTest assembleDebug lintDebug
.\gradlew.bat clean assembleRelease
```

Release signing is conditional. If the `READWIDE_*` (or legacy `TEXTVIEW_*`) signing environment values are absent, `assembleRelease` builds an unsigned release artifact for source-builder environments instead of requiring a private keystore. See `RELEASE_BUILD.md` for the release build and verification checklist.

## Release documents

- `CHANGELOG.md` — public changelog.
- `PATCHNOTES.md` — detailed public release notes.
- `GITHUB_UPLOAD_NOTES.md` — GitHub upload checklist.
- `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_17.md` — copy-ready GitHub release notes (per-version notes back through 1.0.2 are retained alongside).
- `docs/FDROID_SUBMISSION.md` — F-Droid submission notes.
- `docs/EPUB_COMPATIBILITY_AUDIT_1_0_16.md` — off-device audit against the 45 supplied IDPF EPUB 3 samples, including supported structural paths and known feature gaps.
- `docs/TXT_SEARCH_USAGE.md` — reader find-in-page options for TXT and WebView document readers (case sensitive, whole word, regular expression).
- `docs/FOSS_STATUS.md` — FOSS boundary and caveats.
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md` — historical archive compatibility baseline and support-label glossary; use this README and current release notes for 1.0.17 support claims.
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md` — HWP/HWPX scope and license notes; its legacy `.doc` remarks are historical because `.doc` gained a basic read-only path in 1.0.11.
- `docs/LICENSE_REPORT_READWIDE_1_0_17.md` and `docs/SBOM_READWIDE_1_0_17.spdx.json` — direct-dependency license/SBOM drafts.
