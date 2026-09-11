# Readwide 1.0.18 development changes

This note records the implementation delta from 1.0.17, grouped by subsystem. Per-stage records remain in [archive performance history](ARCHIVE_VIEWER_PERFORMANCE_1_0_18.md); the sections below describe the final implementation.

## Adjustable archive viewer background timeout

- `SettingsActivity` preserves the timeout field's 8dp rounded background and 14dp horizontal padding during theme application. The XML keeps start/vertical-center alignment and the existing 48dp touch height. This presentation-only follow-up has not been rebuilt or device-checked.
- `PrefsManager` stores the numeric **Archive viewer background timeout (minutes)** setting, clamped to `0..10080`; the default `0` disables time-based closing.
- `ArchiveViewerTimeoutPolicy` uses one monotonic remaining-time calculation for callbacks, foreground return, and saved-state restore. Counting starts in `onStop()`, not `onPause()`; restored elapsed timestamps require a matching Android boot count.
- Numeric input uses overflow-safe parsing, preserves the saved value during empty/invalid edits, and immediately displays normalized values. Disabling view-text restoration and rereading preferences on focus loss prevent reset/import from being overwritten by stale text.
- Only confirmed stopped intervals are saved. Missing boot identity or state saved before `onStop()` does not create a guessed expiry. No alarm or wake lock is added.
- Stopped memory callbacks release bitmaps and invalidate obsolete decode work while retaining the archive index, entry metadata, credentials, and navigation state. `onStart()` reloads the same page when needed.

## Folder-aware archive image sequences

- `MainArchiveImageOpenController` delegates direct comic opening to `ArchiveEntryListController.collectImageSequence()` instead of sorting flattened images by basename.
- `FileSortUtils.sortArchiveImageSequence()` compares full internal paths in natural order, keeping repeated page names grouped by chapter.
- Direct and whole-archive auto-opening share this order. Archive-folder navigation retains the visible folder's order and scope, and macOS resource-fork entries remain excluded.

## Archive viewer scheduling and metadata reuse

- Spread-aware `ImagePrefetchMath.updateNavigationStreak` preserves adjacent navigation intent and resets direction for jumps. `ImageSequenceState` reuses immutable generation snapshots; `ImagePrefetchRequests` reserves up to 16 ordinary warm-up tickets before submission, with separate companion-quality ownership and stale-completion protection.
- Ordinary ZIP avoids redundant AES ZIPX probing, while ZIPX uses bounded leased indexes. Bulk fallback prefers rename over another image copy, and forward-reader teardown runs off the UI thread.
- `AlzipArchiveReader` reuses immutable decoded-name/member-offset indexes across listing, password-status checks, and single extraction. `EggArchiveReader` caches non-solid entry/block metadata and flags. Both retain bounded metadata rather than passwords, plaintext payloads, or open decoder sessions; larger indexes remain usable uncached.
- ALZ/EGG cache lookup checks the ordered volume set, and indexed writes recheck identity before commit. Failed single-entry extraction restores the previous target. EGG directory classification follows archive-wide filename decoding.
- `TarEntryIndex` supplies direct offsets for ordinary nonsparse plain-TAR members. Sparse, split, and compressed cases retain their established routes; single-entry scanning skips preceding symbolic/hard links.
- `ArchiveSourceSnapshot` guards preparation and viewer handoff for RAR and standard split 7z/CB7 sets using resolved paths, lengths, and timestamps. Failed or changed captures are rejected; complete all-volume preview-cache identity and content-hash invalidation remain outside this change.

## RAR3/RAR4 streaming, mixed modes, and standard filters

- `RarBitInput` shares a bounded bit reservoir between Huffman reads and aligned byte reads, retaining prefetched bytes across mode changes. Long counters, explicit payload bounds, short control-marker reads, and precomputed Huffman length counts avoid redundant bit work and false boundary EOF.
- `Rar3ClassicLzEngine.decodeMixed` and `Rar3MixedPpmdState` share raw history and a standard-filter queue across LZ/PPMd table switches. Saved classic-LZ match state stays separate from PPMd escape matches; supported solid continuation retains explicit table-reuse flags, distances, and match lengths.
- Production classic-LZ output streams through `Rar3PpmdFilterOutput` and buffered CRC/output sinks instead of whole-entry arrays. Mixed history grows from actual output up to its retained-history bound, not from an attacker-controlled declared entry size.
- The first-party fallback admits CRC/boundary-checked plain single-volume compressed solid runs with valid starting entries. Primers are verified before target output is opened; failed decoding, size, CRC, or cancellation invalidates shared state.
- PPMd-only single/bulk/forward paths use `Rar3PpmdPayload` for bounded plain/AES/split input, chunked output, and retained rolling history. Forward reading keeps verified entry spools and decodes skipped primers to a checked discard sink.
- PPMd VM records use the shared E8/E8E9/Itanium/Delta/RGB/Audio transforms, with bounded delayed output and retained program slots. Raw dictionary bytes remain separate from transformed checksum output; omitted escape-symbol fields preserve the existing symbol.
- Hardened VM program-slot memory, channel iteration, RGB parameters, long Itanium offsets, and cancellation. Custom VM, encrypted/split mixed streams, stored-member solid runs in the new mixed fallback, cross-entry ranges, partial overlaps, and unsupported queue resets remain rejected.

