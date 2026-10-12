# Readwide 1.0.20

Release date: 2026-10-11.

Readwide 1.0.20 improves large-TXT reading, adds configurable TXT Contents, expands archive decoding, and strengthens viewer restoration and local backup recovery.

## TXT page counting and navigation

- Reuse validated exact-page counts when reopening large files, including saved indexes that exceeded the previous cache-file limit.
- Prepare adjacent partitions while scrolling and preserve the visible text position when joining them.
- Keep sequential/automatic page turning independent of estimated totals, and resolve line-number moves against the whole document.
- Correct overlap-only pages at the true document end. Affected exact-page caches rebuild; existing bookmark characters and context remain intact.
- Use the same visible-row rule for page/row labels, new bookmarks, and saved progress: keep the first row while at least half is visible.
- Harden file-picker restoration, background-memory recovery, pending jumps, short viewports, and reloads after source/settings changes.
- Keep an out-of-window saved position when opening large TXT and finish loading at the correct partition. Report worker-side text allocation failures instead of letting them escape the loading error handler.

## TXT Contents and reader controls

- Add searchable detected headings, manual current-position titles, hide/delete actions, rescan, and hidden-title restoration.
- Recognize English chapter headings, Korean episode/chapter headings, Chinese/Japanese numbered headings, `345===`, `345---`, and `<--Title-->`, including supported following-line titles.
- Choose heading families and detection conditions per file, or add optional RE2/J regular expressions. There is no fixed chapter-number or heading-count cutoff.
- Keep heading detection heuristic and customizable; changing a file's path or text version does not automatically transfer its saved Contents edits.
- Use rounded Contents buttons/editors, clearer spacing, compact font cards, and an EPUB font picker matching language selection.

## Settings and backup recovery

- Include durable TXT Contents options, manual titles, and hidden titles in optional JSON backup data. Older backups remain compatible; automatic caches stay excluded.
- Validate Unicode, numbers, record identities, and custom expressions before importing, and include Contents records in failed-import rollback.
- Keep reading characters/context when restored display settings invalidate old page/pixel hints.
- Preserve valid local folder fallbacks and handle unavailable fonts safely. Backups do not transfer document/font/theme files, storage permissions, or automatically relocate path-bound entries.

## Archive image viewing

- Recover the retained image after returning from another app without resetting pan/zoom, or reload the same page when its display surface was released.
- Save the last successfully displayed page, not a failed or still-loading request.
- Handle password-required/wrong-password failures on later pages and retry supported WebP/GIF decoding with smaller allocations under memory pressure.
- Reuse archive metadata and image sequences, avoid redundant prefetch, and allow independent reads for eligible plain ZIP/CBZ images.
- Validate split-volume identities for previews and cover thumbnails; changed archives require reopening.
- Keep slow reader creation, cleanup, and cover work off UI paths. The reported long-background black-screen recovery still needs device confirmation.

## PDF and document reading

- Unify document/PDF system-bar appearance handling and use current-load WebView scale callbacks for document scroll geometry, retaining initial-scale fallback and bounded zoom reset.
- Reduce avoidable PDF rendering work during rapid portrait-page navigation without adding animation. Improve incremental search/resource ownership; scanned PDFs still have no OCR.
- Preserve more DOC/DOCX/HWP structure, inherited styles, visible content, text boxes, table spacing, and page proportions.
- Improve supported scientific subscript/superscript and simple equation previews while retaining ordinary underscores and unsupported literal expressions.
- Protect Markdown code/links from conflicting inline parsing.
- Improve chapter-local EPUB style hints, CSS declaration handling, and document resource/position restoration.
- Complex office layout, Markdown, and EPUB compatibility remain partial; no measured device-speedup claim is made.

## Read aloud

- Buffer bounded text segments with stable source positions and avoid replaying TXT page overlap.
- Isolate callbacks, highlights, pause/resume, timers, and audio focus across playback changes.
- Keep source-aware checkpoints and reject incompatible old relative TTS positions without changing ordinary bookmarks.
- Background playback still depends on the Android TTS engine and system lifecycle.

## RAR compatibility

- Extend admitted first-party RAR3/RAR4 LZ/PPMd paths to encrypted, split, mixed-solid, and stored-companion cases.
- Add scoped custom VM filters and supported within-entry resizing/deferred descriptors. Unsupported cross-file and filter scheduling cases remain excluded.
- Improve RAR5/RAR6/RAR7 stream endings, empty solid entries, dictionary planning, standard filters, and checksum-resolved unknown stored sizes.
- Treat unsupported redirection records explicitly and keep integrity/cancellation failures terminal.
- Complete RAR compatibility is not claimed.

## Other archive formats

- Extend shared 7z/CB7 graph/metadata handling and admitted Zstandard, LZ4, LZ5, Lizard, Brotli, and LZHAM profiles.
- Strengthen ZIP/ZIPX AES, split-volume, concatenated-stream, and PPMd-I handling within the documented method/engine boundaries.
- Add EGG LEA/authenticated encrypted-solid handling and improve EGG/ALZ names, split discovery, and password-encoding checks.
- Add linked multi-volume CAB and admitted LHA LH4/Amiga byte-split reading; check compressed-TAR completion during full extraction.
- Remove additional fixed extraction/preview quotas while retaining free-space, integrity, overflow, and real decoder/platform limits. Unsupported profiles, encrypted EGG names, and unavailable external dictionaries remain outside coverage.
- Detailed scope: [archive support notes](ARCHIVE_BOUNDARY_SUPPORT_NOTES.md).

## File browser and local operations

- Make the Recent files and Pinned folders headings and header rows more compact.
- Avoid resetting unchanged thumbnails while refreshing recent files and reading progress; retain archive-volume identity validation.
- Restore Home/folder/search context and keep recent-file/font preparation off blocking UI paths.
- Apply returned settings consistently while retaining intended subfolder search behavior.
- Protect archive sources during replacement, reject unsafe destinations, and strengthen clipboard/rename/cancellation ownership and rollback.
- Preserve committed results and retryable failures. File operations are not durable transactions across process death.

## Privacy and dependencies

- No ads, analytics, account, cloud sync, or `INTERNET` permission. Android Auto Backup remains disabled.
- Documents are processed locally. Archive previews may create plaintext temporary files; encrypted RAR history does not encrypt every cache or extracted file.
- First-party source remains Apache License 2.0. Added RE2/J and source-built decoder components retain their respective notices; CAB/ZIPX components retain their LGPL boundaries and corresponding source.
- See the [license report](LICENSE_REPORT_READWIDE_1_0_20.md) for dependency details. F-Droid approval and publication are separate from the local release build.

## Install

- Version name: `1.0.20`
- Version code: `10020`
- Release date: `2026-10-11`
- Source license: Apache License 2.0
- Source: https://github.com/k1717/Readwide
- APK releases: https://github.com/k1717/Readwide/releases
- Public APK filename: `Readwide_1.0.20.apk`. Local build filenames may differ.
- With the same project release key, this version updates the GitHub 1.0.19 build in place.
- The local release build completed successfully on 2026-10-11. Subsequent system-bar/WebView changes have static review only. See [verification status](CURRENT_SOURCE_STATUS_1_0_20.md) and [remaining runtime checks](RELEASE_READINESS_1_0_20.md); earlier results do not establish complete compatibility or current performance.
