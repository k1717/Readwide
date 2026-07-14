# TXT, archive, and comic-mode engine audit (Readwide 1.0.15)

This note records the follow-up audit performed after the spread/TTS/PDF fixes. It separates verified routing improvements from archive-format claims that still depend on the bundled backend.

## Large TXT

- Sequential partition paging already uses `LargeTextPartitionReader.ForwardCursor`; its output remains field-equivalent to full scan under the permanent randomized suite.
- The remaining repeated-I/O path was search: the background total count scanned the whole file but discarded every position, so a later sparse Next/Previous or nth jump reopened the reader and scanned again.
- `LargeTextSearchEngine` now builds `LargeTextMatchIndex` during that count. It retains at most 200,000 `(character position, display line)` pairs in primitive arrays and uses binary search afterward. No document strings are retained.
- File path/size/mtime, query, option signature, blank-line collapse, and the active display-rule signature are part of identity. Cancelled, over-cap, or concurrently modified scans do not publish partial positions.

## Comic-mode archive flow

Previous startup behavior for forward archives was:

1. the loader opened a reader and decoded from archive start to the saved page;
2. it closed that reader;
3. `ImageReaderActivity` opened another reader and decoded from archive start again before it could read ahead.

The prepared `SequentialArchiveImageReader` is now transferred through `ImageSequenceHandoffStore` and adopted only when path/size/mtime and reader state still match. Every abandoned-provider, failed-launch, destroyed-activity, invalid-resource, and normal viewer-destroy path closes the owner.

The identity comparison now uses path/length/mtime captured when the reader was created. Comparing two live `File` instances for the same path was insufficient: after replacement, both returned the new metadata and an old open stream could be adopted for the new archive. Lazy and full sequence preparation also compare a start snapshot immediately before publishing their entry/cache plan. That original snapshot now travels through `Result` and `ImageSequenceHandoffStore.Sequence`; `ImageReaderActivity` rechecks it immediately before applying paths/password state or adopting the prepared reader, and discards the complete handoff on mismatch. This intentionally remains a metadata identity check rather than a full archive hash: replacement with the same length and forged mtime, or a change confined to a secondary file of a multi-volume set, is outside this lightweight handoff contract.

Password-backed prepared readers are transferred too. `ArchiveImageSequenceLoader.Result` snapshots the exact set of every sensitive cache path successfully verified during lazy or full sequence preparation, including entries committed by the prepared reader, and `ImageSequenceHandoffStore` transfers that set with the sequence. The viewer merges only those paths into its session set: all freshly decoded entries can be reused without a later `loadFully` extraction, but unrelated ready files in an entry-by-entry mixed-password archive remain untrusted. The prepared reader clears its cloned password on close. Forward reuse, on-demand reads, and prefetch use the same sensitive-cache gate as normal extraction; a ready marker by itself never proves that plaintext belongs to the current password session.

Libarchive can move to the next header without returning unread data, but that is not equivalent to decoding a member that contributes to a solid RAR dictionary. Skipped non-image and cache-hit entries are therefore decode-drained through the bridge's reusable direct buffer. This preserves sequential state without copying each block into the caller's Java byte array. Commons Compress readers retain their buffered drain fallback.

## 7z matrix

- COPY, LZMA/LZMA2, Deflate/Deflate64, BZip2, Delta, and branch-filter chains continue through Apache Commons Compress.
- PPMd and BCJ2 already have first-party extraction, including AES-encrypted/header-encrypted combinations.
- For an unencrypted special-coder archive, comic-mode forward paging now selects libarchive so it can cache images incrementally instead of failing the Commons forward decoder and falling into a full temporary extraction.
- Standard `.7z.001`, `.002`, ... sets pass the complete contiguous volume list to the forward backend. Missing-volume validation remains in `SevenZSplitVolumeResolver`.
- Password-protected PPMd/BCJ2 stays on the first-party path; bundled libarchive cannot decrypt 7z.

