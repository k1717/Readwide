# Readwide Code Map

A maintenance map of screens, controllers, shared helpers and archive readers.
Use the subsystem sections to find ownership; the [development notes](DEV_CHANGES_1_0_20.md)
record release changes and [current status](CURRENT_SOURCE_STATUS_1_0_20.md) records
support and verification boundaries. This map is not a line-by-line code audit.

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

Build ownership: `app/build.gradle` resolves removed-source cleanup paths during
configuration, applies explicit Android property assignments and reports javac
deprecation call sites. The app and both Android libraries use Java 17 targets.
Library build scripts place external native work under `cxx/archive` and `cxx/zipx`;
the ZIPX CMake file uses `wv` for WavPack's nested build and marks its independent
LZHAM target decoder-only. Release helpers request `:app:assembleRelease`; test
sources and explicit test-build tasks remain available separately.

```
com.readwide.manager            activities, per-screen controllers,
                                readers' front ends, TTS core
├── archive/                    archive detection, browsing,
│                               extraction, split-volume handling, password
│                               routing, and format-specific readers. This
│                               package mixes first-party parsers/decoders
│                               with bundled-library backends; creation
│                               support is limited. [package-level summary]
├── util/                        shared helpers and pure math
├── document/render              HTML/document rendering pipeline
│                               (FixedHtmlRenderer, RenderedDocument, blocks,
│                               styles) [package-level summary]
├── model/                       persisted/value types (Bookmark, DocumentAnnotation, ...)
├── view/                         custom views (CustomReaderView, ...)
├── adapter/                      list/grid adapters
├── document/doc                  legacy .doc (Word 97-2003) reader
│                               (CompoundFileReader, DocLegacyLayoutExtractor, ...)
├── image/                        image decode/drawable helpers
│                               (ImageDecodeHelper, ImageInfoReader, ...)
├── search/                       large-text search engine
├── controller/                   cross-screen toolbar helpers
│                               (ReaderToolbarController, ...)
├── widget/                       
└── ui/                           
```

Additional compatibility adapters live outside `com.readwide.manager`:
`javax/xml/bind/DatatypeConverter.java` and
`org/apache/commons/compress/archivers/zip/ReadwideZipLegacyStreams.java`.
The latter exposes the bundled ZIP Shrink/Implode decoders to the authenticated
ZIPX stream layer. The package tree is an ownership guide, not a fixed file count.

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
| `SettingsActivity` | settings; shared rounded text-option rows/list height for language and EPUB default-font popups (`SettingsReaderControlsController` owns font choices/preferences) |
| `ThemeEditorActivity` | reader theme editor |
| `LockActivity` | app lock |

`SettingsBackupViewModel` is the application-context backup worker, not an Activity.
It owns the single-job gate and retained confirmation/result state observed by Settings.

## MainActivity (file browser)

Owns ~20 controllers, each a vertical slice of browser behavior:

```
MainActivity
├── MainActivityStartupController      onCreate wiring
├── MainNavigationState               compact recreation snapshot + final-list anchor
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
├── MainRecentFilesController          separate cancellable history-read worker
├── MainFolderLoadController           async folder listing
├── MainFolderChangeObserverController filesystem change refresh
├── MainFileOperationProgressController
├── MainBrowseStateController          live folder cache / viewer-return validation
└── MainThemeController
```

`MainNavigationState` restores mode/path/query/filter/Back destination after the lock
gate; list controllers restore the saved row/offset after final data arrives. Large
lists and pending operations are not saved. `MainRecentFilesController` invalidates
older snapshots after swipe removal and closes its read worker at teardown, without
waiting behind paused file operations. `MainSearchFilterController` refreshes saved
scope/hidden/sort preferences on resume. `SettingsReaderControlsController` owns
lazy background font discovery and drops callbacks for dismissed/closed pickers.

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

The toolbar overflow remains fixed beside the current title (`Readwide`,
`Download`, or the active folder) and switches roles only while multi-select
is active. `MainHomeDialogController` owns the adaptive Home/folder preference
menu, including the persisted list/tile display choice;
`MainSelectionActionDropdownController` measures the selection count and
available localized actions and lets rows wrap at the screen edge rather than
clipping them.

`MainActivityStartupController` applies the display preference to both browser
and Recent lists, swaps `LinearLayoutManager`/`GridLayoutManager`, preserves the
visible adapter position, and keeps tile mode at two columns across
configuration changes. `FileAdapter` owns stable list/tile view types:
`item_file.xml` retains the compact 40dp preview in a fixed 42dp horizontal
slot, while `item_file_tile.xml` uses a 96dp cover area and reuses the same
selection, progress, path, open, and long-hold binding path. The optional
thumbnail toggle remains shared by the browser and Recent views. Its bounded
two-worker source queue is paired with a separate two-worker cache-lookup queue.
Attached-holder demand removes unused pending work; generation changes invalidate
requests and retry attached rows. Failure records and directory memory TTL remain.
`FileThumbnailLoader` supplies loose/folder images, ZIP/CBZ, RAR/CBR,
7z/CB7, ALZ, EGG, CAB, LHA/LZH, and TAR/CBT-family first-image covers, PDF page 1, and
raster EPUB package covers. It checks a source-fingerprinted, bounded
app-private PNG disk cache before decoding; source snapshots prevent a changing
file from being committed under stale metadata, disk replacement is atomic,
and a global two-slot gate bounds cache-miss work across both
adapters. `FileAdapter` deduplicates requests per list generation, makes stale
queued work exit before expensive format decoding, posts completion through an
adapter-owned main handler, and updates only subscribed visible icons without
scanning the dataset or rebinding rows. Cache-only PNG lookup bypasses the heavy
queue, semaphore and source lock; folder discovery remains on the normal path. Folder/archive selection is ordered and
bounded but can fall through past a corrupt or device-unsupported first image.
Layout-specific thumbnail keys prevent a list-sized memory result from being
reused as a low-resolution tile cover.

Recent-file refresh uses `setItems` diffs rather than full dataset replacement.
Progress payloads refresh badges and cover identity without resetting unrelated
row state; combined selection payloads are also applied. An already displayed
same-binding archive cover remains visible during off-thread identity validation.
Rejected cache identities clear only their matching old cover; a different file,
generation or replacement cache entry is not cleared by the stale callback.
New/recycled holders still wait for archive identity validation. Home heading
resources use 12sp text and 38dp minimum rows, keeping Clear all at 34dp.

`MainArchiveImageOpenController` delegates whole-archive comic-page collection
to `ArchiveEntryListController.collectImageSequence()`. Direct comic opening and
whole-archive auto-opening therefore use full internal-path natural ordering:
folder components are compared before repeated page basenames. If the user
navigates into an archive folder and opens an image there, the sequence remains
scoped to that folder and follows its visible browser order.

Provider URI copies are coordinated by `FileUtils` with an interruptible fair
lock. This preserves serialized atomic `opened_files` prune/commit semantics
without trapping a cancelled SAF worker behind another long provider copy.

### Lifecycle and shared ownership

- **File-operation ownership:** `MainFileOperationProgressController.ProgressQueue`
  keeps the first unfinished token's pause/cancel controls visible while later jobs
  wait on the existing serial executor. Finish promotes the next owner; close detaches
  listeners and cancels all tokens. Old dialog callbacks cannot control a replacement.
  `MainActivity.PendingFileOperation` selects run/discard exactly once for extraction
  passwords and clipboard claims, including work abandoned by `shutdownNow()`. ZIP creation
  rechecks pending membership and contains nonfatal writer errors at `createForUi`;
  `ArchiveZipWriter` and `ArchiveSourceSnapshot` preserve cancellation at return boundaries.
  Paste confirmation/worker/completion uses a captured `FileClipboardController.PendingItem`;
  batch claims use identity-set membership. Paste/delete completion uses the Activity's
  UI dispatcher independently of search-handler teardown, updating shared/persisted
  state before suppressing destroyed-screen refreshes/toasts (bulk-delete metadata
  writes remain on the worker). Archive batch planning
  errors are contained at the worker boundary without adding format support.
  `FileClipboardBatch` owns one worker's inventory, just-in-time destination naming
  and committed results. It never mutates queue membership or Android UI.
  `MainClipboardController` owns dialogs, claims and completion; only successful
  items retire in a linear pass. Refresh respects the current browsing context.
  `FileSystemOps` preserves committed moves after nonfatal reporting errors and
  rechecks overwrite permission before backing up a newly appeared copy target.
  Rename helpers separately validate names, distinguish case aliases, commit a
  temporary hop and restore the original without overwriting occupied names.
  `BookmarkManager.moveReferences` dispatches both successful rename and clipboard
  move to the existing file/prefix rebinding rules. `PrefsManager.moveFolderReferences`
  separately rebinds navigation preferences after successful directory moves/renames,
  using `NavigationPathRewriter` for whole-segment lexical matching. Rename dialog/keyboard callbacks check
  screen/dialog lifetime. Per-root progress folder sets are complete even when
  selected roots share a parent and finish in a different order.

- **Thumbnails:** `FileAdapter` attachment/recycling uses `VisibleThumbnailBindings`; `FileThumbnailLoader.decodeCachedOnly` serves PNG cache hits through a separate queue. Archive memory hits are validated there with the same volume-aware identity as the PNG cache. Last-subscriber removal/dataset invalidation cancels archive `FutureTask` decodes through existing engine interruption checkpoints. Completed results target matching visible icons; source changes cannot substitute an old memory bitmap.

- **Thumbnail preparation:** `FileThumbnailLoader` keeps a stable eight-candidate prefix for covers and snapshots cache size/time before eviction sorting. Directory/archive listing remains required; disk persistence still runs synchronously within decoding.

- **File operations/search:** `FileClipboardController` owns queue membership and single-item plans; `FileClipboardBatch` coordinates worker inventory, just-in-time naming and committed results; `MainClipboardController` owns UI claims/completion. `FileSystemOps` owns staging/commit/rollback, one operation-local copy buffer and progress-enabled direct moves. `FileTreeWalk` supplies cancellable no-follow traversal; `FileTreeProgressTracker` collects bytes/items/folders together. `MainFileOperationProgressController` uses `LatestValueDispatcher` for latest-only delayed redraws. `FileSortUtils` snapshots archive keys and uses iterator-based list access. `ReaderSearchController` uses `TextMatchIndex`/`MatchOffsetIndex` on a bounded worker with content/options/generation checks. `DocumentSearchController` shares non-overlapping count/highlight ranges; `DocumentSearchCounts` and `LargeTextMatchIndex` support cached navigation. `SearchMatcher` uses bounded KMP literal scans and retains whole-input regex match starts. Folder search remains recursive from the chosen starting folder.

## ReaderActivity (TXT reader)

`ReaderMemoryController` keeps launch URI ownership when preparing a restore from
the resolved local copy. `ReaderRestoreTargetMath` gives an explicit current path
precedence over any retained URI, matching `ReaderFileLoadController` target selection.
The legacy Go To dialog delegates whole-document percentage/line requests to
`ReaderPageJumpController`: global page routing for percentages, and cancellable
displayed-line resolution in `ReaderLargeTextPartitionReadController` on the existing
partition worker before `ReaderLargeTextPartitionNavigator` adopts a character target.
The unresolved line request does not invent a persisted character. `AutoPageTurnController`
uses reader readiness/physical large-file EOF callbacks instead of estimated page labels;
ticks during partition or layout work wait without queuing further page requests.
`ReaderDocumentNavigationInstrumentedTest`, URI session regressions and restore-target
math tests describe these paths as source only; they have not been compiled/run.

`ReaderReloadController.ensureLoadedTextCurrent` is the UI admission gate for live
file size/mtime and applied-transform identity. Partition/cache, search and exact-index
results check it before adoption; changed sources rebuild the session with context.
Its read-only `loadedTextMatchesCurrentSourceAndTransform` also gates final autosave
and background trim/restore without launching work during teardown. Shared
`contentReloadIntent` keeps owned queued destinations and removes old page/partition/
launch-anchor hints. Partition-mode changes capture their target before queue reset.
`ReaderFileLoadController` publishes captured source/transform provenance with text
and cooperatively cancels obsolete first-load scans. Snapshots keep the applied
signature, not current settings. This is metadata-based detection, not a guarantee
against same-size/same-mtime external replacements.

`ReaderPreferencesController` applies margins before relative insets within
`CustomReaderView.updatePresentation`; listeners see only settled geometry.
Its applied view/preferences signature is published after that update and compared
to live render settings plus applied partition policy before preserving saved hints.
`ReaderActivity.readerLayoutPreferencesSignature` includes overlap and is shared by
this check and saved presentation identity. `ReaderMemoryController` clears stale
page/pixel hints without changing the owned character/context; background restore
compares a private transient Intent signature to the current presentation too.
Selection hit testing distinguishes insertion endpoints from word-hit characters.

