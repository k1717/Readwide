# Readwide 1.0.1 GitHub release notes

Readwide 1.0.1 focuses on viewer polish, backup/bookmark portability, archive safety, lifecycle hardening, and public GitHub/F-Droid packaging cleanup.

## Release scope

- Android metadata is `versionCode 10001` and `versionName "1.0.1"`.
- The Android package/application ID remains `com.textview.reader` for update compatibility with earlier compatible builds when signed with the same key.
- Default builds remain local-first: no default `INTERNET` permission, no ads, no analytics, no account system, no app-network update check, Android Auto Backup disabled, and no Junrar/UnRAR-license fallback in the default build.

## Final changes included in this release

- Missing bookmark target files remain visible in the bookmark list with a theme-matched missing-file label. Tapping one opens a themed explanation dialog while preserving the bookmark for later portable rebind.
- Backup import restores last directory, recent folders, and drawer folder shortcuts only when those directories exist on the current device; invalid imported paths are skipped.
- TXT bookmarks keep character position, logical line, surrounding anchor text, and file fingerprint as the authoritative location. Cached Page X/Y metadata is refreshed under the current device layout when the file opens or the bookmark is used.
- Zoomed PDF pages support inertial fling panning in single-page mode; zoomed pages in vertical continuous mode support horizontal fling across the visible page.
- Image viewer landscape safe-area handling is fixed for Android 3-button navigation. The default image fit is adaptive: wide images fit to width and tall images fit to height.
- Image detail/original decode is retained after returning from zoom to adaptive fit, avoiding repeated detail re-decodes for the same image.
- Legacy archive entry filename decoding was expanded for raw ZIP central-directory names and first-party ALZ/EGG name fields.
- Password-protected archive image viewing uses lazy extraction after password entry: selected image first, adjacent images on demand.
- Password-sensitive archive preview cache reuse validates the current password before trusting cached output. Sensitive previews use a separate app-private cache root with stricter pruning.
- ALZ Store/Deflate/BZip2 and EGG Store/Deflate/BZip2/LZMA extraction stream to output with CRC verification where possible.
- Archive error UI separates password-required, bad-password, unsupported-feature, corrupt-archive, and generic failures, while keeping archive support claims conservative.
- RAR and non-RAR fixture-report scripts were added for local QA; they are verification tooling and do not expand public compatibility claims.
- Public GitHub/F-Droid documents were refreshed for Readwide 1.0.1, including current archive/RAR/license report filenames and F-Droid submission notes.

## Archive support boundary

RAR support is still limited. Common RAR read/extract attempts use libarchive-android plus first-party metadata/stored-entry handling, but split/multi-volume RAR, encrypted RAR, broad solid archives, PPMd, VM-filtered, broad SFX, and RAR5 compressed/solid/encrypted-header cases are not guaranteed.
