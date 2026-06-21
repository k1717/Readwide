# Patch Notes

## Readwide 1.0.7 - 2026-06-20

### Release scope

- Android metadata is `versionCode 10007` and `versionName "1.0.7"`.
- Keeps the `com.readwide.manager` applicationId and the `readwide` release signing key introduced in 1.0.6, so 1.0.7 installs in place over 1.0.6 as a normal update. Updating from 1.0.4/1.0.5 (previous key) still requires uninstalling the old version first, then migrating via the in-app JSON backup export/import.
- This release centers on an optional blank-line collapsing display setting, more reliable text reading-position restore, improved large-file bookmark page accuracy, recent-list scroll preservation, refined find-in-page behavior when its options change, and a find-in-page crash fix.

### Reading — collapse repeated blank lines

- Added an optional **Collapse repeated blank lines** display setting for the text reader (Display settings, under the large-TXT options). When enabled, any run of two or more consecutive blank lines is shown as a single blank line; a lone blank line is left as-is and the original file is never modified. It applies to all text files the reader opens (TXT, log, CSV, and similar), both small and large, treats whitespace-only lines as blank, and is applied consistently to the page model, large-file partition/exact-page index, and in-text search so page numbers, bookmarks, and search positions stay aligned. Toggling it reloads the open file, and the collapse state is folded into the page-layout signature so the page model is recomputed instead of reusing a stale one. Bookmarks from before this version stay compatible while the option is off. Default off.

### Reading — position restore

- Reopening a text file restores the reading position more reliably. The saved position now carries short before/after text anchors and a page-layout signature, so the reader re-finds the original spot even when the page layout would otherwise differ (for example after a display-setting change), instead of falling back to an approximate page. The restored position also stays correct after the system recreates the reader and when scrolling back through a large file.
- If a text file changed on disk since it was last opened, reopening it reloads the current contents instead of restoring the earlier cached view and position.
- Large-file bookmark jumps now prefer surrounding-text anchors when resolving the destination, improving landing accuracy after a layout or display change (for example a different font size or margin).

### Settings and display rules

- Settings are reorganized into two screens. Display and reading-layout options (theme, reading theme, text layout, EPUB layout) now live in a dedicated **Display settings** screen reached from Settings, while general app settings (behavior, button order, security, backup) stay on the main Settings screen. This keeps display options together and out of the general list; the large-TXT options, including Collapse repeated blank lines, are in the Display settings screen.
- **Edit actual TXT file**: enabled TXT display rules can now be permanently applied to the current text file from the text reader's **More** menu. You choose between fixing the original file in place or writing a separate `_edited` copy, and the flow keeps the rule-order, overwrite, and large-file warnings followed by a final confirmation. Display-only rules still never modify the file; this is the explicit opt-in that writes changes. It moved here from the TXT layout settings so it always runs with the currently open file in context.

### Files — recent list

- Returning to the app after opening a file from the recent list no longer forces the list back to the top. The list keeps its scroll position near the row the file was opened from.

### Fixes

- Fixed a crash that could occur when an invalid regular expression was the active find-in-page query; an invalid pattern is now treated as no match instead of failing during drawing.
- Changing the find-in-page options (case-sensitive, whole-word, or regular expression) now restarts the search under the new options, so the next match is found with the new settings instead of continuing from the previous result.
- When the system recreates the reader from memory (for example under memory pressure), large-file exact page numbering is rebuilt for the current layout instead of remaining on the initial estimate.

## Readwide 1.0.6 - 2026-06-19

### Release scope

- Android metadata is `versionCode 10006` and `versionName "1.0.6"`.
- Keeps the `com.readwide.manager` applicationId from 1.0.4 but changes the release signing key, so 1.0.6 does not install over an existing 1.0.4/1.0.5 (which used the previous key); uninstall the old version first, then migrate via the in-app JSON backup export/import.
- This release centers on file-list performance, reading-progress for image archives, per-type file icons, folder auto-refresh, image-viewer paging smoothness, text-to-speech refinements, and several browsing fixes.

### Final changes included in this release

**Performance — file listing and sorting**

- Folder listing and sorting no longer issue a MediaStore (ContentResolver) query per file or per scrolled row. Date sorting previously queried MediaStore for each image/video, and each visible row queried again while scrolling, which was slow in large media folders.
- Folders now sort and display immediately using filesystem timestamps (the newer of last-modified and creation time). When a date sort is active, a background pass batches MediaStore date lookups and re-sorts only if the order actually changes; folder loads and non-date sorts do no MediaStore work at all.
- Large folders such as Downloads open noticeably faster as a result, with the same set of files shown.

**Performance — file search**

- Removed the 5,000-item cap on file search. The search walk now streams results into the list incrementally (only the new items per step), then applies the final sorted order when the walk finishes, so very large result sets are no longer truncated.

**Performance — image and PDF caches**

