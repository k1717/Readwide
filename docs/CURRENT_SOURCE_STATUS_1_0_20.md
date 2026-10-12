# Current source status — Readwide 1.0.20

## Identity and verification

- The latest maintainer log completed signed Release APK assembly after build-setting/native warning cleanup: BUILD SUCCESSFUL, 132 actionable tasks (50 executed), with v2 signature verification. It displayed 100 deprecated-API warnings. An earlier successful log also compiled local tests and assembled the Android-test APK; neither log reports test-suite execution.
- Release date: **2026-10-11**.
- Android application ID: `com.readwide.manager`; version `1.0.20 / 10020`.
- The successful 2026-10-11 release build includes the Home edits and Java/Gradle/native-warning cleanup, but precedes the system-bar/WebView changes described below.
- Successful assembly and signature verification do not establish test-suite execution, device behavior, F-Droid acceptance, or remote publication.
- Subsequent source edits remove mixed system-bar appearance control and prefer owned scale callbacks for WebView scroll geometry. Their eight instrumentation regression methods are source additions, not executed tests; verification is static only. Initial-scale fallback, gesture zoom probes and required legacy APIs remain. TXT paging/viewport geometry and existing bookmarks are unchanged. Detailed deprecated-API/plugin diagnostics remain enabled.
- First-party code remains Apache-2.0. Component provenance and licensing are recorded in the [license report](LICENSE_REPORT_READWIDE_1_0_20.md), [FOSS status](FOSS_STATUS.md), and source/direct-dependency SBOM.
- Local archive fixture I/O uses a strict UTF-8 compatibility helper and `Paths.get`; document tests use the actual builder constructor. Their compilation is confirmed by the maintainer log, not their execution. Earlier focused host results apply only to their recorded source and fixtures.

## TXT reading and saved state

- Large-TXT reading retains exact page counting, partitioned loading and adjacent-window preparation. Validated exact-page indexes are reused after reopening, including large saved indexes.
- Sequential and automatic navigation uses actual source continuation/EOF rather than treating an estimated page count as a hard boundary.
- Page and in-page row labels, new bookmarks and saved progress use one visible-row rule: retain the first row while at least half remains visible. Existing bookmark character positions and context are not rewritten.
- Partition handoff preserves the visible text anchor. Touch/inertia, page taps, pending destinations and asynchronous layout/index callbacks have explicit ownership; stale work cannot replace a newer navigation request.
- Initial restoration retains an unmatched absolute target outside the first buffer and reloads its owning window through the complete initial-load path. Matching context remains preferred.
- Snapshot reuse checks source size/time and decoding/partition policy. Restoration waits for current-text layout; source or settings changes preserve characters/context while invalidating incompatible page/pixel hints.
- Small viewports and coordinate arithmetic are guarded. TXT positions still have representability and memory limits; this is not unlimited or fully 64-bit document addressing.
- Reading/transform allocation failures reach the owning load's error callback; this does not promise recovery from every UI/native/process-wide allocation failure.
- Search, Contents and legacy line/percentage moves target the whole document. User display rules and blank-line collapse remain part of the coordinate policy.

## Contents, settings and backup

- TXT Contents provides detected headings, manual titles, hidden-title recovery, search and per-file options. Built-in forms cover English/Korean/CJK headings, decorated numbers and arrow-delimited titles; optional RE2/J patterns are line-local.
- Heading detection is heuristic and configurable, not a semantic chapter parser. There is no fixed chapter-number cutoff.
- Optional JSON backup data includes durable Contents options/manual/hidden records, bookmarks, reading positions, annotations and supported settings. Automatic page/heading/search caches are not exported.
- Import validates Unicode, numeric types, record identities and custom expressions before publication. Failed imports include Contents in rollback; old backups without that section leave Contents unchanged.
- Restored rendering preferences retain character/context anchors and retire incompatible page hints. Missing folders/fonts use valid local fallbacks.
- Backups do not carry document, imported-font or theme files, storage grants, signing keys or arbitrary filesystem content. Path/text-bound records are not automatically relocated to new paths or document revisions.
- The backup import size guard is separate from archive extraction policy.

