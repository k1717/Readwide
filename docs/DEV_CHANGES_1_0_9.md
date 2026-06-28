# Readwide 1.0.9 development changes (vs 1.0.8)

Baseline: 1.0.8 (versionCode 10008), the released source package.

Public-facing docs are now updated to 1.0.9: README, CHANGELOG.md,
PATCHNOTES.md, fastlane changelog 10009.txt, THIRD_PARTY_NOTICES.md,
docs/FOSS_STATUS.md, docs/FDROID_SUBMISSION.md, F-Droid metadata
(com.readwide.manager.yml: 1.0.9 build entry + CurrentVersion 1.0.9), and the
new versioned docs docs/GITHUB_RELEASE_NOTES_READWIDE_1_0_9.md,
docs/LICENSE_REPORT_READWIDE_1_0_9.md, docs/SBOM_READWIDE_1_0_9.spdx.json (the
last two add the PdfBox-Android dependency). "Current public version" is 1.0.9.
Note: the F-Droid metadata carries a 1.0.7 build entry (real commit hash) and a
1.0.9 build entry whose `commit` field is still a
REPLACE_WITH_v1.0.9_FULL_COMMIT_HASH placeholder. It must be set to the full
40-character commit hash that the v1.0.9 tag points to (this app's F-Droid
maintainer requires a full hash, not the tag name) once that tag exists.

Theme: PDF in-document text search ("Find"), responding to GitHub issue #1.
Compatibility-first: keeps minSdk 24 and uses PdfBox-Android (pure Java,
Apache-2.0) for text extraction rather than androidx.pdf (Android 15 / minSdk 35,
alpha). androidx.pdf kept as reference only. OCR for scanned/image-only PDFs is
out of scope.

This release also folds in a recent-files list overhaul (reading-history search
with a result banner, a 5000-entry list over the full history, swipe-to-remove,
file-type-filter integration, and Back-to-clear) and an archive-entry-row styling
fix; see the dedicated sections at the end of this document.

## Changes in the tree

### app/build.gradle
- versionCode 10008 -> 10009, versionName "1.0.8" -> "1.0.9".
- Dependency `com.tom-roush:pdfbox-android:2.0.27.0` (note: Maven group uses a
  hyphen `com.tom-roush`; the Java package uses an underscore `com.tom_roush`).
- proguard-rules.pro: `-dontwarn com.gemalto.jp2.**` so R8 ignores PdfBox's
  optional, unbundled JPEG2000 decoder reference (would otherwise fail minify).

### PdfPageView.java
- Search-highlight overlay: `setHighlights(List<RectF> normRects, RectF current)`,
  `clearHighlights()`, `drawHighlights(Canvas)` at the end of `onDraw`. Normalized
  rects [0..1] mapped through the same matrix as the page bitmap, so highlights
  track zoom/pan. Active match emphasized. Cleared on new page / detach.

### PdfTextSearchEngine.java (new)
- PdfBox text extraction, per-page lazy cache, background scan with a generation
  token, match navigation (ordinal/total), options (case/word/regex),
  `matchesOnPage()` / `pageSizePts()`. Rects in PDF point space.

### PdfSearchController.java (new)
- Drives Find: lazy background PDDocument load on first query, owns the engine,
  converts engine point rects to normalized, navigates matches, reports status
  via a StatusListener. API: setSource / setStatusListener / setActive /
  startQuery / move / onPageShown / close + a small Host interface.

### PdfReaderActivity.java (wiring)
- Field `pdfSearchController`.
- `showPdfSearchDialog()` (mirrors `showGoToPageDialog`): a stably-positioned
  dialog (input + status + Find Previous / Find Next / Close) built with the
  existing dialog helpers, so the page and highlights stay visible. Plus
  `ensurePdfSearchController()` (lazy Host wiring) and `makePdfDialogButton()`.
- `setupControls()`: Find button (`R.id.pdf_find`) -> `showPdfSearchDialog()`.
- After `setFitBitmap(...)`: `onPageShown(currentPage, pageWpts, pageHpts)` to
  refresh highlights for the rendered page (single-page Matrix mode).
- `onDestroy`: `pdfSearchController.close()`.
- Find button added to the theming color array.

### ButtonOrderManager.java
- `GROUP_PDF_VIEWER` gains a `find` item (`R.id.pdf_find`), mirroring the document
  viewer, so Find participates in the reorderable bottom strip.

### res/layout/activity_pdf_reader.xml
- Added the `pdf_find` bottom button (ReaderBottomButton style, `ic_bottom_search`,
  `@string/find`) to `pdf_bottom_actions`.

No new strings: reuses `find`, `search_text_hint`, `find_previous`, `find_next`,
`close`.

## Flow
Tap Find -> dialog opens (setActive true) -> type -> controller debounces ->
engine loads the PDDocument in the background on first use -> scans pages ->
jumps to the first match page -> PdfPageView draws highlights for that page ->
Find Next/Previous walks matches (jump + highlight + "k/total"). Close dismisses
the dialog (setActive false -> highlights cleared). The PDDocument stays open
while the reader lives; closed in onDestroy.

