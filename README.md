# Readwide

Readwide is a local Android reader and file-browser app for TXT, PDF, EPUB, Word, image, comic archive, and general archive workflows.

Readwide 1.0.1 is the current public source package. Readwide is the public successor to TextView Reader; the launcher name and public repository changed to Readwide, while the Android `applicationId` remains `com.textview.reader` for update compatibility when the APK is signed with the same key.

Public repository: `https://github.com/k1717/Readwide`

Current version: **Readwide 1.0.1**

Update page: `https://github.com/k1717/Readwide/releases`

## Readwide 1.0.1

- Android metadata is `versionCode 10001` and `versionName "1.0.1"`.
- Missing bookmark files remain in the bookmark list with a theme-matched missing-file label. Tapping one opens a themed explanation dialog instead of silently dropping the bookmark.
- Zoomed PDF pages now support inertial fling panning in single-page mode, and zoomed pages in vertical continuous mode support horizontal fling across the visible page. Original-zoom swipes still keep the existing page-turn behavior.
- TXT bookmarks continue to restore by character position, line, and surrounding anchor text. Cached Page X/Y labels are refreshed against the current TXT layout/page model when the file is open or a bookmark is used, so page-count changes do not move the bookmark target.
- ZIP raw central-directory filename decoding is cached per archive fingerprint, reducing repeated central-directory parsing and legacy-name scoring during list, extract, and image-preview flows.
- Image decode retry after `OutOfMemoryError` now backs off by increasing sample size without forcing a process-wide GC on every retry.
- Image viewer default fitting is adaptive: wide pages open fit-to-width and tall pages open fit-to-height, while double tap can toggle with true 1:1 scale.
- Delayed local-image viewer launches now discard pending sequence handoff tokens if the main Activity is destroyed before the delayed start runs.
- Document WebView and PDF delayed callbacks received additional lifecycle guards so stale post-delayed work does not touch destroyed viewers.
- Archive image extraction cache checks now use a shared helper across browser, lazy loader, and image reader paths, keeping password-sensitive cache validation consistent.
- Archive password array clone/clear handling is centralized, and image-reader archive prefetch now snapshots state before background work to reduce Activity retention risk.
- Font scanning received lifecycle/concurrency cleanup: duplicate recursive scan calls were removed, font maps are accessed under synchronization, and async cancellation clears stale listener references.
- Hardened password-protected archive image preview cache handling: sensitive cached images are no longer reused until the currently supplied password has re-extracted/validated the requested entry in the active session.
- Tightened archive-image handoff and password lifetime handling by adding handoff TTL/overflow pruning, clearable password providers, launch-failure cleanup, and explicit zero-fill paths in archive/image activities.
- Reduced image-viewer lifecycle leak risk by clearing pending main-thread callbacks on destroy, using application context for preview/detail decode, and keeping password snapshots local to extraction calls.
- Refined archive extraction failure classification so unsupported encryption, missing password, bad password, corrupt archive, and generic failures route to more accurate UI messages.
- Throttled archive preview cache pruning so large cache directory scans do not run unnecessarily on every archive open while keeping password-sensitive cache cleanup aggressive.
- Fixed the image viewer landscape layout on devices using Android 3-button navigation by applying left/right system-bar insets to the image surface, top toolbar, and bottom image slider.
- Added best-effort automatic filename-encoding detection for legacy archive entry names, covering raw ZIP central-directory names plus first-party ALZ/EGG name fields.
- The decoder keeps ASCII and valid UTF-8 stable, then falls back across major legacy archive filename families/code pages including Korean MS949/CP949, Simplified Chinese GB18030/GBK, Traditional Chinese Big5, Japanese Shift_JIS, Western Latin Windows-1252, Central/Eastern European Windows-1250, Baltic Windows-1257, Cyrillic Windows-1251/KOI8-R/IBM866, Greek Windows-1253, Turkish Windows-1254, Hebrew Windows-1255, Arabic Windows-1256, Thai Windows-874, Vietnamese Windows-1258, and DOS ZIP IBM437.
- EGG locale code-page hints are honored when present, so hinted names use the declared code page instead of relying only on ambiguous byte-pattern scoring.
- ZIP listing and single-entry extraction now share the same decoded-name mapping where raw central-directory names are available, so archive image preview and extraction can use the corrected displayed name.
- Password-protected archive image viewing now uses the same lazy image-sequence path: only the selected image is extracted before the viewer opens, and adjacent images are extracted/prefetched on demand.

- ALZ Store/Deflate/BZip2 and EGG Store/Deflate/BZip2/LZMA extraction now stream data to the output file with CRC verification instead of buffering entire entries first where the format path allows it.
- Archive preview cache now separates ordinary preview entries from password/sensitive archive previews. Sensitive previews stay in app-private cache and use shorter/smaller pruning limits.
- Archive failure UI now shows clearer support-boundary messages for RAR, ZIPX, 7z, ALZ, and EGG, and separates wrong password from unsupported features and damaged/incomplete archives.
- Added RAR real-fixture QA scaffolding and scripts that generate listing/extraction/image-preview smoke reports without expanding public RAR support claims.
- Added a generic archive fixture matrix report for ZIP/ZIPX/7z/TAR/ALZ/EGG and single-compressor samples, keeping non-RAR support notes tied to local real-fixture checks.
- Split archive failure classification into a smaller tested helper and added a cancellable font-scan handle for safer future UI lifecycle handling.