- The image viewer now prefetches three pages in each direction instead of two, and its decoded-bitmap cache budget was raised (up to 128 MB, scaled to a fraction of app heap). Rapid continuous paging is less likely to outrun the prefetch window and stall on a decode.
- The PDF viewer's single-page and continuous-mode bitmap caches were enlarged (cap raised to 96 MB each, still scaled to app heap), for smoother paging and scrolling on large documents.

**Reading progress — image archives**

- Image archives opened in the comic/image viewer (and folder image sequences) now record a reading position and show a progress percent in the recent list, the same as PDF and EPUB. Progress is keyed on the archive so the archive's recent entry gets the badge.
- Progress is saved with a short debounce as you turn pages (so fast paging does not write to disk every page) and saved immediately when you leave the viewer.

**Files — per-type icons**

- File rows now show a Material icon chosen by type: PDF, EPUB, document (Word/HWP), archive, image, video, audio, app package (APK), or a generic file icon as the fallback. Icons are tinted with the current theme color. Folder icons are unchanged.

**Files — long-name display**

- Long file names now keep their extension and the end of the name visible. When a name does not fit, it is shortened in the middle as "start…<tail><extension>" so the file type and trailing context (for example a resolution or part number) remain readable across two lines. Names that fit are shown in full.
- The truncation is computed during layout so the final text appears in one pass, without the brief flash that a deferred rewrite produced when re-entering the recent list.

**Files — list layout**

- Reworked the recent/file list row: two-line file names, slightly smaller and tighter secondary text (type, size, date), and more consistent row spacing.

**Files — search result location**

- Search results now display each file's location relative to the searched folder instead of the full absolute path that repeated the search root on every row. A file directly in the searched folder shows no location line at all; a file in a subfolder shows ".../subfolder" so it reads as a path below the searched folder rather than a bare folder name.
- An all-storage search (which spans multiple roots such as internal storage, Downloads, and SD cards) keeps the matched storage's folder name as a prefix, so results from different storages remain distinguishable. The deepest matching root is used when roots are nested.

**Files — image formats**

- The image filter chip and the image viewer now also recognize `.jfif` (a JPEG container), `.wbmp`, and `.dng`. These were chosen because Android's bitmap decoder can render them, so they both appear under the Image filter and open in the viewer (including when found inside an archive). Formats Android cannot decode by default (such as TIFF and ICO) were intentionally not added.

**Files — recent list**

- The recent list now shows up to 300 recently opened files, so more of your reading history stays visible on the home screen. Entries whose underlying file no longer exists are skipped.

**Files — search and filter loading**

- While a search or file-type filter runs, the file list shows a single loading spinner. The spinner is new in this release; earlier versions showed a "loading" text label, and the spinner now takes its place. The empty-state message appears only once a finished search returns no results.

**Folders — refresh**

- The visible folder is re-read when the app regains window focus, picking up downloads and other external changes that the filesystem watcher can miss on FUSE/MediaStore-routed storage. The re-read keeps scroll position and only happens when the folder's on-disk signature actually changed.
- Pull down on the file list to refresh the current folder (or re-run the active search) manually.

**Text-to-speech — resume on reopen**

- When a read-aloud session is interrupted — leaving the app, the process being killed in the background, or pausing and navigating away — reopening Readwide shows a prompt on the main screen offering to resume that book. Continuing reopens the file at its saved position, restores the sleep-timer value that was active when playback stopped, and starts reading automatically. "Later" (or dismissing the prompt) clears the saved session, and finishing a book to the end also clears it so it does not prompt again.
- Resume is page-level: reopening restores the saved reading page and TTS begins from there. Because Android's speech engine has no true mid-sentence pause and the exact paused sentence is not persisted across process death, playback resumes from the start of that page rather than mid-sentence.

**Text-to-speech — speech cleanup**

- Runs of punctuation are no longer read aloud. Ellipses, sequences of two or more periods, and underscores are collapsed to a pause instead of being spoken, and a semicolon is spoken as a short, comma-like pause. Single sentence-ending periods are unaffected.

**Security & stability**

- External open path: files handed to Readwide through `ACTION_VIEW`/`BROWSABLE` (from a browser, messenger, file manager, or document provider) are copied into an app-private `opened_files` cache before rendering. The copy sanitizes the provider-supplied display name, verifies the cached path stays inside the cache directory, keeps each source URI in its own subdirectory, enforces a 2 GB per-file copy limit, and prunes the cache before and after the copy (preserving the just-opened file). A failed, aborted, or over-limit copy deletes its partial file, and provider `query`/`getType` exceptions are caught so a misbehaving provider cannot crash the open.
- Backup/settings import caps the JSON read at 256 MB and rejects (rather than truncates) anything larger.
- App lock: launching the lock screen now returns before the home/recent UI is built, so it is not prepared or briefly shown behind the lock; the main UI is set up after a successful unlock.
- Search and current-folder filtering build their result rows in a generation-aware, cancellable pass and stop promptly when a newer search or folder change supersedes them.
- Document text entries (EPUB/Word/HWPX), in-document resources, and EPUB chapters each have a read-size cap (32 MB / 64 MB / 32 MB) so a crafted file cannot exhaust memory during rendering.

