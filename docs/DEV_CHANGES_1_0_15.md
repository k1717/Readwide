# Readwide 1.0.15 development changes (vs 1.0.14)

## Document text-selection gesture arbitration

- Leading/trailing tap-zone paging now accepts only a short, stationary, single-pointer release. A hold at or beyond the platform long-press timeout, an active selection, a prior swipe, or multi-touch suppresses the page turn.
- Markdown no longer cancels the native WebView long-press pipeline on `ACTION_DOWN`; cancellation happens only immediately before a confirmed page tap.
- Long-press release no longer invokes `performClick()`, and the horizontal swipe path rejects long-duration selection gestures.
- `LessSensitiveWebView` now closes its delayed/native touch sequence even when the Activity consumes UP/CANCEL, preventing orphaned DOWN events and post-turn phantom selection.
- `TapZoneMath.isShortTapRelease` keeps the decision Android-free and unit-testable.

## Spread boundary and input correctness

- EPUB spread mode now requires `epubImagePageLike`, calculated from distributed spine samples rather than from orientation or fixed-layout metadata alone. Text/reflowable EPUB remains a single responsive-width WebView in landscape; image-page EPUB alone uses the two-WebView spread.
- Forward spread turns no longer clamp to an already-visible right page at an even document boundary.
- Hardware keys and PDF tap paths now use the same spread-aware navigation helpers as buttons and swipes.
- The EPUB right WebView participates in swipe paging and source-targeted double-tap zoom.
- RTL EPUB spreads mirror visual order, tap zones, and DPAD direction.
- `SpreadMath.visibleEndIndex` and `SpreadMath.canTurn` drive labels and boundary state; `SpreadMathTest` covers even/odd end spreads, backward clamping, and single-page navigation.

## Viewer and render correctness

- Document seekbar movement is preview-only until release, avoiding transient corruption of current-page state and unnecessary same-page WebView reloads.
- PDF search/TTS highlights now map both source pages into the final two-page composite, including the post-cap page rectangles, gap, mixed dimensions, and vertical centering. The final odd page keeps the normal identity mapping.
- Search aggregates visible results from both halves, and a current result on the right half no longer navigates away from the displayed spread. TTS retains a pre-render request until geometry commits and generation-guards page-to-page glyph-extraction races.
- Two-page PDF rendering uses one bounded composite plus one opaque white page bitmap at a time. Full-page rendering before composition prevents transparent text paper and translated destination-clip cropping; low-priority neighbor prefetch still uses a smaller cap and catches OOM.
- Cached single pages retain per-page point dimensions and display width, preventing mixed-size PDF cache hits from using stale geometry.
- Continuous rows retain intended layout height after pixel-cap shrink and contain bitmap OOM by dropping caches.
- Obsolete render-task errors are generation-guarded.
- PDF chrome show/hide no longer changes the viewport at all: the accepted bitmap, Matrix fit, padding, cache, and geometry generation remain untouched. Only real geometry changes such as rotation cancel stale speculative prefetch.
- Portrait PDF startup/rotation now rejects incomplete pre-inset toolbar measurements and IME-expanded bottom measurements. The top fallback resolves the active theme's `actionBarSize`, retaining 56dp only as an attribute-unavailable fallback. Exact toolbar-ON fallbacks keep the body frame stable until the new status/cutout/navigation insets arrive. Cached reserves are retained only while they match the current expected status/navigation frame, so an in-place preference or Android navigation-mode change cannot reuse stale padding.
- `PdfReaderActivity` resets document-scoped renderer/search/TTS/highlight/file-identity state on every `singleTop` replacement. Path resolution, lazy PDFBox load, search scan, and TTS extraction each reject stale document/query generations; unused or partially opened descriptors/documents are closed. `PdfTextSearchEngine` queues PDFBox close after the single scan executor and generation-checks result insertion while holding the result lock, so neither concurrent document close nor cross-query result contamination is possible. TTS full-text construction deliberately does not use the page render generation, because normal page turns and cache hits must not cancel a same-document build.