## RAR5/RAR6/RAR7 streaming and integrity

- Replaced whole-entry packed/unpacked arrays with volume-spanning input and chunked filtered output, removing the former 64 MiB packed / 256 MiB unpacked policy caps in supported stored/compressed, AES, split, and solid paths.
- `Rar5HistoryStore` uses lazy 64 KiB pages, a 64 MiB RAM cache, long ring offsets, and AES-GCM disk spill. The RAM cache is no longer a match-distance ceiling; the format-declared dictionary remains the logical bound.
- History cleanup covers reset, failure, and single/bulk/forward teardown. Temporary history uses ephemeral keys and free-space checks; large dictionaries can increase storage use and latency. Other preview or extracted files are not thereby encrypted.
- `RarBlake2sp` and shared checksum helpers verify decoded CRC32/BLAKE2sp and encrypted HashMAC. `RarPackedInputStream` checks intermediate plain or covered AES-ciphertext segments separately from final member checks, preserving RAR4's absent-intermediate-CRC sentinel without weakening RAR5 checks.
- `RarCryptoStreams` shares bounded segment reads and one CBC state across volume boundaries. Per-volume checksum flags may differ while required key/IV parameters must remain consistent.
- `RarVolumeChain` carries final-part size/checksum metadata, skips continuation records during solid planning, and uses monotonic scanning with identity-based duplicate rejection. Volume-name resolution reuses a sibling snapshot and handles case-varied names.
- RAR5 extra records use individually bounded cursors; high-precision time fractions follow whole timestamps, password-check data is required when flagged, and tweaked checksums are distinguished from plain CRCs.
- Eligible first-party RAR5 forward reading retains solid decoder history and verifies requested spools before publication. Native decoding remains preferred for ordinary supported plain routes; complete RAR compatibility is not claimed.

## Streaming and split special-7z decoding

- `SevenZBcj2ArchiveReader` streams supported Copy/LZMA/LZMA2/AES/BCJ2 chains, while `SevenZPpmd7Decoder.decodeStream` adds lazy model ownership, strict range EOF, and reusable scratch buffers. Former whole-stream/file/folder 512 MiB policy guards are removed from these streaming paths; model and metadata guards remain.
- `SevenZAdditionalCoders` adds bundled Deflate, Deflate64, BZip2, Delta, and x86/PowerPC/IA64/ARM/Thumb/SPARC BCJ filters to supported special graphs. Coder properties, graph shape, output lengths, and integrity checks remain enforced.
- Header, packed-stream, folder, and substream CRCs are checked, including inherited single-substream digests. Verified complete-folder spools are reused; CRC failures propagate rather than triggering a blind decoder retry.
- Standard split 7z/CB7 special-forward reading connects `SevenZSplitVolumeResolver` and `SplitVolumeInput` to header parsing and independent coder views without concatenating another archive copy. Source snapshots bracket setup and new-folder publication.
- Input ownership transfers only after successful special-coder detection. Decline, failure, close, and EOF release owned volume handles; failed folders cannot be reused. Whole-folder verification can still delay the first image, and unsupported graphs/split naming remain excluded.

## EGG volume handling and storage-based extraction accounting

- Store/Deflate/BZip2/LZMA EGG paths stream without the blanket 512 MiB ceiling. AZO retains its per-block array-memory guard; LEA and encrypted-solid combinations remain unsupported.
- Supported plain solid EGG uses a persistent forward session and CRC-verified block spools. Solid single-entry extraction validates the final containing block; zero block CRC continues to mean no CRC comparison.
- EGG volume discovery uses a sibling catalog keyed by ordinal, supporting case/zero-padding variants while rejecting gaps, ambiguous aliases, and overflowing ordinals. Both advertised forward IDs and previous-volume links are checked, including on index reuse.
- `EggArchiveReader.checkedPayloadEnd` rejects out-of-range prefixes/extras/blocks and unrepresentable sizes before publishing metadata. Decoded block/file totals and solid offset arithmetic remain checked.
- `SplitVolumeInput` validates physical extents and logical windows, uses binary-search segment lookup, and provides independent bounded cursors. Interrupted or failed physical reads retire the owner, preventing reuse after partial delivery; construction failures close opened handles.
- Removed the shared fixed 128 GiB extraction ceiling and viewer-only 2 GiB ceiling. Java/native output still passes through shared available-space accounting with the 64 MiB reserve, measured-space refresh, overflow checks, and guarded writes. The separate 2 GB inbound content-copy limit and codec-memory guards remain unchanged.

