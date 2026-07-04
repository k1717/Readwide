# Read-aloud (TTS) status and EPUB/PDF extension design (Readwide 1.0.11)

This note records what read-aloud currently covers, the neural-engine fixes and
controls added in 1.0.11 in response to user feedback, and the design by which
read-aloud was extended from the text reader to the document viewer (EPUB, Word,
HWP, Markdown) and PDF.

## Current coverage

Read-aloud lives in `ReaderTtsController`, which is shared by every reader and
viewer through a small text-source abstraction (`TtsTextSource` / `TtsHost`,
see the extension design below). It is offered in all of them:

- **`ReaderActivity`** - plain text and, historically, Markdown opened as text
  (the reflowable `CustomReaderView` path).
- **`DocumentPageActivity`** - EPUB, Word-family, HWP/HWPX, and Markdown (the
  WebView-rendered path). Markdown is now included here (see the Markdown note
  in the extension design); it is how Markdown normally opens.
- **`PdfReaderActivity`** - text-based PDF (`PdfRenderer` bitmaps plus a PdfBox
  text layer).

So read-aloud is available everywhere a document exposes selectable text. The
original 1.0.11 user report - "EPUB and PDF have no TTS entry point" - is
resolved; the sections below record how.

The engine is the Android `TextToSpeech` API, so any installed engine works,
including neural engines exposed as system TTS (sherpa-onnx / VoxSherpa with
Kokoro and Piper voices). Within a page, sentences are already queued ahead
with `QUEUE_ADD` (not a synchronous speak-then-wait loop), so intra-page
playback does not stall between sentences.

### Controls and placement

Beyond language/voice/speed/pitch, the read-aloud dialog exposes two text-level
controls aimed at neural engines that over-pause or add latency (see issue #7):
**phrase length** (Short/Medium/Long, mapping to the segmenter's target chunk
size) and **pause reduction** (Off/Medium/Aggressive, softening punctuation in
`TtsSegmenter.normalizeForSpeech`). Both are text transforms applied before the
text reaches the engine - no audio is touched - which is why they fit the
`TextToSpeech`-delegated architecture. A read-aloud button also sits immediately
right of the bookmark button in the document and PDF viewers' toolbars (default
order in `ButtonOrderManager`, user-customizable), matching the text reader.

## Fixes in 1.0.11

Both target the neural-engine experience described in the feedback.

1. **No silent failure on an unmatched voice/locale.** `applySelectedLanguage`
   used to refuse to start (leaving `active = false`, no audio) whenever the
   selected locale came back `LANG_NOT_SUPPORTED` / `LANG_MISSING_DATA`. Neural
   engines frequently report a specific locale as unsupported while still
   speaking fine with their own default voice, which read to users as "TTS is
   broken - even .txt produces no audio." It now falls back to the engine's
   default voice locale (`getDefaultVoice().getLocale()`), then the device
   default locale, and only surfaces the unavailable-language message if none
   can speak.

2. **No audible gap at page boundaries in continuous mode.** The page-internal
   queue already buffered ahead, but the *seam* between pages waited for the
   last utterance's `onDone`, then turned the page, then re-segmented, then
   spoke - a gap a high-latency neural voice makes obvious. When the whole
   document text is resident (i.e. not the lazily partitioned large-text path),
   `maybePrefetchNextPageSegments` now appends the next page's opening segments
   (up to `PREFETCH_NEXT_PAGE_CHARS`) onto the *same* engine queue while the
   current page is still playing, using the full text from
   `CustomReaderView.getTextContent()` and the current page's end char. When
   playback crosses the prefetched boundary, `handleUtteranceStart` catches the
   UI up with `jumpToAbsoluteCharPosition` (scroll only; it does not touch the
   engine queue) and chains another prefetch. Highlight, saved position, and
   the sleep timer continue to track the spoken segment. The lazily paged
   large-text path is intentionally excluded because its text is not fully
   resident, so it keeps the existing page-turn advance.

Both fixes originated in `ReaderActivity`'s text/Markdown path but live in the
shared `ReaderTtsController`, so they apply to every viewer that now uses it.
Device verification with a real neural engine is the remaining step, since the
sandbox cannot run the engine.

## EPUB / PDF extension design

Stages 1, 2, and 3 below are now implemented in 1.0.11.

The blocker was coupling: `ReaderTtsController` reached into `ReaderActivity`
and `CustomReaderView` in ~90 places (text content, char position, page
number, page turn, char jump, highlight, large-text state). EPUB and PDF live
in different activities with different rendering models, so read-aloud needed a
small text-source abstraction rather than a copy of the controller.

