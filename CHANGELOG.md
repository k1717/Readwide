# Changelog

## Readwide 1.0.1 - 2026-06-09

### Release scope

- Kept Android metadata at `versionCode 10001` and `versionName "1.0.1"`.
- Kept the existing Android `applicationId` / package name so Readwide 1.0.1 remains update-compatible with earlier compatible builds when signed with the same key.
- Focused on viewer polish, portable backup/bookmark handling, archive safety, lifecycle hardening, and public GitHub/F-Droid packaging cleanup.

### Final changes included in this upload

- Missing bookmark target files remain visible in the bookmark list with a theme-matched **File missing / 파일 없음** label. Tapping one opens a themed explanation dialog while preserving the bookmark for later portable rebind.
- Backup import keeps `last_directory`, `recent_folders`, and `folder_shortcuts` only when those folders exist on the current device. Invalid imported paths are skipped, and valid current-device entries are preserved when the backup has no accessible replacement.
- TXT bookmark imports treat `pageNumber`, `totalPages`, and `pageLayoutSignature` as layout-dependent cache. Character position, logical line, surrounding anchor text, and file fingerprint remain the stable bookmark location, and Page X/Y refreshes under the current device layout when the file is opened or the bookmark is used.
- Zoomed PDF pages can fling/pan with inertia in single-page mode, and zoomed pages in vertical continuous mode support horizontal fling across the visible page while original-scale swipes keep their page-turn behavior.
- Image viewer landscape safe-area handling now respects Android 3-button navigation on the right side of the screen.
- Image viewer default fitting is adaptive: wide images open fit-to-width and tall images open fit-to-height. Double tap toggles against true 1:1 scale when applicable.
- Image viewer keeps successfully decoded detail/original bitmaps after returning from zoom to adaptive-fit view, avoiding repeated detail re-decodes for the same image.
- Archive-backed image viewer recent/saved-position reopen paths were hardened so deferred image sequence metadata is applied before decoding, with fallback handling for missing or invalid handoff metadata.
- Legacy archive entry filename decoding was expanded for raw ZIP central-directory names and first-party ALZ/EGG name fields, covering Korean, Chinese, Japanese, Cyrillic, Greek, Turkish, Hebrew, Arabic, Thai, Vietnamese, Western/Central/Baltic Latin, and DOS ZIP code pages where raw name bytes are available.
- Password-protected archive image viewing uses selected-image-first lazy extraction after password entry. Password-sensitive preview cache reuse validates the current password before trusting cached output.
- ALZ Store/Deflate/BZip2 and EGG Store/Deflate/BZip2/LZMA extraction paths stream to output with CRC verification where the format path allows it.
- Archive failure messages now distinguish password-required, bad-password, unsupported-feature, corrupt-archive, and generic failures, with conservative family-specific support-boundary details.
- Added external RAR and non-RAR archive fixture report tooling for local compatibility QA without broadening public archive compatibility claims.
- Removed forced process-wide `System.gc()` calls from image decode OOM retry; retries increase sample size and yield instead.
- Added lifecycle guards and cleanup for document WebView callbacks, PDF delayed callbacks, TXT TTS callbacks, reader-toolbar delayed work, drawer delayed work, image sequence handoffs, archive password snapshots, and font-scan callbacks.
- Bounded EPUB fixed-layout/font detection reads so large HTML/CSS entries are not fully loaded during detection-only scans.
- Public GitHub/F-Droid documentation, release notes, archive/RAR/license report filenames, and Fastlane changelogs were normalized for the Readwide 1.0.1 release line.

### Archive support boundary

- ZIP/CBZ stays on Zip4j as the primary path, with Apache Commons Compress fallback for non-encrypted unsupported ZIP methods where bundled codecs can read them.
- 7z/CB7, TAR-family archives, and single-compressor streams continue through Apache Commons Compress.
- ALZ and EGG remain limited first-party implementations with documented method coverage and unsupported encrypted/split/solid variants.
- RAR/CBR remains libarchive-primary with scoped first-party Java support for metadata, safe paths, stored entries, selected stored split paths, RAR4 Unicode names, diagnostics, and covered RAR5 stored-entry handling.
- RAR creation is not implemented.
- Split/multi-volume RAR and encrypted RAR are not guaranteed.
- Solid RAR, PPMd, custom VM filters, broad SFX, RAR5 compressed/solid/encrypted-header cases, and unusual RAR variants remain backend-dependent or unsupported unless a specific file is covered by the bundled backend.

