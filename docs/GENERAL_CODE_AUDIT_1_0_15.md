# General code audit and refactoring (Readwide 1.0.15)

This pass followed the spread, TTS, archive, prefetch, and fullscreen fixes. It prioritized user-visible state changes, untrusted document parsing, persistence integrity, allocation math, and duplicated ownership rules over cosmetic lint cleanup.

## Confirmed bugs fixed

### Image viewer chrome stability

- A middle tap changed `ZoomImageView` padding from the full viewport to a 48dp toolbar plus an 82dp sequence-slider reserve, then rebuilt the base matrix. The same bitmap therefore shifted and resized every time information chrome opened or closed.
- The image canvas is now permanently full-screen. Status/navigation insets belong only to toolbar and slider overlays; chrome toggles no longer mutate image padding, margins, or the current zoom/pan matrix.
- `ZoomImageView.performClick()` is now emitted for confirmed/immediate taps so accessibility click semantics match touch behavior.

### PDF and EPUB chrome stability

- PDF chrome OFF previously left a compact top strip or a permanent bottom safe-area boundary, while visible landscape controls could overlap the render viewport. EPUB also changed WebView dimensions when immersive navigation removed its body-column spacer or landscape side inset.
- PDF chrome visibility no longer selects the body frame. Portrait reuses the first valid toolbar-ON top/bottom reserves in both chrome states; its startup fallback resolves the active theme's `actionBarSize`, with 56dp only as a last resort. Landscape always uses the toolbar-OFF frame: only the fullscreen top safety inset is reserved and the bottom reserve is zero, while visible controls overlay the body. System-bar/chrome visibility changes therefore do not resize or rerender the current page.
- The policy is reapplied after rotation, but only orientation/frame identity—not chrome visibility—may change the body reserves.
- EPUB body roots reserve only physical display-cutout sides. Live side navigation insets belong to overlay controls, and the EPUB bottom spacer stays collapsed, so a landscape navigation bar cannot narrow the document canvas or its optional image-page spread.
- The hidden document/EPUB page-status strip uses cutout insets that ignore transient visibility, plus the status-bar reserve only when the reading preference enables it. Temporary system-bar reveals cannot change its height or move the WebView.
- TXT now keeps live side-navigation excess out of the body root too. Only overlay status/title/bottom controls receive that excess, preserving the text width and derived page count across bar visibility changes; the vertical bottom spacer remains chrome-gated.

### EPUB internal links

- Same-page `#fragment` links were consumed by `shouldOverrideUrlLoading()` without scrolling. Cross-page links changed the page but discarded their fragment.
- Link routing now keeps the source WebView, handles left/right spread anchors in the touched pane, recognizes fragment-only URLs produced by the parent-directory base URL, and carries a cross-page anchor until the target DOM has loaded.

### Persistence integrity and concurrency

- Bookmarks, reading states, and custom themes previously overwrote their JSON file directly. A process kill or storage failure during the write could leave empty/partial JSON and lose the previous valid state.
- `AtomicUtf8File` now uses Android `AtomicFile` rollback semantics for these files. `BookmarkManager` public state APIs are synchronized so viewer/TTS/file-operation callbacks cannot concurrently mutate `ArrayList`/`HashMap` while another call serializes them.
- `FileUtils.copyUriToLocal()` previously wrote directly to the stable per-URI destination, so reopening the same URI could truncate bytes still being consumed by an older PDFBox/TTS task. Copy/prune/commit is now synchronized: the current URI directory is protected during the pre-copy prune, a unique same-directory staging file is flushed and `fsync`ed, then atomically renamed over the destination. Failure removes only staging and leaves the prior valid cache intact; already-open descriptors continue to reference the old file. The cache does not yet lifetime-pin every URI directory referenced by a paused viewer, so a later unrelated large open may still prune such an inactive path; active-descriptor reads survive deletion, but a later lazy path reopen is a remaining cache-lifetime boundary.

### Untrusted XML parsing