**Fixes**

- Starting a folder navigation while a file search was still running no longer leaves a stale search screen, stutter, or brief freeze. Navigation now cancels the in-progress search walk cleanly so it stops consuming the search thread.
- Tapping the navigation drawer's Recent shortcut while a large folder was open no longer delays the drawer from closing. Entering the recent/home view from the drawer now uses the fast state-save path instead of a synchronous folder rescan that could stall the close animation.
- In fixed-layout EPUBs (including image-based books and comics), a double-tap on the left or right side now turns the page the same way a single side tap does, instead of zooming. Double-tapping the center still zooms, and reflowable EPUBs and other documents are unchanged.

**Internal**

- Background work executors for file search, folder operations, and document loading are guarded so late tasks submitted around viewer teardown do not throw.

## Readwide 1.0.5 - 2026-06-17

### Release scope

- Android metadata is `versionCode 10005` and `versionName "1.0.5"`.
- Keeps the `com.readwide.manager` applicationId from 1.0.4, so 1.0.5 installs as an in-place update over 1.0.4 when signed with the same key.
- This release centers on a text-to-speech overhaul, a folder-aware multi-select delete confirmation, two PDF mode-switch crash fixes, a text-rule performance fix, and internal code cleanup.

### Final changes included in this release

**Text-to-speech — sleep timer**

- Added a sleep timer with Off / 15 / 30 / 45 / 60-minute presets and a custom-minutes entry (0–600). An optional "finish the current sentence" setting lets playback complete the sentence in progress before stopping.
- The timer measures playback time only: time spent paused does not count toward it. When it expires, playback stops.
- The timer can be changed mid-playback and takes effect immediately. The setting is shown as "Timer" in the UI.

**Text-to-speech — pause, resume, and session**

- Added real pause and resume. Pausing stops speech but keeps the session active and remembers the current sentence; resuming continues from that sentence rather than restarting from a saved character position. Because the Android speech engine cannot pause mid-sentence, resume replays from the start of the interrupted sentence.
- A paused session now persists its position when you leave the app, so playback can resume where it left off on return.

**Text-to-speech — floating control card**

- Added a floating control card over the text reader (TXT) with play/pause and stop buttons. It appears while TTS is active, reflects the paused state on its play/pause button, and can be dragged anywhere on screen; a tap on either button is distinguished from a drag by the touch slop.
- The card complements, and stays in sync with, the existing foreground-service notification, lock-screen controls, and Bluetooth/media-button controls.

**Text-to-speech — audio policy**

- TTS keeps reading when you scroll or move within the page; manual navigation no longer stops playback. As a result the reading position and the visible position can diverge.
- Audio focus is now requested for speech playback. A transient focus loss (for example a phone call) pauses playback and auto-resumes when focus returns; a permanent loss (another app taking over media) stops playback.
- Unplugging headphones (audio becoming noisy) now pauses playback instead of stopping it, so it can be resumed after reconnecting.

**Text-to-speech — translations**

- All new text-to-speech strings (sleep-timer presets and labels, pause/resume, and the folder-warning message) were translated across the bundled locales: Arabic, German, Greek, Spanish, French, Hindi, Indonesian, Italian, Japanese, Korean, Dutch, Polish, Portuguese, Russian, Swedish, Thai, Turkish, Ukrainian, Vietnamese, Simplified Chinese, and Traditional Chinese, with English as the fallback.

**Files**

- The multi-select delete confirmation now appends a warning when the selection includes one or more folders, stating that all of their contents will be deleted too. The warning is shown only when at least one folder is selected. All real file and folder deletions in the app continue to pass through a confirmation dialog; the single-file action-sheet delete and the image-viewer delete are unchanged.

**Fixes**

- Fixed a PDF crash when switching from a zoomed-in vertical (continuous) view into horizontal (single-page) mode. The single-page render reused the carried-over zoom factor and could allocate an oversized bitmap; single-page mode now resets to fit and evicts the stale single-page cache on entry.
- Fixed a second PDF crash when switching display modes, where the matrix page view could draw a bitmap that the activity had already recycled. The view now detaches its bitmap references before any recycle and skips drawing a null or recycled bitmap.
- Fixed text-to-speech sentence segmentation for short pages. A page shorter than the internal segment size was returned as a single segment, so pause/resume rewound to the start of the page; segmentation now always splits by sentence terminator, so resume continues from the correct sentence.
- Made the navigation drawer's Recent shortcut respond immediately. Building the recent list scanned saved reading states on the main thread (checking file existence and cleaning up stale or image-only entries) before the list appeared; the scan and cleanup now run on a background thread.

**Performance**

- Text display rules compile their regular expressions once per file load instead of once per line. When a large text file is read line by line with active regex rules, each rule's pattern was recompiled for every line; rules are now pre-compiled and reused, so compilation scales with the rule count rather than the line count. Invalid user regexes are skipped at compile time, matching the previous fail-safe behavior, and files with no rules or only literal rules are unaffected.