## Highlights

- Local-first reader app with no default `INTERNET` permission, no analytics, no ads, no account system, and Android Auto Backup disabled.
- TXT reader with bookmarks, search highlighting, themes, custom fonts, page navigation, large-TXT paging safeguards, and TTS controls.
- File browser with recent files, filters, folder/search scope controls, fast scrolling, multi-select actions, queued copy/move/delete/extract/compress operations, and progress UI.
- Image and comic viewing for local folders and supported archive formats, including sorted image sequences and saved comic positions.
- Archive preview/extraction support for common ZIP, 7z, TAR-family, ALZ, EGG, and limited RAR/CBR paths.
- Readwide branding, icon assets, settings text, backup labels, and developer-contact documentation are updated for the 1.0.x line.

## Archive Support Summary

| Format family | Current path | Notes |
| --- | --- | --- |
| ZIP / ZIPX / CBZ | Zip4j primary, Apache Commons Compress fallback for non-encrypted special methods | Legacy internal filenames can use best-effort raw central-directory encoding detection when available. Encrypted ZIP remains on Zip4j. AES plus unsupported special compression methods are not guaranteed. |
| 7z / CB7 | Apache Commons Compress 7z path | Password forwarding is attempted. Unsupported method chains depend on Commons Compress coverage. |
| TAR / CBT and TAR.GZ / TAR.BZ2 / TAR.XZ / TAR.LZMA / TAR.Z | Apache Commons Compress stream wrappers | Regular-file extraction is supported. Special entries are handled conservatively. |
| GZ / BZ2 / XZ / LZMA / Z | Single-file compressor streams | These are not multi-file archive containers. |
| RAR / CBR | Bundled libarchive-android for common compressed RAR attempts plus first-party Java paths for metadata and stored entries | RAR creation is not supported. Split/multi-volume RAR and encrypted RAR were not re-tested for this release package and are not guaranteed. Solid, PPMd, VM-filtered, broad SFX, and RAR5 compressed/solid/encrypted-header cases remain backend-dependent or unsupported. |
| Archive image preview | Lazy extraction into the image viewer | Password-protected archives use the lazy path after a password is supplied: selected image first, adjacent images on demand. Ordinary and sensitive/password preview cache roots are pruned separately and remain app-private disposable cache. |
| ALZ | First-party parser for Store/Deflate/BZip2 and covered ZipCrypto-style cases | Internal filenames use best-effort UTF-8/MS949/legacy detection. Broader legacy/split/encrypted variants still need real-world fixture QA. |
| EGG | First-party parser for Store/Deflate/BZip2/AZO/LZMA | Internal filenames use best-effort UTF-8/MS949/legacy detection and locale hints when present. Encrypted, split, and solid EGG archives are unsupported. |
| Archive creation | Plain ZIP creation through queued file actions | RAR/7z/ALZ/EGG creation and encrypted ZIP creation are not implemented. |

See `docs/ARCHIVE_SUPPORT_MATRIX_READWIDE_1_0_1.md` and `docs/RAR_STATUS_READWIDE_1_0_1.md` for the detailed archive/RAR compatibility boundary.

## Privacy And FOSS Notes

- Default builds do not request `INTERNET`.
- Android Auto Backup is disabled with `android:allowBackup="false"`.
- The developer contact button opens the user's mail app with `readwide.kj7w5@addy.io` or copies the address if no mail app is available.
- Junrar and other UnRAR-license fallback code are not part of the default FOSS-oriented build.
- First-party source is Apache License 2.0. Runtime dependency notices are kept in `THIRD_PARTY_NOTICES.md`.
- Any local jar added under `app/libs` must be audited separately before that custom APK is described as FOSS.

## Build

Requirements:

- Android Studio / Android Gradle Plugin compatible JDK
- Android SDK installed locally

Common local commands:

```powershell
.\gradlew.bat assembleDebug --offline
.\gradlew.bat testDebugUnitTest --offline
```

Release signing is intentionally local. The public source package does not include private keystores, APK outputs, IDE metadata, local SDK paths, or build directories.

## Public Release Docs

- `CHANGELOG.md` lists result-focused release changes.
- `PATCHNOTES.md` is the shorter user-facing release note format.
- `GITHUB_UPLOAD_NOTES.md` contains upload/checklist notes for publishing the GitHub source package.
- `docs/FDROID_SUBMISSION.md` and `fdroid/metadata/com.textview.reader.yml` provide the F-Droid submission draft and build hygiene notes.
- `fastlane/metadata/android/` contains source-embedded store/F-Droid metadata for English and Korean.
- `PRIVACY.md`, `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md` should be included with source releases.
- `docs/ASSET_PROVENANCE.md` records the release-facing provenance note for launcher artwork.
- `docs/RAR_FIXTURE_QA.md` explains the external RAR fixture report workflow and generated support-boundary reports.
- `docs/ARCHIVE_FIXTURE_QA.md` explains the non-RAR external archive fixture matrix workflow.