- **Contents:** `ReaderContentsController` owns on-demand scanning, a searchable
  recycled list, manual entries and hide/restore actions. `util/TxtContentsScanner`
  recognizes conservative repeated heading families (including `345===` / `345---`
  decorated numbers) in displayed UTF-16 coordinates. A bare decorated marker may
  take one next-line title, bounded by blank lines/EOF; presentation labels are kept
  separate from original marker offsets/source anchors. Per-family decimal sequences
  prefer a clear decorated run (three consecutive numbers) over a smaller ordinary
  numeric family without a consecutive pair, retaining parts/nondecimal families.
  Standalone `<--Title-->` lines form an independent nonnumeric repeated family;
  strip wrappers only from the display label, retaining original offsets/anchors.
  Exclude empty wrappers, HTML comments/tags and inline fragments.
  Bare Korean episode markers (`761화` / `제761화`) can take a next-line title too.
  `util/TxtContentsOptions` selects built-in formats and boundary/repetition/prose/
  combining conditions, with conservative defaults. Advanced RE2/J 1.8 rules match
  whole trimmed candidate lines; the first usable match wins before built-ins,
  using group 1 or the whole line as its label. Validation runs on the worker.
  Detector revision 6 and option keys invalidate only automatic caches, not edits.
  `util/TxtContentsRepository` separates disposable auto-index caches from atomic
  path/text-version-scoped edits. Options are atomic path-scoped files in a separate
  directory, without changing text-version identities. All formats off gives manual
  entries only. Font/margins do not invalidate heading indexes.
  Normal text uses the loaded snapshot; large text reuses the existing line transform
  and verifies source bytes/stamps. Exact page lookup is read-only and signature-checked;
  jumps delegate to existing absolute-position navigation. No paging algorithm change.
  Contents child confirmations/title entry reuse rounded alert styling, retaining
  the parent list instead of invoking the single-positioned-dialog replacement path.
  `ReaderContentsController.roundAction` locally adds rounded, state-aware surfaces
  and bounded ripple feedback to Contents actions and its child-alert buttons; the
  shared dialog-button style is unchanged. Help/status text has 12 dp side insets.
  Child buttons have wider 24 dp horizontal gaps, 4 dp vertical surface insets and
  48 dp minimum touch targets. The Options editor scrolls and keeps draft reset/
  cancellation separate from accepted asynchronous saves.
  Main action rows use a 40 dp minimum, 4 dp vertical padding, 8 dp row gaps and 12 dp panel
  bottom padding. Child button-panel padding (0 dp top / 10 dp bottom) keeps actions
  above the lower edge without view translations. Standard child buttons have 2 dp top/bottom
  margins for spacing when stacked. The regex input has a 12 dp
  margin after the examples. Wrap-content sizing preserves long/large labels.
  Standard child spacing uses `ViewGroup.MarginLayoutParams` and sets start/end as well as
  left/right, preventing theme-defined logical margins from overriding it in LTR/RTL.
  Options instead owns a horizontal Reset / Cancel / Save row with 2:1:1 weights,
  a 24 dp Reset–Cancel gap, an 8 dp Cancel–Save gap and equal-height, multiline
  Button controls (48 dp minimum). It has no
  native dialog action buttons, avoiding automatic ButtonBarLayout stacking/reversal.
  Save/reset enabled states and error recovery use explicit references to these controls.
  The scanner/repository/adapter impose no fixed heading-count or chapter-number cap;
  large scans reach EOF. The user traced the 760 report to the wrapped-title format
  now handled by the detector; runtime validation remains pending.
  `util/TxtContentsBackup` includes durable options/manual/hidden records in JSON
  backup with validated hash-only private targets and per-record transaction rollback.
  It shares EDIT_LOCK through commit/undo; caches are excluded. Rename rebinding and
  old-version edit relocation remain unsupported. `AtomicUtf8File.read` reports
  malformed UTF-8, so durable backup reads cannot silently substitute title/context
  characters; only missing files are treated as absent. Its writer/schema is unchanged.

The most finely decomposed screen: 41 `Reader*` controllers. Groups:

- **Lifecycle/shell:** `ReaderLifecycleController` (owns onDestroy teardown),
  `ReaderActivityStartupController`, `ReaderShellController`,
  `ReaderChromeController`, `ReaderMemoryController`,
  `ReaderPreferencesController`.
- **Text loading:** `ReaderFileLoadController`, `ReaderFileApplyController`,
  `ReaderReloadController`, `ReaderEncodingController`,
  `ReaderLoadedTextSnapshotController`, `ReaderLoadingWindowController`.
  Snapshot caching/restoration validates the loaded content's known size/mtime
  against a current regular file through `sourceMatchesContent`; adoption carries
  those stamps to Contents. `restorePositionAfterLayout` owns current-layout
  completion by load/switch/path/view/content identity, remembers the target for
  another recreation, and marks persistence ready only after landing. New navigation
  with a usable layout, replacement and release cancel the callback. Initial full-load
  completion uses `ReaderFileApplyController.finishWhenLayoutReady` with the same
  current-layout/ownership principle, cancelled on new load/release and checked on
  layout or same-bounds style notifications. Its listener detaches before landing.
  Size/mtime checks are not an atomic file snapshot.
  `LoadedTextSnapshot` also owns the applied partition mode; incompatible snapshots
  fall back to the saved viewport's normal load, and pending switches are not cached.
  Mode changes use the file-load lifecycle and publish the applied mode only on
  successful admission. Initial EOF restoration shares the partition applier's
  end clamp and scrolls before persistence is enabled.
  An unmatched initial target outside the window retains its absolute character
  and re-enters the file-load lifecycle without line/byte partition hints; it must
  not bypass screen reveal/loading completion through an ordinary partition switch.
  Worker-side decoding/allocation failures use the loader's generation-owned error
  callback. UI/native allocation failures remain a separate runtime boundary.
- **Large-text engine** (windowed reading of huge files):
  `ReaderLargeText{State, Paging, Jump, Cache, BoundaryHandoff,
  PartitionRead, PartitionPrefetch, ExactAnchorBuild, ExactPageIndex}Controller`,
  backed by `search/LargeTextSearchEngine` for find. Start-line partition
  reads currently route through `LargeTextPartitionReader.ForwardCursor`, a
  shared forward-read cursor with a lookbehind/lookahead replay queue owned
  through `ForwardCursorSession` in `ReaderLargeTextPartitionReadController`;
  retirement rejects new reads and closes off the UI thread after active I/O.
  Eligible char targets within the loaded window are mapped to a canonical start
  line by `LargeTextContinuityMath.partitionStartLineForLoadedChar` and reuse this
  cursor. It rejects windows whose rendered newline count differs from emitted
  logical-line count (e.g. display rules inserting breaks). Resolved line jumps first
  recheck the existing cache, then use the same loaded-window mapping when available.
  Request/cache generations and source/policy admission remain authoritative.
  Unloaded/anchor-relocated or otherwise unmappable targets retain `readForChar`,
  which bypasses the cursor and scans to EOF. Any change to this
  path is checked against the full-scan path for field equivalence
  (`content`, `baseCharOffset`, `bodyStartCharCount`, `bodyCharCount`, plus the
  remaining result metadata) by `LargeTextForwardCursorEquivalenceTest`, which
  also verifies canonical body tiling without skips or duplication.
  A completed background match count publishes a bounded
  `search/LargeTextMatchIndex`; nearest/nth navigation then uses binary lookup
  until file state, options, blank-line mode, or display rules change.
- **Navigation:** `ReaderSeekController`, `ReaderPageJumpController`,
  `ReaderPagePositionController`, `ReaderTapNavigationController`.
  `ReaderActivity.getLineOffsetWithinDisplayedPage` delegates to `ReaderPagePositionController`,
  which uses the displayed global exact-page anchor. `CustomReaderView`
  measures the selected visible row relative to that supplied character anchor; partition-local
  starts remain the fallback only without an exact index. Before partition replacement,
  a shared logical line can transfer one measured plain-TXT `PartitionRowOrigin`;
  its label uses the same absolute visible-row character as page display. Keep the
  first row while at least half is visible, otherwise use the next actually visible
  row inside the drawing clip. New bookmark/reading-position capture shares this
  rule instead of the former 55% sample. Existing bookmark coordinates/context and
  migration, raw navigation, exact page formulas and v6 cache identity are unchanged.
  Content/window/anchor/signature checks prevent reuse on another layout. Markdown or
  unmeasurable ranges omit the suffix. `ReaderLargeTextPartitionNavigator` owns request
  generations, loader cleanup and cancellation for local jumps or fresh manual drags.
  Its `ViewportHandoff` carries the visible character/baseline plus content, window
  and layout identity for automatic window replacement. It rejects adoption after
  a new contact/scroll/reflow or when the destination cannot represent the same text.
  Explicit page/search/bookmark jumps retain their own landing rules.
  Without exact anchors, `ReaderLargeTextPagingController` uses physical window/EOF
  boundaries for sequential movement, not estimated label limits or a guessed
  last-page shortcut. The redundant unreachable boundary fallback is removed.
  `TextSearchMath` scans overlapping context candidates with wide scores; only a
  provably best match at the saved position bypasses scanning. Manual bookmark
  capture requires ready content/current layout and uses a checked range-end sum.
  `ReaderActivity.onReaderManualScroll` also clears stale seek previews without stopping
  the new gesture. A replacement destination
  resets old queued taps; `LargeTextPartitionSwitchState` stores the pending and latest
  queued absolute character destinations. Same-request taps resolve their next exact
  destination from that character, not from a captured estimated page/delta.
  Boundary callbacks are
  cancellable. Explicit backward page fallback uses the loaded window's predecessor;
  automatic backward manual overscroll waits for idle and preserves the old top row
  in the previous window. `CustomReaderView.stopScrollMotion` retires inertia/old
  gestures on replacement; DOWN establishes contact ownership before drag detection,
  and UP/CANCEL clears it. Same-text reflow retains a character viewport anchor.
  Touch movement retains fractional deltas and drops boundary/reversal debt.
  Unsupported pointer transitions retire the gesture until the next DOWN.
  `setReaderManualScrollY` lets drag/fling return from search-only bottom space;
  normal programmatic clamping and the page model are separate and unchanged.
  The applier commits immediately only with `hasCurrentTextLayout`; otherwise it owns
  a real layout-readiness callback, cancelled on replacement/release and checked against
  load/switch/view/content identity. `ReaderMemoryController` saves the visible row
  during automatic handoffs but the pending/queued requested character for explicit
  navigation, even across an estimate-to-exact transition. Durable capture additionally
  rejects stale source/transform identity; transient recreation/reload context remains
  available. Background release retains one target without old partition hints, skips
  stale durable writes, and drops page hints if source/policy changes before or after
  release. Captured file size comes from the loaded provenance. `LargeTextPageModelMath`, final jumps and offset
  commits prefer the current exact total over captured pending estimates.
  `ReaderMemoryController.captureReadingStateForRecreation` separately retains an
  active partition's owned destination while layout is unready; ordinary autosave
  keeps its guard. `ReaderLifecycleController.snapshotMatchesSavedViewport` requires
  the snapshot character/launch to match saved viewport state; otherwise startup uses
  its existing restored-intent fresh load. These repairs remain runtime-unverified.
  After a large-TXT slider release, `ReaderSeekController` clears its numeric overlay
  so the partition target owns the label; active-drag preview is unchanged.
  `ReaderLargeTextExactPageIndexController` gates signature/build/ready-anchor access
  on usable current-text geometry; zero-size or consumed-width layouts wait for a real
  layout callback without polling. Redundant usable callbacks preserve same-layout work.
  `CustomReaderView.setOverlapLines` recalculates the natural scroll range before new
  anchor extension, preventing stale extra end space after reducing overlap.
  `ReaderActivity.loadedTextWindowReachesDocumentEnd` passes real EOF metadata through
  file/partition adoption and snapshot restore. Static/runtime/next-page paths stop
  overlap-only tails only at true EOF; nonterminal-window page steps remain unchanged.
  Saved-index file format is unchanged, but its shared page-model signature is revised.
- **TXT coordinate/drawing bounds:** `LargeTextCoordinateMath` validates wide
  character/line accumulation before narrowing to the existing signed-int model.
  Partition and exact-index scans fail with `IOException` for unrepresentable actual
  content, with existing reader/cursor cleanup; a private traversal cursor may pass
  the maximum only for the final synthetic separator. `LargeTextLinePartitionResult`
  rejects unrepresentable base-plus-content ends. Partition-end, lookahead, handoff
  and estimate math avoid intermediate wrap. This does not add full 64-bit TXT support.
  `CustomReaderView` preserves the available text slice if the viewport is shorter
  than one row, while ordinary bottom partial-row clipping remains unchanged.
- **Active-scroll preparation:** `ReaderLargeTextBoundaryHandoffController` uses
  `CustomReaderView.getCharPositionAfterViewportScreens` over the loaded layout;
  `LargeTextScrollPrefetchState` selects two screens normally or four briefly at
  forward speed >= two screens/second (250 ms sampling gap, 300 ms fast hold).
  Only the next manual/lookbehind window is queued near the body boundary, with
  existing cache-ticket deduplication/foreground priority and a 500 ms retry cooldown.
  Content/window/cache changes reset tracking. Preparation runs during touch/fling;
  the actual viewport-preserving swap still waits for idle. A prepared manual window
  is reused after crossing the seam when it still covers the visible top row.
  No source scan or off-screen layout runs in the scroll callback. This behavior and
  the new regression sources remain uncompiled/unrun; timing is not measured.
- **Dialogs/UI:** `ReaderDialogStyleController`, `ReaderBottomControlsController`,
  `ReaderAppearanceDialogController`, `ReaderFontDialogController`,
  `ReaderToolsDialogController`, `ReaderTextDisplayRuleDialogController`,
  `ReaderSearchController`, `ReaderBookmark{Dialog, Action, PageModel}Controller`,
  `ReaderActionController`.
  `ReaderBottomControlsController.PageMoveControls` synchronizes the open Page Move
  range from reader position updates, preserves focused numeric input and defers
  range changes during a drag. `ReaderSeekController` also freezes toolbar drag
  bounds. New file loads/destruction dismiss the owned Page Move dialog.
  TXT font cards are styled in `ReaderDialogStyleController`; document font cards
  are styled in `DocumentFontDialogController`. Both use 40 dp minimum/wrap-content
  rows with 4 dp vertical padding. Their main/system font pickers omit internal
  top/bottom separators while keeping rounded outer borders and scroll clipping.