**Internal**

- Removed dead code (an unused stop-on-navigation path left over after TTS stopped halting on scroll) and an unused, fully duplicated color utility class.
- Consolidated duplicated helpers into shared utilities: CSS quoting/coloring, same-or-child path checks, and an empty-to-null string helper used by the document render builders.
- Collapsed a redundant tap-zone action overload into a single method.
- Split the text-to-speech controller's sleep-timer dialog and its stateless dialog view builders into their own classes, reducing the controller's size without changing behavior.

## Readwide 1.0.4 - 2026-06-16

### Release scope

- Android metadata is `versionCode 10004` and `versionName "1.0.4"`.
- Readwide moves to a new Android applicationId, `com.readwide.manager`, completing the rename away from the earlier TextView Reader package identity.
- Because the application ID changed, 1.0.4 is installed as a separate app rather than an in-place update over older TextView Reader/Readwide builds.
- This release keeps the 1.0.3 document-fidelity cycle and focuses on package identity cleanup, reader-search consistency, document-viewer search behavior, and translated UI clipping fixes.

### Final changes included in this release

**Package identity**

- Renamed the Android applicationId and source package from `com.textview.reader` to `com.readwide.manager`, including all package declarations, the FileProvider authority (derived from `${applicationId}`), layout custom-view references, ProGuard keep rules, fixture report scripts, F-Droid metadata, and release materials.
- Existing users are not auto-updated to 1.0.4 because the package identity differs; bookmarks, reading positions, themes, and settings transfer through the in-app JSON backup export/import, which is independent of package name and signing key.

**PDF viewer**

- Fixed single-page PDF fit when toggling the PDF toolbar on tablets. The visible toolbar now reserves its full overlay height before fitting the page, stale pre-reserve renders are cancelled, and the cache key includes viewport height so returning from toolbar-off mode cannot leave an oversized page under the toolbar. Toolbar-off mode releases the reserve and immediately refits the current bitmap into the larger viewport before the sharper rerender completes.
- Made double-tap zoom smoother in vertical (continuous) mode. Zooming has to re-render every visible page at the new scale on the one render thread; while zoomed, each page now renders at a smaller pixel cap (about half the work), reducing the stutter.
- Made horizontal (single-page) prefetch buffer equally forward and backward (two pages each way), so flipping back is as fast as flipping forward. The page cache holds several full pages (each ~13MB at fit size), and prefetch runs on a second independent PDF renderer so it fills without blocking the page you're reading. This brings rapid horizontal paging much closer to vertical mode, which stays fast because it pages by scrolling over pages it already rendered.
- Improved rapid tapping in horizontal (single-page) mode by prerendering further ahead in the direction you're paging (two ahead, one behind) and kicking it off almost immediately rather than only after you pause.
- Fixed the PDF viewer dropping back to the main screen when you double-tap to zoom. Zoomed pages make large bitmaps, and caching several of them could run the app out of memory, causing the system to quietly close the viewer. Prefetch is now skipped while zoomed, cached neighbor pages are freed when you zoom in, and zoomed pages render at a smaller pixel cap, so zooming no longer exhausts memory.
- Made PDF pages render about twice as fast by lowering the supersample factor from 2.0 to 1.4. Pages are still rendered above screen resolution and scaled down for display, so text stays sharp, but roughly half as many pixels are rendered. Both regular page turns and neighbor prerender benefit.
- Fixed the lag at the start of rapid tapping in horizontal (single-page) mode. Every tap queued a full render on the one render thread, and pages you'd already tapped past were rendered to completion before the page you stopped on, so the first few taps felt stuck until the queue drained. Renders for pages you've moved past are now dropped before rendering, so the page you land on shows right away. Neighbor prerender now also kicks in during short gaps between taps instead of only after you stop.
- Fixed the page number flickering between nearby pages on rapid taps in vertical (continuous) mode before landing on the target. Page turns scheduled delayed settle timers that weren't cancelled, so quick taps left several stale timers that fired in sequence and momentarily reverted the page. Pending scroll/settle callbacks are now cancelled before the next is scheduled, so only the most recent target is applied.
- Fixed the page number showing the previous page after dragging the page slider a long distance in continuous mode. The target page is re-asserted after the scroll settles so a late scroll callback can't revert the counter to an intermediate page.
- Fixed pressing a toolbar icon hiding the toolbar while its popup stayed open. A tap on a visible toolbar or app-bar no longer also toggles the viewer chrome beneath it.
- Sharpened PDF text by rendering pages at a higher-than-screen resolution (supersampling) and downscaling for display in both single-page and continuous modes. The page keeps its aspect ratio and is never stretched, including when the toolbar is hidden. The existing per-page pixel cap still limits memory, so very large pages are scaled down automatically.
- Fixed the PDF viewer placing the page behind the bottom toolbar. With the toolbar visible, the page viewport now reserves the toolbar's height at the bottom so a full page is shown between the top title area and the toolbar; the reserved space is released when the toolbar is hidden. Works in single-page and continuous modes and on initial load.
- Fixed the PDF page being partly hidden under the 3-button navigation bar in landscape; the page area is inset from the side nav bar and the fit-to-width calc accounts for it, so the page fits the visible area when the toolbar is shown.