## Readwide 1.0.0 - 2026-06-09

### Release scope

- Renamed the public app line from TextView Reader to Readwide.
- Kept Android metadata at `versionCode 10000` and `versionName "1.0.0"`.
- Kept the existing Android `applicationId` / package name so Readwide 1.0.0 remains update-compatible with the TextView 2.2.6 line when signed with the same key.
- Updated the in-app static update link to `https://github.com/k1717/Readwide/releases`.

### Final changes included in this upload

- Updated launcher/app display branding, settings wording, backup wording, exported backup filename prefix, TTS media-session label, and developer-contact documentation to Readwide.
- Changed the developer contact address to `readwide.kj7w5@addy.io`.
- Reworked the main language setting from a long radio-button page into a compact selected-language row with a rounded picker dialog.
- Added selectable major UI languages and initial resource coverage for the expanded language list. Untranslated strings fall back to the default English resources.
- Fixed the recent-file multi-select menu so long English actions such as `Remove from recent list` can wrap instead of being clipped.
- Replaced launcher/adaptive/play-store icon assets with the approved Readwide book artwork and adjusted launcher safe margins to avoid clipped-looking edges.
- Fixed the custom reading theme create/edit screen so the top app bar respects the Android status bar and display cutout, preventing the back button from overlapping system UI.
- Fixed the reading-theme selection mark so the selected row shows a real check mark instead of mojibake text.
- Matched the custom reading theme editor's app-bar/status-inset background to the active main theme bar color instead of leaving a gray strip above the toolbar.
- Switched the public update URL to the standard GitHub releases page: `https://github.com/k1717/Readwide/releases`.
- Cleaned RAR source comments so the public FOSS package describes first-party RAR work as independent implementation based on public format behavior and fixture validation, not as UnRAR source porting.
- Documented launcher icon provenance as project-owned generated artwork and removed the unused optional local RAR5 decoder bridge/readme from the public source package.
- Removed development-session wording from RAR diagnostic strings and comments so detailed archive failures use release-facing wording.
- Removed the default `app/libs/*.jar` dependency hook so the public FOSS/F-Droid-oriented source tree has no local optional jar path in the Gradle dependency graph.
- Made release signing conditional so F-Droid-style source builds can run `assembleRelease` without a private developer keystore and produce an unsigned release artifact.
- Removed the unused Foojay toolchain resolver plugin from `settings.gradle` to keep the build script leaner for reproducible source-build review.
- Added Readwide backup filename patterns to `.gitignore` so exported user backups are not accidentally committed.
- Kept the TextView 2.2.6 privacy/license hardening base: Auto Backup disabled, no default `INTERNET` permission, no analytics, no ads, no account system, and no Junrar/UnRAR-license fallback in the default build.
- Preserved archive preview/image-sequence fixes from the late TextView 2.2.6 line, including direct comic-open ordering, preview-to-viewer ordering, archive folder sort-state restoration, and macOS resource-fork image filtering.

### Archive support boundary

- ZIP/CBZ stays on Zip4j as the primary path, with Apache Commons Compress fallback for non-encrypted unsupported ZIP methods where bundled codecs can read them.
- 7z/CB7, TAR-family archives, and single-compressor streams continue through Apache Commons Compress.
- ALZ and EGG remain limited first-party implementations with documented method coverage and unsupported encrypted/split/solid variants.
- RAR/CBR remains libarchive-primary with scoped first-party Java support for metadata, safe paths, stored entries, selected stored split paths, RAR4 Unicode names, diagnostics, and covered RAR5 stored-entry handling.
- RAR creation is not implemented.
- Split/multi-volume RAR and encrypted RAR were not re-tested for this release package and are not guaranteed.
- Solid RAR, PPMd, custom VM filters, broad SFX, RAR5 compressed/solid/encrypted-header cases, and unusual RAR variants remain backend-dependent or unsupported unless a specific file is covered by the bundled backend.

## TextView Reader 2.2.6 - 2026-06-07

### Release scope