Implemented shape:

- **Stage 1 (behavior-preserving abstraction).** `TtsTextSource` is the text
  surface the controller speaks from (`getTextContent()`,
  `getCurrentCharPosition()`, `getCharPositionAfterCurrentVisibleContent()`,
  `setTtsHighlightRange(int,int)`, `clearTtsHighlight()`), implemented as-is by
  `CustomReaderView`. `TtsHost` is everything else the controller needs from
  its owning activity (prefs, dialog styler, dp conversion, paging, char jump,
  displayed page numbers, the text-resident and text-temporarily-unavailable
  flags that gate prefetch/retry, floating-card refresh, remote-command
  routing, and which activity the notification opens). `ReaderActivity`
  implements both as thin public wrappers over the exact members the controller
  used before, so the text/Markdown path is unchanged by construction.
  `ReaderDialogStyleController` was likewise generalized behind
  `ReaderDialogStyleHost` (theme snapshot colors, ThemeManager, dp), and
  `TtsSleepTimerDialog`/`TtsDialogViews` now build against `TtsHost`.
  `TtsPlaybackBridge` holds a `TtsHost` instead of a `ReaderActivity`, and
  `TtsPlaybackService` routes the notification tap to the host's activity via a
  `host_kind` extra.
- **Stage 2 (EPUB / Word / HWP / Markdown in `DocumentPageActivity`).**
  `DocumentTtsTextSource` concatenates `FileUtils.htmlToPlainText` (now public)
  over the loaded `pages` into one buffer with per-page start offsets, so the
  controller's absolute char positions map to page indices both ways; the
  "visible page" is the whole current document page, and because the buffer is
  fully resident the cross-page prefetch works exactly as in the text reader.
  The buffer is built once per document on the document executor the first time
  read-aloud is opened (More dialog -> read-aloud row) and dropped on reload.
  Continuous mode turns the WebView page as speech crosses each seam.

  Markdown is included here (it was initially excluded). Markdown renders as a
  single WebView page scrolled by a visual-page model rather than a page list,
  so it fills `pages` with one rendered `Page`: the text buffer is that one
  page, extracted for free like any other document. Following differs, though,
  because there is no page list to advance - see the Markdown-following note
  below.

  `MainActivity`'s TTS resume prompt routes document types (EPUB / Word / HWP /
  Markdown) to `DocumentPageActivity` with autostart (see the autostart note),
  so "continue reading aloud" resumes at the saved position in the same viewer -
  and therefore the same char-offset coordinate space - the position was saved
  in.
  Still deferred from this version, by design: spoken-sentence highlight on the
  WebView page (needs a char-offset-to-block mapping over the injected
  `window.__rwDocBlocks` anchors) and the floating playback card.
- **Stage 3 - PDF (`PdfReaderActivity`, `PdfRenderer`).** `PdfRenderer` has no
  text layer, so text comes from PdfBox - the same `com.tom-roush:pdfbox-android`
  library the PDF search already uses. `PdfPlainTextExtractor` does one stripper
  pass over the document (mirroring the search engine's `DocStripper`, minus the
  glyph rectangles read-aloud does not need) and returns per-page plain text;
  `PdfTtsTextSource` concatenates it into the same page-indexed buffer shape as
  `DocumentTtsTextSource`, so absolute char positions map to page indices both
  ways and the cross-page prefetch works. The buffer is built once on the PDF
  executor the first time read-aloud is opened (More dialog -> read-aloud row)
  and playback follows pages through `goToPage`. Scanned / image-only PDFs
  extract no text, so `PdfTtsTextSource.hasAnyText()` is false and the viewer
  shows a clear "no selectable text" message instead of starting silent
  playback. `PdfReaderActivity` implements `TtsHost` and `ReaderDialogStyleHost`
  like the document viewer; `TtsPlaybackService` gained a third `host_kind`
  (`HOST_PDF`) so the notification tap reopens the PDF viewer, and
  `MainActivity`'s resume prompt routes PDF files there. Highlight on the bitmap
  page is still deferred: it needs glyph bounding boxes (PdfBox exposes them -
  the search overlay uses them - so this is a feasible later pass), and v1 is
  audio-only with page-level follow, as the design anticipated.

### Markdown following (approximate)