**EPUB viewer**

- Fixed fixed-layout EPUBs (pages with a declared fixed pixel size such as 1366×768) not fitting the screen. The viewer now lets the WebView scale the declared viewport to fit the screen width and only removes page margins / matches the page width to prevent sideways scroll; it no longer forces a fixed body height or centering, which had pushed full-page images to the top. The book's own layout is kept.

**Image viewer**

- Removed the per-tap latency on image page turns. A page-turn tap in a side zone now fires as soon as the finger lifts rather than waiting for the gesture detector to rule out a double tap (about 300 ms). Before, a quick second or third tap was absorbed into a double-tap sequence and delayed, so fast tapping felt sluggish; each tap now registers right away. Horizontal swipes and double-tap-to-zoom still behave as before.
- Sped up image page turns, especially under rapid repeated taps. Decoded preview bitmaps are cached (with a memory budget), and neighboring pages (two each direction) are pre-decoded on a small parallel pool that runs alongside archive extraction rather than behind it, so a turn to a prepared page is shown immediately. The cache owns its bitmaps and recycles them only on eviction, so a page that is on screen or cached is never recycled while still in use.
- Raised the preview decode budget from 12 to 16 megapixels so higher-resolution images display at full detail before any downsampling. Larger images are still downsampled to fit the screen and memory, and the out-of-memory fallback is unchanged.

**File list and sorting**

- Sped up file-list sorting and refresh by extracting each file's sort attributes (name, directory flag, and the active date/size/type key) one time before sorting, instead of calling `File.getName()`/`isDirectory()`/`length()` repeatedly inside the comparator where they ran about n·log n times and hit the filesystem. The resulting order is identical.
- Cut redundant filesystem calls during folder listing, which is the path used when entering a folder or a folder shortcut such as Downloads. Each entry's name and directory flag are read once per item rather than repeatedly, so the initial load of large folders is faster.

**TXT find-in-page**

- Fixed TXT find-in-page being extremely slow for common words in some files. The matcher now prepares the comparison view once per search and reuses it, and the large-file engine scans each line in a single pass, keeping returned offsets aligned to the original text for bookmarks and page anchors.
- Improved TXT find-in-page with case-sensitive, whole-word, and regular-expression options. Unicode normalization is always applied, overlapping literal occurrences are counted correctly, and the in-memory and large-file search paths share the same matcher and option semantics.
- Improved TXT search reveal near the end of a file. Search jumps now use a search-only virtual bottom scroll allowance so a match in the final lines can be pulled above the search dialog without changing normal paging, manual scrolling, bookmarks, or saved-position restore.

**Document-viewer search (Markdown, EPUB, HWP/HWPX, Word)**

- Reworked Markdown, EPUB, HWP/HWPX, and Word-family document-viewer search to use the same TXT-style search options and match counter instead of relying on WebView native find. The document search dialog supports previous/next movement, nth-match jumps, current/total status, case-sensitive mode, whole-word mode, and regex mode.
- Fixed document-viewer search result visibility. Current matches now use explicit highlighted spans and popup-safe reveal logic; the selected result is placed near the upper safe area, with top/bottom document spacer handling so matches near the beginning or end of a rendered document can still be moved into view above the bottom search dialog.
- Reduced same-page Markdown search bounce by updating the current highlighted match inside the existing DOM when possible instead of reloading the whole page for every previous/next movement.
- Strengthened EPUB/HWP/Word search highlight styling so reader/theme CSS does not erase the yellow/current-result highlight.

**Toolbar and dialogs**

- Fixed content and toolbars overlapping the Android 3-button navigation bar in landscape (the nav bar moves to a screen side). Left/right insets are now applied on the main screen, settings, and the TXT, document, and PDF viewers, so nothing slides under the side nav bar.

- Added a screen-rotation (portrait/landscape) button to the TXT, document (Markdown/EPUB/Word/HWP/HWPX), and PDF viewer toolbars (the image viewer already had one), using the same icon as the image viewer. The icon now matches the current screen orientation — landscape icon in landscape, portrait icon in portrait — and updates when you tap it or rotate the device. It flips the screen orientation and is independent of any page-slide direction. In TXT it sits at the far right of the buttons (right of Text encoding); in the document and PDF viewers it sits next to a new Settings button, just left of the pinned More. All can be rearranged in the button-order settings.
- Added a Settings button to the document and PDF viewer toolbars (right of the rotation button); Settings is still also in those viewers' More dialog.
- Made the PDF and document bottom toolbars scroll like the TXT viewer's: buttons sit in a horizontally scrollable row with More pinned at the far right. They now use the same toolbar controller as TXT, so button widths are balanced evenly and the row snaps to the nearest button when you stop scrolling (and re-balances on rotation). The rotation and settings buttons are also tinted with the active theme color like the rest.
- Fixed tap-to-turn paging triggering when the visible bottom toolbar was tapped. Because the toolbar floats over the full-screen view, a tap on it also landed in the page-turn zone beneath it. A tap on a shown chrome bar now just toggles/keeps the toolbar; with the toolbar hidden the whole view pages as before. Applies to the TXT, document (Markdown/EPUB/Word/HWP/HWPX), and PDF viewers.