- TextView Reader 2.2.6 is the privacy/license hardening base that Readwide 1.0.0 continues from.
- Readwide 1.0.0 keeps the same application ID for update compatibility with this line.

### Final changes included in this release

- Disabled Android app-data Auto Backup in the manifest.
- Replaced new PIN storage with salted PBKDF2 verifier strings and kept migration for legacy plain-PIN data after successful verification.
- Removed the default `INTERNET` and `REQUEST_INSTALL_PACKAGES` permission paths.
- Replaced in-app update checking with a static, copyable release link in Settings.
- Added developer contact through the user's mail app, with copy fallback if no mail app is available.
- Removed Junrar/UnRAR-license fallback code from the default dependency path.
- Documented the default source/APK as the FOSS-friendly line with Apache-2.0 first-party source and third-party notices.
- Added bundled libarchive-android routing for common compressed RAR3/RAR4 attempts while keeping first-party RAR stored-entry and metadata paths.
- Added scrollable/copyable archive failure detail dialogs so long backend errors are no longer reduced to truncated toasts.
- Refined archive password dialogs with compact buttons and a show/hide password toggle.
- Kept ARM-only release native packaging and excluded unnecessary desktop native payloads from Android packaging.

### Known support boundaries

- Split/multi-volume RAR and encrypted RAR were not guaranteed for the public 2.2.6 package.
- First-party compressed RAR was not complete.
- RAR5 compressed/solid/encrypted-header extraction remained backend-dependent.

## TextView Reader 2.2.5 - 2026-06-02

### Release scope

- Android metadata: `versionCode 2250`, `versionName "2.2.5"`.
- Focused on archive fallback behavior, browse-state responsiveness, file-operation progress, and reducing large activity responsibilities.

### Final changes included in this release

- ZIP extraction uses Zip4j as the primary path and Apache Commons Compress as a fallback for non-encrypted unsupported compression methods such as Deflate64, BZip2, XZ, and ZSTD where available.
- Pending ZIP creation resolves the destination from the folder where the queued action is executed.
- Main file/folder action short-hold opens faster, while multi-select hold remains separate.
- Returning from internal viewers preserves the current main-folder list and scroll state when the folder has not changed.
- Fully loaded folder snapshots can be restored in both directions, including A -> B -> A and A -> B -> A -> B navigation.
- Drawer shortcut and recent-folder navigation restore cached target folders optimistically and validate them in the background.
- Multi-select delete exits selection mode after confirmation so background progress can be reopened from the toolbar.
- Browse-state logic moved into `MainBrowseStateController`.
- Archive list shaping, archive image sequence loading, and archive create/extract planning moved into focused helper classes.

### Known support boundaries

- Encrypted ZIP entries remain on Zip4j.
- AES entries that also use unsupported special ZIP methods remain unsupported.
- ZIP creation remains plain ZIP only.

## TextView Reader 2.2.4 - 2026-06-02

### Release scope

- Android metadata: `versionCode 2240`, `versionName "2.2.4"`.
- Focused on public license packaging, queued archive actions, archive preview safety, and theme editing.

### Final changes included in this release

- First-party project source is Apache License 2.0 and ships with `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`.
- Compress actions add pending ZIP creation tasks instead of running immediately.
- Pending copy, move, extract, and compress tasks are managed from the same pending-actions menu.
- ALZ supports Store/Deflate/BZip2 extraction with CRC verification.
- EGG supports Store/Deflate/BZip2/AZO/LZMA through the first-party parser.
- Standard 7z/CB7 split volumes resolve to the first part and open through a concatenated seekable channel.
- Archive management includes safer preview caching, stricter path sanitization, password preflight, backup/restore overwrite extraction, free-space guards, and cache pruning.
- Custom main-theme and reading-theme color editors include a lightweight shader-based color palette picker with HEX/RGB input.

### Known support boundaries

- The 2.2.4 RAR/CBR line still used a then-bundled Junrar fallback for older RAR extraction. That fallback is removed from the default Readwide 1.0.0 / TextView 2.2.6 FOSS-oriented line.
- RAR creation is not implemented.
- ALZ/EGG encrypted, split, solid, and unusual legacy variants remain limited or unsupported unless explicitly covered by tests.
