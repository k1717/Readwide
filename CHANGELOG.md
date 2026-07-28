# Changelog

## Readwide 1.0.16 - 2026-07-27

### Archive and image reading

- Archive pages can be saved to Downloads or a user-selected folder without recompression.
- Added optional two-page archive viewing in landscape when both adjacent pages are portrait-shaped. RTL placement, mixed single/spread navigation, cache ownership, and companion-page quality follow the visible spread.
- Raised loose-image and archive-preview limits to 24MP. Neighbor-cache previews are upgraded in place, and a long-backgrounded image viewer saves its position and closes instead of reviving stale decoder state.

### File browsing and cover thumbnails

- Added a provider-backed Storage Access Framework fallback when the built-in raw filesystem browser cannot enumerate shared storage. The existing **Internal Storage** entry selects the usable route, remembers a granted folder, and keeps one-time document grants usable when persistence is unavailable.
- Added optional 40×40dp cover thumbnails without shifting the filename column: loose images, folder covers, archive first images, PDF first pages, and EPUB covers are supported in normal and Recent lists.
- Thumbnail decoding and memory/disk caching are bounded. Atomic replacement, stale-source checks, retry cooldowns, bounded failure records, and generation cancellation prevent stale or failed work from destabilizing long folder lists.
- Folder, selection, Recent, and image-viewer overflow menus now size themselves from their localized actions. Hidden-file and cover-thumbnail actions report their on/off state without clipped labels.
- EPUB has a dedicated global default-font preference shared with the in-book font picker.

### PDF reading

- Hiding PDF controls now removes the Readwide title and bottom bars in portrait and landscape, releases their empty layout reserves, and refits the existing bitmap without rendering the page again. Android status and navigation bars remain visible and system-safe insets are preserved.
- Continuous mode retains its visible anchor; single-page mode retains fit/zoom focus. A zero-sized or not-yet-laid-out viewport now waits through a bounded, lifecycle-aware retry instead of starting an invalid render or leaving a callback after destruction.

### EPUB compatibility and bookmarks

- EPUB keeps Android status/navigation bars visible in the active body color, removes its hidden 32dp page-status reserve, and no longer reapplies boundary JavaScript when only Readwide controls are toggled.
- Fixed-layout image pages use a bounded canvas, a PDF-like landscape gutter, and page-local image/text classification. Direct image spine items, mixed fixed/reflowable layouts, CSS background pages, and legacy Japanese vertical-writing CSS are handled without clipping later columns or creating long empty scroll tails.
- Vertical-writing bookmarks now save and restore the visible sentence/glyph position, keep distinct bookmarks on one spine page, and show a useful column-start excerpt. Stale page-load, search, and delayed-restore callbacks can no longer overwrite a newer navigation.
- Expanded the local-only EPUB compatibility path for validated scripted bindings, linked SMIL media overlays, and a safe point-CFI subset. The 45-book sample audit and remaining limits are documented in `docs/EPUB_COMPATIBILITY_AUDIT_1_0_16.md`.

### Reliability

- URI archive copies remain atomic and serialized, but waiting for the shared cache is now interruptible so cancelled SAF/open-document work cannot remain blocked behind another large copy.

## Readwide 1.0.15 - 2026-07-14

### EPUB and PDF - landscape spread correctness

- EPUB landscape spread mode is now limited to image-page books whose sampled spine pages are overwhelmingly image-dominant. Ordinary reflowable and text-based fixed-layout EPUBs return to one responsive-width page in landscape, so a cover image alone cannot force the whole book into a spread.
- Image-page EPUB detection also recognizes SVG, CSS background pages, and image `object`/`embed` pages. Reader theme colors no longer erase a publisher `background-image`, and embedded image canvases are fitted to the viewport.
- In landscape two-page mode, an even-length document no longer shows its final page twice. Once the last complete spread is visible, Next correctly becomes unavailable instead of reopening the already-visible right page by itself.
- Hardware page keys, PDF fast taps, normal tap zones, buttons, and swipes now use the same spread-aware page-turn path instead of some inputs moving only one page and producing overlapping spreads such as `1-2 -> 2-3`.
- Both halves of an EPUB spread now support swipe paging. Double-tapping the right page zooms that page rather than the left page, and right-page taps are checked against the real toolbar coordinates.
- EPUB right-to-left mode now mirrors the spread order and tap/arrow-key semantics as well as the swipe direction.
- The document page slider previews a target without changing the actual current page before release, preventing autosave, TTS, or rotation from observing a page that has not yet been rendered; releasing on the current page also no longer reloads the WebView or loses its in-page scroll.
- PDF search and read-aloud sentence highlights now work on both halves of a real two-page composite. The overlay uses the final post-cap bitmap geometry for each page, including mixed page sizes, the inter-page gap, and vertical centering; the final unpaired page continues to use the normal single-page path.

### Read-aloud (TTS) - continuous queue correctness

- Continuous read-aloud no longer stops after only the prefetched opening of a long final page. It resumes from the exact end of the accepted queue until all resident text has been spoken.
- Blank or image-only pages are skipped during continuous playback instead of ending the session while later pages still contain text.
- Pausing before a prefetched page boundary now preserves the boundary state, so resuming keeps the reader UI synchronized and does not replay the next page from its beginning. Delayed prefetch/page-advance callbacks are also blocked while paused.
- If a TTS engine accepts only part of a prefetch batch, the accepted utterances remain tracked and playback continues from their real end instead of producing hidden speech or losing the screen highlight. Delayed resume/notification-page commands can now be cancelled by Stop instead of restarting playback afterward.

### PDF - rendering robustness

- Two-page PDF spreads now render each full page onto an opaque white temporary bitmap and composite one page at a time into the bounded spread. This prevents transparent text-only PDF paper and avoids the translated-clip path that could trim page tops or bottoms, while keeping peak memory to the composite plus one temporary page.
- Neighbor-page prefetch uses a smaller memory budget, handles speculative out-of-memory failures without terminating the reader, and keeps each cached page's own geometry so mixed-size PDFs do not reuse the previous page's zoom/search coordinates.
- Errors from obsolete/cancelled render jobs no longer overwrite a newer page with a stale load error. In continuous mode, capped tall-page bitmaps keep their intended on-screen height and allocation failure is contained instead of crashing the reader.
- PDF status controls now clear and disable safely when no pages are loaded.
- Replacing a PDF through the viewer's `singleTop` intent now tears down the previous document's renderer, search dialog/engine, read-aloud buffer, and highlight state before resolving the new source. Stale path, PDFBox search, and TTS extraction callbacks cannot navigate or paint into the replacement document; a failed or partially opened replacement cannot leak its descriptor or overwrite saved state. PDFBox document close is serialized behind any active scan, and an obsolete query cannot append results after a newer query clears them. Same-document page turns no longer cancel a valid long-running TTS text build.
- Showing or hiding PDF controls now leaves the accepted page bitmap, fit matrix, viewport padding, render generation, and page cache untouched. Portrait position and landscape full-canvas sizing therefore remain identical across the toggle.
- Opening in landscape, rotating while controls are hidden, or receiving system insets after the first layout no longer leaves the portrait PDF frame with zero/incomplete toolbar reserves. Only complete portrait measurements become canonical, with toolbar-ON fallbacks used until exact insets arrive; the top fallback resolves the active theme's `actionBarSize` before using 56dp as a last resort, and IME height is never cached as reader chrome. Changing the reading status-bar preference or Android gesture/3-button navigation while the viewer remains alive invalidates the old frame before it can leave a gap or toolbar overlap.

### Reader fullscreen and system bars

- Hiding reader controls now enters immersive navigation mode in PDF, TXT, EPUB/document, and image/comic viewers. A swipe can reveal Android's navigation controls transiently without restoring a permanent layout boundary.
- PDF, document/EPUB, and TXT keep their body canvas independent from live side-navigation insets; immutable display cutouts still protect the body while overlay controls own the live side inset. TXT page width and page count therefore stay stable when a transient or side navigation bar changes visibility; its vertical bottom spacer remains chrome-gated.
- The image/comic viewer no longer reserves 48dp toolbar or 82dp slider regions inside the image canvas. Those controls overlay a stable full-screen page in both chrome states.
- **Show Status Bar While Reading** now applies consistently to PDF, EPUB/document, and image/comic viewers as well as TXT.
- PDF and EPUB/document readers reassert the current immersive policy after an in-place rotation, preventing OEM system bars from reappearing and narrowing or shifting the body canvas.
- The hidden document/EPUB page-status strip derives its top reserve from display-cutout insets that ignore transient visibility, plus the status-bar reserve only when **Show Status Bar While Reading** is enabled. A transient system-bar reveal therefore does not resize the strip or move the WebView.

### General audit and refactoring follow-up

- Long-pressing selectable text in the leading or trailing tap zones no longer turns the page on release. Long-hold, multi-touch, and active-selection gestures remain owned by the WebView, while short taps still use the configured page-turn zones. Consumed tap releases also clear the delayed WebView touch state, preventing a phantom selection after the page changes.
- Opening or closing image/comic information controls no longer changes the image viewport padding or rebuilds its fit matrix. The image stays fixed while the toolbar and slider overlay it.
- EPUB same-page footnotes/backlinks now scroll to their anchors, cross-page links retain their fragments after loading, and links in the right spread pane target that pane.
- Bookmark, reading-state, and custom-theme JSON writes are crash-safe and atomic. Bookmark state access is serialized so background/viewer callbacks cannot mutate collections during persistence.
- External URI copies are serialized and written to a unique same-directory staging file, flushed and synchronized, then committed with an atomic rename. A failed replacement removes only its staging file, preserving the previous valid cache; an already-open PDFBox/TTS reader can continue reading the old file while a `singleTop` open commits the replacement path.
- Untrusted EPUB/DOCX/HWPX XML parsing now uses one hardened builder with an entity-resolver fallback instead of three inconsistent best-effort implementations.
- PDF render-size capping is shared and overflow-safe across normal, continuous, prefetch, and sharpening paths, including extreme thin/tall pages.
- PDF chrome visibility no longer changes the body frame. Portrait keeps one cached toolbar-ON frame in both states, preventing vertical movement; landscape keeps the toolbar-OFF safe frame in both states, so visible overlay controls cannot shrink the page. EPUB keeps a full-width WebView frame while only overlay controls consume live side-system insets.
- Reflowable EPUB boundary sliders now notify the active viewer directly on every step. The viewer first updates the live DOM and then reloads the same page with the new CSS while restoring its scroll position, so reflection no longer depends on JavaScript execution being permitted by a JavaScript-disabled WebView. Values remain physical screen pixels through the `devicePixelRatio` conversion.
- The shared 36dp document/PDF side scroller now separates real scroll motion from silent metrics refreshes. Motion updates are coalesced once per frame; content/layout and toolbar/inset changes update the thumb without flashing it. The thumb has a 32dp minimum, fades to alpha zero at rest, and its invisible rail no longer captures reader taps. Drag termination always releases pressed/parent-intercept state, including mode changes, pause, cancellation, and multi-pointer interruption.
- PDF continuous fast-scroll uses a long-range page-height index refined by rendered page heights. It jumps directly with `scrollToPositionWithOffset` instead of issuing one huge `scrollBy`, so a jump across a very long PDF does not bind and enqueue every intermediate page. A drag freezes its height index until release; bitmap eviction preserves row geometry/pan state, and stale OOM callbacks cannot clear a newer adapter generation.