- DOCX/EPUB/HWPX/file-detection code maintained three slightly different best-effort DOM hardening implementations.
- `SecureXml` centralizes DOCTYPE/external-general/external-parameter/DTD/XInclude/JAXP-access restrictions and installs a rejecting entity resolver as the enforcement fallback when a parser does not expose a feature flag.

### PDF allocation sizing

- Four render paths repeated square-root pixel-cap math. Ultra-thin, extremely long pages could still exceed the cap after the smaller dimension was rounded back to one pixel; malformed dimensions also stressed integer limits.
- `PdfRenderSize` now provides overflow-safe, maximum-dimension-aware sizing for normal pages, neighbor prefetch, continuous rows, and sharpening patches. JVM tests cover ordinary, large, ultra-thin, and `Integer.MAX_VALUE` inputs.

### Android Zstandard backend

- The previous Android path could instantiate Commons Compress over desktop-only `zstd-jni` resources and let `LinkageError` escape from `.tar.zst` or `.zst` work.
- Android now routes tar Zstandard through the bundled libarchive forward/fallback path and opens single `.zst` payloads with a Zstd-filter-only raw/empty native reader. The reader verifies that libarchive actually selected the Zstandard filter, so renamed plain data cannot be copied out as a successful decode. Decoded bytes still pass through the extraction safety cap. The optional Java stream converts linkage failure into a normal unsupported result, and `zstd-jni` is retained only for JVM fixtures.

## Refactoring and verification

- Removed repeated bookmark inset lookups flagged as likely cut/paste errors.
- Added `SecureXmlTest` and `PdfRenderSizeTest` alongside the existing 801-test baseline.
- No permission, runtime dependency, network behavior, file format, or signing configuration was added. The existing `zstd-jni` declaration moved from `implementation` to `testImplementation` so desktop JNI resources cannot enter the Android runtime graph.

Remaining device checks are image chrome toggling at fit and zoomed states, EPUB footnote/backlink navigation in portrait, text-landscape single-page, and image-page spread, and process-kill recovery while reading-state JSON is being updated.

## PDF/EPUB follow-up audit

### Confirmed PDF races and memory failures

- Neighbor prefetch compared only zoom and width, although fit-to-height depends on the usable viewport height. A chrome or immersive transition that changed height without changing width could publish an old-height cached page. Cache acceptance now uses a monotonic geometry generation, avoiding both stale insertion and live `View` reads from the worker thread.
- Background renders wrote `lastRenderedPageWidthPts`/`lastRenderedPageHeightPts` before their generation was accepted. A cancelled render could therefore overwrite the geometry of a newer cache hit. Rendered dimensions are task-local and committed only on the winning UI callback.
- Sharpen-patch allocation did not catch `OutOfMemoryError` and had no request supersession. It now treats the patch as optional, recycles stale/OOM results, and requires both the sharpen request and its base render generation to remain current.
- `PdfPageRenderPlan` now owns shared fit/display/allocation math for visible, prefetch, and continuous rendering. Tests cover height-constrained fit, width-only extreme pages, invalid float inputs, and cap preservation.

### Confirmed EPUB input and classification bugs

- EPUB ZIP text entries were decoded as UTF-8 unconditionally. UTF-16 XHTML and books declaring another HTML charset could render as garbled text and evade layout/image-page detection. `DocumentTextDecoder` handles BOMs, BOM-less UTF-16 markup, and XML/HTML charset declarations.
- `URLDecoder` converted literal `+` to spaces. Fixing only manifest parsing left the same failure in WebView resource interception, EPUB internal links, document extraction, and display-name normalization. `UriPathCodec` now owns percent decoding for all of these URI-path/name surfaces, preserving plus while still decoding `%20`/`%2B`/UTF-8 escapes.
- Fixed-layout viewport regexes required a particular attribute order; rendering could detect `content`-before-`name` metadata but fail to replace it, leaving the publisher viewport authoritative. `EpubViewportParser` now parses and replaces the actual viewport tag independent of attribute order, supports unquoted `name=viewport`, and the common injection path handles headless pages.
- Fixed-layout centering/find CSS used the `background` shorthand and could erase publisher `background-image` page art. It now changes only `background-color`.
- Image-page detection treated `background-image:none` and unrelated image filename text near a non-image object as image evidence. `EpubImagePageClassifier` now scopes signals to real `url()`/`image-set()` declarations and image object/embed attributes.