- Fixed reader toolbar buttons running their action multiple times when tapped repeatedly, which could open duplicate dialogs or trigger repeated loading. Toolbar taps are now debounced, and only one positioned reader dialog is shown at a time.
- Fixed the settings "Button / icon order" rows (main filter, TXT, EPUB/Word, PDF) being vertically clipped under longer translations such as German. The rows changed from a fixed `48dp` height to `wrap_content` with a `48dp` minimum height and vertical padding, so longer labels wrap instead of being cut off.
- Applied the same wrapping fix to the sort dialog's options and to the TXT search dialog's option/action rows, whose fixed-height controls could clip longer translations.

**Manifest and distribution**

- Removed the dead `android:requestLegacyExternalStorage="true"` manifest flag, which had no effect under `targetSdk 35` and added an unnecessary legacy-storage signal for static scanners. File access behavior is unchanged.
- Public GitHub/F-Droid materials were updated for the 1.0.4 package, including the renamed F-Droid metadata file, Fastlane changelogs, and the package/version references in the release and submission docs.

## Readwide 1.0.3 - 2026-06-14

### Release scope

- Readwide 1.0.3 keeps the same Android package identity for update compatibility with earlier compatible builds when signed with the same key.
- Android metadata is `versionCode 10003` and `versionName "1.0.3"`.
- This release starts the document viewer fidelity cycle for DOCX, HWPX, and HWP, targeting L3 content-fidelity HTML preview: document structure, inline styling, tables, and images where verified.
- Exact MS Word/Hancom pagination, exact font metrics, editing/saving, and complete floating-object placement are explicit non-goals.

### Final changes included in this release

- A shared rendered-document model (page containers, paragraph/run styles, tables, images, text anchors, unsupported placeholders) now backs the document viewer, with fallback to the previous semantic HTML path when conversion fails.
- DOCX now bridges paragraphs, run/character styles with `styles.xml` inheritance, `numbering.xml` ordered/bullet lists, basic tables (width, column proportions, vertical merges, border colors, shading), inline images with extent hints, footnotes/endnotes, and headers/footers into the rendered model.
- DOCX Symbol/Wingdings bullets are normalized to standard Unicode markers, and list paragraphs no longer double-apply Word hanging indents.
- DOCX rendered tables clamp cell overflow and wrap by word so narrow phone-width columns no longer draw text over neighboring cells, while long first-column labels stay readable without introducing horizontal scrolling.
- DOCX/Word lecture-note math now renders inline and conservative `$$...$$` display fragments to HTML+CSS without WebView JavaScript, including fractions, square roots, superscripts/subscripts, Greek letters, and symbols, and including expressions split across runs by spell/grammar markers. Lone currency amounts such as `$200` stay as plain text.
- HWP binary documents now convert section/paragraph/control structure into the rendered model, preserving partially-ruled table borders per edge, column spans and proportional widths, authored cell heights for empty layout cells, character size/bold/italic/color/underline, paragraph alignment, paragraph-head bullet markers, and control-line horizontal rules. HWPX carries header/run styles, page metrics, and table color where directly present.
- RAR5 AES visible-header multi-volume handling and password-protected archive image preview caching were tightened so stale or wrong-password preview images are regenerated instead of reused.
- EPUB/Markdown/document/PDF pages now snap without slide/fade animation, and the compact hidden-toolbar top page counter height was refined.
- The selectable UI languages reached full coverage for the 1.0.3 string set: newly added archive support-boundary messages, bookmark "file missing" notices, and tap/image paging labels are now translated across all 20 non-default bundled locales, with English kept as the fallback for any future untranslated string.
- Public GitHub/F-Droid documents, Fastlane changelogs, the document viewer fidelity matrix and notes, and the F-Droid metadata draft were updated for the Readwide 1.0.3 package.

### Archive and FOSS boundary

- The 1.0.3 package keeps the no-network/local-first privacy baseline from earlier releases: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, and Android Auto Backup disabled.
- HWP/HWPX support is text-first and read-only through Apache-2.0 dogfoot libraries; cell vertical alignment, cell background fill, non-line GSO shapes, embedded images, and encrypted/password HWP are not claimed.
- RAR/CBR support remains limited and backend/scoped-path dependent; complete, encrypted, broad split, SFX, or VM-filtered RAR compatibility is not claimed.

## Readwide 1.0.2

### Release scope

