# Readwide Code Map

A connection map of the codebase for future maintenance: which screen owns
which controllers, where the shared seams are, and where each subsystem's
logic actually lives. Relationships listed here were verified against the
1.0.14 sources (creation sites, interface implementations, call sites); areas
not yet explored in depth are described at the package level and marked as
such rather than guessed at.

## How the app is wired (conventions)

- **Activity + controller swarm.** Each screen is one Activity that creates a
  set of single-purpose `*Controller` classes, passing itself to their
  constructors. Controllers keep the `activity` reference and read/write its
  package-private fields directly; the Activity exposes package-private
  helper methods the controllers call. There is no DI framework and no
  event bus - edges are plain constructor references.
- **Lazy singletons per screen.** Most controllers are created on first use
  through an accessor on the Activity (e.g. `documentTts()`,
  `readerMemory()`), not all up front in `onCreate`.
- **`*Math` classes are intended to stay pure.** Anything named `...Math`
  (`SpreadMath`, `TapZoneMath`, `PdfGlyphBoxMath`,
  `DocumentTtsHighlightMath`, `PdfTtsHighlightMath`, ...) should be static
  and Android-free where possible. Many of them have off-device JVM tests;
  new index/geometry/string logic should add or extend those tests rather
  than relying on device-only coverage.
- **Pure Java.** No Kotlin anywhere; keep it that way.

## Top-level layout

```
com.readwide.manager            148 classes - activities, per-screen controllers,
                                readers' front ends, TTS core
├── archive/                    138 classes - archive detection, browsing,
│                               extraction, split-volume handling, password
│                               routing, and format-specific readers. This
│                               package mixes first-party parsers/decoders
│                               with bundled-library backends; creation
│                               support is limited. [package-level summary]
├── util/                        49 classes - shared helpers and pure math
├── document/render              15 classes - HTML/document rendering pipeline
│                               (FixedHtmlRenderer, RenderedDocument, blocks,
│                               styles) [package-level summary]
├── model/                       12 classes - persisted/value types (Bookmark, ...)
├── view/                         6 classes - custom views (CustomReaderView, ...)
├── adapter/                      5 classes - list/grid adapters
├── document/doc                  4 classes - legacy .doc (Word 97-2003) reader
│                               (CompoundFileReader, DocLegacyLayoutExtractor, ...)
├── image/                        4 classes - image decode helpers
│                               (ImageDecodeHelper, ImageInfoReader, ...)
├── search/                       3 classes - large-text search engine
├── controller/                   2 classes - cross-screen toolbar helpers
│                               (ReaderToolbarController, ...)
├── widget/                       2 classes
└── ui/                           1 class
```

## Activities (screens)

| Activity | Screen |
|---|---|
| `MainActivity` | file browser / home |
| `ReaderActivity` | plain-text (TXT) reader |
| `DocumentPageActivity` | document viewer: EPUB, Word family, HWP/HWPX, Markdown |
| `PdfReaderActivity` | PDF viewer |
| `ImageReaderActivity` | image viewer (incl. archive image sequences) |
| `ArchiveBrowserActivity` | archive contents browser |
| `BookmarkListActivity` | bookmark list |
| `SettingsActivity` | settings |
| `ThemeEditorActivity` | reader theme editor |
| `LockActivity` | app lock |

## MainActivity (file browser)

Owns ~20 controllers, each a vertical slice of browser behavior:

```
MainActivity
├── MainActivityStartupController      onCreate wiring
├── MainDrawerController / MainDrawerGestureController
├── MainSelectionModeController        multi-select mode
├── MainHomeDialogController
├── MainFileActionDialogController     rename/delete/etc. dialog
│      └── util/FileSystemOps.renameInPlace  (case-only rename two-hop)
├── MainSearchFilterController         name search + filter chips
├── MainArchiveExtractController / MainArchiveCreateController
├── MainClipboardController / MainShareController
├── MainConfirmDialogController
├── MainImageOpenController / MainArchiveImageOpenController
├── MainRecentFilesController
├── MainFolderLoadController           async folder listing
├── MainFolderChangeObserverController filesystem change refresh
├── MainFileOperationProgressController
├── MainBrowseStateController          navigation state
└── MainThemeController
```

## ReaderActivity (TXT reader)

The most finely decomposed screen: 36 `Reader*` controllers. Groups:

- **Lifecycle/shell:** `ReaderLifecycleController` (owns onDestroy teardown),
  `ReaderActivityStartupController`, `ReaderShellController`,
  `ReaderChromeController`, `ReaderMemoryController`,
  `ReaderPreferencesController`.
- **Text loading:** `ReaderFileLoadController`, `ReaderFileApplyController`,
  `ReaderReloadController`, `ReaderEncodingController`,
  `ReaderLoadedTextSnapshotController`, `ReaderLoadingWindowController`.