- **TTS:** `ReaderTtsController` (shared core, see TTS section). Text and
  highlight live in `view/CustomReaderView` (`setTtsHighlightRange`, fully
  bounds-clamped).
- **Notes/highlights:** TXT selection endpoints are converted to absolute
  document offsets by `ReaderActivity`; `DocumentAnnotationManager` persists
  them outside the source file and `CustomReaderView` projects persistent
  highlight ranges into the currently loaded large-TXT partition. The reader
  toolbar exposes the shared annotation list directly; exact duplicate
  highlight ranges are rejected and older duplicates are collapsed on load.

Rendering is the custom-drawn `view/CustomReaderView` (not a WebView).
Snapshot restore reapplies the collapse policy before indexing/search/prefetch.
Encoding reloads clear old anchor/partition extras. Stale-coordinate anchor recovery
uses a rolling context window and saved-offset proximity; exact paging arithmetic and
configured overlap remain unchanged. New regression sources have not been executed.

### Lifecycle and shared ownership

- **TXT saved-page reuse:** `ReaderFileApplyController` starts saved-index validation
  after first layout; `ReaderLargeTextExactPageIndexController` retains full source
  checks and the layout debounce. `LargeTextPageIndexCache.configurationKey` ignores
  only Android's bookkeeping sequence using literal token scanning, avoiding regex
  compilation on the large-TXT opening path; `TextPageFontIdentity` recognizes the reader's
  built-in sans variants. `LargeTextExactPageIndexState` tracks checking vs calculation;
  `LargeTextPageModelMath.pageNumberLabel` supplies pending/exact/estimated UI labels.
  Exact page-offset arithmetic is unchanged; imported fonts remain process-scoped.
  The v1 payload is streamed by `LargeTextPageIndexCache`, without the former 8 MiB
  save cutoff; checksum validation and parsing share one rewound file descriptor.
  `PageIndexCacheManager` retains its existing recent-entry protection/eviction policy.

- **Search/filename preparation:** `SearchMatcher` deduplicates normalized hits by original start and provides a single-pass bounded `lastUpTo` for backward partition search. `FileThumbnailMath` uses lowercase-only internal predicates and direct split suffix checks without regex compilation.

## DocumentPageActivity (EPUB / Word / HWP / Markdown)

`DocumentWebViewController.scaleFor` prefers a finite positive `onScaleChanged`
observation owned by the same WebView and page-load generation. Initial/unmatched
loads retain an uncached compatibility query; invalid/stale callbacks cannot
replace the observation. Fast-scroll range and Markdown visual height share this
accessor. Zoom reset uses the result of `zoomOut()` within its existing bound;
gesture minimum-zoom probes remain separate. Document/PDF system-bar appearance
uses only the compat controller, without subsequent legacy flag writes. TXT
canonical viewport geometry is not changed by this compatibility cleanup.

Markdown: `MarkdownDocumentRenderer` protects inline code/link markup during emphasis,
indexes backtick runs by length, and attaches source anchors to fenced code/diagrams.
EPUB: `EpubBindingRewriter.transformEmbeddedCss` reuses the quote-aware tokenizer;
`EpubCssCompatibility` applies aliases to spine style blocks/attributes and detects
inline vertical declarations without scanning script/example text as CSS.
Its shared lexical walker keeps custom-property tokens opaque, preserves quoted
comment markers, handles XHTML style wrappers and resolves same-block writing-mode
order/importance. Only open scopes are retained. `EpubBindingRewriter.mayApplyToScreen`
filters clearly non-screen link/style/import/media rules; this is not computed CSS.
`DocumentArchiveUtils.EpubChapterStyles` follows chapter-local linked/imported sheets
with cycle-safe traversal and bounded summary caching. `DocumentPageActivity` stores
the resulting page flag, and `DocumentWebViewController` uses that same flag for
autosizing. The archive-wide vertical diagnostic no longer controls viewer layout.
`DocumentTextDecoder` also honors leading CSS charset declarations with BOM precedence.

Word formatting: `DocumentDocxLayoutExtractor` preserves explicit alignment/baseline
resets and exclusive line-height overrides. `TextStyle` separates highlighting from
shading; `FixedHtmlRenderer` applies highlight precedence. `DocParagraphProperties`
decodes explicit before/after spacing and zero-indent resets. The shared model keeps
default values while recording whether alignment/baseline were explicitly supplied.
`DocumentDocxLayoutExtractor.visibleChildren` unwraps content controls/custom XML at
block, row and cell levels across body/header/footer/note parts. Blank paragraphs and
special hyphens survive extraction; same-style runs are joined with a linear buffer.
`DocLegacyLayoutExtractor` tracks nested field instruction/result states and preserves
nonbreaking characters. No field evaluation or full tracked-change mode is provided.
It validates all CLX piece descriptors and the existing text budget before allocating
reconstructed text/FC mappings, rejects truncated pieces, and sizes text/FC buffers once.
Compressed characters use the MS-DOC mapping rather than a Windows-1252 shortcut.
`DocumentWordUtils.selectMarkupAlternatives` keeps one compatible DOCX branch with
its namespace ancestors. The layout reader unwraps the retained containers across
body/header/footer/notes; text-box paragraphs/tables use existing style handlers and
separate list counters. Drawing traversal includes VML fallback pictures and multiple
blips, without claiming exact floating coordinates. Binary HWP structural blocks now
use `DocumentHwpLayoutExtractor`'s shared preview paginator; tables stay intact and
extracted plain text is retained. `FixedHtmlRenderer` and Activity CSS preserve table
whitespace; list content can shrink to wrap. DOCX/HWP fallback paths pass memory/stack
exhaustion to `DocumentPageLoadController`'s generation-checked error/cleanup boundary.
The rendered paper now owns a shared `--rw-point` scale used by text, margins and
authored sizes; paper height is a minimum, not a crop. `RenderedPage` normalizes invalid
geometry. `RenderedTable` carries fixed width/alignment/indent, and `RenderedTableCell`
carries vertical alignment and cell-height minima. DOCX imports table/cell geometry and
defaults; HWP structural output uses the first `PageDef` plus stored table/cell metrics.
HWPX alignment is scoped to the cell/subList. `DocumentWebViewController` disables text
autosizing for rendered Word/HWP HTML while retaining pinch zoom and other document modes.

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

Markdown annotation actions are added by `widget/SelectionContrastWebView`.
`DocumentPageActivity` resolves the selected rendered block through the
renderer-owned `data-rw-src-offset`, stores the quote/source range in
`DocumentAnnotationManager`, and reapplies saved highlight markup after the
primary WebView page finishes loading. `DocumentAnnotationDialogController`
is shared with TXT for adaptive themed, scrollable list/open/edit/delete UI and
refreshes a visible list immediately after mutation.

Two-page EPUB spread: `isLandscapeTwoPageDocumentMode()` delegates to
`SpreadMath.shouldUseEpubSpread()`. A landscape image-page EPUB spreads on any
device; ordinary EPUBs also spread on Android large screens (`sw600dp` or
wider). Portrait and compact-phone text EPUBs stay single-pane.
`DocumentArchiveUtils` delegates image-page classification to pure
`EpubImagePageClassifier`; fixed-layout metadata alone is not enough.
`DocumentTextDecoder` decodes spine/metadata text
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

### Lifecycle and shared ownership

- **Scientific document notation:** `DocumentDocxLayoutExtractor.currentRunStyle`
  maps basic OMML script operands to `TextStyle`; `DocumentHwpLayoutExtractor` reads
  HWPX script flags and overlays specified inline properties. `FixedHtmlRenderer`
  recognizes bounded explicit math fragments, consumes complete script tokens and
  preserves surrounding run styles with `RunCursor`; link/anchor/ID runs are barriers.
  Unknown formulas remain literal. This is a limited HTML preview, not a math engine.

## PdfReaderActivity (PDF)

Viewport persistence uses Matrix-based `PDF_PAGE_COORD_v3` center anchors for
single/spread views and legacy-compatible continuous anchors. `PdfPageView`
captures/restores the Matrix; `PdfSpreadHighlightMath.Layout.unmapPoint` recovers
the source page from composite coordinates. Saved-instance state takes precedence
over launch bookmark extras for the same target. Background trimming retains its
anchor until cached/fresh render restoration completes. See
`DEV_CHANGES_1_0_18.md` for implementation details.

```
PdfReaderActivity  (implements TtsHost)
├── PdfReaderStartupController      startup; posts applyPdfViewportBarInsets
├── PdfPageTurnController
├── PdfSearchController             async find; Host interface seam
│                                   (goToPage/currentPage/pageView/runOnUi/
│                                   right-page identity + per-page mapping)
│      └── PdfTextSearchEngine     -> PdfSearchText (query/offset/separator logic)
│                                -> util/PdfGlyphBoxMath (shared glyph box)
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
`PdfPageRenderPlan`; raw pixel capping remains in `PdfRenderSize`.
`PdfPrefetchQueue` is a main-thread rolling neighbor plan with one active native
request. `PdfReaderActivity` retains navigation direction separately from animation
state, promotes an active prefetch requested by the reader, and shares a fit-page
cache budget between visible/prefetch renders. Completion reselects current
priorities; geometry invalidation retains active-token ownership until completion.
Four valid neighbor slots are filled at edges. Sticky volatile cancellation retires
unneeded work before native rendering where possible; an exact pending page may
still be promoted. Completed single/spread bitmaps are prepared on the worker;
cold Matrix display skips the hidden legacy layout just like cache-hit display.
Non-continuous turns call `BookmarkManager.saveReadingStateDeferred`, which publishes
memory state immediately and uses `util/CoalescingSnapshotWriter` for 100 ms batched,
revision-ordered disk saves. `ReaderState.copy` gives the writer owned snapshots.
Pause/close, deletes, moves and imports retain synchronous atomic checkpoints.
`PdfContinuousRenderQueue` similarly keeps one outstanding continuous render, but
prioritizes all visible rows before one neighbor per side. Viewport changes retire
obsolete queued tokens; capped allocation shares the continuous cache across three
pages. `PdfContinuousPageAdapter.RenderedPage` keeps intended display geometry with
each bitmap, including cache/retry paths, and bound-holder reference counts protect
shared bitmaps. Hidden/detached lists do not submit new work after cache cleanup.
`PdfSharpPatchPlan` shares capped viewport geometry and density/coverage reuse
checks with the Matrix view. Sharpen requests and continuous geometry jobs reject
obsolete generations before rendering; spreads can retire between source pages.
Index math
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

`PdfRendererSession` owns the visible/prefetch renderer-descriptor pairs. The activity
publishes worker-opened sessions through an atomic pending handoff and retires them
on render workers, never by waiting for render locks on the UI thread.

### Lifecycle and shared ownership

- **PDF search/read-aloud:** `PdfSearchController` and `PdfTextSearchEngine` own query lifecycle and original glyph maps. `PdfSearchText` supplies original-offset matching and null separator geometry; `PdfGlyphText` checks spoken-text alignment. `ReaderTtsController` persists `TtsHost.ttsTextFormatVersion` via `PrefsManager`, and `PdfTtsTextSource` resolves legacy/new checkpoints.

See the [1.0.19 release notes](GITHUB_RELEASE_NOTES_READWIDE_1_0_19.md) for inherited format boundaries and the [development changes](DEV_CHANGES_1_0_19.md) for their implementation history. The [1.0.20 status](CURRENT_SOURCE_STATUS_1_0_20.md) records the current baseline.

ZIP integrity: the Commons bulk/single-entry fallbacks call
`ZipxAesArchiveReader.extractPlainEntry` for explicit size/CRC verification and
guarded commit. Supplemental ZIPX descriptors also guard the full authentication/
decoder-close lifetime. ALZ's payload writer checks exact decoded lengths and
guards bulk writes; its indexed single-entry caller retains source-change rollback.
`EggArchiveReader.SolidForwardReader` retires read/advance/drain failures, including
interruption, and closes input/spool ownership. These do not change codec coverage.

## ImageReaderActivity (images)

```
ImageReaderActivity
├── ImageDialogStyleController
├── ImageReaderSliderController
├── image/ImageDecodeHelper, ImageInfoReader, LoadedImage
├── image/ArchiveImageSpreadDrawable  allocation-free two-bitmap surface
├── util/ArchiveImageSpreadMath       tall-page gate + mixed-screen navigation
├── util/ArchiveImageSpreadNavigator  actual mixed-screen history/reversal
├── util/ArchiveViewerTimeoutPolicy   numeric input + monotonic/same-boot deadlines
├── util/ImageExportName              safe original-byte export names
├── util/ImageSequenceState        sequence bookkeeping (applyRename matches
│                                  the exact pre-rename path)
├── SequentialArchiveImageReader      handed-off RAR/7z/TAR/solid-EGG forward reader
└── lifecycle: 5 executors + LruCache (min(128MB, heap/5), recycle-on-evict),
    with two-worker lightweight neighbor decode. Archive pages upgrade to a
    denser preview profile when current, and a visible spread companion uses
    that same profile; explicit zoom uses the detail tier. A stopped viewer
    answers background memory pressure by invalidating decode generations and
    releasing bitmap surfaces/cache while retaining the current archive index;
    onStart reloads that same page. The archive-only background timeout is a
    numeric Settings preference (0..10080 minutes; default 0 disables expiry).
    onStop starts the elapsed-time interval; saved intervals require a matching
    boot count before use after recreation. All workers shut down in onDestroy.
