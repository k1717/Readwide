# Patch Notes

## Readwide 1.0.16 - 2026-07-27

### Provider-backed storage fallback

- The raw browser keeps its existing platform permission behavior, while one persisted `ACTION_OPEN_DOCUMENT_TREE` route provides a read-oriented fallback whenever raw enumeration fails. `SafStorageAccess` centralizes document/tree grant policy and `UriOpenRequest` centralizes URI viewer routing.
- `MainFolderLoadController` treats `listFiles() == null` and `SecurityException` as access failures rather than empty folders. The unified **Internal Storage** entry selects raw or provider-backed browsing without duplicating drawer routes.
- SAF folder queries and archive preparation use separate executors and generations. `FileUtils.copyUriToLocal()` keeps atomic serialized cache commits but now acquires the cache lock interruptibly, so cancelled workers do not wait behind another large URI copy.

### Archive image export and portrait-only spreads

- `ImageReaderActivity` exports the current archive entry's original bytes through Downloads or SAF, with normalized names and partial-output cleanup.
- `ArchiveImageSpreadMath`, `ArchiveImageSpreadDrawable`, and `ArchiveImageSpreadNavigator` limit landscape pairing to two portrait pages, keep LTR/RTL and mixed single/spread history consistent, and draw cache-owned bitmaps without a composite allocation.
- Loose and archive preview paths use a 24MP ceiling; a low-tier neighbor preview is upgraded in place and both spread halves use the same decode profile.
- Long background expiry saves the current page and finishes only the viewer, avoiding stale archive/bitmap revival after ten minutes or background memory pressure.

### Browser thumbnails and EPUB font preference

- `FileAdapter` applies the same 40×40dp preview mode to browser and Recent rows inside a fixed 42dp slot. `FileThumbnailLoader` supports loose images, folder books, archive covers, PDF page 1, and raster EPUB cover metadata/fallbacks.
- Thumbnail work is limited to two decoders and a 96-item queue. Requests are generation-scoped; memory, disk, per-key locks, atomic commits, source stamps, folder expiry, candidate fallback, and a 60-second negative cooldown are coordinated instead of being owned by recycled row views.
- Negative-result bookkeeping is capped at 512 records per visible generation, preventing a huge folder of damaged/unsupported sources from growing the retry map without bound.
- Current-folder, Recent, selection, and image-viewer popups measure localized actions and clamp to the live content width.
- `epub_font_family` is a shared Settings/in-book preference and remains separate from the TXT font preference.

### PDF chrome and safe-area fullscreen

- `ReaderChromeLayoutMath` derives the PDF frame from `pdfChromeVisible`. Hidden app chrome releases only its own measured reserves; status/navigation/cutout insets remain and `pdf_nav_bar_spacer` stays `0/GONE`.
- `setPdfViewportPadding()` and `PdfPageView` preserve the continuous anchor or single-page fit-relative Matrix state without renderer calls, render-generation changes, or cache eviction.
- `renderCurrentPage()` now requires positive content width and height before cache lookup/render submission. A named, bounded `postOnAnimation` retry is cancelled on mode changes and destruction, eliminating invalid zero-height renders and anonymous callbacks that could outlive the Activity.
- No dependency or permission was added.

### EPUB chrome and fixed-image scroll geometry

- Vertical-writing bookmarks use a `visible-sentence` v2 anchor containing stable element/text identity, glyph/block offsets, signed DOM scroll, and normalized focus within the unobscured visual viewport. Capture and restore share one caret point and reject clipped/neighboring columns.
- Explicit saves require a fresh DOM result, retry once after layout settles, and keep multiple precise anchors on one spine page distinct. Column-start text is presentation-only; duplicate and restore identity remains tied to the precise sentence/glyph anchor.
- Page-load and interaction generations guard delayed JavaScript, font-settle, search, CFI, and bookmark callbacks. `BookmarkMergeMath` compares non-empty content anchors instead of treating the integer spine page as the whole location.
- EPUB owns a stable `systemBars`/`displayCutout` safe frame and body-colored system-bar scrims while keeping Android bars visible. `document_top_page_status` is `GONE` for EPUB, and app-chrome toggles no longer reapply boundary JavaScript.
- Page-local `EpubImagePageClassifier` results separate image canvases from mixed/text fixed pages. Near-image pages fit declared/media bounds rather than publisher wrapper `scrollHeight`, retain root zoom/pan, and preserve publisher `background-image`.
- Typed `EpubSpineItem` metadata preserves package/per-item layout, fallback chains, direct image entries, scripts, bindings, and validated media-overlay links without promoting a minority of fixed pages to a fixed-layout book.
- Scripted bindings use sandboxed local-only handlers; `EpubSmilParser`/`EpubMediaOverlayController` implement linked foreground cues; `EpubCfi`/JavaScript implement a deliberately bounded point-CFI subset. Fresh per-open synthetic origins, traversal checks, MIME routing, and independent WebView load generations isolate stale or unsafe resources.
- The optional 45-book compatibility audit verifies supported spines and zero missing resources; unsupported browser/SMIL/CFI/package features remain listed in `docs/EPUB_COMPATIBILITY_AUDIT_1_0_16.md`.
- `EpubSpreadSlotMath` gives real image/image pairs a 6 CSS-pixel inward inset per pane, with LTR/RTL placement and an unpaired final page handled explicitly.
- `EpubCssCompatibility` aliases legacy Japanese vertical-writing declarations. Vertical pages keep horizontal column overflow and logical right-edge startup, while ordinary reflow pages drop publisher height caps that confined Haruko content.
- Page-specific layout/boundary eligibility and URI-safe synthetic image paths prevent mixed-layout pages or Unicode/plus-sign resources from inheriting the wrong book-wide behavior.

## Readwide 1.0.15 - 2026-07-14

### Document text-selection gesture arbitration

- Fast tap-zone paging now requires a release before Android's long-press timeout, within touch slop, with no active selection, page swipe, or second pointer.
- Side-zone Markdown touches no longer call `cancelLongPress()` on `ACTION_DOWN`; native WebView selection is cancelled only when a confirmed short tap actually turns the page.
- Long-hold releases no longer call `performClick()`, and long-duration selection drags cannot fall through to horizontal page-swipe logic.
- `LessSensitiveWebView.dispatchTouchEvent()` now detects terminal events consumed by an Activity `OnTouchListener`; it drops a pending delayed DOWN or sends native WebView an explicit CANCEL when DOWN was already forwarded, preventing a phantom long-press after a page tap.
- `TapZoneMathTest` covers short-tap acceptance and long-hold, movement, selection, swipe, and multi-pointer rejection.

### Two-page spread navigation and input routing

- `DocumentArchiveUtils.detectEpubImagePageLike()` samples up to 32 distributed spine documents and requires at least 75% image-dominant pages plus a non-cover image page. `rendition:layout=pre-paginated` remains only a hint; text-heavy fixed-layout EPUBs and cover-only reflowable books stay single-page in landscape. Reflowable text EPUB uses a centered responsive body with the configured physical-pixel boundaries, while image-page EPUB removes WebView boundary margins and keeps 100% text zoom.
- Image-page signals include raster images, SVG/image elements, canvas, CSS page backgrounds, and image-typed `object`/`embed` elements. The image-page CSS uses `background-color` rather than the `background` shorthand so publisher background images survive theme injection; page objects are centered and contained without enabling non-image embeds.
- `SpreadMath.turnTarget()` treats a forward spread turn as all-or-nothing. For a four-page document, `1-2 -> 3-4` is final; a further turn no longer clamps to page 4 and repeats it alone. Odd totals still reach the final unpaired page (`1-2 -> 3-4 -> 5`).
- `DocumentPageTurnController` and `PdfPageTurnController` now route hardware keys through the same spread-aware helpers used by buttons and swipes. PDF fast-tap and normal-tap paths were also moved to `turnPdfDisplayPageBy`, eliminating one-page overlap in landscape mode.
- The EPUB right WebView now uses the same edge-swipe pipeline as the primary WebView. Gesture geometry is evaluated against the touched WebView, and fast-tap cleanup releases the touched view's parent intercept state.
- Document double-tap zoom tracks the source WebView. A double tap on the right EPUB page now zooms or resets the right page instead of always mutating the left page.
- Chrome hit testing uses raw screen coordinates, fixing right-page taps that were compared through the left WebView's screen origin.
- RTL EPUB spreads set the spread container to RTL layout order, mirror tap-zone previous/next semantics, and reverse DPAD left/right behavior while keeping volume/PageUp/PageDown semantic navigation unchanged.

### Slider and highlight correctness

- The document seekbar no longer writes `currentPage` or the Markdown visual-page state while the thumb is moving. It updates only preview labels and buttons, then performs the real page change on release; releasing on the current page restores live status without reloading the WebView.
- PDF search and TTS highlights now cover both halves of a real two-page composite as well as the final odd single page. Page-normalized rectangles are projected through the exact post-cap bitmap bounds retained by the winning render, so mixed page sizes, vertical centering, and the inter-page gap do not skew the overlay.
- A TTS glyph request that changes pages while another page is extracting is generation-guarded and queues the newest page explicitly. Requests arriving before the spread bitmap commits are retained and reprojected once its geometry is available; Stop/page changes clear deferred overlay state.
- PDF seekbar previews use spread range labels and the same edge-state logic as the live viewer.

### TTS resident-text queue coverage

- Resident-text prefetch no longer requires `canAdvancePage()`. It can queue another slice within the final logical page, preventing long final pages from ending after the first 1,400 prefetched characters.
- When the accepted queue ends before the full resident buffer, `continueFromUnqueuedResidentText()` jumps to the exact final accepted character and starts the next slice. This also recovers from partial prefetch acceptance by an engine.
- A partial `QUEUE_ADD` failure retains already accepted segments in `queuedSegments`; their callbacks, highlight ranges, and final char position remain visible to the controller instead of becoming untracked engine speech.
- Continuous playback treats empty/whitespace-only pages as skippable pages. A blank final page ends normally with the finished state, while single-page playback still reports that no readable text is present.
- Pause/resume preserves the prefetch boundary and crossed-boundary state. Resuming before the boundary now advances the UI when prefetched speech begins and does not repeat that page after the queue completes. `speakCurrentPage`, utterance callbacks, delayed page advances, and follow-on prefetch all reject work while paused, preventing an already-posted callback from restarting audio.
- Delayed “continue reading aloud” and notification previous/next restarts are generation-token guarded. A Stop command or newer request invalidates the pending task, so TTS cannot resurrect after an explicit stop during the page-settle delay.

### PDF bitmap and render-task safety

- Landscape spread rendering measures both pages and allocates one composite bitmap capped at 12M pixels. Each `PdfRenderer.Page` is rendered completely into an opaque white page bitmap, drawn into the vertically centered spread, and recycled before the other page is rendered. This avoids transparent paper and top/bottom clipping without restoring two simultaneous page intermediates.
- Single-page neighbor prefetch is capped at 6M pixels per bitmap because up to four neighbors may be retained. Speculative `OutOfMemoryError` is contained, the temporary bitmap is recycled, and cached neighbors are evicted to release pressure.
- `SinglePageCacheMetadata` now stores each cached page's point dimensions and intended display width. Cache hits no longer reuse the previously displayed page's geometry for Matrix zoom, sharpening, search highlights, or legacy display sizing when a PDF mixes page sizes/orientations.
- Continuous-page rows now preserve the intended fit/zoom height even when the pixel cap reduces backing-bitmap resolution. `OutOfMemoryError` clears speculative continuous caches instead of escaping the render task and terminating the viewer.
- Visible render error callbacks, including OOM handling, now check `renderGeneration`; an obsolete cancelled job cannot replace a newer page with a stale error state.
- Toolbar visibility no longer produces a viewport-padding change, so `setPdfViewportPadding()` returns before geometry generation, cache, fit, or rendering can be touched. For real geometry changes such as rotation, `PdfPageView.onSizeChanged()` refits the accepted bitmap and old-geometry neighbor prefetch is cancelled independently.
- The winning spread render retains the exact left/right bitmap rectangles after the 12M-pixel cap. Search and TTS project each source page through those rectangles instead of treating the composite as one page; a right-page match no longer forces an unnecessary spread turn.
- TTS glyph extraction tracks both page identity and its own generation. If a second page is requested while the first is extracting, the obsolete callback cannot paint and the new page is queued even when no later utterance callback arrives.
- A `singleTop` PDF replacement now has a document generation independent from page-render generations. It closes the previous search dialog/engine and renderer, invalidates lazy PDFBox loads and queued search callbacks, releases read-aloud state, and clears the old file identity before the replacement resolves. A partial renderer open closes its `PdfRenderer`/descriptor and clears page/file identity before reporting failure, so autosave cannot attach invalid state. PDFBox search serializes document cleanup behind the scan thread, and result insertion rechecks the query generation under the same result lock, preventing both close-vs-stripper races and old-query result contamination. Ordinary page turns no longer discard a valid full-document TTS build.

### PDF render geometry and EPUB parsing follow-up

- `PdfPageRenderPlan` centralizes page fit, intended display size, supersampling, and allocation-cap math for the visible single-page renderer, neighbor prefetch, and continuous rows. This removes three subtly divergent copies of the same calculation.
- Neighbor prefetch cache acceptance now uses a monotonic geometry generation covering usable width/height/zoom changes. The old worker read live `View` dimensions off-thread and could let a bitmap rendered for the previous toolbar/fullscreen height enter the new cache.
- `renderCurrentPage` keeps rendered PDF point dimensions in task-local holders and commits them only after the render-generation check. A cancelled job can no longer race a cache hit and leave search/zoom geometry from the wrong page.
- Sharpen requests have their own generation and also capture the accepted base-render generation. A patch started before rotation/chrome relayout cannot attach to the replacement bitmap; `OutOfMemoryError` recycles the optional patch and leaves the fit bitmap visible.
- `DocumentTextDecoder` handles UTF BOMs, BOM-less UTF-16 XML/XHTML, and declared XML/HTML charsets for EPUB spine and metadata previews. `UriPathCodec` replaces direct `URLDecoder` calls in EPUB manifest parsing, WebView resources/navigation, document extraction, and display-name normalization, preserving literal plus signs because those values are URI paths/names rather than form fields.
- `EpubViewportParser` reads and replaces viewport metadata regardless of attribute order, supports an HTML-style unquoted `name=viewport`, and is shared by fixed-layout detection and rendering. Missing-head pages receive the replacement viewport through the common head-injection path.
- `EpubImagePageClassifier` is now Android-free and independently testable. Embedded-object and CSS image signals are declaration-scoped, excluding `background-image:none` and unrelated filename strings. Fixed-layout CSS uses `background-color` so page images carried by publisher background layers survive centering and find-offset injection.

### Immersive reader chrome and inset ownership

- `EdgeToEdgeUtil.applyReaderSystemBarVisibility()` now gives all reading surfaces one policy: visible controls show the navigation bar, hidden controls hide it with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, and the independent status-bar preference remains authoritative.
- PDF's hidden navigation spacer is always collapsed. Its visible bottom toolbar alone owns `bars.bottom`; transient bars overlay the hidden-chrome page instead of shrinking it.
- Document/EPUB keeps its body-column navigation spacer collapsed. The body root reserves only a physical display cutout; a live side navigation inset is owned by the overlay controls so landscape spreads retain their full canvas width. TXT now follows the same side-inset ownership, keeping body width/page count fixed while its overlay title/status/bottom chrome receives the live navigation excess; the vertical bottom spacer remains chrome-gated.
- Image/comic controls now own their insets as overlays; the page canvas itself has no 48dp toolbar, 82dp slider, or system-bar reserve in either chrome state.
- PDF portrait startup no longer accepts the pre-inset app-bar/bottom-control measurements as its permanent toolbar frame. Its top fallback resolves the current theme's `actionBarSize` and uses 56dp only if the attribute is unavailable. Complete toolbar-ON fallbacks remain active until exact status/cutout/navigation insets arrive, IME-inflated bottom measurements are rejected, and a cached frame outside the current status/navigation fallback identity is discarded, covering an in-place status-bar preference or gesture/3-button navigation change.
- PDF and document/EPUB reapply the current immersive/status-bar policy in `onConfigurationChanged`, preventing OEM rotation handling from revealing a navigation bar that shifts or narrows the body.
- The document/EPUB hidden page-status strip uses display-cutout insets ignoring visibility and adds the status-bar reserve only when the reading preference requests it. Transient bar reveals can overlay the page without changing the strip height or WebView frame.

### General audit: stable overlays, persistence, XML, and sizing

- Image chrome is now a true overlay in both visibility states. Insets update only the controls; neither a middle tap nor the resulting system-bar transition calls `configureBaseMatrix()` or changes `ZoomImageView` content bounds.
- EPUB URL interception preserves source-pane identity and fragment state. Fragment-only base-directory URLs, same-page anchors, right-spread anchors, and cross-page anchors each have an explicit route.
- `AtomicUtf8File` consolidates rollback-safe JSON persistence for `BookmarkManager` and `ThemeManager`. All public bookmark/reading-state collection operations use the manager monitor, closing concurrent iteration/save races.
- `FileUtils.copyUriToLocal()` serializes URI-cache prune/commit work and writes into a unique staging file beside the destination. It flushes and `fsync`s before `Os.rename`, and failure deletes only the staging file, preserving the previous valid destination. Same-URI `singleTop` replacement therefore cannot truncate a file still being read by PDFBox/TTS.
- `SecureXml` replaces three duplicated DOM factory configurations and adds a rejecting entity resolver. Tests prove normal namespace parsing and prevent a local-file external entity from being expanded.
- `PdfRenderSize.capToPixels()` replaces four copies of render cap math and enforces both pixel and per-dimension bounds after minimum-one-pixel rounding. Four boundary tests cover aspect preservation and overflow inputs.
- PDF viewport policy is now independent of chrome visibility. Portrait caches the first valid toolbar-ON app-bar/bottom-bar frame and reuses it while hidden; landscape always uses the toolbar-OFF top safe inset and zero bottom reserve even while controls are visible.
- PDF chrome callbacks may re-evaluate the policy, but identical targets make the viewport setter a no-op. A toolbar toggle therefore cannot resize/refit the accepted bitmap, increment the geometry generation, clear cache state, or call the PDF renderer.
- PDF/EPUB body roots reserve only immutable display-cutout side insets. Live side navigation insets are applied to overlay controls, preventing them from narrowing the landscape PDF canvas or the optional image-page EPUB spread panes.

### Document/PDF proportional scrolling and boundary follow-up

