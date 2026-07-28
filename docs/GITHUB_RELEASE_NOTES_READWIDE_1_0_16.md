# Readwide 1.0.16

Readwide 1.0.16 fixes PDF fullscreen behavior when the reader controls are hidden.

## Archive/comic images

- Save the current archive page directly to Downloads or a location selected with Android's file picker. The original image bytes are copied without recompression, and the destination chooser now uses a compact 320dp maximum width.
- Optional two-page comic view is available in landscape. It pairs only two meaningfully portrait-shaped pages; square/wide and final unpaired pages remain single, with RTL placement supported.
- Two-page comic view now recovers when the visible first page is no longer in the decoded-page cache and prepares the exact following page when enabled or after rotation. Static WebP pages are supported without disabling genuine animated WebP/GIF playback, and Back follows the actual single/spread screens already shown.
- Ordinary image previews now allow a higher 24MP ceiling. Archive previews use a denser rendering tier, and a lightweight prefetched page upgrades in place after it becomes current. Both pages of a landscape comic spread now use the same archive-preview resolution as single-page viewing.
- An image viewer left fully in the background for ten minutes, or affected by background memory pressure, now saves its position and closes back to the browser instead of returning to a stale-page error.

## File browser and EPUB font

- The built-in file browser now distinguishes inaccessible raw directory enumeration from a genuinely empty folder. The drawer keeps one **Internal Storage** entry: it uses the normal browser where raw storage works and automatically becomes a persisted Android folder browser where it does not. The provider-backed path opens supported books without raw/all-files permission and returns to the same folder after reading. This adds no permission and leaves the existing raw-path permission behavior unchanged.
- The compatible provider browser now keeps folder listing separate from long archive-cache copies, releases stale open state after navigation, suppresses duplicate file taps, and follows the global hidden-file preference. Persistable grant fallback is shared by all pickers instead of being implemented differently per screen.

- Optional 40×40dp list cover thumbnails now work in both the normal and Recent lists. They support loose images, folder covers, first images from ZIP/CBZ, RAR/CBR, 7z/CB7, ALZ, EGG, and TAR/CBT-family archives, PDF first pages, and raster EPUB covers. Generated previews are reused from a bounded app-private disk cache until the source changes. Encrypted or unsupported files keep their normal icons; this remains a list view rather than a grid.
- Thumbnail requests are deduplicated and run through a bounded queue, so a fast scroll or folder change no longer leaves current rows behind an unbounded backlog of obsolete archive/PDF work. Transient failures retry after a cooldown, directory covers are revalidated, and folder/archive covers fall through across several candidates when the first image is broken or unsupported.
- Retry records are bounded as well, so a very large folder of damaged or unsupported sources cannot grow thumbnail bookkeeping indefinitely.
- Disk thumbnails are replaced atomically and active cache entries are protected from cleanup races. A source that changes while decoding is rejected, and the refreshed cache identity regenerates previews created by older unstable paths.
- Late failures from an obsolete folder load can no longer suppress thumbnails in the current list, and zero-length, unknown-length, or oversized embedded cover resources are rejected before optional decoding/extraction.
- The current-folder three-dot menu expands for longer translations and wraps only when it reaches the available screen width. Both hidden files and cover thumbnails display their current on/off state directly in the menu.
- The three-dot menu beside **Readwide** now stays visible on the Recent-files screen and provides hidden-file and cover-thumbnail on/off controls there as well. Multi-selection actions such as Select all, Share, and Extract archive also use adaptive width and unrestricted label wrapping.
- The image viewer's three-dot menu is adaptive as well, so long translated information, view, sharing, saving, and file-operation labels remain fully visible.
- Settings now has a dedicated global EPUB font. The book menu uses the same setting, existing choices migrate, and newly opened books consistently apply it. Fixed-layout/image EPUBs retain their publisher layout.
- **Open file** remains usable with document providers that grant temporary read access but reject Android's optional persistable-grant call.

## PDF viewing

- Hiding controls now removes the complete Readwide title bar and bottom toolbar in portrait and landscape.
- The PDF page immediately expands into the released app space; no toolbar-sized empty strip or navigation spacer remains.
- Android's status bar and navigation bar remain visible and protected, including landscape three-button navigation on supported devices.
- The current PDF bitmap is reused and refitted instead of decoded again, avoiding a toolbar-toggle render flash.
- Zoomed pages retain their relative zoom and reading focus, while vertical-continuous mode retains its visible content anchor.
- Rendering waits for a real non-zero viewport and cancels that wait on mode changes or teardown, avoiding invalid startup geometry and stale render callbacks.