- **Large-text engine** (windowed reading of huge files):
  `ReaderLargeText{State, Paging, Jump, Cache, BoundaryHandoff,
  PartitionRead, PartitionPrefetch, ExactAnchorBuild, ExactPageIndex}Controller`,
  backed by `search/LargeTextSearchEngine` for find. Start-line partition
  reads currently route through `LargeTextPartitionReader.ForwardCursor`, a
  shared forward-read cursor with a lookbehind/lookahead replay queue owned
  and lock-serialized by `ReaderLargeTextPartitionReadController`;
  char-position jumps (`readForChar`) bypass it by design. Any change to this
  path must be checked against the full-scan path for field equivalence
  (`content`, `baseCharOffset`, `bodyStartCharCount`, `bodyCharCount`).
- **Navigation:** `ReaderSeekController`, `ReaderPageJumpController`,
  `ReaderPagePositionController`, `ReaderTapNavigationController`.
- **Dialogs/UI:** `ReaderDialogStyleController`, `ReaderBottomControlsController`,
  `ReaderAppearanceDialogController`, `ReaderFontDialogController`,
  `ReaderToolsDialogController`, `ReaderTextDisplayRuleDialogController`,
  `ReaderSearchController`, `ReaderBookmark{Dialog, Action, PageModel}Controller`,
  `ReaderActionController`.
- **TTS:** `ReaderTtsController` (shared core, see TTS section). Text and
  highlight live in `view/CustomReaderView` (`setTtsHighlightRange`, fully
  bounds-clamped).

Rendering is the custom-drawn `view/CustomReaderView` (not a WebView).

## DocumentPageActivity (EPUB / Word / HWP / Markdown)

```
DocumentPageActivity  (implements TtsHost)
├── DocumentPageStartupController   onCreate: bindViews (incl. rightWebView,
│                                   documentSpreadContainer) -> toolbar ->
│                                   setupWebView -> installSwipePaging -> load
├── DocumentWebViewController       WebView clients; right view is non-primary
│                                   (no selection bridge/scroll listeners;
│                                   onPageFinished early-returns)
├── DocumentPageLoadController      document parsing/pagination entry
├── DocumentPageDisplayController   showPage: left load + spread visibility +
│                                   right page load
├── DocumentPageTurnController      turn orchestration/animation
├── DocumentSearchController        in-document find (markup-based, so the
│                                   spread's right page shows its own matches)
├── DocumentFontDialogController / DocumentBookmarkDialogController
├── ReaderDialogStyleController     (shared with TXT)
├── controller/ReaderToolbarController
└── TTS cluster
    ├── ReaderTtsController             shared playback core
    ├── DocumentTtsIntegrationController dialog entry, resume, MD follow
    ├── DocumentTtsTextSource           page text, anchors (one-shot paged
    │                                   anchor; MD caret/anchor-text search via
    │                                   indexOfCollapsed + snapToNaturalStart)
    └── DocumentTtsHighlightController  injects window.__rwTtsHl into the LEFT
                                        WebView; pending-sentence replay on
                                        page load; DocumentTtsHighlightMath
```

Two-page spread (1.0.14): gate `isLandscapeTwoPageDocumentMode()` =
EPUB && pages>1 && landscape. Left `document_webview` + right
`document_webview_right` inside `document_spread_container`. Index math in
`util/SpreadMath`. Tap zones are computed against the whole spread
(`getDocumentTapPagingAction`), both halves route through the shared gesture
pipeline, EPUB boundary margins mirror to both views, and
`destroyDocumentWebView` tears both down.

## PdfReaderActivity (PDF)

```
PdfReaderActivity  (implements TtsHost)
├── PdfReaderStartupController      startup; posts applyPdfViewportBarInsets
├── PdfPageTurnController
├── PdfSearchController             async find; Host interface seam
│                                   (goToPage/currentPage/pageView/runOnUi/
│                                   twoPageSpreadActive - spread guard clears
│                                   instead of painting composite-wrong rects)
│      └── PdfTextSearchEngine     -> util/PdfGlyphBoxMath (shared glyph box)
├── PdfBookmarkDialogController
├── view: PdfPageView               bitmap + matrix zoom/pan + highlight layers
│                                   (search layer and TTS layer are separate)
└── TTS cluster
    ├── ReaderTtsController             shared playback core
    ├── PdfTtsIntegrationController     autostart/resume orchestration
    ├── PdfTtsTextSource                page text + one-shot resume anchor
    │      └── PdfPlainTextExtractor    text + per-glyph boxes
    │             └── util/PdfGlyphBoxMath
    └── PdfTtsHighlightController       glyph-box highlight; structural
                                        text-equality guard; spread guard;
                                        PdfTtsHighlightMath (line merge)
```

Two-page spread (1.0.14): gate `isPdfTwoPageSpreadMode()` = single-page mode
&& pages>1 && landscape. Renders one composite bitmap (left + 12dp gap +
right, 22M px cap, parts recycled), skips cache/prefetch/sharpen. Index math
in `util/SpreadMath`. Bars are root-level overlays; viewport reserves are
managed by `applyPdfViewportBarInsets` (top = app-bar height outside the
spread; inside the spread a constant compact-strip height in both chrome
states, so toggling the controls never re-renders).

## ImageReaderActivity (images)

