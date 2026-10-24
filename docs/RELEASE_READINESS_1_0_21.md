# Readwide 1.0.21 release readiness

Version: `1.0.21 / 10021`; GitHub source package prepared for **2026-10-24**.
Source preparation is not APK validation or remote publication. The latest code
and added regression sources have not been compiled or executed; earlier build
results predate later changes.

## Completed source preparation

- Release documents use the existing subsystem-based families. Historical entries are retained; feature summaries, implementation details and verification status have separate homes in the [documentation index](README.md).
- Current implementation and unsupported profiles are summarized in [source status](CURRENT_SOURCE_STATUS_1_0_21.md). Detailed contracts and regression coverage are in [development changes](DEV_CHANGES_1_0_21.md), not repeated in this checklist.
- Full-source packaging excludes generated/private artifacts, refuses existing destinations and checks inventory, CRC, portable modes and source bytes without requiring digests. Source-package privacy checks are targeted scans, not a universal secret-detection guarantee.

## Before publishing an installable APK

All checks below remain pending for the latest source. Regression files are test
inputs to these checks, not recorded passing results.

### Viewers and Android behavior

- On Galaxy S20, check TXT status-bar ON/OFF with controls open/closed, Settings return and rotation. OFF must remove the extra strip without placing text under the cutout. Recheck large-TXT partition seams, normal/fast scrolling, saved positions and Contents failure/retry while preserving exact paging.
- Check EPUB typography with horizontal, RTL, fixed/image/vertical and mixed-spread books, including old/new backup merge/replacement/reset. Exercise nested Contents, fragment/right-pane/adjacent notes, malformed metadata and fallback reopening; dismissing a note must leave both panes unchanged.
- Check EPUB percentages against actual WebView metrics through blank history URLs, delayed images, zoom/reflow, slider previews and restoration. Unsupported/non-scrollable layouts must retain plain section ranges.
- Check menu colors with gesture/three-button navigation, RTL interfaces, asymmetric cutouts and side rails. Rotation and cutout-only/same-side/opposite-side combinations must not change content padding, viewport or saved positions.
- Verify actual Android 8+ renderer termination with single/shared-process panes, background return and pending parse/script/save callbacks. Only the affected reader should close, guidance should appear once and reopening should retain durable progress. Callback fixtures do not validate process death; Android 7.x has no equivalent callback.
- Exercise pending restores, recreation, DOM-only app navigation, search/read-aloud reveals and overlapping scripts. Check PDF zoom/reset after pinch/chrome changes, keyboard/TalkBack sliders and load/render retry. Test image numeric entry during rotation, keyboard/split-screen and source/sequence replacement.
- Check failure-card buttons with long messages, large fonts and short windows, and PDF spread cleanup after page open/render/close failure. Test image EOF cleanup, checked/unchecked close retry, cooperative yield, sensitive-cache reuse and unchanged last-page behavior.

### Archives and recovery

