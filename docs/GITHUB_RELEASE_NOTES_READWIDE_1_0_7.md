# Readwide 1.0.7 GitHub release notes

Readwide 1.0.7 keeps the `com.readwide.manager` application ID and the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.6. It adds an optional blank-line collapsing display setting, makes reading-position restore for text files more reliable, improves large-file bookmark page accuracy, preserves the recent list's scroll position when returning from a file, refines find-in-page behavior when its options change, fixes a find-in-page crash, reorganizes settings into a dedicated display-settings screen, and adds an explicit option to apply TXT display rules to the actual file from the reader's More menu, while keeping the local-first privacy baseline unchanged.

## Highlights

- **Collapse repeated blank lines (optional)**: a new reader display setting shows any run of two or more consecutive blank lines as a single blank line, without modifying the file. A lone blank line is left as-is. It applies to all text files the reader opens (TXT, log, CSV, and similar), both small and large, and is applied consistently to the page model, the large-file partition/exact-page index, and in-text search so page numbers, bookmarks, and search positions stay aligned. Toggling it reloads the open file. Default off; bookmarks from before this version stay compatible while it is off.
- **More reliable reading-position restore**: reopening a text file restores your position more robustly. The saved position now carries short before/after text anchors and a page-layout signature, so the reader re-finds the right spot even when the page layout would otherwise differ, instead of snapping to an approximate page. The restored position also stays correct after the system recreates the reader and when scrolling back through a large file, and a file that changed on disk is reloaded fresh instead of restoring an outdated cached view.
- **Recent list keeps its place**: returning to the app after opening a file from the recent list no longer forces the list back to the top; it stays near the row you opened from.
- **Find-in-page stability**: fixed a crash that could occur when an invalid regular expression was the active find-in-page query.
- **Find-in-page options apply on change**: changing case-sensitive, whole-word, or regular-expression options now restarts the search under the new options, so the next match uses the new settings instead of continuing from the previous result.
- **Large-file page labels after restore**: when the system recreates the reader from memory, large-file exact page numbering is rebuilt instead of staying on the estimate.
- **Large-file bookmark accuracy**: large-file bookmark jumps now prefer surrounding-text anchors when resolving the destination, improving landing accuracy after a layout or display change (for example a different font size, margin, or the blank-line collapse setting).
- **Reorganized settings**: display and reading-layout options (theme, reading theme, text layout, EPUB layout) now live in a dedicated **Display settings** screen reached from Settings, separate from general app settings, so the main Settings screen is no longer a mix of display and app-wide options. The large-TXT options, including Collapse repeated blank lines, are in this Display settings screen.
- **Edit actual TXT file**: enabled TXT display rules can be permanently applied to the current text file from the reader's **More** menu — fixing the original in place or writing a separate `_edited` copy — with rule-order, overwrite, and large-file warnings plus a final confirmation. Display-only rules still never modify the file; this is the explicit opt-in that does, and it always runs with the open file in context.
- **Local-first privacy baseline (unchanged)**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Version metadata

```text
versionName 1.0.7
versionCode 10007
applicationId com.readwide.manager
```

1.0.7 keeps the `com.readwide.manager` application ID introduced in 1.0.4 and the `readwide` release signing key introduced in 1.0.6, so it installs in place over 1.0.6.

## Migration for existing users

- Updating from 1.0.6: 1.0.7 uses the same signing key, so it installs in place as a normal update; no uninstall or backup step is required.
- Updating from 1.0.4/1.0.5: those used the previous signing key, so Android will not install 1.0.7 over them (signature mismatch). Uninstall the previous version, install 1.0.7, then import a JSON backup to restore bookmarks, reading positions, themes, and settings.
- Coming from TextView Reader or an older Readwide build with the `com.textview.reader` application ID: export a backup (`readwide_backup_<timestamp>.json`) from the old app, install 1.0.7, then import the backup to restore bookmarks, reading positions, themes, and settings.

## Compatibility and support boundaries

Readwide is a reader/file-browser app, not a complete office-suite or archive-suite replacement.

- TXT keeps the exact source-page reader model; Markdown, EPUB, PDF, Word, and HWP/HWPX page counts remain viewer/layout dependent.
- DOCX/HWP/HWPX document rendering targets content-fidelity HTML preview. Exact pagination, exact font metrics, editing/saving, and complete floating-object placement are non-goals.
- HWP/HWPX is read-only. RAR/CBR support is limited and backend/scoped-path dependent.

See:

- `docs/FDROID_SUBMISSION.md`
- `docs/FOSS_STATUS.md`
- `THIRD_PARTY_NOTICES.md`

## Build / verification reminder

Before tagging the release, run the local verification gates from a network-enabled Android/Gradle environment (`gradlew.bat clean testDebugUnitTest` and `assembleRelease`) and push the immutable `v1.0.7` Git tag referenced by `fdroid/metadata/com.readwide.manager.yml`.