## Immersive fullscreen consistency

- PDF, document/EPUB, TXT, and image/comic viewers share the same system-bar visibility policy. Hiding controls hides the navigation bar immersively; edge swipes reveal transient bars without permanently reducing the reader viewport.
- PDF, document/EPUB, and TXT body roots retain only immutable display-cutout side protection. Live side navigation excess belongs to overlay controls, so landscape content remains full-width and TXT page width/count cannot change when the side bar appears; TXT's vertical bottom spacer remains chrome-gated. Image controls remain overlays on a stable canvas.
- The existing **Show Status Bar While Reading** preference is now honored by every reader family, not only TXT.
- PDF and document/EPUB reassert the active system-bar policy after in-place rotation so OEM bar reappearance cannot become a new body inset.
- Document/EPUB's hidden top page-status strip uses display-cutout insets ignoring visibility and includes the status-bar reserve only when that preference is enabled, so transient bars cannot change the strip or WebView frame.

## General code audit follow-up

- Image chrome became a stable overlay: toggling it no longer changes `ZoomImageView` padding/margins or rebuilds the current fit/zoom matrix.
- EPUB link interception now preserves same-page, cross-page, and right-spread fragments through target page load.
- Bookmark/reading-state/theme JSON uses crash-safe atomic replacement, and `BookmarkManager` serializes all public collection access.
- External URI caching serializes prune/commit work, writes a unique same-directory staging file, flushes and `fsync`s it, and commits with `Os.rename`. A failed open removes only staging and preserves the previous valid cache, while an existing PDFBox/TTS descriptor continues reading the pre-replacement file.
- EPUB/DOCX/HWPX DOM parsing shares `SecureXml`, including a rejecting entity-resolver fallback.
- PDF allocation sizing shares `PdfRenderSize`, which remains within pixel/dimension limits for extreme aspect ratios and integer-boundary inputs.
- Details and remaining device checks are recorded in [GENERAL_CODE_AUDIT_1_0_15.md](GENERAL_CODE_AUDIT_1_0_15.md).
- PDF uses a fixed frame per orientation rather than per chrome state: portrait retains its measured toolbar-ON reserves, while landscape retains the toolbar-OFF safe frame even when controls are visible. EPUB keeps a full-width body frame, with ordinary text EPUB using responsive landscape width and only image-page EPUB enabling a spread.
- Reflowable EPUB applies all four reader boundaries as final HTML body CSS while keeping both WebViews margin- and padding-free. Slider steps explicitly notify the active viewer as well as writing preferences. A boundary signature drives an immediate same-page reload with scroll restoration, making the generated HTML—not optional DOM JavaScript—the authoritative real-time path. Physical px conversion still uses display density initially and `window.devicePixelRatio` for live DOM updates.

## Shared document/PDF proportional fast-scroll refactor

