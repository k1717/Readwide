# Readwide 1.0.11 development changes (vs 1.0.10)

Baseline: 1.0.10 (versionCode 10010), the released source package.

Public-facing docs are updated to 1.0.11: README, CHANGELOG.md, PATCHNOTES.md,
fastlane changelog 10011.txt (en-US + ko-KR), THIRD_PARTY_NOTICES.md,
docs/FOSS_STATUS.md, docs/FDROID_SUBMISSION.md, RELEASE_BUILD.md,
GITHUB_UPLOAD_NOTES.md, BUILD_FIX_NOTES.md, and the new versioned docs
docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_11.md,
docs/LICENSE_REPORT_READWIDE_1_0_11.md, docs/SBOM_READWIDE_1_0_11.spdx.json. The
dependency set is identical to 1.0.10 (no new dependency); PDF read-aloud reuses
the `pdfbox-android` library already bundled for PDF search, so the license
report and SBOM carry the same packages as 1.0.10 with only the version stamp
and two descriptions changed. "Current public version" is 1.0.11.

Note: the F-Droid metadata's 1.0.11 build entry `commit` field must be set to the
full 40-character commit hash the `v1.0.11` tag points to (this app's F-Droid
maintainer requires a full hash, not the tag name) once the 1.0.11 tag exists. The
earlier release entries (1.0.7 through 1.0.10) already carry their real commit
hashes in the maintained repository; if this source snapshot still shows a
placeholder for 1.0.10, that is only the snapshot being behind the repository.

1.0.11 is a broad release spanning several tracks. Everything is pure-Java,
minSdk 24, no new dependency. The per-feature technical detail lives in the
dedicated versioned docs listed under each track below; this file is the
development-level overview of the delta from 1.0.10.

## Read-aloud (TTS): document/PDF/Markdown viewers, controls, and autostart

Design: `docs/TTS_STATUS_AND_EPUB_PDF_DESIGN_READWIDE_1_0_11.md`.

The playback controller (`ReaderTtsController`) was decoupled from
`ReaderActivity` behind two small interfaces: `TtsTextSource` (the
text/position/highlight surface) and `TtsHost` (everything the controller needs
from its owning activity), with `ReaderDialogStyleController` generalized behind
`ReaderDialogStyleHost`. `CustomReaderView` satisfies `TtsTextSource` verbatim,
so the text/Markdown path is unchanged by construction.

- Stage 2 - document viewer (`DocumentPageActivity`: EPUB, `.docx`, `.doc`,
  HWP/HWPX): `DocumentTtsTextSource` concatenates `FileUtils.htmlToPlainText`
  over the loaded pages into one page-indexed buffer (per-page start offsets,
  `\n` separator, binary-search page mapping). Built on the document executor on
  first use, dropped on reload.
- Stage 3 - PDF viewer (`PdfReaderActivity`): `PdfRenderer` has no text layer, so
  `PdfPlainTextExtractor` runs one PdfBox `PDFTextStripper` pass (mirroring the
  search engine's stripper, text-only) and `PdfTtsTextSource` builds the same
  page-indexed buffer. `hasAnyText()` gates scanned/image-only PDFs to a
  "no selectable text" message. `TtsPlaybackService` gained a third `host_kind`
  (`HOST_PDF`) so the notification reopens the PDF viewer; `MainActivity`'s resume
  prompt routes `.pdf` there.
- Neural-engine fixes: `applySelectedLanguage` falls back engine -> device
  default voice instead of going silent on `LANG_NOT_SUPPORTED`, and
  cross-page prefetch queues the next page's opening segments ahead of the seam
  for documents whose text is fully resident.
- Text-level controls (issue #7): phrase length (`TtsSegmenter.phraseLengthToChars`,
  200/400/700 chars) and pause reduction (`normalizeForSpeech`: Medium drops
  commas, Aggressive also softens sentence stops), both surfaced as tap-to-cycle
  rows in the TTS dialog and covered by `TtsSegmenterTest`. Text transforms only -
  no audio path - so they stay within the `TextToSpeech`-delegated design.
- Toolbar button: a read-aloud button was added to the PDF viewer's bottom
  toolbar (`pdf_tts`, gated visible once the PDF is loaded), and the read-aloud
  button's default position is standardized immediately right of the bookmark
  button in the document and PDF viewers via `ButtonOrderManager.defaultItems`
  (the runtime order source, user-customizable). The text reader's order is
  unchanged.
- Markdown read-aloud in the document viewer: `documentSupportsTts()` now allows
  Markdown. Text extraction reuses `DocumentTtsTextSource` (Markdown fills
  `pages` with one rendered page). Following is approximate -
  `MarkdownTtsFollowMath.approximateSourceOffset` maps the spoken plain-text
  position back to a raw-source offset (probe search, proportional fallback) and
  `scrollMarkdownToSourceOffset` scrolls there, driven per segment through the
  `setTtsHighlightRange` -> `onDocumentTtsSegmentSpoken` callback and throttled to
  ~half a visual page. Covered by `MarkdownTtsFollowMathTest`.
- Autostart on resume: `DocumentPageActivity` gained `EXTRA_AUTOSTART_TTS`;
  `loadFromIntent` polls until ready, builds the buffer off-thread, and calls the
  controller's new public `autoStartOrResume` (resume from saved position, else
  start from top). `MainActivity`'s resume prompt now routes Markdown/EPUB/Word to
  the document viewer with this extra, keeping the saved char offset in the same
  coordinate space it was saved in.