```

### Lifecycle and shared ownership

- **Recreation and load ownership:** `ImageReaderActivity` persists the accepted displayed path/entry (falling back to the launch/current target before any display) and rebuilds archive sequences through `ArchiveEntryListController.restoreImageSequence` and `ArchiveImageSequenceLoader`; launch intents carry small browser scope/order recipes. `DocumentPageLoadController` parses into static `DocumentPageActivity.ParsedDocument`, then generation-checks UI adoption. `canSaveDocumentReadingState` rejects incomplete loads/restores; passwords remain memory-only.

- **Viewer identity:** `ArchiveImageSequenceLoader` captures `ArchiveSourceSnapshot` and passes it through `ImageSequenceHandoffStore.Sequence`. `ImageReaderActivity` retains it for off-thread cached-page, extraction and decode checks; resume re-resolves membership. Source mismatch retires display/result/progress ownership until reopen. `SequentialArchiveImageReader` validates known volumes before reuse/publication and rejects fallback on a changed session. Metadata checks cannot detect same-size/same-mtime edits or provide an external-write transaction.

- **Cached-page latency:** a dedicated single metadata worker keeps source validation out of the foreground decode/solid-extraction queues. Requests check the page generation before delivery; Activity teardown shuts down this worker with the existing pools.

- **Preview cancellation:** `ArchiveImageSequenceLoader` and `ArchiveImageEntryCache` stop interrupted sequence/fallback work and check again after extraction-lock waits. `ArchiveBrowserActivity` tracks pending image results independently of callbacks so destruction can close their prepared readers.

- **Image scheduling:** `ImageReaderActivity` uses `ImagePrefetchMath` navigation intent, `ImageSequenceState.SnapshotCache` and `ImagePrefetchRequests` ownership tickets. Ordinary warm-ups are bounded/deduplicated; foreground loads take over queued work or join started work after UI-cache publication. High-quality companions retain separate ownership. Full-resolution cache entries satisfy the single-page preview profile. Reader close is dispatched off the UI thread.

- **Image return and failures:** `restoreMissingImageSurface` checks live surface/pending-load ownership; `ZoomImageView.restoreImageReady` reattaches a retained drawable with the same matrix. Missing surfaces use the current-page loader. Accepted progress survives trim independently of the requested index. Typed foreground password failures use sequence rebuilding, not generic image failure; prefetch never prompts. Failed-decode cleanup runs on a worker. Reader creation does I/O outside the state lock and uses an epoch to reject publication after retirement.
  Bitmap-owning callbacks use `imageResultHandler`, which is not cancelled with
  ordinary UI work during teardown; stale callbacks recycle their results. Decode
  workers retain a local reference to the volatile bitmap cache. Aspect metadata is
  captured before cache admission, which can synchronously evict/recycle a value.

- **Image memory pressure:** `ImageDecodeHelper.decodeWithMemoryRetry` bounds smaller-allocation ImageDecoder retries for GIF/WebP preview, detail and warm-up, including static WebP. Cancellation remains terminal; the ordinary bitmap decoder retains its existing quality/sample policy.

## Shared TTS core (cross-viewer subsystem)

`ReaderTtsController.startNow` retires previous playback before restart checks.
Saved resume eligibility uses path/timestamp, including offset zero. TXT additionally
requires a source/encoding/transform signature and rejects legacy relative offsets;
document/PDF migration remains host-owned. Queue positions carry their original window
base. TXT continuation uses the consumed absolute character, not the next overlapping
display page, and highlights are remapped into the currently displayed window.
`TtsPlaybackBridge`
exposes live-host availability; `TtsPlaybackService` is non-sticky and removes orphaned
commands/null-intent sessions because the engine is still owned by the activity.
These lifecycle changes require engine/device verification.

```
TtsHost (interface)  <- implemented by ReaderActivity, DocumentPageActivity,
│                       PdfReaderActivity
├── ReaderTtsController      the ONE playback engine: segmentation queue,
│                            utterance callbacks, one logical page advance
│                            at a time (in spread mode, the next spoken page
│                            becomes the left/primary page of the newly
│                            displayed spread), stop/pause/sleep timer, dialog
│      ├── TtsSegmenter / TtsSpeechSegment
│      ├── TtsSpeechQueue             stable sequence IDs, spoken-prefix retirement
│      ├── TtsPlaybackTimer           playback-only elapsed time and deadline policy
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

Speech submission uses a current source window plus one lookahead window (up to
1,400 UTF-16 units and 32 segments each), not the whole chapter. A cursor tracks accepted/normalized
source coverage; only resident continuous sources cross page seams. Pause/resume and
page replacement rotate callback generations independently of queue sequence IDs.
Sleep timing excludes synthesis gaps and pauses; source sentence boundaries gate
finish-sentence mode, while immediate mode schedules a cancellable handler deadline.

## Document/PDF pure helpers at the root package

`DocumentZipSession` retires publication ZIPs through reference-counted request leases.
`DocumentPageActivity` publishes ZIP/host/metadata snapshots, saves current viewport
state for recreation and uses fresh Markdown anchors with native-scroll fallback.
`PdfTextSearchEngine` matches pages as a whole-document traversal completes them;
only complete extractions up to eight pages / 128K UTF-16 slots are cached. Larger
documents are scanned again per new query. PDFBox/current-page/hit memory is outside
that cache budget; page dimensions remain available without retaining all glyphs.
Search failures clear partial matches through `Listener.onSearchFailed`; the controller
clears highlights and reports failure. `PdfSearchController.pendingDocument` transfers
unclaimed lazy-load handles to ordered loader-worker cleanup when the viewer closes.
Markdown manual bookmarks require a new accepted capture, while outgoing document saves
checkpoint cached state/native scroll before replacement. Opening viewport state is
retired after initial restoration; pending theme/search reload state takes precedence.

`ReaderLifecycleController` saves small current TXT viewport/context Bundles for the
no-static-snapshot restoration path. `ReaderFileLoadController` invalidates retained
`LargeTextSearchEngine` indexes and stops TTS on reload. Partition navigation/prefetch
check load/switch identity and `LargeTextPartitionCache` epochs before UI publication.
`LargeTextPartitionCache.PrefetchTicket` identifies each pending normal/manual read:
new switches replace stale reservations; publication/release cannot retire a newer
owner, and foreground cache writes supersede background work. Character-position
hits refresh LRU recency without changing canonical body selection.
`LargeTextWorkQueue` keeps one foreground-priority partition worker; scoped
`LargeTextPartitionReader.readWhile` checkpoints cancel stale reads and make active
prefetch yield to visible work. Partial cursor reads close on cancellation; next-task
thread interruption state is not changed. Before cursor publication, fresh/resumed
reads compare source size/mtime with the pre-read snapshot and retire changed-source
results within the cleanup scope. Cursor stamps use that snapshot. Exact partition/
page calculation arithmetic is unchanged.
`TextDisplayRuleManager.getExactSignature` reuses rule hashes for 16 recent paths,
invalidated by raw preference changes/saves/imports; source/layout freshness checks remain.
`CustomReaderView` builds annotation paths only for viewport lines plus edge slack.
`LargeTextExactPageIndexState` performs locked direct lookups over immutable anchors;
tap destination selection also stays inside the lock without a full-list copy.
The existing binary-search math and full-snapshot callers remain unchanged.
`invalidateIfSignatureChanged` retires old-layout anchors immediately when restart
is scheduled, before debounce; identical signatures retain ready/running indexes.
`CustomReaderView.buildPageStartOffsets` avoids temporary context strings in the
partition indexer. Its explicit EOF flag suppresses overlap-only terminal pages;
the continuing-window path retains prior arithmetic. Chunked indexing holds a full
window until a next emitted line confirms continuation, then uses terminal coverage
carry at EOF to avoid republishing final lookahead as a new body page.
`LargeTextPageIndexCache` persists completed plain-text/TXT-reader Markdown indexes,
validating full content SHA-256, exact layout/encoding/
rules, Android configuration and checksummed anchor records. Cache misses and
non-cacheable inputs use the same builder. `PageIndexCacheManager` owns disposable
cache cleanup; its stable bookkeeping key is separate from payload identity.
`ReaderLargeTextExactPageIndexController.PAGE_MODEL_REVISION` is `txt-v6-eof-overlap`
in both page signatures, invalidating pre-correction indexes without changing the
cache binary format. Bookmark character/context anchors remain intact; the opened
file's stored page metadata refreshes when the replacement exact index becomes ready.
`TextPageFontIdentity` permits cross-process reuse for built-in default/serif/monospace
and process-scoped weak actual-face identity for other fonts. Markdown identity uses
`MarkdownSyntaxHighlighter.PAGE_LAYOUT_REVISION` and reader colors. Its spans are unchanged.
`LargeTextPartitionReader.readPartitionForChar` gathers statistics and a bounded
candidate partition together; exact layout and known-statistics cursor reads remain
separate. `LargeTextSingleScanEquivalenceTest` compares all fields with the two-pass path.

Search uses activity-owned `DocumentSearchController` plus pure
`DocumentSearchCounts` (per-page counts/prefix sums). TXT drawing consumes spans
from a bounded worker using `SearchMatcher.PreparedText`; large-TXT indexes use
primitive arrays up to 200,000 matches and fixed-width disk records thereafter.
Normal TXT count/navigation uses `TextMatchIndex` on the `ReaderSearchController`
worker. `MatchOffsetIndex` keeps sparse starts or a ranked bitmap within an 800,000-byte
retained primitive offset budget, allowing dense buffers to keep complete navigation
indexes beyond 200,000 hits. If neither complete form fits, prepared scanning retains
every result. Literal `SearchMatcher.PreparedText` scans use a query prefix table, no
band substring and no per-hit `Match` object, preserving original offsets and overlaps.
Query/options changes and memory/dialog lifecycle invalidate queued results.
See `DEV_CHANGES_1_0_19.md` for the inherited normal-TXT path; the earlier
`DEV_CHANGES_1_0_18.md` records large-TXT ownership and cleanup.
Java regex cancellation remains cooperative.

| Class | Role |
|---|---|
| `EpubImagePageClassifier` | distributed-spine book classification plus a stricter page-local near-image-only decision for fixed-layout scroll policy; excludes cover-only, mixed/text-heavy, and false CSS/object signals |
| `EpubViewportParser` | order-independent fixed-layout viewport metadata parsing |
| `EpubCssCompatibility` | safe standard/WebKit aliases for legacy EPUB vertical-writing, text-combine, and text-emphasis declarations |
| `EpubCfi` / `EpubCfiJavascript` | bounded point-CFI parsing plus injection-safe DOM target resolution; range/temporal/spatial forms are rejected |
| `EpubSmilParser` / `EpubMediaOverlayJavascript` | bounded parsing of explicitly linked local SMIL cues plus safe DOM active-fragment markup |
| `EpubPlaybackGate` | foreground, granted-focus and transient-loss resume policy used by `EpubMediaOverlayController` |
| `EpubMediaCache` | worker-thread audio extraction with framed SHA-256 keys, unique temporary files, and size/CRC validation |
| `DocumentTextDecoder` | UTF BOM/sniff/declaration-aware EPUB HTML/XML decoding |
| `DocumentContentAnchorJavascript` | DOM capture/restore helpers for rendered-document anchors; atomically installs/captures on normally script-disabled EPUB pages, pairs a viewport caret with its stable sentence element for `vertical-rl`, optionally scans that same physical column from its first fully visible glyph for an explicit bookmark's presentation text, detects namespaced semantics without fragile CSS escaping, retains native WebView position fallbacks, and preserves the horizontal v1 path |
| `util/DocumentAnchorMath` | pure policy for accepting new matched-caret vertical anchors, recognizing earlier stored v2 anchors during restore, and choosing column-start/focus/sentence bookmark presentation text without changing restore identity |
| `PdfPageRenderPlan` | shared fit/display/allocation plan for visible, prefetch, and continuous PDF page renders |
| `PdfPrefetchQueue` | one-active rolling neighbor priorities, request ownership and shared fit-page cache budget |
| `PdfContinuousRenderQueue` | visible-first continuous demand, bounded neighbor work, token invalidation and three-page bitmap budget |
| `PdfSharpPatchPlan` | capped sharp-patch sizing, sufficient-base-detail gate and coverage/pixel-density reuse |
| `PdfRenderSize` | overflow-safe bitmap pixel/dimension cap used by PDF render plans and patches |

## util/ highlights