- `ProportionalFastScrollController` now has separate APIs for silent metrics synchronization and real scroll motion. Motion is coalesced with `postOnAnimation`; load/layout updates use bounded 260ms/1s settle passes outside the motion path instead of registering three delayed callbacks on every scroll event. A rail layout listener silently recomputes the thumb after toolbar, inset, rotation, or split-screen height changes.
- The proportional calculation uses `long` range/extent/offset values and retains the 32dp minimum. A zero-travel rail pins the thumb at Y=0 instead of translating a full-height thumb one pixel outside its bounds.
- Alpha-zero thumbs reject `ACTION_DOWN`, and both XML rails are non-clickable, so the invisible 36dp edge area passes taps and swipes to the reader. While visible, the horizontal rail remains the enlarged hit target, but vertical recognition is limited to the thumb plus 8dp at each end.
- Drag setup preserves the exact grab point, tracks the active pointer, snapshots PDF height geometry, and centralizes completion. `UP`, `CANCEL`, active-pointer loss, source invalidation, pause, and destroy all release parent interception and pressed state. Pause hides and suspends the rail; resume performs a no-flash metrics sync. The rail no longer calls `bringToFront`, leaving loading/search overlays above it.
- WebView scroll callbacks explicitly reveal the thumb only for primary-view vertical motion; page load, content-height, scale, and layout callbacks only synchronize metrics. This removes the first-scroll baseline miss and prevents secondary fixed-layout EPUB callbacks from driving the primary WebView thumb.
- PDF continuous mode replaces RecyclerView's visible-row-average fast-scroll mapping with an adapter-owned long-range height model. A Fenwick prefix index combines the default unrendered-page estimate with per-page rendered heights, allowing fraction-to-position lookup without scanning every page. Fast scrub uses `LinearLayoutManager.scrollToPositionWithOffset`, avoiding intermediate ViewHolder binds/render requests; the prefix tree is frozen for the duration of a drag and reconciled on release.
- PDF bitmap/render cache clearing is separated from page-height and horizontal-pan geometry. Background/OOM bitmap eviction no longer collapses row heights or resets position, obsolete-generation OOM callbacks are ignored, and a valid OOM gets at most one half-pixel-cap retry without a dataset-wide notify/rebind loop. Continuous navigation and content-anchor posts carry a mode generation so work queued in vertical mode cannot change the current page after switching to horizontal mode.
- PDF dispatch reserves a gesture sequence for the visible fast-scroll thumb before tap paging, zoom, or horizontal-pan handling. This prevents a rail drag from also turning a page and guarantees the rail receives its terminal event. Continuous reading-state writes are debounced during scrub/fling and committed at idle; thumb release additionally waits for the pending RecyclerView layout before forcing the final page/y-ratio save.

## TTS queue correctness

- Fully resident continuous playback continues from the accepted queue end even within the final page.
- Blank/image-only pages advance during continuous playback.
- Partial prefetch acceptance remains tracked.
- Pause/resume retains cross-page prefetch boundary bookkeeping and blocks delayed speech/prefetch callbacks while paused.
- Delayed saved-state/notification page restarts are generation-token guarded so Stop cancels them.

## Large TXT equivalence verification

- `LargeTextPartitionReader` exposes a package-private per-line transform seam while production continues to provide the unchanged selector/display-rule pipeline.
- `LargeTextForwardCursorEquivalenceTest` compares full-scan and `ForwardCursor` results field-by-field across adversarial chains and 5,400 deterministic randomized requests, including mixed CR/LF/CRLF input, display transforms that create blank lines, blank-line collapse, lookbehind/lookahead changes, gaps, repeats, backward resets, and EOF tails.
- A separate tiling assertion concatenates every sequential canonical body and requires it to equal the complete transformed document, directly guarding against skipped or duplicated text.

## Large TXT search index reuse

- The background full-file count now records up to 200,000 match positions and display-line numbers in primitive arrays.
- Nearest, previous, wrap-around, and nth-result searches reuse the completed index through binary lookup instead of reopening and rescanning the large file.
- Cache identity includes file size/time, query/options, blank-line collapse, and a deterministic signature of all active display rules. Cancellation, file mutation during the scan, and an over-cap match set do not publish an index.
- `LargeTextSearchEngineIndexTest` checks lookup equivalence and asserts that indexed navigation performs no additional file opens.

## Image viewer cache and async safety

- On-demand bitmaps are attached to the live surface before `LruCache.put`, so an item larger than the cache budget cannot be synchronously evicted/recycled before display.
- Full-quality state is recorded only when the cache retained the exact bitmap, preventing an oversized detail decode from leaving a stale quality marker for a later preview at the same index.
- Sequence mutations increment a generation checked by decode-prefetch callbacks. Delete also evicts the index-keyed cache because following pages shift indexes; rename invalidates old path work while preserving the valid current bitmap.
- A failed page load removes an older page surface rather than showing it beneath the new page number. Zoom gesture completion/cancellation and image clearing release parent touch interception.
- Archive comic-mode startup transfers the prepared forward reader into the viewer instead of reopening at byte zero. Unclaimed readers are closed on every handoff/discard/failure path.
- Speculative decode uses two display-sized bitmap workers, with an 8M-pixel safety backstop for tall fit-width pages. A cached display bitmap is not automatically re-decoded; detail is zoom-triggered only. Animated candidates skip bitmap prefetch, and in-flight ownership lasts through the main-thread cache commit.
- Archive decode prefetch is scheduled only after extraction reports success and a committed ready marker is present, rather than from `path.exists()` alone. Worker/main commit validate the sequence and exact index/path, while a valid in-flight neighbor decode remains reusable after a direction-plan change so the new plan cannot lose it to the duplicate-work guard.

