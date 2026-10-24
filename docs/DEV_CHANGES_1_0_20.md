# Readwide 1.0.20 development changes

This note records the implementation delta from 1.0.19, grouped by subsystem. Format-specific boundaries remain in [archive support notes](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md); source licensing is recorded in the [license report](LICENSE_REPORT_READWIDE_1_0_20.md).

## TXT exact-page indexes and partition navigation

- Large exact-page indexes use streaming serialization and checksum validation instead of the former 8 MiB saved-index admission limit. Cache validation remains separate from calculation: a checking indicator is not an estimated page count, and changed source/layout identity can still require a full recount.
- Built-in font identity remains stable across reopening, including supported weight/width variants. Imported font reuse requires a matching face identity; in-process reuse does not certify a newly imported or unavailable font.
- Retained partition data, direct binary anchor lookup, and combined text/statistics reads reduce repeated work and whole-anchor copies. Foreground navigation and background exact indexing retain separate ownership and scheduling.
- Active normal/fast scrolling prepares an adjacent partition before the edge. Adoption waits for a valid handoff and preserves the measured visible baseline; explicit jumps or newer touch intent cancel obsolete landings. Backward lookbehind does not become a forward jump.
- Sequential navigation follows actual resident-window/document boundaries while exact totals are unavailable. Estimates are display/navigation hints, not sequential movement limits. Automatic turning waits for text/layout readiness and stops at real EOF instead of accumulating pending turns.
- True-EOF overlap-only tail pages are excluded. The `txt-v6-eof-overlap` identity rebuilds affected old exact caches without changing the binary cache format. Ordinary page-step, overlap, and partition sizes remain unchanged; existing bookmark characters/context are not migrated, and page hints refresh after a valid index is ready.
- Page labels, in-page row labels, newly captured bookmarks, and saved progress share a measured visible-row anchor. The first row remains selected while at least half is visible; a successor is used only when actually inside the drawing clip.
- Partition seams retain the shared row origin/baseline used by that anchor. An unmeasurable row suffix is omitted instead of displaying a partition-local row as a global-page row.
- Positive-height viewports shorter than a row retain their available drawing area; zero-area layouts are not ready. Character/line arithmetic uses checked wide intermediates before returning supported integer coordinates. Oversized coordinates fail instead of wrapping; there is no new 64-bit text-coordinate representation.
- Fractional drag movement is accumulated. Unsupported multiple-pointer gestures are cancelled until a new contact, and manual scrolling from an extended search position returns continuously to ordinary bounds.
- Percentage and displayed-line moves resolve against the whole large document. Known-line/resident-cache paths avoid a full scan when their mapping is valid; unknown coordinates retain the asynchronous full-scan fallback, including inserted line breaks and collapsed blank lines.
- Page-move popup and toolbar bounds refresh when exact totals arrive. Their scale is frozen while dragging and current bounds are applied on release.

## TXT loading, restoration, and search

- `ReaderFileApplyController` retains the absolute fallback when initial context matching cannot locate it in the first window. Out-of-window restoration re-enters `ReaderFileLoadController` without stale line/byte partition hints, keeping first-load screen reveal, loading completion, and exact-index startup together. Matching context still wins; real EOF uses the existing clamp.
- `ReaderFileLoadController` reports worker-side `OutOfMemoryError` from input/transform preparation through its generation-owned failure callback, without retrying the same large allocation. This does not cover every UI/native allocation or guarantee recovery from process-wide memory exhaustion.
- Initial text and saved positions are adopted only after the owned current-text layout is ready. A posted callback or an unchanged view size alone cannot mark a new layout complete; cancelled/replaced loads cannot perform a stale landing.
- Snapshot, index, and search identity includes source metadata, applied encoding/text transformations, partition policy, and relevant rendering inputs. Requested-but-unapplied preferences cannot certify the old text or geometry.
- Reloads preserve the owned destination and recovery context while dropping invalid page/pixel/partition hints. A failed replacement cannot relabel the old window as belonging to the new partition policy.
- Pending destinations survive recreation and memory release. Same-file instance state takes precedence over stale launch anchors, while an explicit new target cannot inherit an old restore request.
- Picker/SAF launch ownership is retained while reading the resolved local copy, allowing restoration after memory cleanup without confusing URI and local-file identities.
- Restoration clamps at actual EOF, including shortened sources, before enabling persistence. Stale source/decoding sessions cannot overwrite saved reading state.
- Rendering identity includes overlap and the presentation actually applied to the view. Restored settings preserve source characters/context but invalidate page/pixel hints until the matching layout exists.
- Search maps normalized matches back into ordered, deduplicated original UTF-16 offsets. Bounded backward search uses one pass, and retained match indexes are invalidated when text/source policy changes.
- Context recovery considers overlapping candidates with overflow-safe ranking. Selection endpoints, wrapped final lines, and saved range ends use checked boundaries.