### PDF/EPUB follow-up audit and refactoring

- PDF visible-page, neighbor-prefetch, and continuous-mode sizing now share `PdfPageRenderPlan` and allocation-cap math. Single-page/prefetch paths retain their fit-to-viewport height constraint, while continuous rows intentionally remain width-led and preserve their logical display height; each path keeps its own memory cap.
- Neighbor prefetch invalidates on real viewport-height changes such as rotation, while toolbar visibility alone no longer changes that height. Speculative workers no longer read live Android `View` geometry off the background thread.
- Cancelled PDF renders no longer overwrite the visible page's point dimensions. Sharpen-patch requests are tied to both their own request and the accepted base render, so a patch from before rotation/chrome relayout cannot attach to a replacement bitmap; out-of-memory failure falls back to the fit page.
- EPUB spine text entries now decode UTF-8/UTF-16/declared HTML charsets rather than assuming UTF-8. A shared URI-path decoder preserves literal `+` characters while percent escapes still decode normally across manifest parsing, WebView resource loading, internal navigation, extraction, and display-name normalization.
- Fixed-layout viewport metadata is parsed and replaced independently of attribute order (including an unquoted `name=viewport`) and inserted even when the source page has no `<head>`. Fixed-layout theme/find CSS now uses `background-color`, preserving publisher `background-image` pages.
- Image-page EPUB classification moved to a pure tested helper and no longer treats `background-image:none` or an unrelated image filename near a non-image `<object>` as proof of an image-page book.

### Large TXT and image viewer follow-up

- The large-TXT forward cursor is now covered by a permanent JVM equivalence suite that compares the real cursor and full-scan reader fields across adversarial and randomized request chains, then verifies that sequential body windows reconstruct the canonical transformed text without skips or duplication.
- A completed large-TXT background match count now retains a bounded primitive position/line index (up to 200,000 matches). Subsequent next, previous, wrap-around, and nth-result navigation for the same file/query/options/display rules uses binary lookup instead of scanning the file from the beginning again. File size/time, blank-line mode, search flags, and display-rule content are part of the cache identity; cancellation never publishes a partial index.
- The image viewer no longer risks displaying a bitmap recycled by an immediate oversized-cache eviction, and it records “full quality” only when that exact bitmap was retained by the cache.
- Deleting an image now invalidates the index-keyed bitmap cache and stale prefetch results, so shifted sequence indexes cannot show the deleted or preceding page. Rename-time decode work is invalidated without discarding the still-valid displayed bitmap.
- A failed next-page decode clears an older page instead of leaving it visible under the new page number, and completed/cancelled zoom gestures release parent touch interception cleanly.

### Archive and comic-mode engine efficiency

- The archive page prepared while opening comic mode now hands its still-open forward reader to `ImageReaderActivity`. RAR/7z/TAR no longer decompress from the archive start once in the loader and a second time after the viewer opens.
- Libarchive-backed comic paging now decode-drains skipped/cache-hit entries through a reusable direct buffer. This preserves the sequential dictionary/window required by solid RAR while avoiding a second copy through a Java byte array; a header-only skip is no longer used for stateful archives.
- Plain 7z archives containing PPMd or BCJ2 folders now use libarchive's forward stream in comic mode, including the complete `.7z.001` volume list. AES+PPMd/BCJ2 keeps the existing first-party decoder because bundled libarchive cannot decrypt 7z. Common 7z methods remain on Commons Compress.
- RAR/CBR uses the same session-scoped libarchive forward stream (with the resolved volume chain) and retains whole-archive/single-entry fallback for backend-specific variants. The common user RAR samples and password-protected 7z sample were rechecked end to end.
- Android Zstandard no longer calls desktop-only `zstd-jni` natives. `.tar.zst` forward/list/extract paths use the bundled Android libarchive filter, raw `.zst` uses a raw/empty libarchive stream with the decoded-size safety cap, and missing optional codecs now fail or fall back without a `LinkageError` crash. `zstd-jni` is retained only for JVM fixtures and does not enter the APK runtime graph.
- Speculative neighbor bitmap decoding now uses two workers and targets the actual display size, with an 8M-pixel safety backstop only for very tall fit-width pages. Turning onto that cached page does not launch a second detail decode; original/detail quality is requested only by an explicit zoom gesture. Animated GIF/WebP candidates bypass bitmap prefetch so a static first frame cannot be mislabeled full quality.
- Password-backed forward readers now apply the same session-verification gate as normal extraction before reusing plaintext preview files. `ArchiveImageSequenceLoader.Result` hands off the exact set of every sensitive cache path successfully verified during lazy or full sequence preparation, including paths decoded by the prepared reader; the viewer can reuse those files without `loadFully` extracting them again, while unrelated old ready files in a mixed-password archive are never promoted. Failed extraction cannot schedule decode from a stale/partial path; a valid display-sized bitmap already in flight remains reusable by a newer direction plan instead of being discarded while the duplicate-work guard blocks its replacement.
- RAR/7z deep prefetch yields between fully drained entries when a page request or newer plan supersedes it, while preserving solid-stream state within an entry. The prepared password-backed reader is handed to the viewer instead of decoding again from byte zero.
- Archive handoff compares the original path/length/mtime snapshot rather than two live views of the same `File`, carries that identity with the sequence, and checks it again immediately before the image viewer applies cached paths. A file replaced during preparation or the launch handoff is rejected instead of pairing stale entry metadata with the new archive.
- Complete RAR compatibility is still not claimed: uncommon RAR3/RAR4 classic-LZ/PPMd table transitions, non-standard VM filters, damaged/recovery edge cases, and unverified split/encryption combinations remain libarchive-dependent or cleanly unsupported. No dependency or permission was added.

### Internal cleanup

- Added and reused shared spread-boundary helpers and JVM tests, including committed large-TXT full-scan/cursor equivalence coverage.
- Ordered the EPUB boundary preference listener after its lifecycle-state fields, fixing the Java `illegal forward reference` reported by the release build.
- Replaced an API-35-only `CharSequence.isEmpty()` call in document search with a minSdk-safe length check, ordered animated-image SDK guards before API-28 class checks, and resolved the remaining debug lint errors without changing permissions or dependencies.
- Removed unused spread and obsolete document-toolbar helper methods.
- Android metadata is now `versionCode 10015` and `versionName "1.0.15"`. No dependency or permission was added; `zstd-jni` moved from the APK runtime configuration to JVM tests only.
- Added a source-controlled APK notice asset for libarchive-android and its bundled codecs, with pinned upstream revisions and the applicable BSD, 0BSD, zlib, and Apache-2.0 terms. Release checks now verify that the notice survives APK packaging.

## Readwide 1.0.14 - 2026-07-08

### Release scope

- Android metadata is `versionCode 10014` and `versionName "1.0.14"`. It keeps the `com.readwide.manager` applicationId and the `readwide` release signing key, so 1.0.14 updates in place over 1.0.13 and earlier compatible Readwide builds.

### EPUB and PDF - landscape two-page view

- EPUB documents now switch automatically to a side-by-side two-page spread in landscape orientation on phones and tablets. Portrait remains single-page.
- PDF single-page mode now renders a side-by-side two-page spread in landscape orientation on phones and tablets. PDF vertical continuous mode remains unchanged.
- Page buttons, tap zones, and page-swipe gestures advance by one spread in landscape two-page mode. The page indicator shows the visible range, such as `3-4 / 20`, and direct page jumps still land on the requested page.
- In landscape two-page mode, the controls overlay the spread instead of shrinking or re-rendering it. Portrait keeps the existing below-the-bar layout.
- EPUB taps, tap-zone page turns, and swipes work on both halves of the spread. The far page edges turn pages, and the center area, including the seam, toggles the controls.

### Documents - rotation correctness

- Markdown recomputes its visual pages when the device rotates, so the page count, page turns, and read-aloud start position match the new orientation.

### TXT reader - title, chrome, and line endings

- Text files now open with the reader controls and theme-colored file title visible, matching the document and PDF viewers. Tapping the page hides them as before, and restored sessions keep their previous chrome state.
- With controls visible, the text reader's top area becomes one solid theme-colored band covering the status/camera area, page indicator, and title strip. The title uses the body font at a smaller size and is clamped to the masked first-line row.
- Text files with old-Mac CR or Windows CRLF line endings display and position correctly in whole-file view. Saved positions, search, and read-aloud offsets now use the same character space as the large-file engine.
- Text files opened from apps or storage providers that do not report a display name now fall back to the file's own name instead of showing a blank title or misrouting the file.

### Large TXT and image-viewer efficiency

- Sequential large-TXT partition reads avoid repeatedly re-reading the file from the beginning during forward movement. Backward movement and setting changes reset the forward path safely.
- Revisiting a page in the image viewer no longer re-decodes it when a full-quality cached bitmap is still available. Re-decode is reserved for pages evicted under memory pressure.

## Readwide 1.0.13 - 2026-07-04

### Read-aloud (TTS) - sentence highlight in the document viewer

- The document viewer (EPUB, Word, HWP/HWPX, Markdown) and the PDF viewer now highlight the sentence being read aloud, like the text reader already did. The document viewer also scrolls it into view.
- Read-aloud no longer speaks each page's title before the content in EPUB and similar documents.
- Markdown read-aloud starts from the first line on screen instead of the top of the document, and restarting continues from the sentence that was being heard.

### File actions - case-only rename

- Renaming a file or folder to the same name in a different case (for example `test` to `tESt`, or Title Case to lowercase) now works. It previously appeared to succeed but left the name unchanged on storage that treats upper- and lowercase names as the same.

### Read-aloud (TTS) - "continue reading aloud" fixes for document and PDF viewers

- Resuming from the main-screen "continue reading aloud" prompt now works correctly in every viewer. Markdown opened in the plain-text reader instead of the document viewer (the view visibly changed); EPUB, Word, and PDF resumed from the top of the saved page instead of the exact spot playback stopped; and PDF did not auto-start at all.

## Readwide 1.0.12 - 2026-07-04

### Main screen - double-tap open

- Quickly double-tapping a file no longer opens the same file twice (which previously required pressing back through a duplicate viewer).

### Read-aloud (TTS) - floating card in more viewers

- The floating play/pause and stop card shown during read-aloud in the text reader now also appears in the document viewer (EPUB, Word, HWP/HWPX, Markdown) and the PDF viewer, so you can pause, resume, and stop without opening the dialog. It can be dragged anywhere on screen.

### Read-aloud (TTS) - PDF start crash fixed

- Starting read-aloud in the PDF viewer no longer crashes the app when PDF search was never opened first (the text extractor now initializes the PDF library itself instead of relying on the search feature having done it).

### Read-aloud (TTS) - quotation-mark muting at the strongest pause reduction

- The Aggressive pause-reduction level now also mutes quotation marks (straight, curly, and CJK corner brackets), so dialogue-heavy text keeps moving on neural voices that pause at every quote. Off and Medium leave quotes untouched.

## Readwide 1.0.11 - 2026-07-03

### Release scope

