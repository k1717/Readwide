# Readwide 1.0.9 GitHub release notes

Readwide 1.0.9 adds in-document text find to the PDF reader for digital (text-based) PDFs, makes paging through images in large archives smoother, and reworks the recent-files list (it now searches your reading history with a result banner, keeps your full history and shows up to 5000 entries, combines with the file-type filters, and supports swipe-to-remove). It also matches the archive preview's row styling to the main file list. It keeps the `com.readwide.manager` application ID and the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.8, 1.0.7, and 1.0.6. The local-first privacy baseline is unchanged. This release adds one new runtime dependency, PdfBox-Android (Apache-2.0), used only for PDF text extraction.

## Highlights

- **New: find text inside a PDF**: the PDF reader now has a **Find** action that searches for text inside the open PDF. You can step through matches with previous/next, see a current/total match count, and matches are highlighted on the page; the highlights track zoom and pan. The find dialog matches the search dialog used by the other viewers.
- **Whole-document search**: find covers every page, not just the page you are on. Previous/next step through matches across the document and jump to the page where each match is.
- **Digital PDFs only, no OCR**: this works on text-based (digital) PDFs that carry a real text layer. Scanned or image-only PDFs have no extractable text, so they are not searched. OCR is intentionally not included, to keep the app lean and fully local.
- **Rendering is unchanged**: PDF pages are still drawn by the platform `PdfRenderer`, exactly as before. The new dependency (PdfBox-Android) is used only to read the text and its on-page positions, not to render pages, so page rendering, zoom, pan, and the continuous/single-page modes behave the same.
- **Smoother large-archive image reading**: paging to the next or previous image inside a big comic archive (ZIP/CBZ) is faster, especially the first time through, because the reader caches each archive's parsed entry index instead of re-reading the whole archive directory for every page. This matters most for large comics (around two thousand images), and applies to password-protected archives too, without keeping the password between pages.
- **Recent list searches your reading history**: the home-screen search box now searches your recently-read files (your reading history) and shows how many match, instead of searching device storage. The recent list keeps your full history and shows up to 5000 entries, and the file-type filter chips combine with the search (filter first, then search within it). Searching while browsing a folder still searches storage.
- **Swipe to remove a recent file**: swipe a recent row to the left to remove it - the card follows your finger and commits past about 45% of its width, and removing a row clears that file's saved reading position. Back clears an active recent search first (restoring the list) before dropping a filter or leaving the screen.
- **Consistent archive listing**: file rows inside the archive (ZIP/CBZ) preview now use the same text sizes and spacing as the main file list.
- **Local-first privacy baseline (unchanged)**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Version metadata

```text
versionName 1.0.9
versionCode 10009
applicationId com.readwide.manager
```

1.0.9 keeps the `com.readwide.manager` application ID introduced in 1.0.4 and the `readwide` release signing key introduced in 1.0.6, so it installs in place over 1.0.8, 1.0.7, and 1.0.6.

## New dependency

- `com.tom-roush:pdfbox-android:2.0.27.0` — Apache-2.0, pure Java. Used only for PDF text extraction with glyph positions, to support in-document find. It does not render PDF pages. The optional JP2/JPEG2000 image decoder (`com.gemalto.jp2`) is not bundled, so JPX images are ignored; this does not affect text search. See `THIRD_PARTY_NOTICES.md`, `docs/LICENSE_REPORT_READWIDE_1_0_9.md`, and `docs/SBOM_READWIDE_1_0_9.spdx.json`.

## Migration for existing users

- Updating from 1.0.6, 1.0.7, or 1.0.8: 1.0.9 uses the same signing key, so it installs in place as a normal update; no uninstall or backup step is required.
- Updating from 1.0.4/1.0.5: those used the previous signing key, so Android will not install 1.0.9 over them (signature mismatch). Uninstall the previous version, install 1.0.9, then import a JSON backup to restore bookmarks, reading positions, themes, and settings.
- Coming from TextView Reader or an older Readwide build with the `com.textview.reader` application ID: export a backup (`readwide_backup_<timestamp>.json`) from the old app, install 1.0.9, then import the backup to restore bookmarks, reading positions, themes, and settings.

## Compatibility and support boundaries

Readwide is a reader/file-browser app, not a complete office-suite or archive-suite replacement.

- PDF in-document find works on digital (text-based) PDFs only; scanned/image-only PDFs are not searched, and OCR is not included.
- TXT keeps the exact source-page reader model; Markdown, EPUB, PDF, Word, and HWP/HWPX page counts remain viewer/layout dependent.
- DOCX/HWP/HWPX document rendering targets content-fidelity HTML preview. Exact pagination, exact font metrics, editing/saving, and complete floating-object placement are non-goals.
- HWP/HWPX is read-only. RAR/CBR support is limited and backend/scoped-path dependent.

See:

- `docs/FDROID_SUBMISSION.md`
- `docs/FOSS_STATUS.md`
- `THIRD_PARTY_NOTICES.md`

## Build / verification reminder

Before tagging the release, run the local verification gates from a network-enabled Android/Gradle environment (`gradlew.bat clean testDebugUnitTest` and `assembleRelease`) and push the immutable `v1.0.9` Git tag referenced by `fdroid/metadata/com.readwide.manager.yml`.
