# Readwide 1.0.15

Readwide 1.0.15 is a correctness and stability update for landscape EPUB/PDF spreads and continuous read-aloud.

## Fixed

- Long-pressing text in the left/right page-turn zones no longer flips the page on release. Multi-touch and selection drags are also excluded from tap paging, while normal short taps still turn pages.
- PDF landscape spread navigation now behaves consistently across buttons, tap zones, swipes, volume/Page keys, and direction keys. EPUB uses the same spread controls only for image-page books; ordinary text EPUB stays a single responsive-width page in landscape. Even-page spreads no longer repeat their final page.
- Both EPUB pages accept swipe gestures, and double-tapping the right page now zooms the right page. RTL EPUB spreads also mirror their visual order and tap/arrow semantics.
- The document page slider previews without changing the saved/current page before release.
- PDF search and read-aloud highlights now work on both halves of a true two-page spread as well as the final unpaired page, including mixed page sizes and post-memory-cap geometry.
- Opening another PDF into the existing viewer now cancels and closes the previous document's search/TTS/render work. Late PDFBox/search callbacks cannot move, append results to, or highlight the replacement PDF; partial opens close their renderer/descriptor and clear invalid identity before error handling, and turning pages no longer cancels a same-document TTS text build. Same-URI cache replacement is staged, synchronized, and atomically committed so an older PDFBox/TTS reader is never truncated in place.
- Continuous read-aloud now finishes long final pages, skips blank/image-only pages when later text exists, preserves prefetched page state across pause/resume, safely tracks partially accepted TTS prefetch queues, and no longer restarts from delayed callbacks after Stop.
- PDF spread rendering uses substantially less peak bitmap memory, neighbor prefetch has a tighter memory budget, cached mixed-size pages retain their own geometry, capped continuous pages keep the correct display height, and stale cancelled render errors no longer override the current page.
- Large-TXT forward reading now has committed full-scan equivalence and no-skip/no-duplication regression coverage.
- Large-TXT match counting now produces a bounded reusable position index, so repeated next/previous/nth navigation no longer rescans a large file after the count has finished.
- Image viewing is safer under memory pressure and file changes: oversized cache entries cannot be recycled before display, deleting a page cannot reuse shifted index-cache entries, stale rename/delete prefetch results are rejected, failed loads do not leave the previous page visible, and zoom gestures release parent interception after completion.
- Archive comic mode reuses the already-positioned RAR/7z/TAR forward reader across viewer launch and decode-drains skipped entries so solid RAR state remains valid. Plain PPMd/BCJ2 7z (including split volumes) uses a forward stream instead of whole-archive temporary extraction. Neighbor warm-up is display-sized, never triggers an automatic second decode on page turn, and leaves original detail for explicit zoom.
- Encrypted forward-reader handoff now carries the exact set of every sensitive preview path successfully verified during sequence preparation. Freshly decoded entries are reused without a later full extraction, while unrelated old ready files in a mixed-password archive remain untrusted. Failed/stale prefetch paths cannot be decoded, superseded deep RAR/7z prefetch yields between complete entries, and the original archive metadata snapshot is checked again just before the image viewer applies the sequence, rejecting replacements made during preparation or launch.
- Android `.tar.zst` and plain `.zst` now use the bundled libarchive Zstandard filter instead of desktop-only JNI resources. Missing optional codecs fall back or fail cleanly rather than crashing, and the decoded-size safety limit remains enforced.
- The APK now carries the libarchive-android and bundled-codec license notices as a source-controlled asset; release documentation, the license report, and the SPDX SBOM were updated to match the runtime/test dependency boundary.
- Hiding controls now provides real immersive reading in PDF, TXT, EPUB/document, and image/comic viewers. Stale navigation spacers and hidden toolbar/slider padding no longer leave a bottom boundary, and the status-bar reading preference applies across all viewers.
- Image/comic controls now overlay a stable canvas, eliminating the vertical jump on middle tap. EPUB footnote/anchor links work across pages and spread panes; saved reading/bookmark/theme JSON is atomic; document XML parsing is hardened; and extreme PDF render dimensions are capped safely.
- PDF toolbar toggling no longer changes the body frame. Portrait keeps one toolbar-ON frame, while landscape keeps the toolbar-OFF safe frame even when controls overlay it; this removes portrait movement and landscape shrinkage. Text-only PDF pages render on opaque white paper, and full-page composition prevents top/bottom trimming.
- Landscape-first launch and rotation while controls are hidden no longer leave portrait PDF chrome reserves incomplete. Exact inset-aware fallbacks use the active theme's action-bar size and discard stale status/navigation-frame reserves; PDF/EPUB/document readers reapply immersive bars after OEM rotation handling.
- Document/EPUB's hidden page strip and the TXT body no longer react to transient system-bar visibility: the strip uses visibility-independent cutout/status-preference reserves, while live side-navigation excess affects only TXT overlay controls, preserving text width and page count.
- EPUB left/right/top/bottom sliders now directly refresh the active page on every step, including a same-page HTML reload fallback with scroll restoration, and remain physical-pixel settings. The proportional 36dp side scroller now also covers Word, HWP/HWPX, Markdown, and PDF continuous mode, with a 32dp minimum for very long documents. It appears only for real scrolling or an active thumb drag, fades completely transparent at rest, and no longer leaves an invisible edge input trap. PDF uses an indexed page-height estimate refined as pages render and jumps directly to the target row rather than binding every intermediate page.
- Toggling the PDF toolbar neither resizes nor rerenders the current page. The accepted bitmap and its viewport coordinates remain unchanged across the toggle.

- PDF visible, prefetch, and continuous rendering share the same sizing/cap primitive while retaining their intended mode-specific height constraints; stale prefetch results from a previous toolbar/fullscreen geometry are rejected without worker-thread `View` reads, cancelled renders cannot overwrite current page geometry, and sharpen patches are bound to the current base render and fail safely under memory pressure.
- EPUB now opens UTF-16 and declared-charset XHTML correctly, preserves literal `+` across manifest/resource/link/extraction paths, parses and replaces fixed-layout viewport metadata regardless of attribute order, and no longer erases CSS-background page art or falsely treats `background-image:none` as an image page.

## Internal

- Added shared spread-boundary tests, permanent large-TXT cursor/full-scan equivalence tests, and consolidated page-turn routing.
- Fixed the API-24 compatibility issue found by an earlier full-lint pass. Release verification must rerun debug lint and the documented build commands on the exact tagged tree.
- No new permissions or dependencies.

## Known limitations

- PDF continuous-scroll search still navigates to the result page without drawing the bitmap rectangle overlay; the new per-half rectangle mapping applies to single-page/two-page Matrix mode.
- Scanned/image-only PDFs have no OCR.
- RAR/CBR support remains limited and backend-dependent; complete encrypted, split, SFX, VM-filtered, and future-format compatibility is not claimed.