## Verification still owed (local builds)
- Run `PdfTextSearchSpike` on a device to confirm the glyph-box top/baseline
  (`top = getYDirAdj() - getHeightDir()`); apply any nudge to the engine.
- Confirm engine cropbox page size matches the rendered PdfRenderer page size for
  standard PDFs (the normalize step assumes they match).
- Continuous scroll mode currently jumps to the match page only; highlight
  rendering there is a follow-up (highlights work in single-page Matrix mode).
- Memory: the controller keeps a second PDDocument handle open while searching;
  closed in close(). Watch the 1.0.7-style trim behavior on large PDFs.

## Post-user-test fixes (first on-device run)
1. Highlight too small: glyph boxes used getHeightDir() (≈cap height) only, a
   thin band. New glyphBox() pads up h*0.18 and down past the baseline h*0.30
   (~1.48x) so the highlight covers the full line including descenders.
2. Cross-page search broken (only the current page matched): root cause was
   repeated per-page PDFTextStripper.getText() on the same PDDocument coming back
   empty after the first page. Rewrote extraction to a single full-document pass
   (ensureExtracted + DocStripper, one getText over the whole doc, text/rects
   bucketed per page via getCurrentPageNo). scan() now iterates
   document.getNumberOfPages() instead of a passed-in count.
3. Search dialog inconsistent with the document viewer: restyled to mirror
   showDocumentSearchDialog -- title and match count share one row (title left
   20sp, "k / total" right 12sp), flat equal-width buttons (13sp bold,
   transparent), rounded panel container.

## Post-user-test fixes (second on-device run)
1. Highlight still too small: widened glyphBox padding from up h*0.18 / down
   h*0.30 to up h*0.32 / down h*0.42, so the band reaches past ascenders and
   descenders rather than hugging the cap height.
2. Match behind the find dialog (lower-page hits obscured): the find dialog is
   docked at the screen bottom (Gravity.BOTTOM, y=74dp), so a match in the lower
   part of a fit page sits behind it -- the same problem the EPUB/Word viewer
   solves by scrolling the hit above the dialog. Added a reveal-lift to
   PdfPageView:
   - `setSearchSafeBottom(int px)`: the host measures the dialog's top edge in the
     page view's pixel space (PdfReaderActivity.pdfSearchDialogTopInPageViewPx,
     measured a few frames after show() since the window is WRAP_CONTENT) and
     passes it in; 0 disables (dialog closed).
   - `recomputeRevealLift()`: if the current match's on-screen bottom would fall
     below the safe line, compute the upward shift needed (with a 12dp margin),
     correcting for any lift already applied so repeated recomputes converge.
   - `clampMatrix()`: a fit page is normally vertically centered; it may now lift
     by revealLiftPx, capped so the page top doesn't pass the viewport top and the
     page bottom doesn't rise above the safe line.
   - Only fit-height pages are lifted (a zoomed page is already pannable). A
     manual pan / pinch / double-tap / fling clears the lift
     (`releaseRevealLift`, with `revealSuppressedByUser`) so the user stays in
     control; a new match (next/prev, or a new page) re-enables it.
   - Also added the missing `onPageShown(...)` call at the second `setFitBitmap`
     site (the render-callback path), so highlights and lift also apply on a
     cache-miss freshly-rendered page, not only on cache hits.

## Post-user-test fixes (third on-device run)
1. Reveal-lift too weak on a full-height page (match still behind the dialog):
   the previous clampMatrix capped the lift to the page's centering slack
   (`maxLift = min(centered, page-bottom-to-safe-line)`). For a page that fits
   by height — drawnH ≈ viewport height, so `centered ≈ 0` — that cap was ≈ 0 and
   the page barely moved, leaving a lower-page match behind the dialog (the
   screenshot case: 37/775 "the" obscured). Fixes:
   - clampMatrix: dropped both caps for the fit-height branch. The page is now
     lifted by the full revealLiftPx; the page top going off the top of the
     screen is fine and expected (the empty top is what we trade to reveal a
     bottom match).
   - recomputeRevealLift: the lift is instead capped only so the match's *own*
     top stays on screen (`unliftedTop - 8dp` guard), which is the constraint
     that actually matters. Bumped the clearance margin 12dp -> 28dp so the match
     sits comfortably above the dialog.
   - Safe-bottom measurement now uses the dialog's content panel
     (`panel.getLocationOnScreen`) rather than the window decor, which is
     ambiguous across OEMs for a WRAP_CONTENT/bottom-gravity window; decor is a
     fallback. Retried up to 5 frames after show().

## Archive image viewer - faster sequential page-flipping
- Large archives (e.g. a CBZ/ZIP comic with ~2000 images) lagged on the first
  sequential read: each image extraction re-opened the zip4j ZipFile and
  re-parsed the whole central directory, then linear-scanned it -- O(entries)
  per image, O(entries^2) over a full read. Neighbour-prefetch and the decoded
  bitmap cache already existed; the parse was the bottleneck (getZipRawNames and
  the non-split archive path were already cached / no-copy).