| Class | Role |
|---|---|
| `TxtContentsOptions` | immutable per-file heading formats/conditions and worker-compiled RE2/J custom rules; does not alter text or pagination |
| `NavigationPathRewriter` | lexical whole-directory-path rebinding for saved pins/recent folders after a successful move; no filesystem probing |
| `LargeTextPageIndexCache` | streamed v1 exact TXT anchors; full source/layout/CRC validation, no 8 MiB serialized-index cutoff |
| `LargeTextCoordinateMath` | checked signed-int UTF-16 positions/logical lines with wide intermediate accumulation; rejects unrepresentable actual text and permits only a private final-separator cursor past the maximum |
| `TextPageFontIdentity` | actual resolved font identity; non-built-in faces use weak process-scoped tokens rather than font-path hashes |
| `SpreadMath` | two-page spread index math (step/right index/visible range/clamp/turn/can-turn), shared by document + PDF; edge behavior is covered by `SpreadMathTest` |
| `ArchiveImageSpreadMath` | optional archive-comic screen math: 1.10 portrait gate, paired/single visible range, and previous/next targets |
| `ArchiveImageSpreadNavigator` | session history for reversing the actual mixed single/spread path; backward fallback queries only one adjacent pair instead of building a whole-sequence array |
| `LargeTextWorkQueue` | one TXT partition worker, foreground priority and FIFO within each priority; exposes foreground presence for cooperative prefetch yielding |
| `LargeTextScrollPrefetchState` | bounded per-window forward-speed sampling, two/four-viewport lookahead selection and 500 ms speculative-request cooldown; owns no text, layout, I/O or page model |
| `EpubSpreadSlotMath` | maps logical image-spread pages to physical left/right slots for LTR/RTL inward gutter alignment; standalone pages remain centered |
| `EpubFontPreferenceMath` | dedicated EPUB font default and one-time legacy preference migration rules |
| `LargeTextPartitionReader.ForwardCursor` | shared forward-read cursor for large-TXT start-line reads, with pre-read source stamps checked before publication; regression sources cover full-scan field equivalence, body tiling and mutation cleanup/recovery |
| `PdfGlyphBoxMath` | single source of the PDF glyph highlight box (search + TTS) |
| `PdfSpreadHighlightMath` | maps source-page rectangles/anchor points into post-cap composite geometry and inversely resolves viewport points to the nearest source-page rectangle |
| `TapZoneMath` | tap-zone action resolution for all tap paging |
| `UriPathCodec` | percent-decodes raw archive/document references while preserving literal `+`, and encodes synthetic image-page segments and EPUB base-URL directory paths; Android-decoded WebView paths are not decoded again |
| `EpubBindingRewriter` | converts validated OPF custom objects to `allow-scripts`-only local handler frames, sanitizes active content from a binding-only non-scripted parent, and resolves only the actual binding-payload XML URI attributes without exposing filesystem paths |
| `ReaderRestoreTargetMath` | pure target matching for TXT restore intents; prevents stale background restore from reopening a previous file after an in-place file switch |
| `DocumentAnnotationManager` | crash-safe app-private `annotations.json` CRUD, exact-highlight deduplication, file/folder move rebinding, and backup array import/export; it never writes the annotated TXT/Markdown source |
| `CoalescingSnapshotWriter` | one-task background snapshot persistence with revision-ordered synchronous checkpoints; request and file-I/O locks stay separate |
| `BackupImportData` | preflight for supplied backup sections/preferences; exact int/long narrowing (including nested pending edits/identity), owned numeric-string normalization and finite 0–1 theme opacity before live mutations; absent legacy fields retain defaults |
| `BackupTextContext` | owned prospective import preferences; exact small/large TXT display transforms and one-book reuse for edited bookmark coordinates, without early preference mutation |
| `TxtContentsBackup` | optional durable Contents section; hash-only private storage, shared record validation, merge/replacement planning, AtomicFile recovery inventory and per-record undo inside the shared edit lock; no caches or path rebinding |
| `IndexedBackupMerge` | stable first-ID replacement and reference-counted duplicate-location indexes; shared by bookmark, theme and annotation imports |
| `BackupImportTransaction` | checked commit steps and reverse rollback, including the failing step; not a multi-file crash journal |
| `BookmarkManager` / `PrefsManager` | owned import candidates, checked persistence, single preference commit, excluded security/cache keys and local-folder fallback |
| `AtomicUtf8File` | strict UTF-8 reads, AtomicFile I/O, backup-only recovery and reported sync/incomplete-commit errors; malformed saved text is not treated as absent |
| `TextDisplayRuleManager` | raw-value-keyed rules, bounded per-path exact hashes, owned rule copies, versioned compiled snapshots and invalid replacement handling; reused by large-TXT statistics/partitions/search |
| `TtsAnchorTextMath` | whitespace-insensitive anchor search + natural-start snapping for read-aloud |
| `FileClipboardBatch` | worker-only paste inventory, just-in-time collision resolution and immutable completion snapshots; no queue/UI mutation |
| `FileClipboardController` | pending-item identity, queue membership, single-item plans and linear batch success cleanup |
| `FileSystemOps` | staged copy/rollback with one operation-local buffer, no-follow deletion, progress-enabled direct moves; rename name/alias/commit/rollback helpers protect occupied links and retain blocked recovery entries |
| `FileTreeWalk` / `FileTreeProgressTracker` | iterative no-follow traversal with cancellation/identity checks and shared byte/file/folder inventory |
| `NaturalSort` | case-insensitive natural ordering across Unicode decimal scripts |
| `FileSortUtils` | per-entry archive sort keys and iterator-based main/archive list access; preserves natural chapter paths and stable ties |
| `TextMatchIndex` / `MatchOffsetIndex` | worker-confined exact TXT counts/navigation with adaptive sparse/ranked-bitmap offsets and complete overflow scans |
| `LatestValueDispatcher` | one pending latest-value UI delivery, stale-token checks and queued-work removal on close; used by file-operation progress dialogs |
| `FileUtils` | shared file/text helpers incl. `htmlToPlainText` (strips head/title - TTS buffer depends on this) |
| `ImageSequenceState` | image sequence list bookkeeping |
| `ImageExportName` / `FileThumbnailMath` | safe archive-page export filenames and natural first-image selection for lightweight browser thumbnails |
| `FontManager` | font scanning (locale-safe extension matching) |
| `ThemeManager` / `EdgeToEdgeUtil` / `ButtonOrderManager` | theming, insets, toolbar order |

The rest of `util/` is smaller single-purpose helpers; consult the directory
listing when hunting.

## ArchiveBrowserActivity (archive contents)

- **Archive folder view:** `ArchiveBrowserActivity` owns a single
  `ArchiveEntryListController.FolderView`, retaining sorted direct children for
  query/type-filter changes. Listing identity, prefix and sort-mode changes rebuild
  the view from source order. Results are fresh lists; no process-wide cache is added.
  The controller also uses path offsets for direct-child and image-metadata scans.

## Archive readers and shared I/O

### Shared ownership and output

- **Bulk replacement source boundary:** `ArchiveSourceSnapshot.requireOutsideReplacementTree`
  rejects a destination equal to or above any resolved source volume, and rejects
  unresolved capture. `ArchiveSupport.extractArchiveDetailed` uses it before staging
  overwrite output, then rechecks source identity and destination aliases before
  replacement. Failures use the existing owned-stage cleanup and preserve the old tree.

- **Native-codec adapter cleanup:** `PpmdZipInputStream` and `LzhamInputStream`
  distinguish logical closure from successful source cleanup; PPMd property reads
  validate scalar byte values. They and `LzFamilyInputStream` reject callback reentry
  and retain primary cleanup errors. CAB session closure stays terminal for reads,
  but retries failed decoder/input cleanup; `CabArchiveInputs` retains a failed
  physical handle until close succeeds. No codec grammar or native ABI is changed.

- **Volume discovery and probing:** `SevenZSplitVolumeResolver` validates generic
  numeric selected-file identity; `RarVolumeChainResolution` requires membership
  for first/base selections too. `NumericSplitArchiveMerger` reuses
  `SplitSeekableByteChannel.requireVolumeLengths` before copying payloads.
  ALZ/EGG membership walks honor cancellation; EGG's ordinal map has no ordering
  role. `RarArchiveLocator` tries an eight-byte prefix before its unchanged bounded
  SFX scan. `ZipEntryNameDecoder` hashes raw names only for usable Unicode Path
  extras. `ArchiveFailureClassifier` checks shared cancellation traversal first.

- **Archive extraction progress:** `ArchiveExtractionProgressTracker` counts
  complete folder ancestry, including empty directories, using identity sets.
  Setup is cancellable; known parents stop repeat walks. Deepest-folder updates
  are coalesced for siblings, root files clear stale labels, and outer queue
  folder progress is preserved.

- **Decoder failure state:** `RarBitInput`, `SevenZSwapInputStream` and
  `ZipReduceInputStream` retain checked/unchecked failures without resuming partial
  state. Swap/Reduce distinguish logical closure from successful source closure.
  TAR indexing and its `ArchiveSupport` fallback gates share typed cancellation
  handling; existing bit alignment, word tails and output verification remain.

- **Archive output lifecycle:** `ArchiveExtractionByteBudget` owns terminal stream
  failures, free-space accounting and borrowed nested scopes; fresh fallback outputs
  replace the prior path contribution. `ArchiveCancellation` inspects causes and
  suppressed cleanup failures with identity-based cycle detection. Rollback remains
  in `RarOutputFileGuard`, including independent cleanup retries after restoration.

- **Archive metadata helpers:** `ArchiveSupport` owns synthetic-parent listing,
  with first-all and first-file identity wrappers preserving route-specific
  directory metadata policies. ALZ, RAR, lightweight ZIP and native rows share
  that implementation. `RarLibarchiveFallback` uses the extraction path sanitizer
  and incremental row framing. `SplitVolumeInput.readLittleEndian` is the exact
  synchronized scalar reader for ALZ/EGG/supplemental-7z; its existing active-volume
  range avoids redundant searches. No codec state machine or new cache is added.

- **Archive metadata/viewer boundaries:** ALZ parses valid DOS listing times and
  separates parse errors from encryption evidence. RAR indexing preserves unknown
  encrypted stored sizes; `RarStoredPayloadIO.UnknownSizeOutput` resolves only unique
  final-checksum matches within one AES block. Ambiguous/no-checksum nonempty data
  remains unsupported. `RarBlake2sp.snapshotDigest` checks tails without finalizing
  the live prefix. Fourteen new methods/two revised expectations are uncompiled/unrun.
  The supplemental 7z forward reader records `spoolStart`, discards only already-skipped
  prefix storage, and retains all folder integrity checks before publication.

- **Archive cleanup/recovery:** `ArchiveSupport` reuses the now-public `FileTreeWalk`
  no-follow visitor API for iterative cleanup and backup copying. Recovery creates only
  new destinations, rejects links/special files and rechecks source identity/size.
  Its copies use `openExtractionOutputStream`; failed recovery retains the old backup.
  Cleanup still runs after cancellation. Containment failure no longer falls back to
  unresolved path text. These are path/identity checks, not atomic filesystem locks.

- **Archive size policy:** `FileThumbnailMath` admits positive/unknown cover sizes;
  `FileThumbnailLoader` uses sampled decoding without the old 128 MiB cover quota.
  AZO and supplemental 7z metadata check array representability, not 512 MiB quotas;
  PPMd validates model alignment/sentinel indexing, not a 256 MiB RAM quota.
  ZIPX LZMA shares useful-history dictionary sizing without a 64 MiB memory quota.
  The changed live allocation boundaries report allocation failure as I/O failure.
  `ArchiveExtractionByteBudget` and native output retain free-space protection.

