# Readwide

Readwide is an Android app for reading local TXT, Markdown, PDF, EPUB, Word-family documents, HWP/HWPX, images and comic archives. It also browses files and creates or extracts supported archives.

[![Latest release](https://img.shields.io/github/v/release/k1717/Readwide?label=latest)](https://github.com/k1717/Readwide/releases)
[![Downloads](https://img.shields.io/github/downloads/k1717/Readwide/total?label=downloads)](https://github.com/k1717/Readwide/releases)

Readwide succeeds TextView Reader and uses the Android application ID
`com.readwide.manager`. Updates from 1.0.6 or later require the same release
signing key. To migrate from 1.0.4/1.0.5 or the older `com.textview.reader` app,
transfer bookmarks, reading positions, themes and settings through JSON backup
export/import.

- Current source version: **1.0.22**
- Verification: see [current source status](docs/CURRENT_SOURCE_STATUS_1_0_22.md) and [release readiness](docs/RELEASE_READINESS_1_0_22.md). Source-package checks do not validate an APK.
- Android metadata: `versionCode 10022`, `versionName "1.0.22"`
- License for first-party source: **Apache License 2.0**
- Source repository: `https://github.com/k1717/Readwide`
- Release page: `https://github.com/k1717/Readwide/releases`

## Changes in 1.0.22

- Manage registered folders in Settings > Security and folder permissions > Folder access. Browse and perform supported file operations through Android's storage provider, including shared internal storage and detected SD/USB volumes.
- Protect original files and existing destinations when transfers fail or sources change. Keep provider operations through rotation, offer permission recovery after eligible failures, and avoid sidebar refresh flicker.
- Fit images between visible controls without decoding them again. Preserve short-image placement, tighten panel spacing, and allow previous/next taps in the bottom panel's empty app area.
- Recover failed TXT index and load/search requests. Protect unreadable bookmark and reading-position stores from replacement, while keeping page calculations, partitions and existing bookmark coordinates.
- Choose per-viewer tap zones or follow TXT. Regroup settings, use compact two-line tap/folder rows, and show the installed version beside App information.
- Correct EGG AZO history decoding and encryption-header lengths, RAR header integrity/framing and archive source/cleanup handling. Scoped host comparisons passed; encrypted EGG filenames and some complex producer combinations remain outside verified support.

See [Changelog](CHANGELOG.md), [Patch Notes](PATCHNOTES.md) and the
[GitHub release notes](docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_22.md).
Implementation details are in the [development notes](docs/DEV_CHANGES_1_0_22.md)
and [code map](docs/CODE_MAP.md). Build and runtime verification are separate from
source-package checks; see [current source status](docs/CURRENT_SOURCE_STATUS_1_0_22.md).

## Local-first privacy baseline

- No `INTERNET` permission in the default manifest.
- No ads, analytics, account system, Firebase, Google Play Services dependency, telemetry, cloud sync, or in-app network update checker.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- The raw-path file manager requests broad storage access. Folder access uses persisted Storage Access Framework grants for selected directories without that permission. Provider browsing and file operations require the corresponding read/write grant and provider capabilities; they do not bypass Android's restrictions.
- Opening or sharing a file with another app uses Android's explicitly initiated intent / `FileProvider` flow; Readwide does not upload the file itself.
- Read-aloud sends spoken text to the installed Android TTS engine. A network-required voice can use the engine's online service; its data handling is separate from Readwide. Choose an offline voice for local synthesis.
- Files opened *into* Readwide from another app (browser, messenger, file manager, document provider) via `ACTION_VIEW`/`BROWSABLE` are copied into an app-private cache with filename sanitization, a canonical-path containment check, a 2 GB per-file copy limit, and cache pruning; JSON backup import is capped at 256 MB.
- Imported reader fonts are stored in app-private storage. JSON backups record the selected font name, not the font file. Re-import fonts after a reinstall or distribution-channel switch.

See `PRIVACY.md` for the full local-data and cache policy.

## Main features

- TXT reading with bookmarks, saved positions, large-file support and configurable
  text display rules.
- TXT Contents with configurable heading detection, custom patterns and manual/hidden entries. Durable Contents settings and edits are included in JSON backups; generated indexes and document files are not. Records remain bound to the original file/content identity.
- Markdown, EPUB, Word-family and HWP/HWPX reading with shared search options.
- Notes and highlights in TXT and Markdown, stored separately from the original
  document and included in JSON backups.
- PDF reading in single-page, landscape spread and continuous-scroll modes, with
  zoom, bookmarks, page controls and search in text-based PDFs.
- EPUB support for reflowable text, image pages, Japanese vertical writing, reader
  themes, point-CFI navigation and basic local media-overlay narration.
- EPUB paragraph alignment: Book default, Left / start-aligned, or Justified in
  More and Settings. Applies to reflowable horizontal text; explicit centered and
  right-aligned styles remain intact. TXT alignment is independent.
- EPUB-specific text size and line spacing, with settings backup/reset. Paragraph
  spacing applies to reflowable horizontal text; complex CSS expressions and font
  shorthands retain the book’s values.
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

Support is read-only unless a creation or editing option is available. A bundled
codec does not guarantee every encryption, split, solid or filter combination.
See the [archive boundary baseline](docs/ARCHIVE_DECODE_BOUNDARIES_1_0_20.md) and
[1.0.21 additions](docs/DEV_CHANGES_1_0_21.md#archive-compatibility) for exact codec
profiles, unsupported cases and storage requirements.

| Family | Current public scope |
| --- | --- |
| TXT | Exact-page reading, large-file partitions, bookmarks, local annotations, configurable Contents and text display rules. |
| Markdown | Lightweight WebView previews with search and source-anchored restoration; no full CommonMark or relative-resource parity. |
| PDF | Platform rendering, text-based search/read-aloud, zoom and bookmarks; no OCR or editing. |
| EPUB | Reflowable/image/fixed-layout content, scoped CSS and vertical writing, point CFI and local media overlays; not full browser/SMIL/CFI parity. |
| Word | Read-only DOC/DOCX/DOCM/DOTX/DOTM previews with partial layout fidelity; fields are not recalculated. |
| HWP/HWPX | Text-first read-only previews; no Hancom layout parity, editing or encrypted HWP support. |
| ZIP/ZIPX/CBZ | Ordinary and supplemental codecs, ZIP64, canonical filenames and PKZIP splits; admitted ZipCrypto/AES paths retain integrity checks. |
| 7z/CB7 | Registered codecs/filters, AES, solid folders, numbered splits and checked prefixed content under these filenames; no executable-file recognition. Unknown graphs/properties and missing external dictionaries remain excluded. |
| RAR/CBR | Scoped native/first-party stored, LZ/PPMd, solid, encrypted and split paths, including RAR5/6/7; no complete RAR compatibility or link restoration. |
| TAR / compressor streams | Supported TAR/sparse and gzip/BZip2/XZ/LZMA/LZ4/Zstandard paths; completion and trailer checks depend on the selected route. |
| ALZ/EGG | Supported codecs and splits, including ALZ method 3 (Obfuscated Deflate) in source, ALZ ZipCrypto and EGG ZipCrypto/AES/LEA block-owner paths. Method-3 producer validation is pending; encrypted EGG names remain excluded. |
| CAB | Single and scoped linked-set read-only paths; compressed-multipart producer/device validation remains separate. |
| LHA/LZH/PMA | Stored LH0/LZ4 and CRC-bearing level-0 PM0, compressed LH4–LH7 and scoped LHA/LZH Amiga byte splits. PM1/PM2, other legacy methods and general multi-disc semantics remain excluded. |

Archive extraction and preview no longer impose fixed payload caps. Free-space
checks, format/array limits and device memory still limit decoding. No universal
speedup has been measured.

## Quick filter buttons

The file list has quick-filter chips. Each matches by file-name extension (case-insensitive); folders are always shown regardless of the active filter.

| Filter | Matches |
| --- | --- |
| All | Every file (no extension filter). |
| General | Text-like and source/config files **except** plain `.txt`/`.text` and `.svg`: `.log .md .markdown .csv .tsv .ini .cfg .conf .properties .prop .json .jsonl .xml .html .htm .xhtml .css .scss .sass .yaml .yml .toml .sql .srt .vtt .rtf .tex .bib`, common source code (`.java .kt .kts .gradle .groovy .js .mjs .cjs .tsx .jsx .vue .svelte .py .pyw .rb .go .rs .swift .c .cc .cpp .cxx .h .hh .hpp .m .mm .cs .php .pl .pm .r .lua .dart .scala .sc .sh .bash .zsh .fish .bat .cmd .ps1 .psm1`), dotfiles (`.gitignore .gitattributes .editorconfig .env`), and `.manifest .mf .plist`. Extensionless files named `readme`, `license`/`licence`, `copying`, `notice`, `authors`, `contributors`, `changelog`, `changes`, `makefile`, `dockerfile`, `gemfile`, `rakefile`, `podfile`, `procfile` are also matched. |
| TXT | `.txt .text` |
| Archive | `.zip .zipx .cbz .rar .cbr .cab .lha .lzh .pma .alz .egg .7z .cb7 .tar .cbt .tar.gz .tgz .tar.bz2 .tbz2 .tbz .tar.xz .txz .tar.lzma .tlz .tar.z .taz .tar.zst .tzst .tar.lz4 .gz .bz2 .xz .lzma .z .zst .lz4`, plus split-volume parts (RAR `.partN.rar` / old-style `.rNN`, 7z `.7z.NNN`, EGG volumes, ALZ `.a00`–`.j99` and wide `.aNN` aliases with a matching first volume, and first numeric `.001` split parts). |
| PDF | `.pdf` |
| EPUB | `.epub` |
| Word | `.doc .docx .docm .dotx .dotm` and HWP `.hwp .hwpx` (grouped together under the Word filter). |
| Image | `.jpg .jpeg .jfif .png .webp .gif .bmp .wbmp .dng .heic .heif .avif` |

The same image extension set is also what the image viewer opens (including images inside archives). Video files (`.mp4 .mkv .webm .avi .mov` and similar) get a video icon in listings but have no dedicated quick-filter chip.

## FOSS / F-Droid preparation

Readwide 1.0.22 keeps the existing FOSS source and dependency layout. Use the immutable tagged commit and the guidance below for publication or repository submission.

- First-party code is Apache-2.0.
- The default build does not bundle Junrar or RARLAB UnRAR-license code.
- HWP/HWPX support uses Apache-2.0 Java libraries.
- `THIRD_PARTY_NOTICES.md`, `docs/FOSS_STATUS.md`, license reports, and the source dependency SBOM are included.
- Checked-in F-Droid metadata mirrors releases through 1.0.21 and needs updating before submission. Start from current upstream metadata. Add 1.0.22 after the final tag exists and pin it to the immutable 40-character release commit hash.

F-Droid-facing notes are in `docs/FDROID_SUBMISSION.md`.

## Build

Requirements:

- JDK 17
- Android SDK / Android Gradle Plugin repositories available through Google Maven and Maven Central

Release assembly (no test execution or installation):

```bash
./gradlew clean assembleRelease
```

On Windows:

```powershell
.\gradlew.bat clean assembleRelease
```

Release signing is conditional. If the `READWIDE_*` (or legacy `TEXTVIEW_*`) signing environment values are absent, `assembleRelease` builds an unsigned release artifact for source-builder environments instead of requiring a private keystore. See `RELEASE_BUILD.md` for the release build and verification checklist.

## Release documents

- [Documentation index](docs/README.md) — current guides, format references, component provenance and historical release records.
- `CHANGELOG.md` — public changelog.
- `PATCHNOTES.md` — detailed public release notes.
- `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_22.md` — GitHub release notes for 1.0.22 (per-version notes back through 1.0.2 are retained alongside).
- `docs/FDROID_SUBMISSION.md` — F-Droid submission notes.
- `docs/EPUB_COMPATIBILITY_AUDIT_1_0_16.md` — historical 1.0.16 off-device audit of 45 IDPF EPUB 3 samples; not validation of the current viewer.
- `docs/TXT_SEARCH_USAGE.md` — reader find-in-page options for TXT and WebView document readers (case sensitive, whole word, regular expression).
- `docs/FOSS_STATUS.md` — FOSS boundary and caveats.
- `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md` — historical archive compatibility baseline and support-label glossary; use this README and current source status for current support boundaries.
- `docs/HWP_SUPPORT_STATUS_READWIDE_1_0_2.md` — HWP/HWPX scope and license notes; its legacy `.doc` remarks are historical because `.doc` gained a basic read-only path in 1.0.11.
- [1.0.21 license report](docs/LICENSE_REPORT_READWIDE_1_0_21.md) and [source/direct-dependency SBOM](docs/SBOM_READWIDE_1_0_21.spdx.json) — inherited dependency baseline, not a 1.0.22 artifact inventory or resolved transitive audit.
