# Readwide 1.0.4 GitHub release notes

Readwide 1.0.4 completes the public package rename by moving to the new Android application ID `com.readwide.manager`, while keeping the local-first privacy baseline and the document-viewer fidelity work delivered in 1.0.3. It also unifies find-in-page behavior across TXT, Markdown, EPUB, HWP/HWPX, and Word-family readers.

## Highlights

- **New application ID (`com.readwide.manager`)**: Readwide finishes the move away from the original TextView Reader package identity. Because the application ID changed, 1.0.4 installs as a **separate app** rather than an in-place update over older TextView Reader/Readwide builds.
- **Data carries over via backup**: bookmarks, reading positions, themes, and settings transfer through the in-app JSON backup export/import, which is independent of the package name and signing key. Existing users export a backup from the old app, install 1.0.4, then import it.
- **Unified reader search options**: TXT, Markdown, EPUB, HWP/HWPX, and Word-family document search now share case-sensitive, whole-word, and regular-expression options, with Unicode normalization always applied.
- **TXT search performance and end-of-file reveal**: common-word searches in large TXT files no longer repeatedly normalize the full text, and final-line search results can be pulled above the search dialog through a search-only virtual bottom allowance without changing normal paging/bookmark behavior.
- **Document viewer search polish**: Markdown/EPUB/HWP/Word search no longer depends on WebView native find. The document search dialog now provides previous/next movement, nth-match jumps, current/total status, visible highlight spans, and popup-safe result reveal for matches near the top or bottom of a rendered document.
- **PDF viewer**: pages are rendered above screen density (supersampling) and downscaled for sharper text without stretching, and a full page now sits between the top title area and the bottom toolbar when the toolbar is visible instead of being partly hidden behind it.
- **Image viewer**: the preview decode budget is raised from 12 to 16 megapixels so higher-resolution images show at full detail before any downsampling; large images are still scaled to fit screen and memory.
- **Settings text-clipping fixes**: the "Button / icon order" rows, sort options, and TXT search controls no longer cut off longer translations vertically. Rows grow or wrap instead of being locked to a single fixed height.
- **Manifest cleanup**: removed the obsolete `requestLegacyExternalStorage` flag, which had no effect under target SDK 35. File access behavior is unchanged.
- **Carried forward from 1.0.3**: the shared rendered-document model for DOCX/HWP/HWPX content fidelity, inline/display math rendering without WebView JavaScript, archive-preview safety work, and full UI localization remain part of this package.
- **Local-first privacy baseline**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Version metadata

```text
versionName 1.0.4
versionCode 10004
applicationId com.readwide.manager
```

Earlier TextView Reader/Readwide builds used the `com.textview.reader` application ID. The change to `com.readwide.manager` means Android treats 1.0.4 as a distinct app, so it does not auto-update older installs and the two can coexist until the user removes the old one.

## Migration for existing users

1. Open the previous app (TextView Reader / older Readwide) and export a backup (`readwide_backup_<timestamp>.json`) from Settings.
2. Install Readwide 1.0.4.
3. In 1.0.4, import the backup JSON to restore bookmarks, reading positions, themes, and settings.
4. Optionally remove the old app once the import is confirmed.

## Compatibility and support boundaries

Readwide is a reader/file-browser app, not a complete office-suite or archive-suite replacement.

- TXT keeps the exact source-page reader model; Markdown, EPUB, PDF, Word, and HWP/HWPX page counts remain viewer/layout dependent.
- DOCX/HWP/HWPX document rendering targets L3 content-fidelity HTML preview. Exact pagination, exact font metrics, editing/saving, and complete floating-object placement are non-goals.
- HWP/HWPX is read-only. Cell vertical alignment, cell background fill, non-line GSO shapes, embedded images, and encrypted/password HWP are not yet claimed.
- Legacy binary `.doc` is recognized but unsupported for rendering.
- RAR/CBR support is limited and backend/scoped-path dependent.

See:

- `docs/DOCUMENT_VIEWER_FIDELITY_MATRIX_READWIDE_1_0_3.md`
- `docs/DOCUMENT_VIEWER_FIDELITY_READWIDE_1_0_3.md`
- `docs/TXT_SEARCH_USAGE.md`
- `docs/FDROID_SUBMISSION.md`
- `docs/FOSS_STATUS.md`
- `THIRD_PARTY_NOTICES.md`

## Build / verification reminder

Before tagging the release, run the local verification gates from a network-enabled Android/Gradle environment (`gradlew.bat clean testDebugUnitTest` and `assembleRelease`) and push the immutable `v1.0.4` Git tag referenced by `fdroid/metadata/com.readwide.manager.yml`.
