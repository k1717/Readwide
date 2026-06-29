# Readwide 1.0.10 GitHub release notes

Readwide 1.0.10 speeds up image page-flipping inside solid/sequential comic archives — 7z/CB7, the TAR family (TAR/CBT and its gzip/bzip2/xz/lzma/compress variants), and now RAR/CBR via its libarchive engine — fixes a problem where the previously shown image could stay on screen while you page through them, and smooths PDF page-flipping during rapid taps. It keeps the `com.readwide.manager` application ID and the `readwide` release signing key introduced in 1.0.6, so it updates in place over 1.0.9, 1.0.8, 1.0.7, and 1.0.6. The local-first privacy baseline is unchanged, and this release adds no new dependency.

## Highlights

- **Faster solid/sequential archive reading**: paging through images inside a solid 7z (`.7z`/`.cb7`) or a TAR-family archive (`.tar`/`.cbt` and its compressed variants) is faster. These formats have no cheap random access to a single image, so the viewer used to re-open the archive and re-decompress its stream from the start up to the requested image for every page. The viewer now keeps one forward reader open for the whole viewing session, decodes each image once, and caches every image it passes, so flipping forward decodes just the next image and pages you have already seen come from cache.
- **No more lingering previous image**: because the next image now extracts quickly, the previously shown image no longer stays on screen while it loads.
- **Faster first page on large archives**: opening a large solid archive no longer waits for the whole archive to decompress before the first image appears; only the images up to the one you open are extracted.
- **Reading-ahead is bounded by how far you read**: neighbour prefetch for these archives flows through the same forward reader, so reading ahead extends only as far as you go instead of decompressing the whole archive in the background.
- **Safe by construction**: the forward reader is a pure optimization. If it cannot serve an image for any reason, the viewer falls back to the previous whole-archive extraction, so the change can only improve speed, never reduce what opens. ZIP/CBZ, ALZ, and EGG already have direct per-entry access and are unchanged.
- **Smoother large-RAR paging**: RAR/CBR now flows through the same forward reader as 7z and TAR, backed by its libarchive engine (itself a strictly forward reader). Opening a large RAR/CBR no longer decompresses the whole archive before the first image shows, paging forward decodes each image once, and reading ahead extends only as far as you read. This covers ordinary RAR v4/v5 comics; anything the engine cannot read on its own (for example some encrypted RAR) falls back to the previous whole-archive extraction. Paging back to a page the cache cap had to evict re-extracts just that one page.
- **Smoother PDF page-flipping**: in single-page mode the PDF reader pre-renders neighbouring pages so a turn shows instantly. It now buffers further ahead in the direction you are reading (instead of splitting the budget evenly between forward and backward), so rapid forward or backward tapping stays ahead of the on-demand render more often. Page rendering, zoom, pan, and continuous-scroll mode are unchanged.
- **Local-first privacy baseline (unchanged)**: no default `INTERNET` permission, no ads, no analytics, no account system, no cloud sync, no Firebase/Google Play Services dependency, no in-app network update checker, and Android Auto Backup disabled.

## Version metadata

```text
versionName 1.0.10
versionCode 10010
applicationId com.readwide.manager
```

1.0.10 keeps the `com.readwide.manager` application ID introduced in 1.0.4 and the `readwide` release signing key introduced in 1.0.6, so it installs in place over 1.0.9, 1.0.8, 1.0.7, and 1.0.6.

## Dependencies

No new dependency. The change is internal to the archive image viewer; the dependency set is identical to 1.0.9. See `THIRD_PARTY_NOTICES.md`, `docs/LICENSE_REPORT_READWIDE_1_0_10.md`, and `docs/SBOM_READWIDE_1_0_10.spdx.json`.

## Migration for existing users

- Updating from 1.0.6, 1.0.7, 1.0.8, or 1.0.9: 1.0.10 uses the same signing key, so it installs in place as a normal update; no uninstall or backup step is required.
- Updating from 1.0.4/1.0.5: those used the previous signing key, so Android will not install 1.0.10 over them (signature mismatch). Uninstall the previous version, install 1.0.10, then import a JSON backup to restore bookmarks, reading positions, themes, and settings.
- Coming from TextView Reader or an older Readwide build with the `com.textview.reader` application ID: export a backup (`readwide_backup_<timestamp>.json`) from the old app, install 1.0.10, then import the backup to restore bookmarks, reading positions, themes, and settings.

## Compatibility and support boundaries

Readwide is a reader/file-browser app, not a complete office-suite or archive-suite replacement.

- The archive image speedup applies to solid 7z, the TAR family, and RAR/CBR (the last via its libarchive forward reader, for ordinary RAR v4/v5; cases libarchive cannot read on its own fall back to whole-archive extraction). ZIP/CBZ, ALZ, and EGG were already fast (direct per-entry access).
- PDF in-document find works on digital (text-based) PDFs only; scanned/image-only PDFs are not searched, and OCR is not included.
- TXT keeps the exact source-page reader model; Markdown, EPUB, PDF, Word, and HWP/HWPX page counts remain viewer/layout dependent.
- DOCX/HWP/HWPX document rendering targets content-fidelity HTML preview. Exact pagination, exact font metrics, editing/saving, and complete floating-object placement are non-goals.

See:

- `docs/FDROID_SUBMISSION.md`
- `docs/FOSS_STATUS.md`
- `THIRD_PARTY_NOTICES.md`

## Build / verification reminder

Before tagging the release, run the local verification gates from a network-enabled Android/Gradle environment (`gradlew.bat clean testDebugUnitTest` and `assembleRelease`) and push the immutable `v1.0.10` Git tag referenced by `fdroid/metadata/com.readwide.manager.yml`.
