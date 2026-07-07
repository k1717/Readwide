# Readwide 1.0.13 development changes (vs 1.0.12)

Read-aloud sentence highlighting for the document and PDF viewers, resume-flow
fixes in every viewer, Markdown start-position rework, case-only rename support,
and Turkish-locale extension-matching fixes. The dependency set
is identical to 1.0.12 (no new dependency, no version bumps), so the license
report and SBOM carry the same packages with only the version stamp changed.
minSdk 24, compileSdk/targetSdk 35 unchanged. The user-facing summary is in
`docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_13.md`; full technical detail is in
`PATCHNOTES.md` under the 1.0.13 heading.

## Markdown resumed into the wrong viewer

- Root cause: the main-screen resume prompt's routing tested
  `FileUtils.isTextFile()` first, and that predicate matches `.md`/`.markdown`,
  so Markdown was routed to `ReaderActivity` (plain-text reader) even though it
  opens in the WebView-based `DocumentPageActivity` normally - the view visibly
  switched on resume.
- Fix: the prompt now tests the document formats (Markdown/EPUB/Word) before the
  text check, matching the order in the normal file-open funnel.

## EPUB/Word resumed from the top of the saved page

- Root cause: for paged documents `DocumentTtsTextSource.getCurrentCharPosition()`
  returned `pageStartOffsets[page]` (the page start), so resume turned to the
  right page but `currentVisiblePage()` always queued from the page's first
  character. The saved within-page offset was discarded.
- Fix: a one-shot within-page resume anchor
  (`pagedTtsResumeAnchorCharPosition`), mirroring the existing Markdown anchor.
  The resume jump records the exact saved char position; the text source returns
  it when it falls inside the current page; `showPage` clears it once the
  displayed page changes so ordinary page turns after the resume still start
  from the page top; `resetDocumentTts` clears it per document. Verified with a
  JVM round-trip (resume mid-page returns the exact offset and queues to page
  end; next page turn clears the anchor and speaks the full page; page-boundary
  and char-0 cases behave correctly).

## PDF did not auto-start on resume

- Root cause: the prompt opened the PDF viewer without `EXTRA_AUTOSTART_TTS`
  (resume was left to a manual dialog row), so nothing played.
- Fix: the prompt passes the autostart extra to `PdfReaderActivity`, and
  `PdfTtsIntegrationController` gained the same arm/poll/build/resume path the
  document viewer uses: `onLoadFromIntent` arms it, a poll waits until the PDF
  has pages, the text buffer is extracted off-thread, then
  `ReaderTtsController.autoStartOrResume` resumes from the saved position or
  starts from the top. Scanned/image-only PDFs stay silent on this automatic
  path rather than showing the scanned-PDF toast. The new controller references
  to activity members were access-checked (this caught a `getPrefs()` call that
  did not exist - corrected to the package-private `prefs` field - before it
  could fail compilation).

## Follow-up fixes (same release)

- **PDF mid-page resume.** `PdfTtsTextSource.getCurrentCharPosition()` had the
  same page-start-offset bug as the document viewer, so PDF resume (newly added
  above) would have started from the top of the saved page. The same one-shot
  within-page anchor (`pagedTtsResumeAnchorCharPosition` on `PdfReaderActivity`,
  read by `PdfTtsTextSource`, cleared by `goToPage`) was applied for parity.
- **Locale-independent extension matching.** `Bookmark.getFileTypeLabel`,
  `Bookmark.getPcEditPositionType`, and the `FontManager` font scan used
  `toLowerCase()` without a locale; under the Turkish locale the dotless-i
  mapping can break extension matching. All three now use
  `toLowerCase(Locale.ROOT)`.

## Read-aloud sentence highlight (document viewer)

The document viewer now highlights the spoken sentence (EPUB/Word/HWP/HWPX/
Markdown). The buffer is flattened plain text, so offsets don't map to the DOM;
`DocumentTtsHighlightController` injects a `window.__rwTtsHl` helper that
text-searches the DOM (whitespace-collapsed, case-insensitive) for the spoken
sentence and wraps the matching range in a highlight span, scrolling it into
view. The searched text is the segment's original page text
(`fullText.substring(startChar, endChar)`), confirmed to be fullText-relative
through the chain `VisiblePage.startChar = getCurrentCharPosition()` ->
`segmentPage(pageStartChar=...)` -> `segment.startChar = pageStartChar+cursor`,
so it matches the DOM rather than the speech-normalized (quote-stripped) form.
Script reinstalled on `onPageFinished`; page turns reload the WebView so the
prior highlight clears. Pure string handling (normalization + JS-literal
escaping) is in `DocumentTtsHighlightMath`, JVM-verified (14 cases). PDF is
deferred (bitmap pages need a glyph-coordinate overlay).

## Read-aloud sentence highlight (PDF viewer)

Same feature via glyph coordinates (option A): `extractPageGlyphs` extracts one
page's text + per-char boxes assembled exactly like the buffer's extractor;
`PdfTtsHighlightController` verifies the extracted text equals the buffer's
page slice before trusting coordinates (mismatch -> page marked unmappable,
highlight skipped), merges the segment range into per-line boxes
(`PdfTtsHighlightMath`, JVM-verified 12 cases incl. separator offset mapping),
and draws through a new dedicated read-aloud layer on `PdfPageView`
(independent of search highlights). Bug check caught: the per-page '\n'
separator in the buffer meant the naive slice never matched (highlight would
never appear) - excluded from expected text and clamps; the page-change hook
ran before currentPage updates, so conditional cache retention was inverted -
now an unconditional drop.

### Document-viewer highlight fixes (device feedback)

Whitespace-squeezed matching (paragraph-seam newlines broke exact matches and
made the prefix fallback bleed into unrelated text); prefix fallback bounded to
the matched prefix; cross-element fallback bounded to the start node's tail;
scroll only when off-screen and never for Markdown. Simulation-verified incl.
the failing paragraph-boundary case, inline-split words, 1:1 case folding.

### Root cause via device logs: <title> leaking into the TTS buffer

The MISS diagnostic showed dom:[introduction this...] vs
tgt:[introduction introduction this...] - EPUB chapter <head><title> text was
not stripped by htmlToPlainText, duplicating the heading in the buffer (spoken
twice per page) and breaking DOM matching from the first segment.
htmlToPlainText now strips <head> and <title>; regex behavior verified against
the failing sample. Diagnostics (hl MISS/PREFIX/NOSCRIPT logs) retained for the
diagnostic build type.

### Markdown start position (final form)

Fresh Markdown playback starts at the beginning of the first visible line: a
caret probe finds the viewport-top text node (y sweep for a text hit, then an
x sweep left-to-right at that y so the LEFTMOST hit = line start, not the
center-x mid-line character), the captured text is located in the read-aloud
buffer by whitespace-insensitive search (`indexOfCollapsed`, JVM-verified), and
the result is snapped to the nearest preceding line/sentence/word boundary
(`snapToNaturalStart`, JVM-verified incl. the mid-word screenshot cases). The
anchor refreshes when the read-aloud dialog opens; the Markdown speech anchor
tracks segment starts and retires on stop (verified against every
ReaderTtsController clearTtsHighlight call site - none on the start path), so
restart follows the screen. Proportional source-offset mapping remains only as
a last-resort fallback.