## Refactoring added in this pass

- Added pure helpers: `PdfPageRenderPlan`, `DocumentTextDecoder`, `EpubViewportParser`, `EpubImagePageClassifier`, and `UriPathCodec`.
- Added JVM tests for PDF render plans, document text decoding, viewport parse/replacement order, URI plus/percent decoding, and EPUB image-classification false positives.
- Restored the repository `.gitignore`; no permissions, network behavior, or version identifiers changed. The only dependency-configuration change is the `zstd-jni` runtime-to-test move documented above.

## Proportional document/PDF scroll audit

### Confirmed input and lifecycle failures

- The alpha-zero thumb left its 36dp rail visible and clickable. At rest this formed an invisible edge zone that could consume WebView taps/swipes or jump the document. The rails are now non-clickable, hidden thumbs reject DOWN, and the active vertical hit area is the thumb plus 8dp at each end.
- Every touch action was gated by the current scroll range. If a PDF mode switch, relayout, or data change invalidated the range between DOWN and UP/CANCEL, terminal cleanup was skipped and `dragging`, pressed state, and parent interception could remain stuck. Cleanup is now centralized and runs independently of current source validity, including pause/destroy and active-pointer loss.
- PDF Activity gesture handling ran before child dispatch and did not exclude the fast-scroll rail. One rail gesture could therefore enter tap paging or horizontal pan and prevent the thumb from receiving UP. A visible-thumb sequence is now reserved for child dispatch from DOWN through its terminal event.
- The rail previously called `bringToFront`, potentially covering loading/search overlays. XML ordering is now retained, keeping the rail above content but below those overlays.

### Motion/metrics separation and long-PDF routing

- Offset comparison was an unreliable proxy for user motion: the first scroll after activation became a baseline and stayed invisible, while an asynchronous row-height change could appear as motion. Callers now report real WebView/RecyclerView movement explicitly; content, layout, scale, and rail-size changes use a no-reveal metrics path.
- Per-scroll callbacks previously removed and registered immediate/90ms/260ms work repeatedly. Motion now coalesces to one animation-frame callback. Content/layout changes use bounded settle passes, including a late pass for WebView image/font height changes.
- PDF no longer interprets RecyclerView's visible-row-average pixel range as a direct giant `scrollBy`. The adapter maintains a long-range Fenwick prefix index from default and rendered page heights, maps a fraction to a row/within-row offset, and lands with `scrollToPositionWithOffset`. Only the target neighborhood is bound. The prefix index is snapshotted during drag to prevent thumb/target oscillation as asynchronous renders report final heights.
- Continuous bitmap eviction no longer clears logical row heights or horizontal pan. Geometry resets only when count/viewport/zoom identity changes. Obsolete-generation OOM callbacks are ignored; valid OOM cleanup evicts non-visible cache entries and permits one reduced half-cap retry without a full rebind/retry loop.
- Vertical navigation/settle/content-anchor posts use a generation invalidated by mode changes and newer navigation. Continuous scroll persistence is debounced during motion and forced at idle. Fast-thumb release also commits once after the pending RecyclerView layout, with a bounded fallback when no new layout is needed, so an already-idle jump saves both its target page and within-page offset.

## Final remaining-bug audit

### PDF orientation frame and spread overlays

