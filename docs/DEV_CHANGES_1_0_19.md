# Readwide 1.0.19 development changes

This note records the implementation delta from 1.0.18.

## Home and settings

- `MainHomeShortcutsController` adds a collapsible Pinned folders section above Recent, using the drawer's existing saved shortcuts. Expanding reveals the horizontal list; unavailable folders remain visible and are checked again when opened. Removal deletes only the shortcut.
- `HomeDisclosureSpan` places the disclosure triangle after the localized title and centers it with the text. The header shares the toolbar colors and toggles without animation; the separate Manage button is removed. Home section rows use 42dp minimum heights and 15sp titles, and the main toolbar uses a 48dp row with an 18sp title.
- `SettingsThemeSelectionController` applies explicit selections without repeated recreation. `SettingsCollapsibleSectionController` restores expanded sections so light/dark changes keep the theme controls open.
- `SettingsPreferenceViewState` prevents old control state from replacing reset/imported preferences. Custom-color drafts are discarded for reset/import and retained during ordinary rotation. Reset also restores text alignment.
- `SettingsButtonOrderController` keeps Reset in the dialog draft until Save. Cancel and Back preserve the saved order.
- `SettingsTextDisplayRuleController` retains file-specific targets, receives the current TXT file through View settings, reports the existing 50-rule limit, and validates regex/replacement syntax and single-line input before saving.
- `ThemeEditorActivity` retains the background image, name, colors and partial HEX input across recreation. `ThemeManager` publishes changes only after a successful write; failed saves/deletions remain available for retry.
- `LockActivity` and `LockEntryViewModel` keep PIN setup/change steps and input in memory through rotation, restart the flow after process death, and allow scrolling in short windows. Canceling setup refreshes the lock switch from the saved state; programmatic switch restoration cannot start setup or clear a PIN.

## Reader position, search and controls

- `TtsAnchorTextMath` uses KMP over non-whitespace characters and returns the original UTF-16 position. `ArchiveEntryPaths` scans leading prefixes once and shares the existing cache/image-key spelling.
- Search text segments store raw offsets in growable primitive arrays. Markdown anchor updates reuse one pending 40 ms task per page, calculate source-line fallbacks only when needed and reject old load/view results.
- `DocumentSearchController` limits HTML entity-terminator scanning to the existing 12-character acceptance window, retaining decoded text and original-offset maps.
- `DocumentPageActivity` preserves EPUB reading anchors across rotation, font, theme, margin and search-cleanup reloads. Page-load and interaction generations prevent delayed work from replacing newer navigation, including repeated settings changes and initial bookmark restoration.
- Vertical Japanese EPUB bookmark previews retain opening characters in the selected visible column. Search reveals horizontal columns correctly, and removing search wrappers preserves the original text offsets used by bookmarks.
- EPUB links to a visible landscape chapter target the correct pane. Links without fragments move to that chapter's logical start. Double-tapping an enlarged pane resets that pane without turning a page.
- `DocumentTtsHighlightController` carries the spoken page and text position so repeated sentences highlight the correct occurrence. Highlight following handles horizontal columns and shifted viewports; ambiguous text clears the highlight while narration continues.
- `PdfPageView` retains unrestricted fit-size tap paging, resets enlarged pages on double tap, and supports outward page swipes from an enlarged page's edge. Search progress for an unchanged match respects manual panning.
- `PdfReaderActivity` captures the page at the top of the padded continuous viewport instead of the next nearby page. Anchors preserve page gaps and that page's horizontal pan; older anchors remain readable.
- The image reader resets zoom on a double tap in side zones without also changing images. Asynchronous image refreshes leave an active page-slider drag intact until release.
- TXT reloads use the current reading context and discard obsolete launch-bookmark anchors, including at file boundaries.
- `ViewerWindowPreferences` applies Keep Screen On when document, PDF and image readers resume. TXT resume also refreshes the brightness override after settings changes, reset or import.
- `DocumentPageLoadController` rechecks the document generation when a queued load error reaches the UI, matching its existing success guard.
- `TtsPlaybackService` delegates hardware key sequences to Android MediaSession and sends distinct Play/Pause commands. `ReaderTtsController` tracks deferred resumes separately from engine initialization, makes Play/Pause idempotent and cancels deferred starts through the shared pause path. Warm-engine starts consume their pending flag.
- Explicit document-search navigation bypasses the gesture page-turn lock so rapid result changes cannot leave the displayed chapter behind.