- EPUB boundary slider steps notify the active document viewer through `ViewerRegistry`, update the live DOM, and use a physical-pixel boundary signature to trigger an authoritative same-page HTML reload with scroll restoration when needed. Values remain physical px despite the legacy `*_dp` preference-key names. The preference listener is declared after the lifecycle state it captures, removing the `illegal forward reference` compile failure.
- `ProportionalFastScrollController` serves reflowable EPUB, Word, HWP/HWPX, Markdown, and PDF continuous mode. It separates silent metrics synchronization from real scroll activity, coalesces motion updates once per animation frame, reacts to rail layout changes, uses long ranges, and keeps a 32dp minimum thumb.
- At rest the thumb fades to alpha zero and the non-clickable rail passes input through. The enlarged 36dp horizontal hit region applies only while the thumb is visible, with vertical recognition limited to the thumb plus 8dp at each end. The rail stays below loading/search overlays.
- Drag state has one terminal path. Mode/source invalidation, `UP`, `CANCEL`, active-pointer loss, pause, and destroy release parent interception and pressed state. The initial grab offset is preserved, a zero-travel thumb stays at Y=0, and document/PDF pause-resume performs a hidden metrics resync.
- PDF touch dispatch reserves visible-thumb sequences before tap paging, pinch/pan, or toolbar gesture handling, preventing one rail gesture from also turning a page or losing its terminal event.
- PDF continuous fast-scroll uses an adapter-owned long-range page-height model. A Fenwick prefix index combines default estimates with rendered heights and is snapshotted during a drag; `scrollToPositionWithOffset` lands directly on the target without binding/rendering all intermediate pages. Bitmap eviction preserves height/pan geometry, stale-generation OOM work is ignored, and a valid OOM receives at most one half-cap retry without a full dataset notification loop.
- Continuous scroll/anchor posts use a mode generation and are cancelled centrally, so vertical-mode work cannot reassert an old page after switching to horizontal mode. Reading-state writes during fling/scrub are debounced and committed at idle.

### Robustness, tests, and cleanup

- PDF page-status controls are cleared and disabled when a document has no loaded pages, and load-error status updates are null-safe.
- `SpreadMath.visibleEndIndex()` and `canTurn()` centralize visible-range labels and boundary state. `SpreadMathTest` covers even/odd final spreads, backward boundaries, and single-page mode.
- `LargeTextForwardCursorEquivalenceTest` executes the real partition core in full-scan and cursor modes. It compares every result field over fixed lookbehind/lookahead/reset/EOF chains and 5,400 deterministic randomized requests, then tiles sequential body ranges back into canonical transformed text to prove no skip or duplication. The production transform remains selector + display-rule application; only a package-private transform seam was added so the same core can run on the JVM.
- Image bitmap caching now presents an on-demand decode before offering it to `LruCache`. A bitmap larger than the cache budget can therefore be rejected synchronously without being recycled before display, and the full-quality marker is retained only if the cache still contains that exact instance.
- Image sequence mutations have their own generation token. Rename/delete invalidates obsolete decode-prefetch work; delete additionally clears the index-keyed cache because all later indexes shift. A stale old-sequence bitmap cannot repopulate a new index after the clear.
- A terminal image decode failure clears a surface belonging to the previous page, while a same-page reload may keep its valid surface. `ZoomImageView` releases parent interception at every gesture end/cancel and when its image is cleared.
- An earlier full-lint pass found one real minSdk issue outside the image path: `DocumentSearchController` called Java/Android's API-35 `CharSequence.isEmpty()` while the app supports API 24. It now uses `length() == 0`. Animated-image type checks put their API-28 guard first, AppCompat tint uses `app:tint`, intentional newer theme attributes declare their target API, and two documented `Layout.BREAK_STRATEGY_SIMPLE` uses carry narrow suppressions for an Android 15 lint constant-set false positive. Because the source changed after that earlier run, `lintDebug` must be rerun on the exact tagged tree rather than treating the earlier result as final verification.
- Removed unused spread-step/clamp wrappers and obsolete EPUB toolbar-height helpers.
- Version remains `1.0.15` / `10015`; no dependency or permission was added. `zstd-jni` moved from the APK runtime configuration to JVM tests only.
- Added `app/src/main/assets/open_source_licenses/libarchive_android_and_codecs.txt` so the release APK carries the pinned libarchive-android and bundled-codec notices even though dependency `META-INF` license files are excluded during packaging. Source and APK release checklists now verify this asset explicitly.

### Large-TXT search engine follow-up

- `LargeTextSearchEngine.countMatches()` now builds a bounded primitive match index while it is already performing the required full scan. The index stores only character positions and display-line numbers, not document text, and is capped at 200,000 matches.
- `searchNearest()` and nth-result search consult the completed index first and use binary search with wrap-around semantics. A count followed by repeated Next/Previous no longer creates one file-open/full-scan per sparse result.
- Index identity covers absolute path, size, last-modified time, query, all search-option flags, blank-line collapse, and the complete active display-rule signature. A changed file/rule never reuses stale positions, and a cancelled or over-cap count publishes no partial index.
- `LargeTextSearchEngineIndexTest` verifies exact positions, line numbers, ordinals, both wrap directions, nth lookup, no additional reader opens after indexing, file/rule invalidation, cancellation, and the memory cap.

### Archive image preview and extraction routing follow-up

- `ArchiveImageSequenceLoader` no longer discards the forward reader after extracting the selected comic page. Ownership is transferred through `ImageSequenceHandoffStore` and adopted by `ImageReaderActivity`, with explicit close-on-discard, close-on-launch-failure, activity-destroy, and invalid-handoff paths. This removes the loader/viewer double scan from the start of solid RAR/7z/TAR archives.
- `ForwardArchiveReader.drainCurrentEntry()` lets libarchive decode-discard non-images and cache hits in its reusable direct buffer. RAR/7z no longer use header-only skipping, because solid dictionary/window state must be advanced even when output bytes are not needed. Commons readers retain the buffered drain fallback.
- Unencrypted PPMd/BCJ2 7z enters a libarchive forward stream rather than failing Commons sequential decoding and degrading immediately to a whole-archive temporary extraction. Split 7z forwards every contiguous volume. Encrypted special-coder 7z stays on the verified first-party AES+PPMd/BCJ2 path.
- RAR/CBR continues through libarchive using the full resolved volume chain and now shares the same handed-off session reader. Whole-archive and first-party fallbacks remain available after a forward-stream failure; complete RAR support is not overstated.
- Android Zstandard now uses the libarchive Zstd filter for `.tar.zst` forward/list/extract and a raw/empty libarchive stream for plain `.zst`; decoded output retains the extraction-size cap. Optional-codec linkage failures become clean fallback/unsupported results instead of crashing, and desktop-native `zstd-jni` is JVM-test-only rather than an APK runtime dependency.
- Neighbor bitmap warm-up uses the viewport display scale with two workers and an 8M-pixel tall-page safety backstop. A sampled cached page is final for normal viewing and is not automatically decoded again at detail size; pinch/double-tap zoom remains the only detail trigger. GIF/WebP candidates skip bitmap warm-up on API 28+, and the in-flight marker remains held until the main-thread cache decision.
- Sensitive forward readers reuse a ready preview only when its path is verified by the current password session. An unverified ready marker/file is removed under the cache stripe before extraction. `ArchiveImageSequenceLoader.Result` carries the exact set of all sensitive cache paths successfully verified by lazy/full preparation and the prepared reader; `ImageSequenceHandoffStore` transfers that set to the viewer. Already-decoded entries therefore avoid a later `loadFully` re-extraction, while an unrelated old ready file in a mixed-password archive is never trusted merely because one password succeeded elsewhere.
- Archive prefetch now returns an explicit extraction-success value before scheduling bitmap decode, and decode rechecks the committed ready marker. Bitmap work remains keyed by sequence/index/path rather than direction-plan generation, so an already-running valid neighbor decode can serve the next plan instead of being discarded while its `in-flight` key prevents replacement.
- A superseded RAR/7z forward prefetch checks cancellation between completely drained entries. It never abandons a solid entry mid-stream, but it releases the reader before walking to an obsolete distant target when a user page request or newer plan is waiting.
- Password-backed prepared forward readers are handed to the viewer instead of being closed and reopened at byte zero. Handoff and sequence preparation compare a constructor/start snapshot of path, length, and modification time, carry it through the launch token, and recheck it immediately before applying the sequence; replacing the archive during preparation or launch rejects the stale reader and entry plan.
- Added policy/ownership/search-index tests and reran the local user RAR samples plus the password 7z fixture. No new dependency or permission was required; the later Android Zstandard audit only moved the existing `zstd-jni` declaration to JVM tests.

## Readwide 1.0.14 - 2026-07-08

### Release scope

- Android metadata is `versionCode 10014` and `versionName "1.0.14"`. The package remains `com.readwide.manager`, and the release signing lineage remains the Readwide key introduced in 1.0.6.
- No new Android permission or runtime dependency was added. The default build still has no `INTERNET` permission, no ads, no analytics SDKs, no account system, no cloud sync, and no telemetry.

### EPUB - landscape two-page spread

- EPUB pages now enter a two-page side-by-side spread automatically whenever the device is in landscape orientation, on both phones and tablets. Portrait keeps the previous single-page WebView path.
- The left WebView remains the primary page host. The right WebView is used only for the second EPUB page in the spread and does not install the text-selection bridge or primary scroll listeners.
- Spread navigation uses one visible spread per page turn. Next/previous buttons, tap zones, and swipe paging advance by the spread step, while direct page jumps still land on the requested page.
- The right half of the spread now participates in the same tap and swipe pipeline as the left half. In spread mode, tap zones are calculated across the full spread container: far edges page, and the center area, including the seam, toggles the controls.
- Reflowable EPUB boundary values are injected after publisher and responsive styles as final body padding, so left/right/top/bottom settings affect rendered text without shrinking the WebView.

### PDF - landscape two-page spread

- PDF single-page mode now renders a combined two-page bitmap spread in landscape orientation. Phone landscape and tablet landscape use the same rule.
- PDF vertical continuous mode is deliberately excluded and keeps the existing RecyclerView/continuous-scroll behavior.
- In spread mode, page buttons, tap zones, and swipes advance by one spread. The page indicator displays the visible page range, such as `3-4 / 20`.
- The bottom controls and the PDF title bar overlay the spread instead of changing the render area. Toggling controls therefore does not re-fit or re-render both pages just because chrome visibility changed.
- At the 1.0.14 stage, search and read-aloud overlays were suppressed on a combined spread to avoid drawing incorrect single-page rectangles. Readwide 1.0.15 implements the per-half coordinate mapping described in the current section above.

### Markdown and document rotation

- Markdown visual-page caches are reset on orientation change, matching the reset already used for document load and text-zoom changes. This keeps visual page count, page turns, and read-aloud start anchors aligned with the current viewport after rotation.
- Word, HWP/HWPX, and Markdown remain single-page in landscape. The 1.0.14 spread gate is intentionally EPUB-only for the document viewer.

### TXT reader - initial chrome and title band

- The TXT reader now opens with controls and the file title visible, matching the document and PDF viewers. The reveal is keyed to the file path so a second file opened in the same activity still gets its own initial title reveal, while reloads and restored sessions do not force controls open again.
- The visible TXT top chrome is rendered as one solid theme-colored band across the status/camera area, page indicator, and title strip. The title is positioned over the masked first text row, uses the body font at a smaller size, and shrinks to fit the available slot.
- Empty display names from content providers are treated as missing. The TXT reader, restore path, and main open-from-app router fall back to the local file name or URI basename instead of leaving the title blank or failing extension routing.

### TXT reader - CR/CRLF whole-file consistency

- Whole-file TXT loading now normalizes CRLF and old-Mac CR line endings into the same newline character space used by the large-file engine. This prevents lone-CR files from rendering as one merged line and keeps saved positions, search hits, and read-aloud offsets consistent across file size modes.

### Large TXT - forward partition read path

- Sequential large-TXT forward reads use a forward cursor so ordinary forward movement does not repeatedly scan the file from the beginning for every section.
- The forward path is reset when moving backward or when encoding, blank-line collapse, or display-rule settings change. The exact page-index path remains a separate full-file scan.
- Because page-count and partition-boundary correctness are more important than speed, future edits to this area should compare the cursor path against the full-scan path for `content`, `baseCharOffset`, `bodyStartCharCount`, and `bodyCharCount` equivalence.

### Image viewer - revisiting cached pages

- The image cache now records when a cached bitmap is already full quality, including after a completed detail decode. Returning to that page displays the cached bitmap immediately instead of launching another detail decode.
- The full-quality marker is invalidated on cache eviction, so memory-pressure eviction cannot leave a stale “already full quality” state behind.

### Code map and release-document cleanup

- `docs/CODE_MAP.md` was updated to match the current 1.0.15 structure, including the tested large-TXT forward cursor, PDF/EPUB pure helpers, current class counts, and archive backend wording.
- The local F-Droid metadata mirror now contains only the published builds through 1.0.13. Unresolved 1.0.14 placeholders were removed; 1.0.15 must be added only after its final tag exists and can be pinned to the exact immutable commit.

## Readwide 1.0.13 - 2026-07-04

### Read-aloud (TTS) - spoken-sentence highlight in the document viewer

- The document viewer (EPUB, Word-family, HWP/HWPX, Markdown) highlights the sentence currently being read aloud, matching the text reader. The read-aloud buffer is plain text (the page HTML flattened by `Html.fromHtml`), so character offsets don't map onto the rendered DOM; `DocumentTtsHighlightController` instead injects a helper (`window.__rwTtsHl`) that walks the page's visible text nodes and locates the spoken sentence by whitespace-squeezed comparison (all whitespace dropped from both sides, case folded only where the fold is 1:1), which makes the match immune to paragraph-seam and spacing differences. The matched range is wrapped with one span per text node, so sentences that cross element boundaries - a bold header plus the first body sentence, or a paragraph seam - are highlighted in full. Span styles are applied with the `important` priority (plus a box-shadow of the same color) so book or theme CSS cannot mask them. The viewer recenters the highlight when it leaves the comfortably visible area (fully above the viewport, or entering the bottom band (~180px) the toolbars cover), so playback follows smoothly through the lower part of long pages; Markdown is excluded, as it already follows playback with its own scroll. The helper is reinstalled on every page load; a page turn reloads the WebView, clearing the old highlight, and the most recent sentence is replayed once the new page finishes loading - so when read-aloud rolls onto the next page, its first sentence is highlighted as soon as the page is ready. A sentence that cannot be located is skipped without interrupting playback. The string handling (normalization and JavaScript-literal escaping, including quotes, backslashes, and U+2028/U+2029) lives in `DocumentTtsHighlightMath` and is JVM-verified.
- `htmlToPlainText` now strips `<head>` and `<title>` content. EPUB chapter XHTML carries a `<title>` alongside the visible heading, and its text used to leak into the read-aloud buffer: read-aloud spoke each page's title twice, and the duplicated text also prevented the highlight from matching the page. (Saved read-aloud positions from earlier 1.0.13 test builds may land a few characters off once, since the buffer no longer contains title text.)

### Read-aloud (TTS) - spoken-sentence highlight in the PDF viewer

- The PDF viewer also highlights the sentence being read aloud. PDF pages are bitmaps, so this uses glyph coordinates instead of DOM search: `PdfPlainTextExtractor.extractPageGlyphs` extracts one page's text together with a per-character box (text assembled exactly like the read-aloud buffer's extractor; box formula and rotation-adjusted page dimensions mirror the search engine's stripper), and `PdfTtsHighlightController` lazily extracts the visible page's glyphs off-thread, merges the spoken segment's character range into one rectangle per visual line (`PdfTtsHighlightMath`, JVM-verified), and hands page-normalized rectangles to a dedicated read-aloud layer on `PdfPageView`, independent of the search-highlight layer so speaking and searching don't clobber each other. Correctness is guarded structurally: the extracted page text must equal the read-aloud buffer's page slice (excluding the buffer's per-page separator) before any coordinate is trusted; on mismatch the page is marked unmappable and highlighting is skipped, because a wrong highlight is worse than none. One page's glyph boxes are cached at a time and dropped on page change, which also clears the overlay.

### Correctness - locale-independent file-extension matching

- Three file-extension checks used `String.toLowerCase()` without a locale (`Bookmark.getFileTypeLabel`, `Bookmark.getPcEditPositionType`, `FontManager` font-file scan). Under the Turkish locale, `toLowerCase()` maps `I` to a dotless `\u0131`, so a name like `FILE.PDF` could fail to match an extension - a font might be skipped or a bookmark mislabeled on Turkish-locale devices. All three now use `toLowerCase(Locale.ROOT)`, matching the rest of the codebase's extension handling.

### File actions - case-only rename now works

- Renaming a file or directory to the same name in different case (e.g. `test` -> `tESt`, or Title Case to lowercase) silently did nothing: the rename reported success but the name was unchanged. On the case-insensitive, case-preserving file systems Android mounts external storage through (FAT32/exFAT, sdcardfs/FUSE), the destination name resolves to the same directory entry as the source, so `File.renameTo` treats it as renaming a file onto itself and no-ops while returning true; renames that also add or remove characters worked. Both rename entry points (the main file-action dialog and the image viewer) now go through a shared `FileSystemOps.renameInPlace`, which detects a case-only change and performs it in two hops through a unique temporary name in the same directory, forcing the entry to be rewritten. Non-case renames are unchanged and still refuse to clobber an unrelated existing file.

### Read-aloud (TTS) - "continue reading aloud" resume fixed for every viewer

- The main-screen "continue reading aloud" prompt resumes playback in the correct viewer, at the correct position, for every format:
  1. **Markdown** is routed to the document viewer it normally opens in; the prompt used to test `isTextFile()` first, which also matches `.md`, sending it to the plain-text reader.
  2. **EPUB and Word** resume from the exact saved position within the page rather than the page top. Paged documents carry a one-shot within-page resume anchor (`pagedTtsResumeAnchorCharPosition`): the resume jump records the saved position, the queue consumes it once, and a page change discards it - so the read-aloud dialog's page restart, and every later page, begin from the page top as expected.
  3. **PDF** auto-starts on resume (`EXTRA_AUTOSTART_TTS` plus the same arm/poll/build/resume path the document viewer uses) and applies the same one-shot within-page anchor, resuming mid-page. Scanned/image-only PDFs stay silent on this automatic path.
