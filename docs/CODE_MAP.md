# Readwide Code Map

A connection map of the codebase for future maintenance: which screen owns
which controllers, where the shared seams are, and where each subsystem's
logic actually lives. Relationships listed here were verified against the
1.0.16 sources (creation sites, interface implementations, call sites); areas
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
  (`SpreadMath`, `TapZoneMath`, `PdfGlyphBoxMath`, `PdfSpreadHighlightMath`,
  `DocumentTtsHighlightMath`, `PdfTtsHighlightMath`, ...) should be static
  and Android-free where possible. Many of them have off-device JVM tests;
  new index/geometry/string logic should add or extend those tests rather
  than relying on device-only coverage.
- **Pure Java.** No Kotlin anywhere; keep it that way.

## Top-level layout

```
com.readwide.manager            166 classes - activities, per-screen controllers,
                                readers' front ends, TTS core
├── archive/                    138 classes - archive detection, browsing,
│                               extraction, split-volume handling, password
│                               routing, and format-specific readers. This
│                               package mixes first-party parsers/decoders
│                               with bundled-library backends; creation
│                               support is limited. [package-level summary]
├── util/                        63 classes - shared helpers and pure math
├── document/render              15 classes - HTML/document rendering pipeline
│                               (FixedHtmlRenderer, RenderedDocument, blocks,
│                               styles) [package-level summary]
├── model/                       13 classes - persisted/value types (Bookmark, ...)
├── view/                         6 classes - custom views (CustomReaderView, ...)
├── adapter/                      6 classes - list/grid adapters
├── document/doc                  4 classes - legacy .doc (Word 97-2003) reader
│                               (CompoundFileReader, DocLegacyLayoutExtractor, ...)
├── image/                        5 classes - image decode/drawable helpers
│                               (ImageDecodeHelper, ImageInfoReader, ...)
├── search/                       4 classes - large-text search engine
├── controller/                   2 classes - cross-screen toolbar helpers
│                               (ReaderToolbarController, ...)
├── widget/                       2 classes
└── ui/                           1 class
```

The tree above covers all 425 classes under `com.readwide.manager`. One
additional main-source compatibility shim lives outside that package at
`javax/xml/bind/DatatypeConverter.java`, for 426 main Java files in total.

## Activities (screens)

| Activity | Screen |
|---|---|
| `MainActivity` | file browser / home |
| `SafBrowserActivity` | persisted Storage Access Framework tree browser fallback |
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
├── MainSelectionActionDropdownController adaptive selection actions
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

The raw `java.io.File` browser remains the full file-management path. When an
OEM or Android storage mode cannot enumerate it, `SafStorageAccess` retains a
persisted tree grant and `SafBrowserActivity` supplies provider-backed,
read-oriented navigation. `UriOpenRequest` keeps system-picker and SAF viewer
routing identical; raw-path-only mutations are not exposed as if a provider URI
were a `File`. These remain separate backends internally, but the drawer exposes
one `Internal Storage` route. A persisted provider tree becomes that route and
suppresses duplicate raw root shortcuts; without one, the same entry uses the
authorized raw browser or opens Android's folder picker when raw access is
unavailable. `SafStorageAccess` is also the single policy boundary for optional
document-grant persistence and read-only tree fallback. `SafBrowserActivity`
keeps provider directory queries and archive-to-local-cache copies on separate
executors, with independent busy state and stale-result guards, so a long copy
cannot serialize ordinary folder navigation behind it.

The toolbar overflow remains fixed beside the `Readwide` title on the Recent
home screen and switches roles only while multi-select is active.
`MainHomeDialogController` owns the adaptive Home/folder preference menu;
`MainSelectionActionDropdownController` measures the selection count and
available localized actions and lets rows wrap at the screen edge rather than
clipping them.