## EPUB viewing

- Japanese vertical-writing EPUB bookmarks now save the sentence visible at the logical right-hand reading edge and return to that sentence. Stable publisher IDs are used when available, with text and position fallbacks for other books.
- Kusamakura's fragmented vertical sentence spans are now resolved from the actual on-screen caret point. The caret's normalized screen position is saved with the sentence and restored into the same visible area. Native WebView X/Y is reserved for a sentence-less emergency fallback and no longer overrides a valid DOM position on WebView variants with different vertical-scroll coordinate conventions.
- High-density Android/WebView pixel units are now converted correctly during that capture. Fresh saves no longer silently become page-start bookmarks, an old same-page `Page N / Position N` entry is upgraded when resaved, and restore verifies the target is truly visible before accepting it.
- Fixed the remaining Kusamakura save failure caused by the `epub:type` selector losing an escape between Java, JavaScript, and WebView's CSS parser. Sentence detection now uses the DOM attribute API, and helper setup plus capture are atomic. A page that genuinely exposes no sentence still saves its native vertical screen position rather than being reported as a file-operation error.
- The saved vertical focus point must now be a complete glyph inside a guarded safe viewport. Caret results snapped into a clipped edge or neighboring column are rejected before saving, while restore remains aligned to the caret rectangle center. Short ID-less Japanese utterances are retained.
- A newly saved vertical-writing bookmark now uses the first fully visible glyph near the start of the selected physical column for its displayed keyword. This clearer label does not change the precise sentence/caret anchor used for reopening or duplicate detection.
- Mixed-layout EPUBs no longer become globally fixed because only a cover or a minority of pages is pre-paginated; package defaults and per-page overrides are handled separately.
- Android status and navigation bars remain visible for EPUB and now always match the active reader paper color, regardless of whether Readwide's own controls are shown.
- EPUB no longer keeps a compact top page strip after the app controls are hidden, so the WebView uses the complete system-safe reading frame.
- Android status/navigation bars and display cutouts now remain outside that EPUB frame in both control states. Hiding Readwide's overlays no longer lets Haruko image pages draw beneath a punch hole or navigation area.
- Toggling EPUB controls no longer reinjects boundary CSS or invalidates the current WebView layout.
- Near-image-only fixed-layout pages no longer inherit long empty vertical scroll ranges from publisher wrapper overflow, including pages without usable size metadata. Zoom/pan remains available; text and mixed article pages in the same book retain normal scrolling.
- CSS background-image page art is preserved.
- Image-page landscape spreads, including CSS-background pages, now use a compact PDF-sized center gap instead of combining two large independently centered margins. Mixed image/text pairs and unpaired pages remain centered.
- EPUBs whose spine directly contains JPEG/PNG/SVG pages now display those pages in order instead of dropping them in favor of fallback XHTML.
- Japanese vertical-writing CSS is translated for Android WebView and no longer clipped by horizontal overflow rules, restoring later columns and their local font/image resources without overriding publisher cover placement or column padding.
- Per-page reflowable overrides remain scrollable inside otherwise fixed-layout image books.
- Reflowable information pages no longer remain trapped inside legacy fixed-canvas height limits on either the HTML root or body; Haruko's final HTML page now uses the complete reading height and then scrolls normally.
- Japanese vertical-writing chapters use the full screen height instead of a vertically centered short strip, while retaining their intended right-to-left column flow.
- Turning to a Japanese vertical-writing chapter now starts from its logical right-hand content edge; physical WebView x=0 is no longer mistaken for the CSS `vertical-rl` start position.
- Applying the Readwide paper color no longer clears publisher background-image resources on EPUB pages.
- The included 45-sample EPUB 3 audit confirms zero missing resolved spine resources. It also adds scoped local scripted/binding support, foreground playback for seven OPF-linked SMIL documents, and Georgia-style point-CFI navigation; the audit states the remaining browser, full-SMIL, range-CFI, obfuscated-font, and OPF progression/spread limits explicitly.
- Scripted books use a fresh local origin for each open and missing package resources no longer fall through to networking. Stale WebView completion callbacks, fallback overlay ownership, vertical cross-page CFI positioning, and SMIL seek/page-turn races are guarded.

This release adds no permission or dependency. PDF rendering remains local through Android's platform `PdfRenderer`; text search/read-aloud extraction remains local through PdfBox-Android.

## Notes

- Scanned/image-only PDFs still have no OCR.
- F-Droid builds are produced independently from tagged source and use F-Droid's signing key.