## File-list thumbnail scheduling

- `VisibleThumbnailBindings` tracks generation-keyed attached-holder demand. Unused pending work is removed, and reattachment or generation changes retry requests.
- `FileThumbnailLoader.decodeCachedOnly` serves existing PNGs through a separate bounded two-worker queue without source decoding. Source validation and atomic disk publication remain in place.
- Completion updates matching visible icons rather than rebinding the whole list. Queue rejection retries are coalesced, and unpublished results retain bitmap cleanup after release. Cold cover generation and synchronous cache writes remain possible sources of latency.

## File replacement and document search

- File/folder overwrites stage replacements before commit, with rollback and retained recovery backups if rollback fails. Ancestor-folder pastes and distinct case-only collisions are rejected, and cancellation cannot become success through a final size check.
- Document counts and highlights use the same non-overlapping ranges. Regex line anchors, HTML comment/attribute parsing, and Unicode tag offsets are corrected.
- TXT drawing consumes worker-produced highlight spans. Document recounting is asynchronous/debounced, and page navigation reuses cached counts and prefix sums.
- Large-TXT exact match indexes spill to app-private storage above 200,000 matches, with cleanup and scanning fallback if storage is unavailable. Regex cancellation remains cooperative.

## EPUB resource paths and media overlays

- Removed double percent decoding for local navigation, OPF-resolved SMIL/audio names, and decoded CFI fragments; chapter base URLs encode special folder characters.
- Narration requires foreground state and granted audio focus. Explicit/background pause cancels focus auto-resume, while preparation/seek/completion callbacks retain cue position and cannot release a replacement player.
- Audio caches use SHA-256 identities, size/CRC validation, and unique temporary files. Container/OPF DOM input uses the 32 MiB text guard, and raw chapter references are released during preparation; prepared HTML is still retained for the whole book.

## PDF restoration, search, and read-aloud

- Bookmark capture uses the active Matrix view's source-page point and relative zoom, with center anchors distinguished from legacy top-left coordinates. Cached/fresh pages restore after layout, and instance-state progress overrides old launch bookmarks.
- Background bitmap cleanup and configuration changes retain within-page anchors, including source-page mapping in two-page spreads.
- `PdfSearchController` clears results/highlights before debounce and on dismissal. `PdfTextSearchEngine` coalesces pending scans, snapshots options, and gates callbacks; cleanup stays serialized with extraction.
- `PdfSearchText` compiles one query per scan and preserves original UTF-16 ranges, inferred separators, non-overlap, multiline anchors, and code-point word boundaries. Empty regex hits do not suppress later matches; whole-document text/geometry remains retained.
- Both PDF read-aloud strippers retain inferred spaces/newlines. `PdfGlyphText` rejects ambiguous run-to-glyph mapping without disabling speech, and inferred separators use null geometry rather than borrowing adjacent glyph rectangles.
- PDF TTS checkpoints carry text-format version 1. Legacy/unknown formats or invalid offsets resume from the saved page; valid current-format positions remain exact.

## Localization

- Revised 216 strings in all 22 locale resource files: archive support/errors, shared read-aloud instructions, selected bookmark hints and Korean general UI wording. Preserve IDs, formatting arguments and preference behavior.
- Verified shared read-aloud callers in TXT, PDF and document readers before removing TXT-only wording. Archive descriptions retain EGG LEA/encrypted-solid and RAR custom-VM/encrypted-or-split mixed-mode exclusions.
- This is a targeted phrasing/consistency review, not native-speaker certification of every string. Static XML, duplicate-key, locale-key and placeholder checks cover all locale files; runtime layout and this UI/resource follow-up remain unbuilt.

## Resource and release changes

- Android metadata is `1.0.18` / `10018`; permissions, runtime dependencies and signing configuration are unchanged.
- Release notes, changelog, patch notes, code map, support summaries and EN/KR store changelogs describe final behavior rather than intermediate batches.
- The 1.0.18 license report and source/direct-dependency SBOM use the unchanged declared dependency baseline with current app identity; historical reports remain unchanged.
- Source-ZIP scripts preserve portable paths/modes and vendored CMake source, exclude generated/private material, and refuse existing destinations.

## Validation boundary

- The maintainer reported a successful 1.0.18 build before the timeout-field and localization follow-up. The latest UI/resource snapshot has not been rebuilt; unit-test, signing and device results have not been reported.
- Regression sources cover the changed paths; their presence is not a passing result. See [release readiness](RELEASE_READINESS_1_0_18.md) for remaining checks and [current source status](CURRENT_SOURCE_STATUS_1_0_18.md) for support boundaries.