- ArchiveSupport now caches a per-archive parsed index (ZIP_INDEX_CACHE, LRU 3,
  keyed by path+size+mtime): a zip4j ZipFile instance plus a name->FileHeader map
  built once. extractSingleZipEntry does an O(1) lookup + inflate instead of
  re-parsing. zip4j ZipFile holds no persistent OS handle, so a cached instance
  only retains parsed headers; eviction drops them.
- Encrypted archives are cached too, but setPassword is applied only for the
  duration of one extraction (under the index lock) and cleared in a finally, so
  no password is retained in the shared cache between pages. Name resolution,
  fallbacks, and PasswordRequiredException behaviour are unchanged.
- Not yet device-verified: large-archive flip smoothness, encrypted archives,
  non-UTF-8 (e.g. Shift-JIS) entry names, and no regression on the second pass
  (cache files already persisted to disk, so re-viewing was already fast).

## Recent files list - history search, larger list, swipe-remove

The home-screen recent list is a view over BookmarkManager.readingStates (no
separate store), so search/remove operate on that. The home search box, which
previously ran a filesystem walk, now filters the in-memory reading history.

### MainRecentFilesController.java
- DISPLAY_LIMIT 300 -> 5000; SCAN_LIMIT removed (getRecentFiles(Integer.MAX_VALUE)
  loads the whole history). The full visible list is cached in fullRecentItems /
  fullRecentStates for search; the adapter shows the first DISPLAY_LIMIT when not
  searching (displayItems()).
- applyRecentSearch / clearRecentSearch / applyRecentSearchInternal:
  case-insensitive filename substring filter over fullRecentItems. The match count
  is matches.size(), i.e. over the full (unlimited) history. A reload re-applies
  the filter from the live search box (currentSearchBoxQuery()), not a stale flag.
- Search banner: showRecentSearchBanner / hideRecentSearchBanner drive
  R.id.recent_search_banner using the shared file_search_results_for string
  ("Search: <query> (<count>)"), shown only while searching and kept in sync after
  a swipe-delete.
- Swipe-to-dismiss: attachSwipeToDismiss() attaches an ItemTouchHelper (LEFT) to
  the recent RecyclerView. getSwipeThreshold = 0.45 and getSwipeEscapeVelocity =
  Float.MAX_VALUE, so only crossing ~45% commits (a flick that stops short snaps
  back); the default onChildDraw gives the card-follows-finger + recover
  animation. Disabled in multi-select (getSwipeDirs = 0). onSwiped ->
  removeRecentItemAt(): deleteReadingState(path) (drops the reading position) +
  adapter.removeItemAt + removeFromFullRecent + chrome refresh.

### adapter/FileAdapter.java
- Added getItemAt(pos) and removeItemAt(pos) (in-place items.remove +
  notifyItemRemoved) for the swipe handler; items is a stable final list.

### MainSearchFilterController.java
- runLiveFileSearchNow: home (homeMode && !searchMode) is intercepted so the search
  box filters the recent reading history (applyRecentSearch, or clearRecentSearch
  on empty) and stays in home mode, instead of falling through to the filesystem
  walk. Folder-browse search is unchanged.
- File-type chip handler: home + non-empty query now reloads recent for the new
  filter (set activeFileFilter + loadRecentFiles), which re-applies the live search
  on top, so the chip and the search compose rather than override each other.

### MainActivity.java
- Delegates applyRecentSearch / clearRecentSearch / attachRecentSwipeToDismiss to
  the recent controller.
- handleMainBackPressed: a new early step clears an active recent search first
  (homeMode && !searchMode && hasFileSearchQuery -> empty the box +
  clearRecentSearch) before the filter-drop / exit handling. Without it a recent
  search left Back going straight to the exit prompt, since the recent search
  stays on the home screen rather than the separate search screen.

### MainActivityStartupController.java
- Calls activity.attachRecentSwipeToDismiss() after the recent adapter is set.

### MainThemeController.java
- Themes R.id.recent_search_banner with the runtime panel background / sub text
  (mirrors the path bar), so the banner follows the active (e.g. navy) theme
  instead of the base ?attr/colorSurfaceVariant, which is near-black under that
  theme.

### res/layout/activity_main.xml
- Added recent_search_banner (TextView, gone by default) under recent_header_row,
  above recent_content_container.

## Archive entry rows - unify with main file list metrics

### ArchiveBrowserActivity.java (EntryAdapter)
- The archive preview builds its rows programmatically and they used larger
  font/spacing than the XML item_file rows. EntryAdapter.Holder now matches
  item_file.xml: name via ExtensionEllipsisTextView at 12sp (maxLines 2, line
  spacing 1.05), info at 11sp (1dp top margin), a 28dp icon with 3dp/12dp margins
  (icon glyph at 11dp, text at 51dp), row padding 8/5/10/5, and 57dp min height
  (previously 16sp / 13sp, a 32dp icon, 14/10/14/10 padding, and 62dp).