## TXT Contents detection and customization

- Contents provides search, current-heading selection, source-position navigation, manual titles at the current position, hiding automatic entries, deleting manual entries, rescan, and restoring hidden titles for the same text version.
- Six built-in heading families cover English Chapter/Part/Book; Korean episode/chapter/part/volume; Chinese/Japanese numbered headings; `345===`; `345---`; and `<--Title-->`.
- Eligible bare markers, including `761화` and `제761화`, can combine with the immediately following letter-starting title. Navigation remains anchored to the marker.
- Defaults require repeated heading families and blank/file boundaries. Dominant decorated sequences help suppress sparse author-note mentions; this is heuristic detection, not semantic classification.
- There is no fixed chapter-number or heading-count cutoff. Actual detection settings, source-coordinate bounds, and memory still constrain what can be represented.
- Per-file options select the six families and blank-boundary, repetition, author-note, and two-line conditions. Disabling all formats leaves manual entries. Reset edits the draft; Cancel does not apply it; Save validates and publishes before an off-thread rescan.
- Optional RE2/J 1.8 rules match each whole trimmed nonblank line. Group 1 supplies the title when present, otherwise the full match; the first nonempty custom result precedes built-in detection. Shared detection conditions still apply.
- Custom rules are limited to 8,192 UTF-16 units in total and candidates/titles to 180 units. Lookaround, backreferences, scripts, and arbitrary multiline parsing are not supported.
- Automatic index revision 6 includes the options identity. Automatic caches remain separate from durable options/manual/hidden records, which are scoped to path and text-transform version.
- Durable records are included in the optional JSON backup section described below. They are not automatically rebound after rename/move, changed content, or a different text transformation.

## Reader controls and localization

- Contents uses rounded action/editor surfaces, inset explanatory text, compact main rows, slightly separated actions, and a 12 dp bottom gap below Close.
- Options uses a dedicated horizontal Reset/Cancel/Save layout rather than platform auto-stacking. The regex example has its own space above the input. Child actions retain their touch targets.
- Font cards use content-aware height with a 40 dp minimum instead of a fixed row height; list dividers are removed while rounded outer borders and selected styling remain.
- The EPUB font picker follows the language-selection layout without radio/bullet markers. The TXT bookmark Close label moves upward within its existing touch area.
- Contents and reader explanations are localized; Korean page labels use “페이지.” These changes do not establish native-speaker review of every locale.

## Settings and backup recovery

- Strict UTF-8 reads reject malformed/truncated saved JSON instead of replacing characters in titles, context, or paths. Valid Unicode and existing writer/schema behavior are retained.
- Numeric preflight checks raw JSON before tolerant model conversion: reject fractions, overflow, wrong types, invalid theme colors, and non-finite/out-of-range opacity. Valid exact long strings remain supported where the schema permits them.
- `BackupTextContext` resolves portable bookmark edits with prospective restored encoding/display preferences, reusing per-book context. Edits for unavailable books remain pending, and preference drift before commit aborts the operation.
- `TxtContentsBackup` supplies optional `txtContents` version 1 data. Options are keyed by validated path hashes; manual/hidden records additionally require validated text-version keys. Record contents, regular expressions, types, and coordinates are checked before mutation.
- Imported identifiers select only fixed app-private durable-record locations, never arbitrary filesystem paths. Source documents and automatic Contents indexes are excluded.
- One ownership boundary covers Contents snapshot, commit, and rollback. Per-record undo is prepared before mutation, and Contents writes precede preference publication so failed imports can restore prior records.
- An absent Contents section leaves local data unchanged for backward compatibility. Replace is authoritative only for its two durable-record directories; merge applies matching options/manual IDs and unions hidden IDs.
- Corrupt existing records abort rather than disappearing silently. Linked paths are rejected, and `.bak` inventory is admitted only for the internal AtomicFile recovery contract.
- Restored reading state preserves source characters/context when rendering preferences invalidate page/pixel hints. Valid local folder fallbacks are retained; unavailable fonts fall back safely.
- Backup does not carry document/font/theme files, create external folders, transfer SAF permissions, or remap moved paths. Contents recovery still requires matching path/text identity. Multi-store rollback is not a power-loss journal or a cross-device relocation guarantee.