- Readwide 1.0.2 keeps the same Android package identity for update compatibility with earlier compatible builds when signed with the same key.
- This release focuses on new document reader formats, rendered-document viewer polish, scoped archive decoding boundaries, and release documentation for the 1.0.2 line.

### Final changes included in this release

- Markdown files now open in a dedicated themed WebView reader. Markdown rendering is separate from the exact TXT reader model and does not change plain TXT paging.
- HWP/HWPX files now have text-first read-only support through Apache-2.0 dogfoot libraries. The app does not claim Hancom-compatible layout rendering, editing/writing, password/encrypted HWP support, or original page-count parity.
- The visible Word filter remains compact while grouping OOXML Word, HWP/HWPX, and recognized legacy DOC files. Legacy binary DOC is recognized for classification but remains unsupported for rendering.
- Markdown, EPUB, Word, HWP/HWPX, and PDF bookmark rows now use a shared rendered-document display model with content/text anchors as the primary label and page/position/date as secondary metadata.
- WebView document chrome was adjusted so toolbar toggles do not move the rendered body. Compact top page labels, bottom toolbar shape, slider presentation, Markdown CSS isolation, and Android navigation-inset handling were refined without changing TXT.
- PDF system-bar and navigation-inset behavior was refined separately from WebView documents so fixed-layout PDF behavior is preserved.
- The project launcher source reference was updated at `docs/readwide_launcher_icon_source.png`; checked-in Android launcher/adaptive/play-store PNG resources were left unchanged.
- Unknown-size decoded stream extraction, failed single-entry extraction cleanup, 7z solid-member drains, and 7z split/password classification were tightened under conservative archive compatibility claims.
- RAR/CBR support remains limited and backend/scoped-path dependent, with scoped decode-only paths for covered unencrypted single-volume RAR3/RAR4 PPMd solid and RAR5 v5.0 compressed/solid cases. Full RAR, encrypted RAR, broad split RAR, SFX, VM-filtered RAR, and complete RAR compatibility are not claimed.
- Public GitHub/F-Droid documents, Fastlane changelogs, FOSS notes, license report, SBOM draft, release notes, and release checklist were updated for the Readwide 1.0.2 package.

### Archive and FOSS boundary

- The 1.0.2 package keeps the no-network/local-first privacy baseline from earlier releases while adding Apache-2.0 HWP/HWPX libraries and updated direct-dependency notices.
- Archive claims for 1.0.2 should point reviewers to `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_2.md`; this release adds 7z safety/classification work and narrows RAR wording around scoped decode-only paths.
- RAR/CBR marketing must stay limited to documented covered cases; complete, encrypted, broad split, SFX, or VM-filtered RAR compatibility is not claimed.

## Readwide 1.0.1

### Release scope

- Readwide 1.0.1 keeps the same Android package identity for update compatibility with earlier compatible builds when signed with the same key.
- This release focuses on viewer polish, portable backup/bookmark handling, archive safety, lifecycle hardening, and public GitHub/F-Droid packaging cleanup.

### Final changes included in this release

- Missing bookmark target files remain visible with a theme-matched file-missing label. Tapping one opens an explanation dialog and keeps the bookmark for later file rebind.
- Backup import restores last directory, recent folders, and folder shortcuts only when those directories exist on the current device. Invalid imported paths are skipped without deleting valid current-device entries.
- TXT bookmark positions remain based on character position, line number, anchor text, and file fingerprint. Cached Page X/Y values are treated as layout-dependent cache and refresh under the current device layout when the file is opened.
- Zoomed PDF pages can fling/pan with inertia in single-page mode, and zoomed pages in vertical continuous mode can fling horizontally.
- Image viewer landscape safe-area handling was fixed for Android 3-button navigation. Wide images open fit-to-width, tall images open fit-to-height, and detail/original decode is kept after returning from zoom.
- Archive filename decoding, password-protected archive image preview, ALZ/EGG streaming extraction, archive preview cache pruning, and archive failure messages were hardened while keeping archive support claims conservative.
- Public GitHub/F-Droid documents, Fastlane changelogs, and release-note files were cleaned into result-focused Readwide 1.0.1 sections.

### Archive and FOSS boundary

- Default builds remain local-first and FOSS-oriented: no default `INTERNET` permission, no ads, no analytics, no account system, no app-network update check, Android Auto Backup disabled, and no Junrar/UnRAR-license fallback in the default build.
- ZIP, 7z, TAR-family, ALZ, EGG, and limited RAR/CBR support remain as documented in the archive support matrix.
- RAR support is still limited and should not be advertised as complete compatibility.

## Readwide 1.0.0

### Release scope

- Readwide 1.0.0 is the public continuation of TextView Reader 2.2.6.
- The app name is now Readwide, but the Android package/application ID stays unchanged for update compatibility with the TextView 2.2.6 line when signed with the same key.
- Settings now points to `https://github.com/k1717/Readwide/releases` for update information.

### Final changes included in this upload