## Documents

- Legacy binary `.doc` (Word 97-2003) now renders through a self-contained
  compound-file reader (DIFAT/FAT walk with bounds and cycle detection, piece
  table, UTF-16 and 8-bit/compressed piece forms), mapped to the shared
  `RenderedDocument` model.
- HWP/HWPX embedded raster images (PNG/JPEG/GIF/BMP/WebP) render as data URIs at
  authored size; unrenderable WMF/EMF/OLE pictures show a `.rw-image-missing`
  placeholder frame (language-neutral CSS glyph, no new translated string) via
  `RenderedImage.unrenderablePlaceholder`.
- EPUB reflowable pages cap image width and height to the viewport; HWP paragraph
  formatting was improved.

## Archives

Encryption/boundary detail: `docs/RAR_7Z_SPLIT_ENCRYPTION_REVALIDATION_READWIDE_1_0_11.md`,
`docs/RAR5_HEADER_ENCRYPTION_READWIDE_1_0_11.md`,
`docs/SEVENZ_PPMD_READER_READWIDE_1_0_11.md`,
`docs/SEVENZ_BCJ2_READER_READWIDE_1_0_11.md`.

- Zstandard/LZ4 added to the TAR family and single-compressor streams via the
  already-bundled codecs (no new dependency).
- 7z: first-party PPMd decoder and BCJ2 reader (including AES-encrypted PPMd/BCJ2);
  Deflate64 coverage verified; `getEntries()` used for listing so PPMd/BCJ2
  archives are browseable.
- EGG: real-ALZip-file fixes (archive header, block-header END field, LZMA
  properties offset), split volumes, solid extraction, ALZ 4.x bzip2 decode, and
  AES-128/256 encrypted-entry extraction.
- RAR: RAR5 header-encrypted (`-hp`) archives open; the RAR/7z encryption
  boundaries were re-audited against real WinRAR 7.00 and p7zip archives, with the
  unsupported messages made precise. RAR5 stored and compressed AES extraction is
  verified byte-for-byte against real WinRAR files; RAR5 header-encryption is a
  hard unsupported boundary in every bundled backend.
- Legacy-encoding filename decoding no longer breaks per name.

## Reader fixes

- TXT reader: switching files no longer resurrects the previous file (stale
  background-restore intent discard, target guard, `ReaderRestoreTargetMath`).
- Large-TXT autosave: during an in-flight partition switch, `saveReadingState`
  now takes the char position and anchors from the pending page's exact anchor
  when the exact page index is ready, so the persisted page number and
  position/anchors stay consistent (`ReaderSaveAnchorMath`).
- Image preview: smoother continuous paging through sequential archives; recent
  list swipe angle gate refined.

## Verification note

The sandbox used for authoring has no Android SDK/Gradle, so verification is by
per-file brace/paren gate, full `javalang` parse, minimal-stub compilation of
Android-dependent code, and Python/JVM real-file mirrors for archive decode,
crypto key derivation, and offset math (RAR5 AES against real WinRAR archives,
7z PPMd/BCJ2 boundaries against real p7zip archives). Run the full
`gradlew.bat clean testDebugUnitTest assembleRelease` on a real build machine
before release.
