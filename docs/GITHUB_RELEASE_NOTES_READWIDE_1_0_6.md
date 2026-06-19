# Readwide 1.0.6 GitHub release notes

Readwide 1.0.6 keeps the `com.readwide.manager` application ID but is signed with a new release key, so it does not update in place over 1.0.4/1.0.5 (see Migration below). It focuses on file-list performance, reading-progress for image archives, per-type file icons, folder auto-refresh, image-viewer paging smoothness, text-to-speech refinements, and several browsing fixes, while keeping the local-first privacy baseline unchanged.

## Highlights

- **Faster file listing and sorting**: folder listing and sorting no longer issue a MediaStore query per file or per scrolled row. Folders sort and display immediately using filesystem timestamps; when a date sort is active, a background pass batches MediaStore date lookups and re-sorts only if the order changes. Large folders such as Downloads open noticeably faster with the same set of files shown.
- **Uncapped file search**: the previous 5,000-item search cap is removed. Results stream into the list incrementally as the search walks storage, then settle into the final sorted order, so very large result sets are no longer truncated.
- **Reading progress for image archives**: comic/image archives (and folder image sequences) now record a reading position and show a progress percent in the recent list, like PDF and EPUB. Progress is saved with a short debounce while paging and immediately when leaving the viewer.
- **Per-type file icons**: file rows show a Material icon chosen by type (PDF, EPUB, document, archive, image, video, audio, app package, or a generic fallback), tinted with the current theme color. Folder icons are unchanged.
- **Extension-preserving long names**: long file names keep their extension and trailing context visible by shortening in the middle as "start…end.ext", so the file type and the end of the name stay readable across two lines. The shortening is computed during layout, so the final text appears in one pass without a flash on re-entry.
- **Search-relative file locations**: search results show each file's location relative to the searched folder instead of repeating the full absolute path on every row. A file directly in the searched folder shows no location; a subfolder file shows ".../subfolder". An all-storage search keeps the matched storage's folder name as a prefix so results stay distinguishable.
- **More image formats**: the image filter and image viewer now also recognize `.jfif`, `.wbmp`, and `.dng` (formats Android's decoder can render); TIFF/ICO were intentionally left out since they would not open.
- **Folder auto-refresh**: the visible folder is re-read when the app regains focus, catching downloads and other external changes the filesystem watcher can miss on FUSE/MediaStore-routed storage. The re-read keeps scroll position and only runs when the folder's on-disk signature changed. Pull down on the file list to refresh manually.
- **Smoother image and PDF paging**: the image viewer prefetches three pages in each direction (was two) with a larger decoded-bitmap cache, and the PDF single-page and continuous caches were enlarged, reducing stalls on rapid paging and scrolling.
- **Resume reading aloud**: if a read-aloud session is interrupted, the main screen offers to resume that book from where it stopped — reopening the file at its saved page, restoring the sleep-timer value that was active, and starting playback automatically. Choosing "Later" dismisses the prompt and finishing a book clears it. Text-to-speech also no longer reads out runs of punctuation such as ellipses or underscores.
- **Browsing fixes**: starting a folder navigation during a running search no longer leaves a stale search screen or brief freeze; and tapping the drawer's Recent shortcut in a large folder no longer delays the drawer from closing.
- **Security & stability**: files opened into Readwide from other apps are copied into an app-private cache with filename sanitization, a canonical path-containment check, a 2 GB per-file copy limit, and cache cleanup before and after the copy (a failed or oversized copy leaves no partial file, and a misbehaving provider can't crash the open); backup import rejects JSON larger than 256 MB; with the optional app lock on, the home and recent screens are no longer prepared behind the lock screen; search and file-type filtering cancel promptly when superseded by a newer search or folder change; and document, in-document resource, and EPUB chapter reads are size-capped so a malformed file cannot exhaust memory.
- **Local-first privacy baseline (unchanged)**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Version metadata

```text
versionName 1.0.6
versionCode 10006
applicationId com.readwide.manager
```

1.0.6 keeps the `com.readwide.manager` application ID introduced in 1.0.4 but is signed with a new release key (the `readwide` alias), so it does not install over an existing 1.0.4/1.0.5 — see Migration.

## Migration for existing users

- Updating from 1.0.4/1.0.5: 1.0.6 uses a new signing key, so Android will not install it over an existing 1.0.4/1.0.5 (signature mismatch). Uninstall the previous version, install 1.0.6, then import a JSON backup to restore bookmarks, reading positions, themes, and settings.
- Coming from TextView Reader or an older Readwide build with the `com.textview.reader` application ID: export a backup (`readwide_backup_<timestamp>.json`) from the old app, install 1.0.6, then import the backup to restore bookmarks, reading positions, themes, and settings.

## Compatibility and support boundaries

Readwide is a reader/file-browser app, not a complete office-suite or archive-suite replacement.

- TXT keeps the exact source-page reader model; Markdown, EPUB, PDF, Word, and HWP/HWPX page counts remain viewer/layout dependent.
- DOCX/HWP/HWPX document rendering targets L3 content-fidelity HTML preview. Exact pagination, exact font metrics, editing/saving, and complete floating-object placement are non-goals.
- HWP/HWPX is read-only. RAR/CBR support is limited and backend/scoped-path dependent.

See:

- `docs/DOCUMENT_VIEWER_FIDELITY_MATRIX_READWIDE_1_0_3.md`
- `docs/DOCUMENT_VIEWER_FIDELITY_READWIDE_1_0_3.md`
- `docs/TXT_SEARCH_USAGE.md`
- `docs/FDROID_SUBMISSION.md`
- `docs/FOSS_STATUS.md`
- `THIRD_PARTY_NOTICES.md`

## Build / verification reminder

Before tagging the release, run the local verification gates from a network-enabled Android/Gradle environment (`gradlew.bat clean testDebugUnitTest` and `assembleRelease`) and push the immutable `v1.0.6` Git tag referenced by `fdroid/metadata/com.readwide.manager.yml`.