- Starting Markdown playback reads from the position on screen. The start point is the beginning of the first visible line: a caret probe captures the viewport-top text (y sweep for a text hit, then an x sweep so the leftmost hit gives the line start; the top block's text is the fallback), it is located in the read-aloud buffer by whitespace-insensitive search, and the position is snapped to the nearest preceding line/sentence/word boundary - so pressing play on any visual page begins with the line on screen, including mid-paragraph when a long paragraph spans pages. The anchor is refreshed when the read-aloud dialog opens, so it reflects the screen at the moment of play. The previous proportional source-offset mapping (which drifted by pages on larger documents) remains only as a last-resort fallback. While playing, the view follows the spoken sentence, so stopping and restarting continues from the sentence that was being heard; scrolling back to the top starts from the beginning. (The speech anchor tracks the start of the segment being spoken and is retired when playback stops.)

## Readwide 1.0.12 - 2026-07-04
### Read-aloud (TTS) - PDF crash on start fixed (missing PdfBox init)

- Starting PDF read-aloud crashed the app (or tore the viewer down) whenever it was the first PdfBox user in the process: `PDFBoxResourceLoader.init` was called only in `PdfSearchController`'s constructor, so a user who opened read-aloud without ever opening PDF search ran `PDFTextStripper` against an uninitialized resource loader. PdfBox's font/glyph machinery then fails partly with `Error`s (static-initializer failures such as `ExceptionInInitializerError`, then `NoClassDefFoundError` on retry), which the extractor's `catch (IOException | RuntimeException)` did not contain - and an uncaught throw on the bare extraction executor thread kills the process. Three-part fix, each verified: (1) `PdfPlainTextExtractor.extractPageText` now takes a `Context` and calls `PDFBoxResourceLoader.init` first, mirroring the search controller's best-effort pattern (root cause - the search path was immune precisely because its constructor inits); (2) the extractor's catch was widened to `Throwable`, proven by a stub harness that makes the stripper throw `ExceptionInInitializerError`: the pre-fix code lets it escape ("would kill the process"), the fixed code returns an empty map that surfaces as the existing scanned-PDF message; (3) the background build in `showPdfTtsDialog` is wrapped so any unexpected failure resets `pdfTtsTextBuilding` instead of freezing the entry point at "Preparing read-aloud..." forever, and a no-text result is still cached so scanned PDFs answer later taps from the fast path.

### Read-aloud (TTS) - quotation-mark muting at Aggressive pause reduction

- The Aggressive pause-reduction level now mutes quotation marks (straight and curly doubles, guillemets, CJK corner brackets) in `TtsSegmenter.normalizeForSpeech` - some neural engines pause at every quote, which makes dialogue crawl. Order matters twice here: quote muting runs before the sentence-stop softening because a dialogue-final stop (`...!"` ) is not followed by whitespace until the quote becomes a space, so quotes-first is what lets those stops soften at all (locked by `aggressiveSoftensDialogueFinalStopsOnceQuotesAreMuted`); and apostrophes/single quotes are deliberately untouched so contractions survive (locked by `apostrophesSurviveAggressiveQuoteMuting`). Off and Medium keep quotes - they carry meaning in fiction - so only the strongest level trades them away, matching the external feedback's own caution.

### Read-aloud (TTS) - logcat diagnostics for silent-failure triage

- `ReaderTtsController` gained targeted `Log.d` instrumentation under the `ReadwideTts` tag (obtainable from release builds via `adb logcat -s ReadwideTts`), covering the diagnostic points the external feedback asked for: engine init status and engine name, `setVoice`/`setLanguage` results including both fallback hops of the locale-silence fix, per-page queue summaries (segment count, generation, first segment lengths), every `speak()` failure with its position and whether it was fatal (page queue / resume) or non-fatal (prefetch drop), `onError` utterance ids, and callbacks dropped by the stale-generation guard - the last one turns "audio stopped and nothing happened" reports into a diagnosable trace. The PDF read-aloud build path logs under the same tag: the buffer result (page count, text presence, buffer size) and any contained extraction or build failure with its stack - without this, a failure contained by the crash fix above would be silent and a "says scanned but the PDF has text" report would be undiagnosable. No behavior changes; logging only.

### Main screen - double-tap no longer opens the same file twice

- A main-screen and viewer-internal bug audit (excluding the read-aloud areas already covered this cycle) found one real papercut: the file-open funnels (`MainActivity.openFile` / `openFileFromUri`) had no debounce, so a fast double tap on a recent card fired two `startActivity` calls before the first viewer reached the top of the stack - and `singleTop` only dedupes an activity that is already on top, so the same file stacked twice and the user had to back out through it. Both funnels now accept one open per 600 ms window (`acceptFileOpenNow`), which cannot interfere with intentional use. Two merge artifacts (two constant declarations fused onto one line in `DocumentPageActivity` and `ReaderTtsController`) were also split back out; no behavior change.
- Audited and verified clean, with the checks on record: `ButtonOrderManager` group lists vs the merged layouts (PDF 9/9 and document 8/8 one-to-one, TXT rollback intact, `btn_more`/`pdf_zoom_more` intentionally unmanaged outside the strips); resume-routing intents vs declared activity extras; an automated scan of every `executor`/`sequenceExecutor`/`prefetchDecodeExecutor` lambda for view calls outside `handler.post`/`runOnUiThread` (zero suspects); the image viewer's decoded-bitmap `LruCache` recycle policy (all `put` calls are posted to the main thread, so `entryRemoved`'s `currentBitmap` guard runs on the UI thread - no cross-thread visibility hazard); receiver and loading-dialog lifecycle pairing; `MarkdownVisualPageMath` divide/clamp edges; the PDF continuous adapter's generation-guarded binding; and the autostart-consumed flag, which never resets but is unreachable as a bug because `singleTop` cannot deliver a second autostart intent to a live viewer instance while `MainActivity` is on top.

### Refactor - PDF viewer read-aloud integration extracted to a controller

- The same extraction applied to `PdfReaderActivity`, the second-largest file (3,851 lines). Its read-aloud integration - the dialog entry point, the off-thread PDF text extraction/build (including the 1.0.12 crash-fix guards), and the toolbar button visibility - moved into `PdfTtsIntegrationController`, mirroring the document-viewer controller. This one is simpler: the PDF viewer has no Markdown following and no autostart-on-resume, so the controller holds only the in-flight-build flag (the one piece of state nothing else reads), while the playback controller instance, the text buffer, and the `TtsHost` implementation stay on the activity. The activity keeps thin delegates for `showPdfTtsDialog` and `updatePdfTtsButtonVisibility`, and `isTtsTextTemporarilyUnavailable()` reads the flag through `isTextBuilding()`. Verified with the same battery as the document-viewer extraction: brace gate and full parse on both files, a scripted check that all controller references to activity members resolve and are non-private (this caught two fields, `localFile` and `renderGeneration`, that were still `private` and would not compile from the controller - both relaxed to package-private), no leftover duplicates of the moved methods, and every `TtsHost` method still present on the activity. The activity is 3,793 lines after the extraction.

### Refactor - document viewer read-aloud integration extracted to a controller

- `DocumentPageActivity` was the largest and highest-churn file in the codebase (4,066 lines, touched by every read-aloud change this cycle), with the TTS integration - dialog/autostart entry points, the off-thread buffer build, toolbar button visibility, and Markdown's approximate following - grown inline. That block now lives in `DocumentTtsIntegrationController`, following the codebase's existing controller-extraction convention (same package, activity reference, package-private field access). Behavior-preserving by construction: method bodies moved verbatim with member references rewritten through `activity.`, and the activity keeps thin delegates for every external entry point (`showDocumentTtsDialog`, `updateDocumentTtsButtonVisibility`, `resetDocumentTts`, `onDocumentTtsSegmentSpoken`, and the `TtsHost` interface methods, whose Markdown branches now delegate). One genuine dedup came out of it: the dialog and autostart paths had duplicated the entire off-thread buffer-build block verbatim; both now share `buildTextSourceThen(Runnable)`. State ownership was split deliberately - the playback controller instance, the text buffer, and the Markdown speech anchor stay on the activity (they are read by the activity's lifecycle, the `TtsHost` methods, and `DocumentTtsTextSource` respectively), while the autostart arm/attempt flags, the in-flight-build flag, and the follow throttle position moved into the new controller because nothing else reads them. Verified without a device by: brace gate and full Java parse on both files; a script proving every activity member the controller references exists and is non-private (19 references); no leftover duplicates of the moved methods; every `TtsHost` interface method still present on the activity; and a residual-symbol sweep that caught (and rewired) the one reference the initial inventory missed - `isTtsTextTemporarilyUnavailable()` reading the moved in-flight-build flag, now served by `isTextBuilding()`. The activity is 3,892 lines after the extraction.

### Read-aloud (TTS) - floating playback card in the document and PDF viewers

- The floating play/pause + stop card that the text reader already shows during read-aloud is now in the document viewer (EPUB/Word/`.doc`/HWP/HWPX/Markdown) and the PDF viewer as well, so playback can be paused, resumed, and stopped without opening the dialog or reaching for the notification. It behaves identically in all three: the card appears while playback is active, updates its icon between play and pause, and can be dragged anywhere on screen, with taps routing to whichever button is under the finger. The wiring was extracted into a shared `TtsFloatingCardController` (view binding, drag/tap gesture handling, and visibility/icon updates driven through a small `Controls` callback) rather than copied, so the three viewers stay in sync; the previously empty `ttsUpdateFloatingCard()` stubs in the document and PDF activities now delegate to it. The card is refreshed from the same controller state-change hook the text reader uses, so it tracks play/pause/stop and page turns without extra plumbing. The card is given a higher elevation (16dp) than the bottom toolbars (12dp) so it draws above them instead of being clipped behind the chrome, matching how it already floats above the text reader's controls. Verified without a device by gate + full parse on all changed files, an access-level sweep confirming the shared helper only touches the activities through the callback and inherited `Activity` API (no private access, the class of bug that slipped through the PDF refactor), and a stub compile of `TtsFloatingCardController` against a minimal Android API to confirm it builds.

## Readwide 1.0.11 - 2026-07-03

### Release scope

- Android metadata is `versionCode 10011` and `versionName "1.0.11"`. It keeps the `com.readwide.manager` applicationId and the `readwide` release signing key, so 1.0.11 installs in place over 1.0.10 and earlier as a normal update.
- This release adds legacy binary Word (`.doc`) rendering through a new self-contained pure-Java parser, with no new dependency and no new permission.

### Documents - legacy .doc reader

- `document.doc.CompoundFileReader` is a read-only OLE2 / Compound File Binary Format ([MS-CFB]) reader. It parses the header, the FAT (via the DIFAT), the mini FAT, and the directory, then returns whole streams by name (WordDocument, 0Table/1Table). Sector chains are walked with signed sector numbers so the terminators (ENDOFCHAIN, FREESECT) end a chain naturally, and every read is range-checked so a corrupt file fails cleanly instead of reading out of bounds.
- `document.doc.DocLegacyLayoutExtractor` reconstructs the main document text from the piece table (CLX). It walks the variable-length File Information Block (FIB) - past `fibRgW` and `fibRgLw` to the `fibRgFcLcb` array - to read `fcClx`, then reconstructs text from the piece descriptors, decoding both uncompressed UTF-16LE pieces and compressed windows-1252 pieces. Every reconstructed character retains its file character position (FC) so its formatting can be resolved during run splitting. Field codes are dropped while field results are kept, manual line breaks and special hyphens/spaces are normalized, and table cell marks split into separate paragraphs.
- `document.doc.DocCharacterProperties` decodes character formatting from the CHPX bin table (`fcPlcfbteChpx`). It reads each Formatted Disk Page (FKP), walks the SPRM list per run using the `spra` operand-size encoding, and decodes bold (`sprmCFBold`), italic (`sprmCFItalic`), strike (`sprmCFStrike`), underline (`sprmCKul`), size (`sprmCHps`, half-points), and color (`sprmCCv` direct RGB, or `sprmCIco` palette index as a fallback). A floor search over the collected FC spans returns the effective style for any character, and adjacent characters that share a style are merged into a single run.
- `document.doc.DocParagraphProperties` decodes paragraph formatting from the PAPX bin table (`fcPlcfbtePapx`). The PAPX FKP layout differs from the CHPX one - 13-byte BxPap entries whose first byte is a word offset to a PapxInFkp, and each PapxInFkp begins with a length byte, a 2-byte style index (istd), then the SPRM list - so it is parsed separately. It decodes alignment (`sprmPJc80`/`sprmPJc`: left/center/right/justify) and left, right, and first-line indents (`sprmPDxaLeft`/`sprmPDxaRight`/`sprmPDxaLeft1`, twips converted to points; the first-line value is signed so hanging indents are preserved). Each paragraph resolves its style by its own file character position and maps to the shared `ParagraphStyle`.
- The result maps to the shared `RenderedDocument` model (A4 page, 28 paragraphs per page) and renders through the existing `FixedHtmlRenderer`, so `.doc` shares the `.docx`/HWP paging, search, and bookmark surface. Real table structure (cells are flattened to text) and inline images (omitted) are out of scope for this release.
- `DocumentPageActivity.loadWordPages` now detects an actual OLE2 compound file by its magic bytes (`D0 CF 11 E0 A1 B1 1A E1`) and routes it to the legacy reader; the previous explicit "unsupported" path for `.doc` is removed. A mis-named `.docx` still opens through the OOXML path.
- A JUnit test (`DocLegacyLayoutExtractorTest`) validates compound-stream access, text reconstruction (English, Korean, and mixed), character formatting (bold/italic/underline/size/color, including Korean runs), and paragraph formatting (alignment and indents) against real embedded `.doc` fixtures.
- The parsers are hardened against malformed or hostile files: the CFB reader bounds the DIFAT walk by the file's real sector capacity (not the header-supplied count) and breaks reference cycles so a crafted header cannot exhaust memory; sector and mini-sector offsets are computed in 64-bit to avoid overflow wrap; the FIB and piece-table walks range-check every file-controlled advance; and text reconstruction is capped so a crafted piece table cannot amplify a small file. A corrupt `.doc` fails with a clean error instead of crashing.

### Archives - Zstandard and LZ4 (tar family + single-file)

- `ArchiveSupport.Type` gains `TAR_ZST`, `TAR_LZ4`, `SINGLE_ZST`, and `SINGLE_LZ4`. `ArchiveTypeDetector` recognizes `.tar.zst`, `.tzst`, `.tar.lz4`, `.zst`, and `.lz4` (compound suffixes checked before the short ones, so `.tzst`/`.tar.zst` never fall into the single-file path, and the existing bare `.z`/`.taz` rules are unaffected). `FileUtils.isArchiveFile` and the output-base-name stripping lists carry the same suffixes, and numeric `.001` split parts resolve through the shared recursion.
- Android Zstandard uses the Zstd filter already compiled into `libarchive-android`: `.tar.zst` list/extract/single-entry and sequential comic paths route or fall back there without touching a desktop native, and plain `.zst` uses a raw/empty-format forward stream whose output passes through the existing decoded-byte safety cap. `LinkageError` from an optional Commons codec becomes a normal unsupported/fallback result instead of escaping the worker. LZ4 remains on `FramedLZ4CompressorInputStream` (pure Java, standard frame magic `04 22 4D 18`).
- `ZstdLz4ArchiveSupportTest` embeds real fixtures and verifies JVM list/extract behavior byte-for-byte. `zstd-jni` remains a `testImplementation` dependency solely for those desktop JVM fixtures; it is not packaged into the Android APK, so the fixture result is not used as Android-native evidence.

### Home screen - recent list swipe angle gate

- `ItemTouchHelper` starts a swipe whenever horizontal travel merely exceeds vertical travel, which lets drags up to 45 degrees off the horizontal slide a recent row. `MainRecentFilesController.attachSwipeToDismiss` now registers an observe-only `OnItemTouchListener` (added before the `ItemTouchHelper` attaches its own, so it sees each event first) that records the gesture's cumulative dx/dy from `ACTION_DOWN`, and `getSwipeDirs` returns 0 unless `|dx| >= 2 * |dy|` (`SWIPE_HORIZONTAL_DOMINANCE`, about 26.6 degrees). Because `ItemTouchHelper` consults `getSwipeDirs` only before selection, an already-started swipe is unaffected by later wobble; multi-select mode still disables swiping, and the 45% commit threshold and disabled velocity trigger are unchanged.

### Archives - 7z PPMd / BCJ2 / Deflate64 verification

- Coverage audit result: Commons Compress 1.28's 7z coder table includes Deflate64 (pure Java) but not PPMd or BCJ2; the bundled native libarchive reads PPMd and BCJ2 but rejects Deflate64 (codec 0x040109, "Unknown codec ID"). The two backends are therefore complementary and all three methods work on device through the existing routing: `listSevenZEntriesWithFallback`, `extractSingleSevenZEntryWithFallback`, `extractSevenZIntoDirectoryWithFallback`, and the sequential image reader's broken-mark degradation to whole-archive/single-entry extraction all fall back to libarchive on any dedicated-path IOException. No routing changes were needed.
- Empirical verification: self-made fixtures (`7z a -m0=PPMd`, `-m0=BCJ2 -m1..3=LZMA`, `-m0=Deflate64`, from first-party content) were extracted byte-identically by libarchive 3.7.2 (PPMd, BCJ2) and round-tripped by p7zip (Deflate64). New `SevenZMethodCoverageTest` embeds the fixtures: Deflate64 lists and extracts end to end on the JVM (SHA-256-verified content); PPMd/BCJ2 list correctly and single-entry extraction fails cleanly without partial output when no libarchive backend is present, with a bridge-availability guard so instrumented runs assert nothing false. Running these tests on a plain JVM exposed one real bug: `listSevenZEntries` iterated with `SevenZFile.getNextEntry()`, which eagerly builds each entry's decoder chain and throws for coders Commons Compress cannot decode (PPMd, BCJ2), so listing those archives only worked through the libarchive fallback. It now walks parsed header metadata via `SevenZFile.getEntries()` (decode-free), making PPMd/BCJ2 archives browsable on the primary path everywhere; extraction routing and password classification are unchanged (header-encrypted 7z still fails at header parse and prompts; AES content streams are built lazily and fail only on read, same as before).
- FOSS/provenance: PPMd/BCJ2 decoding stays inside the already-shipped BSD-licensed libarchive binary; its PPMd is Igor Pavlov's public-domain `Ppmd7.c` (based on Dmitry Shkarin's public-domain PPMd var.H). THIRD_PARTY_NOTICES now records this explicitly. No 7-Zip/libarchive source is copied into the repository and no new dependency is added.

### Archives - RAR/7z encryption boundaries sharpened against real fixtures

- The unsupported boundaries around RAR and 7z encryption were re-verified with real archives created in-sandbox (WinRAR 7.00 trial CLI for RAR5; p7zip for 7z), correcting two claims and one message:
- **Intermediate RAR5 `-hp` audit result (superseded later in the same 1.0.11 work):** bsdtar/libarchive 3.7.2 could not list a real WinRAR 7.00 header-encrypted RAR5 archive ("Encryption is not supported"), and no first-party RAR5 header decryptor existed at that checkpoint. `RarHeaderEncryptionDetector` gained `headerEncryptedRarVersion` so the temporary unsupported message distinguished RAR5 `-hp` from RAR4 `-hp`. The later “RAR5 header encryption (-hp) first-party decrypt” section records the implementation that closed this gap before release; current code treats a post-password RAR5 detector hit as a first-party parser failure, not as a missing decrypt capability.
- **RAR5 stored + password is now real-file verified.** `Rar5Crypto`'s KDF (UTF-8 password, single HMAC-SHA256 U-chain, 8-byte check folded from the 32-byte accumulator itself) reproduces the exact password-check bytes of a genuine `rar a -ma5 -m0 -p...` archive, and AES-256-CBC decryption of the stored payload is byte-identical to bsdtar's output. This upgrades the first-party RAR5 stored-encrypted path from "mirrored KDF" to "verified against a real WinRAR file". (RAR 7.00 no longer *creates* RAR4 archives - `-ma4` is rejected - so RAR4 stored+encrypted end-to-end remains a device-test item.)
- **Encrypted PPMd/BCJ2 7z: the failure is at the AES layer, not the coder.** Against real `7z a -m0=PPMd -p...` fixtures, libarchive fails with "The archive header is encrypted, but currently not supported" (`-mhe=on`, cannot list) or "The file content is encrypted, but currently not supported" (`-mhe=off`, lists but extracts nothing) - it never reaches the PPMd/BCJ2 stage, because libarchive has no 7z decryption at all. The earlier note ("libarchive decrypts the AES layer but then reports PPMd as unsupported") is corrected in `docs/RAR_7Z_SPLIT_ENCRYPTION_REVALIDATION_READWIDE_1_0_11.md`. App behavior is already correct (password prompt, then clean unsupported, no partial output); only the documented reasoning changed.