## Archive image sessions and thumbnails

- `ImageReaderActivity` separates the requested page from the last accepted displayed image. Progress and recreation use the accepted entry in the current cache namespace; memory trimming does not promote an unreadable or pending request.
- Foreground return inspects the actual surface. A retained drawable is reattached through `ZoomImageView.restoreImageReady`, preserving its matrix and restarting supported animation; a missing/recycled surface reloads the same page.
- Foreground-load generation ownership prevents duplicate work during sequence/password restoration or an already pending image request. Foreground allocation failure reaches error UI instead of silently leaving a blank surface.
- Later-page extraction keeps password-required and wrong-password results typed, using the existing password/rebuild flow. Cancelling that flow still closes the viewer and retains the last accepted progress.
- WebP/GIF decoding has bounded lower-allocation retries for foreground, detail, and neighbor work. Successful quality and animation behavior remain unchanged.
- `ImagePrefetchRequests` handles queued foreground takeover and valid running joins. Immutable sequence snapshots avoid repeated full-list copies; stale callbacks release only their owned resources, and unnecessary full-quality upgrades are avoided.
- Eligible plain Store/Deflate ZIP/CBZ inputs can overlap through independent handles. Stateful formats retain scoped sequential readers, and verified solid-folder spooling can delay first display.
- Prepared archive metadata and bounded cover-candidate selection reduce repeated parsing/full sorting. Cached-image metadata work has a separate worker from slow solid extraction.
- `ArchiveSourceSnapshot` identity is retained across cache use, extraction, and decoding; resume resolves the full volume membership. A changed source retires the visible session and blocks stale progress until reopening.
- Split-volume cover keys include resolved paths, sizes, and timestamps, including memory-cache validation. Obsolete cover tasks are cancelled at decoder checkpoints after the last subscriber leaves.
- Sequential reader construction performs I/O outside the short retirement lock; an epoch rejects late publication into a retired session. Slow close and failed-cache cleanup remain off the UI thread.
- Metadata identity cannot detect same-size/same-timestamp edits or make extraction transactional. The reported long-background black screen has a recovery path, but its exact device cause and runtime resolution remain unconfirmed. The configured background-close timeout is unchanged.

## PDF rendering and incremental search

- Renderer and descriptor ownership form one session/lease. Opening and retirement run off the UI thread; a superseded result cannot close resources adopted by another request.
- Rapid portrait-page navigation checks request validity before bitmap allocation and rasterization, reducing avoidable queued work without adding animation. Running native rasterization is not forcibly interrupted.
- Search advances incrementally at page boundaries. Glyph data is cached only within the small-document budget of eight pages and 128K UTF-16 slots; larger documents may need re-extraction for a new query.
- Failure clears incomplete search state rather than publishing a complete cache. Superseded PDFBox documents close on workers, with page/highlight ownership retained by the current request.
- Scanned PDFs have no OCR. No device-measured rendering speedup or uniformly smooth behavior is asserted.

## DOC, DOCX, HWP, and scientific notation

- Word styling distinguishes omission from explicit normal/left/baseline resets, retains inherited line height, and keeps highlighting separate from shading. Legacy DOC spacing uses unsigned twip values where required and preserves explicit zero indents.
- DOCX visible content wrappers are followed in body, cells, headers/footers, and notes without duplicating nested content. Blank paragraphs, special hyphens, and adjacent same-style runs retain their intended output.
- Legacy DOC fields use nested instruction/result ownership so hidden outer instructions do not leak. Saved field results are displayed, not recalculated; malformed piece bounds and compressed character decoding are checked.
- Text boxes and alternate-content branches select supported visible content/fallback pictures without displaying both branches.
- Paper dimensions, margins, and authored content proportions scale together. DOCX/HWP tables preserve more placement, padding, alignment, and height information; binary HWP structural pagination and list wrapping are improved.
- Resource exhaustion does not trigger another expensive parsing attempt. Complex floating objects and office pagination remain approximate.
- Supported scientific subscript/superscript notation, grouped commands, simple fractions, and split-run expressions map to styled preview text, alongside admitted DOCX math/HWPX styling. Ordinary underscores and unsupported literal expressions are retained; this is not a full TeX or Office equation engine.