`FileAdapter` also owns the optional lightweight list-thumbnail path. It
uses one persisted toggle for the main and Recent lists and presents square
40dp list previews in 42dp containers; the OFF-state icon keeps the same 42dp
horizontal slot so the text column remains stable. Its two-worker request queue
is bounded, generation-cancelled on dataset replacement, and uses expiring
failure records capped per visible generation plus short-lived directory memory
entries.
`FileThumbnailLoader` supplies loose/folder images, ZIP/CBZ, RAR/CBR,
7z/CB7, ALZ, EGG, and TAR/CBT-family first-image covers, PDF page 1, and
raster EPUB package covers. It checks a source-fingerprinted, bounded
app-private PNG disk cache before decoding; source snapshots prevent a changing
file from being committed under stale metadata, disk replacement is atomic,
and a global two-slot gate bounds cache-miss work across both
adapters. `FileAdapter` deduplicates requests per list generation, makes stale
queued work exit before expensive format decoding, posts completion through an
adapter-owned main handler, and rebinds the current row when an asynchronous
result arrives. Folder/archive selection is ordered and
bounded but can fall through past a corrupt or device-unsupported first image.
It does not switch RecyclerView layout managers.

Provider URI copies are coordinated by `FileUtils` with an interruptible fair
lock. This preserves serialized atomic `opened_files` prune/commit semantics
without trapping a cancelled SAF worker behind another long provider copy.

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
  path is checked against the full-scan path for field equivalence
  (`content`, `baseCharOffset`, `bodyStartCharCount`, `bodyCharCount`, plus the
  remaining result metadata) by `LargeTextForwardCursorEquivalenceTest`, which
  also verifies canonical body tiling without skips or duplication.
  A completed background match count publishes a bounded
  `search/LargeTextMatchIndex`; nearest/nth navigation then uses binary lookup
  until file state, options, blank-line mode, or display rules change.
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
├── DocumentWebViewController       WebView clients; each open EPUB receives a
│                                   separate synthetic local origin and each
│                                   primary/right load carries a generation;
│                                   stale onPageFinished callbacks are rejected.
│                                   The right view remains non-primary (no
│                                   selection bridge or primary scroll handling;
│                                   after generation/boundary setup it skips
│                                   primary-only post-load work)
├── DocumentPageLoadController      document parsing/pagination entry
├── DocumentPageDisplayController   showPage: left load + spread visibility +
│                                   right page load
├── DocumentPageTurnController      turn orchestration/animation
├── DocumentSearchController        in-document find (markup-based, so the
│                                   spread's right page shows its own matches)
├── EpubMediaOverlayController      foreground OPF-linked SMIL cue/audio
│                                   playback, page following, DOM highlight
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

Two-page EPUB spread: gate `isLandscapeTwoPageDocumentMode()` = EPUB &&
`epubImagePageLike` && pages>1 && landscape. `DocumentArchiveUtils` delegates
image-page classification to pure `EpubImagePageClassifier`; fixed-layout
metadata alone is not enough. `DocumentTextDecoder` decodes spine/metadata text
and `EpubViewportParser` supplies order-independent fixed-layout dimensions.
Typed `DocumentArchiveUtils.EpubSpineItem` values also retain direct image
spine entries, fallback chains, itemref/spine identity, package-wide layout,
page-level rendition/scripted overrides, and validated OPF-linked SMIL paths.
Package layout and itemref overrides are deliberately kept separate, so mixed
books are not promoted to all-fixed merely because some pages are
pre-paginated. Explicit scripted pages keep JavaScript enabled;
`util/EpubBindingRewriter` maps validated custom objects to opaque local handler
frames while sanitizing a non-scripted parent and rewriting only the actual
binding payload resources. Scoped point-CFI navigation is split between
`EpubCfi` parsing and `EpubCfiJavascript` DOM resolution.
Ordinary text/reflowable EPUB remains one responsive-width `document_webview`.
Image-page EPUB adds `document_webview_right` inside
`document_spread_container`; index math is in `util/SpreadMath`, both halves
route through the shared gesture pipeline, and `destroyDocumentWebView` tears
both down. EPUB chrome is a stable overlay: the compact normal-flow top counter
is `GONE`, `document_content_column` owns stable Android system/cutout safe
insets, Android bars remain visible over body-colored stable-inset scrims, and
toolbar toggles do not reapply boundary CSS or resize the WebView. Vertical
reflowable EPUB uses `DocumentContentAnchorJavascript` to derive a stable sentence
element and caret from the same logical block-start viewport probe (with portable
text and same-viewport native-scroll fallbacks) and remains backward-compatible
with v1 block/Y anchors. Fixed-layout page
display also retains a stricter page-local image-dominance flag. Only near-image-
only pages derive a bounded canvas from their declared viewport plus actual media
bounds, while mixed/text pages preserve natural fixed-layout scrolling.
The two image panes use `EpubSpreadSlotMath` to inward-align physical LTR/RTL
slots around a PDF-sized 12 CSS-pixel seam. EPUB CSS resources pass through
`EpubCssCompatibility`; vertical-writing pages keep horizontal overflow, use a
DOM logical block-start snap after normal page turns, and are not forced into
the ordinary centered horizontal reading column. Reflow height,
reader-boundary, tap-paging, and fast-scroll policy is page-derived rather than
book-wide: ordinary information pages can clear obsolete publisher canvas-height
caps, while image/fixed pages keep their canvas and vertical-writing pages fill
the WebView's inline axis without discarding their right-to-left columns.

## PdfReaderActivity (PDF)

```
PdfReaderActivity  (implements TtsHost)
├── PdfReaderStartupController      startup; posts applyPdfViewportBarInsets
├── PdfPageTurnController
├── PdfSearchController             async find; Host interface seam
│                                   (goToPage/currentPage/pageView/runOnUi/
│                                   right-page identity + per-page mapping)
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
                                        text-equality/generation guards;
                                        spread projection + line merge
```

Two-page spread: gate `isPdfTwoPageSpreadMode()` = single-page mode &&
pages>1 && landscape. Each source page is rendered completely onto an opaque
white temporary bitmap, then drawn into the capped composite canvas with a
12dp gap. Only one temporary page bitmap is retained at a time. Single-page,
neighbor-prefetch, and continuous fit/allocation math is centralized in pure
`PdfPageRenderPlan`; raw pixel capping remains in `PdfRenderSize`. Index math
is in `util/SpreadMath`; the winning composite retains each page rectangle and
`util/PdfSpreadHighlightMath` projects search/TTS overlays through it. Root-level
app bars remain overlays, but their measured height is reserved only while PDF
chrome is visible. Hiding chrome releases those reserves in portrait and
landscape and keeps only Android status/navigation/cutout safe edges. The
accepted bitmap and cache remain in place: `PdfPageView` refits its Matrix while
preserving a zoomed page's relative scale and normalized center, and continuous
mode restores its content anchor. PDF keeps Android system bars visible through
a PDF-specific policy; a side-mounted navigation rail is part of the root safe
width, and the legacy navigation spacer remains collapsed. Single-page render
submission requires a positive content width and height; viewport startup uses
one named bounded retry which is cancelled on display-mode changes and teardown.

## ImageReaderActivity (images)

```
ImageReaderActivity
├── ImageDialogStyleController
├── ImageReaderSliderController
├── image/ImageDecodeHelper, ImageInfoReader, LoadedImage
├── image/ArchiveImageSpreadDrawable  allocation-free two-bitmap surface
├── util/ArchiveImageSpreadMath       tall-page gate + mixed-screen navigation
├── util/ArchiveImageSpreadNavigator  actual mixed-screen history/reversal
├── util/ImageExportName              safe original-byte export names
├── util/ImageSequenceState        sequence bookkeeping (applyRename matches
│                                  the exact pre-rename path)
├── SequentialArchiveImageReader      one handed-off forward RAR/7z/TAR stream
└── lifecycle: 5 executors + LruCache (min(128MB, heap/5), recycle-on-evict),
    with two-worker lightweight neighbor decode. Archive pages upgrade to a
    denser preview profile when current, and a visible spread companion uses
    that same profile; explicit zoom uses the detail tier. All workers shut
    down in onDestroy.
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

## Document/PDF pure helpers at the root package

| Class | Role |
|---|---|
| `EpubImagePageClassifier` | distributed-spine book classification plus a stricter page-local near-image-only decision for fixed-layout scroll policy; excludes cover-only, mixed/text-heavy, and false CSS/object signals |
| `EpubViewportParser` | order-independent fixed-layout viewport metadata parsing |
| `EpubCssCompatibility` | safe standard/WebKit aliases for legacy EPUB vertical-writing, text-combine, and text-emphasis declarations |
| `EpubCfi` / `EpubCfiJavascript` | bounded point-CFI parsing plus injection-safe DOM target resolution; range/temporal/spatial forms are rejected |
| `EpubSmilParser` / `EpubMediaOverlayJavascript` | bounded parsing of explicitly linked local SMIL cues plus safe DOM active-fragment markup |
| `DocumentTextDecoder` | UTF BOM/sniff/declaration-aware EPUB HTML/XML decoding |
| `DocumentContentAnchorJavascript` | DOM capture/restore helpers for rendered-document anchors; atomically installs/captures on normally script-disabled EPUB pages, pairs a viewport caret with its stable sentence element for `vertical-rl`, optionally scans that same physical column from its first fully visible glyph for an explicit bookmark's presentation text, detects namespaced semantics without fragile CSS escaping, retains native WebView position fallbacks, and preserves the horizontal v1 path |
| `util/DocumentAnchorMath` | pure policy for accepting new matched-caret vertical anchors, recognizing earlier stored v2 anchors during restore, and choosing column-start/focus/sentence bookmark presentation text without changing restore identity |
| `PdfPageRenderPlan` | shared fit/display/allocation plan for visible, prefetch, and continuous PDF page renders |
| `PdfRenderSize` | overflow-safe bitmap pixel/dimension cap used by PDF render plans and patches |

## util/ highlights (63 classes; the load-bearing ones)

| Class | Role |
|---|---|
| `SpreadMath` | two-page spread index math (step/right index/visible range/clamp/turn/can-turn), shared by document + PDF; edge behavior is covered by `SpreadMathTest` |
| `ArchiveImageSpreadMath` | optional archive-comic screen math: 1.10 portrait gate, paired/single visible range, and previous/next targets |
| `ArchiveImageSpreadNavigator` | session history for reversing the actual mixed single/spread path after asynchronous aspect-ratio discovery |
| `EpubSpreadSlotMath` | maps logical image-spread pages to physical left/right slots for LTR/RTL inward gutter alignment; standalone pages remain centered |
| `EpubFontPreferenceMath` | dedicated EPUB font default and one-time legacy preference migration rules |
| `LargeTextPartitionReader.ForwardCursor` | shared forward-read cursor for large-TXT start-line partition reads; `LargeTextForwardCursorEquivalenceTest` enforces full-scan field equivalence and no-skip/no-duplication body tiling |
| `PdfGlyphBoxMath` | single source of the PDF glyph highlight box (search + TTS) |
| `PdfSpreadHighlightMath` | maps one source page's normalized search/TTS rectangles into the exact post-cap two-page composite geometry |
| `TapZoneMath` | tap-zone action resolution for all tap paging |
| `UriPathCodec` | percent-decodes archive/document URI paths while preserving literal `+`, and encodes synthetic image-page path segments without form semantics; shared by EPUB manifest parsing, WebView resource/navigation routing, extraction, and display-name normalization |
| `EpubBindingRewriter` | converts validated OPF custom objects to `allow-scripts`-only local handler frames, sanitizes active content from a binding-only non-scripted parent, and resolves only the actual binding-payload XML URI attributes without exposing filesystem paths |
| `ReaderRestoreTargetMath` | pure target matching for TXT restore intents; prevents stale background restore from reopening a previous file after an in-place file switch |
| `TtsAnchorTextMath` | whitespace-insensitive anchor search + natural-start snapping for read-aloud |
| `FileSystemOps` | case-only rename two-hop (`renameInPlace`) |
| `FileUtils` | shared file/text helpers incl. `htmlToPlainText` (strips head/title - TTS buffer depends on this) |
| `ImageSequenceState` | image sequence list bookkeeping |
| `ImageExportName` / `FileThumbnailMath` | safe archive-page export filenames and natural first-image selection for lightweight browser thumbnails |
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