- Android metadata is `versionCode 10011` and `versionName "1.0.11"`. It keeps the `com.readwide.manager` applicationId and the `readwide` release signing key, so 1.0.11 updates in place over 1.0.10, 1.0.9, 1.0.8, 1.0.7, and 1.0.6.
- This release adds a reader for legacy binary Word documents (`.doc`, the Word 97-2003 format). Previously only the newer `.docx` family opened; a `.doc` was grouped under Word but reported as unsupported. The reader is a self-contained pure-Java parser and adds no new dependency and no new permission.

### Documents - legacy .doc rendering

- `.doc` files (the OLE2 compound-file Word 97-2003 format) now open in the document viewer. A read-only compound-file reader extracts the WordDocument and table streams, the piece table reconstructs the document text (both the 16-bit UTF-16 and the 8-bit/compressed piece forms are handled), and the result is shown through the same paginated viewer used for `.docx` and HWP, so paging, search, and bookmarks work the same way.
- Character formatting is applied. Bold, italic, underline, strike-through, font size, and text color are decoded from the document's character-property runs, and each paragraph is split into styled runs at formatting boundaries.
- Paragraph formatting is applied. Horizontal alignment (left, center, right, justified) and left, right, and first-line (including hanging) indents are decoded from the paragraph-property runs and applied per paragraph. Tables and inline images are not yet rendered: table cells are flattened to their text and pictures are omitted, so a heavily laid-out `.doc` opens as readable, formatted text rather than a pixel-faithful reproduction.
- Detection is by content, not by extension. A Word-grouped file is routed to the legacy reader only when it is an actual OLE2 compound file, so a mis-named `.doc` that is really a `.docx` still opens through the `.docx` path, and a `.doc` that is really OOXML is handled correctly.
- The parser is pure Java added under the app, needs no `INTERNET` permission, and pulls in no library. `.docx`, HWP/HWPX, PDF, and the archive/comic viewers are untouched by the `.doc` reader itself.

### Archives - Zstandard and LZ4

- The tar family gains Zstandard and LZ4 members: `.tar.zst` (also `.tzst`) and `.tar.lz4` archives now list and extract like the existing `.tar.gz`/`.tar.bz2`/`.tar.xz`/`.tar.lzma`/`.tar.Z` forms, including numeric split parts (`.001`) and the sequential image-reading path used by the comic viewer.
- Single-file `.zst` and `.lz4` files decompress like the existing `.gz`/`.bz2`/`.xz`/`.lzma`/`.Z` forms, restoring the original name (`notes.txt.zst` extracts to `notes.txt`).
- The initial implementation used `zstd-jni` for JVM fixtures; the final Android path uses the Zstandard filter already present in bundled libarchive, while LZ4 remains on the pure-Java framed reader in Commons Compress. `zstd-jni` is test-only and does not enter the APK runtime graph.

### Home screen - recent list swipe

- Swiping a recent-file row left to remove it from the read list now requires a clearly horizontal gesture. Diagonal drags (steeper than about 27 degrees off the horizontal) no longer grab the row, so a sloppy vertical scroll cannot slide a card sideways or delete it. Once a legitimate horizontal swipe has started, finger wobble during the drag does not cancel it, and the existing 45%-of-width commit threshold is unchanged.

### Archives - 7z PPMd, BCJ2, and Deflate64 coverage verified

- This paragraph records the intermediate backend-only coverage before the first-party PPMd and BCJ2 sections later in this same release. At that stage Deflate64 decoded on the pure-Java path and unencrypted PPMd/BCJ2 used libarchive, while AES combinations had no complete route. The later first-party sections below supersede that AES limitation and describe the final scope.
- One listing fix came out of running the new tests on a plain JVM: entry listing had iterated 7z entries with `getNextEntry()`, which in Commons Compress also builds each entry's decoder chain and therefore throws for PPMd ("Unsupported compression method [3, 4, 1]") and BCJ2 ("Multi input/output stream coders are not yet supported") even though the names and sizes are fully readable from the header. Listing now walks the parsed header metadata via `getEntries()` instead, so PPMd/BCJ2 archives are browsable on the primary path without needing the libarchive fallback at all; extraction is unchanged (decode paths still use `getNextEntry()` and fall back to libarchive). Password behavior is unchanged too: header-encrypted archives still prompt at open (the header itself cannot be parsed), and AES content streams fail only when read.

### Archives - EGG fixes for real ALZip files, and split volume support