## Document readers and file browsing

- Document/PDF system-bar appearance no longer mixes compat control with legacy flag writes. Document scroll range/Markdown visual height prefer valid scale callbacks owned by the current WebView/load; a bounded zoom reset uses the operation's result. Initial-scale fallback and platform minimum-zoom gesture decisions remain.
- PDF uses platform rendering with improved request ownership, cache reuse and stale-result rejection during rapid navigation. No measured universal speedup or OCR is claimed.
- EPUB, Markdown and Word/HWP previews improve resource routing, CSS/layout hints, source-position restoration and scientific super/subscript presentation. They do not provide full browser, Office or Hancom rendering fidelity.
- Read-aloud follows the accepted document/page, foreground/audio-focus state and saved checkpoints. Scanned PDFs require actual text for speech/search.
- Image sessions restore retained surfaces after background return or reload the accepted page when necessary. Progress tracks displayed output, not failed/pending requests; later encrypted pages preserve password handling.
- Thumbnail demand follows visible rows and volume-aware cache identity. Metadata checks cannot detect edits preserving both size and modification time.
- Recent-file refresh uses item diffs and targeted progress/cover binding, preserving an already displayed unchanged cover during archive identity checks. Invalidated covers still clear. Home headings use 12 sp text and 38 dp minimum rows.
- Home/settings navigation, folder fallbacks and file-operation ownership reject stale callbacks. Subfolder search intentionally starts from the selected folder.
- Rename/move/copy use guarded staging and recoverable rollback where possible. They are not durable multi-file transactions across process death.

## Current archive scope

- ZIP/ZIPX/CBZ: ordinary and supplemental codecs, canonical names, ZIP64, PKZIP splits, selected-entry recovery and guarded plain image reads. PPMd-I uses an independent source-built decoder and explicit freeze-profile checks.
- RAR/CBR: native and scoped first-party stored/LZ/PPMd, solid, AES, split and encrypted-header routes, including RAR5-container algorithms 0/1. Mixed RAR3/4 logical streams and within-entry VM chains retain integrity and admission checks.
- 7z/CB7: registered one-output coders plus four-input BCJ2, AES, solid folders and split metadata/payload input. Brotli large-window, LZ5/Lizard and LZHAM are source-built integrations with defined properties and limits.
- ALZ/EGG: supported streaming codecs and split sets; ALZ metadata/password-candidate checks and EGG block-owner encryption/authentication are shared across supported extraction/viewing routes.
- TAR and standalone compressors retain format-specific completion/trailer checks, sparse logical sizes, special-member exclusion and selected-read boundaries.
- CAB linked sets and LHA LH4/Amiga byte-split routes are connected in source. Producer interoperability and Android/native runtime validation remain distinct from build success.
- Fixed extraction/preview payload caps are removed. Free-space protection, overflow/array representability, format rules and available memory remain enforced.
- Unknown codecs/properties, missing external dictionary bytes, encrypted EGG names, separate LHA multi-disc semantics and unsupported RAR VM range/order cases remain excluded.
- RAR links/junctions/file-copy references are not restored. 7z anti-items are omitted from readable content and never applied as deletions.
- Full compatibility across every encryption, split, solid and filter combination is not claimed.

See [archive support boundaries](ARCHIVE_DECODE_BOUNDARIES_1_0_20.md) for the format matrix,
[routing and identity](ARCHIVE_ROUTING_1_0_20.md) and
[extraction and stream ownership](ARCHIVE_EXTRACTION_1_0_20.md) for implementation details.

## Reading older documents

[Development changes](DEV_CHANGES_1_0_20.md) describe this release by subsystem;
[release readiness](RELEASE_READINESS_1_0_20.md) separates completed preparation from
remaining runtime checks. The [code map](CODE_MAP.md) describes ownership.

Older version notes and focused validation reports retain their historical scope.
Their former size caps, codec exclusions, failed-build checkpoints or local package
instructions do not override this release's current status. Do not add repeated
test counts across reports or treat synthetic host fixtures as producer/device evidence.