## Markdown and EPUB styles

- `DocumentWebViewController` retains finite positive scale observations per WebView/load generation. Current owned callbacks feed fast-scroll range and Markdown visual height; initial or unmatched loads use the existing uncached `getScale()` fallback because unchanged initial scales need not produce callbacks. Invalid, replaced-view, obsolete-load and destroyed-activity callbacks are rejected.
- Scale changes update the ready Markdown model without saving progress or queuing the page-load retry sequence for every pinch frame. Zoom reset retains its 18-attempt bound, using `zoomOut()`'s result rather than a separate stale `canZoomOut()` precheck. Gesture fit/minimum-zoom decisions retain the existing platform query; minimum scale is not guessed from density or the first callback.
- Document/PDF system-bar icon appearance has one compat-controller owner; redundant legacy flag writes are removed. Existing background fills, insets and older-Android color/contrast calls remain. TXT canonical top padding, page calculations, caches and bookmark coordinates are unchanged; nonlinear-font padding normalization remains deferred to preserve page boundaries.
- Markdown code and generated link HTML use opaque tokens so later inline parsing cannot corrupt code or URLs. Escaped raw HTML is handled once; indexed backtick runs, fence length, leading code blanks, and source anchors preserve the intended boundaries.
- The lightweight Markdown renderer does not claim full CommonMark support for every nested list, reference link, or escaped table delimiter.
- EPUB's quote-aware tokenizer confines compatibility aliases to style attributes and style blocks, excluding comments and raw-text/script-like elements.
- Layout hints follow chapter-local linked/imported styles rather than applying one book-wide hint blindly. Local references use decoded paths and charset/BOM handling while rejecting external/root-escaping resources.
- Hint scanning uses bounded summaries/traversal and stylesheet previews; those limits constrain optional hint analysis, not EPUB admission or WebView rendering.
- CSS declaration walking respects quotes, comments, functions, custom-property braces, and `!important`. It avoids duplicate aliases and descriptor/animation false positives, retaining writing-mode order/priority within the relevant block.
- Known non-screen media is conservatively excluded from hints. The WebView performs the actual cascade; the hint parser does not compute every selector, variable, script, or dynamic media condition.

## Document resources and position persistence

- `DocumentZipSession` leases keep active resource reads valid while a document is replaced. Stale parse results close only their owned sessions.
- Reading state is saved only after the current document is adopted and position restoration has a valid layout. Same-file saved instance state outranks an old launch position.
- Markdown bookmark capture checks document generation and scroll ownership around asynchronous JavaScript anchors, uses bounded retry, and does not fabricate offset zero on failure.
- Native scroll fallback and an outgoing cached checkpoint remain available when document replacement prevents a fresh anchor callback.

## Read-aloud continuity and lifecycle

- Source text is queued in bounded segments of up to 1,400 UTF-16 units with at most 32 lookahead segments. Stable utterance IDs and source cursors preserve partial queue acceptance.
- Continuous TXT narration follows resident text across pages without replaying page overlap; single-page narration stops at its original range.
- Pause/restart, page/file replacement, callbacks, highlights, and audio focus are generation-owned. Finish-sentence timers belong to active playback and the current deadline.
- TXT checkpoints use absolute transformed-source identity. Incompatible legacy relative TTS checkpoints are rejected while ordinary bookmarks remain unchanged.
- Failed restarts stop the old queue, focus, and notification. A matching offset-zero resume remains valid; orphaned commands do not leave a sticky service alive.
- Sentence/word splitting remains heuristic, and background survival depends on the Android TTS engine and system lifecycle.

## RAR3/RAR4 logical streams and VM filters

