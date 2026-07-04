# Readwide 1.0.12 development changes (vs 1.0.11)

A small read-aloud patch release. Three changes, all in the TTS path; the
dependency set is identical to 1.0.11 (no new dependency, no version bumps), so
the license report and SBOM carry the same packages with only the version stamp
changed. minSdk 24, compileSdk/targetSdk 35 unchanged. The user-facing summary
lives in `docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_12.md`; the full technical
detail is in `PATCHNOTES.md` under the 1.0.12 heading.

## PDF read-aloud start crash (missing PdfBox init)

- Root cause: `PDFBoxResourceLoader.init` was called only in
  `PdfSearchController`'s constructor, so read-aloud as the first PdfBox user in
  the process ran `PDFTextStripper` against an uninitialized resource loader.
  PdfBox's font/glyph machinery then fails partly with `Error`s
  (`ExceptionInInitializerError`, then `NoClassDefFoundError` on retry), which
  the extractor's `catch (IOException | RuntimeException)` did not contain; an
  uncaught throw on the bare extraction executor kills the process.
- Fix, three parts: (1) `PdfPlainTextExtractor.extractPageText` now takes a
  `Context` and initializes the loader itself, mirroring the search
  controller's best-effort pattern; (2) the extractor catch was widened to
  `Throwable` and the contained failure is logged (`ReadwideTts` tag) so a
  "says scanned but has text" report stays diagnosable; (3) the background
  build in `PdfReaderActivity.showPdfTtsDialog` is guarded so a failure resets
  `pdfTtsTextBuilding` (no permanent "Preparing read-aloud...") and a no-text
  result is still cached for the scanned-PDF fast path.
- Verified by a stub harness that makes the stripper throw
  `ExceptionInInitializerError`: the pre-fix extractor lets it escape (process
  death), the fixed one returns an empty map.

## Quotation-mark muting at Aggressive pause reduction

- `TtsSegmenter.normalizeForSpeech` at level 2 now mutes
  `["\u201C\u201D\u201E\u00AB\u00BB\u300C\u300D\u300E\u300F]` before the
  comma/stop transforms. Quotes-first ordering is load-bearing: a
  dialogue-final stop (`...!"`) is not followed by whitespace until the quote
  becomes a space, so muting quotes first is what lets those stops soften.
  Apostrophes/single quotes are untouched (contractions). Off/Medium keep
  quotes. Covered by three new `TtsSegmenterTest` cases (16 total).

## Logcat diagnostics (`ReadwideTts` tag)

- `ReaderTtsController` logs: engine init status and engine name,
  `setVoice`/`setLanguage` results including both locale-fallback hops,
  per-page queue summaries (segment count, generation, first segment lengths),
  every `speak()` failure with position and fatality (page queue / resume =
  fatal, prefetch = non-fatal drop), `onError` utterance ids, and callbacks
  dropped by the stale-generation guard. The PDF text-source build logs its
  result (page count, text presence, buffer size) and any contained failure.
  No behavior changes; collect with `adb logcat -s ReadwideTts`.

## Refactor: `DocumentTtsIntegrationController`

The document viewer's read-aloud integration (dialog/autostart entry points,
off-thread buffer build, toolbar button visibility, Markdown approximate
following) moved out of `DocumentPageActivity` into
`DocumentTtsIntegrationController`, following the existing controller
convention. Behavior-preserving: bodies moved verbatim with `activity.`
rewrites, thin delegates kept for all external entry points and `TtsHost`
methods, and the previously verbatim-duplicated dialog/autostart build block
deduplicated into `buildTextSourceThen(Runnable)`. The activity went from
4,066 to 3,892 lines. Verification (no device): brace gate + full parse on both
files, scripted existence/access check of all 19 activity members the
controller references, leftover-duplicate and `TtsHost`-conformance sweeps, and
a residual-symbol sweep that caught one missed reference
(`isTtsTextTemporarilyUnavailable()` -> new `isTextBuilding()` accessor).

## Refactor: `PdfTtsIntegrationController`

The PDF viewer's read-aloud integration (dialog entry, off-thread text
extraction/build with the 1.0.12 crash-fix guards, toolbar button visibility)
moved out of `PdfReaderActivity` into `PdfTtsIntegrationController`, mirroring
the document-viewer controller. Simpler than that one (no Markdown following, no
autostart), so only the in-flight-build flag moved; the controller instance,
text buffer, and `TtsHost` implementation stay on the activity. The activity
went from 3,851 to 3,793 lines. Verification (no device): brace gate + full
parse, scripted existence/access check of controller references (caught
`localFile` and `renderGeneration` still private -> relaxed to package-private),
leftover-duplicate and `TtsHost`-conformance sweeps.