## RAR/CBR matrix

- Common RAR4/RAR5 files use the bundled libarchive reader; comic mode keeps that forward stream and the resolved volume chain for the session.
- Existing first-party stored, covered RAR3/RAR4 PPMd, covered RAR5 v5.0 compressed/solid/filter, visible-header AES, and header-encrypted RAR5 paths remain available through normal extraction fallback.
- A forward-stream failure does not reduce compatibility: the loader retains whole-archive and single-entry fallbacks and returns the original password/unsupported classification.
- The five user `sample-*.rar` files list and extract their first regular file in the JVM fixture smoke test when the bundled backend is available.

This is not a claim of complete RAR compatibility. Uncommon RAR3/RAR4 classic-LZ or PPMd table transitions, non-standard VM programs, damaged/recovery-record behavior, RAR7-era algorithm changes, and broad unverified split/encryption combinations remain backend-dependent or fail cleanly. Replacing those boundaries requires a substantially larger decoder project or an additional engine with separate licensing/distribution review.

## Bitmap warm-up

- On-demand preview remains capped at 16M pixels and detail at 48M pixels.
- Speculative neighbor decode uses the actual viewport display scale and two workers. An 8M-pixel cap is only a safety backstop for very tall fit-width pages.
- Turning onto a display-sized cached page does not schedule another detail decode. Original/detail decoding is reserved for an explicit pinch or double-tap zoom request.
- Animated GIF/WebP candidates bypass bitmap prefetch on API 28+, so a static first frame cannot be cached as full-quality animation.
- An index remains in the in-flight set until the main-thread cache decision, closing the former duplicate-decode window between worker completion and cache insertion.
- Archive bitmap warm-up is scheduled only when extraction returns success and the output still has a committed ready marker. Decode and main-thread commit validate the sequence generation plus exact index/path identity. They deliberately do not reject a valid bitmap merely because the direction plan changed: otherwise the replacement plan can be blocked by the old task's `in-flight` key and lose that neighbor entirely.
- A forward-reader prefetch checks its plan generation and waiting on-demand count before opening the next entry. It always finishes draining the current entry to preserve solid state, then yields the shared reader instead of continuing toward an obsolete distant target.

## Reader fullscreen follow-up

- PDF, TXT, document/EPUB, and image/comic viewers now share one navigation-bar visibility policy. Hidden controls enter immersive mode with transient swipe reveal; visible controls restore the navigation bar.
- PDF, document/EPUB, and TXT body roots reserve only immutable display cutouts; live side navigation excess is owned by overlay controls, keeping TXT width/page count stable as bars change. PDF chrome visibility no longer selects the body frame: portrait reuses the first valid toolbar-ON top/bottom reserves in both states, with the top fallback derived from the theme's `actionBarSize`, while landscape always uses the toolbar-OFF frame with only its fullscreen top safety inset and zero bottom reserve. Visible landscape controls overlay the body. TXT's vertical spacer remains chrome-gated, and image/comic controls overlay an always-full-screen canvas and own all system insets themselves.
- The hidden document/EPUB status strip uses cutout insets ignoring visibility and includes a status-bar reserve only when the reading preference enables it, so a transient bar reveal cannot resize the strip or WebView.
- The status-bar reading preference is independent of chrome visibility and is applied across all four viewer families.

## Verification targets

- `LargeTextSearchEngineIndexTest`
- `LargeTextForwardCursorEquivalenceTest`
- `ArchiveImageSequenceLoaderTest`
- `ArchivePreviewCacheTest`
- `ImageDecodeHelperTest`
- `ArchiveForwardReaderPolicyTest`
- `SevenZMethodCoverageTest`
- `SevenZPpmdArchiveTest`
- `SevenZBcj2ArchiveReaderTest`
- `ExternalArchiveFixtureSmokeTest` user RAR and password-7z cases

No dependency, permission, archive-creation feature, or network behavior was added.