- First-party LZ/PPMd routes share bounded logical plain/AES/split input for selected, bulk, and verified-forward extraction. Admitted independent and mixed-solid runs retain verified primer history.
- Stored companions preserve compressed history without injecting stored bytes into it. Encrypted-header and split-rewrite temporary resources remain owned through final stream closure.
- A one-pass split-chain index supports required-solid-chain planning. Packed sizes, source bounds, AES alignment, and final integrity metadata are checked before the selected output is opened; unrelated later damage need not invalidate an earlier admitted selection.
- Six standard filters and scoped custom VM programs retain raw dictionary history separately from final filtered size/CRC. Within-entry resizing chains, trailing controls, and zero-final-output cases follow that distinction.
- Supported future descriptors can cross explicit solid boundaries when their state remains unconsumed. Partially consumed cross-file filters, straddling/late records, and unsupported ordering remain rejected.
- Cancellation stays terminal through password derivation, rewrite/copy, decode, and checksum work. Failure cleanup preserves the primary error and retires shared state.
- These are admitted first-party paths, not a full UnRAR implementation or a guarantee for every RAR producer.

## RAR5/RAR6/RAR7 boundaries

- Known/unknown-size streams require valid compressed endings and explicit last-block handling, including AES alignment tails. Empty first/reset entries and zero-output solid continuations still process required metadata.
- Dictionary planning validates admitted solid growth against retained history, using encrypted paging where applicable rather than assuming every requested history byte exists.
- Ordered same-block DELTA/E8/E8E9/ARM filters handle short blocks and final output boundaries. Unsupported schedules fail instead of returning apparently complete output.
- Plain stored unknown size can follow packed length. Encrypted stored unknown size is admitted only when CRC/BLAKE2sp, including applicable keyed verification, selects a unique plaintext length; ambiguous candidates remain unsupported.
- Links, junctions, and file-copy redirection records are not ordinary empty files. Their actual restoration is outside the current support boundary.
- Packed-end, checksum, cancellation, source-change, and decoder-failure states remain terminal across selected/bulk/forward routes.

## Shared 7z coder graphs and supplemental codecs

- The coder registry serves admitted plain/AES, encoded-header, split, external-name/folder-definition, and additional-substream graphs. Canonical empty streams are distinguished from malformed metadata.
- Differential archives expose supported readable contents while skipping anti-items; they are not applied as filesystem backup deletions.
- Packed/folder/substream integrity, PPMd7 flushing, BCJ2 initialization/failure, and valid LZMA/LZMA2 endings are checked consistently. Public LZMA2 ArrayCache construction avoids the inaccessible constructor; reader-owned reusable arrays are cleared.
- Supplemental profiles include Zstandard, modern/legacy LZ4, LZ5, Lizard, Brotli/Brotli-MT, and LZHAM. Source-built Brotli 1.2.0 supplies admitted large-window decoding.
- LZ4 dictionary IDs are accepted only when the stream does not actually need unavailable external bytes. LZ5/Lizard linked history and checksums, and admitted LZHAM property layouts, retain their own profile checks.
- Unknown flags, experimental methods, external-dictionary requirements, and unsupported graph combinations still fail explicitly. Verified whole-folder spooling can delay the first selected image.
- Another engine is considered only when a route is unavailable/inapplicable before output, not after payload writing, corruption, cancellation, or a changed source.

## ZIP/ZIPX streams and encryption

- ZIP64/split offsets, canonical/duplicate names, and leased indexes use consistent source identity. Ordinary ZIP avoids unnecessary encrypted-ZIPX probing.
- Existing and supplemental method routes include admitted Shrink, Reduce, Implode, Deflate64, BZip2, LZMA, XZ, Zstandard, PPMd-I, JPEG, and WavPack handling. Codec presence does not prove every plain/ZipCrypto/WinZip-AES combination.
- Independent PPMd-I freeze-profile selection verifies final size/integrity before accepting a unique or identical result; conflicting candidates are rejected.
- Concatenated XZ/BZip2 completion and bounded member input are checked. Local/central AES version, strength, and method must agree; authentication and applicable AE-1 CRC checks complete before output commit.
- Buffered AES processing reduces small-read overhead while clearing sensitive state and preserving terminal failures. Fallback does not restart after output or integrity failure.

## EGG, ALZ, CAB, LHA, and TAR