## PDF rendering and progress

- `PdfTextSearchEngine.matchesOnPage` uses a binary lower bound over the existing page-ordered results, then collects only that page's rectangles under the same lock.
- `PdfContinuousRenderQueue` keeps one outstanding render and prioritizes the current visible pages, followed by neighboring pages. Obsolete offscreen or geometry requests are retired before rendering where possible.
- `PdfContinuousPageAdapter` retains intended display dimensions through bitmap caps and reduced-size retries. Its cache budget allows for three pages, and shared display references prevent recycling a bitmap still used by another row.
- `PdfPrefetchQueue` schedules one speculative render at a time and fills four valid neighboring slots, including at document edges. A requested active prefetch can supply the visible page; failures fall back to the normal render path.
- `PdfSharpPatchPlan` skips unnecessary sharpening and reuses patches only when coverage and pixel density are sufficient. Superseded spread work stops between source pages; running native renders remain non-interruptible.
- `BookmarkManager` and `CoalescingSnapshotWriter` move rapid-turn history serialization and writes to an application-owned worker. Revision ordering prevents older snapshots from overwriting newer saves, imports or deletions. Pause/close checkpoints remain synchronous; process termination before a deferred commit can lose the latest progress.
- PDF slider status uses the dragged target during rendering, without changing the committed reading page.

## TXT rules, search and file operations

- `FileAdapter` resolves changed selections in one row pass while preserving notification order and the first matching row. Sorting calls `FileSortUtils.sortMainItems` with cached metadata, removing the file-list round trip and repeated filesystem reads.
- `TextDisplayRuleManager` returns owned rule snapshots, invalidates them after preference changes, and reuses compiled patterns during reads and searches. Replacement validation and original-text matching avoid invalid references and Unicode case-conversion offset errors.
- `TextMatchIndex`, `MatchOffsetIndex` and `ReaderSearchController` count and navigate normal TXT matches on a bounded worker queue. Sparse offsets or a ranked bitmap use the existing 800,000-byte retained-offset budget; larger result sets remain available through a worker scan.
- `SearchMatcher` reuses a query-owned literal search table and preserves regex count/navigation ordering. Previous-result navigation wraps correctly before the first position, including in large TXT. Regex cancellation remains cooperative.
- `FileTreeWalk` replaces recursive traversal with an iterative, cancellable walk. Deletion removes links themselves; staged copy rejects links and special nodes. `FileTreeProgressTracker` shares one inventory for totals, and eligible moves try rename before copying.
- `NaturalSort` handles decimal digits from different scripts consistently. `FileSortUtils` prepares sort keys once and uses iterator access while retaining stable ties and archive chapter ordering.
- Folder copies reuse one buffer, completed operations retain their bookkeeping after cancellation, and progress dialogs coalesce updates through `LatestValueDispatcher`. Folder search remains recursive from the selected starting folder.
- `ReaderFileApplyController` and `ReaderLoadedTextSnapshotController` reject layout callbacks from an older load. TXT progress, memory snapshots and background trims wait for the current layout restore to finish, including initial large-file partition handoffs.

## Backup and persistence