## Archive engine routing

- Libarchive-backed forward readers decode-drain skipped payloads through a reusable direct buffer, preserving solid RAR/7z state without a Java byte-array copy.
- Plain PPMd/BCJ2 7z, including standard split volumes, uses libarchive forward streaming in comic mode. Password-protected special-coder 7z retains the first-party AES-capable reader.
- RAR/CBR keeps one libarchive forward reader and the resolved volume chain across loader/viewer boundaries, with existing whole-archive and first-party fallback preserved.
- Password-backed prepared RAR/7z readers are also handed off. `ArchiveImageSequenceLoader.Result` snapshots every sensitive cache path actually verified by lazy/full preparation and the prepared reader, and `ImageSequenceHandoffStore` transfers only that exact set into the viewer's concurrent session set. Freshly decoded entries avoid `loadFully` re-extraction, while an unrelated ready file cannot bypass password-session validation in a mixed-password archive.
- The archive path/length/mtime snapshot now travels through `Result` and the sequence token and is checked again immediately before `ImageReaderActivity` applies cached paths, password state, or the prepared reader. A same-path replacement during the Activity handoff discards the complete stale sequence.
- Forward prefetch checks its plan/user-waiter token between fully drained entries, yielding before an obsolete distant target without interrupting the middle of a solid-stream entry.
- Handoff identity is captured when the reader is created (`path`, `length`, `mtime`) and compared with the later file. Lazy/full sequence preparation performs the same start/end check so replacing an archive at the same path cannot reuse stale metadata or a stale stream.
- Android Zstandard uses libarchive's bundled filter for `.tar.zst` and a SINGLE_ZST-only raw/empty reader for plain `.zst`. The raw reader registers and requires the Zstandard filter, preventing renamed or damaged plain input from being copied as a successful decode. Raw output goes through the same decoded-byte cap as Java streams; raw format is deliberately not enabled on the generic archive reader. Optional Commons codec `LinkageError` is translated into the normal fallback/unsupported path. `zstd-jni` is JVM-test-only and does not enter the release APK.
- The supported matrix and remaining RAR backend-dependent cases are recorded in [ENGINE_ARCHIVE_IMAGE_AUDIT_1_0_15.md](ENGINE_ARCHIVE_IMAGE_AUDIT_1_0_15.md).

## Min-SDK and lint follow-up

- Document HTML search no longer invokes API-35 `CharSequence.isEmpty()` on the API-24 runtime path; it uses the universally available `length()` contract.
- `AnimatedImageDrawable` checks place the API-28 guard before the class/type operation in both the activity and zoom surface.
- Newer framework theme items explicitly declare their intended API to lint, the bookmark icon uses AppCompat `app:tint`, and narrow suppressions document Android 15 lint's constant-set mismatch for the valid `Layout.BREAK_STRATEGY_SIMPLE` calls.
- An earlier full `lintDebug` run succeeded with warnings after these fixes. The source changed afterward, so release verification must rerun `lintDebug` on the exact tagged tree; this document does not claim that the earlier result covers that tree.

## Metadata

- versionCode 10014 -> 10015.
- versionName 1.0.14 -> 1.0.15.
- No dependency or permission was added. `zstd-jni` moved from `implementation` to `testImplementation`; Android runtime decoding uses the existing libarchive dependency.
- Added `app/src/main/assets/open_source_licenses/libarchive_android_and_codecs.txt`, pinning the libarchive-android tag/submodules and carrying the applicable native-component notices inside the APK. Release checks verify both the source file and packaged asset.