```
ImageReaderActivity
├── ImageDialogStyleController
├── ImageReaderSliderController
├── image/ImageDecodeHelper, ImageInfoReader, LoadedImage
├── util/ImageSequenceState        sequence bookkeeping (applyRename matches
│                                  the exact pre-rename path)
└── lifecycle: 4 executors + LruCache (min(128MB, heap/5), recycle-on-evict),
    all shut down/drained in onDestroy
```

## Shared TTS core (cross-viewer subsystem)

```
TtsHost (interface)  <- implemented by ReaderActivity, DocumentPageActivity,
│                       PdfReaderActivity
├── ReaderTtsController      the ONE playback engine: segmentation queue,
│                            utterance callbacks, one logical page advance
│                            at a time (in spread mode, the next spoken page
│                            becomes the left/primary page of the newly
│                            displayed spread), stop/pause/sleep timer, dialog
│      ├── TtsSegmenter / TtsSpeechSegment
│      ├── TtsDialogViews / TtsSleepTimerDialog
│      └── TtsFloatingCardController (.Controls seam per activity)
├── text sources: DocumentTtsTextSource, PdfTtsTextSource
│                 (TXT reads via CustomReaderView directly)
├── highlight controllers: DocumentTtsHighlightController (DOM),
│                          PdfTtsHighlightController (glyph boxes),
│                          CustomReaderView (native draw)
└── TtsPlaybackBridge / TtsPlaybackService   background playback + media
                                             session; activities register/
                                             unregister on create/destroy
```

Anchor semantics worth remembering: paged documents use a ONE-SHOT resume
anchor (consumed by the first queue build, discarded on page change); the
Markdown anchor tracks segment starts, retires on stop (`clearTtsHighlight`
is stop/finish/error-only - verified against every call site), and fresh MD
starts locate the viewport-top text in the buffer (caret probe -> leftmost-x
line start -> `indexOfCollapsed` -> `snapToNaturalStart`).

## util/ highlights (49 classes; the load-bearing ones)

| Class | Role |
|---|---|
| `SpreadMath` | two-page spread index math (step/right index/clamp/turn), shared by document + PDF |
| `LargeTextPartitionReader.ForwardCursor` | shared forward-read cursor for large-TXT start-line partition reads; intended to reduce repeated forward scans, but it must remain field-equivalent to the full-scan path (`content`, `baseCharOffset`, `bodyStartCharCount`, `bodyCharCount`) |
| `PdfGlyphBoxMath` | single source of the PDF glyph highlight box (search + TTS) |
| `TapZoneMath` | tap-zone action resolution for all tap paging |
| `ReaderRestoreTargetMath` | pure target matching for TXT restore intents; prevents stale background restore from reopening a previous file after an in-place file switch |
| `TtsAnchorTextMath` | whitespace-insensitive anchor search + natural-start snapping for read-aloud |
| `FileSystemOps` | case-only rename two-hop (`renameInPlace`) |
| `FileUtils` | shared file/text helpers incl. `htmlToPlainText` (strips head/title - TTS buffer depends on this) |
| `ImageSequenceState` | image sequence list bookkeeping |
| `FontManager` | font scanning (locale-safe extension matching) |
| `ThemeManager` / `EdgeToEdgeUtil` / `ButtonOrderManager` | theming, insets, toolbar order |

The rest of `util/` is smaller single-purpose helpers; consult the directory
listing when hunting.

## Packages summarized at package level

These have not been mapped class-by-class in this document; treat the notes
as orientation, and read the package before changing it:

- **`archive/` (138 classes):** archive support lives here: ZIP/CBZ,
  RAR/CBR/RAR5 including scoped encrypted/header-encrypted paths, 7z/CB7
  including scoped PPMd/LZMA paths, EGG, ALZ, tar and single-compressor
  streams including zstd/lz4, split volumes, password routing, and
  archive-wide filename charset detection. Some paths are first-party; others
  route through bundled-library backends such as libarchive-android and
  Apache Commons Compress. Creation support is limited and should not be
  implied for every archive format. Unsupported variants should surface
  explicit errors. A standalone `readwide-rar` library extraction exists as a
  separate repo.
- **`document/render` (15):** the HTML rendering pipeline used by the
  document viewer (`FixedHtmlRenderer`, `RenderedDocument`, block/style
  types).
- **`document/doc` (4):** the pure-Java legacy `.doc` reader.
- **`adapter/`, `widget/`, `ui/`:** small UI support classes.

## Where to make common changes

- **Paging/spread behavior:** `util/SpreadMath` for index math; the mode gates
  (`isLandscapeTwoPageDocumentMode`, `isPdfTwoPageSpreadMode`) stay in their
  activities.
- **TTS behavior for all viewers at once:** `ReaderTtsController`.
  Per-viewer text/anchor behavior: the viewer's `*TtsTextSource`.
  Highlight visuals: the viewer's `*TtsHighlightController` (+ its `*Math`).
- **New document format in the document viewer:** loading enters through
  `DocumentPageLoadController`; display through
  `DocumentPageDisplayController.showPage`.
- **File operations:** `util/FileSystemOps` (browser dialogs and the image
  viewer both route renames through it - keep it that way).
- **Anything with index/geometry/string math:** put it in a `*Math` class and
  add or extend a JVM harness case where practical.