### Archives - EGG real-file correctness and split volumes

- Verification against genuine ALZip-created EGG files (rather than the previous synthetic fixtures, which had encoded a wrong layout) uncovered three bugs in `EggArchiveReader`: (1) the archive header prefix - extra fields such as Split/Solid terminated by an END field, present in every real file - was not parsed, so the top-level loop hit the prefix END immediately and rejected every real archive; (2) the END field that terminates a BLOCK header (after method/hint/sizes/CRC, before the data) was not consumed, so every `dataOffset` was 4 bytes early and CRC checks failed; (3) the LZMA block preamble was misread - the 9-byte preamble is 4 bytes of version/props-size words followed by the 5-byte LZMA properties (props at offset 4, dictionary size at 5..8), not props at offset 0. All three are fixed; legacy tolerances remain for the old synthetic layout (immediate FILE without prefix END, block data without a block END).
- Split volumes: new `EggVolumeInput` presents `name.vol1.egg` + `name.vol2.egg` + ... as one seekable logical stream (first volume whole, later volumes after their own header prefixes) without any temp-file copy, so multi-gigabyte split archives do not need double disk space. `EggArchiveReader.openVolumes` walks the chain by incrementing the `volN` filename number (zero padding preserved) and validating each volume's Split `prev` id against the previous volume's header id; a middle volume opened without its first volume, a missing volume, or a chain mismatch fail with clear errors and no partial output. `ArchiveSupport`'s existing `.volN.egg` -> `.vol1.egg` resolution feeds the reader, so opening any volume works. Block data straddling a volume boundary is handled by the segment-crossing positional reads, which also back the bounded streams handed to the decompressors.
- `EggArchiveReaderTest` fixtures were rebuilt to the real ALZip layout (prefix END, per-file extras END, block-header END) and split coverage was added: extraction across a volume boundary, opening from the second volume, and a missing second volume failing without partial output. The corrected layout and split semantics are recorded in `docs/EGG_FORMAT_NOTES.md` (recreated; the previous Javadoc reference had gone stale). Provenance: the reader remains first-party Java; real-file behavior was verified empirically and no third-party extractor code or fixtures were copied into the repository.
- ZipCrypto-encrypted EGG entries (EncryptMethod 0) now decrypt, verify the check byte before output, and keep one keystream across all file blocks. This paragraph records the intermediate ZipCrypto-only stage: the later AES section below supersedes its AES limitation. LEA remains unsupported in the final implementation.

### Archives - ALZ revalidation, split assembly, zero-size-nibble entries

- The ALZ reader was revalidated against real ALZip archives with the same methodology as the EGG pass. Result: the existing single-file logic was already correct - store, deflate ("high" preset = method 2), the bzip2 path, ZipCrypto entries, and CP949 names all CRC-verified. The `?` characters in one legacy fixture's name are literal `0x3F` bytes ALZip wrote into the CP949 name for characters the codepage cannot encode; emitting them verbatim matches ground truth.
- Two real defects were found and fixed. (1) Split archives were never assembled: `prepareArchiveForRead` resolved `name.aNN` to `name.alz` but the reader consumed only that first file, truncating everything past segment one. `AlzipArchiveReader` now resolves the segment set itself (`openAlzVolumes`): first segment minus its 16-byte trailer (`CLZ\1 + 8 B + CLZ\3` when more segments follow), each continuation minus its 8-byte header (`ALZ sig + version + segment id`, when the signature is present) and 16-byte trailer (`... + CLZ\2` on the last), presented through `SplitVolumeInput` as one seekable stream. Continuations must be contiguous from `.a00`; a gap or an undersized first segment fails with a clear error and no partial output. Verified (CRC) against the real 65,536 + 12,587-byte two-segment set (101,422 B payload). (2) Entries whose descriptor size nibble is 0 (directories, empty files - the method/CRC/size fields are physically absent) previously threw `unsupported` for the whole archive; they now parse as size-0 stored entries.
- Internals: `EggVolumeInput` was renamed `SplitVolumeInput` (shared by the EGG and ALZ readers; messages de-EGG-ed); `RandomAccessFileBoundedInputStream` became orphaned and was deleted. New docs: `docs/ALZ_FORMAT_NOTES.md`; `docs/EGG_FORMAT_NOTES.md` gained the encryption-field layout. Tests: EGG - correct/wrong/no password on store and deflate, and a two-block keystream-continuity fixture built by an in-test ZipCrypto encryptor; ALZ - split extraction across a segment boundary, opening from `.a00`, a missing segment failing without partial output, and a zero-size-nibble directory entry.

### Archives - ALZ bzip2 bitstream variant (ALZip 4.x)

- Real ALZip 4.x bzip2 (method 1) payloads use a trimmed bitstream variant, not standard bzip2: no `BZh` magic or block-size byte (900k implied), block framing `'D','L','Z',0x01` / end `'D','L','Z',0x02` instead of the 48-bit magics, and no per-block CRC or randomised bit - every subsequent bit shifts, so standard decoders fail at the first block header and the old "prepend `BZ`" repair could never apply to them. Added `AlzBzip2InputStream` - a modified Apache-2.0 copy of commons-compress 1.28.0 `BZip2CompressorInputStream` with the framing deltas and the CRC/randomised machinery removed (Apache-2.0 modification notice in the file; `THIRD_PARTY_NOTICES.md` updated) - and `openAlzBzipStream`, which sniffs the byte-aligned first bytes to route `DLZ` to the variant and `BZh`/headerless-`h` to the standard decoder, keeping the previously verified plain-bzip2 real file working. Integrity remains the container's per-entry CRC32. The variant facts follow the zlib-licensed unalz 0.65 (`UnAlzBz2decompress.c`); the transform used to build the embedded fixtures (one 900k single-block, one 4-block level-1 stream, plus a corrupted-stream case and a plain-flavor regression) was validated byte-identical through a compiled unalz before check-in. No unalz code was ported.

### Archives - solid EGG extraction

- Solid EGG archives (SOLID field in the archive prefix) now extract instead of being refused. Layout per the EGG Specification solid example: all FILE headers first, then the archive's blocks, whose decoded concatenation carries every file's data back to back in header order. `extractSolidArchive` does one sequential pass through `writeBlock` into a `SolidEntryWriter` that splits the stream by declared entry sizes (path-traversal-rejected names still consume their bytes to keep alignment); each block's CRC32 over its decoded bytes is verified exactly as non-solid. `extractSolidSingle` decodes from the stream start, discards to the entry offset, and stops when the entry is written (a `SolidRangeDone` control signal; blocks fully consumed en route are CRC-checked). Undersized streams fail with "Solid EGG stream ended before entry data" and no partial output. Validated black-box against ESTsoft's unegg 0.5 binary: first-party deflate fixtures with one block and with a block boundary inside a file both extract byte-identically (unegg's own store-coder pop-size and early-CRC quirks, plus the spec example's CRC typo - `9E 83 48 6D` is CRC32 of "ab", not "abc" - are documented in `docs/EGG_FORMAT_NOTES.md`). Encrypted solid remains unsupported.

### Archives - AES-128/256 EGG decryption

- The EGG Encrypt field's methods 1/2 are the WinZip AES construction - confirmed both by the field's byte counts in the spec (salt 8/16 + verifier 2 + footer 10) and black-box: unegg links Gladman's `fileenc`, and first-party archives built to the scheme (AES-128/256, store and deflate) decrypt byte-identically through ESTsoft's own binary with the password. `EggWinZipAesCrypto` implements it with JCE only (`Cipher AES/ECB` for the CTR keystream, little-endian counter from 1; `Mac HmacSHA1`; first-party PBKDF2-HMAC-SHA1 x1000 over raw password bytes for byte-exact control). One context per file: keystream and MAC continue across blocks; the 2-byte verifier gates before any output ("Invalid password"), the 10-byte ciphertext HMAC is checked at file end ("EGG AES data authentication failed"), and non-ASCII passwords retry as MS949 bytes. Note: unegg finalizes the footer MAC after *every* block, so real ALZip AES files are single-block; whole-file verification is equivalent there and stricter beyond. `writeBlock`'s decryptor hook was generalized (`BlockDecryptor` interface) with ZipCrypto unchanged behind an adapter. Oracle-validated fixtures (including a zero-block-CRC one proving the HMAC alone rejects tampering) are embedded in `EggArchiveReaderTest`. LEA stays unsupported by name; solid+encrypted stays refused.

### Image preview - direction-aware prefetch for sequential archives

- Investigated the RAR continuous-paging stutter across the whole pipeline: SequentialArchiveImageReader (shared libarchive forward stream, on-demand-priority via onDemandReaderWaiters, behind-frontier single-entry policy), ArchiveImageEntryCache (whole-archive bulk once + WHOLE_ARCHIVE_BULK_DONE), ArchivePreviewCache caps (256M/64M, other-archive pruning), and ImageReaderActivity (imageLoadGeneration on-demand guard, +-3 prefetch, bitmap cache instant path). The design is sound for isolated turns; the gap is sustained turning: the plan was re-issued symmetric +-3 on every turn with no direction memory and no staleness cut, so the stream idled between turns and stale plans walked no-op offsets. New `com.readwide.manager.util.ImagePrefetchMath` (pure Java, 5 tests): updateStreak (consecutive +-1 turns; jumps/multi-page reset), sustainedDirection (threshold 2), extractionOffsets (neutral = historical {1,-1,2,-2,3,-3}; sustained = dir,-dir,2..8*dir - one page behind kept), bitmapOffsets (always nearest +-3; decoded bitmaps cost memory, extracted files do not). ImageReaderActivity: pagingStreak updated in showImageAtIndex, prefetchPlanGeneration bumped per plan and checked per offset (stale plan exits), extraction loop uses extractionOffsets with bitmap warm-up gated to |off|<=3. Waiter-yield and behind-frontier rules unchanged. Not implemented (deliberate): first-party RAR forward reader for -hp/encrypted archives where libarchive streaming fails - those fall back to the existing whole-archive bulk path, which already amortizes; revisit only if device profiling shows it hot.

### Image preview - numeric split archives no longer re-concatenate per page

- An efficiency audit of the archive image preview path found that viewing images inside a numeric split archive of a random-access type (`comic.zip.001` + `.002` + ... , including `.cbz.001`) paid a full multi-volume concatenation *per entry*: every read path opens such archives through `prepareArchiveForRead`, which combines all volumes into a temporary file, so each page turn and each first-visit prefetched neighbor re-wrote the entire archive to disk, extracted one image, and deleted the temp - O(pages x archive size) of I/O over a read-through. The image entry cache now treats random-access numeric splits like the formats without cheap per-entry access: the first image access runs one whole-archive pass (one concatenation, every image into the preview cache), and after that bulk succeeds it downgrades to single-entry extraction for cache-evicted refills (one concatenation for one page beats re-running the bulk), mirroring the existing RAR pattern including the size/mtime-keyed `WHOLE_ARCHIVE_BULK_DONE` marker. Splits of the sequential formats (`name.7z.001`, `name.tar.gz.001`) are deliberately excluded - type detection maps them to their inner sequential types (verified standalone: `.zip.001`/`.cbz.001` -> ZIP, `.7z.001` -> SEVEN_Z, `.tar.gz.001` -> TAR_GZ), and those already take the forward-reader-first sequential path whose one open pays the volume handling once per session. All degradation paths are bounded by the old behavior: if the bulk pass fails (extraction safety limit, free space), the code falls through to the previous per-entry path unchanged. `ArchiveSupport.isNumericSplitArchive` was added as the public predicate (the detector itself is package-private). Also reviewed and left as-is, with reasons: per-entry archive reopen for plain (non-split) ZIP during paging (milliseconds of background work per entry; a session-scoped open-archive cache would add file-handle lifecycle risk for marginal gain), the decoded-bitmap LruCache and +-3 prefetch window (already generation-cancelled, reader-yielding, and heap-budgeted), and preview decode downsampling (already bounded to display-resolution multiples with a 16 MP cap).

### Documents - bottom-toolbar TTS button

- `activity_document_page.xml`: `btn_document_tts` after the settings button (ic_bottom_tts + tts_toolbar string reused from the text reader), `visibility=gone` initially. `DocumentPageActivity`: listener -> existing `showDocumentTtsDialog()` (More-dialog row kept), `updateDocumentTtsButtonVisibility()` (documentSupportsTts(): pages ready, not Markdown) called after wiring and from `showPage` - the funnel every document passes once pages exist; button added to the onConfigurationChanged tint array. PDF viewer untouched: `PdfReaderActivity` does not implement `TtsHost`, so "applicable viewers" = the document page viewer only.

### TXT reader - stale background restore after file switch (PR #10 direction, manual)