- The EGG reader now opens real ALZip-created archives. Verification against genuine ALZip files uncovered three layout bugs the previous synthetic test files had masked: the archive header's extra-field prefix (terminated by an END field) was not parsed, so every real file was rejected as unsupported; the END field that terminates each block header was not consumed, shifting every data offset by four bytes; and the LZMA properties were read from the wrong position in the block preamble. All three are fixed and the reader is verified end to end (CRC-checked) against real store, deflate, and LZMA archives, including Unicode file names.
- Split EGG archives (`name.vol1.egg`, `name.vol2.egg`, ...) now list and extract. Volumes are presented as one logical stream (the first volume whole, later volumes minus their own headers) with the prev/next header-id chain validated volume by volume, so blocks that straddle a volume boundary decode correctly and a missing or mismatched volume fails cleanly instead of producing partial output. Opening any volume of the set resolves to the full chain.
- ZipCrypto-encrypted EGG entries (ALZip's default password mode) now extract. The password is verified before output and the per-file keystream continues across blocks. At this intermediate stage AES/LEA and solid EGG were still refused; the later solid and AES sections below supersede that boundary. The final unsupported cases are LEA-encrypted entries and encrypted-solid archives. The container layout is documented in `docs/EGG_FORMAT_NOTES.md`.

### Archives - ALZ bzip2: the ALZip 4.x bitstream variant now decodes

- ALZip 4.x-era archives compressed with bzip2 (method 1) carry a trimmed bitstream variant, not standard bzip2: the stream magic, block-size byte, per-block CRC, randomised flag, and end-of-stream CRC are all absent, and block framing is `DLZ`+0x01/0x02 - so standard decoders fail on them at the first block header, and the previous "prepend the missing magic" path could never decode one. A first-party decoder for the variant was added (`AlzBzip2InputStream`, a modified copy of Apache Commons Compress's Apache-2.0 `BZip2CompressorInputStream` with the framing differences applied; change notice in the file and in `THIRD_PARTY_NOTICES.md`). The first payload bytes select between the variant and plain bzip2 (which some real archives carry, and which keeps working), so both flavors extract with the container CRC verified. The variant bitstream facts follow the zlib-licensed unalz reference decoder, and the embedded test fixtures - a single-block and a four-block stream - were validated byte-identical through a compiled unalz 0.65 before being checked in. Details in `docs/ALZ_FORMAT_NOTES.md`.

### Archives - solid EGG archives now extract

- Solid EGG archives (ALZip's "solid compression" option, marked by the SOLID field in the archive header) previously listed their entries but refused extraction. They now extract: the decoded blocks form one continuous stream carrying every file's data in order, and a single sequential pass splits it by each entry's declared size, verifying every block's CRC as in the non-solid path. Extracting one entry from a solid archive decodes from the stream start up to that entry and stops there. A stream shorter than the declared entry sizes fails cleanly with no partial output. The layout and CRC semantics were validated as a black box against ESTsoft's own unegg decoder (first-party fixtures with a block boundary falling inside a file extract byte-identically through it); no vendor code was used. Encrypted solid archives remain unsupported. Details in `docs/EGG_FORMAT_NOTES.md`.

### Archives - AES-encrypted EGG entries now extract

- AES-128 and AES-256 encrypted EGG entries (ALZip's non-default encryption options) now extract with the password. The scheme is the WinZip AES construction - PBKDF2-HMAC-SHA1 (1000 iterations) key derivation from the per-file salt, AES-CTR data with a little-endian counter, a 2-byte password verifier, and a 10-byte HMAC-SHA1 footer over the ciphertext - implemented with the platform's JCE primitives only. A wrong password is rejected at the verifier before any output; tampered ciphertext is rejected by the HMAC footer ("EGG AES data authentication failed"); non-ASCII passwords are also tried as legacy Windows-949 bytes. The scheme was confirmed by black-box testing against ESTsoft's own unegg decoder: first-party-built AES archives (store and deflate, both key sizes) decrypt byte-identically through it with the password, and those oracle-validated fixtures are embedded in the unit tests. LEA-encrypted entries remain unsupported and are reported by name. Details in `docs/EGG_FORMAT_NOTES.md`.

### Image preview - smoother continuous paging through sequential archives

- Continuous paging through RAR/7z/compressed-TAR previews is decode-bound: the shared forward stream can only produce entries so fast, and the fixed symmetric three-page prefetch window left it idle exactly when a reader flipping steadily forward was about to need eight more pages. Prefetch planning is now direction-aware (`ImagePrefetchMath`): after two page turns in the same direction the file-extraction window deepens to eight pages ahead (keeping one behind for a quick step back), while the decoded-bitmap warm-up stays at the nearest neighbors so memory use is unchanged. Stale prefetch plans - windows centered on pages the user already left - now exit as soon as a newer page turn re-plans, and the existing rule that read-ahead always yields the shared stream to the page the user actually requested is unchanged. Jumps and direction changes fall back to the historical symmetric window.

### Documents - read-aloud button on the bottom toolbar

- The TTS button that the text reader has always had on its bottom toolbar is now also on the document viewer's (EPUB/Word/HWP/PDF-text and the other page-based formats). It appears once the document's pages are ready and read-aloud is supported (Markdown, which has no read-aloud, never shows it), and opens the same dialog as the existing More-dialog row, which stays in place.

### TXT reader - switching files no longer resurrects the previous file

- Opening file A, switching to file B in the same reader, and then passing through a background memory trim could reopen A over B: the trim stores a restore intent for the file it released, and the file switch never discarded it. `onNewIntent` now saves the previous file's state and then drops every transient restore artifact (pending trim, released-memory flag, stored restore intent, loaded text snapshot, in-flight large-TXT partition switch) before the new file loads, and the restore path independently verifies that the stored target matches the reader's current target before executing - with a deliberately strict rule: when the current intent names an explicit path or URI, only the intent decides, so a mid-switch stale loaded-file path can never let the previous file's restore through. Matching logic lives in the pure-Java `ReaderRestoreTargetMath` with unit tests covering the mismatch and fallback cases. Large-TXT autosave needed no change: the displayed-page getters already report the pending partition-switch page in 1.0.11.

### Archives - filenames in legacy encodings no longer break per name

- Entry-name charset detection for ZIP/ALZ/EGG now makes one decision per archive instead of one per name, borrowing the text viewer's encoding-detection principle (judge one large sample once) at a scale that works for filenames: readers collect every legacy-path raw name during the entry scan, pick the single code page that best explains all of them, and decode every name with that shared decision - so a one-syllable CP949 name can no longer flip to a different encoding than its longer siblings. Scoring also gained structural naturalness checks (Thai combining-mark placement, Greek final-sigma and accent positions, Russian marker-letter positions, and case-structure sanity for Cyrillic/Greek), which fixes cases per-name scoring could never win, such as DOS-era Russian archives losing to Thai at any name length. UTF-8 flags, valid UTF-8, and EGG locale hints keep their existing precedence. Validated across eight language corpora plus genuine Thai/Greek/Turkish guards against over-correction. Details in `docs/ARCHIVE_NAME_CORPUS_READWIDE_1_0_11.md`.

### Archives - RAR5 header-encrypted (-hp) archives now open

- RAR5 archives created with header encryption (`-hp`) previously had no path at all: the bundled libarchive cannot decrypt RAR5 headers (it cannot even list such archives), and the first-party reader raised a clean unsupported error. The reader now derives the header key from the archive encryption block (reusing the real-file-validated `Rar5Crypto` key derivation) and reads every following header through AES-256-CBC, after which all existing first-party machinery - stored-entry decryption, compressed decrypt+decode, solid chains, split payloads, and multi-volume resolution - applies unchanged. Validated byte-for-byte against UNRAR 7.00 across stored/compressed/max-compression/solid/multi-volume `-hp` fixtures, Korean entry names, and an 84 KB mixed payload; missing and wrong passwords re-prompt cleanly with no partial output. Details in `docs/RAR5_HEADER_ENCRYPTION_READWIDE_1_0_11.md`.

### Archives - 7z PPMd first-party decoder; AES-encrypted PPMd now extracts

- 7z archives compressed with PPMd now decode first party, closing the last unsupported 7z combination: AES-encrypted PPMd archives, which no bundled backend could handle (Commons Compress has no PPMd coder, and the bundled libarchive cannot decrypt 7z). `SevenZPpmd7Decoder` is a Java port of the public-domain Ppmd7 reference (Dmitry Shkarin's PPMd var.H, Igor Pavlov's Ppmd7 codec - both explicitly public domain, which is Apache-2.0 compatible; provenance in `THIRD_PARTY_NOTICES.md`). The model lives in one flat byte array mirroring the reference memory layout, because in PPMd the memory allocator is part of the format: the encoder and decoder must rescale, glue free blocks, and restart the model at exactly the same points. Validation followed the project's oracle discipline - a Python reference was debugged to byte-exactness first, then the Java port re-verified against the reference `7z` tool and pyppmd across orders 2-32, memory sizes 64 KiB-1 MiB, and text/repetitive/random/zero payloads, with the hard paths instrumented to confirm they ran (one fixture alone triggers 8 model restarts and 7 free-block glue passes, all byte-exact). Wired into the same gated fallback as BCJ2: only archives actually containing PPMd or BCJ2 folders are intercepted, and on any failure other than a missing password the previous libarchive path still runs, so existing behaviour cannot regress. Plain PPMd, and AES+PPMd with an encrypted header, all extract byte-identically; wrong passwords fail cleanly. Details in `docs/SEVENZ_PPMD_READER_READWIDE_1_0_11.md`.

### Archives - 7z BCJ2 archives now extract, including AES-encrypted ones

- 7z archives whose entries use the BCJ2 branch filter (7-Zip applies it to executables; it is a four-input coder) now extract. Apache Commons Compress cannot decode BCJ2 - it rejects the folder with "Multi input/output stream coders are not yet supported" - and the bundled native libarchive cannot decrypt 7z at all, so AES-encrypted BCJ2 archives previously had no working path and plain BCJ2 depended on the native fallback. A first-party 7z reader (`SevenZBcj2ArchiveReader`) now parses the container, resolves each folder's coder graph, decodes the base streams with the bundled LZMA/LZMA2 decoders, decrypts AES-256 folders (including archives with an AES-encrypted header) with a first-party 7z key schedule, and applies a first-party BCJ2 join. It runs only as a fallback and only when the archive actually contains a BCJ2 folder, so all other 7z archives are unaffected. Everything is clean-room Java from the documented 7z format and published algorithms (no 7-Zip or libarchive source); self-made fixtures across three coder chains (BCJ2 over stored inputs, BCJ2 over LZMA, and AES+LZMA+BCJ2 with an encrypted header) decode byte-identically to the reference 7z tool. Details in `docs/SEVENZ_BCJ2_READER_READWIDE_1_0_11.md`.

### Archives - RAR/7z encryption boundaries verified against real archives

- During the 1.0.11 work, the encryption edges of RAR and 7z were re-checked with real archives (WinRAR 7.00 for RAR5, p7zip for 7z). At that intermediate checkpoint, libarchive could not decrypt RAR5 `-hp` headers or 7z AES content, and the first-party RAR5-header, PPMd, and BCJ2 paths had not yet landed. Those temporary gaps were subsequently closed by the first-party implementations documented in the three sections immediately above. The lasting result of this checkpoint was precise password/unsupported classification and byte-exact confirmation of password-protected stored RAR5 extraction; it is not a current unsupported-format claim.

### Archives - ALZ revalidation and split support

- The ALZ reader was re-verified against real ALZip-created archives the same way EGG was, with a better outcome: store, deflate, bzip2, ZipCrypto-encrypted entries, and CP949 file names already decoded correctly (CRC-verified; the `?` characters in some legacy names are baked into the file by ALZip itself for characters CP949 cannot encode).
- Split ALZ archives (`name.alz` + `name.a00`, `name.a01`, ...) now extract. Previously the app resolved a continuation part to the first `.alz` but read only that one file, so anything past the first segment was cut off. Segments are now presented as one logical stream with each segment's framing (a 16-byte trailer per segment, plus an 8-byte header on continuations) stripped, so entries that straddle a segment boundary decode normally; a missing segment fails cleanly with no partial output, and opening any part of the set resolves the whole chain. Verified end to end (CRC) against a real two-segment archive.
- Directory and empty-file entries that omit the method/CRC/size fields (a zero size nibble in the header) no longer cause the whole archive to be rejected as unsupported.
- Internals: the EGG split-volume view was renamed `EggVolumeInput` -> `SplitVolumeInput` and is now shared by both readers; the ALZ-only `RandomAccessFileBoundedInputStream` class became unused and was removed. Format notes added in `docs/ALZ_FORMAT_NOTES.md`.

### Image preview - split archives

- Viewing images inside numeric split archives (`comic.zip.001` style) is much faster: the volumes are assembled once for the whole viewing session instead of once per page and per prefetched neighbor.

### Read-aloud (TTS) - no more silence on unmatched voices, smoother page seams

- Text-to-speech no longer goes silent when the selected language or voice is not available on the installed engine. Previously, if the engine reported the chosen locale as unsupported (common with neural engines such as sherpa-onnx / VoxSherpa and their Kokoro/Piper voices, which often expose only their own default locale), read-aloud stopped before producing any audio. It now falls back to the engine's default voice, then the device default, and only reports a problem if none can speak - so "Play" produces sound instead of nothing.
- Continuous read-aloud no longer pauses audibly at page boundaries. For documents whose text is fully in memory, the start of the next page is now queued onto the speech engine ahead of time, while the current page's last sentence is still playing, so a high-latency neural voice keeps synthesizing across the seam instead of leaving a gap. The on-screen page turn, sentence highlight, and saved position follow the audio as before. The lazily paged very-large-text path keeps its existing behavior.

### Read-aloud (TTS) - Markdown documents

- Markdown files now support read-aloud in the document viewer, like EPUB and Word. Because Markdown renders as one long scrolling page rather than discrete pages, playback follows along by scrolling the view to roughly the passage being spoken. "Continue reading aloud" from the main screen now resumes Markdown (and EPUB/Word) playback automatically at the saved spot in the document viewer.

### Read-aloud (TTS) - toolbar button in the PDF viewer, next to the bookmark

- The PDF viewer now has a read-aloud button directly on the bottom toolbar (it previously lived only in the More menu), placed immediately to the right of the bookmark button. The EPUB/Word/HWP document viewer's read-aloud button was moved to the same spot - right of the bookmark - so the two page-based viewers match. You can still reorder or hide it per viewer in Settings' button-order customization.

### Read-aloud (TTS) - phrase length and pause reduction controls

- Read-aloud gains two text-level controls in the TTS dialog, aimed at neural voices (for example Kokoro) that either add latency on long passages or pause too long at punctuation. Phrase length (Short/Medium/Long) sets how much text is sent to the engine at a time - shorter phrases start faster and can sound snappier, longer phrases give the most natural prosody (Long matches previous behavior). Pause reduction (Off/Medium/Aggressive) smooths punctuation so the voice keeps moving: Medium runs clauses together, Aggressive also softens sentence stops. Both are text-only settings that take effect on the next page; playback still uses your installed TTS engine and no audio is modified.

### Read-aloud (TTS) - PDF documents

- Read-aloud now works in the PDF viewer too. PDFs render as page images and have no text layer of their own, so the text is extracted with the same PDF text library the in-document search already uses; the reader then offers the full read-aloud experience - language and voice, speed and pitch, pause/resume, sleep timer, the playback notification, and continuous reading that turns the page as speech crosses each boundary. It is opened from the PDF viewer's More menu; the first start extracts the document's text in the background, which can take a moment on large PDFs. Scanned or image-only PDFs have no selectable text, so instead of playing silence the reader now says the PDF has no text to read aloud. Following the spoken word with an on-page highlight is not part of this first version.

### Read-aloud (TTS) - EPUB, Word, and HWP documents

- Read-aloud now works in the document viewer: EPUB, `.docx`, legacy `.doc`, and HWP/HWPX documents gain the same text-to-speech the plain-text/Markdown reader has - language and voice choice, speed and pitch, pause/resume, the sleep timer, the playback notification with media controls, and continuous reading that turns the page as speech crosses each boundary (with the same ahead-of-the-seam queueing, so neural voices do not pause between pages). It is opened from the viewer's More menu; the first start extracts the book's text in the background, which can take a moment on very large books.
- Internals: the playback controller was decoupled from the text reader behind two small interfaces (`TtsTextSource` for the text/position/highlight surface, `TtsHost` for everything the controller needs from its activity), with the text reader implementing them unchanged. The document viewer supplies a page-indexed plain-text buffer built from the rendered page HTML, so spoken positions map exactly to pages; saved positions resume from the read-aloud dialog. Spoken-sentence highlight on the WebView page and the floating control card are not part of this first version, and Markdown opened as a document is excluded (it already has full read-aloud in the text reader). The home screen's "continue listening" prompt now opens document formats in the document viewer instead of the text reader.

### Documents - EPUB images kept inside the screen

- Images in reflowable EPUBs no longer overflow the screen. Books frequently ship covers and illustrations larger than the display with their own sizing CSS; the viewer previously capped only the width, so a full-bleed cover could still run several screens tall (most visibly on tablets). Images (and embedded SVG) are now fitted to the visible page on both axes with their aspect ratio preserved, matching how the same page looks in the PDF viewer. Fixed-layout EPUBs keep their own page geometry and are unaffected.

### Documents - HWP/HWPX embedded images that can't be shown

- When an HWP or HWPX document embeds a picture in a vector or object format the reader can't display (WMF, EMF, or an OLE object), it no longer vanishes without a trace. A framed placeholder box is now shown at the picture's authored size, so the layout keeps its shape and it's clear an image belongs there. Raster pictures (PNG, JPEG, GIF, BMP, WebP) continue to render normally. The placeholder uses a language-neutral picture symbol, so it adds no new translated text.

### Documents - HWP paragraph formatting

- Binary `.hwp` documents now render paragraph indents, paragraph spacing, and line spacing, not just alignment. Left/right margins, first-line indent (including hanging indents), space before/after a paragraph, and line spacing (percent and fixed) are read from each paragraph's shape and applied, matching what `.hwpx`, `.docx`, and `.doc` already did. A document written with indented outline levels or 160% line spacing now looks like it does in Hangul instead of collapsing to flush-left, single-spaced text.

## Readwide 1.0.10 - 2026-06-29

### Release scope

- Android metadata is `versionCode 10010` and `versionName "1.0.10"`. It keeps the `com.readwide.manager` applicationId and the `readwide` release signing key, so 1.0.10 updates in place over 1.0.9, 1.0.8, 1.0.7, and 1.0.6.
- This release speeds up image page-flipping inside solid/sequential comic archives — 7z/CB7 and the TAR family (TAR/CBT and its gzip/bzip2/xz/lzma/compress variants) — fixes a problem where the previously shown image could stay on screen while paging through them, and smooths PDF page-flipping during rapid taps. The changes are internal to the archive image viewer and the PDF reader and add no new dependency.

### Images - faster page-flipping in solid/sequential archives

- Paging through images inside a solid 7z (`.7z`/`.cb7`) or a TAR-family archive (`.tar`/`.cbt` and its compressed variants) is faster, and the previous image no longer lingers while the next one loads. These formats have no cheap random access to a single entry: the viewer used to re-open the archive and re-decompress its shared stream from the start up to the requested image for every page, which is work proportional to the image's position and made each forward step progressively slower on a large archive. The viewer now keeps one forward reader open for the whole viewing session, decodes each image once, and caches every image it passes, so flipping forward decodes just the next image and pages already seen are served from cache.
- The first page of a large solid archive now appears without waiting for the whole archive to be decompressed first; only the images up to the opened one are extracted. Paging back to a page that the cache size cap had to evict re-reads just that one page rather than re-reading the rest of the archive, so backward paging in a large 7z/CB7 or TAR/CBT stays smooth too.
- Neighbour prefetch for these archives now flows through the same forward reader, so reading ahead extends only as far as you read rather than decompressing the whole archive in the background. Extraction still falls back to the previous whole-archive method whenever the forward reader cannot serve an image, so the change can only improve speed, never reduce what opens.
- ZIP/CBZ, ALZ, and EGG already support direct per-entry access and are unchanged. RAR/CBR now flows through the same forward reader, backed by its libarchive engine, which is itself a strictly forward reader: opening a large RAR/CBR no longer decompresses the whole archive before the first image appears, paging forward decodes each image once, and reading ahead extends only as far as you read. This covers ordinary RAR v4/v5 comics; anything the libarchive engine cannot read on its own (for example some encrypted RAR) automatically falls back to the previous whole-archive extraction, so the change can only improve speed, never reduce what opens. Paging back to a page that the cache size cap had to evict re-extracts just that one page rather than the whole archive.

### Reading - smoother PDF page-flipping

- Flipping through pages in the PDF reader with rapid taps is smoother. In single-page mode the reader pre-renders neighbouring pages into a cache so a turn shows instantly; it now buffers further ahead in the direction you are reading instead of splitting the same budget evenly between forward and backward, so quick forward (or backward) tapping stays ahead of the on-demand render more often. Page rendering, zoom, pan, and continuous-scroll mode are otherwise unchanged.

## Readwide 1.0.9 - 2026-06-28

### Release scope

- Android metadata is `versionCode 10009` and `versionName "1.0.9"`. It keeps the `com.readwide.manager` applicationId and the `readwide` release signing key, so 1.0.9 updates in place over 1.0.8, 1.0.7, and 1.0.6.
- This release adds in-document text find to the PDF reader for digital (text-based) PDFs, speeds up image page-flipping inside large archives, and reworks the recent-files list: it now searches your reading history (with a result banner), keeps your full history and shows up to 5000 entries, combines with the file-type filters, and supports swipe-to-remove. It also matches the archive preview's row styling to the main file list. It adds one new runtime dependency, PdfBox-Android (Apache-2.0), used only for PDF text extraction; the recent-list, archive-styling, and archive-image changes are internal and add no dependency.

### Reading - PDF in-document find

- Added a **Find** action to the PDF reader that searches for text inside the open PDF. Matches are highlighted on the page (the highlights track zoom and pan), previous/next step through them, and a current/total match count is shown. The find dialog matches the search dialog used by the other viewers.
- Find covers the whole document, not just the current page. Previous/next move through matches across pages and jump to the page where each match is.
- When you move to a match, the page shifts as needed so the current match stays visible above the find dialog instead of being hidden behind it.
- This works on digital (text-based) PDFs only. Scanned or image-only PDFs have no text layer to extract, so they are not searched; OCR is intentionally not included.
- PDF pages continue to be rendered by the platform `PdfRenderer`. The new dependency is used only to read the text and its on-page positions, not to render pages, so rendering, zoom, pan, and the single-page/continuous modes are unchanged.

### Images - faster page-flipping in large archives

- Paging to the next or previous image inside an archive (a ZIP/CBZ comic) is faster, especially on the first pass through a large archive such as a comic with around two thousand images. The reader now caches each archive's parsed entry index, so showing each image is a direct lookup instead of re-reading the whole archive directory every time. This applies to both unencrypted and password-protected archives; for an encrypted archive the password is used only during extraction and is not retained.

### Files - recent list search and management

- The search box on the home screen now searches your recently-read files (your reading history) as you type, instead of walking device storage. Matches come from your full history, and a banner under the **Recently Read** header shows the query and how many recent files match. Searching while browsing a folder still searches storage as before.
- The recent list keeps your entire reading history and shows up to 5000 entries (previously a few hundred), so older reads stay listed and remain reachable through search.
- The file-type filter chips (All, General, TXT, Archive, PDF, EPUB, Word, Image) now combine with the recent search: the chip narrows the list first, then the search runs within the filtered set. Changing the chip while searching re-applies the search on top of the new filter.
- Swipe a recent row to the left to remove it from the list. The card follows your finger and is removed once dragged past about 45% of its width; a shorter swipe slides back without removing. Removing a row also clears that file's saved reading position.
- Back now clears an active recent search first (restoring the list and hiding the banner) before dropping any active file-type filter or leaving the home screen.

### Archive viewer

- File rows inside the archive (ZIP/CBZ) preview now use the same font sizes and spacing as the main file list, so the two lists look consistent.

### Dependencies

- Added `com.tom-roush:pdfbox-android:2.0.27.0` (Apache-2.0, pure Java) for PDF text extraction. A proguard/R8 rule ignores its optional, unbundled JPEG2000 (JP2) decoder; JPX images are unaffected because find only uses extracted text.

## Readwide 1.0.8 - 2026-06-26

### Release scope

- Android metadata is `versionCode 10008` and `versionName "1.0.8"`. This is a hotfix over 1.0.7 and keeps the `com.readwide.manager` applicationId and the `readwide` release signing key, so 1.0.8 updates in place over 1.0.7 and 1.0.6.

### Fixes

- Fixed a regression where a large text or PDF document could suddenly turn blank while it was being read. Under system memory pressure the reader released its on-screen text even while the app was still in the foreground; because that content is only restored when you return to the app (which does not happen while you are already reading), the page went blank at random and the reading position was lost. The reader now releases that memory only when the app is actually in the background.

## Readwide 1.0.7 - 2026-06-20

### Release scope

- Android metadata is `versionCode 10007` and `versionName "1.0.7"`.
- Keeps the `com.readwide.manager` applicationId and the `readwide` release signing key introduced in 1.0.6, so 1.0.7 updates in place over 1.0.6. Updating from 1.0.4/1.0.5 (previous key) still requires uninstalling first, then migrating via the in-app JSON backup export/import.
- This release adds an optional blank-line collapsing display setting, makes text reading-position restore more reliable, improves large-file bookmark page accuracy, preserves the recent list's scroll position, refines find-in-page behavior when its options change, and fixes a find-in-page crash.

### Reading

- Added an optional **Collapse repeated blank lines** display setting for the text reader (Display settings, under the large-TXT options). When enabled, any run of two or more consecutive blank lines is shown as a single blank line; a lone blank line is left as-is and the original file is never modified. It applies to all text files the reader opens (TXT, log, CSV, and similar), both small and large, treats whitespace-only lines as blank, and is applied consistently to the page model, large-file partition/exact-page index, and in-text search so page numbers, bookmarks, and search positions stay aligned. Toggling it reloads the open file, and the collapse state is folded into the page-layout signature so the page model is recomputed instead of reusing a stale one. Legacy bookmarks from before this version stay compatible while the option is off. Default off.
- Reopening a text file restores the reading position more reliably. The saved position now carries short before/after text anchors and a page-layout signature, so the reader re-finds the right spot even when the page layout would otherwise differ, instead of falling back to an approximate page. The restored position also stays correct after the system recreates the reader and when scrolling back through a large file. If the file changed on disk since it was last opened, the reader reloads the current contents instead of restoring the earlier cached view.
- Large-file bookmark jumps now prefer surrounding-text anchors when resolving the destination, improving landing accuracy after a layout or display change (for example a different font size or margin).

### Settings and display rules

- Settings are reorganized: display and reading-layout options (theme, reading theme, text layout, EPUB layout) now live in a dedicated **Display settings** screen reached from Settings, separate from the general app settings (behavior, button order, security, backup). The general Settings screen no longer mixes display options with app-wide options.
- **Edit actual TXT file**: enabled TXT display rules can now be permanently applied to the current text file from the reader's **More** menu, either fixing the original in place or writing a separate `_edited` copy. The flow keeps the rule-order, overwrite, and large-file warnings and a final confirmation step; display-only rules still never modify the file. This replaces the previous entry point under the TXT layout settings, so it now always runs with the open file in context.

### Files

- Returning to the app after opening a file from the recent list no longer forces the list to the top; it stays near the row you opened from.

### Fixes

- Fixed a crash that could occur when an invalid regular expression was the active find-in-page query.
- Changing the find-in-page options (case-sensitive, whole-word, or regular expression) now restarts the search under the new options, so the next match uses the new settings instead of continuing from the previous result.
- When the system recreates the reader from memory, large-file exact page numbering is rebuilt instead of remaining on the estimate.

## Readwide 1.0.6 - 2026-06-19

### Release scope

- Android metadata is `versionCode 10006` and `versionName "1.0.6"`.
- Keeps the `com.readwide.manager` applicationId from 1.0.4 but switches to a new release signing key, so 1.0.6 does not update in place over an installed 1.0.4/1.0.5; uninstall the old version, install 1.0.6, then migrate via the in-app JSON backup export/import.
- This release focuses on file-list performance, reading-progress for image archives, per-type file icons, folder auto-refresh, text-to-speech refinements, and several browsing fixes.

### Final changes included in this release

**Performance**

- File listing and sorting no longer query MediaStore per file or per scrolled row. Folders now sort and display immediately using filesystem timestamps, with a background pass refining date order only when a date sort is active. Large folders such as Downloads open noticeably faster.
- Removed the 5,000-item cap on file search. Results now stream in incrementally as the search walks storage, so very large result sets are no longer truncated.
- The image viewer prefetches three pages in each direction (was two) and uses a larger decoded-bitmap cache, so rapid continuous paging is less likely to stall waiting on a decode.
- The PDF viewer's page caches were enlarged for smoother paging and scrolling on large documents.

**Reading progress**

- Image archives (and folder image sequences) now show a reading-progress percent in the recent list, the same way PDF and EPUB do. Progress is saved as you turn pages and when you leave the viewer.

**Files**

- File rows now show a per-type icon (PDF, EPUB, document, archive, image, video, audio, app package, or generic file) instead of a single shared icon. Folder icons are unchanged.
- Long file names keep their extension visible. When a name is too long to fit, it is shortened in the middle as "start…end.ext" so the file type and the end of the name stay readable across two lines.
- Search results now show each file's location relative to the searched folder. A file directly in the searched folder shows no location; a file in a subfolder shows ".../subfolder". An all-storage search keeps the storage's folder name as a prefix so results from different storages stay distinct.
- The image filter and image viewer now also recognize `.jfif`, `.wbmp`, and `.dng` files.
- Tightened the recent/file list row layout: two-line names, smaller secondary text, and more consistent spacing.
- The recent list now shows up to 300 recently opened files.
- While a search or file-type filter is loading, a loading spinner (new in this release) is shown in place of the previous "loading" text.

**Folders**

- The visible folder now refreshes when the app regains focus, catching downloads and other external changes that the folder watcher misses on some storage. Pull down on the file list to refresh manually.

**Text-to-speech**

- When reading aloud is interrupted — you leave the app, or it stops before reaching the end of a book — reopening Readwide offers to resume that book from the main screen. Continuing reopens the file where it left off, restores the sleep-timer value that was set when playback stopped, and starts reading automatically. Choosing "Later" dismisses it, and finishing a book to the end clears the prompt.
- Text-to-speech no longer reads out runs of punctuation. Ellipses, sequences of periods, and underscores are no longer spoken, and a semicolon is treated as a short pause, so playback sounds more natural.

**Security & stability**

- Files opened into Readwide from another app are copied into an app-private cache with filename sanitization, a path-containment check, a 2 GB per-file size limit, and automatic cache cleanup; a failed or oversized copy no longer leaves a partial file.
- Backup import rejects JSON larger than 256 MB instead of reading it fully into memory.
- When the optional app lock is on, the home and recent screens are no longer prepared behind the lock screen.
- Search and file-type filtering stop their background work promptly when superseded by a newer search or a folder change.
- Document, in-document resource, and EPUB chapter reads each have a size limit so a malformed file cannot exhaust memory.

**Fixes**

- Starting a folder navigation while a search was running no longer leaves a stale search screen or briefly freezes; the in-progress search is cancelled cleanly.
- Tapping the navigation drawer's Recent shortcut while a large folder was open no longer delays the drawer from closing.
- In fixed-layout EPUBs, double-tapping the left or right side now turns the page instead of zooming; double-tapping the center still zooms.

## Readwide 1.0.5 - 2026-06-17

### Release scope

- Android metadata is `versionCode 10005` and `versionName "1.0.5"`.
- Keeps the `com.readwide.manager` applicationId from 1.0.4, so 1.0.5 is an in-place update over 1.0.4 when signed with the same key.
- This release focuses on a text-to-speech overhaul, a safer multi-select delete confirmation, and two PDF mode-switch crash fixes.

### Final changes included in this release

**Text-to-speech**

- Added a sleep timer. Choose Off, 15, 30, 45, or 60 minutes, or enter a custom number of minutes; an optional setting lets TTS finish the current sentence before stopping. The timer counts playback time only, so time spent paused does not count toward it, and it stops playback when it expires.
- Added real pause and resume. Pausing keeps your place and resumes from the sentence you stopped on, regardless of page length, instead of restarting from a saved position. Because Android's speech engine has no true mid-sentence pause, resuming replays from the start of the sentence that was interrupted.
- Added a floating control card over the text reader with play/pause and stop buttons, so playback can be controlled without opening the TTS dialog. The card can be dragged anywhere on screen and works alongside the existing notification, lock-screen, and Bluetooth/media-button controls.
- TTS now keeps reading when you scroll or move within the page. Manual navigation no longer stops playback, so the reading position and the on-screen position can move independently.
- Reworked how TTS reacts to other audio. A temporary interruption such as a phone call pauses playback and resumes automatically when the interruption ends; another app taking over audio for good stops playback; unplugging headphones pauses (rather than stops) so playback can resume after reconnecting.
- A paused session is remembered if you leave the app, so playback can resume where it left off.
- All new text-to-speech strings were translated across the bundled locales. The timer setting is labelled simply "Timer" rather than "Sleep timer".

**Files**

- The multi-select delete confirmation now warns when the selection includes folders, noting that all of their contents will be deleted too. The warning appears only when at least one folder is selected, and the existing single-file and image-viewer delete confirmations are unchanged.

**Fixes**

- Fixed two PDF viewer crashes when switching between vertical (continuous) and horizontal (single-page) modes. Switching from a zoomed-in continuous view into single-page mode could allocate an oversized bitmap and crash; the single-page view now always renders the page at fit. Separately, switching modes could leave the matrix page view drawing a bitmap that had already been freed, aborting the app; the view now drops its bitmap references before any bitmap is recycled and guards against drawing a recycled bitmap.
- Made the navigation drawer's Recent shortcut as responsive as the other shortcuts. Loading the recent list scanned up to several hundred saved reading states on the main thread, checking each file's existence and cleaning up stale or image-only entries before the list could appear, which briefly blocked the UI when the history was large. The scan and cleanup now run on a background thread and the list is applied when ready.

**Performance**

- Text display rules now compile their regular expressions once per file load instead of recompiling on every line. When a large text file is read line by line with active regex rules, the previous code recompiled each rule's pattern for every line; the patterns are now compiled once and reused, so regex compilation scales with the number of rules rather than the number of lines. Behavior is unchanged for files with no rules or literal-only rules.

## Readwide 1.0.4 - 2026-06-16

### Release scope

- Android metadata is `versionCode 10004` and `versionName "1.0.4"`.
- Readwide moves to a new Android applicationId, `com.readwide.manager`, completing the rename away from the earlier TextView Reader package identity.
- Because the application ID changed, 1.0.4 is installed as a separate app rather than an in-place update over older TextView Reader/Readwide builds.
- This release keeps the 1.0.3 document-fidelity cycle and focuses on package identity cleanup, reader-search consistency, document-viewer search behavior, and translated UI clipping fixes.

### Final changes included in this upload

**Package identity**

- Renamed the Android applicationId and source package from `com.textview.reader` to `com.readwide.manager`, including all package declarations, the FileProvider authority, layout custom-view references, ProGuard keep rules, fixture report scripts, F-Droid metadata, and release materials.
- Existing users are not auto-updated to 1.0.4 because the package identity differs; bookmarks, reading positions, themes, and settings transfer through the in-app JSON backup export/import, which is independent of package name and signing key.

**PDF viewer**

- Reworked single-page (horizontal) PDF zoom onto a single image matrix. Double-tap zoom, pinch-to-zoom, panning, and fling are driven by one transform instead of a scroll-view stack with a separate bitmap per zoom level, so the point you tap or pinch stays under your finger with no position jump or flicker. When a zoom or pan settles, the visible region is re-rendered at full resolution so text sharpens in place, while only the visible region is rendered at high resolution so memory stays bounded. Page navigation works alongside the zoom: swipes and the left/right tap zones turn the page while the center zone toggles the toolbar, double-tap zoom is suppressed inside the page-turn zones, and page-turn taps fire immediately. (Vertical continuous mode keeps its existing zoom behavior.)
- Rendered PDF pages above screen density (1.4× supersampling) and downscaled them for display, sharpening text without stretching the page; per-page pixel budgets still bound memory.
- Made single-page paging smooth in both directions by prerendering two pages each way on a second, independent PDF renderer that runs in parallel with the on-demand render, so a page turn usually shows an already-rendered page and going back is as fast as going forward. Renders for pages skipped past during rapid taps are abandoned before doing the work, so the page you land on renders immediately.
- Made double-tap zoom in vertical (continuous) mode more responsive by lowering the per-page pixel cap while zoomed, roughly halving the render work.
- Fixed the PDF viewer being killed back to the main screen when double-tapping to zoom: neighbor prefetch is skipped while zoomed, cached neighbor bitmaps are released on zoom-in, and zoomed renders use a smaller pixel cap, so a zoom no longer exhausts memory.
- Fixed the page counter misbehaving during rapid taps and slider jumps in continuous mode: stale scroll/settle timers are cancelled before scheduling the next, and the target page is re-asserted once the scroll settles, so a late callback cannot revert to an intermediate page.
- Fixed single-page PDF fit when toggling the toolbar on tablets, so a full page sits between the title area and the toolbar and returning from toolbar-off mode no longer leaves an oversized page.

**EPUB viewer**

- Fixed fixed-layout EPUBs (pages with a declared pixel size, e.g. 1366×768) not fitting the screen. The viewer now uses the WebView's wide-viewport scaling to fit the declared viewport to screen width and only neutralizes margins to prevent horizontal scroll, preserving the book's own full-height images and centered covers.

**Image viewer**

- Made image page turns much faster, especially on rapid taps: page-turn taps fire the moment the finger lifts (instead of waiting out the ~300 ms double-tap window), decoded preview bitmaps are kept in a memory-budgeted cache, and adjacent pages (two each way) are pre-decoded on a small parallel pool that runs alongside archive extraction. Swipes and double-tap-to-zoom are unaffected.
- Raised the preview decode budget from 12 to 16 megapixels so higher-resolution images show at full detail before downsampling; large images are still scaled to fit screen and memory.

**File list and sorting**

- Sped up file-list sorting and folder loading by reading each entry's name, directory flag, and active sort key once instead of repeatedly inside the comparator and scan loop, removing redundant filesystem calls for folders with many entries. Sort order is unchanged.

**Reader search (TXT and document viewers)**

- Unified find-in-page across TXT, Markdown, EPUB, HWP/HWPX, and Word-family viewers with shared case-sensitive, whole-word, and regular-expression options, an nth-match jump, and current/total status, replacing the WebView native find previously used by the document viewers. Unicode normalization is always applied and overlapping literal matches are counted correctly.
- Fixed slow TXT find-in-page for common words by preparing the comparison view once per search and scanning each line in a single pass, keeping offsets aligned for bookmarks and page anchors.
- Fixed search-result visibility near the top and bottom of a document: matches use explicit highlighted spans and popup-safe reveal, with a search-only virtual scroll allowance so a match in the final lines can be pulled above the search dialog without changing normal paging or saved positions. The highlight styling is hardened so reader/theme CSS cannot erase the current-result highlight.

**Toolbar and viewer chrome**

- Converted the PDF and document viewer bottom toolbars to the TXT viewer's layout: a horizontally scrollable button row with the More button pinned at the right, shared equal button widths, snap-to-nearest-button scrolling, and re-balancing on rotation.
- Added a screen-rotation button to the TXT, document, and PDF viewer toolbars (the image viewer already had one). It reflects the current orientation, toggles it, and is part of the customizable button-order system. Added a Settings button to the document and PDF viewer toolbars; Settings also remains in their More dialog.
- Fixed taps on a visible toolbar turning the page or leaving a popup on screen: taps that land on a shown chrome bar now toggle or keep the toolbar instead of paging the view underneath, across the TXT, document, and PDF viewers. Reader toolbar taps are also debounced so a repeated tap cannot open duplicate dialogs.
- Applied left/right system-bar insets across the main screen, settings, and the TXT, document, and PDF viewers, so content and toolbars no longer slide under the Android 3-button navigation bar in landscape.
- Fixed settings rows ("Button / icon order", sort options, TXT search options) being vertically clipped under longer translations by letting them wrap instead of using a fixed height.

**Manifest and distribution**

- Removed the dead `android:requestLegacyExternalStorage="true"` manifest flag, which had no effect under `targetSdk 35`. File access behavior is unchanged.
- Updated public GitHub/F-Droid materials for the 1.0.4 package, including the renamed F-Droid metadata file, Fastlane changelogs, and package/version references in the release docs.

## Readwide 1.0.3 - 2026-06-14

### Release scope

- Android metadata is `versionCode 10003` and `versionName "1.0.3"`.
- Keeps the existing Android `applicationId` / package name so Readwide remains update-compatible with earlier compatible builds when signed with the same key.
- Starts the document viewer fidelity cycle for DOCX, HWPX, and HWP.
- The target for this cycle is L3 content-fidelity HTML preview: document structure, inline styling, tables, and images where verified.
- Exact MS Word/Hancom pagination, exact font metrics, editing/saving, and complete floating-object placement are explicit non-goals.

### Final changes included in this upload

- Existing text-first and semantic WebView document readers remain the fallback path, and a shared RenderedDocument / FixedHtmlRenderer scaffold was added before changing real document conversion behavior. The scaffold supports page containers, paragraph/run styles, tables, images, unsupported placeholders, and text anchors.
- DOCX now has a conservative bridge into the shared RenderedDocument model for paragraphs, run styling, basic tables, inline images, and page margins, with fallback to the previous Word semantic HTML path if conversion fails.
- DOCX `numbering.xml` lists now enter the rendered bridge as visible ordered/bullet markers with level-aware counters and indentation; malformed or missing numbering definitions still fall back to ordinary paragraphs.
- DOCX tables in the rendered bridge now preserve basic table width, grid column proportions, cell width hints, vertical merge row spans, table/cell border colors, and cell shading where those properties are directly present in the OOXML.
- DOCX `styles.xml` inheritance now feeds the rendered bridge for document defaults, paragraph styles, character styles, based-on style chains, and direct override precedence, improving heading/body/emphasis fidelity without changing the fallback path.
- DOCX images in the rendered bridge now read WordprocessingML/DrawingML extent metadata, emit width/height hints, and mark floating `wp:anchor` drawings as block-downgraded images instead of pretending to preserve exact floating layout.
- DOCX footnotes and endnotes in the rendered bridge are now preserved at the end of the rendered document, with superscript reference links in the body and backlink targets in the note section.
- DOCX headers and footers in the rendered bridge are now preserved as reading-order sections before and after the body, including paragraph/table content and local header/footer image relationships where directly referenced.
- DOCX Symbol/Wingdings bullet markers are normalized to standard Unicode bullets in the rendered document bridge, and list paragraphs no longer double-apply Word hanging indents on top of the flex marker layout.
- DOCX rendered tables now clamp cell overflow and use stronger word wrapping so narrow columns cannot draw text over neighboring cells on phone-width pages, while long first-column labels stay readable without introducing horizontal scrolling and cells prefer word-level wrapping over arbitrary letter breaks.
- DOCX split text runs are coalesced before rendering, and DOCX/Word lecture-note formulas now render inline and conservative `$$...$$` display-math fragments, including fractions, square roots, superscripts/subscripts, Greek letters, and symbols such as `\partial`. Covered examples include `$2Dt$`, `$L/W$`, `$\nu_0$`, `$\rho_{S}$`, and `$e^{-\text{barrier}/kT}$`, including expressions split across runs by spell/grammar markers. Lone currency amounts such as `$200` are left as plain text.
- HWP and HWPX rendered output now preserves partially-ruled table borders per edge (for example header bands with only top and bottom rules), paragraph-head bullet markers, authored cell heights so empty layout cells do not collapse, character size/bold/italic/color, paragraph alignment, and horizontal rules.
- RAR5 AES visible-header multi-volume handling was tightened against real encrypted multi-volume source fixtures. Covered RAR5 v5.0 compressed/solid split entries now assemble their packed stream across volumes, tolerate continuation encryption-flag differences when the actual AES material matches, and avoid plaintext CRC checks only when a RAR5 password-check value is present.
- Password-protected archive image preview caches now require current-session verification before a ready marker is trusted. Sensitive preview files that were produced by an older failed password/decode attempt are deleted and regenerated, preventing stale black/invalid images from being reused after the RAR5 visible-header multi-volume decoder succeeds.
- RAR5 encrypted/solid/split preview single-entry extraction now prefers the first-party ordered decoder instead of a backend call that may start from a later volume or an unprimed solid member. This targets archive preview document opens where whole extraction succeeds but preview opens the wrong or invalid cached file. RAR5 visible-header listing also prefers first-party header parsing before libarchive to reduce password-preview startup delay.
- Archive preview/list loading now uses the same centered loading window as archive entry preview extraction, removing the leftover tiny inline spinner from the archive browser.
- EPUB page transition animation settings were removed because WebView document pages now snap without slide/fade animation, and the compact top page counter shown while EPUB/Markdown/document/PDF toolbars are hidden now forces a 48dp strip plus the status-bar inset and nudges the page-number glyphs downward for better balance.
- The selectable UI languages were brought to full coverage: newly added archive support-boundary messages, bookmark "file missing" notices, and tap/image paging labels are now translated across all bundled locales (Arabic, German, Greek, Spanish, French, Hindi, Indonesian, Italian, Japanese, Korean, Dutch, Polish, Portuguese, Russian, Swedish, Thai, Turkish, Ukrainian, Vietnamese, Simplified Chinese, and Traditional Chinese), with English remaining the fallback for any future untranslated string.
- Public GitHub/F-Droid materials were updated for the 1.0.3 package, including the document viewer fidelity matrix and fidelity notes, Fastlane changelogs, and the F-Droid submission metadata draft.

## Readwide 1.0.2 - 2026-06-12

### Release scope

- Android metadata is `versionCode 10002` and `versionName "1.0.2"`.
- Keeps the existing Android `applicationId` / package name so Readwide remains update-compatible with earlier compatible builds when signed with the same key.
- Focuses on new document reader formats, rendered-document viewer polish, scoped archive decoding boundaries, and release documentation for the 1.0.2 line.

### Final changes included in this upload

- `.md` and `.markdown` files now open in a dedicated themed Markdown WebView reader. Markdown headings, emphasis, lists, links, code blocks, blockquotes, and tables are rendered as HTML while ordinary `.txt` files stay on the exact TXT reader model.
- HWP/HWPX files now have text-first read-only support through Apache-2.0 dogfoot libraries: `hwplib` for HWP 5.x and `hwpxlib` for HWPX. Hancom-compatible layout rendering, editing/writing, password/encrypted HWP, original page-count parity, and embedded object rendering are not claimed.
- The visible `Word` filter remains compact while grouping OOXML Word files, HWP/HWPX, and recognized legacy `.doc` files. Legacy binary `.doc` is recognized for classification/filtering but is still reported as unsupported for rendering.
- Markdown, EPUB, Word, HWP/HWPX, and PDF bookmark rows now use the same rendered-document display model: content/text anchors are shown as the primary label where available, while page/position/date metadata is shown separately.
- WebView-backed document viewer chrome was adjusted so toolbar toggles do not move the rendered WebView body. Compact top page labels, bottom toolbar shape, slider presentation, Markdown CSS isolation, and Android navigation-inset handling were refined without changing the TXT reader model.
- PDF viewer system-bar and navigation-inset handling was refined separately from WebView documents so the PDF viewport keeps fixed-layout behavior while avoiding stale toolbar-colored system bars.
- The Readwide launcher source reference was updated to the project-supplied image at `docs/readwide_launcher_icon_source.png`; checked-in Android launcher/adaptive/play-store PNG resources were left unchanged.
- Unknown-size decoded stream extraction, failed single-entry extraction cleanup, and 7z solid-member drain handling now use the decoded-byte safety boundary so unsupported or hostile streams cannot bypass the total extraction ceiling or leave stale output.
- 7z/CB7 password and split handling was tightened. Standard raw split chains such as `.7z.001` / `.7z.002` and `.cb7.001` / `.cb7.002` are resolved conservatively, missing/gapped chains are treated as corrupt/incomplete, and wrong-password AES failures are classified with password context where possible.
- RAR/CBR support remains conservatively documented. The default build uses libarchive-android as the compressed-RAR backend, plus scoped first-party decode-only paths for covered stored entries, eligible unencrypted single-volume RAR3/RAR4 PPMd solid cases, and eligible unencrypted single-volume RAR5 v5.0 compressed/solid cases. Full RAR, encrypted RAR, broad split RAR, SFX, VM-filtered RAR, and complete RAR compatibility are not claimed.
- ALZ/EGG first-party extraction paths remain limited and CRC-verified for covered methods, with unsupported encrypted/split/solid cases failing cleanly.
- Public GitHub/F-Droid materials were updated for the 1.0.2 package: `README.md`, `PRIVACY.md`, `THIRD_PARTY_NOTICES.md`, FOSS notes, F-Droid submission notes, license report, SBOM draft, Fastlane metadata, release checklist, and release notes.

### Archive support boundary

- ZIP/CBZ stays on Zip4j as the primary path, with Apache Commons Compress fallback for selected methods where bundled codecs can read them.
- 7z/CB7, TAR-family archives, and single-compressor streams continue through Apache Commons Compress, with the 1.0.2 split/password/drain safety boundaries documented separately.
- ALZ/EGG behavior is unchanged from the existing scoped coverage; 1.0.2 keeps those limits documented while focusing archive changes on 7z safety and RAR boundary wording.
- RAR/CBR remains limited and backend/scoped-path dependent. It includes covered stored paths, selected stored split paths, RAR4 Unicode metadata handling, eligible unencrypted single-volume RAR3/RAR4 PPMd solid extraction, and eligible unencrypted single-volume RAR5 v5.0 compressed/solid extraction.
- RAR creation/compression, password recovery, encrypted RAR support, broad split RAR support, broad SFX support, and complete RAR compatibility are not implemented or claimed.

## Readwide 1.0.1 - 2026-06-09

### Release scope

- Kept Android metadata at `versionCode 10001` and `versionName "1.0.1"`.
- Kept the existing Android `applicationId` / package name so Readwide 1.0.1 remains update-compatible with earlier compatible builds when signed with the same key.
- Focused on viewer polish, portable backup/bookmark handling, archive safety, lifecycle hardening, and public GitHub/F-Droid packaging cleanup.

### Final changes included in this upload

- Missing bookmark target files remain visible in the bookmark list with a theme-matched **File missing / 파일 없음** label. Tapping one opens a themed explanation dialog while preserving the bookmark for later portable rebind.
- Backup import keeps `last_directory`, `recent_folders`, and `folder_shortcuts` only when those folders exist on the current device. Invalid imported paths are skipped, and valid current-device entries are preserved when the backup has no accessible replacement.
- TXT bookmark imports treat `pageNumber`, `totalPages`, and `pageLayoutSignature` as layout-dependent cache. Character position, logical line, surrounding anchor text, and file fingerprint remain the stable bookmark location, and Page X/Y refreshes under the current device layout when the file is opened or the bookmark is used.
- Zoomed PDF pages can fling/pan with inertia in single-page mode, and zoomed pages in vertical continuous mode support horizontal fling across the visible page while original-scale swipes keep their page-turn behavior.
- Image viewer landscape safe-area handling now respects Android 3-button navigation on the right side of the screen.
- Image viewer default fitting is adaptive: wide images open fit-to-width and tall images open fit-to-height. Double tap toggles against true 1:1 scale when applicable.
- Image viewer keeps successfully decoded detail/original bitmaps after returning from zoom to adaptive-fit view, avoiding repeated detail re-decodes for the same image.
- Archive-backed image viewer recent/saved-position reopen paths were hardened so deferred image sequence metadata is applied before decoding, with fallback handling for missing or invalid handoff metadata.
- Legacy archive entry filename decoding was expanded for raw ZIP central-directory names and first-party ALZ/EGG name fields, covering Korean, Chinese, Japanese, Cyrillic, Greek, Turkish, Hebrew, Arabic, Thai, Vietnamese, Western/Central/Baltic Latin, and DOS ZIP code pages where raw name bytes are available.
- Password-protected archive image viewing uses selected-image-first lazy extraction after password entry. Password-sensitive preview cache reuse validates the current password before trusting cached output.
- ALZ Store/Deflate/BZip2 and EGG Store/Deflate/BZip2/LZMA extraction paths stream to output with CRC verification where the format path allows it.
- Archive failure messages now distinguish password-required, bad-password, unsupported-feature, corrupt-archive, and generic failures, with conservative family-specific support-boundary details.
- Added external RAR and non-RAR archive fixture report tooling for local compatibility QA without broadening public archive compatibility claims.
- Removed forced process-wide `System.gc()` calls from image decode OOM retry; retries increase sample size and yield instead.
- Added lifecycle guards and cleanup for document WebView callbacks, PDF delayed callbacks, TXT TTS callbacks, reader-toolbar delayed work, drawer delayed work, image sequence handoffs, archive password snapshots, and font-scan callbacks.
- Bounded EPUB fixed-layout/font detection reads so large HTML/CSS entries are not fully loaded during detection-only scans.
- Public GitHub/F-Droid documentation, release notes, archive/RAR/license report filenames, and Fastlane changelogs were normalized for the Readwide 1.0.1 release line.

### Archive support boundary

- ZIP/CBZ stays on Zip4j as the primary path, with Apache Commons Compress fallback for non-encrypted unsupported ZIP methods where bundled codecs can read them.
- 7z/CB7, TAR-family archives, and single-compressor streams continue through Apache Commons Compress.
- ALZ and EGG remain limited first-party implementations with documented method coverage and unsupported encrypted/split/solid variants.
- RAR/CBR remains libarchive-primary with scoped first-party Java support for metadata, safe paths, stored entries, selected stored split paths, RAR4 Unicode names, diagnostics, and covered RAR5 stored-entry handling.
- RAR creation is not implemented.
- Split/multi-volume RAR and encrypted RAR are not guaranteed.
- Solid RAR, PPMd, custom VM filters, broad SFX, RAR5 compressed/solid/encrypted-header cases, and unusual RAR variants remain backend-dependent or unsupported unless a specific file is covered by the bundled backend.

## Readwide 1.0.0 - 2026-06-09

### Release scope

- Renamed the public app line from TextView Reader to Readwide.
- Kept Android metadata at `versionCode 10000` and `versionName "1.0.0"`.
- Kept the existing Android `applicationId` / package name so Readwide 1.0.0 remains update-compatible with the TextView 2.2.6 line when signed with the same key.
- Updated the in-app static update link to `https://github.com/k1717/Readwide/releases`.

### Final changes included in this upload

- Updated launcher/app display branding, settings wording, backup wording, exported backup filename prefix, TTS media-session label, and developer-contact documentation to Readwide.
- Changed the developer contact address to `readwide.kj7w5@addy.io`.
- Reworked the main language setting from a long radio-button page into a compact selected-language row with a rounded picker dialog.
- Added selectable major UI languages and initial resource coverage for the expanded language list. Untranslated strings fall back to the default English resources.
- Fixed the recent-file multi-select menu so long English actions such as `Remove from recent list` can wrap instead of being clipped.
- Replaced launcher/adaptive/play-store icon assets with the approved Readwide book artwork and adjusted launcher safe margins to avoid clipped-looking edges.
- Fixed the custom reading theme create/edit screen so the top app bar respects the Android status bar and display cutout, preventing the back button from overlapping system UI.
- Fixed the reading-theme selection mark so the selected row shows a real check mark instead of mojibake text.
- Matched the custom reading theme editor's app-bar/status-inset background to the active main theme bar color instead of leaving a gray strip above the toolbar.
- Switched the public update URL to the standard GitHub releases page: `https://github.com/k1717/Readwide/releases`.
- Cleaned RAR source comments so the public FOSS package describes first-party RAR work as independent implementation based on public format behavior and fixture validation, not as UnRAR source porting.
- Documented launcher icon provenance as project-owned generated artwork and removed the unused optional local RAR5 decoder bridge/readme from the public source package.
- Removed development-session wording from RAR diagnostic strings and comments so detailed archive failures use release-facing wording.
- Removed the default `app/libs/*.jar` dependency hook so the public FOSS/F-Droid-oriented source tree has no local optional jar path in the Gradle dependency graph.
- Made release signing conditional so F-Droid-style source builds can run `assembleRelease` without a private developer keystore and produce an unsigned release artifact.
- Removed the unused Foojay toolchain resolver plugin from `settings.gradle` to keep the build script leaner for reproducible source-build review.
- Added Readwide backup filename patterns to `.gitignore` so exported user backups are not accidentally committed.
- Kept the TextView 2.2.6 privacy/license hardening base: Auto Backup disabled, no default `INTERNET` permission, no analytics, no ads, no account system, and no Junrar/UnRAR-license fallback in the default build.
- Preserved archive preview/image-sequence fixes from the late TextView 2.2.6 line, including direct comic-open ordering, preview-to-viewer ordering, archive folder sort-state restoration, and macOS resource-fork image filtering.

### Archive support boundary

- ZIP/CBZ stays on Zip4j as the primary path, with Apache Commons Compress fallback for non-encrypted unsupported ZIP methods where bundled codecs can read them.
- 7z/CB7, TAR-family archives, and single-compressor streams continue through Apache Commons Compress.
- ALZ and EGG remain limited first-party implementations with documented method coverage and unsupported encrypted/split/solid variants.
- RAR/CBR remains libarchive-primary with scoped first-party Java support for metadata, safe paths, stored entries, selected stored split paths, RAR4 Unicode names, diagnostics, and covered RAR5 stored-entry handling.
- RAR creation is not implemented.
- Split/multi-volume RAR and encrypted RAR were not re-tested for this release package and are not guaranteed.
- Solid RAR, PPMd, custom VM filters, broad SFX, RAR5 compressed/solid/encrypted-header cases, and unusual RAR variants remain backend-dependent or unsupported unless a specific file is covered by the bundled backend.

## TextView Reader 2.2.6 - 2026-06-07

### Release scope

- TextView Reader 2.2.6 is the privacy/license hardening base that Readwide 1.0.0 continues from.
- Readwide 1.0.0 keeps the same application ID for update compatibility with this line.

### Final changes included in this release

- Disabled Android app-data Auto Backup in the manifest.
- Replaced new PIN storage with salted PBKDF2 verifier strings and kept migration for legacy plain-PIN data after successful verification.
- Removed the default `INTERNET` and `REQUEST_INSTALL_PACKAGES` permission paths.
- Replaced in-app update checking with a static, copyable release link in Settings.
- Added developer contact through the user's mail app, with copy fallback if no mail app is available.
- Removed Junrar/UnRAR-license fallback code from the default dependency path.
- Documented the default source/APK as the FOSS-friendly line with Apache-2.0 first-party source and third-party notices.
- Added bundled libarchive-android routing for common compressed RAR3/RAR4 attempts while keeping first-party RAR stored-entry and metadata paths.
- Added scrollable/copyable archive failure detail dialogs so long backend errors are no longer reduced to truncated toasts.
- Refined archive password dialogs with compact buttons and a show/hide password toggle.
- Kept ARM-only release native packaging and excluded unnecessary desktop native payloads from Android packaging.

### Known support boundaries

- Split/multi-volume RAR and encrypted RAR were not guaranteed for the public 2.2.6 package.
- First-party compressed RAR was not complete.
- RAR5 compressed/solid/encrypted-header extraction remained backend-dependent.

## TextView Reader 2.2.5 - 2026-06-02

### Release scope

- Android metadata: `versionCode 2250`, `versionName "2.2.5"`.
- Focused on archive fallback behavior, browse-state responsiveness, file-operation progress, and reducing large activity responsibilities.

### Final changes included in this release

- ZIP extraction uses Zip4j as the primary path and Apache Commons Compress as a fallback for non-encrypted unsupported compression methods such as Deflate64, BZip2, XZ, and ZSTD where available.
- Pending ZIP creation resolves the destination from the folder where the queued action is executed.
- Main file/folder action short-hold opens faster, while multi-select hold remains separate.
- Returning from internal viewers preserves the current main-folder list and scroll state when the folder has not changed.
- Fully loaded folder snapshots can be restored in both directions, including A -> B -> A and A -> B -> A -> B navigation.
- Drawer shortcut and recent-folder navigation restore cached target folders optimistically and validate them in the background.
- Multi-select delete exits selection mode after confirmation so background progress can be reopened from the toolbar.
- Browse-state logic moved into `MainBrowseStateController`.
- Archive list shaping, archive image sequence loading, and archive create/extract planning moved into focused helper classes.

### Known support boundaries

- Encrypted ZIP entries remain on Zip4j.
- AES entries that also use unsupported special ZIP methods remain unsupported.
- ZIP creation remains plain ZIP only.

## TextView Reader 2.2.4 - 2026-06-02

### Release scope

- Android metadata: `versionCode 2240`, `versionName "2.2.4"`.
- Focused on public license packaging, queued archive actions, archive preview safety, and theme editing.

### Final changes included in this release

- First-party project source is Apache License 2.0 and ships with `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`.
- Compress actions add pending ZIP creation tasks instead of running immediately.
- Pending copy, move, extract, and compress tasks are managed from the same pending-actions menu.
- ALZ supports Store/Deflate/BZip2 extraction with CRC verification.
- EGG supports Store/Deflate/BZip2/AZO/LZMA through the first-party parser.
- Standard 7z/CB7 split volumes resolve to the first part and open through a concatenated seekable channel.
- Archive management includes safer preview caching, stricter path sanitization, password preflight, backup/restore overwrite extraction, free-space guards, and cache pruning.
- Custom main-theme and reading-theme color editors include a lightweight shader-based color palette picker with HEX/RGB input.

### Known support boundaries

- The 2.2.4 RAR/CBR line still used a then-bundled Junrar fallback for older RAR extraction. That fallback is removed from the default Readwide 1.0.0 / TextView 2.2.6 FOSS-oriented line.
- RAR creation is not implemented.
- ALZ/EGG encrypted, split, solid, and unusual legacy variants remain limited or unsupported unless explicitly covered by tests.