- **Decode boundaries and failed state:** AzoDecoder preflights framed sizes and
  owns one exact output array; EggArchiveReader preserves its cancellation signal.
  ZipxAesArchiveReader's bounded method-95 stream consumes concatenated XZ streams
  under the existing output integrity guard. Rar3PpmdFilterOutput and the solid
  decoder retire unchecked failures; SevenZBrotliInputStream owns decoder cleanup
  and retryable physical-source close. Eighteen new host methods are unrun; see
  [scope](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

- **Shared path and search fast paths:** Java RAR/EGG/ALZ/lightweight ZIP and
  extraction progress reuse `ArchiveSupport.sanitizeEntryPathForList`; prefix
  skipping and separator collapse are linear and drive checks do not compile regexes.
  NULs and drive-prefixed newline names are rejected. `MatchOffsetIndex.rankIfKnown`
  exposes only provable ranks from an incomplete retained prefix; `TextMatchIndex`
  uses them for cached navigation and keeps whole-input fallback for unknown answers.

- **Native extraction boundary:** `LibarchiveNativeBridge` shares
  `ArchiveSupport.sanitizeEntryPathForList`, skips non-readable special entries,
  rejects unsafe bulk paths, and checks decoded length against a declared size.
  Existing `RarOutputFileGuard` protects each file and validates directories; this
  is not an additional whole-archive preflight. Its SFX `VolumeInput` owns one
  checked physical handle, rejects metadata/truncation/seek errors and stays retired
  after failure/close. `ArchiveCancellation` recognizes wrapped causes, including
  cycles safely; ZIP/TAR/7z retry gates and RAR forward admission keep cancellation
  terminal. New host/device regression sources are unrun, not native coverage evidence.

- **Forward lifecycle and buffered AES:** `LibarchiveNativeBridge.ForwardStream`
  retains I/O/cancellation failures, checks interruption while draining, treats an
  empty read as zero, and retries failed supplemental close. PPMd spool cleanup
  preserves unchecked primary failures; RAR5 decoder and forward-reader owners
  retain failed history cleanup for a later close. `ArchiveSupport` reuses the
  existing strict directory helper for Java ZIP/TAR/7z bulk output.
  `ZipxAesArchiveReader.WinZipAesInputStream` uses aligned 64 KiB decrypt batches,
  retaining authentication/size checks and wiping its plaintext buffer on close.
  Regression sources exist; this is not native validation or a performance result.

- **Shared bulk overwrite:** `ArchiveSupport.extractArchiveDetailed` tracks work-directory creation before checked/unchecked failure cleanup and preserves the original unchecked failure. Cancellation is checked before output creation and success/overwrite handoff, including after final source/alias validation. Folder replacement delegates rollback to `restoreDirectoryBackup`, preserving the original backup and reporting its path if restoration fails. This does not make concurrent filesystem changes atomic.

- **Result/queue/forward stabilization:** `ArchiveCancellation.restoreIfCancelled` preserves cancellation at failure-result boundaries. `FileOperationProgress` wakes paused waiters on completion/interruption. Extraction callbacks recheck queue membership and running state; rejected submissions clear passwords. RAR/7z/CAB retain failed spool-input closes. `LibarchiveNativeBridge.ForwardStream` checks post-call cancellation/counts and retires unchecked failures; `LibarchiveForwardReader` preserves stream/owner close errors and retries failed components. Native code and codec admission are unchanged.

- **Viewer/output lifecycle:** `RarOutputFileGuard` uses `FileTreeWalk.kind` to reject linked output/backup targets. `SequentialArchiveImageReader.readEntryChunk` validates progress and cancellation, while `ArchiveImageEntryCache` checks cache publication and shares `ArchiveCancellation` cause traversal. Failed sequential/TAR cleanup stays owned for retry; password clearing runs independently. Cache inventory/sizing/deletion reuse `FileTreeWalk`/`FileSystemOps`; failed pruning does not count as reclaimed space. `MainArchiveExtractController.extractForUi` contains nonfatal worker errors after core rollback, and completed pending items are removed on the UI thread. These are source changes, not runtime validation.

- **Bulk destination safety:** `ArchiveOutputPreflight` checks canonical destination
  kinds, implicit parents and existing file/directory conflicts before Java RAR and
  supplemental 7z/ZIP bulk writes. It does not serialize concurrent filesystem changes
  or preflight native bulk output. The 7z path uses the common listing name resolver.

- **Cancellation and decoder ownership:** `ArchiveCancellation` makes cancellation
  terminal at RAR fallback boundaries and 7z forward probes. Supplemental ZIP Deflate
  uses `OwnedDeflateInputStream` for deterministic inflater release and buffered input.

- **Archive output types:** classic `Rar3FirstPartyArchiveExtractor` and special-7z bulk extraction require directory entries to resolve to actual directories. `RarOutputFileGuard.ensureDirectory` also supplies this check to stored, special-case, PPMd and RAR5 bulk routes, accepting a concurrent directory-creation race but not a regular file. Special 7z `writeEmptyEntry` guards no-stream output through close; zero-byte stream-backed folders still pass `FolderStream.drain()` before committing.

- **Volume input and discovery:** `SplitVolumeInput` owns checked physical/logical ranges, binary-search lookup, bounded-view cursors and failure retirement. `EggArchiveReader.VolumeFiles` resolves numeric siblings and `checkedPayloadEnd` validates metadata ranges. `TarEntryIndex` provides direct ordinary plain-TAR offsets; sparse/split/compressed cases retain their established paths. Java TAR paths share terminal member-header checksum rejection and skip links/special members; streaming and indexed writes use destination guards.

### RAR and CBR

- **RAR packed input:** `Rar5CompressedDecoder` validates scalar/bulk source
  results and checks interruption around reads and final output. Its existing
  failure close/reset path prevents continued use of partially advanced solid
  history. This adds no format route, buffering layer or alternate decoder.

- **RAR5-container compressed completion:** `Rar5CompressedArchiveExtractor`
  owns the shared `verifyPackedEnd` check after decoder completion and before
  final data-check acceptance. Known/unknown sizes require EOF plus at most AES
  alignment padding. Selected/bulk guards and `Rar5ForwardReader` retain output
  ownership and retire failed history; no decoder/model state moves into the helper.

- **RAR3/4 VM chains:** `Rar3PpmdFilterOutput` groups same-start stages and verifies
  each input length against the prior result. `tailSourceEnd` retains the original
  raw extent for following overlap checks, regardless of intermediate resizing.
  `written` counts raw bytes and `published` counts final file bytes. `rawBytesUntilBoundary`
  lets the unpacker clip only legacy final-match surplus without clipping raw filter
  input at final file size. `finishStreamingEntry` handles trailing VM/table controls
  and resumes `run` without re-reading an active PPMd header. Zero-final-size streams
  can therefore consume raw filter input. PPMd-only decoding continues to its marker.
  Length changes and trailing controls require explicit EOF; ordinary excess data
  is not silently consumed. Filter offsets use `published`; CRC/progress observe
  final output. `DeferredFilters` belongs to `Rar3SolidState` or the PPMd-only
  decoder; independent contexts pass no state. `finish(explicitFileBoundary)`
  rebases wholly future descriptors by raw bytes only after complete output and
  flush. A lease prevents simultaneous sinks; close preserves successful carry,
  while abort/reset/failure clears it. Partially consumed/late ranges still fail.
  Sixteen new carry methods are uncompiled/unrun. Nine earlier accounting and
  six control-tail methods, three revised expectations and two expanded cases are
  uncompiled/unrun, in addition to the earlier ten chain methods.

- **RAR5/RAR7 planned history:** `Rar5CompressedArchiveExtractor.planDictionaryStarts`
  walks metadata backward once and maps each eligible compressed reset to its run's
  maximum dictionary. Bulk and `Rar5ForwardReader` use these hints; selected extraction
  uses only its validated `RarSolidReadPlan` steps through the target. A reset-only
  `decodeEntry` overload reserves storage separately from active `windowSize` match
  bounds. The forward factory detects growing runs even with native available.
  History remains demand-paged with existing authentication/free-space checks; direct
  unplanned late growth still fails. Ten added decoder/integration methods are unrun.

- **Native RAR redirection bounds:** the vendored reader checks extra-record size/type
  framing, then gives `parse_file_extra_redir` only that record's remaining bytes.
  Fields retain 64-bit values until bounds checks, and every vint consumes its actual
  width. The retained `libarchive.patch` records this local delta.

- **RAR redirections:** `RarArchiveReader.parseFileExtra` validates record `0x05`
  without allocating/resolving its target. `parseRar5FileBlock` excludes supported
  metadata types from regular entries, rejects attached payloads, and records their
  presence in `EntryScan.hasRedirections`, including excluded pathnames. The forward
  factory reuses that complete scan to select first-party decoding for eligible
  remaining files. Native RAR5 classifies junctions as links and rejects unresolved
  file-copy/unknown types; the bridge skips links. No reference restoration is added.

- **RAR split-head eligibility:** `Rar5CompressedArchiveExtractor` defers the
  zero-packed decision for a split head to `validateCompressedSplitChain`, which
  uses summed packed bytes and the final unpacked size. `isEmptyCompressedPayload`
  shares the known-empty/solid rule with standalone eligibility. Existing packed
  streams still open and verify zero-length segments; the forward factory selects
  this path for zero-byte split heads even when native is available.

- **RAR empty resets:** `RarArchiveReader` reads the main-header solid flag into
  immutable `RarEntry.archiveSolid`, including decrypted headers. Eligibility and
  split consistency stay in `Rar5CompressedArchiveExtractor`; the selected fallback
  and forward factory choose ordered first-party decoding for these empty entries.
  The public selected API retains its existing initial native attempt.
  The decoder's explicit empty mode uses normal reset or continuation semantics,
  with shared payload/checksum validation before output publication.

- **RAR dictionary minima:** `Rar5CompressedDecoder` retains a larger solid dictionary
  and delegates growth to `Rar5HistoryStore.ensureCapacity`. Growth before overwrite
  preserves absolute page slots, encrypted spills and all decoder carryover state;
  no header-sized allocation or history copy occurs. Unplanned growth after history
  loss remains rejected; archive paths now reserve the run maximum before decoding.
  Smaller minima never shrink/reinterpret history.

- **RAR3/4 PPMd forward session:** `Rar3PpmdSolidArchiveExtractor.openForwardReader`
  keeps PPMd eligibility, encrypted-header routing and mixed-LZ fallback selection.
  `Rar3PpmdForwardReader` owns the cursor, password copy, shared solid model and
  verified spool. Package-local checked-file and checked-discard helpers reuse the
  extractor's private payload/CRC logic for retained output and skipped primers.
  No common session superclass or alternate codec path is introduced.

- **RAR5 forward session:** `Rar5CompressedArchiveExtractor.openForwardReader` keeps
  format/backend eligibility and builds the volume index. `Rar5ForwardReader` owns
  the cursor, password copy, persistent decoder and verified-entry spool. It calls
  package-local extractor helpers for split validation and checked decoding. Selected
  and bulk extraction still own their separate decoder lifetimes. This separation
  does not change routing, integrity checks, drain limits or failure cleanup.

- **RAR progress/failures:** `RarArchiveReader.sumUnpackedBytes` counts final parts,
  returning unknown if any logical size is unknown or the sum overflows. Stored and
  first-party compressed bulk paths use it to reset byte progress. It does not enforce
  extraction limits. `RarPackedInputStream` latches I/O/runtime/error failures, suppresses
  cleanup errors under the primary failure and retains a failed-close volume handle
  for retry; argument validation stays outside the failure latch.

- **RAR unknown sizes:** `RarArchiveReader` maps flagged sizes to `-1`.
  `Rar5CompressedDecoder` shares a last-block-driven loop
  for known/unknown compressed sizes, retaining known-size bounds, numeric headroom,
  completed filters and actual solid history. `Rar5CompressedArchiveExtractor` verifies
  packed/final checks and unknown-size tails (AES alignment only). Forward draining
  uses the known final size or an actual-output counting discard sink; overruns retire
  history. `RarStoredPayloadIO.resolvedUnpackedSize` shares stored-size validation
  between single-entry extraction and `RarSplitStoredPlan`: unknown plain RAR5 sizes
  resolve to the overflow-checked complete packed sum. Eligible unknown AES stored
  sizes stay `-1` until a unique final CRC/hash candidate resolves the last block;
  single/split paths reuse checked decryption and final guarded verification.
  Forward stored drains preflight the known size or packed-minus-15 lower bound,
  then enforce the actual spool size before accepting an unknown-size drain. Header
  metadata retains `-1`; unknown headers prefer the first-party forward reader.

- **RAR5/RAR7 empty continuations:** `Rar5CompressedArchiveExtractor.PackedPayload`
  carries the assembled size. `decodeOne` explicitly enables the decoder's empty-solid
  mode only for a zero-packed solid member. The decoder requires zero output, physical
  EOF and a primed compatible history, then completes the normal entry transition
  without a block header. `RarSolidReadPlan` and forward/bulk ordering are unchanged;
  `RarPackedInputStream`, AES and `DataCheck` still validate source/password/integrity.
  Nine additional regression methods are uncompiled/unrun.

- **RAR5/RAR7 block boundaries:** `Rar5CompressedDecoder.openBlock` records the exact
  valid bit count and admits empty table-less frames. `skipBits` bounds every code/raw
  operand consumption; padded lookahead cannot extend the logical block. Canonical
  table construction rejects oversubscription and `decodeNumber` rejects missing
  symbols. Tail decoding reuses `decodeOneSymbol`, so no-op repeats work while existing
  `emitByte`/`copyString` output bounds remain authoritative. Block/tail cancellation
  uses existing decoder failure cleanup. Thirteen additional regression methods are unrun.

- **RAR5/RAR7 filter chains:** `Rar5CompressedDecoder` admits exact same-range
  chains and nonempty short blocks, commits ordering after full record parsing,
  and limits simultaneously queued descriptors to 8,192. `emitOutputByte` applies
  the chain iteratively before one final write, keeping `Rar5HistoryStore` raw.
  E8/E8E9/ARM reuse owned arrays; DELTA returns separate output. Transformation
  cancellation retires the decoder through existing failure cleanup. Thirteen added
  host methods are uncompiled/unrun; [scope](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

- **RAR3 records and pending programs:** `Rar3VmFilter.ProgramState` validates
  complete records before committing slot metadata and wraps the 32-bit offset
  bias before long entry-position addition. RGB handles partial/minimum-stride
  blocks; E8/E8E9/Itanium reuse exact arrays. `Rar3PpmdFilterOutput` and the legacy
  classic-LZ collector preserve queued descriptors across slot resets.
  `Rar3VmFilter.ProgramRetention` bounds distinct queued custom instructions by
  identity/refcount, independently of active slots. Same-length/within-entry and
  queue-order checks remain. Seventeen new host methods are unrun; see
  [scope](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

- **Verified RAR output:** RAR5, PPMd and checked RAR4 forward readers record spool length and bound reads, rejecting size changes/early EOF. RAR5/PPMd failure retirement retains failed-deletion paths for repeated close. `Rar5CompressedArchiveExtractor.ChecksumException` is classified by type before filename-bearing message heuristics.

- **RAR entry planning:** `Rar3DecodePlan` snapshots entries/source references, classifies once, probes independent starts and promotes actual solid primers to checked mode. `Rar3FirstPartyArchiveExtractor.PlannedDecoder` executes the same plan for bulk, selected-entry and forward paths, retiring on failure. Standalone classic-LZ uses size/CRC termination; checked starts/continuations require boundaries. Stored files clear history, directories preserve it, and stored solid members remain excluded. `Rar3PpmdBlockProbe` validates the full declared packed range before reading mode bytes.

- **Selected PPMd/RAR5 history:** `RarSolidReadPlan` uses one iterator pass to select the latest compressed reset through the exact requested head, then `RarVolumeChain.indexSelected` resolves only required chains in another pass. The PPMd/RAR5 extractors retain mode/codec admission and decode each step with its prepared parts, eliminating per-primer full-list scans. Unrelated malformed chains are ignored; required incomplete/ambiguous parts fail before primer decoding. Stored files retain the previous skip policy, not new stored-as-history support. Plan membership/order is fixed, not source bytes or mutable payload metadata.

- **Mixed RAR3/RAR4:** `Rar3FirstPartyArchiveExtractor` gates plain single-volume compressed solid runs using CRCs and file boundaries. `Rar3Unpacker` connects `Rar3ClassicLzEngine.decodeMixed`, `Rar3MixedPpmdState`, shared `RarBitInput`/`RarLzWindow`, and `Rar3PpmdFilterOutput` for streamed output. `Rar3UnpackContext` owns match/table/model continuation and failure invalidation.

- **Logical mixed-solid input:** `Rar3LogicalSolidArchiveExtractor`
  bridges bounded plain/AES/split payloads to that existing checked decoder for selected
  targets, bulk extraction and checksum-checked archive forward reading. Primers, final CRCs,
  explicit solid boundaries and packed tails remain required. Bulk/forward plans also
  admit independent compressed archives and encrypted/split stored companions, even
  without solid entries. Plain independent LZ forward input is admitted when native
  is unavailable; plain solid runs can retain history across stored members.
  `RarHeaderEncryptedArchiveSupport` owns visible-header
  temporary volumes for checksum-checked forward readers. Stored-only forward archives
  reuse the same executor, preserving native preference for plain inputs. Runtime
  interoperability is unverified.

- **Independent RAR bulk routes:** `Rar3Or4SpecialCaseExtractor.ArchivePlan` snapshots source/encryption metadata, preflights independent plain PPMd/LZ, stored and AES/split routes, and validates topology through `RarVolumeChain.Index`. `PreparedEntry` reuses the plain one-entry `Rar3DecodePlan` or the selected volume chain. Bulk plain entries no longer repeat mode probes/full-archive target scans; solid planning remains separate. Orphan/reused continuations are errors, not skipped successes.

- **RAR chain index:** `RarVolumeChain.index` prepares exact-path split membership in one iterator pass for stored, independent, PPMd and RAR5 bulk fallbacks plus PPMd/RAR5 forward adapters. Heads are identity-keyed and chain lists are unmodifiable. A new same-path head cannot cross an incomplete chain; completed duplicate names remain separate. Selected-entry `build` keeps unrelated malformed paths out of its scope. Forward adapters copy entry order; existing payload/crypto/CRC validators still own byte integrity and solid decoders retain their ordered history.

- **Checked RAR forward fallback:** `Rar3CheckedForwardReader` owns a `Rar3DecodePlan` and `PlannedDecoder` when native RAR is unavailable or stream opening fails, after existing RAR5/PPMd adapters. It verifies requested pages into owned spools and uses `Rar3Unpacker.unpackToDiscard` for skipped compressed entries, retaining history only for checked runs. Independent stored files reset history; directories do not. It does not replace working native streams or add mid-stream failover.

- **Logical RAR3/RAR4 input:** `Rar3PackedPayload` supplies bounded plain/AES/split input and drains remaining packed segments before acceptance. `Rar3IndependentPayloadExtractor` probes the same stream and supplies a single-use `Rar3UnpackContext` for independent AES/split LZ/PPMd entries, retaining decoded CRC/size checks and destination rollback. This route does not retain solid history or add a forward-reader adapter; runtime validation is pending.

- **Separate PPMd route:** `Rar3PpmdSolidArchiveExtractor` shares `Rar3PackedPayload` for scoped single/bulk/forward extraction. Its `ForwardReader` retains the decoder and verified entry spools. This route does not dispatch mixed LZ; the plain mixed-solid fallback above is separate.

- **RAR5/RAR7:** `Rar5CompressedDecoder` uses `Rar5HistoryStore` for bounded RAM and encrypted disk-backed history. `Rar5ForwardReader` owns the viewer session's decoder/spool cleanup and retained solid state; `Rar5CompressedArchiveExtractor` owns selected/bulk decoding and shared payload checks. `RarBlake2sp`, `RarStoredPayloadIO.DataCheck`, `Rar5Crypto` and `RarPackedInputStream` separate final output integrity from packed-part checks.

- **RAR scan completeness:** `RarArchiveReader.EntryScan` separates a complete archive scan from selected-prefix recovery. Listing/bulk/forward callers require completion; selected readers retain checked split/solid dependency validation. `RarLibarchiveFallback` keeps first-file duplicate metadata to match native selected extraction.

### ZIP and ZIPX

- **PPMd-I explicit profiles:** `PpmdZipInputStream.openFreeze` requires a revision;
  JNI passes it to the small `Ppmd8` Refresh override. The core/JNI share the PUBLIC
  freeze compile definition. `DataException` marks decoder rejection, with a keep
  rule. `ZipPpmdEntryExtractor` tracks source failures separately, checks revisions
  sequentially and compares two valid outputs exactly using one guarded file.
  `ZipxAesArchiveReader` supplies owned plain/ZipCrypto/AES payloads and authentication;
  source/volume checks precede commit. `ArchiveSupport` makes covered failures terminal
  in plain Commons fallback. Raw `open` still rejects implicit freeze selection.

- **Split ZIP boundaries:** `ZipVolumeChannels` verifies each expected disk length
  against `SplitSeekableByteChannel`'s captured lengths before constructing windows.
  The channel first checks its open volume's range and keeps one physical handle.
  CAB indexing uses the progress token; its already-unique listing goes directly
  through synthetic-parent expansion, and single-byte writes do not allocate arrays.

- **ZIP PPMd-I:** `ZipxAesArchiveReader` selects `PpmdZipInputStream` when available;
  the independent `readwide-ppmd` JNI target contains pinned public-domain Ppmd8.
  The native stream checks end/range/count/packed exhaustion; the app retains
  CRC/MAC/source identity and guarded output. The old container fallback remains
  eligible when unavailable. Freeze stays excluded. [Validation remains pending](ZIP_PPMDI_INTEGRATION_1_0_20.md).

- **ZIP output preflight:** `LightweightZipArchiveReader` combines shared
  `ArchiveOutputPreflight` type checks with its stricter duplicate-target rejection,
  before sequential or parallel writes; directory creation uses `ensureDirectory`.

- **New codec/ZIP page paths:** `ZipReduceInputStream` supplies ZIP Reduce 2–5
  behind `ZipxAesArchiveReader`'s existing plain/ZipCrypto/AES integrity layer.
  `SevenZBrotliInputStream` handles standard/large-window raw and Brotli-MT framing
  with the pinned MIT Google Brotli v1.2.0 Java source module, registered as 04F71102
  in `SevenZCoderRegistry` (25 entries).
  `AlzipZipCrypto.checked` owns legacy/strict UTF-8/MS949 candidate preparation;
  ALZ/EGG enable extra candidates only with payload CRC checks.
  ALZ supplies a decoded-length/CRC verifier for multiple distinct header matches. It reads
  independent bounded views before output, without extracted-byte progress; copies
  preserve the selected key's payload position. A single match avoids probing and
  tracked source/close/cancellation/unchecked failures abort selection. `decodePayload`
  serves both probes and extraction; only decoder rejection may advance candidates.
  `AlzipZipCrypto.ProbeInput` records source failures and `PasswordProbeDecoder` closes
  outside the rejection catch. EGG's `openEntryDecryption` supplies `passwordMatches`
  across all owned nonzero-CRC blocks. `verifyPasswordBlock` shares `openDecodedBlock`,
  `copyDecodedBlock` and `drainPackedBlock` with extraction, keeping one cipher state
  through unused packed tails. `EntryDecryption.close` clears selected keys after
  direct extraction or solid spooling. No-CRC and single-match policies remain.
  `ArchiveImageEntryCache` tries `ArchiveSupport.tryExtractIndependentZipImageEntry`
  before its archive-wide lock for non-sensitive, password-free requests. Eligible
  plain single-file ZIP/CBZ Store/Deflate entries reuse leased immutable metadata
  but own separate input handles. Per-output locks and checked publication remain;
  all other formats/methods use the original path. No new worker pool is introduced.

- **ZIP/ZIPX, ALZ and EGG:** `ArchiveSupport` reuses routing metadata; `ZipxAesArchiveReader` owns leased indexes, interruptible per-key construction gates and reusable bounded local-header readers. Global LRU locking excludes parsing and close I/O. Recorded-volume checks surround payloads; full membership discovery remains at acquisition/commit. Bulk ZIP paths preflight explicit and implied file/directory collisions. Nested ALZ and non-solid EGG indexes retain metadata, not decrypted payloads or decoder sessions. `ArchiveSupport.releaseViewerArchiveIndex` retires them. EGG's separate `SolidForwardReader` owns verified block spools.

### 7z and codec adapters

- **7z LZHAM:** registry method `04F71001` prepares five-byte `0x10`/`0x11`
  default profiles and selects the dedicated forward route. `LzhamInputStream`
  owns bounded I/O, native state, exact completion and terminal source failures;
  `readwide-lzham` supplies MIT decoder/Adler32 with no fake EOF padding.
  Container CRC/output/free-space protections remain. No native execution yet;
  see [profile and validation](LZHAM_INTEGRATION_1_0_20.md).

- **Brotli source module:** `:brotliJava` compiles 12 unchanged runtime Java files
  from Google Brotli v1.2.0. `SevenZBrotliInputStream.startDecoder` owns setup for
  raw and MT frame payloads and enables large-window before decoding. The app's
  `CheckedSource` validates physical reads before pushback and retains failures
  through upstream error-code conversion. Adapter guards block read/close reentry,
  preserve post-read cancellation and retain failed source-close ownership.
  Seven later boundary methods are uncompiled/unrun. The app's
  release configuration includes upstream `proguard.pgcfg` for DictionaryData
  reflection. No external custom dictionary is attached. App inventory was 510 at that integration;
  third-party module files are counted separately. Eight new regressions are unrun.

- **7z differential contents:** `SevenZBcj2ArchiveReader.readFilesInfo` decodes Anti
  using empty-stream indexes and omits marked entries before output preflight/name
  matching. `hasAntiItems` selects supplemental extraction and dedicated forward
  routing, including `SevenZNativeForwardAdapter`; solid stream offsets are unaffected.
  The four Commons listing/selected/bulk/forward paths in `ArchiveSupport` also skip
  `isAntiItem()`. Anti-only bulk extraction succeeds without marker writes/deletions.
  `SevenZAntiItemTest` adds fifteen unrun cases; malformed bitmap guards are retained.

- **7z fallback boundary:** `ArchiveSupport` supplemental bulk/selected/listing helpers
  return null only after a negative capability probe, not a selected reader's failure.
  Unchecked failures become I/O errors with their cause. `SevenZBcj2ArchiveReader`
  shares `parseForProbe` and a `Source`-owning forward factory for single/split input;
  password/integrity failures propagate and source checks precede an unhandled-header
  fallback. Transfer the same checked input to an eligible forward reader, otherwise
  close it. `SevenZFallbackBoundaryTest` covers dispatch and restoration (unrun).

- **PPMd completion:** `SevenZPpmd7Decoder.DecodedStream.finish` validates the range
  code before releasing the model/last bytes. Empty streams share initializer parsing
  without constructing the model; failures retire input and remain sticky.

- **Supplemental 7z LZMA lifecycle:** `SevenZCoderRegistry.Prepared.open` accepts an
  explicit XZ cache without retaining it in the metadata-only decode plan. LZMA
  permits a validated optional end marker at known size. `SevenZDecoderArrayCache`
  retains at most two cleared byte arrays / 8 MiB per forward reader or bulk operation,
  not a payload cap. `SevenZBcj2ArchiveReader.openFolder` wraps it in XZ's
  `ResettableArrayCache` for constructor-failure/close cleanup; reader EOF/failure/close
  and bulk completion discard idle arrays. Selected/header-only decoding remains uncached.

- **Modern/legacy LZ4:** `Lz4ArchiveInputStream` owns frame sequencing, descriptors,
  block preflight, optional sizes/checksums and circular linked history for
  `ArchiveSupport`'s standalone/TAR wrappers and the 7z registry. It reuses format-bounded
  packed storage and delegates raw decoding to Commons. Empty blocks preserve frame/history
  state; Dict-ID frames are allowed only while matches use actual eligible history.
  Missing preset-dictionary references fail before block output; no provider is added.
  BZip2/LZ4 share terminal 7z payload error
  wrapping; cancellation is preserved. `OwnedDeflateInputStream` retains unchecked read
  failures. The earlier 27 and 18 additional modern-frame regression methods are unrun;
  [scope](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

- **Embedded BZip2 and 7z forward routing:** ZIP/ZIPX, EGG, standard ALZ and
  SevenZCoderRegistry use concatenating BZip2 readers over bounded payloads.
  The ALZ DLZ path is separate. ZIP descriptor/plain writers share supplemental
  stream decoding. Registry dedicated-forward flags replace the reader's separate
  list for Swap2/Swap4, LZ4, Brotli, LZ5/Lizard, LZHAM and BZip2; metadata flags propagate
  the choice to SevenZNativeForwardAdapter. LZ5/Lizard still use native codecs.
  ArchiveSupport tries supplemental extraction first; BZip2 payload failures are
  terminal so a less strict fallback cannot accept a valid prefix. Nineteen new
  host methods are unrun; see [scope](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md).

- **LZ block integrity:** the native LZ5/Lizard frame contexts retain checksum and
  content-size presence flags. Packed block checksums are verified before decode
  or copy; checked stored blocks reuse frame buffering, unchecked ones stream.
  Empty stored blocks preserve history, and size checks cannot underflow.
  LzFrameIntegrityTest covers synthetic frame boundaries; execution is pending.

- **LZ frame and RAR stream corrections:** the native LZ5 frame reader retains
  the full 4 MiB codec window; its raw decoder checks offsets and literal boundaries.
  LzFamilyInputStream streams non-MT skippable metadata, retires unchecked failures
  and retries failed source close. Rar3Unpacker's clipping adapter forwards bulk
  matches to Rar3PpmdFilterOutput, which chunks plain spans and buffers filter blocks.
  New regressions are unrun; see [stream corrections](ARCHIVE_STREAM_REPAIR_1_0_20.md).

- **LEA, custom RAR VM and native LZ frames:** `EggAuthenticatedCrypto` shares
  password derivation/authentication across AES and `EggLeaCipher`; EGG methods
  5/6 select LEA-128/256 for non-solid entries. `Rar3VmFilter.ProgramState` retains
  standard-filter fast paths and compiles custom bytecode with `Rar3VirtualMachine`.
  `Rar3PpmdFilterOutput`, shared by LZ/PPMd paths, accepts intermediate resizing only
  when the final chain preserves the original within-entry length.
  The `zipx-codecs-android` module's `LzFamilyInputStream`
  and JNI bridge own framed LZ5/Lizard decoding, registered as 04F71105/04F71106.
  Java/native transfer buffers are bounded; the codecs own additional frame/history
  memory. New host/device regression sources are uncompiled/unrun. Scope and source
  attribution: [codec additions](ARCHIVE_CODEC_ADDITIONS_1_0_20.md).

- **Special 7z:** `SevenZCoderRegistry` owns registered codec/filter descriptors, input arities,
  property preparation and special-route flags. `SevenZAdditionalCoders` delegates
  to it; Brotli requires the size-aware overload. `SevenZDecodePlan` topologically
  orders supported graphs before payload I/O. `SevenZBcj2ArchiveReader` caches
  folder plans/packed offsets and composes bounded streams, including PPMd/BCJ2,
  Swap2/Swap4, ARM64/RISC-V, Brotli and LZ4. `LibarchiveZstdInputStream` connects
  04F71101 to one native Zstd filter, accepting current zero flags or legacy 3/5-byte
  properties. Brotli 04F71102 requires three bytes and uses a Java raw/MT adapter.
  LZMA/LZMA2 memory is bounded by known coder output. Special encoded headers retain
  their route identity; forward reads check verified spool length and retain failed
  cleanup paths. Source snapshots surround parsing/decoding; standard split sets
  use `SevenZSplitVolumeResolver` and independent bounded views of `SplitVolumeInput`.
  New Brotli regressions are unrun; native/device validation remains pending.

### ALZ and EGG

- **Encrypted-solid EGG:** `EggBlock.owner` links encryption to the physical block
  owner. `EntryDecryption` centralizes cipher selection; `SolidBlockSession` stages
  one owner's decrypted packed bytes and verifies authentication before bulk,
  selected or forward readers decode slices. Forward passwords are cloned/cleared.
  Plain solid blocks retain direct access. New regressions are uncompiled/unrun.

- **ALZ rollover catalogue:** `AlzVolumeResolver.partOrdinal` maps `.a00`–`.j99`
  and wide `.aNN` aliases onto one ordinal space. The shared `CONTINUATION` pattern
  feeds `ArchiveTypeDetector` and `ArchiveSourceSnapshot`; first-part normalization,
  extraction and index invalidation all use the same validated chain. Duplicate
  ordinals/gaps fail; old-style RAR suffixes stay separate. Twelve synthetic
  `AlzVolumeRolloverTest` methods are uncompiled/unrun.

- **ALZ metadata completion:** `AlzVolumeResolver.VolumeSet.retainedFinalTrailer`
  identifies the terminal framing retained in split logical streams. `AlzipArchiveReader`
  keeps entry payloads before that trailer, validates central endings, and skips
  tail-sized comments with bounded record reads. Empty archives need a terminator;
  nonempty raw exact-EOF compatibility remains. Invalid indexes are not cached.
  Fourteen new `AlzipArchiveReaderTest` methods are uncompiled/unrun.

- **Solid EGG preflight:** `EggArchiveReader.preflightSolidLayout` combines encryption/
  size eligibility, checked aggregate equality and physical block collection for bulk,
  selected and forward routes before output mutation. `SolidForwardReader` receives
  that list instead of independently validating totals. Directory-only bulk extraction
  still decodes/checks attached empty blocks through `SolidEntryWriter`.

- **Container compatibility:** `EggArchiveReader.readEntryBlocks` is shared by
  file-local blocks and blocks resumed after archive comments/padding. Name decoding
  combines locale metadata with Windows/POSIX directory classification.
  `resolveParentPaths` resolves directory-ID names iteratively after decoding, keeping
  physical/solid order and rejecting unsafe or invalid parent chains before indexing.
  Encrypted names remain unsupported. EGG and `AlzipArchiveReader` reuse
  `ArchiveOutputPreflight` for bulk destinations and preserve first-selected listing
  identities. `Rar3DecodePlan`/`PlannedDecoder` retain solid history across stored files;
  `SevenZBcj2ArchiveReader` delays EmptyFile/Anti decoding until the final empty-stream
  count is available. All new regression sources are unrun.

- **Solid EGG boundaries:** `EggArchiveReader.SolidForwardReader` retains verified block size, checks it before accepting block EOF and retires changed-spool reads/drains. `closeBlock` preserves primary/suppressed cleanup errors and `close` retries a retained failed-deletion path without reopening the session.

### TAR, CAB and LHA

- **Amiga LHA byte splits:** `LhaVolumeResolver` supplies ordered part membership
  to preparation, source snapshots and queue grouping. `LhaSplitFraming` checks the
  merged spool's header-level-0/1/2/3 boundaries and exact terminator before native
  opening. `NumericSplitArchiveMerger` retains existing space/source/cancellation
  checks. The prepared forward session owns one spool; no new decoder is added.

- **LHA LH4:** the vendored `archive_read_support_format_lha.c` now selects the
  4 KiB method window in its existing static Huffman decoder. Table bounds and
  history initialization derive from that size; Java routes remain in
  `LibarchiveNativeBridge`. The retained native patch records the addition.
  The separate Amiga byte-split resolver above adds no codec module.

- **CAB set routing:** `CabVolumeResolver` validates explicit linked header sets;
  `CabArchiveInputs` owns approved handles/read-ahead and metadata checks.
  `CabArchiveReader` connects listing/selected/bulk writes to shared free-space and
  output guards; `CabForwardReader` retains folder state and checked entry spools.
  Snapshots/queue grouping include every part, before generic numeric concatenation.
  `CabNativeDecoder` and its JNI adapter own the separate `readwide-cab` target with
  strict libmspack continuation matching. No build/native validation yet; see
  [remaining validation](CAB_MULTIVOLUME_INTEGRATION_1_0_20.md).
  Current inventory is 528 main / 526 manager / 185 archive / 91 util Java files.
  The native-codec module has five main API classes, separate from this app count.

- **Compressed TAR completion:** `ArchiveSupport.checkedTarPayload` maps opened-codec
  read/skip/close failures to terminal `TarIntegrityException`, preserving cancellation.
  `finishTarPayload` drains with bounded memory and progress/interruption checks after
  TAR EOF in streaming listing, bulk extraction and `TarForwardReader`. The viewer
  owns the payload reference and retains failures; selected reads and early close do
  not drain unrelated data. Plain TAR indexing and native TAR-Zstandard forward routing
  are unchanged. `TarPayloadCompletionTest` adds fifteen unrun regression methods.

- **Single-compressor streams:** `ArchiveSupport` enables concatenated GZIP/BZip2/XZ/LZ4 decoding for standalone and TAR wrappers; `Lz4ArchiveInputStream` sequences modern and legacy frames. `extractSingleCompressedPayload` shares guarded standalone writes, including the native Zstandard route. Standalone `.zst` raw reading forces one Zstd layer and rejects an empty source file, preserving nested compressed payloads. This does not merge separate TAR archives or change unconsumed-tail validation.

## Resources and launcher

- **Launcher icons:** the manifest uses `@mipmap/ic_launcher` and `ic_launcher_round`.
  `mipmap-anydpi-v33` adds monochrome layers for Android 13+ themed icons, reusing the
  transparent density-specific foreground PNGs. API-26 adaptive and legacy icons remain
  unchanged; colors and masks are supplied by the launcher, not Readwide's theme manager.

## Packages summarized at package level

These have not been mapped class-by-class in this document; treat the notes
as orientation, and read the package before changing it:

- **`archive/`:** archive support lives here: ZIP/CBZ/ZIPX,
  RAR/CBR/RAR5 including scoped encrypted/header-encrypted paths, CAB and
  LHA/LZH through the generic native route, 7z/CB7
  including scoped PPMd/LZMA paths, EGG, ALZ, tar and single-compressor
  streams including zstd/lz4, split volumes, password routing, and
  archive-wide filename charset detection. `ZipxAesArchiveReader` supplements
  Zip4j for authenticated WinZip-AES Shrink/Reduce/Implode/Deflate64/BZip2/LZMA/XZ entries; PPMd-I uses the independent source-built stream with libarchive fallback, and
  Zstandard uses the source-built libarchive 3.8.9 backend, while JPEG and
  WavPack route to the source-built `zipxCodecsAndroid` module. The
  first-party `Rar5CompressedDecoder` covers RAR5-container algorithm v0
  (RAR 5/6) and algorithm-v1 (RAR 7) with 80 distance codes and paged
  history through `Rar5HistoryStore` (bounded RAM, encrypted temporary spill). `RarPackedInputStream` supplies bounded
  volume-spanning plain/AES input; RAR5 block decoding and filtered output
  now stream without whole-file packed/unpacked arrays or their former
  64 MiB/256 MiB per-file caps. `EggArchiveReader` uses whole-block arrays
  only for AZO, with representability checks but no fixed 512 MiB quota.
  `SevenZBcj2ArchiveReader` constructs
  pull streams from `SevenZDecodePlan`'s cached linear-time graph order and
  `SevenZCoderRegistry`'s shared factories, then writes sequential folder slices.
  LZMA/LZMA2 dictionary memory is bounded by known stream output; PPMd and
  metadata retain array/format bounds, not fixed RAM quotas.
  `SevenZAdditionalCoders` is a registry facade.
  `SevenZBcj2Decoder` retains probability state and a pending address,
  while `SevenZAesDecoder` finalizes CBC streams without whole-file arrays.
  `ArchiveSupport` derives shared
  output budgets from available space; the fixed 128 GiB ceiling is removed.
  Some paths are first-party; others
  route through bundled-library backends such as libarchive-android and
  Apache Commons Compress. Creation support is limited and should not be
  implied for every archive format. Unsupported variants should surface
  explicit errors. A standalone `readwide-rar` library extraction exists as a
  separate repo.
- **`third_party/libarchive-android/`:** pinned source-only Android JNI backend.
  It contains the exact libarchive 3.8.9, bzip2, XZ, LZ4, Zstandard, and Mbed TLS
  sources used by `:libarchiveAndroid`; upstream test assets and all prebuilt
  AAR/`.so` files are excluded. `UPSTREAM.md` records immutable revisions.
- **`document/render`:** the HTML rendering pipeline used by the
  document viewer (`FixedHtmlRenderer`, `RenderedDocument`, block/style
  types).
- **`document/doc`:** the pure-Java legacy `.doc` reader.
- **`adapter/`, `widget/`, `ui/`:** small UI support classes.

## Where to make common changes

Local archive test fixtures share `app/src/test/java/com/readwide/manager/archive/ArchiveTestTextFiles.java`
for strict UTF-8 reads/writes through APIs exposed by Android local-test compilation.
This helper and its regression checks are test-only; they do not enter the app APK
or change extraction behavior. `assembleRelease` does not compile or run these
tests; local-test compilation, host execution and device instrumentation are
separate verification steps.

RAR extraction uses `RarVolumeNameResolver` for a single directory snapshot
and case-aware volume discovery, `RarVolumeChain` for scoped selected-entry traversal
and reusable bulk/forward chain indexing, and `Rar5CompressedArchiveExtractor` for logical-file solid preparation
and final-part CRC/size selection. Edit `Rar5ForwardReader` for viewer cursor,
verified-spool, retained-decoder and session-cleanup changes. `RarArchiveReader` bounds each RAR5 extra
record independently; `RarStoredPayloadIO` verifies plain CRCs and delegates
key-dependent CRC transforms to `Rar5Crypto`, reusing decryption secrets.
`Rar3PpmdForwardReader` owns the PPMd viewer session and calls checked-output helpers.
`Rar3PpmdSolidArchiveExtractor` feeds bounded streams to
`Rar3PpmdSolidStreamDecoder`; its rolling history replaces cumulative output
arrays, and `RarPpmdVarHDecoder` accepts streamed range-coded input.
See `CURRENT_SOURCE_STATUS_1_0_20.md` for current boundaries and validation status;
`DEV_CHANGES_1_0_18.md` retains the earlier implementation history.

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
- **Browser list/tile display:** the persisted policy is in
  `PrefsManager`; the fixed top-right entry and radio dialog are in
  `MainHomeDialogController`; layout-manager switching belongs to
  `MainActivityStartupController`; row/tile binding stays in `FileAdapter`.
- **Anything with index/geometry/string math:** put it in a `*Math` class and
  add or extend a JVM harness case where practical.