- Portrait frame initialization could run before edge-to-edge insets reached the toolbar. The bare app bar or bottom controls were then cached as canonical, while a landscape-first or chrome-hidden rotation could leave the bottom cache at zero. The first later toolbar reveal changed padding and refit the page. Canonical capture now accepts only a complete toolbar-ON measurement, ignores IME-expanded bottom chrome, and uses an inset-aware fallback whose top resolves the theme's `actionBarSize` before falling back to 56dp. A cache whose expected status/navigation frame changes while the Activity remains alive is invalidated and recaptured.
- Some OEMs reveal system bars during an in-place rotation even after immersive mode was active. PDF and document/EPUB now reapply their current bar policy from `onConfigurationChanged`; the subsequent inset dispatch idempotently re-evaluates the fixed orientation frame.
- A real two-page PDF spread previously suppressed search and TTS rectangles because all coordinates were normalized to one source page. The winning renderer now stores each page's final bitmap rectangle after the composite cap. `PdfSpreadHighlightMath` maps per-page rectangles through those exact bounds, covering unequal sizes, vertical centering, and the gap. Search combines both visible halves; TTS reprojects pending speech after render and generation-guards page extraction replacement.
- A live `singleTop` PDF viewer previously retained document-scoped search/TTS controllers while resolving a replacement intent. A late PDFBox load or queued search callback could act on the new viewer, a stale TTS build could attach to it, and a failed/partial replacement could retain a descriptor or save invalid state. Replacement now closes/detaches the old renderer and controllers, clears the old identity, and uses independent document/query generations. PDFBox cleanup runs after the active single-thread scan, while old-query result insertion is rejected atomically under the result lock. Same-document page render generations no longer invalidate full-text TTS extraction.

### Archive/comic concurrency and handoff identity

- Forward archive code reused a ready marker without applying the password-session verification gate used by normal extraction. Sensitive forward/on-demand/prefetch paths now share the same gate and delete an unverified plaintext file under its cache lock before extraction.
- Archive prefetch previously scheduled bitmap decode whenever the target path existed, even if extraction failed and left stale/partial state. Extraction returns success explicitly and the ready marker is rechecked. Decode/commit require the same sequence and exact index/path but intentionally survive a direction-plan change; cancelling a still-valid decode while its in-flight key blocks the new plan would create a missing neighbor prefetch.
- A deep forward prefetch could keep walking to an obsolete target after a page turn. Cancellation/user-waiter checkpoints now run between fully drained entries; no stateful entry is abandoned in the middle.
- Password-backed prepared RAR/7z readers were discarded after the loader, causing a second scan from byte zero. `ArchiveImageSequenceLoader.Result` now snapshots the exact set of every sensitive path successfully verified by lazy/full sequence preparation and the prepared reader, and the handoff transfers that set with reader ownership. The viewer can reuse all just-decoded entries without `loadFully` extracting them again, while supplying one password never promotes unrelated old ready files in an entry-by-entry mixed-password archive.
- Handoff compared `archiveFile.length()/lastModified()` against another `File` object for the same path, so both sides observed the replacement file and always matched. The reader now stores path/length/mtime at construction, lazy/full sequence preparation validates the same start snapshot before publishing results, and that snapshot is carried through `Result`/`Sequence` for one final check immediately before the viewer applies the cached path plan. A mismatch discards the positioned reader and the entire sequence. This deliberately remains a lightweight metadata check: same-length/same-mtime replacement and secondary-volume-only changes are documented residual boundaries rather than silently claimed as covered.

### Static and device verification boundary

- This source handoff intentionally does not run Gradle or Java compilation; the recipient performs the build. Static Java delimiter/string/comment structure, XML parsing, stale-symbol searches, source-only packaging, and ZIP entry reads are performed before delivery.
- Device checks should cover: PDF landscape-first launch then portrait toolbar toggling; Samsung/Android rotation with immersive bars; mixed-size spread search and TTS on each half; edge swipes after the thumb has faded; thumb drag followed by PDF vertical-to-horizontal mode change; pause/rotation during a drag; mixed-size and 10,000-page PDF end-to-end scrub without intermediate render backlog; rapid RAR page turns that supersede deep prefetch; encrypted RAR/7z loader-to-viewer handoff; delayed row-height updates while stationary; and a stale render OOM after zoom/generation replacement.