- EGG adds LEA-128/256 alongside admitted existing encryption routes. Encrypted-solid blocks have one staged ownership/authentication boundary before entry decoding; decoded totals and CRC include empty blocks.
- Forward/nested EGG parent-directory references, locale names/comments, padding, and empty terminated archives are handled within the parsed format. Encrypted filenames remain unsupported.
- EGG/ALZ ZipCrypto candidate encodings, including UTF-8/MS949, use decoded size/integrity when several header checks succeed, before output acceptance. A unique header candidate avoids redundant probing; legacy cases without integrity retain their narrower boundary.
- ALZ split discovery extends past `a99` through later lettered volumes, with alternate names checked for ambiguity/gaps. Framed/raw continuation, empty archives, comments, and timestamps retain their distinct parsing paths.
- AZO reduces per-symbol allocation and removes the fixed model quota while retaining actual RAM/array bounds.
- Linked CAB sets use checked ordinal/name/continuation metadata and a separately linked libmspack module. Guarded verified spools and full set identity cover listing, selected output, and forward reading; this is not unrestricted CAB compatibility.
- LHA LH4 uses its 4 KiB history. Admitted Amiga `.lha/.lzh + .l01/.l02` byte-split archives validate header levels 0/1/2/3 before verified spooling; undocumented multi-disc layouts and other unimplemented methods remain excluded.
- Full compressed-TAR extraction completes the underlying decoder/integrity checks. Fast selected-entry reads do not certify the unread tail, and borrowed stream failures remain terminal.

## Archive output safety and resource policy

- Fixed extraction, preview, and model-size quotas are removed from the reviewed paths. Free-space protection retains the 64 MiB reserve and cumulative guarded writes, with size/CRC/authentication and overflow checks.
- Actual decoder arrays, integer coordinates, memory, temporary storage, and operating-system limits remain. “No fixed quota” is not “unlimited size.”
- Source discovery and selected/bulk routing share validated identities and entry classifications. Unsafe names, links/devices, file-directory conflicts, incomplete split sets, and unsupported records are not silently converted into safe-looking output.
- Replacement uses staged output and rechecks canonical source membership before commit, including destinations containing any active source volume. Existing output is protected on supported rollback paths.
- Cancellation before commit does not erase previously accepted output; cancellation racing a completed commit does not turn success into destructive rollback.
- Cleanup retains the primary failure and records secondary close/rollback problems. Metadata checks and staging are not a race-free or whole-archive durable transaction guarantee.

## Home, settings, and navigation state

- Home's Recent files and Pinned folders titles use 12 sp text and 38 dp minimum rows, down from 15 sp/42 dp. Recent-header vertical padding is 2 dp per side, retaining the 34 dp Clear all control.
- `MainRecentFilesController` keeps unchanged holders through `FileAdapter` item diffs. Progress payloads update badges and revalidate covers without clearing an already displayed same-binding archive cover. Rejected volume identities invalidate retained images; new holders cannot display unvalidated archive-cache hits. Selection/progress payloads compose, and unknown payloads use a full bind.
- `MainNavigationState` restores compact Home/folder/search/query/filter/Back anchors after unlock and list reload. Unavailable folders return to Home; full lists and running jobs are not serialized.
- Recent-file preparation uses a separate cancellable worker so a paused filesystem job does not block it. Swipe removal invalidates pending rows/snapshots.
- Settings font discovery is lazy/off-thread with built-ins available immediately. Reset includes file display mode, and returning from settings refreshes relevant sorting/visibility/search state.
- Subfolder search intentionally treats the chosen folder as the search starting point; this behavior is unchanged.
- Successful moves/renames rebind saved folder references by whole path segments, alongside existing reading/bookmark/annotation handling. This is not an atomic filesystem/metadata transaction, and Contents retains its separate path/text identity.

## Clipboard, rename, and operation queues