- `PreferenceBackupSchema` checks recognized preference types after JSON numeric conversion, even when a key has never been saved. Unknown keys retain the existing compatibility rules.
- `BackupImportData` validates supplied sections before mutation. `IndexedBackupMerge` preserves ID replacement, location deduplication and stable order without repeated full-list scans. Legacy bookmark-only backups remain accepted; PIN/lock settings and device encoding caches stay excluded.
- `BackupImportTransaction` uses checked writes and reverse rollback on reported failures, including a partially applied step. Preference imports invalidate TXT rule caches, and reading-state revisions prevent older deferred PDF saves from overwriting imported data.
- `AtomicUtf8File` attempts recovery when only the platform backup remains. Export reports serialization and destination open/write/close failures instead of announcing success.
- `SettingsReaderControlsController` ignores initial/duplicate dropdown selection callbacks and avoids redundant preference writes after a refresh.
- `SettingsBackupViewModel` runs backup work outside the UI thread and retains confirmation/results through rotation. A started commit can finish after Settings closes. Per-file writes and rollback do not provide a cross-file power-loss journal.

## Archive decoding and extraction

- `ArchiveEntryListController.visibleChildren` gathers direct children without sorting, then filters and performs one stable sort. The standalone sorted-child API and preview image sequence keep their contracts.
- `Rar3DecodePlan` and `Rar3FirstPartyArchiveExtractor.PlannedDecoder` share entry classification, primer dependencies and ordered execution across extraction and forward reading. Independent classic-LZ and stored files can coexist with checked solid runs; stored files break compressed history, while directories preserve it.
- `Rar3CheckedForwardReader` retains solid state and verifies requested entries before exposing bytes. Skipped compressed entries verify to discard. Native opening remains preferred; the fallback stays limited to eligible plain, single-volume RAR4 runs.
- `SevenZCoderRegistry` and `SevenZDecodePlan` centralize codec capabilities and validate a topological decode plan before payload I/O. LZMA/LZMA2 history allocation is bounded by known output size as well as the declared dictionary and decoder limits.
- Supplemental 7z routing uses bundled xz-java ARM64/RISC-V filters and retains special-coder identity from encoded headers. Empty-file and zero-byte-folder extraction now follows the same output and integrity checks.
- Commons ZIP and supplemental ZIPX writers check decoded size/CRC and retain output guards through authentication and decoder close. ALZ enforces exact decoded length. RAR, special-7z and solid-EGG forward readers reject damaged spool boundaries and retire failed sessions with retriable cleanup.
- Java TAR paths reject bad member-header checksums, skip links/special members, and guard streaming destinations. GZIP, BZip2, XZ and framed-LZ4 decode concatenated compression members, including a TAR stream spanning members.
- `ArchiveSupport` retains the original backup and reports its location if overwrite recovery fails. Cleanup removes only work directories owned by that extraction. This protects affected destinations; it does not make whole-archive extraction transactional.
- Corrected unreachable cleanup catches in `EggArchiveReader` and `SevenZBcj2ArchiveReader`.
- Existing exclusions remain: encrypted/split mixed RAR runs, stored members inside solid runs, custom RAR VM code and unsupported filters; unsupported 7z graphs; EGG LEA/encrypted-solid input. Broader sparse/compressed-TAR indexing and complete all-volume preview-cache identity remain unfinished.

## Resources and dependencies

- Version metadata is `1.0.19` / `10019`; application ID, permissions, SDK levels, build tooling and signing configuration are unchanged.
- Updated AppCompat to 1.8.0, ConstraintLayout to 2.2.2, SwipeRefreshLayout to 1.2.0, hwplib to 1.1.11 and test-only zstd-jni to 1.5.7-17. PDFBox Android and runtime archive dependency pins are retained.
- Source-built libarchive 3.8.9 and ZIPX JPEG/WavPack modules retain their provenance and notices. Dependency versions and notices are listed in [the license report](LICENSE_REPORT_READWIDE_1_0_19.md).

## Release files

- Updated the changelog, patch notes, GitHub release notes, Fastlane changelogs, license report and source dependency SBOM for `1.0.19` / `10019`.
- The public package includes app and native source, Gradle build files, tests and license notices.
- Build and publication commands are in [RELEASE_BUILD.md](../RELEASE_BUILD.md).