- **RAR:** cover legacy generations, excluded names/omitted history, stored plain/AES solid-flagged splits and PPMd↔LZ switches in selected/bulk/forward routes. Include table-less successors, keep-old tables, low-distance reset, stored/directory interleaving, short tails, metadata mutation and decode/flush/CRC/close failures.
- **RAR VM:** cover standard/custom same-start chains, zero/resized intermediate results, Audio/Itanium vectors, raw-history offsets, cancellation and terminal/reset rejection. Check explicit EOF and complete-result ownership for prefix carry, selected-target scope, missing successors and late/overlap rejection. Synthetic prefix carry is not producer proof and does not implement completed-result splitting.
- **7z:** cover prefixed/split encoded/additional/main streams, zero/multiple encoded-header folders, malformed/truncated/short PPMd/LZMA/LZMA2/Copy output and physical-error/cancel distinctions. Exercise solid/independent folders, skipped/unused-folder verification and stable unsupported-header fallback; retired inputs must remain terminal.
- **BCJ2:** check matching-CRC but unfinished control state, final addresses, empty output, unused CALL/JUMP compressed branches, nested Copy tails and extra decoded MAIN/CALL/JUMP/control bytes. Retain valid packed padding and lazy partial close; bulk/selected failure must roll back affected output and forward failure must expose no bytes/spool.
- **ALZ method 3:** compare representative ALZip-produced files and synthetic scalar/bulk cases across dynamic orders, fixed/stored blocks, large declared sizes, overlap/window wrap, short refills and malformed data. Include UTF-8/MS949 password probes, ZipCrypto split payloads, descriptor flags, every encryption-header cut, late source changes, entry reset and unchanged methods 0/1/2.
- **EGG framing and crypto:** check inter-file SKIP and block-header extras independently, preserving metadata-shaped legacy payload/EOF successors. Include encrypted split/solid blocks, empty terminal blocks, full-MAC candidate selection, fresh CTR/MAC state and verified-prefix retention. Injected verifier ambiguity is not a real colliding archive; encrypted-name rejection is not filename-decryption coverage.
- **Source acceptance:** resolve EGG/ALZ segments once, then mutate stripped framing before reader adoption. Exercise missing/changed active and earlier parts, growth with restored mtime, same-length spool edits with changed mtime, forward read/drain/EOF and empty files. Changed sources must retire input and restore pending targets without altering earlier verified output.
- **Cancellation and ownership:** check pause-before-validation and cancel-after-validation ordering at probes, intermediate/final folders, callbacks and trailing empty files. Exercise duplicate-target rollback, registration/wrapper/close failures and shared split-input retirement, preserving original errors and cleanup ownership.
- **Buffering and codecs:** run Deflate, standard/trimmed BZip2, LZMA and AZO preparation/refill/buffered-work cases with encrypted split/solid input. Include concatenated/headerless streams, source-error priority and tokenless controls. Test ALZ/RAR scratch/table isolation, removed EGG cumulative-CRC work and 7z forward member cursors.
- **PMarc/TAR:** validate PM0 listing/selected/bulk/forward behavior and header/CRC/size/comment rejection on Android; PM1/PM2 remain excluded. Check compressed TAR cancellation without unrelated tail drains.
- Use the [archive regression scope](DEV_CHANGES_1_0_21.md#archive-regression-scope) for source-case details and the [1.0.20 readiness baseline](RELEASE_READINESS_1_0_20.md) for retained checks. Native/device execution and representative producer-created files remain necessary; injected failures do not prove actual exhausted-heap recovery. Measure performance before claiming speed gains.

### Saved data, files and release identity

- Exercise annotation/deferred-save failure, settings reset and backup rollback followed by retry. Check duplicate bookmark bindings, changing copy/move/TXT-edit targets and unavailable storage. Verify external providers, MIME-only opening, Recent selection and read-aloud notification returns.
- Verify current source separately from the earlier successful build, including regression compilation/execution and supported Android API/ABI paths. Preserve Back/PIN/search, audio focus and transitions.
- Confirm application ID/version, notices and signing identity. Keep release debugging, cleartext and Android Auto Backup disabled, and `INTERNET` absent. The public APK name is `Readwide_1.0.21.apk`; local filenames may differ.
- Confirm the actual publication date and immutable tag/commit when publishing. The document date does not record a GitHub upload.

## F-Droid source review

- Preserve pinned corresponding sources and notices; no new third-party module is added.
- Checked-in metadata is a historical mirror through 1.0.19. A 1.0.21 submission needs current upstream metadata and the final commit; source inspection is not build acceptance or publication. See [submission notes](FDROID_SUBMISSION.md).

## Preserved boundaries

- Exact TXT paging, partition sizes/overlap, half-visible-row selection and existing bookmark positions remain unchanged, as do free-space protection, dependency pins, permissions and signing.
- Metadata checks do not lock files, rediscover added volumes, detect every same-metadata replacement or exclude final-check races. Backup import is not a cross-file power-loss journal.
- Individual blocking/native/codec/KDF calls and allocations may delay cancellation; sustained memory/storage failure may prevent cleanup. Complete format compatibility, universal speedup and bug-free runtime are not claimed.