- Readwide branding is applied across app labels, Settings, backup text, TTS labels, public docs, and developer contact text.
- Developer contact email is `readwide.kj7w5@addy.io`.
- Main language selection uses a compact row and rounded picker dialog instead of a long settings page.
- Major UI language options were added with initial translated resources; missing strings fall back to English.
- Recent-file multi-select actions can wrap long English labels instead of clipping.
- Launcher icons were replaced with the approved Readwide book artwork and adjusted for safer launcher margins.
- The custom reading theme editor now respects status-bar/cutout insets, so the top back button no longer overlaps the system bar.
- Reading-theme selection now shows a normal check mark again instead of broken encoded text.
- The custom reading theme editor's top app-bar/status-inset area now follows the active main theme bar color instead of showing a gray strip.
- The update link now uses the standard GitHub releases URL: `https://github.com/k1717/Readwide/releases`.
- RAR implementation comments and public packaging notes were cleaned for clearer FOSS/provenance wording.
- Launcher icon provenance is documented as project-owned generated artwork.
- The unused optional local RAR5 decoder bridge/readme was removed from the default public source tree.
- RAR detailed failure messages no longer expose development-session wording.
- The public Gradle dependency graph no longer includes a local `app/libs/*.jar` hook.
- Release signing is conditional, so F-Droid-style source builders can assemble an unsigned release without a private keystore.
- The unused Foojay toolchain resolver plugin was removed from `settings.gradle`.
- Readwide backup export filenames are ignored by git.
- Public docs are cleaned into release-result sections instead of development-session notes.

### Archive and FOSS boundary

- Default builds remain FOSS-oriented: no Junrar/UnRAR-license fallback code and no default `INTERNET` permission.
- ZIP, 7z, TAR-family, ALZ, EGG, and limited RAR/CBR support remain as documented in the archive support matrix.
- Split/multi-volume RAR and encrypted RAR were not re-tested for this release package and are not guaranteed.
- RAR solid archives, PPMd, custom VM filters, broad SFX, RAR5 compressed/solid/encrypted-header cases, and unusual variants remain backend-dependent or unsupported.

## TextView Reader 2.2.6

### Release scope

- TextView Reader 2.2.6 is the direct base for Readwide 1.0.0.
- Privacy, license, and archive-backend boundaries from this line are preserved in Readwide unless noted otherwise.

### Final changes included in this release

- Android Auto Backup is disabled.
- New PIN storage uses salted PBKDF2 verifier strings with legacy migration.
- Default builds have no `INTERNET` permission, no app-network update check, no telemetry, no ads, and no account system.
- Developer contact uses the user's mail app or copies the address when no mail app is available.
- Junrar/UnRAR-license fallback code is removed from the default build.
- Common compressed RAR3/RAR4 attempts route through bundled libarchive-android, with first-party Java kept for metadata, stored entries, safe paths, diagnostics, and selected stored RAR5 paths.
- Archive password prompts use compact buttons and include a password visibility toggle.
- Long archive errors open in a scrollable/copyable dialog.

### Known support boundaries

- Split/multi-volume RAR and encrypted RAR are not guaranteed.
- First-party compressed RAR is not complete.
- RAR5 compressed/solid/encrypted-header cases remain backend-dependent.

## TextView Reader 2.2.5

### Release scope

- Focused on archive fallback handling, smoother folder navigation, file-operation progress, and activity refactoring.

### Final changes included in this release

- ZIP extraction falls back to Apache Commons Compress for non-encrypted unsupported methods where bundled codecs can decode them.
- Pending ZIP creation runs in the destination folder where the queued action is executed.
- Viewer returns, drawer shortcuts, recent-folder navigation, and already-loaded folder revisits preserve or restore cached folder state when safe.
- Multi-select delete progress can be reopened after confirmation/backgrounding.
- Browse-state, archive list shaping, archive image sequence loading, and archive create/extract planning were split into focused controllers/helpers.

### Known support boundaries

- Encrypted ZIP entries stay on Zip4j.
- AES plus unsupported ZIP methods remain unsupported.
- ZIP creation is plain ZIP only.

## TextView Reader 2.2.4

### Release scope

- Focused on public license packaging, queued archive work, archive safety, and theme editing.

### Final changes included in this release

- First-party source ships under Apache License 2.0 with `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`.
- Compress actions enter the pending-action queue instead of running immediately.
- Pending copy, move, extract, and compress actions share the same queue flow.
- ALZ supports Store/Deflate/BZip2 extraction with CRC verification.
- EGG supports Store/Deflate/BZip2/AZO/LZMA through the first-party parser.
- 7z/CB7 split volumes open from the first part through a concatenated seekable channel.
- Archive preview and extraction include safer path handling, overwrite handling, free-space guards, and cache pruning.
- Main-theme and reading-theme color editors include a palette picker plus HEX/RGB input.

### Known support boundaries

- The older 2.2.4 RAR fallback path is not part of the default Readwide 1.0.0 FOSS-oriented package.
- RAR creation is not implemented.
- ALZ/EGG encrypted, split, solid, and unusual legacy variants remain limited or unsupported.