- Root cause: `trimReaderMemoryForBackground` stores `backgroundTextRestoreIntent` + sets `backgroundTextMemoryReleased`; `onNewIntent` switched files without clearing either, so the next `restoreReaderAfterBackgroundMemoryTrimIfNeeded()` replayed file A over file B. Fix in three layers. (1) `ReaderMemoryController.discardTransientRestoreStateForNewLoad()` (delegate on `ReaderActivity`): cancels the pending trim runnable, clears `backgroundTextMemoryReleased`/`backgroundTextRestoreIntent`, `clearLoadedTextSnapshot()`, `clearLargeTextPartitionSwitchPending()`, `clearLargeTextQueuedPageDelta()`. (2) `onNewIntent` order: `saveReadingState()` (no-op when trimmed - the trim already saved) -> discard -> `setIntent` -> existing clears -> `loadFileFromIntent`. (3) Restore guard: before executing, the restore intent's `EXTRA_FILE_PATH`/`EXTRA_FILE_URI` must match the current intent's via new pure-Java `com.readwide.manager.util.ReaderRestoreTargetMath.matchesRestoreTarget` - strict fallback: explicit current path/uri is authoritative (loaded `filePath` is consulted only when the current intent has no target, so a mid-switch stale `filePath`=A cannot pass A's restore when current=B); on mismatch the stale state is discarded and no restore runs. PR #10 fetch was blocked (patch-diff domain + API rate limit + robots), implemented from the spec. Item 5 (large-TXT pending exact page anchor at autosave): already satisfied in 1.0.11 - `getDisplayedCurrentPageNumber()` routes through `LargeTextPageModelMath.displayedCurrentPage` with `largeTextPartitionSwitchState.pendingDisplayPage()` during in-flight switches, and both `saveReadingState` and the trim use it; no change made. 9 unit tests in `ReaderRestoreTargetMathTest` incl. `loadedFileFallbackDoesNotOverrideExplicitCurrentMismatch` and a path-vs-uri dimension-mismatch case. Also fixed: `AlzipArchiveReaderTest` corrupt-bzip2 test caught `IOException` from `ArchiveSupport.extractSingleEntry`, which reports failure by return value - now asserts `assertFalse` (this was the `testDebugUnitTest` compile failure).

### Tests - stale 7z coverage expectations flipped to first-party success

- `SevenZMethodCoverageTest` still asserted that BCJ2/PPMd single-entry extraction fails cleanly without libarchive - written before the first-party path existed, with a javadoc note predicting exactly this: "if a future upgrade adds these coders, extraction will start succeeding; revisit these assertions then". Flipped both to `assertSingleEntryExtractsFirstParty` with pinned payload hashes (doc1.txt reuses DOC1_SHA256; prog.bin pinned at 314ce04b...d444, 3,200 B, computed from the embedded fixture and verified through the first-party reader in-session). Class javadoc updated; unused assertFalse import removed. This was the `testDebugUnitTest` 2-failure.

### Archives - RAR4 legacy names join the corpus; 7z needs none

- `readRar4Entries` two-pass: walk unchanged (pass-1 parse keeps historical dataSize/seek semantics exactly, including the null-entry corner), `Rar4FileBlock` triples collected, corpus built via `extractRar4LegacyRawName` (fixed 25-byte pre-name layout, +8 when LARGE; Unicode-flagged names excluded), pass-2 re-parse with corpus replaces the result list. `decodeRar4Name` legacy tail: strict UTF-8 (unchanged) then `ArchiveFilenameDecoder.decodeLegacyName(plain, corpus)` replacing the hardcoded IBM437 (IBM437 stays a scoring candidate, so DOS-Latin archives decode as before). Validated with a synthetic RAR4 (RAR 7.00 cannot create RAR4): stored entries, CP949 names incl. one-syllable "가.txt" - listing + extraction byte-exact through `RarArchiveReader`; -hp suite and RAR unit tests no-regression. 7z: names are UTF-16LE by spec (`readUtf16Name`), RAR5 names UTF-8 by spec - no legacy path exists, nothing to wire.

### Archives - archive-wide filename charset detection (name corpus)

- Question answered: the TXT-side `TextEncodingDetector` cannot be reused directly for archive names (ICU/Mozilla statistical detection needs document-scale samples; filenames are 5-50 bytes; Android-bound vs pure-Java archive layer), but its two principles transfer. (1) Corpus judgment: new `ArchiveFilenameDecoder.NameCorpus` - readers observe all legacy-path raw names, resolution picks the one charset (UTF-8 + 18 legacy candidates) decoding *all* of them with the highest summed score, every name then decodes with that decision. Precedence unchanged: ASCII > UTF-8 flag/validity > EGG locale hint > corpus > per-name fallback. (2) Naturalness: position-bound structure checks - Thai combining marks must follow consonants + leading vowels need consonants + consonant-only runs are fake (also fixed: legal vowels U+0E30/32/33/45 were counted suspicious); Greek final-sigma word-final-only + tonos capitals word-initial-only + block non-letters suspicious + tonos flat bonus 120->50 gated; Russian markers (ёыэюяйщ) +8/ea +60 for Cyrillic code pages but word-initial ё/ы/й cancels (real Russian never; misreads always); bicameral case chaos (lower->UPPER within Cyrillic/Greek words) -40/ea, Latin exempt (camelCase). Measured failures fixed: CP949 1-syllable -> Thai, IBM866 -> windows-874 at any length (11/char vs 9/char ratio), GB18030 -> MS949 fake-Hangul flip; the 1253 tonos-farm (+120/name from CP949/1251 bytes) and the KOI8 marker-farm ("ЖэЙЕКОР", legal-position markers but impossible case pattern) were the counter-attackers killed by the structure checks. Wiring: ZIP central-dir + tail-scan (two-pass in `ArchiveSupport`), ALZ (`PendingAlzEntry` holder, decode after scan, `decodeAlzName` removed), EGG (raw FILENAME payload kept on entry, decoded post-scan; locale-hinted names still feed the corpus as evidence). Harness: 8 corpora byte-exact + genuine Thai/Greek/Turkish guards + per-name 866 + EGG E2E with hint-free MS949 names; 9 pre-existing + 4 new unit tests pass.

### Archives - RAR5 header encryption (-hp) first-party decrypt

- `RarArchiveReader.readRar5Entries` now handles the archive encryption header (type 4): parse version/flags/kdfCount/salt + optional 12-byte check record (8 check bytes + SHA-256 guard; inconsistent records ignored, matching unrar), derive secrets via the existing `Rar5Crypto.deriveSecrets`, verify the check (mismatch -> `PasswordRequiredException` re-prompt), then read subsequent headers via new `readRar5EncryptedHeader`: 16-byte IV + AES-256-CBC ciphertext of (crc32 + size vint + header), 16-aligned; CRC verified on decrypted bytes, and a CRC failure on a header-encrypted archive is surfaced as a password problem (wrong key is the overwhelmingly likely cause). Data offsets account for IV + alignment so the block-walk seek stays valid. Everything downstream was already in place - `Rar5CompressedArchiveExtractor.readPackedPayload` decrypts per-file records, `Rar5CompressedDecoder` decodes, solid/split/volume machinery unchanged - so the diff is one loop change plus three helpers. Validated against UNRAR 7.00: `-hp` x {`-m0`, `-m3`, `-m5`, solid 3-entry, `-v20k` 3-volume with split entries}, 한글 파일명, 84 KB mixed payload - all byte-exact; no-pw/wrong-pw re-prompt. Fixture note: the RAR CLI mangles non-ASCII names to `?` when run without a UTF-8 locale (`LANG=C.UTF-8` required at *creation*; verified with unrar that the first bad fixture was mangled at rest, not by the reader). `RarHeaderEncryptionDetector` message updated (RAR5 branch now indicates a parser gap, since capability exists). Fixtures embedded in `Rar5HeaderEncryptedArchiveTest` (2,910/366 B, payload SHA-256 pinned, password `speak`). Stale docs corrected: revalidation doc's RAR5 `-hp` "hard boundary" and 7z "encrypted PPMd/BCJ2 out of scope" paragraphs now point at the closing docs.

### Archives - 7z PPMd first-party decoder (plain and AES)

- New `SevenZPpmd7Decoder`: PPMd var.H (Ppmd7) with the 7z range coder, ported from the public-domain Ppmd7 reference (Shkarin 2001 / Pavlov, both public domain - obtained from pyppmd's sdist, headers carry the notices verbatim; Apache-2.0 compatible, recorded in `THIRD_PARTY_NOTICES.md`; the no-UnRAR/libarchive/7-Zip-*licensed*-code rule is not implicated). Flat byte-array memory model mirroring the reference layout - contexts 12 B, states 6 B, refs as offsets, one-state union at ctx+2 - because the sub-allocator (38-index unit table, SplitBlock, the free-block glue pass building a doubly-linked node list inside the free blocks, restart-on-exhaustion) determines *when the model resets*, and encoder/decoder must reset at the same symbol. 7z range decoder (init byte 0 + 4-byte code, bottom normalization at 2^24) - distinct from RAR's, which is why the existing first-party RAR PPMd could not be reused. Debug history: a Python reference hit 29-symbol divergence last session; against the public-domain source the root causes were pinned - `CreateSuccessors` up-state frequency is `1 + (5*cf > s0)` in the 2cf<=s0 branch (not 2cf>s0), missing `numPs==0` early return, the masked-decode loop must stop after exactly `NumStats - numMasked` unmasked states (the encoder runs the same early-stop walk), text-successor detection is `fSuccessor <= REF(Text)` (not `< UnitsStart`), and MakeEscFreq's suffix-NumStats difference is unsigned (wraps). After fixes, byte-exact across orders 2-32, mem 64 KiB-1 MiB, five payload classes; instrumentation confirmed 8 restarts/7 glues/7 rescales/~1200 rare-allocs exercised on the hardest fixture. Java port re-verified on all 10 fixtures (40 KB order-32 random: 101 ms). Wired as a PPMd case in `SevenZBcj2ArchiveReader.runCoder` with gating widened to `archiveUsesSpecialCoder` (BCJ2 or PPMd); `ArchiveSupport` try-helpers now catch non-password failures and return null so the libarchive fallback still runs (no regression surface for previously working unencrypted PPMd). Fixtures embedded in `SevenZPpmdArchiveTest` (plain `-mhc=off` + AES `-mhe=on`, 743/849 B, payload SHA-256 pinned). Order 2-64 and mem 2 KiB-256 MiB accepted; outside that, a clean unsupported error. AES+PPMd with encrypted header - previously impossible on any path - now extracts byte-identically.

### Archives - 7z BCJ2 first-party reader (plain and AES)

- BCJ2 (coder `03 03 01 1B`) is a four-input 7z branch filter Commons Compress cannot decode. `SevenZBcj2ArchiveReader` adds a clean-room first-party container/coder path, including covered AES-encrypted headers/folders and BCJ2 chains, with reference-tool-verified fixtures and clean wrong-password failure. The older closing sentence in this development record that left PPMd on native and AES+PPMd unsupported is superseded by the immediately preceding first-party PPMd section; both PPMd and BCJ2 use their gated first-party paths in the final implementation. See `docs/SEVENZ_BCJ2_READER_READWIDE_1_0_11.md`.

### Documents - HWP paragraph formatting

- `DocumentHwpLayoutExtractor.hwpParagraphStyle` previously read only horizontal alignment from a binary `.hwp` paragraph's ParaShape; the `.hwpx`, `.docx`, and `.doc` paths already applied indents and line spacing. It now also maps left/right margins, first-line indent, paragraph space before/after, and line spacing onto the shared `ParagraphStyle`.
- Unit handling follows a documented quirk of the binary format: ParaShape margin, indent, and paragraph-space fields are stored as HWPUNIT (1pt = 100) multiplied by 2, so points are `value / 200`. This is cross-confirmed by neolord0's hwp2hwpx converter (which divides exactly these fields by 2 when emitting HWPUNIT values) and by pyhwp's binmodel (`doubled_margin_left # 1/7200 * 2`).
- Hanging indents use the HWP storage convention (left margin is where the first line starts; a negative indent pushes the body lines right), which differs from the CSS/Word convention, so the hang is folded into `margin-left` with a negative `text-indent` - the same shape the `.doc` path produces from `dxaLeft`/`dxaLeft1`.
- Line spacing selects the version-correct pair: files at format 5.0.2.5 or later carry it in `lineSpace2` with the type in property3, older files in `lineSpace` with the type in property1. Percent spacing maps to a line-height multiplier (clamped 0.75-3.0); fixed spacing (doubled HWPUNIT) maps to a fixed line height in points; "margin only" and "at least" spacing have no direct CSS equivalent and keep the default.
- All values are clamped to sane ranges so a corrupt ParaShape degrades to default styling instead of producing absurd layout. Table cell paragraphs resolve through the same path, so cell-level paragraph shapes are honored too.

### Read-aloud (TTS) - Markdown documents, with approximate scroll-following

- Markdown read-aloud was previously excluded in the document viewer (`documentSupportsTts()` returned false for `isMarkdownDocument()`), and although the TTS-resume prompt routed Markdown to the text reader, normal opening routes it to `DocumentPageActivity` - so in practice Markdown had no read-aloud entry point. It is now enabled. The text-extraction half is shared with Word for free: Markdown populates `pages` with a single rendered `Page`, so `DocumentTtsTextSource.build` produces one plain-text buffer via `htmlToPlainText`. The page-following half is Markdown-specific because there is no page list to advance (one long WebView page scrolled by `markdownVisualCurrentPage`). Following is "approximate": a new pure helper `MarkdownTtsFollowMath.approximateSourceOffset` maps the spoken char position (in rendered plain-text space) back to a raw `markdownSourceText` offset by pulling a short word-run probe around the position and locating it in the source (case-insensitive, whitespace-normalized so wrapped source still matches), falling back to a proportional estimate when the probe can't be found; the resulting offset drives the existing `scrollMarkdownToSourceOffset` (JS `__rwMdScrollToOffset`, with a proportional visual-page fallback). Because the controller only calls `ttsJumpToAbsoluteCharPosition` at page-prefetch boundaries - which never fire for Markdown's single page - the per-segment `DocumentTtsTextSource.setTtsHighlightRange` callback (previously a no-op) now routes to `onDocumentTtsSegmentSpoken`, which scrolls only when the spoken position crosses roughly half a visual page (throttled so it doesn't jerk every sentence). `MarkdownTtsFollowMath` is covered by `MarkdownTtsFollowMathTest`.
- Auto-start-on-resume was added to the document viewer so "continue reading aloud" works for Markdown/EPUB/Word, not just plain text. `DocumentPageActivity` gained an `EXTRA_AUTOSTART_TTS` extra; when set, `loadFromIntent` polls until the document is ready, builds the text buffer off-thread, and calls the controller's new public `autoStartOrResume`, which resumes from the exact saved char position (scrolling Markdown to follow via the same approximate mapping) when saved state matches the file, or starts from the top otherwise. The main-screen resume prompt now routes Markdown/EPUB/Word to `DocumentPageActivity` with this extra, so the saved char position stays in the same buffer coordinate space it was saved in (previously Markdown resumed in the text reader, a different coordinate space, which would have mis-seeked).

- Post-review corrections (found in a self-audit before release, none shipped):
  (1) *Aggressive pause reduction deleted every pause* - the transform converted
  sentence stops to commas and then removed all commas, including the ones it
  just created; the order is now commas-first, then stops-to-comma, so a
  comma-length pause survives at old sentence boundaries (`TtsSegmenterTest`
  locks both directions). (2) *Markdown resume/restart spoke from the top* -
  `DocumentTtsTextSource.getCurrentCharPosition()` answered the page-start table,
  which is always 0 for Markdown's single page, so playback ignored the resume
  jump; Markdown now tracks an explicit speech anchor in plain-text space
  (advanced per spoken segment, set on jumps, reset per document; a fresh start
  reverse-maps the current scroll position proportionally so read-aloud begins
  near what is on screen). The anchor also makes the end-of-document state
  reachable, closing a potential full-document re-read loop: Markdown's
  displayed pages are *visual* (scroll) pages, so `canAdvancePage()` could stay
  true after speech finished, and the advance path would have restarted from
  char 0. (3) *Per-segment follow did a full source scan* -
  `approximateSourceOffset` searched the whole Markdown source from index 0 on
  every spoken segment (O(source) on the UI thread per sentence, and repeated
  phrases snapped the view backward to the first occurrence); the search is now
  windowed (+-16 K chars) around the last known offset (falling back to a window
  around the proportional estimate, then to the estimate itself), which bounds
  the cost and biases duplicates to the nearby occurrence. (4) *Autostart
  races* - the pending-autostart flag now resets when a new document loads, and
  the retry that waits for an in-flight buffer build checks the load generation,
  so switching documents mid-resume can no longer auto-play the new document.
- The seven phrase-length / pause-reduction strings are translated in all 21
  locales (the unused `tts_phrase_length` base label was removed instead of
  translated); fastlane changelogs exist for en-US and ko-KR only, as before.

### Read-aloud (TTS) - toolbar button in every viewer, standardized next to bookmark

- The PDF viewer gained a direct bottom-toolbar read-aloud button (`pdf_tts`), wired to the same `showPdfTtsDialog()` entry point as the More-dialog row and gated visible only once the PDF is loaded with pages (`updatePdfTtsButtonVisibility`, driven from the `updatePageStatus` funnel, which runs on the UI thread via the post-load `openPdfFile`). It is added to the theme-tint button array so its icon/label follow the reader theme. Across all three viewers the read-aloud button's default position is now immediately right of the bookmark button: this is set in `ButtonOrderManager.defaultItems` (the runtime source of truth for toolbar order, not the raw layout XML), where `tts` was moved up in the TXT group and added after `bookmark` in the document and PDF groups (both previously left the TTS button unmanaged/XML-positioned). The layout XML orderings were aligned to match. Because `ButtonOrderManager` is user-customizable, existing users with a saved custom order keep it (the newly-managed `tts` item appends for them); new users and anyone who resets get bookmark -> read-aloud. The button also now appears as a reorderable/hideable item in the document and PDF button-order settings, which it wasn't before.

### Read-aloud (TTS) - phrase length and pause reduction (issue #7)

- Two text-level TTS controls were added in response to issue #7, which reported that high-latency neural engines (Kokoro via VoxSherpa/sherpa-onnx) over-pause at punctuation and stall on long chunks. Both stay strictly within the existing architecture - Readwide delegates playback to the Android `TextToSpeech` engine and never touches the audio buffer - so these are text transforms applied in `TtsSegmenter` before `speak()`, not audio DSP. Phrase length maps a stored 0/1/2 level to a target chunk size (`TtsSegmenter.phraseLengthToChars`: 200/400/700 chars; Long = the prior default) that `ReaderTtsController` reads via `ttsSegmentChars()` at both the page-queue and next-page-prefetch call sites. Pause reduction (0/1/2) is applied in `normalizeForSpeech`: Medium drops commas, Aggressive also rewrites sentence-final `.!?` to comma-length pauses; the standard cleanup (ellipsis muting, underscore/semicolon handling) still runs at every level, and the sentence-boundary chunker runs on the original text before the transform so splitting is unaffected. Both surface as tap-to-cycle rows in the TTS dialog and are covered by `TtsSegmenterTest`. The audio-domain requests from the same issue (silence trimming, crossfading, PCM double-buffering, and full-book audio-file export to Opus/M4B) were intentionally not implemented: they require owning the synthesized PCM buffer, which would mean embedding a TTS engine and encoder into the app - breaking the no-dependency, offline-by-default, Apache-2.0 model and turning a reader into a TTS host.

### Read-aloud (TTS) - locale-fallback silence and page-boundary gap

- `ReaderTtsController.applySelectedLanguage` treated any `TextToSpeech.setLanguage` result of `LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED` as fatal and stopped before speaking, which made read-aloud silently do nothing on engines that report most locale queries as unsupported while still speaking through their own default voice (typical of neural engines such as sherpa-onnx/VoxSherpa exposing Kokoro or Piper voices). The failure path now falls back in order: the engine's default voice locale (`getDefaultVoice().getLocale()`), then the device default locale, and only reports an error if every step fails - so Play produces audio on those engines instead of nothing.
- Continuous read-aloud had an audible gap at every page boundary: the last utterance of a page finished, the page turned, and only then were the next page's sentences segmented and queued, which on high-latency neural voices left seconds of silence. `maybePrefetchNextPageSegments` now runs when continuous mode is on and the document's text is fully in memory (`!largeTextEstimateActive`): while the current page is still speaking, the first `PREFETCH_NEXT_PAGE_CHARS` (1400) characters of the next page are read from `getTextContent()`, segmented, and appended to the same engine queue with `QUEUE_ADD`, so synthesis continues across the seam. The first prefetched utterance's `onStart` drives the visible page turn via `jumpToAbsoluteCharPosition` (scroll-only) and re-arms the next prefetch through `handler.post`. State is two fields - `prefetchedNextPageBoundaryIndex` (-1 = none) and `crossedPrefetchBoundary` - reset on every new page queue and on resume (resume plays the surviving queue and then uses the existing page-advance fallback; it does not prefetch). A `speak` ERROR on a prefetch utterance rolls back only the prefetch (non-fatal). The lazily paged very-large-text path is untouched.
- Diagnosis note for the GitHub report: the reported "per-sentence synchronous loop" was not the cause - within a page, sentences were already buffered onto the engine queue with `QUEUE_ADD`. The real gaps were the locale silence above, the page seam above, and the fact that EPUB/Word/PDF viewers have no TTS wiring at all (addressed separately).

### Read-aloud (TTS) - wired into the document viewer (EPUB/Word/HWP)

- Stage 1 of the design in `docs/TTS_STATUS_AND_EPUB_PDF_DESIGN_READWIDE_1_0_11.md` (behavior-preserving decoupling): `ReaderTtsController` no longer types against `ReaderActivity`. It now holds a `TtsHost` (prefs, dialog styler, dp conversion, text source, paging, char jump, displayed page numbers, the two flags that gate prefetch and the partition-wait retry, floating-card refresh, remote-command routing, notification target kind) plus a plain `AppCompatActivity` for Context plumbing. The text/position/highlight surface is the new `TtsTextSource` (`getTextContent`/`getCurrentCharPosition`/`getCharPositionAfterCurrentVisibleContent`/`setTtsHighlightRange`/`clearTtsHighlight`), which `CustomReaderView` already satisfied verbatim. `ReaderDialogStyleController` was generalized the same way behind `ReaderDialogStyleHost` (theme snapshot get/set, ThemeManager, dp, optional BookmarkManager), and `TtsSleepTimerDialog`/`TtsDialogViews` build against `TtsHost`. `TtsPlaybackBridge` registers a `TtsHost`; `TtsPlaybackService` gained a `host_kind` extra so the notification tap opens the owning viewer (ReaderActivity or DocumentPageActivity) with the file path. `ReaderActivity` implements both interfaces as one-line public wrappers over the exact members the controller previously reached into, so the text/Markdown path is unchanged by construction.
- Stage 2: `DocumentPageActivity` implements the same two interfaces and owns its own `ReaderTtsController`. `DocumentTtsTextSource` concatenates `FileUtils.htmlToPlainText` (now public; previously private to document search) over the loaded `pages` with per-page start offsets and a hard `\n` separator per page, so absolute char positions map to page indices in both directions (binary search) and the segmenter never fuses sentences across a seam. The controller's "visible page" is the whole current document page; the buffer is fully resident, so the 1.0.11 cross-page prefetch works unchanged, and the prefetch-boundary `onStart` drives the actual WebView page turn. The buffer is built on the document executor on first use (guarded by `loadGeneration` against reloads) and dropped in `loadFromIntent`; the entry point is a read-aloud row in the More dialog, hidden for Markdown-as-document (different visual-paging model; full TTS exists in the text reader) and shown for EPUB/Word/`.doc`/HWP/HWPX. Highlight is a documented no-op in this version (`window.__rwDocBlocks` sentence-to-block mapping is the follow-up), there is no floating card (dialog + notification control), and `MainActivity`'s resume prompt routes document formats to `DocumentPageActivity` (in-dialog resume row; autostart stays text/Markdown-only).

- Stage 3: `PdfReaderActivity` implements `TtsHost`/`ReaderDialogStyleHost` and owns its own `ReaderTtsController`, same pattern as stage 2. Because `PdfRenderer` has no text layer, text comes from PdfBox (`com.tom-roush:pdfbox-android`, already bundled for PDF search): `PdfPlainTextExtractor` runs one `PDFTextStripper` pass (mirroring `PdfTextSearchEngine.DocStripper`, but text-only - no glyph rects) and returns page index -> plain text, opening and closing its own short-lived `PDDocument` rather than holding a second handle for the reader's lifetime. `PdfTtsTextSource` builds the same page-indexed buffer as `DocumentTtsTextSource` (per-page start offsets, `\\n` separator, binary-search `pageIndexForChar`), extracted once on the PDF executor on first read-aloud open and paging via `goToPage`. `hasAnyText()` gates scanned/image-only PDFs to a "no selectable text" message instead of silent playback. `TtsPlaybackService` gained a third `host_kind` (`HOST_PDF`) so the notification reopens `PdfReaderActivity`; `MainActivity`'s resume prompt routes `.pdf` there. The buffer math (missing pages, image-only pages, out-of-range clamps, grow-beyond-reported-count) is covered by `PdfTtsTextSourceTest` via a map-based `build` overload and was cross-checked against a 3000-case Python model. Glyph highlight on the bitmap page is deferred (PdfBox exposes the rects the search overlay already uses, so it is feasible later); v1 is audio + page follow.

### Documents - reflowable EPUB image viewport fit

- Reflowable EPUB pages gain an image-fit rule in the injected reader-theme CSS: `img,svg,video{max-width:100% !important;max-height:98vh !important;height:auto;object-fit:contain}` (plus `svg{width:auto}`). Width alone was already capped, but a cover or illustration wider than the screen was scaled to full width and could then run several screens tall - the tablet report. Capping both axes with `height:auto`/`object-fit:contain` preserves aspect ratio whether the book sizes images by attribute or by CSS; the style is injected after the book's stylesheets so equal-specificity book rules lose, and `!important` on the caps beats the common `img{max-width:none}` reset. Fixed-layout EPUB returns early with its own centering CSS and is untouched, as are Word/HWP pages (the rule is emitted only for `docType == "EPUB"`).

### Documents - HWP/HWPX unrenderable-picture placeholder

- HWP/HWPX raster image rendering (ControlPicture -> DocInfo BinData, and hp:pic -> binaryItemIDRef -> manifest/stem -> data URI, for PNG/JPEG/GIF/BMP/WebP) was already implemented and tested. The gap this closes is the silent-drop path: when a picture references a real embedded binary with no WebView-decodable raster form (WMF/EMF/OLE), `imageDataUri` returned null and the whole picture was discarded, so the reader saw nothing where an image belonged. `RenderedImage` gains an `unrenderablePlaceholder` flag (and a `RenderedImage.placeholder(w,h)` factory); the `.hwp` path emits it when `binData` bytes exist but sniff as non-raster, and the `.hwpx` path tracks `unrenderableBinaryItemKeys` (base/stem plus manifest-id) during `loadHwpxBinaryImages` so an `hp:pic` reference to a non-raster item resolves to a placeholder rather than null. `FixedHtmlRenderer` renders it as a dashed `.rw-image-missing` frame at the picture's authored size with a language-neutral framed-picture glyph (U+1F5BC via CSS `content`), so no new translated string is introduced. Pictures with no bytes at all are still skipped. A JVM test builds an HWPX with an EMF BinData and asserts the placeholder frame renders at the authored 180x120pt with no `data:image` leakage.

### Archives - RAR5 encrypted-compressed extraction regression fixture

- Added `Rar5EncryptedCompressedFixtureTest`, a real-fixture end-to-end regression for AES-256 encrypted + compressed RAR5 - the combination libarchive cannot handle at all (it does not decrypt RAR5), so the first-party `Rar5CompressedArchiveExtractor` (AES-CBC via `Rar5Crypto` + `Rar5CompressedDecoder`) is the only route. The embedded archive is a genuine WinRAR 7.00 `rar a -ma5 -m5 -pReadwide2026` file with two method-5 compressed text entries; it was cross-checked with `unrar t` ("All OK") and, at the crypto+decode level, byte-for-byte against a Python AES-CBC mirror feeding the real `Rar5CompressedDecoder` (both entry CRCs matched their originals) before embedding. The test asserts listing, whole-archive extraction (byte-for-byte on both entries), single-entry extraction, and that a no-password attempt fails cleanly (PasswordRequiredException or a clean IOException, never silent garbage) rather than producing partial output. This locks in that encrypted+compressed RAR5, which has no libarchive fallback, keeps working.

### Implementation notes

- The compressed (8-bit windows-1252) `.doc` piece path was subsequently validated against a genuine old-Word file (Apache Tika's `testWORD.doc`) by mirroring the full pipeline in Python; the bundled LibreOffice-generated fixtures still exercise only the UTF-16 path in unit tests.
- The HWP ParaShape change cannot be empirically validated in the development sandbox (hwplib is a Java library); it was written against the hwplib reader/object source on GitHub and cross-checked against hwp2hwpx and pyhwp as above. Final verification is the local build plus on-device testing with real `.hwp` files.

## Readwide 1.0.10 - 2026-06-29

### Release scope

- Android metadata is `versionCode 10010` and `versionName "1.0.10"`. It keeps the `com.readwide.manager` applicationId and the `readwide` release signing key, so 1.0.10 installs in place over 1.0.9, 1.0.8, 1.0.7, and 1.0.6 as a normal update.
- This release reworks how the archive image viewer reads solid/sequential archives so that paging through them is faster and the previously shown image no longer stays on screen while the next one loads, and tunes PDF single-page prefetch so rapid page-flipping is smoother. It is internal to the archive image viewer and the PDF reader and adds no new dependency.

### Images - forward reader for solid/sequential archives

- Solid 7z (`.7z`/`.cb7`) and the TAR family (`.tar`/`.cbt` plus the gzip/bzip2/xz/lzma/compress variants) have no cheap random access to a single entry: reaching entry N means decompressing the shared stream from the start through N. The viewer previously extracted each image independently, re-opening the archive and re-decompressing from the beginning for every page, which is O(N) work per page and O(N^2) across a full read-through. On a large archive this made each successive forward page slower, and the next image often had not finished extracting in time, so the previous image was left on screen (the viewer intentionally does not blank the current image until a replacement is ready).
- A new session-scoped forward reader keeps a single forward stream open for the whole viewing session. It reads strictly forward, decodes each image exactly once, and writes every image it passes into the same preview cache the viewer already uses. Showing the next image is then a single decode, and any page already passed is a cache hit that does not touch the stream. Jumping forward advances the reader to that point (caching the images in between); jumping back to an already-seen page is a cache hit. If a passed page's cache file was later evicted by the preview-cache size cap, a request for it is re-read with a one-off single-entry extraction (one page) rather than scanning the open stream forward to the end - which can never reach a passed entry - and exhausting the reader, so paging back into a large 7z/CB7 or TAR/CBT no longer stutters or re-reads the rest of the archive.
- First-page open extracts only up to the target entry instead of decompressing the whole archive, so opening a large solid archive no longer waits on a full extraction before the first image appears.
- Neighbour prefetch for these formats was routed through the same shared reader. Previously prefetch extracted each neighbour independently, which for a solid/sequential archive fell back to a whole-archive extraction and decompressed the entire archive in the background right after the first page. Prefetch now advances only the shared forward reader, so the reading frontier - not the whole archive - bounds how much is extracted, and the on-disk cache stays under its existing size cap.
- The reader is a pure optimization. Any reader failure, or a request it cannot reach (for example a page behind the current read position that was never cached), falls back to the existing whole-archive extraction, so correctness is unchanged and the worst case is the previous behaviour. Random-access archives (ZIP/CBZ, ALZ, EGG) keep their direct per-entry extraction and are untouched.
- RAR/CBR now uses the forward reader as well. Its libarchive engine is itself a strictly forward, single-pass reader with no random access, and the bundled `me.zhanghai.android.libarchive` binding exposes that forward iteration to Java (`Archive.readNextHeader` to advance, `Archive.readData` to read the current entry), so a small `LibarchiveNativeBridge.ForwardStream` wraps one open libarchive handle and a `LibarchiveForwardReader` adapts it to `ForwardArchiveReader`. RAR is routed to it only when the libarchive engine is present and the file is a RAR version libarchive reads (v4/v5); `openForwardReader` resolves the volume chain (one file for a single-volume archive) so split RAR and embedded-SFX offsets are handled by libarchive's own volume input. First-page open now extracts only up to the target entry instead of decompressing the whole archive, and forward paging plus neighbour prefetch advance the same shared reader, exactly as for 7z/TAR. Anything libarchive cannot read on its own (an encryption variant or a compression case it does not support) surfaces as a stream error, which abandons the forward reader and falls back to the existing whole-archive extraction, so correctness never depends on libarchive covering every entry. The whole-archive path keeps its `WHOLE_ARCHIVE_BULK_DONE` marker (per archive: path+size+mtime) so that, when it is used as the fallback, a later cache miss extracts only that one member via the single-entry path rather than re-extracting the whole archive; the `isLikelyUnsupportedRar3PpmdSolidImage` guard still short-circuits solid PPMd RAR3 before single-entry. When the libarchive engine is unavailable, RAR is not forward-readable and uses the whole-archive path as before, so there is no regression.

### Implementation notes

- New `SequentialArchiveImageReader` (manager package, `Closeable`): owns the forward stream for one viewer session, extracts passed images via a new `ArchiveImageEntryCache.commitReadyImageFile` (which reuses the existing image validation and ready-marker logic), records entries it failed to extract so it does not retry them, and abandons the reader on any stream error so callers fall back.
- New forward-reader API in `ArchiveSupport`: `ForwardArchiveReader`/`ForwardEntry`, `isForwardImageReadableType`, and `openForwardReader`, with `SevenZFile`-, `TarArchiveInputStream`-, and libarchive-backed implementations. For RAR, `isForwardImageReadableType(File)` gates on the libarchive engine being present and the file being RAR v4/v5; `openForwardReader` resolves the RAR volume chain and opens a `LibarchiveNativeBridge.ForwardStream` wrapped by `LibarchiveForwardReader`. The `ForwardStream.read(byte[])` contract relies on the binding's `Archive.readData(long, ByteBuffer)` advancing the buffer position by the byte count (0 = end of the current entry), confirmed against the binding's JNI. The whole-archive image cache (`ArchiveImageEntryCache`) remains the correctness fallback for all of these types.
- `ImageReaderActivity` lazily opens one reader per session (closed in `onDestroy`) and routes both on-demand extraction and neighbour prefetch through it; `ArchiveImageSequenceLoader.loadLazy` routes the initial target extraction through it as well. The cache file paths and the password/sensitive-cache handling are unchanged, so cached pages remain interchangeable with the previous path.
- Prefetch never runs a single-entry decode for a page behind the read frontier. `SequentialArchiveImageReader.ensureExtracted` takes an `extractBehindFrontier` flag: on-demand requests pass `true` (a behind page whose cache file was evicted is re-read as one member), while prefetch passes `false` (the behind page is skipped, leaving the reader lock free for the on-demand page that may be waiting on it). Prefetch also branches through the memoized session reader rather than re-detecting the archive type on every neighbour, which for RAR avoids re-reading the file signature each call.

### Reading - smoother PDF single-page flipping

- The PDF reader pre-renders neighbouring pages into a bitmap cache (`singlePageCache`) on a separate prefetch thread and renderer, so a page turn shows from cache instead of rendering on demand. It previously buffered two pages each way (`+1, -1, +2, -2`), so a rapid forward tap-through ran out of the forward buffer after two pages, paused for the on-demand render, refilled, and repeated - a perceptible "two pages, pause, two pages" rhythm.
- Prefetch is now biased toward the travel direction: forward navigation buffers `+1, +2, +3, -1` and backward navigation buffers `-1, -2, -3, +1` (the same number of renders, redistributed), so the side being read toward stays buffered deeper without spending render budget on the side being navigated away from. With no direction yet (first page) it still buffers both sides evenly. The stale-prefetch guard that drops in-flight renders once the reader moves away from the batch centre was widened from two pages to three to match the deeper buffer. Page rendering, the supersample factor, zoom, pan, and continuous-scroll mode are unchanged; this only changes which neighbours are pre-rendered.

## Readwide 1.0.9 - 2026-06-28

### Release scope

- Android metadata is `versionCode 10009` and `versionName "1.0.9"`. It keeps the `com.readwide.manager` applicationId and the `readwide` release signing key, so 1.0.9 installs in place over 1.0.8, 1.0.7, and 1.0.6 as a normal update.
- This release adds in-document text find to the PDF reader for digital (text-based) PDFs, speeds up image page-flipping inside large archives, and reworks the recent-files list: it now searches your reading history (with a result banner), keeps your full history and shows up to 5000 entries, combines with the file-type filters, and supports swipe-to-remove. It also matches the archive preview's row styling to the main file list. It adds one new runtime dependency, PdfBox-Android (Apache-2.0), used only for PDF text extraction; the recent-list, archive-styling, and archive-image changes are internal and add no dependency.

### Reading - PDF in-document find

- The PDF reader now has a **Find** action. Enter text and the reader searches the whole PDF, highlights matches on the page, and lets you step through them with previous/next while showing a current/total match count. The highlights track zoom and pan, and the find dialog is consistent with the search dialog used by the other viewers (title and match count on one row, equal-width controls).
- Find covers every page of the document. Previous/next walk through all matches in order and jump to the page of each match, so matches on other pages are reachable, not just the ones on the current page.
- When you move to a match that would sit behind the find dialog, the page shifts up so the current match stays visible above the dialog.
- It works on digital (text-based) PDFs that carry a real text layer. Scanned or image-only PDFs have no extractable text and are not searched; OCR is intentionally not included, to keep the app lean and fully local.
- Page rendering is unchanged: PDF pages are still drawn by the platform `PdfRenderer`. PdfBox-Android is used only to extract the page text and the on-page position of each glyph (so highlights land on the right words); it does not render pages. Page text is extracted in a single pass over the document the first time you search, then reused.

### Images - faster page-flipping in large archives

- Moving to the next or previous image inside an archive (a ZIP/CBZ comic) is faster, most noticeably on the first pass through a large archive such as a comic with around two thousand images. Each image used to be extracted by re-opening the archive and re-parsing its entire entry directory and then scanning that for the entry, which is work proportional to the number of entries for every single image. The reader now caches each archive's parsed index (keyed by path, size, and modified time), so showing each image is a direct lookup plus the decode; the existing neighbour-prefetch and decoded-image cache then keep up while you flip quickly.
- This applies to password-protected archives as well. For an encrypted archive the password is attached to the cached archive handle only while a single image is being extracted and is cleared immediately afterward, so it is never kept in the shared cache between pages.

### Files - recent list search and management

- The home-screen search box now searches your recently-read files (your reading history) as you type, rather than walking device storage. Results are filtered from your full history, and a banner just under the **Recently Read** header shows the current query and the number of matching recent files. Searching while you are browsing a folder still searches storage, unchanged.
- The recent list now keeps your whole reading history and shows up to 5000 entries (it was capped at a few hundred), so reads that previously dropped off the bottom stay listed and are reachable through the search.
- The file-type filter chips and the recent search now compose: the chip filters the recent list first, and the search then runs only within that filtered set. Switching chips while a search is active re-applies the search over the new filter, so the two no longer override each other.
- Swipe a recent row left to remove it. The card tracks your finger and commits the removal once it passes about 45% of the row width; a shorter swipe snaps back and keeps the row. Removing a row deletes that file's saved reading position (the same effect as clearing it individually).
- Back clears an active recent search first - it empties the search box, hides the banner, and restores the list - before it drops any active file-type filter or leaves the home screen. Previously a recent search left Back going straight to the exit prompt, because the recent search stays on the home screen rather than entering the separate search screen.

### Archive viewer

- File rows inside the archive (ZIP/CBZ) preview now match the main file list's row metrics - name and detail text sizes, line spacing, icon size, and row padding - so the archive listing looks the same as the main file browser instead of using a larger, looser row.

### Dependencies

- Added `com.tom-roush:pdfbox-android:2.0.27.0` (Apache-2.0, pure Java) for PDF text extraction with glyph positions. The optional JP2/JPEG2000 image decoder (`com.gemalto.jp2`) it can reference is not bundled; a proguard/R8 `-dontwarn` keeps the release build from failing on that optional class. JPX images are not affected because find only uses extracted text. See `THIRD_PARTY_NOTICES.md`, `docs/LICENSE_REPORT_READWIDE_1_0_10.md`, and `docs/SBOM_READWIDE_1_0_10.spdx.json`.

## Readwide 1.0.8 - 2026-06-26

### Release scope

- Android metadata is `versionCode 10008` and `versionName "1.0.8"`. This is a hotfix over 1.0.7 and keeps the `com.readwide.manager` applicationId and the `readwide` release signing key, so 1.0.8 installs in place over 1.0.7 and 1.0.6 as a normal update.

### Fixes — blank document while reading

- Fixed a regression where a large text or PDF document could suddenly become a blank document while you were reading it, at random: sometimes right after opening, sometimes after reading for a while, and again after reopening, which made it hard to get back to your place. The cause was the reader's memory-trim handler. Under system memory-pressure signals it released the on-screen text whenever the level reached `TRIM_MEMORY_RUNNING_LOW`, but those `RUNNING_*` levels are delivered while the app is still in the foreground. Clearing the text there blanked the page the user was reading, and nothing restored it because the restore only runs when you return to the app. The trim now happens only when the app is actually in the background (`TRIM_MEMORY_BACKGROUND` and above); foreground memory pressure no longer clears the page. This applies to both the text reader and the PDF reader.
- Hardened reading-position autosave so it no longer writes a position while the reader content is temporarily released for a background memory trim. In that released window the derived char position is 0, and a later pause could otherwise persist it over the real saved position (which is why, before this, closing the app from the reset state lost the place while merely backgrounding and returning recovered it). The autosave now skips that state, so the correct position survives even if the app is closed from it.

## Readwide 1.0.7 - 2026-06-20

### Release scope

- Android metadata is `versionCode 10007` and `versionName "1.0.7"`.
- Keeps the `com.readwide.manager` applicationId and the `readwide` release signing key introduced in 1.0.6, so 1.0.7 installs in place over 1.0.6 as a normal update. Updating from 1.0.4/1.0.5 (previous key) still requires uninstalling the old version first, then migrating via the in-app JSON backup export/import.
- This release centers on an optional blank-line collapsing display setting, more reliable text reading-position restore, improved large-file bookmark page accuracy, recent-list scroll preservation, refined find-in-page behavior when its options change, and a find-in-page crash fix.

### Reading — collapse repeated blank lines

- Added an optional **Collapse repeated blank lines** display setting for the text reader (Display settings, under the large-TXT options). When enabled, any run of two or more consecutive blank lines is shown as a single blank line; a lone blank line is left as-is and the original file is never modified. It applies to all text files the reader opens (TXT, log, CSV, and similar), both small and large, treats whitespace-only lines as blank, and is applied consistently to the page model, large-file partition/exact-page index, and in-text search so page numbers, bookmarks, and search positions stay aligned. Toggling it reloads the open file, and the collapse state is folded into the page-layout signature so the page model is recomputed instead of reusing a stale one. Bookmarks from before this version stay compatible while the option is off. Default off.

### Reading — position restore

- Reopening a text file restores the reading position more reliably. The saved position now carries short before/after text anchors and a page-layout signature, so the reader re-finds the original spot even when the page layout would otherwise differ (for example after a display-setting change), instead of falling back to an approximate page. The restored position also stays correct after the system recreates the reader and when scrolling back through a large file.
- If a text file changed on disk since it was last opened, reopening it reloads the current contents instead of restoring the earlier cached view and position.
- Large-file bookmark jumps now prefer surrounding-text anchors when resolving the destination, improving landing accuracy after a layout or display change (for example a different font size or margin).

### Settings and display rules

- Settings are reorganized into two screens. Display and reading-layout options (theme, reading theme, text layout, EPUB layout) now live in a dedicated **Display settings** screen reached from Settings, while general app settings (behavior, button order, security, backup) stay on the main Settings screen. This keeps display options together and out of the general list; the large-TXT options, including Collapse repeated blank lines, are in the Display settings screen.
- **Edit actual TXT file**: enabled TXT display rules can now be permanently applied to the current text file from the text reader's **More** menu. You choose between fixing the original file in place or writing a separate `_edited` copy, and the flow keeps the rule-order, overwrite, and large-file warnings followed by a final confirmation. Display-only rules still never modify the file; this is the explicit opt-in that writes changes. It moved here from the TXT layout settings so it always runs with the currently open file in context.

### Files — recent list

- Returning to the app after opening a file from the recent list no longer forces the list back to the top. The list keeps its scroll position near the row the file was opened from.

### Fixes

- Fixed a crash that could occur when an invalid regular expression was the active find-in-page query; an invalid pattern is now treated as no match instead of failing during drawing.
- Changing the find-in-page options (case-sensitive, whole-word, or regular expression) now restarts the search under the new options, so the next match is found with the new settings instead of continuing from the previous result.
- When the system recreates the reader from memory (for example under memory pressure), large-file exact page numbering is rebuilt for the current layout instead of remaining on the initial estimate.

## Readwide 1.0.6 - 2026-06-19

### Release scope

- Android metadata is `versionCode 10006` and `versionName "1.0.6"`.
- Keeps the `com.readwide.manager` applicationId from 1.0.4 but changes the release signing key, so 1.0.6 does not install over an existing 1.0.4/1.0.5 (which used the previous key); uninstall the old version first, then migrate via the in-app JSON backup export/import.
- This release centers on file-list performance, reading-progress for image archives, per-type file icons, folder auto-refresh, image-viewer paging smoothness, text-to-speech refinements, and several browsing fixes.

### Final changes included in this release

**Performance — file listing and sorting**

- Folder listing and sorting no longer issue a MediaStore (ContentResolver) query per file or per scrolled row. Date sorting previously queried MediaStore for each image/video, and each visible row queried again while scrolling, which was slow in large media folders.
- Folders now sort and display immediately using filesystem timestamps (the newer of last-modified and creation time). When a date sort is active, a background pass batches MediaStore date lookups and re-sorts only if the order actually changes; folder loads and non-date sorts do no MediaStore work at all.
- Large folders such as Downloads open noticeably faster as a result, with the same set of files shown.

**Performance — file search**

- Removed the 5,000-item cap on file search. The search walk now streams results into the list incrementally (only the new items per step), then applies the final sorted order when the walk finishes, so very large result sets are no longer truncated.

**Performance — image and PDF caches**

- The image viewer now prefetches three pages in each direction instead of two, and its decoded-bitmap cache budget was raised (up to 128 MB, scaled to a fraction of app heap). Rapid continuous paging is less likely to outrun the prefetch window and stall on a decode.
- The PDF viewer's single-page and continuous-mode bitmap caches were enlarged (cap raised to 96 MB each, still scaled to app heap), for smoother paging and scrolling on large documents.

**Reading progress — image archives**

- Image archives opened in the comic/image viewer (and folder image sequences) now record a reading position and show a progress percent in the recent list, the same as PDF and EPUB. Progress is keyed on the archive so the archive's recent entry gets the badge.
- Progress is saved with a short debounce as you turn pages (so fast paging does not write to disk every page) and saved immediately when you leave the viewer.

**Files — per-type icons**

- File rows now show a Material icon chosen by type: PDF, EPUB, document (Word/HWP), archive, image, video, audio, app package (APK), or a generic file icon as the fallback. Icons are tinted with the current theme color. Folder icons are unchanged.

**Files — long-name display**

- Long file names now keep their extension and the end of the name visible. When a name does not fit, it is shortened in the middle as "start…<tail><extension>" so the file type and trailing context (for example a resolution or part number) remain readable across two lines. Names that fit are shown in full.
- The truncation is computed during layout so the final text appears in one pass, without the brief flash that a deferred rewrite produced when re-entering the recent list.

**Files — list layout**

- Reworked the recent/file list row: two-line file names, slightly smaller and tighter secondary text (type, size, date), and more consistent row spacing.

**Files — search result location**

- Search results now display each file's location relative to the searched folder instead of the full absolute path that repeated the search root on every row. A file directly in the searched folder shows no location line at all; a file in a subfolder shows ".../subfolder" so it reads as a path below the searched folder rather than a bare folder name.
- An all-storage search (which spans multiple roots such as internal storage, Downloads, and SD cards) keeps the matched storage's folder name as a prefix, so results from different storages remain distinguishable. The deepest matching root is used when roots are nested.

**Files — image formats**

- The image filter chip and the image viewer now also recognize `.jfif` (a JPEG container), `.wbmp`, and `.dng`. These were chosen because Android's bitmap decoder can render them, so they both appear under the Image filter and open in the viewer (including when found inside an archive). Formats Android cannot decode by default (such as TIFF and ICO) were intentionally not added.

**Files — recent list**

- The recent list now shows up to 300 recently opened files, so more of your reading history stays visible on the home screen. Entries whose underlying file no longer exists are skipped.

**Files — search and filter loading**

- While a search or file-type filter runs, the file list shows a single loading spinner. The spinner is new in this release; earlier versions showed a "loading" text label, and the spinner now takes its place. The empty-state message appears only once a finished search returns no results.

**Folders — refresh**

- The visible folder is re-read when the app regains window focus, picking up downloads and other external changes that the filesystem watcher can miss on FUSE/MediaStore-routed storage. The re-read keeps scroll position and only happens when the folder's on-disk signature actually changed.
- Pull down on the file list to refresh the current folder (or re-run the active search) manually.

**Text-to-speech — resume on reopen**

- When a read-aloud session is interrupted — leaving the app, the process being killed in the background, or pausing and navigating away — reopening Readwide shows a prompt on the main screen offering to resume that book. Continuing reopens the file at its saved position, restores the sleep-timer value that was active when playback stopped, and starts reading automatically. "Later" (or dismissing the prompt) clears the saved session, and finishing a book to the end also clears it so it does not prompt again.
- Resume is page-level: reopening restores the saved reading page and TTS begins from there. Because Android's speech engine has no true mid-sentence pause and the exact paused sentence is not persisted across process death, playback resumes from the start of that page rather than mid-sentence.

**Text-to-speech — speech cleanup**

- Runs of punctuation are no longer read aloud. Ellipses, sequences of two or more periods, and underscores are collapsed to a pause instead of being spoken, and a semicolon is spoken as a short, comma-like pause. Single sentence-ending periods are unaffected.

**Security & stability**

- External open path: files handed to Readwide through `ACTION_VIEW`/`BROWSABLE` (from a browser, messenger, file manager, or document provider) are copied into an app-private `opened_files` cache before rendering. The copy sanitizes the provider-supplied display name, verifies the cached path stays inside the cache directory, keeps each source URI in its own subdirectory, enforces a 2 GB per-file copy limit, and prunes the cache before and after the copy (preserving the just-opened file). A failed, aborted, or over-limit copy deletes its partial file, and provider `query`/`getType` exceptions are caught so a misbehaving provider cannot crash the open.
- Backup/settings import caps the JSON read at 256 MB and rejects (rather than truncates) anything larger.
- App lock: launching the lock screen now returns before the home/recent UI is built, so it is not prepared or briefly shown behind the lock; the main UI is set up after a successful unlock.
- Search and current-folder filtering build their result rows in a generation-aware, cancellable pass and stop promptly when a newer search or folder change supersedes them.
- Document text entries (EPUB/Word/HWPX), in-document resources, and EPUB chapters each have a read-size cap (32 MB / 64 MB / 32 MB) so a crafted file cannot exhaust memory during rendering.

**Fixes**

- Starting a folder navigation while a file search was still running no longer leaves a stale search screen, stutter, or brief freeze. Navigation now cancels the in-progress search walk cleanly so it stops consuming the search thread.
- Tapping the navigation drawer's Recent shortcut while a large folder was open no longer delays the drawer from closing. Entering the recent/home view from the drawer now uses the fast state-save path instead of a synchronous folder rescan that could stall the close animation.
- In fixed-layout EPUBs (including image-based books and comics), a double-tap on the left or right side now turns the page the same way a single side tap does, instead of zooming. Double-tapping the center still zooms, and reflowable EPUBs and other documents are unchanged.

**Internal**

- Background work executors for file search, folder operations, and document loading are guarded so late tasks submitted around viewer teardown do not throw.

## Readwide 1.0.5 - 2026-06-17

### Release scope

- Android metadata is `versionCode 10005` and `versionName "1.0.5"`.
- Keeps the `com.readwide.manager` applicationId from 1.0.4, so 1.0.5 installs as an in-place update over 1.0.4 when signed with the same key.
- This release centers on a text-to-speech overhaul, a folder-aware multi-select delete confirmation, two PDF mode-switch crash fixes, a text-rule performance fix, and internal code cleanup.

### Final changes included in this release

**Text-to-speech — sleep timer**

- Added a sleep timer with Off / 15 / 30 / 45 / 60-minute presets and a custom-minutes entry (0–600). An optional "finish the current sentence" setting lets playback complete the sentence in progress before stopping.
- The timer measures playback time only: time spent paused does not count toward it. When it expires, playback stops.
- The timer can be changed mid-playback and takes effect immediately. The setting is shown as "Timer" in the UI.

**Text-to-speech — pause, resume, and session**

- Added real pause and resume. Pausing stops speech but keeps the session active and remembers the current sentence; resuming continues from that sentence rather than restarting from a saved character position. Because the Android speech engine cannot pause mid-sentence, resume replays from the start of the interrupted sentence.
- A paused session now persists its position when you leave the app, so playback can resume where it left off on return.

**Text-to-speech — floating control card**

- Added a floating control card over the text reader (TXT) with play/pause and stop buttons. It appears while TTS is active, reflects the paused state on its play/pause button, and can be dragged anywhere on screen; a tap on either button is distinguished from a drag by the touch slop.
- The card complements, and stays in sync with, the existing foreground-service notification, lock-screen controls, and Bluetooth/media-button controls.

**Text-to-speech — audio policy**

- TTS keeps reading when you scroll or move within the page; manual navigation no longer stops playback. As a result the reading position and the visible position can diverge.
- Audio focus is now requested for speech playback. A transient focus loss (for example a phone call) pauses playback and auto-resumes when focus returns; a permanent loss (another app taking over media) stops playback.
- Unplugging headphones (audio becoming noisy) now pauses playback instead of stopping it, so it can be resumed after reconnecting.

**Text-to-speech — translations**

- All new text-to-speech strings (sleep-timer presets and labels, pause/resume, and the folder-warning message) were translated across the bundled locales: Arabic, German, Greek, Spanish, French, Hindi, Indonesian, Italian, Japanese, Korean, Dutch, Polish, Portuguese, Russian, Swedish, Thai, Turkish, Ukrainian, Vietnamese, Simplified Chinese, and Traditional Chinese, with English as the fallback.

**Files**

- The multi-select delete confirmation now appends a warning when the selection includes one or more folders, stating that all of their contents will be deleted too. The warning is shown only when at least one folder is selected. All real file and folder deletions in the app continue to pass through a confirmation dialog; the single-file action-sheet delete and the image-viewer delete are unchanged.

**Fixes**

- Fixed a PDF crash when switching from a zoomed-in vertical (continuous) view into horizontal (single-page) mode. The single-page render reused the carried-over zoom factor and could allocate an oversized bitmap; single-page mode now resets to fit and evicts the stale single-page cache on entry.
- Fixed a second PDF crash when switching display modes, where the matrix page view could draw a bitmap that the activity had already recycled. The view now detaches its bitmap references before any recycle and skips drawing a null or recycled bitmap.
- Fixed text-to-speech sentence segmentation for short pages. A page shorter than the internal segment size was returned as a single segment, so pause/resume rewound to the start of the page; segmentation now always splits by sentence terminator, so resume continues from the correct sentence.
- Made the navigation drawer's Recent shortcut respond immediately. Building the recent list scanned saved reading states on the main thread (checking file existence and cleaning up stale or image-only entries) before the list appeared; the scan and cleanup now run on a background thread.

**Performance**

- Text display rules compile their regular expressions once per file load instead of once per line. When a large text file is read line by line with active regex rules, each rule's pattern was recompiled for every line; rules are now pre-compiled and reused, so compilation scales with the rule count rather than the line count. Invalid user regexes are skipped at compile time, matching the previous fail-safe behavior, and files with no rules or only literal rules are unaffected.

**Internal**

- Removed dead code (an unused stop-on-navigation path left over after TTS stopped halting on scroll) and an unused, fully duplicated color utility class.
- Consolidated duplicated helpers into shared utilities: CSS quoting/coloring, same-or-child path checks, and an empty-to-null string helper used by the document render builders.
- Collapsed a redundant tap-zone action overload into a single method.
- Split the text-to-speech controller's sleep-timer dialog and its stateless dialog view builders into their own classes, reducing the controller's size without changing behavior.

## Readwide 1.0.4 - 2026-06-16

### Release scope

- Android metadata is `versionCode 10004` and `versionName "1.0.4"`.
- Readwide moves to a new Android applicationId, `com.readwide.manager`, completing the rename away from the earlier TextView Reader package identity.
- Because the application ID changed, 1.0.4 is installed as a separate app rather than an in-place update over older TextView Reader/Readwide builds.
- This release keeps the 1.0.3 document-fidelity cycle and focuses on package identity cleanup, reader-search consistency, document-viewer search behavior, and translated UI clipping fixes.

### Final changes included in this release

**Package identity**

- Renamed the Android applicationId and source package from `com.textview.reader` to `com.readwide.manager`, including all package declarations, the FileProvider authority (derived from `${applicationId}`), layout custom-view references, ProGuard keep rules, fixture report scripts, F-Droid metadata, and release materials.
- Existing users are not auto-updated to 1.0.4 because the package identity differs; bookmarks, reading positions, themes, and settings transfer through the in-app JSON backup export/import, which is independent of package name and signing key.

**PDF viewer**

- Fixed single-page PDF fit when toggling the PDF toolbar on tablets. The visible toolbar now reserves its full overlay height before fitting the page, stale pre-reserve renders are cancelled, and the cache key includes viewport height so returning from toolbar-off mode cannot leave an oversized page under the toolbar. Toolbar-off mode releases the reserve and immediately refits the current bitmap into the larger viewport before the sharper rerender completes.
- Made double-tap zoom smoother in vertical (continuous) mode. Zooming has to re-render every visible page at the new scale on the one render thread; while zoomed, each page now renders at a smaller pixel cap (about half the work), reducing the stutter.
- Made horizontal (single-page) prefetch buffer equally forward and backward (two pages each way), so flipping back is as fast as flipping forward. The page cache holds several full pages (each ~13MB at fit size), and prefetch runs on a second independent PDF renderer so it fills without blocking the page you're reading. This brings rapid horizontal paging much closer to vertical mode, which stays fast because it pages by scrolling over pages it already rendered.
- Improved rapid tapping in horizontal (single-page) mode by prerendering further ahead in the direction you're paging (two ahead, one behind) and kicking it off almost immediately rather than only after you pause.
- Fixed the PDF viewer dropping back to the main screen when you double-tap to zoom. Zoomed pages make large bitmaps, and caching several of them could run the app out of memory, causing the system to quietly close the viewer. Prefetch is now skipped while zoomed, cached neighbor pages are freed when you zoom in, and zoomed pages render at a smaller pixel cap, so zooming no longer exhausts memory.
- Made PDF pages render about twice as fast by lowering the supersample factor from 2.0 to 1.4. Pages are still rendered above screen resolution and scaled down for display, so text stays sharp, but roughly half as many pixels are rendered. Both regular page turns and neighbor prerender benefit.
- Fixed the lag at the start of rapid tapping in horizontal (single-page) mode. Every tap queued a full render on the one render thread, and pages you'd already tapped past were rendered to completion before the page you stopped on, so the first few taps felt stuck until the queue drained. Renders for pages you've moved past are now dropped before rendering, so the page you land on shows right away. Neighbor prerender now also kicks in during short gaps between taps instead of only after you stop.
- Fixed the page number flickering between nearby pages on rapid taps in vertical (continuous) mode before landing on the target. Page turns scheduled delayed settle timers that weren't cancelled, so quick taps left several stale timers that fired in sequence and momentarily reverted the page. Pending scroll/settle callbacks are now cancelled before the next is scheduled, so only the most recent target is applied.
- Fixed the page number showing the previous page after dragging the page slider a long distance in continuous mode. The target page is re-asserted after the scroll settles so a late scroll callback can't revert the counter to an intermediate page.
- Fixed pressing a toolbar icon hiding the toolbar while its popup stayed open. A tap on a visible toolbar or app-bar no longer also toggles the viewer chrome beneath it.
- Sharpened PDF text by rendering pages at a higher-than-screen resolution (supersampling) and downscaling for display in both single-page and continuous modes. The page keeps its aspect ratio and is never stretched, including when the toolbar is hidden. The existing per-page pixel cap still limits memory, so very large pages are scaled down automatically.
- Fixed the PDF viewer placing the page behind the bottom toolbar. With the toolbar visible, the page viewport now reserves the toolbar's height at the bottom so a full page is shown between the top title area and the toolbar; the reserved space is released when the toolbar is hidden. Works in single-page and continuous modes and on initial load.
- Fixed the PDF page being partly hidden under the 3-button navigation bar in landscape; the page area is inset from the side nav bar and the fit-to-width calc accounts for it, so the page fits the visible area when the toolbar is shown.

**EPUB viewer**

- Fixed fixed-layout EPUBs (pages with a declared fixed pixel size such as 1366×768) not fitting the screen. The viewer now lets the WebView scale the declared viewport to fit the screen width and only removes page margins / matches the page width to prevent sideways scroll; it no longer forces a fixed body height or centering, which had pushed full-page images to the top. The book's own layout is kept.

**Image viewer**

- Removed the per-tap latency on image page turns. A page-turn tap in a side zone now fires as soon as the finger lifts rather than waiting for the gesture detector to rule out a double tap (about 300 ms). Before, a quick second or third tap was absorbed into a double-tap sequence and delayed, so fast tapping felt sluggish; each tap now registers right away. Horizontal swipes and double-tap-to-zoom still behave as before.
- Sped up image page turns, especially under rapid repeated taps. Decoded preview bitmaps are cached (with a memory budget), and neighboring pages (two each direction) are pre-decoded on a small parallel pool that runs alongside archive extraction rather than behind it, so a turn to a prepared page is shown immediately. The cache owns its bitmaps and recycles them only on eviction, so a page that is on screen or cached is never recycled while still in use.
- Raised the preview decode budget from 12 to 16 megapixels so higher-resolution images display at full detail before any downsampling. Larger images are still downsampled to fit the screen and memory, and the out-of-memory fallback is unchanged.

**File list and sorting**

- Sped up file-list sorting and refresh by extracting each file's sort attributes (name, directory flag, and the active date/size/type key) one time before sorting, instead of calling `File.getName()`/`isDirectory()`/`length()` repeatedly inside the comparator where they ran about n·log n times and hit the filesystem. The resulting order is identical.
- Cut redundant filesystem calls during folder listing, which is the path used when entering a folder or a folder shortcut such as Downloads. Each entry's name and directory flag are read once per item rather than repeatedly, so the initial load of large folders is faster.

**TXT find-in-page**

- Fixed TXT find-in-page being extremely slow for common words in some files. The matcher now prepares the comparison view once per search and reuses it, and the large-file engine scans each line in a single pass, keeping returned offsets aligned to the original text for bookmarks and page anchors.
- Improved TXT find-in-page with case-sensitive, whole-word, and regular-expression options. Unicode normalization is always applied, overlapping literal occurrences are counted correctly, and the in-memory and large-file search paths share the same matcher and option semantics.
- Improved TXT search reveal near the end of a file. Search jumps now use a search-only virtual bottom scroll allowance so a match in the final lines can be pulled above the search dialog without changing normal paging, manual scrolling, bookmarks, or saved-position restore.

**Document-viewer search (Markdown, EPUB, HWP/HWPX, Word)**

- Reworked Markdown, EPUB, HWP/HWPX, and Word-family document-viewer search to use the same TXT-style search options and match counter instead of relying on WebView native find. The document search dialog supports previous/next movement, nth-match jumps, current/total status, case-sensitive mode, whole-word mode, and regex mode.
- Fixed document-viewer search result visibility. Current matches now use explicit highlighted spans and popup-safe reveal logic; the selected result is placed near the upper safe area, with top/bottom document spacer handling so matches near the beginning or end of a rendered document can still be moved into view above the bottom search dialog.
- Reduced same-page Markdown search bounce by updating the current highlighted match inside the existing DOM when possible instead of reloading the whole page for every previous/next movement.
- Strengthened EPUB/HWP/Word search highlight styling so reader/theme CSS does not erase the yellow/current-result highlight.

**Toolbar and dialogs**

- Fixed content and toolbars overlapping the Android 3-button navigation bar in landscape (the nav bar moves to a screen side). Left/right insets are now applied on the main screen, settings, and the TXT, document, and PDF viewers, so nothing slides under the side nav bar.

- Added a screen-rotation (portrait/landscape) button to the TXT, document (Markdown/EPUB/Word/HWP/HWPX), and PDF viewer toolbars (the image viewer already had one), using the same icon as the image viewer. The icon now matches the current screen orientation — landscape icon in landscape, portrait icon in portrait — and updates when you tap it or rotate the device. It flips the screen orientation and is independent of any page-slide direction. In TXT it sits at the far right of the buttons (right of Text encoding); in the document and PDF viewers it sits next to a new Settings button, just left of the pinned More. All can be rearranged in the button-order settings.
- Added a Settings button to the document and PDF viewer toolbars (right of the rotation button); Settings is still also in those viewers' More dialog.
- Made the PDF and document bottom toolbars scroll like the TXT viewer's: buttons sit in a horizontally scrollable row with More pinned at the far right. They now use the same toolbar controller as TXT, so button widths are balanced evenly and the row snaps to the nearest button when you stop scrolling (and re-balances on rotation). The rotation and settings buttons are also tinted with the active theme color like the rest.
- Fixed tap-to-turn paging triggering when the visible bottom toolbar was tapped. Because the toolbar floats over the full-screen view, a tap on it also landed in the page-turn zone beneath it. A tap on a shown chrome bar now just toggles/keeps the toolbar; with the toolbar hidden the whole view pages as before. Applies to the TXT, document (Markdown/EPUB/Word/HWP/HWPX), and PDF viewers.

- Fixed reader toolbar buttons running their action multiple times when tapped repeatedly, which could open duplicate dialogs or trigger repeated loading. Toolbar taps are now debounced, and only one positioned reader dialog is shown at a time.
- Fixed the settings "Button / icon order" rows (main filter, TXT, EPUB/Word, PDF) being vertically clipped under longer translations such as German. The rows changed from a fixed `48dp` height to `wrap_content` with a `48dp` minimum height and vertical padding, so longer labels wrap instead of being cut off.
- Applied the same wrapping fix to the sort dialog's options and to the TXT search dialog's option/action rows, whose fixed-height controls could clip longer translations.

**Manifest and distribution**

- Removed the dead `android:requestLegacyExternalStorage="true"` manifest flag, which had no effect under `targetSdk 35` and added an unnecessary legacy-storage signal for static scanners. File access behavior is unchanged.
- Public GitHub/F-Droid materials were updated for the 1.0.4 package, including the renamed F-Droid metadata file, Fastlane changelogs, and the package/version references in the release and submission docs.

## Readwide 1.0.3 - 2026-06-14

### Release scope

- Readwide 1.0.3 keeps the same Android package identity for update compatibility with earlier compatible builds when signed with the same key.
- Android metadata is `versionCode 10003` and `versionName "1.0.3"`.
- This release starts the document viewer fidelity cycle for DOCX, HWPX, and HWP, targeting L3 content-fidelity HTML preview: document structure, inline styling, tables, and images where verified.
- Exact MS Word/Hancom pagination, exact font metrics, editing/saving, and complete floating-object placement are explicit non-goals.

### Final changes included in this release

- A shared rendered-document model (page containers, paragraph/run styles, tables, images, text anchors, unsupported placeholders) now backs the document viewer, with fallback to the previous semantic HTML path when conversion fails.
- DOCX now bridges paragraphs, run/character styles with `styles.xml` inheritance, `numbering.xml` ordered/bullet lists, basic tables (width, column proportions, vertical merges, border colors, shading), inline images with extent hints, footnotes/endnotes, and headers/footers into the rendered model.
- DOCX Symbol/Wingdings bullets are normalized to standard Unicode markers, and list paragraphs no longer double-apply Word hanging indents.
- DOCX rendered tables clamp cell overflow and wrap by word so narrow phone-width columns no longer draw text over neighboring cells, while long first-column labels stay readable without introducing horizontal scrolling.
- DOCX/Word lecture-note math now renders inline and conservative `$$...$$` display fragments to HTML+CSS without WebView JavaScript, including fractions, square roots, superscripts/subscripts, Greek letters, and symbols, and including expressions split across runs by spell/grammar markers. Lone currency amounts such as `$200` stay as plain text.
- HWP binary documents now convert section/paragraph/control structure into the rendered model, preserving partially-ruled table borders per edge, column spans and proportional widths, authored cell heights for empty layout cells, character size/bold/italic/color/underline, paragraph alignment, paragraph-head bullet markers, and control-line horizontal rules. HWPX carries header/run styles, page metrics, and table color where directly present.
- RAR5 AES visible-header multi-volume handling and password-protected archive image preview caching were tightened so stale or wrong-password preview images are regenerated instead of reused.
- EPUB/Markdown/document/PDF pages now snap without slide/fade animation, and the compact hidden-toolbar top page counter height was refined.
- The selectable UI languages reached full coverage for the 1.0.3 string set: newly added archive support-boundary messages, bookmark "file missing" notices, and tap/image paging labels are now translated across all 20 non-default bundled locales, with English kept as the fallback for any future untranslated string.
- Public GitHub/F-Droid documents, Fastlane changelogs, the document viewer fidelity matrix and notes, and the F-Droid metadata draft were updated for the Readwide 1.0.3 package.

### Archive and FOSS boundary

- The 1.0.3 package keeps the no-network/local-first privacy baseline from earlier releases: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, and Android Auto Backup disabled.
- HWP/HWPX support is text-first and read-only through Apache-2.0 dogfoot libraries; cell vertical alignment, cell background fill, non-line GSO shapes, embedded images, and encrypted/password HWP are not claimed.
- RAR/CBR support remains limited and backend/scoped-path dependent; complete, encrypted, broad split, SFX, or VM-filtered RAR compatibility is not claimed.

## Readwide 1.0.2

### Release scope

- Readwide 1.0.2 keeps the same Android package identity for update compatibility with earlier compatible builds when signed with the same key.
- This release focuses on new document reader formats, rendered-document viewer polish, scoped archive decoding boundaries, and release documentation for the 1.0.2 line.

### Final changes included in this release

- Markdown files now open in a dedicated themed WebView reader. Markdown rendering is separate from the exact TXT reader model and does not change plain TXT paging.
- HWP/HWPX files now have text-first read-only support through Apache-2.0 dogfoot libraries. The app does not claim Hancom-compatible layout rendering, editing/writing, password/encrypted HWP support, or original page-count parity.
- The visible Word filter remains compact while grouping OOXML Word, HWP/HWPX, and recognized legacy DOC files. Legacy binary DOC is recognized for classification but remains unsupported for rendering.
- Markdown, EPUB, Word, HWP/HWPX, and PDF bookmark rows now use a shared rendered-document display model with content/text anchors as the primary label and page/position/date as secondary metadata.
- WebView document chrome was adjusted so toolbar toggles do not move the rendered body. Compact top page labels, bottom toolbar shape, slider presentation, Markdown CSS isolation, and Android navigation-inset handling were refined without changing TXT.
- PDF system-bar and navigation-inset behavior was refined separately from WebView documents so fixed-layout PDF behavior is preserved.
- The project launcher source reference was updated at `docs/readwide_launcher_icon_source.png`; checked-in Android launcher/adaptive/play-store PNG resources were left unchanged.
- Unknown-size decoded stream extraction, failed single-entry extraction cleanup, 7z solid-member drains, and 7z split/password classification were tightened under conservative archive compatibility claims.
- RAR/CBR support remains limited and backend/scoped-path dependent, with scoped decode-only paths for covered unencrypted single-volume RAR3/RAR4 PPMd solid and RAR5 v5.0 compressed/solid cases. Full RAR, encrypted RAR, broad split RAR, SFX, VM-filtered RAR, and complete RAR compatibility are not claimed.
- Public GitHub/F-Droid documents, Fastlane changelogs, FOSS notes, license report, SBOM draft, release notes, and release checklist were updated for the Readwide 1.0.2 package.

### Archive and FOSS boundary

- The 1.0.2 package keeps the no-network/local-first privacy baseline from earlier releases while adding Apache-2.0 HWP/HWPX libraries and updated direct-dependency notices.
- Archive claims for 1.0.2 should point reviewers to `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`; this release adds 7z safety/classification work and narrows RAR wording around scoped decode-only paths.
- RAR/CBR marketing must stay limited to documented covered cases; complete, encrypted, broad split, SFX, or VM-filtered RAR compatibility is not claimed.

## Readwide 1.0.1

### Release scope

- Readwide 1.0.1 keeps the same Android package identity for update compatibility with earlier compatible builds when signed with the same key.
- This release focuses on viewer polish, portable backup/bookmark handling, archive safety, lifecycle hardening, and public GitHub/F-Droid packaging cleanup.

### Final changes included in this release

- Missing bookmark target files remain visible with a theme-matched file-missing label. Tapping one opens an explanation dialog and keeps the bookmark for later file rebind.
- Backup import restores last directory, recent folders, and folder shortcuts only when those directories exist on the current device. Invalid imported paths are skipped without deleting valid current-device entries.
- TXT bookmark positions remain based on character position, line number, anchor text, and file fingerprint. Cached Page X/Y values are treated as layout-dependent cache and refresh under the current device layout when the file is opened.
- Zoomed PDF pages can fling/pan with inertia in single-page mode, and zoomed pages in vertical continuous mode can fling horizontally.
- Image viewer landscape safe-area handling was fixed for Android 3-button navigation. Wide images open fit-to-width, tall images open fit-to-height, and detail/original decode is kept after returning from zoom.
- Archive filename decoding, password-protected archive image preview, ALZ/EGG streaming extraction, archive preview cache pruning, and archive failure messages were hardened while keeping archive support claims conservative.
- Public GitHub/F-Droid documents, Fastlane changelogs, and release-note files were cleaned into result-focused Readwide 1.0.1 sections.

### Archive and FOSS boundary

- Default builds remain local-first and FOSS-oriented: no default `INTERNET` permission, no ads, no analytics, no account system, no app-network update check, Android Auto Backup disabled, and no Junrar/UnRAR-license fallback in the default build.
- ZIP, 7z, TAR-family, ALZ, EGG, and limited RAR/CBR support remain as documented in the archive support matrix.
- RAR support is still limited and should not be advertised as complete compatibility.

## Readwide 1.0.0

### Release scope

- Readwide 1.0.0 is the public continuation of TextView Reader 2.2.6.
- The app name is now Readwide, but the Android package/application ID stays unchanged for update compatibility with the TextView 2.2.6 line when signed with the same key.
- Settings now points to `https://github.com/k1717/Readwide/releases` for update information.

### Final changes included in this upload

- Readwide branding is applied across app labels, Settings, backup text, TTS labels, public docs, and developer contact text.
- Developer contact email is `readwide.kj7w5@addy.io`.
- Main language selection uses a compact row and rounded picker dialog instead of a long settings page.
- Major UI language options were added with initial translated resources; missing strings fall back to English.
- Recent-file multi-select actions can wrap long English labels instead of clipping.
- Launcher icons were replaced with the approved Readwide book artwork and adjusted for safer launcher margins.
- The custom reading theme editor now respects status-bar/cutout insets, so the top back button no longer overlaps the system bar.
- Reading-theme selection now shows a normal check mark again instead of broken encoded text.
- The custom reading theme editor's top app-bar/status-inset area now follows the active main theme bar color instead of showing a gray strip.
- The update link now uses the standard GitHub releases URL: `https://github.com/k1717/Readwide/releases`.
- RAR implementation comments and public packaging notes were cleaned for clearer FOSS/provenance wording.
- Launcher icon provenance is documented as project-owned generated artwork.
- The unused optional local RAR5 decoder bridge/readme was removed from the default public source tree.
- RAR detailed failure messages no longer expose development-session wording.
- The public Gradle dependency graph no longer includes a local `app/libs/*.jar` hook.
- Release signing is conditional, so F-Droid-style source builders can assemble an unsigned release without a private keystore.
- The unused Foojay toolchain resolver plugin was removed from `settings.gradle`.
- Readwide backup export filenames are ignored by git.
- Public docs are cleaned into release-result sections instead of development-session notes.

### Archive and FOSS boundary

- Default builds remain FOSS-oriented: no Junrar/UnRAR-license fallback code and no default `INTERNET` permission.
- ZIP, 7z, TAR-family, ALZ, EGG, and limited RAR/CBR support remain as documented in the archive support matrix.
- Split/multi-volume RAR and encrypted RAR were not re-tested for this release package and are not guaranteed.
- RAR solid archives, PPMd, custom VM filters, broad SFX, RAR5 compressed/solid/encrypted-header cases, and unusual variants remain backend-dependent or unsupported.

## TextView Reader 2.2.6

### Release scope

- TextView Reader 2.2.6 is the direct base for Readwide 1.0.0.
- Privacy, license, and archive-backend boundaries from this line are preserved in Readwide unless noted otherwise.

### Final changes included in this release

- Android Auto Backup is disabled.
- New PIN storage uses salted PBKDF2 verifier strings with legacy migration.
- Default builds have no `INTERNET` permission, no app-network update check, no telemetry, no ads, and no account system.
- Developer contact uses the user's mail app or copies the address when no mail app is available.
- Junrar/UnRAR-license fallback code is removed from the default build.
- Common compressed RAR3/RAR4 attempts route through bundled libarchive-android, with first-party Java kept for metadata, stored entries, safe paths, diagnostics, and selected stored RAR5 paths.
- Archive password prompts use compact buttons and include a password visibility toggle.
- Long archive errors open in a scrollable/copyable dialog.

### Known support boundaries

- Split/multi-volume RAR and encrypted RAR are not guaranteed.
- First-party compressed RAR is not complete.
- RAR5 compressed/solid/encrypted-header cases remain backend-dependent.

## TextView Reader 2.2.5

### Release scope

- Focused on archive fallback handling, smoother folder navigation, file-operation progress, and activity refactoring.

### Final changes included in this release

- ZIP extraction falls back to Apache Commons Compress for non-encrypted unsupported methods where bundled codecs can decode them.
- Pending ZIP creation runs in the destination folder where the queued action is executed.
- Viewer returns, drawer shortcuts, recent-folder navigation, and already-loaded folder revisits preserve or restore cached folder state when safe.
- Multi-select delete progress can be reopened after confirmation/backgrounding.
- Browse-state, archive list shaping, archive image sequence loading, and archive create/extract planning were split into focused controllers/helpers.

### Known support boundaries

- Encrypted ZIP entries stay on Zip4j.
- AES plus unsupported ZIP methods remain unsupported.
- ZIP creation is plain ZIP only.

## TextView Reader 2.2.4

### Release scope

- Focused on public license packaging, queued archive work, archive safety, and theme editing.

### Final changes included in this release

- First-party source ships under Apache License 2.0 with `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`.
- Compress actions enter the pending-action queue instead of running immediately.
- Pending copy, move, extract, and compress actions share the same queue flow.
- ALZ supports Store/Deflate/BZip2 extraction with CRC verification.
- EGG supports Store/Deflate/BZip2/AZO/LZMA through the first-party parser.
- 7z/CB7 split volumes open from the first part through a concatenated seekable channel.
- Archive preview and extraction include safer path handling, overwrite handling, free-space guards, and cache pruning.
- Main-theme and reading-theme color editors include a palette picker plus HEX/RGB input.

### Known support boundaries

- The older 2.2.4 RAR fallback path is not part of the default Readwide 1.0.0 FOSS-oriented package.
- RAR creation is not implemented.
- ALZ/EGG encrypted, split, solid, and unusual legacy variants remain limited or unsupported.
