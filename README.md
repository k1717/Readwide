# Readwide

Readwide is a local-first Android reader and file browser for TXT, Markdown, PDF, EPUB, Word-family documents, HWP/HWPX, images, comic archives, and common archive workflows.

[![Latest release](https://img.shields.io/github/v/release/k1717/Readwide?label=latest)](https://github.com/k1717/Readwide/releases)
[![Downloads](https://img.shields.io/github/downloads/k1717/Readwide/total?label=downloads)](https://github.com/k1717/Readwide/releases)

Readwide succeeds TextView Reader and uses the Android application ID
`com.readwide.manager`. Updates from 1.0.6 or later require the same release
signing key. Users of 1.0.4/1.0.5 or the older `com.textview.reader` app can move
their bookmarks, reading positions, themes and settings through JSON backup
export/import.

- Current source version: **1.0.19**
- Android metadata: `versionCode 10019`, `versionName "1.0.19"`
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

- TXT reading with bookmarks, saved positions, large-file support and configurable
  text display rules.
- Markdown, EPUB, Word-family and HWP/HWPX reading with shared search options.
- Notes and highlights in TXT and Markdown, stored separately from the original
  document and included in JSON backups.
- PDF reading in single-page, landscape spread and continuous-scroll modes, with
  zoom, bookmarks, page controls and search in text-based PDFs.
- EPUB support for reflowable text, image pages, Japanese vertical writing, reader
  themes, point-CFI navigation and basic local media-overlay narration.
- Landscape EPUB spreads for image-page books on phones and tablets, and for
  ordinary text EPUBs on large screens (`sw600dp` or wider).
- Read-aloud with installed Android TTS engines: voice, speed, pitch, pause/resume,
  sleep timer, playback controls and saved reading positions.
- Image and comic-archive reading with touch page zones, zoom, optional landscape
  spreads and left-to-right or right-to-left reading order.
- File browsing with List or Tiles, recent files, pinned folders, thumbnails,
  search, sorting, bookmarks and multi-selection.
- Copy, move, delete, extraction and archive creation for supported formats.
- Reader themes, custom colors, toolbar ordering and JSON backup export/import.

## Format support summary

This table is the current release-summary view. Use the 1.0.19 release notes and format-specific documents for current precise boundaries; `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md` is retained as a historical support-label baseline.

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
| 7z/CB7 | Common methods through Apache Commons Compress, with supplemental PPMd/BCJ2, ARM64/RISC-V and other supported filter combinations. Covered AES/header-encrypted archives and standard `.001/.002/...` split chains are supported; decoder-graph and split-naming limits still apply. |
| TAR family / single compressor streams | Commons Compress for pure-Java covered combinations; Android Zstandard (`.tar.zst`/`.tzst`/`.zst`) routes through the bundled libarchive Zstd filter, including raw single-stream handling with shared storage-based output accounting. |
| RAR/CBR | Limited read/extraction support through libarchive and first-party Java readers, including covered stored, compressed, solid, split and encrypted cases. The mixed LZ/PPMd fallback is limited to plain single-volume archives; stored members within solid runs, custom VM programs and unsupported filter arrangements remain excluded. Large RAR5/RAR7 history can require temporary storage. |
| CAB / LHA / LZH | Read-only listing, image browsing, and extraction through the source-built libarchive backend. Archive creation, password handling, and broad multi-volume compatibility are not claimed. |
| ALZ/EGG | Covered ALZ Store/Deflate/BZip2 and EGG Store/Deflate/BZip2/AZO/LZMA, with split-volume support. EGG checks decoded lengths, nonzero block CRCs and authentication for supported AES entries. ZipCrypto, AES-128/256 non-solid EGG and unencrypted solid EGG are covered; LEA and encrypted-solid EGG remain unsupported. |

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

Readwide 1.0.19 is structured as a FOSS-friendly source release. For publication or repository submission, use the immutable tagged commit and run the clean source-builder checks below.

- First-party code is Apache-2.0.
- The default build does not bundle Junrar or RARLAB UnRAR-license code.
- HWP/HWPX support uses Apache-2.0 Java libraries.
- `THIRD_PARTY_NOTICES.md`, `docs/FOSS_STATUS.md`, license reports, and the source dependency SBOM are included.
- The checked-in F-Droid metadata file is a historical mirror through 1.0.13, not a submission-ready copy. Start from current upstream metadata and add 1.0.19 only after the final tag exists, pinning it to the immutable 40-character release commit hash.

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
- `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_19.md` — copy-ready GitHub release notes (per-version notes back through 1.0.2 are retained alongside).
- `docs/FDROID_SUBMISSION.md` — F-Droid submission notes.
- `docs/EPUB_COMPATIBILITY_AUDIT_1_0_16.md` — off-device audit against the 45 supplied IDPF EPUB 3 samples, including supported structural paths and known feature gaps.
- `docs/TXT_SEARCH_USAGE.md` — reader find-in-page options for TXT and WebView document readers (case sensitive, whole word, regular expression).
- `docs/FOSS_STATUS.md` — FOSS boundary and caveats.
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md` — historical archive compatibility baseline and support-label glossary; use this README and current release notes for 1.0.19 support claims.
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md` — HWP/HWPX scope and license notes; its legacy `.doc` remarks are historical because `.doc` gained a basic read-only path in 1.0.11.
- [1.0.19 license report](docs/LICENSE_REPORT_READWIDE_1_0_19.md) and [source/direct-dependency SBOM](docs/SBOM_READWIDE_1_0_19.spdx.json) — current release identity and updated declared dependencies; not a resolved transitive audit.