- `FileClipboardBatch` owns worker planning/results; `MainClipboardController` owns UI claim/completion; `FileClipboardController` owns queue state; `FileSystemOps` owns staging/commit/rollback.
- Destinations are resolved just in time, including case aliases and dangling-link occupancy. Only committed items retire from the clipboard; failed items remain retryable.
- Captured item/mode/generation checks reject stale callbacks. Completion refreshes the current relevant context instead of navigating back to an obsolete folder.
- Never-started/cancelled tasks release their ownership and password data. Paused queue heads remain owned, and old callbacks cannot complete another job.
- Case-only rename uses a checked temporary hop. If recovery is blocked, the recoverable temporary file is retained rather than overwriting an unexpected target.
- Bulk replacement protects all source volumes and rejects unsafe ancestor destinations. Partial success and post-commit cancellation are reported without discarding committed results.
- Running filesystem operations are not restored after process death; no durable multi-file transaction is introduced.

## Resource and release changes

- The libarchive binding now uses Java 17 source/target like the app, without new Java APIs or a minimum-Android change. Android Gradle properties use explicit assignment, and removed-source cleanup resolves files during configuration instead of accessing Project from a task action. Release helpers request only `:app:assembleRelease` and show Gradle warning details; app javac reports deprecated call sites without suppressing them.
- External native staging uses short `cxx/archive` and `cxx/zipx` directories; WavPack's binary subdirectory is `wv` to leave room for its nested architecture probe. LZ5's compression-only constant follows its existing decode-only guard. LZHAM's no-exit branch excludes the unused exit flag, marks unused location parameters, and labels the intentional decoder-only portable build so it does not warn about compressor threading. Decoding, exception behavior and fallback platforms remain unchanged.
- Document renderer/extractor test fixtures use the actual `new TextStyle.Builder()` constructor; six calls to a nonexistent static `builder()` factory are corrected without changing production rendering or assertions.
- `ArchiveTestTextFiles` replaces the Java convenience text-file methods unavailable to Android local-test compilation. It preserves strict UTF-8 decoding/encoding and create/truncate/write defaults, encoding before opening an existing destination. Archive assertions and fixture data remain unchanged; the standalone ALZ fixture path uses `Paths.get`.
- Version metadata is `1.0.20` / `10020`. Historical release notes remain records of their own versions.
- Supported Android 13+ launchers can use the themed icon; the normal book artwork remains. Localized Contents/reader resources accompany the UI changes.
- RE2/J 1.8 is BSD-3-Clause. Added source-built decoder components retain their own notices: Brotli and LEA/LZHAM components use MIT terms, scoped RAR VM/LZ5/Lizard components retain BSD notices, and independent PPMd-I sources retain their public-domain notices.
- CAB uses a separate libmspack shared module under LGPL-2.1 terms. Existing ZIPX JPEG/WavPack components retain their respective LGPL/BSD boundaries. Corresponding sources/notices remain included; the app's first-party source remains Apache License 2.0.
- No full proprietary UnRAR engine is introduced. Dependency declarations and source inventories are not a resolved-transitive license audit or F-Droid approval.
- No new ads, analytics, account, cloud sync, or `INTERNET` permission. Android Auto Backup remains disabled; archive previews may create local plaintext temporary files, and encrypted RAR history does not encrypt every cache.
- Bundled F-Droid metadata still refers to earlier released versions. A successful local build does not establish an F-Droid build, approval, or publication.

## Validation boundary

- Release APK assembly, local-test Java compilation and Android-test APK assembly completed successfully. This covers the Home edits and the test I/O/`TextStyle.Builder` corrections, but no test suite was executed and device behavior remains unverified.
- A subsequent signed release build completed after the build-setting/native warning cleanup, with v2 signature verification. It displayed 100 deprecated-API warnings; this was not a zero-warning build or a test-suite result.
- Subsequent system-bar/WebView fixes have static review only. Eight instrumentation regression methods cover scale observation ownership, fallback and invalid values; they have not been compiled or executed. These changes have no follow-up build, device or benchmark results. Necessary legacy-Android/abstract API implementations and gesture zoom probes remain; warnings are not globally suppressed.
- Earlier host/device/build results apply only to their recorded checkpoints. The confirmed large-TXT saved-count fix does not validate subsequent partition, lifecycle or cache changes.
- Runtime checks remain for TXT seams, rapid navigation, font/settings changes, recreation, low memory, backup merge/replace/rollback and cross-device paths, and long-background archive image recovery.
- Producer-generated encrypted/split/solid archive combinations, native decoder behavior, PDF speed, and office/EPUB visual fidelity still require representative runtime checks. Complete archive compatibility and measured performance gains are not claimed.