Markdown has no page list to advance, so it cannot follow playback by turning
pages the way EPUB/Word/PDF do. Instead it follows by scrolling to roughly the
passage being spoken. The difficulty is a coordinate mismatch: the TTS buffer is
rendered plain text (`htmlToPlainText`, no `#`/`*`/link syntax), while the viewer
scrolls by offsets into the raw `markdownSourceText`. There is no exact bijection
without instrumenting the renderer, so `MarkdownTtsFollowMath.approximateSourceOffset`
does a best-effort search: it builds a short word-run probe around the spoken
position and locates it in the source (case-insensitive, treating any run of
source whitespace as one space so wrapped source still matches), falling back to
a proportional estimate when the probe cannot be found. The resulting offset
drives the existing `scrollMarkdownToSourceOffset` (WebView JS
`window.__rwMdScrollToOffset`, with a proportional visual-page fallback).

Because the controller only issues `ttsJumpToAbsoluteCharPosition` at page-
prefetch boundaries - which never fire for Markdown's single page - the
follow is instead driven per segment: `DocumentTtsTextSource.setTtsHighlightRange`
(a no-op for rendering, since there is no glyph highlight yet) forwards to
`DocumentPageActivity.onDocumentTtsSegmentSpoken`, which recomputes the source
offset and scrolls, throttled so it only moves when the position crosses roughly
half a visual page (no jerk per sentence). This is "approximate following" by
design - the view lands near the spoken paragraph, not on the exact glyph. It is
covered by `MarkdownTtsFollowMathTest`.

### Autostart on resume

`DocumentPageActivity` accepts an `EXTRA_AUTOSTART_TTS` intent extra. When set,
`loadFromIntent` polls until the document is ready (`documentSupportsTts()`),
builds the text buffer off-thread, and calls the controller's public
`autoStartOrResume`, which resumes from the exact saved char position when the
saved state matches the current file (for Markdown this jumps via the approximate
mapping above and scrolls to follow) or starts from the top otherwise. This is
what lets the main-screen "continue reading aloud" prompt resume EPUB / Word /
HWP / Markdown, not just plain text; the prompt routes those types here rather
than to the text reader precisely so the saved offset is interpreted in the same
buffer space it was recorded in.

## Note on the reported "synchronous speak-then-wait" diagnosis

The intra-page queue was already `QUEUE_ADD`-buffered, so the stutter users hit
was concentrated at page seams (fix 2) and, for .txt specifically, the silent
locale-mismatch abort (fix 1) - not a per-sentence synchronous loop inside a
page. The proposed `QUEUE_ADD` buffering in the feedback matches what the code
already does within a page; 1.0.11 extends that same idea across page
boundaries.

## 1.0.12 addendum

Three read-aloud changes shipped as the 1.0.12 patch; recorded here so this
document stays the single design reference.

- **PdfBox initialization is a hard precondition of the Stage 3 extractor.**
  `PDFBoxResourceLoader.init` was only called in `PdfSearchController`'s
  constructor, so read-aloud as the first PdfBox user in the process crashed:
  the font/glyph machinery fails partly with `Error`s that
  `catch (RuntimeException)` cannot contain, and an uncaught throw on the bare
  extraction executor kills the process. `PdfPlainTextExtractor` now initializes
  the loader itself and contains `Throwable`, logging the failure. Design
  lesson: every independent PdfBox entry point must init - do not assume another
  feature ran first.
- **Quotation-mark muting at Aggressive pause reduction.** Level 2 of the pause
  reduction now also mutes double quotes/guillemets/CJK corner brackets, applied
  *before* the stop-softening because a dialogue-final stop (`..."!`) only
  matches the softening pattern once the quote becomes whitespace. Apostrophes
  are untouched; Off/Medium keep quotes.
- **Logcat diagnostics.** `ReaderTtsController` and the PDF text-source build
  log under the `ReadwideTts` tag (engine init, language/voice results with
  fallback hops, queue summaries, `speak()` failures, dropped stale-generation
  callbacks, extraction results). Collect from any build with
  `adb logcat -s ReadwideTts`; this is the intended first step for triaging
  silent-failure reports before changing queueing behavior.

- **Where the document-viewer integration lives (1.0.12).** The non-interface
  integration logic (dialog/autostart entry, buffer build, button visibility,
  Markdown following) is in `DocumentTtsIntegrationController`;
  `DocumentPageActivity` keeps the `TtsHost` implementation and thin delegates.
  Start there when changing document-viewer read-aloud behavior. The PDF
  viewer's equivalent is `PdfTtsIntegrationController` (dialog entry, text
  extraction/build, button visibility); `PdfReaderActivity` keeps the `TtsHost`
  implementation and thin delegates.
